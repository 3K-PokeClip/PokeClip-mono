#!/usr/bin/env bash
#
# Media 상자 시작 스크립트 — systemd 유닛 pokeclip-media 의 ExecStart.
#
# 순서:
#   1. 선행 점검 세 항목(compose 판 · ECR 자격 도우미 · DOCKER_CONFIG 의
#      credHelpers)을 매번 같은 순서로 모두 보고 결과 줄을 낸다. 하나라도
#      실패하면 「MEDIA PREREQ FAIL <항목>」 을 남기고 끝낸다.
#   2. CDN 비밀(MediaMTX hlsCDNSecret)을 Secrets Manager 에서 읽는다(argv 에는
#      ARN 만). 실패 · 빈 값 · 허용 문자([A-Za-z0-9._+=-]) 밖의 글자(줄바꿈
#      포함)가 있으면 「MEDIA START BLOCKED: CDN secret unavailable」. 허용
#      문자는 MediaMTX 가 hlsCDNSecret 에 받는 글자(/ · ~ 는 거부 — 기동 실패)와
#      YAML 큰따옴표 안에서 이스케이프가 필요 없는 글자의 교집합이다. 템플릿은
#      토큰을 YAML 큰따옴표 안에 둔다.
#   3. 사이드카가 MediaMTX 관리 API 에 붙을 비밀번호를 새로 만든다(/dev/urandom
#      32바이트 → 16진 64자). 저장하지 않는다 — 기동마다 바뀌고, MediaMTX 설정과
#      사이드카 파일에 같은 실행에서 같이 쓰이므로 둘이 어긋나지 않는다. 만들지
#      못하면 「MEDIA START BLOCKED: MTX API credential unavailable」.
#   4. MediaMTX 설정 템플릿(1단계 렌더본)의 토큰 집합으로 모드를 안다 —
#      media.env 로는 정하지 않는다. 토큰마다 나온 수가
#        - CDN 비밀 · API 비밀번호 각 1, 응답기 토큰 0 → 옛 모드(M2 내부 인증).
#        - CDN 비밀 · Basic 비밀번호 · SRT 송출 잠금 · SRT 읽기 잠금 각 1, API
#          비밀번호 0 → 응답기 모드(송출 인증 응답기).
#      그 밖이면 「MEDIA START FAIL render template-token」. 모드 판정 줄은 따로
#      내지 않는다.
#   5. compose 렌더본(MEDIA_COMPOSE_FILE)의 응답기 서비스 키 줄(`  ingest-auth:`)
#      수가 모드와 맞는지 본다 — 응답기 모드 ⇔ 1, 옛 모드 ⇔ 0. 아니면 「MEDIA
#      START BLOCKED: ingest-auth mode mismatch」. 읽을 수 없는 파일은 0 으로
#      센다(옛 모드는 compose up 이 그 파일로 실패를 드러낸다). 응답기 모드의
#      외부 입력(6)을 받기 전에 본다.
#   6. 응답기 모드만(옛 모드는 아래 키 · 파일을 읽지 않는다):
#      - compose 파일 옆 ingest-auth.env 가 링크 아닌 일반 파일이고 내용이
#        정확히 INGEST_AUTH_IP_LIMIT=on 또는 =off 한 줄인지 본다(없으면 compose
#        의 required env_file 때문에 판정 줄 없이 재시작이 되풀이된다). 아니면
#        「MEDIA START BLOCKED: ingest-auth ip-limit setting unavailable」.
#      - 생성 셋(Basic 비밀번호 · 잠금 둘 — 각각 3 과 같은 꼴의 새 값)을
#        만든다. 저장하지 않는다. 하나라도 만들지 못하면 「MEDIA START
#        BLOCKED: generated credential unavailable」.
#      - 4C 토큰(공용 창구 INTERNAL_API_TOKEN)을 Secrets Manager 에서 읽는다
#        (argv 에는 ARN 만). ARN 빔 · 실패 · 빈 값 · None · 제어 문자(NUL ·
#        C1 포함) · 앞뒤 공백 · 파일(값 + 줄바꿈)이 4096바이트 초과면 「MEDIA
#        START BLOCKED: ingest-auth resolve token unavailable」.
#      - 거부 목록을 SSM 파라미터에서 받아(argv 에는 이름만) 거부 목록
#        디렉터리의 임시 파일에 바이트 그대로 쓴다. 이름 빔 · 받기 실패면
#        「… ingest-auth denylist unavailable」, 꼴 밖(lib/deny_tokens.sh)이면
#        「… ingest-auth denylist invalid」. 꼴이 맞으면 rename 으로 둔다.
#   7. 모드의 토큰(각각 정확히 한 번)을 채워 tmpfs(RUNTIME_DIR)의
#      mediamtx.yml(MediaMTX 실행 계정 소유 0400)로, API 비밀번호 한 줄을
#      mtx_api_pass(사이드카 실행 계정 소유 0400)로 둔다. 응답기 모드면
#      RUNTIME_DIR/ingest-auth(root 0711)에 Basic 비밀번호 한 줄(ingest_basic_pass)
#      과 같은 API 비밀번호 한 줄(mtx_api_pass) · 4C 토큰 한 줄
#      (stream_key_resolve_token)을 응답기 실행 계정 소유 0400 으로 더 두고, 그
#      안의 상태 디렉터리(state)를 응답기 실행 계정 소유 0700 으로 맞춘다. 잠금 둘은 mediamtx.yml 에만 있고, 응답기 모드 mediamtx.yml 에는
#      API 비밀번호가 없다(그 토큰이 없다). 모든 파일을 각 디렉터리의 임시
#      파일에 다 쓰고 대상을 모두 점검한 뒤에야 차례로 rename 한다 — 실행 중인
#      스택이 보는 옛 파일을 제자리에서 고쳐 쓰지 않는다. 중간에 실패하면 새 임시
#      파일은 남기지 않는다(EXIT 트랩). rename 전에 실패하면 이미 있던 파일은
#      그대로 두고, rename 하나가 실패하면 이번에 옮긴 파일을 지워 짝이 어긋난
#      설정을 남기지 않는다. 치환은 bash 내장 문자열 연산으로만 해서 값이 외부
#      명령의 argv 에 오르지 않는다.
#   8. docker compose up 으로 바뀐다(exec). 출력은 mediamtx · nginx 에만 붙는다
#      — 둘 중 하나가 끝나면 compose 전체가 끝나고 systemd 가 다시 띄운다.
#      나머지 서비스(사이드카 등)는 엔진 재시작 정책(on-failure)으로 혼자 다시
#      뜬다. 컨테이너는 늘 새로 만든다(--force-recreate) — 정지가 compose 를
#      내리지 못해 남은 컨테이너는 지운 옛 렌더(옛 CDN 비밀 · 옛 API
#      비밀번호)를 붙들고 있어, 다시 쓰면 새 렌더가 반영되지 않는다. 렌더에서
#      빠진 서비스(비운 이미지 변수 · 끈 janitor)의 옛 컨테이너도 지운다
#      (--remove-orphans) — 남으면 옛 비밀을 든 채 같은 망 칸에 붙어 있다.
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
#   SIDECAR_UID · SIDECAR_GID       기본 10001 · 10001(사이드카 이미지 USER).
#   INGEST_AUTH_UID · INGEST_AUTH_GID
#                                   기본 10003 · 10003(응답기 이미지 USER — 응답기
#                                   모드만 쓴다).
#   MEDIA_COMPOSE_PROJECT           기본 pokeclip-media.
#   MEDIA_COMPOSE_FILE              기본 /etc/pokeclip-media/compose.yml. 옆의
#                                   ingest-auth.env 도 본다(응답기 모드).
#   INTERNAL_API_TOKEN_SECRET_ARN   응답기 모드 필수. 4C 토큰 비밀 ARN.
#   INGEST_AUTH_DENYLIST_PARAM      응답기 모드 필수. 거부 목록 SSM 파라미터
#                                   이름.
#   INGEST_AUTH_DENY_DIR            기본 /var/lib/pokeclip-media/ingest-auth/deny
#                                   (절대 경로 — 응답기 모드만).
#
# 출력: 판정 줄(MEDIA PREREQ OK|FAIL · MEDIA START BLOCKED · MEDIA START FAIL)만
# 낸다. 비밀 값은 어디에도 쓰지 않는다.

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail
# 부모가 넘긴 셸 옵션(SHELLOPTS)으로 xtrace · allexport 가 켜져 있어도 끈다 —
# allexport 면 비밀을 담은 변수가 자식 환경으로 나간다.
set +x +a
umask 077
BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BIN_DIR
# shellcheck source=lib/docker_config.sh
source "${BIN_DIR}/lib/docker_config.sh"
# shellcheck source=lib/deny_tokens.sh
source "${BIN_DIR}/lib/deny_tokens.sh"
# shellcheck source=lib/render_files.sh
source "${BIN_DIR}/lib/render_files.sh"
# 비밀이 든 메모리가 core 파일로 남지 않게 한다.
ulimit -c 0
# 비밀 · 렌더 내용을 담는 변수 이름이 부모 환경에서 export 된 채 들어오면, bash
# 의 local 이 그 export 속성을 이어받아 자식(chown 등) 환경에 값이 실린다. 쓰기
# 전에 그 이름들을 환경에서 지운다.
unset -v secret rendered value content head tail api_pass values basic_pass \
  publish_lock read_lock file_bodies template_body resolve_token

