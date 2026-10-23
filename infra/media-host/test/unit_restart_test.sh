#!/usr/bin/env bash
#
# Media 상자 systemd 유닛 재기동 시험 — prod 유닛을 시험 이름으로 설치해 재기동 ·
# 정지 · 데몬 흔들기 · 크래시 경로에서 유닛과 컨테이너가 기대대로 움직이는지 본다.
#
# 사례 1 – 3 · 20 · 21 은 systemd 없이 돈다(유닛 파일 지시어 · 시험 compose 구성 ·
# 가짜 aws 상태 파일 · 저널 건수 · 렌더 자리 건수 도구). 사례 4 – 19 는 리눅스 ·
# systemd · sudo 가 있는 곳(infra-ci ubuntu 러너)에서만 돈다. 없으면 그 사례들은
# FAIL 이다 — 건너뛰는 갈래는 없다.
#
# 사례 13 – 17 은 호스트를 흔든다(docker 엔진 재시작 · dockerd kill -9 · 유닛
# cgroup · compose 플러그인 SIGKILL · 유닛 밖 스택). 그래서 CI 러너
# (GITHUB_ACTIONS=true) 이거나 PCBENCH_ALLOW_HOST_DISRUPTION=1 을 준 일회용 상자
# 에서만 돌고, 그 밖에서는 거부해 FAIL 이다 — 개발 머신에서 잘못 돌려 같은 엔진의
# 다른 컨테이너를 내리지 않게(실수로 돌린 경우의 보호 — 위조 가능한 표식이라 보안 경계는 아니다).
#
# 설치 방식:
#   - systemd/pokeclip-media.service 를 바이트 그대로
#     /etc/systemd/system/pcbench-edge-proxy.service 로 복사한다.
#   - bin/ 을 /opt/pokeclip-media/bin/ 에 설치한다(유닛의 ExecStart 경로).
#   - drop-in pcbench-edge-proxy.service.d/test.conf 에는 Environment= ·
#     EnvironmentFile= 만 둔다 — 값만 바꾸고 동작 지시어는 prod 유닛 그대로다.
#   - 이미 /opt/pokeclip-media 나 같은 이름의 유닛이 있으면 설치하지 않고
#     FAIL 이다 — prod 상자에서 잘못 돌려도 덮어쓰지 않는다.
#
# 사례 제목 앞의 기호(① – ⑧)는 재기동 시나리오 표지이고 출력에 그대로 남는다.
# 같은 시나리오를 나눈 사례는 같은 기호에 「확장」 · a · b 를 붙인다. 사례와
# 기호: 6 ① 종료 0 · 7 ② 종료 1 · 8 ⑥ 사이드카 종료 반복 · 9 ⑧ 태그 저널 보존 ·
# 10 ③ stop · 11 ③ 확장(사이드카 재시작 중 stop) · 12 ④ CDN 비밀 빈 값 ·
# 13 ⑦a docker 재시작 · 14 ⑦b dockerd kill -9 · 18 ⑤ 정리(잔여 0). 동작 사례는
# lib/unit_runtime_cases.sh 에 있다. 사례 15 – 17 은 크래시 경로(compose 플러그인
# · 유닛 cgroup SIGKILL · 유닛 밖에 남은 스택 — 옛 비밀을 붙든 컨테이너가 남지
# 않는지)이고 lib/unit_crash_cases.sh 에 있다. 사례 19 는 기동 중(비밀 조회 중)
# 정지다.
#
# 한 바퀴 약 8분이다. 반복(N=3)은 부르는 쪽(CI)이 이 시험을 여러 번 돌린다.
#
# 사용: bash infra/media-host/test/unit_restart_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
MEDIA_HOST_DIR="$(cd -- "${TEST_DIR}/.." && pwd)"
readonly MEDIA_HOST_DIR
# shellcheck source=lib/harness.sh
source "${TEST_DIR}/lib/harness.sh"
# shellcheck source=lib/unit_runtime_cases.sh
source "${TEST_DIR}/lib/unit_runtime_cases.sh"
# shellcheck source=lib/unit_crash_cases.sh
source "${TEST_DIR}/lib/unit_crash_cases.sh"

