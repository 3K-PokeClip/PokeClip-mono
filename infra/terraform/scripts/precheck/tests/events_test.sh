#!/usr/bin/env bash
#
# events.sh 를 시험한다 — 계획 M17a c1 사례 4(조회 리전 · 이벤트 ID 한 번 ·
# 출처 구분 · 90일 경고) · 5(유출) · 6(조회 실패).
#
# 사본 트리의 data/event_names.txt 에 고정 입력을 넣고, 가짜 aws 가 돌려줄
# 이벤트 표(FAKE_AWS_EVENTS)를 사례마다 정한다. 고정 입력의 행마다 조회
# 리전이 argv 의 --region 으로 그대로 가는지 본다. 실제 event_names.txt 의
# 리전 규칙(IAM · CloudFront = us-east-1 · STS = 둘 다 · 그 밖 = 서울)과
# SNS · SQS 정책 이름은 data_files_test.sh 가 본다.
#
# 사용: bash infra/terraform/scripts/precheck/tests/events_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly OLD_SINCE='2000-01-01T00:00:00Z'

#######################################
# 사본 트리 · 고정 이벤트 이름 · 가짜 이벤트 표를 만든다.
#   AssumeRole 은 us-east-1 · 서울 두 행이고, 같은 ID(ev-1)가 두 리전에서
#   온다. AddPermission 은 SNS 행만 있고, 가짜 표는 Lambda 출처(ev-3)도
#   돌려준다. AttachUserPolicy 는 us-east-1 행만 있어 서울의 ev-6 은 조회
#   밖이다. 기대 건수 = ev-1 · ev-2 · ev-4 · ev-5 = 4.
# Arguments:
#   사례 디렉터리.
# Returns:
#   만들지 못하면 1.
#######################################
setup_tree() {
  local case_dir="$1"
  testlib::make_tree "${case_dir}/tree" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'iam.amazonaws.com\tAttachUserPolicy\tus-east-1' \
    $'cloudfront.amazonaws.com\tUpdateDistribution\tus-east-1' \
    $'sts.amazonaws.com\tAssumeRole\tus-east-1' \
    $'sts.amazonaws.com\tAssumeRole\tap-northeast-2' \
    '' \
    $'sns.amazonaws.com\tAddPermission\tap-northeast-2' \
    $'sqs.amazonaws.com\tSetQueueAttributes\tap-northeast-2' \
    $'s3.amazonaws.com\tPutBucketPolicy\tap-northeast-2' \
    >"${case_dir}/tree/data/event_names.txt" || return 1
  {
    event_row us-east-1 AssumeRole ev-1 sts
    event_row ap-northeast-2 AssumeRole ev-1 sts
    event_row ap-northeast-2 AssumeRole ev-2 sts
    event_row ap-northeast-2 AddPermission ev-3 lambda
    event_row ap-northeast-2 AddPermission ev-4 sns
    event_row us-east-1 AttachUserPolicy ev-5 iam
    event_row ap-northeast-2 AttachUserPolicy ev-6 iam
  } >"${case_dir}/in/events.tsv"
}

#######################################
# 가짜 이벤트 표의 행 하나(시각은 고정 · 주체는 가짜 사용자 이름).
# Arguments:
#   리전, 이벤트 이름, 이벤트 ID, 서비스(출처 앞부분).
# Outputs:
#   리전 · 이름 · ID · 출처 · 시각 · 주체(탭 구분).
#######################################
event_row() {
  testlib::tsv "$1" "$2" "$3" "$4.amazonaws.com" '2026-10-13T03:00:00Z' \
    "${FAKE_USER}"
}

#######################################
# 새 사례 디렉터리와 사본 트리를 만든다. 명령 치환 안에서 불리므로 실패는
# 세지 않고 종료 코드로만 알린다(부르는 쪽이 센다).
# Outputs:
#   사례 디렉터리.
# Returns:
#   준비 실패면 1.
#######################################
new_tree_case() {
  local case_dir
  case_dir="$(testlib::new_case)" || return 1
  setup_tree "${case_dir}" || return 1
  printf '%s\n' "${case_dir}"
}

#######################################
# 사본 트리의 events.sh 를 돌린다. 「이름=값」 꼴 인자는 환경으로 넘긴다.
# Arguments:
#   사례 디렉터리, 결과 이름, [이름=값 …] 스크립트 인자.
#######################################
run_events() {
  local case_dir="$1" name="$2"
  local -a envs=("FAKE_AWS_EVENTS=${case_dir}/in/events.tsv")
  shift 2
  while [[ "$#" -gt 0 && "$1" == FAKE_AWS_*=* ]]; do
    envs+=("$1")
    shift
  done
  testlib::run "${case_dir}" "${name}" env "${envs[@]}" \
    bash "${case_dir}/tree/events.sh" "$@"
}

#######################################
# 지금 시각(UTC)을 --since 꼴로 낸다 — 90일 경고가 나오지 않아야 하는 값.
# Outputs:
#   YYYY-MM-DDTHH:MM:SSZ.
#######################################
now_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

#######################################
# 가짜 aws argv 기록에서 한 이벤트 이름의 조회 리전들을 정렬해 낸다.
# Arguments:
#   argv 기록 파일, 이벤트 이름.
# Outputs:
#   리전(공백 구분, 정렬).
#######################################
regions_for() {
  local log="$1" name="$2" line regions=""
  while IFS= read -r line; do
    [[ "${line}" =~ AttributeValue=${name}( |$) ]] || continue
    [[ "${line}" =~ --region\ ([a-z0-9-]+) ]] || continue
    regions+="${BASH_REMATCH[1]}"$'\n'
  done <"${log}"
  printf '%s' "${regions}" | LC_ALL=C sort | tr '\n' ' '
}

