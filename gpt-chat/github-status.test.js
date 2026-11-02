const test = require('node:test');
const assert = require('node:assert/strict');
const { parsePrLink, fetchPrStatus } = require('./github-status');

test('정확한 GitHub PR 링크만 API 조회 대상으로 삼는다', () => {
  assert.equal(parsePrLink('PR: https://github.com/podokajoa/Motion-trainer/pull/26').number, 26);
  assert.equal(parsePrLink('https://evil.example/pull/26'), null);
  assert.equal(parsePrLink('PR #26'), null);
});

test('PR 병합과 CI 통과는 검증 완료라고 표시하지 않는다', async () => {
  const calls = [];
  const fakeFetch = async (url) => {
    calls.push(url);
    if (url.includes('/pulls/')) return { ok: true, json: async () => ({ merged_at: '2026-11-02', state: 'closed', head: { sha: 'abc' } }) };
    return { ok: true, json: async () => ({ check_runs: [{ status: 'completed', conclusion: 'success' }] }) };
  };
  const result = await fetchPrStatus('https://github.com/podokajoa/Motion-trainer/pull/26', fakeFetch);
  assert.equal(result.status, 'merged');
  assert.equal(result.checks, 'passed');
  assert.equal(calls.length, 2);
  assert.ok(!Object.hasOwn(result, 'verified'));
});
