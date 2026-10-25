#!/usr/bin/env bash
#
# Media 상자 정지 스크립트 — systemd 유닛 pokeclip-media 의 ExecStop ·
# ExecStopPost(둘 다 인자 없음). 주 프로세스가 실패로 끝나면 systemd 가
# ExecStop 을 건너뛰므로 ExecStopPost 도 down 까지 한다.
#
# 인자 없음: `docker compose … down` 으로 프로젝트를 내리고, down 의 결과와
#   상관없이 tmpfs 의 렌더 파일 둘(CDN 비밀 · API 비밀번호가 든 mediamtx.yml ·
#   사이드카의 mtx_api_pass), 응답기 모드의 비밀 파일 셋(RUNTIME_DIR/ingest-auth
#   의 ingest_basic_pass · mtx_api_pass · stream_key_resolve_token)과 시작
#   스크립트가 남겼을 수 있는 임시 파일을 지운다. 응답기 디렉터리 자체와 그 안의
#   상태 디렉터리, 거부 목록(/var/lib 쪽)은 남긴다 — 비밀이 아니고, 상태는 다음
#   기동이 이어 쓰며 거부 목록은 다음 기동이 SSM 에서 다시 받는다. 응답기
#   디렉터리가 링크면 따라가 지우지 않고 실패로 적는다(시작 스크립트도 받지
#   않는다).
#   down 의 종료 코드를 돌려준다. down 인자는 `down`
#   하나로 고정한다 — 볼륨(녹화 · 훅 스풀 · DVR)은 지우지 않는다.
#   down 이 실패하면(compose 파일이 깨져 읽지 못하는 경우 포함) compose 파일
#   없이 프로젝트 라벨로 남은 컨테이너를 찾아 지운다 — 남으면 옛 렌더(옛
#   비밀)를 붙든 채 돈다. 그래도 종료 코드는 down 의 것이다(실패를 드러낸다).
#   폴백의 docker 호출은 하나에 10초 상한을 둔다 — 넘기면 그 호출을 끝내고
#   폴백 실패로 적는다.
#   down · 폴백이 끝나지 않는 동안 TERM · INT 를 받으면(systemd 가 정지 상한을
#   넘긴 정지 명령에 보낸다) 그 명령을 멈추고 렌더를 지운 뒤 143 · 130 으로
#   끝난다. 이때 컨테이너는 거두지 않는다 — 유닛의 ExecStopPost(이 스크립트를
#   다시 부른다)와 다음 시작의 --force-recreate · --remove-orphans 몫이다.
# --cleanup-only: docker 를 부르지 않고 지우기만 한다(유닛은 쓰지 않는다).
#
# 기본값은 시작 스크립트와 같다(MEDIA_COMPOSE_PROJECT · MEDIA_COMPOSE_FILE ·
# RUNTIME_DIR). RUNTIME_DIR 이 절대 경로가 아니면 아무것도 하지 않고 「MEDIA STOP
# FAIL runtime-dir」 로 끝난다. 지운 뒤 남은 파일이 있으면 그 디렉터리마다
# 「MEDIA STOP CLEANUP FAIL <디렉터리>」 를 내고 0 아닌 코드로 끝난다.

set -euo pipefail

# 시작 스크립트가 RUNTIME_DIR 에 두는 파일 이름(임시 파일은 .<이름>.XXXXXX).
readonly RENDER_NAMES=(mediamtx.yml mtx_api_pass)
# 응답기 모드에서 RUNTIME_DIR/ingest-auth 에 두는 비밀 파일 이름(임시 파일 꼴은
# 위와 같다).
readonly INGEST_AUTH_DIR_NAME='ingest-auth'
readonly INGEST_AUTH_NAMES=(ingest_basic_pass mtx_api_pass
  stream_key_resolve_token)
# 폴백의 docker 호출(ps · rm) 한 번의 상한(초).
readonly FALLBACK_TIMEOUT=10

runtime_dir=""
down_pid=""
fallback_pid=""
fallback_out=""

#######################################
# 디렉터리 하나에서 정한 이름의 파일과 그 임시 파일을 지우고 0건인지 본다.
# 디렉터리가 없으면 0건이다.
# Arguments:
#   디렉터리, 파일 이름 ….
# Returns:
#   0 남은 것 없음 / 1 남음.
#######################################
remove_rendered() {
  local dir="$1" name f left=0 paths=()
  shift
  for name in "$@"; do
    paths+=("${dir}/${name}" "${dir}/.${name}".*)
  done
  for f in "${paths[@]}"; do
    if [[ -e "${f}" || -L "${f}" ]]; then
      rm -f -- "${f}" || true
    fi
  done
  for f in "${paths[@]}"; do
    if [[ -e "${f}" || -L "${f}" ]]; then
      left=$((left + 1))
    fi
  done
  (( left == 0 ))
}

#######################################
# 렌더 · 응답기 비밀 파일을 지우고, 남은 디렉터리마다 실패 줄을 낸다.
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 남은 것 없음 / 1 남음.
#######################################
cleanup_rendered() {
  local ia="$1/${INGEST_AUTH_DIR_NAME}" status=0
  if ! remove_rendered "$1" "${RENDER_NAMES[@]}"; then
    printf 'MEDIA STOP CLEANUP FAIL %s\n' "$1"
    status=1
  fi
  # 링크면 가리키는 곳의 같은 이름 파일을 지우게 되므로 손대지 않는다.
  if [[ -L "${ia}" ]] || ! remove_rendered "${ia}" "${INGEST_AUTH_NAMES[@]}"
  then
    printf 'MEDIA STOP CLEANUP FAIL %s\n' "${ia}"
    status=1
  fi
  return "${status}"
}

