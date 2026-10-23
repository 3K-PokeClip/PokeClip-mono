#!/usr/bin/env bash
#
# 응답기 운영 스크립트 둘의 시험 — bin/ingest-auth-deny-sync.sh(거부 목록
# 동기화) · bin/ingest-auth-ip-limit.sh(IP 한도 전환).
#
# 판정: 표준 출력은 판정 줄 하나 · 종료 코드, 가짜 명령 기록(argv)으로 부른 꼴과
# 차례를, 파일로 원자 교체(같은 디렉터리 임시 파일 → rename — 미리 걸어 둔 하드
# 링크가 옛 내용을 그대로 쥔다)와 모드를 본다. 재생성은 가짜 docker compose 가
# 흉내 낸다(새 ID · healthy · 새 컨테이너 저널의 started 줄 — ip_limit 은 만들 때
# 읽은 env 파일 값). 실행 도구는 다른 운영 시험과 같다(test/lib/ops_fixture.sh).
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/ingest_auth_ops_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
MEDIA_HOST_DIR="$(cd -- "${TEST_DIR}/.." && pwd)"
readonly MEDIA_HOST_DIR
# shellcheck source=lib/harness.sh
source "${TEST_DIR}/lib/harness.sh"
# shellcheck source=lib/ops_fixture.sh
source "${TEST_DIR}/lib/ops_fixture.sh"

readonly CASE_NAMES='D1 D2 D3 D4 D5 D6 D7 D8 D9 I1 I2 I3 I4 I5 I6 I7 I8 I9'
readonly DENY_SCRIPT='bin/ingest-auth-deny-sync.sh'
readonly LIMIT_SCRIPT='bin/ingest-auth-ip-limit.sh'
readonly DENY_PARAM='pcbench-edge-proxy-fake-deny-param'
readonly IA_ID='pcbenchingestauth01'
readonly IA_TAG='pokeclip-media-ingest-auth'
readonly HEADER='# pokeclip deny-tokens v1'
# 공개 식별자 꼴(26자 Crockford 대문자) — 비밀이 아니다.
readonly TOKEN_A='M15OPSTEST0000000000000001'
readonly TOKEN_B='M15OPSTEST0000000000000002'
readonly OLD_DENY="${HEADER}
${TOKEN_A}"

#######################################
# 응답기 사례 디렉터리 — 실행 중 응답기 하나 · compose 파일 자리 · 거부 목록
# 디렉터리(옛 파일) · env 파일(on) · 가짜 chown.
# Globals:
#   ops_dir, ops_env
#######################################
setup_case() {
  ops_fixture::new_case
  ln -s -- "${TEST_DIR}/ops_fakebin/chown" "${ops_dir}/bin/chown"
  mkdir -p -- "${ops_dir}/etc" "${ops_dir}/deny"
  : >"${ops_dir}/etc/compose.yml"
  printf 'INGEST_AUTH_IP_LIMIT=on\n' >"${ops_dir}/etc/ingest-auth.env"
  printf '%s\n' "${OLD_DENY}" >"${ops_dir}/deny/deny-tokens"
  ln -- "${ops_dir}/deny/deny-tokens" "${ops_dir}/deny.keep"
  ln -- "${ops_dir}/etc/ingest-auth.env" "${ops_dir}/env.keep"
  ops_fixture::container "${IA_ID}" ingest-auth running
  ops_env+=("MEDIA_COMPOSE_FILE=${ops_dir}/etc/compose.yml"
    "INGEST_AUTH_DENY_DIR=${ops_dir}/deny"
    "INGEST_AUTH_DENYLIST_PARAM=${DENY_PARAM}" 'INGEST_AUTH_GID=10003'
    'MEDIA_OPS_HEALTH_WAIT=3')
}

# 가짜 SSM 파라미터 값을 둔다(aws text 출력이 끝 줄바꿈을 붙인다).
ssm_value() { printf '%s' "$1" >"${ops_dir}/state/ssm.param.${DENY_PARAM}"; }

# 상태 표식 파일을 둔다.
mark() { : >"${ops_dir}/state/$1"; }

