#!/usr/bin/env bash
#
# prod/workers README(일꾼 감시의 수신 불능 · 무완료 · 기동 판정 — POK-408)의
# 판정 함수를 가짜 aws 로 시험한다. AWS 에 접속하지 않고 terraform 도 쓰지
# 않는다.
#
# README 의 셸 블록을 그대로 뽑는다(사본 없음 — README 글자를 바꾸면 이 시험이
# 바뀐 글자를 잰다). 블록은 그 안의 한 줄(ANCHORS — 줄 머리가 같아야 함)로
# 찾고, 그 줄을 품은 블록이 README 에 정확히 하나여야 한다. 뽑은 블록을 사례마다
# 하위 셸에서 읽는다. TMPDIR 은 사례 디렉터리이고, aws 는 셸 함수(fake_aws)로
# 덮는다. 가짜 aws 는 받은 인자를 사례의 calls 에 적고, 응답은 사례의 resp/
# 에서 고른다.
#
# 응답 파일 이름은 「<서비스>.<동작>」 에 식별 인자를 이어 붙인 열쇠다. 식별
# 인자는 --log-group-name · --queue-name · --metric-name · --attribute-names ·
# --next-token 의 값, --queue-url 의 끝 조각, --dimensions 의 Value 다(이어 받는
# 쪽은 「logs.filter-log-events.<그룹>.<표>」). 「<열쇠>.fail」 이 있으면
# 종료 254(조회 실패)다. 응답이 없으면 종료 255 로 실패하고 그 열쇠를 표준
# 오류에 적는다. 가짜 aws 는 --start-time · --filter-pattern 으로 거르지 않는다 —
# 판정 함수가 시각 · 줄 이름을 다시 거르는지도 이 시험이 본다. 사례에 가짜
# 시계(clock)가 있으면 가짜 aws 는 시계를 따른다 — 조회 시간만큼 시계를 옮기고
# (delays 의 줄마다 한 호출 · 없으면 delay · 「기다림+상한 밖」 꼴), 기다림이
# --cli-connect-timeout + --cli-read-timeout 을 넘으면 AWS_MAX_ATTEMPTS 번
# 그 상한까지 걸린 뒤 실패하고(없으면 CLI 기본 60 · 60 · 3), fail_from 시각
# 이후 첫 호출을 한 번 실패시키고, lag 가 있으면 호출 시각에서 lag 초 앞까지의
# 로그 사건만 낸다(수집 지연).
#
# 사례(판정 줄 = 표준 출력의 마지막 줄 · 종료 코드 0 통과 · 1 정지 · 2
# 판정불가 · 3 대기). 설계 3-6 의 다섯은 「설계」 를 붙인다.
#   설계 — 정상 6분 렌더 + 뒤 대기 주문 = 수신 · 무완료 모두 통과.
#   설계 — 연결 시간 초과 반복 = 1분 넘으면 수신 정지(50초는 통과).
#   설계 — 빈 큐 정상 + 지표 10분 지연 = 로그 잠정 통과 → 지표 대기 → 통과.
#   설계 — 빈 큐 연결 불능 = 수신 정지 · 로그 판정 실패.
#   설계 — DELAY 로 숨긴 메시지만 = 무완료 통과.
#   수신 — 줄 이름이 다른 실패는 잇지 않음 · 180초 넘는 틈(0 · 190)은 끊김 ·
#     SDK 재시도 상한 꼴의 틈(0 · 126 · 252 · 378 과 0 · 134 · 268)은 정지 ·
#     같은 줄의 시작 · 완료(0 실패 · 10 started · 145 message_done · 160 실패)와
#     upload.begin 은 이어짐을 끊음 · 다른 줄의 완료는 끊지 않음 · 사이 신호
#     없는 고립 실패 0 · 170 은 정지 · 회복한 지난 장애(0–72초 · 지금 3600초)는
#     통과 · 지금 epoch 없음 · 시작 epoch 가 문장 · 조회 실패 · 이어 받기
#     표(nextToken)가 있는 조회 = 판정불가.
#   감시 반복 — 정지면 첫 주기에 1(기다리지 않음) · 2초 쉬며 보고 끝 시각에 0 ·
#     조회 실패 두 번 이어짐 = 판정불가 · 한 번은 다음 주기에 다시 · 끝 시각의
#     조회 실패는 한 주기 더 · 5초 넘긴 판정 두 번 = 판정불가 · 판정 줄마다
#     끝난 시각(UTC) · 늦은 조회 실패도 늦음 줄 · 시작 epoch 가
#     판정불가 문장이거나 끝 epoch 가 빈 값 = 판정불가(반복하지 않음) · README
#     「쓰는 예」 블록을 기동 신호 전에 띄우면 기다렸다가 기동 epoch 를 찍고
#     돎. date · sleep 은 사례의 가짜 시계다(sleep 200번을 넘으면 끊음).
#   다시 띄우기 — README 「다시 띄우기」 블록을 이어지던 장애 한가운데서 띄운
#     r4 cx(실패 0 · 134 · 268 · 133초에 다시 · 기동 10초 전)와 r4 cc(실패
#     0 · 134 · 194 · 328 · 1초에 다시 · 기동 1시간 전 · 수집 지연 10초)
#     시간열 — 첫 실패 줄에서 240초 안에 정지(위 정지 시한과 같은 셈).
#     started 를 옮겨 적지 않은 꼴(미설정)은 bash · 대화형 zsh 모두 감시를
#     띄우지 않고 판정불가로 멈춤(zsh 는 [ … -gt "" ] 가 빈 값을 0 으로 읽음).
#   정지 시한 — 실패 0 · 59 · 193(r2 cx) · 0–52 · 186 + 수집 지연 2초(r2 cc) ·
#     60 · 194 · 지연 10초 · 판정마다 5초 · r3 cx(조회 2 · 14 · 14초) · 조회가
#     상한까지 걸려 실패 둘 · 셋째 조회가 상한 밖에서 100초 멈춤(줄 끊김) —
#     판정 줄의 시각(끊김이면 앞 줄 + 10초)에 명령 여유 12초를 더해 첫 실패
#     줄에서 240초 안. 끊김 사례 밖에서는 줄 사이 10초 넘는 끊김도 실패,
#     끊김 사례는 끊김이 실제로 있어야 함. 뒤 둘은 조회 셋을 다 탐.
#   무완료 — 20분 무완료 = 정지 · 창 밖 완료는 세지 않음 · 기동 20분 전 = 대기 ·
#     나이 20분 이하 = 통과 · 나이 지표 없음 · 깊이 조회 실패 = 판정불가.
#   로그 판정 — 깊이 > 0 인데 시작 · 완료 줄 없음 = 실패 · 3분 전(150초) =
#     대기 · 시작 줄(render.started · 진행 중 업로드의 upload.begin)로 통과.
#   지표 판정 — 기동 앞 분의 점은 세지 않음 · 20분 넘어 점 없음 = 판정불가 ·
#     조회 실패 · 쌍점 없는 시각 · 범위 밖 오프셋 · 없는 날짜(변환 오류) =
#     판정불가.
#   사진 판정 — 옛 라이브 실패 넷 + captured 다수 · 라이브 실패 한 줄 · 세션
#     끝 라이브 실패 둘 · 기동 직후 단일 실패 · 창이 차기 전 못 만든 처리 셋 ·
#     영구 실패 셋 · 못 만든 처리 둘 · target_gone 셋 ·
#     live_redelivered_dropped 셋 · 카드 영구 실패 하나 · 사이에 captured 가
#     있는 영구 실패 셋 · 볼 창 바로 앞 captured 의 message_done = 통과,
#     120초 지속 captured 0 · r2 cx 시간열 · captured 로 닫힌 지난 구간 · 61초
#     간격 라이브 실패 셋 · 영구 실패 셋 · 절반 넘는 라이브 실패 · 앞 120초
#     구간의 비율 · 카드 · 클립 잠깐 실패 1건 = 정지.
#   사진 반복 — README 사진 반복 블록이 카드 실패 줄에서 52초 안(그 판정이
#     조회 실패면 88초 안)에, r2 cx 시간열(위상 다섯) · 60초마다 못 만든 처리 ·
#     61초마다 라이브 실패(위상 둘씩)는 조건이 선 때에서 52초 안에 정지 줄로
#     멈추고, 실패가 없으면 30초 쉼으로 끝 시각에 끝 줄(끝 시각의 조회 실패는
#     한 주기 더) · 조회 실패 둘 이어짐 = 판정불가 · started · watch_end 없음 =
#     판정불가(판정하지 않음).
#   기동 시각 — 첫 worker_started 의 초 · 없음 · apply 시작 epoch 빈 값 =
#     판정불가.
#   블록 글자 — 줄 머리나 공백 뒤의 `#` 가 0개(부르지 않는 갈래의 주석까지) ·
#     사진 반복 블록은 꺾쇠 · 글자가 붙은 `!` 도 0개이고 대화형 zsh 에 붙여
#     넣은 결과가 bash 와 같음.
#   대화형 zsh — 블록을 `zsh -f -i -s` 에 표준 입력으로 넣은 것(제어 터미널
#     없음 · 새 PTY 를 제어 터미널로 준 것 둘)과 PTY 의 bracketed
#     paste(python3)로 블록마다 붙여 넣은 것, 셋에서 판정 함수 열이 모두
#     whence -w 로 function 이고, 정의하는 동안 가짜 aws 가 한 번도 불리지
#     않고, 그중 여덟을 차례로 한 번씩 부른 결과(나머지 둘 workers_events ·
#     workers_queue_depth 는 그 안에서 불림 — 출력 · 오류 · 종료 코드)가
#     같은 입력의 bash 와 바이트 단위로 같고 `command not found` 가 없음. 음성
#     대조로 수신 판정 본문 첫 줄에 `# …` 를 넣으면 이 검사가 잡는지 본다.
#     -s 는 제어 터미널이 있어도 명령을 표준 입력에서 읽게 한다 — 없으면 zsh 가
#     터미널에서 읽어, 사람이 터미널에서 시험을 돌릴 때 프롬프트를 띄운 채
#     멈춘다(터미널을 준 사례가 20초 상한으로 잡는다). zsh(PTY 둘은 python3
#     도)가 없으면 skip 으로 센다. 대화형 zsh 의 기본값은 interactivecomments
#     꺼짐 · 이력 확장 켜짐이다. 그래서 `<…>` · 글자가 붙은 `!` 처럼 정의를
#     깨는 글자는 정의 검사가, 정의는 되지만 부를 때 `command not found: #` 가
#     되는 본문 주석은 부르는 검사가 잡는다. 부르는 검사는 실제로 탄 갈래만
#     보므로, 타지 않은 갈래의 `#` 는 블록 글자 사례가 맡는다. bash eval 과
#     `zsh -n` 은 셋 다 못 잡는다.
#
# 변이: 판정 규칙 한 줄을 뒤집은 README 사본(MUTATIONS)마다 사례 묶음을 다시
# 돌려 하나라도 실패해야 한다. 바꿀 글자가 README 블록에 정확히 한 번 있어야
# 한다. 대화형 zsh 사례는 변이 묶음에 넣지 않는다(한 번만 돈다).
#
# 임시 디렉터리는 한 뿌리($TMPDIR/workers_watch_runbook_test.*) 아래에 두고, 끝에
# 지운 뒤 남지 않았는지 본다(중단 신호에도 지운다). 끝에 통과와 skip 건수의
# 합이 사례와 변이를 더한 수(EXPECTED_CASES)와 같은지 본다. 틀은 같은 디렉터리의
# network_vpce_runbook_test 와 같다.
#
# 사용: bash infra/terraform/scripts/test/workers_watch_runbook_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly README="${SCRIPT_DIR}/../../prod/workers/README.md"

# 뽑을 블록을 찾는 줄(줄 머리). 차례대로 이어 붙여 읽는다.
readonly ANCHORS=(
  'region=ap-northeast-2'
  'workers_events() {'
  'workers_receive_verdict() {'
  'workers_receive_watch() {'
  'workers_nodone_verdict() {'
  'workers_start_log_verdict() {'
  'workers_empty_receive_verdict() {'
  'workers_thumbnail_verdict() {'
)

# 「사진 반복」 블록(언어 없는 블록)을 찾는 줄(줄 머리).
# shellcheck disable=SC2016
readonly LOOP_ANCHOR='for arg in "${started-}" "${watch_end-}"; do'

# 변이 — 이름 · 찾을 글자 · 바꿀 글자(같은 차례). 글자는 README 의 셸 · jq ·
# awk 코드 그대로라 펼치지 않는다.
readonly MUTATION_NAMES=(
  'receive: 이어짐 판정 뒤집기'
  'receive: 줄 이름별로 나누기 빼기'
  'receive: 틈 판정 빼기'
  'receive: 틈 상한을 120초로 되돌리기'
  'events: 시작 시각 거르기 빼기'
  'nodone: 보이는 깊이 대신 숨긴 깊이'
  'nodone: 기동 뒤 20분 대기 빼기'
  'nodone: 나이 판정 뒤집기'
  'start-log: 깊이 > 0 의 시작 줄 확인 빼기'
  'empty: 기동 앞 분 거르기 빼기'
  'empty: 20분 상한 빼기'
  'receive: 같은 줄의 정상 신호로 끊기 빼기'
  'receive: 지금 이어지는 중인지(최근성) 빼기'
  'start-log: upload.begin 을 진행 신호에서 빼기'
  'watch: 판정불가 한 번에 멈추기'
  'watch: 쉼 2초를 5초로(옛 주기)'
  'watch: 끝 시각의 조회 실패에도 끝내기'
  'watch: 시각 인자의 숫자 검사 빼기'
  'receive: 시작 epoch 의 숫자 검사 빼기'
  'started: apply 시작 epoch 의 숫자 검사 빼기'
  'watch: 판정 한 번의 시간 상한 빼기'
  'watch: 늦음 검사를 통과에만(옛 꼴)'
  'watch: 판정 줄의 시각 빼기'
  'events: 시도 1번 빼기'
  'events: 연결 시간 상한 빼기'
  'events: 읽기 시간 상한 빼기'
  'events: 이어 받기 표 따라가기 빼기'
  'restart: 시작을 600초 앞으로 당기기 빼기'
  'restart: started 의 숫자 검사 빼기'
  'events: 쪽 따라가기 2초 상한 빼기'
  'events: 표가 남은 채 끝나면 쓰기'
  'watch: 지난 600초 조회 창 빼기'
  'empty: 시각 오프셋 빼기'
  'empty: 시각 꼴 검사 빼기'
  'thumb: 카드 · 클립 실패 정지 빼기'
  'thumb: 처리 중 captured 0 정지 빼기'
  'thumb: 라이브 실패 비율 정지 빼기'
  'thumb: kind=live 실패도 한 줄로 정지(옛 규칙)'
  'thumb: 볼 창 빼기(기동부터 다 봄)'
  'thumb: 기동 epoch 숫자 검사 빼기'
  'thumb: 관찰 기간(창이 120초를 채움) 검사 빼기'
  'thumb: 못 만든 처리의 최소 건수 빼기'
  'thumb: 의도된 지우기(target_gone · 다시 받은 라이브)도 못 만든 처리로 셈'
  'thumb: 줄 정규식에서 의도된 지우기 빼기'
  'thumb: 카드 · 클립 영구 실패 한 건도 정지(kty 결정 「가」 전 꼴)'
  'thumb: 영구 실패를 못 만든 처리로 셈'
  'empty: 시 · 분 · 초 범위 검사 빼기'
  'empty: 오프셋 범위 검사 빼기'
  'empty: 없는 날짜를 되돌려 보는 검사 빼기'
  'empty: 시각 변환 오류를 대기로(옛 꼴)'
  'thumb: 볼 창 240초를 120초로(한 창 — 옛 꼴)'
  'thumb: 짝짓기 여유(볼 창 앞 5초) 빼기'
  'thumb: captured 로 닫힌 지난 구간 보지 않기'
  'thumb: 비율 · 영구 실패를 지금 120초 한 창으로(옛 꼴)'
  'thumb: captured 0 구간의 live 실패 셋 정지 빼기'
  'thumb: 영구 실패 셋 정지 빼기'
  'thumb: 영구 실패 정지의 captured 0 검사 빼기'
  'thumb: 영구 실패 정지의 120초 채움 검사 빼기'
  'thumb: 처리(message_done)와 결과 줄 짝짓기 빼기'
  'thumb-loop: 조회 실패 한 번에 멈추기'
  'thumb-loop: 끝 시각의 조회 실패에도 끝내기'
  'thumb-loop: started · watch_end 숫자 검사 빼기'
)
# shellcheck disable=SC2016
readonly MUTATION_FROMS=(
  'last[name] - first[name] > limit'
  'name = substr($0, RSTART, dot - 1)'
  '$1 - last[name] <= gap'
  '-v gap=180 '
  'select(.timestamp >= $from)'
  '--attribute-names ApproximateNumberOfMessages '
  '[ "$(($5 - $4))" -lt 1200 ]'
  '[ "${age}" -le 1200 ]'
  '[ "${depth}" -gt 0 ] && [ "$(grep -c . "${work}/progress-$2.txt")" -eq 0 ]'
  '>= $from) | .Sum]'
  '[ "$(($3 - $2))" -lt 1200 ]'
  'delete last[name]'
  'now - last[name] <= gap'
  '"$2\\.(started|begin|message_done) "'
  $'[ "${misses}" -ge 2 ]; then\n      echo "${stamp} 수신'
  'sleep 2'
  '[ "${misses}" -eq 0 ] && [ "${begin}" -ge "$3" ]'
  'for arg in "${2-}" "${3-}"; do'
  'case "${2-}" in'
  $'local first\n  case "${3-}" in'
  '[ "$((now - begin))" -gt 5 ]'
  'if [ "$((now - begin))" -gt 5 ]'
  'sed "s/^/${stamp} /"'
  'AWS_MAX_ATTEMPTS=1 '
  '--cli-connect-timeout 1 '
  '--cli-read-timeout 2 '
  'if [ -z "${token}" ]; then'
  ' - 600 ))"'
  'case "${started-}" in'
  '- begin))" -ge 2 ]'
  $'nextToken · ${pages}쪽)" >&2\n      return 2'
  '      from="$((begin - 600))"'
  '- ($t[19:] |'
  'type == "array" and all('
  'if (other > 0) {'
  'if (n["unmade"] >= least) {'
  'if (w["live"] >= least && w["live"] > w["captured"]) {'
  'if ($0 ~ / kind=live /) { pending[tail++] = "live" }'
  'lo="$(($3 - 240))"'
  '  case "${from}" in'
  'end - start >= span'
  'n["unmade"] >= least'
  '{ pending[tail++] = "skipped" }'
  "|target_gone|live_redelivered_dropped) '"
  $'      else if ($0 ~ / retryable=false /) { pending[tail++] = "permanent" }\n'
  '{ pending[tail++] = "permanent" }'
  'T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]'
  '|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$'
  'if ($at | todate) == $utc then . else'
  '    1) ;;'
  'lo="$(($3 - 240))"'
  '    from="$((lo - 5))"'
  $'          close_span(t - 1)\n'
  'for (s = lo; s <= last; s++) {'
  '} else if (n["live"] >= least) {'
  'w["permanent"] >= least && w["captured"] == 0'
  ' && w["captured"] == 0) {'
  'if (e - s >= span && '
  'if (head < tail) { got = pending[head++] }'
  '    *) misses=$((misses + 1)) ;;'
  'elif [ "${misses}" -eq 0 ] && [ "${begin}" -ge "${watch_end}" ]; then'
  $'      misses=2\n'
)
# shellcheck disable=SC2016
readonly MUTATION_TOS=(
  'last[name] - first[name] < limit'
  'name = "all"'
  '1'
  '-v gap=120 '
  'select(true)'
  '--attribute-names ApproximateNumberOfMessagesNotVisible '
  'false'
  '[ "${age}" -gt 1200 ]'
  'false'
  '>= 0) | .Sum]'
  'true'
  'n = n'
  '1'
  '"$2\\.(started|message_done) "'
  $'[ "${misses}" -ge 1 ]; then\n      echo "${stamp} 수신'
  'sleep 5'
  '[ "${begin}" -ge "$3" ]'
  'for arg in 0; do'
  'case 0 in'
  $'local first\n  case 0 in'
  'false'
  'if [ "${rc}" -eq 0 ] && [ "$((now - begin))" -gt 5 ]'
  'cat'
  ''
  ''
  ''
  'if true; then'
  ' ))"'
  'case 0 in'
  '- begin))" -ge 99 ]'
  $'nextToken · ${pages}쪽)" >&2\n      break'
  '      from="$2"'
  '- 0 * ($t[19:] |'
  'type == "array" or all('
  'if (0) {'
  'if (0) {'
  'if (0) {'
  'if (0) { pending[tail++] = "live" }'
  'lo="$2"'
  '  case 0 in'
  '1'
  'n["unmade"] > 0'
  '{ }'
  ") '"
  ''
  '{ pending[tail++] = "unmade" }'
  'T[0-9:]{8}'
  '|[+-][0-9]{2}:[0-9]{2})$'
  'if true then . else'
  '    *) ;;'
  'lo="$(($3 - 120))"'
  '    from="${lo}"'
  $'          split("", n)\n'
  'for (s = last; s <= last; s++) {'
  '} else if (0) {'
  '0'
  ') {'
  'if ('
  ''
  '    *) misses=2 ;;'
  'elif [ "${begin}" -ge "${watch_end}" ]; then'
  $'      :\n'
)

