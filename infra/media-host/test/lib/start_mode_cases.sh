# shellcheck shell=bash
# 전역(fixture_* · FIXTURE_* · AUTH_* · PROD_MTX_TEMPLATE 등)과 check_rendered
# 는 start_fixture.sh · start_ingest_auth_cases.sh · start_ingest_input_cases.sh
# · start_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 모드 블록 사례(사례 62 · 63) — 상자에는 저장소의 MediaMTX
# 템플릿이 렌더 없이 그대로 깔린다(M9 설치 묶음). 시작 스크립트는 compose
# 렌더본의 응답기 서비스 줄 수로 모드를 정하고, 템플릿에서 그 모드 블록만
# 남긴다.
#   - 62: 운영 템플릿 원본 그대로 + compose 응답기 줄 0 이면 옛 모드, 1 이면
#     응답기 모드로 기동한다. 렌더는 그 모드 블록의 토큰을 값으로 바꾼 것이다
#     (표지 줄 · 다른 모드 줄 0).
#   - 63: 표지 꼴이 깨지면(표지 없음 · Terraform 지시자 그대로 · 닫히지 않음 ·
#     짝 없는 end · 겹침 · 짝 다름 · 모르는 모드 · 들여쓴 표지 · 뒤 공백 · 한
#     모드 블록 없음) 응답기 외부 입력을 받기 전에 「MEDIA START FAIL render
#     template-mode」 로 막는다(렌더 · 파일 0).
#
# start_ingest_input_cases.sh 다음에 source 한다. 이 파일은 source 해서 쓴다
# (실행하지 않는다). bash 3.2 에서도 돈다.

readonly MODE_SHAPE_LINE='MEDIA START FAIL render template-mode'

