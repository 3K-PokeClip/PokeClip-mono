# shellcheck shell=bash
# 전역(fixture_* · API_PASS_* 등)은 start_fixture.sh · start_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 사이드카 관측 자격 사례(사례 30 – 35)와 판정 도우미 — 시작
# 스크립트가 기동마다 만드는 MediaMTX 관리 API 비밀번호(16진 64자)가 두 렌더
# 파일(mediamtx.yml 의 사이드카 pass · 사이드카 비밀번호 파일 mtx_api_pass)에만
# 있고, 운영 템플릿 원본(옛 모드 블록)의 두 토큰이 각 한 번씩 채워지는지 본다.
# start_test.sh 가 harness.sh · start_fixture.sh · render.sh 를 먼저 source 한
# 뒤 이 파일을 source 한다. API_PASS_* · PROD_MTX_TEMPLATE · check_rendered 는
# start_test.sh 몫이다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

#######################################
# 가짜 chown 기록이 둘이고 순서대로 MediaMTX 렌더(10002) · 사이드카 비밀번호
# 파일(10001)의 임시 파일인지 본다.
# Globals:
#   fixture_dir
#######################################
api_pass::check_chown_records() {
  local first second dir="${fixture_dir}/runtime"
  first="$(paste -s -d ' ' - <"${fixture_dir}/log/chown.1.argv" 2>/dev/null)" \
    || first=""
  second="$(paste -s -d ' ' - <"${fixture_dir}/log/chown.2.argv" 2>/dev/null)" \
    || second=""
  if [[ "$(fixture::count_records chown)" != 2 \
    || "${first}" != "10002:10002 ${dir}/.mediamtx.yml."* \
    || "${second}" != "10001:10001 ${dir}/.${API_PASS_NAME}."* ]]; then
    harness::add_reason "chown 기록 「${first}」 · 「${second}」(기대 \
10002:10002 <렌더 임시 파일> · 10001:10001 <비밀번호 임시 파일>)"
    return 1
  fi
}

#######################################
# 사이드카 비밀번호 파일의 값(끝 줄바꿈 하나를 뗀 것)을 낸다.
# Globals:
#   fixture_dir
# Outputs:
#   값.
#######################################
api_pass::value() {
  local value
  value="$(cat -- "${fixture_dir}/runtime/${API_PASS_NAME}" 2>/dev/null \
    && printf x)" \
    || return 1
  value="${value%x}"
  printf '%s' "${value%$'\n'}"
}