# 사례 시각의 기준(2026-11-10T00:00:00Z) — 사례는 여기서 몇 초 뒤로 적는다.
readonly T0=1794268800
# 가짜 계정 ID(12자리 — 0 으로 채운 값이라 실제 계정이 아니다).
FAKE_ACCOUNT="$(printf '%012d' 42)"
readonly FAKE_ACCOUNT
# 사례 139(대화형 zsh 여섯 · 블록 글자 둘 · 본문 안내 둘 포함) + 변이 62.
# 사례나 변이를 더하거나 빼면 함께 고친다.
readonly EXPECTED_CASES=201
# kty 가 판정 줄 끊김으로 판정불가를 보는 줄 사이(초) · 정지를 본 뒤 정지
# 명령을 돌리는 여유(초) — README 「정지 시한의 셈」 과 같은 값이다.
readonly WATCH_GAP_SECONDS=10
readonly STOP_COMMAND_SECONDS=12
# 블록을 표준 입력으로 넣는 대화형 zsh 의 옵션(터미널 없음 · 있음 두 사례가
# 같이 쓴다). -s 가 없으면 제어 터미널이 있을 때 zsh 가 표준 입력 대신
# 터미널에서 읽어 멈춘다.
readonly ZSH_STDIN_FLAGS=(-f -i -s)
# 대화형 zsh 사례가 정의를 확인하는 판정 함수(README 블록이 정의하는 전부).
readonly ZSH_FUNCTIONS=(
  workers_iso
  workers_events
  workers_started_at
  workers_queue_depth
  workers_receive_verdict
  workers_receive_watch
  workers_nodone_verdict
  workers_start_log_verdict
  workers_empty_receive_verdict
  workers_thumbnail_verdict
)

failures=0
passes=0
skips=0
tmp_root=""
case_dir=""
readme_text=""
# README 블록 하나씩(ANCHORS 차례) — 대화형 zsh 의 블록별 붙여 넣기에 쓴다.
readme_blocks=()
# README 「다시 띄우기」 블록(언어 없는 블록 — 판정 함수를 부르므로
# readme_text 에 넣지 않는다). 변이는 이 블록에도 건다.
restart_block=""
# README 「사진 반복」 블록(언어 없는 블록 — 위와 같은 까닭). 변이는 이
# 블록에도 건다.
loop_block=""
# 대화형 zsh 사례에서 zsh 를 띄울 환경(env -i …) — new_zsh_case 가 둔다.
zsh_env=()
status=0
# quiet 이면 사례 결과를 적지 않고 실패만 센다(변이 묶음).
quiet=""
suite_failures=0

#######################################
# 임시 디렉터리 뿌리를 지운다(EXIT 트랩).
# Globals:
#   tmp_root
#######################################
remove_tmp_root() {
  if [[ -n "${tmp_root}" ]]; then
    rm -rf -- "${tmp_root:?}"
  fi
}

