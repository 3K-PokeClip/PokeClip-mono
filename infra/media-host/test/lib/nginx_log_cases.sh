# shellcheck shell=bash
#
# 앞단 프록시 계약 시험의 nginx 로그 몫 — 사례 8(로그에 시험 토큰 0건)과 그
# 판정이 기대는 로그 모으기 · 오류 로그 양성 대조 요청.
#
# proxy_contract.sh 가 source 해서 돌린다. harness.sh · proxy_requests.sh 를
# 먼저 source 한다. proxy_contract.sh 의 전역 scratch(로그를 둘 디렉터리) ·
# setup_reason · phase_b_reason(준비 실패 원인) · 상수 OTHER_STREAM_ID 와 함수
# save_nginx_logs · bench_compose 를 쓴다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 오류 로그 양성 대조 — nginx 기본 client_max_body_size(1m)를 넘는 본문을 보내면
# nginx 가 오류 로그에 이 문구를 쓴다(413).
readonly ERROR_PROBE_PATH='/pcbench-edge-proxy-error-probe'
readonly ERROR_PROBE_BYTES=1100000
readonly ERROR_PROBE_LINE='client intended to send too large body'

# 사례 8 이 셀 nginx 로그를 모으지 못한 단계들(collect_nginx_logs 가 채운다).
nginx_log_failures=""
error_probe_code=""

#######################################
# 사례 8 이 셀 nginx 로그를 모은다. 조회가 실패하면 그 단계를 적어 둔다 —
# 사례 8 이 실패한 조회의 출력을 로그로 세지 않고 확인 불가로 FAIL 한다.
# Globals:
#   nginx_log_failures
# Arguments:
#   출력 파일, 단계 이름.
#######################################
collect_nginx_logs() {
  if ! save_nginx_logs "$1"; then
    nginx_log_failures="${nginx_log_failures:+${nginx_log_failures} · }$2"
  fi
}

#######################################
# 오류 로그 양성 대조 요청을 보낸다(사례 8 이 그 오류 로그 줄을 찾는다). 본문이
# client_max_body_size 를 넘어 nginx 가 413 으로 끊으므로, 응답 코드는 기록만
# 한다(본문을 다 보내기 전에 끊겨 000 일 수도 있다).
# Globals:
#   scratch, error_probe_code
#######################################
send_error_log_probe() {
  # scratch 는 proxy_contract.sh 가 정한다.
  # shellcheck disable=SC2154
  local body="${scratch}/error-probe.body"
  head -c "${ERROR_PROBE_BYTES}" /dev/zero >"${body}" || return 1
  error_probe_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -X POST -H "Authorization: Bearer ${PROXY_FAKE_TOKEN}" \
    -H 'Content-Type: application/octet-stream' --data-binary "@${body}" \
    "$(proxy::base_url control)${ERROR_PROBE_PATH}" 2>/dev/null)" || true
  error_probe_code="${error_probe_code:-000}"
}

#######################################
# nginx 로그(사례 1 · 7 단계 + 사례 15 단계)와 에코 로그에서 시험 토큰을 센다.
# 접근 로그 · 오류 로그가 실제로 모였는지(양성 대조 — 사례 1 요청 줄 · 오류
# 로그 대조 줄)부터 본다 — 비어 있으면 0건은 거짓 통과다. 로그 조회가
# 실패했으면 세지 않고 확인 불가로 FAIL 한다. 접근 로그의 경로가 받은 원문인지
# (사례 1b 가 보낸 우회 원문이 풀고 접히지 않고 남았는지)도 본다.
# Globals:
#   nginx_log_failures
#######################################
check_case_8() {
  local ok=0 log_a="${scratch}/nginx-a.log" log_b="${scratch}/nginx-b.log"
  local echo_log="${scratch}/echo.log" n seen token="${PROXY_FAKE_TOKEN}"
  if [[ -n "${nginx_log_failures}" ]]; then
    harness::add_reason \
      "nginx 로그 조회 실패(${nginx_log_failures}) — 확인 불가"
    return 1
  fi
  if [[ ! -s "${log_a}" ]]; then
    harness::add_reason "사례 1 · 7 단계 nginx 로그 없음 — \
${setup_reason:-${phase_b_reason:-로그 비어 있음}}"
    return 1
  fi
  seen="$(harness::count_fixed "/${PROXY_STREAM_ID}/index.m3u8" "${log_a}")"
  if (( seen == 0 )); then
    harness::add_reason '접근 로그에 사례 1 요청 줄이 없음(docker 로그 아님)'
    ok=1
  fi
  seen="$(harness::count_fixed \
    "GET /${PROXY_STREAM_ID}/..%2f${OTHER_STREAM_ID}/index.m3u8 " "${log_a}")"
  if (( seen == 0 )); then
    harness::add_reason '접근 로그 경로가 원문이 아님(사례 1b 우회 원문 줄 없음)'
    ok=1
  fi
  seen="$(harness::count_fixed "${ERROR_PROBE_LINE}" "${log_a}")"
  if (( seen == 0 )); then
    harness::add_reason "오류 로그에 대조 줄이 없음(docker 로그 아님 — 대조 \
요청 HTTP ${error_probe_code:-미발송})"
    ok=1
  fi
  n="$(harness::count_fixed "${token}" "${log_a}")"
  if [[ -s "${log_b}" ]]; then
    seen="$(harness::count_fixed '/api/ingest/end-signal' "${log_b}")"
    if (( seen == 0 )); then
      harness::add_reason '접근 로그에 사례 15 요청 줄이 없음'
      ok=1
    fi
    n=$((n + $(harness::count_fixed "${token}" "${log_b}")))
  else
    harness::add_reason \
      "사례 15 단계 nginx 로그 없음 — ${phase_b_reason:-로그 비어 있음}"
    ok=1
  fi
  if (( n != 0 )); then
    harness::add_reason "nginx 로그에 시험 토큰 ${n}건"
    ok=1
  fi
  if ! bench_compose logs --no-color --no-log-prefix mediamtx \
    >"${echo_log}" 2>&1; then
    harness::add_reason '에코 로그 조회 실패 — 확인 불가'
    ok=1
  elif [[ "$(harness::count_fixed "${token}" "${echo_log}")" != 0 ]]; then
    harness::add_reason '에코 로그에 시험 토큰이 있음(대조 오염)'
    ok=1
  fi
  return "${ok}"
}
