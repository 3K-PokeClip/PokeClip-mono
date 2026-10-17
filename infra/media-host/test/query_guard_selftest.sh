#!/usr/bin/env bash
#
# 앞단 프록시 계약 시험(proxy_contract.sh · lib/)의 조회 가드 자체 시험 — docker
# 없이 돈다.
#
# 계약 시험은 docker · compose 조회 결과를 센다(잔여 컨테이너 수 · 로그 줄 ·
# 탐침 줄). 조회 명령 자체가 실패하면 그 결과는 「0 · 비었음」 이 아니라 「확인
# 불가」 다 — 사례 판정은 FAIL, 정리 출력은 「잔여 확인 불가」 와 0 아닌 종료,
# 선행 점검은 준비 실패. 이 시험은 PATH 앞에 가짜 docker 를 두고 조회 실패 · 두
# 줄 · 빈 출력(compose 는 실패 · run 만 실패 · 성공) 모드에서 그 자리들이
# 기대대로 나오는지 단언한다.
#
# 계약 시험을 source 해 함수를 직접 부른다. proxy_contract.sh 는 source 되면
# main 을 돌리지 않는다 — 이것부터 별도 프로세스에서 확인하고, 아니면 나머지를
# 돌리지 않는다. 가드가 아닌 부품(start-media.sh 띄우기 · 망 칸 구조 읽기)은
# 가짜로 바꿔 둔다.
#
# 가짜 docker 는 임시 디렉터리에 만들고, 끝날 때와 중단 신호에 그 디렉터리를
# 통째로 지운다. 가짜가 모르는 호출은 종료 3 으로 끝나고 진짜 docker 는 부르지
# 않는다.
#
# 사용: bash infra/media-host/test/query_guard_selftest.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

SELFTEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SELFTEST_DIR
readonly CONTRACT_SCRIPT="${SELFTEST_DIR}/proxy_contract.sh"

selftest_root=""
selftest_pass_count=0
selftest_fail_count=0

#######################################
# 임시 디렉터리를 지운다(EXIT 트랩 — INT · TERM 도 exit 로 여기 온다).
# Globals:
#   selftest_root
#######################################
remove_selftest_root() {
  if [[ -n "${selftest_root}" ]]; then
    rm -rf -- "${selftest_root:?}"
  fi
}

#######################################
# 가짜 docker 를 쓴다. ps 는 SELFTEST_PS_MODE(fail — 종료 1 · two — 두 줄 ·
# empty — 빈 출력)를, compose 는 SELFTEST_COMPOSE_MODE(fail — 종료 1 · ok — 한
# 줄 · run-fail — run 만 종료 1 이고 나머지는 ok)를 따른다. 그 밖 호출 ·
# 모르는 모드는 종료 3 이다.
# Arguments:
#   bin 디렉터리.
#######################################
write_fake_docker() {
  mkdir -p -- "$1"
  cat >"$1/docker" <<'EOF'
#!/usr/bin/env bash
# query_guard_selftest.sh 의 가짜 docker — 진짜 docker 를 부르지 않는다.
case "${1:-}:${SELFTEST_PS_MODE:-}:${SELFTEST_COMPOSE_MODE:-}" in
  ps:fail:*)
    printf 'fake docker: Cannot connect to the Docker daemon\n' >&2
    exit 1
    ;;
  ps:two:*) printf '%s\n' 0123456789ab ba9876543210 ;;
  ps:empty:*) ;;
  compose:*:fail)
    printf 'fake docker: compose 실패\n' >&2
    exit 1
    ;;
  compose:*:run-fail)
    if [[ " $* " == *' run '* ]]; then
      printf 'fake docker: compose run 실패\n' >&2
      exit 1
    fi
    printf 'fake compose line\n'
    ;;
  compose:*:ok) printf 'fake compose line\n' ;;
  *)
    printf 'fake docker: 모르는 호출: %s\n' "$*" >&2
    exit 3
    ;;
esac
EOF
  chmod +x "$1/docker"
}

#######################################
# 단언 하나를 통과로 적는다.
# Globals:
#   selftest_pass_count
# Arguments:
#   이름.
#######################################
record_pass() {
  selftest_pass_count=$((selftest_pass_count + 1))
  printf 'PASS %s\n' "$1"
}

#######################################
# 단언 하나를 실패로 적는다.
# Globals:
#   selftest_fail_count
# Arguments:
#   이름, 설명.
#######################################
record_fail() {
  selftest_fail_count=$((selftest_fail_count + 1))
  printf 'FAIL %s — %s\n' "$1" "$2"
}

#######################################
# 두 값이 같은지 본다.
# Arguments:
#   이름, 기대, 실제.
#######################################
assert_eq() {
  if [[ "$3" == "$2" ]]; then
    record_pass "$1"
  else
    record_fail "$1" "기대 '$2' · 실제 '$3'"
  fi
}

