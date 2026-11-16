# shellcheck shell=bash
# fixture_* · harness_* 전역은 start_fixture.sh · harness.sh 가 채운다.
# shellcheck disable=SC2154
#
# start_test 의 정지 스크립트 방어 사례(37 – 40) — down 이 실패하면 compose
# 파일 없이 프로젝트 라벨로 컨테이너를 지우는 폴백, down · 폴백이 끝나지 않는
# 동안 신호를 받아도 렌더(비밀)를 지우고 끝나는지, 끝나지 않는 폴백에 상한이
# 있는지. 대리 창구 토큰 디렉터리 셋의 정리(사례 89 · 90 — 토큰 넷 · 그 임시
# 파일 삭제 · 남으면 CLEANUP FAIL · 링크 디렉터리는 따라가지 않음, 92 — 지우는
# 이름이 시작 스크립트 lib 의 호출자 목록과 같음)도 여기 있다.
# start_test.sh 가 source 한다.
# start_fixture.sh · harness.sh 와 start_test.sh 의 seed_runtime 을 쓴다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly STOP_FAKE_IDS='c0ffee000001 c0ffee000002'
# 끝나지 않는 down 이 머무는 시간 · 신호 뒤 끝나기를 기다리는 상한(초).
readonly STOP_DOWN_HOLD=30
readonly STOP_SIGNAL_TIMEOUT=10
# 정지 스크립트의 폴백 docker 호출 한 번의 상한(초 — 스크립트의 값과 같다).
readonly STOP_FALLBACK_LIMIT=10
# 대리 창구 토큰 — RUNTIME_DIR 아래 디렉터리와 그 안의 파일 이름.
readonly STOP_CRED_FILES=(cred-indexer/token cred-janitor/token
  cred-relay/indexer cred-relay/janitor)

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

#######################################
# 표식 파일이 생길 때까지 기다린다.
# Arguments:
#   파일, 상한(초).
#######################################
stop::wait_marker() {
  local deadline=$((SECONDS + $2))
  while [[ ! -e "$1" ]] && (( SECONDS < deadline )); do
    sleep 0.2
  done
  [[ -e "$1" ]]
}

