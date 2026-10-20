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
# --cleanup-only: docker 를 부르지 않고 지우기만 한다(유닛은 쓰지 않는다).
#
# 기본값은 시작 스크립트와 같다(MEDIA_COMPOSE_PROJECT · MEDIA_COMPOSE_FILE ·
# RUNTIME_DIR). RUNTIME_DIR 이 절대 경로가 아니면 아무것도 하지 않고 「MEDIA STOP
# FAIL runtime-dir」 로 끝난다. 지운 뒤 남은 파일이 있으면 「MEDIA STOP CLEANUP
# FAIL」 을 내고 0 아닌 코드로 끝난다.

set -euo pipefail

# 시작 스크립트가 RUNTIME_DIR 에 두는 파일 이름(임시 파일은 .<이름>.XXXXXX).
readonly RENDER_NAMES=(mediamtx.yml mtx_api_pass)

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
