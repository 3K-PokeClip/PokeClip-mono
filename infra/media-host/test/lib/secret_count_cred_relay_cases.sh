# shellcheck shell=bash
# 전역(ops_dir · ops_env 등)과 ops_fixture::* 는 ops_fixture.sh 가, setup_box ·
# setup_ssm_one · plant · run_count · expect_hygiene · COMMAND_ID 는
# secret_count_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# secret_count_test 의 IMDS 대리 창구 사례(35 – 41). 창구 켬 상자에서 시작
# 스크립트는 RUNTIME_DIR/cred-relay/<호출자>(indexer · janitor)에 중계 토큰(16진
# 64자 · 끝 줄바꿈 없음)을 둔다. box 는 그 둘이 있으면 켬으로 보고 두 토큰과
# 「지금」 역할 자격(호스트 aws CLI 의 configure export-credentials — 비밀 키 ·
# 세션 토큰)을 대조 값에 더한다. 둘 다 없으면(끔 상자 · 정지 뒤) 지금처럼 센다.
# 하나만 있거나 꼴 밖이면 cred-relay, 역할 자격을 못 받거나 꼴 밖이면
# role-cred 로 실패한다. 중계 태그 저널(pokeclip-media-cred-relay)은 두 모드
# 모두 읽는다. ssm 은 그 파일을 읽지 않는다(응답기 파일과 같은 한계).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 중계 토큰 둘(시작 스크립트 꼴 — 소문자 16진 64자, 서로 다르고 다른 시험
# 값과도 다르다).
CR_INDEXER_TOKEN="$(printf '1234abcd%.0s' 1 2 3 4 5 6 7 8)"
readonly CR_INDEXER_TOKEN
CR_JANITOR_TOKEN="$(printf '5678ef90%.0s' 1 2 3 4 5 6 7 8)"
readonly CR_JANITOR_TOKEN
# 역할 자격(가짜 aws 가 export-credentials 로 낸다). 액세스 키 ID 는 비밀이
# 아니라 대조하지 않는다. 비밀 키는 실제 꼴처럼 40자, 세션 토큰은 더 길다 —
# 둘 다 base64 글자(「/」 · 「+」 · 「=」 포함).
readonly CR_ACCESS_KEY_ID='PCBENCHFAKEACCESSKEY'
readonly CR_ROLE_SECRET='pcbench/FakeRoleSecret+0123456789abcdefg'
CR_ROLE_SESSION="$(printf 'pcbenchFakeSessionToken0123/+%.0s' 1 2 3 4 5 6 7 \
  8)="
readonly CR_ROLE_SESSION
readonly CR_EXPIRATION='2026-11-15T12:00:00+00:00'
readonly CR_TAG='pokeclip-media-cred-relay'
# 역할 자격의 원천을 IMDS 아닌 곳으로 바꾸는 환경 변수(AWS CLI 기본 자격 체인의
# 앞 단계 · IMDS 주소) — export-credentials 의 환경에는 없어야 한다.
readonly CR_SOURCE_ENV=(AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
  AWS_SESSION_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
  AWS_CONTAINER_CREDENTIALS_FULL_URI
  AWS_CONTAINER_CREDENTIALS_RELATIVE_URI AWS_WEB_IDENTITY_TOKEN_FILE
  AWS_EC2_METADATA_SERVICE_ENDPOINT AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE)

# 중계 토큰 디렉터리 경로를 낸다.
cr_cases::relay_dir() {
  printf '%s/runtime/cred-relay' "${ops_dir}"
}

#######################################
# 가짜 aws 가 낼 export-credentials 출력(env-no-export 꼴)을 둔다.
# Arguments:
#   비밀 키, 세션 토큰.
#######################################
cr_cases::role_creds() {
  {
    printf 'AWS_ACCESS_KEY_ID=%s\n' "${CR_ACCESS_KEY_ID}"
    printf 'AWS_SECRET_ACCESS_KEY=%s\n' "$1"
    printf 'AWS_SESSION_TOKEN=%s\n' "$2"
    printf 'AWS_CREDENTIAL_EXPIRATION=%s\n' "${CR_EXPIRATION}"
  } >"${ops_dir}/state/role.creds"
}

#######################################
# 창구 켬 상자 — setup_box 에 중계 토큰 둘과 역할 자격을 더한다.
#######################################
cr_cases::setup_on() {
  local dir
  setup_box
  dir="$(cr_cases::relay_dir)"
  mkdir -p -- "${dir}"
  printf '%s' "${CR_INDEXER_TOKEN}" >"${dir}/indexer"
  printf '%s' "${CR_JANITOR_TOKEN}" >"${dir}/janitor"
  cr_cases::role_creds "${CR_ROLE_SECRET}" "${CR_ROLE_SESSION}"
}

