#!/usr/bin/env bash
#
# principal-check.sh 를 시험한다 — (5) 실제 질문의 동시 전송과 Throttling
# 다시 시도(계획 「2-3 판정 시뮬레이터 질문 동시 전송」 시험 P1–P11).
#
# 동시 수는 환경 변수 PRINCIPAL_CHECK_JOBS(기본 6 · 1 이상 16 이하)다.
# Throttling 계열 오류만 첫 시도 포함 6번까지, 사이에 1 · 2 · 4 · 8 · 16초
# 쉬고 다시 묻는다. 쉬는 것은 사례마다 PATH 맨 앞에 둔 가짜 sleep 이 받아
# 적기만 한다(실제로 쉬지 않음) — 시험은 그 기록으로 대기 순서를 본다. 가짜
# aws 의 응답 지연(FAKE_AWS_SIM_DELAY)은 /bin/sleep 으로 재서 이 기록에 섞이지
# 않는다.
#
# 고정 입력 · 사본 트리 · 공용 단언(사례 22 유출 — 모든 사례가 끝에
# 본다)은 principal_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/principal_check_parallel_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/principal_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/principal_testlib.sh"

# 동시 수 꼴 밖일 때의 표준 오류 한 줄(값을 되울리지 않는다).
JOBS_REJECTED='principal-check: 검사 불가: PRINCIPAL_CHECK_JOBS 꼴이 틀림'
JOBS_REJECTED+='(1 이상 16 이하 정수 · 앞자리 0 없음)'
readonly JOBS_REJECTED
readonly THROTTLE_CAP='Throttling 재시도 상한을 넘음'
# 다시 시도 상한까지의 대기(초) — 가짜 sleep 기록과 맞춘다.
readonly CAP_SLEEPS='1 2 4 8 16'
# PC0(순차 구간의 첫 시뮬레이터 질문)의 가짜 aws 키.
readonly PC0_KEY="${FAKE_USER}@s3:ListBucket@${PC_BUCKET}@ap-northeast-1@-"
# 동시 구간의 질문 하나(dev-box 단위 · 상태 버킷 정책 쓰기 — 두 번째 질문).
readonly JUDGE_KEY="${MARK_ROLE}-box@s3:PutBucketPolicy@${STATE_BUCKET}@*"
# 순차 구간(PC0 · PC1 · PC2 · PC-A)의 시뮬레이터 질문 수(고정 입력 O7a).
readonly SEQUENTIAL_SIMS=8

#######################################
# new_tree_case 에 더해 가짜 sleep(in/sleepbin)과 동시 수 계측 디렉터리
# (in/flight)를 둔다.
# Outputs:
#   사례 디렉터리.
# Returns:
#   준비 실패면 1.
#######################################
new_jobs_case() {
  local case_dir bin
  case_dir="$(new_tree_case)" || return 1
  bin="${case_dir}/in/sleepbin"
  mkdir -p -- "${bin}" "${case_dir}/in/flight" || return 1
  # shellcheck disable=SC2016 # 가짜 명령의 본문 글자 그대로다.
  printf '%s\n' '#!/bin/bash' \
    'printf '"'"'%s\n'"'"' "$*" >>"${FAKE_SLEEP_LOG:?}"' >"${bin}/sleep" ||
    return 1
  chmod 700 "${bin}/sleep" || return 1
  printf '%s\n' "${case_dir}"
}

#######################################
# run_check 와 같되 가짜 sleep 을 PATH 맨 앞에 두고, 마지막 인자 앞의
# 「이름=값」 을 모두 환경으로 넘긴다(PRINCIPAL_CHECK_JOBS 포함). sleep
# 기록은 out/<결과 이름>.sleep 이다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH
# Arguments:
#   사례 디렉터리, 결과 이름, [이름=값 …] 단계.
#######################################
run_jobs() {
  local case_dir="$1" name="$2"
  local -a envs=("PATH=${case_dir}/in/sleepbin:${FAKEBIN}:${PATH}"
    "FAKE_AWS_READS=${case_dir}/in/reads.tsv"
    "FAKE_AWS_SIM_TABLE=${case_dir}/in/sim.tsv"
    "FAKE_SLEEP_LOG=${case_dir}/out/${name}.sleep")
  shift 2
  while [[ "$#" -gt 1 ]]; do
    envs+=("$1")
    shift
  done
  # shellcheck disable=SC2016 # 안쪽 bash 가 펼친다.
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
    env "${envs[@]}" bash -c 'cd -- "$1" && shift && exec bash "$@"' _ \
    "${case_dir}/cwd" "${case_dir}/tree/principal-check.sh" "$1"
}

