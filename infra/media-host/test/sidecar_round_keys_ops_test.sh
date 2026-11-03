#!/usr/bin/env bash
#
# 새 꼴 쓰기 스위치 운영 스크립트 시험 — bin/sidecar-round-keys.sh on|off.
#
# 판정: 표준 출력은 판정 줄 하나 · 종료 코드, 상자 스위치 파일(compose 파일 옆
# sidecar.env — 원자 교체 · 0644 · 0:0), 그리고 「지금 실행 중 사이드카 ID · 그
# 컨테이너가 만들어질 때 읽은 env 값 · 그 컨테이너 저널의 playback_key_layout
# 줄」. 재생성은 가짜 docker compose 가 흉내 낸다(test/ops_fakebin/docker — 새
# ID · 만들 때 읽은 sidecar.env · 새 컨테이너 저널). k 번째 재생성의 갈래(실패 ·
# 저널 없음 · 반대 layout 등)는 state/sidecar.up.<k> 로 고른다. 실행 도구는 다른
# 운영 시험과 같다(test/lib/ops_fixture.sh).
#
# 사례 R1–R11 은 계획 4-6 사례 표 1–11 과 같은 차례다. R12 는 검수 짝 사례
# (verify 실패 · 복원 컨테이너 round), R13–R15 는 설정 · on 의 읽기 점검 · off 의
# 실패 갈래다.
#
# R16 · R17 은 이 스크립트가 아니라 교체 런북(infra/terraform/runbooks/
# media-swap.md 「POK-525 교체」)의 상자 판정 스크립트 둘이다(계획 4-7 셸 사례).
# 런북의 here-document 본문(`cat > "${work}/<이름>" <<'SH'` 부터 `SH` 줄 앞까지)을
# 그대로 뽑아 /bin/sh 로 돌린다 — 사본이 없어 런북 글자를 바꾸면 이 사례가 바뀐
# 글자를 잰다. systemctl 은 사례 디렉터리에 둔 가짜다.
#
# R30–R32 는 PR 단계 보강이다 — 동시 실행 잠금(R30 · R31)과 layout 대조 뒤 그
# 컨테이너가 실행 중인지 다시 보는 것(R32). 잠금은 가짜 flock 이 진짜
# flock(2) 로 건다(test/ops_fakebin/flock — macOS 에 flock 명령이 없다).
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/sidecar_round_keys_ops_test.sh
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

readonly CASES='R1 R2 R3 R4 R5 R6 R7 R8 R9 R10 R11 R12 R13 R14 R15 R16 R17
R30 R31 R32'
readonly SCRIPT='bin/sidecar-round-keys.sh'
readonly SIDECAR_TAG='pokeclip-media-sidecar'
readonly KEY='PLAYBACK_ROUND_KEYS'
readonly LINE_ON="${KEY}=true"
readonly LINE_OFF="${KEY}=false"
# 원래 실행 중 사이드카(끔으로 만들어짐)의 저널 줄.
readonly LEGACY_LINE='{"level":"INFO","msg":"playback_key_layout",'\
'"layout":"legacy","round_frozen":"absent"}'
# 교체 런북 — 상자 판정 스크립트의 원문(R16 · R17).
readonly RUNBOOK="${MEDIA_HOST_DIR}/../terraform/runbooks/media-swap.md"
# 런북 판정 스크립트가 거르는 compose 프로젝트(prod 고정값).
readonly RUNBOOK_PROJECT='pokeclip-media'
# 동시 실행 잠금 파일(compose 파일 옆).
readonly LOCK_NAME='sidecar-round-keys.lock'
# 동시 실행 사례에서 앞 실행이 재생성에 닿기를 기다리는 상한(초).
readonly FIRST_RUN_WAIT=20

# 동시 실행 사례의 앞 실행(배경) PID.
first_pid=""

#######################################
# 사례 디렉터리 — 실행 중 사이드카 하나(만들 때 env 끔 · 저널 legacy) ·
# compose 파일 자리 · 스위치 파일 · 그 하드 링크(옛 inode) · 가짜 chown.
# Globals:
#   ops_dir, ops_env
# Arguments:
#   스위치 파일의 처음 줄(기본 끔).
#######################################
setup_case() {
  ops_fixture::new_case
  link_fakes
  mkdir -p -- "${ops_dir}/etc"
  : >"${ops_dir}/etc/compose.yml"
  printf '%s\n' "${1:-${LINE_OFF}}" >"${ops_dir}/etc/sidecar.env"
  ln -- "${ops_dir}/etc/sidecar.env" "${ops_dir}/env.keep"
  ops_fixture::container "${OPS_SIDECAR_ID}" sidecar running "${LINE_OFF}"
  printf '%s\n' "${LEGACY_LINE}" \
    >"${ops_dir}/state/journal.c.${OPS_SIDECAR_ID}"
  ops_env+=("MEDIA_COMPOSE_FILE=${ops_dir}/etc/compose.yml"
    'MEDIA_OPS_HEALTH_WAIT=2')
}

# 사례 디렉터리에 이 시험만 쓰는 가짜(chown · flock)를 둔다.
link_fakes() {
  local name
  for name in chown flock; do
    ln -s -- "${TEST_DIR}/ops_fakebin/${name}" "${ops_dir}/bin/${name}"
  done
}

