// 삼자대면: 미나 · Claude · GPT 그룹채팅 (내 PC에서만 실행)
// Claude는 Claude Code CLI(`claude -p --resume`), GPT는 Codex CLI(`codex exec resume`)로
// 각자 자기 대화를 이어 가므로, 매번 새로 온 메시지만 넘겨도 앞의 대화를 기억한다.
// API 키 없이 Claude 구독과 ChatGPT 구독(로그인)으로 돌아간다.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { freshProject, normalizeProject, parseProposal, applyUpdate, projectContext } = require('./project-state');
const { parsePrLink, fetchPrStatus } = require('./github-status');

const PORT = Number(process.env.PORT) || 3939;
const DIR = __dirname;
const STATE_FILE = path.join(DIR, 'chat-state.json');
const IS_WIN = process.platform === 'win32';
const CLAUDE_CMD = process.env.CLAUDE_CMD || 'claude';
const CODEX_CMD = process.env.CODEX_CMD || 'codex';
const TIMEOUT_MS = 5 * 60 * 1000;

const UPLOAD_DIR = path.join(DIR, 'uploads');
try { fs.mkdirSync(UPLOAD_DIR, { recursive: true }); } catch (_) {}
const IMG_EXT = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };
const TEXT_EXT = ['txt', 'md', 'csv', 'json', 'js', 'ts', 'py', 'html', 'css', 'log', 'xml', 'yml', 'yaml', 'kt', 'java'];
const MAX_IMG = 8 * 1024 * 1024, MAX_TEXT = 1024 * 1024, MAX_FILES = 40, TEXT_INLINE = 20000;

const NAMES = { mina: '미나', claude: 'Claude', gpt: 'GPT' };

const NEW_TITLE = '새 대화';

// 대화방(스레드): 방마다 메시지와 Claude·GPT 세션이 따로다.
function freshRoom() {
  return {
    id: 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5),
    title: NEW_TITLE,
    t: Date.now(),
    messages: [],
    claude: { sid: null, seen: 0 },
    gpt: { sid: null, seen: 0 },
  };
}

let state = { rooms: [], models: { claude: '', gpt: '' }, project: freshProject() }; // 모델 ''이면 CLI 기본 모델
try {
  const old = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
  state.models = Object.assign(state.models, old.models);
  state.project = normalizeProject(old.project);
  if (Array.isArray(old.rooms)) state.rooms = old.rooms;
  else if (Array.isArray(old.messages) && old.messages.length) {
    // 방이 없던 예전 저장 형식: 지금까지 대화를 방 하나로 옮긴다.
    const r = freshRoom();
    Object.assign(r, { messages: old.messages, claude: old.claude || r.claude, gpt: old.gpt || r.gpt, title: '이전 대화' });
    state.rooms = [r];
  }
} catch (_) {}
if (!state.rooms.length) state.rooms.push(freshRoom());

const getRoom = (id) => state.rooms.find((r) => r.id === id);
const roomInfo = (r) => ({ id: r.id, title: r.title, t: r.t, count: r.messages.length, busy: busyRooms.has(r.id) });
const roomList = () => state.rooms.map(roomInfo).sort((a, b) => b.t - a.t);

function save() {
  const temp = STATE_FILE + '.tmp';
  try {
    fs.writeFileSync(temp, JSON.stringify(state, null, 1));
    fs.renameSync(temp, STATE_FILE);
    return true;
  } catch (e) {
    console.error('대화·프로젝트 저장 실패:', e.message);
    return false;
  }
}

let checkingGithub = false;
async function syncGithub() {
  if (checkingGithub || !state.project.currentTask) return;
  const evidence = state.project.currentTask.evidence;
  if (!evidence) return;
  checkingGithub = true;
  try {
    const result = await fetchPrStatus(evidence);
    // 기다리는 동안 다른 작업이 등록됐으면 오래된 조회 결과는 버린다.
    if (result && state.project.currentTask && state.project.currentTask.evidence === evidence) {
      state.project.github = result;
      save();
      broadcast('project', state.project);
    }
  } catch (_) {
    // 비공개 저장소·오프라인·API 호출 제한 시 '확인 불가'를 명시한다.
    const target = parsePrLink(evidence);
    if (target && state.project.currentTask && state.project.currentTask.evidence === evidence) {
      state.project.github = { url: target.url, status: 'unavailable', checks: 'unknown', checkedAt: Date.now() };
      save();
      broadcast('project', state.project);
    }
  } finally { checkingGithub = false; }
}
const githubTimer = setInterval(syncGithub, 10 * 60 * 1000);
githubTimer.unref();

