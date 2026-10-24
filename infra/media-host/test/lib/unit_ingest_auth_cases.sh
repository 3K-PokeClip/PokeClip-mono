# shellcheck shell=bash
# 전역(scratch · env_file · runtime_dir · unit_env · docker_config 등)과
# require_installed · write_unit_env · dropin_content 는 unit_restart_test.sh 가
# 정의한다.
# shellcheck disable=SC2154
#
# 유닛 재기동 시험(unit_restart_test.sh)의 응답기 모드 사례(D8 — 재시작 단위).
# 시험 유닛을 응답기 모드(시험 compose compose.unit.ingest-auth.yml · 응답기 모드
# 토큰 넷의 시험 템플릿 · 4C 토큰 ARN · 거부 목록 이름)로 다시 띄운 뒤:
#   - 사례 22: 운영 스크립트 bin/ingest-auth-ip-limit.sh off 로 응답기만
#     재생성해도 유닛 주 프로세스 · 부착 서비스(mediamtx · nginx)가 그대로다.
#   - 사례 23(양성 대조): 부착 서비스(nginx)를 밖에서 재생성하면 사례 22 의
#     검출기(abort 건수 · 상태 비교기)가 잡는다. 재기동 뒤 MainPID · 시작
#     시각 바뀜도 같은 비교기로 잡혀야 한다.
#
# 판별 신호(S-3 실측 — _workspace/m15_spike_s3_report.md):
#   - 불변 = MainPID · 시작 시각(ActiveEnterTimestampMonotonic) · NRestarts ·
#     mediamtx · nginx · sidecar ID 그대로 · 유닛 저널 「Aborting on container
#     exit」 0.
#   - 깨짐 = 「Aborting on container exit」 1 · 부착 ID 바뀜. 주 `up` 이 끝나는
#     것만 보면 안 된다 — 밖에서 재생성한 부착 컨테이너는 abort 의 정지
#     대상에서 빠져 주 `up` 이 그 컨테이너를 기다리며 걸린다. 그래서 그
#     컨테이너를 따로 정지한다.
#
# 부르는 쪽이 정하는 값: PROJECT · UNIT_NAME · TEST_DIR · UNIT_COMPOSE · FAKEBIN ·
# UNIT_BASE_PATH · INSTALL_BIN · DROPIN_FILE · UNIT_READY_TIMEOUT ·
# UNIT_RECOVER_TIMEOUT · UNIT_IDLE_DELAY 와 unit::* 함수. harness.sh 를 먼저
# source 한다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다).

readonly RESPONDER_COMPOSE="${TEST_DIR}/compose.unit.ingest-auth.yml"
readonly RESPONDER_SERVICE='ingest-auth'
# 가짜 aws(test/fakebin/aws)가 4C 토큰 갈래로 가르는 ARN — 값이 아니다.
readonly RESPONDER_RESOLVE_ARN='pcbench-ingest-auth-fake-resolve-arn'
readonly RESPONDER_DENY_PARAM='pcbench-ingest-auth-fake-deny-param'
readonly RESPONDER_ABORT='Aborting on container exit'
# 시간(초). 재생성 뒤 늦은 abort 를 기다리는 창(S-3 의 회차당 관찰 8초).
readonly RESPONDER_SETTLE=8
readonly RESPONDER_OPS_LIMIT=180
readonly RESPONDER_ABORT_WAIT=60

responder_dir=""

#######################################
# 프로젝트 서비스의 실행 중 컨테이너 ID(전체)를 낸다 — 하나가 아니면 빈 값.
# Arguments:
#   서비스 이름.
#######################################
responder::id() {
  local ids
  ids="$(docker ps -q --no-trunc --filter status=running \
    --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter "label=com.docker.compose.service=$1" 2>/dev/null)" || ids=""
  [[ "${ids}" == *$'\n'* ]] && ids=""
  printf '%s' "${ids}"
}