#######################################
# 사례 하나의 실패를 알리고 센다.
# Globals:
#   failures, suite_failures, quiet
# Arguments:
#   실패 설명.
#######################################
fail() {
  suite_failures=$((suite_failures + 1))
  if [[ -n "${quiet}" ]]; then
    return 0
  fi
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

#######################################
# 사례 하나의 통과를 적고 센다.
# Globals:
#   passes, quiet
# Arguments:
#   사례 이름.
#######################################
pass() {
  if [[ -n "${quiet}" ]]; then
    return 0
  fi
  printf 'ok: %s\n' "$1"
  passes=$((passes + 1))
}

#######################################
# 사례 하나를 건너뛰었다고 적고 센다.
# Globals:
#   skips
# Arguments:
#   사례 이름(건너뛴 이유 포함).
#######################################
skip() {
  printf 'skip: %s\n' "$1"
  skips=$((skips + 1))
}

#######################################
# README 에서 줄 머리가 같은 줄을 품은 코드 블록 하나의 본문을 낸다. 울타리는
# 줄 머리의 ``` 끼리 짝짓고, 여는 줄이 「```<언어>」 인 블록만 본다.
# Arguments:
#   줄 머리, 언어(없으면 shell · 빈 글자면 언어 없는 블록).
# Returns:
#   0 / 1 그 블록이 하나가 아님.
#######################################
readme_block() {
  awk -v anchor="$1" -v open="\`\`\`${2-shell}" '
    /^```/ {
      if (!fenced) {
        fenced = 1
        inside = ($0 == open)
        body = ""
        hit = 0
        next
      }
      fenced = 0
      if (inside && hit) { found++; out = body }
      inside = 0
      next
    }
    inside {
      body = body $0 "\n"
      if (index($0, anchor) == 1) { hit = 1 }
    }
    END { if (found != 1) { exit 1 } printf "%s", out }' "${README}"
}

#######################################
# ANCHORS 의 블록을 차례대로 이어 readme_text 에 두고, 하나씩 readme_blocks
# 에도 둔다. 「다시 띄우기」 블록은 restart_block 에, 「사진 반복」 블록은
# loop_block 에 둔다.
# Globals:
#   readme_text, readme_blocks, restart_block, loop_block
# Returns:
#   0 / 1 블록 하나라도 못 찾음.
#######################################
load_readme() {
  local anchor block
  readme_text=""
  readme_blocks=()
  # shellcheck disable=SC2016
  if ! restart_block="$(readme_block 'case "${started-}" in' '')"; then
    fail 'README 에 「다시 띄우기」 블록(case "${started-}" in)이 하나가 아님'
    return 1
  fi
  # shellcheck disable=SC2016
  if ! loop_block="$(readme_block "${LOOP_ANCHOR}" '')"; then
    fail "README 에 「사진 반복」 블록(${LOOP_ANCHOR})이 하나가 아님"
    return 1
  fi
  for anchor in "${ANCHORS[@]}"; do
    if ! block="$(readme_block "${anchor}")"; then
      fail "README 에 「${anchor}」 블록이 하나가 아님"
      return 1
    fi
    readme_text+="${block}"$'\n'
    readme_blocks+=("${block}"$'\n')
  done
}

#######################################
# 새 사례 디렉터리(resp/ · tmp/)를 만든다.
# Globals:
#   tmp_root, case_dir
#######################################
new_case() {
  case_dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" &&
    mkdir -- "${case_dir}/resp" "${case_dir}/tmp"
}

#######################################
# 응답 파일을 쓴다(표준 입력 그대로).
# Globals:
#   case_dir
# Arguments:
#   응답 파일 이름.
#######################################
resp() {
  cat >"${case_dir}/resp/$1"
}

#######################################
# 가짜 aws — 인자를 calls 에 적고 resp/ 의 응답을 낸다(머리 주석). 사례에
# 시계(clock)가 있으면 fake_aws_clock 의 조회 시간 · 한 번 실패를 따르고, lag 가
# 있으면 로그 사건을 수집 지연만큼 늦게 보인다.
# Globals:
#   case_dir
# Arguments:
#   aws 인자.
#######################################
fake_aws() {
  local key="$1.$2" arg want="" value timed_fail=""
  printf '%s\n' "$*" >>"${case_dir}/calls"
  for arg in "$@"; do
    if [[ -n "${want}" ]]; then
      case "${want}" in
        url) value="${arg##*/}" ;;
        dims) value="${arg##*Value=}" ;;
        *) value="${arg}" ;;
      esac
      key+=".${value//[^A-Za-z0-9._-]/_}"
      want=""
    fi
    case "${arg}" in
      --log-group-name | --queue-name | --metric-name | --attribute-names | \
        --next-token)
        want=plain
        ;;
      --queue-url) want=url ;;
      --dimensions) want=dims ;;
    esac
  done
  if ! fake_aws_clock "$@"; then
    timed_fail=1
  fi
  if [[ -e "${case_dir}/resp/${key}.fail" || -n "${timed_fail}" ]]; then
    printf 'An error occurred (Throttling) when calling %s\n' "$2" >&2
    return 254
  fi
  if [[ ! -e "${case_dir}/resp/${key}" ]]; then
    printf '가짜 aws: 응답 없음 %s\n' "${key}" >&2
    return 255
  fi
  if [[ "${key}" == logs.filter-log-events.* && -e "${case_dir}/lag" ]]; then
    # 수집 지연 — 호출을 시작한 시각에서 lag 초 앞까지의 사건만 보인다.
    jq --argjson upto "$((($(cat -- "${case_dir}/called") - $(cat -- \
      "${case_dir}/lag")) * 1000))" \
      '.events |= map(select(.timestamp <= $upto))' "${case_dir}/resp/${key}"
    return
  fi
  cat -- "${case_dir}/resp/${key}"
}

#######################################
# 가짜 aws 의 조회 시간을 「망 쪽 기다림 · 상한 밖 시간」 두 수(초)로 낸다.
# delays 가 있으면 그 첫 줄(「기다림」 또는 「기다림+상한 밖」)을 꺼내 쓰고,
# 비었으면 delay, 그것도 없으면 0 이다. 상한 밖 시간은 CLI 기동 · DNS 처럼
# --cli-*-timeout 이 자르지 못하는 몫이다.
# Globals:
#   case_dir
#######################################
fake_aws_wait() {
  local entry=0
  if [[ -s "${case_dir}/delays" ]]; then
    entry="$(head -n 1 -- "${case_dir}/delays")"
    tail -n +2 -- "${case_dir}/delays" >"${case_dir}/delays.rest"
    mv -- "${case_dir}/delays.rest" "${case_dir}/delays"
  elif [[ -e "${case_dir}/delay" ]]; then
    entry="$(cat -- "${case_dir}/delay")"
  fi
  if [[ "${entry}" == *+* ]]; then
    printf '%s %s\n' "${entry%%+*}" "${entry#*+}"
  else
    printf '%s 0\n' "${entry}"
  fi
}

#######################################
# 가짜 aws 의 시계 효과 — 호출을 시작한 시각을 called 에 적고, 조회 시간만큼
# 시계를 옮긴다. 조회 시간은 awscli 의 상한을 따른다: 시도마다 연결
# (--cli-connect-timeout) + 읽기(--cli-read-timeout) 초가 상한(없으면 각 60 —
# CLI 기본)이고, 시도 수는 AWS_MAX_ATTEMPTS(없으면 3 — CLI 기본)다. 망 쪽
# 기다림이 시도 상한을 넘으면 시도마다 상한까지 걸린 뒤 실패하고, 실패하는
# 호출(fail_from 의 시각 이후 첫 호출 — 한 번)은 시도마다 기다림만큼 걸린다.
# 상한 밖 시간(over)은 늘 더한다.
# Globals:
#   case_dir
# Arguments:
#   aws 인자.
# Returns:
#   0 / 1 이번 호출을 조회 실패로 낸다.
#######################################
fake_aws_clock() {
  local now wait over cap connect=60 read_timeout=60 want="" arg spent failed=""
  if [[ ! -e "${case_dir}/clock" ]]; then
    return 0
  fi
  now="$(cat -- "${case_dir}/clock")"
  printf '%s\n' "${now}" >"${case_dir}/called"
  for arg in "$@"; do
    case "${want}" in
      connect) connect="${arg}" ;;
      read_timeout) read_timeout="${arg}" ;;
    esac
    want=""
    case "${arg}" in
      --cli-connect-timeout) want=connect ;;
      --cli-read-timeout) want=read_timeout ;;
    esac
  done
  cap=$((connect + read_timeout))
  read -r wait over < <(fake_aws_wait)
  if [[ -e "${case_dir}/fail_from" ]] &&
    ((now >= $(cat -- "${case_dir}/fail_from"))); then
    rm -f -- "${case_dir}/fail_from"
    failed=1
  fi
  if ((wait > cap)); then
    spent=$((${AWS_MAX_ATTEMPTS:-3} * cap))
    failed=1
  elif [[ -n "${failed}" ]]; then
    spent=$((${AWS_MAX_ATTEMPTS:-3} * wait))
  else
    spent="${wait}"
  fi
  printf '%s\n' "$((now + spent + over))" >"${case_dir}/clock"
  [[ -z "${failed}" ]]
}

#######################################
# 가짜 date — `date -u +%s` 에만 사례의 시계(clock)를 낸다. 시계가 없는
# 사례(판정 함수를 한 번 부르는 것)는 T0 하루 뒤로 고정한다 — 조회의 끝
# 시각과 쪽 따라가기의 시간 상한에만 쓰이고, 고정이라 상한에 걸리지 않는다.
# Globals:
#   case_dir, T0
# Arguments:
#   date 인자.
#######################################
fake_date() {
  if [[ "$*" != "-u +%s" ]]; then
    printf '가짜 date: 모르는 호출 %s\n' "$*" >&2
    return 255
  fi
  if [[ ! -e "${case_dir}/clock" ]]; then
    echo "$((T0 + 86400))"
    return 0
  fi
  cat -- "${case_dir}/clock"
}

#######################################
# 가짜 sleep — 기다리지 않고 시계를 그만큼 옮기며, 초를 sleeps 에 적는다.
# heal 이 있으면 조회 실패 응답(resp/*.fail)을 지운다(다음 주기에 풀린 실패).
# 200번 넘게 불리면 하위 셸을 끝낸다(종료 97).
# Globals:
#   case_dir
# Arguments:
#   초.
#######################################
fake_sleep() {
  printf '%s\n' "$1" >>"${case_dir}/sleeps"
  # 끝나지 않는 반복(변이 · 옛 꼴)이 시험을 멈추지 않게 200번째에서 끊는다.
  if (($(wc -l <"${case_dir}/sleeps") > 200)); then
    printf '가짜 sleep: 200번 넘게 기다림 — 반복이 끝나지 않음\n' >&2
    exit 97
  fi
  printf '%s\n' "$(($(cat -- "${case_dir}/clock") + $1))" >"${case_dir}/clock"
  if [[ -e "${case_dir}/heal" ]]; then
    rm -f -- "${case_dir}"/resp/*.fail
  fi
}

#######################################
# 뽑은 README 블록을 하위 셸에서 읽고 함수를 부른다. 결과는 사례의 out · err ·
# status 다.
# Globals:
#   case_dir, readme_text, status
# Arguments:
#   부를 함수와 인자.
#######################################
run_readme() {
  status=0
  # 환경 · aws 는 이 하위 셸 안에서만 바꾸고 eval 한 README 블록이 쓴다(의도).
  # shellcheck disable=SC2030,SC2329
  (
    export TMPDIR="${case_dir}/tmp"
    # 시도 수는 README 블록이 정한다 — 시험을 돌리는 셸의 값을 들이지 않는다.
    unset AWS_MAX_ATTEMPTS AWS_RETRY_MODE
    cd -- "${case_dir}" || exit 99
    eval "${readme_text}" || exit 98
    aws() { fake_aws "$@"; }
    date() { fake_date "$@"; }
    sleep() { fake_sleep "$@"; }
    "$@"
  ) >"${case_dir}/out" 2>"${case_dir}/err" || status=$?
}

#######################################
# 판정 줄 · 종료 코드를 본다.
# Globals:
#   case_dir, status
# Arguments:
#   사례 이름, 기대 마지막 줄 머리, 기대 종료 코드.
#######################################
expect() {
  local name="$1" last="$2" want="$3" got
  got="$(tail -n 1 -- "${case_dir}/out")"
  if [[ "${got}" != "${last}"* ]]; then
    fail "${name}: 마지막 줄 「${got}」 (기대 「${last}…」) / 오류: $(LC_ALL=C \
      tr '\n' '|' <"${case_dir}/err")"
    return 1
  fi
  if [[ "${status}" -ne "${want}" ]]; then
    fail "${name}: 종료 코드 ${status} (기대 ${want})"
    return 1
  fi
  pass "${name}"
}

#######################################
# 감시 반복의 판정 줄 · 종료 코드를 본다. 마지막 줄은 「HH:MM:SS UTC 」 시각으로
# 시작해야 하고, 시각을 뗀 나머지를 expect 와 같이 본다.
# Globals:
#   case_dir, status
# Arguments:
#   사례 이름, 기대 마지막 줄 머리(시각 뒤), 기대 종료 코드.
#######################################
expect_watch() {
  local name="$1" last="$2" want="$3" got
  got="$(tail -n 1 -- "${case_dir}/out")"
  if [[ "${got}" != [0-9][0-9]:[0-9][0-9]:[0-9][0-9]' UTC '* ]]; then
    fail "${name}: 마지막 줄에 시각이 없음 「${got}」"
    return 1
  fi
  if [[ "${got#* UTC }" != "${last}"* ]]; then
    fail "${name}: 마지막 줄 「${got}」 (기대 「HH:MM:SS UTC ${last}…」)"
    return 1
  fi
  if [[ "${status}" -ne "${want}" ]]; then
    fail "${name}: 종료 코드 ${status} (기대 ${want})"
    return 1
  fi
  pass "${name}"
}

#######################################
# 로그 사건 응답(filter-log-events)을 쓴다. 줄마다 「T0 뒤 초 · 메시지」 다.
# 메시지는 render 이미지의 logback 줄 꼴(머리 · 스레드 · 로거 뒤 이벤트)이다.
# Globals:
#   case_dir
# Arguments:
#   로그 그룹, 「초 메시지」 줄 ….
#######################################
log_resp() {
  local group="$1" line
  shift
  {
    printf '{"events": ['
    local sep=""
    for line in "$@"; do
      printf '%s{"timestamp": %s, "message": "2026-11-10 WARN 1 --- [q] %s"}' \
        "${sep}" "$(((T0 + ${line%% *}) * 1000))" "${line#* }"
      sep=", "
    done
    printf ']}\n'
  } | resp "logs.filter-log-events.${group}"
}

#######################################
# 큐 응답(get-queue-url · 보이는 깊이 · 숨긴 깊이)을 쓴다.
# Arguments:
#   큐 이름, 보이는 깊이, 숨긴 깊이.
#######################################
queue_resp() {
  printf 'https://sqs.ap-northeast-2.amazonaws.com/%s/%s\n' "${FAKE_ACCOUNT}" \
    "$1" |
    resp "sqs.get-queue-url.$1"
  printf '%s\n' "$2" |
    resp "sqs.get-queue-attributes.$1.ApproximateNumberOfMessages"
  printf '%s\n' "$3" |
    resp "sqs.get-queue-attributes.$1.ApproximateNumberOfMessagesNotVisible"
}

#######################################
# 지표 응답(get-metric-statistics)을 쓴다. 줄마다 「T0 뒤 초 · 값」 이다.
# Arguments:
#   지표 이름, 큐 이름, 통계(Sum · Maximum), 「초 값」 줄 ….
#######################################
metric_resp() {
  local metric="$1" queue="$2" stat="$3" line sep=""
  shift 3
  {
    printf '{"Label": "%s", "Datapoints": [' "${metric}"
    for line in "$@"; do
      printf '%s{"Timestamp": "%s", "%s": %s}' "${sep}" \
        "$(jq -rn --argjson t "$((T0 + ${line%% *}))" \
          '$t | todate | sub("Z$"; "+00:00")')" "${stat}" "${line#* }"
      sep=", "
    done
    printf ']}\n'
  } | resp "cloudwatch.get-metric-statistics.${metric}.${queue}"
}

#######################################
# 지표 응답의 시각을 서울 시간대 꼴(+09:00 — 이 맥의 aws CLI 출력)로 바꾼다.
# 같은 순간이다.
# Globals:
#   case_dir
# Arguments:
#   지표 이름, 큐 이름.
#######################################
metric_to_kst() {
  local file="${case_dir}/resp/cloudwatch.get-metric-statistics.$1.$2"
  jq '.Datapoints |= map(.Timestamp |= (sub("\\+00:00$"; "Z")
    | fromdateiso8601 + 32400 | todate | sub("Z$"; "+09:00")))' "${file}" \
    >"${case_dir}/kst" && mv -- "${case_dir}/kst" "${file}"
}

readonly G=pokeclip-prod-render
readonly RQ=pokeclip-prod-jobs-render
readonly TQ=pokeclip-prod-jobs-thumbnail
readonly UG=pokeclip-prod-upload
readonly UQ=pokeclip-prod-jobs-upload

#######################################
# 연결 시간 초과 꼴의 receive_failed 줄을 12초마다 낸다.
# Arguments:
#   줄 이름, 첫 초, 끝 초.
#######################################
timeouts() {
  local t
  for ((t = $2; t <= $3; t += 12)); do
    printf '%s %s.receive_failed err=Unable to execute HTTP request: ' "${t}" "$1"
    printf 'Connect timed out\n'
  done
}

#######################################
# 설계 3-6 의 다섯.
#######################################
test_design_cases() {
  local lines=()
  # 정상 6분 렌더: 기동 0 · 시작 300 · 완료 660 · 뒤 대기 주문(깊이 1 · 나이
  # 25분). 지금 = 기동 30분 뒤.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '300 render.started jobId=j1 clipId=c1 attempt=1' \
    '660 render.message_done messageId=m1 receiveCount=1 disposition=DELETE' &&
    queue_resp "${RQ}" 1 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 1500'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 1800))"
  expect '설계: 정상 6분 렌더 = 수신 통과' '수신: 통과' 0
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect '설계: 정상 6분 렌더 + 대기 주문 = 무완료 통과' '무완료 render: 통과' 0

  # 연결 시간 초과 반복: 12초마다 72초 동안.
  new_case
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 80))"
  expect '설계: 연결 시간 초과 72초 = 수신 정지' '수신: 정지' 1

  new_case
  lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 48)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect '설계: 연결 시간 초과 48초 = 수신 통과' '수신: 통과' 0

  # 빈 큐 정상 + 지표 10분 지연: 3분 로그 = 잠정 통과, 5분 = 대기(점 없음),
  # 11분 = 통과(기동 뒤 분의 점).
  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 0 0 &&
    metric_resp NumberOfEmptyReceives "${RQ}" Sum
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect '설계: 빈 큐 정상 = 로그 잠정 통과' '로그 render: 잠정 통과' 0
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 300))"
  expect '설계: 빈 큐 + 지표 지연 5분 = 대기' '지표 '"${RQ}"': 대기' 3
  metric_resp NumberOfEmptyReceives "${RQ}" Sum '60 3' '120 3'
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 660))"
  expect '설계: 빈 큐 + 지표 10분 뒤 도착 = 통과' '지표 '"${RQ}"': 통과' 0

  # 빈 큐 연결 불능.
  new_case
  lines=('0 render.worker_started queue=x')
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 5 77)
  log_resp "${G}" "${lines[@]}"
  queue_resp "${RQ}" 0 0
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 180))"
  expect '설계: 빈 큐 연결 불능 = 수신 정지' '수신: 정지' 1
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect '설계: 빈 큐 연결 불능 = 로그 판정 실패' '로그 render: 실패' 1

  # DELAY 로 숨긴 메시지만: 보이는 0 · 숨긴 3 · 나이 40분 · 완료 0.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 0 3 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 2400'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect '설계: DELAY 숨김만 = 무완료 통과' '무완료 render: 통과' 0
}

#######################################
# 수신 불능 판정의 경계.
#######################################
test_receive() {
  local lines=() line
  # 렌더 줄 0 초 · 사진 줄 70 초 — 줄마다 한 번뿐이라 잇지 않는다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '70 thumbnail.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 80))"
  expect 'receive: 다른 줄 이름은 잇지 않음' '수신: 통과' 0

  # 같은 줄이지만 틈이 190초(사이에 성공한 수신이 있었다고 본다).
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '190 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 200))"
  expect 'receive: 180초 넘는 틈은 끊김' '수신: 통과' 0

  # 틈 100초도 이어진 실패다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '100 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 110))"
  expect 'receive: 100초 틈 두 번 = 정지' '수신: 정지' 1

  # 읽기 시간 초과 꼴의 실측 간격(125.3초 — SDK LEGACY 시도 4회 · 소켓 30초 ·
  # 재시도 대기 · 루프 5초 쉼)으로 이어진 실패.
  new_case && log_resp "${G}" '0 render.receive_failed err=Read timed out' \
    '126 render.receive_failed err=Read timed out' \
    '252 render.receive_failed err=Read timed out' \
    '378 render.receive_failed err=Read timed out'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 390))"
  expect 'receive: 126초 간격(실측) 연속 = 정지' '수신: 정지' 1

  # 같은 꼴의 최악 간격(약 134초).
  new_case && log_resp "${G}" '0 render.receive_failed err=Read timed out' \
    '134 render.receive_failed err=Read timed out' \
    '268 render.receive_failed err=Read timed out'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 280))"
  expect 'receive: 134초 간격(상한) 연속 = 정지' '수신: 정지' 1

  # 실패 사이에 같은 줄의 시작 · 완료가 있으면 받기가 그 사이에 됐다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '10 render.started jobId=j1 clipId=c1 attempt=1' \
    '145 render.message_done messageId=m1 receiveCount=1 disposition=DELETE' \
    '160 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 170))"
  expect 'receive: 사이의 시작 · 완료 줄은 이어짐을 끊음' '수신: 통과' 0

  # 사이 신호 없는 두 실패(틈 170초 — SDK 최악 134초와 180초 사이)는 잇는다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '170 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 175))"
  expect 'receive: 사이 신호 없는 고립 실패 0 · 170초 = 정지' '수신: 정지' 1

  # 사진 줄의 완료는 렌더 줄의 이어짐을 끊지 않는다(큐마다 스레드가 다름).
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '30 thumbnail.message_done messageId=m1 receiveCount=1 disposition=DELETE' \
    '72 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 80))"
  expect 'receive: 다른 줄의 완료는 끊지 않음' '수신: 정지' 1

  # 업로드 줄은 upload.begin(유튜브로 보내기 시작)도 받기가 됐다는 신호다.
  new_case && log_resp "${UG}" '0 upload.receive_failed err=x' \
    '20 upload.begin uploadId=1 bytes=10 resuming=false tokenValid=true' \
    '100 upload.receive_failed err=x'
  run_readme workers_receive_verdict "${UG}" "${T0}" "$((T0 + 110))"
  expect 'receive: upload.begin 도 끊음' '수신: 통과' 0

  # 지난 장애(0–72초)는 회복된 뒤(지금 3600초) 정지가 아니다.
  new_case
  lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 3600))"
  expect 'receive: 회복한 지난 장애(마지막 실패 3528초 전) = 통과' '수신: 통과' 0

  # 지금 epoch 를 빠뜨리면(옛 두 인자 꼴) 늘 통과가 되지 않게 판정불가다.
  new_case
  lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}"
  expect 'receive: 지금 epoch 없음 = 판정불가' '수신: 판정불가' 2

  new_case && : >"${case_dir}/resp/logs.filter-log-events.${G}.fail"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect 'receive: 조회 실패 = 판정불가' '수신: 판정불가' 2

  # 기동 전 workers_started_at 이 낸 판정불가 문장을 시작 epoch 로 넘긴 꼴.
  new_case && log_resp "${G}"
  run_readme workers_receive_verdict "${G}" \
    '기동 render: 판정불가 — 조회 실패' "$((T0 + 60))"
  expect 'receive: 시작 epoch 가 숫자가 아님 = 판정불가' \
    '수신: 판정불가 — 시작 epoch(둘째 인자)가 숫자가 아님' 2
}

#######################################
# 무완료 판정의 경계.
#######################################
test_nodone() {
  # 보이는 2 · 나이 25분 · 완료는 창(지금 - 20분) 밖에만.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '100 render.message_done messageId=m0 receiveCount=1 disposition=DELETE' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 1500'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 20분 무완료(창 밖 완료) = 정지' '무완료 render: 정지' 1

  # 사진 줄의 failed 도 완료로 센다(렌더 줄의 완료는 세지 않음).
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '1500 render.message_done messageId=m0 receiveCount=1 disposition=DELETE' \
    '1600 thumbnail.failed kind=clip targetId=1 retryable=true code=INTERNAL' &&
    queue_resp "${TQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${TQ}" Maximum '1740 1500'
  run_readme workers_nodone_verdict "${G}" thumbnail "${TQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 그 줄의 failed 는 완료' '무완료 thumbnail: 통과' 0

  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '540 1500'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 600))"
  expect 'nodone: 기동 20분 전 = 대기' '무완료 render: 대기' 3

  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 900'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 나이 20분 이하 = 통과' '무완료 render: 통과' 0

  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 나이 지표 없음 = 판정불가' '무완료 render: 판정불가' 2

  new_case && queue_resp "${RQ}" 2 0 &&
    : >"${case_dir}/resp/sqs.get-queue-attributes.${RQ}.ApproximateNumberOfMessages.fail"
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 깊이 조회 실패 = 판정불가' '무완료 render: 판정불가' 2
}

#######################################
# 로그 판정(3분)의 경계.
#######################################
test_start_log() {
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '30 render.started_rejected jobId=j1 status=503' &&
    queue_resp "${RQ}" 1 0
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect 'start-log: 깊이 > 0 · 시작 줄 없음 = 실패' '로그 render: 실패' 1

  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '30 render.started jobId=j1 clipId=c1 attempt=1' &&
    queue_resp "${RQ}" 1 0
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect 'start-log: 긴 렌더의 시작 줄 = 잠정 통과' '로그 render: 잠정 통과' 0

  # 업로드 하나가 진행 중(begin 뒤 조각 전송 — 조각 하나 최대 5분)이고 다음
  # 주문이 보이는 깊이에 있다.
  new_case && log_resp "${UG}" '0 upload.worker_started queue=x' \
    '30 upload.begin uploadId=1 bytes=10 resuming=false tokenValid=true' &&
    queue_resp "${UQ}" 1 0
  run_readme workers_start_log_verdict "${UG}" upload "${UQ}" "${T0}" \
    "$((T0 + 180))"
  expect 'start-log: 진행 중 업로드의 upload.begin = 잠정 통과' \
    '로그 upload: 잠정 통과' 0

  # 첫 receive_failed 는 기동 뒤 약 120.5초에 찍힌다 — 150초는 아직 이르다.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 0 0
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 150))"
  expect 'start-log: 3분 전(150초) = 대기' '로그 render: 대기' 3
}

#######################################
# 지표 판정(20분)과 기동 시각의 경계.
#######################################
test_empty_and_started() {
  local stamp
  # 기동(30초) 앞 분(0초)의 점은 옛 태스크의 빈 수신일 수 있어 세지 않는다.
  new_case && metric_resp NumberOfEmptyReceives "${RQ}" Sum '0 5' '60 0'
  run_readme workers_empty_receive_verdict "${RQ}" "$((T0 + 30))" \
    "$((T0 + 600))"
  expect 'empty: 기동 앞 분의 점은 세지 않음' '지표 '"${RQ}"': 대기' 3

  new_case && metric_resp NumberOfEmptyReceives "${RQ}" Sum
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 1260))"
  expect 'empty: 20분 넘어 점 없음 = 판정불가' '지표 '"${RQ}"': 판정불가' 2

  new_case &&
    : >"${case_dir}/resp/cloudwatch.get-metric-statistics.NumberOfEmptyReceives.${RQ}.fail"
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 600))"
  expect 'empty: 조회 실패 = 판정불가' '지표 '"${RQ}"': 판정불가' 2

  # 이 맥의 aws CLI 는 시각을 로컬 시간대(+09:00)로 낸다(2026-11-10 실측).
  # 기동(30초) 앞 분의 점은 여전히 세지 않고, 뒤 분의 점은 센다.
  new_case && metric_resp NumberOfEmptyReceives "${RQ}" Sum '0 5' '60 0' &&
    metric_to_kst NumberOfEmptyReceives "${RQ}"
  run_readme workers_empty_receive_verdict "${RQ}" "$((T0 + 30))" \
    "$((T0 + 600))"
  expect 'empty: +09:00 시각 — 기동 앞 분의 점은 세지 않음' \
    '지표 '"${RQ}"': 대기' 3
  new_case && metric_resp NumberOfEmptyReceives "${RQ}" Sum '0 5' '60 3' &&
    metric_to_kst NumberOfEmptyReceives "${RQ}"
  run_readme workers_empty_receive_verdict "${RQ}" "$((T0 + 30))" \
    "$((T0 + 600))"
  expect 'empty: +09:00 시각 — 기동 뒤 분의 점 = 통과' '지표 '"${RQ}"': 통과' 0

  # 시각 꼴을 모르면 대기로 흘려보내지 않고 판정불가다.
  new_case &&
    printf '%s\n' '{"Datapoints": [{"Timestamp": "11/10/2026 00:01",' \
      '"Sum": 3}]}' |
    resp "cloudwatch.get-metric-statistics.NumberOfEmptyReceives.${RQ}"
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 600))"
  expect 'empty: 모르는 시각 꼴 = 판정불가' '지표 '"${RQ}"': 판정불가' 2

  # 시 · 분 · 초에 쌍점이 없는 꼴과 범위 밖 오프셋은 꼴 검사에서 판정불가다
  # (r1 cx — 앞 꼴은 변환 오류가 대기로, 뒤 꼴은 통과로 흘렀다).
  for stamp in 2026-11-10T00000100Z 2026-11-10T00:01:00-99:99; do
    new_case &&
      printf '{"Datapoints": [{"Timestamp": "%s", "Sum": 3}]}\n' "${stamp}" |
      resp "cloudwatch.get-metric-statistics.NumberOfEmptyReceives.${RQ}"
    run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 600))"
    expect "empty: 틀린 시각 꼴 ${stamp} = 판정불가" \
      "지표 ${RQ}: 판정불가 — NumberOfEmptyReceives 조회 실패 · 시각 꼴 모름" 2
  done

  # 꼴은 맞는데 없는 날짜(11월 31일) — jq 가 12월 1일로 넘기므로 되돌려 보아
  # 변환 오류로 내고, 정상 false(대기)와 갈라 판정불가다.
  new_case &&
    printf '%s\n' '{"Datapoints": [{"Timestamp": "2026-11-31T00:01:00Z",' \
      '"Sum": 3}]}' |
    resp "cloudwatch.get-metric-statistics.NumberOfEmptyReceives.${RQ}"
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 600))"
  expect 'empty: 없는 날짜 2026-11-31 = 판정불가(변환 오류)' \
    "지표 ${RQ}: 판정불가 — 시각 변환 실패" 2

  new_case && log_resp "${G}" '5 render.receive_failed err=x' \
    '42 render.worker_started queue=x' '43 thumbnail.worker_started queue=y'
  run_readme workers_started_at "${G}" render "${T0}"
  expect 'started: 첫 worker_started 의 초' "$((T0 + 42))" 0

  new_case && log_resp "${G}" '43 thumbnail.worker_started queue=y'
  run_readme workers_started_at "${G}" render "${T0}"
  expect 'started: 없음 = 판정불가' '기동 render: 판정불가' 2

  # 감시 터미널에 apply_at 을 옮겨 적지 않은 꼴(빈 값).
  new_case && log_resp "${G}" '42 render.worker_started queue=x'
  run_readme workers_started_at "${G}" render ''
  expect 'started: apply 시작 epoch 가 숫자가 아님 = 판정불가' \
    '기동 render: 판정불가 — apply 시작 epoch(셋째 인자)가 숫자가 아님' 2
}

#######################################
# 사진 줄 결과 한 건(사진 · 처분 줄 둘)을 낸다.
# Arguments:
#   초, 결과(captured · failed), kind, targetId.
#######################################
thumb() {
  if [[ "$2" == captured ]]; then
    printf '%s thumbnail.captured kind=%s targetId=%s\n' "$1" "$3" "$4"
  else
    printf '%s thumbnail.failed kind=%s targetId=%s reason=Failed\n' "$1" \
      "$3" "$4"
  fi
  done_line "$1"
}

#######################################
# 사진 줄의 처리 결과 한 줄(captured · failed 밖의 줄)을 낸다 — 그 줄의
# message_done 은 done_line 이 낸다.
# Arguments:
#   초, 줄 이름(thumbnail. 뒤 — report_unavailable · invalid_job ·
#   target_gone · live_redelivered_dropped).
#######################################
thumb_line() {
  case "$2" in
    invalid_job) printf '%s thumbnail.invalid_job\n' "$1" ;;
    target_gone)
      printf '%s thumbnail.target_gone kind=live targetId=S-%s deleted=true\n' \
        "$1" "$1"
      ;;
    live_redelivered_dropped)
      printf '%s thumbnail.live_redelivered_dropped targetId=S-%s ' "$1" "$1"
      printf 'receiveCount=2\n'
      ;;
    *) printf '%s thumbnail.%s kind=live targetId=S-%s\n' "$1" "$2" "$1" ;;
  esac
}

#######################################
# 사진 줄의 처분 줄(message_done) 하나를 낸다.
# Arguments:
#   초.
#######################################
done_line() {
  printf '%s thumbnail.message_done messageId=m%s receiveCount=1 ' "$1" "$1"
  printf 'disposition=DELETE\n'
}

#######################################
# 사진 줄 판정(workers_thumbnail_verdict) — 지난 120초의 결과로 정지를 가른다.
# Globals:
#   case_dir, G, T0
#######################################
test_thumbnail() {
  local lines=() line t kind
  local permanent='retryable=false code=SOURCE_EXPIRED'
  # 2026-11-11 첫 소비 꼴 — 대기분을 몰아 처리하며 captured 다수 사이에
  # 11-08 옛 라이브 세션 둘의 kind=live 실패 넷(reason=Failed · 지움 · DLQ 0).
  new_case
  for ((t = 0; t < 60; t++)); do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      captured live "S-20261108-005851-${t}")
  done
  for t in 3 10 37 44; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      failed live "S-20261108-074601-${t}")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect 'thumb: 옛 kind=live 실패 넷 + captured 다수 = 통과' '사진: 통과' 0

  # 기동이 오래 전(창이 다 참)이고, 사진을 만들려다 못 만든 처리(clip 보고
  # 불능 — 라이브라 지움) 셋에 captured 0 이 120초 이어짐.
  new_case && log_resp "${G}" \
    "$(thumb_line 10 report_unavailable)" "$(done_line 10)" \
    "$(thumb_line 50 report_unavailable)" "$(done_line 50)" \
    "$(thumb_line 90 report_unavailable)" "$(done_line 90)"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 120))"
  expect 'thumb: 120초 지속 captured 0(못 만든 처리 셋) = 정지' \
    '사진: 정지 — captured 0 인 240초 구간에 못 만든 처리 3건' 1

  # 라이브 실패 한 줄과 그 message_done 만 — 라이브는 지우고 다음 분이 다시
  # 찍는다(창이 다 참).
  new_case && lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 10 failed \
    live S-1)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 120))"
  expect 'thumb: 라이브 실패 한 줄만 = 통과' '사진: 통과' 0

  # 기동 10초 뒤 라이브 실패 한 줄(위와 같은 줄) · 30초에 판정(r1 cx 재현 —
  # 창이 덜 참).
  new_case && log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 30))"
  expect 'thumb: 기동 직후 단일 라이브 실패 = 통과' '사진: 통과' 0

  # 기동 60초 안의 못 만든 처리 셋 — 창이 120초를 채우기 전에는 captured 0
  # 을 판정하지 않는다.
  new_case && log_resp "${G}" \
    "$(thumb_line 10 invalid_job)" "$(done_line 10)" \
    "$(thumb_line 20 invalid_job)" "$(done_line 20)" \
    "$(thumb_line 30 invalid_job)" "$(done_line 30)"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect 'thumb: 기동 직후 못 만든 처리 셋(창 미충족) = 통과' '사진: 통과' 0

  # 못 만든 처리 둘 — 최소 건수(3) 밑이라 정지가 아니다(창이 다 참).
  new_case && log_resp "${G}" \
    "$(thumb_line 10 invalid_job)" "$(done_line 10)" \
    "$(thumb_line 70 invalid_job)" "$(done_line 70)"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 120))"
  expect 'thumb: 못 만든 처리 둘(최소 3건 밑) = 통과' '사진: 통과' 0

  # 대상 삭제(target_gone — 사진을 올린 뒤 대상이 지워져 사진을 지움) 셋 ·
  # 다시 받은 라이브(live_redelivered_dropped — 찍지 않고 지움) 셋은 정상
  # 처리라 못 만든 처리로 세지 않는다. 두 줄은 CloudWatch 거르기 식에도 있어야
  # 조회에 담긴다(가짜 aws 는 거르지 않으므로 인자로 본다).
  for kind in target_gone live_redelivered_dropped; do
    new_case && log_resp "${G}" \
      "$(thumb_line 10 "${kind}")" "$(done_line 10)" \
      "$(thumb_line 50 "${kind}")" "$(done_line 50)" \
      "$(thumb_line 90 "${kind}")" "$(done_line 90)"
    run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" \
      "$((T0 + 120))"
    if ! grep -qF "?\"thumbnail.${kind}\"" "${case_dir}/calls"; then
      fail "thumb: ${kind} 셋 — 거르기 식에 thumbnail.${kind} 가 없음"
    else
      expect "thumb: ${kind} 셋 = 통과" '사진: 통과' 0
    fi
  done

  # 카드 · 클립 영구 실패(retryable=false — 원본이 저장소에 없음 · 지움 ·
  # DLQ 아님)는 따로 떨어진 것이면 기록만 하고, 120초 안 3건 이상에 captured
  # 0 이면 정지한다(kty 결정 「가-1」 2026-11-11). 창이 다 참.
  new_case && log_resp "${G}" "50 thumbnail.failed kind=card targetId=50 \
${permanent}" "$(done_line 50)"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 120))"
  expect 'thumb: 카드 영구 실패 하나 = 통과(기록만)' \
    '사진: 통과 — 지난 120초 captured 0건 · live 실패 0건 · 카드 · 클립 영구 실패 1건' 0

  new_case && lines=()
  for t in 10 50 90; do
    lines+=(
      "${t} thumbnail.failed kind=card targetId=${t} ${permanent}"
      "$(done_line "${t}")"
    )
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 120))"
  expect 'thumb: 카드 영구 실패 셋 · captured 0 = 정지(가-1)' \
    '사진: 정지 — 150초 전부터 120초 동안 카드 · 클립 영구 실패 3건에 captured 0' 1

  # 같은 셋이 기동 뒤 120초가 차기 전(기동 0초 · 지금 100초) — 창이 다 차야
  # 정지한다.
  new_case && log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 100))"
  expect 'thumb: 기동 직후 카드 영구 실패 셋(창 미충족) = 통과' '사진: 통과' 0

  # 같은 셋 사이(50초)에 captured — 셋을 품는 120초 구간마다 captured 가 있다.
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 50 captured \
    clip 500)
  new_case && log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 120))"
  expect 'thumb: 카드 영구 실패 셋 + 사이의 captured = 통과' '사진: 통과' 0

  # 잠깐 실패를 retryable=true 로 찍은 줄(S3 403 등 — LEAVE → DLQ 손실)은
  # 1건이어도 정지.
  new_case && lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 1 captured \
    live S-1)
  log_resp "${G}" "${lines[@]}" \
    '9 thumbnail.failed kind=clip targetId=77 retryable=true code=INTERNAL' \
    "$(done_line 9)"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect 'thumb: 클립 잠깐 실패(retryable=true) 한 건 = 정지' '사진: 정지' 1

  # live 실패가 captured 보다 많음(넷 대 둘).
  new_case && lines=()
  for t in 1 2; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      captured live "S-${t}")
  done
  for t in 3 4 5 6; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      failed live "S-${t}")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect 'thumb: live 실패가 captured 보다 많음 = 정지' '사진: 정지' 1

  # 카드 · 클립 실패는 한 건이어도 정지(다시 받혀 DLQ 로 가는 손실).
  for kind in card clip; do
    new_case && lines=()
    for t in 1 2 3; do
      while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
        captured live "S-${t}")
    done
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb 9 failed \
      "${kind}" 77)
    log_resp "${G}" "${lines[@]}"
    run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 60))"
    expect "thumb: kind=${kind} 실패 한 건 = 정지" '사진: 정지' 1
  done

  # 창(지난 120초) 밖의 실패는 세지 않는다 — 0–4초 live 실패 다섯 · 200–202초
  # captured 셋 · 지금 300초.
  new_case && lines=()
  for t in 0 1 2 3 4; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      failed live "S-${t}")
  done
  for t in 200 201 202; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      captured live "S-${t}")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 300))"
  expect 'thumb: 창 밖의 지난 실패는 세지 않음 = 통과' '사진: 통과' 0

  new_case && : >"${case_dir}/resp/logs.filter-log-events.${G}.fail"
  run_readme workers_thumbnail_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect 'thumb: 조회 실패 = 판정불가' '사진: 판정불가' 2

  new_case && log_resp "${G}"
  run_readme workers_thumbnail_verdict "${G}" '' "$((T0 + 60))"
  expect 'thumb: 기동 epoch 가 숫자가 아님 = 판정불가' '사진: 판정불가' 2

  test_thumbnail_spans
}

#######################################
# 사진 줄 판정이 한 번의 120초 창이 아니라 지난 240초 안의 어느 구간이든
# 조건이 섰는지 보는지 — 판정 한 번으로 본다(반복은 test_thumbnail_loop).
# Globals:
#   case_dir, G, T0
#######################################
test_thumbnail_spans() {
  local lines=() line t
  # r2 cx 시간열 — 0초 captured 뒤 1 · 2 · 3초 못 만든 처리(주문서 거절)와
  # 조용함. 150초의 120초 창([30, 150])은 비었지만 captured 0 인 구간
  # [1, 150] 이 120초를 넘고 셋을 품는다.
  new_case && lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 0 captured \
    live S-0)
  for t in 1 2 3; do
    lines+=("$(thumb_line "${t}" invalid_job)" "$(done_line "${t}")")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 150))"
  expect 'thumb: r2 cx — captured 뒤 못 만든 처리 셋 · 조용함 = 정지' \
    '사진: 정지 — captured 0 인 149초 구간에 못 만든 처리 3건' 1

  # 같은 셋 뒤 130초에 captured — captured 로 닫힌 지난 구간 [1, 129] 도 본다.
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 130 \
    captured live S-130)
  new_case && log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 145))"
  expect 'thumb: captured 로 닫힌 지난 구간(128초)의 못 만든 처리 셋 = 정지' \
    '사진: 정지 — captured 0 인 128초 구간에 못 만든 처리 3건' 1

  # 라이브 세션 하나가 통째로 실패(61초 간격 — 어느 120초 창에도 둘뿐).
  new_case && lines=()
  for t in 0 61 122; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      failed live "S-${t}")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 132))"
  expect 'thumb: 라이브 세션 하나 통째 실패(61초 간격 셋) = 정지' \
    '사진: 정지 — captured 0 인 240초 구간에 live 실패 3건' 1

  # 세션 끝의 라이브 실패 둘(2026-11-11 옛 세션 꼴 — 세션마다 둘) 뒤 조용함.
  new_case && lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb -30 captured \
    live S-30)
  for t in 0 60; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      failed live "S-${t}")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 200))"
  expect 'thumb: 세션 끝 라이브 실패 둘 뒤 조용함 = 통과' '사진: 통과' 0

  # 라이브 실패 넷(0–3초)이 지금 120초 창([-20, 100])에서는 captured 열에
  # 묻히지만, 그 앞 120초 구간([-118, 2])에서는 captured 하나보다 많다.
  new_case && lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb -80 captured \
    live S-80)
  for t in 0 1 2 3; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      failed live "S-${t}")
  done
  for ((t = 30; t < 40; t++)); do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      captured live "S-${t}")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 100))"
  expect 'thumb: 앞 120초 구간의 라이브 실패 비율 = 정지' \
    '사진: 정지 — 218초 전부터 120초 동안 live 실패 3건이 captured 1건보다 많음' 1

  # 볼 창(지난 240초 — 60초부터)의 바로 앞 59초에 찍힌 captured 셋의
  # message_done 이 60초에 찍힘. 창 앞 5초까지 읽어 짝을 지으므로 못 만든
  # 처리가 아니다(짝 없이 세면 [60, 300] 이 captured 0 에 셋).
  new_case && lines=()
  for t in 1 2 3; do
    lines+=("59 thumbnail.captured kind=live targetId=S-${t}"
      "60 thumbnail.message_done messageId=m${t} receiveCount=1 \
disposition=DELETE")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_thumbnail_verdict "${G}" "$((T0 - 600))" "$((T0 + 300))"
  expect 'thumb: 볼 창 바로 앞 captured 의 message_done = 통과(짝지음)' \
    '사진: 통과' 0
}

#######################################
# README 본문의 운영 안내 — 따라 읽기는 파이썬 버퍼를 끄고(파이프로 받으면
# 줄이 늦게 나와 120초 감시 불능이 거짓으로 섬 · 2026-11-10 clip 289줄 10초
# 0줄), 감시 · 점검 터미널은 맥 잠자기를 막는다(15분 멈춤 실측 두 번).
#######################################
test_runbook_prose() {
  if ! grep -q 'PYTHONUNBUFFERED=1 aws logs tail' -- "${README}"; then
    fail 'prose: 따라 읽기 명령에 PYTHONUNBUFFERED=1 이 없음'
  else
    pass 'prose: 따라 읽기 명령에 PYTHONUNBUFFERED=1'
  fi
  if ! grep -q 'caffeinate -i' -- "${README}"; then
    fail 'prose: 감시 터미널의 caffeinate -i 안내가 없음'
  else
    pass 'prose: 감시 터미널의 caffeinate -i 안내'
  fi
}

#######################################
# 사례의 시계를 둔다(가짜 date 가 낸다).
# Globals:
#   case_dir
# Arguments:
#   T0 뒤 초.
#######################################
clock_at() {
  printf '%s\n' "$((T0 + $1))" >"${case_dir}/clock"
}

#######################################
# 가짜 sleep 이 받은 초를 한 줄로 이어 본다(쉼마다 몇 초 · 몇 번).
# Globals:
#   case_dir
# Arguments:
#   사례 이름, 기대(「2 2」 꼴 · 없으면 빈 글자).
#######################################
expect_sleeps() {
  local got=""
  if [[ -e "${case_dir}/sleeps" ]]; then
    got="$(LC_ALL=C tr '\n' ' ' <"${case_dir}/sleeps")"
    got="${got% }"
  fi
  if [[ "${got}" != "$2" ]]; then
    fail "$1: sleep 「${got}」 (기대 「$2」)"
    return 1
  fi
  pass "$1"
}

#######################################
# 수신 감시 반복(workers_receive_watch)의 주기 · 종료 · 조회 실패.
#######################################
test_watch() {
  local lines=() line
  # 이어지는 실패 중이면 첫 주기에 정지하고 기다리지 않는다.
  new_case && clock_at 80
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 600))"
  expect_watch 'watch: 정지면 첫 주기에 1' '수신: 정지' 1
  expect_sleeps 'watch: 정지면 기다리지 않음' ''

  # 실패가 없으면 2초 쉬며 보고 끝 시각에 0 — 0 · 2 · 4 · 6 · 8 · 10초에 판정.
  new_case && clock_at 0 && log_resp "${G}"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 10))"
  expect_watch 'watch: 끝 시각까지 보면 0' '수신 감시: 끝' 0
  expect_sleeps 'watch: 2초씩 다섯 번 기다림' '2 2 2 2 2'

  # 조회 실패가 두 주기 이어지면 판정불가.
  new_case && clock_at 0 &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 600))"
  expect_watch 'watch: 조회 실패 두 번 이어짐 = 판정불가' '수신 감시: 판정불가' 2

  # 조회 실패 한 번은 다음 주기에 다시 본다.
  new_case && clock_at 0 && log_resp "${G}" &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail" &&
    : >"${case_dir}/heal"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 60))"
  expect_watch 'watch: 조회 실패 한 번은 다음 주기에 다시' '수신 감시: 끝' 0

  # 끝 시각의 판정이 조회 실패면 끝내지 않고 한 주기 더 본다.
  new_case && clock_at 0 && log_resp "${G}" &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail" &&
    : >"${case_dir}/heal"
  run_readme workers_receive_watch "${G}" "${T0}" "${T0}"
  expect_watch 'watch: 끝 시각의 조회 실패 뒤 통과 = 끝' '수신 감시: 끝' 0
  expect_sleeps 'watch: 끝 시각의 조회 실패는 한 주기 더' '2'

  # 판정 한 번이 상한(5초)을 넘기면 통과여도 판정하지 못한 주기로 센다 —
  # CLI 상한 밖(기동 · DNS)에서 6초가 걸린 꼴.
  new_case && clock_at 0 && log_resp "${G}" &&
    printf '0+6\n0+6\n' >"${case_dir}/delays"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 600))"
  expect_watch 'watch: 판정이 5초를 넘긴 주기 두 번 = 판정불가' \
    '수신 감시: 판정불가' 2
  expect_sleeps 'watch: 늦은 판정 한 번은 다음 주기에 다시' '2'
}

#######################################
# 감시 반복의 판정 줄 시각과, 조회 실패에도 거는 늦음 검사.
# Globals:
#   case_dir, G, T0
#######################################
test_watch_stamps() {
  local stamps
  # 판정 줄마다 판정이 끝난 때의 UTC 시각(T0 = 00:00:00)이 앞에 붙는다.
  new_case && clock_at 0 && log_resp "${G}"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 4))"
  stamps="$(cut -d ' ' -f 1-2 -- "${case_dir}/out" | LC_ALL=C tr '\n' '|')"
  if [[ "${stamps}" != '00:00:00 UTC|00:00:02 UTC|00:00:04 UTC|00:00:04 UTC|' ]]
  then
    fail "watch: 판정 줄마다 끝난 시각 — 「${stamps}」"
  else
    pass 'watch: 판정 줄마다 끝난 시각(UTC)'
  fi

  # 조회 실패가 6초 걸리면(상한 밖) 실패여도 「늦음」 을 시각과 함께 찍는다.
  new_case && clock_at 0 && log_resp "${G}" &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail" &&
    printf '0+6\n0+6\n' >"${case_dir}/delays"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 600))"
  if ! grep -q '^00:00:06 UTC 수신 감시: 늦음 — 판정에 6초' "${case_dir}/out"
  then
    fail "watch: 늦은 조회 실패에 늦음 줄 없음 — $(LC_ALL=C tr '\n' '|' \
      <"${case_dir}/out")"
  else
    expect_watch 'watch: 늦은 조회 실패도 늦음 · 두 번이면 판정불가' \
      '수신 감시: 판정불가' 2
  fi
}

#######################################
# 수신 감시 반복의 시각 인자 — 숫자가 아니면 판정하지 않고 판정불가.
#######################################
test_watch_inputs() {
  # 기동 전(worker_started 없음) workers_started_at 이 낸 문장을 넘긴 꼴.
  local unready='기동 render: 판정불가 — pokeclip-prod-render 에 '
  unready+='render.worker_started 가 없거나 조회 실패'
  new_case && clock_at 0 && log_resp "${G}"
  run_readme workers_receive_watch "${G}" "${unready}" "$((T0 + 600))"
  expect 'watch: 시작 epoch 가 판정불가 문장 = 판정불가' \
    '수신 감시: 판정불가 — 시작 · 끝 epoch' 2
  expect_sleeps 'watch: 시작 epoch 가 숫자가 아니면 반복하지 않음' ''

  # 감시 터미널에 watch_end 를 옮겨 적지 않은 꼴(빈 값).
  new_case && clock_at 0 && log_resp "${G}"
  run_readme workers_receive_watch "${G}" "${T0}" ''
  expect 'watch: 끝 epoch 가 빈 값 = 판정불가' \
    '수신 감시: 판정불가 — 시작 · 끝 epoch' 2
}

#######################################
# README 「쓰는 예」 의 수신 감시 블록(언어 없는 블록)을 글자 그대로 돌린다 —
# 기동 신호(40초) 전에 띄워도 숫자가 나올 때까지 기다렸다가 반복을 띄우고 끝
# 시각(60초)에 끝난다. 기다림이 끝나면 반복 전에 「기동 epoch: <초>」 를
# 찍는다 — 다른 감시 터미널이 옮겨 적을 숫자다.
# Globals:
#   case_dir, G, T0
#######################################
test_watch_usage() {
  local usage got
  if ! usage="$(readme_block 'until started=' '')"; then
    fail 'usage: README 에 수신 감시 쓰는 예 블록이 하나가 아님'
    return 1
  fi
  new_case && clock_at 0 && echo 0 >"${case_dir}/lag" &&
    log_resp "${G}" '40 render.worker_started queue=x'
  run_readme eval "apply_at=${T0}; watch_end=$((T0 + 60)); ${usage}"
  expect_watch 'usage: 기동 신호 전에 띄운 쓰는 예 = 기다렸다가 끝 시각에 끝' \
    '수신 감시: 끝' 0
  expect_sleeps 'usage: 기동 대기 10초 넷 뒤 2초 쉼 열' \
    '10 10 10 10 2 2 2 2 2 2 2 2 2 2'
  # 다른 감시 터미널에 옮겨 적을 기동 epoch 가 반복 전에 화면에 남는다.
  got="$(grep -av '^기동 대기' -- "${case_dir}/out" | head -n 1)"
  if [[ "${got}" != "기동 epoch: $((T0 + 40))" ]]; then
    fail "usage: 기다림 뒤 첫 줄 「${got}」 (기대 「기동 epoch: $((T0 + 40))」)"
  else
    pass 'usage: 기다림이 끝나면 반복 전에 기동 epoch 를 찍음'
  fi
}

#######################################
# 사진 반복 블록을 글자 그대로 돌린다(started 는 기동 600초 뒤 · 끝 시각은
# 인자). 판정마다 5초(망 쪽 상한 3초 + 상한 밖 2초)에 수집 지연 10초다.
# Globals:
#   case_dir, loop_block, T0
# Arguments:
#   시계의 첫 초(T0 뒤), 끝 시각(T0 뒤 초).
#######################################
run_thumbnail_loop() {
  clock_at "$1" && echo 10 >"${case_dir}/lag" && echo 3+2 >"${case_dir}/delay"
  run_readme eval "started=$((T0 - 600)); watch_end=$((T0 + $2)); \
${loop_block}"
}

#######################################
# 사진 반복이 정지 줄로 멈췄고 그 줄의 시각(T0 뒤 초)이 상한 안인지 본다.
# Globals:
#   case_dir, T0
# Arguments:
#   사례 이름, 상한(T0 뒤 초).
#######################################
expect_loop_stop() {
  local got at
  got="$(tail -n 1 -- "${case_dir}/out")"
  at="$(jq -rn --arg s "${got%% *}" '$s | fromdateiso8601' 2>/dev/null)"
  if [[ "${got#* }" != '사진: 정지'* || -z "${at}" ]]; then
    fail "$1: 마지막 줄 「${got}」 (기대 「<ISO 시각> 사진: 정지…」)"
  elif ((at - T0 > $2)); then
    fail "$1: 정지 줄 $((at - T0))초 > 상한 $2초"
  else
    pass "$1 — $((at - T0))초 정지 줄(상한 $2초)"
  fi
}

#######################################
# 사진 반복 블록 — 판정마다 끝난 시각(ISO)을 앞에 붙여 30초 쉼으로 되풀이하고,
# 정지면 그 줄에서 멈추며, 끝 시각이 지나면 「끝」 줄로 끝난다. 정지 줄은
# 조건이 선 때에서 수집 지연 10초 + 앞 판정 6초 + 쉼 30초 + 판정 6초 = 52초
# 안이고, 그 판정이 조회 실패면 한 주기 더(88초) 안이다(README 「사진 반복의
# 셈」).
# Globals:
#   case_dir, G, T0, loop_block
#######################################
test_thumbnail_loop() {
  local t lines=() line phase
  # 카드 실패 100초 — 판정 줄 5 · 40 · 75 · 110 · 145초, 145초 판정이 처음으로
  # 본다(130초까지 보임).
  new_case
  for t in 0 20 40 60 80 120; do
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
      captured live "S-${t}")
  done
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 100 failed \
    card 77)
  log_resp "${G}" "${lines[@]}"
  run_thumbnail_loop 0 3600
  expect_loop_stop 'thumb-loop: 카드 실패 100초' 152

  # 같은 입력에 140초 판정이 조회 실패 — 판정불가 줄을 찍고 이어 가 175초
  # 판정이 정지를 낸다(수신 반복처럼 이어진 두 번에서만 멈춤).
  new_case && log_resp "${G}" "${lines[@]}" && echo "$((T0 + 140))" \
    >"${case_dir}/fail_from"
  run_thumbnail_loop 0 3600
  if ! grep -q '사진: 판정불가' -- "${case_dir}/out"; then
    fail 'thumb-loop: 조회 실패 한 번 — 판정불가 줄이 없음'
  else
    expect_loop_stop 'thumb-loop: 조회 실패 한 번 뒤 이어 가 정지' 188
  fi

  # r2 cx 시간열(0초 captured · 1 · 2 · 3초 못 만든 처리 · 뒤 조용) — 조건은
  # 121초에 서고 121초 한 초 뒤 지금 120초 창에서는 사라진다. 반복의 위상(첫
  # 판정 시각)마다 121 + 52초 안에 정지.
  for phase in 0 9 18 25 34; do
    new_case && lines=()
    while IFS= read -r line; do lines+=("${line}"); done < <(thumb 0 captured \
      live S-0)
    for t in 1 2 3; do
      lines+=("$(thumb_line "${t}" invalid_job)" "$(done_line "${t}")")
    done
    log_resp "${G}" "${lines[@]}"
    run_thumbnail_loop "${phase}" 600
    expect_loop_stop "thumb-loop: r2 cx 시간열 · 위상 ${phase}초" 173
  done

  # 60초마다 이어지는 못 만든 처리(0 · 60 · 120 …) — 셋째가 120초에 서고
  # 130초부터 보임. 61초마다 이어지는 라이브 실패 — 셋째가 122초.
  for phase in 0 17; do
    new_case && lines=()
    for ((t = 0; t <= 600; t += 60)); do
      lines+=("$(thumb_line "${t}" invalid_job)" "$(done_line "${t}")")
    done
    log_resp "${G}" "${lines[@]}"
    run_thumbnail_loop "${phase}" 900
    expect_loop_stop "thumb-loop: 60초마다 못 만든 처리 · 위상 ${phase}초" 172

    new_case && lines=()
    for ((t = 0; t <= 600; t += 61)); do
      while IFS= read -r line; do lines+=("${line}"); done < <(thumb "${t}" \
        failed live "S-${t}")
    done
    log_resp "${G}" "${lines[@]}"
    run_thumbnail_loop "${phase}" 900
    expect_loop_stop "thumb-loop: 61초마다 라이브 실패 · 위상 ${phase}초" 174
  done

  test_thumbnail_loop_ends
}

#######################################
# 사진 반복 블록이 끝나는 길 — 끝 시각 · 이어진 조회 실패 둘 · 끝 시각의 조회
# 실패 · 옮겨 적지 않은 started · watch_end.
# Globals:
#   case_dir, G, T0, loop_block
#######################################
test_thumbnail_loop_ends() {
  local lines=() line got name end_iso='2026-11-10T00:01:30Z'
  # 실패 없이 끝 시각(90초)까지 — 0 · 30 · 60 · 90초에 판정하고 90초 판정
  # 뒤 「끝」 줄.
  new_case && clock_at 0
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 1 captured \
    live S-1)
  log_resp "${G}" "${lines[@]}"
  run_readme eval "started=$((T0 - 600)); watch_end=$((T0 + 90)); \
${loop_block}"
  got="$(tail -n 1 -- "${case_dir}/out")"
  if [[ "${got}" != "2026-11-10T00:01:30Z 사진 반복: 끝 — ${end_iso}"* ]]; then
    fail "thumb-loop: 끝 시각 사례의 마지막 줄 「${got}」"
  else
    expect_sleeps 'thumb-loop: 끝 시각까지 30초마다 통과 · 끝 줄' '30 30 30'
  fi

  # 끝 시각(60초) 판정이 조회 실패 — 한 주기 더 돌아 90초 판정 뒤 끝.
  new_case && clock_at 0 && log_resp "${G}" "${lines[@]}" &&
    echo "$((T0 + 60))" >"${case_dir}/fail_from"
  run_readme eval "started=$((T0 - 600)); watch_end=$((T0 + 60)); \
${loop_block}"
  got="$(tail -n 1 -- "${case_dir}/out")"
  if [[ "${got}" != '2026-11-10T00:01:30Z 사진 반복: 끝'* ]]; then
    fail "thumb-loop: 끝 시각 조회 실패 사례의 마지막 줄 「${got}」"
  else
    expect_sleeps 'thumb-loop: 끝 시각의 조회 실패는 한 주기 더' '30 30 30'
  fi

  # 조회 실패가 이어짐 — 둘째에서 판정불가로 멈춤(쉼 한 번).
  new_case && clock_at 0 &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail"
  run_readme eval "started=$((T0 - 600)); watch_end=$((T0 + 3600)); \
${loop_block}"
  got="$(tail -n 1 -- "${case_dir}/out")"
  if [[ "${got}" != *' 사진 반복: 판정불가 — 두 주기 이어 판정하지 못함' ]]; then
    fail "thumb-loop: 이어진 조회 실패의 마지막 줄 「${got}」"
  else
    expect_sleeps 'thumb-loop: 조회 실패 두 번 이어짐 = 판정불가' '30'
  fi

  # 옮겨 적지 않은 started · watch_end — 판정하지 않고 판정불가 줄.
  for name in started watch_end; do
    new_case && clock_at 0 && log_resp "${G}" "${lines[@]}"
    run_readme eval "started=$((T0 - 600)); watch_end=$((T0 + 90)); \
unset ${name}; ${loop_block}"
    got="$(tail -n 1 -- "${case_dir}/out")"
    if [[ "${got}" != '사진 반복: 판정불가 — started · watch_end 중'* ]]; then
      fail "thumb-loop: ${name} 없음 — 마지막 줄 「${got}」"
    elif [[ -e "${case_dir}/calls" ]]; then
      fail "thumb-loop: ${name} 없음 — 판정을 부름"
    else
      pass "thumb-loop: ${name} 없음 = 판정불가(반복하지 않음)"
    fi
  done
}

#######################################
# 사진 반복 블록의 글자(줄 머리나 공백 뒤 `#` · 꺾쇠 · 글자가 붙은 `!` 0개)와
# 대화형 zsh 붙여 넣기 — 판정 함수 블록 뒤에 넣어 카드 실패로 첫 판정에서
# 멈추는 결과가 같은 입력의 bash 와 바이트 단위로 같고 `command not found` 가
# 없는지 본다. 가짜 date 는 고정 시각이라 정지가 없으면 반복이 끝나지 않으므로
# 정지로 끝나는 입력을 준다.
# Globals:
#   case_dir, zsh_env, ZSH_STDIN_FLAGS, G, T0
#######################################
test_thumbnail_loop_paste() {
  local zsh hits lines=() line
  local name='zsh: 사진 반복 블록 붙여 넣기 = bash 와 같은 결과'
  hits="$(printf '%s' "${loop_block}" |
    grep -nE '(^|[[:space:]])#|<|![^[:space:]]')"
  if [[ -n "${hits}" ]]; then
    fail "thumb-loop 블록: 막는 글자 $(printf '%s' "${hits}" | LC_ALL=C \
      tr '\n' '|')"
  else
    pass 'thumb-loop 블록: # · 꺾쇠 · 붙은 ! 0개'
  fi
  if ! zsh="$(command -v zsh)"; then
    skip "${name}(zsh 없음)"
    return 0
  fi
  new_zsh_case || return 1
  while IFS= read -r line; do lines+=("${line}"); done < <(thumb 1 captured \
    live S-1; thumb 9 failed card 77)
  log_resp "${G}" "${lines[@]}"
  # shellcheck disable=SC2016
  {
    cat -- "${case_dir}/fake" "${case_dir}/blocks"
    printf '%s\n' 'WORKERS_ZSH_CALLS="${WORKERS_ZSH_CALLS}.loop"' \
      "started=$((T0 - 600))" "watch_end=$((T0 + 3600))"
    printf '%s\n' "${loop_block}"
  } >"${case_dir}/loop.stdin"
  "${zsh_env[@]}" bash -s <"${case_dir}/loop.stdin" >"${case_dir}/loop.bash" \
    2>&1
  "${zsh_env[@]}" "${zsh}" "${ZSH_STDIN_FLAGS[@]}" \
    <"${case_dir}/loop.stdin" >"${case_dir}/out" 2>"${case_dir}/err"
  if ! grep -aq '^2026-11-10T00:00:00Z 사진: 정지' "${case_dir}/loop.bash"; then
    fail "${name}: bash 기준이 정지 줄을 내지 않음 — $(LC_ALL=C tr '\n' '|' \
      <"${case_dir}/loop.bash")"
    return 1
  fi
  if ! grep -aqF "$(cat -- "${case_dir}/loop.bash")" "${case_dir}/out" ||
    grep -aqi 'command not found' -- "${case_dir}/out" "${case_dir}/err"; then
    fail "${name}: zsh 「$(LC_ALL=C tr '\n' '|' <"${case_dir}/out")」"
    return 1
  fi
  pass "${name}"
}

#######################################
# README 「다시 띄우기」 블록을 글자 그대로 돌린다 — 이어지던 장애 한가운데서
# 감시를 다시 띄워도 정지가 첫 실패 줄(T0)에서 240초(명령 여유 포함) 안에
# 나야 한다. started 는 앞 감시 터미널에서 옮겨 적은 기동 시각이다.
# Globals:
#   case_dir, G, T0, restart_block
#######################################
test_watch_restart() {
  local env
  # r4 cx 시간열 — 실패 0 · 134 · 268초, 133초에 다시 띄움(기동은 10초 전).
  # 시작을 그때 시각으로 주면 0초 줄을 못 봐 269초에 정지(+12 = 281초).
  new_case && clock_at 133 && echo 0 >"${case_dir}/lag" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '134 render.receive_failed err=x' '268 render.receive_failed err=x'
  env="started=$((T0 - 10)); watch_end=$((T0 + 3600))"
  run_readme eval "${env}; ${restart_block}"
  expect_watch 'restart: r4 cx 시간열(0 · 134 · 268 · 133초에) = 정지' \
    '수신: 정지' 1
  expect_stop_by_deadline 'restart: r4 cx 시간열(0 · 134 · 268 · 133초에)'

  # r4 cc 시간열 — 실패 0 · 134 · 194 · 328초, 1초에 다시 띄움(기동은 1시간
  # 전) · 수집 지연 10초. 시작을 그때 시각으로 주면 134 · 194 가 60초를 넘지
  # 않아 339초에 정지(+12 = 351초).
  new_case && clock_at 1 && echo 10 >"${case_dir}/lag" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '134 render.receive_failed err=x' '194 render.receive_failed err=x' \
      '328 render.receive_failed err=x'
  env="started=$((T0 - 3600)); watch_end=$((T0 + 3600))"
  run_readme eval "${env}; ${restart_block}"
  expect_watch 'restart: r4 cc 시간열(0 · 134 · 194 · 328 · 1초에) = 정지' \
    '수신: 정지' 1
  expect_stop_by_deadline 'restart: r4 cc 시간열(0 · 134 · 194 · 328 · 1초에)'

  # started 를 옮겨 적지 않은 꼴(미설정) — 감시를 띄우지 않고 판정불가로 멈춘다.
  new_case && clock_at 133 && echo 0 >"${case_dir}/lag" &&
    log_resp "${G}" '0 render.receive_failed err=x'
  run_readme eval "unset started; watch_end=$((T0 + 3600)); ${restart_block}"
  expect_restart_refused 'restart: started 미설정 = 판정불가로 멈춤(bash)'
}

#######################################
# 「다시 띄우기」 블록이 감시를 띄우지 않고 판정불가로 멈췄는지 본다 — 마지막
# 줄이 「다시 띄우기: 판정불가」 이고 aws · sleep 이 한 번도 불리지 않음.
# Globals:
#   case_dir
# Arguments:
#   사례 이름.
#######################################
expect_restart_refused() {
  local got
  got="$(tail -n 1 -- "${case_dir}/out")"
  if [[ "${got}" != '다시 띄우기: 판정불가'* ]]; then
    fail "$1: 마지막 줄 「${got}」 (기대 「다시 띄우기: 판정불가…」) / 오류: $(
      LC_ALL=C tr '\n' '|' <"${case_dir}/err")"
    return 1
  fi
  if [[ -s "${case_dir}/calls" || -e "${case_dir}/sleeps" ]]; then
    fail "$1: 감시를 띄움 — aws $(grep -c . "${case_dir}/calls" 2>/dev/null)번"
    return 1
  fi
  pass "$1"
}

