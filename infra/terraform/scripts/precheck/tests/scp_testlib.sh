# shellcheck shell=bash
# 상수 · 도우미 일부는 이 파일이 아니라 시험 파일(scp_recheck_*_test.sh)이
# 쓴다 — 이 파일만 검사하면 사용 쪽이 안 보인다.
# shellcheck disable=SC2034
#
# scp-recheck.sh 시험(scp_recheck_*_test.sh)이 함께 쓰는 부품 — 상수 · 사본
# 트리 · 실행 · 공용 단언(사례 5 유출 포함) · 판정 뒤 승인.
#
# 사례마다 점검 스크립트의 사본 트리를 만들고 data/ 에 고정 입력(시험 묶음 ·
# baseline.tsv)을 넣는다. 실제 묶음 데이터는 data_files_test.sh 가 본다.
# 가짜 aws 는 요청 파일이 계약(호출 주체 ARN · 계정 자리를 채운 자원 ARN ·
# 리전 문맥)과 다르면 입력 오류로 답하므로, 판정이 나온 사례는 요청 계약도
# 지킨 것이다. 모든 사례가 끝에 유출(사례 5)을 본다.
#
# 이 파일이 testlib.sh 를 source 한다 — 시험 파일은 이 파일만 source 한다
# (testlib.sh 를 두 번 읽으면 readonly 가 겹쳐 실패한다).

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly REJECTED='EVIDENCE PATH REJECTED'
# 13-1 덧붙임 줄: | `동작` | 리전 | 허용·거부 | YYYY-MM-DD HH:MM KST | 묶음 … |
# shellcheck disable=SC2016 # 역따옴표는 마크다운 코드 표지 글자 그대로다.
ROW_PATTERN='^\| `[^`]+` \| [a-z0-9-]+ \| (허용|거부) \| '
ROW_PATTERN+='[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2} KST \| '
readonly ROW_PATTERN
# 13-1 판정 시각(고정 입력 baseline.tsv 의 다섯째 칸).
readonly JUDGED='2026-10-13T10:59+09:00'
# 판정 · 승인 한 번의 제한 시간(초). 정상 실행은 1초 안에 끝난다 — 넘기면
# 걸린 것으로 보고 끝낸다(FIFO 로 바꾼 증거를 읽다 걸리는 회귀).
readonly RUN_LIMIT=10
# 꼴은 맞지만 어느 결과의 해시도 아닌 sha256(승인 기록 변조용).
OTHER_SHA256="$(printf '%064d' 0)"
readonly OTHER_SHA256
# UTC 가 아닌 시각(완료 표지 · 승인 기록 변조용).
readonly NOT_UTC='2026-10-14T12:15:00+09:00'

#######################################
# 사본 트리와 고정 입력을 만든다.
#   alpha: 셋 다 baseline 허용. 빈 줄 · 주석 줄 · 계정 자리를 담는다.
#   beta: baseline 에 rds-db:connect 거부가 있다.
#   gamma: tag:GetTagKeys 가 baseline 에 없다.
#   alpha-stop: 이름이 alpha 로 시작한다(앞 기준을 섞어 찾지 않는지).
#   broken · dup · collapsed: 꼴이 틀린 묶음(열 수 · 동작 · 리전 중복 ·
#   빈 칸이 탭 IFS 로 접혀 세 칸처럼 읽히는 행).
# Globals:
#   JUDGED
# Arguments:
#   사례 디렉터리.
# Returns:
#   만들지 못하면 1.
#######################################
setup_tree() {
  local data="$1/tree/data/stage_actions"
  testlib::make_tree "$1/tree" || return 1
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 계정 자리다.
  {
    printf '# 출처: 시험 고정 입력\n'
    testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 '*'
    testlib::tsv rds-db:connect ap-northeast-2 \
      'arn:aws:rds-db:ap-northeast-2:${ACCOUNT}:dbuser:*/media_indexer'
    printf '\n'
    testlib::tsv cloudfront-keyvaluestore:PutKey us-east-1 '*'
  } >"${data}/alpha.tsv" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'iam:GetAccountAuthorizationDetails\tus-east-1\t*' \
    $'rds-db:connect\tap-northeast-2\t*' >"${data}/beta.tsv" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'iam:GetAccountAuthorizationDetails\tus-east-1\t*' \
    $'tag:GetTagKeys\tap-northeast-2\t*' >"${data}/gamma.tsv" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'ssm:SendCommand\tap-northeast-2\t*' >"${data}/alpha-stop.tsv" || return 1
  printf '%s\n' $'iam:GetAccountAuthorizationDetails\tus-east-1' \
    >"${data}/broken.tsv" || return 1
  printf '%s\n' $'tag:GetTagKeys\tap-northeast-2\t*' \
    $'tag:GetTagKeys\tap-northeast-2\tarn:aws:tag:::x' >"${data}/dup.tsv" ||
    return 1
  testlib::tsv tag:GetTagKeys '' ap-northeast-2 '*' >"${data}/collapsed.tsv" ||
    return 1
  {
    testlib::tsv '# 묶음' 동작 리전 판정 '13-1 판정 시각'
    testlib::tsv alpha iam:GetAccountAuthorizationDetails us-east-1 allow \
      "${JUDGED}"
    testlib::tsv alpha rds-db:connect ap-northeast-2 allow "${JUDGED}"
    testlib::tsv alpha cloudfront-keyvaluestore:PutKey us-east-1 allow \
      "${JUDGED}"
    testlib::tsv beta iam:GetAccountAuthorizationDetails us-east-1 allow \
      "${JUDGED}"
    testlib::tsv beta rds-db:connect ap-northeast-2 deny \
      '2026-10-12T13:38+09:00'
    testlib::tsv gamma iam:GetAccountAuthorizationDetails us-east-1 allow \
      "${JUDGED}"
  } >"${data}/baseline.tsv"
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
# 사본 트리의 scp-recheck.sh 를 제한 시간(RUN_LIMIT) 안에서 돌린다. 「이름=값」
# 꼴 인자는 환경으로 넘긴다(FAKE_AWS_* · AWS_CLI_HISTORY_FILE).
# Globals:
#   RUN_LIMIT
# Arguments:
#   사례 디렉터리, 결과 이름, [이름=값 …] 스크립트 인자.
#######################################
run_recheck() {
  local case_dir="$1" name="$2"
  local -a envs=()
  shift 2
  while [[ "$#" -gt 0 && ( "$1" == FAKE_AWS_*=* ||
    "$1" == AWS_CLI_HISTORY_FILE=* ) ]]; do
    envs+=("$1")
    shift
  done
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
    env ${envs[@]+"${envs[@]}"} bash "${case_dir}/tree/scp-recheck.sh" "$@"
}

#######################################
# 마지막 실행의 종료 코드 · 첫 줄 · 13-1 줄 수를 본다.
# Globals:
#   run_out, ROW_PATTERN, run_status
# Arguments:
#   사례 이름, 기대 종료 코드, 기대 첫 줄, 기대 13-1 줄 수.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_result() {
  local name="$1" status="$2" first="$3" rows="$4"
  local got_first got_rows bad_rows
  got_first="$(head -n 1 -- "${run_out}.stdout")"
  got_rows="$(grep -cE -- "${ROW_PATTERN}" "${run_out}.stdout")"
  bad_rows="$(tail -n +2 -- "${run_out}.stdout" |
    grep -cvE -- "${ROW_PATTERN}")"
  if [[ "${run_status}" -ne "${status}" || "${got_first}" != "${first}" ]]; then
    testlib::fail "${name}: 종료 ${status} · 「${first}」 를 기대했으나" \
      "${run_status} · 「${got_first}」 — $(cat -- "${run_out}.stderr")"
    return 1
  fi
  if [[ "${got_rows}" -ne "${rows}" || "${bad_rows}" -ne 0 ]]; then
    testlib::fail "${name}: 13-1 줄 ${rows}개를 기대했으나 ${got_rows}개" \
      "(꼴 밖 ${bad_rows}줄) — $(cat -- "${run_out}.stdout")"
    return 1
  fi
}

