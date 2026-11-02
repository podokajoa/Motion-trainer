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

const NAMES = { mina: '미나', claude: 'Claude', gpt: 'GPT' };

function freshState() {
  return {
    messages: [],
    claude: { sid: null, seen: 0 },
    gpt: { sid: null, seen: 0 },
    models: { claude: '', gpt: '' }, // ''이면 각 CLI의 기본 모델
  };
}

let state = freshState();
try {
  state = Object.assign(freshState(), JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')));
  state.models = Object.assign({ claude: '', gpt: '' }, state.models);
} catch (_) {}

function save() {
  try { fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 1)); } catch (_) {}
}

// ---------- 실시간 화면 갱신 (SSE) ----------
const clients = new Set();
function broadcast(type, data) {
  const line = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) res.write(line);
}
const typing = { claude: false, gpt: false };
function setTyping(who, on) {
  typing[who] = on;
  broadcast('typing', typing);
}

function addMessage(who, text, extra) {
  const msg = Object.assign({ who, text, t: Date.now() }, extra || {});
  state.messages.push(msg);
  save();
  broadcast('message', msg);
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
    '',
  ].join('\n');
}

function buildPrompt(me) {
  const p = state[me];
  const fresh = !p.sid;
  // 새 세션이면 지금까지 대화를 전부(자기 말 포함) 넘기고, 이어 가는 세션이면 새 메시지만 넘긴다.
  const news = state.messages.slice(p.seen).filter((m) => !m.system && (fresh || m.who !== me));
  const lines = news.map((m) => `[${NAMES[m.who]}${m.who === me ? '(너)' : ''}] ${m.text}`).join('\n\n');
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

async function askClaude(prompt) {
  const sid = state.claude.sid;
  const args = ['-p', '--output-format', 'json'];
  if (sid) args.push('--resume', sid);
  if (state.models.claude) args.push('--model', state.models.claude);
  const r = await run(CLAUDE_CMD, args, prompt);
  let j = null;
  try { j = JSON.parse(r.out.trim().split('\n').pop()); } catch (_) {}
  if (j && j.session_id && !j.is_error && typeof j.result === 'string') {
    return { ok: true, text: j.result.trim(), sid: j.session_id };
  }
  return { ok: false, error: (j && j.result) || r.err || r.out || `종료 코드 ${r.code}` };
}

async function askGpt(prompt) {
  const sid = state.gpt.sid;
  const lastFile = 'gpt-last.txt';
  try { fs.unlinkSync(path.join(DIR, lastFile)); } catch (_) {}
  const args = sid
    ? ['exec', 'resume', '--skip-git-repo-check', '-c', 'sandbox_mode=read-only', '--json', '-o', lastFile, sid, '-']
    : ['exec', '--skip-git-repo-check', '-s', 'read-only', '--json', '-o', lastFile, '-'];
  if (state.models.gpt) args.splice(args.length - (sid ? 2 : 1), 0, '-m', state.models.gpt);
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
async function turn(who) {
  const ask = who === 'claude' ? askClaude : askGpt;
  const upto = state.messages.length;
  setTyping(who, true);
  let r = await ask(buildPrompt(who));
  if (!r.ok && state[who].sid) {
    state[who] = { sid: null, seen: 0 };
    r = await ask(buildPrompt(who));
  }
  setTyping(who, false);
  if (r.ok) {
    state[who].sid = r.sid;
    state[who].seen = upto;
    // seen은 프롬프트를 만든 시점까지만 올린다. 그사이 상대가 쓴 말은 다음 차례에 받는다.
    // 자기 말은 buildPrompt에서 걸러진다.
    addMessage(who, r.text);
  } else {
    addMessage(who, `${NAMES[who]} 응답 실패:\n${String(r.error).trim().slice(0, 1500)}`, { system: true });
  }
}

let busy = false;
async function withBusy(fn) {
  if (busy) return false;
  busy = true;
  broadcast('busy', true);
  try { await fn(); } finally { busy = false; broadcast('busy', false); }
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
    req.on('data', (d) => (b += d));
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

  if (req.method === 'GET' && url.pathname === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write(`event: init\ndata: ${JSON.stringify({ messages: state.messages, typing, busy })}\n\n`);
    clients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 20000);
    req.on('close', () => { clearInterval(ping); clients.delete(res); });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/api/send') {
    const { text, to } = await readBody(req);
    if (!text || !String(text).trim()) return json(res, 400, { error: 'empty' });
    if (busy) return json(res, 409, { error: 'busy' });
    json(res, 200, { ok: true });
    addMessage('mina', String(text).trim());
    const who = to === 'claude' ? ['claude'] : to === 'gpt' ? ['gpt'] : ['claude', 'gpt'];
    // 둘 다면 동시에 답한다(단톡방처럼). 서로의 답은 다음 차례에 보게 된다.
    return withBusy(() => Promise.all(who.map(turn)));
  }

  if (req.method === 'POST' && url.pathname === '/api/debate') {
    const { rounds } = await readBody(req);
    const n = Math.min(Math.max(Number(rounds) || 2, 1), 4);
    if (busy) return json(res, 409, { error: 'busy' });
    json(res, 200, { ok: true });
    addMessage('mina', '둘이 이 얘기로 이야기 나눠 봐. 서로의 말에 반응해 줘.');
    return withBusy(async () => {
      for (let i = 0; i < n; i++) { await turn('claude'); await turn('gpt'); }
    });
  }

  if (req.method === 'GET' && url.pathname === '/api/models') {
    return json(res, 200, { claude: CLAUDE_MODELS, gpt: await listGptModels(), current: state.models });
  }

  if (req.method === 'POST' && url.pathname === '/api/models') {
    const body = await readBody(req);
    for (const who of ['claude', 'gpt']) {
      if (typeof body[who] !== 'string' || !SAFE_MODEL.test(body[who])) continue;
      if (state.models[who] === body[who]) continue;
      state.models[who] = body[who];
      addMessage(who, `${NAMES[who]} 모델을 ${body[who] || '기본'}(으)로 바꿨어요. 대화 기억은 그대로예요.`, { system: true, note: true });
    }
    save();
    broadcast('models', state.models);
    return json(res, 200, { ok: true, current: state.models });
  }

  if (req.method === 'POST' && url.pathname === '/api/reset') {
    if (busy) return json(res, 409, { error: 'busy' });
    const keep = state.models;
    state = freshState();
    state.models = keep;
    save();
    broadcast('init', { messages: [], typing, busy });
    return json(res, 200, { ok: true });
  }

  res.writeHead(404);
  res.end();
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`삼자대면 준비 완료: http://localhost:${PORT}`);
  console.log('이 창을 닫으면 대화방도 꺼져요.');
});