readonly SECRET_TOKEN='@@HLS_CDN_SECRET@@'
# CDN 비밀이 가질 수 있는 글자 — MediaMTX 평문 자격 글자(hlsCDNSecret 검사)와
# YAML 큰따옴표 안에서 이스케이프가 필요 없는 글자의 교집합.
readonly SECRET_PATTERN='^[A-Za-z0-9._+=-]+$'
readonly API_PASS_TOKEN='@@MTX_API_PASS@@'
# 응답기 모드 토큰 — 호출자 Basic 비밀번호(authHTTPAddress 의 사용자 정보) ·
# SRT 송출 잠금 · SRT 읽기 잠금.
readonly BASIC_PASS_TOKEN='@@INGEST_AUTH_BASIC_PASS@@'
readonly PUBLISH_LOCK_TOKEN='@@SRT_PUBLISH_LOCK@@'
readonly READ_LOCK_TOKEN='@@SRT_READ_LOCK@@'
# 모드를 가르는 토큰과, 모드마다 그 토큰이 나와야 하는 수(같은 차례).
readonly MODE_TOKENS=("${SECRET_TOKEN}" "${API_PASS_TOKEN}" \
  "${BASIC_PASS_TOKEN}" "${PUBLISH_LOCK_TOKEN}" "${READ_LOCK_TOKEN}")
