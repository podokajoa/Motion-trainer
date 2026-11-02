// 삼자대면의 공동 진행판. 모든 방이 하나의 확정된 프로젝트 상태를 공유한다.
// AI가 작성한 회의 요약은 초안으로만 보관하고, 미나가 채택한 뒤에만 확정된다.

const STATUS = new Set(['todo', 'doing', 'review', 'done']);
const OWNER = new Set(['claude', 'gpt', 'mina']);

function clipped(value, max = 240) {
  return String(value || '').replace(/\s+/g, ' ').trim().slice(0, max);
}

function freshProject() {
  return { goal: '', decisions: [], currentTask: null, pending: null, lastMeeting: null, github: null, updatedAt: 0 };
}

function normalizeProject(value) {
  const p = value && typeof value === 'object' ? value : {};
  return {
    goal: clipped(p.goal, 500),
    decisions: Array.isArray(p.decisions) ? p.decisions.map((s) => clipped(s)).filter(Boolean).slice(-20) : [],
    currentTask: normalizeTask(p.currentTask),
    pending: normalizeProposal(p.pending),
    lastMeeting: typeof p.lastMeeting === 'string' ? clipped(p.lastMeeting, 500) : null,
    github: p.github && typeof p.github === 'object' && p.github.url ? {
      url: clipped(p.github.url, 500),
      status: clipped(p.github.status, 32), checks: clipped(p.github.checks, 32),
      checkedAt: Number(p.github.checkedAt) || 0,
    } : null,
    updatedAt: Number(p.updatedAt) || 0,
  };
}

function normalizeTask(value) {
  if (!value || typeof value !== 'object' || !clipped(value.title)) return null;
  return {
    title: clipped(value.title),
    owner: OWNER.has(value.owner) ? value.owner : 'claude',
    doneWhen: clipped(value.doneWhen),
    status: STATUS.has(value.status) ? value.status : 'todo',
    evidence: clipped(value.evidence, 500),
  };
}

function normalizeProposal(value) {
  if (!value || typeof value !== 'object' || !clipped(value.summary, 500)) return null;
  const nextTask = normalizeTask(value.nextTask);
  // 회의 기록 모델은 다음 일을 제안할 수만 있다. 스스로 '완료'를 확정할 수 없다.
  if (nextTask) {
    nextTask.status = 'todo';
    nextTask.evidence = '';
  }
  return {
    summary: clipped(value.summary, 500),
    decision: clipped(value.decision),
    nextTask,
    risk: clipped(value.risk),
    room: clipped(value.room, 80),
    t: Number(value.t) || Date.now(),
  };
}

function parseProposal(output, room) {
  const raw = String(output || '').trim();
  const body = raw.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
  let parsed;
  try { parsed = JSON.parse(body); } catch (_) { return null; }
  const proposal = normalizeProposal({ ...parsed, room, t: Date.now() });
  return proposal;
}

function applyUpdate(project, action, input = {}) {
  const p = normalizeProject(project);
  if (action === 'goal') {
    p.goal = clipped(input.goal, 500);
  } else if (action === 'approve') {
    if (!p.pending) return { error: '채택할 회의 초안이 없어.' };
    if (p.pending.decision && !p.decisions.includes(p.pending.decision)) {
      p.decisions.push(p.pending.decision);
      p.decisions = p.decisions.slice(-20);
    }
    p.lastMeeting = p.pending.summary;
    // 미완료 작업은 자동으로 덮어쓰지 않는다. 먼저 끝내거나 취소해야 한다.
    if (p.pending.nextTask) {
      if (p.currentTask && p.currentTask.status !== 'done') {
        return { error: '기존 작업이 진행 중이야. 먼저 상태를 정리해 줘.' };
      }
      p.currentTask = p.pending.nextTask;
      p.github = null;
    }
    p.pending = null;
  } else if (action === 'discard') {
    p.pending = null;
  } else if (action === 'task') {
    if (!p.currentTask) return { error: '진행 중인 작업이 없어.' };
    if (!STATUS.has(input.status)) return { error: '잘못된 작업 상태야.' };
    p.currentTask.evidence = clipped(input.evidence, 500);
    if (p.github && p.github.url !== p.currentTask.evidence) p.github = null;
    if (input.status === 'done' && !p.currentTask.evidence) {
      return { error: '완료 전에 PR·테스트 결과 등 근거를 적어 줘.' };
    }
    p.currentTask.status = input.status;
  } else if (action === 'new-task') {
    if (p.currentTask && p.currentTask.status !== 'done') return { error: '이전 작업이 아직 끝나지 않았어.' };
    p.currentTask = normalizeTask(input.task);
    p.github = null;
    if (!p.currentTask) return { error: '작업 이름이 없어.' };
  } else {
    return { error: '지원하지 않는 변경이야.' };
  }
  p.updatedAt = Date.now();
  return { project: p };
}

function projectContext(project) {
  const p = normalizeProject(project);
  return [
    '=== 모든 대화방이 공유하는 진행판 (확정 사항만) ===',
    `목표: ${p.goal || '(아직 없음)'}`,
    `확정된 결정: ${p.decisions.length ? p.decisions.map((s, i) => `${i + 1}. ${s}`).join(' / ') : '(없음)'}`,
    p.currentTask ? `현재 한 가지 작업: ${p.currentTask.title} | 담당: ${p.currentTask.owner} | 상태: ${p.currentTask.status} | 완료 조건: ${p.currentTask.doneWhen || '(미정)'} | 근거: ${p.currentTask.evidence || '(없음)'}` : '현재 작업: (없음)',
    `GitHub PR 자동 조회: ${p.github ? `${p.github.url} | ${p.github.status} | CI ${p.github.checks}` : '(연결된 PR 없음 또는 확인 전)'}`,
    `최근 회의 요약: ${p.lastMeeting || '(없음)'}`,
    'AI의 제안은 미나가 확정하기 전까지 결정이 아님. 코드·PR·빌드·실기기 검증을 구분할 것.',
    '=============================================',
  ].join('\n');
}

module.exports = { freshProject, normalizeProject, normalizeProposal, parseProposal, applyUpdate, projectContext };