#######################################
# 결과 이름의 가짜 sleep 기록을 한 줄로 낸다(공백 구분 · 없으면 빈 줄).
# Arguments:
#   사례 디렉터리, 결과 이름.
# Outputs:
#   대기 초들.
#######################################
sleeps_of() {
  local file="$1/out/$2.sleep"
  if [[ ! -f "${file}" ]]; then
    printf '\n'
    return
  fi
  tr '\n' ' ' <"${file}" | sed -e 's/ $//'
  printf '\n'
}

#######################################
# 마지막 실행이 complete.txt 없이 끝났는지 본다.
# Arguments:
#   사례 이름, 사례 디렉터리.
# Returns:
#   없으면 0(아니면 실패를 센다).
#######################################
expect_no_complete() {
  local dir
  dir="$(evidence_dir "$2")"
  if [[ -n "${dir}" && -e "${dir}/complete.txt" ]]; then
    testlib::fail "$1: 실패한 실행인데 complete.txt 가 있음"
    return 1
  fi
}

#######################################
# 두 증거 디렉터리의 파일 집합과 파일별 바이트가 같은지 본다(complete.txt 는
# 시각 칸을 빼고 해시 칸만).
# Arguments:
#   증거 디렉터리 A, 증거 디렉터리 B.
# Outputs:
#   다른 점(같으면 빈 출력).
#######################################
evidence_diff() {
  local a="$1" b="$2" file name
  if [[ -z "${a}" || -z "${b}" ]]; then
    printf '증거 디렉터리가 없음\n'
    return
  fi
  if [[ "$(cd -- "${a}" && ls)" != "$(cd -- "${b}" && ls)" ]]; then
    printf '파일 집합이 다름\n'
    return
  fi
  for file in "${a}"/*; do
    name="${file##*/}"
    if [[ "${name}" == complete.txt ]]; then
      [[ "$(cut -f 2 -- "${file}")" == "$(cut -f 2 -- "${b}/${name}")" ]] ||
        printf '%s 해시 칸이 다름\n' "${name}"
      continue
    fi
    cmp -s -- "${file}" "${b}/${name}" || printf '%s 가 다름\n' "${name}"
  done
}

#######################################
# 사례의 가짜 aws 기록을 사례 경로 · 증거 디렉터리 이름을 지우고 정렬해 낸다.
# Arguments:
#   사례 디렉터리.
# Outputs:
#   정규화한 기록.
#######################################
normalized_log() {
  sed -e "s|$1|CASE|g" -e 's|principal-O7a-[0-9TZ-]*|EVIDENCE|g' \
    -- "$1/out/aws.log" | LC_ALL=C sort
}

#######################################
# 증거 디렉터리의 요청 파일 수를 낸다.
# Arguments:
#   증거 디렉터리.
# Outputs:
#   개수.
#######################################
request_count() {
  local -a files=("$1"/req-*.json)
  if [[ ! -e "${files[0]}" ]]; then
    printf '0\n'
    return
  fi
  printf '%s\n' "${#files[@]}"
}

#######################################
# P1: 동시 수가 꼴 밖(0 · 17 · 06 · abc · -1 · 6x · 빈 값)이면 종료 2 · 판정
# 줄 없음 · aws 호출 0 · 표준 오류는 값을 되울리지 않는 한 줄.
#######################################
expect_jobs_rejected() {
  local case_dir value name got
  case_dir="$(new_jobs_case)" || {
    testlib::fail "P1: 시험 준비 실패"
    return
  }
  for value in 0 17 06 abc -1 6x ''; do
    name="jobs-${value:-empty}"
    run_jobs "${case_dir}" "${name}" "PRINCIPAL_CHECK_JOBS=${value}" O7a
    expect_unverifiable "${name}" || return
    got="$(cat -- "${run_out}.stderr")"
    if [[ "${got}" != "${JOBS_REJECTED}" ]]; then
      testlib::fail "${name}: 표준 오류가 「${JOBS_REJECTED}」 한 줄이 아님 —" \
        "${got}"
      return
    fi
    if [[ "$(testlib::count "${case_dir}/out/aws.log" ' ')" -ne 0 ]]; then
      testlib::fail "${name}: aws 가 불림"
      return
    fi
  done
  expect_no_leak jobs-rejected "${case_dir}" || return
  testlib::ok "P1 jobs → 0 · 17 · 06 · abc · -1 · 6x · 빈 값은 종료 2 · aws 0 ·" \
    "값을 되울리지 않음"
}

