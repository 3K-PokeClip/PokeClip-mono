#!/usr/bin/env bash
#
# dev 공유 DB 의 앱 표를 public 에서 서버마다 자기 스키마로 옮기는 배포 단계다
# (POK-428 · 설계 K11 가). services-deploy.yml 이 SSM 으로 상자에서 부른다 —
# 서버 넷 · 일꾼 둘을 굽고 나서, 새 이미지로 띄우기 전에 한 번.
#
# 실행: services 디렉터리에서 root 로 `bash scripts/dev-move-to-app-schemas.sh
# <빌드 캐시 상한>`(워크플로의 BUILD_CACHE_MAX). docker 는 ec2-user 로 부른다
# (워크플로의 다른 줄과 같다).
#
# 갈래(출력은 SSM 24,000자 상한 안에 들게 요약 줄만):
#   건너뜀  public 에 서버 이력 표가 하나도 없다(이미 옮겼거나 뜬 적이 없다).
#           `schema move: skipped` · 종료 0.
#   디스크  남은 공간이 DB 크기의 두 배 + 1GB 보다 적다. 아무것도 멈추지 않고
#           종료 1(옛 컨테이너는 그대로 돈다).
#   옮김    서버 넷 · 일꾼 둘 정지 → public 백업(0600) → 이전 SQL(울타리
#           검사와 한 트랜잭션).
#           `schema move: moved` · 종료 0. 뒤이은 워크플로의 up -d 가 새 이미지로
#           다시 만든다.
#   실패    정지 뒤의 어느 실패든 restore 가 먼저 DB 에 이 실행의 울타리(스키마
#           pokeclip_move_fence_<실행 식별자>)를 세워 그 뒤에 시작하는 이 실행의
#           이전을 막고, 이전 psql 이 끝나기를 기다린 뒤(호스트의 exec 연결만
#           끊겨도 안의 psql 은 계속 돌아 나중에 커밋할 수 있고, 데몬은 연결이
#           끊긴 뒤에 그 psql 을 늦게 시작할 수도 있다) 이전이 커밋됐는지 읽는다.
#           울타리를 못 세우면 판정 불가다.
#           커밋 전(이력 표가 public 에 그대로) → 여섯을 다시 start · 종료 1.
#           커밋 뒤 · 판정 불가(상한 안에 안 끝남 포함) → 옛 컨테이너를 띄우지
#           않는다(옛 Flyway 가 public 에 빈 표를 만들어 자료가 갈린다) ·
#           ::error:: 한 줄 · 종료 1.
#           울타리는 실행마다 이름이 달라 다음 배포가 건드리지 않는다 — 실패
#           하나마다 빈 스키마 하나가 dev DB 에 남는다(이전 · 역이전 SQL 이 보지
#           않아 해가 없다). 손으로도 지우지 않는다 — 도커에 아직 시작되지 않은
#           이 실행의 이전 요청은 프로세스 · 세션 확인에 보이지 않아, 밀린 이전이
#           없음을 증명할 수단이 없다(README).
#   표지    상태 불명 표지(BACKUP_DIR/dev-move-unknown)는 진행 중 잠금이다 —
#           결과가 확실하지 않으면 남는다. 옮길 것이 있으면 main 이 캐시
#           줄이기 · 정지보다 먼저 상자 디스크에 쓰고(실행 식별자 · 시각 ·
#           in-progress 한 줄), 못 쓰면 아무것도 멈추지 않고 종료 1. 결과가
#           확실할 때만 지운다 — 옮김, 커밋 전(옛 컨테이너 start 뒤), 커밋 뒤,
#           정지 전에 끝남. 판정 불가 · 도중에 끊긴 실행은 남긴다. 울타리를 못
#           세웠을 수 있어, 다음 배포가 자기 울타리만 세우고 옛 컨테이너를
#           띄우면 이 실행의 밀린 이전이 그 뒤에 커밋할 수 있어서다. 표지가
#           있으면 다음 배포의 main 은 맨 앞에서 아무것도 멈추지 않고 ::error:: ·
#           종료 1. 확인한 뒤 손으로 지운다(README). 지우기 실패는 ::error:: ·
#           종료 1 이고 다음 배포가 괜히 멈출 뿐이다.
#
# 되돌리기 순서 · 백업 정리는 services/README.md 「dev 앱별 스키마 이전과
# 되돌리기」. 이전 단계를 지우는 후속 PR 에서 이 파일도 지운다.