#######################################
# 사례 39 — down 이 실패하고 폴백의 docker ps 가 끝나지 않는 동안 TERM 이 오면
# 곧 143 으로 끝나고 렌더(비밀)를 지운다 — 폴백이 신호 처리를 미루지 않는다.
# Globals:
#   fixture_dir, fixture_cmd, harness_spawned_pid, harness_waited_status
#######################################
stop::case_signal_during_fallback() {
  local ok=0 pid status=0 marker
  fixture::new_case
  fixture::base_env
  seed_runtime
  marker="${fixture_dir}/log/docker.ps.hold"
  fixture::command "${MEDIA_HOST_DIR}/bin/stop-media.sh" \
    PCBENCH_FAKE_DOWN_STATUS=3 "PCBENCH_FAKE_PS_HOLD=${STOP_DOWN_HOLD}"
  harness::spawn_group "${fixture_cmd[@]}" >"${fixture_dir}/out" 2>&1
  pid="${harness_spawned_pid}"
  if ! stop::wait_marker "${marker}" "${STOP_SIGNAL_TIMEOUT}"; then
    harness::add_reason "${STOP_SIGNAL_TIMEOUT}초 안에 폴백 ps 에 닿지 않음"
    harness::terminate "${pid}"
    return 1
  fi
  kill -TERM "${pid}" 2>/dev/null || true
  harness::wait_exit "${pid}" 3 || status=$?
  if (( status == 124 )); then
    harness::add_reason 'TERM 뒤 3초 안에 끝나지 않음(폴백이 신호를 미룸)'
    ok=1
  elif (( harness_waited_status != 143 )); then
    harness::add_reason "TERM 뒤 종료 코드 ${harness_waited_status}(기대 143)"
    ok=1
  fi
  harness::terminate "${pid}"
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason 'TERM 뒤 RUNTIME_DIR 에 파일이 남음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 40 — 폴백의 docker ps 가 끝나지 않으면 상한 뒤 그 호출을 끝내고
# 「MEDIA STOP fallback FAIL」 을 낸 뒤 렌더를 지운다(종료 코드는 down 의 것).
# Globals:
#   fixture_dir, fixture_status
#######################################
stop::case_fallback_bounded() {
  local ok=0 start
  fixture::new_case
  fixture::base_env
  seed_runtime
  start="${SECONDS}"
  fixture::run stop-media.sh PCBENCH_FAKE_DOWN_STATUS=3 \
    "PCBENCH_FAKE_PS_HOLD=${STOP_DOWN_HOLD}" || return 1
  if (( SECONDS - start > STOP_FALLBACK_LIMIT + 5 )); then
    harness::add_reason "$((SECONDS - start))초 걸림(상한 ${STOP_FALLBACK_LIMIT}초)"
    ok=1
  fi
  stop::expect_result 3 'MEDIA STOP fallback FAIL' || ok=1
  return "${ok}"
}

#######################################
# 정지 사례 준비 — 창구 디렉터리 셋에 토큰 넷과 끊긴 렌더의 임시 파일 꼴 잔여를
# 둔다.
# Globals:
#   fixture_dir
#######################################
stop::seed_cred() {
  local rel dir
  for rel in "${STOP_CRED_FILES[@]}"; do
    dir="${fixture_dir}/runtime/${rel%/*}"
    mkdir -p -- "${dir}"
    printf 'pcbench-cred-token' >"${dir}/${rel##*/}"
    printf 'partial' >"${dir}/.${rel##*/}.AbC123"
    chmod 0400 "${dir}/${rel##*/}"
  done
}

#######################################
# 사례 89 — 정지: 창구 토큰 넷과 그 임시 파일을 지우고 디렉터리는 남긴다. 토큰이
# 남으면(지울 수 없는 디렉터리) 그 디렉터리를 CLEANUP FAIL 로 적는다.
# Globals:
#   fixture_dir, fixture_status
#######################################
stop::case_cred_cleanup() {
  local ok=0 rel dir status=0
  fixture::new_case
  fixture::base_env
  stop::seed_cred
  fixture::run stop-media.sh -- --cleanup-only || return 1
  if (( fixture_status != 0 )) || [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason "정지 뒤 종료 ${fixture_status} · RUNTIME_DIR 파일 \
$(fixture::runtime_files)개(기대 0 · 0): $(harness::log_tail \
"${fixture_dir}/out")"
    ok=1
  fi
  for rel in "${STOP_CRED_FILES[@]}"; do
    if [[ ! -d "${fixture_dir}/runtime/${rel%/*}" ]]; then
      harness::add_reason "창구 디렉터리 ${rel%/*} 가 사라짐"
      ok=1
    fi
  done
  if [[ "$(id -u)" == 0 ]]; then
    harness::add_reason 'root 로 돌아 쓰기 불가 디렉터리를 흉내 낼 수 없음(판정 불가)'
    return 1
  fi
  fixture::new_case
  fixture::base_env
  stop::seed_cred
  dir="${fixture_dir}/runtime/cred-relay"
  chmod 0500 "${dir}"
  fixture::run stop-media.sh -- --cleanup-only || status=1
  chmod 0700 "${dir}"
  (( status == 0 )) || return 1
  if (( fixture_status == 0 )) || ! grep -q -F -x -- \
    "MEDIA STOP CLEANUP FAIL ${dir}" "${fixture_dir}/out"; then
    harness::add_reason "중계 토큰이 남았는데 CLEANUP FAIL 이 아님(종료 \
${fixture_status}): $(harness::log_tail "${fixture_dir}/out")"
    ok=1
  fi
  if [[ -e "${fixture_dir}/runtime/cred-indexer/token" ]]; then
    harness::add_reason '중계 쪽 실패 때문에 소비자 토큰을 지우지 않음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 90 — 정지: 창구 디렉터리가 링크면 따라가 지우지 않고 CLEANUP FAIL 로
# 적는다. 링크 대상의 파일은 그대로다.
# Globals:
#   fixture_dir, fixture_status
#######################################
stop::case_cred_link() {
  local ok=0 name dir elsewhere
  for name in cred-indexer cred-janitor cred-relay; do
    fixture::new_case
    fixture::base_env
    elsewhere="${fixture_dir}/elsewhere"
    mkdir -p -- "${elsewhere}" "${fixture_dir}/runtime"
    printf 'keep' >"${elsewhere}/token"
    printf 'keep' >"${elsewhere}/indexer"
    printf 'keep' >"${elsewhere}/janitor"
    dir="${fixture_dir}/runtime/${name}"
    ln -s -- "${elsewhere}" "${dir}"
    fixture::run stop-media.sh -- --cleanup-only || return 1
    if (( fixture_status == 0 )) || ! grep -q -F -x -- \
      "MEDIA STOP CLEANUP FAIL ${dir}" "${fixture_dir}/out"; then
      harness::add_reason "${name} 링크인데 CLEANUP FAIL 이 아님(종료 \
${fixture_status}): $(harness::log_tail "${fixture_dir}/out")"
      ok=1
    fi
    if [[ "$(find "${elsewhere}" -type f | awk 'END { print NR }')" != 3 ]]
    then
      harness::add_reason "${name} 링크: 링크 대상의 파일이 지워짐"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 92 — 정지가 지우는 창구 토큰 이름이 시작 스크립트 lib(cred_relay.sh)의
# 호출자 목록 · 디렉터리 이름과 같다. lib 의 CRED_CALLERS 마다 소비자
# 토큰(cred-<호출자>/token)과 중계 사본(cred-relay/<호출자>)을 두면 정지가 모두
# 지운다 — lib 에 호출자를 더하고 정지 쪽 목록을 빠뜨린 회귀(새 호출자 토큰이
# 재부팅까지 tmpfs 에 남음)를 잡는다.
# Globals:
#   fixture_dir, fixture_status, MEDIA_HOST_DIR
#######################################
stop::case_cred_callers_match() {
  local facts prefix relay token rest caller rel callers=()
  # shellcheck disable=SC2016 # $1 · ${…} 는 하위 셸이 펼친다.
  facts="$(bash -c 'source "$1" && printf "%s %s %s %s" \
    "${CRED_CONSUMER_DIR_PREFIX}" "${CRED_RELAY_DIR_NAME}" \
    "${CRED_TOKEN_NAME}" "${CRED_CALLERS[*]}"' _ \
    "${MEDIA_HOST_DIR}/bin/lib/cred_relay.sh")" || facts=""
  read -r prefix relay token rest <<<"${facts}"
  read -r -a callers <<<"${rest}"
  if (( ${#callers[@]} == 0 )); then
    harness::add_reason 'lib/cred_relay.sh 의 이름 · 호출자를 읽지 못함'
    return 1
  fi
  fixture::new_case
  fixture::base_env
  for caller in "${callers[@]}"; do
    for rel in "${prefix}${caller}/${token}" "${relay}/${caller}"; do
      mkdir -p -- "${fixture_dir}/runtime/${rel%/*}"
      printf 'pcbench-cred-token' >"${fixture_dir}/runtime/${rel}"
      chmod 0400 "${fixture_dir}/runtime/${rel}"
    done
  done
  fixture::run stop-media.sh -- --cleanup-only || return 1
  if (( fixture_status != 0 )) || [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason "호출자 「${callers[*]}」 토큰 정지 뒤 종료 \
${fixture_status} · RUNTIME_DIR 파일 $(fixture::runtime_files)개(기대 0 · 0): \
$(harness::log_tail "${fixture_dir}/out")"
    return 1
  fi
}
