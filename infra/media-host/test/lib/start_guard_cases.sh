# shellcheck shell=bash
# fixture_* · harness_* 전역은 start_fixture.sh · harness.sh 가 채운다.
# shellcheck disable=SC2154
#
# start_test 의 방어 사례(23 – 29) — 비밀 값 허용 문자 · config.json 반례 ·
# 상속 export · 렌더 대상 꼴 · 신호 · 정지 경로 검사. start_test.sh 가 source
# 한다. start_fixture.sh · harness.sh 와 start_test.sh 의 expect_prereq_fail ·
# expect_secret_blocked 를 쓴다(이 파일을 실을 때가 아니라 사례가 돌 때 있으면
# 된다).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 허용 문자 밖의 글자가 든 시험 값 — YAML 주석 · 별칭 · 태그 · 키 구분 ·
# 흐름 컬렉션 · 공백 · 따옴표 · 셸 · sed 특수 문자.
# shellcheck disable=SC2016 # 펼치지 않는 것이 뜻이다.
readonly GUARD_DISALLOWED_VALUES=(
  'pcbench #comment'
  '*pcbench'
  '!pcbench'
  'pcbench: edge'
  '{pcbench}'
  'pcbench edge'
  'pcbench"edge'
  'pcbench&edge\proxy$fake'
)
# 신호 사례 — 가짜 chown 이 머무는 시간 · 그 표식을 기다리는 상한(초).
readonly GUARD_CHOWN_HOLD=3
readonly GUARD_HOLD_TIMEOUT=10