#######################################
# k 번째 사이드카 재생성의 갈래를 둔다.
# Arguments:
#   k, fail | hang | nojournal | stale | round | legacy | exited | restarting.
#######################################
up_mode() { printf '%s\n' "$2" >"${ops_dir}/state/sidecar.up.$1"; }

# 사이드카를 실행 중 아님으로 바꾼다(정지 · 없음 · 둘 · 조회 실패).
sidecar_absent() {
  rm -f -- "${ops_dir}/state/ps.sidecar"
  case "$1" in
    exited) ops_fixture::container "${OPS_SIDECAR_ID}" sidecar exited ;;
    two)
      ops_fixture::container "${OPS_SIDECAR_ID}" sidecar running
      ops_fixture::container pcbenchsidecar02 sidecar running
      ;;
    psfail)
      ops_fixture::container "${OPS_SIDECAR_ID}" sidecar running
      : >"${ops_dir}/state/ps.fail"
      ;;
  esac
}

# 지금 실행 중 사이드카 ID 를 낸다(가짜 docker 의 상태 — 하나가 아니면 빈 값).
running_id() {
  awk '$2 == "running" { n++; id = $1 } END { if (n == 1) print id }' \
    "${ops_dir}/state/ps.sidecar" 2>/dev/null
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
# 첫 argv 가 compose 인 docker 기록 수가 기대와 같은지 본다.
# Arguments:
#   기대 수.
#######################################
expect_compose_count() {
  local got
  got="$(ops_fixture::docker_count compose)"
  if [[ "${got}" != "$1" ]]; then
    harness::add_reason "docker compose ${got}번(기대 $1)"
    return 1
  fi
}

# compose 기록이 모두 사이드카만 재생성하는 꼴(--no-deps)인지 본다.
expect_recreate_argv() {
  local f want
  want="$(printf '%s\n' compose -p "${OPS_FAKE_PROJECT}" -f \
    "${ops_dir}/etc/compose.yml" up -d --no-deps --force-recreate sidecar)"
  while IFS= read -r f; do
    [[ "$(head -n 1 -- "${f}")" == compose ]] || continue
    if [[ "$(cat -- "${f}")" != "${want}" ]]; then
      harness::add_reason "compose 꼴이 다름: $(tr '\n' ' ' <"${f}")"
      return 1
    fi
  done < <(ops_fixture::calls docker)
}

#######################################
# 실행 중 사이드카를 본다 — ID(빈 값이면 「원래와 다름」 만), 만들 때 읽은 env
# 줄, 그 컨테이너 저널의 layout(빈 값이면 보지 않음).
# Arguments:
#   기대 ID 또는 빈 값, 기대 env 줄, 기대 layout 또는 빈 값.
#######################################
expect_running() {
  local id
  id="$(running_id)"
  if [[ -z "${id}" ]]; then
    harness::add_reason '실행 중 사이드카가 하나가 아님'
    return 1
  fi
  if [[ -n "$1" && "${id}" != "$1" ]] \
    || [[ -z "$1" && "${id}" == "${OPS_SIDECAR_ID}" ]]; then
    harness::add_reason "실행 중 사이드카 ${id}(기대 ${1:-원래와 다른 ID})"
    return 1
  fi
  if [[ "$(cat -- "${ops_dir}/state/ctr.${id}.env" 2>/dev/null)" != "$2" ]]
  then
    harness::add_reason "실행 중 ${id} 의 env 가 「$2」 아님"
    return 1
  fi
  if [[ -n "$3" ]] && ! grep -q -F -- "\"layout\":\"$3\"" \
    "${ops_dir}/state/journal.c.${id}" 2>/dev/null; then
    harness::add_reason "실행 중 ${id} 의 저널 layout 이 $3 아님"
    return 1
  fi
}

#######################################
# 스위치 파일이 원자 교체로 이 줄이 됐는지 본다 — 내용 · 0644 · 하드 링크는 옛
# 내용 · 임시 파일을 0:0 으로 맞춤 · 임시 파일 잔여 0.
# Arguments:
#   기대 줄, 옛 줄.
#######################################
expect_written() {
  local ok=0 env="${ops_dir}/etc/sidecar.env"
  expect_content "${env}" "$1" '스위치 파일' || ok=1
  expect_mode "${env}" 644 '스위치 파일' || ok=1
  expect_content "${ops_dir}/env.keep" "$2" '하드 링크(옛 파일)' || ok=1
  if ! grep -q -x -F -- '0:0' "${ops_dir}/log/chown.1.argv" 2>/dev/null \
    || ! grep -q -F -- "${ops_dir}/etc/.sidecar.env." \
      "${ops_dir}/log/chown.1.argv"; then
    harness::add_reason '임시 스위치 파일을 0:0 으로 맞추지 않음'
    ok=1
  fi
  expect_no_temp || ok=1
  return "${ok}"
}

# 임시 파일 잔여(스위치 파일 임시 이름 · TMPDIR)가 없는지 본다.
expect_no_temp() {
  local left
  left="$(find "${ops_dir}" -name '.sidecar.env.*' | awk 'END { print NR }')"
  if [[ "${left}" != 0 || "$(ops_fixture::tmp_files)" != 0 ]]; then
    harness::add_reason "임시 파일 잔여(디렉터리 ${left}개)"
    return 1
  fi
}

# 스위치 파일이 처음 그대로이고 재생성이 0 인지 본다.
expect_untouched() {
  local ok=0
  expect_content "${ops_dir}/etc/sidecar.env" "$1" '스위치 파일' || ok=1
  expect_compose_count 0 || ok=1
  expect_no_temp || ok=1
  return "${ok}"
}

#######################################
# 복원 묶음이 성공한 상태를 본다 — 파일 끔 · 재생성 두 번(둘 다 사이드카만) ·
# 실행 중 = 두 번째 재생성이 만든 컨테이너(env 끔 · 저널 legacy) · 그 ID 로
# 거른 저널을 읽음.
# Arguments:
#   기대 판정 줄, 두 번째 재생성이 만든 ID.
#######################################
expect_rolled_back() {
  local ok=0
  ops_fixture::expect_line "$1" 1 || ok=1
  expect_content "${ops_dir}/etc/sidecar.env" "${LINE_OFF}" '스위치 파일' \
    || ok=1
  expect_compose_count 2 || ok=1
  expect_recreate_argv || ok=1
  expect_running "$2" "${LINE_OFF}" legacy || ok=1
  if ! grep -q -x -F -- "CONTAINER_ID_FULL=$2" \
    "${ops_dir}"/log/journalctl.*.argv; then
    harness::add_reason "복원 컨테이너 $2 의 저널을 읽지 않음"
    ok=1
  fi
  expect_no_temp || ok=1
  return "${ok}"
}

# R1 — on 성공 → 원자 교체 · 사이드카만 재생성 · 새 ID 저널 layout=round.
case_on_ok() {
  local ok=0
  setup_case
  ops_fixture::run "${SCRIPT}" -- on || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS OK on' 0 || ok=1
  expect_written "${LINE_ON}" "${LINE_OFF}" || ok=1
  expect_compose_count 1 || ok=1
  expect_recreate_argv || ok=1
  expect_running pcbenchrecreated1 "${LINE_ON}" round || ok=1
  if [[ "$(cat -- "${ops_dir}/log/journalctl.1.argv" 2>/dev/null)" != \
    "$(printf '%s\n' -t "${SIDECAR_TAG}" \
      CONTAINER_ID_FULL=pcbenchrecreated1 -q --no-pager -o cat)" ]]; then
    harness::add_reason '첫 저널 조회가 새 컨테이너 ID 로 거른 꼴이 아님'
    ok=1
  fi
  return "${ok}"
}

# R2 — on · 재생성 성공 · 저널 줄 없음 → journal rolled-back(E34 — 파일만
# 되돌리지 않고 다시 재생성해 실행 중 컨테이너가 끔으로 만들어짐).
case_on_journal_rolled_back() {
  setup_case
  up_mode 1 nojournal
  ops_fixture::run "${SCRIPT}" -- on || return 1
  expect_rolled_back 'SIDECAR ROUND KEYS FAIL journal rolled-back' \
    pcbenchrecreated2
}

# R3 — on · 재생성 성공 · layout=legacy 가 옴 → verify rolled-back.
case_on_verify_rolled_back() {
  setup_case
  up_mode 1 stale
  ops_fixture::run "${SCRIPT}" -- on || return 1
  expect_rolled_back 'SIDECAR ROUND KEYS FAIL verify rolled-back' \
    pcbenchrecreated2
}

# R4 — on · 재생성 실패 → recreate rolled-back(복원 재생성이 만든 끔 컨테이너).
case_on_recreate_rolled_back() {
  setup_case
  up_mode 1 fail
  ops_fixture::run "${SCRIPT}" -- on || return 1
  expect_rolled_back 'SIDECAR ROUND KEYS FAIL recreate rolled-back' \
    pcbenchrecreated1
}

# R5 — on · 저널 없음 · 복원 재생성도 실패 → journal restore-failed(종료 4).
case_on_restore_recreate_fail() {
  local ok=0
  setup_case
  up_mode 1 nojournal
  up_mode 2 fail
  ops_fixture::run "${SCRIPT}" -- on || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL journal restore-failed' 4 \
    || ok=1
  expect_compose_count 2 || ok=1
  return "${ok}"
}

# R6 — on · 저널 없음 · 복원 컨테이너 저널이 round → journal restore-failed.
# 원래 컨테이너의 저널은 legacy 다 — 복원 대조가 옛 ID 를 보면 rolled-back 으로
# 잘못 끝난다.
case_on_restore_round() {
  local ok=0
  setup_case
  up_mode 1 nojournal
  up_mode 2 round
  ops_fixture::run "${SCRIPT}" -- on || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL journal restore-failed' 4 \
    || ok=1
  expect_compose_count 2 || ok=1
  return "${ok}"
}

# R7 — on · 사이드카 실행 중 아님(없음 · 정지 · 둘 · 조회 실패) → not-running ·
# 파일 무변 · 재생성 0.
case_on_not_running() {
  local ok=0 variant
  for variant in none exited two psfail; do
    setup_case
    sidecar_absent "${variant}"
    ops_fixture::run "${SCRIPT}" -- on || return 1
    ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL not-running' 1 || ok=1
    expect_untouched "${LINE_OFF}" || ok=1
  done
  return "${ok}"
}

# R8 — off · 실행 중 사이드카 하나를 확인 못 함(없음 · 정지 · 둘 · 조회 실패)
# → 파일만 끔 · FILE off(종료 3) · 재생성 0.
case_off_file_only() {
  local ok=0 variant
  for variant in none exited two psfail; do
    setup_case "${LINE_ON}"
    sidecar_absent "${variant}"
    ops_fixture::run "${SCRIPT}" -- off || return 1
    ops_fixture::expect_line 'SIDECAR ROUND KEYS FILE off' 3 || ok=1
    expect_written "${LINE_OFF}" "${LINE_ON}" || ok=1
    expect_compose_count 0 || ok=1
  done
  return "${ok}"
}

# R9 — off · 쓰기 실패(자리가 링크 · 디렉터리 · chown 실패) → write · 원본
# 그대로 · 재생성 0. 사이드카가 있든 없든 같다.
case_off_write_fail() {
  local ok=0 variant env
  for variant in link link-absent dir chown; do
    setup_case "${LINE_ON}"
    env="${ops_dir}/etc/sidecar.env"
    case "${variant}" in
      link | link-absent)
        rm -f -- "${env}"
        ln -s -- "${ops_dir}/env.keep" "${env}"
        [[ "${variant}" != link-absent ]] || sidecar_absent none
        ;;
      dir) rm -f -- "${env}" && mkdir -- "${env}" ;;
    esac
    if [[ "${variant}" == chown ]]; then
      ops_fixture::run "${SCRIPT}" PCBENCH_FAKE_CHOWN_STATUS=1 -- off \
        || return 1
    else
      ops_fixture::run "${SCRIPT}" -- off || return 1
    fi
    ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL write' 1 || ok=1
    expect_compose_count 0 || ok=1
    expect_content "${ops_dir}/env.keep" "${LINE_ON}" "${variant}: 옛 파일" \
      || ok=1
    [[ "${variant}" != link* || -L "${env}" ]] || {
      harness::add_reason "${variant}: 링크가 바뀜"
      ok=1
    }
    expect_no_temp || ok=1
  done
  return "${ok}"
}