set -euo pipefail

readonly COMPOSE_FILE='docker-compose.dev.yml'
readonly MOVE_SQL='scripts/dev-move-to-app-schemas.sql'
readonly BACKUP_DIR='/home/ec2-user/pokeclip-backup'
# 상태 불명 표지(진행 중 잠금). DB 가 죽어도 남게 상자 디스크(재부팅 · /tmp
# 정리에 지워지지 않는 곳)에 둔다.
readonly UNKNOWN_MARKER="${BACKUP_DIR}/dev-move-unknown"
readonly PRUNE_LOG='/tmp/pokeclip-prune.log'
# 이전하는 동안 세우는 것 — 서버 넷과, 서버에 보고하는 일꾼 둘(멈춘 서버에 보고하다
# 재시도 끝에 DLQ 로 가지 않게).
readonly SERVICES=(auth clip chat-collector chat-detector render-worker
  upload-worker)
# 서버 넷의 Flyway 이력 표. public 에 있으면 그 서버가 아직 옮기지 않은 것이다.
readonly HISTORY_TABLES="'flyway_schema_history_auth', \
'flyway_schema_history_clip', 'flyway_schema_history_chat', \
'flyway_schema_history_chat_detector'"
readonly ONE_GB=1073741824
# 이전 psql 의 표식. 그 프로세스의 argv 와 세션의 application_name 에 실려,
# restore 가 이전이 정말 끝났는지 이 이름으로 본다.
readonly MOVE_APP_NAME='pokeclip-dev-move'
# 이전 SQL 의 문장 상한. 잠금 대기도 이 안에 든다 — 그래서 lock_timeout 을 따로
# 두지 않는다. 옮기기는 카탈로그만 바꿔 평소 1초 안쪽이다.
readonly MOVE_STATEMENT_TIMEOUT='60s'
# restore 가 이전 psql 이 끝나기를 기다리는 상한(2초 × 45 = 90초). 문장 상한보다
# 길어 도는 이전은 이 안에 커밋하거나 되돌린다.
readonly MOVE_WAIT_TRIES=45
readonly MOVE_WAIT_SECONDS=2
# 이 실행의 식별자(UTC 시각 + PID). 울타리 이름에 붙어 실행마다 다른 울타리를
# 만든다 — 다음 배포가 앞 울타리를 지우면 앞 배포의 밀린 이전이 다시 열린다.
# 소문자 · 숫자 · 밑줄만이라 따옴표 없는 식별자로 그대로 쓴다.
MOVE_RUN_ID="$(date -u '+%Y%m%d%H%M%S')_$$"
readonly MOVE_RUN_ID
# restore 가 세우는 이 실행의 울타리. 이 스키마가 있으면 이 실행의 이전 SQL 이
# 시작하지 않는다. public 이 아니어야 이전 SQL 의 끝 검사(public 에 남은 객체)에
# 걸리지 않는다. 길이는 많아야 42바이트다(PID 7자리 · 식별자 한도 63).
readonly MOVE_FENCE_SCHEMA="pokeclip_move_fence_${MOVE_RUN_ID}"
readonly MOVE_FENCE_CHECK="DO \$fence\$ BEGIN \
IF pg_catalog.to_regnamespace('${MOVE_FENCE_SCHEMA}') IS NOT NULL THEN \
RAISE EXCEPTION 'schema move: fence ${MOVE_FENCE_SCHEMA} exists'; \
END IF; END \$fence\$"

# 걸음 1 에서 public 에 있다고 읽은 이력 표(restore 가 커밋 여부를 가를 때 쓴다).
history_before=''

#######################################
# dev compose 를 ec2-user 로 부른다.
# Arguments:
#   docker compose 의 하위 명령과 인자.
#######################################
compose() {
  sudo -u ec2-user docker compose -f "${COMPOSE_FILE}" "$@"
}

