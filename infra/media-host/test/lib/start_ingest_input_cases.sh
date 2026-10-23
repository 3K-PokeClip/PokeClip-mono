# shellcheck shell=bash
# 전역(fixture_* · FIXTURE_* · AUTH_* · auth_mode_tmpl 등)은 start_fixture.sh ·
# start_ingest_auth_cases.sh · start_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 응답기 모드 외부 입력 사례(사례 51 – 59)와 도우미 — 시작
# 스크립트가 응답기 모드에서만 4C 토큰(Secrets Manager) · 거부 목록(SSM) ·
# 상태 디렉터리 · IP 한도 env 파일을 다루고, compose 렌더본과 모드가 어긋나면
# 막는지, 옛 모드는 그 무엇도 보지 않는지, 정지 스크립트가 링크인 응답기
# 디렉터리를 따라가지 않는지 본다.
#
# start_ingest_auth_cases.sh 다음에 source 한다. 이 파일은 source 해서 쓴다
# (실행하지 않는다). bash 3.2 에서도 돈다.

# 가짜 aws 와 같은 값(test/fakebin/aws) — 4C 토큰 ARN 은 값이 아니다.
readonly AUTH_RESOLVE_ARN='pcbench-ingest-auth-fake-resolve-arn'
readonly AUTH_RESOLVE_TOKEN='pcbench-ingest-auth-fake-resolve-token'
readonly AUTH_DENY_PARAM='/pcbench/ingest-auth/deny-tokens'
readonly AUTH_DENY_HEADER='# pokeclip deny-tokens v1'
readonly AUTH_DENY_TOKEN='pcbench-deny-token-1'
readonly AUTH_RESOLVE_NAME='stream_key_resolve_token'
readonly AUTH_DENY_NAME='deny-tokens'
readonly AUTH_ENV_NAME='ingest-auth.env'
readonly AUTH_RESOLVE_LINE='MEDIA START BLOCKED: ingest-auth resolve token unavailable'
readonly AUTH_DENY_UNAVAILABLE_LINE='MEDIA START BLOCKED: ingest-auth denylist unavailable'
readonly AUTH_DENY_INVALID_LINE='MEDIA START BLOCKED: ingest-auth denylist invalid'
readonly AUTH_IP_LIMIT_LINE=\
'MEDIA START BLOCKED: ingest-auth ip-limit setting unavailable'
readonly AUTH_MISMATCH_LINE='MEDIA START BLOCKED: ingest-auth mode mismatch'

# 응답기 모드 실행에서 넣지 않을 env 이름(사례가 정한다 — 공백으로 잇는다).
auth_input_drop=""
# 1 이면 add_env 가 IP 한도 env 파일을 두지 않는다(없음 사례).
auth_input_no_env=0

#######################################
# 응답기 모드 실행의 외부 입력을 준비한다 — env 세 개(4C ARN · 거부 목록 이름 ·
# 거부 목록 디렉터리)를 fixture_env 에 더하고(auth_input_drop 의 이름은 뺀다),
# compose 렌더본 · IP 한도 env 파일이 아직 없으면 정상 꼴로 둔다.
# Globals:
#   fixture_env, fixture_dir, auth_input_drop
#######################################
auth_input::add_env() {
  local entry
  for entry in "INTERNAL_API_TOKEN_SECRET_ARN=${AUTH_RESOLVE_ARN}" \
    "INGEST_AUTH_DENYLIST_PARAM=${AUTH_DENY_PARAM}" \
    "INGEST_AUTH_DENY_DIR=${fixture_dir}/deny"; do
    case " ${auth_input_drop} " in
      *" ${entry%%=*} "*) continue ;;
    esac
    fixture_env+=("${entry}")
  done
  if [[ ! -e "${fixture_dir}/compose.yml" ]]; then
    auth_input::write_compose 1
  fi
  if (( ! auth_input_no_env )) && [[ ! -e "${fixture_dir}/${AUTH_ENV_NAME}" \
    && ! -L "${fixture_dir}/${AUTH_ENV_NAME}" ]]; then
    printf 'INGEST_AUTH_IP_LIMIT=on\n' >"${fixture_dir}/${AUTH_ENV_NAME}"
  fi
}