# R10 — off 성공 → 파일 끔 · 사이드카만 재생성 · 새 ID 저널 layout=legacy.
case_off_ok() {
  local ok=0
  setup_case "${LINE_ON}"
  ops_fixture::run "${SCRIPT}" -- off || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS OK off' 0 || ok=1
  expect_written "${LINE_OFF}" "${LINE_ON}" || ok=1
  expect_compose_count 1 || ok=1
  expect_recreate_argv || ok=1
  expect_running pcbenchrecreated1 "${LINE_OFF}" legacy || ok=1
  return "${ok}"
}

# R11 — 인자 오류 → usage(2) · docker 0 · 파일 무변.
case_usage() {
  local ok=0 args
  for args in '' =on ON 'on off' --on; do
    setup_case
    # shellcheck disable=SC2086 # 인자 묶음을 낱낱으로 넘긴다.
    ops_fixture::run "${SCRIPT}" -- ${args} || return 1
    ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL usage' 2 || ok=1
    [[ "$(ops_fixture::count docker)" == 0 ]] || {
      harness::add_reason "인자 「${args}」: docker 호출 있음"
      ok=1
    }
    expect_untouched "${LINE_OFF}" || ok=1
  done
  return "${ok}"
}

# R12 — on · layout=legacy 가 옴 · 복원 컨테이너 저널이 round → verify
# restore-failed(R2 · R6 의 짝 — verify 갈래의 복원 대조).
case_on_verify_restore_round() {
  local ok=0
  setup_case
  up_mode 1 stale
  up_mode 2 round
  ops_fixture::run "${SCRIPT}" -- on || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL verify restore-failed' 4 \
    || ok=1
  expect_compose_count 2 || ok=1
  return "${ok}"
}

