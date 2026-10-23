#!/usr/bin/env bash
#
# scp-recheck.sh 를 시험한다 — 실패 · 검사 불가 묶음(계획 M17a c1 사례
# 6 과 기록 시각 · I/O · 호출 주체 증거 쓰기 실패, CLI 기록 켜짐, 입력 경로
# 되울림 금지).
#
# 사본 트리 · 고정 입력 · 공용 단언(사례 5 유출 — 모든 사례가 끝에 본다)은
# scp_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/scp_recheck_failure_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/scp_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/scp_testlib.sh"

#######################################
# 완료 표지 · 승인 기록은 다 쓴 뒤에만 생긴다(cc r2 지적 2): 기록에 넣을
# UTC 시각을 읽지 못하면(date 실패) 종료 2 이고 빈 complete.txt · 시각 칸이
# 빈 accepted.tsv 가 남지 않는다 — 남으면 끝나지 않은 판정이 끝난 것처럼,
# 하지 않은 승인이 한 것처럼 보인다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH
#######################################
expect_no_record_when_time_fails() {
  local case_dir bin real_date name
  case_dir="$(new_tree_case)" || {
    testlib::fail "time-fails: 시험 준비 실패"
    return
  }
  bin="${case_dir}/in/datebin"
  # 기록의 UTC 시각 꼴(+%Y-%m-%dT%H:%M:%SZ)일 때만 실패하는 date.
  if ! real_date="$(command -v date)" || ! mkdir -p -- "${bin}" ||
    ! {
      # shellcheck disable=SC2016 # 가짜 date 의 본문 글자 그대로다.
      printf '%s\n' '#!/bin/bash' 'for arg in "$@"; do' \
        '  [[ "${arg}" == "+%Y-%m-%dT%H:%M:%SZ" ]] && exit 1' 'done'
      printf 'exec %q "$@"\n' "${real_date}"
    } >"${bin}/date" || ! chmod 700 "${bin}/date"; then
    testlib::fail "time-fails: 시험 준비 실패"
    return
  fi
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" judged \
    env PATH="${bin}:${FAKEBIN}:${PATH}" \
    bash "${case_dir}/tree/scp-recheck.sh" gamma
  expect_unverifiable time-fails-judge || return
  name="$(judged_name)" || {
    testlib::fail "time-fails: 판정이 증거 디렉터리 이름을 알리지 않음"
    return
  }
  if [[ -e "${case_dir}/home/.pokeclip-precheck/${name}/complete.txt" ]]; then
    testlib::fail "time-fails: 표지를 쓰지 못했는데 complete.txt 가 남음"
    return
  fi
  run_recheck "${case_dir}" judged-ok gamma
  name="$(judged_name)"
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" accept \
    env PATH="${bin}:${FAKEBIN}:${PATH}" \
    bash "${case_dir}/tree/scp-recheck.sh" --accept gamma --dir "${name}" \
    --reason 'kty 답'
  expect_unverifiable time-fails-accept || return
  if [[ -e "${case_dir}/home/.pokeclip-precheck/${name}/accepted.tsv" ]]; then
    testlib::fail "time-fails: 승인 시각을 못 읽었는데 accepted.tsv 가 남음"
    return
  fi
  testlib::ok "time-fails → 기록 시각 실패는 종료 2 · complete.txt ·" \
    "accepted.tsv 남지 않음"
}