readonly PROJECT='pcbench-edge-proxy'
readonly UNIT_NAME='pcbench-edge-proxy'
readonly SYSTEMD_DIR='/etc/systemd/system'
readonly UNIT_FILE="${SYSTEMD_DIR}/${UNIT_NAME}.service"
readonly DROPIN_DIR="${SYSTEMD_DIR}/${UNIT_NAME}.service.d"
readonly DROPIN_FILE="${DROPIN_DIR}/test.conf"
readonly INSTALL_ROOT='/opt/pokeclip-media'
readonly INSTALL_BIN="${INSTALL_ROOT}/bin"
readonly PROD_UNIT_REL='systemd/pokeclip-media.service'
readonly PROD_UNIT_NAME='pokeclip-media'
readonly UNIT_COMPOSE="${TEST_DIR}/compose.unit.yml"
readonly FAKEBIN="${TEST_DIR}/fakebin"
readonly MTX_TEMPLATE="${TEST_DIR}/mediamtx.test.yml.tmpl"
readonly IMAGES_FILE="${MEDIA_HOST_DIR}/images.json"
readonly ECHO_IMAGE='pcbench-edge-proxy/echo:local'
readonly ECHO_ADMIN_PORT=28199
readonly FAKE_ARN='pcbench-edge-proxy-fake-cdn-secret-arn'
readonly FAKE_ECR_HOST='pcbench-edge-proxy.invalid'
readonly FAKE_SECRET='pcbench-edge-proxy-fake-cdn-secret'
# prod 유닛의 PATH(Environment=) — 시험 유닛은 앞에 fakebin 만 붙인다.
readonly UNIT_BASE_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin'
readonly CONFIG_TIMEOUT=60
readonly SYSTEMCTL_TIMEOUT=120
readonly CASE_COUNT=21

# prod 유닛 지시어(설명 줄 Description= 은 빼고) — 「절|키=값」 한 줄에 하나.
# 줄 잇기(끝 백슬래시)는 systemd 처럼 이어 붙인 한 줄로 적는다. 담는 것: docker
# 뒤에 뜨기(Requires · After) · 재시도 한도 없음 · 정지 두 단계(주 프로세스 TERM
# 뒤 down — 그 TERM 의 종료 130 은 성공) · ExecStopPost 도 down(크래시 경로에서
# ExecStop 을 건너뛰어도 컨테이너를 내림) · LimitCORE=0(코어 덤프에 비밀이 남지
# 않게).
# shellcheck disable=SC2016 # 유닛 원문 — $ 는 펼치지 않는다.
readonly EXPECTED_UNIT_LINES='Unit|Requires=docker.service
Unit|After=docker.service
Unit|StartLimitIntervalSec=0
Service|Type=simple
Service|EnvironmentFile=/etc/pokeclip-media/media.env
Service|Environment=DOCKER_CONFIG=/root/.docker
Service|Environment=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
Service|Environment=MEDIA_COMPOSE_PROJECT=pokeclip-media
Service|ExecStart=/opt/pokeclip-media/bin/start-media.sh
Service|ExecStop=/bin/sh -c '"'"'kill -TERM "$$MAINPID" 2>/dev/null; i=0;    while kill -0 "$$MAINPID" 2>/dev/null && [ "$$i" -lt 150 ]; do    sleep 0.2; i=$$((i + 1)); done'"'"'
Service|ExecStop=/opt/pokeclip-media/bin/stop-media.sh
Service|ExecStopPost=/opt/pokeclip-media/bin/stop-media.sh
Service|SuccessExitStatus=130
Service|Restart=always
Service|RestartSec=20
Service|LimitCORE=0
Install|WantedBy=multi-user.target'

scratch=""
setup_reason=""
systemd_reason=""
install_reason=""
installed=0
health_port=""
health_path=""
nginx_image=""
env_file=""
mode_file=""
runtime_dir=""
docker_config=""
compose_version=""
unit_env=()

cleanup() {
  local status=$?
  harness::end_last_group
  if (( installed )); then
    unit::teardown >/dev/null 2>&1 || true
  fi
  if [[ -n "${scratch}" ]]; then
    rm -rf -- "${scratch:?}" 2>/dev/null \
      || sudo -n rm -rf -- "${scratch:?}" 2>/dev/null || true
  fi
  exit "${status}"
}

add_setup_reason() {
  setup_reason="${setup_reason:+${setup_reason}; }$1"
}

