# shellcheck shell=bash
# 부품끼리 · principal-check.sh 와 전역 변수를 나눠 쓴다(각 함수 주석의
# Globals) — 파일 하나만 검사하면 정의 · 사용 쪽이 안 보인다.
# shellcheck disable=SC2034,SC2154
#
# 2-3 판정 (5) 실제 질문의 동시 전송. principal-check.sh 가 source 한다 —
# aws_send · die · evidence_dir · req_n · call_out 은 부르는 쪽에,
# principal::send_simulate · principal::read_response ·
# principal::fail_simulate 는 lib/principal_simulate.sh 에 있다.
#
# 계약: 주 셸이 요청 파일(req-<n>.json · 빈 res-<n>.err)을 지금 순서대로 다
# 쓰고 principal::batch_add 로 번호를 올린 뒤 principal::batch_run 을
# 부른다. 고정 작업자 N 개(PRINCIPAL_CHECK_JOBS — 기본 6)가 요청을 나눠
# 보낸다 — 목록의 k 번째 요청은 작업자 k mod N 이 맡는다. 작업자는
# res-<n>.txt · res-<n>.err 만 쓴다(순차로 보낼 때와 같은 파일 · 같은 바이트).
# 판정은 부르는 쪽이 번호 순서로 응답을 다시 읽어 한다. 그래서 성공 경로의
# 결과 · 증거 · 표준 출력은 동시 수와 상관없이 같다.
#
# 실패: 작업자는 실패 때만 res-<n>.fail(호출 실패면 빈 파일, 그 밖이면 die
# 문구)과 batch-abort 를 증거 디렉터리에 남기고 멈춘다. 다른 작업자는 요청마다
# batch-abort 를 보고 멈춘다(진행 중이던 한 건은 끝까지 간다).
# principal::batch_check 가 번호 순서로 첫 res-<n>.fail 의 원인을 순차 판과
# 같은 문구로 내고 종료 2 다. 그 밖의 어긋남(작업자의 비정상 종료 · 응답
# 파일 빠짐)은 「동시 질문 작업이 실패함」 으로 종료 2 다. 성공하면 표지
# 파일이 생기지 않는다. 실패 경로의 증거는 순차 판과 다를 수 있다(멈추기
# 전에 이미 보낸 요청의 응답이 남는다).
#
# 신호: 동시 구간에 INT · TERM · HUP 을 받으면 batch-abort 를 남기고 작업자에
# TERM 을 보내 기다린 뒤 130 · 143 · 129 로 끝난다(complete.txt 없음).
# 작업자는 띄울 때만 set -m 을 켜 저마다 프로세스 묶음을 갖게 하고, TERM 은
# 묶음째 보낸다 — 작업자의 명령 치환 · aws · 대기 sleep 까지 함께 끝나 주
# 셸이 끝난 뒤 남는 프로세스가 없다. 작업자는 터미널 입출력이 없다(표준
# 입출력을 닫음). 묶음이 달라 터미널의 SIGINT · SIGHUP 은 작업자에 가지
# 않으므로 주 셸이 대신 전한다. 띄우는 동안 온 신호는 미뤄 두었다가 pid 를
# 다 적은 뒤 처리한다 — 막 띄운 작업자를 놓치지 않게.
#
# 작업자가 비정상으로 끝나면(SIGKILL 등 — 실패 표지 없이 비0 상태만 남음)
# 주 셸이 batch-abort 를 남겨 다른 작업자를 멈추고, 그 작업자의 묶음에 남은
# 자손(명령 치환 · aws · sleep)에 TERM, 유예(BATCH_REAP_TRIES × 0.1초) 뒤에도
# 남으면 KILL 을 보낸다. 모든 작업자는 기다린 뒤 묶음이 비었는지 본다. bash
# 가 신호로 죽은 작업에 내는 「Killed」 진단(lib 의 절대 경로가 든다)은
# 기다리는 구간의 표준 오류를 버려 가둔다 — 상태 코드는 그대로 받는다. 묶음을
# 세우지 못한 작업자(실측 0번)의 자손은 주 셸의 묶음에 섞여 따로 찾지 못한다
# — 그때는 작업자에게만 TERM 을 보낸다(한계).
#
# bash 3.2 에서 돈다: wait -n · $BASHPID · declare -A · mapfile 을 쓰지
# 않고, pid 마다 wait 한다.

readonly BATCH_JOBS_DEFAULT=6
readonly BATCH_JOBS_PATTERN='^([1-9]|1[0-6])$'
# 남은 자손에 TERM 을 보낸 뒤 KILL 까지 기다리는 횟수와 한 번의 대기(초).
readonly BATCH_REAP_TRIES=20
readonly BATCH_REAP_INTERVAL=0.1