readonly LEGACY_TOKEN_COUNTS='1 1 0 0 0'
readonly INGEST_AUTH_TOKEN_COUNTS='1 0 1 1 1'
# 만드는 비밀(API 비밀번호 · 응답기 모드 생성 셋)의 꼴 — MediaMTX 평문 pass ·
# SRT passphrase(10–79자) 허용 글자 안이고, 작은따옴표 안 · URL 사용자 정보
# 안에서 이스케이프가 필요 없다.
readonly CREDENTIAL_PATTERN='^[0-9a-f]{64}$'
readonly CREDENTIAL_BYTES=32
readonly RENDER_NAME='mediamtx.yml'
readonly API_PASS_NAME='mtx_api_pass'
# 응답기 모드 파일 자리(RUNTIME_DIR 아래 디렉터리)와 Basic 비밀번호 파일 이름.
# 응답기의 API 비밀번호 파일 이름은 사이드카와 같다(API_PASS_NAME).
readonly INGEST_AUTH_DIR_NAME='ingest-auth'
readonly BASIC_PASS_NAME='ingest_basic_pass'
# 응답기 디렉터리 안의 4C 토큰 파일 · 상태 디렉터리 이름과 4C 토큰 파일 크기
# 상한(바이트 — 응답기 secretfile 의 상한과 같다).
readonly RESOLVE_TOKEN_NAME='stream_key_resolve_token'
readonly STATE_DIR_NAME='state'
readonly RESOLVE_TOKEN_MAX_BYTES=4096
# compose 파일 옆 IP 한도 env 파일과 compose 렌더본의 응답기 서비스 키 줄.
readonly IP_LIMIT_ENV_NAME='ingest-auth.env'
readonly INGEST_AUTH_SERVICE_LINE='  ingest-auth:'
# compose 출력이 붙는 서비스 — 템플릿에서 restart: "no" 인 집합과 같아야 한다.
readonly ATTACHED_SERVICES=(mediamtx nginx)