#######################################
# 마지막 실행이 검사 불가(종료 2 · 첫 줄 없음)였는지 본다.
# Globals:
#   run_status, run_out
# Arguments:
#   사례 이름.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_unverifiable() {
  local name="$1"
  if [[ "${run_status}" -ne 2 ]]; then
    testlib::fail "${name}: 종료 2 를 기대했으나 ${run_status} —" \
      "$(cat -- "${run_out}.stdout" "${run_out}.stderr")"
    return 1
  fi
  if grep -q '^SCP RECHECK ' -- "${run_out}.stdout"; then
    testlib::fail "${name}: 검사 불가인데 판정 줄이 나옴"
    return 1
  fi
}

#######################################
# 사례 5: 표준 출력 · 표준 오류 · 가짜 aws argv · 증거 루트 밖 파일에 가짜
# 계정 ID · 사용자 이름이 0건인지 본다.
# Arguments:
#   사례 이름, 사례 디렉터리.
# Returns:
#   0건이면 0(아니면 실패를 센다).
#######################################
expect_no_leak() {
  local name="$1" case_dir="$2" leaked
  leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
  if [[ -n "${leaked}" ]]; then
    testlib::fail "${name}: 증거 루트 밖에 계정 ID · 사용자 이름 — ${leaked}"
    return 1
  fi
}

#######################################
# 마지막 판정이 표준 오류로 알린 증거 디렉터리 이름(kty 가 보고받는 이름).
# Globals:
#   run_out
# Outputs:
#   디렉터리 이름.
# Returns:
#   알림 줄이 없으면 1.
#######################################
judged_name() {
  local line
  line="$(grep -m 1 '^scp-recheck: 증거 ' -- "${run_out}.stderr")" || return 1
  line="${line#*/.pokeclip-precheck/}"
  printf '%s\n' "${line%% *}"
}

#######################################
# 파일의 sha256(시험이 승인 기록을 고쳐 쓸 때).
# Arguments:
#   파일.
# Outputs:
#   64자리 16진수.
#######################################
sha256_of() {
  local out
  out="$(sha256sum -- "$1" 2>/dev/null || shasum -a 256 -- "$1")" || return 1
  printf '%s\n' "${out%% *}"
}

#######################################
# 판정 하나를 돌리고 그 증거 디렉터리를 승인한다. 마지막 실행 결과는
# run_recheck 이 run_out · run_status 에 남긴다.
# Globals:
#   run_status
# Arguments:
#   사례 디렉터리, 묶음.
# Outputs:
#   승인한 증거 디렉터리 이름.
# Returns:
#   판정 · 승인이 안 되면 1.
#######################################
judge_and_accept() {
  local case_dir="$1" bundle="$2" name
  run_recheck "${case_dir}" "judge-${bundle}" "${bundle}"
  name="$(judged_name)" || return 1
  run_recheck "${case_dir}" "accept-${bundle}" --accept "${bundle}" \
    --dir "${name}" --reason 'kty 답: 진행'
  [[ "${run_status}" -eq 0 ]] || return 1
  printf '%s\n' "${name}"
}