#######################################
# 사례 4: 행마다 조회 리전 · 같은 ID 한 번 · 다른 출처 제외 · stdout 한 줄.
#######################################
expect_counts_and_regions() {
  local case_dir log name want got
  case_dir="$(new_tree_case)" || {
    testlib::fail "count: 시험 준비 실패"
    return
  }
  run_events "${case_dir}" count --since "$(now_utc)"
  if [[ "${run_status}" -ne 0 ||
    "$(cat -- "${run_out}.stdout")" != 'EVENTS 4' ]]; then
    testlib::fail "count: 종료 0 · 「EVENTS 4」 한 줄을 기대했으나" \
      "${run_status} · $(cat -- "${run_out}.stdout" "${run_out}.stderr")"
    return
  fi
  log="${case_dir}/out/aws.log"
  for want in \
    'AttachUserPolicy us-east-1 ' \
    'UpdateDistribution us-east-1 ' \
    'AssumeRole ap-northeast-2 us-east-1 ' \
    'AddPermission ap-northeast-2 ' \
    'SetQueueAttributes ap-northeast-2 ' \
    'PutBucketPolicy ap-northeast-2 '; do
    name="${want%% *}"
    got="${name} $(regions_for "${log}" "${name}")"
    if [[ "${got}" != "${want}" ]]; then
      testlib::fail "count: ${name} 조회 리전이 「${want}」 가 아니라 「${got}」"
      return
    fi
  done
  if [[ "$(testlib::count "${run_out}.stderr" '90일')" -ne 0 ]]; then
    testlib::fail "count: 최근 --since 인데 90일 경고가 나옴"
    return
  fi
  testlib::ok "count → EVENTS 4(두 리전 같은 ID 한 번 · 다른 출처 제외) ·" \
    "행마다 조회 리전"
}

#######################################
# 사례 4: --since 가 90일보다 이르면 표준 오류로 한계를 경고한다(조회는 함).
#######################################
expect_ninety_day_warning() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "ninety: 시험 준비 실패"
    return
  }
  run_events "${case_dir}" ninety --since "${OLD_SINCE}"
  if [[ "${run_status}" -ne 0 ||
    "$(cat -- "${run_out}.stdout")" != 'EVENTS 4' ]]; then
    testlib::fail "ninety: 종료 0 · EVENTS 4 를 기대했으나 ${run_status}"
    return
  fi
  if [[ "$(testlib::count "${run_out}.stderr" '90일')" -lt 1 ]]; then
    testlib::fail "ninety: 90일 경고가 없음 — $(cat -- "${run_out}.stderr")"
    return
  fi
  testlib::ok "ninety → 90일 경고 · 조회는 그대로"
}

#######################################
# 사례 5: 이벤트 원본(주체 이름)은 증거 디렉터리에만 있고, 표준 출력 ·
# 표준 오류 · argv 에는 가짜 계정 ID · 사용자 이름이 0건.
#######################################
expect_no_leak() {
  local case_dir leaked raw
  case_dir="$(new_tree_case)" || {
    testlib::fail "leak: 시험 준비 실패"
    return
  }
  run_events "${case_dir}" leak --since "$(now_utc)"
  leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
  raw="$(grep -rlF -- "${FAKE_USER}" "${case_dir}/home/.pokeclip-precheck")"
  if [[ -n "${leaked}" || -z "${raw}" ]]; then
    testlib::fail "leak: 증거 루트 밖 유출(${leaked}) 또는 증거 안 원본 없음"
    return
  fi
  testlib::ok "leak → 원본은 증거 디렉터리에만 · 출력 · argv 유출 0"
}

#######################################
# 사례 6: 조회 실패 · 호출 자체의 AccessDenied · 잘못된 --since 는 종료 2 ·
# EVENTS 줄 없음 · 유출 0.
#######################################
expect_unverifiable_cases() {
  local case_dir spec name leaked
  case_dir="$(new_tree_case)" || {
    testlib::fail "unverifiable: 시험 준비 실패"
    return
  }
  for spec in fail denied-call; do
    run_events "${case_dir}" "lookup-${spec}" FAKE_AWS_LOOKUP="${spec}" \
      --since "$(now_utc)"
    if [[ "${run_status}" -ne 2 ]] ||
      grep -q '^EVENTS ' -- "${run_out}.stdout"; then
      testlib::fail "lookup-${spec}: 종료 2 · EVENTS 없음을 기대했으나" \
        "${run_status}"
      return
    fi
  done
  for spec in 'bad-format 2026-10-13' 'no-value' 'no-option'; do
    name="${spec%% *}"
    case "${name}" in
      bad-format) run_events "${case_dir}" "${name}" --since "${spec#* }" ;;
      no-value) run_events "${case_dir}" "${name}" --since ;;
      *) run_events "${case_dir}" "${name}" ;;
    esac
    if [[ "${run_status}" -ne 2 ]]; then
      testlib::fail "${name}: 종료 2 를 기대했으나 ${run_status}"
      return
    fi
  done
  leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
  if [[ -n "${leaked}" ]]; then
    testlib::fail "unverifiable: 증거 루트 밖 유출 — ${leaked}"
    return
  fi
  testlib::ok "unverifiable → 조회 실패 · AccessDenied · 잘못된 --since 모두" \
    "종료 2 · 유출 0"
}

main() {
  testlib::init events_test
  expect_counts_and_regions
  expect_ninety_day_warning
  expect_no_leak
  expect_unverifiable_cases
  testlib::finish events_test
}

main "$@"