#######################################
# n 번째 기록의 argv 가 정확히 이 줄들인지 본다.
# Arguments:
#   명령, 번호, 기대 argv ….
#######################################
expect_argv() {
  local file="${ops_dir}/log/$1.$2.argv" want
  shift 2
  want="$(printf '%s\n' "$@")"
  if [[ ! -f "${file}" || "$(cat -- "${file}")" != "${want}" ]]; then
    harness::add_reason "${file##*/} 이 「$*」 아님: $(tr '\n' ' ' \
      <"${file}" 2>/dev/null)"
    return 1
  fi
}

#######################################
# 첫 argv 가 이 하위 명령인 docker 기록 수가 기대와 같은지 본다.
# Arguments:
#   하위 명령, 기대 수.
#######################################
expect_docker_count() {
  local got
  got="$(ops_fixture::docker_count "$1")"
  if [[ "${got}" != "$2" ]]; then
    harness::add_reason "docker $1 ${got}번(기대 $2)"
    return 1
  fi
}

#######################################
# 파일 내용이 기대와 같은지 본다.
# Arguments:
#   파일, 기대 내용(끝 줄바꿈 제외), 이름.
#######################################
expect_content() {
  if [[ "$(cat -- "$1" 2>/dev/null)" != "$2" ]]; then
    harness::add_reason "$3 내용이 다름: $(head -c 120 -- "$1" 2>/dev/null \
      | tr '\n' '|')"
    return 1
  fi
}

#######################################
# 파일 모드(8진 세 자리)가 기대와 같은지 본다(GNU · BSD stat).
# Arguments:
#   파일, 기대 모드, 이름.
#######################################
expect_mode() {
  local got
  got="$(stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1")"
  if [[ "${got}" != "$2" ]]; then
    harness::add_reason "$3 모드 ${got}(기대 $2)"
    return 1
  fi
}

#######################################
# 임시 파일 잔여가 없는지 본다(거부 목록 · compose 디렉터리의 점 파일 · TMPDIR).
#######################################
expect_no_temp() {
  local left
  left="$(find "${ops_dir}/deny" "${ops_dir}/etc" -name '.*' -type f \
    | awk 'END { print NR }')"
  if [[ "${left}" != 0 || "$(ops_fixture::tmp_files)" != 0 ]]; then
    harness::add_reason "임시 파일 잔여(디렉터리 ${left}개)"
    return 1
  fi
}

# 거부 목록 · env 파일이 옛 그대로이고 compose 호출이 0 인지 본다.
expect_untouched() {
  local ok=0
  expect_content "${ops_dir}/deny/deny-tokens" "${OLD_DENY}" '거부 목록' || ok=1
  expect_content "${ops_dir}/etc/ingest-auth.env" 'INGEST_AUTH_IP_LIMIT=on' \
    'env 파일' || ok=1
  expect_docker_count compose 0 || ok=1
  return "${ok}"
}

# compose 재생성 · healthcheck 기록의 기대 argv.
compose_argv() {
  printf '%s\n' compose -p "${OPS_FAKE_PROJECT}" -f \
    "${ops_dir}/etc/compose.yml" "$@"
}

# ---- 거부 목록 동기화 --------------------------------------------------------

# D1 — 덧붙임 → 원자 교체(0640 · root:<그룹>) · 실행 중 healthcheck · 재생성 0 ·
# 유효 토큰 수(주석 · 빈 줄 · 중복 제외).
case_deny_sync() {
  local ok=0 value="${HEADER}
${TOKEN_A}  # 사고 1

${TOKEN_B}
${TOKEN_A}"
  setup_case
  ssm_value "${value}"
  ops_fixture::run "${DENY_SCRIPT}" -- || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC OK tokens=2' 0 || ok=1
  expect_content "${ops_dir}/deny/deny-tokens" "${value}" '거부 목록' || ok=1
  expect_mode "${ops_dir}/deny/deny-tokens" 640 '거부 목록' || ok=1
  expect_content "${ops_dir}/deny.keep" "${OLD_DENY}" '하드 링크(옛 파일)' \
    || ok=1
  expect_argv aws 1 ssm get-parameter --name "${DENY_PARAM}" --query \
    Parameter.Value --output text || ok=1
  if ! grep -q -x -F -- '0:10003' "${ops_dir}/log/chown.2.argv" 2>/dev/null \
    || ! grep -q -F -- "${ops_dir}/deny/.deny-tokens." \
      "${ops_dir}/log/chown.2.argv"; then
    harness::add_reason '임시 파일을 0:10003 으로 맞추지 않음'
    ok=1
  fi
  expect_docker_call "$(compose_argv exec -T ingest-auth /ingest-auth \
    healthcheck)" || ok=1
  expect_docker_count compose 1 || ok=1
  expect_no_temp || ok=1
  return "${ok}"
}

