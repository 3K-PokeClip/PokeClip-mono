#!/usr/bin/env bash
#
# Media 상자 시작 스크립트 — systemd 유닛 pokeclip-media 의 ExecStart.
#
# 순서:
#   1. 선행 점검 세 항목(compose 판 · ECR 자격 도우미 · DOCKER_CONFIG 의
#      credHelpers)을 매번 같은 순서로 모두 보고 결과 줄을 낸다. 하나라도
#      실패하면 「MEDIA PREREQ FAIL <항목>」 을 남기고 끝낸다.
#   2. CDN 비밀(MediaMTX hlsCDNSecret)을 Secrets Manager 에서 읽는다(argv 에는
#      ARN 만). 실패 · 빈 값 · 허용 문자([A-Za-z0-9._~+/=-]) 밖의 글자(줄바꿈
#      포함)가 있으면 「MEDIA START BLOCKED: CDN secret unavailable」. 템플릿은
#      토큰을 YAML 큰따옴표 안에 둔다 — 허용 문자는 그 안에서 이스케이프가 필요
#      없다.
#   3. MediaMTX 설정 템플릿의 고정 토큰 한 곳을 그 값으로 채워 tmpfs
#      (RUNTIME_DIR)에 렌더한다. 같은 디렉터리의 임시 파일(0400 · MediaMTX 실행
#      계정 소유)에 쓴 뒤 rename 한다 — 실행 중인 스택이 보는 옛 파일을 제자리에서
#      고쳐 쓰지 않는다. 치환은 bash 내장 문자열 연산으로만 해서 값이 외부 명령의
#      argv 에 오르지 않는다.
#   4. docker compose up 으로 바뀐다(exec). 출력은 mediamtx · nginx 에만 붙는다
#      — 둘 중 하나가 끝나면 compose 전체가 끝나고 systemd 가 다시 띄운다.
#      나머지 서비스(사이드카 등)는 엔진 재시작 정책(on-failure)으로 혼자 다시
#      뜬다.
#
# 인자는 받지 않는다. 점검을 건너뛰는 플래그 · 변수는 없다 — 시험은 아래 변수의
# 값만 바꾼다.
#
# 읽는 변수(prod 값은 유닛과 /etc/pokeclip-media/media.env 가 정한다):
#   MEDIA_EXPECTED_COMPOSE_VERSION  필수. `docker compose version --short` 와 같아야
#                                   한다.
#   DOCKER_CONFIG                   기본 /root/.docker. 점검한 경로를 docker 도
#                                   쓰도록 기본값을 채워 export 한다.
#   ECR_REGISTRY_HOST               필수. config.json credHelpers 의 키.
#   CDN_SECRET_ARN                  필수. 비밀 ARN(값이 아니다).
#   MEDIA_MTX_TEMPLATE              기본 /etc/pokeclip-media/mediamtx.yml.tmpl.
#   RUNTIME_DIR                     기본 /run/pokeclip-media(절대 경로).
#   MTX_UID · MTX_GID               기본 10002 · 10002(MediaMTX 이미지 USER).
#   MEDIA_COMPOSE_PROJECT           기본 pokeclip-media.
#   MEDIA_COMPOSE_FILE              기본 /etc/pokeclip-media/compose.yml.
#
# 출력: 판정 줄(MEDIA PREREQ OK|FAIL · MEDIA START BLOCKED · MEDIA START FAIL)만
# 낸다. 비밀 값은 어디에도 쓰지 않는다.

set -euo pipefail
# 부모가 넘긴 셸 옵션(SHELLOPTS)으로 xtrace · allexport 가 켜져 있어도 끈다 —
# allexport 면 비밀을 담은 변수가 자식 환경으로 나간다.
set +x +a
umask 077
# 비밀이 든 메모리가 core 파일로 남지 않게 한다.
ulimit -c 0
# 비밀 · 렌더 내용을 담는 변수 이름이 부모 환경에서 export 된 채 들어오면, bash
# 의 local 이 그 export 속성을 이어받아 자식(chown 등) 환경에 값이 실린다. 쓰기
# 전에 그 이름들을 환경에서 지운다.
unset -v secret rendered value content head tail

readonly SECRET_TOKEN='@@HLS_CDN_SECRET@@'
# CDN 비밀이 가질 수 있는 글자(YAML 큰따옴표 안에서 이스케이프가 필요 없는 것).
readonly SECRET_PATTERN='^[A-Za-z0-9._~+/=-]+$'
readonly RENDER_NAME='mediamtx.yml'
# compose 출력이 붙는 서비스 — 템플릿에서 restart: "no" 인 집합과 같아야 한다.
readonly ATTACHED_SERVICES=(mediamtx nginx)
# 선행 점검이 읽는 config.json 의 크기 상한(바이트) — 넘으면 점검 실패.
readonly DOCKER_CONFIG_MAX_BYTES=65536

