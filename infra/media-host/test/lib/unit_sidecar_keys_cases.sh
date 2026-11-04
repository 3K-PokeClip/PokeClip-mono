# shellcheck shell=bash
# 전역(scratch · env_file · mode_file · runtime_dir · unit_env · docker_config
# 등)과 require_installed · write_unit_env · dropin_content 는
# unit_restart_test.sh 가, responder::* 는 lib/unit_ingest_auth_cases.sh 가
# 정의한다.
# shellcheck disable=SC2154
#
# 유닛 재기동 시험(unit_restart_test.sh)의 새 꼴 쓰기 스위치 사례(E38 — 재시작
# 단위). 시험 유닛을 옛 모드 시험 compose 의 scratch 사본(옆에 상자 스위치
# 파일 sidecar.env · 끔)으로 다시 띄운 뒤:
#   - 사례 24: 운영 스크립트 bin/sidecar-round-keys.sh on 으로 사이드카만
#     재생성해도 유닛 주 프로세스 · 부착 서비스(mediamtx · nginx)가 그대로이고,
#     새 사이드카가 켬으로 만들어진다(env · 기동 신호 줄).
#
# 판별 신호는 사례 22 와 같다(responder::snapshot · responder::changed — 불변 =
# MainPID · 시작 시각 · NRestarts · mediamtx · nginx ID, 유닛 저널 「Aborting on
# container exit」 0). 깨짐을 잡는 양성 대조는 사례 23 이 같은 비교기로 한다.
#
# 부르는 쪽이 정하는 값: UNIT_COMPOSE · UNIT_NAME · PROJECT · TEST_DIR · FAKEBIN ·
# UNIT_BASE_PATH · INSTALL_BIN · DROPIN_FILE · UNIT_READY_TIMEOUT ·
# UNIT_IDLE_DELAY 와 unit::* · responder::* 함수. harness.sh 를 먼저 source
# 한다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다).

readonly SIDECAR_KEYS_SERVICE='sidecar'
readonly SIDECAR_KEYS_ON='PLAYBACK_ROUND_KEYS=true'

sidecar_keys_dir=""

#######################################
# 스위치 사례 자리(옛 모드 시험 compose 사본 · sidecar.env 끔)를 만든다.
# compose 는 scratch 로 복사한다 — 운영 스크립트가 compose 파일 옆의
# sidecar.env 를 바꿔 쓰므로 저장소 안에 두지 않는다.
# Globals:
#   sidecar_keys_dir
#######################################
sidecar_keys::write_assets() {
  local env_path
  sidecar_keys_dir="${scratch}/sidecar-keys"
  env_path="${sidecar_keys_dir}/sidecar.env"
  # sidecar.env 는 지우고 새로 쓴다 — 앞 실행의 운영 스크립트(sudo)가 바꿔 끼운
  # 파일은 root 소유다(사례 22 의 ingest-auth.env 와 같은 까닭).
  if ! mkdir -p -- "${sidecar_keys_dir}" \
    || ! cp -- "${UNIT_COMPOSE}" "${sidecar_keys_dir}/compose.yml" \
    || ! rm -f -- "${env_path}" \
    || ! cp -- "${TEST_DIR}/sidecar.env" "${env_path}" \
    || ! chmod 0644 "${env_path}"; then
    harness::add_reason "스위치 사례 자리 쓰기 실패(${sidecar_keys_dir})"
    return 1
  fi
}

#######################################
# 시험 유닛을 스위치 사례 자리로 다시 띄운다 — drop-in 끝에 compose 경로를
# 덮어 적는다(같은 변수의 뒤 값이 이긴다).
#######################################
sidecar_keys::enter() {
  sidecar_keys::write_assets || return 1
  write_unit_env "${UNIT_IDLE_DELAY}"
  if ! printf 'ok\n' >"${mode_file}"; then
    harness::add_reason '가짜 aws 상태 파일 쓰기 실패'
    return 1
  fi
  if ! { dropin_content
    printf 'Environment=MEDIA_COMPOSE_FILE=%s\n' \
      "${sidecar_keys_dir}/compose.yml"; } >"${scratch}/test.conf" \
    || ! sudo -n install -m 0644 -- "${scratch}/test.conf" "${DROPIN_FILE}" \
    || ! sudo -n systemctl daemon-reload \
    || ! unit::ctl restart "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason '스위치 사례 drop-in 설치 · 재기동 실패'
    return 1
  fi
  unit::wait_ready "${UNIT_READY_TIMEOUT}"
}

#######################################
# 사례 24 — E38: 사이드카만 재생성(sidecar-round-keys.sh on)해도 유닛 주
# 프로세스 · 부착 서비스가 그대로이고 새 사이드카가 켬으로 만들어진다.
#######################################
sidecar_keys::case_recreate_only() {
  local t0 before after out changed id ok=0 aborts env_value
  require_installed || return 1
  sidecar_keys::enter || return 1
  t0="$(unit::now)"
  before="$(responder::snapshot)"
  out="$(harness::bounded "${RESPONDER_OPS_LIMIT}" sudo -n env \
    PATH="${FAKEBIN}:${UNIT_BASE_PATH}" DOCKER_CONFIG="${docker_config}" \
    "${unit_env[@]}" RUNTIME_DIR="${runtime_dir}" \
    MEDIA_COMPOSE_PROJECT="${PROJECT}" \
    MEDIA_COMPOSE_FILE="${sidecar_keys_dir}/compose.yml" \
    "${INSTALL_BIN}/sidecar-round-keys.sh" on 2>/dev/null)" || true
  if [[ "${out}" != 'SIDECAR ROUND KEYS OK on' ]]; then
    harness::add_reason "운영 스크립트 출력 「${out}」(OK on 기대)"
    ok=1
  fi
  sleep "${RESPONDER_SETTLE}"
  after="$(responder::snapshot)"
  changed="$(responder::changed "${before}" "${after}" pid at n mediamtx nginx)"
  if [[ -n "${changed}" ]]; then
    harness::add_reason "${changed} 바뀜 · 빔(전 ${before} · 뒤 ${after})"
    ok=1
  fi
  changed="$(responder::changed "${before}" "${after}" \
    "${SIDECAR_KEYS_SERVICE}")"
  if [[ "${changed}" != "${SIDECAR_KEYS_SERVICE}" ]]; then
    harness::add_reason "사이드카 ID 가 바뀌지 않음 · 빔(전 ${before} · 뒤 \
${after})"
    return 1
  fi
  id="$(responder::field "${after}" "${SIDECAR_KEYS_SERVICE}")"
  env_value="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \
    "${id}" 2>/dev/null | grep -x -F "${SIDECAR_KEYS_ON}")" || true
  if [[ -z "${env_value}" ]]; then
    harness::add_reason "새 사이드카 env 에 ${SIDECAR_KEYS_ON} 없음"
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