# export-credentials 를 부른 가짜 aws 기록 수를 낸다.
cr_cases::export_calls() {
  local f n=0
  while IFS= read -r f; do
    [[ "$(head -n 1 -- "${f}")" != configure ]] || n=$((n + 1))
  done < <(ops_fixture::calls aws)
  printf '%s\n' "${n}"
}

#######################################
# 창구 값(토큰 둘 · 비밀 키 · 세션 토큰)이 의도된 통로 밖에 0건인지 본다.
#######################################
cr_cases::expect_no_leak() {
  local ok=0
  ops_fixture::expect_no_leak "${CR_INDEXER_TOKEN}" 'indexer 중계 토큰' || ok=1
  ops_fixture::expect_no_leak "${CR_JANITOR_TOKEN}" 'janitor 중계 토큰' || ok=1
  ops_fixture::expect_no_leak "${CR_ROLE_SECRET}" '역할 비밀 키' || ok=1
  ops_fixture::expect_no_leak "${CR_ROLE_SESSION}" '역할 세션 토큰' || ok=1
  return "${ok}"
}

#######################################
# export-credentials 호출 수와 꼴을 본다 — 기대가 1 이면 argv 가 정확히
# 「configure export-credentials --format env-no-export」 이다.
# Arguments:
#   기대 호출 수.
#######################################
cr_cases::expect_export_calls() {
  local n f
  n="$(cr_cases::export_calls)"
  if [[ "${n}" != "$1" ]]; then
    harness::add_reason "export-credentials ${n}회(기대 $1)"
    return 1
  fi
  (( $1 == 1 )) || return 0
  while IFS= read -r f; do
    [[ "$(head -n 1 -- "${f}")" == configure ]] || continue
    if [[ "$(paste -s -d ' ' -- "${f}")" \
      != 'configure export-credentials --format env-no-export' ]]; then
      harness::add_reason "export-credentials argv: $(paste -s -d ' ' -- \
"${f}")"
      return 1
    fi
  done < <(ops_fixture::calls aws)
}

# 사례 35 — box 켬: 중계 토큰 둘 · 역할 비밀 키 · 세션 토큰을 센다 — 중계 태그
# 저널의 indexer 토큰 1 · 세션 토큰 1 · janitor 태그 저널의 janitor 토큰 1 ·
# 사이드카 태그 저널의 비밀 키 1 · 유닛 저널의 CDN 1 = 5. 값을 담는 새 지역
# 변수 이름(relay_token · role_secret · role_session · rest)이 부모 환경에서
# export 된 채 들어와도 자식 환경에 남지 않는다. 값은 argv · 환경 · 출력에 0.
cr_cases::case_count() {
  local ok=0 joined found_in
  cr_cases::setup_on
  plant "${ops_dir}/state/journal.t.${CR_TAG}" "${CR_INDEXER_TOKEN}" 1
  plant "${ops_dir}/state/journal.t.${CR_TAG}" "${CR_ROLE_SESSION}" 1
  plant "${ops_dir}/state/journal.t.pokeclip-media-janitor" \
    "${CR_JANITOR_TOKEN}" 1
  plant "${ops_dir}/state/journal.t.pokeclip-media-sidecar" \
    "${CR_ROLE_SECRET}" 1
  plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
  ops_fixture::run "${SCRIPT}" relay_token=inherited role_secret=inherited \
    role_session=inherited rest=inherited -- box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 5' 0 || ok=1
  expect_hygiene || ok=1
  cr_cases::expect_no_leak || ok=1
  cr_cases::expect_export_calls 1 || ok=1
  joined="$(cat "${ops_dir}"/log/journalctl.*.argv 2>/dev/null \
    | paste -s -d ' ' -)"
  if [[ "${joined}" != *"-t ${CR_TAG} -q --no-pager -o cat"* ]]; then
    harness::add_reason "태그 저널 ${CR_TAG} 를 읽지 않음"
    ok=1
  fi
  found_in="$(cd -- "${ops_dir}/log" && grep -l -E \
    '^(relay_token|role_secret|role_session|rest)=' -- *.env \
    | paste -s -d ' ' -)" || true
  if [[ -n "${found_in}" ]]; then
    harness::add_reason "상속 export 이름이 자식 환경에 남음: ${found_in}"
    ok=1
  fi
  return "${ok}"
}

