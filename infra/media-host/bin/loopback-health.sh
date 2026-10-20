#!/usr/bin/env bash
#
# 루프백 헬스 — 교체 런북 5-3 의 3(B 를 대상 그룹 밖에서 확인)과 O7f 9 가 쓴다.
# 상자에서 SSM 으로 돌린다(실행 직전 승인).
#
# 사용: loopback-health.sh [--passphrase] <ingestStreamId>
#   ingestStreamId  시험 계정의 공개 스트림 토큰(26자 Crockford Base32 —
#                   streamid 의 r 값 · 경로 이름. 비밀이 아니다 — kty 86차 R3).
#   --passphrase    시험 스트림 키 passphrase 를 실어 송출한다. 송출 인증(M15
#                   응답기)이 반영된 상자에서만 쓴다. 그 전의 상자는 익명
#                   publish 라 passphrase 를 실으면 MediaMTX 가 끊는다 — 기본은
#                   싣지 않는다(kty 86차 R4 · 착수 덧붙임 편차 23). M15 뒤에는
#                   거꾸로 기본 갈래가 거부되는 것이 정상이다.
#
# 하는 일:
#   1. CDN 비밀(과 --passphrase 면 시험 키)을 Secrets Manager 에서 읽어 셸
#      변수에만 둔다(argv 에는 ARN 만). 못 읽으면 송출하지 않고 끝낸다 — 서비스는
#      건드리지 않는다.
#   2. 송출기 이미지(LOOPBACK_SENDER_IMAGE — 다이제스트 고정 linuxserver/ffmpeg
#      미러)를 따로 받는다(상한 안에서 — 받는 시간이 대기 상한을 깎지 않게).
#      DOCKER_CONFIG 는 시작 스크립트와 같은 기본값(/root/.docker — ECR 자격
#      도우미 설정)을 채워 넘긴다.
#   3. 송출기를 고유 이름 · 라벨로 `docker run --rm --pull never --network
#      container:<mediamtx>` 로 띄워 MediaMTX 망 칸의 srt://127.0.0.1:8890 으로
#      시험 신호(GOP 2초 -g 60)를 보낸다. 권한은 줄인다 — 모든 cap 버림 ·
#      no-new-privileges · 읽기 전용 루트(+ /tmp tmpfs) · pids · 메모리 상한 ·
#      비root(65534) · 진입점은 ffmpeg 바로(이미지 래퍼의 계정 조정을 건너뜀).
#      환경 변수 · 볼륨 · 포트는 넘기지 않는다. ffmpeg 은 -hide_banner -loglevel
#      quiet 이고 출력은 버린다.
#   4. 127.0.0.1:<live 프록시>/<ingestStreamId>/index.m3u8 이 200 인지 대기
#      상한까지 1초마다 본다. Bearer(CDN 비밀)는 `curl -q --config -` 의 표준
#      입력으로만 넘긴다(-q · noproxy — ~/.curlrc · 프록시 환경 변수를 따르지
#      않는다). 대기는 이 셸에서 돈다 — TERM 을 받으면 곧바로 정리한다.
#   5. 송출기를 끝낸다 — docker run 에 TERM, 그다음 그 이름의 컨테이너를
#      `docker rm -f` 하고 라벨로 남은 것이 0 인지 본다. 남으면 OK 를 내지
#      않는다.
#
# 노출(kty 20차 수용 범위 — 계획 c5 노출 표): CDN 비밀은 curl 표준 입력 밖
# 어디에도 없다. passphrase(--passphrase 일 때만)는 송출기 argv 에만 있다 — 송출이
# 도는 동안 상자 프로세스 목록 · docker inspect 에 보인다(한계 — 5 의 정리로
# 스크립트가 끝난 뒤에는 남지 않는다).
#
# 출력(표준 출력 한 줄 — SSM 기록에 남는 것은 이 줄뿐):
#   LOOPBACK OK
#   LOOPBACK FAIL <단계>   usage · config · cdn-secret · test-key · mediamtx ·
#                          scratch · pull · sender(송출기가 먼저 끝남) ·
#                          playlist(상한 안에 200 아님) · sender-cleanup(송출기
#                          컨테이너가 남음).
# 종료 코드: 0 OK / 1 실패 / 2 인자 오류.
#
# 읽는 변수(prod 값은 /etc/pokeclip-media/media.env):
#   CDN_SECRET_ARN              필수. CDN 비밀 ARN.
#   TEST_STREAM_KEY_SECRET_ARN  --passphrase 일 때 필수. 시험 키 ARN.
#   LOOPBACK_SENDER_IMAGE       필수. <저장소>@sha256:<64자>.
#   MEDIA_LIVE_PORT             필수. nginx live 포트(compose proxy_live_port).
#   LOOPBACK_WAIT_SECONDS       200 을 기다리는 상한(초, 1–300). 기본 30.
#   DOCKER_CONFIG               기본 /root/.docker.
#   그 밖은 lib/ops.sh 머리 주석(MEDIA_COMPOSE_PROJECT · MEDIA_OPS_TIMEOUT).
#   스크래치는 TMPDIR(없으면 /tmp) 아래다.