#######################################
# 이상 I/O 때 명령 자체의 진단(절대 경로가 든)을 내지 않는다: 앞
# 기준의 완료 표지 · 승인 기록 · 호출 주체 기록을 읽는 cat 과 완료 표지를
# 옮기는 mv 가 실패하면 종료 2 이고, 원인은 그 자리의 die 문구가 말하며,
# 표준 출력 · 표준 오류에 절대 경로가 없다. 검사(링크 아닌 0600)는 지나고
# 읽기 · 옮기기만 실패하는 상태(읽기 거부 ACL 등 — chmod 000 은 검사가 먼저
# 거부한다)는 PATH 앞의 가짜 cat · mv 로 만든다. 가짜는 FAKE_FAIL 로 끝나는
# 인자를 받으면 진짜처럼 경로를 담은 진단을 내고 실패한다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH, run_out
#######################################
expect_io_failure_hides_paths() {
  local case_dir bin tool real target want paths
  case_dir="$(new_tree_case)" || {
    testlib::fail "io-failure: 시험 준비 실패"
    return
  }
  bin="${case_dir}/in/iobin"
  mkdir -p -- "${bin}" || {
    testlib::fail "io-failure: 시험 준비 실패"
    return
  }
  for tool in cat mv; do
    if ! real="$(command -v "${tool}")" ||
      ! {
        # shellcheck disable=SC2016 # 가짜 명령의 본문 글자 그대로다.
        printf '%s\n' '#!/bin/bash' 'for arg in "$@"; do' \
          '  if [[ "${arg}" == */"${FAKE_FAIL}" ]]; then' \
          '    printf "%s: %s: Permission denied\n" "${0##*/}" "${arg}" >&2' \
          '    exit 1' '  fi' 'done'
        printf 'exec %q "$@"\n' "${real}"
      } >"${bin}/${tool}" || ! chmod 700 "${bin}/${tool}"; then
      testlib::fail "io-failure: 시험 준비 실패"
      return
    fi
  done
  if ! judge_and_accept "${case_dir}" gamma >/dev/null; then
    testlib::fail "io-failure: 시험 준비 실패"
    return
  fi
  for target in complete.txt accepted.tsv sts.txt complete.txt.tmp; do
    case "${target}" in
      complete.txt) want='완료 표지를 읽지 못함' ;;
      accepted.tsv) want='승인 기록을 읽지 못함' ;;
      sts.txt) want='앞 기준의 호출 주체 기록을 읽지 못함' ;;
      *) want='완료 표지를 쓰지 못함' ;;
    esac
    testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "io-${target}" \
      env PATH="${bin}:${FAKEBIN}:${PATH}" FAKE_FAIL="${target}" \
      bash "${case_dir}/tree/scp-recheck.sh" gamma
    expect_unverifiable "io-${target}" || return
    paths="$(testlib::abs_path_lines "${run_out}.stdout")"
    paths+="$(testlib::abs_path_lines "${run_out}.stderr")"
    if [[ -n "${paths}" ||
      "$(testlib::count "${run_out}.stderr" "${want}")" -ne 1 ]]; then
      testlib::fail "io-${target}: 「${want}」 만 기대했으나 —" \
        "$(cat -- "${run_out}.stderr")"
      return
    fi
  done
  testlib::ok "io-failure → cat · mv 실패는 종료 2 · 그 자리 die 문구 ·" \
    "명령 진단의 절대 경로 0"
}

#######################################
# 호출 주체 증거(sts.err · sts.txt)를 쓰지 못하면 종료 2 이고, 원인은
# 「증거를 쓰지 못함(<파일 이름>)」 한 줄이며 bash 원시 진단(절대 경로)이
# 없다. sts.err 자리는 호출 전에 비운 파일을 먼저 만들므로 「호출 주체를
# 확인하지 못함」 으로 가지 않는다. 쓰기 막기는 PATH 앞의 가짜 mkdir 이 증거
# 디렉터리를 만들 때 그 이름의 디렉터리를 심어 만든다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH, run_out
#######################################
expect_identity_write_failure() {
  local case_dir bin target name paths
  case_dir="$(new_tree_case)" || {
    testlib::fail "identity-write: 시험 준비 실패"
    return
  }
  bin="${case_dir}/in/mkdirbin"
  if ! mkdir -p -- "${bin}" || ! testlib::make_blocking_mkdir "${bin}"; then
    testlib::fail "identity-write: 시험 준비 실패"
    return
  fi
  for target in sts.err sts.txt; do
    name="identity-write-${target}"
    testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
      env PATH="${bin}:${FAKEBIN}:${PATH}" FAKE_AWS_SIM=ok \
      FAKE_BLOCK_PREFIX=scp-alpha FAKE_BLOCK_NAME="${target}" \
      bash "${case_dir}/tree/scp-recheck.sh" alpha
    expect_unverifiable "${name}" || return
    testlib::expect_stderr_count "${name}" "증거를 쓰지 못함(${target})" 1 ||
      return
    if [[ "${target}" == sts.err ]]; then
      testlib::expect_stderr_count "${name}" '호출 주체를 확인하지 못함' 0 ||
        return
    fi
    paths="$(testlib::abs_path_lines "${run_out}.stdout")"
    paths+="$(testlib::abs_path_lines "${run_out}.stderr")"
    if [[ -n "${paths}" ]]; then
      testlib::fail "${name}: 절대 경로 — ${paths}"
      return
    fi
  done
  expect_no_leak identity-write "${case_dir}" || return
  testlib::ok "identity-write → sts.err · sts.txt 쓰기 실패는 종료 2 ·" \
    "「증거를 쓰지 못함(<파일>)」 한 줄 · 절대 경로 0"
}

