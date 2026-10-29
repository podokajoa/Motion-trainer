# 모션 트레이너

카메라로 자세를 인식해 홈트 동작을 세어 주고, 자세 교정과 들숨/날숨을 음성으로 안내하는 웹앱입니다.

- 운동: 스쿼트, 런지, 푸시업, 플랭크, 덤벨 컬
- 자세 인식: Google MediaPipe Pose (브라우저 안에서만 처리, 영상은 외부로 전송되지 않음)
- 안드로이드 크롬에서 열고 메뉴 > "홈 화면에 추가"로 설치

## 폰에서 쓰는 법
1. https://podokajoa.github.io/Motion-trainer/ 를 안드로이드 크롬으로 열기
2. 메뉴(⋮) > 홈 화면에 추가
3. 폰을 바닥이나 선반에 세우고, 몸 전체가 보이게 2~3m 떨어져서 시작

## 미소 트레이너 (smile/)
입꼬리 웃음 연습 앱입니다. MediaPipe Face Landmarker로 입꼬리 좌우 대칭, 높이, 유지 시간을 음성으로 코칭해요.

- 주소: https://podokajoa.github.io/Motion-trainer/smile/ (따로 홈 화면에 추가 가능)
- 은은한 미소 / 활짝 미소, 유지 시간과 횟수 선택, 연습 기록 저장

## 갈대 탈출 (decide/)
마음이 갈대처럼 흔들리는 사람을 위한 결정 도우미예요. 고민과 선택지를 적으면 골라 주고, 정한 결정을 지키도록 도와줘요.

- 주소: https://podokajoa.github.io/Motion-trainer/decide/ (따로 홈 화면에 추가 가능)
- 고르는 방법: 운에 맡기기(결과를 본 순간의 기분으로 속마음 확인), 둘씩 비교하기(토너먼트), 장단점 저울(무게 1~3), 직접 고르기
- 결정 잠금(3시간~일주일), 나중의 나에게 남기는 한마디, 번복할 때마다 "흔들림" 기록
- 기록은 폰(브라우저)에만 저장

## 새벽 시장 떡집 (mystery/)
보드게임 머더미스터리를 폰으로 옮긴 1인 추리 게임이에요. 새벽 시장 떡집 사장이 쌀가마에 깔린 채 발견되고, 8시 전에 범인을 밝혀야 해요.

- 주소: https://podokajoa.github.io/Motion-trainer/mystery/ (따로 홈 화면에 추가 가능)
- 현장 사진 조사, 용의자 3명 심문(증거 내밀기), 단서 두 개를 실로 이어 새 사실 열기
- 동료 형사 2명(NPC)이 단톡방에서 단서를 가져오고 서로 다른 추리를 펼쳐요
- 한 번 움직일 때마다 10분, 범인 지목엔 근거 3개(동기, 현장에 있었다, 거짓말) 필요
- 단서마다 처음 가진 사람(holder)이 정해져 있어서, 나중에 2~4인 모드로 넓힐 때 NPC 자리를 사람이 채우면 돼요

## 여행 발자국 (travel-footprints/)
갤럭시 갤러리 사진의 GPS로 여행 기록을 지도에 보여주는 안드로이드 앱이에요 (Kotlin, MapLibre).

- 설치: 폰에서 https://github.com/podokajoa/Motion-trainer/releases/download/travel-footprints-latest/travel-footprints.apk 를 받아 설치
- APK는 GitHub Actions가 main에 올라올 때마다 자동으로 만들어요. 기존 앱을 지우지 않고 업데이트하려면 저장소 Secret `DEBUG_KEYSTORE_B64`(집 PC `debug.keystore`의 base64)가 필요해요.
- 사진과 분석 기록은 폰 안에만 저장돼요.
