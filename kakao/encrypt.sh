#!/usr/bin/env bash
# 메시지를 잠가서 한 줄로 출력해요. 이 값을 kakao-todo 워크플로의 msg 입력에 넣으면 돼요.
# 저장소가 공개라서, 할 일 내용이 실행 기록에 보이지 않도록 잠가서 보내요.
#   사용: kakao/encrypt.sh "보낼 내용" [열 링크]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
text="${1:?보낼 내용을 적어 주세요}"
link="${2:-}"
TEXT="$text" LINK="$link" python3 -c 'import json,os; d={"text":os.environ["TEXT"]}; os.environ["LINK"] and d.update(link=os.environ["LINK"]); print(json.dumps(d,ensure_ascii=False),end="")' \
  | openssl cms -encrypt -aes256 -binary -outform PEM "$here/msg-lock.pem" \
  | grep -v -- '-----' | tr -d '\n'
echo
