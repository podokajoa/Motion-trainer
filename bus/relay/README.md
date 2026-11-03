# 버스 바로 중계 서버 만들기

서울시·경기도 버스 도착 정보를 앱이 받을 수 있게 대신 불러 주는 작은 서버예요. 무료이고, 한 번만 만들면 돼요.

## 1. 공공데이터 키 받기 (무료, 로그인 필요)
[data.go.kr](https://www.data.go.kr)에서 아래 두 가지를 검색해 **활용신청** 해요. 보통 바로 승인되고, 키가 반영되기까지 최대 1~2시간 걸릴 수 있어요.

- 서울: "서울특별시_정류소정보조회 서비스" (버스 정류소·도착 정보)
- 경기: "경기도_버스도착정보 조회", "경기도_버스정류소 조회"

신청이 끝나면 마이페이지 > 활용신청 내역에서 **일반 인증키(Decoding)**를 복사해 둬요.

## 2. Cloudflare에 서버 올리기 (무료)
1. [dash.cloudflare.com](https://dash.cloudflare.com)에 가입하고 **Workers & Pages > Create > Hello World**로 새 Worker를 만들어요 (이름 예: `bus-relay`).
2. **Edit code**에서 기본 코드를 지우고 이 폴더의 `worker.js` 전체를 붙여 넣고 **Deploy**.
3. Worker 설정 > **Variables and Secrets**에 **Secret** 두 개를 추가해요.
   - `SEOUL_KEY` = 서울 인증키(Decoding)
   - `GG_KEY` = 경기 인증키(Decoding)
4. Worker 주소(`https://bus-relay.○○○.workers.dev`)를 복사해요.

## 3. 앱에 연결
앱 오른쪽 위 **설정**에 Worker 주소를 붙여 넣고 저장해요. "연결됐어요"가 뜨면 끝이에요.