#######################################
# 응답기 모드 자리(compose 사본 · ingest-auth.env · 시험 템플릿)를 만든다.
# compose 는 scratch 로 복사한다 — 운영 스크립트가 compose 파일 옆의
# ingest-auth.env 를 바꿔 쓰므로 저장소 안에 두지 않는다.
# Globals:
#   responder_dir
#######################################
responder::write_assets() {
  local env_path
  responder_dir="${scratch}/ingest-auth-mode"
  env_path="${responder_dir}/ingest-auth.env"
  if [[ ! -f "${RESPONDER_COMPOSE}" ]]; then
    harness::add_reason 'test/compose.unit.ingest-auth.yml 없음'
    return 1
  fi
  # ingest-auth.env 는 지우고 새로 쓴다 — 앞 사례의 운영 스크립트(sudo)가 바꿔
  # 끼운 파일은 root 소유라, 비root 로 도는 CI 러너는 덮어쓰지 못한다(EACCES).
  # 디렉터리는 시험 사용자 것이라 지우기는 된다.
  # 응답기 모드 토큰 넷(CDN 비밀 · Basic 비밀번호 · 잠금 둘) · API 비밀번호 0.
  # 시작 스크립트는 두 모드 블록이 다 있는 템플릿만 받는다 — 옛 모드 블록은
  # 표지 둘뿐이다. 시험 compose 의 mediamtx 자리(에코)는 렌더본을 읽지 않는다.
  if ! mkdir -p -- "${responder_dir}" \
    || ! cp -- "${RESPONDER_COMPOSE}" "${responder_dir}/compose.yml" \
    || ! rm -f -- "${env_path}" \
    || ! printf 'INGEST_AUTH_IP_LIMIT=on\n' >"${env_path}" \
    || ! chmod 0644 "${env_path}" \
    || ! cat >"${responder_dir}/mediamtx.yml.tmpl" <<'EOF'
hlsCDNSecret: "@@HLS_CDN_SECRET@@"
# @mode legacy begin
# @mode legacy end
# @mode ingest-auth begin
authHTTPAddress: http://u:@@INGEST_AUTH_BASIC_PASS@@@127.0.0.1:8080/a
pathDefaults:
  srtPublishPassphrase: '@@SRT_PUBLISH_LOCK@@'
  srtReadPassphrase: '@@SRT_READ_LOCK@@'
# @mode ingest-auth end
EOF
  then
    harness::add_reason "응답기 모드 자리 쓰기 실패(${responder_dir})"
    return 1
  fi
}

#######################################
# 시험 유닛을 응답기 모드로 다시 띄운다 — env 파일에 응답기 모드 키를 더하고,
# drop-in 끝에 compose · 템플릿 경로를 덮어 적는다(같은 변수의 뒤 값이
# 이긴다). 응답기가 healthy 가 될 때까지 기다린다.
# Globals:
#   unit_env
#######################################
responder::enter() {
  local deadline
  responder::write_assets || return 1
  write_unit_env "${UNIT_IDLE_DELAY}"
  unit_env+=(
    "PCBENCH_UNIT_COMPOSE=${UNIT_COMPOSE}"
    "INTERNAL_API_TOKEN_SECRET_ARN=${RESPONDER_RESOLVE_ARN}"
    "INGEST_AUTH_DENYLIST_PARAM=${RESPONDER_DENY_PARAM}"
    "INGEST_AUTH_DENY_DIR=${scratch}/ingest-auth-deny"
  )
  if ! printf '%s\n' "${unit_env[@]}" >"${env_file}" \
    || ! printf 'ok\n' >"${mode_file}"; then
    harness::add_reason '시험 env · 가짜 aws 상태 파일 쓰기 실패'
    return 1
  fi
  if ! { dropin_content
    printf 'Environment=MEDIA_COMPOSE_FILE=%s\n' "${responder_dir}/compose.yml"
    printf 'Environment=MEDIA_MTX_TEMPLATE=%s\n' \
      "${responder_dir}/mediamtx.yml.tmpl"; } >"${scratch}/test.conf" \
    || ! sudo -n install -m 0644 -- "${scratch}/test.conf" "${DROPIN_FILE}" \
    || ! sudo -n systemctl daemon-reload \
    || ! unit::ctl restart "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason '응답기 모드 drop-in 설치 · 재기동 실패'
    return 1
  fi
  unit::wait_ready "${UNIT_READY_TIMEOUT}" || return 1
  deadline=$((SECONDS + UNIT_READY_TIMEOUT))
  until [[ -n "$(docker ps -q --filter health=healthy \
    --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter "label=com.docker.compose.service=${RESPONDER_SERVICE}")" ]]; do
    if (( SECONDS >= deadline )); then
      harness::add_reason "응답기 ${UNIT_READY_TIMEOUT}초 안에 healthy 아님"
      return 1
    fi
    sleep 1
  done
}