#######################################
# compose 렌더본(사례 디렉터리의 compose.yml)을 응답기 서비스 키 줄 수를 정해
# 쓴다.
# Globals:
#   fixture_dir
# Arguments:
#   `  ingest-auth:` 줄 수.
#######################################
auth_input::write_compose() {
  local i
  {
    printf 'services:\n  mediamtx:\n    image: pcbench\n'
    for (( i = 0; i < $1; i++ )); do
      printf '  ingest-auth:\n    image: pcbench\n'
    done
  } >"${fixture_dir}/compose.yml"
}

#######################################
# aws 기록 n 의 argv 를 한 줄로 낸다.
# Globals:
#   fixture_dir
# Arguments:
#   기록 번호.
#######################################
auth_input::aws_argv() {
  paste -s -d ' ' - <"${fixture_dir}/log/aws.$1.argv" 2>/dev/null || true
}

#######################################
# 막힌 실행의 공통 판정 — 줄 · up 0 · RUNTIME_DIR 파일 0 · aws 호출 수 상한.
# Globals:
#   fixture_dir
# Arguments:
#   행 이름, 기대 줄, aws 호출 수 상한.
#######################################
auth_input::expect_blocked() {
  local ok=0 calls
  fixture::expect_blocked "$2" || ok=1
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason "$1: RUNTIME_DIR 에 파일이 남음"
    ok=1
  fi
  calls="$(fixture::count_records aws)"
  if (( calls > $3 )); then
    harness::add_reason "$1: aws ${calls}회(기대 $3회 이하)"
    ok=1
  fi
  fixture::common_checks || ok=1
  (( ok == 0 )) || harness::add_reason "행 「$1」"
  return "${ok}"
}

#######################################
# 사례 51 — 4C 토큰: 응답기 파일(stream_key_resolve_token)에 값 한 줄 · 0400 ·
# 응답기 소유로 두고, aws argv 에는 ARN · 파라미터 이름만, 값은 그 파일에만.
#######################################
auth_input::case_resolve_token() {
  local ok=0 f want
  auth_mode::prod_template || return 1
  fixture::new_case
  auth_mode::run "${auth_mode_tmpl}" || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}: $(harness::log_tail \
"${fixture_dir}/out")"
    return 1
  fi
  f="${fixture_dir}/runtime/${AUTH_DIR_NAME}/${AUTH_RESOLVE_NAME}"
  if [[ "$(cat -- "${f}" 2>/dev/null && printf x)" \
    != "${AUTH_RESOLVE_TOKEN}"$'\n'x ]]; then
    harness::add_reason '4C 토큰 파일 내용이 「값 한 줄」 이 아님'
    ok=1
  fi
  if [[ "$(fixture::perm "${f}" 2>/dev/null)" != -r-------- ]]; then
    harness::add_reason "4C 토큰 파일 권한 $(fixture::perm "${f}" 2>/dev/null)"
    ok=1
  fi
  want="secretsmanager get-secret-value --secret-id ${AUTH_RESOLVE_ARN} \
--query SecretString --output text"
  if [[ "$(auth_input::aws_argv 2)" != "${want}" ]]; then
    harness::add_reason "aws 2 「$(auth_input::aws_argv 2)」"
    ok=1
  fi
  want="ssm get-parameter --name ${AUTH_DENY_PARAM} --query Parameter.Value \
--output text"
  if [[ "$(auth_input::aws_argv 3)" != "${want}" \
    || "$(fixture::count_records aws)" != 3 ]]; then
    harness::add_reason "aws 3 「$(auth_input::aws_argv 3)」 · \
$(fixture::count_records aws)회(기대 3)"
    ok=1
  fi
  auth_mode::check_only_in "${AUTH_RESOLVE_TOKEN}" '4C 토큰' "${f}" || ok=1
  return "${ok}"
}