#######################################
# compose 기록 가운데 이 argv(줄 묶음)와 같은 것이 있는지 본다.
# Arguments:
#   기대 argv(줄바꿈으로 이은 것).
#######################################
expect_docker_call() {
  local f
  while IFS= read -r f; do
    [[ "$(cat -- "${f}")" != "$1" ]] || return 0
  done < <(ops_fixture::calls docker)
  harness::add_reason "docker 기록에 「$(tr '\n' ' ' <<<"$1")」 없음"
  return 1
}

# D2 — --release → 설치 · healthcheck 뒤 응답기만 재생성(--no-deps) · healthy.
case_deny_release() {
  local ok=0
  setup_case
  ssm_value "${HEADER}"
  ops_fixture::run "${DENY_SCRIPT}" -- --release || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC OK release tokens=0' 0 \
    || ok=1
  expect_content "${ops_dir}/deny/deny-tokens" "${HEADER}" '거부 목록' || ok=1
  expect_docker_call "$(compose_argv up -d --no-deps --force-recreate \
    ingest-auth)" || ok=1
  expect_docker_count compose 2 || ok=1
  if ! grep -q -x -F -- 'health=healthy' "${ops_dir}/log/docker".*.argv; then
    harness::add_reason 'healthy 조회(--filter health=healthy) 없음'
    ok=1
  fi
  return "${ok}"
}

# D3 — streamid 통째 붙여 넣기(# 로 시작 → 주석) → 성공이지만 tokens=0.
case_deny_streamid_paste() {
  setup_case
  ssm_value "${HEADER}
#!::r=${TOKEN_B},m=publish"
  ops_fixture::run "${DENY_SCRIPT}" -- || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC OK tokens=0' 0
}

# D4 — 응답기 실행 중 아님(정지 · 없음 · 둘 · ps 실패) → not-running · 받기 0.
case_deny_not_running() {
  local ok=0 variant
  for variant in exited none two psfail; do
    setup_case
    rm -f -- "${ops_dir}/state/ps.ingest-auth"
    case "${variant}" in
      exited) ops_fixture::container "${IA_ID}" ingest-auth exited ;;
      two)
        ops_fixture::container "${IA_ID}" ingest-auth running
        ops_fixture::container pcbenchingestauth02 ingest-auth running
        ;;
      psfail)
        ops_fixture::container "${IA_ID}" ingest-auth running
        mark ps.fail
        ;;
    esac
    ssm_value "${HEADER}"
    ops_fixture::run "${DENY_SCRIPT}" -- --release || return 1
    ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL not-running' 1 \
      || ok=1
    [[ "$(ops_fixture::count aws)" == 0 ]] || {
      harness::add_reason "${variant}: aws 호출 있음"
      ok=1
    }
    expect_untouched || ok=1
  done
  return "${ok}"
}

# D5 — SSM 실패 · 꼴 밖(머리 줄 없음 · 공백뿐) → ssm · invalid · 옛 파일 그대로.
case_deny_ssm_invalid() {
  local ok=0 value
  setup_case
  ops_fixture::run "${DENY_SCRIPT}" -- || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL ssm' 1 || ok=1
  expect_untouched || ok=1
  expect_no_temp || ok=1
  for value in "${TOKEN_A}" '   '; do
    setup_case
    ssm_value "${value}"
    ops_fixture::run "${DENY_SCRIPT}" -- || return 1
    ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL invalid' 1 || ok=1
    expect_untouched || ok=1
    expect_no_temp || ok=1
  done
  return "${ok}"
}

