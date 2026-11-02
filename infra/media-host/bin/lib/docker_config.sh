# shellcheck shell=bash
#
# 시작 스크립트의 선행 점검 3 — DOCKER_CONFIG 의 config.json 최상위
# credHelpers 객체에 ECR 레지스트리 호스트 키가 있고 그 값이 ecr-login 인지
# 본다. jq 가 상자에 있다는 보장이 없어 bash 로 훑는다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). 상자(AL2023 · bash 5)와 kty
# 로컬(macOS · bash 3.2) 양쪽에서 돈다.

# 선행 점검이 읽는 config.json 의 크기 상한(바이트) — 넘으면 점검 실패.
readonly DOCKER_CONFIG_MAX_BYTES=65536

#######################################
# 지금 자리에 JSON 값이 올 수 있는지 본다(json_scan_helpers 의 도우미).
# Arguments:
#   여는 괄호 쌓기(예: "{["), 바로 앞 토큰 종류.
#######################################
json_value_slot() {
  local stack="$1" prev="$2"
  if [[ -z "${stack}" ]]; then
    [[ "${prev}" == start ]]
    return
  fi
  case "${stack: -1}" in
    '{') [[ "${prev}" == ':' ]] ;;
    *) [[ "${prev}" == '[' || "${prev}" == ',' ]] ;;
  esac
}

#######################################
# config.json 내용을 토큰 단위로 훑어, 최상위 객체의 credHelpers 객체에 호스트
# 키가 정확히 한 번 있고 그 값이 문자열 "ecr-login" 인지 본다. jq 가 상자에 있다는
# 보장이 없어 bash 로 훑는다.
#
# 구조(괄호 짝 · 키와 값 자리 · 쉼표)가 어긋나면 실패다. 한계 — 이스케이프(\)가
# 든 최상위 키 · credHelpers 안의 키 · 값은 풀지 않고 실패로 본다(fail closed).
# 그 밖 자리의 문자열(예: auths 의 값)은 이스케이프가 있어도 건너뛴다. 같은 키가
# 두 번 나오는 것(credHelpers · 호스트)도 실패다.
# Arguments:
#   config.json 내용, 호스트.
# Returns:
#   0 있음 / 1 없음 · 형식 오류.
#######################################
json_scan_helpers() {
  local rest="$1" host="$2" stack="" prev=start key1="" hkey="" raw depth
  local in_helpers=0 helpers_seen=0 host_seen=0 matched=0
  # JSON 공백은 넷뿐이다(SP · HT · LF · CR) — [[:space:]] 는 VT · FF 도 받는다.
  local ws_re=$'^[ \t\n\r]+'
  # 문자열 · 숫자는 JSON 문법 그대로다 — 이스케이프는 \" \\ \/ \b \f \n \r \t
  # \uXXXX 만, 제어 문자는 이스케이프해야만, 숫자는 앞 0 · 빈 소수부 · 빈
  # 지수가 없다. 그 밖은 형식 오류다(fail closed).
  local str_re='^"(([^"\\[:cntrl:]]|\\["\\/bfnrt]|\\u[0-9A-Fa-f]{4})*)"'
  local num_re='-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?'
  local lit_re="^(true|false|null|${num_re})"
  local LC_ALL=C
  while :; do
    if [[ "${rest}" =~ ${ws_re} ]]; then
      rest="${rest:${#BASH_REMATCH[0]}}"
    fi
    [[ -n "${rest}" ]] || break
    depth=${#stack}
    case "${rest:0:1}" in
      '{' | '[')
        json_value_slot "${stack}" "${prev}" || return 1
        [[ -n "${stack}" || "${rest:0:1}" == '{' ]] || return 1
        if [[ "${rest:0:1}" == '{' && "${stack}" == '{' \
          && "${key1}" == credHelpers ]]; then
          in_helpers=1
        fi
        stack+="${rest:0:1}"
        prev="${rest:0:1}"
        rest="${rest:1}"
        ;;
      '}' | ']')
        (( depth > 0 )) || return 1
        if [[ "${rest:0:1}" == '}' ]]; then
          [[ "${stack: -1}" == '{' ]] || return 1
          [[ "${prev}" == '{' || "${prev}" == v ]] || return 1
        else
          [[ "${stack: -1}" == '[' ]] || return 1
          [[ "${prev}" == '[' || "${prev}" == v ]] || return 1
        fi
        (( depth != 2 )) || in_helpers=0
        stack="${stack%?}"
        prev=v
        rest="${rest:1}"
        ;;
      ':')
        [[ "${prev}" == k ]] || return 1
        prev=':'
        rest="${rest:1}"
        ;;
      ',')
        [[ "${prev}" == v && -n "${stack}" ]] || return 1
        prev=','
        rest="${rest:1}"
        ;;
      '"')
        [[ "${rest}" =~ ${str_re} ]] || return 1
        raw="${BASH_REMATCH[1]}"
        rest="${rest:${#BASH_REMATCH[0]}}"
        if [[ "${stack: -1}" == '{' && ( "${prev}" == '{' \
          || "${prev}" == ',' ) ]]; then
          prev=k
          if (( depth == 1 || in_helpers )) && [[ "${raw}" == *\\* ]]; then
            return 1
          fi
          if (( depth == 1 )); then
            key1="${raw}"
            [[ "${raw}" != credHelpers ]] || helpers_seen=$((helpers_seen + 1))
          elif (( in_helpers && depth == 2 )); then
            hkey="${raw}"
            [[ "${raw}" != "${host}" ]] || host_seen=$((host_seen + 1))
          fi
        else
          json_value_slot "${stack}" "${prev}" || return 1
          prev=v
          if (( in_helpers )) && [[ "${raw}" == *\\* ]]; then
            return 1
          fi
          if (( in_helpers && depth == 2 )) && [[ "${hkey}" == "${host}" \
            && "${raw}" == ecr-login ]]; then
            matched=$((matched + 1))
          fi
        fi
        ;;
      *)
        [[ "${rest}" =~ ${lit_re} ]] || return 1
        json_value_slot "${stack}" "${prev}" || return 1
        prev=v
        rest="${rest:${#BASH_REMATCH[0]}}"
        ;;
    esac
  done
  [[ -z "${stack}" && "${prev}" == v ]] || return 1
  (( helpers_seen == 1 && host_seen == 1 && matched == 1 ))
}

