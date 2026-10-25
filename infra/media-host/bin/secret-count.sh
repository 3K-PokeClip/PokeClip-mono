#!/usr/bin/env bash
#
# 비밀 문자열 건수 — O7f 9(루프백 뒤 「SSM 기록 · 로그 0건」)와 O8a 3 이 쓴다.
#
# 사용:
#   secret-count.sh [--passphrase] box
#       상자에서 SSM 으로 돌린다(실행 직전 승인). 유닛 저널(pokeclip-media) ·
#       비부착 서비스 태그 저널 셋(pokeclip-media-sidecar · -ingest-auth ·
#       -janitor) · mediamtx · nginx 컨테이너(정지한 것 포함)의 docker logs 를
#       대조한다. 사이드카는 docker logs 가 아니라 태그 저널로 읽는다 — 엔진이
#       다시 띄운 사이드카의 앞 컨테이너 로그까지 남는 곳이다. docker inspect ·
#       exec · env 는 쓰지 않는다.
#   secret-count.sh [--passphrase] ssm <command-id>
#       kty 로컬에서 돌린다. 그 SSM 명령의 인스턴스마다 기록(표준 출력 · 오류
#       — aws ssm get-command-invocation, 읽기)을 대조한다. 명령과, 출력을 받은
#       바로 그 응답의 invocation 상태가 끝난 상태(Success · Failed — 둘 다
#       끝까지 돈 기록)가 아니면 실패다(진행 중이면 출력이 비어 0 건으로
#       보이므로). API 는 표준 출력의 처음
#       24,000자 · 표준 오류의 처음 8,000자만 준다 — 그 한도에 닿은 기록은
#       뒷부분이 잘렸을 수 있어 ssm-truncated 로 실패한다(바이트로 세므로
#       여러 바이트 글자가 많으면 한도보다 일찍 실패할 수 있다 — 안전한 쪽).
#
# 대조 값: CDN 비밀(CDN_SECRET_ARN). --passphrase 면 시험 스트림 키 passphrase
# (TEST_STREAM_KEY_SECRET_ARN)도 — passphrase 를 싣는 루프백은 응답기
# 모드(ingest_auth_image 찬 구성) 상자에서만 돈다. 값은 Secrets Manager 에서
# 직접 읽어 셸 변수에만 둔다(argv 에는 ARN 만). box 면 사이드카의 MediaMTX API
# 비밀번호(시작 스크립트가 RUNTIME_DIR/mtx_api_pass 에 둔 이번 기동의 값 —
# root 로 읽는다)와 그 Basic 값(「pokeclip-sidecar:<비밀번호>」 의 base64 —
# 줄바꿈 없이)도 대조한다. 그 파일이 없거나 꼴 밖이면 실패다 — box 는 스택이
# 떠 있을 때만 쓴다(정상 정지 뒤에는 compose down 이 컨테이너를 지워 셀 로그가
# 없다). 한계: 비밀번호는 기동마다 새로 만들어지므로 이전 기동의 로그에 남은 옛
# 비밀번호는 대조하지 못하고, ssm 은 그 파일을 읽을 수 없어 대조하지 않는다.
#
# box 응답기 모드: RUNTIME_DIR/ingest-auth 의 ingest_basic_pass ·
# stream_key_resolve_token 과 렌더본 RUNTIME_DIR/mediamtx.yml 의 잠금 두 줄
# (srtPublishPassphrase · srtReadPassphrase) — 표지 넷 — 이 모두 있으면 응답기
# 모드로 보고 넷을 더 대조한다: 호출자 Basic 비밀번호와 그 Basic 머리 값
# (「pokeclip-mediamtx:<비밀번호>」 의 base64 — 줄바꿈 없이) · 4C 토큰 · 잠금
# 둘. 4C 토큰은 원문과 함께 slog JSON 처리기가 문자열로 낼 꼴(「\」 ·
# 「"」 · U+2028 · U+2029 를 이스케이프한 것 — 원문과 다를 때만)도 대조한다.
# 표지가 하나도 없으면 옛 모드(위 대조만)다. 일부만 있거나, 있는 것이 시작
# 스크립트가 쓰는 꼴 밖이면(Basic 파일은 16진 64자 한 줄 · 4C 파일은 끝
# 줄바꿈 하나의 한 줄 · 4096바이트 이하이고 값은 시작 스크립트
# read_resolve_token 이 받는 꼴 — 빈 값 · None · 제어 문자(C1 포함) · 앞뒤
# 공백 없음 · 잠금 줄은 「  키: '16진 64자'」 정확히 한 줄씩 · 링크 아님)
# 실패다. ssm 은 넷도 대조하지 않는다(한계 — 같은 까닭). 대조는
# `printf '%s\n' … | LC_ALL=C grep -a -F -c -f - <파일>` 로만 한다 — 값은 표준
# 입력으로만 grep 에 가고, 바이너리 · 깨진 바이트가 섞인 로그도 줄 단위로
# 센다. 대조할 입력은 스크래치 파일(0600)에 먼저 받고 끝나면 지운다.
#
# 출력(표준 출력 한 줄):
#   SECRET COUNT <box|ssm> <n>   값이 든 줄 수(두 값이면 어느 하나라도 든 줄).
#   SECRET COUNT FAIL <단계>     usage · config · cdn-secret · test-key ·
#                                api-pass(box — 비밀번호 파일 없음 · 못 읽음 ·
#                                파일 전체가 16진 64자 한 줄이 아님) ·
#                                ingest-auth(box — 응답기 표지 일부만 · 꼴 밖 ·
#                                못 읽음) ·
#                                scratch · journal · mediamtx · nginx ·
#                                docker-logs ·
#                                ssm-command · ssm-list · ssm-status ·
#                                ssm-output · ssm-truncated · grep.
#                                0 건이 아니다.
# 종료 코드: 0 셈 / 1 실패 / 2 인자 오류.
#
# 읽는 변수: RUNTIME_DIR(box — 기본 /run/pokeclip-media, 시작 스크립트와 같다).
# 그 밖은 lib/ops.sh 머리 주석.