#######################################
# 장면 하나를 사례에 차린다.
# Arguments:
#   사례 디렉터리, pass · mixed · branch-a.
#######################################
set_scene() {
  case "$2" in
    mixed)
      prepend "$1/in/sim.tsv" \
        "$(testlib::tsv "${MARK_ROLE}-box@ecr:PutImage@*" allowed None - - -)" \
        "$(testlib::tsv "${MARK_USER}-deploy@secretsmanager:GetSecretValue@*" \
          implicitDeny None aws:SourceVpc - -)"
      ;;
    branch-a) set_bucket_policy "$1" "$(allow_policy)" ;;
    *) ;;
  esac
}

#######################################
# P2 의 한 장면: 동시 수 1 · 5 · 16 의 실행이 표준 출력 · 종료 코드 · 증거
# 파일 집합 · 파일별 바이트 · 정규화한 aws 기록에서 같은지 본다.
# Arguments:
#   장면(pass · mixed · branch-a).
# Returns:
#   같으면 0(아니면 실패를 센다).
#######################################
expect_scene_same() {
  local scene="$1" jobs case_dir base_dir base_status base_log diff
  for jobs in 1 5 16; do
    case_dir="$(new_jobs_case)" || {
      testlib::fail "P2 ${scene}: 시험 준비 실패"
      return 1
    }
    set_scene "${case_dir}" "${scene}" || return 1
    run_jobs "${case_dir}" run "PRINCIPAL_CHECK_JOBS=${jobs}" O7a
    expect_no_leak "P2 ${scene}" "${case_dir}" || return 1
    if [[ "${jobs}" -eq 1 ]]; then
      base_dir="${case_dir}"
      base_status="${run_status}"
      base_log="$(normalized_log "${case_dir}")"
      continue
    fi
    if [[ "${run_status}" -ne "${base_status}" ]] ||
      ! cmp -s -- "${base_dir}/out/run.stdout" "${run_out}.stdout"; then
      testlib::fail "P2 ${scene} jobs ${jobs}: 종료 코드 · 표준 출력이 다름 —" \
        "${base_status} · ${run_status}"
      return 1
    fi
    diff="$(evidence_diff "$(evidence_dir "${base_dir}")" \
      "$(evidence_dir "${case_dir}")")"
    if [[ -n "${diff}" ]]; then
      testlib::fail "P2 ${scene} jobs ${jobs}: 증거가 다름 — ${diff}"
      return 1
    fi
    if [[ "$(normalized_log "${case_dir}")" != "${base_log}" ]]; then
      testlib::fail "P2 ${scene} jobs ${jobs}: aws 기록이 다름"
      return 1
    fi
  done
}

#######################################
# P2: 장면 셋(PASS · 멈춤과 판정 불가 섞임 · 갈래 A + Allow 버킷 정책) ×
# 동시 수 1 · 5 · 16 이 같은 결과 · 증거를 낸다.
#######################################
expect_same_results() {
  local scene
  for scene in pass mixed branch-a; do
    expect_scene_same "${scene}" || return
  done
  testlib::ok "P2 same → 장면 셋 × 동시 수 1 · 5 · 16 의 출력 · 종료 코드 ·" \
    "증거 바이트 · aws 기록이 같음"
}

#######################################
# 사례의 동시 수 계측 최고값을 낸다.
# Arguments:
#   사례 디렉터리.
# Outputs:
#   최고값(기록이 없으면 0).
#######################################
peak_flight() {
  local file="$1/in/flight/counts"
  if [[ ! -f "${file}" ]]; then
    printf '0\n'
    return
  fi
  LC_ALL=C sort -n -- "${file}" | tail -n 1
}