# R13 — 설정 오류 → config · docker 0 · 파일 무변(on · off 둘 다).
case_config() {
  local ok=0 extra want
  for extra in MEDIA_COMPOSE_FILE=compose.yml MEDIA_OPS_TIMEOUT=0 \
    MEDIA_OPS_HEALTH_WAIT=x; do
    for want in on off; do
      setup_case
      ops_fixture::run "${SCRIPT}" "${extra}" -- "${want}" || return 1
      ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL config' 1 || ok=1
      [[ "$(ops_fixture::count docker)" == 0 ]] || {
        harness::add_reason "${extra} ${want}: docker 호출 있음"
        ok=1
      }
      expect_untouched "${LINE_OFF}" || ok=1
    done
  done
  return "${ok}"
}

# R14 — on · 지금 파일이 꼴 밖(두 줄 · 다른 값 · 링크 · 없음) → write · 아무것도
# 바꾸지 않음.
case_on_bad_current() {
  local ok=0 variant env want
  for variant in two-lines yes link missing; do
    setup_case
    env="${ops_dir}/etc/sidecar.env"
    want="${LINE_OFF}"
    case "${variant}" in
      two-lines) printf '%s\n%s\n' "${LINE_OFF}" "${LINE_OFF}" >"${env}" ;;
      yes) printf '%s=yes\n' "${KEY}" >"${env}" ;;
      link)
        rm -f -- "${env}"
        ln -s -- "${ops_dir}/env.keep" "${env}"
        ;;
      missing) rm -f -- "${env}" ;;
    esac
    [[ "${variant}" != two-lines ]] \
      || want="$(printf '%s\n%s' "${LINE_OFF}" "${LINE_OFF}")"
    [[ "${variant}" != yes ]] || want="${KEY}=yes"
    [[ "${variant}" != missing ]] || want=''
    ops_fixture::run "${SCRIPT}" -- on || return 1
    ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL write' 1 || ok=1
    expect_untouched "${want}" || ok=1
    [[ "${variant}" != missing || ! -e "${env}" ]] || {
      harness::add_reason 'missing: 파일을 만듦'
      ok=1
    }
  done
  return "${ok}"
}