#######################################
# 글에 조각이 들어 있는지 본다.
# Arguments:
#   이름, 글, 조각.
#######################################
assert_contains() {
  if [[ "$2" == *"$3"* ]]; then
    record_pass "$1"
  else
    record_fail "$1" "'$3' 없음 — 실제 '$2'"
  fi
}

#######################################
# 글에 조각이 없는지 본다.
# Arguments:
#   이름, 글, 조각.
#######################################
assert_lacks() {
  if [[ "$2" != *"$3"* ]]; then
    record_pass "$1"
  else
    record_fail "$1" "'$3' 이 있으면 안 됨 — 실제 '$2'"
  fi
}

#######################################
# proxy_contract.sh 를 source 하면 main 이 돌지 않는지 별도 프로세스에서 본다
# (돌면 계약 시험 전체가 이 셸에서 돈다).
# Globals:
#   selftest_root
# Returns:
#   0 source 해도 됨 / 1 아님.
#######################################
check_sourceable() {
  local out status=0
  # shellcheck disable=SC2016 # 작은따옴표 안은 새 bash 가 펼친다.
  out="$(TMPDIR="${selftest_root}" SELFTEST_COMPOSE_MODE=ok \
    bash -c 'source "$1" && printf "SOURCED\n"' _ "${CONTRACT_SCRIPT}" \
    2>/dev/null)" || status=$?
  assert_eq 'proxy_contract.sh 를 source 하면 main 이 돌지 않음' \
    '0:SOURCED' "${status}:${out%%$'\n'*}"
  [[ "${status}:${out}" == '0:SOURCED' ]]
}

#######################################
# 가드가 아닌 부품을 가짜로 바꾼다 — start-media.sh 띄우기(가짜 프로세스는
# 137 로 곧 끝난다) · 망 칸 구조 읽기(늘 실패). 계약 시험을 source 한 뒤에
# 부른다.
# Globals:
#   start_media_run_dir, start_media_env, start_media_pid, netns_reason,
#   selftest_root
#######################################
install_stubs() {
  start_media::preflight() { return 0; }
  start_media::prepare_env() {
    start_media_run_dir="$(mktemp -d "${selftest_root}/run.XXXXXX")"
    start_media_env=('SELFTEST_STUB=1')
  }
  start_media::start() {
    : >"$1"
    (exit 137) &
    start_media_pid=$!
  }
  load_netns_facts() {
    netns_reason='자체 시험 가짜'
    return 1
  }
}

#######################################
# harness::count_output_lines — 실패 → 출력 없이 0 아닌 종료 · 두 줄 → 2 ·
# 빔 → 0.
#######################################
test_count_output_lines() {
  local mode out status
  for mode in fail two empty; do
    export SELFTEST_PS_MODE="${mode}"
    status=0
    out="$(harness::count_output_lines docker ps -q 2>/dev/null)" \
      || status=$?
    case "${mode}" in
      fail)
        assert_eq "도우미(${mode}) 0 아닌 종료" 1 "$(( status != 0 ))"
        assert_eq "도우미(${mode}) 출력 없음" '' "${out}"
        ;;
      two) assert_eq "도우미(${mode})" '0:2' "${status}:${out}" ;;
      empty) assert_eq "도우미(${mode})" '0:0' "${status}:${out}" ;;
    esac
  done
}

#######################################
# 사례 17 의 잔여 판정 — 조회 실패 → FAIL(확인 불가) · 두 줄 → FAIL(2개) ·
# 빔 → 통과.
# Globals:
#   harness_fail_reason
#######################################
test_case_17_leftover() {
  local mode status
  start_media::wait_ready() { return 0; }
  export SELFTEST_COMPOSE_MODE=ok
  start_media::configure "${PROJECT}" "${CONTRACT_COMPOSE}" \
    "${MEDIA_HOST_DIR}" "${selftest_root}" 1 ''
  for mode in fail two empty; do
    export SELFTEST_PS_MODE="${mode}"
    harness_fail_reason=''
    status=0
    start_media::case_17 2>/dev/null || status=$?
    case "${mode}" in
      fail)
        assert_eq "사례 17(${mode}) FAIL" 1 "${status}"
        assert_contains "사례 17(${mode}) 원인" "${harness_fail_reason}" \
          '조회 실패 — 확인 불가'
        ;;
      two)
        assert_eq "사례 17(${mode}) FAIL" 1 "${status}"
        assert_contains "사례 17(${mode}) 원인" "${harness_fail_reason}" \
          '컨테이너 2개'
        ;;
      empty)
        assert_eq "사례 17(${mode}) 통과" '0:' \
          "${status}:${harness_fail_reason}"
        ;;
    esac
  done
}