# 사례 36 — box 켬: 어디에도 심지 않으면 0 이다(창구 값이 빈 값 · 짧은 값으로
# 모든 줄에 맞지 않는다 — 중계 태그 저널에 값 없는 줄이 있어도).
cr_cases::case_zero() {
  local ok=0
  cr_cases::setup_on
  printf 'cred_served caller=indexer from=cache\n' \
    >"${ops_dir}/state/journal.t.${CR_TAG}"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 0' 0 || ok=1
  expect_hygiene || ok=1
  cr_cases::expect_no_leak || ok=1
  cr_cases::expect_export_calls 1 || ok=1
  return "${ok}"
}

# 사례 37 — box 끔: 중계 토큰 파일이 없으면(디렉터리만 있음 — 정지 뒤 ·
# 디렉터리도 없음 — 끔 상자) 지금처럼 센다. 역할 자격은 받지 않는다
# (export-credentials 0). 중계 태그 저널은 그래도 읽는다 — 거기 심은 CDN 1 = 1.
cr_cases::case_off() {
  local ok=0 row
  for row in dir-only none; do
    setup_box
    [[ "${row}" == none ]] || mkdir -p -- "$(cr_cases::relay_dir)"
    plant "${ops_dir}/state/journal.t.${CR_TAG}" "${OPS_FAKE_CDN_SECRET}" 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT box 1' 0 \
      || ! expect_hygiene || ! cr_cases::expect_export_calls 0; then
      harness::add_reason "행 ${row}"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 중계 토큰 자리 하나를 꼴 밖으로 만든다(setup_on 뒤).
# Arguments:
#   갈래 이름.
#######################################
cr_cases::write_relay_variant() {
  local dir file
  dir="$(cr_cases::relay_dir)"
  file="${dir}/janitor"
  case "$1" in
    partial) rm -f -- "${file}" ;;
    partial-indexer) rm -f -- "${dir}/indexer" ;;
    newline) printf '%s\n' "${CR_JANITOR_TOKEN}" >"${file}" ;;
    short) printf '%s' "${CR_JANITOR_TOKEN:1}" >"${file}" ;;
    non-hex) printf 'g%s' "${CR_JANITOR_TOKEN:1}" >"${file}" ;;
    upper) printf 'A%s' "${CR_JANITOR_TOKEN:1}" >"${file}" ;;
    too-large) printf '%s0' "${CR_JANITOR_TOKEN}" >"${file}" ;;
    empty) : >"${file}" ;;
    file-dir)
      rm -f -- "${file}"
      mkdir -- "${file}"
      ;;
    file-symlink)
      mv -- "${file}" "${dir}/real"
      ln -s -- "${dir}/real" "${file}"
      ;;
    dir-symlink)
      mv -- "${dir}" "${ops_dir}/runtime/elsewhere"
      ln -s -- "${ops_dir}/runtime/elsewhere" "${dir}"
      ;;
    *) return 1 ;;
  esac
}

# 사례 38 — box: 중계 토큰이 하나만 있거나(janitor 만 없음 · indexer 만 없음)
# 시작 스크립트가 쓰는 꼴(소문자 16진 64자 · 끝 줄바꿈 없음 · 일반 파일 · 링크
# 아님) 밖이면 cred-relay 로 실패한다(종료 1). 역할 자격은 받으러 가지 않는다.
cr_cases::case_relay_malformed() {
  local ok=0 variant
  for variant in partial partial-indexer newline short non-hex upper \
    too-large empty file-dir file-symlink dir-symlink; do
    cr_cases::setup_on
    cr_cases::write_relay_variant "${variant}" || return 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL cred-relay' 1 \
      || ! expect_hygiene || ! cr_cases::expect_no_leak \
      || ! cr_cases::expect_export_calls 0; then
      harness::add_reason "갈래 ${variant}"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# export-credentials 출력 하나를 꼴 밖으로 만든다(setup_on 뒤).
# Arguments:
#   갈래 이름.
#######################################
cr_cases::write_role_variant() {
  local file="${ops_dir}/state/role.creds"
  case "$1" in
    denied) rm -f -- "${file}" ;;
    empty) : >"${file}" ;;
    no-secret)
      printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SESSION_TOKEN=%s\n' \
        "${CR_ACCESS_KEY_ID}" "${CR_ROLE_SESSION}" >"${file}"
      ;;
    no-session)
      printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\n' \
        "${CR_ACCESS_KEY_ID}" "${CR_ROLE_SECRET}" >"${file}"
      ;;
    two-secrets)
      cr_cases::role_creds "${CR_ROLE_SECRET}" "${CR_ROLE_SESSION}"
      printf 'AWS_SECRET_ACCESS_KEY=%s\n' "${CR_ROLE_SECRET}" >>"${file}"
      ;;
    two-sessions)
      cr_cases::role_creds "${CR_ROLE_SECRET}" "${CR_ROLE_SESSION}"
      printf 'AWS_SESSION_TOKEN=%s\n' "${CR_ROLE_SESSION}" >>"${file}"
      ;;
    empty-secret) cr_cases::role_creds '' "${CR_ROLE_SESSION}" ;;
    short-secret)
      cr_cases::role_creds "${CR_ROLE_SECRET:1}" "${CR_ROLE_SESSION}"
      ;;
    bad-session)
      cr_cases::role_creds "${CR_ROLE_SECRET}" "${CR_ROLE_SESSION} x"
      ;;
    *) return 1 ;;
  esac
}