// ---------- 실시간 화면 갱신 (SSE) ----------
const clients = new Set();
function broadcast(type, data) {
  const line = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) res.write(line);
}
const typingMap = {}; // 방 id → { claude, gpt }
const typingOf = (room) => typingMap[room.id] || { claude: false, gpt: false };
function setTyping(room, who, on) {
  typingMap[room.id] = Object.assign(typingOf(room), { [who]: on });
  broadcast('typing', { room: room.id, typing: typingMap[room.id] });
}

function addMessage(room, who, text, extra) {
  const msg = Object.assign({ who, text, t: Date.now() }, extra || {});
  room.messages.push(msg);
  if (!msg.system) room.t = msg.t;
  // 방 제목은 미나의 첫 말로 자동으로 정한다.
  if (who === 'mina' && room.title === NEW_TITLE) {
    const t = (text || (extra && extra.files && extra.files[0].name) || '').replace(/\s+/g, ' ').trim();
    if (t) room.title = t.length > 24 ? t.slice(0, 24) + '…' : t;
  }
  save();
  broadcast('message', { room: room.id, msg });
  broadcast('rooms', roomList());
  return msg;
}

// ---------- 프롬프트 ----------
function intro(me) {
  const other = me === 'claude' ? 'GPT' : 'Claude';
  return [
    `너는 ${NAMES[me]}야. 지금 미나, ${other}와 셋이 그룹채팅 중이야.`,
    '이 방은 미나가 자기 PC에서 돌리는 "삼자대면" 앱이야. Claude는 Claude Code CLI, GPT는 Codex CLI로 연결돼 있고,',
    '각자 모델은 미나가 화면 위쪽 메뉴에서 바꿀 수 있어. 다른 방의 프로젝트 확정 사항은 아래 공동 진행판을 참고해.',
    '규칙:',
    '- 한국어로, 단톡방에서 말하듯 짧게(보통 2~6줄) 답해.',
    `- ${other}의 말에 동의하면 동의하고, 생각이 다르면 분명하게 말해. 서로 띄워 주기만 하지 마.`,
    '- 이름표나 "[Claude]" 같은 접두어는 붙이지 마. 네 말만 써.',
    '- 이 채팅은 읽기 전용이므로 실행하지 않은 코딩·PR·테스트를 완료했다고 주장하지 마.',
    me === 'claude' ? '- 너는 주 실행 담당자야. 구현 방안과 구체적인 다음 작업·완료 조건을 제시해. 코드 수정은 별도 실행 환경에서 명시적 요청이 있을 때만 해.' : '- 너는 독립 검토자야. Claude 안의 빈틈·위험·사용성을 검토하고 개선안을 하나로 좁혀. 실행 담당 역할을 빼앗지 마.',
    '- 같은 주장 반복은 피하고, 한 번의 회의에 실행 가능한 다음 행동 하나만 제안해.',
    '- 앱 화면이나 시안을 보여 달라고 하면, 폰 크기(가로 360px)로 보이는 완결된 HTML 한 덩어리를 ```html 코드 블록으로 써. 스크립트와 바깥 주소는 쓰지 마. 대화창이 그 코드를 그림처럼 보여 줘.',
    '- 미나가 사진이나 글자 파일을 올리면 직접 보고 그 내용에 대해 말해. 못 열면 못 열었다고 솔직히 말해.',
    '',
  ].join('\n');
}