#######################################
# 4C 토큰 행 하나를 돌린다(value 행은 값 파일을 둔다).
# Globals:
#   fixture_dir, fixture_value_file, auth_input_drop, auth_mode_tmpl
# Arguments:
#   행 이름.
#######################################
auth_input::resolve_run() {
  local value=""
  case "$1" in
    arn-unset) auth_input_drop=INTERNAL_API_TOKEN_SECRET_ARN ;;
    fail | empty | none) ;;
    ctrl) value=$'pcbench\001token' ;;
    nul) value='nul' ;;
    lead) value=' pcbench-token' ;;
    trail) value='pcbench-token ' ;;
    c1) value=$'pcbench\xc2\x85token' ;;
    oversize) value="$(printf '%04096d' 0)" ;;
    max) value="$(printf '%04095d' 0)" ;;
  esac
  if [[ "$1" == nul ]]; then
    fixture_value_file="${fixture_dir}.value"
    printf 'pcbench\000token' >"${fixture_value_file}"
  elif [[ -n "${value}" ]]; then
    fixture::value_file "${value}"
  fi
  case "$1" in
    arn-unset) auth_mode::run "${auth_mode_tmpl}" ;;
    fail | empty | none)
      auth_mode::run "${auth_mode_tmpl}" "PCBENCH_FAKE_AWS_RESOLVE_MODE=$1" ;;
    *)
      auth_mode::run "${auth_mode_tmpl}" PCBENCH_FAKE_AWS_RESOLVE_MODE=value \
        "PCBENCH_FAKE_AWS_RESOLVE_VALUE_FILE=${fixture_value_file}" ;;
  esac
}

