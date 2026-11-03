#!/usr/bin/env python3
"""카카오톡 '나에게 보내기'로 메시지를 보내요. GitHub Actions(kakao-todo.yml)에서 실행돼요.

환경변수
  KAKAO_AUTH     kakao/ 페이지에서 받은 연결 코드 (REST 키·시크릿·리프레시 토큰을 base64로 묶은 것)
  KAKAO_MSG_KEY  메시지 자물쇠를 여는 열쇠 (msg-lock.pem 짝)
  MSG            encrypt.sh로 잠근 메시지 (한 줄 base64)

잠긴 메시지 안은 JSON: {"text": "...", "link": "https://..."(선택)}
"""
import base64, json, os, subprocess, sys, tempfile, urllib.parse, urllib.request

HOME = "https://podokajoa.github.io/Motion-trainer/kakao/"
LIMIT = 200  # 카카오 텍스트 템플릿 글자 수 한도


def post(url, data, token=None):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode())
    req.add_header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        # 토큰이 로그에 남지 않도록 응답 본문만 보여 줘요
        sys.exit(f"카카오 요청 실패 {e.code}: {body}")


def unlock(cipher):
    pem = "-----BEGIN CMS-----\n"
    pem += "\n".join(cipher[i:i + 64] for i in range(0, len(cipher), 64))
    pem += "\n-----END CMS-----\n"
    with tempfile.TemporaryDirectory() as d:
        key = os.path.join(d, "k.pem")
        with open(key, "w") as f:
            f.write(os.environ["KAKAO_MSG_KEY"].strip() + "\n")
        out = subprocess.run(["openssl", "cms", "-decrypt", "-inform", "PEM", "-inkey", key],
                             input=pem.encode(), capture_output=True)
    if out.returncode:
        sys.exit("메시지를 열지 못했어요 (KAKAO_MSG_KEY 확인): " + out.stderr.decode()[:300])
    return json.loads(out.stdout.decode())


def chunks(text):
    """200자 넘으면 줄 단위로 나눠요. 한 줄이 너무 길면 글자 단위로 자르고요."""
    parts, cur = [], ""
    for line in text.strip().split("\n"):
        while len(line) > LIMIT - 8:
            if cur:
                parts.append(cur); cur = ""
            parts.append(line[:LIMIT - 8]); line = line[LIMIT - 8:]
        if len(cur) + len(line) + 1 > LIMIT - 8:
            parts.append(cur); cur = line
        else:
            cur = f"{cur}\n{line}" if cur else line
    if cur:
        parts.append(cur)
    if len(parts) > 1:
        parts = [f"{p}\n({i}/{len(parts)})" for i, p in enumerate(parts, 1)]
    return parts


def main():
    auth = json.loads(base64.b64decode(os.environ["KAKAO_AUTH"].strip()).decode())
    msg = unlock(os.environ["MSG"].strip().replace(" ", ""))
    link = msg.get("link") or HOME

    data = {"grant_type": "refresh_token", "client_id": auth["k"], "refresh_token": auth["r"]}
    if auth.get("s"):
        data["client_secret"] = auth["s"]
    tok = post("https://kauth.kakao.com/oauth/token", data)
    access = tok["access_token"]
    print(f"::add-mask::{access}")

    texts = [(t, link) for t in chunks(msg["text"])]
    # 리프레시 토큰이 새로 나오면 = 기존 토큰이 한 달 안에 끝나요. 다시 연결하라고 알려요.
    if tok.get("refresh_token"):
        print(f"::add-mask::{tok['refresh_token']}")
        texts.append(("🔔 카톡 알림 연결이 한 달 안에 끝나요. 시간 날 때 아래 '열기'를 눌러 카카오 로그인을 다시 해 주세요 (1분이면 돼요).", HOME))

    for t, url in texts:
        tpl = {"object_type": "text", "text": t,
               "link": {"web_url": url, "mobile_web_url": url}, "button_title": "열기"}
        r = post("https://kapi.kakao.com/v2/api/talk/memo/default/send",
                 {"template_object": json.dumps(tpl, ensure_ascii=False)}, access)
        if r.get("result_code") != 0:
            sys.exit(f"보내기 실패: {r}")
    print(f"카톡 {len(texts)}통 보냈어요")


if __name__ == "__main__":
    main()