# 사례 39 — box 켬: 역할 자격을 못 받거나(거부 · 빈 출력) 꼴 밖이면(비밀 키 ·
# 세션 토큰 줄이 없음 · 둘 이상 · 빈 값 · 40자 미만 · base64 글자 밖) role-cred
# 로 실패한다(종료 1). 받은 값은 출력 · 기록에 0.
cr_cases::case_role_malformed() {
  local ok=0 variant
  for variant in denied empty no-secret no-session two-secrets two-sessions \
    empty-secret short-secret bad-session; do
    cr_cases::setup_on
    cr_cases::write_role_variant "${variant}" || return 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL role-cred' 1 \
      || ! expect_hygiene || ! cr_cases::expect_no_leak \
      || ! cr_cases::expect_export_calls 1; then
      harness::add_reason "갈래 ${variant}"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 40 — ssm: 중계 토큰 파일은 읽지 않고 역할 자격도 받지 않는다(알려진
# 한계) — 꼴 밖 파일이 있어도 실패하지 않고 SSM 기록만 센다.
cr_cases::case_ssm_ignores() {
  local ok=0
  setup_ssm_one
  mkdir -p -- "$(cr_cases::relay_dir)"
  printf 'None\n' >"$(cr_cases::relay_dir)/indexer"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT ssm 1' 0 || ok=1
  cr_cases::expect_export_calls 0 || ok=1
  return "${ok}"
}

#######################################
# 마지막 실행의 aws 기록 가운데 첫 argv 가 정한 하위 명령인 첫 기록의 환경 파일을
# 낸다(없으면 빈 값).
# Arguments:
#   하위 명령(configure · secretsmanager).
#######################################
cr_cases::aws_env_file() {
  local f
  while IFS= read -r f; do
    if [[ "$(head -n 1 -- "${f}")" == "$1" ]]; then
      printf '%s\n' "${f%.argv}.env"
      return 0
    fi
  done < <(ops_fixture::calls aws)
}

# 사례 41 — box 켬: 역할 자격은 인스턴스 역할(IMDS)에서만 받는다. 부모 환경의
# 자격 · 프로필 · 컨테이너 자격 · 웹 ID · IMDS 주소 변수(CR_SOURCE_ENV)는
# export-credentials 의 환경에 없고, 설정 · 자격 파일은 /dev/null 이다. 고정은
# 그 호출 하나만이다 — 비밀 읽기(secretsmanager)는 부모 환경 그대로다.
cr_cases::case_role_source_pinned() {
  local ok=0 name env_file parent=()
  cr_cases::setup_on
  for name in "${CR_SOURCE_ENV[@]}"; do
    parent+=("${name}=pcbench-parent")
  done
  parent+=("AWS_CONFIG_FILE=${ops_dir}/home/config"
    "AWS_SHARED_CREDENTIALS_FILE=${ops_dir}/home/credentials")
  ops_fixture::run "${SCRIPT}" "${parent[@]}" -- box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 0' 0 || ok=1
  cr_cases::expect_export_calls 1 || ok=1
  env_file="$(cr_cases::aws_env_file configure)"
  if [[ ! -f "${env_file}" ]]; then
    harness::add_reason 'export-credentials 환경 기록 없음'
    return 1
  fi
  for name in "${CR_SOURCE_ENV[@]}"; do
    if grep -q -- "^${name}=" "${env_file}"; then
      harness::add_reason "export-credentials 환경에 ${name} 이 남음"
      ok=1
    fi
  done
  for name in AWS_CONFIG_FILE AWS_SHARED_CREDENTIALS_FILE; do
    if ! grep -q -x -F -- "${name}=/dev/null" "${env_file}"; then
      harness::add_reason "export-credentials 환경의 ${name} 이 /dev/null 아님"
      ok=1
    fi
  done
  env_file="$(cr_cases::aws_env_file secretsmanager)"
  if [[ ! -f "${env_file}" ]] || ! grep -q -x -F -- \
    'AWS_PROFILE=pcbench-parent' "${env_file}"; then
    harness::add_reason '비밀 읽기(secretsmanager)의 부모 환경이 바뀜'
    ok=1
  fi
  return "${ok}"
}