# shellcheck source-path=SCRIPTDIR

set -euo pipefail
set +x +a
umask 077
ulimit -c 0
# 비밀을 담는 변수 이름이 부모 환경에서 export 된 채 들어오면 local 이 그
# 속성을 이어받아 자식(docker · curl) 환경에 실린다 — 쓰기 전에 지운다.
unset -v cdn_secret passphrase srt_url

BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BIN_DIR
# shellcheck source=lib/ops.sh
source "${BIN_DIR}/lib/ops.sh"

readonly STREAM_ID_PATTERN='^[0-9A-HJKMNP-TV-Z]{26}$'
readonly IMAGE_PATTERN='^[a-z0-9][a-z0-9._/:-]*@sha256:[0-9a-f]{64}$'
readonly DEFAULT_WAIT_SECONDS=30
readonly MAX_WAIT_SECONDS=300
# 송출기 길이(초)는 대기 상한보다 이만큼 길다 — 대기 중에 송출이 먼저 끝나지
# 않게.
readonly SENDER_EXTRA_SECONDS=15
# 송출기 이미지 받기 상한(초).
readonly PULL_TIMEOUT=300
# curl 한 번의 상한(초)과 그 바깥 상한.
readonly CURL_MAX_TIME=5
readonly CURL_BOUND=10
# 송출기 컨테이너를 찾는 라벨 키 — 값은 그 실행의 고유 이름.
readonly SENDER_LABEL='com.pokeclip.loopback'
# 송출기 docker run 옵션(이름 · 라벨 · 망 칸은 실행마다 붙인다). tmpfs 옵션의
# 쉼표는 한 인자 안의 값이다.
# shellcheck disable=SC2054
readonly SENDER_OPTIONS=(--rm --pull never --cap-drop ALL
  --security-opt no-new-privileges:true --read-only
  --tmpfs /tmp:rw,noexec,nosuid,size=16m --pids-limit 64 --memory 256m
  --user 65534:65534 --entrypoint /usr/local/bin/ffmpeg)

sender_pid=""
sender_name=""
run_limit="${OPS_DEFAULT_TIMEOUT}"
playlist_result=""

#######################################
# 실패 줄을 내고 끝낸다.
# Arguments:
#   단계, 종료 코드(기본 1).
#######################################
fail() {
  ops::say "LOOPBACK FAIL $1"
  exit "${2:-1}"
}

#######################################
# 송출기를 끝내고 컨테이너가 남지 않았는지 본다. docker run 클라이언트를 TERM
# (유예 뒤 KILL)으로 끝낸 다음, 클라이언트가 컨테이너를 못 끝냈을 때를 위해 그
# 이름의 컨테이너를 rm -f 하고 라벨로 0 개인지 확인한다.
# Globals:
#   sender_pid, sender_name, run_limit
# Returns:
#   0 남은 것 없음 / 1 남음 · 확인 못 함.
#######################################
stop_sender() {
  local left
  if [[ -n "${sender_pid}" ]]; then
    ops::stop_pid "${sender_pid}"
    sender_pid=""
  fi
  [[ -n "${sender_name}" ]] || return 0
  # 이미 --rm 으로 지워졌으면 rm -f 는 「없음」 으로 실패한다 — 판정은 아래
  # 라벨 조회로 한다.
  ops::bounded "${run_limit}" docker rm -f "${sender_name}" </dev/null \
    >/dev/null 2>&1 || true
  left="$(ops::bounded "${run_limit}" docker ps -a -q --no-trunc \
    --filter "label=${SENDER_LABEL}=${sender_name}" </dev/null 2>/dev/null)" \
    || return 1
  [[ -z "${left}" ]] || return 1
  sender_name=""
}

#######################################
# 끝날 때 송출기 · 상한 실행 자식 · 스크래치를 정리한다(EXIT 트랩).
#######################################
on_exit() {
  stop_sender || true
  ops::on_exit
}

#######################################
# 설정 값을 확인한다.
# Globals:
#   LOOPBACK_SENDER_IMAGE, MEDIA_LIVE_PORT, LOOPBACK_WAIT_SECONDS
# Returns:
#   0 맞음 / 1 틀림.
#######################################
check_config() {
  local port="${MEDIA_LIVE_PORT:-}"
  local wait="${LOOPBACK_WAIT_SECONDS:-${DEFAULT_WAIT_SECONDS}}"
  [[ "${LOOPBACK_SENDER_IMAGE:-}" =~ ${IMAGE_PATTERN} ]] || return 1
  [[ "${port}" =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )) || return 1
  [[ "${wait}" =~ ^[1-9][0-9]{0,2}$ ]] && (( wait <= MAX_WAIT_SECONDS )) \
    || return 1
  ops::timeout >/dev/null
}