# D6 — 설치 실패(디렉터리가 링크 · chown 실패) → install · 옛 파일 그대로.
case_deny_install_fail() {
  local ok=0
  setup_case
  ssm_value "${HEADER}"
  ops_fixture::run "${DENY_SCRIPT}" PCBENCH_FAKE_CHOWN_STATUS=1 -- || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL install' 1 || ok=1
  expect_untouched || ok=1
  expect_no_temp || ok=1
  setup_case
  ssm_value "${HEADER}"
  mv -- "${ops_dir}/deny" "${ops_dir}/deny.real"
  ln -s -- "${ops_dir}/deny.real" "${ops_dir}/deny"
  ops_fixture::run "${DENY_SCRIPT}" -- || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL install' 1 || ok=1
  return "${ok}"
}

# D7 — healthcheck 실패 → health · 새 파일 그대로(되돌리지 않음) · 재생성 0.
case_deny_health_fail() {
  local ok=0
  setup_case
  ssm_value "${HEADER}"
  mark health.exec.fail
  ops_fixture::run "${DENY_SCRIPT}" -- --release || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL health' 1 || ok=1
  expect_content "${ops_dir}/deny/deny-tokens" "${HEADER}" '거부 목록' || ok=1
  expect_docker_count compose 1 || ok=1
  return "${ok}"
}

# D8 — --release 의 재생성 실패 → recreate · healthy 안 됨 → unhealthy.
case_deny_release_fail() {
  local ok=0
  setup_case
  ssm_value "${HEADER}"
  mark compose.up.fail
  ops_fixture::run "${DENY_SCRIPT}" -- --release || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL recreate' 1 || ok=1
  setup_case
  ssm_value "${HEADER}"
  mark health.never
  ops_fixture::run "${DENY_SCRIPT}" -- --release || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL unhealthy' 1 || ok=1
  return "${ok}"
}

# D9 — 인자 · 설정 오류 → usage(2) · config · docker 0.
case_deny_usage_config() {
  local ok=0 args extra
  for args in --bogus '--release --release' release; do
    setup_case
    # shellcheck disable=SC2086 # 인자 묶음을 낱낱으로 넘긴다.
    ops_fixture::run "${DENY_SCRIPT}" -- ${args} || return 1
    ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL usage' 2 || ok=1
    expect_docker_count ps 0 || ok=1
  done
  for extra in INGEST_AUTH_DENYLIST_PARAM= INGEST_AUTH_DENY_DIR=deny \
    INGEST_AUTH_GID=abc MEDIA_OPS_TIMEOUT=0 MEDIA_OPS_HEALTH_WAIT=x; do
    setup_case
    ops_fixture::run "${DENY_SCRIPT}" "${extra}" -- || return 1
    ops_fixture::expect_line 'INGEST AUTH DENY SYNC FAIL config' 1 || ok=1
    expect_docker_count ps 0 || ok=1
  done
  return "${ok}"
}

# ---- IP 한도 전환 ------------------------------------------------------------

#######################################
# IP 한도 전환 성공을 본다 — 판정 줄 · env 파일(원자 교체 · 0644 · 0:0) ·
# 재생성 argv · 새 컨테이너 ID 로 거른 저널.
# Arguments:
#   on | off.
#######################################
expect_limit_ok() {
  local ok=0 env="${ops_dir}/etc/ingest-auth.env"
  ops_fixture::expect_line "INGEST AUTH IP LIMIT OK $1" 0 || ok=1
  expect_content "${env}" "INGEST_AUTH_IP_LIMIT=$1" 'env 파일' || ok=1
  expect_mode "${env}" 644 'env 파일' || ok=1
  expect_content "${ops_dir}/env.keep" 'INGEST_AUTH_IP_LIMIT=on' \
    '하드 링크(옛 파일)' || ok=1
  if ! grep -q -x -F -- '0:0' "${ops_dir}/log/chown.1.argv" 2>/dev/null \
    || ! grep -q -F -- "${ops_dir}/etc/.ingest-auth.env." \
      "${ops_dir}/log/chown.1.argv"; then
    harness::add_reason '임시 env 파일을 0:0 으로 맞추지 않음'
    ok=1
  fi
  expect_docker_call "$(compose_argv up -d --no-deps --force-recreate \
    ingest-auth)" || ok=1
  expect_docker_count compose 1 || ok=1
  expect_argv journalctl 1 -t "${IA_TAG}" \
    CONTAINER_ID_FULL=pcbenchrecreated1 -q --no-pager -o cat || ok=1
  expect_no_temp || ok=1
  return "${ok}"
}

