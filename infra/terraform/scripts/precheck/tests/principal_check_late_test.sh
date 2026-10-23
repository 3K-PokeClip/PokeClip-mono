#!/usr/bin/env bash
#
# principal-check.sh 를 시험한다 — 켬 · apply 뒤에 정해지는 대상(rt1a 종결
# r1 H2 · H4, 사례 34 · 35).
#
#   34 실행기 인스턴스: tag:ec2:instance 해석 0건은 TARGET_NONE, 풀리면
#      ssm:SendCommand · ssm:StartSession 을 인스턴스 ARN 으로 묻고 허용이면
#      멈춤.
#   35 관리형 마스터 비밀: input:<이름> 해석은 증거 루트의
#      input-<이름>.txt(0600) 를 읽는다. 파일이 없으면 TARGET_NONE, 이 계정 ·
#      행의 리전의 비밀 전체 ARN(이름이 rds!db- 로 시작 · 끝이 「-」 + 영숫자
#      6자)이면 그 ARN 으로 묻고, 빈 파일 · 꼴 밖 ARN(부분 ARN · 엉뚱한 비밀
#      · 접미 길이 틀림 포함) · 0600 아님 · 링크(끊긴 링크 포함)는 종료 2 다.
#   36 정책 읽기 응답의 ARN: 비밀 정책 응답의 ARN 이 요청한 ARN 과 다르거나
#      (부분 ARN 을 서비스가 다른 비밀로 풂) 응답 꼴 계약(SECRET_QUERY)이
#      어긋나면 종료 2 다. 모르는 입력 이름 · 비밀 아닌 서비스의 input: 행은
#      데이터 꼴로 종료 2 다.
#
# 고정 입력 · 사본 트리 · 공용 단언(사례 22 유출 — 모든 사례가 끝에
# 본다)은 principal_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/principal_check_late_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/principal_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/principal_testlib.sh"

readonly INSTANCE_ARN="arn:aws:ec2:${SEOUL_ACCOUNT}:instance/i-0qx1"
MASTER_ARN="arn:aws:secretsmanager:${SEOUL_ACCOUNT}:secret:rds!db-"
MASTER_ARN+="${MARK_SECRET}-0a1b-AbCdEf"
readonly MASTER_ARN
readonly INPUT_NAME='rds-master'

#######################################
# 사본 트리의 대상 행에 한 행을 덧붙인다.
# Arguments:
#   사례 디렉터리, 행 칸들.
#######################################
add_row() {
  local case_dir="$1"
  shift
  testlib::tsv "$@" >>"${case_dir}/tree/data/principal_sim/prod_targets.tsv"
}

#######################################
# 증거 루트(0700)에 입력 파일(0600)을 쓴다.
# Arguments:
#   사례 디렉터리, 내용(printf 형식 없이 그대로).
#######################################
write_input() {
  local root="$1/home/.pokeclip-precheck"
  [[ -d "${root}" ]] || mkdir -m 700 -- "${root}" || return 1
  (umask 077 && printf '%s' "$2" >"${root}/input-${INPUT_NAME}.txt")
}

#######################################
# 사례 34: 실행기 인스턴스 행 — 0건 TARGET_NONE · 허용 멈춤 · 거부 통과.
#######################################
expect_instance_row() {
  local case_dir dir action
  case_dir="$(new_tree_case)" || {
    testlib::fail "instance: 시험 준비 실패"
    return
  }
  add_row "${case_dir}" prod-instance O7a tag:ec2:instance instance \
    ap-northeast-2 ssm:SendCommand,ssm:StartSession none -
  run_check "${case_dir}" instance-none O7a
  expect_result instance-none 3 'PRINCIPAL CHECK UNDECIDED 1' || return
  expect_rows instance-none \
    '| - | prod-instance | - | ap-northeast-2 | 판정 불가 | TARGET_NONE |' 1 ||
    return
  expect_no_leak instance-none "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  add_row "${case_dir}" prod-instance O7a tag:ec2:instance instance \
    ap-northeast-2 ssm:SendCommand,ssm:StartSession none -
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-resources ec2:instance@ap-northeast-2 \
      "${INSTANCE_ARN}")"
  run_check "${case_dir}" instance-allowed O7a
  expect_result instance-allowed 1 'PRINCIPAL CHECK STOP 2' || return
  expect_rows instance-allowed \
    '| prod-instance | ssm:SendCommand | ap-northeast-2 | 멈춤 | ALLOWED |' 2 ||
    return
  dir="$(evidence_dir "${case_dir}")"
  for action in ssm:SendCommand ssm:StartSession; do
    if [[ "$(count_requests "${dir}" "\"ActionNames\":[\"${action}\"]" \
      "\"ResourceArns\":[\"${INSTANCE_ARN}\"]")" -ne 3 ]]; then
      testlib::fail "instance-allowed: ${action} 를 인스턴스 ARN 으로 단위" \
        "셋에 묻지 않음"
      return
    fi
  done
  expect_no_leak instance-allowed "${case_dir}" || return
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "*@ssm:SendCommand@${INSTANCE_ARN}@*" implicitDeny None \
      - - -)"
  run_check "${case_dir}" instance-denied O7a
  expect_result instance-denied 0 "PRINCIPAL CHECK PASS $((NORMAL_ROWS + 6))" ||
    return
  expect_no_leak instance-denied "${case_dir}" || return
  testlib::ok "34 instance → tag:ec2:instance 0건 TARGET_NONE · 세션 · 명령" \
    "허용은 멈춤 · 거부는 통과"
}