#######################################
# 신호 처리(TERM · INT 트랩) — 진행 중인 down 을 멈추고 렌더를 지운 뒤 끝낸다.
# Globals:
#   down_pid, runtime_dir
# Arguments:
#   종료 코드.
#######################################
# 트랩 문자열이 부른다.
# shellcheck disable=SC2329
stop_on_signal() {
  local pid
  trap - INT TERM
  for pid in "${down_pid}" "${fallback_pid}"; do
    if [[ -n "${pid}" ]]; then
      kill -TERM "${pid}" 2>/dev/null || true
    fi
  done
  if [[ -n "${fallback_out}" ]]; then
    rm -f -- "${fallback_out}" || true
  fi
  printf 'MEDIA STOP interrupted\n'
  cleanup_rendered "${runtime_dir}" || true
  exit "$1"
}

#######################################
# 프로젝트를 내린다. down 을 배경에서 띄우고 기다린다 — 전경 명령을 기다리는
# 동안은 bash 가 트랩을 미뤄, 끝나지 않는 down 이 신호 처리를 막는다.
# Globals:
#   down_pid
# Arguments:
#   프로젝트, compose 파일.
# Returns:
#   down 의 종료 코드.
#######################################
compose_down() {
  local status=0
  docker compose -p "$1" -f "$2" down &
  down_pid=$!
  wait "${down_pid}" || status=$?
  down_pid=""
  return "${status}"
}

#######################################
# 폴백의 docker 호출 하나를 상한 안에서 돌린다. 배경에서 띄우고 기다린다 —
# 전경 명령(명령 치환 포함)을 기다리는 동안은 bash 가 트랩을 미룬다. 표준
# 출력은 부르는 쪽이 정한 곳으로 간다.
# Globals:
#   fallback_pid
# Arguments:
#   명령 ….
# Returns:
#   명령의 종료 코드 / 124 상한 넘김(명령은 끝낸다).
#######################################
bounded_fallback() {
  local deadline=$((SECONDS + FALLBACK_TIMEOUT)) status=0
  "$@" &
  fallback_pid=$!
  while kill -0 "${fallback_pid}" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      kill -KILL "${fallback_pid}" 2>/dev/null || true
      wait "${fallback_pid}" 2>/dev/null || true
      fallback_pid=""
      return 124
    fi
    sleep 0.2
  done
  wait "${fallback_pid}" || status=$?
  fallback_pid=""
  return "${status}"
}

#######################################
# compose 파일 없이 프로젝트 라벨로 남은 컨테이너를 찾아 지우고 결과 줄을
# 낸다(down 이 실패했을 때). 찾은 ID 는 임시 파일로 받는다(명령 치환은 신호를
# 미룬다).
# Globals:
#   fallback_out, TMPDIR
# Arguments:
#   프로젝트.
# Returns:
#   0 지움(또는 없음) / 1 찾기 · 지우기 실패.
#######################################
remove_project_containers() {
  local ids="" n status=0
  if ! fallback_out="$(mktemp "${TMPDIR:-/tmp}/pokeclip-media-stop.XXXXXX")"
  then
    fallback_out=""
    printf 'MEDIA STOP fallback FAIL\n'
    return 1
  fi
  bounded_fallback docker ps -aq --filter \
    "label=com.docker.compose.project=$1" >"${fallback_out}" || status=$?
  if (( status == 0 )); then
    ids="$(awk 'NF' "${fallback_out}")" || status=1
  fi
  rm -f -- "${fallback_out}" || true
  fallback_out=""
  if (( status != 0 )); then
    printf 'MEDIA STOP fallback FAIL\n'
    return 1
  fi
  n="$(printf '%s' "${ids}" | awk 'NF { n++ } END { print n + 0 }')"
  # 컨테이너 ID(16진)를 낱말로 나눠 넘긴다.
  # shellcheck disable=SC2086
  if (( n != 0 )) && ! bounded_fallback docker rm -f ${ids} >/dev/null; then
    printf 'MEDIA STOP fallback FAIL\n'
    return 1
  fi
  printf 'MEDIA STOP fallback removed %d\n' "${n}"
}

main() {
  local project="${MEDIA_COMPOSE_PROJECT:-pokeclip-media}"
  local compose_file="${MEDIA_COMPOSE_FILE:-/etc/pokeclip-media/compose.yml}"
  local dir="${RUNTIME_DIR:-/run/pokeclip-media}" cleanup_only=0 status=0
  if (( $# == 1 )) && [[ "$1" == --cleanup-only ]]; then
    cleanup_only=1
  elif (( $# != 0 )); then
    printf 'MEDIA STOP FAIL usage\n'
    exit 2
  fi
  # 시작 스크립트와 같은 검사 — 상대 경로 · 루트(/)면 엉뚱한 파일을 지운다.
  if [[ "${dir}" != /?* ]]; then
    printf 'MEDIA STOP FAIL runtime-dir\n'
    exit 2
  fi
  runtime_dir="${dir}"
  trap 'stop_on_signal 130' INT
  trap 'stop_on_signal 143' TERM
  if (( ! cleanup_only )); then
    compose_down "${project}" "${compose_file}" || status=$?
    if (( status != 0 )); then
      printf 'MEDIA STOP down exit %d\n' "${status}"
      remove_project_containers "${project}" || true
    fi
  fi
  if ! cleanup_rendered "${dir}"; then
    (( status != 0 )) || status=1
  fi
  exit "${status}"
}

main "$@"
