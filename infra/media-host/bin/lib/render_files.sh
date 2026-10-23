# shellcheck shell=bash
# 목록 · 임시 파일 전역(file_* · render_tmps · staged_tmp · render_step)은 시작
# 스크립트가 정의하고 읽는다.
# shellcheck disable=SC2034,SC2154
#
# 시작 스크립트의 렌더 파일 두기 — 이번 기동에 둘 파일 목록(최종 경로 · 소유 ·
# 실패 단계 이름 앞머리 · 내용)을 모아, 모두 같은 디렉터리의 임시 파일(0400 ·
# 지정 소유)에 쓰고 대상을 모두 점검한 뒤 차례로 rename 한다. rename 이 하나라도
# 실패하면 이번에 옮긴 파일을 지운다(짝이 어긋난 설정을 남기지 않는다).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). 상자(AL2023 · bash 5)와 kty
# 로컬(macOS · bash 3.2) 양쪽에서 돈다.

#######################################
# 둘 파일 하나를 목록에 더한다.
# Globals:
#   file_paths, file_owners, file_steps, file_bodies
# Arguments:
#   최종 경로, 소유(uid:gid), 실패 단계 이름 앞머리, 내용.
#######################################
add_file() {
  file_paths+=("$1")
  file_owners+=("$2")
  file_steps+=("$3")
  file_bodies+=("$4")
}

#######################################
# 내용 하나를 최종 경로와 같은 디렉터리의 임시 파일(0400 · 지정 소유)에 쓴다.
# 임시 파일 이름을 render_tmps(EXIT 트랩) · staged_tmp 에 남긴다. 서브셸에서
# 부르지 않는다.
# Globals:
#   render_tmps, staged_tmp, render_step
# Arguments:
#   최종 경로, 내용, 소유(uid:gid), 실패 단계 이름 앞머리.
# Returns:
#   0 / 1 실패(단계 이름은 render_step).
#######################################
stage_file() {
  local path="$1" owner="$3" prefix="$4"
  staged_tmp="$(mktemp "${path%/*}/.${path##*/}.XXXXXX")" \
    || { render_step="${prefix}tmpfile"; return 1; }
  render_tmps+=("${staged_tmp}")
  if ! printf '%s' "$2" >"${staged_tmp}" || ! chmod 0400 "${staged_tmp}"; then
    render_step="${prefix}write"
    return 1
  fi
  if ! chown "${owner}" "${staged_tmp}"; then
    render_step="${prefix}chown"
    return 1
  fi
}

#######################################
# 최종 경로가 일반 파일이거나 없는지 본다. 대상이 디렉터리(또는 그리로 가는
# 링크)면 mv 가 그 안으로 옮기고 성공하므로 받지 않는다.
# Arguments:
#   최종 경로.
#######################################
target_ok() {
  [[ ! -L "$1" && ( ! -e "$1" || -f "$1" ) ]]
}

#######################################
# 목록의 파일을 모두 임시 파일에 쓰고, 대상을 모두 점검한 뒤 차례로 rename
# 한다. rename 이 하나라도 실패하면 이번에 옮긴 파일을 지운다(짝이 어긋난
# 설정을 남기지 않는다). 디렉터리는 부르는 쪽이 맞춰 둔다.
# Globals:
#   file_paths, file_owners, file_steps, file_bodies, render_tmps,
#   staged_tmp, render_step
# Returns:
#   0 둠 / 1 실패(단계 이름은 render_step).
#######################################
install_files() {
  local i j tmps=()
  for (( i = 0; i < ${#file_paths[@]}; i++ )); do
    stage_file "${file_paths[i]}" "${file_bodies[i]}" "${file_owners[i]}" \
      "${file_steps[i]}" || return 1
    tmps+=("${staged_tmp}")
  done
  for (( i = 0; i < ${#file_paths[@]}; i++ )); do
    if ! target_ok "${file_paths[i]}"; then
      render_step="${file_steps[i]}target"
      return 1
    fi
  done
  for (( i = 0; i < ${#file_paths[@]}; i++ )); do
    if ! mv -f -- "${tmps[i]}" "${file_paths[i]}"; then
      for (( j = 0; j < i; j++ )); do
        rm -f -- "${file_paths[j]}"
      done
      render_step="${file_steps[i]}rename"
      return 1
    fi
  done
  render_tmps=()
}

#######################################
# 목록의 소유 값이 모두 숫자 uid:gid 인지 본다.
# Globals:
#   file_owners, render_step
#######################################
check_file_owners() {
  local owner
  for owner in "${file_owners[@]}"; do
    if [[ ! "${owner}" =~ ^[0-9]+:[0-9]+$ ]]; then
      render_step='owner'
      return 1
    fi
  done
}
