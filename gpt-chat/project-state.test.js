const test = require('node:test');
const assert = require('node:assert/strict');
const { freshProject, normalizeProject, parseProposal, applyUpdate, projectContext } = require('./project-state');

test('이전 대화 저장 상태에 프로젝트가 없어도 안전하게 초기화한다', () => {
  const p = normalizeProject(undefined);
  assert.deepEqual(p, freshProject());
});

test('AI의 회의 초안은 확정 상태에 자동 유입되지 않는다', () => {
  const p = freshProject();
  p.pending = parseProposal(JSON.stringify({
    summary: '글쓰기 부담을 줄인다', decision: '질문은 선택 기능으로 제공',
    nextTask: { title: '질문 버튼 시안', owner: 'claude', doneWhen: '앱 화면에서 선택 가능' },
    risk: '매번 질문이 뜨면 피로함',
  }), 'room1');
  assert.ok(p.pending);
  assert.equal(p.decisions.length, 0);
  assert.equal(p.currentTask, null);
  assert.doesNotMatch(projectContext(p), /질문은 선택 기능으로 제공/);
  const approved = applyUpdate(p, 'approve');
  assert.equal(approved.project.decisions[0], '질문은 선택 기능으로 제공');
  assert.equal(approved.project.currentTask.title, '질문 버튼 시안');
  assert.equal(approved.project.pending, null);
});

test('근거 없는 완료와 미완료 작업 덮어쓰기를 막는다', () => {
  const started = applyUpdate(freshProject(), 'new-task', { task: { title: '테스트', owner: 'claude', doneWhen: 'APK 테스트' } }).project;
  assert.match(applyUpdate(started, 'task', { status: 'done' }).error, /근거/);
  assert.match(applyUpdate(started, 'new-task', { task: { title: '다음' } }).error, /아직/);
  const reviewing = applyUpdate(started, 'task', { status: 'review', evidence: 'PR #26' }).project;
  assert.equal(reviewing.currentTask.status, 'review');
  const completed = applyUpdate(reviewing, 'task', { status: 'done', evidence: '실기기 확인' }).project;
  assert.equal(completed.currentTask.status, 'done');
  assert.equal(applyUpdate(completed, 'new-task', { task: { title: '다음' } }).project.currentTask.title, '다음');
});

test('깨진 AI 응답은 회의 초안으로 사용하지 않는다', () => {
  assert.equal(parseProposal('논의해 봤는데요.', 'r1'), null);
  assert.equal(parseProposal('```json\n{"summary":"핵심", "nextTask":null}\n```', 'r1').summary, '핵심');
});

test('AI가 완료라고 써도 채택 시 다음 작업은 시작 전으로 표시한다', () => {
  const p = freshProject();
  p.pending = parseProposal(JSON.stringify({
    summary: '정리', nextTask: { title: '새 작업', status: 'done', evidence: 'AI가 완료라고 함' },
  }), 'r1');
  const approved = applyUpdate(p, 'approve');
  assert.equal(approved.project.currentTask.status, 'todo');
  assert.equal(approved.project.currentTask.evidence, '');
});