#######################################
# postgres 컨테이너 안에서 소유자 계정으로 psql 을 돈다. 접속 값은 컨테이너의
# 환경 변수에서 읽으므로 명령 줄에 실리지 않는다.
# Arguments:
#   psql 인자.
#######################################
psql_in_db() {
  # shellcheck disable=SC2016 # 컨테이너 안의 셸이 풀 변수다.
  compose exec -T postgres sh -c \
    'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' \
    psql "$@"
}

#######################################
# 이전 SQL 을 한 트랜잭션으로 돈다. 그 트랜잭션의 첫 문장은 이 실행의 울타리
# 검사다(다른 실행의 울타리는 보지 않는다) — psql 은 -1 일 때 여러 -c · -f 를
# 차례대로 BEGIN · COMMIT 하나로 감싸고, ON_ERROR_STOP 이면 실패 때 ROLLBACK
# 한다. 표식(MOVE_APP_NAME)을 psql argv 의 application_name 에 싣고, 문장 상한을
# PGOPTIONS 로 준다.
#######################################
run_move_sql() {
  # shellcheck disable=SC2016 # 컨테이너 안의 셸이 풀 변수다.
  compose exec -T postgres sh -c \
    'PGOPTIONS="-c statement_timeout=$2" psql -X -q -v ON_ERROR_STOP=1 \
-U "$POSTGRES_USER" -d "application_name=$1 dbname=$POSTGRES_DB" -1 \
-c "$3" -f -' \
    sh "${MOVE_APP_NAME}" "${MOVE_STATEMENT_TIMEOUT}" "${MOVE_FENCE_CHECK}" \
    < "${MOVE_SQL}"
}

#######################################
# 이전 psql 이 끝나기를 상한 있게 기다린다. postgres 컨테이너에 그 프로세스가
# 없고(접속 전인 것까지) DB 에 그 세션도 없으면(프로세스가 죽은 뒤 커밋 · 롤백
# 중인 백엔드까지) 끝난 것이다. 이 차례로 보면 끝난 뒤의 이력 표만 읽게 된다.
# 데몬이 이 확인 뒤에 늦게 시작하는 이전은 restore 가 먼저 세운 울타리가 막는다.
# Returns:
#   0 끝남, 1 상한을 넘김 · 읽기 실패.
#######################################
wait_for_move_end() {
  local i procs sessions
  for (( i = 0; i < MOVE_WAIT_TRIES; i++ )); do
    procs="$(compose top postgres)" || return 1
    if [[ "${procs}" != *"${MOVE_APP_NAME}"* ]]; then
      sessions="$(psql_in_db -At -c "SELECT pg_catalog.count(*) \
FROM pg_catalog.pg_stat_activity \
WHERE application_name = '${MOVE_APP_NAME}'")" || return 1
      if [[ "${sessions}" == '0' ]]; then
        return 0
      fi
    fi
    sleep "${MOVE_WAIT_SECONDS}"
  done
  return 1
}

#######################################
# public 에 있는 서버 이력 표 이름을 공백으로 이어 낸다(없으면 빈 줄).
# Outputs:
#   이력 표 이름들.
#######################################
history_in_public() {
  psql_in_db -At -c "SELECT pg_catalog.string_agg(c.relname::text, ' ' \
ORDER BY c.relname) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n \
ON n.oid = c.relnamespace WHERE n.nspname = 'public' \
AND c.relname IN (${HISTORY_TABLES})"
}

#######################################
# 빌드 캐시를 상한 아래로 줄인다(워크플로의 cap_prune 과 같은 차례 — 새 옵션
# 이름 → 옛 이름 → 둘 다 안 되면 통째로).
# Arguments:
#   빌드 캐시 상한.
#######################################
prune_build_cache() {
  local cap="$1"
  local flag
  for flag in --max-used-space --keep-storage; do
    # shellcheck disable=SC2024 # 로그는 root(이 스크립트)가 쓴다 — 워크플로와 같다.
    if sudo -u ec2-user docker builder prune -f "${flag}" "${cap}" \
      > "${PRUNE_LOG}" 2>&1; then
      tail -1 "${PRUNE_LOG}"
      return 0
    fi
  done
  echo '::warning::build cache cap prune failed with both flags,' \
    'pruning all build cache'
  sudo -u ec2-user docker builder prune -af | tail -1
}