#######################################
# 정지 시한(첫 실패 줄 = T0 에서 240초)을 kty 가 보는 판정 줄로 본다. 판정 줄의
# 시각을 차례로 읽어, 줄 사이가 WATCH_GAP_SECONDS 를 넘는 첫 자리에서는 앞 줄
# 시각 + WATCH_GAP_SECONDS(끊김 — 판정불가로 봄)에, 없으면 마지막 줄 시각에
# kty 가 움직인다. 그 시각 + 명령 여유(STOP_COMMAND_SECONDS)가 240초 안이어야
# 한다. 끊김은 둘째 인자가 gap 일 때(CLI 상한 밖에서 멈춘 사례)만 받고, 그때는
# 끊김이 실제로 있어야 한다(없으면 끊김 경로를 안 탄 것이라 실패) — 아니면
# 반복이 상한 안에서 스스로 판정을 내야 해 끊김 자체가 실패다. 시각은
# T0(00:00:00 UTC) 뒤 초다.
# Globals:
#   case_dir, T0, WATCH_GAP_SECONDS, STOP_COMMAND_SECONDS
# Arguments:
#   사례 이름, gap(끊김을 받음 — 없으면 빈 글자).
#######################################
expect_stop_by_deadline() {
  local stamp rest at="" prev="" act="" clock
  clock="$(($(cat -- "${case_dir}/clock") - T0))"
  while read -r stamp rest; do
    if [[ "${stamp}" != [0-9][0-9]:[0-9][0-9]:[0-9][0-9] ]]; then
      continue
    fi
    at=$((10#${stamp:0:2} * 3600 + 10#${stamp:3:2} * 60 + 10#${stamp:6:2}))
    if [[ -n "${prev}" ]] && ((at - prev > WATCH_GAP_SECONDS)); then
      if [[ "${2-}" != gap ]]; then
        fail "$1: 판정 줄이 ${prev}초 → ${at}초로 끊김(${WATCH_GAP_SECONDS}초 넘음)"
        return 1
      fi
      act=$((prev + WATCH_GAP_SECONDS))
      break
    fi
    prev="${at}"
  done <"${case_dir}/out"
  if [[ "${2-}" == gap && -z "${act}" ]]; then
    fail "$1: 끊김 사례인데 판정 줄이 ${WATCH_GAP_SECONDS}초 넘게 끊긴 자리가 없음"
    return 1
  fi
  act="${act:-${prev}}"
  if [[ -z "${act}" ]]; then
    fail "$1: 판정 줄에 시각이 없음(시계 ${clock}초 + 명령 여유 $((clock + \
      STOP_COMMAND_SECONDS))초)"
    return 1
  fi
  if ((act + STOP_COMMAND_SECONDS > 240)); then
    fail "$1: 판정 ${act}초 + 명령 여유 ${STOP_COMMAND_SECONDS}초 > 240초"
    return 1
  fi
  pass "$1 — 판정 ${act}초 + 명령 여유 ${STOP_COMMAND_SECONDS}초"
}

#######################################
# 수신 감시 반복의 정지 시한(판정 + 명령 여유가 240초 안). 첫 실패 줄을 0초로
# 두고, 가짜 aws 가 시계에 맞춰 사건을 보이며(lag — 수집 지연), 조회 시간
# (delay · delays)은 awscli 의 시간 상한 · 시도 수를 따른다(fake_aws_clock).
#######################################
test_watch_deadline() {
  local i
  # r2 cx 시간열 — 0 · 59 · 193초 실패, 감시는 12초부터, 193초 뒤 첫 조회
  # 실패(옛 30초 주기에서는 222초 조회 — 252초 정지).
  new_case && clock_at 12 && echo 0 >"${case_dir}/lag" &&
    echo "$((T0 + 193))" >"${case_dir}/fail_from" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '59 render.receive_failed err=x' '193 render.receive_failed err=x'
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  expect_watch 'deadline: 0 · 59 · 193 + 조회 실패 한 번 = 정지' '수신: 정지' 1
  expect_stop_by_deadline 'deadline: 0 · 59 · 193 + 조회 실패 한 번'

  # r2 cc 시간열 — 13초 간격 다섯 뒤 186초, 수집 지연 2초, 186초 실패가 보인
  # 뒤(188초) 첫 조회 실패(옛 30초 주기에서는 그것이 217초 조회 — 247초 정지).
  new_case && clock_at 7 && echo 2 >"${case_dir}/lag" &&
    echo "$((T0 + 188))" >"${case_dir}/fail_from" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '13 render.receive_failed err=x' '26 render.receive_failed err=x' \
      '39 render.receive_failed err=x' '52 render.receive_failed err=x' \
      '186 render.receive_failed err=x'
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  expect_watch 'deadline: 0–52 · 186 + 지연 2초 · 조회 실패 = 정지' '수신: 정지' 1
  expect_stop_by_deadline 'deadline: 0–52 · 186 + 지연 2초 · 조회 실패'

  # 판정마다 5초(망 쪽 상한 3초 + 상한 밖 2초) — 둘째 실패 60초(넘지 않음) ·
  # 셋째 194초 · 수집 지연 10초 · 정지 조건이 보이기 바로 앞(203초)에 판정을
  # 시작 · 그 뒤 첫 조회 실패.
  new_case && clock_at 0 && echo 10 >"${case_dir}/lag" &&
    echo 3+2 >"${case_dir}/delay" &&
    echo "$((T0 + 204))" >"${case_dir}/fail_from" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '60 render.receive_failed err=x' '194 render.receive_failed err=x'
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  expect_watch 'deadline: 판정마다 5초(60 · 194 · 지연 10) = 정지' '수신: 정지' 1
  expect_stop_by_deadline 'deadline: 판정마다 5초'

  # 판정마다 쪽 둘 — 첫 쪽(빈 결과 + 표) 1초 뒤 둘째 쪽이 망 쪽 상한 3초 +
  # 상한 밖 1초(판정 5초 · 늦음 아님). 실패는 둘째 쪽에 있다.
  new_case && clock_at 0 && echo 10 >"${case_dir}/lag" &&
    page_resp "${G}" '' p2 &&
    page_resp "${G}" p2 '' '0 render.receive_failed err=x' \
      '60 render.receive_failed err=x' '194 render.receive_failed err=x'
  for ((i = 0; i < 100; i++)); do
    printf '1\n3+1\n'
  done >"${case_dir}/delays"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  expect_watch 'deadline: 판정마다 쪽 둘(1초 · 3+1초) = 정지' '수신: 정지' 1
  expect_stop_by_deadline 'deadline: 판정마다 쪽 둘'
}

#######################################
# 감시 반복이 로그 조회(filter-log-events)를 몇 번 했는지 본다 — 시간열의
# 조회 시간(delays)을 끝까지 탔는지.
# Globals:
#   case_dir
# Arguments:
#   사례 이름, 기대 조회 수.
#######################################
expect_queries() {
  local got
  got="$(grep -c '^logs filter-log-events ' "${case_dir}/calls")"
  if [[ "${got}" -ne "$2" ]]; then
    fail "$1: 조회 ${got}번(기대 $2번)"
    return 1
  fi
  pass "$1"
}

#######################################
# 정지 시한 — 느리거나 멈춘 조회. 조회 시간이 awscli 의 시간 상한 · 시도 수를
# 따르므로, 상한이 느린 조회를 잘라 판정 줄이 끊기지 않고 시한 안에 판정을
# 낸다. 상한 밖에서 멈추면 kty 가 줄 끊김(10초)으로 본다.
#######################################
test_watch_deadline_caps() {
  # r3 cx 시간열 — 0 · 60 · 194초 실패 · 수집 지연 10초 · 조회가 196초에 2초,
  # 그 뒤 둘이 14초씩(옛 꼴: 196→198 · 203→217 · 222→236 정지 — 명령 여유를
  # 넣으면 248초). 상한이 14초 조회를 잘라 판정불가로 끝난다.
  new_case && clock_at 196 && echo 10 >"${case_dir}/lag" &&
    printf '2\n14\n14\n' >"${case_dir}/delays" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '60 render.receive_failed err=x' '194 render.receive_failed err=x'
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  expect_stop_by_deadline 'deadline: r3 cx 시간열(조회 2 · 14 · 14초)'

  # 조회가 상한까지 걸려 실패 — 203초에 시작한 판정이 5초(망 쪽 상한 3초 +
  # 상한 밖 2초 · 그 줄을 못 봄 · 늦음 아님), 그 뒤 둘이 망 쪽에서 멈춰 시간
  # 상한까지 걸린 뒤 실패(상한 밖 1초씩). 판정 줄 208 · 214 · 220초.
  new_case && clock_at 203 && echo 10 >"${case_dir}/lag" &&
    printf '3+2\n999+1\n999+1\n' >"${case_dir}/delays" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '60 render.receive_failed err=x' '194 render.receive_failed err=x'
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  expect_watch 'deadline: 조회가 상한까지 걸려 두 번 실패 = 판정불가' \
    '수신 감시: 판정불가' 2
  expect_stop_by_deadline 'deadline: 조회가 상한까지 걸려 실패'
  expect_queries 'deadline: 조회가 상한까지 걸려 실패 — 조회 셋을 다 씀' 3

  # 판정 줄이 끊김 — 위와 같다가 셋째 조회가 상한 밖(DNS 등)에서 100초 멈춤.
  # kty 는 줄 사이 10초 끊김에서 판정불가로 본다.
  # 판정 줄 208 · 214 · 316초 — 214초 뒤 끊김을 224초에 본다.
  new_case && clock_at 203 && echo 10 >"${case_dir}/lag" &&
    printf '3+2\n999+1\n0+100\n' >"${case_dir}/delays" &&
    log_resp "${G}" '0 render.receive_failed err=x' \
      '60 render.receive_failed err=x' '194 render.receive_failed err=x'
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  expect_stop_by_deadline 'deadline: 셋째 조회가 상한 밖에서 멈춤(줄 끊김)' gap
  expect_queries 'deadline: 줄 끊김 — 셋째 조회까지 감' 3
}

#######################################
# 로그 사건 응답 한 쪽을 쓴다 — 첫 쪽(표 없음)이나 이어 받는 쪽(그 표)이고,
# 다음 표가 있으면 nextToken 을 단다. 줄은 log_resp 와 같다.
# Globals:
#   case_dir
# Arguments:
#   로그 그룹, 이 쪽의 표(첫 쪽이면 빈 글자), 다음 표(끝 쪽이면 빈 글자),
#   「초 메시지」 줄 ….
#######################################
page_resp() {
  local key="$1${2:+.$2}" next="$3" file
  shift 3
  # 이어 받는 쪽의 열쇠는 「그룹.표」 라, 그 이름을 그룹 자리에 넣어 쓴다.
  log_resp "${key}" "$@" || return 1
  file="${case_dir}/resp/logs.filter-log-events.${key}"
  jq --arg next "${next}" \
    'if $next == "" then . else . + {nextToken: $next} end' "${file}" \
    >"${case_dir}/page" && mv -- "${case_dir}/page" "${file}"
}

#######################################
# 이어 받기 표(nextToken)는 FilterLogEvents 가 창을 아직 다 훑지 못했다는
# 표시라, 같은 창의 쪽을 끝까지 따라가 합친 뒤 판정한다. 2026-11-11 첫 apply
# 의 render · upload 그룹은 표가 늘 붙어(빈 쪽 + 표) 기동 신호를 못 잡았다.
# 따라가는 시간은 상한(시작 뒤 2초가 지나면 새 쪽을 부르지 않음)이 있다.
# Globals:
#   case_dir, G, T0
#######################################
test_events_pages() {
  local i
  # 바쁜 그룹 — 첫 쪽은 빈 결과 + 표, 둘째 쪽에 기동 신호.
  new_case && page_resp "${G}" '' p2 &&
    page_resp "${G}" p2 '' '40 render.worker_started queue=x'
  run_readme workers_started_at "${G}" render "${T0}"
  expect 'pages: 빈 쪽 + 표 뒤 둘째 쪽의 기동 신호' "$((T0 + 40))" 0

  # 실패가 두 쪽에 나뉨 — 합쳐서 72초 이어짐 = 정지.
  new_case && page_resp "${G}" '' p2 '0 render.receive_failed err=x' \
    '24 render.receive_failed err=x' &&
    page_resp "${G}" p2 p3 '48 render.receive_failed err=x' &&
    page_resp "${G}" p3 '' '72 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 80))"
  expect 'pages: 세 쪽에 나뉜 실패를 합쳐 = 수신 정지' '수신: 정지' 1

  # 표가 끝없이 붙음 — 쪽마다 1초. 시작 뒤 2초가 지나면 그만두고
  # 판정불가이고, 판정 한 번이 상한(2초 + 쪽 하나 3초) 안에 끝난다.
  new_case && clock_at 0 && page_resp "${G}" '' p1
  for i in 1 2 3 4 5 6 7 8; do
    page_resp "${G}" "p${i}" "p$((i + 1))"
  done
  printf '1\n1\n1\n1\n1\n1\n1\n1\n1\n' >"${case_dir}/delays"
  run_readme workers_receive_verdict "${G}" "${T0}" "${T0}"
  expect 'pages: 표가 끝나지 않음 = 판정불가' '수신: 판정불가' 2
  if (($(cat -- "${case_dir}/clock") - T0 > 5)); then
    fail "pages: 쪽 따라가기가 $(($(cat -- "${case_dir}/clock") - T0))초(상한 5초)"
  elif [[ "$(grep -c -- '--next-token' "${case_dir}/calls")" -lt 1 ]]; then
    fail 'pages: 표를 한 번도 따라가지 않음'
  else
    pass 'pages: 쪽 따라가기는 시간 상한 안에서 멈춤'
  fi
}

#######################################
# 감시 반복은 조회 창을 지난 600초(시작 epoch 가 더 늦으면 그것)로 묶는다 —
# 「다시 띄우기」 의 600초와 같은 셈이라 이어지는 장애의 첫 줄을 늘 품는다.
# Globals:
#   case_dir, G, T0
#######################################
test_watch_window() {
  local lines=() line want
  new_case && clock_at 3600 && log_resp "${G}"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 3600))"
  want="--start-time $(((T0 + 3000) * 1000)) "
  if ! grep -q -- "${want}" "${case_dir}/calls"; then
    fail "window: 조회 창이 지난 600초가 아님 — $(head -n 1 -- \
      "${case_dir}/calls")"
  else
    expect_watch 'window: 기동 1시간 뒤의 조회 창 = 지난 600초' '수신 감시: 끝' 0
  fi

  # 기동 때부터 이어진 실패(100초마다)도 창 안의 줄로 정지한다.
  new_case && clock_at 3600
  for ((line = 0; line <= 3590; line += 100)); do
    lines+=("${line} render.receive_failed err=x")
  done
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 7200))"
  expect_watch 'window: 창보다 긴 이어짐도 정지' '수신: 정지' 1
}