// 메시지에 붙은 파일을 프롬프트용 글로 바꾼다. 사진은 Claude가 Read로 열고, GPT는 --image로 받는다.
function fileNotes(m, me) {
  const w = NAMES[m.who] || '미나';
  const vids = {};
  (m.files || []).forEach((f) => { if (f.video) (vids[f.video] = vids[f.video] || []).push(f); });
  const notes = Object.keys(vids).map((name) => {
    const fr = vids[name];
    const head = `(${w}가 영상 "${name}"(길이 약 ${fr[0].dur}초)을 올렸어. 소리는 들을 수 없어서, 영상에서 뽑은 장면 사진 ${fr.length}장을 시간 순서로 보내. ` +
      `장면은 ${fr.map((f) => f.t + '초').join(', ')} 지점이야(간격이 다르면 화면이 바뀐 순간 위주로 뽑은 거야). 사진으로 보이는 것만 말하고, 장면 사이나 소리는 모른다고 해.`;
    return me === 'claude'
      ? head + ` Read 도구로 ${fr.map((f) => 'uploads/' + f.file).join(' , ')} 를 차례로 열어서 봐.)`
      : head + ' 첨부 이미지로 같이 보냈어.)';
  });
  const rest = (m.files || []).filter((f) => !f.video).map((f) => {
    if (f.kind === 'image') {
      return me === 'claude'
        ? `(${w}가 사진 "${f.name}"을 올렸어. Read 도구로 uploads/${f.file} 를 열어서 봐.)`
        : `(${w}가 사진 "${f.name}"을 올렸어. 첨부 이미지로 같이 보냈어.)`;
    }
    let body = '';
    try { body = fs.readFileSync(path.join(UPLOAD_DIR, f.file), 'utf8'); } catch (_) {}
    const cut = body.length > TEXT_INLINE ? body.slice(0, TEXT_INLINE) + '\n…(뒤는 길어서 생략)' : body;
    return `(${w}가 파일 "${f.name}"을 올렸어. 내용:)\n--- ${f.name} ---\n${cut}\n---`;
  });
  return notes.concat(rest).join('\n');
}

function newsFor(room, me) {
  const p = room[me];
  const fresh = !p.sid;
  // 새 세션이면 지금까지 대화를 전부(자기 말 포함) 넘기고, 이어 가는 세션이면 새 메시지만 넘긴다.
  return room.messages.slice(p.seen).filter((m) => !m.system && (fresh || m.who !== me));
}

// 규칙이 바뀐 뒤에도 예전 방의 Claude·GPT가 알 수 있게, 이어 가는 대화에 한 번만 덧붙인다.
const RULES_VER = 3;
function rulesUpdate() {
  return [
    '(알림: 삼자대면이 토론 중심에서 실행 관리 중심으로 바뀌었어.',
    '- 아래 공동 진행판은 모든 방이 공유하고, 확정된 결정·실행 담당·완료 조건을 담아. 미나 승인 없이 회의 초안을 확정 사실처럼 말하지 마.',
    '- Claude는 실행 방안을, GPT는 독립 검토를 맡아. 같은 얘기 반복 대신 다음 행동 하나로 좁혀.',
    '- 이 채팅 자체는 읽기 전용이야. 실제 코드 수정·PR·빌드·실기기 테스트 여부를 구분해.',
    '- 미나가 사진, 글자 파일, 영상에서 뽑은 장면 사진을 올리면 직접 보고 그 내용에 대해 말해.',
    '- 앱 화면이나 시안을 보여 달라고 하면, 폰 크기(가로 360px)로 보이는 완결된 HTML 한 덩어리를 ```html 코드 블록으로 써. 스크립트와 바깥 주소는 쓰지 마. 대화창이 그 코드를 그림처럼 보여 줘.',
    '- GPT는 미나가 🎨 버튼을 누르면 그림을 그릴 수 있고, 그림은 대화창에 올라와.)',
    '', '',
  ].join('\n');
}

function buildPrompt(room, me) {
  const p = room[me];
  const fresh = !p.sid;
  const news = newsFor(room, me);
  const lines = news.map((m) => {
    const notes = fileNotes(m, me);
    return `[${NAMES[m.who]}${m.who === me ? '(너)' : ''}] ${m.text}${notes ? '\n' + notes : ''}`;
  }).join('\n\n');
  return (fresh ? intro(me) : (p.rules || 0) < RULES_VER ? rulesUpdate() : '') +
    projectContext(state.project) + '\n\n' +
    '새로 올라온 메시지:\n\n' + (lines || '(새 메시지 없음)') +
    `\n\n이어서 ${NAMES[me]}로서 네 답만 써.`;
}