render_tmps=()
staged_tmp=""
render_step=""
# 1단계 렌더본 템플릿 내용(비밀 없음)과 그 토큰 집합으로 정한 모드
# (legacy | ingest-auth).
template_body=""
render_mode=""
# 실행 계정 소유 값(uid:gid) — set_owners 가 채운다.
mtx_owner=""
sidecar_owner=""
ingest_auth_owner=""
# 이번 기동에 둘 파일 목록 — 같은 칸끼리 한 파일(최종 경로 · 소유 · 실패 단계
# 이름 앞머리 · 내용). add_file 이 더하고 install_files 가 둔다.
file_paths=()
file_owners=()
file_steps=()
file_bodies=()

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
#   render_tmps
#######################################
remove_render_tmp() {
  local f
  for f in ${render_tmps[@]+"${render_tmps[@]}"}; do
    rm -f -- "${f}"
  done
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
# 붙였다가 뗀다). 값의 NUL 은 bash 변수에서 사라지므로 담기 전에 다른 제어
# 문자로 바꿔 허용 문자 검사에 걸리게 한다. 쓸 수 없는 값(빈 값 · None · 허용
# 문자 밖의 글자 — 줄바꿈 · 공백 · YAML 기호 · NUL 포함)도 실패다.
# Globals:
#   CDN_SECRET_ARN
# Outputs:
#   값(성공했을 때만).
#######################################
read_cdn_secret() {
  local arn="${CDN_SECRET_ARN:-}" value
  [[ -n "${arn}" ]] || return 1
  value="$({ aws secretsmanager get-secret-value --secret-id "${arn}" \
    --query SecretString --output text && printf x; } \
    | LC_ALL=C tr '\000' '\001')" || return 1
  [[ "${value}" == *x ]] || return 1
  value="${value%x}"
  value="${value%$'\n'}"
  if [[ "${value}" == None || ! "${value}" =~ ${SECRET_PATTERN} ]]; then
    return 1
  fi
  printf '%s' "${value}"
}

#######################################
# 비밀 하나(사이드카 API 비밀번호 · 응답기 모드 생성 셋)를 새로 만든다 —
# /dev/urandom 32바이트를 16진 64자로. 값은 명령 치환으로만 받는다(od · tr 의
# argv 에 오르지 않는다).
# Outputs:
#   값(꼴이 맞을 때만).
#######################################
generate_credential() {
  local value
  value="$(od -A n -N "${CREDENTIAL_BYTES}" -t x1 /dev/urandom \
    | tr -d ' \n')" || return 1
  [[ "${value}" =~ ${CREDENTIAL_PATTERN} ]] || return 1
  printf '%s' "${value}"
}

#######################################
# MediaMTX 설정 템플릿을 읽어 template_body 에 둔다(끝 줄바꿈을 지키려고 표식
# 글자를 붙였다가 뗀다). 1단계 렌더본이라 비밀이 없다.
# Globals:
#   template_body
# Arguments:
#   템플릿 경로.
#######################################
load_template() {
  template_body="$(cat -- "$1" && printf x)" || return 1
  template_body="${template_body%x}"
}

#######################################
# 내용 안에 토큰이 몇 번 나오는지 왼쪽부터 겹치지 않게 센다. 토큰 앞뒤에 @ 가
# 더 붙어 있어도(@@@토큰@@@) 토큰 하나로 센다.
# Arguments:
#   내용, 토큰.
# Outputs:
#   수.
#######################################
count_token() {
  local rest="$1" n=0
  while [[ "${rest}" == *"$2"* ]]; do
    rest="${rest#*"$2"}"
    n=$((n + 1))
  done
  printf '%s' "${n}"
}

#######################################
# template_body 의 토큰 집합으로 모드를 정해 render_mode 에 둔다 — 토큰마다
# 나온 수가 옛 모드 꼴이면 legacy, 응답기 모드 꼴이면 ingest-auth. 부분 집합 ·
# 섞임 · 두 번은 어느 꼴도 아니다.
# Globals:
#   template_body, render_mode
# Returns:
#   0 / 1 어느 꼴도 아님.
#######################################
detect_mode() {
  local token counts=""
  for token in "${MODE_TOKENS[@]}"; do
    counts+="${counts:+ }$(count_token "${template_body}" "${token}")"
  done
  case "${counts}" in
    "${LEGACY_TOKEN_COUNTS}") render_mode=legacy ;;
    "${INGEST_AUTH_TOKEN_COUNTS}") render_mode=ingest-auth ;;
    *) return 1 ;;
  esac
}