#######################################
# P3: 응답 지연 0.1초에서 동시 수 6 은 최고 동시 2–6, 동시 수 1 은 1.
#######################################
expect_concurrency() {
  local case_dir jobs peak
  for jobs in 6 1; do
    case_dir="$(new_jobs_case)" || {
      testlib::fail "P3: 시험 준비 실패"
      return
    }
    run_jobs "${case_dir}" flight "PRINCIPAL_CHECK_JOBS=${jobs}" \
      FAKE_AWS_SIM_DELAY=0.1 "FAKE_AWS_INFLIGHT_DIR=${case_dir}/in/flight" O7a
    expect_result "P3 jobs ${jobs}" 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" ||
      return
    peak="$(peak_flight "${case_dir}")"
    if { [[ "${jobs}" -eq 6 ]] && ((peak < 2 || peak > 6)); } ||
      { [[ "${jobs}" -eq 1 && "${peak}" -ne 1 ]]; }; then
      testlib::fail "P3 jobs ${jobs}: 최고 동시 ${peak}"
      return
    fi
    expect_no_leak "P3 jobs ${jobs}" "${case_dir}" || return
  done
  testlib::ok "P3 concurrency → 동시 수 6 은 최고 동시 2–6 · 동시 수 1 은 1"
}

#######################################
# P4 의 한 코드: 모든 요청을 2번 Throttling 으로 실패시켜도 PASS · 증거가
# 기준과 바이트 같음 · 대기는 요청마다 1 · 2.
# Arguments:
#   기준 사례 디렉터리, 오류 코드.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_throttled_pass() {
  local base="$1" code="$2" case_dir name="throttle-$2" requests diff
  local ones twos lines
  case_dir="$(new_jobs_case)" || {
    testlib::fail "P4 ${code}: 시험 준비 실패"
    return 1
  }
  run_jobs "${case_dir}" "${name}" 'FAKE_AWS_THROTTLE=*=2' \
    "FAKE_AWS_THROTTLE_CODE=${code}" O7a
  expect_result "${name}" 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return 1
  diff="$(evidence_diff "$(evidence_dir "${base}")" \
    "$(evidence_dir "${case_dir}")")"
  if [[ -n "${diff}" ]]; then
    testlib::fail "${name}: 증거가 기준과 다름 — ${diff}"
    return 1
  fi
  requests="$(request_count "$(evidence_dir "${case_dir}")")"
  ones="$(grep -cx 1 -- "${run_out}.sleep" 2>/dev/null)"
  twos="$(grep -cx 2 -- "${run_out}.sleep" 2>/dev/null)"
  lines="$(testlib::count "${run_out}.sleep" '')"
  if [[ "${requests}" -lt 1 || "${ones:-0}" -ne "${requests}" ||
    "${twos:-0}" -ne "${requests}" || "${lines}" -ne $((2 * requests)) ]]; then
    testlib::fail "${name}: 대기가 요청 ${requests}개 × 1 · 2 가 아님 —" \
      "1 ${ones} · 2 ${twos} · 줄 ${lines}"
    return 1
  fi
  expect_no_leak "${name}" "${case_dir}"
}

#######################################
# P4: Throttling 계열 — 세 코드는 모든 요청 2번 실패 뒤 PASS(증거 바이트
# 같음 · 대기 1 · 2), 나머지 다섯 코드는 PC0 한 번 실패 뒤 PASS(대기 1).
#######################################
expect_throttle_retried() {
  local base code case_dir name
  base="$(new_jobs_case)" || {
    testlib::fail "P4: 시험 준비 실패"
    return
  }
  run_jobs "${base}" base O7a
  expect_result "P4 base" 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return
  for code in Throttling ThrottlingException TooManyRequestsException; do
    expect_throttled_pass "${base}" "${code}" || return
  done
  for code in ThrottledException RequestThrottledException \
    RequestLimitExceeded RequestThrottled SlowDown; do
    name="throttle-${code}"
    case_dir="$(new_jobs_case)" || {
      testlib::fail "P4 ${code}: 시험 준비 실패"
      return
    }
    run_jobs "${case_dir}" "${name}" "FAKE_AWS_THROTTLE=${PC0_KEY}=1" \
      "FAKE_AWS_THROTTLE_CODE=${code}" O7a
    expect_result "${name}" 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return
    if [[ "$(sleeps_of "${case_dir}" "${name}")" != 1 ]]; then
      testlib::fail "${name}: 대기가 1 한 번이 아님 —" \
        "$(sleeps_of "${case_dir}" "${name}")"
      return
    fi
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "P4 throttle → Throttling 계열 여덟 코드는 다시 물어 PASS ·" \
    "증거 바이트 같음 · 대기 1 · 2"
}

