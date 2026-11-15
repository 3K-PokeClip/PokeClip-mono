# shellcheck shell=bash
# 시작 스크립트 전역(say · generate_credential · render_step · sidecar_owner)과
# 목록 · 파일 두기(service_line_counts · add_file · check_file_owners ·
# install_files · reset_file_list · file_* 목록)는 시작 스크립트와
# lib/render_files.sh 가 정의한다.
# shellcheck disable=SC2154
#
# 시작 스크립트의 IMDS 대리 창구(켬 상자). compose 렌더본에 cred-relay 서비스 키
# 줄이 한 줄 있으면 켬이다(cred_relay_mode — 렌더본 하나가 정한다). 켬 상자는
# 컨테이너의 IMDS 홉이 1 이라, 역할 자격은 호스트 망의 중계(cred-relay)가 토큰을
# 가진 사이드카(indexer) · janitor 에게만 준다. 여기서는 그 준비 둘을 한다.
#   - 창구 토큰 묶음(install_cred_tokens): 호출자마다 새 토큰(16진 64자) 하나를
#     소비자 디렉터리(cred-<호출자>/token — 그 컨테이너 실행 계정 소유 0400)와
#     중계 디렉터리(cred-relay/<호출자> — 중계 실행 계정 소유 0400)에 같은 값으로
#     둔다. 끝 개행은 붙이지 않는다(소비자 SDK 가 개행 든 토큰을 거부한다).
#     렌더 묶음과 따로 도는 두 번째 묶음이라, 들어갈 때 파일 목록 넷이 비었는지
#     보고 나갈 때 비운다.
#   - 주소(ensure_cred_address): 더미 링크 pokeclip-cred0 에 169.254.170.2/32 를
#     붙이고 올린다. 매 기동 멱등이고 떼지 않는다(재부팅이 지운다).
#
# 디렉터리는 셋이다. 소비자 둘(cred-indexer · cred-janitor)은 root:root 0711 —
# SDK 가 파일 경로로 열어 디렉터리에는 x 만 있으면 된다. 중계(cred-relay)는
# root:<중계 그룹> 0750 — 중계는 os.Root 로 디렉터리를 열어(O_RDONLY) r 이 필요하고,
# 중계 이미지 USER(사이드카 이미지와 같은 10001)의 그룹으로 연다. 소유는 셋 다
# root 라 실행 계정은 이름 자리를 바꿔치지 못한다. 링크면 받지 않는다(가리키는
# 곳에 비밀을 쓰지 않는다 — 응답기 · 만료 토큰 디렉터리와 같은 규칙).
#
# 판정 줄(전부):
#   MEDIA START BLOCKED: cred-relay mode mismatch    렌더본의 키 줄이 둘 이상 ·
#                                                    다른 꼴(뒤 공백 · 같은 줄 값)
#   MEDIA START BLOCKED: cred-relay token unavailable  토큰 생성 실패
#   MEDIA START FAIL render cred-relay-<단계>        dir · tmpfile · write ·
#                                                    chown · target · rename ·
#                                                    list(목록이 비지 않음)
#   MEDIA START FAIL cred-relay address              링크 · 주소 · 올리기 실패
#   MEDIA START BLOCKED: cred-relay needs sidecar timing v2 label=<…>
#                                                    기동 전 검사(시작 스크립트) —
#                                                    켬 상자는 검사 서비스를
#                                                    돌리지 않는다.
# 마지막 줄은 판 2 라벨이 없는 사이드카(c0 아래 · 이미지 받기 실패에 로컬에도
# 없음)만 드러낸다. c0 이상이지만 이 창구 하한 아래인 사이드카 이미지는 이 점검을
# 지나고, 중계 진입점이 없어 중계 컨테이너가 시작 오류(종료 127)로 서지 않는다.
# 유닛은 정리 뒤 20초마다 재시도를 되풀이하며 방송을 받지 못한다(유닛 정의
# Restart=always — 재시도 순환은 실측하지 않음. 축약 시작 스크립트(같은
# compose up)의 유닛 밖 단독 실행 실측은 종료 1 · MediaMTX 만 섬). 그 하한 판정은 런북의 하한 확인과 기동 판정이 맡는다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). 상자(AL2023 · bash 5)와 kty
# 로컬(macOS · bash 3.2) 양쪽에서 돈다.