#######################################
# 재생 목록 상태 코드를 한 번 받는다(ops::bounded 가 배경에서 부른다). Bearer 와
# URL 은 curl 설정(표준 입력)으로만 넘긴다 — 내장 printf 라 argv 에 오르지
# 않는다.
# Arguments:
#   CDN 비밀, URL.
# Outputs:
#   상태 코드.
#######################################
playlist_request() {
  printf '%s\n' 'silent' 'output = "/dev/null"' 'write-out = "%{http_code}"' \
    'noproxy = "*"' "max-time = ${CURL_MAX_TIME}" \
    "header = \"Authorization: Bearer $1\"" "url = \"$2\"" \
    | curl -q --config - 2>/dev/null
}

#######################################
# 송출기를 배경에서 띄운다.
# Globals:
#   sender_pid, sender_name, LOOPBACK_SENDER_IMAGE
# Arguments:
#   mediamtx 컨테이너 ID, 송출 길이(초), SRT URL.
#######################################
start_sender() {
  sender_name="pokeclip-loopback-$$-${RANDOM}${RANDOM}"
  docker run --name "${sender_name}" --label "${SENDER_LABEL}=${sender_name}" \
    --network "container:$1" "${SENDER_OPTIONS[@]}" "${LOOPBACK_SENDER_IMAGE}" \
    -hide_banner -loglevel quiet -re -f lavfi \
    -i 'testsrc=size=320x240:rate=30' -t "$2" -c:v libx264 -pix_fmt yuv420p \
    -g 60 -f mpegts "$3" </dev/null >/dev/null 2>&1 &
  sender_pid=$!
}

#######################################
# 재생 목록이 200 이 될 때까지 기다린다. 이 셸에서 돈다(명령 치환 안에서
# 돌리면 TERM 트랩이 대기 끝까지 미뤄진다).
# Globals:
#   sender_pid, playlist_result, ops_scratch
# Arguments:
#   CDN 비밀, URL, 대기 상한(초).
#######################################
wait_playlist() {
  local deadline=$((SECONDS + $3)) code_file="${ops_scratch}/code" code
  playlist_result=playlist
  while (( SECONDS < deadline )); do
    if ! kill -0 "${sender_pid}" 2>/dev/null; then
      playlist_result=sender
      return 0
    fi
    : >"${code_file}"
    ops::bounded "${CURL_BOUND}" playlist_request "$1" "$2" </dev/null \
      >"${code_file}" || true
    code=""
    read -r code <"${code_file}" || true
    if [[ "${code}" == 200 ]]; then
      playlist_result=ok
      return 0
    fi
    sleep 1
  done
}

main() {
  local with_passphrase=0 stream_id wait mtx
  local cdn_secret passphrase="" srt_url
  if [[ "${1:-}" == --passphrase ]]; then
    with_passphrase=1
    shift
  fi
  if (( $# != 1 )) || [[ ! "$1" =~ ${STREAM_ID_PATTERN} ]]; then
    fail usage 2
  fi
  stream_id="$1"
  check_config || fail config
  run_limit="$(ops::timeout)"
  wait="${LOOPBACK_WAIT_SECONDS:-${DEFAULT_WAIT_SECONDS}}"
  # 이미지 받기가 ECR 자격 도우미 설정을 쓰도록 시작 스크립트와 같은 기본값을
  # 채워 넘긴다.
  DOCKER_CONFIG="${DOCKER_CONFIG:-/root/.docker}"
  export DOCKER_CONFIG
  trap on_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  cdn_secret="$(ops::read_secret "${CDN_SECRET_ARN:-}" \
    "${OPS_CDN_SECRET_PATTERN}" "${run_limit}")" || fail cdn-secret
  if (( with_passphrase )); then
    passphrase="$(ops::read_secret "${TEST_STREAM_KEY_SECRET_ARN:-}" \
      "${OPS_PASSPHRASE_PATTERN}" "${run_limit}")" || fail test-key
  fi
  mtx="$(ops::one_container mediamtx "${run_limit}")" || fail mediamtx
  ops::make_scratch || fail scratch
  ops::bounded "${PULL_TIMEOUT}" docker pull --quiet \
    "${LOOPBACK_SENDER_IMAGE}" </dev/null >/dev/null 2>&1 || fail pull
  srt_url="srt://127.0.0.1:8890?streamid=#!::r=${stream_id},m=publish"
  if (( with_passphrase )); then
    srt_url+="&passphrase=${passphrase}&pbkeylen=32"
  fi
  passphrase=""
  start_sender "${mtx}" "$((wait + SENDER_EXTRA_SECONDS))" "${srt_url}"
  srt_url=""
  wait_playlist "${cdn_secret}" \
    "http://127.0.0.1:${MEDIA_LIVE_PORT}/${stream_id}/index.m3u8" "${wait}"
  cdn_secret=""
  stop_sender || fail sender-cleanup
  if [[ "${playlist_result}" != ok ]]; then
    fail "${playlist_result}"
  fi
  ops::say 'LOOPBACK OK'
}

main "$@"
