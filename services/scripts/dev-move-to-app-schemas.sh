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
#   옮김    서버 넷 · 일꾼 둘 정지 → public 백업(0600) → 이전 SQL(한 트랜잭션).
#           `schema move: moved` · 종료 0. 뒤이은 워크플로의 up -d 가 새 이미지로
#           다시 만든다.
#   실패    정지 뒤의 어느 실패든 restore 가 이전이 커밋됐는지 읽는다.
#           커밋 전(이력 표가 public 에 그대로) → 여섯을 다시 start · 종료 1.
#           커밋 뒤 · 판정 불가 → 옛 컨테이너를 띄우지 않는다(옛 Flyway 가 public
#           에 빈 표를 만들어 자료가 갈린다) · ::error:: 한 줄 · 종료 1.
#
# 되돌리기 순서 · 백업 정리는 services/README.md 「dev 앱별 스키마 이전과
# 되돌리기」. 이전 단계를 지우는 후속 PR 에서 이 파일도 지운다.

set -euo pipefail

readonly COMPOSE_FILE='docker-compose.dev.yml'
readonly MOVE_SQL='scripts/dev-move-to-app-schemas.sql'
readonly BACKUP_DIR='/home/ec2-user/pokeclip-backup'
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
# 실패 뒤 복구. 이전이 커밋됐는지부터 읽는다 — 커밋 뒤에 옛 컨테이너를 띄우면 옛
# Flyway 가 public 에 빈 표를 만들어 자료가 갈린다.
# Globals:
#   history_before
#######################################
restore() {
  local now state
  trap - EXIT
  set +e
  now="$(history_in_public)" || state='unknown'
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
      ;;
    committed)
      echo '::error::schema move committed but a later step failed;' \
        'old containers NOT restarted'
      ;;
    *)
      echo '::error::schema move state unknown; old containers NOT restarted'
      ;;
  esac
  exit 1
}

main() {
  local cache_cap="${1:?빌드 캐시 상한(예: 6gb)을 첫 인자로 준다}"
  local backup_file

  history_before="$(history_in_public)"
  if [[ -z "${history_before}" ]]; then
    echo 'schema move: skipped'
    return 0
  fi

  prune_build_cache "${cache_cap}"
  require_disk_for_backup

  # stop 바로 앞에 건다 — stop 이 일부만 세우고 실패해도 restore 를 거친다(도는
  # 컨테이너에 start 는 아무 일도 하지 않는다).
  trap restore EXIT
  compose stop "${SERVICES[@]}"

  umask 077
  mkdir -p "${BACKUP_DIR}"
  backup_file="${BACKUP_DIR}/public-$(date -u '+%Y%m%dT%H%M%SZ').sql"
  # shellcheck disable=SC2016 # 컨테이너 안의 셸이 풀 변수다.
  compose exec -T postgres sh -c \
    'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -n public' > "${backup_file}"

  psql_in_db -1 -f - < "${MOVE_SQL}"

  trap - EXIT
  echo 'schema move: moved'
  echo "schema move: public backup written under ${BACKUP_DIR}"
}

main "$@"