#######################################
# 정리 함수를 하위 셸에서 돌린다(그 함수는 exit 로 끝난다).
# Globals:
#   scratch, env_ready, selftest_root
# Arguments:
#   정리 직전 종료 코드.
#######################################
run_cleanup() {
  (
    set +e
    scratch="$(mktemp -d "${selftest_root}/scratch.XXXXXX")"
    env_ready=1
    (exit "$1")
    cleanup
  )
}

#######################################
# 정리 출력 — 조회 실패 → 「잔여 확인 불가」 · 0 아닌 종료(원래 코드가 0 이
# 아니면 그대로) · 두 줄 → 잔여 2 · 빔 → 잔여 0.
#######################################
test_cleanup_output() {
  local mode err status
  export SELFTEST_COMPOSE_MODE=ok
  for mode in fail two empty; do
    export SELFTEST_PS_MODE="${mode}"
    err="${selftest_root}/cleanup-${mode}.err"
    status=0
    run_cleanup 0 2>"${err}" || status=$?
    case "${mode}" in
      fail)
        assert_eq "정리(${mode}) 0 아닌 종료" 1 "$(( status != 0 ))"
        assert_contains "정리(${mode}) 출력" "$(cat -- "${err}")" \
          '잔여 확인 불가'
        ;;
      two)
        assert_eq "정리(${mode}) 종료 코드" 0 "${status}"
        assert_contains "정리(${mode}) 출력" "$(cat -- "${err}")" '잔여 2'
        ;;
      empty)
        assert_eq "정리(${mode}) 종료 코드" 0 "${status}"
        assert_contains "정리(${mode}) 출력" "$(cat -- "${err}")" '잔여 0'
        ;;
    esac
  done
  export SELFTEST_PS_MODE=fail
  status=0
  run_cleanup 3 2>/dev/null || status=$?
  assert_eq '정리(fail · 원래 종료 코드 3) 그대로' 3 "${status}"
}

#######################################
# check_prestate — 조회 실패 → 준비 실패(확인 불가) · 두 줄 → 준비 실패(잔여
# 있음) · 빔 → 통과.
# Globals:
#   setup_reason
#######################################
test_check_prestate() {
  local mode status
  for mode in fail two empty; do
    export SELFTEST_PS_MODE="${mode}"
    setup_reason=''
    status=0
    check_prestate 2>/dev/null || status=$?
    case "${mode}" in
      fail)
        assert_eq "선행 점검(${mode}) 실패" 1 "${status}"
        assert_contains "선행 점검(${mode}) 원인" "${setup_reason}" \
          '조회 실패 — 확인 불가'
        ;;
      two)
        assert_eq "선행 점검(${mode}) 실패" 1 "${status}"
        assert_contains "선행 점검(${mode}) 원인" "${setup_reason}" \
          '잔여 컨테이너 있음'
        ;;
      empty)
        assert_eq "선행 점검(${mode}) 통과" '0:' "${status}:${setup_reason}"
        ;;
    esac
  done
}

#######################################
# 사례 16 의 사이드카 로그 조회 — 실패 → FAIL(확인 불가 · 재시작 횟수를 적지
# 않음) · 성공 → 확인 불가 아님.
# Globals:
#   harness_fail_reason
#######################################
test_case_16_log_query() {
  local mode status
  # 가짜 프로세스가 끝날 때까지 기다려 관찰 창을 건너뛴다.
  start_media::wait_ready() { wait "${start_media_pid}" 2>/dev/null || true; }
  for mode in fail ok; do
    export SELFTEST_COMPOSE_MODE="${mode}"
    harness_fail_reason=''
    status=0
    start_media::observe_restarts 4s 2>/dev/null || status=$?
    if [[ "${mode}" == fail ]]; then
      assert_eq "사례 16 로그(${mode}) FAIL" 1 "${status}"
      assert_contains "사례 16 로그(${mode}) 원인" "${harness_fail_reason}" \
        '사이드카 로그 조회 실패 — 확인 불가'
      assert_lacks "사례 16 로그(${mode}) 재시작 횟수 안 적음" \
        "${harness_fail_reason}" '재시작'
    else
      assert_lacks "사례 16 로그(${mode}) 확인 불가 아님" \
        "${harness_fail_reason}" '확인 불가'
    fi
  done
}

