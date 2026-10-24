# shellcheck shell=bash
# 목록 · 임시 파일 전역(file_* · render_tmps · staged_tmp · render_step)은 시작
# 스크립트가 정의하고 읽는다.
# shellcheck disable=SC2034,SC2154
#
# 시작 스크립트의 렌더 — 모드 고르기와 렌더 파일 두기.
#   - 모드 고르기: compose 렌더본의 응답기 서비스 키 줄 수로 송출 인증 모드를
#     정하고(compose_mode), MediaMTX 템플릿에서 그 모드 블록만 남긴다
#     (select_mode_blocks). 템플릿은 렌더하지 않은 저장소 파일 그대로 상자에
#     깔린다(M9 설치 묶음) — 두 모드의 블록을 모두 품고, 모드는 compose 하나가
#     정한다. 시험(render_test · secret_count_test · 계약 시험)도 이 두 함수를
#     source 해 같은 길로 고른다.
#   - 파일 두기: 이번 기동에 둘 파일 목록(최종 경로 · 소유 · 실패 단계 이름
#     앞머리 · 내용)을 모아, 모두 같은 디렉터리의 임시 파일(0400 · 지정 소유)에
#     쓰고 대상을 모두 점검한 뒤 차례로 rename 한다. rename 이 하나라도 실패하면
#     이번에 옮긴 파일을 지운다(짝이 어긋난 설정을 남기지 않는다).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). 상자(AL2023 · bash 5)와 kty
# 로컬(macOS · bash 3.2) 양쪽에서 돈다.

# compose 렌더본의 응답기 서비스 키 줄(compose.yml.tmpl 의 서비스 키 그대로).
readonly INGEST_AUTH_SERVICE_LINE='  ingest-auth:'
# MediaMTX 템플릿의 모드 블록 표지 줄 — 「# @mode <모드> begin」 ·
# 「# @mode <모드> end」 한 줄 전체. 이 글자가 든 줄은 모두 표지로 읽는다.
readonly MODE_MARK='@mode'

#######################################
# compose 렌더본의 응답기 서비스 키 줄 수로 송출 인증 모드를 낸다 — 0 이면
# legacy, 1 이면 ingest-auth. 2 이상이거나, 같은 키를 다른 꼴(뒤 공백 · 같은
# 줄 값)로 쓴 줄이 있으면 실패다. 읽을 수 없는 파일은 0 으로 센다(옛 모드 —
# compose up 이 그 파일로 실패를 드러낸다).
# Globals:
#   INGEST_AUTH_SERVICE_LINE
# Arguments:
#   compose 파일.
# Outputs:
#   legacy | ingest-auth.
# Returns:
#   0 / 1 꼴 밖.
#######################################
compose_mode() {
  local counts='0 0'
  if [[ -f "$1" && -r "$1" ]]; then
    counts="$(awk -v line="${INGEST_AUTH_SERVICE_LINE}" '
      $0 == line { exact++ }
      index($0, line) == 1 { loose++ }
      END { print exact + 0, loose + 0 }' "$1")" || return 1
  fi
  case "${counts}" in
    '0 0') printf 'legacy' ;;
    '1 1') printf 'ingest-auth' ;;
    *) return 1 ;;
  esac
}

#######################################
# MediaMTX 템플릿 내용에서 한 모드의 블록만 남겨 낸다 — 다른 모드 블록과 표지
# 줄은 지우고, 블록 밖 줄은 그대로 둔다(끝 줄바꿈 보존). 표지 글자(@mode)가 든
# 줄이 표지 꼴이 아니거나, 블록이 겹치거나 · 닫히지 않거나 · 짝이 다르거나, 두
# 모드 가운데 하나라도 블록이 없으면 실패다. 템플릿에는 비밀이 없다.
# Globals:
#   MODE_MARK
# Arguments:
#   모드(legacy | ingest-auth), 템플릿 내용.
# Outputs:
#   고른 내용.
# Returns:
#   0 / 1 꼴 밖.
#######################################
select_mode_blocks() {
  local want="$1" rest="$2" out="" open="" seen=" " line nl
  [[ "${want}" == legacy || "${want}" == ingest-auth ]] || return 1
  while [[ -n "${rest}" ]]; do
    line="${rest%%$'\n'*}"
    nl=""
    if [[ "${line}" != "${rest}" ]]; then
      nl=$'\n'
    fi
    rest="${rest#"${line}${nl}"}"
    if [[ "${line}" != *"${MODE_MARK}"* ]]; then
      [[ -n "${open}" && "${open}" != "${want}" ]] || out+="${line}${nl}"
    elif [[ -z "${open}" && ( "${line}" == "# ${MODE_MARK} legacy begin" \
      || "${line}" == "# ${MODE_MARK} ingest-auth begin" ) ]]; then
      open="${line#"# ${MODE_MARK} "}"
      open="${open% begin}"
      seen+="${open} "
    elif [[ -n "${open}" && "${line}" == "# ${MODE_MARK} ${open} end" ]]; then
      open=""
    else
      return 1
    fi
  done
  [[ -z "${open}" && "${seen}" == *" legacy "* \
    && "${seen}" == *" ingest-auth "* ]] || return 1
  printf '%s' "${out}"
}

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
# Returns:
#   0 / 1 숫자 꼴 아님(단계 이름은 render_step = owner).
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
