#!/usr/bin/env bash
#
# IMDS 대리 창구 토큰 디렉터리 실소유 계약 시험(POK-574 c9 — 로컬 실행 · CI
# 상설 아님). 시작 스크립트가 실제 리눅스 소유 · 권한으로 둔 중계 디렉터리를
# 실물 중계(사이드카 이미지의 cred-relay — 이미지 USER · cap 없음)가 여는지
# 본다. CI 몫은 start_test(chown argv · 모드 단언)와 credrelay 의 EACCES 시험이고,
# 이 시험은 그 둘이 대역으로 둔 「진짜 chown 뒤 진짜 계정이 연다」 를 잰다.
#
#   양성  bin/lib/cred_relay.sh 의 install_cred_tokens 가 둔 그대로(중계
#         디렉터리 root:<중계 그룹> 0750). 중계가 토큰을 읽고 듣기 시작한다
#         (cred_relay_started) · healthcheck 0 · TERM 이면 cred_relay_stopped ·
#         종료 0. token_file 실패 줄이 없어야 한다.
#   음성  같은 묶음 뒤 중계 디렉터리만 설계 원안(root:root 0711)으로 바꾼다 —
#         중계가 os.Root 로 디렉터리를 열지 못해 cred_relay_failed
#         reason=token_file · 종료 1 이어야 한다(시험이 그 결함을 잡는다는
#         교정).
#
# 회차마다 양성 · 음성을 한 번씩, REPEATS 회 돈다(사례 번호: 홀수 양성 · 짝수
# 음성).
#
# 준비(root 도우미): 지명 볼륨 하나가 RUNTIME_DIR 이다. bash 가 든 AL2023
# 이미지(상자와 같은 배포판 · bash 5)를 root · 망 없음으로 띄우고, 실제
# bin/lib/render_files.sh · bin/lib/cred_relay.sh 를 source 해
# install_cred_tokens 를 돈다. 시작 스크립트 전역 가운데 say ·
# generate_credential 은 시험 쪽 짧은 대역이고(검사 대상 아님), 중계 그룹은
# 사이드카 이미지의 USER 에서 읽는다(시작 스크립트의 sidecar_owner 기본값과
# 같은 자리 — 이미지 USER 가 바뀌면 이 시험이 드러낸다). RUNTIME_DIR 0711 은
# prepare_runtime_dir 의 대역이다. 볼륨은 tmpfs 가 아니다 — local 드라이버의
# tmpfs 볼륨은 쓰는 컨테이너가 끝나면 내려가 내용이 사라진다(2026-11-16 실측).
#
# 중계: 볼륨을 RUNTIME_DIR 경로에 읽기 전용으로 통째 붙이고 CALLER_DIR 은 그 안의
# 중계 디렉터리다 — 운영(중계 디렉터리만 bind)보다 엄격하다(부모 0711 을
# 지난다). volume-subpath 에 기대지 않는다. 중계는 IPv4 링크 로컬 주소만
# 받으므로(cmd/cred-relay 설정), 바깥으로 나가지 않는 전용 망(--internal ·
# 서브넷 169.254.170.0/24)에서 운영 주소 그대로 듣는다. 망 없음(--network
# none)으로 띄우면 토큰은 지나고 듣기에서 reason=listen 으로 끝난다(2026-11-16
# 실측 — 그래서 듣기까지 보려고 전용 망을 쓴다).
#
# 이미지: 사이드카 이미지는 이 트리의 media/ 에서 매번 로컬 빌드한다(캐시를
# 쓴다 — 지금 코드의 중계를 시험한다). 빌드한 이미지(RELAY_IMAGE)는 다음
# 실행이 다시 쓰도록 남긴다 — 지우려면 docker image rm 으로 지운다. 도우미
# 이미지는 로컬에 없으면 받는다.
#
# 실험 식별: 라벨 com.pokeclip.bench=pcbench-pok574 와
# com.pokeclip.bench.part=c9-contract(컨테이너 · 망 · 볼륨 — 정리 · 잔여 셈은
# 두 라벨을 다 가진 것만 겨눈다) · 이름 앞머리 pcbench-pok574-c9. 호스트 포트
# 게시 0. 이름이 고정이라 한 번에 하나만 돈다 — 시작할 때 잠금 디렉터리
# (/tmp/pcbench-pok574-c9.lock — 안에 PID)를 잡지 못하거나, 정리 범위에 앞 실행
# 잔여가 있으면 아무것도 건드리지 않고 멈춘다. 사례마다 끝에 그 사례의
# 컨테이너 · 망 · 볼륨을 지우고 잔여 0 을 확인한다(남으면 그 사례 FAIL). 끝날
# 때와 중단 신호에 남은 것을 지우고 다시 센다 — 남거나 셀 수 없으면 종료 1.
# 토큰은 매번 새 무작위 값이고 볼륨과 함께 지워진다. 출력에 싣지 않는다.
#
# 제품 파일(bin/lib 둘 · media/Dockerfile)이 없으면 모든 사례가 FAIL 이다.
# 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/cred_relay_contract.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
MEDIA_HOST_DIR="$(cd -- "${TEST_DIR}/.." && pwd)"
readonly MEDIA_HOST_DIR
MEDIA_DIR="$(cd -- "${MEDIA_HOST_DIR}/../../media" && pwd)"
readonly MEDIA_DIR
# shellcheck source=lib/harness.sh
source "${TEST_DIR}/lib/harness.sh"

