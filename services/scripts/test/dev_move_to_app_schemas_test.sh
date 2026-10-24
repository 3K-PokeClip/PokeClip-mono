#!/usr/bin/env bash
#
# dev-move-to-app-schemas.sh 의 실패 복구(restore)와 이전 SQL 실행 시험.
#
# 지키는 것: 호스트의 `docker compose exec` 연결만 끊기고 컨테이너 안 psql 은
# 이전을 계속하는 갈래에서, restore 가 그 psql 이 끝나기 전에 이력 표를 읽어
# 「커밋 전」으로 오판하고 옛 컨테이너를 띄우면 안 된다(그 뒤 커밋되면 옛
# Flyway 가 public 에 빈 표를 만든다). restore 는 이전 psql 프로세스와 그
# 세션이 둘 다 사라진 뒤에만 이력 표를 읽고, 상한 안에 사라지지 않거나 읽기가
# 실패하면 띄우지 않는다.
#
# 가짜 sudo · docker · sleep 을 PATH 맨 앞에 둔다. 컨테이너는 띄우지 않는다.
# 가짜 docker 는 이전 psql 이 「대기 몇 번 동안 살아 있는지」를 환경 변수로
# 받아, 그동안의 이력 표 읽기에는 커밋 전 모습을, 그 뒤에는 최종 모습을 낸다.
#
# 제품 스크립트는 마지막 줄이 `main "$@"` 라 source 하면 main 이 돈다. 시험은
# 가짜 docker 가 public 에 이력 표가 없다고 답하게 해 main 을 「건너뜀」
# 갈래(종료 0)로 끝내고 함수만 얻는다.
#
# 사용: bash services/scripts/test/dev_move_to_app_schemas_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
SERVICES_DIR="$(cd -- "${TEST_DIR}/../.." && pwd)"
readonly SERVICES_DIR
readonly SCRIPT="${SERVICES_DIR}/scripts/dev-move-to-app-schemas.sh"
readonly HISTORY_BEFORE='flyway_schema_history_auth flyway_schema_history_clip'
readonly SIX="auth clip chat-collector chat-detector render-worker \
upload-worker"

work_dir=''
fail_count=0
case_count=0

#######################################
# 가짜 sudo · docker · sleep 을 만든다.
# Globals:
#   work_dir
#######################################
make_fakes() {
  mkdir -p "${work_dir}/bin"
  cat > "${work_dir}/bin/sudo" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == '-u' ]]; then
  shift 2
fi
exec "$@"
EOF
  cat > "${work_dir}/bin/sleep" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "${FAKE_DIR}/sleeps"
EOF
  cat > "${work_dir}/bin/docker" <<'EOF'
#!/usr/bin/env bash
# 가짜 docker — argv 를 한 줄로 펴(줄바꿈 → 공백) calls 에 적고 하위 명령
# 종류로 답한다.
set -u
d="${FAKE_DIR}"
args="$*"
args="${args//$'\n'/ }"
printf '%s\n' "${args}" >> "${d}/calls"
count() { cat "${d}/$1" 2>/dev/null || echo 0; }
bump() { echo $(( $(count "$1") + 1 )) > "${d}/$1"; count "$1"; }
case "${args}" in
  *' top postgres'*)
    [[ "${FAKE_TOP_FAIL:-0}" == 1 ]] && exit 1
    n="$(bump top)"
    echo 'UID PID PPID C STIME TTY TIME CMD'
    echo '999 1 0 0 10:00 ? 00:00:00 postgres'
    if (( n <= FAKE_PROC_POLLS )); then
      echo '0 77 70 0 10:01 ? 00:00:00 /usr/lib/postgresql/17/bin/psql' \
        '-d application_name=pokeclip-dev-move dbname=pokeclip -1 -f -'
    fi
    ;;
  *pg_stat_activity*)
    [[ "${FAKE_STAT_FAIL:-0}" == 1 ]] && exit 1
    n="$(bump stat)"
    if (( n <= FAKE_SESSION_POLLS )); then echo 1; else echo 0; fi
    ;;
  *flyway_schema_history*)
    if [[ "${FAKE_BOOT:-0}" == 1 ]]; then
      echo ''
    elif (( $(count top) > FAKE_PROC_POLLS \
      && $(count stat) > FAKE_SESSION_POLLS )); then
      echo "${FAKE_HISTORY_FINAL}"
    else
      echo "${FAKE_HISTORY_DURING}"
    fi
    ;;
  *' -f -'*)
    cat > "${d}/move_stdin"
    ;;
esac
exit 0
EOF
  chmod +x "${work_dir}/bin/sudo" "${work_dir}/bin/sleep" \
    "${work_dir}/bin/docker"
}

#######################################
# 사례 하나의 상태를 새로 깐다.
# Globals:
#   work_dir
#######################################
reset_state() {
  rm -rf "${work_dir}/state"
  mkdir -p "${work_dir}/state"
  export FAKE_DIR="${work_dir}/state"
  export FAKE_PROC_POLLS=0 FAKE_SESSION_POLLS=0 FAKE_TOP_FAIL=0 FAKE_STAT_FAIL=0
  export FAKE_HISTORY_DURING="${HISTORY_BEFORE}"
  export FAKE_HISTORY_FINAL="${HISTORY_BEFORE}"
}