#######################################
# 사례 52 — 4C 토큰을 쓸 수 없으면(ARN 없음 · 조회 실패 · 빈 값 · None · 제어
# 문자 · NUL · 앞뒤 공백 · C1 제어 문자 · 파일 4096바이트 초과) 거부 목록을
# 받기 전에 막고 파일을 남기지 않는다. 4095바이트 값(줄바꿈까지 4096)은 받는다.
#######################################
auth_input::case_resolve_blocked() {
  local ok=0 row f max
  auth_mode::prod_template || return 1
  for row in arn-unset fail empty none ctrl nul lead trail c1 oversize; do
    fixture::new_case
    auth_input::resolve_run "${row}" || return 1
    auth_input_drop=""
    # ARN 이 없으면 4C 조회를 부르지 않는다(aws 는 CDN 하나).
    max=2
    [[ "${row}" != arn-unset ]] || max=1
    auth_input::expect_blocked "${row}" "${AUTH_RESOLVE_LINE}" "${max}" || ok=1
  done
  fixture::new_case
  auth_input::resolve_run max || return 1
  f="${fixture_dir}/runtime/${AUTH_DIR_NAME}/${AUTH_RESOLVE_NAME}"
  if (( fixture_status != 0 )) \
    || [[ "$(wc -c <"${f}" 2>/dev/null | tr -d ' ')" != 4096 ]]; then
    harness::add_reason "4095바이트 값이 막힘(종료 ${fixture_status})"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 53 — 거부 목록: SSM 값(머리 줄 + 토큰 줄 · 머리 줄만)을 거부 목록
# 디렉터리(root:응답기 0750)에 파일(root:응답기 0640)로 rename 해 둔다(옛 inode
# 불변).
#######################################
auth_input::case_denylist_installed() {
  local ok=0 dir f mode want
  auth_mode::prod_template || return 1
  fixture::new_case
  dir="${fixture_dir}/deny"
  f="${dir}/${AUTH_DENY_NAME}"
  for mode in ok headeronly; do
    [[ ! -f "${f}" ]] || ln -- "${f}" "${fixture_dir}/old-deny"
    auth_mode::run "${auth_mode_tmpl}" "PCBENCH_FAKE_AWS_SSM_MODE=${mode}" \
      || return 1
    want="${AUTH_DENY_HEADER}"$'\n'
    [[ "${mode}" != ok ]] || want+="${AUTH_DENY_TOKEN}"$'\n'
    if (( fixture_status != 0 )) \
      || [[ "$(cat -- "${f}" 2>/dev/null && printf x)" != "${want}x" ]]; then
      harness::add_reason "${mode}: 거부 목록 내용이 SSM 값과 다름(종료 \
${fixture_status})"
      ok=1
    fi
    if [[ "$(fixture::perm "${f}" 2>/dev/null)" != -rw-r----- \
      || "$(fixture::perm "${dir}" 2>/dev/null)" != drwxr-x--- ]]; then
      harness::add_reason "${mode}: 권한 $(fixture::perm "${f}" 2>/dev/null) · \
$(fixture::perm "${dir}" 2>/dev/null)"
      ok=1
    fi
    if [[ "$(find "${dir}" -mindepth 1 | awk 'END { print NR }')" != 1 ]]; then
      harness::add_reason "${mode}: 거부 목록 디렉터리에 임시 파일이 남음"
      ok=1
    fi
  done
  if [[ "$(cat -- "${fixture_dir}/old-deny" 2>/dev/null)" != \
    "${AUTH_DENY_HEADER}"$'\n'"${AUTH_DENY_TOKEN}" ]]; then
    harness::add_reason '옛 거부 목록 inode 의 내용이 바뀜(제자리 쓰기)'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 54 — 거부 목록을 받지 못하거나(이름 없음 · 없는 파라미터) 꼴이 틀리면
# (공백뿐 · 머리 줄 없음 · 64 KiB 초과 · NUL) 막고, 이미 있던 거부 목록은
# 그대로 · 임시 파일 0 · RUNTIME_DIR 파일 0.
#######################################
auth_input::case_denylist_blocked() {
  local ok=0 row mode want dir
  auth_mode::prod_template || return 1
  for row in 'param-unset unavailable' 'notfound unavailable' \
    'blank invalid' 'noheader invalid' 'oversize invalid' 'nul invalid'; do
    mode="${row% *}"
    want="${AUTH_DENY_UNAVAILABLE_LINE}"
    [[ "${row#* }" != invalid ]] || want="${AUTH_DENY_INVALID_LINE}"
    fixture::new_case
    dir="${fixture_dir}/deny"
    mkdir -p -- "${dir}"
    printf 'keep\n' >"${dir}/${AUTH_DENY_NAME}"
    if [[ "${mode}" == param-unset ]]; then
      auth_input_drop=INGEST_AUTH_DENYLIST_PARAM
      auth_mode::run "${auth_mode_tmpl}" || return 1
      auth_input_drop=""
    else
      auth_mode::run "${auth_mode_tmpl}" "PCBENCH_FAKE_AWS_SSM_MODE=${mode}" \
        || return 1
    fi
    # 이름이 없으면 SSM 을 부르지 않는다(aws 는 CDN · 4C 둘).
    auth_input::expect_blocked "${mode}" "${want}" \
      "$([[ "${mode}" == param-unset ]] && echo 2 || echo 3)" || ok=1
    if [[ "$(cat -- "${dir}/${AUTH_DENY_NAME}")" != keep \
      || "$(find "${dir}" -mindepth 1 | awk 'END { print NR }')" != 1 ]]; then
      harness::add_reason "${mode}: 옛 거부 목록이 바뀌었거나 임시 파일이 남음"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 55 — 상태 디렉터리: 매 기동 응답기 소유 0700 으로 맞추고(안의 파일은
# 둔다 · chown -h), 링크면 따라가지 않고 막는다.
#######################################
auth_input::case_state_dir() {
  local ok=0 state
  auth_mode::prod_template || return 1
  fixture::new_case
  state="${fixture_dir}/runtime/${AUTH_DIR_NAME}/state"
  mkdir -p -- "${state}"
  chmod 0755 "${state}"
  printf 'keep\n' >"${state}/connlog.jsonl"
  auth_mode::run "${auth_mode_tmpl}" || return 1
  if (( fixture_status != 0 )) \
    || [[ "$(fixture::perm "${state}")" != drwx------ \
    || "$(cat -- "${state}/connlog.jsonl")" != keep ]]; then
    harness::add_reason "상태 디렉터리 $(fixture::perm "${state}")(종료 \
${fixture_status} · 기대 drwx------ · 안의 파일 유지)"
    ok=1
  fi
  if ! grep -q -F -x -- "-h ${AUTH_OWNER} ${state}" \
    <(for f in "${fixture_dir}"/log/chown.*.argv; do paste -s -d ' ' - <"${f}"
    done); then
    harness::add_reason '상태 디렉터리 chown 이 「-h 10003:10003」 이 아님'
    ok=1
  fi
  fixture::new_case
  state="${fixture_dir}/runtime/${AUTH_DIR_NAME}/state"
  mkdir -p -- "${state%/*}" "${fixture_dir}/elsewhere"
  chmod 0755 "${fixture_dir}/elsewhere"
  ln -s -- "${fixture_dir}/elsewhere" "${state}"
  auth_mode::run "${auth_mode_tmpl}" || return 1
  auth_input::expect_blocked state-link \
    'MEDIA START FAIL render ingest-auth-state-dir' 3 || ok=1
  if [[ "$(fixture::perm "${fixture_dir}/elsewhere")" != drwxr-xr-x ]]; then
    harness::add_reason '상태 디렉터리 링크의 대상 권한이 바뀜'
    ok=1
  fi
  return "${ok}"
}

#######################################
# IP 한도 env 파일 행 하나를 둔다.
# Globals:
#   fixture_dir
# Arguments:
#   행 이름.
#######################################
auth_input::seed_env_file() {
  local f="${fixture_dir}/${AUTH_ENV_NAME}"
  case "$1" in
    missing) auth_input_no_env=1 ;;
    link)
      printf 'INGEST_AUTH_IP_LIMIT=on\n' >"${fixture_dir}/real.env"
      ln -s -- "${fixture_dir}/real.env" "${f}"
      ;;
    dir) mkdir -p -- "${f}" ;;
    yes) printf 'INGEST_AUTH_IP_LIMIT=yes\n' >"${f}" ;;
    two) printf 'INGEST_AUTH_IP_LIMIT=on\nINGEST_AUTH_IP_LIMIT=off\n' >"${f}" ;;
    crlf) printf 'INGEST_AUTH_IP_LIMIT=on\r\n' >"${f}" ;;
    space) printf ' INGEST_AUTH_IP_LIMIT=on\n' >"${f}" ;;
    blank-tail) printf 'INGEST_AUTH_IP_LIMIT=on\n\n' >"${f}" ;;
    empty) : >"${f}" ;;
    on) printf 'INGEST_AUTH_IP_LIMIT=on\n' >"${f}" ;;
    off) printf 'INGEST_AUTH_IP_LIMIT=off\n' >"${f}" ;;
    off-no-eol) printf 'INGEST_AUTH_IP_LIMIT=off' >"${f}" ;;
  esac
}