#######################################
# 상한 초과 사례 하나를 돌리고 공통 단언을 한다 — 종료 2 · 판정 줄 없음 ·
# 상한 문구 1줄 · complete.txt 없음 · 대기 1 2 4 8 16.
# Arguments:
#   사례 이름, FAKE_AWS_THROTTLE 값.
# Outputs:
#   사례 디렉터리(맞을 때만).
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
run_capped() {
  local name="$1" case_dir
  case_dir="$(new_jobs_case)" || return 1
  run_jobs "${case_dir}" "${name}" "FAKE_AWS_THROTTLE=$2" O7a
  expect_unverifiable "${name}" || return 1
  testlib::expect_stderr_count "${name}" "${THROTTLE_CAP}" 1 || return 1
  expect_no_complete "${name}" "${case_dir}" || return 1
  if [[ "$(sleeps_of "${case_dir}" "${name}")" != "${CAP_SLEEPS}" ]]; then
    testlib::fail "${name}: 대기가 「${CAP_SLEEPS}」 가 아님 —" \
      "$(sleeps_of "${case_dir}" "${name}")"
    return 1
  fi
  expect_no_leak "${name}" "${case_dir}" || return 1
  printf '%s\n' "${case_dir}"
}

#######################################
# P4b · P5 · P6: 읽기 호출 · 순차 구간(PC0) · 동시 구간의 한 질문이 상한을
# 넘으면 종료 2 · 상한 문구 · 6번 물음 · 대기 1 2 4 8 16 · complete.txt 없음.
#######################################
expect_throttle_capped() {
  local case_dir calls
  case_dir="$(run_capped capped-read "get-user@${MARK_USER}-deploy=6")" || {
    testlib::fail "P4b: 상한 사례 실패(위 줄)"
    return
  }
  calls="$(testlib::count "${case_dir}/out/aws.log" 'iam get-user ')"
  if [[ "${calls}" -ne 6 ]]; then
    testlib::fail "P4b capped-read: get-user 를 ${calls}번 물음(6 기대)"
    return
  fi
  case_dir="$(run_capped capped-pc0 "${PC0_KEY}=6")" || {
    testlib::fail "P5: 상한 사례 실패(위 줄)"
    return
  }
  calls="$(testlib::count "${case_dir}/out/aws.log" \
    'simulate-principal-policy')"
  if [[ "${calls}" -ne 6 ]]; then
    testlib::fail "P5 capped-pc0: 시뮬레이터를 ${calls}번 물음(6 기대)"
    return
  fi
  run_capped capped-judge "${JUDGE_KEY}=6" >/dev/null || {
    testlib::fail "P6: 상한 사례 실패(위 줄)"
    return
  }
  testlib::ok "P4b · P5 · P6 capped → 읽기 · 순차 구간 · 동시 구간의 상한 초과는" \
    "종료 2 · 6번 · 대기 1 2 4 8 16 · complete.txt 없음"
}

#######################################
# P7: 한도 초과(LimitExceeded · LimitExceededException)는 Throttling 이
# 아니라 다시 묻지 않고 종료 2.
#######################################
expect_limit_not_retried() {
  local case_dir code name
  for code in LimitExceeded LimitExceededException; do
    name="limit-${code}"
    case_dir="$(new_jobs_case)" || {
      testlib::fail "P7: 시험 준비 실패"
      return
    }
    run_jobs "${case_dir}" "${name}" "FAKE_AWS_THROTTLE=${JUDGE_KEY}=1" \
      "FAKE_AWS_THROTTLE_CODE=${code}" O7a
    expect_unverifiable "${name}" || return
    testlib::expect_stderr_count "${name}" '시뮬레이터 호출 실패' 1 || return
    if [[ -n "$(sleeps_of "${case_dir}" "${name}")" ]]; then
      testlib::fail "${name}: 한도 초과인데 기다려 다시 물음"
      return
    fi
    expect_no_complete "${name}" "${case_dir}" || return
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "P7 limit → LimitExceeded · LimitExceededException 은 다시 묻지" \
    "않고 종료 2"
}