#######################################
# CLI 기록 — 프로필의 cli_history 가 enabled 이거나 AWS_CLI_HISTORY_FILE 이
# 설정돼 있으면 첫 AWS 호출 전에 종료 2(값 · 경로를 되울리지 않고 증거
# 디렉터리도 만들지 않음), 설정 읽기 실패도 종료 2, disabled 면 정상이고
# 설정 조회가 호출 주체 확인보다 먼저다. --accept 는 AWS 를 부르지 않으므로
# 켜져 있어도 승인한다.
# Globals:
#   run_out, run_status
#######################################
expect_cli_history() {
  local case_dir spec name log want calls dirs name_judged
  for spec in enabled file fail disabled; do
    name="cli-history-${spec}"
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    log="${case_dir}/out/aws.log"
    case "${spec}" in
      file)
        run_recheck "${case_dir}" "${name}" \
          "AWS_CLI_HISTORY_FILE=${case_dir}/in/qxhistory.db" alpha
        ;;
      *)
        run_recheck "${case_dir}" "${name}" "FAKE_AWS_CLI_HISTORY=${spec}" alpha
        ;;
    esac
    if [[ "${spec}" == disabled ]]; then
      expect_result "${name}" 0 'SCP RECHECK OK 3' 3 || return
      if [[ "$(sed -n 1p -- "${log}")" != 'configure get cli_history' ||
        "$(sed -n 2p -- "${log}")" != 'sts get-caller-identity'* ]]; then
        testlib::fail "${name}: 설정 조회 · 호출 주체 확인 순서가 아님 —" \
          "$(cat -- "${log}")"
        return
      fi
      continue
    fi
    expect_unverifiable "${name}" || return
    calls="$(testlib::count "${log}" ' ')"
    want=1
    [[ "${spec}" == file ]] && want=0
    if [[ "${calls}" -ne "${want}" || ( "${want}" -eq 1 &&
      "$(cat -- "${log}")" != 'configure get cli_history' ) ]] ||
      grep -qF -e qxhistory -e enabled -- "${run_out}.stderr"; then
      testlib::fail "${name}: AWS 호출 ${calls}건(기대 ${want})이거나 값 ·" \
        "경로를 되울림 — $(cat -- "${run_out}.stderr")"
      return
    fi
    if [[ "${spec}" == fail ]]; then
      testlib::expect_stderr_count "${name}" \
        'CLI 기록 설정(cli_history)을 읽지 못함' 1 || return
    else
      testlib::expect_stderr_count "${name}" \
        'CLI 기록이 켜져 있음 — 증거 밖에 원문이 남음' 1 || return
    fi
    dirs="$(find "${case_dir}/home" -name 'scp-*' -type d | wc -l)"
    if [[ "${dirs}" -ne 0 ]]; then
      testlib::fail "${name}: 멈췄는데 증거 디렉터리 ${dirs}개"
      return
    fi
    expect_no_leak "${name}" "${case_dir}" || return
  done
  run_recheck "${case_dir}" cli-history-judge alpha
  name_judged="$(judged_name)" || {
    testlib::fail "cli-history-accept: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" cli-history-accept FAKE_AWS_CLI_HISTORY=enabled \
    --accept alpha --dir "${name_judged}" --reason 'kty 답'
  if [[ "${run_status}" -ne 0 ]]; then
    testlib::fail "cli-history-accept: 승인은 AWS 를 부르지 않는데 종료" \
      "${run_status} — $(cat -- "${run_out}.stderr")"
    return
  fi
  expect_no_leak cli-history "${case_dir}" || return
  testlib::ok "cli-history → enabled · 기록 파일 환경 변수 · 설정 읽기 실패는" \
    "첫 AWS 호출 전 종료 2 · disabled 는 설정 조회 뒤 정상 · --accept 는 승인"
}

#######################################
# 사용자가 준 입력을 그대로 되울리지 않는다 — 묶음 자리 · --dir
# 자리에 절대 경로(셸이 ~ 를 펼친 HOME 경로 등)를 주면 종료 2 이고, 표준
# 출력 · 표준 오류에 절대 경로(HOME · 로컬 계정 이름)가 없다.
# Globals:
#   run_out
#######################################
expect_input_paths_not_echoed() {
  local case_dir name spec paths
  case_dir="$(new_tree_case)" || {
    testlib::fail "input-paths: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" judged gamma
  name="$(judged_name)"
  for spec in judge-bundle accept-bundle accept-dir; do
    case "${spec}" in
      judge-bundle)
        run_recheck "${case_dir}" "${spec}" "${case_dir}/home/gamma"
        ;;
      accept-bundle)
        run_recheck "${case_dir}" "${spec}" --accept "${case_dir}/home/gamma" \
          --dir "${name}" --reason 'kty 답'
        ;;
      *)
        run_recheck "${case_dir}" "${spec}" --accept gamma \
          --dir "${case_dir}/home/.pokeclip-precheck/${name}" --reason 'kty 답'
        ;;
    esac
    expect_unverifiable "input-paths-${spec}" || return
    paths="$(testlib::abs_path_lines "${run_out}.stdout")"
    paths+="$(testlib::abs_path_lines "${run_out}.stderr")"
    if [[ -n "${paths}" ]]; then
      testlib::fail "input-paths-${spec}: 입력의 절대 경로를 되울림 — ${paths}"
      return
    fi
  done
  testlib::ok "input-paths → 묶음 · --dir 자리의 절대 경로는 종료 2 · 되울리지" \
    "않음"
}