#######################################
# 응답기 모드 compose 를 시험 env 를 넣어 상한 안에서 부른다(운영자 셸 흉내).
# Arguments:
#   로그 파일(출력을 여기에 쓴다), compose 인자...
#######################################
responder::compose() {
  local log="$1"
  shift
  harness::run_bounded "${HARNESS_DOCKER_TIMEOUT}" "${log}" \
    env "${unit_env[@]}" RUNTIME_DIR="${runtime_dir}" docker compose \
    --ansi never -p "${PROJECT}" -f "${responder_dir}/compose.yml" "$@"
}

#######################################
# 유닛 · 서비스 상태 한 줄을 낸다(MainPID · 시작 시각 · NRestarts · 서비스
# ID 넷). 키=값 을 공백으로 잇는다 — 값에 공백이 없어야 한다. 그래서 시작
# 시각은 단조 시계 값(마이크로초 수)을 쓴다. ActiveEnterTimestamp 는 「요일
# 날짜 시각」 이라 공백에서 잘려 요일만 남는다.
#######################################
responder::snapshot() {
  printf 'pid=%s at=%s n=%s mediamtx=%s nginx=%s sidecar=%s ingest-auth=%s' \
    "$(unit::prop MainPID)" "$(unit::prop ActiveEnterTimestampMonotonic)" \
    "$(unit::prop NRestarts)" "$(responder::id mediamtx)" \
    "$(responder::id nginx)" "$(responder::id sidecar)" \
    "$(responder::id "${RESPONDER_SERVICE}")"
}

# 상태 한 줄에서 키 하나의 값을 낸다.
responder::field() {
  printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"
}

#######################################
# 두 상태 줄을 키마다 견준다 — 사례 22(불변)와 양성 대조(사례 23)가 같이
# 쓴다.
# Arguments:
#   전 상태 줄, 뒤 상태 줄, 키...
# Outputs:
#   값이 바뀐 키 · 한쪽이 빈 키(「키(빔)」)를 공백으로 이어 낸다. 모두
#   같으면 빈 값.
#######################################
responder::changed() {
  local before="$1" after="$2" key old new out=""
  shift 2
  for key in "$@"; do
    old="$(responder::field "${before}" "${key}")"
    new="$(responder::field "${after}" "${key}")"
    if [[ -z "${old}" || -z "${new}" ]]; then
      out+=" ${key}(빔)"
    elif [[ "${old}" != "${new}" ]]; then
      out+=" ${key}"
    fi
  done
  printf '%s' "${out# }"
}