# PTY 에 띄운 대화형 zsh 에 파일마다 bracketed paste(ESC[200~ … ESC[201~ 와
# Enter)로 붙여 넣는 python3 프로그램. 붙여 넣은 뒤마다 print 로 표지를 찍어
# 끝났는지 보고, 쓰는 동안 출력을 읽어 PTY 버퍼가 차 멈추지 않게 한다. 한 걸음
# 20초를 넘기면 그만두고 종료 1 이다.
# 사용: python3 -I -c "${PTY_DRIVER}" <zsh> <기록 파일> <꼬리 명령 파일>
#   <붙여 넣을 파일> ….
PTY_DRIVER="$(
  cat <<'PY'
import fcntl
import os
import pty
import select
import struct
import sys
import termios
import time

PASTE_START = b"\x1b[200~"
PASTE_END = b"\x1b[201~"
STEP_SECONDS = 20


class Session:
    """PTY 의 zsh 하나. 쓰는 동안에도 출력을 읽는다."""

    def __init__(self, fd):
        self.fd = fd
        self.seen = bytearray()
        self.closed = False

    def _step(self, pending, deadline):
        left = deadline - time.monotonic()
        if left <= 0 or self.closed:
            return False
        writers = [self.fd] if pending else []
        readable, writable, _ = select.select([self.fd], writers, [], left)
        if readable:
            try:
                chunk = os.read(self.fd, 65536)
            except OSError:
                chunk = b""
            if not chunk:
                self.closed = True
                return False
            self.seen.extend(chunk)
        if writable and pending:
            try:
                del pending[:os.write(self.fd, bytes(pending[:512]))]
            except BlockingIOError:
                pass
        return True

    def send(self, data):
        pending = bytearray(data)
        deadline = time.monotonic() + STEP_SECONDS
        while pending:
            if not self._step(pending, deadline):
                return False
        return True

    def wait_for(self, token):
        deadline = time.monotonic() + STEP_SECONDS
        while token not in self.seen:
            if not self._step(None, deadline):
                return False
        return True

    def sync(self, n):
        return (self.send(b"print -r -- Z$((%d+100))Z\r" % n)
                and self.wait_for(b"Z%dZ" % (n + 100)))


