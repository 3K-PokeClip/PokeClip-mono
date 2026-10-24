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
# 데몬이 늦게 이전을 시작하는 갈래(0→1 후발 기동)도 지킨다: restore 가 볼 때는
# 이전 psql 프로세스도 세션도 없어 옛 컨테이너를 띄웠는데, 그 뒤 데몬이 버퍼에
# 남은 이전을 시작하면 그 이전은 restore 가 먼저 만든 울타리를 보고 아무것도
# 바꾸지 않고 실패해야 한다.
#
# 가짜 sudo · docker · sleep · psql 을 PATH 맨 앞에 둔다. 컨테이너는 띄우지
# 않는다. 가짜 docker 는 이전 psql 이 「대기 몇 번 동안 살아 있는지」를 환경
# 변수로 받아, 그동안의 이력 표 읽기에는 커밋 전 모습을, 그 뒤에는 최종 모습을
# 낸다. 울타리 스키마는 state 의 fence 파일 하나로 본다. 이전 SQL 호출만은 안쪽
# `sh -c` 를 그대로 돌려 가짜 psql 에 넘기고, 가짜 psql 은 psql 의 -1 · -c · -f
# 차례(앞에서부터 · -1 이면 한 트랜잭션)를 흉내 내 커밋되면 move_committed 를
# 남긴다.
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
# 가짜 sudo · docker · sleep · psql 을 만든다.
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
# 이전 SQL 호출 — 안쪽 sh -c 를 그대로 돌린다(접속 값은 가짜).
run_inner() {
  while (( $# > 1 )) && [[ "$1" != 'sh' || "$2" != '-c' ]]; do
    shift
  done
  shift 2
  POSTGRES_USER='owner' POSTGRES_DB='pokeclip' sh -c "$@"
}
case "${args}" in
  *' -f -'*)
    run_inner "$@"
    exit
    ;;
  *'CREATE SCHEMA IF NOT EXISTS pokeclip_move_fence'*)
    [[ "${FAKE_FENCE_FAIL:-0}" == 1 ]] && exit 1
    : > "${d}/fence"
    ;;
  *'DROP SCHEMA IF EXISTS pokeclip_move_fence'*)
    [[ "${FAKE_FENCE_FAIL:-0}" == 1 ]] && exit 1
    rm -f "${d}/fence"
    ;;
  *' stop '*)
    [[ "${FAKE_STOP_FAIL:-0}" == 1 ]] && exit 1
    ;;
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
esac
exit 0
EOF
  cat > "${work_dir}/bin/psql" <<'EOF'
#!/usr/bin/env bash
# 가짜 psql(이전 SQL 호출 전용) — argv 를 한 줄에 하나씩 move_argv 에 적고,
# -c · -f 를 앞에서부터 돈다. 울타리 이름이 든 -c 는 울타리가 있으면 실패한다.
# -1 이면 실패 때 앞서 돈 것도 되돌리고, 아니면 -f 가 돈 자리에서 커밋된다.
set -u
d="${FAKE_DIR}"
printf '%s\n' "$@" > "${d}/move_argv"
single=0
for a in "$@"; do
  [[ "${a}" == '-1' ]] && single=1
done
pending=0
while (( $# > 0 )); do
  case "$1" in
    -c)
      if [[ "$2" == *pokeclip_move_fence* && -e "${d}/fence" ]]; then
        echo 'ERROR:  schema move: fenced' >&2
        exit 3
      fi
      shift
      ;;
    -f)
      cat > "${d}/move_stdin"
      if (( single )); then
        pending=1
      else
        : > "${d}/move_committed"
      fi
      shift
      ;;
  esac
  shift
done
if (( pending )); then
  : > "${d}/move_committed"
fi
exit 0
EOF
  chmod +x "${work_dir}/bin/sudo" "${work_dir}/bin/sleep" \
    "${work_dir}/bin/docker" "${work_dir}/bin/psql"
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
  export FAKE_FENCE_FAIL=0 FAKE_STOP_FAIL=0
  export FAKE_HISTORY_DURING="${HISTORY_BEFORE}"
  export FAKE_HISTORY_FINAL="${HISTORY_BEFORE}"
}

