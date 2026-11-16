# shellcheck shell=bash
# 렌더 전역(staged_tmp · render_step · sidecar_owner)과 say · read_token_secret
# 는 시작 스크립트가, stage_file · target_ok 는 lib/render_files.sh 가
# 정의한다.
# shellcheck disable=SC2154
#
# 시작 스크립트의 60일 만료 창구 토큰 두기(머리 주석 순서 8 끝 문단). compose
# 렌더본은 clip 주소가 찰 때 사이드카에 이 디렉터리를 읽기 전용으로 붙이고
# CLIP_EXPIRY_TOKEN_FILE 로 그 안의 토큰 파일을 가리킨다. 토큰 파일이 없으면
# 사이드카는 뜨고 만료만 끈다(expiry_disabled reason=token_file) — 그래서 받기
# 실패는 기동을 막지 않는다. 앞 기동의 토큰을 받기 전에 지워 두므로, 받기가
# 실패하면 낡은 토큰이 아니라 「파일 없음」 이 남는다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). 상자(AL2023 · bash 5)와 kty
# 로컬(macOS · bash 3.2) 양쪽에서 돈다.

# RUNTIME_DIR 아래 토큰 디렉터리 이름(compose bind 원본)과 그 안의 파일 이름
# (사이드카 CLIP_EXPIRY_TOKEN_FILE 의 끝 이름).
readonly CLIP_EXPIRY_DIR_NAME='clip-expiry'
readonly CLIP_EXPIRY_TOKEN_NAME='token'

#######################################
# 토큰 디렉터리를 root 소유 0711 로 매번 맞추고(엔진이 만든 디렉터리도 고쳐
# 쓴다), 앞 기동이 남긴 토큰과 그 임시 파일을 지운다. 링크면 받지 않는다
# (가리키는 곳에 비밀을 쓰지 않는다 — 응답기 디렉터리와 같은 규칙).
# Globals:
#   render_step
# Arguments:
#   토큰 디렉터리.
# Returns:
#   0 / 1 링크 · 실패(단계 이름은 render_step).
#######################################
prepare_clip_expiry_dir() {
  local token="$1/${CLIP_EXPIRY_TOKEN_NAME}"
  if [[ -L "$1" ]] || ! mkdir -p -- "$1" || ! chown -h 0:0 "$1" \
    || ! chmod 0711 "$1" || [[ ! -d "$1" || -L "$1" ]] \
    || ! rm -f -- "${token}" "$1/.${CLIP_EXPIRY_TOKEN_NAME}".* \
    || [[ -e "${token}" || -L "${token}" ]]; then
    render_step='clip-expiry-dir'
    return 1
  fi
}

#######################################
# CLIP_EXPIRY_TOKEN_SECRET_ARN 이 차면 토큰을 받아 사이드카 실행 계정 소유
# 0400 파일로 둔다(같은 디렉터리의 임시 파일에 쓴 뒤 rename). ARN 이 비면
# 아무것도 하지 않는다. 받기 실패 · 쓸 수 없는 값이면 판정 줄 없이 파일 없이
# 지나간다. 디렉터리 · 파일 두기 실패는 막는다 — 실패 줄은 여기서 낸다.
# Globals:
#   CLIP_EXPIRY_TOKEN_SECRET_ARN, sidecar_owner, staged_tmp, render_step
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 둠 · 건너뜀 / 1 실패.
#######################################
install_clip_expiry_token() {
  local arn="${CLIP_EXPIRY_TOKEN_SECRET_ARN:-}" expiry_token
  local dir="$1/${CLIP_EXPIRY_DIR_NAME}"
  local path="${dir}/${CLIP_EXPIRY_TOKEN_NAME}"
  [[ -n "${arn}" ]] || return 0
  if ! prepare_clip_expiry_dir "${dir}"; then
    say "MEDIA START FAIL render ${render_step}"
    return 1
  fi
  expiry_token="$(read_token_secret "${arn}")" || return 0
  if ! stage_file "${path}" "${expiry_token}"$'\n' "${sidecar_owner}" \
    'clip-expiry-'; then
    unset expiry_token
    say "MEDIA START FAIL render ${render_step}"
    return 1
  fi
  unset expiry_token
  if ! target_ok "${path}"; then
    say 'MEDIA START FAIL render clip-expiry-target'
    return 1
  fi
  if ! mv -f -- "${staged_tmp}" "${path}"; then
    say 'MEDIA START FAIL render clip-expiry-rename'
    return 1
  fi
}