#######################################
# 선행 점검 3 — config.json 의 최상위 credHelpers 객체에 ECR 레지스트리 호스트
# 키가 있고 그 값이 ecr-login 이다.
# Globals:
#   ECR_REGISTRY_HOST
# Arguments:
#   config.json 경로.
#######################################
check_docker_config() {
  local host="${ECR_REGISTRY_HOST:-}" config size kept LC_ALL=C
  [[ -n "${host}" && -f "$1" && -r "$1" ]] || return 1
  # 크기 · NUL 은 원본 바이트로 본다 — 명령 치환은 NUL 을 지워, 지운 뒤의 내용이
  # 상한 안 · 정상 JSON 으로 보일 수 있다. NUL 이 하나라도 있으면 거부한다.
  size="$(wc -c <"$1")" || return 1
  size=$((size + 0))
  (( size <= DOCKER_CONFIG_MAX_BYTES )) || return 1
  kept="$(tr -d '\000' <"$1" | wc -c)" || return 1
  (( kept + 0 == size )) || return 1
  # 끝 줄바꿈도 크기에 넣는다 — 명령 치환이 지우지 않게 표식 글자를 붙였다가
  # 뗀다. 원본과 길이가 다르면(그 사이 바뀜) 거부한다.
  config="$(head -c "$((DOCKER_CONFIG_MAX_BYTES + 1))" -- "$1" && printf x)" \
    || return 1
  config="${config%x}"
  (( ${#config} == size )) || return 1
  json_scan_helpers "${config}" "${host}"
}
