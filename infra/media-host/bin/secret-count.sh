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
# (TEST_STREAM_KEY_SECRET_ARN)도 — passphrase 를 싣는 루프백은 송출 인증(M15)
# 반영 뒤에만 돈다(kty 86차 R4). 값은 Secrets Manager 에서 직접 읽어 셸 변수에만
# 둔다(argv 에는 ARN 만). box 면 사이드카의 MediaMTX API 비밀번호(시작 스크립트가
# RUNTIME_DIR/mtx_api_pass 에 둔 이번 기동의 값 — root 로 읽는다)와 그 Basic
# 값(「pokeclip-sidecar:<비밀번호>」 의 base64 — 줄바꿈 없이)도 대조한다. 한계:
# 비밀번호는 기동마다 새로 만들어지므로 이전 기동의 로그에 남은 옛 비밀번호는
# 대조하지 못하고, ssm 은 그 파일을 읽을 수 없어 대조하지 않는다. 대조는
# `printf '%s\n' … | LC_ALL=C grep -a -F -c -f - <파일>` 로만 한다 — 값은 표준
# 입력으로만 grep 에 가고, 바이너리 · 깨진 바이트가 섞인 로그도 줄 단위로
# 센다. 대조할 입력은 스크래치 파일(0600)에 먼저 받고 끝나면 지운다.
#
# 출력(표준 출력 한 줄):
#   SECRET COUNT <box|ssm> <n>   값이 든 줄 수(두 값이면 어느 하나라도 든 줄).
#   SECRET COUNT FAIL <단계>     usage · config · cdn-secret · test-key ·
#                                api-pass(box — 비밀번호 파일 없음 · 꼴 밖) ·
#                                journal · mediamtx · nginx · docker-logs ·
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
unset -v values value first pass basic

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
# 기동마다 만드는 16진 64자).
readonly SIDECAR_API_USER='pokeclip-sidecar'
readonly API_PASS_PATTERN='^[0-9a-f]{64}$'

step=""

#######################################
# 실패 줄을 내고 끝낸다.
# Arguments:
#   단계, 종료 코드(기본 1).
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
# 이번 기동의 사이드카 API 비밀번호와 그 Basic 값을 대조 값(부르는 main 의
# 지역 배열 values)에 더한다. 비밀번호는 내장 read · printf 로만 다루고
# base64 에는 표준 입력으로만 넘긴다.
# Globals:
#   RUNTIME_DIR, values(main 의 지역 — 동적 범위)
# Returns:
#   0 더함 / 1 파일 없음 · 못 읽음 · 꼴 밖.
#######################################
add_api_pass_values() {
  local file="${RUNTIME_DIR:-/run/pokeclip-media}/mtx_api_pass" pass basic
  [[ -f "${file}" && -r "${file}" ]] || return 1
  IFS= read -r pass <"${file}" || return 1
  [[ "${pass}" =~ ${API_PASS_PATTERN} ]] || return 1
  basic="$(printf '%s:%s' "${SIDECAR_API_USER}" "${pass}" | base64 \
    | tr -d '\n')" || return 1
  [[ -n "${basic}" ]] || return 1
  values+=("${pass}" "${basic}")
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
    add_api_pass_values || fail api-pass
  fi
  ops::make_scratch
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