#######################################
# 사례 35 의 앞 절반: 입력 파일 없음 · 맞는 ARN · 허용.
#######################################
expect_input_row() {
  local case_dir dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "input: 시험 준비 실패"
    return
  }
  add_row "${case_dir}" rds-master O7a "input:${INPUT_NAME}" secret \
    ap-northeast-2 secretsmanager:GetSecretValue secretsmanager -
  run_check "${case_dir}" input-none O7a
  expect_result input-none 3 'PRINCIPAL CHECK UNDECIDED 1' || return
  expect_rows input-none \
    '| - | rds-master | - | ap-northeast-2 | 판정 불가 | TARGET_NONE |' 1 ||
    return
  expect_no_leak input-none "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  add_row "${case_dir}" rds-master O7a "input:${INPUT_NAME}" secret \
    ap-northeast-2 secretsmanager:GetSecretValue secretsmanager -
  write_input "${case_dir}" "${MASTER_ARN}" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-resource-policy "${MASTER_ARN}" \
      '{"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",'\
'"Action":"*","Resource":"*"}]}')" || return
  run_check "${case_dir}" input-ok O7a
  expect_result input-ok 0 "PRINCIPAL CHECK PASS $((NORMAL_ROWS + 3))" ||
    return
  dir="$(evidence_dir "${case_dir}")"
  if [[ "$(count_requests "${dir}" \
    '"ActionNames":["secretsmanager:GetSecretValue"]' \
    "\"ResourceArns\":[\"${MASTER_ARN}\"]")" -ne 3 ||
    "$(count_requests "${dir}" "{\"SecretId\":\"${MASTER_ARN}\"}")" -ne 1 ]]
  then
    testlib::fail "input-ok: 마스터 비밀 ARN 질문 셋 · 정책 읽기 하나가 아님"
    return
  fi
  if [[ "$(testlib::count "${dir}/policies.tsv" \
    "secretsmanager"$'\t'"${MASTER_ARN}"$'\t'"deny")" -ne 1 ]]; then
    testlib::fail "input-ok: 응답 ARN 이 같은 비밀 정책을 deny 로 읽지 않음"
    return
  fi
  expect_no_leak input-ok "${case_dir}" || return
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv \
      "${MARK_USER}-deploy@secretsmanager:GetSecretValue@${MASTER_ARN}@*" \
      allowed None - - -)"
  run_check "${case_dir}" input-allowed O7a
  expect_result input-allowed 1 'PRINCIPAL CHECK STOP 1' || return
  expect_rows input-allowed '| dev-deployer | rds-master |' 1 || return
  expect_no_leak input-allowed "${case_dir}" || return
  testlib::ok "35a input → 파일 없음 TARGET_NONE · 맞는 ARN 은 그 ARN 으로" \
    "질문 · 정책 읽기(응답 ARN 같음 — 정책 deny) · 허용은 멈춤"
}