# R15 — off · 실행 중 사이드카의 재생성 · 저널 · 대조 실패 → 그 단계(종료 1 ·
# 복원 묶음 없음 — 파일은 이미 끔 · 재생성 한 번).
case_off_fail_stages() {
  local ok=0 pair mode stage
  for pair in fail:recreate nojournal:journal stale:verify; do
    mode="${pair%%:*}"
    stage="${pair#*:}"
    setup_case "${LINE_ON}"
    up_mode 1 "${mode}"
    ops_fixture::run "${SCRIPT}" -- off || return 1
    ops_fixture::expect_line "SIDECAR ROUND KEYS FAIL ${stage}" 1 || ok=1
    expect_content "${ops_dir}/etc/sidecar.env" "${LINE_OFF}" \
      "${stage}: 스위치 파일" || ok=1
    expect_compose_count 1 || ok=1
  done
  return "${ok}"
}

#######################################
# 교체 런북에서 상자 판정 스크립트 하나의 here-document 본문을 낸다.
# Arguments:
#   스크립트 파일 이름(예: disabled-check.sh).
# Returns:
#   0 / 1 그 here-document 가 없거나 닫히지 않음.
#######################################
runbook_script() {
  awk -v start="cat > \"\${work}/$1\" <<'SH'" '
    $0 == start { inside = 1; found++; next }
    inside && $0 == "SH" { inside = 0; closed++; next }
    inside { print }
    END { exit !(found == 1 && closed == 1) }' "${RUNBOOK}"
}

#######################################
# 사례 디렉터리에 가짜 systemctl 을 둔다 — argv 를 log/systemctl.<n>.argv 에
# 남기고, is-enabled 는 state/systemctl.enabled 의 낱말을 낸다(enabled 면 종료 0,
# 그 밖은 종료 1 — 진짜 systemctl 은 disabled 에서도 1 이다).
# state/systemctl.fail 이 있으면 표준 출력 없이 종료 1(조회 실패).
# Globals:
#   ops_dir
#######################################
fake_systemctl() {
  cat >"${ops_dir}/bin/systemctl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
n=1
until (set -C; : >"${PCBENCH_FAKE_LOG_DIR}/systemctl.${n}.argv") 2>/dev/null
do
  n=$((n + 1))
done
printf '%s\n' "$@" >"${PCBENCH_FAKE_LOG_DIR}/systemctl.${n}.argv"
[[ "$*" == 'is-enabled pokeclip-media' ]] || exit 2
if [[ -e "${PCBENCH_FAKE_STATE_DIR}/systemctl.fail" ]]; then
  printf 'Failed to get unit file state for pokeclip-media.service\n' >&2
  exit 1
fi
word="$(cat -- "${PCBENCH_FAKE_STATE_DIR}/systemctl.enabled")"
printf '%s\n' "${word}"
[[ "${word}" == enabled ]]
FAKE
  chmod +x "${ops_dir}/bin/systemctl"
}

#######################################
# 런북 판정 스크립트를 빈 환경에서 /bin/sh 로 돌린다(SSM 의 AWS-RunShellScript
# 꼴 — 인자는 media_ssm 이 앞에 붙이는 set -- 대신 sh 의 인자로 준다). 가짜
# docker 의 프로젝트는 런북 고정값으로 맞춘다.
# Globals:
#   ops_dir, ops_status
# Arguments:
#   스크립트 파일 이름, 스크립트 인자 ….
#######################################
run_runbook() {
  local script="${ops_dir}/$1"
  shift
  ops_status=0
  if ! runbook_script "${script##*/}" >"${script}"; then
    harness::add_reason "런북에 ${script##*/} here-document 가 하나가 아님"
    ops_status=127
    return 1
  fi
  # shellcheck disable=SC2016 # $0 · $@ 는 sh -c 안에서 펼친다.
  harness::bounded "${OPS_RUN_TIMEOUT}" /bin/sh -c 'exec "$@" 2>"$0"' \
    "${ops_dir}/err" env -i "HOME=${ops_dir}/home" \
    "PATH=${ops_dir}/bin:/usr/bin:/bin" "TMPDIR=${ops_dir}/tmp" \
    "PCBENCH_FAKE_LIB=${TEST_DIR}/ops_fakebin/record.sh" \
    "PCBENCH_FAKE_LOG_DIR=${ops_dir}/log" \
    "PCBENCH_FAKE_STATE_DIR=${ops_dir}/state" \
    "PCBENCH_FAKE_PROJECT=${RUNBOOK_PROJECT}" \
    /bin/sh "${script}" "$@" </dev/null >"${ops_dir}/out" \
    2>"${ops_dir}/harness.err" || ops_status=$?
}