# 동시 수와 보낼 요청(요청 번호 · 자원 정책 파일 — 없으면 빈 값).
batch_jobs="${BATCH_JOBS_DEFAULT}"
batch_n=()
batch_policy=()
# 띄운 작업자 pid(기다린 것은 빈 값).
batch_pids=()
# 작업자가 지금 맡은 요청 번호(작업자 안에서만 쓴다).
batch_current=""
# 작업자를 띄우는 동안 받아 미룬 신호의 종료 코드(없으면 빈 값).
batch_pending=""

#######################################
# 동시 수(PRINCIPAL_CHECK_JOBS — 없으면 기본 6)를 검사해 batch_jobs 에 둔다.
# 꼴 밖(빈 값 포함)이면 값을 되울리지 않고 멈춘다. 첫 AWS 호출 전에 부른다.
# Globals:
#   PRINCIPAL_CHECK_JOBS, batch_jobs, BATCH_JOBS_DEFAULT, BATCH_JOBS_PATTERN
#######################################
principal::batch_jobs() {
  local value="${PRINCIPAL_CHECK_JOBS-${BATCH_JOBS_DEFAULT}}"
  [[ "${value}" =~ ${BATCH_JOBS_PATTERN} ]] ||
    die "PRINCIPAL_CHECK_JOBS 꼴이 틀림(1 이상 16 이하 정수 · 앞자리 0 없음)"
  batch_jobs="${value}"
}

#######################################
# 보낼 요청 하나를 목록 끝에 더한다(요청 파일은 이미 쓴 것).
# Globals:
#   batch_n, batch_policy
# Arguments:
#   요청 번호, 자원 정책 파일(없으면 빈 값).
#######################################
principal::batch_add() {
  batch_n+=("$1")
  batch_policy+=("$2")
}

#######################################
# 실패 표지 하나를 쓴다. 쓰지 못해도 여기서 멈추지 않는다 — 작업자가 0 이
# 아닌 코드로 끝나 principal::batch_check 가 「동시 질문 작업이 실패함」 으로
# 멈춘다.
# Arguments:
#   파일, 내용.
# Returns:
#   쓰지 못하면 1.
#######################################
principal::batch_mark() {
  { printf '%s' "$2"; } 2>/dev/null >"$1"
}

#######################################
# 작업자의 실패를 남긴다 — 맡은 요청의 res-<n>.fail(내용 = 문구) 다음에
# batch-abort.
# Globals:
#   evidence_dir, batch_current
# Arguments:
#   문구(호출 실패면 빈 값).
#######################################
principal::batch_fail() {
  if [[ -n "${batch_current}" ]]; then
    principal::batch_mark "${evidence_dir}/res-${batch_current}.fail" "$1"
  fi
  principal::batch_mark "${evidence_dir}/batch-abort" ''
}