# shellcheck source-path=SCRIPTDIR

set -euo pipefail
set +x +a
umask 077
ulimit -c 0
# 비밀 · 그것이 든 원문을 담는 변수 이름이 부모 환경에서 export 된 채 들어오면
# local 이 그 속성을 이어받아 자식(grep · wc · tail · base64) 환경에 값이
# 실린다 — 쓰기 전에 지운다.
unset -v values value first pass basic raw token found line locks

BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BIN_DIR
# shellcheck source=lib/ops.sh
source "${BIN_DIR}/lib/ops.sh"

readonly UNIT_NAME='pokeclip-media'
readonly DETACHED_TAGS=(pokeclip-media-sidecar pokeclip-media-ingest-auth
  pokeclip-media-janitor)
readonly ATTACHED_SERVICES=(mediamtx nginx)
readonly COMMAND_ID_PATTERN='^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
readonly INSTANCE_ID_PATTERN='^i-[0-9a-f]{8,17}$'
# get-command-invocation 이 주는 출력 한도(글자) — aws ssm get-command-invocation
# 도움말의 StandardOutputContent · StandardErrorContent 「max」.
readonly SSM_STDOUT_LIMIT=24000
readonly SSM_STDERR_LIMIT=8000
# 끝까지 돈 상태 — 명령(list-commands)과 invocation 의 Status · StatusDetails.
readonly SSM_DONE_PATTERN='^(Success|Failed)$'
# 사이드카 API 사용자 이름(compose MTX_API_USER) · 비밀번호 꼴(시작 스크립트가
# 기동마다 만드는 16진 64자) · 파일 크기(그 64자 + 끝 줄바꿈 하나 — 시작
# 스크립트가 쓰는 꼴 하나만 받는다).
readonly SIDECAR_API_USER='pokeclip-sidecar'
readonly API_PASS_PATTERN='^[0-9a-f]{64}$'
readonly API_PASS_FILE_BYTES=65
# 응답기 모드(시작 스크립트와 같은 이름 · 꼴): 파일 디렉터리 · Basic 비밀번호 ·
# 4C 토큰 파일, 호출자 Basic 사용자(mediamtx.yml.tmpl authHTTPAddress), 4C
# 파일 크기 상한(응답기 secretfile 과 같다), 렌더본 이름 · 크기 상한, 잠금 키.
readonly INGEST_AUTH_DIR_NAME='ingest-auth'
readonly BASIC_PASS_NAME='ingest_basic_pass'
readonly RESOLVE_TOKEN_NAME='stream_key_resolve_token'
readonly INGEST_AUTH_BASIC_USER='pokeclip-mediamtx'
readonly RESOLVE_TOKEN_MAX_BYTES=4096
readonly RENDER_NAME='mediamtx.yml'
readonly RENDER_MAX_BYTES=1048576
readonly LOCK_KEYS=(srtPublishPassphrase srtReadPassphrase)
# slog JSON 처리기가 문자열에서 이스케이프하는 것 중 4C 꼴 안에 들 수 있는 것과
# 그 꼴(Go log/slog json_handler.go appendEscapedJSONString — 「\」 를 맨
# 앞에 둔다: 뒤에서 넣는 역슬래시를 다시 바꾸지 않게). 같은 자리끼리 짝이다.
# 제어 문자는 4C 꼴 밖이라 없다. 깨진 UTF-8 은 U+FFFD 로 바뀌어 원문 조각을
# 되찾을 수 없어 대조하지 않는다(한계).
readonly JSON_ESCAPE_FROM=("\\" '"' $'\xe2\x80\xa8' $'\xe2\x80\xa9')
readonly JSON_ESCAPE_TO=("\\\\" "\\\"" '\u2028' '\u2029')