# I1 — off → 원자 교체 · 응답기만 재생성 · started 의 ip_limit off.
case_limit_off() {
  setup_case
  ops_fixture::run "${LIMIT_SCRIPT}" -- off || return 1
  expect_limit_ok off
}

# I2 — on(이미 on 이어도 다시 쓰고 재생성한다).
case_limit_on() {
  setup_case
  ops_fixture::run "${LIMIT_SCRIPT}" -- on || return 1
  expect_limit_ok on
}

# I3 — 인자 · 설정 오류 → usage(2) · config · docker 0 · env 그대로.
case_limit_usage_config() {
  local ok=0 args extra
  for args in '' ON 'on off' --off; do
    setup_case
    # shellcheck disable=SC2086 # 인자 묶음을 낱낱으로 넘긴다.
    ops_fixture::run "${LIMIT_SCRIPT}" -- ${args} || return 1
    ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL usage' 2 || ok=1
    expect_docker_count ps 0 || ok=1
  done
  for extra in MEDIA_COMPOSE_FILE=compose.yml MEDIA_OPS_TIMEOUT=601 \
    MEDIA_OPS_HEALTH_WAIT=0; do
    setup_case
    ops_fixture::run "${LIMIT_SCRIPT}" "${extra}" -- off || return 1
    ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL config' 1 || ok=1
    expect_docker_count ps 0 || ok=1
    expect_untouched || ok=1
  done
  return "${ok}"
}

# I4 — 응답기 실행 중 아님 → not-running · env 그대로 · 재생성 0.
case_limit_not_running() {
  local ok=0
  setup_case
  rm -f -- "${ops_dir}/state/ps.ingest-auth"
  ops_fixture::run "${LIMIT_SCRIPT}" -- off || return 1
  ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL not-running' 1 || ok=1
  expect_untouched || ok=1
  return "${ok}"
}

# I5 — env 자리가 링크 · 디렉터리 · chown 실패 → write · 재생성 0.
case_limit_write_fail() {
  local ok=0 variant env
  for variant in link dir chown; do
    setup_case
    env="${ops_dir}/etc/ingest-auth.env"
    case "${variant}" in
      link)
        rm -f -- "${env}"
        ln -s -- "${ops_dir}/env.keep" "${env}"
        ;;
      dir) rm -f -- "${env}" && mkdir -- "${env}" ;;
    esac
    if [[ "${variant}" == chown ]]; then
      ops_fixture::run "${LIMIT_SCRIPT}" PCBENCH_FAKE_CHOWN_STATUS=1 -- off \
        || return 1
    else
      ops_fixture::run "${LIMIT_SCRIPT}" -- off || return 1
    fi
    ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL write' 1 || ok=1
    expect_docker_count compose 0 || ok=1
    expect_content "${ops_dir}/env.keep" 'INGEST_AUTH_IP_LIMIT=on' \
      "${variant}: 옛 파일" || ok=1
    expect_no_temp || ok=1
  done
  return "${ok}"
}

# I6 — 재생성 실패 · 끝나지 않음 · healthy 안 됨 → recreate · unhealthy.
case_limit_recreate_fail() {
  local ok=0
  setup_case
  mark compose.up.fail
  ops_fixture::run "${LIMIT_SCRIPT}" -- off || return 1
  ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL recreate' 1 || ok=1
  setup_case
  mark compose.up.hang
  ops_fixture::run "${LIMIT_SCRIPT}" MEDIA_OPS_TIMEOUT=2 -- off || return 1
  ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL recreate' 1 || ok=1
  setup_case
  mark health.never
  ops_fixture::run "${LIMIT_SCRIPT}" -- off || return 1
  ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL unhealthy' 1 || ok=1
  return "${ok}"
}