#######################################
# 표준 출력의 마지막 줄 · 종료 코드 · 품은 줄을 본다.
# Arguments:
#   이름, 기대 마지막 줄, 기대 종료 코드, 품어야 할 줄 ….
#######################################
expect_verdict() {
  local name="$1" last="$2" status="$3" line ok=0
  shift 3
  if [[ "$(tail -n 1 -- "${ops_dir}/out")" != "${last}" ]]; then
    harness::add_reason "${name}: 마지막 줄이 「${last}」 아님: $(tr '\n' '|' \
      <"${ops_dir}/out") / 오류: $(harness::log_tail "${ops_dir}/err")"
    ok=1
  fi
  if (( ops_status != status )); then
    harness::add_reason "${name}: 종료 코드 ${ops_status}(기대 ${status})"
    ok=1
  fi
  for line in "$@"; do
    if ! grep -q -x -F -- "${line}" "${ops_dir}/out"; then
      harness::add_reason "${name}: 「${line}」 줄 없음"
      ok=1
    fi
  done
  return "${ok}"
}

# R16 — 런북 MEDIA DISABLED 판정(교체 걸음 4.5 · E26) — disabled 만 OK, enabled ·
# 조회 실패는 NOT OK. 조회는 is-enabled 한 번.
case_runbook_disabled() {
  local ok=0 variant
  for variant in disabled enabled fail; do
    ops_fixture::new_case
    fake_systemctl
    if [[ "${variant}" == fail ]]; then
      : >"${ops_dir}/state/systemctl.fail"
    else
      printf '%s\n' "${variant}" >"${ops_dir}/state/systemctl.enabled"
    fi
    run_runbook disabled-check.sh || return 1
    case "${variant}" in
      disabled)
        expect_verdict disabled 'MEDIA DISABLED OK' 0 'ENABLED disabled' \
          || ok=1
        ;;
      enabled)
        expect_verdict enabled 'MEDIA DISABLED NOT OK' 1 'ENABLED enabled' \
          || ok=1
        ;;
      fail)
        expect_verdict fail 'MEDIA DISABLED NOT OK' 1 'ENABLED unknown' \
          || ok=1
        ;;
    esac
    if [[ "$(cat -- "${ops_dir}/log/systemctl.1.argv" 2>/dev/null)" != \
      "$(printf '%s\n' is-enabled pokeclip-media)" \
      || -e "${ops_dir}/log/systemctl.2.argv" ]]; then
      harness::add_reason "${variant}: systemctl 조회가 is-enabled 한 번이 아님"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 「켜기 전」 선언 판정 사례 디렉터리 — 사이드카 컨테이너들과 그 저널을 둔다.
# Arguments:
#   「<id> <running|exited> <저널 layout|none> <round_frozen>」 묶음 ….
#######################################
setup_before_on() {
  local spec id status layout frozen
  ops_fixture::new_case
  for spec in "$@"; do
    read -r id status layout frozen <<<"${spec}"
    ops_fixture::container "${id}" sidecar "${status}"
    [[ "${layout}" != none ]] || continue
    printf '{"level":"INFO","msg":"playback_key_layout","layout":"%s",%s}\n' \
      "${layout}" "\"round_frozen\":\"${frozen}\"" \
      >"${ops_dir}/state/journal.c.${id}"
  done
}