// ---------- CLI 실행 ----------
function run(cmd, args, input, cwd) {
  return new Promise((resolve) => {
    let out = '', err = '';
    let child;
    try {
      // 윈도우에서 npm 전역 명령(.cmd)은 shell로 실행해야 한다. 인자에는 공백을 넣지 않는다.
      child = spawn(cmd, args, { cwd: cwd || DIR, shell: IS_WIN, windowsHide: true });
    } catch (e) {
      return resolve({ code: -1, out, err: String(e) });
    }
    const timer = setTimeout(() => { err += '\n(5분 넘게 응답이 없어 멈췄어요)'; child.kill(); }, TIMEOUT_MS);
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    child.on('error', (e) => (err += String(e)));
    child.on('close', (code) => { clearTimeout(timer); resolve({ code, out, err }); });
    child.stdin.on('error', () => {});
    child.stdin.end(input, 'utf8');
  });
}

async function askClaude(room, prompt) {
  const sid = room.claude.sid;
  const args = ['-p', '--output-format', 'json'];
  if (sid) args.push('--resume', sid);
  args.push('--allowedTools', 'Read'); // 올라온 사진을 열어 볼 수 있게 (읽기만)
  if (state.models.claude) args.push('--model', state.models.claude);
  const r = await run(CLAUDE_CMD, args, prompt);
  let j = null;
  try { j = JSON.parse(r.out.trim().split('\n').pop()); } catch (_) {}
  if (j && j.session_id && !j.is_error && typeof j.result === 'string') {
    return { ok: true, text: j.result.trim(), sid: j.session_id };
  }
  return { ok: false, error: (j && j.result) || r.err || r.out || `종료 코드 ${r.code}` };
}

async function askGpt(room, prompt) {
  const sid = room.gpt.sid;
  const lastFile = 'gpt-last.txt';
  try { fs.unlinkSync(path.join(DIR, lastFile)); } catch (_) {}
  const args = sid
    ? ['exec', 'resume', '--skip-git-repo-check', '-c', 'sandbox_mode=read-only', '--json', '-o', lastFile, sid, '-']
    : ['exec', '--skip-git-repo-check', '-s', 'read-only', '--json', '-o', lastFile, '-'];
  const tail = sid ? 2 : 1; // 끝의 [세션id] '-' 앞에 옵션을 끼워 넣는다
  const extra = [];
  if (state.models.gpt) extra.push('-m', state.models.gpt);
  const imgs = [];
  for (const m of newsFor(room, 'gpt')) for (const f of m.files || []) if (f.kind === 'image') imgs.push(f.file);
  // 사진은 최근 40장까지. 경로는 gpt-chat 폴더 기준 상대 경로(공백 없음).
  for (const f of imgs.slice(-MAX_FILES)) extra.push(`--image=uploads/${f}`);
  args.splice(args.length - tail, 0, ...extra);
  const r = await run(CODEX_CMD, args, prompt);
  let threadId = sid;
  let lastText = '';
  let lastError = '';
  for (const line of r.out.split('\n')) {
    let e;
    try { e = JSON.parse(line); } catch (_) { continue; }
    if (e.type === 'thread.started' && e.thread_id) threadId = e.thread_id;
    if (e.type === 'item.completed' && e.item && e.item.type === 'agent_message') lastText = e.item.text || lastText;
    if (e.type === 'error' || e.type === 'turn.failed') lastError = e.message || (e.error && e.error.message) || lastError;
  }
  let text = '';
  try { text = fs.readFileSync(path.join(DIR, lastFile), 'utf8').trim(); } catch (_) {}
  text = text || lastText.trim();
  if (r.code === 0 && text && threadId) return { ok: true, text, sid: threadId };
  return { ok: false, error: lastError || r.err.slice(-1500) || `종료 코드 ${r.code}` };
}