readonly BENCH_LABEL='com.pokeclip.bench=pcbench-pok574'
readonly PART_LABEL='com.pokeclip.bench.part=c9-contract'
readonly NAME_PREFIX='pcbench-pok574-c9'
readonly LOCK_DIR='/tmp/pcbench-pok574-c9.lock'
readonly RELAY_IMAGE='pcbench-pok574/sidecar:c9'
readonly HELPER_IMAGE='amazonlinux:2023'
# 제품 lib(MEDIA_HOST_DIR 기준)와 도우미 · 중계 안의 경로.
readonly LIB_REL='bin/lib'
readonly HELPER_LIB='/media-host/lib'
readonly HELPER_RUNTIME='/rt'
readonly RELAY_RUNTIME='/run/pokeclip-media'
readonly RELAY_BIN='/usr/local/bin/cred-relay'
# 전용 망 — 중계 주소(lib 의 CRED_RELAY_ADDR)가 든 링크 로컬 서브넷.
readonly CRED_SUBNET='169.254.170.0/24'
readonly RELAY_PORT=9911
# 설계 원안(음성) — 중계 디렉터리 소유 · 권한.
readonly ORIGINAL_DIR_OWNER='0:0'
readonly ORIGINAL_DIR_MODE='0711'
readonly REPEATS=3
readonly CASE_COUNT=$((REPEATS * 2))
# 중계가 듣기 시작하거나 끝나기를 기다리는 상한 · docker stop 유예(초).
readonly RELAY_TIMEOUT=20
readonly STOP_GRACE=10
# 중계 로그(JSON)의 판정 글자 — slog 는 msg 다음에 속성을 넘긴 차례로 쓴다.
readonly LOG_STARTED='"msg":"cred_relay_started"'
readonly LOG_STOPPED='"msg":"cred_relay_stopped"'
readonly LOG_TOKEN_FILE='"msg":"cred_relay_failed","reason":"token_file"'

scratch=""
setup_reason=""
lock_held=0
relay_owner=""
callers_csv=""
relay_addr=""
relay_dir_name=""
rep=0

#######################################
# 준비 실패 원인에 한 마디를 더한다(모든 사례가 이 원인을 함께 낸다).
# Globals:
#   setup_reason
# Arguments:
#   원인.
#######################################
add_setup_reason() {
  setup_reason="${setup_reason:+${setup_reason}; }$1"
}

# docker 명령 하나를 상한 안에서 돌린다(넘기면 124).
bench_docker() {
  harness::bounded "${HARNESS_DOCKER_TIMEOUT}" docker "$@"
}

#######################################
# 정리 범위(두 라벨을 다 가진 컨테이너 · 망 · 볼륨)의 수를 낸다. 조회가 하나라도
# 실패하면 아무것도 내지 않고 실패한다 — 「확인 불가」 는 0 이 아니다.
# Outputs:
#   수.
#######################################
count_residue() {
  local c n v
  c="$(harness::count_output_lines bench_docker ps -aq \
    --filter "label=${BENCH_LABEL}" --filter "label=${PART_LABEL}")" \
    || return 1
  n="$(harness::count_output_lines bench_docker network ls -q \
    --filter "label=${BENCH_LABEL}" --filter "label=${PART_LABEL}")" \
    || return 1
  v="$(harness::count_output_lines bench_docker volume ls -q \
    --filter "label=${BENCH_LABEL}" --filter "label=${PART_LABEL}")" \
    || return 1
  printf '%d' $((c + n + v))
}