def main():
    zsh, transcript, tail_file = sys.argv[1:4]
    pastes = []
    for path in sys.argv[4:]:
        with open(path, "rb") as f:
            pastes.append(f.read())
    with open(tail_file, "rb") as f:
        tail = f.read()
    pid, fd = pty.fork()
    if pid == 0:
        os.execv(zsh, [zsh, "-f", "-i"])
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 200, 0, 0))
    flags = fcntl.fcntl(fd, fcntl.F_GETFL)
    fcntl.fcntl(fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)
    session = Session(fd)
    ok = session.sync(0)
    for i, text in enumerate(pastes, 1):
        if not ok:
            break
        paste = PASTE_START + text.replace(b"\n", b"\r") + PASTE_END + b"\r"
        ok = session.send(paste) and session.sync(i)
    if ok:
        ok = (session.send(tail.replace(b"\n", b"\r"))
              and session.sync(len(pastes) + 1))
        session.send(b"exit\r")
        session.wait_for(b"\x00")
    try:
        os.kill(pid, 9)
    except ProcessLookupError:
        pass
    os.waitpid(pid, 0)
    with open(transcript, "wb") as f:
        f.write(bytes(session.seen))
    return 0 if ok else 1


sys.exit(main())
PY
)"
readonly PTY_DRIVER

