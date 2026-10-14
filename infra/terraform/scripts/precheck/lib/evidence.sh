# shellcheck shell=bash
#
# 단계 직전 점검 도구의 증거 경로 함수(계획 M17a 「계약」 · 설계 2-3 원칙
# 11). 점검 도구(scp-recheck.sh · events.sh · M17b collect.sh ·
# principal-check.sh)는 원본 출력을 이 함수가 돌려준 디렉터리에만 쓴다 —
# 검사 구현은 여기 한 곳이다.
#
# 고정 루트는 $HOME/.pokeclip-precheck 이고 인자 · 전용 환경 변수로 바꿀 수
# 없다. 루트가 HOME 에서 나오므로 HOME 을 바꾸면 루트도 바뀐다(시험이 이를
# 쓴다). 그래서 실제 방어는 아래 검사다. 정규화(cd -P) 뒤 다음이면 표준
# 오류에 「EVIDENCE PATH REJECTED <사유>」 를 적고 2 를 돌려준다.
#   - 루트 밖.
#   - 루트 또는 그 조상에 .git(저장소) · .obsidian(볼트) 표지가 있음.
#   - 루트부터 대상까지의 경로 구성 요소에 심볼릭 링크가 있음(루트 포함).
#     HOME 과 그 위의 링크(macOS 의 /var 등)는 정규화로 풀고 넘어간다.
#   - 디렉터리가 0700 이 아님.
#
# 이름 규약: 하위 디렉터리 이름은 도구 접두로 시작해 도구끼리 겹치지 않게
# 한다 — scp-recheck.sh 는 scp-<묶음>, events.sh 는 events, M17b 는
# collect-<단계> 꼴. 앞 결과를 찾는 쪽도 자기 접두만 본다.
#
# 거부 문구의 경로는 HOME 접두를 「~」 로 가린다 — 로컬 계정 이름을 표준
# 오류에 내지 않는다(성공 경로를 알리는 문구와 같은 규칙).
#
# 전제(이 함수가 막지 않는 것 — 한계로 둔다):
#   - 부르는 스크립트는 `umask 077` 로 돈다. 이 함수는 디렉터리만 0700 으로
#     만들고, 그 안 파일의 0600 은 부르는 쪽 umask 가 정한다.
#   - HOME 과 그 위 디렉터리는 남이 쓸 수 없다.
#   - 같은 uid 의 다른 프로세스가 검사와 쓰기 사이에 경로를 바꾸는
#     경쟁(TOCTOU)은 막지 못한다. bash 로 디렉터리를 원자적으로 고정하는
#     것은 과설계라 하지 않는다.
#
# 사용: source lib/evidence.sh
#       dir="$(evidence::new_dir <묶음>)" || exit 2

readonly EVIDENCE_ROOT_NAME='.pokeclip-precheck'
# 증거 디렉터리 이름의 앞부분(묶음 이름 등). 「/」 · 「..」 로 루트를 벗어나지
# 못하게 글자를 묶는다.
readonly EVIDENCE_LABEL_PATTERN='^[A-Za-z0-9][A-Za-z0-9-]*$'

#######################################
# 경로의 HOME 접두(정규화한 것 · 적힌 그대로)를 「~」 로 바꾼다. HOME 밖
# 경로는 그대로 둔다.
# Globals:
#   HOME
# Arguments:
#   경로.
# Outputs:
#   가린 경로.
#######################################
evidence::mask() {
  local path="$1" home tilde='~'
  for home in "$(cd -P -- "${HOME:-}" 2>/dev/null && pwd -P)" "${HOME:-}"; do
    home="${home%/}"
    [[ -n "${home}" ]] || continue
    case "${path}" in
      "${home}") path="${tilde}" ;;
      "${home}/"*) path="${tilde}/${path#"${home}"/}" ;;
      *) continue ;;
    esac
    break
  done
  printf '%s\n' "${path}"
}

#######################################
# 거부를 알린다.
# Arguments:
#   사유, [경로 — HOME 접두는 「~」 로 가림].
# Outputs:
#   표준 오류에 「EVIDENCE PATH REJECTED <사유>[ — <경로>]」.
# Returns:
#   늘 2.
#######################################
evidence::reject() {
  if [[ "$#" -ge 2 ]]; then
    printf 'EVIDENCE PATH REJECTED %s — %s\n' "$1" "$(evidence::mask "$2")" >&2
  else
    printf 'EVIDENCE PATH REJECTED %s\n' "$1" >&2
  fi
  return 2
}