// GPT에게 그림을 그리게 한다. 한 번짜리 작업이라 대화 기억과 따로 돌고, 이 요청 전용 폴더 안에만 쓸 수 있다.
async function drawImage(room, desc) {
  const dir = path.join(DIR, 'generated', String(Date.now()));
  fs.mkdirSync(dir, { recursive: true });
  const prompt = `Generate an image using your image generation tool: ${desc}\n` +
    'Save the resulting image file in the current directory as image.png. Then reply in Korean with one short sentence describing what you drew.';
  const args = ['exec', '--skip-git-repo-check', '-s', 'workspace-write', '--json', '-o', 'draw-last.txt'];
  if (state.models.gpt) args.push('-m', state.models.gpt);
  args.push('-');
  setTyping(room, 'gpt', true);
  const r = await run(CODEX_CMD, args, prompt, dir);
  setTyping(room, 'gpt', false);
  let lastError = '';
  for (const line of r.out.split('\n')) {
    let e;
    try { e = JSON.parse(line); } catch (_) { continue; }
    if (e.type === 'error' || e.type === 'turn.failed') lastError = e.message || (e.error && e.error.message) || lastError;
  }
  const pics = fs.readdirSync(dir)
    .filter((n) => IMG_EXT[n.split('.').pop().toLowerCase()])
    .map((n) => ({ n, size: fs.statSync(path.join(dir, n)).size }))
    .sort((a, b) => b.size - a.size);
  if (!pics.length) {
    const why = lastError || r.err.slice(-1200) || `그림 파일이 만들어지지 않았어요 (종료 코드 ${r.code}).`;
    return addMessage(room, 'gpt', `GPT 그림 실패:\n${String(why).trim().slice(0, 1500)}`, { system: true });
  }
  const ext = pics[0].n.split('.').pop().toLowerCase();
  const file = `${Date.now()}-gpt-draw.${ext}`;
  fs.copyFileSync(path.join(dir, pics[0].n), path.join(UPLOAD_DIR, file));
  let caption = '';
  try { caption = fs.readFileSync(path.join(dir, 'draw-last.txt'), 'utf8').trim(); } catch (_) {}
  addMessage(room, 'gpt', caption || '그렸어요.', { files: [{ name: `GPT 그림.${ext}`, file, kind: 'image' }] });
}

// 한 명에게 차례를 준다. 세션이 깨졌으면 지금까지 대화를 처음부터 넘겨 새로 시작한다.
async function turn(room, who) {
  const ask = who === 'claude' ? askClaude : askGpt;
  const upto = room.messages.length;
  setTyping(room, who, true);
  let r = await ask(room, buildPrompt(room, who));
  if (!r.ok && room[who].sid) {
    room[who] = { sid: null, seen: 0 };
    r = await ask(room, buildPrompt(room, who));
  }
  setTyping(room, who, false);
  if (r.ok) {
    room[who].sid = r.sid;
    room[who].rules = RULES_VER;
    room[who].seen = upto;
    // seen은 프롬프트를 만든 시점까지만 올린다. 그사이 상대가 쓴 말은 다음 차례에 받는다.
    // 자기 말은 buildPrompt에서 걸러진다.
    addMessage(room, who, r.text);
  } else {
    addMessage(room, who, `${NAMES[who]} 응답 실패:\n${String(r.error).trim().slice(0, 1500)}`, { system: true });
  }
  return r.ok;
}