# 새 PTY 를 제어 터미널로 준 채 명령 하나를 띄우는 python3 프로그램. 표준
# 입력 · 출력 · 오류는 파일이다 — 사람이 터미널에서 이 시험을 돌릴 때 zsh 가
# 보는 꼴이다. 그때처럼 PTY 의 슬레이브를 이 프로그램이 열어 둔다(아무도 열어
# 두지 않으면 zsh 가 터미널을 쓰지 못해 멈춤이 재현되지 않는다). PTY 에 나오는
# 것(프롬프트 등)은 읽어 버린다. 20초 안에 끝나지 않으면 그 세션을 죽이고
# 종료 1 이다.
# 사용: python3 -I -c "${TTY_RUNNER}" <표준 입력 파일> <표준 출력 파일>
#   <표준 오류 파일> <명령> <인자> ….
TTY_RUNNER="$(
  cat <<'PY'
import fcntl
import os
import select
import signal
import sys
import termios
import time

RUN_SECONDS = 20


def redirect(path, target, flags):
    fd = os.open(path, flags, 0o600)
    os.dup2(fd, target)
    os.close(fd)


def main():
    stdin_path, out_path, err_path = sys.argv[1:4]
    fd, slave = os.openpty()
    pid = os.fork()
    if pid == 0:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
        written = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
        redirect(stdin_path, 0, os.O_RDONLY)
        redirect(out_path, 1, written)
        redirect(err_path, 2, written)
        os.close(fd)
        os.close(slave)
        os.execvp(sys.argv[4], sys.argv[4:])
    deadline = time.monotonic() + RUN_SECONDS
    while time.monotonic() < deadline:
        if os.waitpid(pid, os.WNOHANG)[0]:
            return 0
        readable, _, _ = select.select([fd], [], [], 0.1)
        if not readable:
            continue
        try:
            chunk = os.read(fd, 65536)
        except OSError:
            chunk = b""
        if not chunk:
            time.sleep(0.1)
    os.killpg(pid, signal.SIGKILL)
    os.waitpid(pid, 0)
    return 1


sys.exit(main())
PY
)"
readonly TTY_RUNNER

# 대화형 zsh 와 bash 에 같이 넣는 가짜 aws · date. aws 는 받은 인자를
# WORKERS_ZSH_CALLS 에 적고, 응답은 WORKERS_ZSH_RESP 에서 fake_aws 와 같은
# 열쇠로 고른다(없으면 cat 이 실패). date 는 WORKERS_ZSH_NOW 를 내 감시 반복의
# 판정 줄 시각을 두 셸에서 같게 한다. 대화형 zsh 에 들어가므로 `#` · `!` 를
# 쓰지 않는다. 명령 치환 안의 here-doc 은 bash 3.2 가 case 의 `)` 를 치환의
# 끝으로 읽어 read 로 받는다.
IFS= read -r -d '' SHARED_FAKE_AWS <<'SH'
aws() {
  printf '%s\n' "$*" >> "${WORKERS_ZSH_CALLS}"
  local key="$1.$2" want="" arg
  for arg in "$@"; do
    case "${want}" in
      plain) key="${key}.${arg}" ;;
      url) key="${key}.${arg##*/}" ;;
      dims) key="${key}.${arg##*Value=}" ;;
    esac
    want=""
    case "${arg}" in
      --log-group-name | --queue-name | --metric-name | --attribute-names | \
        --next-token)
        want=plain
        ;;
      --queue-url) want=url ;;
      --dimensions) want=dims ;;
    esac
  done
  cat -- "${WORKERS_ZSH_RESP}/${key}"
}
date() {
  printf '%s\n' "${WORKERS_ZSH_NOW}"
}
SH
readonly SHARED_FAKE_AWS

#######################################
# 대화형 zsh 사례의 입력 파일을 사례 디렉터리에 쓴다 — fake(가짜 aws · 가짜
# date — WORKERS_ZSH_NOW 를 냄) · run(판정 함수 여덟을 차례로 한 번씩 불러
# 결과와 종료 코드를 WORKERS_ZSH_RUN 에 적음 — 나머지 둘은 그 안에서 불림) ·
# tail(함수마다 「whence: <whence -w 출력>」) · stdin(fake · 블록 · run · tail) ·
# bash.stdin(fake · 블록 · run — bash 기준 실행). run 의 첫 줄이 가짜 aws 의
# 기록 파일을 바꿔, 정의하는 동안의 호출과 부른 뒤의 호출을 가른다. 모두 대화형
# zsh 에 들어가므로 `#` · `!` 를 쓰지 않는다.
# Globals:
#   case_dir, readme_text, ZSH_FUNCTIONS, SHARED_FAKE_AWS, G, RQ, T0
# Arguments:
#   블록 글자(없으면 readme_text — 음성 대조가 바꾼 글자를 준다).
#######################################
write_zsh_inputs() {
  local text="${1-${readme_text}}" call
  printf '%s' "${SHARED_FAKE_AWS}" >"${case_dir}/fake"
  # shellcheck disable=SC2016
  {
    printf '%s\n' 'WORKERS_ZSH_CALLS="${WORKERS_ZSH_CALLS}.run"'
    for call in \
      "workers_iso ${T0}" \
      "workers_started_at ${G} render ${T0}" \
      "workers_start_log_verdict ${G} render ${RQ} ${T0} $((T0 + 200))" \
      "workers_receive_verdict ${G} ${T0} $((T0 + 200))" \
      "workers_nodone_verdict ${G} render ${RQ} ${T0} $((T0 + 1800))" \
      "workers_empty_receive_verdict ${RQ} ${T0} $((T0 + 600))" \
      "workers_thumbnail_verdict ${G} ${T0} $((T0 + 200))" \
      "workers_receive_watch ${G} ${T0} 0"; do
      printf '{ %s; echo "rc=$?"; } >> "${WORKERS_ZSH_RUN}" 2>&1\n' "${call}"
    done
  } >"${case_dir}/run"
  # shellcheck disable=SC2016
  printf 'for f in %s; do print -r -- "whence: $(whence -w $f)"; done\n' \
    "${ZSH_FUNCTIONS[*]}" >"${case_dir}/tail"
  printf '%s' "${text}" >"${case_dir}/blocks"
  cat -- "${case_dir}/fake" "${case_dir}/blocks" "${case_dir}/run" \
    >"${case_dir}/bash.stdin"
  cat -- "${case_dir}/bash.stdin" "${case_dir}/tail" >"${case_dir}/stdin"
  mkdir -- "${case_dir}/home"
  # 판정 함수가 끝까지 도는 응답 — 기동 0 · 시작 30 · 완료 1700 · 깊이 1 ·
  # 나이 25분 · 기동 뒤 분의 빈 수신.
  log_resp "${G}" '0 render.worker_started queue=x' \
    '30 render.started jobId=j1 clipId=c1 attempt=1' \
    '1700 render.message_done messageId=m1 receiveCount=1 disposition=DELETE'
  queue_resp "${RQ}" 1 0
  metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 1500'
  metric_resp NumberOfEmptyReceives "${RQ}" Sum '60 3'
}