step=""

#######################################
# 실패 줄을 내고 끝낸다.
# Arguments:
#   단계, 종료 코드(기본 1).
# Outputs:
#   「SECRET COUNT FAIL <단계>」 한 줄(표준 출력).
#######################################
fail() {
  ops::say "SECRET COUNT FAIL $1"
  exit "${2:-1}"
}

#######################################
# box 입력(저널 넷 · 부착 서비스 docker logs)을 파일에 모은다.
# Globals:
#   step
# Arguments:
#   상한(초), 모을 파일.
# Returns:
#   0 모음 / 1 실패(단계는 step).
#######################################
collect_box() {
  local limit="$1" out="$2" tag service ids id
  if ! ops::bounded "${limit}" journalctl -u "${UNIT_NAME}" -q --no-pager \
    -o cat </dev/null >>"${out}" 2>/dev/null; then
    step=journal
    return 1
  fi
  for tag in "${DETACHED_TAGS[@]}"; do
    if ! ops::bounded "${limit}" journalctl -t "${tag}" -q --no-pager -o cat \
      </dev/null >>"${out}" 2>/dev/null; then
      step=journal
      return 1
    fi
  done
  for service in "${ATTACHED_SERVICES[@]}"; do
    if ! ids="$(ops::containers "${service}" all "${limit}")" \
      || [[ -z "${ids}" ]]; then
      step="${service}"
      return 1
    fi
    for id in ${ids}; do
      # 컨테이너의 표준 오류도 로그다 — 함께 모은다.
      if ! ops::bounded "${limit}" docker logs "${id}" </dev/null >>"${out}" \
        2>&1; then
        step=docker-logs
        return 1
      fi
    done
  done
}