#######################################
# 남은 공간이 백업을 담을 만한지 본다(DB 크기의 두 배 + 1GB). 모자라면 아무것도
# 멈추지 않고 끝낸다.
#######################################
require_disk_for_backup() {
  local db_bytes free_bytes need_bytes
  db_bytes="$(psql_in_db -At -c \
    'SELECT pg_catalog.pg_database_size(pg_catalog.current_database())')"
  free_bytes="$(df --output=avail -B1 / | tail -1 | tr -dc 0-9)"
  need_bytes=$(( db_bytes * 2 + ONE_GB ))
  if (( free_bytes < need_bytes )); then
    echo "::error::schema move: ${free_bytes} bytes free, need ${need_bytes}" \
      '(db size x2 + 1GB); nothing stopped'
    exit 1
  fi
}

#######################################
# 진행 중 표지를 쓴다 — 이 실행의 식별자 · UTC 시각 · in-progress 한 줄.
# 디렉터리는 백업과 같이 0700 으로 만든다.
# Returns:
#   0 씀, 그 밖 쓰기 실패.
#######################################
write_marker() {
  (
    umask 077
    mkdir -p -- "${BACKUP_DIR}" \
      && printf '%s %s in-progress\n' "${MOVE_RUN_ID}" \
        "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "${UNKNOWN_MARKER}"
  )
}

#######################################
# 결과가 확실해진 뒤 표지를 지운다. 못 지우면 ::error:: 를 낸다 — 다음 배포가
# 표지를 보고 괜히 멈출 뿐인 안전 쪽 실패다.
# Returns:
#   0 지움(없었음 포함), 1 지우기 실패.
#######################################
clear_marker() {
  if rm -f -- "${UNKNOWN_MARKER}"; then
    return 0
  fi
  echo '::error::schema move: could not delete the marker' \
    "${UNKNOWN_MARKER} (run ${MOVE_RUN_ID}); the result above is settled but" \
    'the next deploy will stop on it — check and delete it by hand' \
    '(services/README.md)'
  return 1
}

#######################################
# 표지를 쓴 뒤 · 정지 전의 EXIT trap. 아무것도 멈추지 않았으니 결과가 확실하다 —
# 표지를 지우고, 못 지우면 종료 1.
#######################################
clear_marker_on_exit() {
  local status=$?
  clear_marker || status=1
  exit "${status}"
}

#######################################
# 앞 배포가 남긴 상태 불명 표지가 있으면 아무것도 멈추지 않고 끝낸다. 그 배포가
# 아직 돌거나 결과가 확실하지 않아 밀린 이전이 커밋할 수 있고, 이 배포가 옛
# 컨테이너를 띄우면 자료가 갈린다.
#######################################
require_no_unknown_marker() {
  local content
  if [[ ! -e "${UNKNOWN_MARKER}" ]]; then
    return 0
  fi
  content="$(head -n 1 -- "${UNKNOWN_MARKER}" 2>/dev/null)" \
    || content='(unreadable)'
  echo '::error::schema move: an earlier run is in progress or ended in an' \
    "unknown state (${UNKNOWN_MARKER}: ${content}); nothing stopped. Check" \
    'that no pending move remains and where the tables are, then delete the' \
    'marker (services/README.md)'
  exit 1
}