#######################################
# 사례 62 의 옛 모드 행 — 운영 템플릿 원본 · compose 응답기 줄 0.
# Globals:
#   fixture_dir, fixture_env, fixture_status, PROD_MTX_TEMPLATE
#######################################
mode_cases::raw_legacy() {
  local ok=0 selected="${fixture_dir}.selected.tmpl"
  local target="${fixture_dir}/runtime/mediamtx.yml"
  auth_input::write_compose 0
  fixture::base_env MEDIA_MTX_TEMPLATE
  fixture_env+=("MEDIA_MTX_TEMPLATE=${PROD_MTX_TEMPLATE}")
  fixture::run start-media.sh || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "옛 모드 종료 코드 ${fixture_status}: \
$(harness::log_tail "${fixture_dir}/out")"
    return 1
  fi
  fixture::mode_body legacy "${PROD_MTX_TEMPLATE}" "${selected}"
  check_rendered "${target}" "${FIXTURE_FAKE_SECRET}" "${selected}" || ok=1
  if grep -q -E -e '@mode|%\{|\$\{|authMethod: http|Passphrase' -- "${target}"
  then
    harness::add_reason '옛 모드 렌더에 표지 · 지시자 · 응답기 모드 줄이 남음'
    ok=1
  fi
  if [[ -e "${fixture_dir}/runtime/${AUTH_DIR_NAME}" ]]; then
    harness::add_reason '옛 모드인데 응답기 디렉터리가 생김'
    ok=1
  fi
  api_pass::check_chown_records || ok=1
  api_pass::check_contained || ok=1
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 62 의 응답기 모드 행 — 운영 템플릿 원본 · compose 응답기 줄 1.
# Globals:
#   fixture_dir, fixture_status, PROD_MTX_TEMPLATE
#######################################
mode_cases::raw_ingest_auth() {
  local ok=0 selected="${fixture_dir}.selected.tmpl"
  local target="${fixture_dir}/runtime/mediamtx.yml"
  auth_input::write_compose 1
  auth_mode::run "${PROD_MTX_TEMPLATE}" || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "응답기 모드 종료 코드 ${fixture_status}: \
$(harness::log_tail "${fixture_dir}/out")"
    return 1
  fi
  auth_mode::read_values || return 1
  fixture::mode_body ingest-auth "${PROD_MTX_TEMPLATE}" "${selected}"
  auth_mode::check_render "${selected}" || ok=1
  if grep -q -E -e '@mode|%\{|\$\{|pokeclip-sidecar|user: any' -- "${target}"
  then
    harness::add_reason '응답기 모드 렌더에 표지 · 지시자 · 옛 모드 줄이 남음'
    ok=1
  fi
  auth_mode::check_files || ok=1
  auth_mode::check_contained || ok=1
  auth_mode::check_quiet || ok=1
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 62 — 운영 템플릿 원본(렌더하지 않은 저장소 파일)을 그대로 넘겨도 두 모드
# 모두 기동한다(M9 묶음 설치 경로 — 종결 r1 지적 1).
#######################################
mode_cases::case_raw_template() {
  local ok=0
  if [[ ! -f "${PROD_MTX_TEMPLATE}" ]]; then
    harness::add_reason 'mediamtx.yml.tmpl 없음'
    return 1
  fi
  fixture::new_case
  mode_cases::raw_legacy || ok=1
  fixture::new_case
  mode_cases::raw_ingest_auth || ok=1
  return "${ok}"
}

#######################################
# 표지 꼴 표 한 행의 템플릿 본문을 낸다. 토큰은 옛 모드 꼴(CDN · API 비밀번호)
# 이 옛 블록에, 응답기 모드 꼴(Basic · 잠금 둘)이 응답기 블록에 들어간다 —
# 표지만 맞으면 두 모드 다 기동하는 내용이다.
# Arguments:
#   행 이름.
# Outputs:
#   본문.
#######################################
mode_cases::shape_body() {
  local cdn="hlsCDNSecret: \"${FIXTURE_SECRET_TOKEN}\""
  local api="    pass: '${FIXTURE_API_PASS_TOKEN}'"
  local auth="${AUTH_BASIC_LINE_HEAD}${AUTH_BASIC_TOKEN}${AUTH_BASIC_LINE_TAIL}
  srtPublishPassphrase: '${AUTH_PUBLISH_LOCK_TOKEN}'
  srtReadPassphrase: '${AUTH_READ_LOCK_TOKEN}'"
  local lb='# @mode legacy begin' le='# @mode legacy end'
  local ab='# @mode ingest-auth begin' ae='# @mode ingest-auth end'
  printf '%s\n' "${cdn}"
  case "$1" in
    none) printf '%s\n' "${api}" ;;
    terraform)
      printf '%s\n' '%{ if ingest_auth_image != "" ~}' "${auth}" \
        '%{ else ~}' "${api}" '%{ endif ~}' ;;
    unclosed) printf '%s\n' "${lb}" "${api}" "${ab}" "${auth}" "${ae}" ;;
    stray-end) printf '%s\n' "${lb}" "${api}" "${le}" "${le}" "${ab}" \
      "${auth}" "${ae}" ;;
    nested) printf '%s\n' "${lb}" "${api}" "${ab}" "${auth}" "${ae}" "${le}" ;;
    crossed) printf '%s\n' "${lb}" "${api}" "${ae}" "${ab}" "${auth}" "${le}" ;;
    unknown) printf '%s\n' "${lb}" "${api}" "${le}" '# @mode other begin' \
      "${auth}" '# @mode other end' ;;
    # 두 모드 블록은 온전하고 꼴 하나만 깨진 행 — 끝 검사의 닫힘 절반 · begin
    # 의 모드 이름 대조를 각각 따로 본다(위 unclosed 는 겹침에, unknown 은
    # 응답기 블록 없음에 먼저 걸린다).
    unclosed-eof) printf '%s\n' "${lb}" "${api}" "${le}" "${ab}" "${auth}" ;;
    unknown-extra) printf '%s\n' "${lb}" "${api}" "${le}" "${ab}" "${auth}" \
      "${ae}" '# @mode other begin' '# 모르는 모드' '# @mode other end' ;;
    indented) printf '%s\n' "  ${lb}" "${api}" "  ${le}" "${ab}" "${auth}" \
      "${ae}" ;;
    trailing) printf '%s\n' "${lb} " "${api}" "${le}" "${ab}" "${auth}" \
      "${ae}" ;;
    legacy-only) printf '%s\n' "${lb}" "${api}" "${le}" ;;
    auth-only) printf '%s\n' "${ab}" "${auth}" "${ae}" ;;
  esac
  printf 'paths:\n  all_others:\n'
}

#######################################
# 사례 63 — 표지 꼴 표: 꼴이 깨진 템플릿은 compose 응답기 줄 0 · 1 어느 쪽이든
# 「template-mode」 로 막는다. 막히는 자리는 CDN 비밀 조회 뒤 · 응답기 외부
# 입력(4C 토큰 · 거부 목록) 앞이다(aws 1회 · 거부 목록 디렉터리 없음).
#######################################
mode_cases::case_shape_table() {
  local ok=0 row n tmpl
  for row in none terraform unclosed stray-end nested crossed unknown \
    unclosed-eof unknown-extra indented trailing legacy-only auth-only; do
    for n in 0 1; do
      fixture::new_case
      tmpl="${fixture_dir}.shape.tmpl"
      mode_cases::shape_body "${row}" >"${tmpl}"
      auth_input::write_compose "${n}"
      auth_mode::run "${tmpl}" || return 1
      auth_input::expect_blocked "${row} ${n}" "${MODE_SHAPE_LINE}" 1 || ok=1
      if [[ -e "${fixture_dir}/deny" ]]; then
        harness::add_reason "${row} ${n}: 거부 목록 디렉터리가 생김"
        ok=1
      fi
    done
  done
  return "${ok}"
}