#######################################
# 사례 35 의 뒤 절반: 꼴 밖 입력은 종료 2(유출 0).
#######################################
expect_input_rejected() {
  local case_dir spec content want file
  local other="arn:aws:secretsmanager:ap-northeast-2:${FAKE_ACCOUNT/7/8}"
  for spec in empty other-account wildcard other-service other-region \
    two-arns-one-bad partial other-secret short-suffix long-suffix \
    suffix-char mode link dangling; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "input-rejected: 시험 준비 실패"
      return
    }
    add_row "${case_dir}" rds-master O7a "input:${INPUT_NAME}" secret \
      ap-northeast-2 secretsmanager:GetSecretValue secretsmanager -
    want='입력 파일의 줄이 이 계정 · 행의 리전 · 기대 이름의 비밀 전체 ARN'
    case "${spec}" in
      empty) content='' want='입력 파일이 비어 있음' ;;
      other-account) content="${other}:secret:rds!db-x-AbCdEf" ;;
      wildcard) content="${MASTER_ARN%-*}-*" ;;
      other-service) content="${TOPIC_ARN}" ;;
      other-region) content="${MASTER_ARN/ap-northeast-2/us-east-1}" ;;
      two-arns-one-bad) content="${MASTER_ARN}"$'\n'"${TOPIC_ARN}" ;;
      partial) content="${MASTER_ARN%-*}" ;;
      other-secret) content="${SECRET_ARN}" ;;
      short-suffix) content="${MASTER_ARN%?}" ;;
      long-suffix) content="${MASTER_ARN}G" ;;
      suffix-char) content="${MASTER_ARN%?}_" ;;
      mode) content="${MASTER_ARN}" want='0600 파일이 아님' ;;
      *) content="${MASTER_ARN}" want='링크가 아닌 일반 파일이 아님' ;;
    esac
    write_input "${case_dir}" "${content}" || return
    file="${case_dir}/home/.pokeclip-precheck/input-${INPUT_NAME}.txt"
    if [[ "${spec}" == mode ]]; then
      chmod 644 "${file}" || return
    elif [[ "${spec}" == link ]]; then
      mv "${file}" "${file%/*}/real.txt" &&
        ln -s real.txt "${file}" || return
    elif [[ "${spec}" == dangling ]]; then
      rm "${file}" && ln -s missing.txt "${file}" || return
    fi
    run_check "${case_dir}" "input-${spec}" O7a
    expect_unverifiable "input-${spec}" || return
    testlib::expect_stderr_count "input-${spec}" "${want}" 1 || return
    expect_no_leak "input-${spec}" "${case_dir}" || return
  done
  testlib::ok "35b input-rejected → 빈 파일 · 다른 계정 · * · 다른 서비스 ·" \
    "다른 리전 · 섞인 줄 · 부분 ARN · 엉뚱한 비밀 · 접미 5자 · 7자 · 꼴 밖" \
    "글자 · 0600 아님 · 링크 · 끊긴 링크는 종료 2(그 원인 문구) · 유출 0"
}

#######################################
# 사례 36: 비밀 정책 응답의 ARN 이 요청과 다름 · 응답 꼴 계약 어긋남 · 모르는
# 입력 이름 · 비밀 아닌 input: 행은 종료 2(유출 0).
#######################################
expect_input_response() {
  local case_dir spec want
  for spec in arn-mismatch secret-query unknown-name other-service; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "input-response: 시험 준비 실패"
      return
    }
    want='해석 방법 · 동작 · 정책 읽기 꼴이 틀림'
    case "${spec}" in
      unknown-name)
        add_row "${case_dir}" rds-master O7a input:other-input secret \
          ap-northeast-2 secretsmanager:GetSecretValue secretsmanager -
        ;;
      other-service)
        add_row "${case_dir}" rds-master O7a "input:${INPUT_NAME}" topic \
          ap-northeast-2 sns:Publish sns -
        ;;
      *)
        add_row "${case_dir}" rds-master O7a "input:${INPUT_NAME}" secret \
          ap-northeast-2 secretsmanager:GetSecretValue secretsmanager -
        ;;
    esac || return
    write_input "${case_dir}" "${MASTER_ARN}" || return
    if [[ "${spec}" == arn-mismatch ]]; then
      prepend "${case_dir}/in/reads.tsv" \
        "$(testlib::tsv get-resource-policy "${MASTER_ARN}" None \
          "${MASTER_ARN}-Xy12Zw")" || return
      want='비밀 응답의 ARN 이 요청과 다름'
    elif [[ "${spec}" == secret-query ]]; then
      edit_lib "${case_dir}" principal_policy.sh \
        's/\[ARN,ResourcePolicy\]/[ARN,ResourcePolciy]/' || return
      want='비밀 응답이 None'
    fi
    run_check "${case_dir}" "input-${spec}" O7a
    expect_unverifiable "input-${spec}" || return
    testlib::expect_stderr_count "input-${spec}" "${want}" 1 || return
    expect_no_leak "input-${spec}" "${case_dir}" || return
  done
  testlib::ok "36 input-response → 비밀 응답 ARN 다름 · 응답 꼴 계약 어긋남 ·" \
    "모르는 입력 이름 · 비밀 아닌 input: 행은 종료 2 · 유출 0"
}

main() {
  testlib::init principal_check_late_test
  expect_instance_row
  expect_input_row
  expect_input_rejected
  expect_input_response
  testlib::finish principal_check_late_test
}

main "$@"