#######################################
# 사례 8 의 로그 조회 — 에코 로그 조회 실패 → FAIL(확인 불가) · 성공 → 통과 ·
# nginx 로그 모으기 실패 → 그 단계를 적고 사례 8 이 FAIL(확인 불가).
# Globals:
#   scratch, nginx_log_failures, harness_fail_reason
#######################################
test_case_8_log_queries() {
  local mode status
  scratch="$(mktemp -d "${selftest_root}/case8.XXXXXX")"
  printf 'GET /%s/index.m3u8\n' "${PROXY_STREAM_ID}" >"${scratch}/nginx-a.log"
  printf 'POST /api/ingest/end-signal\n' >"${scratch}/nginx-b.log"
  nginx_log_failures=''
  for mode in fail ok; do
    export SELFTEST_COMPOSE_MODE="${mode}"
    harness_fail_reason=''
    status=0
    check_case_8 2>/dev/null || status=$?
    if [[ "${mode}" == fail ]]; then
      assert_eq "사례 8 에코 로그(${mode}) FAIL" 1 "${status}"
      assert_contains "사례 8 에코 로그(${mode}) 원인" \
        "${harness_fail_reason}" '에코 로그 조회 실패 — 확인 불가'
    else
      assert_eq "사례 8 에코 로그(${mode}) 통과" '0:' \
        "${status}:${harness_fail_reason}"
    fi
  done
  export SELFTEST_COMPOSE_MODE=fail
  collect_nginx_logs "${scratch}/nginx-x.log" '사례 15 단계' 2>/dev/null \
    || true
  assert_contains 'nginx 로그 모으기(fail) 단계 적음' \
    "${nginx_log_failures}" '사례 15 단계'
  export SELFTEST_COMPOSE_MODE=ok
  harness_fail_reason=''
  status=0
  check_case_8 2>/dev/null || status=$?
  assert_eq '사례 8(nginx 로그 모으기 실패) FAIL' 1 "${status}"
  assert_contains '사례 8(nginx 로그 모으기 실패) 원인' \
    "${harness_fail_reason}" 'nginx 로그 조회 실패(사례 15 단계) — 확인 불가'
  nginx_log_failures=''
  collect_nginx_logs "${scratch}/nginx-y.log" '대조' 2>/dev/null || true
  assert_eq 'nginx 로그 모으기(ok) 실패 안 적음' '' "${nginx_log_failures}"
  assert_contains 'nginx 로그 모으기(ok) 파일' \
    "$(cat -- "${scratch}/nginx-y.log" 2>/dev/null)" 'fake compose line'
}

#######################################
# 사례 12 의 탐침 조회 두 곳 — 망 칸 안 탐침 실패 → FAIL(확인 불가 · 「닫힘」
# 을 적지 않음) · bridge 탐침 실패 → FAIL(확인 불가 · 「열려 있음」 · 「닿지
# 않음」 을 적지 않음) · 성공 → 확인 불가 아님.
# Globals:
#   stack_ready, harness_fail_reason
#######################################
test_case_12_probe_query() {
  local mode status name
  stack_ready=1
  for mode in fail run-fail ok; do
    export SELFTEST_COMPOSE_MODE="${mode}"
    name="사례 12 탐침(${mode})"
    harness_fail_reason=''
    status=0
    check_case_12 2>/dev/null || status=$?
    case "${mode}" in
      fail)
        assert_eq "${name} FAIL" 1 "${status}"
        assert_contains "${name} 원인" "${harness_fail_reason}" \
          '망 칸 안 탐침 실행 실패 — 확인 불가'
        assert_lacks "${name} 닫힘 안 적음" "${harness_fail_reason}" '닫힘'
        ;;
      run-fail)
        assert_eq "${name} FAIL" 1 "${status}"
        assert_contains "${name} 원인" "${harness_fail_reason}" \
          'bridge 컨테이너 탐침 실행 실패 — 확인 불가'
        assert_lacks "${name} 열려 있음 안 적음" "${harness_fail_reason}" \
          '열려 있음'
        assert_lacks "${name} 닿지 않음 안 적음" "${harness_fail_reason}" \
          '닿지 않음'
        ;;
      ok)
        assert_lacks "${name} 확인 불가 아님" "${harness_fail_reason}" \
          '확인 불가'
        ;;
    esac
  done
  stack_ready=0
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/query_guard_selftest.sh\n' >&2
    exit 1
  fi
  trap remove_selftest_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  selftest_root="$(mktemp -d \
    "${TMPDIR:-/tmp}/pcbench-edge-proxy-selftest.XXXXXX")"
  write_fake_docker "${selftest_root}/bin"
  export PATH="${selftest_root}/bin:${PATH}"
  if check_sourceable; then
    # 계약 시험의 main 도 함께 실린다 — 이 함수는 이미 도는 중이라 영향이
    # 없고, 그 main 은 아무도 부르지 않는다.
    # shellcheck source=proxy_contract.sh
    source "${CONTRACT_SCRIPT}"
    install_stubs
    test_count_output_lines
    test_case_17_leftover
    test_cleanup_output
    test_check_prestate
    test_case_16_log_query
    test_case_8_log_queries
    test_case_12_probe_query
  fi
  printf '요약: PASS %d · FAIL %d\n' "${selftest_pass_count}" \
    "${selftest_fail_count}"
  if (( selftest_fail_count != 0 )); then
    exit 1
  fi
}

main "$@"
