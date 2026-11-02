// 삼자대면: 미나 · Claude · GPT 그룹채팅 (내 PC에서만 실행)
// Claude는 Claude Code CLI(`claude -p --resume`), GPT는 Codex CLI(`codex exec resume`)로
// 각자 자기 대화를 이어 가므로, 매번 새로 온 메시지만 넘겨도 앞의 대화를 기억한다.
// API 키 없이 Claude 구독과 ChatGPT 구독(로그인)으로 돌아간다.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

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
const MAX_IMG = 8 * 1024 * 1024, MAX_TEXT = 1024 * 1024, MAX_FILES = 12, TEXT_INLINE = 20000;

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

let state = { rooms: [], models: { claude: '', gpt: '' } }; // models ''이면 각 CLI의 기본 모델
try {
  const old = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
  state.models = Object.assign(state.models, old.models);
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
  try { fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 1)); } catch (_) {}
}

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
    '각자 모델은 미나가 화면 위쪽 메뉴에서 바꿀 수 있어. 앞선 다른 대화나 프로젝트 기억은 없으니 모르는 건 모른다고 해.',
    '규칙:',
    '- 한국어로, 단톡방에서 말하듯 짧게(보통 2~6줄) 답해.',
    `- ${other}의 말에 동의하면 동의하고, 생각이 다르면 분명하게 말해. 서로 띄워 주기만 하지 마.`,
    '- 이름표나 "[Claude]" 같은 접두어는 붙이지 마. 네 말만 써.',
    '- 파일을 고치거나 명령을 실행하지 말고 대화만 해.',
    '- 미나가 사진이나 글자 파일을 올리면 직접 보고 그 내용에 대해 말해. 못 열면 못 열었다고 솔직히 말해.',
    '',
  ].join('\n');
}

// 메시지에 붙은 파일을 프롬프트용 글로 바꾼다. 사진은 Claude가 Read로 열고, GPT는 --image로 받는다.
function fileNotes(m, me) {
  const vids = {};
  (m.files || []).forEach((f) => { if (f.video) (vids[f.video] = vids[f.video] || []).push(f); });
  const notes = Object.keys(vids).map((name) => {
    const fr = vids[name];
    const head = `(미나가 영상 "${name}"(길이 약 ${fr[0].dur}초)을 올렸어. 소리는 들을 수 없어서, 같은 간격으로 뽑은 장면 사진 ${fr.length}장을 시간 순서로 보내. ` +
      `장면은 ${fr.map((f) => f.t + '초').join(', ')} 지점이야. 사진으로 보이는 것만 말하고, 소리나 장면 사이는 모른다고 해.`;
    return me === 'claude'
      ? head + ` Read 도구로 ${fr.map((f) => 'uploads/' + f.file).join(' , ')} 를 차례로 열어서 봐.)`
      : head + ' 첨부 이미지로 같이 보냈어.)';
  });
  const rest = (m.files || []).filter((f) => !f.video).map((f) => {
    if (f.kind === 'image') {
      return me === 'claude'
        ? `(미나가 사진 "${f.name}"을 올렸어. Read 도구로 uploads/${f.file} 를 열어서 봐.)`
        : `(미나가 사진 "${f.name}"을 올렸어. 첨부 이미지로 같이 보냈어.)`;
    }
    let body = '';
    try { body = fs.readFileSync(path.join(UPLOAD_DIR, f.file), 'utf8'); } catch (_) {}
    const cut = body.length > TEXT_INLINE ? body.slice(0, TEXT_INLINE) + '\n…(뒤는 길어서 생략)' : body;
    return `(미나가 파일 "${f.name}"을 올렸어. 내용:)\n--- ${f.name} ---\n${cut}\n---`;
  });
  return notes.concat(rest).join('\n');
}

function newsFor(room, me) {
  const p = room[me];
  const fresh = !p.sid;
  // 새 세션이면 지금까지 대화를 전부(자기 말 포함) 넘기고, 이어 가는 세션이면 새 메시지만 넘긴다.
  return room.messages.slice(p.seen).filter((m) => !m.system && (fresh || m.who !== me));
}

function buildPrompt(room, me) {
  const p = room[me];
  const fresh = !p.sid;
  const news = newsFor(room, me);
  const lines = news.map((m) => {
    const notes = fileNotes(m, me);
    return `[${NAMES[m.who]}${m.who === me ? '(너)' : ''}] ${m.text}${notes ? '\n' + notes : ''}`;
  }).join('\n\n');
  return (fresh ? intro(me) : '') +
    '새로 올라온 메시지:\n\n' + (lines || '(새 메시지 없음)') +
    `\n\n이어서 ${NAMES[me]}로서 네 답만 써.`;
}

// ---------- CLI 실행 ----------
function run(cmd, args, input) {
  return new Promise((resolve) => {
    let out = '', err = '';
    let child;
    try {
      // 윈도우에서 npm 전역 명령(.cmd)은 shell로 실행해야 한다. 인자에는 공백을 넣지 않는다.
      child = spawn(cmd, args, { cwd: DIR, shell: IS_WIN, windowsHide: true });
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
  // 사진은 최근 12장까지. 경로는 gpt-chat 폴더 기준 상대 경로(공백 없음).
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
    room[who].seen = upto;
    // seen은 프롬프트를 만든 시점까지만 올린다. 그사이 상대가 쓴 말은 다음 차례에 받는다.
    // 자기 말은 buildPrompt에서 걸러진다.
    addMessage(room, who, r.text);
  } else {
    addMessage(room, who, `${NAMES[who]} 응답 실패:\n${String(r.error).trim().slice(0, 1500)}`, { system: true });
  }
}

// 방마다 따로 바쁘다: 한 방에서 답을 기다리는 동안 다른 방에서 대화할 수 있다.
const busyRooms = new Set();
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
    clients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 20000);
    req.on('close', () => { clearInterval(ping); clients.delete(res); });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/api/rooms') {
    return json(res, 200, roomList());
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
    if (incoming.length > MAX_FILES) return json(res, 400, { error: `파일은 한 번에 ${MAX_FILES}개까지예요.` });
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

  if (req.method === 'POST' && url.pathname === '/api/debate') {
    const { rounds, room: roomId } = await readBody(req);
    const room = getRoom(roomId);
    if (!room) return json(res, 404, { error: '대화방을 찾을 수 없어요.' });
    const n = Math.min(Math.max(Number(rounds) || 2, 1), 4);
    if (busyRooms.has(room.id)) return json(res, 409, { error: 'busy' });
    json(res, 200, { ok: true });
    addMessage(room, 'mina', '둘이 이 얘기로 이야기 나눠 봐. 서로의 말에 반응해 줘.');
    return withBusy(room, async () => {
      for (let i = 0; i < n; i++) { await turn(room, 'claude'); await turn(room, 'gpt'); }
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
});