#######################################
# 정리 범위를 지운다 — 컨테이너 → 망 → 볼륨 차례(쓰는 컨테이너가 남으면 망 ·
# 볼륨이 지워지지 않는다). 지우기 실패는 뒤의 잔여 셈이 드러낸다.
#######################################
remove_residue() {
  local ids
  ids="$(bench_docker ps -aq --filter "label=${BENCH_LABEL}" \
    --filter "label=${PART_LABEL}")" || ids=""
  if [[ -n "${ids}" ]]; then
    # shellcheck disable=SC2086 # id 목록을 낱말로 나눈다.
    bench_docker rm -f ${ids} >/dev/null 2>&1 || true
  fi
  ids="$(bench_docker network ls -q --filter "label=${BENCH_LABEL}" \
    --filter "label=${PART_LABEL}")" || ids=""
  if [[ -n "${ids}" ]]; then
    # shellcheck disable=SC2086
    bench_docker network rm ${ids} >/dev/null 2>&1 || true
  fi
  ids="$(bench_docker volume ls -q --filter "label=${BENCH_LABEL}" \
    --filter "label=${PART_LABEL}")" || ids=""
  if [[ -n "${ids}" ]]; then
    # shellcheck disable=SC2086
    bench_docker volume rm ${ids} >/dev/null 2>&1 || true
  fi
}

#######################################
# 정리(EXIT 트랩 — INT · TERM 도 exit 로 여기 온다). 잠금을 잡은 실행만 지운다.
# 잔여가 남거나 셀 수 없으면 종료 코드가 0 이었어도 1 로 끝낸다.
# Globals:
#   scratch, lock_held
#######################################
cleanup() {
  local status=$? left
  harness::end_last_group
  if (( lock_held )); then
    remove_residue
    if left="$(count_residue)"; then
      printf '정리: c9 계약 범위 잔여 %s\n' "${left}" >&2
      if (( left != 0 && status == 0 )); then
        status=1
      fi
    else
      printf '정리: c9 계약 범위 잔여 확인 불가(docker 조회 실패)\n' >&2
      if (( status == 0 )); then
        status=1
      fi
    fi
    rm -rf -- "${LOCK_DIR}"
  fi
  if [[ -n "${scratch}" ]]; then
    rm -rf -- "${scratch:?}"
  fi
  exit "${status}"
}

#######################################
# 잠금을 잡고 앞 실행 잔여가 없는지 본다. 못 잡거나 잔여가 있으면 아무것도
# 건드리지 않고 멈춘다(남의 실행일 수 있다).
# Globals:
#   lock_held
#######################################
acquire_lock() {
  local left
  if ! mkdir -- "${LOCK_DIR}" 2>/dev/null; then
    printf '멈춤: 잠금 %s 이 있다(다른 실행 중이거나 앞 실행 잔여)\n' \
      "${LOCK_DIR}" >&2
    exit 1
  fi
  printf '%s\n' "$$" >"${LOCK_DIR}/pid"
  if ! left="$(count_residue)" || (( left != 0 )); then
    rm -rf -- "${LOCK_DIR}"
    printf '멈춤: c9 계약 범위에 앞 실행 잔여가 있거나 셀 수 없다(%s)\n' \
      "${left:-조회 실패}" >&2
    exit 1
  fi
  lock_held=1
}

#######################################
# 제품 lib 를 읽어 시험 값(중계 주소 · 디렉터리 이름 · 호출자 목록)을 정한다.
# 하위 셸에서 source 해 이 셸의 이름을 더럽히지 않는다.
# Globals:
#   relay_addr, relay_dir_name, callers_csv
#######################################
load_lib_facts() {
  local lib="${MEDIA_HOST_DIR}/${LIB_REL}" facts
  if [[ ! -f "${lib}/render_files.sh" || ! -f "${lib}/cred_relay.sh" ]]; then
    add_setup_reason "제품 lib 없음: ${LIB_REL}/render_files.sh · cred_relay.sh"
    return 1
  fi
  # shellcheck disable=SC1091,SC2016 # 하위 셸이 제품 lib 를 source 한다.
  facts="$(bash -c 'source "$1/cred_relay.sh" && IFS=, \
    && printf "%s %s %s" "${CRED_RELAY_ADDR}" "${CRED_RELAY_DIR_NAME}" \
    "${CRED_CALLERS[*]}"' _ "${lib}")" || {
    add_setup_reason 'cred_relay.sh 를 source 하지 못함'
    return 1
  }
  read -r relay_addr relay_dir_name callers_csv <<<"${facts}"
  if [[ -z "${callers_csv}" ]]; then
    add_setup_reason 'cred_relay.sh 의 주소 · 디렉터리 · 호출자 값이 비었음'
    return 1
  fi
}

