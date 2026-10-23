#!/usr/bin/env bash
#
# scp-recheck.sh 를 시험한다 — 승인 기준 손상 묶음(승인 기록 · 완료
# 표지 · 결과 파일을 고치거나 링크로 바꾼 기준의 거부, 줄바꿈 없는 마지막
# 행, 저장소를 가리키는 승인 디렉터리 링크).
#
# 사본 트리 · 고정 입력 · 공용 단언(사례 5 유출 — 모든 사례가 끝에 본다)은
# scp_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/scp_recheck_reference_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/scp_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/scp_testlib.sh"

#######################################
# 승인 기록의 해시를 지금 result.tsv 로 다시 쓴다(시각 · 이유는 그대로) —
# 해시 대조를 지나서 꼴 검사만 보게 하려고.
# Arguments:
#   증거 디렉터리.
#######################################
rehash_acceptance() {
  local dir="$1" line sum
  line="$(cat -- "${dir}/accepted.tsv")" || return 1
  sum="$(sha256_of "${dir}/result.tsv")" || return 1
  testlib::tsv "${line%%$'\t'*}" "${sum}" "${line##*$'\t'}" \
    >"${dir}/accepted.tsv"
}

#######################################
# 완료 표지의 해시를 지금 result.tsv 로 다시 쓴다(시각은 그대로) — 판정이
# 처음부터 그 결과를 쓴 것처럼 만들려고.
# Arguments:
#   증거 디렉터리.
#######################################
rehash_marker() {
  local dir="$1" line sum
  line="$(cat -- "${dir}/complete.txt")" || return 1
  sum="$(sha256_of "${dir}/result.tsv")" || return 1
  testlib::tsv "${line%%$'\t'*}" "${sum}" >"${dir}/complete.txt"
}

#######################################
# 결과 파일을 「주어진 행 + 맞는 행(tag:GetTagKeys 허용)」 으로 바꿔 쓰고
# 완료 표지 · 승인 기록의 해시를 맞춘다 — 해시 대조를 지나 결과 파일의 값
# 꼴 검사에 닿게 하려고.
# Arguments:
#   증거 디렉터리, 주어진 행의 칸들.
# Returns:
#   쓰지 못하면 1.
#######################################
rewrite_result_row() {
  local dir="$1"
  shift
  {
    testlib::tsv "$@"
    testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
  } >"${dir}/result.tsv" && rehash_marker "${dir}" &&
    rehash_acceptance "${dir}"
}

