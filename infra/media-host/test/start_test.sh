#!/usr/bin/env bash
#
# 시작 · 정지 스크립트 시험 — 성공 · 실패 갈래와 restart 를 빠뜨린 서비스 · 렌더
# 제자리 쓰기 · 중간에 끊긴 렌더의 임시 파일 · down 실패 뒤 렌더 삭제 · 비밀 변수
# export, 그리고 사이드카 관측 자격(기동마다 만드는 API 비밀번호 · 토큰 둘 — 사례 30 – 35 는
# lib/start_api_pass_cases.sh).
#
# bin/start-media.sh · bin/stop-media.sh 를 빈 환경(env -i)에서 돌린다. PATH 맨
# 앞에는 가짜 docker · chown(test/start_fakebin — argv 와 환경을 전부 기록)과
# 가짜 aws · 자격 도우미(test/fakebin)만 둔다. 컨테이너는 띄우지 않는다. 시험은
# 스크립트가 읽는 변수의 값만 바꾼다 — 우회 플래그는 없고, 선행 점검 세 항목과
# CDN 비밀 조회는 모든 실행에서 똑같이 돈다.
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/start_test.sh
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
# shellcheck source=lib/start_fixture.sh
source "${TEST_DIR}/lib/start_fixture.sh"
# shellcheck source=lib/start_guard_cases.sh
source "${TEST_DIR}/lib/start_guard_cases.sh"
# shellcheck source=lib/render.sh
source "${TEST_DIR}/lib/render.sh"
# shellcheck source=lib/start_api_pass_cases.sh
source "${TEST_DIR}/lib/start_api_pass_cases.sh"
# shellcheck source=lib/stop_guard_cases.sh
source "${TEST_DIR}/lib/stop_guard_cases.sh"

readonly COMPOSE_TEMPLATE="${MEDIA_HOST_DIR}/compose.yml.tmpl"
readonly MTX_TEMPLATE="${TEST_DIR}/mediamtx.test.yml.tmpl"
readonly PROD_MTX_TEMPLATE="${MEDIA_HOST_DIR}/mediamtx.yml.tmpl"
readonly PROD_COMPOSE_FILE='/etc/pokeclip-media/compose.yml'
readonly PROD_PROJECT='pokeclip-media'
readonly PROD_DOCKER_CONFIG='/root/.docker/config.json'
# 허용 문자를 모두 품은 시험 값.
readonly ALLOWED_SECRET='pcbench-AZaz09._+=-secret'
readonly BLOCKED_LINE='MEDIA START BLOCKED: CDN secret unavailable'
readonly API_BLOCKED_LINE='MEDIA START BLOCKED: MTX API credential unavailable'
# 사이드카 관측 자격 — 시작 스크립트가 만드는 비밀번호 파일 이름 · 꼴.
readonly API_PASS_NAME='mtx_api_pass'
readonly API_PASS_PATTERN='^[0-9a-f]{64}$'
# 우회 플래그로 읽힐 문자열(대소문자 무시).
readonly BYPASS_PATTERN='skip|bypass|no_prereq|prereq=0'
readonly CASE_COUNT=40

#######################################
# up 호출 argv 를 한 줄로 낸다(인자 사이 공백). 호출이 하나가 아니면 실패.
# Outputs:
#   argv 한 줄.
#######################################
up_argv() {
  local files
  files="$(fixture::docker_calls up)"
  if [[ -z "${files}" \
    || "$(printf '%s\n' "${files}" | awk 'END { print NR }')" != 1 ]]; then
    harness::add_reason "compose up 호출이 한 번이 아님"
    return 1
  fi
  paste -s -d ' ' - <"${files}"
}