#######################################
# 동시 구간 질문(JUDGE_KEY)의 요청 번호를 증거에서 찾는다.
# Arguments:
#   증거 디렉터리.
# Outputs:
#   번호(없으면 빈 출력).
#######################################
judge_request() {
  local file
  for file in "$1"/req-*.json; do
    [[ -f "${file}" ]] || continue
    if grep -qF -- "${MARK_ROLE}-box" "${file}" &&
      grep -qF -- '"s3:PutBucketPolicy"' "${file}"; then
      file="${file##*/req-}"
      printf '%s\n' "${file%.json}"
      return
    fi
  done
}

#######################################
# P8: 동시 구간 한 질문이 호출 자체의 AccessDenied · 연결 실패면 종료 2 · 그
# 질문은 1번 · 대기 0 · 실패 뒤 aws 호출은 2 × 동시 수 이하.
#######################################
expect_fail_fast() {
  local case_dir answer name want n after log calls
  for answer in AccessDenied fail; do
    name="fast-${answer}"
    want='호출 자체가 거부됨(AccessDenied'
    [[ "${answer}" == fail ]] && want='시뮬레이터 호출 실패'
    case_dir="$(new_jobs_case)" || {
      testlib::fail "P8: 시험 준비 실패"
      return
    }
    prepend "${case_dir}/in/sim.tsv" \
      "$(testlib::tsv "${JUDGE_KEY}" "!${answer}")"
    run_jobs "${case_dir}" "${name}" PRINCIPAL_CHECK_JOBS=6 \
      FAKE_AWS_SIM_DELAY=0.2 O7a
    expect_unverifiable "${name}" || return
    testlib::expect_stderr_count "${name}" "${want}" 1 || return
    n="$(judge_request "$(evidence_dir "${case_dir}")")"
    log="${case_dir}/out/aws.log"
    after="$(sed -e "1,\|/req-${n}\.json |d" -- "${log}" | wc -l |
      tr -d ' ')"
    calls="$(testlib::count "${log}" "/req-${n}.json ")"
    if [[ -z "${n}" || "${calls}" -ne 1 || "${after}" -gt 12 ||
      -n "$(sleeps_of "${case_dir}" "${name}")" ]]; then
      testlib::fail "${name}: 실패 질문 ${n:-없음} 을 한 번만 묻고 대기 없이" \
        "멈추지 않음(실패 뒤 호출 ${after})"
      return
    fi
    expect_no_complete "${name}" "${case_dir}" || return
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "P8 fail-fast → 동시 구간의 AccessDenied · 연결 실패는 종료 2 ·" \
    "1번 · 대기 0 · 실패 뒤 호출 12 이하"
}

#######################################
# P9: 마지막 요청의 응답 파일 쓰기가 막히면 종료 2 · 「증거를 쓰지 못함」 ·
# complete.txt 없음.
#######################################
expect_last_write_failure() {
  local case_dir last name=write-last
  case_dir="$(new_jobs_case)" || {
    testlib::fail "P9: 시험 준비 실패"
    return
  }
  run_jobs "${case_dir}" base O7a
  last="$(request_count "$(evidence_dir "${case_dir}")")"
  case_dir="$(new_jobs_case)" || {
    testlib::fail "P9: 시험 준비 실패"
    return
  }
  run_jobs "${case_dir}" "${name}" "FAKE_AWS_BLOCK_FILE=res-${last}.txt" O7a
  expect_unverifiable "${name}" || return
  testlib::expect_stderr_count "${name}" \
    "증거를 쓰지 못함(res-${last}.txt)" 1 || return
  expect_no_complete "${name}" "${case_dir}" || return
  expect_no_leak "${name}" "${case_dir}" || return
  testlib::ok "P9 write-last → 마지막 응답 파일 쓰기 실패는 종료 2 ·" \
    "complete.txt 없음"
}

#######################################
# P10: 동시 구간 응답 꼴 이상 → 종료 2 · complete.txt 없음.
#######################################
expect_shape_failure() {
  local case_dir name=shape
  case_dir="$(new_jobs_case)" || {
    testlib::fail "P10: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${JUDGE_KEY}" maybe None - - -)"
  run_jobs "${case_dir}" "${name}" PRINCIPAL_CHECK_JOBS=6 O7a
  expect_unverifiable "${name}" || return
  testlib::expect_stderr_count "${name}" '시뮬레이터 판정을 모름' 1 || return
  expect_no_complete "${name}" "${case_dir}" || return
  expect_no_leak "${name}" "${case_dir}" || return
  testlib::ok "P10 shape → 동시 구간 응답 꼴 이상은 종료 2 · complete.txt 없음"
}