#######################################
# 승인 기준 하나를 고친다(종류별).
# Globals:
#   NOT_UTC, OTHER_SHA256
# Arguments:
#   사례 디렉터리, 승인한 증거 디렉터리, 종류.
# Returns:
#   고치지 못하면 1.
#######################################
tamper_reference() {
  local case_dir="$1" dir="$2" kind="$3" line
  case "${kind}" in
    accepted-empty) : >"${dir}/accepted.tsv" ;;
    accepted-link)
      mv -- "${dir}/accepted.tsv" "${case_dir}/in/accepted.tsv" &&
        ln -s -- "${case_dir}/in/accepted.tsv" "${dir}/accepted.tsv"
      ;;
    accepted-dangling-link)
      # 대상 없는 링크 — 후보 찾기에서 빠지면 앞 기준이 조용히 바뀐다.
      rm -f -- "${dir}/accepted.tsv" &&
        ln -s -- "${case_dir}/in/nowhere.tsv" "${dir}/accepted.tsv"
      ;;
    accepted-bad-time)
      line="$(cat -- "${dir}/accepted.tsv")" &&
        testlib::tsv "${NOT_UTC}" "${line#*$'\t'}" >"${dir}/accepted.tsv"
      ;;
    accepted-other-hash)
      line="$(cat -- "${dir}/accepted.tsv")" &&
        testlib::tsv "${line%%$'\t'*}" "${OTHER_SHA256}" "${line##*$'\t'}" \
          >"${dir}/accepted.tsv"
      ;;
    complete-link)
      mv -- "${dir}/complete.txt" "${case_dir}/in/complete.txt" &&
        ln -s -- "${case_dir}/in/complete.txt" "${dir}/complete.txt"
      ;;
    result-link)
      mv -- "${dir}/result.tsv" "${case_dir}/in/result.tsv" &&
        ln -s -- "${case_dir}/in/result.tsv" "${dir}/result.tsv"
      ;;
    result-0644) chmod 644 "${dir}/result.tsv" ;;
    result-fifo)
      rm -f -- "${dir}/result.tsv" && mkfifo -m 600 "${dir}/result.tsv"
      ;;
    result-fifo-link)
      mkfifo -m 600 "${case_dir}/in/result.fifo" &&
        rm -f -- "${dir}/result.tsv" &&
        ln -s -- "${case_dir}/in/result.fifo" "${dir}/result.tsv"
      ;;
    result-fields)
      # 칸이 모자란 행(AllowedByOrganizations 없음) — 칸을 나누면 마지막
      # 칸이 넷째 칸을 되풀이해 다섯 칸처럼 읽힌다. 표지 · 승인 기록의
      # 해시도 맞춰 해시 대조를 지나 칸 수 검사에 닿게 한다.
      {
        testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 allow allowed
        testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
      } >"${dir}/result.tsv" && rehash_marker "${dir}" &&
        rehash_acceptance "${dir}"
      ;;
    result-dup)
      # 표지 · 승인 기록의 해시도 맞춰 중복 검사에 닿게 한다.
      testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True \
        >>"${dir}/result.tsv" && rehash_marker "${dir}" &&
        rehash_acceptance "${dir}"
      ;;
    result-pair)
      # 판정과 시뮬레이터 판정의 짝이 틀림(허용인데 implicitDeny).
      rewrite_result_row "${dir}" iam:GetAccountAuthorizationDetails \
        us-east-1 allow implicitDeny True
      ;;
    result-bad-action)
      rewrite_result_row "${dir}" iam-GetAccountAuthorizationDetails \
        us-east-1 allow allowed True
      ;;
    result-bad-region)
      rewrite_result_row "${dir}" iam:GetAccountAuthorizationDetails \
        us_east_1 allow allowed True
      ;;
    result-quote)
      # 다섯째 칸(AllowedByOrganizations)에 큰따옴표 — precheck::is_plain 이
      # 막는다.
      rewrite_result_row "${dir}" iam:GetAccountAuthorizationDetails \
        us-east-1 allow allowed '"True"'
      ;;
    result-empty)
      : >"${dir}/result.tsv" && rehash_marker "${dir}" &&
        rehash_acceptance "${dir}"
      ;;
    result-changed)
      {
        testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 deny \
          implicitDeny False
        testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
      } >"${dir}/result.tsv"
      ;;
    result-rehashed)
      # 결과를 꼴만 맞게 고치고 승인 기록의 해시도 맞춘다 — 판정 때의
      # 해시(완료 표지)만 다르다.
      {
        testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 deny \
          implicitDeny False
        testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
      } >"${dir}/result.tsv" && rehash_acceptance "${dir}"
      ;;
    complete-missing) rm -f -- "${dir}/complete.txt" ;;
    sts-link)
      mv -- "${dir}/sts.txt" "${case_dir}/in/sts.txt" &&
        ln -s -- "${case_dir}/in/sts.txt" "${dir}/sts.txt"
      ;;
    sts-0644) chmod 644 "${dir}/sts.txt" ;;
    # 승인 디렉터리를 검색할 수 없게 — 승인 기록이 있는지 볼 수 없다. 권한은
    # testlib 의 EXIT 트랩이 지우기 전에 되돌린다.
    approved-dir-0600) chmod 600 "${dir}" ;;
    approved-dir-0000) chmod 000 "${dir}" ;;
    approved-dir-file)
      # 디렉터리 자리에 실행 비트가 있는 일반 파일 — 검색 권한(-x)만 보면
      # 「검색할 수 있는데 승인 기록이 없음」 으로 읽힌다.
      mv -- "${dir}" "${case_dir}/in/approved" && : >"${dir}" &&
        chmod 700 "${dir}"
      ;;
    *) return 0 ;;
  esac
}

#######################################
# 승인 기준이 손대진 채로는 기준이 되지 않는다(종료 2): 승인 기록이 빈 ·
# 링크 · 대상 없는 링크 · UTC 아닌 시각 · 판정 때와 다른 해시, 완료 표지가
# 링크 · 없음, 결과가 링크 · 0644 · FIFO · FIFO 를 가리키는 링크 · 칸 수 틀림
# (칸 모자람) · 중복 행 · 판정과 시뮬레이터 판정의 짝 틀림 · 동작 꼴 밖 ·
# 리전 꼴 밖 · 큰따옴표 든 칸 · 빈 결과 · 승인 뒤 바뀜 · 승인 기록 해시까지
# 맞춰 바뀜, 호출 주체 기록이 링크 · 0644, 승인 디렉터리가 0600 · 0000(검색
# 불가 — 건너뛰면 앞 기준이 조용히 이전 승인 · baseline 으로 바뀐다) ·
# 디렉터리 자리의 실행 가능한 일반 파일, 다른 계정에서 승인한 기준. 기준
# 파일은 내용 · 해시를 읽기 전에 검사하므로 FIFO 에서 걸리지 않는다
# (RUN_LIMIT 안에 끝남). 사례마다 그 사례가 겨누는 검사에 닿게 한다 — 내용을
# 바꾼 사례는 표지 · 승인 기록의 해시를 맞춰 앞의 해시 대조에서 먼저 끝나지
# 않게 한다(해시 대조 자체를 겨누는 result-changed · result-rehashed 는
# 빼고).
# Globals:
#   run_out
#######################################
expect_tampered_reference_rejected() {
  local kind case_dir name env_arg
  for kind in accepted-empty accepted-link accepted-dangling-link \
    accepted-bad-time accepted-other-hash complete-link complete-missing \
    result-link result-0644 result-fifo result-fifo-link result-fields \
    result-dup result-pair result-bad-action result-bad-region result-quote \
    result-empty result-changed result-rehashed sts-link sts-0644 \
    approved-dir-0600 approved-dir-0000 approved-dir-file other-account; do
    if ! case_dir="$(new_tree_case)" ||
      ! name="$(judge_and_accept "${case_dir}" gamma)" ||
      ! tamper_reference "${case_dir}" \
        "${case_dir}/home/.pokeclip-precheck/${name}" "${kind}"; then
      testlib::fail "tamper-${kind}: 시험 준비 실패"
      return
    fi
    env_arg=FAKE_AWS_SIM=ok
    [[ "${kind}" == other-account ]] && env_arg=FAKE_AWS_ACCOUNT=842093557711
    run_recheck "${case_dir}" after "${env_arg}" gamma
    expect_unverifiable "tamper-${kind}" || return
    if grep -q -- '842093557711' "${run_out}.stdout" "${run_out}.stderr"; then
      testlib::fail "tamper-${kind}: 다른 계정 ID 가 출력에 나옴"
      return
    fi
  done
  testlib::ok "tamper → 승인 기록 · 완료 표지 · 결과 · 호출 주체 기록 변조 ·" \
    "검색 불가 승인 디렉터리와 다른 계정 기준 모두 종료 2(FIFO 에서 걸리지" \
    "않음)"
}