#######################################
# 템플릿에서 restart: "no" 인 서비스 이름을 정렬해 낸다. restart 가 없는
# 서비스가 있으면 「MISSING <이름>」 줄을 낸다.
# Arguments:
#   compose 템플릿.
#######################################
template_no_restart_services() {
  awk '
    /^services:/ { insvc = 1; next }
    /^[^ #%]/ { insvc = 0 }
    insvc && /^  [a-z][a-z0-9-]*:[ ]*$/ {
      svc = $1; sub(/:$/, "", svc); seen[svc] = 1; next
    }
    insvc && svc != "" && /^    restart:/ {
      v = $0; sub(/^    restart:[ ]*/, "", v); gsub(/"/, "", v); r[svc] = v
    }
    END {
      for (s in seen) {
        if (!(s in r)) print "MISSING " s
        else if (r[s] == "no") print s
      }
    }' "$1" | LC_ALL=C sort
}

#######################################
# 사례 1 — 성공 갈래: up argv(--force-recreate — 유닛 밖에 남은 컨테이너가 옛
# 렌더를 붙든 채 재사용되지 않게 · --remove-orphans — 렌더에서 빠진
# 서비스의 옛 컨테이너가 같은 망 칸에 남지 않게) · -p · -f · 렌더 내용 · 권한 ·
# chown argv · aws argv · 비밀 노출 0 · exec(가짜 docker 가 스크립트와 같은
# 프로세스) · up 의 종료 코드가 그대로 · docker 가 점검한 DOCKER_CONFIG 를 받음 ·
# chown 순서(그 순간 0400 · 최종 경로 없음) · core 한도 0.
#######################################
case_success() {
  local ok=0 argv target expected aws_argv outside up_file n
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh PCBENCH_FAKE_UP_STATUS=7 || return 1
  if (( fixture_status != 7 )); then
    harness::add_reason "종료 코드 ${fixture_status}(기대 up 의 7): \
$(harness::log_tail "${fixture_dir}/out")"
    return 1
  fi
  argv="$(up_argv)" || return 1
  up_file="$(fixture::docker_calls up)"
  check_exec_and_env "${up_file}" || ok=1
  expected="compose --ansi never -p ${FIXTURE_PROJECT} -f \
${fixture_dir}/compose.yml up --force-recreate --remove-orphans \
--abort-on-container-exit --attach mediamtx --attach nginx"
  if [[ "${argv}" != "${expected}" ]]; then
    harness::add_reason "up argv 「${argv}」"
    ok=1
  fi
  target="${fixture_dir}/runtime/mediamtx.yml"
  check_rendered "${target}" "${FIXTURE_FAKE_SECRET}" || ok=1
  if [[ "$(fixture::perm "${fixture_dir}/runtime")" != drwx--x--x ]]; then
    harness::add_reason "RUNTIME_DIR 권한 $(fixture::perm \
"${fixture_dir}/runtime")(기대 drwx--x--x)"
    ok=1
  fi
  api_pass::check_chown_records || ok=1
  if [[ "$(ulimit -c)" == 0 ]]; then
    harness::add_reason \
      '시험 셸의 core 한도가 0 이라 스크립트의 ulimit 을 가릴 수 없음'
    ok=1
  fi
  for n in 1 2; do
    if [[ "$(cat -- "${fixture_dir}/log/chown.${n}.state" 2>/dev/null)" \
      != 'perm=-r-------- final=absent core=0' ]]; then
      harness::add_reason "chown ${n} 순간 상태 「$(cat -- \
"${fixture_dir}/log/chown.${n}.state" 2>/dev/null)」(기대 0400 · 최종 경로 \
없음 · core 0)"
      ok=1
    fi
  done
  aws_argv="$(paste -s -d ' ' - <"${fixture_dir}/log/aws.1.argv" 2>/dev/null)" \
    || aws_argv=""
  if [[ "${aws_argv}" != "secretsmanager get-secret-value --secret-id \
${FIXTURE_FAKE_ARN} --query SecretString --output text" ]]; then
    harness::add_reason "aws argv 「${aws_argv}」"
    ok=1
  fi
  outside="$(fixture::files_with "${FIXTURE_FAKE_SECRET}" "${fixture_dir}" \
    | grep -v -F -x -- "${target}" | awk 'END { print NR }')"
  if (( outside != 0 )); then
    harness::add_reason "렌더 경로 밖에 시험 비밀이 든 파일 ${outside}개"
    ok=1
  fi
  api_pass::check_contained || ok=1
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# up 을 받은 가짜 docker 가 스크립트 프로세스 자신(exec)이고, DOCKER_CONFIG 를
# 사례 디렉터리의 docker-config 로 받았는지 본다.
# Globals:
#   fixture_dir, harness_bounded_pid
# Arguments:
#   up 호출 argv 파일.
#######################################
check_exec_and_env() {
  local ok=0 base="${1%.argv}" pid
  pid="$(cat -- "${base}.pid" 2>/dev/null)" || pid=""
  # harness_bounded_pid 는 harness.sh 가 채운다.
  # shellcheck disable=SC2154
  if [[ -z "${pid}" || "${pid}" != "${harness_bounded_pid}" ]]; then
    harness::add_reason "up 을 받은 docker 프로세스 ${pid:-없음} ≠ 스크립트 \
${harness_bounded_pid}(exec 아님)"
    ok=1
  fi
  if ! grep -q -F -x -- "DOCKER_CONFIG=${fixture_dir}/docker-config" \
    "${base}.env"; then
    harness::add_reason 'up 의 환경에 점검한 DOCKER_CONFIG 가 없음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 렌더 파일이 템플릿의 두 토큰을 각각 한 곳씩 값으로 바꾼 것과 같고 권한이
# 0400 인지 본다. CDN 토큰 자리는 기대 값, API 비밀번호 토큰 자리는 사이드카
# 비밀번호 파일의 값이다. 기대 내용은 awk index() 로 글자 그대로 바꿔 따로
# 만든다.
# Globals:
#   fixture_dir
# Arguments:
#   렌더 파일, 기대 CDN 값, 템플릿(기본 시험 템플릿).
#######################################
check_rendered() {
  # 기대 내용은 사례 디렉터리 밖(형제)에 둔다 — 「렌더 경로 밖 비밀 0」 검사 대상이다.
  local ok=0 expected="${fixture_dir}.expected.yml" pass
  local template="${3:-${MTX_TEMPLATE}}"
  if [[ ! -f "$1" ]]; then
    harness::add_reason '렌더 파일 없음'
    return 1
  fi
  pass="$(api_pass::value 2>/dev/null)" || pass=""
  PCBENCH_VALUE="$2" PCBENCH_TOKEN="${FIXTURE_SECRET_TOKEN}" \
    PCBENCH_PASS="${pass}" PCBENCH_PASS_TOKEN="${FIXTURE_API_PASS_TOKEN}" awk '
    function put(t, v,   i) {
      i = index($0, t)
      if (i > 0) $0 = substr($0, 1, i - 1) v substr($0, i + length(t))
    }
    { put(ENVIRON["PCBENCH_TOKEN"], ENVIRON["PCBENCH_VALUE"])
      put(ENVIRON["PCBENCH_PASS_TOKEN"], ENVIRON["PCBENCH_PASS"])
      print }' "${template}" >"${expected}"
  if [[ -z "${pass}" ]] || ! cmp -s -- "${expected}" "$1"; then
    harness::add_reason '렌더 내용이 「토큰 두 곳을 각 값으로」 와 다름'
    ok=1
  fi
  if grep -q -F -e "${FIXTURE_SECRET_TOKEN}" -e "${FIXTURE_API_PASS_TOKEN}" \
    -- "$1"; then
    harness::add_reason '렌더에 토큰이 남음'
    ok=1
  fi
  if [[ "$(fixture::perm "$1")" != -r-------- ]]; then
    harness::add_reason "렌더 권한 $(fixture::perm "$1")(기대 -r--------)"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 2 — 허용 문자 전부([A-Za-z0-9._+=-])가 든 값을 글자 그대로 렌더한다.
#######################################
case_allowed_chars() {
  fixture::new_case
  fixture::base_env
  fixture::value_file "${ALLOWED_SECRET}"
  fixture::run start-media.sh PCBENCH_FAKE_AWS_MODE=value \
    "PCBENCH_FAKE_AWS_VALUE_FILE=${fixture_value_file}" || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}"
    return 1
  fi
  check_rendered "${fixture_dir}/runtime/mediamtx.yml" "${ALLOWED_SECRET}"
}

#######################################
# 사례 3 — 시작 스크립트의 --attach 집합 = 템플릿의 restart "no" 집합, 그리고
# 템플릿의 모든 서비스가 restart 를 적었다 — 뒤에 더한 서비스가 restart 를
# 빠뜨리면 그 서비스가 죽어도 아무도 다시 띄우지 않는다.
#######################################
case_attach_set() {
  local ok=0 attach no_set up_file
  if [[ ! -f "${COMPOSE_TEMPLATE}" ]]; then
    harness::add_reason 'compose.yml.tmpl 없음'
    return 1
  fi
  no_set="$(template_no_restart_services "${COMPOSE_TEMPLATE}")"
  if [[ "${no_set}" == *MISSING* ]]; then
    harness::add_reason "restart 를 적지 않은 서비스: $(printf '%s' \
"${no_set}" | grep MISSING | paste -s -d ' ' -)"
    ok=1
  fi
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh || return 1
  up_file="$(fixture::docker_calls up | head -n 1)"
  attach=""
  if [[ -n "${up_file}" ]]; then
    attach="$(awk 'prev == "--attach" { print } { prev = $0 }' "${up_file}" \
      | LC_ALL=C sort)"
  fi
  if [[ -z "${attach}" || "${attach}" != "${no_set}" ]]; then
    harness::add_reason "--attach 「$(printf '%s' "${attach}" \
      | paste -s -d ' ' -)」 ≠ restart \"no\" 「$(printf '%s' "${no_set}" \
      | paste -s -d ' ' -)」"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 선행 점검 실패 사례의 공통 판정.
# Arguments:
#   기대 고정 줄.
#######################################
expect_prereq_fail() {
  local ok=0
  fixture::expect_blocked "$1" || ok=1
  if [[ "$(fixture::count_records aws)" != 0 ]]; then
    harness::add_reason 'CDN 비밀 조회가 선행 점검보다 먼저 돎'
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

# 사례 4 — compose 판 불일치.
case_compose_mismatch() {
  fixture::new_case
  fixture::base_env MEDIA_EXPECTED_COMPOSE_VERSION
  fixture_env+=("MEDIA_EXPECTED_COMPOSE_VERSION=0.0.1-other")
  fixture::run start-media.sh || return 1
  expect_prereq_fail 'MEDIA PREREQ FAIL compose-version'
}

# 사례 5 — 기대 compose 판 변수가 없음 · 빈 값(fail closed).
case_compose_unset() {
  local ok=0
  fixture::new_case
  fixture::base_env MEDIA_EXPECTED_COMPOSE_VERSION
  fixture::run start-media.sh || return 1
  expect_prereq_fail 'MEDIA PREREQ FAIL compose-version' || ok=1
  fixture::new_case
  fixture::base_env MEDIA_EXPECTED_COMPOSE_VERSION
  fixture_env+=("MEDIA_EXPECTED_COMPOSE_VERSION=")
  fixture::run start-media.sh PCBENCH_FAKE_COMPOSE_VERSION= || return 1
  expect_prereq_fail 'MEDIA PREREQ FAIL compose-version' || ok=1
  return "${ok}"
}

# 사례 6 — PATH 에 docker-credential-ecr-login 없음. 호스트 명령 자리에 실물
# 도우미가 있는 러너(GitHub ubuntu)도 흉내 낸다 — 호스트 디렉터리에 도우미를 두고
# 링크 모음을 다시 만들어도 「없음」 이어야 한다.
# Globals:
#   fixture_root, fixture_sysbin, TEST_DIR
case_helper_missing() {
  local ok=0 saved="${fixture_sysbin}" host
  fixture::new_case 0
  fixture::base_env
  fixture::run start-media.sh || return 1
  expect_prereq_fail 'MEDIA PREREQ FAIL ecr-credential-helper' || ok=1
  host="$(mktemp -d "${fixture_root}/host-bin.XXXXXX")"
  ln -s -- "${TEST_DIR}/fakebin/docker-credential-ecr-login" \
    "${host}/docker-credential-ecr-login"
  fixture::make_sysbin "${host}" /usr/bin /bin
  fixture::new_case 0
  fixture::base_env
  fixture::run start-media.sh || { fixture_sysbin="${saved}"; return 1; }
  fixture_sysbin="${saved}"
  if ! expect_prereq_fail 'MEDIA PREREQ FAIL ecr-credential-helper'; then
    harness::add_reason '호스트 자리의 실물 도우미가 「없음」 을 가림'
    ok=1
  fi
  return "${ok}"
}

# 사례 7 — config.json 의 credHelpers 에 호스트 없음 · config.json 없음.
case_cred_helpers_missing() {
  local ok=0 path
  fixture::new_case
  fixture::base_env
  path="${fixture_dir}/docker-config/config.json"
  printf '{"auths":{},"credsStore":"%s"}\n' "${FIXTURE_FAKE_ECR_HOST}" \
    >"${path}"
  fixture::run start-media.sh || return 1
  expect_prereq_fail "MEDIA PREREQ FAIL docker-config ${path}" || ok=1
  fixture::new_case
  fixture::base_env
  path="${fixture_dir}/docker-config/config.json"
  rm -f -- "${path}"
  fixture::run start-media.sh || return 1
  expect_prereq_fail "MEDIA PREREQ FAIL docker-config ${path}" || ok=1
  return "${ok}"
}

#######################################
# 사례 8 — prod 기본값: 기대 판만 넣고 DOCKER_CONFIG · MEDIA_COMPOSE_FILE 을
# 주입하지 않으면 실패 줄에 기본 경로가 찍힌다.
#######################################
case_prod_default_prereq() {
  if [[ -r "${PROD_DOCKER_CONFIG}" ]]; then
    harness::add_reason "이 기계에 ${PROD_DOCKER_CONFIG} 가 있어 판정 불가"
    return 1
  fi
  fixture::new_case
  fixture::base_env DOCKER_CONFIG MEDIA_COMPOSE_FILE MEDIA_COMPOSE_PROJECT
  fixture::run start-media.sh || return 1
  expect_prereq_fail "MEDIA PREREQ FAIL docker-config ${PROD_DOCKER_CONFIG}" \
    || return 1
  # 점검 전에 기본값을 채워 export 했으면 docker(판 확인)도 같은 경로를 받는다.
  if ! grep -q -F -x -- "DOCKER_CONFIG=${PROD_DOCKER_CONFIG%/config.json}" \
    "${fixture_dir}/log/docker.1.env" 2>/dev/null; then
    harness::add_reason 'docker 의 환경에 기본 DOCKER_CONFIG 가 없음'
    return 1
  fi
}

#######################################
# 사례 9 — prod 기본값으로 모든 점검을 통과시키면 -f · -p 가 prod 기본값이다.
#######################################
case_prod_default_up() {
  local argv
  fixture::new_case
  fixture::base_env MEDIA_COMPOSE_FILE MEDIA_COMPOSE_PROJECT
  fixture::run start-media.sh || return 1
  argv="$(up_argv)" || return 1
  if [[ "${argv}" != "compose --ansi never -p ${PROD_PROJECT} -f \
${PROD_COMPOSE_FILE} up "* ]]; then
    harness::add_reason "up argv 「${argv}」(기대 -p ${PROD_PROJECT} -f \
${PROD_COMPOSE_FILE})"
    return 1
  fi
  fixture::common_checks
}

#######################################
# CDN 비밀 차단 사례의 공통 판정 — 렌더 파일도 없어야 한다.
#######################################
expect_secret_blocked() {
  local ok=0
  fixture::expect_blocked "${BLOCKED_LINE}" || ok=1
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason 'RUNTIME_DIR 에 파일이 남음'
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

# 사례 10 — 조회 실패(aws 종료 0 아님).
case_secret_fail() {
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh PCBENCH_FAKE_AWS_MODE=fail || return 1
  expect_secret_blocked
}

# 사례 11 — 빈 값 · None(문자열 비밀이 없음) · ARN 변수 없음.
case_secret_empty() {
  local ok=0 mode
  for mode in empty none; do
    fixture::new_case
    fixture::base_env
    fixture::run start-media.sh "PCBENCH_FAKE_AWS_MODE=${mode}" || return 1
    expect_secret_blocked || ok=1
  done
  fixture::new_case
  fixture::base_env CDN_SECRET_ARN
  fixture::run start-media.sh || return 1
  expect_secret_blocked || ok=1
  return "${ok}"
}

# 사례 12 — 인자를 하나라도 주면 실패하고 docker 를 부르지 않는다.
case_arguments() {
  local ok=0
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh -- --no-prereq || return 1
  if (( fixture_status == 0 )); then
    harness::add_reason '인자를 줬는데 종료 코드 0'
    ok=1
  fi
  if [[ "$(fixture::count_records docker)" != 0 \
    || "$(fixture::count_records aws)" != 0 ]]; then
    harness::add_reason '인자를 줬는데 docker · aws 를 부름'
    ok=1
  fi
  if ! grep -q -F -x 'MEDIA START FAIL usage' "${fixture_dir}/out"; then
    harness::add_reason '출력에 「MEDIA START FAIL usage」 줄 없음'
    ok=1
  fi
  return "${ok}"
}

# 사례 13 — 스크립트 본문에 우회 플래그 문자열 0건(정적).
case_no_bypass() {
  local ok=0 f hits
  for f in "${MEDIA_HOST_DIR}/bin/start-media.sh" \
    "${MEDIA_HOST_DIR}/bin/stop-media.sh"; do
    if [[ ! -f "${f}" ]]; then
      harness::add_reason "bin/${f##*/} 없음"
      ok=1
      continue
    fi
    # grep 의 「맞는 줄 없음」(종료 1)만 0 건이다 — 읽기 실패는 0 이 아니다.
    if ! hits="$(grep -c -i -E -- "${BYPASS_PATTERN}" "${f}")" \
      && [[ "${hits}" != 0 ]]; then
      harness::add_reason "bin/${f##*/} 를 읽지 못함"
      ok=1
    elif (( hits != 0 )); then
      harness::add_reason "bin/${f##*/} 에 우회 문자열 ${hits}줄"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 14 — 렌더는 임시 파일 rename 이다. 옛 렌더를 하드링크로 잡아 두면 그
# inode 의 내용은 기동 뒤에도 그대로다 — 이전 스택이 살아 있는 채 다시 돌아도
# 실행 중인 MediaMTX 가 보는 파일을 고쳐 쓰지 않는다.
#######################################
case_rename_not_overwrite() {
  local ok=0 target
  fixture::new_case
  fixture::base_env
  mkdir -p -- "${fixture_dir}/runtime"
  target="${fixture_dir}/runtime/mediamtx.yml"
  printf 'old-render\n' >"${target}"
  chmod 0600 "${target}"
  ln -- "${target}" "${fixture_dir}/old-inode"
  fixture::run start-media.sh || return 1
  if [[ "$(cat -- "${fixture_dir}/old-inode")" != old-render ]]; then
    harness::add_reason '옛 렌더 inode 의 내용이 바뀜(제자리 쓰기)'
    ok=1
  fi
  check_rendered "${target}" "${FIXTURE_FAKE_SECRET}" || ok=1
  return "${ok}"
}

#######################################
# 사례 15 — chown 이 실패하면 0 아닌 종료 · up 0 · RUNTIME_DIR 에 파일 0(임시
# 파일을 trap 이 지운다 — 끊긴 렌더의 비밀 임시 파일이 /run 에 쌓이지 않게).
#######################################
case_chown_failure() {
  local ok=0 left
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh PCBENCH_FAKE_CHOWN_STATUS=1 || return 1
  fixture::expect_blocked 'MEDIA START FAIL render chown' || ok=1
  left="$(fixture::runtime_files)"
  if (( left != 0 )); then
    harness::add_reason "RUNTIME_DIR 에 파일 ${left}개 남음"
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

# 사례 16 — 0755 로 이미 있는 RUNTIME_DIR 을 0711 로 맞춘다(디렉터리 권한을
# 기동마다 다시 맞춘다).
case_dir_mode_reset() {
  local mode
  fixture::new_case
  fixture::base_env
  mkdir -p -- "${fixture_dir}/runtime"
  chmod 0755 "${fixture_dir}/runtime"
  fixture::run start-media.sh || return 1
  mode="$(fixture::perm "${fixture_dir}/runtime")"
  if [[ "${mode}" != drwx--x--x ]]; then
    harness::add_reason "RUNTIME_DIR 권한 ${mode}(기대 drwx--x--x)"
    return 1
  fi
}

#######################################
# 정지 사례 준비 — RUNTIME_DIR 에 렌더 파일 · 사이드카 비밀번호 파일과 각자의
# 임시 파일 꼴 잔여를 둔다.
#######################################
seed_runtime() {
  mkdir -p -- "${fixture_dir}/runtime"
  printf '%s\n' "${FIXTURE_FAKE_SECRET}" >"${fixture_dir}/runtime/mediamtx.yml"
  printf '%s\n' "${FIXTURE_FAKE_SECRET}" \
    >"${fixture_dir}/runtime/.mediamtx.yml.AbC123"
  printf '%s\n' "${FIXTURE_FAKE_SECRET}" \
    >"${fixture_dir}/runtime/${API_PASS_NAME}"
  printf '%s\n' "${FIXTURE_FAKE_SECRET}" \
    >"${fixture_dir}/runtime/.${API_PASS_NAME}.XyZ789"
  chmod 0400 "${fixture_dir}/runtime/mediamtx.yml" \
    "${fixture_dir}/runtime/${API_PASS_NAME}"
}

#######################################
# 사례 17 — stop: down 인자는 `down` 하나로 고정 · prod 기본 -p · -f · 렌더와
# 임시 파일 잔여 0.
#######################################
case_stop_default() {
  local ok=0 files argv
  fixture::new_case
  fixture::base_env MEDIA_COMPOSE_FILE MEDIA_COMPOSE_PROJECT
  seed_runtime
  fixture::run stop-media.sh || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}"
    ok=1
  fi
  files="$(fixture::docker_calls down)"
  argv="$(paste -s -d ' ' - <"${files:-/dev/null}")"
  if [[ "${argv}" != \
    "compose -p ${PROD_PROJECT} -f ${PROD_COMPOSE_FILE} down" ]]; then
    harness::add_reason "down argv 「${argv}」"
    ok=1
  fi
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason 'stop 뒤 RUNTIME_DIR 에 파일이 남음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 18 — down 이 실패해도 렌더를 지우고 down 의 종료 코드를 돌려준다(비밀이
# 든 렌더가 남지 않게).
#######################################
case_stop_down_failure() {
  local ok=0
  fixture::new_case
  fixture::base_env
  seed_runtime
  fixture::run stop-media.sh PCBENCH_FAKE_DOWN_STATUS=3 || return 1
  if (( fixture_status != 3 )); then
    harness::add_reason "종료 코드 ${fixture_status}(기대 down 의 3)"
    ok=1
  fi
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason 'down 실패 뒤 RUNTIME_DIR 에 파일이 남음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 19 — --cleanup-only: docker 를 부르지 않고 렌더 · 임시 파일을 지운다
# (prod 기본값 그대로 — 주입 없음).
#######################################
case_stop_cleanup_only() {
  local ok=0
  fixture::new_case
  fixture::base_env MEDIA_COMPOSE_FILE MEDIA_COMPOSE_PROJECT
  seed_runtime
  fixture::run stop-media.sh -- --cleanup-only || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}"
    ok=1
  fi
  if [[ "$(fixture::count_records docker)" != 0 ]]; then
    harness::add_reason '--cleanup-only 인데 docker 를 부름'
    ok=1
  fi
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason '--cleanup-only 뒤 RUNTIME_DIR 에 파일이 남음'
    ok=1
  fi
  return "${ok}"
}

# 사례 20 — stop 에 다른 인자 · 인자 둘이면 실패하고 아무것도 하지 않는다.
case_stop_arguments() {
  local ok=0 args
  for args in '--volumes' '--cleanup-only --cleanup-only'; do
    fixture::new_case
    fixture::base_env
    seed_runtime
    # shellcheck disable=SC2086 # 인자 목록을 낱말로 나눈다.
    fixture::run stop-media.sh -- ${args} || return 1
    if (( fixture_status == 0 )) \
      || [[ "$(fixture::count_records docker)" != 0 ]] \
      || [[ ! -f "${fixture_dir}/runtime/mediamtx.yml" ]]; then
      harness::add_reason "인자 「${args}」: 실패 · docker 0 · 파일 유지가 아님"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 21 — 템플릿의 토큰이 정확히 하나가 아니면(0 · 2) 렌더하지 않고 막는다.
#######################################
case_template_token_count() {
  local ok=0 body tmpl
  for body in 'hlsCDNSecret: none' \
    "a: ${FIXTURE_SECRET_TOKEN}"$'\n'"b: ${FIXTURE_SECRET_TOKEN}"; do
    fixture::new_case
    tmpl="${fixture_dir}/bad.tmpl"
    printf '%s\n' "${body}" >"${tmpl}"
    fixture::base_env MEDIA_MTX_TEMPLATE
    fixture_env+=("MEDIA_MTX_TEMPLATE=${tmpl}")
    fixture::run start-media.sh || return 1
    fixture::expect_blocked 'MEDIA START FAIL render template-token' || ok=1
    if [[ "$(fixture::runtime_files)" != 0 ]]; then
      harness::add_reason 'RUNTIME_DIR 에 파일이 남음'
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 22 — 가운데 · 끝 줄바꿈이 든 값을 막는다(끝 줄바꿈을 잘라 통과시키지
# 않는다).
case_secret_multiline() {
  local ok=0 mode
  for mode in multiline trailing; do
    fixture::new_case
    fixture::base_env
    fixture::run start-media.sh "PCBENCH_FAKE_AWS_MODE=${mode}" || return 1
    expect_secret_blocked || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 36 — 시작 스크립트와 운영 확인 부품(bin/lib/ops.sh)의 CDN 비밀 허용 글자
# 정규식이 바이트까지 같다(정적). 한쪽만 바뀌면 시작은 되는데 운영 확인이
# 거부하거나 그 반대가 된다.
#######################################
case_cdn_pattern_same() {
  local start ops
  start="$(sed -n "s/^readonly SECRET_PATTERN='\(.*\)'\$/\1/p" \
    "${MEDIA_HOST_DIR}/bin/start-media.sh" 2>/dev/null)" || start=""
  ops="$(sed -n "s/^readonly OPS_CDN_SECRET_PATTERN='\(.*\)'\$/\1/p" \
    "${MEDIA_HOST_DIR}/bin/lib/ops.sh" 2>/dev/null)" || ops=""
  if [[ -z "${start}" || "${start}" != "${ops}" ]]; then
    harness::add_reason "정규식 시작 「${start:-없음}」 · 운영 「${ops:-없음}」"
    return 1
  fi
}

cleanup() {
  local status=$?
  harness::end_last_group
  if [[ -n "${fixture_root}" ]]; then
    chmod -R u+w -- "${fixture_root}" 2>/dev/null || true
    rm -rf -- "${fixture_root:?}"
  fi
  exit "${status}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/start_test.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/pcbench-edge-proxy-start.XXXXXX")"
  fixture::make_sysbin /usr/bin /bin
  # 스크립트가 core 한도를 스스로 0 으로 내리는지 보려면 물려받는 한도가 0 이
  # 아니어야 한다(macOS 기본 소프트 한도는 0). 못 올리면 사례 1 이 그 사실로
  # 실패한다.
  ulimit -S -c unlimited 2>/dev/null || true

  harness::run_case 1 '성공 — up argv(재생성) · 렌더 0400 · chown · 비밀 노출 0' \
    case_success
  harness::run_case 2 '허용 문자 전부를 글자 그대로 렌더' case_allowed_chars
  harness::run_case 3 '--attach 집합 = 템플릿 restart "no" 집합' case_attach_set
  harness::run_case 4 '선행 점검 — compose 판 불일치' case_compose_mismatch
  harness::run_case 5 '선행 점검 — 기대 판 변수 없음 · 빈 값' case_compose_unset
  harness::run_case 6 '선행 점검 — 자격 도우미 없음' case_helper_missing
  harness::run_case 7 '선행 점검 — credHelpers 없음 · config.json 없음' \
    case_cred_helpers_missing
  harness::run_case 8 'prod 기본값 — 실패 줄에 기본 config 경로' \
    case_prod_default_prereq
  harness::run_case 9 'prod 기본값 — -p · -f 기본값으로 up' case_prod_default_up
  harness::run_case 10 'CDN 비밀 조회 실패 → 기동 차단' case_secret_fail
  harness::run_case 11 'CDN 비밀 빈 값 · None · ARN 없음 → 기동 차단' \
    case_secret_empty
  harness::run_case 12 '인자 하나 → 실패 · docker 0' case_arguments
  harness::run_case 13 '우회 플래그 문자열 0건(정적)' case_no_bypass
  harness::run_case 14 '렌더는 rename — 옛 inode 불변' case_rename_not_overwrite
  harness::run_case 15 'chown 실패 → 임시 파일 잔여 0' case_chown_failure
  harness::run_case 16 '0755 디렉터리 → 0711' case_dir_mode_reset
  harness::run_case 17 'stop — down 인자 고정 · prod 기본값 · 잔여 0' \
    case_stop_default
  harness::run_case 18 'stop — down 실패에도 렌더 삭제 · down 코드 반환' \
    case_stop_down_failure
  harness::run_case 19 'stop --cleanup-only — docker 0 · 잔여 0' \
    case_stop_cleanup_only
  harness::run_case 20 'stop — 다른 인자 거부' case_stop_arguments
  harness::run_case 21 '템플릿 토큰 0 · 2개 → 렌더 거부' \
    case_template_token_count
  harness::run_case 22 '가운데 · 끝 줄바꿈 든 값 → 기동 차단' \
    case_secret_multiline
  harness::run_case 23 '허용 밖 글자(YAML 기호 · 공백 · 특수 문자) → 기동 차단' \
    guard::case_disallowed_values
  harness::run_case 24 'config.json 반례 → 선행 점검 실패' \
    guard::case_config_counterexamples
  harness::run_case 25 'config.json 복잡한 정상 꼴 → 통과' \
    guard::case_config_complex_valid
  harness::run_case 26 '상속 export · allexport 에도 비밀 노출 0' \
    guard::case_inherited_export
  harness::run_case 27 '렌더 대상이 디렉터리 · 링크 → 렌더 거부' \
    guard::case_render_target_not_file
  harness::run_case 28 '렌더 중 TERM · INT → 끝남 · up 0 · 임시 파일 0' \
    guard::case_signal_removes_tmp
  harness::run_case 29 'stop — RUNTIME_DIR 상대 경로 · / 거부' \
    guard::case_stop_runtime_dir
  harness::run_case 30 '운영 템플릿(templatefile) → 두 토큰 치환 · 따옴표 자리' \
    api_pass::case_prod_template_pipe
  harness::run_case 31 'API 비밀번호 — 실행마다 새 16진 64자' api_pass::case_fresh
  harness::run_case 32 'API 비밀번호 생성 실패 → 기동 차단 · 잔여 0' \
    api_pass::case_unavailable
  harness::run_case 33 '비밀번호 파일 단계 실패 → 렌더 거부 · 두 파일 잔여 0' \
    api_pass::case_install_failure
  harness::run_case 34 '템플릿 API 토큰 0 · 2개 → 렌더 거부' api_pass::case_token_count
  harness::run_case 35 '상속 export 에도 API 비밀번호 노출 0' \
    api_pass::case_inherited_export
  harness::run_case 36 'CDN 비밀 허용 글자 정규식 — 시작 = 운영 확인(정적)' \
    case_cdn_pattern_same
  harness::run_case 37 'stop — down 실패면 라벨로 컨테이너 회수 · 렌더 삭제' \
    stop::case_down_fallback
  harness::run_case 38 'stop — 끝나지 않는 down 중 TERM → 143 · 렌더 삭제' \
    stop::case_signal_during_down
  harness::run_case 39 'stop — 끝나지 않는 폴백 ps 중 TERM → 143 · 렌더 삭제' \
    stop::case_signal_during_fallback
  harness::run_case 40 'stop — 끝나지 않는 폴백 ps → 상한 뒤 FAIL · 렌더 삭제' \
    stop::case_fallback_bounded

  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