#######################################
# 사이드카 이미지를 이 트리의 media/ 에서 빌드하고 USER(uid:gid)를 읽는다.
# Globals:
#   scratch, relay_owner
#######################################
build_relay_image() {
  local log="${scratch}/build.log"
  if [[ ! -f "${MEDIA_DIR}/Dockerfile" ]]; then
    add_setup_reason '제품 media/Dockerfile 없음'
    return 1
  fi
  if ! harness::run_bounded "${HARNESS_DOCKER_TIMEOUT}" "${log}" \
    docker buildx build --load --label "${BENCH_LABEL}" \
    -t "${RELAY_IMAGE}" "${MEDIA_DIR}"; then
    add_setup_reason "사이드카 이미지 빌드 실패: $(harness::log_tail "${log}")"
    return 1
  fi
  relay_owner="$(bench_docker image inspect -f '{{.Config.User}}' \
    "${RELAY_IMAGE}")" || relay_owner=""
  if [[ ! "${relay_owner}" =~ ^[0-9]+:[0-9]+$ ]]; then
    add_setup_reason "이미지 USER 가 숫자 uid:gid 가 아님: ${relay_owner}"
    return 1
  fi
}

#######################################
# 도우미 안의 판정 줄 대역 — 시작 스크립트의 say 와 같은 꼴.
# Arguments:
#   줄.
#######################################
# 도우미 스크립트로 실려 가 컨테이너 안에서 불린다.
# shellcheck disable=SC2329
say() {
  printf '%s\n' "$1"
}

#######################################
# 도우미 안의 비밀 생성 대역 — 16진 64자(시작 스크립트와 같은 꼴).
# Outputs:
#   값.
#######################################
# shellcheck disable=SC2329
generate_credential() {
  od -A n -N 32 -t x1 /dev/urandom | tr -d ' \n'
}

#######################################
# 도우미 안(root)에서 토큰 묶음을 둔다 — RUNTIME_DIR 을 0711 로 맞추고
# install_cred_tokens 를 돈다. 음성이면 그 뒤 중계 디렉터리만 설계 원안으로
# 바꾼다. 끝에 중계 디렉터리의 실제 소유 · 권한을 한 줄 낸다(토큰 값 없음).
# Globals:
#   제품 lib 의 전역 · CRED_RELAY_DIR_NAME
# Arguments:
#   양성 | 음성, 중계 소유(uid:gid), RUNTIME_DIR, 원안 소유, 원안 권한.
# Outputs:
#   RELAY_DIR <uid:gid> <권한>.
#######################################
# shellcheck disable=SC2329,SC2034 # 제품 lib 가 전역을 읽는다.
helper_install() {
  local rt="$3" dir
  render_tmps=()
  staged_tmp=""
  render_step=""
  sidecar_owner="$2"
  file_paths=()
  file_owners=()
  file_steps=()
  file_bodies=()
  chmod 0711 "${rt}"
  install_cred_tokens "${rt}"
  dir="${rt}/${CRED_RELAY_DIR_NAME}"
  if [[ "$1" == 음성 ]]; then
    chown "$4" "${dir}"
    chmod "$5" "${dir}"
  fi
  printf 'RELAY_DIR %s\n' "$(stat -c '%u:%g %a' "${dir}")"
}

#######################################
# 도우미 스크립트(함수 셋 + 제품 lib source + 호출)를 scratch 에 쓴다. 도우미
# 컨테이너가 표준 입력으로 받는다.
# Globals:
#   scratch
#######################################
write_helper_script() {
  {
    printf 'set -euo pipefail\n'
    declare -f say generate_credential helper_install
    printf 'source %s/render_files.sh\n' "${HELPER_LIB}"
    printf 'source %s/cred_relay.sh\n' "${HELPER_LIB}"
    printf 'helper_install "$@"\n'
  } >"${scratch}/helper.sh"
}

