#!/usr/bin/env bash
#
# Media 상자 정지 스크립트 — systemd 유닛 pokeclip-media 의 ExecStop ·
# ExecStopPost(둘 다 인자 없음). 주 프로세스가 실패로 끝나면 systemd 가
# ExecStop 을 건너뛰므로 ExecStopPost 도 down 까지 한다.
#
# 인자 없음: `docker compose … down` 으로 프로젝트를 내리고, down 의 결과와
#   상관없이 tmpfs 의 렌더 파일 둘(CDN 비밀 · API 비밀번호가 든 mediamtx.yml ·
#   사이드카의 mtx_api_pass)과 시작 스크립트가 남겼을 수 있는 임시 파일을
#   지운다. down 의 종료 코드를 돌려준다. down 인자는 `down`
#   하나로 고정한다 — 볼륨(녹화 · 훅 스풀 · DVR)은 지우지 않는다.
#   down 이 실패하면(compose 파일이 깨져 읽지 못하는 경우 포함) compose 파일
#   없이 프로젝트 라벨로 남은 컨테이너를 찾아 지운다 — 남으면 옛 렌더(옛
#   비밀)를 붙든 채 돈다. 그래도 종료 코드는 down 의 것이다(실패를 드러낸다).
#   down 이 끝나지 않는 동안 TERM · INT 를 받으면(systemd 가 정지 상한을 넘긴
#   정지 명령에 보낸다) down 을 멈추고 렌더를 지운 뒤 143 · 130 으로 끝난다.
# --cleanup-only: docker 를 부르지 않고 지우기만 한다(유닛은 쓰지 않는다).
#
# 기본값은 시작 스크립트와 같다(MEDIA_COMPOSE_PROJECT · MEDIA_COMPOSE_FILE ·
# RUNTIME_DIR). RUNTIME_DIR 이 절대 경로가 아니면 아무것도 하지 않고 「MEDIA STOP
# FAIL runtime-dir」 로 끝난다. 지운 뒤 남은 파일이 있으면 「MEDIA STOP CLEANUP
# FAIL」 을 내고 0 아닌 코드로 끝난다.

set -euo pipefail

# 시작 스크립트가 RUNTIME_DIR 에 두는 파일 이름(임시 파일은 .<이름>.XXXXXX).
readonly RENDER_NAMES=(mediamtx.yml mtx_api_pass)

runtime_dir=""
down_pid=""

#######################################
# 렌더 파일과 임시 렌더 파일을 지우고 0건인지 본다.
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 남은 것 없음 / 1 남음.
#######################################
remove_rendered() {
  local dir="$1" name f left=0 paths=()
  for name in "${RENDER_NAMES[@]}"; do
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
# 렌더를 지우고 실패하면 그 줄을 낸다.
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 남은 것 없음 / 1 남음.
#######################################
cleanup_rendered() {
  if ! remove_rendered "$1"; then
    printf 'MEDIA STOP CLEANUP FAIL %s\n' "$1"
    return 1
  fi
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
  trap - INT TERM
  if [[ -n "${down_pid}" ]]; then
    kill -TERM "${down_pid}" 2>/dev/null || true
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
# compose 파일 없이 프로젝트 라벨로 남은 컨테이너를 찾아 지우고 결과 줄을
# 낸다(down 이 실패했을 때).
# Arguments:
#   프로젝트.
# Returns:
#   0 지움(또는 없음) / 1 찾기 · 지우기 실패.
#######################################
remove_project_containers() {
  local ids n
  if ! ids="$(docker ps -aq --filter \
    "label=com.docker.compose.project=$1")"; then
    printf 'MEDIA STOP fallback FAIL\n'
    return 1
  fi
  n="$(printf '%s' "${ids}" | awk 'NF { n++ } END { print n + 0 }')"
  # 컨테이너 ID(16진)를 낱말로 나눠 넘긴다.
  # shellcheck disable=SC2086
  if (( n != 0 )) && ! docker rm -f ${ids} >/dev/null; then
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