# compose 렌더본의 중계 서비스 키 줄(compose.yml.tmpl 의 서비스 키 그대로).
readonly CRED_RELAY_SERVICE_LINE='  cred-relay:'
# 중계가 듣는 주소와 그 주소를 붙이는 더미 링크 이름.
readonly CRED_RELAY_ADDR='169.254.170.2'
readonly CRED_RELAY_LINK='pokeclip-cred0'
# 토큰을 받는 호출자(중계 CRED_RELAY_CALLERS 와 같은 차례).
readonly CRED_CALLERS=(indexer janitor)
# RUNTIME_DIR 아래 디렉터리 이름 — 중계 · 소비자(cred-<호출자>)와 소비자
# 디렉터리 안의 토큰 파일 이름(소비자 AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE 의
# 끝 이름). 중계 디렉터리 안의 파일 이름은 호출자 이름이다.
readonly CRED_RELAY_DIR_NAME='cred-relay'
readonly CRED_CONSUMER_DIR_PREFIX='cred-'
readonly CRED_TOKEN_NAME='token'
# janitor 실행 계정(compose 렌더본 janitor 의 user: 와 같은 값 — render_test 가
# 대조한다).
readonly CRED_JANITOR_OWNER='10002:10002'

#######################################
# compose 렌더본의 중계 서비스 키 줄 수로 창구 켬 · 끔을 낸다 — 0 이면 off, 1 이면
# on. 둘 이상이거나 같은 키를 다른 꼴(뒤 공백 · 같은 줄 값)로 쓴 줄이 있으면
# 실패다. 읽을 수 없는 파일은 off(compose up 이 그 파일로 실패를 드러낸다 —
# compose_mode 와 같은 규칙).
# Globals:
#   CRED_RELAY_SERVICE_LINE
# Arguments:
#   compose 파일.
# Outputs:
#   on | off.
# Returns:
#   0 / 1 꼴 밖.
#######################################
cred_relay_mode() {
  local counts
  counts="$(service_line_counts "$1" "${CRED_RELAY_SERVICE_LINE}")" \
    || return 1
  case "${counts}" in
    '0 0') printf 'off' ;;
    '1 1') printf 'on' ;;
    *) return 1 ;;
  esac
}

#######################################
# 호출자 토큰 파일의 소비자 실행 계정(uid:gid)을 낸다 — indexer 는 사이드카,
# janitor 는 janitor 계정.
# Globals:
#   sidecar_owner, CRED_JANITOR_OWNER
# Arguments:
#   호출자 이름.
# Outputs:
#   uid:gid.
#######################################
cred_caller_owner() {
  case "$1" in
    janitor) printf '%s' "${CRED_JANITOR_OWNER}" ;;
    *) printf '%s' "${sidecar_owner}" ;;
  esac
}

#######################################
# 창구 디렉터리 하나를 정한 소유 · 권한으로 매번 맞춘다(엔진 · 앞 기동이 만든
# 디렉터리도 고쳐 쓴다). 링크면 받지 않는다.
# Globals:
#   render_step
# Arguments:
#   디렉터리, 소유(uid:gid), 권한(8진).
# Returns:
#   0 / 1 링크 · 실패(단계 이름은 render_step).
#######################################
prepare_cred_dir() {
  if [[ -L "$1" ]] || ! mkdir -p -- "$1" || ! chown -h "$2" "$1" \
    || ! chmod "$3" "$1" || [[ ! -d "$1" || -L "$1" ]]; then
    render_step='cred-relay-dir'
    return 1
  fi
}