# I7 — 저널 읽기 실패 · 새 컨테이너 started 줄 없음 → journal.
case_limit_journal() {
  local ok=0
  setup_case
  mark journal.fail
  ops_fixture::run "${LIMIT_SCRIPT}" -- off || return 1
  ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL journal' 1 || ok=1
  setup_case
  mark journal.none
  ops_fixture::run "${LIMIT_SCRIPT}" MEDIA_OPS_TIMEOUT=2 -- off || return 1
  ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL journal' 1 || ok=1
  return "${ok}"
}

# I8 — 새 컨테이너가 다른 값으로 떴음(env 파일이 반영 안 됨) → verify.
case_limit_verify() {
  local ok=0
  setup_case
  # 가짜 compose 가 새 컨테이너 저널에 env 파일과 반대 값을 쓴다.
  ops_fixture::run "${LIMIT_SCRIPT}" PCBENCH_FAKE_STALE_ENV=1 -- off \
    || return 1
  ops_fixture::expect_line 'INGEST AUTH IP LIMIT FAIL verify' 1 || ok=1
  return "${ok}"
}

# I9 — 비밀 · 토큰 값이 argv · 출력에 없음(거부 목록 값은 표준 입력 아닌 파일로만).
case_no_leak() {
  local ok=0
  setup_case
  ssm_value "${HEADER}
${TOKEN_B}"
  ops_fixture::run "${DENY_SCRIPT}" -- || return 1
  ops_fixture::expect_line 'INGEST AUTH DENY SYNC OK tokens=1' 0 || ok=1
  ops_fixture::expect_no_leak "${TOKEN_B}" '새 토큰' || ok=1
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/ingest_auth_ops_test.sh\n' >&2
    exit 1
  fi
  trap ops_fixture::cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ops_fixture::init ingest-auth-ops

  harness::run_case D1 '덧붙임 → 원자 교체 · healthcheck · 재생성 0 · tokens' \
    case_deny_sync
  harness::run_case D2 '--release → 응답기만 재생성 · healthy' case_deny_release
  harness::run_case D3 'streamid 통째 붙여 넣기 → tokens=0' \
    case_deny_streamid_paste
  harness::run_case D4 '실행 중 아님 → not-running · 받기 0' \
    case_deny_not_running
  harness::run_case D5 'SSM 실패 · 꼴 밖 → ssm · invalid · 옛 파일' \
    case_deny_ssm_invalid
  harness::run_case D6 '설치 실패 → install' case_deny_install_fail
  harness::run_case D7 'healthcheck 실패 → health · 되돌리지 않음' \
    case_deny_health_fail
  harness::run_case D8 '재생성 실패 · healthy 아님 → recreate · unhealthy' \
    case_deny_release_fail
  harness::run_case D9 '인자 · 설정 오류 → usage · config · docker 0' \
    case_deny_usage_config
  harness::run_case I1 'off → 원자 교체 · 재생성 · started 대조' case_limit_off
  harness::run_case I2 'on → 다시 쓰고 재생성' case_limit_on
  harness::run_case I3 '인자 · 설정 오류 → usage · config · docker 0' \
    case_limit_usage_config
  harness::run_case I4 '실행 중 아님 → not-running' case_limit_not_running
  harness::run_case I5 'env 자리 링크 · 디렉터리 · chown 실패 → write' \
    case_limit_write_fail
  harness::run_case I6 '재생성 실패 · 상한 · healthy 아님' \
    case_limit_recreate_fail
  harness::run_case I7 '저널 실패 · started 없음 → journal' case_limit_journal
  harness::run_case I8 '새 컨테이너가 다른 값 → verify' case_limit_verify
  harness::run_case I9 '토큰 값이 argv · 출력에 없음' case_no_leak

  # shellcheck disable=SC2086 # 이름 목록을 낱낱으로 넘긴다.
  if ! harness::summary 0 ${CASE_NAMES}; then
    exit 1
  fi
}

main "$@"