// 한 라운드가 끝나면 AI들의 의견을 한 장의 실행 초안으로 만든다.
// 초안은 사용자가 '채택'을 누르기 전까지 확정된 프로젝트 정보에 반영되지 않는다.
async function summarizeMeeting(room) {
  const recent = room.messages.filter((m) => !m.system).slice(-12)
    .map((m) => `[${NAMES[m.who]}] ${String(m.text || '').slice(0, 1800)}`).join('\n\n');
  const prompt = [
    '너는 삼자대면의 중립 회의 기록 담당자야. 아래 기록만 이용하고 새로운 사실을 만들지 마.',
    '회의 결론이 없다면 결정은 빈 문자열로, 실행 과제가 불분명하면 nextTask는 null로 둬.',
    '서로 반복하거나 동의한 말은 빼고, 미나가 지금 해야 할 일이 최소가 되도록 다음 작업 하나만 골라.',
    '실제 구현·PR·빌드·폰 테스트가 수행됐다는 증거가 없으면 완료라고 쓰지 마.',
    '오직 JSON 객체 하나만 출력해. 마크다운 금지. 다음 스키마를 정확히 사용해:',
    '{"summary":"회의 결과 한두 문장","decision":"미나의 승인이 필요한 제안 한 문장 또는 빈 문자열","nextTask":{"title":"하나의 실행 작업","owner":"claude","doneWhen":"관찰 가능한 완료 기준","status":"todo","evidence":""},"risk":"가장 중요한 위험 하나"}',
    '현재 확정 상태:', projectContext(state.project),
    '이번 논의:', recent,
  ].join('\n\n');
  const outfile = `summary-${room.id}-${Date.now()}.txt`;
  const args = ['exec', '--skip-git-repo-check', '-s', 'read-only', '-o', outfile, '-'];
  if (state.models.gpt) args.splice(1, 0, '-m', state.models.gpt);
  const result = await run(CODEX_CMD, args, prompt);
  let output = '';
  try { output = fs.readFileSync(path.join(DIR, outfile), 'utf8'); } catch (_) {}
  try { fs.unlinkSync(path.join(DIR, outfile)); } catch (_) {}
  const proposal = result.code === 0 ? parseProposal(output, room.id) : null;
  if (!proposal) {
    addMessage(room, 'gpt', '회의 요약을 자동으로 만들지 못했어. 원래 대화는 그대로 보존됐어.', { system: true });
    return;
  }
  state.project.pending = proposal;
  save();
  broadcast('project', state.project);
  addMessage(room, 'mina', '회의 요약 초안이 준비됐어. 위의 공동 진행판에서 확인하고 채택하거나 버릴 수 있어.', { system: true, note: true });
}

// 방마다 따로 바쁘다: 한 방에서 답을 기다리는 동안 다른 방에서 대화할 수 있다.
const busyRooms = new Set();
let meetingBusy = false; // 대화방이 달라도 회의 초안은 전역 한 장이므로 겹치지 않게 한다.
async function withBusy(room, fn) {
  if (busyRooms.has(room.id)) return false;
  busyRooms.add(room.id);
  broadcast('rooms', roomList());
  try { await fn(); } finally { busyRooms.delete(room.id); broadcast('rooms', roomList()); }
  return true;
}

// ---------- 모델 목록 ----------
const CLAUDE_MODELS = [
  { id: 'opus', name: 'Opus (가장 똑똑함)' },
  { id: 'sonnet', name: 'Sonnet (균형)' },
  { id: 'haiku', name: 'Haiku (빠름)' },
];
let gptModels = null;
async function listGptModels() {
  if (gptModels) return gptModels;
  const r = await run(CODEX_CMD, ['debug', 'models'], '');
  try {
    const j = JSON.parse(r.out);
    gptModels = (j.models || [])
      .filter((m) => m.visibility === 'list')
      .map((m) => ({ id: m.slug, name: m.display_name || m.slug }));
  } catch (_) {
    return [];
  }
  return gptModels;
}

// 이름에는 영문·숫자·.-_ 만 허용 (명령줄에 그대로 들어가므로)
const SAFE_MODEL = /^[A-Za-z0-9._-]{0,64}$/;

// ---------- HTTP ----------
function readBody(req) {
  return new Promise((resolve) => {
    let b = '';
    req.on('data', (d) => { b += d; if (b.length > 40 * 1024 * 1024) { b = ''; req.destroy(); } });
    req.on('end', () => { try { resolve(JSON.parse(b || '{}')); } catch (_) { resolve({}); } });
  });
}