#######################################
# 실패 뒤 복구. 이 실행의 울타리를 세우고, 이전 psql 이 끝나기를 기다린 뒤 이전이
# 커밋됐는지 읽는다 — 커밋 뒤에 옛 컨테이너를 띄우면 옛 Flyway 가 public 에 빈
# 표를 만들어 자료가 갈린다. 끝나기 전에 읽으면 커밋 전 모습이 보여 그 오판을
# 한다(일반 SELECT 는 남의 미커밋 변경을 보지 않는다).
#
# 울타리를 대기보다 먼저 세우는 까닭: 데몬은 호스트 연결이 끊긴 뒤에도 이전
# psql 을 늦게 시작할 수 있어, 대기 때 프로세스 · 세션이 0 이어도 이전이 나중에
# 돌 수 있다. 울타리가 커밋되기 전에 첫 문장(울타리 검사)을 돈 이전은 그때 이미
# 접속해 있으므로 대기의 세션 확인에 보이고, 커밋 뒤에 첫 문장을 돈 이전은
# 울타리를 보고 아무것도 바꾸지 않고 실패한다(READ COMMITTED 는 문장마다 새
# 스냅숏을 잡는다). 울타리는 지우지 않는다 — 이 실행의 이전이 언제 늦게
# 시작할지 모르고, 다음 실행은 자기 이름의 울타리만 보므로 막히지 않는다.
# main 이 시작 때 쓴 표지는 결과가 확실한 커밋 전(띄운 뒤) · 커밋 뒤에만
# 지운다. 판정 불가면 남긴다(다음 배포가 옛 컨테이너를 띄우지 않게 — 울타리를
# 못 세웠을 수 있다).
# Globals:
#   history_before
#######################################
restore() {
  local now state
  trap - EXIT
  set +e
  if ! psql_in_db -c "CREATE SCHEMA IF NOT EXISTS ${MOVE_FENCE_SCHEMA}"; then
    state='unknown'
  elif wait_for_move_end; then
    now="$(history_in_public)" || state='unknown'
  else
    state='unknown'
  fi
  if [[ -z "${state:-}" ]]; then
    if [[ "${now}" == "${history_before}" ]]; then
      state='before_commit'
    elif [[ -z "${now}" ]]; then
      state='committed'
    else
      state='unknown'
    fi
  fi
  case "${state}" in
    before_commit)
      echo 'schema move: failed before commit, restarting old containers'
      compose start "${SERVICES[@]}"
      clear_marker
      ;;
    committed)
      echo '::error::schema move committed but a later step failed;' \
        'old containers NOT restarted'
      clear_marker
      ;;
    *)
      echo '::error::schema move state unknown; old containers NOT restarted;' \
        "marker ${UNKNOWN_MARKER} left (run ${MOVE_RUN_ID})"
      ;;
  esac
  exit 1
}

main() {
  local cache_cap="${1:?빌드 캐시 상한(예: 6gb)을 첫 인자로 준다}"
  local backup_file

  # 이력 읽기 · 정지 · trap 보다 먼저 — 앞 배포의 밀린 이전이 남았을 수 있다.
  require_no_unknown_marker

  # 건너뛰기는 표지를 쓰기 전이다 — 위 검사를 지났으면 밀린 이전을 남긴 앞
  # 실행이 없어 이 읽기가 바뀌지 않고, 건너뛰기는 아무것도 멈추지 않는다.
  history_before="$(history_in_public)"
  if [[ -z "${history_before}" ]]; then
    echo 'schema move: skipped'
    return 0
  fi

  # 캐시 줄이기 · trap · 정지보다 먼저 쓴다 — 그 뒤 어떻게 끝나든(복구가 표지를
  # 못 쓰는 고장 · 끊김 포함) 결과가 확실하지 않으면 표지가 남는다.
  if ! write_marker; then
    echo '::error::schema move: could not write the in-progress marker' \
      "${UNKNOWN_MARKER} (run ${MOVE_RUN_ID}); nothing stopped"
    exit 1
  fi
  trap clear_marker_on_exit EXIT

  prune_build_cache "${cache_cap}"
  require_disk_for_backup

  # stop 바로 앞에 건다 — stop 이 일부만 세우고 실패해도 restore 를 거친다(도는
  # 컨테이너에 start 는 아무 일도 하지 않는다).
  trap restore EXIT
  compose stop "${SERVICES[@]}"

  umask 077
  mkdir -p "${BACKUP_DIR}"
  backup_file="${BACKUP_DIR}/public-$(date -u '+%Y%m%dT%H%M%SZ').sql"
  # public 만으로 넉넉하다 — 이전 SQL 은 public 의 객체를 옮겨 낼 뿐 app_* 에
  # 이미 있던 것은 바꾸지 않는다(같은 이름이 있으면 ALTER 가 실패해 전부
  # 되돌린다). 역이전 직전 백업은 DB 전체다(README).
  # shellcheck disable=SC2016 # 컨테이너 안의 셸이 풀 변수다.
  compose exec -T postgres sh -c \
    'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -n public' > "${backup_file}"

  run_move_sql

  trap - EXIT
  echo 'schema move: moved'
  echo "schema move: public backup written under ${BACKUP_DIR}"
  clear_marker || exit 1
}

main "$@"