render_tmp=""
render_step=""

#######################################
# 판정 줄을 표준 출력에 낸다.
# Arguments:
#   줄.
#######################################
say() {
  printf '%s\n' "$1"
}

#######################################
# 끝날 때 남은 임시 렌더 파일을 지운다(EXIT 트랩).
# Globals:
#   render_tmp
#######################################
remove_render_tmp() {
  if [[ -n "${render_tmp}" ]]; then
    rm -f -- "${render_tmp}"
  fi
}

#######################################
# 선행 점검 1 — compose 판이 기대 판과 같다(기대 판이 없으면 실패).
# Globals:
#   MEDIA_EXPECTED_COMPOSE_VERSION
#######################################
check_compose_version() {
  local expected="${MEDIA_EXPECTED_COMPOSE_VERSION:-}" actual
  [[ -n "${expected}" ]] || return 1
  actual="$(docker compose version --short 2>/dev/null)" || return 1
  [[ "${actual}" == "${expected}" ]]
}

#######################################
# 선행 점검 2 — PATH 에 ECR 자격 도우미가 있다.
#######################################
check_credential_helper() {
  command -v docker-credential-ecr-login >/dev/null 2>&1
}

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
  local ws_re='^[[:space:]]+' str_re='^"(([^"\\]|\\.)*)"'
  local lit_re='^(true|false|null|-?[0-9][0-9.eE+-]*)'
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
  local host="${ECR_REGISTRY_HOST:-}" config LC_ALL=C
  [[ -n "${host}" && -f "$1" && -r "$1" ]] || return 1
  config="$(head -c "$((DOCKER_CONFIG_MAX_BYTES + 1))" -- "$1")" || return 1
  (( ${#config} <= DOCKER_CONFIG_MAX_BYTES )) || return 1
  json_scan_helpers "${config}" "${host}"
}

#######################################
# 선행 점검 세 항목을 정한 순서로 모두 보고 결과 줄을 낸다. DOCKER_CONFIG 는
# main 이 기본값을 채워 둔다.
# Globals:
#   DOCKER_CONFIG
# Returns:
#   0 모두 통과 / 1 하나라도 실패.
#######################################
run_prereqs() {
  local failed=0 config="${DOCKER_CONFIG}/config.json"
  if check_compose_version; then
    say 'MEDIA PREREQ OK compose-version'
  else
    say 'MEDIA PREREQ FAIL compose-version'
    failed=1
  fi
  if check_credential_helper; then
    say 'MEDIA PREREQ OK ecr-credential-helper'
  else
    say 'MEDIA PREREQ FAIL ecr-credential-helper'
    failed=1
  fi
  # 경로는 비밀이 아니다 — 어느 파일을 봤는지 운영자가 알게 남긴다.
  if check_docker_config "${config}"; then
    say "MEDIA PREREQ OK docker-config ${config}"
  else
    say "MEDIA PREREQ FAIL docker-config ${config}"
    failed=1
  fi
  return "${failed}"
}

#######################################
# CDN 비밀을 읽는다. aws CLI 의 text 출력이 붙이는 끝 줄바꿈 하나만 떼고, 값
# 자체의 끝 줄바꿈은 남겨 둔다(명령 치환이 끝 줄바꿈을 지우지 않게 표식 글자를
# 붙였다가 뗀다). 쓸 수 없는 값(빈 값 · None · 허용 문자 밖의 글자 — 줄바꿈 ·
# 공백 · YAML 기호 포함)도 실패다.
# Globals:
#   CDN_SECRET_ARN
# Outputs:
#   값(성공했을 때만).
#######################################
read_cdn_secret() {
  local arn="${CDN_SECRET_ARN:-}" value
  [[ -n "${arn}" ]] || return 1
  value="$(aws secretsmanager get-secret-value --secret-id "${arn}" \
    --query SecretString --output text && printf x)" || return 1
  value="${value%x}"
  value="${value%$'\n'}"
  if [[ "${value}" == None || ! "${value}" =~ ${SECRET_PATTERN} ]]; then
    return 1
  fi
  printf '%s' "${value}"
}

#######################################
# 템플릿의 토큰 한 곳을 값으로 바꾼 내용을 낸다. 토큰이 정확히 하나가 아니면
# 실패한다. bash 의 ${var/…/…} 는 판에 따라 바꿀 글자의 & 를 달리 읽으므로
# 쓰지 않고, 앞뒤를 잘라 잇는다.
# Arguments:
#   템플릿 경로, 값.
# Outputs:
#   렌더 내용(끝 줄바꿈 보존).
#######################################
render_template() {
  local content head tail
  # 끝 줄바꿈을 지키려고 표식 글자를 붙였다가 뗀다.
  content="$(cat -- "$1" && printf x)" || return 2
  content="${content%x}"
  head="${content%%"${SECRET_TOKEN}"*}"
  [[ "${head}" != "${content}" ]] || return 1
  tail="${content#*"${SECRET_TOKEN}"}"
  [[ "${tail}" != *"${SECRET_TOKEN}"* ]] || return 1
  printf '%s%s%s' "${head}" "$2" "${tail}"
}

#######################################
# 렌더 내용을 RUNTIME_DIR 에 MediaMTX 실행 계정 소유 0400 으로 둔다. 같은
# 디렉터리의 임시 파일에 쓰고 rename 한다. 디렉터리 권한(0711)은 매번 맞춘다.
# 서브셸에서 부르지 않는다 — 임시 파일 이름을 EXIT 트랩이 알아야 한다.
# Globals:
#   render_tmp, render_step, MTX_UID, MTX_GID
# Arguments:
#   RUNTIME_DIR, 렌더 내용.
# Returns:
#   0 둠 / 1 실패(단계 이름은 render_step).
#######################################
install_render() {
  local dir="$1" uid="${MTX_UID:-10002}" gid="${MTX_GID:-10002}"
  if [[ ! "${uid}" =~ ^[0-9]+$ || ! "${gid}" =~ ^[0-9]+$ ]]; then
    render_step='owner'
    return 1
  fi
  if ! mkdir -p -- "${dir}" || ! chmod 0711 "${dir}"; then
    render_step='runtime-dir'
    return 1
  fi
  render_tmp="$(mktemp "${dir}/.${RENDER_NAME}.XXXXXX")" \
    || { render_step='tmpfile'; return 1; }
  if ! printf '%s' "$2" >"${render_tmp}" || ! chmod 0400 "${render_tmp}"; then
    render_step='write'
    return 1
  fi
  if ! chown "${uid}:${gid}" "${render_tmp}"; then
    render_step='chown'
    return 1
  fi
  # 대상이 디렉터리(또는 그리로 가는 링크)면 mv 가 그 안으로 옮기고 성공한다 —
  # 일반 파일이 아닌 대상은 받지 않는다.
  if [[ -L "${dir}/${RENDER_NAME}" || ( -e "${dir}/${RENDER_NAME}" \
    && ! -f "${dir}/${RENDER_NAME}" ) ]]; then
    render_step='target'
    return 1
  fi
  if ! mv -f -- "${render_tmp}" "${dir}/${RENDER_NAME}"; then
    render_step='rename'
    return 1
  fi
  render_tmp=""
}

#######################################
# 비밀을 읽어 렌더한다. 실패 줄은 여기서 낸다.
# Globals:
#   MEDIA_MTX_TEMPLATE, RUNTIME_DIR, render_step
# Returns:
#   0 렌더함 / 1 막힘.
#######################################
prepare_config() {
  local template="${MEDIA_MTX_TEMPLATE:-/etc/pokeclip-media/mediamtx.yml.tmpl}"
  local dir="${RUNTIME_DIR:-/run/pokeclip-media}" secret rendered status=0
  if [[ "${dir}" != /?* ]]; then
    say 'MEDIA START FAIL render runtime-dir'
    return 1
  fi
  if ! secret="$(read_cdn_secret)"; then
    say 'MEDIA START BLOCKED: CDN secret unavailable'
    return 1
  fi
  rendered="$(render_template "${template}" "${secret}" && printf x)" \
    || status=$?
  unset secret
  if (( status == 2 )); then
    say 'MEDIA START FAIL render template'
    return 1
  elif (( status != 0 )); then
    say 'MEDIA START FAIL render template-token'
    return 1
  fi
  rendered="${rendered%x}"
  if ! install_render "${dir}" "${rendered}"; then
    unset rendered
    say "MEDIA START FAIL render ${render_step}"
    return 1
  fi
  unset rendered
}

main() {
  local project="${MEDIA_COMPOSE_PROJECT:-pokeclip-media}"
  local compose_file="${MEDIA_COMPOSE_FILE:-/etc/pokeclip-media/compose.yml}"
  local attach=() service
  if (( $# != 0 )); then
    say 'MEDIA START FAIL usage'
    exit 2
  fi
  # 점검한 config.json 과 docker 가 쓰는 것이 같도록 기본값을 채워 넘긴다.
  DOCKER_CONFIG="${DOCKER_CONFIG:-/root/.docker}"
  export DOCKER_CONFIG
  trap remove_render_tmp EXIT
  # 신호로 끝나도 EXIT 트랩이 돌아 임시 렌더 파일이 남지 않게 한다.
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if ! run_prereqs; then
    exit 1
  fi
  if ! prepare_config; then
    exit 1
  fi
  for service in "${ATTACHED_SERVICES[@]}"; do
    attach+=(--attach "${service}")
  done
  trap - EXIT INT TERM
  exec docker compose --ansi never -p "${project}" -f "${compose_file}" \
    up --abort-on-container-exit "${attach[@]}"
}

main "$@"
