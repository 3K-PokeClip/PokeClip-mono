#!/usr/bin/env bash
#
# Media 상자 정지 스크립트 — systemd 유닛 pokeclip-media 의 ExecStop(인자 없음)
# · ExecStopPost(--cleanup-only).
#
# 인자 없음: `docker compose … down` 으로 프로젝트를 내리고, down 의 결과와
#   상관없이 tmpfs 의 렌더 설정(CDN 비밀이 든 파일)과 시작 스크립트가 남겼을 수
#   있는 임시 파일을 지운다. down 의 종료 코드를 돌려준다. down 인자는 `down`
#   하나로 고정한다 — 볼륨(녹화 · 훅 스풀 · DVR)은 지우지 않는다.
# --cleanup-only: 지우기만 한다(유닛이 어떻게 끝났든 둘째 방어선).
#
# 기본값은 시작 스크립트와 같다(MEDIA_COMPOSE_PROJECT · MEDIA_COMPOSE_FILE ·
# RUNTIME_DIR). RUNTIME_DIR 이 절대 경로가 아니면 아무것도 하지 않고 「MEDIA STOP
# FAIL runtime-dir」 로 끝난다. 지운 뒤 남은 파일이 있으면 「MEDIA STOP CLEANUP
# FAIL」 을 내고 0 아닌 코드로 끝난다.

set -euo pipefail

readonly RENDER_NAME='mediamtx.yml'

#######################################
# 렌더 파일과 임시 렌더 파일을 지우고 0건인지 본다.
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 남은 것 없음 / 1 남음.
#######################################
remove_rendered() {
  local dir="$1" f left=0
  for f in "${dir}/${RENDER_NAME}" "${dir}/.${RENDER_NAME}".*; do
    if [[ -e "${f}" || -L "${f}" ]]; then
      rm -f -- "${f}" || true
    fi
  done
  for f in "${dir}/${RENDER_NAME}" "${dir}/.${RENDER_NAME}".*; do
    if [[ -e "${f}" || -L "${f}" ]]; then
      left=$((left + 1))
    fi
  done
  (( left == 0 ))
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
  if (( ! cleanup_only )); then
    docker compose -p "${project}" -f "${compose_file}" down || status=$?
    if (( status != 0 )); then
      printf 'MEDIA STOP down exit %d\n' "${status}"
    fi
  fi
  if ! remove_rendered "${dir}"; then
    printf 'MEDIA STOP CLEANUP FAIL %s\n' "${dir}"
    (( status != 0 )) || status=1
  fi
  exit "${status}"
}

main "$@"