#######################################
# 사례 56 — IP 한도 env 파일(compose 파일 옆 ingest-auth.env)이 없거나 · 링크 ·
# 디렉터리 · 꼴 밖이면 외부 입력(4C · 거부 목록)을 받기 전에 막는다. on · off
# 한 줄(끝 줄바꿈 없음 포함)은 받는다.
#######################################
auth_input::case_ip_limit_env() {
  local ok=0 row
  auth_mode::prod_template || return 1
  for row in missing link dir yes two crlf space blank-tail empty; do
    fixture::new_case
    auth_input::write_compose 1
    auth_input::seed_env_file "${row}"
    auth_mode::run "${auth_mode_tmpl}" || return 1
    auth_input_no_env=0
    auth_input::expect_blocked "${row}" "${AUTH_IP_LIMIT_LINE}" 1 || ok=1
  done
  for row in on off off-no-eol; do
    fixture::new_case
    auth_input::seed_env_file "${row}"
    auth_mode::run "${auth_mode_tmpl}" || return 1
    if (( fixture_status != 0 )); then
      harness::add_reason "${row}: 종료 코드 ${fixture_status}: \
$(harness::log_tail "${fixture_dir}/out")"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 57 — 모드 대조: compose 렌더본의 `  ingest-auth:` 줄 수가 모드와
# 어긋나면(옛 모드 + 1 · 응답기 모드 + 0 · 2 · 뒤 공백 꼴) 4C · 거부 목록을 받기
# 전에 막는다.
#######################################
auth_input::case_mode_mismatch() {
  local ok=0 row n tmpl
  auth_mode::prod_template || return 1
  for row in 'legacy 1' 'auth 0' 'auth 2' 'auth space'; do
    n="${row#* }"
    fixture::new_case
    if [[ "${n}" == space ]]; then
      printf 'services:\n  ingest-auth: \n' >"${fixture_dir}/compose.yml"
    else
      auth_input::write_compose "${n}"
    fi
    tmpl="${auth_mode_tmpl}"
    [[ "${row% *}" != legacy ]] || tmpl="${TEST_DIR}/mediamtx.test.yml.tmpl"
    auth_mode::run "${tmpl}" || return 1
    auth_input::expect_blocked "${row}" "${AUTH_MISMATCH_LINE}" 1 || ok=1
    if [[ -e "${fixture_dir}/deny" ]]; then
      harness::add_reason "${row}: 거부 목록 디렉터리가 생김"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 58 — 옛 모드는 응답기 키(4C ARN · 거부 목록 이름 · 디렉터리)가 있어도
# 읽지 않는다: aws 는 CDN 하나 · 응답기 · 거부 목록 디렉터리 0 · env 파일이
# 없어도(링크여도) 통과.
#######################################
auth_input::case_legacy_untouched() {
  local ok=0
  fixture::new_case
  auth_input::write_compose 0
  ln -s -- "${fixture_dir}/absent.env" "${fixture_dir}/${AUTH_ENV_NAME}"
  fixture::base_env
  fixture_env+=("INTERNAL_API_TOKEN_SECRET_ARN=${AUTH_RESOLVE_ARN}"
    "INGEST_AUTH_DENYLIST_PARAM=${AUTH_DENY_PARAM}"
    "INGEST_AUTH_DENY_DIR=${fixture_dir}/deny")
  fixture::run start-media.sh || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}: $(harness::log_tail \
"${fixture_dir}/out")"
    return 1
  fi
  if [[ "$(fixture::count_records aws)" != 1 \
    || "$(auth_input::aws_argv 1)" != *"--secret-id ${FIXTURE_FAKE_ARN} "* ]]
  then
    harness::add_reason "aws $(fixture::count_records aws)회(기대 CDN 하나)"
    ok=1
  fi
  if [[ -e "${fixture_dir}/deny" \
    || -e "${fixture_dir}/runtime/${AUTH_DIR_NAME}" \
    || "$(fixture::runtime_files)" != 2 ]]; then
    harness::add_reason '옛 모드가 응답기 · 거부 목록 파일을 만듦'
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 59 — stop: 응답기 디렉터리가 링크면 따라가 지우지 않고 「MEDIA STOP
# CLEANUP FAIL <응답기 디렉터리>」 · 0 아닌 코드(RUNTIME_DIR 렌더는 지운다).
# 시작이 그 링크로 막힌 뒤 정지해도 같다.
#######################################
auth_input::case_stop_link() {
  local ok=0 path ia name
  auth_mode::prod_template || return 1
  for path in stop-only start-then-stop; do
    fixture::new_case
    fixture::base_env
    seed_runtime
    ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}"
    mkdir -p -- "${fixture_dir}/elsewhere"
    for name in "${AUTH_BASIC_NAME}" "${API_PASS_NAME}" "${AUTH_RESOLVE_NAME}"
    do
      printf 'keep\n' >"${fixture_dir}/elsewhere/${name}"
    done
    ln -s -- "${fixture_dir}/elsewhere" "${ia}"
    if [[ "${path}" == start-then-stop ]]; then
      auth_mode::run "${auth_mode_tmpl}" || return 1
      fixture::expect_blocked 'MEDIA START FAIL render ingest-auth-dir' || ok=1
      fixture::base_env
    fi
    fixture::run stop-media.sh -- --cleanup-only || return 1
    if (( fixture_status == 0 )) || ! grep -q -F -x -- \
      "MEDIA STOP CLEANUP FAIL ${ia}" "${fixture_dir}/out"; then
      harness::add_reason "${path}: 링크인데 CLEANUP FAIL 이 아님(종료 \
${fixture_status}): $(harness::log_tail "${fixture_dir}/out")"
      ok=1
    fi
    if [[ "$(find "${fixture_dir}/elsewhere" -mindepth 1 -type f \
      | awk 'END { print NR }')" != 3 \
      || -e "${fixture_dir}/runtime/mediamtx.yml" ]]; then
      harness::add_reason "${path}: 링크 밖 파일이 지워졌거나 렌더가 남음"
      ok=1
    fi
  done
  return "${ok}"
}