#######################################
# 제품 스크립트를 source 하고 함수 하나를 부른다(서브셸 — restore 의 exit 가
# 시험을 끝내지 않게). 출력과 종료 코드를 state 에 남긴다.
# Arguments:
#   부를 함수 이름.
#######################################
call_in_script() {
  local fn="$1"
  local status=0
  (
    cd "${SERVICES_DIR}"
    PATH="${work_dir}/bin:${PATH}"
    export FAKE_BOOT=1
    # shellcheck source=/dev/null
    source "${SCRIPT}" 6gb > /dev/null
    export FAKE_BOOT=0
    : > "${FAKE_DIR}/calls"
    # shellcheck disable=SC2034 # source 한 스크립트의 restore 가 읽는다.
    history_before="${HISTORY_BEFORE}"
    "${fn}"
  ) > "${FAKE_DIR}/out" 2>&1 || status=$?
  echo "${status}" > "${FAKE_DIR}/status"
}

#######################################
# 사례를 돌리고 PASS · FAIL 줄을 낸다.
# Arguments:
#   사례 함수 이름.
#######################################
run_case() {
  local name="$1"
  case_count=$(( case_count + 1 ))
  reset_state
  if "${name}"; then
    echo "PASS ${name}"
    return 0
  fi
  fail_count=$(( fail_count + 1 ))
  echo "FAIL ${name}"
  sed 's/^/    out: /' "${FAKE_DIR}/out" 2>/dev/null || true
  sed 's/^/    call: /' "${FAKE_DIR}/calls" 2>/dev/null || true
}

started() {
  grep -q " start ${SIX}\$" "${FAKE_DIR}/calls"
}

exit_status() {
  cat "${FAKE_DIR}/status"
}

# 연결만 끊기고 안의 이전이 대기 둘 동안 더 돈 뒤 커밋 — 띄우지 않는다.
case_inner_move_commits_after_disconnect() {
  export FAKE_PROC_POLLS=2 FAKE_SESSION_POLLS=1 FAKE_HISTORY_FINAL=''
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started && return 1
  grep -q '::error::schema move committed' "${FAKE_DIR}/out"
}

# 프로세스는 끝났는데 세션(백엔드)이 커밋 중 — 세션이 사라질 때까지 기다린다.
case_backend_lingers_then_commits() {
  export FAKE_SESSION_POLLS=3 FAKE_HISTORY_FINAL=''
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started && return 1
  grep -q '::error::schema move committed' "${FAKE_DIR}/out"
}

# 연결만 끊기고 안의 이전이 끝내 롤백 — 다 끝난 뒤에 옛 여섯을 띄운다.
case_inner_move_rolls_back_then_restart() {
  export FAKE_PROC_POLLS=2 FAKE_SESSION_POLLS=1
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started || return 1
  # 띄우기는 대기가 끝난 뒤다.
  [[ "$(grep -n ' start ' "${FAKE_DIR}/calls" | cut -d: -f1)" \
    -gt "$(grep -n 'pg_stat_activity' "${FAKE_DIR}/calls" | tail -1 \
    | cut -d: -f1)" ]]
}

# 이전 psql 이 없던 보통 실패(예: pg_dump 실패) — 바로 띄운다.
case_no_move_process_restarts() {
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started || return 1
  grep -q 'failed before commit' "${FAKE_DIR}/out"
}

# 상한 안에 안 끝남 — 판정 불가 · 띄우지 않음 · 이력 표를 읽지 않음.
case_wait_bound_exceeded_is_unknown() {
  export FAKE_PROC_POLLS=100000
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started && return 1
  grep -q 'flyway_schema_history' "${FAKE_DIR}/calls" && return 1
  grep -q '::error::schema move state unknown' "${FAKE_DIR}/out" || return 1
  # 대기 횟수가 상한(스크립트 상수)과 같다 — 끝없이 돌지 않는다.
  local tries
  tries="$(grep -c ' top postgres' "${FAKE_DIR}/calls")"
  [[ "${tries}" -gt 1 && "${tries}" -lt 1000 ]]
}

# 프로세스 목록 읽기 실패 — 판정 불가 · 띄우지 않음.
case_top_failure_is_unknown() {
  export FAKE_TOP_FAIL=1
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started && return 1
  grep -q '::error::schema move state unknown' "${FAKE_DIR}/out"
}

# 세션 읽기 실패 — 판정 불가 · 띄우지 않음.
case_session_read_failure_is_unknown() {
  export FAKE_STAT_FAIL=1
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started && return 1
  grep -q '::error::schema move state unknown' "${FAKE_DIR}/out"
}

# 이전 SQL 은 표식 이름(프로세스 argv · application_name)과 문장 상한을 달고
# 이전 SQL 파일 전체를 표준 입력으로 한 트랜잭션에 넘긴다.
case_move_sql_carries_marker_and_timeout() {
  call_in_script run_move_sql
  [[ "$(exit_status)" == 0 ]] || return 1
  local call
  call="$(grep -- ' -f -' "${FAKE_DIR}/calls")" || return 1
  [[ "${call}" == *'application_name='* ]] || return 1
  [[ "${call}" == *' pokeclip-dev-move'* ]] || return 1
  [[ "${call}" == *'statement_timeout'* ]] || return 1
  [[ "${call}" == *' -1 '* ]] || return 1
  cmp -s "${FAKE_DIR}/move_stdin" \
    "${SERVICES_DIR}/scripts/dev-move-to-app-schemas.sql"
}

main() {
  work_dir="$(mktemp -d)"
  trap 'rm -rf "${work_dir}"' EXIT
  make_fakes
  run_case case_inner_move_commits_after_disconnect
  run_case case_backend_lingers_then_commits
  run_case case_inner_move_rolls_back_then_restart
  run_case case_no_move_process_restarts
  run_case case_wait_bound_exceeded_is_unknown
  run_case case_top_failure_is_unknown
  run_case case_session_read_failure_is_unknown
  run_case case_move_sql_carries_marker_and_timeout
  echo "cases: ${case_count} · fail: ${fail_count}"
  (( fail_count == 0 ))
}

main "$@"