#######################################
# 유닛 파일을 「절|키=값」 줄로 펼친다. 빈 줄 · 주석 · Description= 은 뺀다.
# 끝이 백슬래시인 줄은 systemd 처럼 그 글자를 공백으로 바꾸고 다음 줄을 그대로
# 이어 붙인다.
# Arguments:
#   유닛 파일.
# Outputs:
#   줄을 파일 순서대로 낸다.
#######################################
unit_lines() {
  awk '
    cont == "" && /^[[:space:]]*($|[#;])/ { next }
    cont == "" && /^\[.*\]$/ { section = substr($0, 2, length($0) - 2); next }
    {
      line = $0
      if (cont != "") { line = cont line; cont = "" }
      else sub(/^[[:space:]]+/, "", line)
      if (line ~ /\\$/) { sub(/\\$/, " ", line); cont = line; next }
      sub(/[[:space:]]+$/, "", line)
      if (line ~ /^Description=/) next
      print section "|" line
    }' "$1"
}

#######################################
# 사례 1 — prod 유닛 지시어가 기대 값과 정확히 같다(빠짐 · 더함 · 다름 0).
# 같은 키가 여러 번 나오는 지시어(ExecStop)는 실행 순서이므로 순서도 본다.
#######################################
check_case_1() {
  local file="${MEDIA_HOST_DIR}/${PROD_UNIT_REL}" want got diff lines ok=0
  if [[ ! -f "${file}" ]]; then
    harness::add_reason "${PROD_UNIT_REL} 없음"
    return 1
  fi
  lines="$(unit_lines "${file}")"
  want="$(printf '%s\n' "${EXPECTED_UNIT_LINES}" | LC_ALL=C sort)"
  got="$(printf '%s\n' "${lines}" | LC_ALL=C sort)"
  if [[ "${got}" != "${want}" ]]; then
    diff="$(diff <(printf '%s\n' "${want}") <(printf '%s\n' "${got}") \
      | grep -E '^[<>]' | sed -e 's/^</빠짐/' -e 's/^>/더함/' || true)"
    harness::add_reason "지시어 차이: $(printf '%s' "${diff}" | tr '\n' ';')"
    ok=1
  fi
  want="$(printf '%s\n' "${EXPECTED_UNIT_LINES}" | grep -F 'Service|ExecStop=' \
    || true)"
  got="$(printf '%s\n' "${lines}" | grep -F 'Service|ExecStop=' || true)"
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason 'ExecStop 순서가 기대(주 프로세스 TERM → stop-media.sh)와 다름'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 시험 compose 에 줄 값(구조 확인용 — 띄우지 않는다)을 넣고 docker compose
# 를 부른다.
# Arguments:
#   docker compose 하위 명령과 인자.
#######################################
unit_compose_static() {
  env PCBENCH_ECHO_IMAGE="${ECHO_IMAGE}" \
    PCBENCH_NGINX_IMAGE='pcbench-edge-proxy/nginx:unit-test' \
    PCBENCH_NGINX_CONF="${scratch}/static-nginx.conf" \
    PCBENCH_HEALTH_PORT=28184 PCBENCH_ECHO_ADMIN_PORT="${ECHO_ADMIN_PORT}" \
    PCBENCH_SIDECAR_EXIT_DELAY=3600s RUNTIME_DIR="${scratch}/static-runtime" \
    docker compose -p "${PROJECT}" -f "${UNIT_COMPOSE}" "$@"
}

#######################################
# 사례 2 — 시험 compose 가 compose 검증을 통과하고, 역할 서비스가 템플릿과
# 같은 이름 셋이며, 사이드카 로그는 journald 고정 tag · 출력 연결 둘은
# json-file 이다(유닛 시험의 ⑥ · ⑧ 판별 신호가 이 로그 자리에 기댄다).
#######################################
check_case_2() {
  local log="${scratch}/unit-compose.log" json="${scratch}/unit-compose.json"
  local services logging
  if [[ ! -f "${UNIT_COMPOSE}" ]]; then
    harness::add_reason 'test/compose.unit.yml 없음'
    return 1
  fi
  if ! harness::bounded "${CONFIG_TIMEOUT}" unit_compose_static config -q \
    >"${log}" 2>&1; then
    harness::add_reason "config -q 실패: $(harness::log_tail "${log}")"
    return 1
  fi
  harness::bounded "${CONFIG_TIMEOUT}" unit_compose_static config \
    --no-normalize --format json >"${json}" 2>"${log}" || true
  services="$(jq -r '.services | keys | join(" ")' "${json}" 2>/dev/null)" \
    || services='(구성 JSON 을 읽지 못함)'
  logging="$(jq -c '.services | map_values(.logging)
    | [.mediamtx.driver, .nginx.driver, .sidecar.driver, .sidecar.options]' \
    "${json}" 2>/dev/null)" || logging='(구성 JSON 을 읽지 못함)'
  local ok=0
  if [[ "${services}" != 'mediamtx nginx sidecar' ]]; then
    harness::add_reason "서비스 셋 기대 mediamtx nginx sidecar · 실제 ${services}"
    ok=1
  fi
  if [[ "${logging}" != \
    '["json-file","json-file","journald",{"tag":"pokeclip-media-sidecar"}]' ]]
  then
    harness::add_reason "로깅 기대 json-file · json-file · journald(tag) · \
실제 ${logging}"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 가짜 aws 를 시작 스크립트와 같은 argv 로 부른다.
# Arguments:
#   모드 파일 경로(빈 문자열이면 넘기지 않음).
#######################################
call_fake_aws() {
  local -a extra=()
  [[ -z "$1" ]] || extra=("PCBENCH_FAKE_AWS_MODE_FILE=$1")
  env ${extra[@]+"${extra[@]}"} "${FAKEBIN}/aws" secretsmanager \
    get-secret-value --secret-id "${FAKE_ARN}" --query SecretString \
    --output text
}

#######################################
# 사례 20 — 저널 건수 도구(unit::journal_count)는 조회 실패를 0 건으로 바꾸지
# 않는다. 가짜 sudo · journalctl 을 PATH 맨 앞에 두고 세 갈래를 본다: 조회
# 실패(종료 1 · 출력 없음) → 실패 · 출력 없음, 정상 빈 저널 → 0, 맞는 줄 둘 →
# 2. systemd 없이 돈다.
#######################################
check_case_20() {
  local ok=0 dir="${scratch}/journal-fake" mode out status want
  mkdir -p -- "${dir}"
  # shellcheck disable=SC2016 # 가짜 sudo 의 본문이다 — 그 셸이 펼친다.
  printf '%s\n' '#!/bin/sh' '[ "$1" = -n ] && shift' 'exec "$@"' \
    >"${dir}/sudo"
  # shellcheck disable=SC2016 # 가짜 journalctl 의 본문이다 — 그 셸이 펼친다.
  printf '%s\n' '#!/bin/sh' '[ "$1" = --sync ] && exit 0' \
    'case "${PCBENCH_FAKE_JOURNAL:-}" in' \
    '  fail) exit 1 ;;' \
    '  lines) printf "MEDIA START FAIL a\nother\nMEDIA START FAIL b\n" ;;' \
    'esac' >"${dir}/journalctl"
  chmod +x "${dir}/sudo" "${dir}/journalctl"
  for mode in fail empty lines; do
    case "${mode}" in
      fail) want='실패:' ;;
      empty) want='0:0' ;;
      *) want='0:2' ;;
    esac
    status=0
    out="$(PATH="${dir}:${PATH}" PCBENCH_FAKE_JOURNAL="${mode}" \
      unit::journal_count -u pcbench-none 0 'MEDIA START FAIL')" || status=$?
    if (( status != 0 )); then
      out="실패:${out}"
    else
      out="0:${out}"
    fi
    if [[ "${out}" != "${want}" ]]; then
      harness::add_reason "갈래 ${mode}: 「${out}」(기대 「${want}」)"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 21 — 렌더 자리 건수 도구(unit::render_count · crash::render_dirs)는 조회
# 실패를 0 건으로 바꾸지 않는다. 가짜 sudo · find 를 PATH 맨 앞에 두고 본다:
# find 실패 → 실패 · 출력 없음, 빈 결과 → 0, 한 건 → 1, 렌더 자리가 없음 → 0.
# 디렉터리 수는 sudo 전체 실패 · sudo 는 되고 조회(true 밖의 명령)만 실패 →
# 「확인 불가」 · 종료 1, 빈 결과 → 0, 한 건 → 1. systemd 없이 돈다.
#######################################
check_case_21() {
  local ok=0 dir="${scratch}/render-fake" mode out status want
  local runtime_dir="${scratch}/render-fake-runtime"
  mkdir -p -- "${dir}" "${runtime_dir}"
  # shellcheck disable=SC2016 # 가짜 sudo 의 본문이다 — 그 셸이 펼친다.
  printf '%s\n' '#!/bin/sh' '[ -n "${PCBENCH_FAKE_SUDO_FAIL:-}" ] && exit 1' \
    '[ "$1" = -n ] && shift' \
    '[ -n "${PCBENCH_FAKE_SUDO_ONLY_TRUE:-}" ] && [ "$1" != true ] && exit 1' \
    'exec "$@"' >"${dir}/sudo"
  # shellcheck disable=SC2016 # 가짜 find 의 본문이다 — 그 셸이 펼친다.
  printf '%s\n' '#!/bin/sh' 'case "${PCBENCH_FAKE_FIND:-}" in' \
    '  fail) exit 1 ;;' '  one) printf "%s\n" "$1/mediamtx.yml" ;;' 'esac' \
    >"${dir}/find"
  chmod +x "${dir}/sudo" "${dir}/find"
  for mode in fail empty one absent; do
    case "${mode}" in
      fail) want='실패:' ;;
      one) want='0:1' ;;
      *) want='0:0' ;;
    esac
    status=0
    if [[ "${mode}" == absent ]]; then
      out="$(runtime_dir="${scratch}/render-fake-none" PATH="${dir}:${PATH}" \
        PCBENCH_FAKE_FIND=fail unit::render_count -name mediamtx.yml)" \
        || status=$?
    else
      out="$(PATH="${dir}:${PATH}" PCBENCH_FAKE_FIND="${mode}" \
        unit::render_count -name mediamtx.yml)" || status=$?
    fi
    if (( status != 0 )); then
      out="실패:${out}"
    else
      out="0:${out}"
    fi
    if [[ "${out}" != "${want}" ]]; then
      harness::add_reason "렌더 파일 갈래 ${mode}: 「${out}」(기대 「${want}」)"
      ok=1
    fi
  done
  for mode in sudo-fail only-true empty one; do
    status=0
    case "${mode}" in
      sudo-fail)
        want='실패:확인 불가'
        out="$(PATH="${dir}:${PATH}" PCBENCH_FAKE_SUDO_FAIL=1 \
          crash::render_dirs)" || status=$?
        ;;
      only-true)
        want='실패:확인 불가'
        out="$(PATH="${dir}:${PATH}" PCBENCH_FAKE_SUDO_ONLY_TRUE=1 \
          PCBENCH_FAKE_FIND=one crash::render_dirs)" || status=$?
        ;;
      *)
        if [[ "${mode}" == one ]]; then want='0:1'; else want='0:0'; fi
        out="$(PATH="${dir}:${PATH}" PCBENCH_FAKE_FIND="${mode}" \
          crash::render_dirs)" || status=$?
        ;;
    esac
    if (( status != 0 )); then
      out="실패:${out}"
    else
      out="0:${out}"
    fi
    if [[ "${out}" != "${want}" ]]; then
      harness::add_reason "렌더 자리 디렉터리 갈래 ${mode}: 「${out}」(기대 「${want}」)"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 3 — 가짜 aws 상태 파일 모드(④ 의 이음매): 파일 내용으로 모드가 바뀌고,