#######################################
# argv 에 문자열이 든 프로세스가 모두 끝날 때까지 0.05초씩 기다린다.
# Arguments:
#   문자열(사례 경로 등), 최대 횟수.
# Returns:
#   남은 것이 없으면 0.
#######################################
wait_no_process() {
  local tries=0
  while pgrep -f -- "$1" >/dev/null; do
    tries=$((tries + 1))
    [[ "${tries}" -lt "$2" ]] || return 1
    /bin/sleep 0.05
  done
}

#######################################
# P11 의 실행을 배경에서 시작하고, 동시 구간에 들어서면(시뮬레이터 질문이
# 순차 구간 수 + 3 에 이르면) 주 프로세스에 신호를 보낸다.
# Arguments:
#   사례 디렉터리, 신호 이름(TERM · INT).
# Returns:
#   보내지 못하면 1.
#######################################
signal_in_batch() {
  local case_dir="$1" runner tries=0 pid
  # 작업 제어 없이 띄운 배경 작업은 SIGINT 를 무시한 채 시작하고, 무시된 채
  # 시작한 bash 는 그 트랩을 걸 수 없다. 터미널에서 돌릴 때처럼 INT 가
  # 닿도록 set -m 으로 띄운다.
  set -m
  (
    run_jobs "${case_dir}" term PRINCIPAL_CHECK_JOBS=6 FAKE_AWS_SIM_DELAY=1 \
      O7a
    printf '%s\n' "${run_status}" >"${case_dir}/in/term.status"
  ) </dev/null >/dev/null 2>&1 &
  runner=$!
  set +m
  while [[ "$(testlib::count "${case_dir}/out/aws.log" \
    'simulate-principal-policy')" -lt $((SEQUENTIAL_SIMS + 3)) ]]; do
    tries=$((tries + 1))
    [[ "${tries}" -lt 500 ]] || break
    /bin/sleep 0.05
  done
  # 가장 오래된 것이 주 프로세스다(작업자는 그 사본이라 argv 가 같다).
  pid="$(pgrep -o -f -- "${case_dir}/tree/principal-check.sh")"
  [[ -n "${pid}" ]] && kill "-$2" "${pid}"
  wait "${runner}"
  [[ -n "${pid}" ]]
}

#######################################
# P11 의 신호 하나: 종료 코드 · 그 뒤 aws 호출 증가 0 · 남은 프로세스 0 ·
# complete.txt 없음.
# Arguments:
#   신호 이름, 기대 종료 코드.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_signal_stops() {
  local name="P11 $1" case_dir status before after
  case_dir="$(new_jobs_case)" || {
    testlib::fail "${name}: 시험 준비 실패"
    return 1
  }
  if ! signal_in_batch "${case_dir}" "$1"; then
    testlib::fail "${name}: 주 프로세스를 찾지 못함"
    return 1
  fi
  # 주 프로세스가 끝난 바로 그때 센다. 그 뒤 1.5초(응답 지연 1초보다 김)
  # 동안 새 호출이 없어야 한다 — 진행 중이던 호출은 들어설 때 이미 적혔다.
  # 작업자 · 그 명령 치환 · aws 는 0.5초 안에 없어야 한다(응답 지연보다
  # 짧다 — 진행 중이던 호출을 기다리지 않고 묶음째 끝냈는지 본다).
  before="$(testlib::count "${case_dir}/out/aws.log" ' ')"
  status="$(cat -- "${case_dir}/in/term.status")"
  if ! wait_no_process "${case_dir}" 10; then
    testlib::fail "${name}: 주 프로세스가 끝났는데 작업자 · aws 가 남음"
    return 1
  fi
  /bin/sleep 1.5
  after="$(testlib::count "${case_dir}/out/aws.log" ' ')"
  if [[ "${status}" != "$2" || "${before}" -ne "${after}" ]]; then
    testlib::fail "${name}: 종료 ${status}($2 기대) · 멈춘 뒤 호출" \
      "${before} → ${after}"
    return 1
  fi
  expect_no_complete "${name}" "${case_dir}" || return 1
  expect_no_leak "${name}" "${case_dir}"
}

