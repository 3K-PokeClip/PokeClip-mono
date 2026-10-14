#!/usr/bin/env bash
#
# 시험 부품(testlib.sh)의 격리 가드를 시험한다 — 시험이 진짜 aws 나 실제
# 자격 증명에 닿지 않는지(장부 r1 #13).
#
#   - testlib::require_fakebin: 가짜 aws 가 실행 가능하고 PATH 앞에서 그것으로
#     풀릴 때만 통과한다. 실행 비트가 빠진 사본은 거부한다.
#   - testlib::run: 부르는 쪽 환경의 AWS_* 를 모두 지우고, 설정 · 자격 증명
#     파일을 /dev/null 로, 인스턴스 메타데이터 조회를 끈 채로 돌린다.
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

main() {
  testlib::init testlib_test
  expect_fakebin_required
  expect_aws_env_isolated
  testlib::finish testlib_test
}

main "$@"