#######################################
# 사례 하나의 자원(볼륨 · 망)을 만들고 도우미로 토큰 묶음을 둔다.
# Arguments:
#   양성 | 음성, 자원 이름, 도우미 로그.
#######################################
prepare_case() {
  local kind="$1" name="$2" log="$3" lib="${MEDIA_HOST_DIR}/${LIB_REL}"
  if ! bench_docker volume create --label "${BENCH_LABEL}" \
    --label "${PART_LABEL}" "${name}" >/dev/null 2>&1; then
    harness::add_reason '볼륨 만들기 실패'
    return 1
  fi
  if ! bench_docker network create --internal --subnet "${CRED_SUBNET}" \
    --label "${BENCH_LABEL}" --label "${PART_LABEL}" "${name}" \
    >/dev/null 2>&1; then
    harness::add_reason "전용 망(${CRED_SUBNET}) 만들기 실패 — 같은 서브넷 망?"
    return 1
  fi
  if ! bench_docker run --rm -i --network none --user 0:0 \
    --label "${BENCH_LABEL}" --label "${PART_LABEL}" \
    --mount "type=volume,src=${name},dst=${HELPER_RUNTIME}" \
    --mount "type=bind,src=${lib},dst=${HELPER_LIB},readonly" \
    "${HELPER_IMAGE}" bash -s -- "${kind}" "${relay_owner}" \
    "${HELPER_RUNTIME}" "${ORIGINAL_DIR_OWNER}" "${ORIGINAL_DIR_MODE}" \
    <"${scratch}/helper.sh" >"${log}" 2>&1; then
    harness::add_reason "도우미 실패: $(harness::log_tail "${log}")"
    return 1
  fi
}

#######################################
# 도우미가 낸 중계 디렉터리 소유 · 권한이 기대와 같은지 본다.
# Arguments:
#   도우미 로그, 기대 「uid:gid 권한」.
#######################################
expect_relay_dir() {
  local got
  got="$(awk '$1 == "RELAY_DIR" { print $2, $3 }' "$1")"
  if [[ "${got}" != "$2" ]]; then
    harness::add_reason "중계 디렉터리 「${got:-없음}」 ≠ 기대 「$2」"
    return 1
  fi
}

#######################################
# 실물 중계를 띄운다 — 이미지 USER · cap 없음 · 읽기 전용 루트 · 권한 상승
# 막음 · 전용 망의 운영 주소. 볼륨은 RUNTIME_DIR 경로에 읽기 전용이다.
# Arguments:
#   자원 이름(컨테이너 · 볼륨 · 망 같은 이름).
#######################################
start_relay() {
  local name="$1"
  bench_docker run -d --name "${name}" \
    --label "${BENCH_LABEL}" --label "${PART_LABEL}" \
    --network "${name}" --ip "${relay_addr}" \
    --cap-drop ALL --read-only --security-opt no-new-privileges \
    --entrypoint "${RELAY_BIN}" \
    --mount "type=volume,src=${name},dst=${RELAY_RUNTIME},readonly" \
    -e "CRED_RELAY_LISTEN=${relay_addr}:${RELAY_PORT}" \
    -e "CRED_RELAY_CALLER_DIR=${RELAY_RUNTIME}/${relay_dir_name}" \
    -e "CRED_RELAY_CALLERS=${callers_csv}" \
    "${RELAY_IMAGE}" >/dev/null 2>&1
}

#######################################
# 중계가 듣기 시작하거나(로그) 끝날 때까지 상한만큼 기다리며 로그를 받는다.
# Arguments:
#   컨테이너 이름, 로그 파일.
#######################################
wait_relay() {
  local deadline=$((SECONDS + RELAY_TIMEOUT)) running
  while (( SECONDS < deadline )); do
    bench_docker logs "$1" >"$2" 2>&1 || true
    if grep -qF -- "${LOG_STARTED}" "$2"; then
      return 0
    fi
    running="$(bench_docker inspect -f '{{.State.Running}}' "$1")" \
      || running=""
    if [[ "${running}" == false ]]; then
      bench_docker logs "$1" >"$2" 2>&1 || true
      return 0
    fi
    sleep 0.5
  done
}

# 컨테이너의 종료 코드를 낸다.
exit_code() {
  bench_docker inspect -f '{{.State.ExitCode}}' "$1"
}

#######################################
# 사례 자원을 지우고 잔여 0 을 확인한다.
#######################################
finish_case() {
  local left
  remove_residue
  if ! left="$(count_residue)"; then
    harness::add_reason '잔여 확인 불가'
    return 1
  fi
  if (( left != 0 )); then
    harness::add_reason "사례 끝 잔여 ${left}"
    return 1
  fi
}