#######################################
# 사례 23 — 허용 문자 밖의 글자가 든 값은 막고, 그 값이 출력 · 기록에 없다.
# Globals:
#   fixture_dir
#######################################
guard::case_disallowed_values() {
  local ok=0 value leaked
  for value in "${GUARD_DISALLOWED_VALUES[@]}"; do
    fixture::new_case
    fixture::base_env
    fixture::value_file "${value}"
    fixture::run start-media.sh PCBENCH_FAKE_AWS_MODE=value \
      "PCBENCH_FAKE_AWS_VALUE_FILE=${fixture_value_file}" || return 1
    if ! expect_secret_blocked; then
      harness::add_reason "값 꼴 「${value%% *}…」 이 막히지 않음"
      ok=1
    fi
    leaked="$(fixture::files_with "${value}" "${fixture_dir}/log" \
      "${fixture_dir}/out" | awk 'END { print NR }')"
    if (( leaked != 0 )); then
      harness::add_reason '막은 값이 기록 · 출력에 있음'
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 24 — config.json 반례: 호스트가 값에만 있음 · credHelpers 가 null 이고
# 호스트가 최상위 다른 곳 · auths 에만 있음 · 값이 ecr-login 아님 · 이스케이프된
# 키 · 형식 오류 · 키 중복 · 중첩 · 최상위가 배열 · 값이 객체 · 뒤에 군더더기.
# 모두 선행 점검 실패다.
# Globals:
#   fixture_dir
#######################################
guard::case_config_counterexamples() {
  local ok=0 h="${FIXTURE_FAKE_ECR_HOST}" body path n=0
  local bodies=(
    "{\"credHelpers\":{\"other.invalid\":\"${h}\"}}"
    "{\"credHelpers\":null,\"${h}\":\"ecr-login\"}"
    "{\"auths\":{\"${h}\":{}},\"credHelpers\":{\"other.invalid\":\
\"ecr-login\"}}"
    "{\"credHelpers\":{\"${h}\":\"desktop\"}}"
    "{\"credHelpers\":{\"${h%%.*}\\u002e${h#*.}\":\"ecr-login\"}}"
    "{\"credHelpers\":{\"${h}\":\"ecr-login\"}"
    "{\"credHelpers\":{},\"credHelpers\":{\"${h}\":\"ecr-login\"}}"
    "{\"x\":{\"credHelpers\":{\"${h}\":\"ecr-login\"}}}"
    "{\"credHelpers\":{\"${h}\":\"ecr-login\",\"${h}\":\"ecr-login\"}}"
    "[\"credHelpers\",{\"${h}\":\"ecr-login\"}]"
    "{\"credHelpers\":{\"${h}\":{\"x\":\"ecr-login\"}}}"
    "{\"credHelpers\":{\"${h}\":\"ecr-login\"}} x"
    "{\"credHelpers\":{\"${h}\":\"ecr-login\",}}"
  )
  for body in "${bodies[@]}"; do
    n=$((n + 1))
    fixture::new_case
    fixture::base_env
    path="${fixture_dir}/docker-config/config.json"
    printf '%s\n' "${body}" >"${path}"
    fixture::run start-media.sh || return 1
    if ! expect_prereq_fail "MEDIA PREREQ FAIL docker-config ${path}"; then
      harness::add_reason "반례 ${n} 이 통과함"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 25 — 공백 · 줄바꿈 · 다른 키 · 이스케이프가 든 auths 값 · 숫자 · 배열 ·
# 중첩이 섞인 정상 config.json 은 통과하고 up 까지 간다.
# Globals:
#   fixture_dir
#######################################
guard::case_config_complex_valid() {
  local h="${FIXTURE_FAKE_ECR_HOST}"
  fixture::new_case
  fixture::base_env
  cat >"${fixture_dir}/docker-config/config.json" <<EOF
{
  "auths" : { "x.invalid" : { "auth" : "YWJj\\u003d\\"" }, "y" : {} },
	"credHelpers": {
    "other.invalid": "desktop",
    "${h}" : "ecr-login"
  },
  "experimental": "enabled",
  "n": [1, -2.5e3, true, false, null, {"a": [[], {}]}]
}
EOF
  fixture::run start-media.sh || return 1
  if (( fixture_status != 0 )) || [[ "$(fixture::count_calls up)" != 1 ]]; then
    harness::add_reason "종료 코드 ${fixture_status}: $(harness::log_tail \
"${fixture_dir}/out")"
    return 1
  fi
  fixture::common_checks
}

#######################################
# 사례 26 — 부모가 비밀 · 렌더 변수 이름을 export 한 채 넘기거나(bash 의 local
# 이 export 속성을 이어받는 꼴) allexport 를 켜 넘겨도(SHELLOPTS), 가짜
# docker · chown 의 환경과 출력에 시험 비밀이 없다. 시험의 env -i 가 지우는
# 조건을 일부러 만든다.
#######################################
guard::case_inherited_export() {
  local ok=0
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh secret=inherited rendered=inherited \
    value=inherited content=inherited head=inherited tail=inherited || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "상속 export: 종료 코드 ${fixture_status}"
    ok=1
  fi
  fixture::common_checks || ok=1
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh SHELLOPTS=allexport || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "allexport: 종료 코드 ${fixture_status}"
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 27 — 렌더 대상(RUNTIME_DIR/mediamtx.yml)이 디렉터리이거나 디렉터리로
# 가는 링크면 그 안으로 옮기지 않고 막는다. 임시 렌더 파일도 남지 않는다.
# Globals:
#   fixture_dir
#######################################
guard::case_render_target_not_file() {
  local ok=0 kind tmp_left
  for kind in dir link; do
    fixture::new_case
    fixture::base_env
    mkdir -p -- "${fixture_dir}/runtime" "${fixture_dir}/elsewhere"
    if [[ "${kind}" == dir ]]; then
      mkdir -- "${fixture_dir}/runtime/mediamtx.yml"
    else
      ln -s -- "${fixture_dir}/elsewhere" "${fixture_dir}/runtime/mediamtx.yml"
    fi
    fixture::run start-media.sh || return 1
    fixture::expect_blocked 'MEDIA START FAIL render target' || ok=1
    tmp_left="$(find "${fixture_dir}/runtime" "${fixture_dir}/elsewhere" \
      -type f | awk 'END { print NR }')"
    if (( tmp_left != 0 )); then
      harness::add_reason "${kind}: 렌더 · 임시 파일 ${tmp_left}개가 남음"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 임시 렌더 파일이 있는 동안(가짜 chown 이 머무는 중) 스크립트 프로세스 하나에만
# 신호를 보내고, 기대 종료 코드 · up 0 · RUNTIME_DIR 파일 0 을 본다.
# Globals:
#   fixture_dir, fixture_cmd, harness_spawned_pid, harness_waited_status
# Arguments:
#   신호(TERM · INT), 기대 종료 코드.
#######################################
guard::signal_during_render() {
  local sig="$1" want="$2" ok=0 pid deadline status=0
  fixture::new_case
  fixture::base_env
  fixture::command "${MEDIA_HOST_DIR}/bin/start-media.sh" \
    "PCBENCH_FAKE_CHOWN_HOLD=${GUARD_CHOWN_HOLD}"
  harness::spawn_group "${fixture_cmd[@]}" >"${fixture_dir}/out" 2>&1
  pid="${harness_spawned_pid}"
  deadline=$((SECONDS + GUARD_HOLD_TIMEOUT))
  while [[ ! -e "${fixture_dir}/log/chown.hold" ]] && (( SECONDS < deadline ))
  do
    sleep 0.2
  done
  if [[ ! -e "${fixture_dir}/log/chown.hold" ]]; then
    harness::add_reason "${sig}: ${GUARD_HOLD_TIMEOUT}초 안에 chown 에 닿지 않음"
    harness::terminate "${pid}"
    return 1
  fi
  kill "-${sig}" "${pid}" 2>/dev/null || true
  harness::wait_exit "${pid}" "${GUARD_HOLD_TIMEOUT}" || status=$?
  if (( status == 124 )); then
    harness::add_reason "${sig} 뒤 상한 안에 끝나지 않음"
    ok=1
  elif (( harness_waited_status != want )); then
    harness::add_reason "${sig} 뒤 종료 코드 ${harness_waited_status}(기대 \
${want})"
    ok=1
  fi
  # 남은 가짜 chown(같은 그룹)까지 끝낸 뒤 센다.
  harness::terminate "${pid}"
  if [[ "$(fixture::count_calls up)" != 0 ]]; then
    harness::add_reason "${sig} 뒤에도 compose up 을 부름"
    ok=1
  fi
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason "${sig} 뒤 RUNTIME_DIR 에 파일이 남음"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 이 셸에서 띄운 자식이 INT 를 받을 수 있는지 본다. 작업 제어 없이 배경(&)으로
# 띄운 셸은 INT 를 무시한 채 시작하고, 그 무시는 자식에게 그대로 넘어가 트랩으로도
# 되돌릴 수 없다 — 그때 INT 갈래는 판정할 수 없다.
# Globals:
#   harness_spawned_pid
# Returns:
#   0 받음 / 1 무시됨.
#######################################
guard::int_deliverable() {
  local pid alive=0
  harness::spawn_group sleep 30
  pid="${harness_spawned_pid}"
  # fork 뒤 exec 전의 자식은 아직 셸이라 신호를 달리 다룰 수 있다 — 자리 잡을
  # 시간을 준다.
  sleep 0.5
  kill -INT "${pid}" 2>/dev/null || true
  sleep 0.5
  if kill -0 "${pid}" 2>/dev/null; then
    alive=1
  fi
  harness::terminate "${pid}"
  (( ! alive ))
}

#######################################
# 사례 28 — 렌더 중 TERM → 143, INT → 130. 어느 쪽이든 up 0 · 임시 렌더 파일
# 잔여 0. INT 는 기다리던 자식이 정상 종료하면 bash 가 그대로 이어 가므로(협조
# 종료 규칙) 트랩이 없으면 up 까지 간다.
#######################################
guard::case_signal_removes_tmp() {
  local ok=0
  if [[ ! -x "${MEDIA_HOST_DIR}/bin/start-media.sh" ]]; then
    harness::add_reason 'bin/start-media.sh 없음'
    return 1
  fi
  guard::signal_during_render TERM 143 || ok=1
  if ! guard::int_deliverable; then
    harness::add_reason \
      'INT: 시험 셸이 INT 를 무시한 채 시작됨(작업 제어 없는 배경 실행) — 판정 불가'
    return 1
  fi
  guard::signal_during_render INT 130 || ok=1
  return "${ok}"
}

#######################################
# 사례 29 — stop 에 RUNTIME_DIR 이 상대 경로 · / 면 아무것도 하지 않고
# 실패한다.
# Globals:
#   fixture_dir, fixture_env, fixture_status
#######################################
guard::case_stop_runtime_dir() {
  local ok=0 dir
  for dir in runtime /; do
    fixture::new_case
    fixture::base_env RUNTIME_DIR
    fixture_env+=("RUNTIME_DIR=${dir}")
    fixture::run stop-media.sh || return 1
    if (( fixture_status == 0 )) \
      || [[ "$(fixture::count_records docker)" != 0 ]] \
      || ! grep -q -F -x 'MEDIA STOP FAIL runtime-dir' "${fixture_dir}/out"
    then
      harness::add_reason "RUNTIME_DIR 「${dir}」: 실패 · docker 0 · 고정 줄이 \
아님"
      ok=1
    fi
  done
  return "${ok}"
}