#######################################
# 작업자 하나(배경 하위 셸 전용 — 끝에 exit 한다). 맡은 요청을 차례로 보내고
# 응답 꼴을 본다. 그 안의 die 는 표지를 남기고 3 으로 끝나도록 덮는다.
# Globals:
#   batch_n, batch_policy, batch_jobs, batch_current, evidence_dir, req_n,
#   call_out
# Arguments:
#   작업자 번호(0 부터).
#######################################
principal::batch_worker() {
  local k
  # shellcheck disable=SC2329 # 이 작업자가 부르는 부품(쓰기 · 응답 읽기)이 부른다.
  die() {
    principal::batch_fail "$*"
    exit 3
  }
  for ((k = $1; k < ${#batch_n[@]}; k += batch_jobs)); do
    [[ ! -e "${evidence_dir}/batch-abort" ]] || exit 1
    batch_current="${batch_n[k]}"
    req_n="${batch_current}"
    if ! principal::send_simulate "${batch_current}" "${batch_policy[k]}"; then
      principal::batch_fail ""
      exit 1
    fi
    principal::read_response "${call_out}"
  done
  exit 0
}

#######################################
# 끝난(기다린) 작업자의 묶음에 남은 프로세스를 끝낸다 — TERM, 유예 뒤에도
# 남으면 KILL. 묶음이 비었으면 아무것도 하지 않는다. 묶음 id 는 그 묶음에
# 프로세스가 남아 있는 동안 다른 프로세스에 다시 쓰이지 않는다.
# Globals:
#   BATCH_REAP_TRIES, BATCH_REAP_INTERVAL
# Arguments:
#   작업자 pid(= 묶음 id).
#######################################
principal::batch_reap() {
  local tries=0
  kill -0 -- "-$1" 2>/dev/null || return 0
  kill -TERM -- "-$1" 2>/dev/null
  while kill -0 -- "-$1" 2>/dev/null; do
    tries=$((tries + 1))
    if [[ "${tries}" -gt "${BATCH_REAP_TRIES}" ]]; then
      kill -KILL -- "-$1" 2>/dev/null
      return 0
    fi
    sleep "${BATCH_REAP_INTERVAL}" 2>/dev/null
  done
}

#######################################
# 동시 구간에 신호를 받으면(트랩) 작업자를 멈추고 끝낸다. complete.txt 는
# 남지 않는다.
# Globals:
#   batch_pids, evidence_dir
# Arguments:
#   종료 코드(130 · 143 · 129).
#######################################
principal::batch_stop() {
  local pid
  trap '' INT TERM HUP
  principal::batch_mark "${evidence_dir}/batch-abort" ''
  for pid in ${batch_pids[@]+"${batch_pids[@]}"}; do
    # 묶음이 없으면(작업 제어가 묶음을 세우지 못함) 작업자만이라도 끝낸다.
    [[ -z "${pid}" ]] || kill -TERM -- "-${pid}" 2>/dev/null ||
      kill -TERM "${pid}" 2>/dev/null
  done
  for pid in ${batch_pids[@]+"${batch_pids[@]}"}; do
    [[ -z "${pid}" ]] || wait "${pid}" 2>/dev/null
  done
  for pid in ${batch_pids[@]+"${batch_pids[@]}"}; do
    [[ -z "${pid}" ]] || principal::batch_reap "${pid}"
  done
  exit "$1"
}

#######################################
# 작업자가 모두 끝난 뒤 결과를 본다. 번호 순서로 첫 res-<n>.fail 의 원인을
# 순차 판 문구로 내고, 그 밖의 어긋남은 「동시 질문 작업이 실패함」 으로
# 멈춘다. 모두 0 · 표지 없음 · 응답 파일 전부 있음이면 통과다.
# Globals:
#   batch_n, evidence_dir, req_n, call_err
# Arguments:
#   작업자의 종료 코드들.
#######################################
principal::batch_check() {
  local n file message status
  for n in ${batch_n[@]+"${batch_n[@]}"}; do
    file="${evidence_dir}/res-${n}.fail"
    [[ -e "${file}" ]] || continue
    message="$(cat -- "${file}" 2>/dev/null)" ||
      die "동시 질문 작업이 실패함(res-${n}.fail 를 읽지 못함)"
    [[ -z "${message}" ]] || die "${message}"
    req_n="${n}"
    call_err="${evidence_dir}/res-${n}.err"
    principal::fail_simulate
  done
  for status in "$@"; do
    [[ "${status}" -eq 0 ]] || die "동시 질문 작업이 실패함"
  done
  [[ ! -e "${evidence_dir}/batch-abort" ]] || die "동시 질문 작업이 실패함"
  for n in ${batch_n[@]+"${batch_n[@]}"}; do
    [[ -f "${evidence_dir}/res-${n}.txt" ]] ||
      die "동시 질문 작업이 실패함(res-${n}.txt 없음)"
  done
}

#######################################
# 목록의 요청을 작업자 min(동시 수, 요청 수) 개로 보내고, 모두 기다린 뒤
# principal::batch_check 로 본다. 띄우는 동안 온 신호는 미루고, 기다리는
# 동안만 INT · TERM · HUP 트랩을 건다.
# Globals:
#   batch_n, batch_jobs, batch_pids, batch_pending, evidence_dir
#######################################
principal::batch_run() {
  local count="${#batch_n[@]}" workers w i status
  local -a statuses=()
  [[ "${count}" -gt 0 ]] || return 0
  workers="${batch_jobs}"
  [[ "${count}" -ge "${workers}" ]] || workers="${count}"
  batch_pids=()
  batch_pending=""
  trap 'batch_pending=130' INT
  trap 'batch_pending=143' TERM
  trap 'batch_pending=129' HUP
  # macOS bash 3.2 의 작업 제어는 드물게(실측 1,800번에 2번) 띄운 자식이
  # 「child setpgid … Operation not permitted」 를 자기 리다이렉션 전에 물려받은
  # 표준 오류로 낸다(lib 의 절대 경로가 든다). 묶음은 부모 쪽 setpgid 로 이미
  # 서 있으므로(같은 실측 1,800/1,800) 띄우는 동안만 표준 오류를 버린다.
  {
    set -m
    for ((w = 0; w < workers; w++)); do
      principal::batch_worker "${w}" </dev/null >/dev/null 2>&1 &
      batch_pids+=("$!")
    done
    set +m
  } 2>/dev/null
  trap 'principal::batch_stop 130' INT
  trap 'principal::batch_stop 143' TERM
  trap 'principal::batch_stop 129' HUP
  [[ -z "${batch_pending}" ]] || principal::batch_stop "${batch_pending}"
  # 이 구간의 표준 오류는 bash 의 작업 알림(「Killed」 등)뿐이라 버린다 — 모든
  # 작업자를 여기서 기다리므로 구간 밖에 남는 알림이 없다.
  {
    for i in "${!batch_pids[@]}"; do
      wait "${batch_pids[i]}"
      status=$?
      statuses+=("${status}")
      if [[ "${status}" -ne 0 ]]; then
        principal::batch_mark "${evidence_dir}/batch-abort" ''
      fi
      principal::batch_reap "${batch_pids[i]}"
      batch_pids[i]=""
    done
  } 2>/dev/null
  trap - INT TERM HUP
  principal::batch_check "${statuses[@]}"
}