#######################################
# P11: 동시 구간 중 TERM · INT · HUP → 종료 143 · 130 · 129 · 그 뒤 aws 호출
# 증가 0 · 남은 프로세스 0 · complete.txt 없음.
#######################################
expect_term_stops() {
  expect_signal_stops TERM 143 || return
  expect_signal_stops INT 130 || return
  expect_signal_stops HUP 129 || return
  testlib::ok "P11 signal → 동시 구간 중 TERM · INT · HUP 은 종료 143 · 130 ·" \
    "129 · 그 뒤 호출 0 · 남은 프로세스 0"
}

#######################################
# P11b 의 실행을 배경에서 시작하고, 느린 질문(JUDGE_KEY — 8초)이 날아가는
# 중이면(순차 구간을 지났고 진행 표시가 있음) 주 프로세스의 직속 자식(작업자)
# 을 모두 SIGKILL 로 죽인다 — 작업자는 실패 표지를 남기지 못하고 비0 상태만
# 남긴다.
# Arguments:
#   사례 디렉터리.
# Returns:
#   죽이지 못하면 1.
#######################################
kill_workers_in_batch() {
  local case_dir="$1" runner tries=0 pid
  local -a flights
  set -m
  (
    run_jobs "${case_dir}" killed PRINCIPAL_CHECK_JOBS=6 \
      "FAKE_AWS_SLOW=${JUDGE_KEY}=8" \
      "FAKE_AWS_INFLIGHT_DIR=${case_dir}/in/flight" O7a
    printf '%s\n' "${run_status}" >"${case_dir}/in/killed.status"
  ) </dev/null >/dev/null 2>&1 &
  runner=$!
  set +m
  while :; do
    flights=("${case_dir}"/in/flight/inflight.*)
    if [[ -e "${flights[0]}" && "$(testlib::count "${case_dir}/out/aws.log" \
      'simulate-principal-policy')" -gt "${SEQUENTIAL_SIMS}" ]]; then
      break
    fi
    tries=$((tries + 1))
    [[ "${tries}" -lt 500 ]] || break
    /bin/sleep 0.05
  done
  pid="$(pgrep -o -f -- "${case_dir}/tree/principal-check.sh")"
  [[ -n "${pid}" ]] && pkill -KILL -P "${pid}"
  wait "${runner}"
  [[ -n "${pid}" ]]
}

#######################################
# P11b: 동시 구간 중 작업자가 SIGKILL 로 죽으면 종료 2 · 「동시 질문 작업이
# 실패함」 · complete.txt 없음 · 주 프로세스가 끝난 뒤 자손(aws 포함) 0 ·
# 표준 오류에 절대 경로 없음(bash 의 「Killed」 진단이 새지 않음).
#######################################
expect_worker_killed() {
  local case_dir
  case_dir="$(new_jobs_case)" || {
    testlib::fail "P11b: 시험 준비 실패"
    return
  }
  if ! kill_workers_in_batch "${case_dir}"; then
    testlib::fail "P11b: 주 프로세스를 찾지 못함"
    return
  fi
  # 느린 질문(8초)이 아직 날고 있을 때 주 프로세스가 끝났으므로, 0.5초 안에
  # 남은 것이 없어야 작업자 묶음을 정리한 것이다.
  if ! wait_no_process "${case_dir}" 10; then
    testlib::fail "P11b: 주 프로세스가 끝났는데 죽은 작업자의 자손이 남음"
    return
  fi
  run_out="${case_dir}/out/killed"
  run_status="$(cat -- "${case_dir}/in/killed.status")"
  expect_unverifiable P11b || return
  testlib::expect_stderr_count P11b '동시 질문 작업이 실패함' 1 || return
  expect_no_complete P11b "${case_dir}" || return
  expect_no_leak P11b "${case_dir}" || return
  testlib::ok "P11b worker-killed → 작업자 SIGKILL 은 종료 2 · complete.txt" \
    "없음 · 자손 0 · 표준 오류에 경로 없음"
}

main() {
  testlib::init principal_check_parallel_test
  expect_jobs_rejected
  expect_same_results
  expect_concurrency
  expect_throttle_retried
  expect_throttle_capped
  expect_limit_not_retried
  expect_fail_fast
  expect_last_write_failure
  expect_shape_failure
  expect_term_stops
  expect_worker_killed
  testlib::finish principal_check_parallel_test
}

main "$@"
