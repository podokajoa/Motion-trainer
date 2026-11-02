// 저장한 근거가 GitHub PR 주소라면 공개 상태·CI 결과만 읽는다.
// PR 병합은 앱 실기기 검증 완료와 다르므로 작업을 자동으로 완료 처리하지 않는다.

function parsePrLink(value) {
  const match = String(value || '').match(/https:\/\/github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)\/pull\/(\d+)(?!\d)/);
  if (!match) return null;
  const [, owner, repo, number] = match;
  return { owner, repo, number: Number(number), url: `https://github.com/${owner}/${repo}/pull/${number}` };
}

async function fetchPrStatus(value, fetcher = fetch) {
  const target = parsePrLink(value);
  if (!target) return null;
  const headers = { 'User-Agent': 'threeway-progress-board', Accept: 'application/vnd.github+json' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const options = { headers, signal: AbortSignal.timeout(8000) };
  const base = `https://api.github.com/repos/${target.owner}/${target.repo}`;
  const response = await fetcher(`${base}/pulls/${target.number}`, options);
  if (!response.ok) throw new Error(`GitHub PR 조회 실패 (${response.status})`);
  const pr = await response.json();
  const status = pr.merged_at ? 'merged' : pr.state === 'closed' ? 'closed' : 'open';
  let checks = 'unknown';
  if (pr.head && pr.head.sha) {
    try {
      const checkResponse = await fetcher(`${base}/commits/${encodeURIComponent(pr.head.sha)}/check-runs?per_page=100`, options);
      if (checkResponse.ok) {
        const data = await checkResponse.json();
        const list = Array.isArray(data.check_runs) ? data.check_runs : [];
        if (list.length) {
          if (list.some((c) => c.conclusion && !['success', 'neutral', 'skipped'].includes(c.conclusion))) checks = 'failed';
          else if (list.some((c) => c.status !== 'completed')) checks = 'pending';
          else checks = 'passed';
        }
      }
    } catch (_) { /* CI 조회 실패는 PR 자체 조회 실패가 아니다. */ }
  }
  return { url: target.url, status, checks, checkedAt: Date.now() };
}

module.exports = { parsePrLink, fetchPrStatus };