#######################################
# template_body 의 토큰을 각 값으로 바꾼 내용을 낸다. 토큰마다 정확히 한 번
# 있어야 한다(모드 판정이 본 것을 바꾸기 직전에 다시 본다). 값의 허용 글자에는
# @ 가 없어 바꾼 값 안에 토큰이 생기지 않는다. bash 의 ${var/…/…} 는 판에 따라
# 바꿀 글자의 & 를 달리 읽으므로 쓰지 않고, 앞뒤를 잘라 잇는다.
# Globals:
#   template_body
# Arguments:
#   토큰, 값 쌍 ….
# Outputs:
#   렌더 내용(끝 줄바꿈 보존).
# Returns:
#   0 / 1 토큰 수가 틀림.
#######################################
render_template() {
  local content="${template_body}" head tail
  while (( $# >= 2 )); do
    head="${content%%"$1"*}"
    [[ "${head}" != "${content}" ]] || return 1
    tail="${content#*"$1"}"
    [[ "${tail}" != *"$1"* ]] || return 1
    content="${head}$2${tail}"
    shift 2
  done
  printf '%s' "${content}"
}

#######################################
# 실행 계정 소유 값(uid:gid)을 변수에서 정한다. 꼴은 install 직전에 본다
# (check_file_owners — 이번 모드가 쓰는 소유만).
# Globals:
#   mtx_owner, sidecar_owner, ingest_auth_owner, MTX_UID, MTX_GID,
#   SIDECAR_UID, SIDECAR_GID, INGEST_AUTH_UID, INGEST_AUTH_GID
#######################################
set_owners() {
  mtx_owner="${MTX_UID:-10002}:${MTX_GID:-10002}"
  sidecar_owner="${SIDECAR_UID:-10001}:${SIDECAR_GID:-10001}"
  ingest_auth_owner="${INGEST_AUTH_UID:-10003}:${INGEST_AUTH_GID:-10003}"
}

#######################################
# RUNTIME_DIR 을 만들고 권한(0711)을 매번 맞춘다.
# Globals:
#   render_step
# Arguments:
#   RUNTIME_DIR.
#######################################
prepare_runtime_dir() {
  if ! mkdir -p -- "$1" || ! chmod 0711 "$1"; then
    render_step='runtime-dir'
    return 1
  fi
}

#######################################
# 응답기 파일 디렉터리를 root 소유 0711 로 매번 맞춘다 — 응답기 계정은 안의 자기
# 파일만 연다. 링크면 받지 않는다(가리키는 곳에 비밀을 쓰지 않는다).
# Globals:
#   render_step
# Arguments:
#   응답기 파일 디렉터리.
#######################################
prepare_ingest_auth_dir() {
  if [[ -L "$1" ]] || ! mkdir -p -- "$1" || ! chown -h 0:0 "$1" \
    || ! chmod 0711 "$1" || [[ ! -d "$1" || -L "$1" ]]; then
    render_step='ingest-auth-dir'
    return 1
  fi
}

#######################################
# 응답기 상태 디렉터리를 응답기 실행 계정 소유 0700 으로 매번 맞춘다(안의
# 파일은 둔다). 링크면 받지 않는다. 부모(응답기 파일 디렉터리)는 root 0711 이라
# 응답기 계정은 이 이름 자리를 바꿔치지 못한다 — 안의 내용만 바꾼다.
# Globals:
#   ingest_auth_owner, render_step
# Arguments:
#   상태 디렉터리.
#######################################
prepare_state_dir() {
  if [[ -L "$1" ]] || ! mkdir -p -- "$1" \
    || ! chown -h "${ingest_auth_owner}" "$1" || ! chmod 0700 "$1" \
    || [[ ! -d "$1" || -L "$1" ]]; then
    render_step='ingest-auth-state-dir'
    return 1
  fi
}

#######################################
# 목록의 파일을 둔다(소유 꼴 → 디렉터리 → install). 목록의 내용은 끝에 비운다.
# 실패 줄은 여기서 낸다.
# Globals:
#   file_bodies, render_step
# Arguments:
#   RUNTIME_DIR, 응답기 파일 디렉터리(응답기 모드만).
# Returns:
#   0 둠 / 1 실패.
#######################################
place_files() {
  local status=0
  check_file_owners && prepare_runtime_dir "$1" \
    && { (( $# < 2 )) || { prepare_ingest_auth_dir "$2" \
      && prepare_state_dir "$2/${STATE_DIR_NAME}"; }; } \
    && install_files || status=1
  file_bodies=()
  if (( status != 0 )); then
    say "MEDIA START FAIL render ${render_step}"
    return 1
  fi
}

#######################################
# 옛 모드 — 토큰 둘(CDN 비밀 · API 비밀번호)을 채운 렌더를 mediamtx.yml 로,
# 비밀번호 한 줄을 사이드카 파일로 둔다. 실패 줄은 여기서 낸다.
# Globals:
#   mtx_owner, sidecar_owner
# Arguments:
#   RUNTIME_DIR, CDN 비밀, API 비밀번호.
# Returns:
#   0 둠 / 1 실패.
#######################################
render_legacy() {
  local rendered
  if ! rendered="$(render_template "${SECRET_TOKEN}" "$2" "${API_PASS_TOKEN}" \
    "$3" && printf x)"; then
    say 'MEDIA START FAIL render template-token'
    return 1
  fi
  add_file "$1/${RENDER_NAME}" "${mtx_owner}" '' "${rendered%x}"
  unset rendered
  add_file "$1/${API_PASS_NAME}" "${sidecar_owner}" 'api-pass-' "$3"$'\n'
  place_files "$1"
}

#######################################
# compose 렌더본의 응답기 서비스 키 줄 수가 모드와 맞는지 본다 — 응답기 모드 ⇔
# 1, 옛 모드 ⇔ 0. 읽을 수 없는 파일은 0 으로 센다.
# Globals:
#   render_mode, INGEST_AUTH_SERVICE_LINE
# Arguments:
#   compose 파일.
# Returns:
#   0 맞음 / 1 어긋남.
#######################################
check_mode_match() {
  local n=0 want=1
  [[ "${render_mode}" != legacy ]] || want=0
  if [[ -f "$1" && -r "$1" ]]; then
    n="$(awk -v line="${INGEST_AUTH_SERVICE_LINE}" \
      '$0 == line { n++ } END { print n + 0 }' "$1")" || return 1
  fi
  (( n == want ))
}

#######################################
# IP 한도 env 파일이 링크 아닌 일반 파일이고 내용이 정확히
# INGEST_AUTH_IP_LIMIT=on 또는 =off 한 줄(끝 줄바꿈은 있어도 없어도)인지
# 본다. 크기는 원본 바이트로 대조한다(명령 치환은 NUL 을 지운다).
# Arguments:
#   env 파일.
# Returns:
#   0 맞음 / 1 없음 · 꼴 밖.
#######################################
check_ip_limit_env() {
  local size content LC_ALL=C
  [[ -f "$1" && ! -L "$1" ]] || return 1
  size="$(wc -c <"$1")" || return 1
  size=$((size + 0))
  (( size <= 64 )) || return 1
  content="$(cat -- "$1" && printf x)" || return 1
  content="${content%x}"
  (( ${#content} == size )) || return 1
  content="${content%$'\n'}"
  [[ "${content}" == INGEST_AUTH_IP_LIMIT=on \
    || "${content}" == INGEST_AUTH_IP_LIMIT=off ]]
}

#######################################
# 4C 토큰을 읽는다(read_cdn_secret 과 같은 방식 — 끝 줄바꿈 하나만 떼고 NUL 은
# 제어 문자로 바꾼다). 응답기가 값을 HTTP 머리글에 싣고(headerSafe) 파일을
# 4096바이트까지 받으므로(secretfile) 빈 값 · None · 제어 문자(C1 포함) · 앞뒤
# 공백 · 값 + 줄바꿈이 상한 초과면 실패다.
# Globals:
#   INTERNAL_API_TOKEN_SECRET_ARN
# Outputs:
#   값(성공했을 때만).
#######################################
read_resolve_token() {
  local arn="${INTERNAL_API_TOKEN_SECRET_ARN:-}" value LC_ALL=C
  local c1=$'\xc2['$'\x80''-'$'\x9f'']'
  [[ -n "${arn}" ]] || return 1
  value="$({ aws secretsmanager get-secret-value --secret-id "${arn}" \
    --query SecretString --output text && printf x; } \
    | LC_ALL=C tr '\000' '\001')" || return 1
  [[ "${value}" == *x ]] || return 1
  value="${value%x}"
  value="${value%$'\n'}"
  if [[ -z "${value}" || "${value}" == None || "${value}" == *[[:cntrl:]]* \
    || "${value}" == ' '* || "${value}" == *' ' ]]; then
    return 1
  fi
  # C1 제어 문자(U+0080–U+009F 의 UTF-8 두 바이트)도 headerSafe 가 거부한다.
  # shellcheck disable=SC2053 # 패턴으로 대조한다.
  [[ "${value}" != *${c1}* ]] || return 1
  (( ${#value} + 1 <= RESOLVE_TOKEN_MAX_BYTES )) || return 1
  printf '%s' "${value}"
}

#######################################
# 거부 목록을 SSM 에서 받아(argv 에는 이름만) 거부 목록 디렉터리의 임시 파일에
# 바이트 그대로 쓰고, 꼴을 본 뒤 rename 한다. 임시 파일은 render_tmps(EXIT
# 트랩)에 남긴다. 판정 줄은 여기서 낸다.
# Globals:
#   INGEST_AUTH_DENYLIST_PARAM, INGEST_AUTH_DENY_DIR, ingest_auth_owner,
#   render_tmps
# Returns:
#   0 둠 / 1 막힘 · 실패.
#######################################
install_denylist() {
  local name="${INGEST_AUTH_DENYLIST_PARAM:-}" gid="${ingest_auth_owner#*:}"
  local dir="${INGEST_AUTH_DENY_DIR:-/var/lib/pokeclip-media/ingest-auth/deny}"
  local tmp
  if [[ -z "${name}" ]]; then
    say 'MEDIA START BLOCKED: ingest-auth denylist unavailable'
    return 1
  fi
  if [[ "${dir}" != /?* || ! "${gid}" =~ ^[0-9]+$ ]] \
    || ! deny_tokens::prepare_dir "${dir}" "${gid}" \
    || ! tmp="$(mktemp "${dir}/.${DENY_TOKENS_FILE_NAME}.XXXXXX")"; then
    say 'MEDIA START FAIL render deny-dir'
    return 1
  fi
  render_tmps+=("${tmp}")
  if ! aws ssm get-parameter --name "${name}" --query Parameter.Value \
    --output text >"${tmp}"; then
    say 'MEDIA START BLOCKED: ingest-auth denylist unavailable'
    return 1
  fi
  if ! deny_tokens::valid "${tmp}"; then
    say 'MEDIA START BLOCKED: ingest-auth denylist invalid'
    return 1
  fi
  if ! deny_tokens::install "${tmp}" "${dir}/${DENY_TOKENS_FILE_NAME}" \
    "${gid}"; then
    say 'MEDIA START FAIL render deny-install'
    return 1
  fi
}

#######################################
# 응답기 모드 — 생성 셋(Basic 비밀번호 · 잠금 둘)을 새로 만들어 토큰 넷(CDN
# 비밀 · Basic · 잠금 둘)을 채운 렌더를 mediamtx.yml 로 두고, API 비밀번호를
# 사이드카 파일과 응답기 파일에, Basic 비밀번호 · 4C 토큰을 응답기 파일에
# 둔다. 그 전에 외부 입력(IP 한도 env 파일 · 4C 토큰 · 거부 목록)을 본다. 실패
# 줄은 여기서 낸다.
# Globals:
#   mtx_owner, sidecar_owner, ingest_auth_owner
# Arguments:
#   RUNTIME_DIR, CDN 비밀, API 비밀번호, compose 파일.
# Returns:
#   0 둠 / 1 막힘 · 실패.
#######################################
render_ingest_auth() {
  local ia="$1/${INGEST_AUTH_DIR_NAME}" basic_pass publish_lock read_lock
  local rendered resolve_token
  if ! check_ip_limit_env "$(dirname -- "$4")/${IP_LIMIT_ENV_NAME}"; then
    say 'MEDIA START BLOCKED: ingest-auth ip-limit setting unavailable'
    return 1
  fi
  if ! resolve_token="$(read_resolve_token)"; then
    say 'MEDIA START BLOCKED: ingest-auth resolve token unavailable'
    return 1
  fi
  install_denylist || { unset resolve_token; return 1; }
  if ! basic_pass="$(generate_credential)" \
    || ! publish_lock="$(generate_credential)" \
    || ! read_lock="$(generate_credential)"; then
    unset basic_pass publish_lock read_lock resolve_token
    say 'MEDIA START BLOCKED: generated credential unavailable'
    return 1
  fi
  if ! rendered="$(render_template "${SECRET_TOKEN}" "$2" \
    "${BASIC_PASS_TOKEN}" "${basic_pass}" "${PUBLISH_LOCK_TOKEN}" \
    "${publish_lock}" "${READ_LOCK_TOKEN}" "${read_lock}" && printf x)"; then
    unset basic_pass publish_lock read_lock resolve_token
    say 'MEDIA START FAIL render template-token'
    return 1
  fi
  unset publish_lock read_lock
  add_file "$1/${RENDER_NAME}" "${mtx_owner}" '' "${rendered%x}"
  unset rendered
  add_file "$1/${API_PASS_NAME}" "${sidecar_owner}" 'api-pass-' "$3"$'\n'
  add_file "${ia}/${BASIC_PASS_NAME}" "${ingest_auth_owner}" \
    'ingest-auth-basic-pass-' "${basic_pass}"$'\n'
  unset basic_pass
  add_file "${ia}/${API_PASS_NAME}" "${ingest_auth_owner}" \
    'ingest-auth-api-pass-' "$3"$'\n'
  add_file "${ia}/${RESOLVE_TOKEN_NAME}" "${ingest_auth_owner}" \
    'ingest-auth-resolve-token-' "${resolve_token}"$'\n'
  unset resolve_token
  place_files "$1" "${ia}"
}

#######################################
# 비밀을 읽고 만들어, 템플릿의 토큰 집합으로 고른 모드대로 렌더한다. 실패 줄은
# 여기서(또는 모드 함수가) 낸다.
# Globals:
#   MEDIA_MTX_TEMPLATE, RUNTIME_DIR, render_mode
# Arguments:
#   compose 파일.
# Returns:
#   0 렌더함 / 1 막힘.
#######################################
prepare_config() {
  local template="${MEDIA_MTX_TEMPLATE:-/etc/pokeclip-media/mediamtx.yml.tmpl}"
  local dir="${RUNTIME_DIR:-/run/pokeclip-media}" secret api_pass status=0
  if [[ "${dir}" != /?* ]]; then
    say 'MEDIA START FAIL render runtime-dir'
    return 1
  fi
  if ! secret="$(read_cdn_secret)"; then
    say 'MEDIA START BLOCKED: CDN secret unavailable'
    return 1
  fi
  if ! api_pass="$(generate_credential)"; then
    unset secret
    say 'MEDIA START BLOCKED: MTX API credential unavailable'
    return 1
  fi
  if ! load_template "${template}"; then
    unset secret api_pass
    say 'MEDIA START FAIL render template'
    return 1
  fi
  if ! detect_mode; then
    unset secret api_pass
    say 'MEDIA START FAIL render template-token'
    return 1
  fi
  if ! check_mode_match "$1"; then
    unset secret api_pass
    say 'MEDIA START BLOCKED: ingest-auth mode mismatch'
    return 1
  fi
  set_owners
  if [[ "${render_mode}" == legacy ]]; then
    render_legacy "${dir}" "${secret}" "${api_pass}" || status=1
  else
    render_ingest_auth "${dir}" "${secret}" "${api_pass}" "$1" || status=1
  fi
  unset secret api_pass
  return "${status}"
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
  # 신호로 끝나도 EXIT 트랩이 돌아 임시 렌더 파일이 남지 않게 한다. TERM 도
  # 130 으로 끝낸다 — 기동 중 systemctl stop(유닛 ExecStop 의 TERM)이 유닛
  # SuccessExitStatus=130 에 맞아 failed 로 남지 않게. compose up 이 TERM 에
  # 내는 값과 같다.
  trap 'exit 130' INT
  trap 'exit 130' TERM
  if ! run_prereqs; then
    exit 1
  fi
  if ! prepare_config "${compose_file}"; then
    exit 1
  fi
  for service in "${ATTACHED_SERVICES[@]}"; do
    attach+=(--attach "${service}")
  done
  trap - EXIT INT TERM
  exec docker compose --ansi never -p "${project}" -f "${compose_file}" \
    up --force-recreate --remove-orphans --abort-on-container-exit \
    "${attach[@]}"
}

main "$@"
