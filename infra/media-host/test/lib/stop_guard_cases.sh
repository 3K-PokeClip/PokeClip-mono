# shellcheck shell=bash
# fixture_* · harness_* 전역은 start_fixture.sh · harness.sh 가 채운다.
# shellcheck disable=SC2154
#
# start_test 의 정지 스크립트 방어 사례(37 · 38) — down 이 실패하면 compose
# 파일 없이 프로젝트 라벨로 컨테이너를 지우는 폴백, down 이 끝나지 않는 동안
# 신호를 받아도 렌더(비밀)를 지우고 끝나는지. start_test.sh 가 source 한다.
# start_fixture.sh · harness.sh 와 start_test.sh 의 seed_runtime 을 쓴다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly STOP_FAKE_IDS='c0ffee000001 c0ffee000002'
# 끝나지 않는 down 이 머무는 시간 · 신호 뒤 끝나기를 기다리는 상한(초).
readonly STOP_DOWN_HOLD=30
readonly STOP_SIGNAL_TIMEOUT=10

#######################################
# 첫 줄이 정한 하위 명령(ps · rm)인 가짜 docker 기록의 argv 를 한 줄씩 낸다
# (인자 사이 공백).
# Globals:
#   fixture_dir
# Arguments:
#   하위 명령.
#######################################
stop::engine_calls() {
  local f
  for f in "${fixture_dir}"/log/docker.*.argv; do
    [[ -f "${f}" ]] || continue
    if [[ "$(head -n 1 -- "${f}")" == "$1" ]]; then
      paste -s -d ' ' - <"${f}"
    fi
  done
}

#######################################
# 사례 37 — down 이 성공하면 폴백을 부르지 않는다. down 이 실패하면 프로젝트
# 라벨로 남은 컨테이너를 찾아 지우고(compose 파일을 읽지 않는다) 그 수를
# 적되, 종료 코드는 down 의 것이다. 찾기가 실패하면 폴백 실패 줄을 낸다.
# 어느 갈래든 렌더는 지운다.
# Globals:
#   fixture_dir, fixture_status
#######################################
stop::case_down_fallback() {
  local ok=0 ps rm
  fixture::new_case
  fixture::base_env
  seed_runtime
  fixture::run stop-media.sh "PCBENCH_FAKE_PS_IDS=${STOP_FAKE_IDS}" || return 1
  if [[ -n "$(stop::engine_calls ps)$(stop::engine_calls rm)" ]]; then
    harness::add_reason 'down 성공인데 폴백(ps · rm)을 부름'
    ok=1
  fi
  fixture::new_case
  fixture::base_env
  seed_runtime
  fixture::run stop-media.sh PCBENCH_FAKE_DOWN_STATUS=3 \
    "PCBENCH_FAKE_PS_IDS=${STOP_FAKE_IDS}" || return 1
  ps="$(stop::engine_calls ps)"
  rm="$(stop::engine_calls rm)"
  if [[ "${ps}" != "ps -aq --filter \
label=com.docker.compose.project=${FIXTURE_PROJECT}" \
    || "${rm}" != "rm -f ${STOP_FAKE_IDS}" ]]; then
    harness::add_reason "폴백 argv ps 「${ps}」 · rm 「${rm}」"
    ok=1
  fi
  stop::expect_result 3 'MEDIA STOP fallback removed 2' || ok=1
  fixture::new_case
  fixture::base_env
  seed_runtime
  fixture::run stop-media.sh PCBENCH_FAKE_DOWN_STATUS=3 \
    PCBENCH_FAKE_PS_STATUS=1 || return 1
  stop::expect_result 3 'MEDIA STOP fallback FAIL' || ok=1
  return "${ok}"
}

#######################################
# 종료 코드 · 출력 줄 · 렌더 잔여 0 을 본다.
# Globals:
#   fixture_dir, fixture_status
# Arguments:
#   기대 종료 코드, 기대 출력 줄.
#######################################
stop::expect_result() {
  local ok=0
  if (( fixture_status != $1 )); then
    harness::add_reason "종료 코드 ${fixture_status}(기대 $1)"
    ok=1
  fi
  if ! grep -q -F -x -- "$2" "${fixture_dir}/out"; then
    harness::add_reason "출력에 「$2」 줄 없음: $(harness::log_tail \
"${fixture_dir}/out")"
    ok=1
  fi
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason "「$2」 갈래 뒤 RUNTIME_DIR 에 파일이 남음"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 38 — down 이 끝나지 않는 동안 정지 스크립트 프로세스 하나에만 TERM 이
# 오면(systemd 가 ExecStop 상한을 넘긴 정지 명령에 보내는 꼴) 곧 143 으로
# 끝나고 렌더(비밀)를 지운다.
# Globals:
#   fixture_dir, fixture_cmd, harness_spawned_pid, harness_waited_status
#######################################
stop::case_signal_during_down() {
  local ok=0 pid deadline status=0
  fixture::new_case
  fixture::base_env
  seed_runtime
  fixture::command "${MEDIA_HOST_DIR}/bin/stop-media.sh" \
    "PCBENCH_FAKE_DOWN_HOLD=${STOP_DOWN_HOLD}"
  harness::spawn_group "${fixture_cmd[@]}" >"${fixture_dir}/out" 2>&1
  pid="${harness_spawned_pid}"
  deadline=$((SECONDS + STOP_SIGNAL_TIMEOUT))
  while [[ ! -e "${fixture_dir}/log/docker.down.hold" ]] \
    && (( SECONDS < deadline )); do
    sleep 0.2
  done
  if [[ ! -e "${fixture_dir}/log/docker.down.hold" ]]; then
    harness::add_reason "${STOP_SIGNAL_TIMEOUT}초 안에 down 에 닿지 않음"
    harness::terminate "${pid}"
    return 1
  fi
  kill -TERM "${pid}" 2>/dev/null || true
  harness::wait_exit "${pid}" "${STOP_SIGNAL_TIMEOUT}" || status=$?
  if (( status == 124 )); then
    harness::add_reason "TERM 뒤 ${STOP_SIGNAL_TIMEOUT}초 안에 끝나지 않음"
    ok=1
  elif (( harness_waited_status != 143 )); then
    harness::add_reason "TERM 뒤 종료 코드 ${harness_waited_status}(기대 143)"
    ok=1
  fi
  # 남은 가짜 down(같은 그룹)까지 끝낸 뒤 센다.
  harness::terminate "${pid}"
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason 'TERM 뒤 RUNTIME_DIR 에 파일이 남음'
    ok=1
  fi
  return "${ok}"
}