# 지정한 파일이 없으면 실패한다.
#######################################
check_case_3() {
  local file="${scratch}/aws-mode-check" out ok=0 status
  printf 'empty\n' >"${file}"
  out="$(call_fake_aws "${file}" && printf x)" || out='(실패)'
  if [[ "${out}" != $'\nx' ]]; then
    harness::add_reason "모드 파일 empty → 빈 값 기대 · 실제 \
$(printf '%s' "${out%x}" | tr '\n' ' ')"
    ok=1
  fi
  printf 'ok\n' >"${file}"
  out="$(call_fake_aws "${file}")" || out='(실패)'
  if [[ "${out}" != "${FAKE_SECRET}" ]]; then
    harness::add_reason "모드 파일 ok → 시험 값 기대 · 실제 ${out}"
    ok=1
  fi
  rm -f -- "${file}"
  status=0
  call_fake_aws "${file}" >/dev/null 2>&1 || status=$?
  if (( status != 2 )); then
    harness::add_reason "모드 파일 없음 → 종료 2 기대 · 실제 ${status}"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 동작 사례가 돌 수 있는 곳인지 본다(리눅스 · PID 1 systemd · 도구 · sudo).
# Globals:
#   systemd_reason
#######################################
check_systemd_host() {
  local tool
  if [[ "$(uname -s)" != Linux || ! -d /run/systemd/system ]]; then
    systemd_reason='systemd 없음 — 리눅스 · systemd 러너(infra-ci)에서 돈다'
    return 1
  fi
  for tool in systemctl journalctl systemd-analyze sudo docker jq curl; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      systemd_reason="도구 없음: ${tool}"
      return 1
    fi
  done
  if ! sudo -n true 2>/dev/null; then
    systemd_reason='암호 없는 sudo 가 안 됨'
    return 1
  fi
}

#######################################
# prod 상자 · 앞선 실행의 잔여가 없는지 본다. 있으면 설치하지 않는다.
#######################################
check_clean_host() {
  local left
  if [[ -e "${INSTALL_ROOT}" || -e "${UNIT_FILE}" || -e "${DROPIN_DIR}" \
    || -e "${SYSTEMD_DIR}/${PROD_UNIT_NAME}.service" ]]; then
    install_reason="이미 설치가 있다(${INSTALL_ROOT} · 유닛) — 덮어쓰지 않는다"
    return 1
  fi
  left="$(harness::count_output_lines docker ps -a -q --filter \
    "label=com.docker.compose.project=${PROJECT}")" || left='?'
  if [[ "${left}" != 0 ]]; then
    install_reason="시험 전부터 프로젝트 컨테이너 ${left}개"
    return 1
  fi
}

#######################################
# 시험 env 파일(유닛 EnvironmentFile)과 같은 값의 배열을 만든다. 사이드카
# 종료 지연만 사례마다 바꾼다.
# Globals:
#   unit_env, env_file
# Arguments:
#   사이드카 역할 종료 지연.
#######################################
write_unit_env() {
  unit_env=(
    "MEDIA_EXPECTED_COMPOSE_VERSION=${compose_version}"
    "CDN_SECRET_ARN=${FAKE_ARN}"
    "ECR_REGISTRY_HOST=${FAKE_ECR_HOST}"
    "PCBENCH_FAKE_AWS_MODE_FILE=${mode_file}"
    "PCBENCH_ECHO_IMAGE=${ECHO_IMAGE}"
    "PCBENCH_NGINX_IMAGE=${nginx_image}"
    "PCBENCH_NGINX_CONF=${scratch}/nginx.conf"
    "PCBENCH_HEALTH_PORT=${health_port}"
    "PCBENCH_ECHO_ADMIN_PORT=${ECHO_ADMIN_PORT}"
    "PCBENCH_SIDECAR_EXIT_DELAY=$1"
  )
  printf '%s\n' "${unit_env[@]}" >"${env_file}"
}

#######################################
# drop-in 내용을 낸다 — Environment= · EnvironmentFile= 만.
#######################################
dropin_content() {
  printf '[Service]\n'
  printf 'EnvironmentFile=\n'
  printf 'EnvironmentFile=%s\n' "${env_file}"
  printf 'Environment=PATH=%s:%s\n' "${FAKEBIN}" "${UNIT_BASE_PATH}"
  printf 'Environment=DOCKER_CONFIG=%s\n' "${docker_config}"
  printf 'Environment=MEDIA_COMPOSE_FILE=%s\n' "${UNIT_COMPOSE}"
  printf 'Environment=MEDIA_MTX_TEMPLATE=%s\n' "${MTX_TEMPLATE}"
  printf 'Environment=MEDIA_COMPOSE_PROJECT=%s\n' "${PROJECT}"
  printf 'Environment=RUNTIME_DIR=%s\n' "${runtime_dir}"
}

# 헬스 포트 · 경로 하나만 고정 200 인 nginx 최소 설정.
write_nginx_conf() {
  cat >"${scratch}/nginx.conf" <<EOF
worker_processes 1;
events {
  worker_connections 64;
}
http {
  access_log off;
  server {
    listen ${health_port};
    location = ${health_path} {
      return 200 "ok\n";
    }
    location / {
      return 404;
    }
  }
}
EOF
  # 컨테이너 안 root(cap 없음)가 다른 계정 소유 파일을 읽으려면 other 읽기가
  # 있어야 한다.
  chmod 0644 "${scratch}/nginx.conf"
}

#######################################
# 이미지 · 시험 값을 준비한다(동작 사례 공통).
#######################################
prepare_assets() {
  local log="${scratch}/prepare.log" src digest
  health_port="$(jq -r '.proxy_health_port // empty' \
    "${TEST_DIR}/render_vars.json")" || health_port=""
  health_path="$(jq -r '.media_health_path // empty' \
    "${TEST_DIR}/render_vars.json")" || health_path=""
  src="$(jq -r '.nginx.source // empty' "${IMAGES_FILE}")" || src=""
  digest="$(jq -r '.nginx.digest // empty' "${IMAGES_FILE}")" || digest=""
  if [[ -z "${health_port}" || "${health_path}" != /* || -z "${src}" \
    || -z "${digest}" ]]; then
    install_reason='render_vars.json · images.json 값을 읽지 못함'
    return 1
  fi
  nginx_image="${src%:*}@${digest}"
  if ! harness::run_bounded "${HARNESS_DOCKER_TIMEOUT}" "${log}" \
    docker build -t "${ECHO_IMAGE}" "${TEST_DIR}/echo"; then
    install_reason="에코 이미지 빌드 실패: $(harness::log_tail "${log}")"
    return 1
  fi
  if ! harness::run_bounded "${HARNESS_DOCKER_TIMEOUT}" "${log}" \
    docker pull "${nginx_image}"; then
    install_reason="nginx 이미지 받기 실패: $(harness::log_tail "${log}")"
    return 1
  fi
  write_nginx_conf
}

#######################################
# 유닛 · 스크립트 · drop-in 을 설치한다.
# Globals:
#   installed, install_reason, compose_version, env_file, mode_file,
#   runtime_dir, docker_config
#######################################
install_unit() {
  local prod="${MEDIA_HOST_DIR}/${PROD_UNIT_REL}"
  check_clean_host || return 1
  prepare_assets || return 1
  env_file="${scratch}/unit.env"
  mode_file="${scratch}/aws.mode"
  runtime_dir="${scratch}/runtime"
  docker_config="${scratch}/docker-config"
  mkdir -p -- "${docker_config}"
  printf '{"credHelpers":{"%s":"ecr-login"}}\n' "${FAKE_ECR_HOST}" \
    >"${docker_config}/config.json"
  printf 'ok\n' >"${mode_file}"
  compose_version="$(sudo -n env DOCKER_CONFIG="${docker_config}" \
    PATH="${UNIT_BASE_PATH}" docker compose version --short 2>/dev/null)" \
    || compose_version=""
  if [[ -z "${compose_version}" ]]; then
    install_reason='root 의 docker compose 판을 읽지 못함'
    return 1
  fi
  write_unit_env "${UNIT_IDLE_DELAY}"
  # 여기부터 호스트를 바꾼다 — 실패해도 teardown 이 거둔다.
  installed=1
  if ! sudo -n install -d -m 0755 "${INSTALL_BIN}" \
    || ! sudo -n cp -R -- "${MEDIA_HOST_DIR}/bin/." "${INSTALL_BIN}/" \
    || ! sudo -n install -m 0644 -- "${prod}" "${UNIT_FILE}" \
    || ! sudo -n install -d -m 0755 "${DROPIN_DIR}" \
    || ! dropin_content >"${scratch}/test.conf" \
    || ! sudo -n install -m 0644 -- "${scratch}/test.conf" "${DROPIN_FILE}" \
    || ! sudo -n systemctl daemon-reload; then
    install_reason='설치 명령 실패'
    return 1
  fi
  if ! sudo -n cmp -s -- "${prod}" "${UNIT_FILE}"; then
    install_reason='설치한 유닛이 prod 파일과 바이트가 다름'
    return 1
  fi
}

#######################################
# 동작 사례의 선행 조건. 실패 원인은 사례 원인으로 옮긴다.
#######################################
require_installed() {
  if [[ -n "${systemd_reason}" ]]; then
    harness::add_reason "${systemd_reason}"
    return 1
  fi
  if [[ -n "${install_reason}" ]]; then
    harness::add_reason "설치 실패 — ${install_reason}"
    return 1
  fi
}

#######################################
# 호스트를 흔드는 사례(13 – 17)의 선행 조건 — CI 러너 · 명시 표식에서만 돈다.
#######################################
require_host_disruption() {
  if [[ "${GITHUB_ACTIONS:-}" == true \
    || "${PCBENCH_ALLOW_HOST_DISRUPTION:-}" == 1 ]]; then
    return 0
  fi
  harness::add_reason "호스트를 흔드는 사례 — GITHUB_ACTIONS=true(CI) 또는 \
PCBENCH_ALLOW_HOST_DISRUPTION=1(일회용 상자)일 때만 돈다"
  return 1
}

#######################################
# 사례 4 — systemd-analyze verify 가 설치한 유닛(drop-in 포함)을 경고 없이
# 받는다. 경고는 이 유닛 · drop-in · 설치 경로가 든 줄만 본다 — 러너의 다른
# 유닛(docker.service 등)에 대한 말은 이 유닛의 결함이 아니다.
#######################################
check_case_4() {
  local log="${scratch}/analyze.log" own
  require_installed || return 1
  if ! harness::run_bounded "${CONFIG_TIMEOUT}" "${log}" \
    sudo -n systemd-analyze verify "${UNIT_FILE}"; then
    harness::add_reason "verify 실패: $(harness::log_tail "${log}")"
    return 1
  fi
  own="$(grep -F -e "${UNIT_NAME}" -e "${INSTALL_ROOT}" "${log}" || true)"
  if [[ -n "${own}" ]]; then
    harness::add_reason "verify 경고: $(printf '%s' "${own}" | tr '\n' ';')"
    return 1
  fi
}

#######################################
# 사례 5 — 이음매: drop-in 은 Environment= · EnvironmentFile= 만이고, 실효
# 동작 값이 prod 유닛과 같다.
#######################################
check_case_5() {
  local ok=0 bad prop want got
  require_installed || return 1
  bad="$(sudo -n cat -- "${DROPIN_FILE}" | grep -v -E \
    '^(\[Service\]|Environment=|EnvironmentFile=|[[:space:]]*(#.*)?$)' \
    || true)"
  if [[ -n "${bad}" ]]; then
    harness::add_reason "drop-in 에 값 밖 지시어: $(printf '%s' "${bad}" \
      | tr '\n' ';')"
    ok=1
  fi
  for prop in Type=simple Restart=always RestartUSec=20s \
    StartLimitIntervalUSec=0 LimitCORE=0 SuccessExitStatus=130; do
    want="${prop#*=}"
    got="$(unit::prop "${prop%%=*}")"
    if [[ "${got}" != "${want}" ]]; then
      harness::add_reason "${prop%%=*} 기대 ${want} · 실제 ${got}"
      ok=1
    fi
  done
  for prop in Requires After; do
    got="$(unit::prop "${prop}")"
    if [[ " ${got} " != *' docker.service '* ]]; then
      harness::add_reason "${prop} 에 docker.service 없음(${got})"
      ok=1
    fi
  done
  for prop in 'ExecStart=path=/opt/pokeclip-media/bin/start-media.sh ' \
    'ExecStop=path=/opt/pokeclip-media/bin/stop-media.sh ' \
    'ExecStopPost=argv[]=/opt/pokeclip-media/bin/stop-media.sh ;'
  do
    got="$(unit::prop "${prop%%=*}")"
    if [[ "${got}" != *"${prop#*=}"* ]]; then
      harness::add_reason "${prop%%=*} 실효 값에 ${prop#*=}없음"
      ok=1
    fi
  done
  # 줄 잇기를 systemd 가 이 시험의 파서와 같게 읽는지 — 첫 ExecStop 의 실효
  # argv 가 기대 값(이어 붙인 한 줄)과 같다.
  want="$(printf '%s\n' "${EXPECTED_UNIT_LINES}" \
    | sed -n "s/^Service|ExecStop=\/bin\/sh -c '\(.*\)'$/\1/p")"
  got="$(unit::prop ExecStop)"
  if [[ -z "${want}" || "${got}" != *"argv[]=/bin/sh -c ${want} ;"* ]]; then
    harness::add_reason 'ExecStop 첫 명령의 실효 argv 가 기대(이어 붙인 한 줄)와 다름'
    ok=1
  fi
  want="$(grep -E '^(ExecStart|Restart|StartLimit)' \
    "${MEDIA_HOST_DIR}/${PROD_UNIT_REL}")"
  got="$(sudo -n systemctl cat "${UNIT_NAME}" \
    | grep -E '^(ExecStart|Restart|StartLimit)' || true)"
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason \
      'systemctl cat 의 ExecStart · Restart · StartLimit 줄이 prod 와 다름'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 18 — ⑤ 정리: 유닛 · drop-in · 설치본을 지우고 잔여 0 을 확인한다.
#######################################
check_case_18() {
  local ok=0 kind n
  require_installed || return 1
  if ! unit::teardown; then
    harness::add_reason "정리 명령 실패: ${unit_teardown_reason}"
    ok=1
  fi
  installed=0
  for kind in "${UNIT_FILE}" "${DROPIN_DIR}" "${INSTALL_ROOT}"; do
    if [[ -e "${kind}" ]]; then
      harness::add_reason "남음: ${kind}"
      ok=1
    fi
  done
  if [[ "$(unit::prop LoadState)" != not-found ]]; then
    harness::add_reason "systemd 가 아직 유닛을 안다($(unit::prop LoadState))"
    ok=1
  fi
  for kind in 'ps -a -q' 'network ls -q' 'volume ls -q'; do
    # shellcheck disable=SC2086 # 하위 명령을 단어로 나눈다.
    n="$(harness::count_output_lines docker ${kind} --filter \
      "label=com.docker.compose.project=${PROJECT}")" || n='?'
    if [[ "${n}" != 0 ]]; then
      harness::add_reason "docker ${kind%% *} 잔여 ${n}"
      ok=1
    fi
  done
  n="$(harness::count_output_lines docker ps -a -q --filter \
    'label=com.pokeclip.bench=edge-proxy')" || n='?'
  if [[ "${n}" != 0 ]]; then
    harness::add_reason "라벨 컨테이너 잔여 ${n}"
    ok=1
  fi
  return "${ok}"
}

main() {
  local n
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/unit_restart_test.sh (인자 없음)\n' \
      >&2
    exit 1
  fi
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  scratch="$(mktemp -d \
    "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pcbench-edge-proxy-unit-test.XXXXXX")"
  for n in docker jq; do
    command -v "${n}" >/dev/null 2>&1 || add_setup_reason "도구 없음: ${n}"
  done

  harness::run_case 1 'prod 유닛 지시어 = 기대 값' check_case_1
  harness::run_case 2 '시험 compose(unit) 검증 · 역할 셋 · 로그 자리' \
    check_case_2
  harness::run_case 3 '가짜 aws 상태 파일 모드(④ 이음매)' check_case_3
  harness::run_case 20 '저널 건수 — 조회 실패는 0 건이 아님(정적)' check_case_20
  harness::run_case 21 '렌더 자리 건수 — 조회 실패는 0 건이 아님(정적)' \
    check_case_21

  if check_systemd_host; then
    install_unit || true
  fi
  harness::run_case 4 'systemd-analyze verify' check_case_4
  harness::run_case 5 '이음매 — drop-in 값만 · 실효 동작 값 = prod' \
    check_case_5
  harness::run_case 6 '① 종료 0 → 전체 재기동(MainPID 바뀜 · 사례 9)' \
    unit::case_exit_0
  harness::run_case 7 '② 종료 1 → 전체 재기동' unit::case_exit_1
  harness::run_case 8 '⑥ 사이드카 120초 종료 1 반복 → 유닛 그대로 · 태그 저널' \
    unit::case_sidecar_flap
  harness::run_case 9 '⑧ 재기동 뒤 태그 저널 표식 보존' \
    unit::case_journal_kept
  harness::run_case 10 '③ stop → 정지 유지 · 컨테이너 0 · 렌더 0' \
    unit::case_stop
  harness::run_case 11 '③ 확장 — 사이드카 재시작 중 stop(30 · 60초)' \
    unit::case_stop_flapping
  harness::run_case 12 '④ CDN 비밀 빈 값 90초 → 재시도 · 되돌리면 자동 기동' \
    unit::case_secret_outage
  harness::run_case 19 '기동 중(비밀 조회 중) stop → inactive · 실패 아님' \
    unit::case_stop_while_starting
  harness::run_case 13 '⑦a docker 재시작 → 비부착 자동 시작 0 · 재통과' \
    unit::case_docker_restart
  harness::run_case 14 '⑦b dockerd kill -9 → 비부착 자동 시작 0 · 재통과' \
    unit::case_docker_kill
  harness::run_case 15 '크래시 — compose 플러그인 SIGKILL → 회수 · 새 렌더로 기동' \
    crash::case_plugin_kill
  harness::run_case 16 '크래시 — 유닛 cgroup SIGKILL → 회수 · 새 렌더로 기동' \
    crash::case_cgroup_kill
  harness::run_case 17 '남은 스택 · 지운 렌더 → 디렉터리 0 · 시작이 새로 만듦' \
    crash::case_leftover_stack
  harness::run_case 18 '⑤ 정리 — 유닛 · 설치본 · 컨테이너 · 망 · 볼륨 0' \
    check_case_18

  if [[ -n "${setup_reason}" ]]; then
    printf '준비 경고: %s\n' "${setup_reason}"
  fi
  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