#######################################
# 사례 22 — D8: 응답기만 재생성(ingest-auth-ip-limit.sh off)해도 유닛 주
# 프로세스 · 부착 서비스가 그대로이고 새 응답기에 off 가 반영된다.
#######################################
responder::case_recreate_only() {
  local t0 before after out changed id ok=0 aborts env_value
  require_installed || return 1
  responder::enter || return 1
  t0="$(unit::now)"
  before="$(responder::snapshot)"
  out="$(harness::bounded "${RESPONDER_OPS_LIMIT}" sudo -n env \
    PATH="${FAKEBIN}:${UNIT_BASE_PATH}" DOCKER_CONFIG="${docker_config}" \
    "${unit_env[@]}" RUNTIME_DIR="${runtime_dir}" \
    MEDIA_COMPOSE_PROJECT="${PROJECT}" \
    MEDIA_COMPOSE_FILE="${responder_dir}/compose.yml" \
    "${INSTALL_BIN}/ingest-auth-ip-limit.sh" off 2>/dev/null)" || true
  if [[ "${out}" != 'INGEST AUTH IP LIMIT OK off' ]]; then
    harness::add_reason "운영 스크립트 출력 「${out}」(OK off 기대)"
    ok=1
  fi
  sleep "${RESPONDER_SETTLE}"
  after="$(responder::snapshot)"
  changed="$(responder::changed "${before}" "${after}" \
    pid at n mediamtx nginx sidecar)"
  if [[ -n "${changed}" ]]; then
    harness::add_reason "${changed} 바뀜 · 빔(전 ${before} · 뒤 ${after})"
    ok=1
  fi
  changed="$(responder::changed "${before}" "${after}" "${RESPONDER_SERVICE}")"
  if [[ "${changed}" != "${RESPONDER_SERVICE}" ]]; then
    harness::add_reason "응답기 ID 가 바뀌지 않음 · 빔(전 ${before} · 뒤 ${after})"
    return 1
  fi
  id="$(responder::field "${after}" "${RESPONDER_SERVICE}")"
  env_value="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \
    "${id}" 2>/dev/null | grep -x -F 'INGEST_AUTH_IP_LIMIT=off')" || true
  if [[ -z "${env_value}" ]]; then
    harness::add_reason '새 응답기 env 에 INGEST_AUTH_IP_LIMIT=off 없음'
    ok=1
  fi
  if ! aborts="$(unit::journal_count -u "${UNIT_NAME}" "${t0}" \
    "${RESPONDER_ABORT}")"; then
    harness::add_reason '유닛 저널 조회 실패'
    ok=1
  elif (( aborts != 0 )); then
    harness::add_reason "유닛 저널 「${RESPONDER_ABORT}」 ${aborts}(0 기대)"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 걸린 주 `up` 을 풀어 유닛이 다시 뜨게 한다. 밖에서 만든 nginx 만 멈추면
# 모자랄 때가 있다 — abort 의 정지가 그 nginx 에서 오류를 내면 mediamtx 도
# 남는다(c7 상자 실측). 그래서 실행 중인 프로젝트 컨테이너를 모두 멈춘다.
#######################################
responder::stop_project() {
  local live
  live="$(docker ps -q --filter \
    "label=com.docker.compose.project=${PROJECT}" 2>/dev/null)" || live=""
  if [[ -n "${live}" ]]; then
    # shellcheck disable=SC2086 # ID 여럿을 단어로 나눈다.
    harness::bounded "${HARNESS_DOCKER_TIMEOUT}" docker stop ${live} \
      >/dev/null 2>&1 || true
  fi
}

#######################################
# 사례 23 — 양성 대조: 부착 서비스(nginx)를 밖에서 재생성하면 「Aborting on
# container exit」 1 · nginx ID 바뀜으로 잡힌다. 남은 컨테이너는 따로 정지하고,
# 유닛이 다시 뜨는 것까지 본다(다음 사례의 시작 상태). ID 바뀜과 재기동 뒤
# MainPID · 시작 시각 바뀜은 사례 22 와 같은 비교기(responder::changed)로
# 본다 — 비교기가 그 키의 차이를 놓치면 여기서 FAIL 이다.
#######################################
responder::case_attached_recreate() {
  local t0 before mid recovered changed aborts="" deadline ok=0
  require_installed || return 1
  responder::enter || return 1
  t0="$(unit::now)"
  before="$(responder::snapshot)"
  responder::compose "${scratch}/responder-pc.log" \
    up -d --no-deps --force-recreate nginx || true
  deadline=$((SECONDS + RESPONDER_ABORT_WAIT))
  while (( SECONDS < deadline )); do
    aborts="$(unit::journal_count -u "${UNIT_NAME}" "${t0}" \
      "${RESPONDER_ABORT}")" || aborts=""
    [[ -n "${aborts}" && "${aborts}" != 0 ]] && break
    sleep 1
  done
  mid="$(responder::snapshot)"
  if [[ "${aborts}" != 1 ]]; then
    harness::add_reason "유닛 저널 「${RESPONDER_ABORT}」 ${aborts:-조회 실패}(1 기대)"
    ok=1
  fi
  changed="$(responder::changed "${before}" "${mid}" nginx)"
  if [[ "${changed}" != nginx ]]; then
    harness::add_reason "비교기가 nginx ID 바뀜을 못 잡음 「${changed}」(전 \
${before} · 뒤 ${mid})"
    ok=1
  fi
  responder::stop_project
  if ! unit::wait_ready "${UNIT_RECOVER_TIMEOUT}" \
    "$(responder::field "${before}" pid)"; then
    return 1
  fi
  recovered="$(responder::snapshot)"
  changed="$(responder::changed "${before}" "${recovered}" pid at)"
  if [[ "${changed}" != 'pid at' ]]; then
    harness::add_reason "비교기가 재기동(MainPID · 시작 시각)을 못 잡음 \
「${changed}」(전 ${before} · 뒤 ${recovered})"
    ok=1
  fi
  return "${ok}"
}