#######################################
# 창구 디렉터리 셋을 맞춘다 — 소비자 둘 root:root 0711, 중계 root:<중계 그룹>
# 0750.
# Globals:
#   sidecar_owner, CRED_CALLERS
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 / 1 실패(단계 이름은 render_step).
#######################################
prepare_cred_dirs() {
  local caller
  for caller in "${CRED_CALLERS[@]}"; do
    prepare_cred_dir "$1/${CRED_CONSUMER_DIR_PREFIX}${caller}" 0:0 0711 \
      || return 1
  done
  prepare_cred_dir "$1/${CRED_RELAY_DIR_NAME}" "0:${sidecar_owner#*:}" 0750
}

#######################################
# 창구 토큰 묶음을 둔다 — 들어갈 때 파일 목록 넷이 비었는지 보고, 호출자마다 새
# 토큰을 만들어 소비자 파일 · 중계 사본에 같은 값으로 목록에 더한 뒤, 디렉터리 ·
# 소유 꼴을 맞추고 한 묶음으로 둔다(임시 파일 → 모두 점검 → rename · 실패 시
# 이번 것 삭제). 끝나면(성공 · 실패 모두) 목록을 비운다. 실패 줄은 여기서 낸다.
# Globals:
#   file_paths, file_owners, file_steps, file_bodies, sidecar_owner,
#   render_step, CRED_CALLERS
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 둠 / 1 막힘 · 실패.
#######################################
install_cred_tokens() {
  local caller cred_token relay="$1/${CRED_RELAY_DIR_NAME}"
  if (( ${#file_paths[@]} + ${#file_owners[@]} + ${#file_steps[@]} \
    + ${#file_bodies[@]} != 0 )); then
    say 'MEDIA START FAIL render cred-relay-list'
    return 1
  fi
  for caller in "${CRED_CALLERS[@]}"; do
    if ! cred_token="$(generate_credential)"; then
      unset cred_token
      reset_file_list
      say 'MEDIA START BLOCKED: cred-relay token unavailable'
      return 1
    fi
    add_file \
      "$1/${CRED_CONSUMER_DIR_PREFIX}${caller}/${CRED_TOKEN_NAME}" \
      "$(cred_caller_owner "${caller}")" 'cred-relay-' "${cred_token}"
    add_file "${relay}/${caller}" "${sidecar_owner}" 'cred-relay-' \
      "${cred_token}"
  done
  unset cred_token
  if ! check_file_owners || ! prepare_cred_dirs "$1" || ! install_files; then
    reset_file_list
    say "MEDIA START FAIL render ${render_step}"
    return 1
  fi
  reset_file_list
}

#######################################
# 더미 링크에 중계 주소를 붙이고 올린다 — 링크가 없을 때만 만든다(멱등). 실패
# 줄은 여기서 낸다.
# Globals:
#   CRED_RELAY_LINK, CRED_RELAY_ADDR
# Returns:
#   0 / 1 실패.
#######################################
ensure_cred_address() {
  if ! ip link show dev "${CRED_RELAY_LINK}" >/dev/null 2>&1 \
    && ! ip link add "${CRED_RELAY_LINK}" type dummy; then
    say 'MEDIA START FAIL cred-relay address'
    return 1
  fi
  if ! ip addr replace "${CRED_RELAY_ADDR}/32" dev "${CRED_RELAY_LINK}" \
    || ! ip link set "${CRED_RELAY_LINK}" up; then
    say 'MEDIA START FAIL cred-relay address'
    return 1
  fi
}

#######################################
# 켬 상자의 창구 준비 — 토큰 묶음을 두고 주소를 붙인다. 실패 줄은 각 걸음이
# 낸다.
# Arguments:
#   RUNTIME_DIR.
# Returns:
#   0 / 1 막힘 · 실패.
#######################################
prepare_cred_relay() {
  install_cred_tokens "$1" && ensure_cred_address
}