#######################################
# 결과 파일의 마지막 줄 뒤에 줄바꿈이 없어도 그 행을 빠뜨리지 않는다(줄
# 단위로 읽는 모든 루프의 규칙). 판정이 그렇게 쓴 것처럼 완료 표지의
# 해시도 맞춘다.
#######################################
expect_result_without_final_newline() {
  local case_dir name dir content
  case_dir="$(new_tree_case)" || {
    testlib::fail "final-newline: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" first gamma
  name="$(judged_name)"
  dir="${case_dir}/home/.pokeclip-precheck/${name}"
  if ! content="$(cat -- "${dir}/result.tsv")" ||
    ! printf '%s' "${content}" >"${dir}/result.tsv" ||
    ! rehash_marker "${dir}"; then
    testlib::fail "final-newline: 시험 준비 실패"
    return
  fi
  run_recheck "${case_dir}" accept --accept gamma --dir "${name}" \
    --reason 'kty 답'
  run_recheck "${case_dir}" second gamma
  expect_result final-newline 0 'SCP RECHECK OK 2' 2 || return
  testlib::ok "final-newline → 줄바꿈 없는 마지막 행도 앞 기준에 듦"
}

#######################################
# 승인 기준을 읽을 때도 증거 경로 검사를 한다: 루트 안의 승인 디렉터리가
# 저장소를 가리키는 링크면 종료 2 이고, 링크 너머에 아무것도 쓰지 않는다.
# Globals:
#   run_out, REJECTED
#######################################
expect_approved_link_rejected() {
  local case_dir root repo before after
  case_dir="$(new_tree_case)" || {
    testlib::fail "approved-link: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  repo="${case_dir}/in/repo"
  if ! mkdir -p -- "${repo}/.git" "${repo}/evidence" ||
    ! chmod 700 "${repo}/evidence" ||
    ! printf '%s\n' \
      $'iam:GetAccountAuthorizationDetails\tus-east-1\tallow\tallowed\tTrue' \
      $'tag:GetTagKeys\tap-northeast-2\tallow\tallowed\tTrue' \
      >"${repo}/evidence/result.tsv" ||
    ! printf '2026-01-01T00:00:00Z\tkty 답\n' >"${repo}/evidence/accepted.tsv" ||
    ! mkdir -m 700 -- "${root}" ||
    ! ln -s -- "${repo}/evidence" "${root}/scp-gamma-20260101T000000Z"; then
    testlib::fail "approved-link: 시험 준비 실패"
    return
  fi
  before="$(find "${repo}" | wc -l)"
  run_recheck "${case_dir}" link gamma
  after="$(find "${repo}" | wc -l)"
  expect_unverifiable approved-link || return
  if [[ "$(testlib::count "${run_out}.stderr" "${REJECTED}")" -lt 1 ||
    "${before}" -ne "${after}" ]]; then
    testlib::fail "approved-link: 증거 경로 거부가 아니거나 링크 너머가 바뀜"
    return
  fi
  testlib::ok "approved-link → 종료 2 · EVIDENCE PATH REJECTED · 링크 너머 그대로"
}

main() {
  testlib::init scp_recheck_reference_test
  expect_tampered_reference_rejected
  expect_result_without_final_newline
  expect_approved_link_rejected
  testlib::finish scp_recheck_reference_test
}

main "$@"