#######################################
# 사례 6: 재판정 호출 실패 · 호출 자체의 AccessDenied · 빈 응답 · 다른 동작의
# 응답 · 여러 줄 · 칸이 모자란 응답 · 호출 주체 확인 실패 · IAM 사용자가
# 아닌 주체 · 묶음 없음 · 꼴이 틀린 묶음은 모두 종료 2 이고 유출이 없다.
# 원인 문구로 가르는 셋: 호출 실패와 호출 자체의 AccessDenied 는 다른
# 문구다(「AccessDenied」 든 줄은 후자에만 하나). IAM 사용자가 아닌 주체는
# 그 문구로 멈춘다 — 가짜 aws 가 역할 세션 ARN 요청을 입력 오류로 끝내므로
# 그 검사가 없어도 종료 2 라, 문구로만 가를 수 있다.
# Globals:
#   run_out
#######################################
expect_unverifiable_cases() {
  local case_dir spec name env_arg bundle
  case_dir="$(new_tree_case)" || {
    testlib::fail "unverifiable: 시험 준비 실패"
    return
  }
  for spec in \
    'sim-fail FAKE_AWS_SIM=fail alpha' \
    'sim-denied-call FAKE_AWS_SIM=denied-call alpha' \
    'sim-empty FAKE_AWS_SIM=empty alpha' \
    'sim-bad-action FAKE_AWS_SIM=bad-action alpha' \
    'sim-two-lines FAKE_AWS_SIM=two-lines alpha' \
    'sim-short FAKE_AWS_SIM=short alpha' \
    'identity-fail FAKE_AWS_IDENTITY=fail alpha' \
    'identity-role FAKE_AWS_IDENTITY=role alpha' \
    'no-bundle FAKE_AWS_SIM=ok nope' \
    'baseline-name FAKE_AWS_SIM=ok baseline' \
    'broken-bundle FAKE_AWS_SIM=ok broken' \
    'dup-bundle FAKE_AWS_SIM=ok dup' \
    'collapsed-bundle FAKE_AWS_SIM=ok collapsed'; do
    # 「이름 환경 묶음」 세 낱말 — here-string 대신 매개변수 확장으로 나눈다.
    name="${spec%% *}"
    bundle="${spec##* }"
    env_arg="${spec#* }"
    env_arg="${env_arg%% *}"
    run_recheck "${case_dir}" "${name}" "${env_arg}" "${bundle}"
    expect_unverifiable "${name}" || return
    case "${name}" in
      sim-fail)
        testlib::expect_stderr_count "${name}" '시뮬레이터 호출 실패(' 1 &&
          testlib::expect_stderr_count "${name}" AccessDenied 0
        ;;
      sim-denied-call)
        testlib::expect_stderr_count "${name}" '시뮬레이터 호출 실패(' 0 &&
          testlib::expect_stderr_count "${name}" AccessDenied 1
        ;;
      identity-role)
        testlib::expect_stderr_count "${name}" \
          '호출 주체가 IAM 사용자가 아님' 1
        ;;
      *) ;;
    esac || return
  done
  run_recheck "${case_dir}" no-args
  expect_unverifiable no-args || return
  expect_no_leak unverifiable "${case_dir}" || return
  testlib::ok "unverifiable → 호출 실패 · AccessDenied · 빈 응답 · 주체 · 묶음" \
    "모두 종료 2 · 유출 0 · 호출 실패 · AccessDenied · 주체는 원인 문구로 가름"
}

main() {
  testlib::init scp_recheck_failure_test
  expect_no_record_when_time_fails
  expect_io_failure_hides_paths
  expect_identity_write_failure
  expect_cli_history
  expect_input_paths_not_echoed
  expect_unverifiable_cases
  testlib::finish scp_recheck_failure_test
}

main "$@"