#######################################
# 양성 판정 — 듣기 시작 · token_file 없음 · healthcheck 0 · TERM 뒤 정상 종료.
# Arguments:
#   자원 이름, 중계 로그.
#######################################
judge_positive() {
  local name="$1" log="$2" ok=0 code
  wait_relay "${name}" "${log}"
  if grep -qF -- "${LOG_TOKEN_FILE}" "${log}"; then
    harness::add_reason '중계가 token_file 로 거부함'
    ok=1
  fi
  if ! grep -qF -- "${LOG_STARTED}" "${log}"; then
    harness::add_reason \
      "cred_relay_started 없음: $(harness::log_tail "${log}")"
    return 1
  fi
  if ! bench_docker exec "${name}" "${RELAY_BIN}" healthcheck \
    >/dev/null 2>&1; then
    harness::add_reason 'healthcheck 실패'
    ok=1
  fi
  bench_docker stop -t "${STOP_GRACE}" "${name}" >/dev/null 2>&1 || true
  bench_docker logs "${name}" >"${log}" 2>&1 || true
  code="$(exit_code "${name}")" || code="?"
  if [[ "${code}" != 0 ]] || ! grep -qF -- "${LOG_STOPPED}" "${log}"; then
    harness::add_reason "TERM 뒤 종료 ${code} · cred_relay_stopped 줄 수 \
$(grep -cF -- "${LOG_STOPPED}" "${log}" || true)"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 음성 판정 — token_file 거부 · 종료 1 · 듣기 시작 없음.
# Arguments:
#   자원 이름, 중계 로그.
#######################################
judge_negative() {
  local name="$1" log="$2" code
  wait_relay "${name}" "${log}"
  code="$(exit_code "${name}")" || code="?"
  if [[ "${code}" != 1 ]] || ! grep -qF -- "${LOG_TOKEN_FILE}" "${log}" \
    || grep -qF -- "${LOG_STARTED}" "${log}"; then
    harness::add_reason \
      "token_file 거부 · 종료 1 이 아님(종료 ${code}): \
$(harness::log_tail "${log}")"
    return 1
  fi
}

#######################################
# 사례 하나 — 준비 → 중계 → 판정 → 정리 · 잔여 0.
# Globals:
#   rep, scratch, relay_owner
# Arguments:
#   양성 | 음성, 자원 이름 끝(pos | neg).
#######################################
run_kind() {
  local kind="$1" name="${NAME_PREFIX}-r${rep}-$2" log ok=0
  log="${scratch}/r${rep}-$2"
  if [[ -n "${setup_reason}" ]]; then
    harness::add_reason "준비 실패: ${setup_reason}"
    return 1
  fi
  # 디렉터리 소유 · 권한이 기대와 달라도 중계는 띄워 판정한다 — 실패 원인에
  # 중계 자신의 줄(token_file 등)이 함께 남게.
  if ! prepare_case "${kind}" "${name}" "${log}.helper"; then
    ok=1
  elif ! start_relay "${name}"; then
    harness::add_reason '중계 띄우기 실패'
    ok=1
  elif [[ "${kind}" == 양성 ]]; then
    expect_relay_dir "${log}.helper" "0:${relay_owner#*:} 750" || ok=1
    judge_positive "${name}" "${log}.relay" || ok=1
  else
    expect_relay_dir "${log}.helper" \
      "${ORIGINAL_DIR_OWNER} ${ORIGINAL_DIR_MODE#0}" || ok=1
    judge_negative "${name}" "${log}.relay" || ok=1
  fi
  finish_case || ok=1
  return "${ok}"
}

case_positive() {
  run_kind 양성 pos
}

case_negative() {
  run_kind 음성 neg
}

main() {
  scratch="$(mktemp -d)"
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  acquire_lock
  load_lib_facts || true
  build_relay_image || true
  write_helper_script
  for (( rep = 1; rep <= REPEATS; rep++ )); do
    harness::run_case $((rep * 2 - 1)) \
      "양성 회차 ${rep} — 설치 그대로(root:중계 그룹 0750)면 중계가 열고 듣는다" \
      case_positive
    harness::run_case $((rep * 2)) \
      "음성 회차 ${rep} — 원안(root:root 0711)이면 token_file 로 거부" \
      case_negative
  done
  harness::summary "${CASE_COUNT}"
}

main "$@"