#######################################
# 제품 스크립트를 source 하고 함수 하나를 부른다. 새 bash 프로세스에서 돈다 —
# restore 의 exit 가 시험을 끝내지 않게, 그리고 set -e 가 제품 안에서 그대로
# 살게(run_case 의 `if` 안에서 부른 서브셸은 set -e 가 꺼져 제품의 실패 갈래가
# 돌지 않는다). 출력과 종료 코드를 state 에 남긴다.
# Arguments:
#   부를 함수 이름(제품 함수 또는 export 한 시험용 흐름).
#######################################
call_in_script() {
  local fn="$1"
  local status=0
  (
    cd "${SERVICES_DIR}"
    export PATH="${work_dir}/bin:${PATH}"
    # shellcheck disable=SC2016 # 새 bash 가 풀 변수다.
    exec bash -c '
      export FAKE_BOOT=1
      source "$1" 6gb > /dev/null
      export FAKE_BOOT=0
      : > "${FAKE_DIR}/calls"
      history_before="$2"
      "$3"' call_in_script "${SCRIPT}" "${HISTORY_BEFORE}" "${fn}"
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

# call_in_script 로 부르는 시험용 흐름 — source 한 스크립트의 함수를 쓴다.
# restore 는 늘 exit 1 이라 서브셸에서 부른다. 그 뒤 데몬이 늦게 시작한 이전을
# run_move_sql 로 흉내 낸다.
late_move_after_restore() {
  ( restore ) || true
  run_move_sql
}

main_6gb() {
  main 6gb
}

# 캐시 줄이기 · 디스크 확인(상자 전용 명령)을 비우고 main 을 stop 까지 돈다.
# shellcheck disable=SC2329 # 덮어쓴 두 함수는 main 이 부른다.
main_until_stop() {
  prune_build_cache() { :; }
  require_disk_for_backup() { :; }
  main 6gb
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

# 0→1 후발 기동 — restore 가 볼 때는 이전 psql 도 세션도 없어 옛 여섯을
# 띄운다. 그 뒤 데몬이 늦게 시작한 이전은 울타리를 보고 아무것도 커밋하지
# 않고 실패한다.
case_late_move_after_restore_is_fenced() {
  call_in_script late_move_after_restore
  started || return 1
  [[ -e "${FAKE_DIR}/move_committed" ]] && return 1
  [[ "$(exit_status)" != 0 ]]
}

# 울타리는 대기(프로세스 · 세션 확인)보다 먼저 만든다 — 대기 뒤에 만들면 그
# 사이에 첫 문장을 돈 이전을 놓친다.
case_fence_created_before_wait() {
  call_in_script restore
  started || return 1
  local fence_line top_line
  fence_line="$(grep -n 'CREATE SCHEMA IF NOT EXISTS pokeclip_move_fence' \
    "${FAKE_DIR}/calls" | head -1 | cut -d: -f1)"
  top_line="$(grep -n ' top postgres' "${FAKE_DIR}/calls" | head -1 \
    | cut -d: -f1)"
  [[ -n "${fence_line}" && -n "${top_line}" ]] || return 1
  (( fence_line < top_line ))
}

# 울타리 만들기 실패 — 판정 불가 · 띄우지 않음.
case_fence_failure_is_unknown() {
  export FAKE_FENCE_FAIL=1
  call_in_script restore
  [[ "$(exit_status)" == 1 ]] || return 1
  started && return 1
  grep -q '::error::schema move state unknown' "${FAKE_DIR}/out"
}

# 이전 SQL 호출은 울타리 검사(-c)를 이전 SQL(-f -)보다 앞에, 같은 -1
# 트랜잭션에 싣는다.
case_move_sql_checks_fence_first_in_one_transaction() {
  call_in_script run_move_sql
  [[ "$(exit_status)" == 0 ]] || return 1
  local argv="${FAKE_DIR}/move_argv"
  grep -qx -- '-1' "${argv}" || return 1
  local check_line f_line
  check_line="$(grep -n 'pokeclip_move_fence' "${argv}" | head -1 \
    | cut -d: -f1)"
  f_line="$(grep -nx -- '-f' "${argv}" | head -1 | cut -d: -f1)"
  [[ -n "${check_line}" && -n "${f_line}" ]] || return 1
  (( check_line < f_line )) || return 1
  [[ "$(sed -n "$(( check_line - 1 ))p" "${argv}")" == '-c' ]] || return 1
  grep -q 'RAISE EXCEPTION' "${argv}"
}

# 앞방향 실행은 서비스를 세우기 전에 남은 울타리를 지운다.
case_main_drops_fence_before_stop() {
  : > "${FAKE_DIR}/fence"
  export FAKE_STOP_FAIL=1
  call_in_script main_until_stop
  local drop_line stop_line
  drop_line="$(grep -n 'DROP SCHEMA IF EXISTS pokeclip_move_fence' \
    "${FAKE_DIR}/calls" | head -1 | cut -d: -f1)"
  stop_line="$(grep -n ' stop ' "${FAKE_DIR}/calls" | head -1 | cut -d: -f1)"
  [[ -n "${drop_line}" && -n "${stop_line}" ]] || return 1
  (( drop_line < stop_line ))
}

# 울타리 지우기 실패 — 아무것도 멈추지 않고 실패로 끝난다.
case_main_fence_drop_failure_stops_nothing() {
  export FAKE_FENCE_FAIL=1
  call_in_script main_6gb
  [[ "$(exit_status)" != 0 ]] || return 1
  grep -q 'DROP SCHEMA IF EXISTS pokeclip_move_fence' "${FAKE_DIR}/calls" \
    || return 1
  grep -q ' stop ' "${FAKE_DIR}/calls" && return 1
  grep -q 'builder prune' "${FAKE_DIR}/calls" && return 1
  grep -q '::error::' "${FAKE_DIR}/out"
}

main() {
  work_dir="$(mktemp -d)"
  trap 'rm -rf "${work_dir}"' EXIT
  make_fakes
  export -f late_move_after_restore main_6gb main_until_stop
  run_case case_inner_move_commits_after_disconnect
  run_case case_backend_lingers_then_commits
  run_case case_inner_move_rolls_back_then_restart
  run_case case_no_move_process_restarts
  run_case case_wait_bound_exceeded_is_unknown
  run_case case_top_failure_is_unknown
  run_case case_session_read_failure_is_unknown
  run_case case_move_sql_carries_marker_and_timeout
  run_case case_late_move_after_restore_is_fenced
  run_case case_fence_created_before_wait
  run_case case_fence_failure_is_unknown
  run_case case_move_sql_checks_fence_first_in_one_transaction
  run_case case_main_drops_fence_before_stop
  run_case case_main_fence_drop_failure_stops_nothing
  echo "cases: ${case_count} · fail: ${fail_count}"
  (( fail_count == 0 ))
}

main "$@"