# R17 — 런북 사이드카 layout 판정(교체 걸음 4.1 · 「켜기 전」 선언 판정 · P1 ·
# E34). 실행 중 사이드카 하나의 저널 마지막 기동 줄이 layout=legacy(트리거
# present 아님)면 SIDECAR LEGACY OK, Q1 을 인자로 주면 그것도 0 이어야 BEFORE
# ON OK. 옛 컨테이너만 legacy · round · Q1 1 이상 · 실행 중 둘 · 트리거 present
# 는 NOT OK, 조회 실패 · 인자 오류는 FAIL.
case_runbook_layout() {
  local ok=0 args
  setup_before_on 'pcbenchnew running legacy absent'
  run_runbook layout-check.sh 0 || return 1
  expect_verdict legacy 'BEFORE ON OK' 0 'SIDECAR_RUNNING 1' 'LAYOUT legacy' \
    'ROUND_FROZEN absent' 'Q1 0' || ok=1
  if ! grep -q -x -F -- 'CONTAINER_ID_FULL=pcbenchnew' \
    "${ops_dir}"/log/journalctl.*.argv; then
    harness::add_reason 'legacy: 실행 중 컨테이너 ID 로 거른 저널을 읽지 않음'
    ok=1
  fi

  setup_before_on 'pcbenchnew running legacy absent'
  run_runbook layout-check.sh || return 1
  expect_verdict legacy-only 'SIDECAR LEGACY OK' 0 'LAYOUT legacy' || ok=1
  if grep -q '^Q1 ' "${ops_dir}/out"; then
    harness::add_reason 'legacy-only: Q1 을 주지 않았는데 Q1 줄이 있음'
    ok=1
  fi

  setup_before_on 'pcbenchnew running round absent'
  run_runbook layout-check.sh || return 1
  expect_verdict round-only 'SIDECAR LEGACY NOT OK' 1 'LAYOUT round' || ok=1

  setup_before_on 'pcbenchnew running round absent'
  run_runbook layout-check.sh 0 || return 1
  expect_verdict round 'BEFORE ON NOT OK' 1 'LAYOUT round' || ok=1

  setup_before_on 'pcbenchold exited legacy absent' 'pcbenchnew running none -'
  run_runbook layout-check.sh 0 || return 1
  expect_verdict old-only 'BEFORE ON NOT OK' 1 'LAYOUT none' || ok=1

  setup_before_on 'pcbenchnew running legacy absent'
  run_runbook layout-check.sh 1 || return 1
  expect_verdict q1 'BEFORE ON NOT OK' 1 'LAYOUT legacy' 'Q1 1' || ok=1

  setup_before_on 'pcbenchnew running legacy present'
  run_runbook layout-check.sh 0 || return 1
  expect_verdict frozen 'BEFORE ON NOT OK' 1 'ROUND_FROZEN present' || ok=1

  setup_before_on 'pcbencha running legacy absent' \
    'pcbenchb running legacy absent'
  run_runbook layout-check.sh 0 || return 1
  expect_verdict two 'BEFORE ON NOT OK' 1 'SIDECAR_RUNNING 2' || ok=1

  setup_before_on 'pcbenchnew running legacy absent'
  : >"${ops_dir}/state/ps.fail"
  run_runbook layout-check.sh 0 || return 1
  expect_verdict docker 'LAYOUT CHECK FAIL docker' 2 || ok=1

  setup_before_on 'pcbenchnew running legacy absent'
  : >"${ops_dir}/state/journal.fail"
  run_runbook layout-check.sh 0 || return 1
  expect_verdict journal 'LAYOUT CHECK FAIL journal' 2 || ok=1

  for args in x '0 0' -1 1x; do
    setup_before_on 'pcbenchnew running legacy absent'
    # shellcheck disable=SC2086 # 인자 묶음을 낱낱으로 넘긴다.
    run_runbook layout-check.sh ${args} || return 1
    expect_verdict "usage「${args}」" 'LAYOUT CHECK FAIL usage' 2 || ok=1
  done
  return "${ok}"
}

#######################################
# 앞 실행(state/sidecar.up.1 = hang — 재생성에서 잠금을 쥔 채 멈춤)을 배경에서
# 띄우고 재생성에 닿기를 기다린다. 앞 실행의 종료 코드는 그 사례 디렉터리의
# status 에 남는다.
# Globals:
#   ops_dir, first_pid
# Returns:
#   0 닿음 / 1 상한 안에 닿지 않음.
#######################################
start_first_run() {
  local first="${ops_dir}" deadline
  up_mode 1 hang
  (
    ops_fixture::run "${SCRIPT}" MEDIA_OPS_TIMEOUT=2 -- on || true
    printf '%s\n' "${ops_status}" >"${first}/status"
  ) &
  first_pid=$!
  deadline=$((SECONDS + FIRST_RUN_WAIT))
  until [[ -e "${first}/state/sidecar.up.done.1" ]]; do
    if (( SECONDS >= deadline )); then
      harness::add_reason '앞 실행이 재생성에 닿지 않음'
      return 1
    fi
    sleep 0.2
  done
}

# R30 — 동시 실행: 앞 실행이 잠금을 쥔 동안 뒤 실행(같은 스위치 파일 · 같은
# 컨테이너 상태)은 lock(종료 1) · docker · chown 0. 앞 실행은 제 판정(recreate
# rolled-back)대로 끝나고, 끝난 뒤 다시 돌리면 잠금이 잡혀 OK on 이다.
case_concurrent_lock() {
  local ok=0 first
  setup_case
  first="${ops_dir}"
  if ! start_first_run; then
    harness::wait_exit "${first_pid}" "${OPS_RUN_TIMEOUT}" || true
    return 1
  fi

  ops_fixture::new_case
  link_fakes
  ops_env+=("MEDIA_COMPOSE_FILE=${first}/etc/compose.yml"
    'MEDIA_OPS_HEALTH_WAIT=2')
  ops_fixture::run "${SCRIPT}" "PCBENCH_FAKE_STATE_DIR=${first}/state" -- on \
    || ok=1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL lock' 1 || ok=1
  if [[ "$(ops_fixture::count docker)" != 0 \
    || "$(ops_fixture::count chown)" != 0 ]]; then
    harness::add_reason '뒤 실행이 docker · chown 을 부름'
    ok=1
  fi
  if [[ "$(cat -- "${ops_dir}/log/flock.1.argv" 2>/dev/null)" != \
    "$(printf '%s\n' -n 9)" ]]; then
    harness::add_reason '뒤 실행의 잠금이 flock -n 9 한 번이 아님'
    ok=1
  fi

  if ! harness::wait_exit "${first_pid}" "${OPS_RUN_TIMEOUT}"; then
    harness::add_reason '앞 실행이 끝나지 않음'
    return 1
  fi
  ops_dir="${first}"
  ops_status="$(cat -- "${first}/status" 2>/dev/null)" || ops_status=-1
  expect_rolled_back 'SIDECAR ROUND KEYS FAIL recreate rolled-back' \
    pcbenchrecreated1 || ok=1
  expect_mode "${first}/etc/${LOCK_NAME}" 600 '잠금 파일' || ok=1

  ops_fixture::run "${SCRIPT}" -- on || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS OK on' 0 || ok=1
  expect_running pcbenchrecreated2 "${LINE_ON}" round || ok=1
  return "${ok}"
}

