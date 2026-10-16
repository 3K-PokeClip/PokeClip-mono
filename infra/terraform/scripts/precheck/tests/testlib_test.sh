#!/usr/bin/env bash
#
# 시험 부품(testlib.sh)의 격리 가드를 시험한다 — 시험이 진짜 aws 나 실제
# 자격 증명에 닿지 않는지(장부 r1 #13).
#
#   - testlib::require_fakebin: 가짜 aws 가 실행 가능하고 PATH 앞에서 그것으로
#     풀릴 때만 통과한다. 실행 비트가 빠진 사본은 거부한다.
#   - testlib::run: 부르는 쪽 환경의 AWS_* 를 모두 지우고, 설정 · 자격 증명
#     파일을 /dev/null 로, 인스턴스 메타데이터 조회를 끈 채로 돌린다.
#   - testlib::run_limited: 제한 시간을 넘긴 명령은 자손까지 끝내고 124 를
#     남긴다(장부 r2 #10 — FIFO 에서 걸리는 회귀가 시험을 멈추지 않게).
#
# 사용: bash infra/terraform/scripts/precheck/tests/testlib_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

#######################################
# 가짜 aws 확인: 저장소의 fakebin 은 통과, 실행 비트가 없는 사본 · aws 가
# 없는 디렉터리는 거부.
#######################################
expect_fakebin_required() {
  local case_dir
  case_dir="$(testlib::new_case)" || {
    testlib::fail "fakebin: 시험 준비 실패"
    return
  }
  if ! testlib::require_fakebin "${FAKEBIN}"; then
    testlib::fail "fakebin: 저장소의 가짜 aws 를 거부함"
    return
  fi
  if ! mkdir -p -- "${case_dir}/in/noexec" "${case_dir}/in/empty" ||
    ! cp -- "${FAKEBIN}/aws" "${case_dir}/in/noexec/aws" ||
    ! chmod 644 "${case_dir}/in/noexec/aws"; then
    testlib::fail "fakebin: 시험 준비 실패"
    return
  fi
  if testlib::require_fakebin "${case_dir}/in/noexec" 2>/dev/null ||
    testlib::require_fakebin "${case_dir}/in/empty" 2>/dev/null; then
    testlib::fail "fakebin: 실행 비트 없는 사본 · 빈 디렉터리를 받아들임"
    return
  fi
  testlib::ok "fakebin → 실행 가능한 가짜 aws 만 받음"
}

#######################################
# 실행 환경 격리: 부르는 쪽에 AWS_* 가 있어도 시험 명령에는 가지 않고,
# 설정 · 자격 증명 파일은 /dev/null, 메타데이터 조회는 꺼져 있다.
#######################################
expect_aws_env_isolated() {
  local case_dir out
  case_dir="$(testlib::new_case)" || {
    testlib::fail "aws-env: 시험 준비 실패"
    return
  }
  AWS_PROFILE=leak-profile AWS_ACCESS_KEY_ID=leak-key \
    AWS_SECRET_ACCESS_KEY=leak-secret AWS_SESSION_TOKEN=leak-token \
    AWS_DEFAULT_PROFILE=leak-default AWS_ROLE_ARN=leak-role \
    testlib::run "${case_dir}" env env
  out="${run_out}.stdout"
  if grep -q -- 'leak-' "${out}"; then
    testlib::fail "aws-env: 부르는 쪽 AWS_* 가 시험 명령에 감 —" \
      "$(grep -- 'leak-' "${out}" | cut -d= -f1 | tr '\n' ' ')"
    return
  fi
  if ! grep -qx 'AWS_CONFIG_FILE=/dev/null' "${out}" ||
    ! grep -qx 'AWS_SHARED_CREDENTIALS_FILE=/dev/null' "${out}" ||
    ! grep -qx 'AWS_EC2_METADATA_DISABLED=true' "${out}"; then
    testlib::fail "aws-env: 설정 · 자격 증명 파일 /dev/null · 메타데이터 끔이 없음"
    return
  fi
  testlib::ok "aws-env → AWS_* 0 · 설정 · 자격 증명 /dev/null · 메타데이터 끔"
}

#######################################
# 시간 제한 실행: 끝나는 명령은 종료 코드를 그대로 남기고, FIFO 를 읽다
# 걸린 명령(명령 치환 안의 손자 프로세스)은 제한 시간에 자손까지 끝내고
# 124 를 남긴다. 걸린 읽기 프로세스가 남지 않아야 한다.
#######################################
expect_run_limited() {
  local case_dir fifo start probe
  case_dir="$(testlib::new_case)" || {
    testlib::fail "run-limited: 시험 준비 실패"
    return
  }
  testlib::run_limited 10 "${case_dir}" quick bash -c 'exit 3'
  if [[ "${run_status}" -ne 3 ]]; then
    testlib::fail "run-limited: 끝나는 명령의 종료 3 이 아니라 ${run_status}"
    return
  fi
  fifo="${case_dir}/in/fifo"
  mkfifo -m 600 "${fifo}" || {
    testlib::fail "run-limited: 시험 준비 실패"
    return
  }
  start="${SECONDS}"
  # shellcheck disable=SC2016 # 작은따옴표 안은 새 bash 가 펼친다.
  testlib::run_limited 1 "${case_dir}" blocked bash -c 'x="$(cat -- "$1")"' _ \
    "${fifo}"
  if [[ "${run_status}" -ne "${TIMED_OUT}" ]] || ((SECONDS - start > 5)); then
    testlib::fail "run-limited: 걸린 명령이 1초 제한에 ${TIMED_OUT} 으로" \
      "끝나지 않음 — ${run_status} · $((SECONDS - start))초"
    return
  fi
  # 걸린 읽기가 남았으면 쓰기 쪽 열기가 바로 짝을 찾아 끝난다. 짝이 없어
  # 걸린 탐침은 끝낼 때 열기 실패를 알리므로 그 문구는 버린다.
  (exec 3>"${fifo}") 2>/dev/null &
  probe=$!
  sleep 1
  if ! kill -0 "${probe}" 2>/dev/null; then
    wait "${probe}"
    testlib::fail "run-limited: FIFO 를 읽던 프로세스가 끝나지 않고 남음"
    return
  fi
  kill "${probe}" 2>/dev/null
  wait "${probe}" 2>/dev/null
  testlib::ok "run-limited → 종료 코드 그대로 · 걸린 명령은 ${TIMED_OUT} ·" \
    "자손까지 끝냄"
}

main() {
  testlib::init testlib_test
  expect_fakebin_required
  expect_aws_env_isolated
  expect_run_limited
  testlib::finish testlib_test
}

main "$@"
