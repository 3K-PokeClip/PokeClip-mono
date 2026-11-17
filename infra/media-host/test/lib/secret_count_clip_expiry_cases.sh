# shellcheck shell=bash
# 전역(ops_dir · ops_env 등)과 ops_fixture::* 는 ops_fixture.sh 가, setup_box ·
# setup_ingest_auth · setup_ssm_one · plant · plant_json · run_count ·
# expect_hygiene · expect_ia_hygiene · expect_watched_calls · IA_JSON_* ·
# COMMAND_ID 는 secret_count_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# secret_count_test 의 60일 만료 창구 토큰 사례(31 – 34). 시작 스크립트는
# CLIP_EXPIRY_TOKEN_SECRET_ARN 이 찰 때(두 모드 공통) RUNTIME_DIR/clip-expiry/
# token 에 사이드카가 clip 에 싣는 토큰을 둔다. box 는 그 파일이 있으면 원문과
# slog JSON 꼴을 대조 값에 더하고, 없으면(만료 끔 · 받기 실패) 지금처럼 센다.
# 있는데 시작 스크립트가 쓰는 꼴 밖이면 clip-expiry 로 실패한다. ssm 은 그
# 파일을 읽지 않는다(응답기 파일과 같은 한계).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 만료 토큰 시험 값(안에 공백 — 꼴 안). 응답기 4C 토큰과 다르다.
readonly CE_TOKEN='pcbench fake clip expiry token'

# 만료 토큰 파일 경로를 낸다.
clip_cases::token_file() {
  printf '%s/runtime/clip-expiry/token' "${ops_dir}"
}

#######################################
# 만료 토큰 파일을 둔다(setup_box · setup_ingest_auth 뒤).
# Arguments:
#   토큰(없으면 CE_TOKEN).
#######################################
clip_cases::plant_token() {
  mkdir -p -- "${ops_dir}/runtime/clip-expiry"
  printf '%s\n' "${1:-${CE_TOKEN}}" >"$(clip_cases::token_file)"
}

#######################################
# 만료 토큰 파일 하나를 꼴 밖으로 만든다(plant_token 뒤).
# Arguments:
#   갈래 이름.
#######################################
clip_cases::write_variant() {
  local dir="${ops_dir}/runtime/clip-expiry" token
  token="$(clip_cases::token_file)"
  case "$1" in
    empty) printf '\n' >"${token}" ;;
    no-newline) printf '%s' "${CE_TOKEN}" >"${token}" ;;
    two-lines) printf '%s\n%s\n' "${CE_TOKEN}" "${CE_TOKEN}" >"${token}" ;;
    control) printf 'pcbench\tclip\n' >"${token}" ;;
    none) printf 'None\n' >"${token}" ;;
    lead-space) printf ' pcbench\n' >"${token}" ;;
    trail-space) printf 'pcbench \n' >"${token}" ;;
    c1) printf 'pc\302\205clip\n' >"${token}" ;;
    too-large)
      head -c 4096 /dev/zero | tr '\0' 'c' >"${token}"
      printf '\n' >>"${token}"
      ;;
    token-dir)
      rm -f -- "${token}"
      mkdir -- "${token}"
      ;;
    token-symlink)
      mv -- "${token}" "${dir}/real"
      ln -s -- "${dir}/real" "${token}"
      ;;
    dir-symlink)
      mv -- "${dir}" "${ops_dir}/runtime/elsewhere"
      ln -s -- "${ops_dir}/runtime/elsewhere" "${dir}"
      ;;
    *) return 1 ;;
  esac
}

# 사례 31 — box 두 모드: 만료 토큰을 원문으로도 slog JSON 꼴로도 센다 —
# 사이드카 태그 저널의 원문 1 · JSON 줄 1 · 유닛 저널의 CDN 1 = 3. 값을 담는
# 새 지역 변수 이름(expiry_token)이 부모 환경에서 export 된 채 들어와도 자식
# 환경에 남지 않는다.
clip_cases::case_count() {
  local ok=0 setup token="${IA_JSON_TOKENS[0]}" escaped="${IA_JSON_ESCAPED[0]}"
  local found_in
  for setup in setup_box setup_ingest_auth; do
    "${setup}"
    clip_cases::plant_token "${token}"
    plant "${ops_dir}/state/journal.t.pokeclip-media-sidecar" "${token}" 1
    plant_json "${ops_dir}/state/journal.t.pokeclip-media-sidecar" \
      "${escaped}"
    plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
    ops_fixture::run "${SCRIPT}" expiry_token=inherited -- box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT box 3' 0 \
      || ! expect_hygiene \
      || ! ops_fixture::expect_no_leak "${token}" '만료 토큰' \
      || ! ops_fixture::expect_no_leak "${escaped}" '만료 토큰 JSON 꼴'; then
      harness::add_reason "${setup}"
      ok=1
    fi
    found_in="$(cd -- "${ops_dir}/log" \
      && grep -l -E '^expiry_token=' -- *.env | paste -s -d ' ' -)" || true
    if [[ -n "${found_in}" ]]; then
      harness::add_reason "${setup}: 상속 export 이름이 자식 환경에 남음: \
${found_in}"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 32 — box: 만료 토큰 파일이 없으면(디렉터리만 있음 · 디렉터리도 없음)
# 지금처럼 센다(CDN 1 = 1). 받기 실패 · 만료 끔은 실패가 아니다.
clip_cases::case_absent() {
  local ok=0 row
  for row in dir-only none; do
    setup_box
    [[ "${row}" == none ]] || mkdir -p -- "${ops_dir}/runtime/clip-expiry"
    plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT box 1' 0 \
      || ! expect_hygiene; then
      harness::add_reason "행 ${row}"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 33 — box: 만료 토큰 파일이 시작 스크립트가 쓰는 꼴 밖이면(빔 · 끝
# 줄바꿈 없음 · 두 줄 · 제어 문자 · None · 앞 공백 · 뒤 공백 · C1 제어
# 문자(U+0085) · 4096바이트 초과 · 디렉터리 · 링크 · 디렉터리 링크 —
# read_token_secret 이 거부하는 것과 같다) clip-expiry 로 실패한다(종료 1). 꼭
# 4096바이트(값 4095 + 줄바꿈)는 받는다.
clip_cases::case_malformed() {
  local ok=0 variant token
  for variant in empty no-newline two-lines control none lead-space \
    trail-space c1 too-large token-dir token-symlink dir-symlink; do
    setup_box
    clip_cases::plant_token
    clip_cases::write_variant "${variant}" || return 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL clip-expiry' 1 \
      || ! expect_hygiene; then
      harness::add_reason "갈래 ${variant}"
      ok=1
    fi
  done
  setup_box
  token="$(head -c 4095 /dev/zero | tr '\0' 'k')"
  clip_cases::plant_token "${token}"
  plant "${ops_dir}/state/journal.t.pokeclip-media-sidecar" "${token}" 2
  run_count box || return 1
  if ! ops_fixture::expect_line 'SECRET COUNT box 2' 0; then
    harness::add_reason '4096바이트 만료 토큰 파일'
    ok=1
  fi
  return "${ok}"
}

# 사례 34 — ssm: 만료 토큰 파일은 읽지 않는다(알려진 한계) — 꼴 밖 파일이 있어도
# 실패하지 않고 SSM 기록만 센다.
clip_cases::case_ssm_ignores() {
  local ok=0
  setup_ssm_one
  mkdir -p -- "${ops_dir}/runtime/clip-expiry"
  printf 'None\n' >"$(clip_cases::token_file)"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT ssm 1' 0 || ok=1
  return "${ok}"
}