# R31 — 잠금 자리가 링크 · 디렉터리 → lock · docker 0 · 파일 무변(on · off).
case_lock_spot() {
  local ok=0 variant want lock
  for variant in link dir; do
    for want in on off; do
      setup_case
      lock="${ops_dir}/etc/${LOCK_NAME}"
      if [[ "${variant}" == link ]]; then
        : >"${ops_dir}/lock.target"
        ln -s -- "${ops_dir}/lock.target" "${lock}"
      else
        mkdir -- "${lock}"
      fi
      ops_fixture::run "${SCRIPT}" -- "${want}" || return 1
      ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL lock' 1 || ok=1
      [[ "$(ops_fixture::count docker)" == 0 ]] || {
        harness::add_reason "${variant} ${want}: docker 호출 있음"
        ok=1
      }
      expect_untouched "${LINE_OFF}" || ok=1
    done
  done
  return "${ok}"
}

# R32 — layout 대조는 맞았지만 그 컨테이너가 실행 중이 아님(기동 줄 뒤 멈춤 ·
# 재시작 중) → verify 갈래: on 은 verify rolled-back, 복원 컨테이너면
# restore-failed(종료 4), off 는 FAIL verify.
case_not_running_after_verify() {
  local ok=0
  setup_case
  up_mode 1 exited
  ops_fixture::run "${SCRIPT}" -- on || return 1
  expect_rolled_back 'SIDECAR ROUND KEYS FAIL verify rolled-back' \
    pcbenchrecreated2 || ok=1

  setup_case
  up_mode 1 nojournal
  up_mode 2 restarting
  ops_fixture::run "${SCRIPT}" -- on || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL journal restore-failed' 4 \
    || ok=1
  expect_compose_count 2 || ok=1

  setup_case "${LINE_ON}"
  up_mode 1 exited
  ops_fixture::run "${SCRIPT}" -- off || return 1
  ops_fixture::expect_line 'SIDECAR ROUND KEYS FAIL verify' 1 || ok=1
  expect_content "${ops_dir}/etc/sidecar.env" "${LINE_OFF}" '스위치 파일' \
    || ok=1
  expect_compose_count 1 || ok=1
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/sidecar_round_keys_ops_test.sh\n' \
      >&2
    exit 1
  fi
  trap ops_fixture::cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ops_fixture::init sidecar-round-keys

  harness::run_case R1 'on 성공 → 원자 교체 · 사이드카만 재생성 · layout=round' \
    case_on_ok
  harness::run_case R2 'on · 저널 줄 없음 → journal rolled-back' \
    case_on_journal_rolled_back
  harness::run_case R3 'on · layout=legacy 옴 → verify rolled-back' \
    case_on_verify_rolled_back
  harness::run_case R4 'on · 재생성 실패 → recreate rolled-back' \
    case_on_recreate_rolled_back
  harness::run_case R5 'on · 저널 없음 · 복원 재생성 실패 → restore-failed' \
    case_on_restore_recreate_fail
  harness::run_case R6 'on · 저널 없음 · 복원 컨테이너 round → restore-failed' \
    case_on_restore_round
  harness::run_case R7 'on · 사이드카 실행 중 아님 → not-running · 무변' \
    case_on_not_running
  harness::run_case R8 'off · 사이드카 하나 아님 → FILE off · 재생성 0' \
    case_off_file_only
  harness::run_case R9 'off · 쓰기 실패(링크 · 디렉터리 · chown) → write' \
    case_off_write_fail
  harness::run_case R10 'off 성공 → 파일 끔 · 재생성 · layout=legacy' \
    case_off_ok
  harness::run_case R11 '인자 오류 → usage · docker 0' case_usage
  harness::run_case R12 'on · verify 실패 · 복원 컨테이너 round → restore-failed' \
    case_on_verify_restore_round
  harness::run_case R13 '설정 오류 → config · docker 0' case_config
  harness::run_case R14 'on · 지금 파일 꼴 밖 → write · 무변' \
    case_on_bad_current
  harness::run_case R15 'off · 실행 중 사이드카의 단계 실패 → 그 단계 · 복원 없음' \
    case_off_fail_stages
  harness::run_case R16 '런북 MEDIA DISABLED 판정 → disabled 만 OK' \
    case_runbook_disabled
  harness::run_case R17 '런북 layout 판정 → 실행 중 legacy(∧ Q1 0)만 OK' \
    case_runbook_layout
  harness::run_case R30 '동시 실행 → 뒤 실행 lock · 무변 · 끝나면 풀림' \
    case_concurrent_lock
  harness::run_case R31 '잠금 자리 링크 · 디렉터리 → lock · 무변' \
    case_lock_spot
  harness::run_case R32 'layout 맞은 뒤 실행 중 아님 → verify 갈래' \
    case_not_running_after_verify

  # shellcheck disable=SC2086 # 이름 목록을 낱낱으로 넘긴다.
  if ! harness::summary 0 ${CASES}; then
    exit 1
  fi
}

main "$@"