#######################################
# 고정 루트의 정규화한 경로를 낸다(만들지 않는다). HOME 은 절대 경로이고
# 있는 디렉터리여야 한다.
# Globals:
#   HOME
# Outputs:
#   루트 경로.
# Returns:
#   HOME 을 쓸 수 없으면 2(거부를 알린다).
#######################################
evidence::root() {
  local home
  if [[ "${HOME:-}" != /* ]]; then
    evidence::reject "HOME 이 절대 경로가 아님"
    return
  fi
  home="$(cd -P -- "${HOME}" 2>/dev/null && pwd -P)" || {
    evidence::reject "HOME 이 없음"
    return
  }
  printf '%s/%s\n' "${home%/}" "${EVIDENCE_ROOT_NAME}"
}

#######################################
# 디렉터리와 그 조상에 저장소 · 볼트 표지가 있는지 본다(링크 표지 포함).
# Arguments:
#   정규화한 디렉터리 경로.
# Returns:
#   표지가 없으면 0, 있으면 2(거부를 알린다).
#######################################
evidence::check_markers() {
  local dir="$1"
  while :; do
    if [[ -e "${dir}/.git" || -L "${dir}/.git" ]]; then
      evidence::reject "저장소 표지(.git)" "${dir}"
      return
    fi
    if [[ -e "${dir}/.obsidian" || -L "${dir}/.obsidian" ]]; then
      evidence::reject "볼트 표지(.obsidian)" "${dir}"
      return
    fi
    [[ -z "${dir}" || "${dir}" == / ]] && return 0
    dir="${dir%/*}"
  done
}

#######################################
# 디렉터리 권한이 정확히 0700 인지 본다(macOS · 리눅스 공통 수단).
# Arguments:
#   디렉터리.
# Returns:
#   0700 디렉터리면 0.
#######################################
evidence::is_private() {
  local found
  found="$(find "$1" -maxdepth 0 -type d -perm 0700 -print 2>/dev/null)" ||
    return 1
  [[ -n "${found}" ]]
}

#######################################
# 있는 디렉터리가 증거 디렉터리로 쓸 수 있는지 검사한다 — 루트 자신 또는
# 루트 아래. 구성 요소마다 링크 · 0700 을 보고, 조상의 표지를 본다.
# 앞 결과(승인 기준)를 읽을 때도 이 검사를 통과해야 한다.
# Arguments:
#   디렉터리 경로(evidence::root 가 낸 루트로 시작해야 한다).
# Returns:
#   쓸 수 있으면 0, 아니면 2(거부를 알린다).
#######################################
evidence::check_dir() {
  local path="$1" root rel component current real
  root="$(evidence::root)" || return 2
  if [[ "${path}" != "${root}" && "${path}" != "${root}/"* ]]; then
    evidence::reject "루트 밖" "${path}"
    return
  fi
  current="${root}"
  rel="${path#"${root}"}"
  rel="${rel#/}"
  while :; do
    if [[ -L "${current}" ]]; then
      evidence::reject "심볼릭 링크" "${current}"
      return
    fi
    if ! evidence::is_private "${current}"; then
      evidence::reject "0700 디렉터리가 아님" "${current}"
      return
    fi
    [[ -z "${rel}" ]] && break
    component="${rel%%/*}"
    if [[ "${component}" == . || "${component}" == .. ]]; then
      evidence::reject "루트 밖" "${path}"
      return
    fi
    current="${current}/${component}"
    if [[ "${rel}" == */* ]]; then
      rel="${rel#*/}"
    else
      rel=""
    fi
  done
  real="$(cd -P -- "${path}" 2>/dev/null && pwd -P)" || {
    evidence::reject "디렉터리가 없음" "${path}"
    return
  }
  if [[ "${real}" != "${root}" && "${real}" != "${root}/"* ]]; then
    evidence::reject "루트 밖" "${path}"
    return
  fi
  evidence::check_markers "${real}"
}

#######################################
# 고정 루트(없으면 0700 으로 만듦) 아래에 「<이름>-<UTC 시각>」 0700 하위
# 디렉터리를 새로 만든다. 같은 초에 이미 있으면 「-2」 ~ 「-9」 를 붙인다.
# 루트 · 조상 검사를 통과하기 전에는 아무것도 만들지 않는다.
# Arguments:
#   이름(도구 접두로 시작 — 영문 · 숫자 · 「-」, 머리 주석 「이름 규약」).
# Outputs:
#   새 디렉터리 경로.
# Returns:
#   0, 또는 거부 · 실패면 2(거부를 알린다).
#######################################
evidence::new_dir() {
  local label="$1" root stamp dir suffix
  if ! [[ "${label}" =~ ${EVIDENCE_LABEL_PATTERN} ]]; then
    evidence::reject "루트 밖으로 나갈 수 있는 이름" "${label}"
    return
  fi
  root="$(evidence::root)" || return 2
  evidence::check_markers "${root%/*}" || return 2
  if [[ -L "${root}" ]]; then
    evidence::reject "심볼릭 링크" "${root}"
    return
  fi
  if [[ ! -e "${root}" ]] && ! mkdir -m 700 -- "${root}" 2>/dev/null; then
    evidence::reject "루트를 만들지 못함" "${root}"
    return
  fi
  evidence::check_dir "${root}" || return 2
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')" || {
    evidence::reject "시각을 읽지 못함"
    return
  }
  dir="${root}/${label}-${stamp}"
  suffix=1
  while ! mkdir -m 700 -- "${dir}" 2>/dev/null; do
    suffix=$((suffix + 1))
    if [[ "${suffix}" -gt 9 ]]; then
      evidence::reject "디렉터리를 만들지 못함" "${root}/${label}-${stamp}"
      return
    fi
    dir="${root}/${label}-${stamp}-${suffix}"
  done
  evidence::check_dir "${dir}" || return 2
  printf '%s\n' "${dir}"
}
