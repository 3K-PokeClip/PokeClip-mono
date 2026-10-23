#!/usr/bin/env bash
#
# 송출 인증 응답기의 거부 목록 동기화(응답기 모드 전용) — 긴급 절단 · 해제 때
# 상자에서 SSM 으로 돌린다(실행 직전 승인 · 다른 응답기 운영 스크립트와 순차).
#
# 차례:
#   1. 응답기 컨테이너가 실행 중 하나가 아니면(옛 모드 · 정지) 멈춘다 — 아무것도
#      받거나 쓰거나 띄우지 않는다.
#   2. 거부 목록 SSM 파라미터를 거부 목록 디렉터리의 임시 파일로 받는다(argv
#      에는 이름만 · 상한).
#   3. 꼴(lib/deny_tokens.sh — 첫 줄 머리 줄 · NUL · 크기)을 보고 rename 으로
#      둔다. 응답기는 요청마다 파일을 다시 읽으므로 덧붙임은 재시작 없이 곧 선다.
#   4. 실행 중 응답기의 healthcheck 를 부른다 — /healthz 는 거부 목록을 다시
#      읽어 틀린 줄이 있거나 못 읽으면 실패한다(FAIL health). 그때 응답기가 새
#      송출을 다루는 법은 둘이다(media/internal/ingestauth 의 denylist.go ·
#      publish.go denylistDenial):
#      - 틀린 줄(토큰 꼴 아님)이 있으면 그 줄의 값은 막히지 않는다(건너뜀). 같은
#        파일의 유효 토큰은 막힌다.
#      - 파일을 못 읽으면 모든 새 송출을 거부한다.
#      되돌리면 이번에 더한 유효 토큰도 풀리므로 되돌리지 않는다 — 고친 값을
#      다시 동기화한다.
#   5. --release 면 응답기만 재생성하고 healthy 를 기다린다(lib/ops.sh). 응답기는
#      한 번 본 토큰을 프로세스가 끝날 때까지 쥐므로, 해제는 재생성으로만 선다.
#
# 출력(표준 출력 한 줄):
#   INGEST AUTH DENY SYNC OK [release] tokens=<N>
#       N 은 설치한 파일의 유효 토큰 수다(주석 · 빈 줄 · 중복 제외 — 응답기
#       파서와 같은 줄 문법). 토큰은 공개 식별자라 수는 비밀이 아니다. streamid
#       를 통째로 붙여 넣으면(`#!::r=…`) 그 줄은 주석이 되어 막히지 않는다 —
#       기대한 수와 N 을 대조해 그 실수를 드러낸다.
#   INGEST AUTH DENY SYNC FAIL <단계>
#       usage(종료 2) · config · not-running · ssm · invalid · install · health ·
#       recreate · unhealthy(그 밖 종료 1).
#
# 읽는 변수(lib/ops.sh 머리 주석의 것 더하기):
#   INGEST_AUTH_DENYLIST_PARAM  필수. 거부 목록 SSM 파라미터 이름.
#   INGEST_AUTH_DENY_DIR        기본 /var/lib/pokeclip-media/ingest-auth/deny
#                               (절대 경로 — 시작 스크립트와 같은 기본값).
#   INGEST_AUTH_GID             기본 10003(응답기 이미지 USER 의 그룹).

# shellcheck source-path=SCRIPTDIR

set -euo pipefail
set +x +a
umask 077

BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BIN_DIR
# shellcheck source=lib/ops.sh
source "${BIN_DIR}/lib/ops.sh"
# shellcheck source=lib/deny_tokens.sh
source "${BIN_DIR}/lib/deny_tokens.sh"

readonly LINE_PREFIX='INGEST AUTH DENY SYNC'

deny_tmp=""

# 끝날 때 임시 파일과 상한 실행 중인 자식을 정리한다(EXIT 트랩).
on_exit() {
  if [[ -n "${deny_tmp}" ]]; then
    rm -f -- "${deny_tmp}"
  fi
  ops::on_exit
}

# 실패 판정 줄을 내고 끝낸다.
fail() {
  ops::say "${LINE_PREFIX} FAIL $1"
  exit "${2:-1}"
}

#######################################
# 파일의 유효 토큰 수를 낸다 — 줄마다 # 뒤를 떼고 C 로캘 공백을 걷은 값 가운데
# 빈 값이 아닌 것의 서로 다른 수(응답기 parseDenyList 의 줄 문법). 꼴이 틀린
# 값도 센다 — 그 줄은 healthcheck 가 잡는다.
# Arguments:
#   파일.
#######################################
count_tokens() {
  LC_ALL=C awk '{
    sub(/#.*/, "")
    gsub(/^[[:space:]]+|[[:space:]]+$/, "")
    if ($0 != "" && !($0 in seen)) { seen[$0] = 1; n++ }
  } END { print n + 0 }' "$1"
}

#######################################
# 거부 목록을 받아 꼴을 보고 둔다. 실패 단계로 끝낸다.
# Globals:
#   deny_tmp, deny_count
# Arguments:
#   파라미터 이름, 디렉터리, 그룹, 상한(초).
#######################################
sync_file() {
  local name="$1" dir="$2" gid="$3" limit="$4"
  if ! deny_tokens::prepare_dir "${dir}" "${gid}" 2>/dev/null \
    || ! deny_tmp="$(mktemp "${dir}/.${DENY_TOKENS_FILE_NAME}.XXXXXX" \
      2>/dev/null)"; then
    fail install
  fi
  ops::bounded "${limit}" aws ssm get-parameter --name "${name}" \
    --query Parameter.Value --output text </dev/null >"${deny_tmp}" \
    2>/dev/null || fail ssm
  deny_tokens::valid "${deny_tmp}" || fail invalid
  deny_count="$(count_tokens "${deny_tmp}")" || fail invalid
  deny_tokens::install "${deny_tmp}" "${dir}/${DENY_TOKENS_FILE_NAME}" \
    "${gid}" 2>/dev/null || fail install
  deny_tmp=""
}

main() {
  local release=0 limit wait file name dir gid project tag=""
  deny_count=0
  if (( $# > 1 )) || [[ $# == 1 && "$1" != --release ]]; then
    fail usage 2
  fi
  (( $# == 0 )) || release=1
  trap on_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  name="${INGEST_AUTH_DENYLIST_PARAM:-}"
  dir="${INGEST_AUTH_DENY_DIR:-/var/lib/pokeclip-media/ingest-auth/deny}"
  gid="${INGEST_AUTH_GID:-10003}"
  if ! limit="$(ops::timeout)" || ! wait="$(ops::health_wait)" \
    || ! file="$(ops::compose_file)" || [[ -z "${name}" ]] \
    || [[ "${dir}" != /?* || ! "${gid}" =~ ^[0-9]+$ ]]; then
    fail config
  fi
  ops::one_container "${OPS_INGEST_AUTH_SERVICE}" "${limit}" >/dev/null \
    || fail not-running
  sync_file "${name}" "${dir}" "${gid}" "${limit}"
  project="$(ops::project)"
  ops::bounded "${limit}" docker compose -p "${project}" -f "${file}" \
    exec -T "${OPS_INGEST_AUTH_SERVICE}" /ingest-auth healthcheck \
    </dev/null >&2 || fail health
  if (( release )); then
    tag=' release'
    ops::recreate_ingest_auth "${file}" "${limit}" "${wait}" >/dev/null \
      || { (( $? == 1 )) && fail recreate; fail unhealthy; }
  fi
  ops::say "${LINE_PREFIX} OK${tag} tokens=${deny_count}"
}

main "$@"
