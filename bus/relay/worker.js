// 버스 바로 중계 서버 (Cloudflare Workers, 무료)
// 서울시·경기도 공공 버스 API를 대신 불러서, 앱이 읽기 쉬운 같은 모양으로 돌려줘요.
// 필요한 비밀값(Secret): SEOUL_KEY, GG_KEY (공공데이터포털 "일반 인증키(Decoding)")
const ALLOW = ["https://podokajoa.github.io"];

const SEOUL = "http://ws.bus.go.kr/api/rest/stationinfo";
const GG_ARR = "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2";
const GG_STN = "https://apis.data.go.kr/6410000/busstationservice/v2/getBusStationListv2";

const list = v => (v == null ? [] : Array.isArray(v) ? v : [v]);
// 결과가 비었을 때 공공 API가 돌려준 원본을 짧게 보여 줘서 원인을 찾기 쉽게 해요 (키는 포함되지 않아요)
const brief = j => JSON.stringify(j).slice(0, 300);
const num = v => { const n = parseInt(v, 10); return Number.isFinite(n) ? n : null; };

function cors(req) {
  const o = req.headers.get("Origin") || "";
  const ok = ALLOW.includes(o) || /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(o);
  return {
    "Access-Control-Allow-Origin": ok ? o : ALLOW[0],
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Vary": "Origin",
  };
}
const json = (req, body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...cors(req) } });

async function getJson(url) {
  const r = await fetch(url, { headers: { Accept: "application/json" } });
  const t = await r.text();
  try { return JSON.parse(t); } catch { throw new Error("공공 API가 JSON이 아닌 답을 줬어요: " + t.slice(0, 120)); }
}

function parseSeoulStops(j) {
  return list(j?.msgBody?.itemList).map(x => ({
    id: String(x.arsId), name: x.stNm, dir: x.arsId && x.arsId !== "0" ? "정류장번호 " + x.arsId : "",
  })).filter(x => x.id && x.id !== "0");
}
function parseSeoulArrivals(j) {
  return list(j?.msgBody?.itemList).map(x => ({
    route: String(x.rtNm), dir: x.adirection || "",
    sec1: num(x.traTime1), sec2: num(x.traTime2), msg1: x.arrmsg1 || "", msg2: x.arrmsg2 || "",
  }));
}
function parseGgStops(j) {
  return list(j?.response?.msgBody?.busStationList).map(x => ({
    id: String(x.stationId), name: x.stationName, dir: [x.regionName, x.mobileNo && "정류장번호 " + String(x.mobileNo).trim()].filter(Boolean).join(" · "),
  }));
}
function parseGgArrivals(j) {
  return list(j?.response?.msgBody?.busArrivalList).map(x => {
    const s = k => { const sec = num(x["predictTimeSec" + k]); if (sec != null) return sec; const m = num(x["predictTime" + k]); return m != null ? m * 60 : null; };
    const loc = k => (num(x["locationNo" + k]) != null ? `${num(x["locationNo" + k])}번째 전` : "");
    return { route: String(x.routeName), dir: x.routeDestName || "", sec1: s(1), sec2: s(2), msg1: loc(1), msg2: loc(2) };
  });
}

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { headers: cors(req) });
    const u = new URL(req.url);
    const city = u.searchParams.get("city");
    try {
      if (u.pathname === "/ping") return json(req, { ok: true, seoul: !!env.SEOUL_KEY, gg: !!env.GG_KEY });
      if (u.pathname === "/search") {
        const q = (u.searchParams.get("q") || "").trim();
        if (!q) return json(req, { error: "정류장 이름을 적어 주세요" }, 400);
        if (city === "seoul") {
          if (!env.SEOUL_KEY) return json(req, { error: "SEOUL_KEY가 설정되지 않았어요" }, 500);
          const j = await getJson(`${SEOUL}/getStationByName?serviceKey=${encodeURIComponent(env.SEOUL_KEY)}&stSrch=${encodeURIComponent(q)}&resultType=json`);
          const stops = parseSeoulStops(j);
          return json(req, stops.length ? { stops } : { stops, info: brief(j) });
        }
        if (city === "gg") {
          if (!env.GG_KEY) return json(req, { error: "GG_KEY가 설정되지 않았어요" }, 500);
          const j = await getJson(`${GG_STN}?serviceKey=${encodeURIComponent(env.GG_KEY)}&keyword=${encodeURIComponent(q)}&format=json`);
          const stops = parseGgStops(j);
          return json(req, stops.length ? { stops } : { stops, info: brief(j) });
        }
      }
      if (u.pathname === "/arrive") {
        const id = (u.searchParams.get("id") || "").trim();
        if (!id) return json(req, { error: "id가 필요해요" }, 400);
        if (city === "seoul") {
          const j = await getJson(`${SEOUL}/getStationByUid?serviceKey=${encodeURIComponent(env.SEOUL_KEY)}&arsId=${encodeURIComponent(id)}&resultType=json`);
          return json(req, { items: parseSeoulArrivals(j), at: Date.now() });
        }
        if (city === "gg") {
          const j = await getJson(`${GG_ARR}?serviceKey=${encodeURIComponent(env.GG_KEY)}&stationId=${encodeURIComponent(id)}&format=json`);
          return json(req, { items: parseGgArrivals(j), at: Date.now() });
        }
      }
      return json(req, { error: "알 수 없는 요청" }, 404);
    } catch (e) {
      return json(req, { error: String(e.message || e) }, 502);
    }
  },
};