#######################################
# 비밀번호 파일이 16진 64자 한 줄 · 0400 이고, 그 값이 RUNTIME_DIR 의 두 렌더
# 파일 밖(가짜 명령 기록 · 출력 · 그 밖 파일) 어디에도 없는지 본다.
# Globals:
#   fixture_dir
#######################################
api_pass::check_contained() {
  local ok=0 file="${fixture_dir}/runtime/${API_PASS_NAME}" value outside
  if ! value="$(api_pass::value)"; then
    harness::add_reason '비밀번호 파일 없음'
    return 1
  fi
  if [[ ! "${value}" =~ ${API_PASS_PATTERN} ]] \
    || [[ "$(cat -- "${file}" && printf x)" != "${value}"$'\n'x ]]; then
    harness::add_reason '비밀번호 파일이 16진 64자 한 줄(끝 줄바꿈 하나)이 아님'
    ok=1
  fi
  if [[ "$(fixture::perm "${file}")" != -r-------- ]]; then
    harness::add_reason "비밀번호 파일 권한 $(fixture::perm "${file}")(기대 \
-r--------)"
    ok=1
  fi
  outside="$(fixture::files_with "${value}" "${fixture_dir}" \
    | grep -v -F -x -e "${file}" -e "${fixture_dir}/runtime/mediamtx.yml" \
    | awk 'END { print NR }')"
  if (( outside != 0 )); then
    harness::add_reason "두 렌더 파일 밖에 비밀번호가 든 파일 ${outside}개"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 30 — 운영 템플릿 원본(prod 상자에 깔리는 저장소 파일 그대로)을 시작
# 스크립트의 템플릿으로 넘긴다(compose 렌더본 없음 = 옛 모드). 두 토큰이 각 한
# 곳씩 값으로 바뀌고(CDN 비밀은 큰따옴표 · API 비밀번호는 작은따옴표 안), 토큰이
# 남지 않는다.
#######################################
api_pass::case_prod_template_pipe() {
  local ok=0 tmpl="${PROD_MTX_TEMPLATE}" pass target
  if [[ ! -f "${PROD_MTX_TEMPLATE}" ]]; then
    harness::add_reason 'mediamtx.yml.tmpl 없음'
    return 1
  fi
  fixture::new_case
  fixture::base_env MEDIA_MTX_TEMPLATE
  fixture_env+=("MEDIA_MTX_TEMPLATE=${tmpl}")
  fixture::run start-media.sh || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}"
    return 1
  fi
  target="${fixture_dir}/runtime/mediamtx.yml"
  check_rendered "${target}" "${FIXTURE_FAKE_SECRET}" "${tmpl}" || ok=1
  pass="$(api_pass::value)" || pass=""
  if [[ "$(grep -c -F -x -- "hlsCDNSecret: \"${FIXTURE_FAKE_SECRET}\"" \
    "${target}")" != 1 ]]; then
    harness::add_reason '렌더에 hlsCDNSecret 큰따옴표 줄이 한 번이 아님'
    ok=1
  fi
  if [[ -z "${pass}" || "$(grep -c -E -x -- "[[:space:]]+pass: '${pass}'" \
    "${target}")" != 1 ]]; then
    harness::add_reason '렌더에 사이드카 pass 작은따옴표 줄이 한 번이 아님'
    ok=1
  fi
  api_pass::check_contained || ok=1
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 31 — 비밀번호는 실행마다 새로 만든다(두 실행의 값이 다르다). 다음
# 실행이 남은 파일을 그대로 쓰지 않는다.
#######################################
api_pass::case_fresh() {
  local first second
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh || return 1
  first="$(api_pass::value)" || first=""
  fixture::run start-media.sh || return 1
  second="$(api_pass::value)" || second=""
  if [[ ! "${first}" =~ ${API_PASS_PATTERN} \
    || ! "${second}" =~ ${API_PASS_PATTERN} || "${first}" == "${second}" ]]
  then
    harness::add_reason '두 실행의 비밀번호가 16진 64자 · 서로 다름이 아님'
    return 1
  fi
}

#######################################
# 사례 32 — 비밀번호를 만들지 못하면(od 실패 · 짧은 값 · 대문자 · 꼴이 맞는
# 값을 내고도 od 가 실패) 기동을 막고 RUNTIME_DIR 에 아무것도 남기지 않는다.
#######################################
api_pass::case_unavailable() {
  local ok=0 mode
  for mode in fail short upper okfail; do
    fixture::new_case
    ln -s -- "${TEST_DIR}/start_fakebin/od" "${fixture_dir}/bin/od"
    fixture::base_env
    fixture::run start-media.sh "PCBENCH_FAKE_OD_MODE=${mode}" || return 1
    fixture::expect_blocked "${API_BLOCKED_LINE}" || ok=1
    if [[ "$(fixture::runtime_files)" != 0 ]]; then
      harness::add_reason "${mode}: RUNTIME_DIR 에 파일이 남음"
      ok=1
    fi
    fixture::common_checks || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 33 — 사이드카 비밀번호 파일 단계가 실패하면(임시 파일 만들기 · 쓰기 ·
# chown 실패 · 대상이 디렉터리 · 소유 값이 숫자가 아님 · 둘째 rename 실패)
# 기동을 막고, MediaMTX 렌더도 남기지 않는다. 둘째 rename 이 실패하면 이미
# 옮긴 mediamtx.yml 을 지운다(짝이 어긋난 설정을 남기지 않는다).
#######################################
api_pass::case_install_failure() {
  local ok=0 kind want
  for kind in tmpfile write chown target owner rename; do
    fixture::new_case
    fixture::base_env
    case "${kind}" in
      tmpfile)
        want='MEDIA START FAIL render api-pass-tmpfile'
        api_pass::run_with_fs_failure mktemp "*/.${API_PASS_NAME}.XXXXXX" \
          || return 1
        ;;
      write)
        want='MEDIA START FAIL render api-pass-write'
        api_pass::run_with_fs_failure chmod "*/.${API_PASS_NAME}.*" \
          || return 1
        ;;
      rename)
        want='MEDIA START FAIL render api-pass-rename'
        api_pass::run_with_fs_failure mv "*/${API_PASS_NAME}" || return 1
        ;;
      chown)
        want='MEDIA START FAIL render api-pass-chown'
        fixture::run start-media.sh \
          "PCBENCH_FAKE_CHOWN_FAIL_NAME=${API_PASS_NAME}" || return 1
        ;;
      target)
        want='MEDIA START FAIL render api-pass-target'
        mkdir -p -- "${fixture_dir}/runtime/${API_PASS_NAME}"
        fixture::run start-media.sh || return 1
        ;;
      *)
        want='MEDIA START FAIL render owner'
        fixture_env+=('SIDECAR_UID=pcbench')
        fixture::run start-media.sh || return 1
        ;;
    esac
    fixture::expect_blocked "${want}" || ok=1
    if [[ "$(fixture::runtime_files)" != 0 ]]; then
      harness::add_reason "${kind}: RUNTIME_DIR 에 파일이 남음"
      ok=1
    fi
    fixture::common_checks || ok=1
  done
  return "${ok}"
}

#######################################
# 가짜 파일 명령(start_fakebin/fsfail)을 그 이름으로 PATH 앞에 두고, 마지막
# 인자가 glob 에 맞을 때만 실패하게 해 시작 스크립트를 돌린다.
# Globals:
#   fixture_dir, TEST_DIR
# Arguments:
#   명령 이름(mktemp · chmod · mv), 실패시킬 대상 glob.
#######################################
api_pass::run_with_fs_failure() {
  ln -s -- "${TEST_DIR}/start_fakebin/fsfail" "${fixture_dir}/bin/$1"
  fixture::run start-media.sh "PCBENCH_FAKE_FS_FAIL_GLOB=$2"
}

#######################################
# 사례 34 — API 비밀번호 토큰이 정확히 하나가 아니면(0 · 2) 렌더하지 않는다
# (CDN 토큰은 하나).
#######################################
api_pass::case_token_count() {
  local ok=0 body tmpl cdn="hlsCDNSecret: \"${FIXTURE_SECRET_TOKEN}\""
  for body in "${cdn}" "${cdn}"$'\n'"a: '${FIXTURE_API_PASS_TOKEN}'"$'\n'"b: \
'${FIXTURE_API_PASS_TOKEN}'"; do
    fixture::new_case
    tmpl="${fixture_dir}/bad.tmpl"
    fixture::marked_template "${body}" >"${tmpl}"
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

#######################################
# 사례 35 — 비밀번호를 담는 변수 이름이 부모 환경에서 export 된 채 들어와도
# 비밀번호가 가짜 명령 기록 · 출력에 나오지 않는다.
#######################################
api_pass::case_inherited_export() {
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh api_pass=inherited rendered=inherited \
    content=inherited || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}"
    return 1
  fi
  api_pass::check_contained
}