function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return fs.createReadStream(path.join(DIR, 'index.html')).pipe(res);
  }

  if (req.method === 'GET' && url.pathname.startsWith('/uploads/')) {
    const name = path.basename(decodeURIComponent(url.pathname));
    const ext = name.split('.').pop().toLowerCase();
    const fp = path.join(UPLOAD_DIR, name);
    if (!IMG_EXT[ext] || !fs.existsSync(fp)) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': IMG_EXT[ext] });
    return fs.createReadStream(fp).pipe(res);
  }

  if (req.method === 'GET' && url.pathname === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write(`event: rooms\ndata: ${JSON.stringify(roomList())}\n\n`);
    res.write(`event: project\ndata: ${JSON.stringify(state.project)}\n\n`);
    clients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 20000);
    req.on('close', () => { clearInterval(ping); clients.delete(res); });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/api/rooms') {
    return json(res, 200, roomList());
  }

  if (req.method === 'GET' && url.pathname === '/api/project') {
    return json(res, 200, state.project);
  }

  if (req.method === 'POST' && url.pathname === '/api/project') {
    const body = await readBody(req);
    const result = applyUpdate(state.project, body.action, body);
    if (result.error) return json(res, 409, { error: result.error });
    const previous = state.project;
    state.project = result.project;
    if (!save()) {
      state.project = previous;
      return json(res, 500, { error: '진행사항 파일을 저장하지 못했어. 저장 공간·권한을 확인해 줘.' });
    }
    broadcast('project', state.project);
    if (body.action === 'approve' || body.action === 'task') void syncGithub();
    return json(res, 200, { ok: true, project: state.project });
  }

  if (req.method === 'GET' && url.pathname === '/api/room') {
    const room = getRoom(url.searchParams.get('id'));
    if (!room) return json(res, 404, { error: 'no room' });
    return json(res, 200, { messages: room.messages, typing: typingOf(room), busy: busyRooms.has(room.id), title: room.title });
  }

  if (req.method === 'POST' && url.pathname === '/api/rooms/new') {
    const r = freshRoom();
    state.rooms.push(r);
    save();
    broadcast('rooms', roomList());
    return json(res, 200, { id: r.id });
  }

  if (req.method === 'POST' && url.pathname === '/api/rooms/rename') {
    const { id, title } = await readBody(req);
    const room = getRoom(id);
    const t = String(title || '').trim().slice(0, 40);
    if (!room || !t) return json(res, 400, { error: 'bad' });
    room.title = t;
    save();
    broadcast('rooms', roomList());
    return json(res, 200, { ok: true });
  }

  if (req.method === 'POST' && url.pathname === '/api/rooms/delete') {
    const { id } = await readBody(req);
    const room = getRoom(id);
    if (!room) return json(res, 404, { error: 'no room' });
    if (busyRooms.has(id)) return json(res, 409, { error: 'busy' });
    state.rooms = state.rooms.filter((r) => r.id !== id);
    if (!state.rooms.length) state.rooms.push(freshRoom());
    save();
    broadcast('rooms', roomList());
    return json(res, 200, { ok: true });
  }

  if (req.method === 'POST' && url.pathname === '/api/send') {
    const { text, to, files, room: roomId } = await readBody(req);
    const room = getRoom(roomId);
    if (!room) return json(res, 404, { error: '대화방을 찾을 수 없어요.' });
    const clean = String(text || '').trim();
    const incoming = Array.isArray(files) ? files : [];
    if (!clean && !incoming.length) return json(res, 400, { error: 'empty' });
    if (busyRooms.has(room.id)) return json(res, 409, { error: 'busy' });
    if (incoming.length > MAX_FILES) return json(res, 400, { error: `사진은 한 번에 ${MAX_FILES}장까지예요.` });
    const saved = [];
    for (const f of incoming) {
      const name = String((f && f.name) || 'file');
      const ext = name.split('.').pop().toLowerCase();
      const kind = IMG_EXT[ext] ? 'image' : TEXT_EXT.includes(ext) ? 'text' : null;
      if (!kind) return json(res, 400, { error: `"${name}"은 열 수 없는 파일이에요. 사진(png, jpg, gif, webp)이나 글자 파일(txt, md, csv, json 등)만 돼요.` });
      const buf = Buffer.from(String(f.data || ''), 'base64');
      if (buf.length > (kind === 'image' ? MAX_IMG : MAX_TEXT)) return json(res, 400, { error: `"${name}"이 너무 커요. 사진은 8MB, 글자 파일은 1MB까지예요.` });
      // 저장 이름은 영문·숫자만(명령줄에 들어가므로). 보여 줄 이름은 원래 이름.
      const safe = name.replace(/\.[^.]*$/, '').replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 30) || 'file';
      const file = `${Date.now()}-${saved.length}-${safe}.${ext}`;
      fs.writeFileSync(path.join(UPLOAD_DIR, file), buf);
      const entry = { name, file, kind };
      // 영상에서 뽑은 장면 사진이면 어느 영상의 몇 초 장면인지 함께 적어 둔다.
      if (kind === 'image' && typeof f.video === 'string' && f.video) {
        entry.video = f.video.slice(0, 120);
        entry.t = Math.max(0, Number(f.t) || 0);
        entry.dur = Math.max(0, Number(f.dur) || 0);
      }
      saved.push(entry);
    }
    json(res, 200, { ok: true });
    addMessage(room, 'mina', clean, saved.length ? { files: saved } : undefined);
    const who = to === 'claude' ? ['claude'] : to === 'gpt' ? ['gpt'] : ['claude', 'gpt'];
    // 둘 다면 동시에 답한다(단톡방처럼). 서로의 답은 다음 차례에 보게 된다.
    return withBusy(room, () => Promise.all(who.map((w) => turn(room, w))));
  }

  if (req.method === 'POST' && url.pathname === '/api/draw') {
    const { text, room: roomId } = await readBody(req);
    const room = getRoom(roomId);
    const desc = String(text || '').trim();
    if (!room) return json(res, 404, { error: '대화방을 찾을 수 없어요.' });
    if (!desc) return json(res, 400, { error: '무엇을 그릴지 입력창에 적어 주세요.' });
    if (busyRooms.has(room.id)) return json(res, 409, { error: 'busy' });
    json(res, 200, { ok: true });
    addMessage(room, 'mina', `🎨 ${desc}`);
    return withBusy(room, () => drawImage(room, desc));
  }

  if (req.method === 'POST' && url.pathname === '/api/debate') {
    const { room: roomId } = await readBody(req);
    const room = getRoom(roomId);
    if (!room) return json(res, 404, { error: '대화방을 찾을 수 없어요.' });
    if (busyRooms.has(room.id)) return json(res, 409, { error: 'busy' });
    if (meetingBusy) return json(res, 409, { error: '다른 방에서 회의 결론을 정리 중이야. 잠시 뒤 다시 해 줘.' });
    if (state.project.pending) return json(res, 409, { error: '아직 채택하거나 버리지 않은 회의 초안이 있어.' });
    meetingBusy = true;
    json(res, 200, { ok: true });
    addMessage(room, 'mina', '이번 논의를 반복하지 말고 서로 다른 관점에서 한 번씩 검토한 뒤, 실행할 다음 작업 하나와 완료 조건을 정리해 줘.');
    return withBusy(room, async () => {
      try {
        const claudeOk = await turn(room, 'claude');
        if (!claudeOk) return;
        const gptOk = await turn(room, 'gpt');
        if (!gptOk) return;
        await summarizeMeeting(room);
      } finally { meetingBusy = false; }
    });
  }

  if (req.method === 'GET' && url.pathname === '/api/models') {
    return json(res, 200, { claude: CLAUDE_MODELS, gpt: await listGptModels(), current: state.models });
  }

  if (req.method === 'POST' && url.pathname === '/api/models') {
    const body = await readBody(req);
    const noteRoom = getRoom(body.room);
    for (const who of ['claude', 'gpt']) {
      if (typeof body[who] !== 'string' || !SAFE_MODEL.test(body[who])) continue;
      if (state.models[who] === body[who]) continue;
      state.models[who] = body[who];
      if (noteRoom) addMessage(noteRoom, who, `${NAMES[who]} 모델을 ${body[who] || '기본'}(으)로 바꿨어요. 대화 기억은 그대로예요.`, { system: true, note: true });
    }
    save();
    broadcast('models', state.models);
    return json(res, 200, { ok: true, current: state.models });
  }

  res.writeHead(404);
  res.end();
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`삼자대면 준비 완료: http://localhost:${PORT}`);
  console.log('이 창을 닫으면 대화방도 꺼져요.');
  void syncGithub();
});