#######################################
# 대화형 zsh 사례 디렉터리를 새로 만들어 입력 파일을 쓰고, 그 사례에서 zsh 를
# 띄울 환경(env -i …)을 zsh_env 에 둔다. HOME · TMPDIR 은 사례 디렉터리이고
# AWS 설정 파일은 /dev/null 이다. bash 기준 실행(run.bash)을 여기서 돌린다.
# Globals:
#   case_dir, zsh_env
# Arguments:
#   블록 글자(write_zsh_inputs 와 같음).
#######################################
new_zsh_case() {
  new_case && write_zsh_inputs "$@" || return 1
  zsh_env=(env -i "PATH=${PATH}" "HOME=${case_dir}/home" "TERM=xterm-256color"
    "LANG=${LANG:-C.UTF-8}" "TMPDIR=${case_dir}/tmp"
    "AWS_CONFIG_FILE=/dev/null" "AWS_SHARED_CREDENTIALS_FILE=/dev/null"
    "WORKERS_ZSH_CALLS=${case_dir}/calls" "WORKERS_ZSH_RESP=${case_dir}/resp"
    "WORKERS_ZSH_RUN=${case_dir}/run.out" "WORKERS_ZSH_NOW=${T0}")
  "${zsh_env[@]}" "WORKERS_ZSH_RUN=${case_dir}/run.bash" bash -s \
    <"${case_dir}/bash.stdin" >/dev/null 2>&1
}

#######################################
# 대화형 zsh 의 결과에서 어긋남을 한 줄씩 낸다(없으면 출력 없음). 판정 함수
# 열이 모두 function 인지, 정의하는 동안 가짜 aws 가 불리지 않았는지, 함수를
# 부른 결과(run.out — 표준 출력 · 오류 · 종료 코드)가 bash 기준(run.bash)과
# 바이트 단위로 같은지, `command not found` 가 없는지 본다.
# Globals:
#   case_dir, ZSH_FUNCTIONS
#######################################
zsh_problems() {
  local fn missing=()
  for fn in "${ZSH_FUNCTIONS[@]}"; do
    if ! grep -aq "whence: ${fn}: function" "${case_dir}/out"; then
      missing+=("${fn}")
    fi
  done
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '정의 안 된 함수 %s개(%s) / 오류: %s\n' "${#missing[@]}" \
      "${missing[*]}" "$(grep -aoh 'zsh: [^[:cntrl:]]*' "${case_dir}/out" \
        "${case_dir}/err" | sort | uniq -c | LC_ALL=C tr '\n' '|')"
  fi
  if [[ -s "${case_dir}/calls" ]]; then
    printf '정의하는 동안 가짜 aws 가 불림 — %s\n' \
      "$(LC_ALL=C tr '\n' '|' <"${case_dir}/calls")"
  fi
  if [[ "$(grep -c '^rc=' "${case_dir}/run.bash" 2>/dev/null)" -ne 8 ]]; then
    printf 'bash 기준 실행의 결과가 여덟 줄이 아님\n'
  fi
  if ! cmp -s -- "${case_dir}/run.bash" "${case_dir}/run.out"; then
    printf '부른 결과가 bash 와 다름 — zsh 「%s」\n' \
      "$(LC_ALL=C tr '\n' '|' <"${case_dir}/run.out" 2>/dev/null)"
  fi
  if grep -aqi 'command not found' -- "${case_dir}/out" "${case_dir}/err" \
    "${case_dir}/run.out" 2>/dev/null; then
    printf 'command not found 가 있음\n'
  fi
}

#######################################
# zsh_problems 가 비었으면 통과, 아니면 첫 어긋남으로 실패한다.
# Arguments:
#   사례 이름.
#######################################
expect_zsh_clean() {
  local problems
  problems="$(zsh_problems)"
  if [[ -n "${problems}" ]]; then
    fail "$1: ${problems%%$'\n'*}"
    return 1
  fi
  pass "$1"
}

#######################################
# 블록 글자를 대화형 zsh 에 표준 입력으로 넣는다(제어 터미널 없음). 결과는
# 사례의 out · err · run.out 이다.
# Globals:
#   case_dir, zsh_env, ZSH_STDIN_FLAGS
# Arguments:
#   zsh 경로, 블록 글자(write_zsh_inputs 와 같음).
#######################################
run_zsh_stdin() {
  local zsh="$1"
  shift
  new_zsh_case "$@" || return 1
  "${zsh_env[@]}" "${zsh}" "${ZSH_STDIN_FLAGS[@]}" <"${case_dir}/stdin" \
    >"${case_dir}/out" 2>"${case_dir}/err"
}

#######################################
# 블록에 줄 머리나 공백 뒤의 `#`(대화형 zsh 에서 주석이 아니라 명령 · 인자가
# 되는 글자)가 없는지 본다. 부르지 않은 갈래의 주석은 실행해 보는 사례가 못
# 보므로 글자로 본다.
# Globals:
#   readme_text
#######################################
test_no_comment_in_blocks() {
  local hits
  hits="$(printf '%s' "${readme_text}" | grep -nE '(^|[[:space:]])#')"
  if [[ -n "${hits}" ]]; then
    fail "블록에 # 주석: $(printf '%s' "${hits}" | LC_ALL=C tr '\n' '|')"
    return 1
  fi
  pass '블록: 줄 머리 · 공백 뒤 # 0개'
}

#######################################
# 대화형 zsh(interactivecomments 꺼짐 · 이력 확장 켜짐)에서 README 블록이
# 판정 함수를 모두 정의하고, 불렀을 때 bash 와 같은 결과를 내는지 본다.
# 표준 입력(터미널 없음 · 있음)과 PTY bracketed paste 세 사례와, 함수 본문에
# `#` 주석을 되돌린 음성 대조.
# Globals:
#   case_dir, zsh_env, readme_blocks, readme_text
#######################################
test_zsh_interactive() {
  local zsh i pastes=() head commented
  if ! zsh="$(command -v zsh)"; then
    skip 'zsh: 표준 입력 붙여 넣기(zsh 없음)'
    skip 'zsh: 음성 대조 — 본문 # 주석(zsh 없음)'
    skip 'zsh: 터미널 + 표준 입력(zsh 없음)'
    skip 'zsh: PTY 붙여 넣기(zsh 없음)'
    return 0
  fi

  run_zsh_stdin "${zsh}"
  expect_zsh_clean 'zsh: 표준 입력 붙여 넣기 = 함수 열 정의 · aws 0회 · bash 와 같은 결과'

  # 음성 대조: 수신 판정 본문 첫 줄에 주석을 되돌리면 잡혀야 한다.
  # bash 3.2 의 ${…/…/…} 따옴표 문제를 피해 앞 · 뒤로 잇는다(test_mutations).
  head="workers_receive_verdict() {"$'\n'
  commented="${readme_text%%"${head}"*}${head}  # comment_reintroduced"$'\n'
  commented+="${readme_text#*"${head}"}"
  # 자리가 없으면 앞 조각이 글자 전체라, 바꾼 글자가 원래 글자로 시작한다.
  if [[ "${commented}" == "${readme_text}"* ]]; then
    fail 'zsh: 음성 대조 — 주석을 넣을 자리를 못 찾음'
  else
    run_zsh_stdin "${zsh}" "${commented}"
    if [[ -n "$(zsh_problems)" ]]; then
      pass 'zsh: 음성 대조 — 본문 # 주석이 잡힘'
    else
      fail 'zsh: 음성 대조 — 본문 # 주석을 넣어도 통과함'
    fi
  fi

  if ! command -v python3 >/dev/null; then
    skip 'zsh: 터미널 + 표준 입력(python3 없음)'
    skip 'zsh: PTY 붙여 넣기(python3 없음)'
    return 0
  fi
  new_zsh_case
  if python3 -I -c "${TTY_RUNNER}" "${case_dir}/stdin" "${case_dir}/out" \
    "${case_dir}/err" "${zsh_env[@]}" "${zsh}" "${ZSH_STDIN_FLAGS[@]}"; then
    expect_zsh_clean 'zsh: 터미널 + 표준 입력 = 20초 안에 끝남 · 함수 열 · bash 와 같은 결과'
  else
    fail 'zsh: 터미널 + 표준 입력이 20초 안에 끝나지 않음(터미널 입력 대기)'
  fi

  new_zsh_case
  pastes=("${case_dir}/fake")
  for i in "${!readme_blocks[@]}"; do
    printf '%s' "${readme_blocks[i]}" >"${case_dir}/block.${i}"
    pastes+=("${case_dir}/block.${i}")
  done
  # 부르는 줄도 붙여 넣는다 — 함수가 도는 동안 쳐 둔 글자는 터미널의 줄
  # 버퍼(macOS 1024바이트)를 넘으면 잘려, 친 글자로는 꼬리 한 줄만 보낸다.
  pastes+=("${case_dir}/run")
  if ! "${zsh_env[@]}" python3 -I -c "${PTY_DRIVER}" "${zsh}" \
    "${case_dir}/out" "${case_dir}/tail" "${pastes[@]}" \
    2>"${case_dir}/err"; then
    fail "zsh: PTY 붙여 넣기가 끝나지 않음(이어 쓰기 대기 등) / 오류: $(
      grep -ao 'zsh: [^[:cntrl:]]*' "${case_dir}/out" | LC_ALL=C tr '\n' '|')"
    return 1
  fi
  expect_zsh_clean 'zsh: PTY bracketed paste = 함수 열 정의 · aws 0회 · bash 와 같은 결과'
}

#######################################
# 대화형 zsh 에 판정 함수 블록과 「다시 띄우기」 블록을 넣되 started 를 옮겨
# 적지 않은 꼴 — zsh 의 [ … -gt "" ] 는 빈 값을 0 으로 읽어 오류 없이 시작
# 맞춤을 건너뛰므로, 블록 앞의 숫자 검사가 감시를 띄우지 않고 판정불가로
# 멈추는지 본다. 끝 epoch 는 지금(가짜 date)이라 검사가 없으면 판정 한 번
# 뒤 끝난다. 가짜 aws 의 기록은 calls.restart 다(new_zsh_case 의 bash 기준
# 실행이 calls.run 을 씀).
# Globals:
#   case_dir, zsh_env, restart_block, ZSH_STDIN_FLAGS, T0
#######################################
test_zsh_restart_unset() {
  local zsh name='restart: started 미설정 = 판정불가로 멈춤(대화형 zsh)'
  if ! zsh="$(command -v zsh)"; then
    skip "${name}(zsh 없음)"
    return 0
  fi
  new_zsh_case || return 1
  # shellcheck disable=SC2016
  {
    cat -- "${case_dir}/fake" "${case_dir}/blocks"
    printf '%s\n' 'WORKERS_ZSH_CALLS="${WORKERS_ZSH_CALLS}.restart"' \
      "watch_end=${T0}"
    printf '%s' "${restart_block}"
  } >"${case_dir}/restart.stdin"
  "${zsh_env[@]}" "${zsh}" "${ZSH_STDIN_FLAGS[@]}" \
    <"${case_dir}/restart.stdin" >"${case_dir}/out" 2>"${case_dir}/err"
  if ! grep -aq '^다시 띄우기: 판정불가' "${case_dir}/out"; then
    fail "${name}: 판정불가 줄 없음 — $(LC_ALL=C tr '\n' '|' \
      <"${case_dir}/out")"
    return 1
  fi
  if [[ -s "${case_dir}/calls.restart" ]]; then
    fail "${name}: 감시를 띄움 — $(LC_ALL=C tr '\n' '|' \
      <"${case_dir}/calls.restart")"
    return 1
  fi
  pass "${name}"
}

#######################################
# 사례 묶음 전부를 지금 readme_text 로 돈다.
# Globals:
#   quiet, suite_failures
#######################################
run_suite() {
  local test_name
  for test_name in \
    test_design_cases \
    test_receive \
    test_nodone \
    test_start_log \
    test_empty_and_started \
    test_events_pages \
    test_thumbnail \
    test_watch_window \
    test_watch \
    test_watch_stamps \
    test_watch_inputs \
    test_watch_usage \
    test_thumbnail_loop \
    test_watch_restart \
    test_watch_deadline \
    test_watch_deadline_caps; do
    # 변이 묶음은 첫 실패에서 멈춘다 — 잡혔는지만 보면 되고 시간이 준다.
    if [[ -n "${quiet}" && "${suite_failures}" -gt 0 ]]; then
      return 0
    fi
    "${test_name}"
  done
}

#######################################
# 글자 안에 찾을 글자가 몇 번 있는지 낸다.
# Arguments:
#   글자, 찾을 글자.
#######################################
count_of() {
  local replaced="${1//"$2"/}"
  echo "$(((${#1} - ${#replaced}) / ${#2}))"
}

#######################################
# 변이마다 README 사본으로 사례 묶음을 돌려 실패가 나오는지 본다. 바꿀 글자는
# 판정 함수 블록(readme_text) · 「다시 띄우기」 블록(restart_block) · 「사진
# 반복」 블록(loop_block)을 합쳐 정확히 한 번 있어야 하고, 있는 쪽만 바꾼다.
# Globals:
#   readme_text, restart_block, loop_block, quiet, suite_failures
#######################################
test_mutations() {
  local base="${readme_text}" restart_base="${restart_block}"
  local loop_base="${loop_block}" i from to in_base in_restart count
  for i in "${!MUTATION_NAMES[@]}"; do
    from="${MUTATION_FROMS[i]}"
    to="${MUTATION_TOS[i]}"
    in_base="$(count_of "${base}" "${from}")"
    in_restart="$(count_of "${restart_base}" "${from}")"
    count=$((in_base + in_restart + $(count_of "${loop_base}" "${from}")))
    if [[ "${count}" -ne 1 ]]; then
      fail "변이 「${MUTATION_NAMES[i]}」: 바꿀 글자가 ${count}번 있음"
      continue
    fi
    # bash 3.2 의 ${base/…/…} 따옴표 문제를 피해 앞 · 뒤로 잇는다
    # (network_vpce_runbook_test 와 같은 이유).
    if [[ "${in_base}" -eq 1 ]]; then
      readme_text="${base%%"${from}"*}${to}${base#*"${from}"}"
    elif [[ "${in_restart}" -eq 1 ]]; then
      restart_block="${restart_base%%"${from}"*}${to}${restart_base#*"${from}"}"
    else
      loop_block="${loop_base%%"${from}"*}${to}${loop_base#*"${from}"}"
    fi
    quiet=1
    suite_failures=0
    run_suite
    quiet=""
    if [[ "${suite_failures}" -eq 0 ]]; then
      fail "변이 「${MUTATION_NAMES[i]}」: 사례가 하나도 실패하지 않음"
    else
      pass "변이 「${MUTATION_NAMES[i]}」 잡힘"
    fi
    readme_text="${base}"
    restart_block="${restart_base}"
    loop_block="${loop_base}"
  done
}

main() {
  if ! command -v jq >/dev/null; then
    printf 'FAIL: jq 가 없다\n' >&2
    exit 1
  fi
  # shellcheck disable=SC2031
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/workers_watch_runbook_test.XXXXXX")" ||
    exit 1
  trap remove_tmp_root EXIT
  trap 'exit 1' INT TERM

  if load_readme; then
    run_suite
    test_runbook_prose
    test_no_comment_in_blocks
    test_zsh_interactive
    test_zsh_restart_unset
    test_thumbnail_loop_paste
    test_mutations
  fi

  remove_tmp_root
  if [[ -e "${tmp_root}" ]]; then
    fail "임시 디렉터리가 남음: ${tmp_root}"
  fi
  tmp_root=""
  if [[ $((passes + skips)) -ne "${EXPECTED_CASES}" ]]; then
    fail "사례 수가 다름 — 통과 ${passes} + skip ${skips} ≠ ${EXPECTED_CASES}"
  fi
  if [[ "${failures}" -ne 0 ]]; then
    printf 'workers_watch_runbook_test: %s건 실패(통과 %s건 · skip %s건)\n' \
      "${failures}" "${passes}" "${skips}" >&2
    exit 1
  fi
  printf 'workers_watch_runbook_test: 전부 통과(%s건 · skip %s건 · %s\n' \
    "${passes}" "${skips}" '임시 디렉터리 잔여 0)'
}

main "$@"