#######################################
# 파일 내용을 그대로 낸다(끝에 x 를 붙여 — 끝 줄바꿈을 지키게). 링크는 따르지
# 않는다(시작 스크립트도 링크 자리에는 쓰지 않는다). wc · head 는 상한 안에서
# 돌린다 — 그 자리가 FIFO 로 바뀌어 있어도 매달리지 않게. 내용은 head 의 표준
# 출력으로만 받는다(argv 에는 경로만). 낸 바이트 수가 파일 크기와 다르면(NUL
# 이 빠짐 · 도중에 바뀜) 실패다.
# Arguments:
#   상한(초), 파일, 크기 상한(바이트).
# Outputs:
#   파일 내용 + 「x」(표준 출력).
# Returns:
#   0 냄 / 1 파일 없음 · 링크 · 못 읽음 · 상한 초과.
#######################################
read_capped() {
  local limit="$1" file="$2" max="$3" size raw LC_ALL=C
  [[ ! -L "${file}" && -f "${file}" && -r "${file}" ]] || return 1
  size="$(ops::bounded "${limit}" wc -c -- "${file}" </dev/null)" || return 1
  read -r size _ <<<"${size}"
  [[ "${size}" =~ ^[0-9]+$ ]] && (( size <= max )) || return 1
  raw="$(ops::bounded "${limit}" head -c "${max}" -- "${file}" </dev/null \
    && printf x)" || return 1
  (( ${#raw} == size + 1 )) || return 1
  printf '%s' "${raw}"
}

#######################################
# 시작 스크립트가 쓰는 자격 파일(「16진 64자 + 끝 줄바꿈」 한 줄 — 파일 전체)의
# 값을 낸다.
# Arguments:
#   상한(초), 파일.
# Outputs:
#   16진 64자(표준 출력 — 줄바꿈 없이).
# Returns:
#   0 냄 / 1 파일 없음 · 못 읽음 · 꼴 밖.
#######################################
read_credential() {
  local raw pass
  raw="$(read_capped "$1" "$2" "${API_PASS_FILE_BYTES}")" || return 1
  pass="${raw%$'\n'x}"
  [[ "${raw}" == "${pass}"$'\n'x && "${pass}" =~ ${API_PASS_PATTERN} ]] \
    || return 1
  printf '%s' "${pass}"
}

#######################################
# 「사용자:비밀번호」 의 Basic 값(base64 — 줄바꿈 없이)을 낸다. 비밀번호는
# base64 에 표준 입력으로만 넘긴다.
# Arguments:
#   사용자, 비밀번호.
# Outputs:
#   Basic 값(표준 출력 — 줄바꿈 없이).
# Returns:
#   0 냄 / 1 base64 실패 · 빈 값.
#######################################
basic_value() {
  local basic
  basic="$(printf '%s:%s' "$1" "$2" | base64 | tr -d '\n')" || return 1
  [[ -n "${basic}" ]] || return 1
  printf '%s' "${basic}"
}

#######################################
# 이번 기동의 사이드카 API 비밀번호와 그 Basic 값을 대조 값(부르는 main 의
# 지역 배열 values)에 더한다.
# Globals:
#   RUNTIME_DIR, values(main 의 지역 — 동적 범위)
# Arguments:
#   상한(초).
# Returns:
#   0 더함 / 1 파일 없음 · 못 읽음 · 꼴 밖.
#######################################
add_api_pass_values() {
  local pass basic
  pass="$(read_credential "$1" \
    "${RUNTIME_DIR:-/run/pokeclip-media}/mtx_api_pass")" || return 1
  basic="$(basic_value "${SIDECAR_API_USER}" "${pass}")" || return 1
  values+=("${pass}" "${basic}")
}

#######################################
# 4C 토큰 파일(값 + 끝 줄바꿈 하나 — 4096바이트 이하)의 값을 낸다. 값의 꼴은
# 시작 스크립트 read_resolve_token 과 같다 — 빈 값 · None(aws text 출력의 빈
# 값) · 제어 문자(줄바꿈 · 탭 포함) · C1 제어 문자(U+0080–U+009F 의 UTF-8 두
# 바이트) · 앞뒤 공백은 꼴 밖이다.
# Arguments:
#   상한(초), 파일.
# Outputs:
#   토큰(표준 출력 — 줄바꿈 없이).
# Returns:
#   0 냄 / 1 파일 없음 · 못 읽음 · 꼴 밖.
#######################################
read_resolve_token() {
  local raw token LC_ALL=C
  local c1=$'\xc2['$'\x80''-'$'\x9f'']'
  raw="$(read_capped "$1" "$2" "${RESOLVE_TOKEN_MAX_BYTES}")" || return 1
  token="${raw%$'\n'x}"
  [[ "${raw}" == "${token}"$'\n'x ]] || return 1
  if [[ -z "${token}" || "${token}" == None || "${token}" == *[[:cntrl:]]* \
    || "${token}" == ' '* || "${token}" == *' ' ]]; then
    return 1
  fi
  # shellcheck disable=SC2053 # 패턴으로 대조한다.
  [[ "${token}" != *${c1}* ]] || return 1
  printf '%s' "${token}"
}

#######################################
# 값을 slog JSON 처리기가 문자열로 낼 꼴로 바꿔 낸다(JSON_ESCAPE_FROM →
# JSON_ESCAPE_TO). 「${v//찾을/바꿀}」 은 쓰지 않는다 — bash 5.2 부터 바꿀
# 문자열의 역슬래시를 따로 다뤄(patsub_replacement) 3.2 와 결과가 다르다.
# Globals:
#   JSON_ESCAPE_FROM, JSON_ESCAPE_TO
# Arguments:
#   값.
# Outputs:
#   바꾼 꼴(표준 출력 — 줄바꿈 없이).
#######################################
json_escaped() {
  local LC_ALL=C value="$1" raw i from
  for (( i = 0; i < ${#JSON_ESCAPE_FROM[@]}; i++ )); do
    from="${JSON_ESCAPE_FROM[i]}"
    raw="${value}"
    value=""
    while [[ "${raw}" == *"${from}"* ]]; do
      value+="${raw%%"${from}"*}${JSON_ESCAPE_TO[i]}"
      raw="${raw#*"${from}"}"
    done
    value+="${raw}"
  done
  printf '%s' "${value}"
}

#######################################
# 렌더본의 잠금 줄을 키마다 「줄 수<탭>마지막 줄」 한 줄로 낸다(LOCK_KEYS
# 순서). 렌더본이 없으면 줄 수 0 이다. 값은 awk 의 표준 출력으로만 받는다.
# Arguments:
#   상한(초), 렌더본.
# Outputs:
#   키마다 「줄 수<탭>마지막 줄」 한 줄(표준 출력 — 두 줄).
# Returns:
#   0 냄 / 1 링크 · 일반 파일 아님 · 못 읽음 · 상한 초과.
#######################################
lock_lines() {
  local limit="$1" file="$2" size
  if [[ ! -e "${file}" && ! -L "${file}" ]]; then
    printf '0\t\n0\t\n'
    return 0
  fi
  [[ ! -L "${file}" && -f "${file}" && -r "${file}" ]] || return 1
  size="$(ops::bounded "${limit}" wc -c -- "${file}" </dev/null)" || return 1
  read -r size _ <<<"${size}"
  [[ "${size}" =~ ^[0-9]+$ ]] && (( size <= RENDER_MAX_BYTES )) || return 1
  # shellcheck disable=SC2016 # awk 프로그램이다(셸이 펼치지 않는다).
  ops::bounded "${limit}" awk -v k1="${LOCK_KEYS[0]}" -v k2="${LOCK_KEYS[1]}" '
    { key = $0; sub(/^[ \t]*/, "", key); sub(/:.*/, "", key) }
    key == k1 { n1++; l1 = $0 }
    key == k2 { n2++; l2 = $0 }
    END { printf "%d\t%s\n%d\t%s\n", n1, l1, n2, l2 }' "${file}" </dev/null
}

#######################################
# 응답기 모드면 대조 넷(Basic 비밀번호 · 그 Basic 머리 값 · 4C 토큰 — 원문과
# 다르면 그 JSON 꼴도 · 잠금 둘)을 대조 값(main 의 지역 배열 values)에
# 더한다. 표지 넷(Basic 파일 · 4C 파일 · 잠금 줄 둘)이 하나도 없으면 옛 모드라
# 더하지 않는다.
# Globals:
#   RUNTIME_DIR, values(main 의 지역 — 동적 범위)
# Arguments:
#   상한(초).
# Returns:
#   0 더함 · 옛 모드 / 1 표지 일부만 · 꼴 밖 · 못 읽음.
#######################################
add_ingest_auth_values() {
  local limit="$1" dir="${RUNTIME_DIR:-/run/pokeclip-media}"
  local ia found marks=0 key n line pattern pass basic token value locks=()
  local basic_file resolve_file
  ia="${dir}/${INGEST_AUTH_DIR_NAME}"
  basic_file="${ia}/${BASIC_PASS_NAME}"
  resolve_file="${ia}/${RESOLVE_TOKEN_NAME}"
  [[ ! -e "${basic_file}" && ! -L "${basic_file}" ]] || marks=$((marks + 1))
  [[ ! -e "${resolve_file}" && ! -L "${resolve_file}" ]] \
    || marks=$((marks + 1))
  found="$(lock_lines "${limit}" "${dir}/${RENDER_NAME}" && printf x)" \
    || return 1
  for key in "${LOCK_KEYS[@]}"; do
    line="${found%%$'\n'*}"
    found="${found#*$'\n'}"
    n="${line%%$'\t'*}"
    line="${line#*$'\t'}"
    [[ "${n}" == 0 ]] && continue
    marks=$((marks + 1))
    pattern="^  ${key}: '([0-9a-f]{64})'\$"
    [[ "${n}" == 1 && "${line}" =~ ${pattern} ]] || return 1
    locks+=("${BASH_REMATCH[1]}")
  done
  unset found line
  (( marks == 0 )) && return 0
  (( marks == 4 )) || return 1
  pass="$(read_credential "${limit}" "${basic_file}")" || return 1
  basic="$(basic_value "${INGEST_AUTH_BASIC_USER}" "${pass}")" || return 1
  token="$(read_resolve_token "${limit}" "${resolve_file}")" || return 1
  values+=("${pass}" "${basic}" "${token}" "${locks[@]}")
  value="$(json_escaped "${token}")"
  [[ "${value}" == "${token}" ]] || values+=("${value}")
}

#######################################
# 상태 두 값(Status · StatusDetails)이 모두 끝난 상태인지 본다.
# Arguments:
#   Status, StatusDetails.
#######################################
ssm_done() {
  [[ "$1" =~ ${SSM_DONE_PATTERN} && "$2" =~ ${SSM_DONE_PATTERN} ]]
}

#######################################
# get-command-invocation 출력 필드 하나를 그 응답의 Status · StatusDetails 와
# 함께 받는다(같은 응답 — 상태와 출력을 따로 물으면 앞 조회는 끝남인데 뒤
# 조회가 오래된 진행 중 응답의 빈 출력을 받을 수 있다). 그 응답이 끝난
# 상태가 아니면 실패이고, 출력이 API 한도에 닿았으면 실패다.
# Globals:
#   step, ops_scratch
# Arguments:
#   상한(초), 명령 ID, 인스턴스 ID, 필드 이름, 한도(글자), 덧붙일 파일.
# Returns:
#   0 받음 / 1 실패(단계는 step).
#######################################
collect_ssm_field() {
  local limit="$1" command_id="$2" instance="$3" field="$4" max="$5" out="$6"
  local part="${ops_scratch}/field" first status details head_bytes bytes
  if ! ops::bounded "${limit}" aws ssm get-command-invocation \
    --command-id "${command_id}" --instance-id "${instance}" \
    --query "[Status,StatusDetails,${field}]" --output text </dev/null \
    >"${part}" 2>/dev/null; then
    step=ssm-output
    return 1
  fi
  # text 출력 = 「Status<탭>StatusDetails<탭>출력」 + 줄바꿈 하나. 출력에도
  # 탭 · 줄바꿈이 있을 수 있어 첫 줄의 앞 두 칸만 상태로 읽는다.
  IFS= read -r first <"${part}" || true
  if [[ "${first}" != *$'\t'*$'\t'* ]]; then
    step=ssm-status
    return 1
  fi
  status="${first%%$'\t'*}"
  first="${first#*$'\t'}"
  details="${first%%$'\t'*}"
  if ! ssm_done "${status}" "${details}"; then
    step=ssm-status
    return 1
  fi
  head_bytes=$(( $(printf '%s\t%s\t' "${status}" "${details}" \
    | LC_ALL=C wc -c) ))
  bytes=$(( $(wc -c <"${part}") - head_bytes - 1 ))
  if (( bytes >= max )); then
    step=ssm-truncated
    return 1
  fi
  tail -c "+$((head_bytes + 1))" "${part}" >>"${out}"
}

#######################################
# SSM 명령이 끝났는지 보고, 인스턴스마다 기록을 파일에 모은다.
# Globals:
#   step
# Arguments:
#   상한(초), 명령 ID, 모을 파일.
# Returns:
#   0 모음 / 1 실패(단계는 step).
#######################################
collect_ssm() {
  local limit="$1" command_id="$2" out="$3" listed instance count=0 status
  if ! status="$(ops::bounded "${limit}" aws ssm list-commands \
    --command-id "${command_id}" --query 'Commands[].Status' --output text \
    </dev/null 2>/dev/null)"; then
    step=ssm-command
    return 1
  fi
  if [[ ! "${status}" =~ ${SSM_DONE_PATTERN} ]]; then
    step=ssm-status
    return 1
  fi
  if ! listed="$(ops::bounded "${limit}" aws ssm list-command-invocations \
    --command-id "${command_id}" --query 'CommandInvocations[].InstanceId' \
    --output text </dev/null 2>/dev/null)"; then
    step=ssm-list
    return 1
  fi
  for instance in ${listed}; do
    if [[ ! "${instance}" =~ ${INSTANCE_ID_PATTERN} ]]; then
      step=ssm-list
      return 1
    fi
    count=$((count + 1))
  done
  if (( count == 0 )); then
    step=ssm-list
    return 1
  fi
  for instance in ${listed}; do
    collect_ssm_field "${limit}" "${command_id}" "${instance}" \
      StandardOutputContent "${SSM_STDOUT_LIMIT}" "${out}" || return 1
    collect_ssm_field "${limit}" "${command_id}" "${instance}" \
      StandardErrorContent "${SSM_STDERR_LIMIT}" "${out}" || return 1
  done
}

main() {
  local with_passphrase=0 mode command_id="" limit input count status=0
  local values=() value
  if [[ "${1:-}" == --passphrase ]]; then
    with_passphrase=1
    shift
  fi
  mode="${1:-}"
  case "${mode}:$#" in
    box:1) ;;
    ssm:2) command_id="$2" ;;
    *) fail usage 2 ;;
  esac
  if [[ "${mode}" == ssm && ! "${command_id}" =~ ${COMMAND_ID_PATTERN} ]]; then
    fail usage 2
  fi
  limit="$(ops::timeout)" || fail config
  trap ops::on_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  value="$(ops::read_secret "${CDN_SECRET_ARN:-}" "${OPS_CDN_SECRET_PATTERN}" \
    "${limit}")" || fail cdn-secret
  values+=("${value}")
  if (( with_passphrase )); then
    value="$(ops::read_secret "${TEST_STREAM_KEY_SECRET_ARN:-}" \
      "${OPS_PASSPHRASE_PATTERN}" "${limit}")" || fail test-key
    values+=("${value}")
  fi
  unset value
  if [[ "${mode}" == box ]]; then
    add_api_pass_values "${limit}" || fail api-pass
    add_ingest_auth_values "${limit}" || fail ingest-auth
  fi
  ops::make_scratch || fail scratch
  input="${ops_scratch}/input"
  : >"${input}"
  if [[ "${mode}" == box ]]; then
    collect_box "${limit}" "${input}" || fail "${step}"
  else
    collect_ssm "${limit}" "${command_id}" "${input}" || fail "${step}"
  fi
  count="$(printf '%s\n' "${values[@]}" \
    | LC_ALL=C grep -a -F -c -f - "${input}")" || status=$?
  unset values
  # grep -c 는 맞는 줄이 없으면 0 을 내고 종료 1 이다. 2 이상은 오류다.
  if (( status > 1 )) || [[ ! "${count}" =~ ^[0-9]+$ ]]; then
    fail grep
  fi
  ops::say "SECRET COUNT ${mode} ${count}"
}

main "$@"
