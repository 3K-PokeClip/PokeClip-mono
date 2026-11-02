# shellcheck shell=bash
# 전역(fixture_* · FIXTURE_* · API_PASS_* · PROD_MTX_TEMPLATE 등)은
# start_fixture.sh · start_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 응답기 모드 사례(사례 42 – 50)와 판정 도우미 — 시작 스크립트가
# compose 렌더본의 응답기 서비스 줄로 모드를 정하고 템플릿에서 그 모드 블록을
# 고른 뒤 토큰 집합이 그 모드 꼴인지 보며, 응답기 모드에서 생성 셋(Basic
# 비밀번호 · SRT 송출 잠금 · 읽기 잠금)을 만들어 채우고, API 비밀번호를 사이드카
# 파일과 응답기 파일(RUNTIME_DIR/ingest-auth/) 두 곳에 두는지 본다. 정지
# 스크립트가 응답기 파일을 지우는지도 본다.
#
# 응답기 모드 템플릿은 운영 템플릿 원본(상자에 렌더 없이 깔리는 저장소 파일)
# 이다(시험 전용 고정 템플릿을 따로 두지 않는다 — 운영 템플릿의 토큰 자리 · @@@
# 경계를 그대로 흘린다). 모드 판정 표 · 경계 사례만 사례 안에서 짧은 본문을
# 만든다.
#
# start_test.sh 가 harness.sh · start_fixture.sh · render.sh 를 먼저 source 한
# 뒤 이 파일을 source 한다. 이 파일은 source 해서 쓴다(실행하지 않는다). bash
# 3.2 에서도 돈다.

readonly AUTH_BASIC_TOKEN='@@INGEST_AUTH_BASIC_PASS@@'
readonly AUTH_PUBLISH_LOCK_TOKEN='@@SRT_PUBLISH_LOCK@@'
readonly AUTH_READ_LOCK_TOKEN='@@SRT_READ_LOCK@@'
# 응답기 모드 파일 자리(RUNTIME_DIR 아래) · 이름 · 소유.
readonly AUTH_DIR_NAME='ingest-auth'
readonly AUTH_BASIC_NAME='ingest_basic_pass'
readonly AUTH_OWNER='10003:10003'
readonly AUTH_GENERATED_LINE=\
'MEDIA START BLOCKED: generated credential unavailable'
# 짧은 본문의 Basic 줄 꼴 — 운영 템플릿처럼 토큰 바로 뒤에 @ 가 붙는다.
readonly AUTH_BASIC_LINE_HEAD="basic: 'http://u:"
readonly AUTH_BASIC_LINE_TAIL="@127.0.0.1:8080/a'"

# 응답기 모드 사례의 템플릿 — 운영 템플릿 원본(auth_mode::prod_template 이
# 채운다).
auth_mode_tmpl=""
# 마지막 실행의 비밀 값(auth_mode::read_values 가 채운다).
auth_basic=""
auth_publish=""
auth_read=""
auth_api=""

#######################################
# 운영 템플릿 원본을 auth_mode_tmpl 에 둔다(이미 정했으면 그대로). 그 응답기
# 모드 블록을 고른 내용(fixture::mode_body)의 토큰 집합이 응답기 모드 꼴(CDN ·
# Basic · 잠금 둘 각 1 · API 비밀번호 0)인지도 본다 — 아니면 이 사례들이 응답기
# 모드를 시험하지 못한다.
# Globals:
#   auth_mode_tmpl, fixture_root, PROD_MTX_TEMPLATE
#######################################
auth_mode::prod_template() {
  local tmpl="${fixture_root}/prod.auth.tmpl" token want got
  if [[ -n "${auth_mode_tmpl}" ]]; then
    return 0
  fi
  if [[ ! -f "${PROD_MTX_TEMPLATE}" ]]; then
    harness::add_reason 'mediamtx.yml.tmpl 없음'
    return 1
  fi
  fixture::mode_body ingest-auth "${PROD_MTX_TEMPLATE}" "${tmpl}"
  for token in "${FIXTURE_SECRET_TOKEN}=1" "${FIXTURE_API_PASS_TOKEN}=0" \
    "${AUTH_BASIC_TOKEN}=1" "${AUTH_PUBLISH_LOCK_TOKEN}=1" \
    "${AUTH_READ_LOCK_TOKEN}=1"; do
    want="${token##*=}"
    got="$(grep -o -F -- "${token%=*}" "${tmpl}" | awk 'END { print NR }')"
    if [[ "${got}" != "${want}" ]]; then
      harness::add_reason "운영 템플릿 응답기 모드 블록의 ${token%=*} ${got}개\
(기대 ${want})"
      return 1
    fi
  done
  auth_mode_tmpl="${PROD_MTX_TEMPLATE}"
}

#######################################
# 주어진 템플릿으로 시작 스크립트를 돌린다(사례 디렉터리는 부르는 쪽이 만든다).
# Globals:
#   fixture_env
# Arguments:
#   템플릿 경로, 가짜 명령 설정(NAME=값 …).
#######################################
auth_mode::run() {
  local tmpl="$1"
  shift
  fixture::base_env MEDIA_MTX_TEMPLATE
  fixture_env+=("MEDIA_MTX_TEMPLATE=${tmpl}")
  auth_input::add_env
  fixture::run start-media.sh "$@"
}

#######################################
# 토큰마다 정한 수만큼 줄을 둔 짧은 템플릿 본문을 파일에 쓴다 — 토큰 줄은 모두
# 한 모드의 블록 안이고, 다른 모드 블록은 표지 둘뿐이다. Basic 줄은 운영
# 템플릿처럼 토큰 바로 뒤에 @ 가 붙는다.
# Arguments:
#   출력 파일, 블록 모드(legacy | ingest-auth), CDN · API · Basic · 송출 잠금 ·
#   읽기 잠금 토큰 수.
#######################################
auth_mode::write_body() {
  local out="$1" mode="$2" other=legacy i
  [[ "${mode}" != legacy ]] || other=ingest-auth
  shift 2
  {
    printf '# @mode %s begin\n' "${mode}"
    for (( i = 0; i < $1; i++ )); do
      printf 'hlsCDNSecret: "%s"\n' "${FIXTURE_SECRET_TOKEN}"
    done
    for (( i = 0; i < $2; i++ )); do
      printf "    pass: '%s'\n" "${FIXTURE_API_PASS_TOKEN}"
    done
    for (( i = 0; i < $3; i++ )); do
      printf '%s%s%s\n' "${AUTH_BASIC_LINE_HEAD}" "${AUTH_BASIC_TOKEN}" \
        "${AUTH_BASIC_LINE_TAIL}"
    done
    for (( i = 0; i < $4; i++ )); do
      printf "  srtPublishPassphrase: '%s'\n" "${AUTH_PUBLISH_LOCK_TOKEN}"
    done
    for (( i = 0; i < $5; i++ )); do
      printf "  srtReadPassphrase: '%s'\n" "${AUTH_READ_LOCK_TOKEN}"
    done
    printf '# @mode %s end\n# @mode %s begin\n# @mode %s end\n' "${mode}" \
      "${other}" "${other}"
    printf 'paths:\n  all_others:\n'
  } >"${out}"
}

#######################################
# 파일 하나의 값(끝 줄바꿈 하나를 뗀 것)을 낸다. 「16진 64자 한 줄」 꼴이
# 아니면 실패다.
# Arguments:
#   파일.
# Outputs:
#   값.
#######################################
auth_mode::line_value() {
  local value
  value="$(cat -- "$1" 2>/dev/null && printf x)" || return 1
  [[ "${value}" == *$'\n'x ]] || return 1
  value="${value%$'\n'x}"
  [[ "${value}" =~ ${API_PASS_PATTERN} ]] || return 1
  printf '%s' "${value}"
}

#######################################
# 렌더 파일의 키 줄(작은따옴표 값)에서 값을 낸다. 줄이 정확히 하나가 아니면
# 실패다.
# Arguments:
#   렌더 파일, 키 이름.
#######################################
auth_mode::quoted_value() {
  local lines
  lines="$(sed -n "s/^ *$2: '\\([0-9a-f]\\{64\\}\\)'\$/\\1/p" "$1")" || return 1
  [[ -n "${lines}" && "${lines}" != *$'\n'* ]] || return 1
  printf '%s' "${lines}"
}

#######################################
# 응답기 모드 실행 뒤의 비밀 값 넷을 읽는다(Basic · 응답기 API 비밀번호는 파일,
# 잠금 둘은 렌더 줄). 하나라도 못 읽으면 실패다.
# Globals:
#   fixture_dir, auth_basic, auth_publish, auth_read, auth_api
#######################################
auth_mode::read_values() {
  local dir="${fixture_dir}/runtime" ok=0
  auth_basic="$(auth_mode::line_value \
    "${dir}/${AUTH_DIR_NAME}/${AUTH_BASIC_NAME}")" || ok=1
  auth_api="$(auth_mode::line_value \
    "${dir}/${AUTH_DIR_NAME}/${API_PASS_NAME}")" || ok=1
  auth_publish="$(auth_mode::quoted_value "${dir}/mediamtx.yml" \
    srtPublishPassphrase)" || ok=1
  auth_read="$(auth_mode::quoted_value "${dir}/mediamtx.yml" \
    srtReadPassphrase)" || ok=1
  if (( ok )); then
    harness::add_reason '응답기 파일 · 잠금 줄을 16진 64자로 읽지 못함'
  fi
  return "${ok}"
}

#######################################
# 렌더 파일이 템플릿의 응답기 모드 블록을 고른 내용(fixture::mode_body)에서
# 토큰 넷 자리를 각 값으로 바꾼 것과 같은지(그 밖의 바이트 불변), @@ 가 남지
# 않았는지, 0400 인지 본다. API 비밀번호 자리는 응답기 모드 블록에 없다.
# Globals:
#   fixture_dir, auth_basic, auth_publish, auth_read
# Arguments:
#   템플릿.
#######################################
auth_mode::check_render() {
  local ok=0 target="${fixture_dir}/runtime/mediamtx.yml"
  local expected="${fixture_dir}.auth.expected.yml"
  local selected="${fixture_dir}.auth.selected.tmpl"
  fixture::mode_body ingest-auth "$1" "${selected}"
  PCBENCH_T1="${FIXTURE_SECRET_TOKEN}" PCBENCH_V1="${FIXTURE_FAKE_SECRET}" \
    PCBENCH_T2="${AUTH_BASIC_TOKEN}" PCBENCH_V2="${auth_basic}" \
    PCBENCH_T3="${AUTH_PUBLISH_LOCK_TOKEN}" PCBENCH_V3="${auth_publish}" \
    PCBENCH_T4="${AUTH_READ_LOCK_TOKEN}" PCBENCH_V4="${auth_read}" awk '
    function put(t, v,   i) {
      i = index($0, t)
      if (i > 0) $0 = substr($0, 1, i - 1) v substr($0, i + length(t))
    }
    { for (n = 1; n <= 4; n++)
        put(ENVIRON["PCBENCH_T" n], ENVIRON["PCBENCH_V" n])
      print }' "${selected}" >"${expected}"
  if ! cmp -s -- "${expected}" "${target}"; then
    harness::add_reason '렌더 내용이 「토큰 넷을 각 값으로」 와 다름'
    ok=1
  fi
  if grep -q -F -- '@@' "${target}"; then
    harness::add_reason '렌더에 @@ 가 남음'
    ok=1
  fi
  if [[ "$(fixture::perm "${target}")" != -r-------- ]]; then
    harness::add_reason "렌더 권한 $(fixture::perm "${target}")"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 응답기 모드 파일의 자리 · 권한 · 짝을 본다 — 디렉터리 0711, 파일 둘 0400,
# 사이드카 · 응답기 API 비밀번호 파일이 같은 값, 생성 셋 셋과 API 비밀번호가 서로
# 다름.
# Globals:
#   fixture_dir, auth_basic, auth_publish, auth_read, auth_api
#######################################
auth_mode::check_files() {
  local ok=0 dir="${fixture_dir}/runtime/${AUTH_DIR_NAME}" f sidecar
  if [[ "$(fixture::perm "${dir}")" != drwx--x--x ]]; then
    harness::add_reason "응답기 디렉터리 권한 $(fixture::perm "${dir}" \
      2>/dev/null)(기대 drwx--x--x)"
    ok=1
  fi
  for f in "${dir}/${AUTH_BASIC_NAME}" "${dir}/${API_PASS_NAME}"; do
    if [[ "$(fixture::perm "${f}" 2>/dev/null)" != -r-------- ]]; then
      harness::add_reason "${f##*/} 권한 $(fixture::perm "${f}" \
        2>/dev/null)(기대 -r--------)"
      ok=1
    fi
  done
  sidecar="$(api_pass::value)" || sidecar=""
  if [[ -z "${auth_api}" || "${sidecar}" != "${auth_api}" ]]; then
    harness::add_reason '사이드카 · 응답기 API 비밀번호 파일의 값이 다름'
    ok=1
  fi
  if [[ "${auth_publish}" == "${auth_read}" \
    || "${auth_basic}" == "${auth_publish}" \
    || "${auth_basic}" == "${auth_read}" \
    || "${auth_api}" == "${auth_basic}" \
    || "${auth_api}" == "${auth_publish}" || "${auth_api}" == "${auth_read}" ]]
  then
    harness::add_reason 'Basic · 잠금 둘 · API 비밀번호가 서로 다르지 않음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 값 하나가 사례 디렉터리에서 허용한 파일에만 있는지 본다(가짜 명령 argv · 환경
# 기록 · 스크립트 출력 포함 그 밖 0).
# Globals:
#   fixture_dir
# Arguments:
#   값, 이름, 허용 파일 ….
#######################################
auth_mode::check_only_in() {
  local value="$1" label="$2" allow=() f outside
  shift 2
  for f in "$@"; do
    allow+=(-e "${f}")
  done
  outside="$(fixture::files_with "${value}" "${fixture_dir}" \
    | grep -v -F -x ${allow[@]+"${allow[@]}"} | awk 'END { print NR }')"
  if (( outside != 0 )); then
    harness::add_reason "${label} 이 허용 파일 밖 ${outside}곳에 있음"
    return 1
  fi
}

#######################################
# 응답기 모드 비밀 여섯(CDN · Basic · 잠금 둘 · API 비밀번호 · 4C 토큰)이 정한
# 파일에만 있는지 본다 — API 비밀번호는 mediamtx.yml 에 없고, 4C 토큰은 응답기
# 파일과 사이드카 사본(RUNTIME_DIR 바로 아래) 둘에만 있다.
# Globals:
#   fixture_dir, auth_basic, auth_publish, auth_read, auth_api
#######################################
auth_mode::check_contained() {
  local ok=0 run="${fixture_dir}/runtime"
  local mtx="${run}/mediamtx.yml" ia="${run}/${AUTH_DIR_NAME}"
  auth_mode::check_only_in "${FIXTURE_FAKE_SECRET}" 'CDN 비밀' "${mtx}" || ok=1
  auth_mode::check_only_in "${auth_basic}" 'Basic 비밀번호' "${mtx}" \
    "${ia}/${AUTH_BASIC_NAME}" || ok=1
  auth_mode::check_only_in "${auth_publish}" '송출 잠금' "${mtx}" || ok=1
  auth_mode::check_only_in "${auth_read}" '읽기 잠금' "${mtx}" || ok=1
  auth_mode::check_only_in "${auth_api}" 'API 비밀번호' "${run}/${API_PASS_NAME}" \
    "${ia}/${API_PASS_NAME}" || ok=1
  auth_mode::check_only_in "${AUTH_RESOLVE_TOKEN}" '4C 토큰' \
    "${ia}/${AUTH_RESOLVE_NAME}" "${run}/${AUTH_RESOLVE_NAME}" || ok=1
  return "${ok}"
}

#######################################
# chown 기록 n 이 기대와 같은지 본다(끝이 * 이면 그 앞까지 앞머리 대조).
# Globals:
#   fixture_dir
# Arguments:
#   기록 번호, 기대(소유 경로).
#######################################
auth_mode::chown_is() {
  local got want="$2"
  got="$(paste -s -d ' ' - <"${fixture_dir}/log/chown.$1.argv" 2>/dev/null)" \
    || got=""
  if [[ "${want}" == *'*' ]]; then
    [[ "${got}" == "${want%'*'}"* ]] && return 0
  elif [[ "${got}" == "${want}" ]]; then
    return 0
  fi
  harness::add_reason "chown ${1} 「${got}」(기대 「${want}」)"
  return 1
}

#######################################
# 응답기 모드 chown 기록 — 거부 목록 디렉터리 · 그 임시 파일(root:응답기) 뒤
# 응답기 디렉터리(root) · 상태 디렉터리(응답기 — 디렉터리는 -h), 그 뒤 임시
# 파일 여섯이 차례로 MediaMTX · 사이드카 · 응답기 셋 · 사이드카(4C 토큰 사본)
# 소유이고, 그 순간 0400 · 최종 경로 없음.
# Globals:
#   fixture_dir
#######################################
auth_mode::check_chown_records() {
  local ok=0 run="${fixture_dir}/runtime" n deny="${fixture_dir}/deny"
  local ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}"
  if [[ "$(fixture::count_records chown)" != 10 ]]; then
    harness::add_reason "chown $(fixture::count_records chown)회(기대 10)"
    ok=1
  fi
  auth_mode::chown_is 1 "-h 0:10003 ${deny}" || ok=1
  auth_mode::chown_is 2 "0:10003 ${deny}/.${AUTH_DENY_NAME}.*" || ok=1
  auth_mode::chown_is 3 "-h 0:0 ${ia}" || ok=1
  auth_mode::chown_is 4 "-h ${AUTH_OWNER} ${ia}/state" || ok=1
  auth_mode::chown_is 5 "10002:10002 ${run}/.mediamtx.yml.*" || ok=1
  auth_mode::chown_is 6 "10001:10001 ${run}/.${API_PASS_NAME}.*" || ok=1
  auth_mode::chown_is 7 "${AUTH_OWNER} ${ia}/.${AUTH_BASIC_NAME}.*" || ok=1
  auth_mode::chown_is 8 "${AUTH_OWNER} ${ia}/.${API_PASS_NAME}.*" || ok=1
  auth_mode::chown_is 9 "${AUTH_OWNER} ${ia}/.${AUTH_RESOLVE_NAME}.*" || ok=1
  auth_mode::chown_is 10 "10001:10001 ${run}/.${AUTH_RESOLVE_NAME}.*" || ok=1
  for n in 5 6 7 8 9 10; do
    if [[ "$(cat -- "${fixture_dir}/log/chown.${n}.state" 2>/dev/null)" \
      != 'perm=-r-------- final=absent core=0' ]]; then
      harness::add_reason "chown ${n} 순간 상태 「$(cat -- \
"${fixture_dir}/log/chown.${n}.state" 2>/dev/null)」"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 출력이 선행 점검 OK 세 줄뿐인지 본다(모드 판정 줄을 새로 내지 않는다).
# Globals:
#   fixture_dir
#######################################
auth_mode::check_quiet() {
  local extra
  extra="$(grep -v -c -E -- '^MEDIA PREREQ OK ' "${fixture_dir}/out")" \
    || extra=0
  if [[ "${extra}" != 0 ]] \
    || [[ "$(grep -c -E -- '^MEDIA PREREQ OK ' "${fixture_dir}/out")" != 3 ]]
  then
    harness::add_reason "출력이 선행 점검 OK 세 줄뿐이 아님: $(harness::log_tail \
"${fixture_dir}/out")"
    return 1
  fi
}

#######################################
# 사례 42 — 응답기 모드 성공(운영 템플릿 응답기 갈래): up 의 종료 코드 그대로 ·
# 렌더 = 토큰 넷을 각 값으로 · @@ 0 · 응답기 파일 자리 · 소유 · 모드 · API
# 비밀번호 두 파일 같은 값(mediamtx.yml 에는 없음) · 잠금 둘 서로 다름 · 비밀
# 값이 argv · 환경 · 출력에 0 · 판정 줄을 새로 내지 않음.
#######################################
auth_mode::case_success() {
  local ok=0
  auth_mode::prod_template || return 1
  fixture::new_case
  auth_mode::run "${auth_mode_tmpl}" PCBENCH_FAKE_UP_STATUS=7 || return 1
  if (( fixture_status != 7 )); then
    harness::add_reason "종료 코드 ${fixture_status}(기대 up 의 7): \
$(harness::log_tail "${fixture_dir}/out")"
    return 1
  fi
  auth_mode::read_values || return 1
  auth_mode::check_render "${auth_mode_tmpl}" || ok=1
  if [[ "$(grep -c -F -x -- "authHTTPAddress: 'http://pokeclip-mediamtx:\
${auth_basic}@127.0.0.1:8080/mediamtx/auth'" \
    "${fixture_dir}/runtime/mediamtx.yml")" != 1 ]]; then
    harness::add_reason 'authHTTPAddress 줄이 「Basic 값 바로 뒤 @」 꼴로 한 번이 아님'
    ok=1
  fi
  auth_mode::check_files || ok=1
  auth_mode::check_chown_records || ok=1
  auth_mode::check_contained || ok=1
  auth_mode::check_quiet || ok=1
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 모드 판정 표 한 행을 돌린다. 토큰 줄은 compose 가 고르는 모드의 블록 안에
# 둔다.
# Arguments:
#   기대(legacy | auth | token), compose 응답기 줄 수(0 | 1), 토큰 수
#   다섯(CDN · API · Basic · 송출 · 읽기).
#######################################
auth_mode::table_row() {
  local want="$1" tmpl shape="$2: $3 $4 $5 $6 $7" ok=0 mode=ingest-auth
  [[ "$2" != 0 ]] || mode=legacy
  fixture::new_case
  tmpl="${fixture_dir}.body.tmpl"
  auth_mode::write_body "${tmpl}" "${mode}" "$3" "$4" "$5" "$6" "$7"
  auth_input::write_compose "$2"
  auth_mode::run "${tmpl}" || return 1
  case "${want}" in
    token)
      fixture::expect_blocked 'MEDIA START FAIL render template-token' || ok=1
      if [[ "$(fixture::runtime_files)" != 0 \
        || -e "${fixture_dir}/runtime/${AUTH_DIR_NAME}" ]]; then
        harness::add_reason "${shape}: RUNTIME_DIR 에 무엇이 남음"
        ok=1
      fi
      ;;
    legacy)
      auth_mode::expect_legacy || ok=1
      ;;
    *)
      auth_mode::expect_auth_body || ok=1
      ;;
  esac
  fixture::common_checks || ok=1
  (( ok == 0 )) || harness::add_reason "행 「${shape}」 기대 ${want}"
  return "${ok}"
}

#######################################
# 옛 모드 행 판정 — 성공 · 응답기 디렉터리 없음 · chown 둘 · 판정 줄 그대로.
# Globals:
#   fixture_dir, fixture_status
#######################################
auth_mode::expect_legacy() {
  local ok=0
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}"
    return 1
  fi
  if [[ -e "${fixture_dir}/runtime/${AUTH_DIR_NAME}" ]]; then
    harness::add_reason '옛 모드인데 응답기 디렉터리가 생김'
    ok=1
  fi
  api_pass::check_chown_records || ok=1
  api_pass::check_contained || ok=1
  auth_mode::check_quiet || ok=1
  return "${ok}"
}

#######################################
# 응답기 모드 짧은 본문 행 판정 — 성공 · 렌더 정확 · Basic 줄 꼴 · 파일 자리.
# Globals:
#   fixture_dir, fixture_status, auth_basic
#######################################
auth_mode::expect_auth_body() {
  local ok=0
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}: $(harness::log_tail \
"${fixture_dir}/out")"
    return 1
  fi
  auth_mode::read_values || return 1
  auth_mode::check_render "${fixture_dir}.body.tmpl" || ok=1
  if [[ "$(grep -c -F -x -- "${AUTH_BASIC_LINE_HEAD}${auth_basic}\
${AUTH_BASIC_LINE_TAIL}" "${fixture_dir}/runtime/mediamtx.yml")" != 1 ]]; then
    harness::add_reason 'Basic 줄이 「값 바로 뒤 @」 꼴이 아님'
    ok=1
  fi
  auth_mode::check_files || ok=1
  auth_mode::check_contained || ok=1
  auth_mode::check_quiet || ok=1
  return "${ok}"
}

#######################################
# 사례 43 — 모드 판정 표: compose 가 고른 모드 블록의 토큰 집합이 그 모드 꼴일
# 때만 받는다. 부분 집합 · 섞임 · CDN 없음 · 토큰 두 번 · 다른 모드 꼴(옛
# 모드에 응답기 꼴 · 응답기 모드에 옛 꼴)은 template-token 으로 막는다(렌더 ·
# 파일 0).
#######################################
auth_mode::case_mode_table() {
  local ok=0 row
  for row in 'legacy 0 1 1 0 0 0' 'auth 1 1 0 1 1 1' \
    'token 1 1 0 1 1 0' 'token 1 1 0 1 0 1' 'token 1 1 0 0 1 1' \
    'token 1 1 1 1 1 1' 'token 1 1 1 0 0 1' 'token 1 1 1 1 0 0' \
    'token 1 0 0 1 1 1' 'token 1 1 0 2 1 1' 'token 1 1 0 1 1 2' \
    'token 1 2 0 1 1 1' 'token 1 1 0 0 0 0' 'token 1 0 0 0 0 0' \
    'token 1 1 1 0 0 0' 'token 0 1 0 1 1 1' 'token 0 1 1 1 1 1' \
    'token 0 1 2 0 0 0' 'token 0 0 1 0 0 0'; do
    # shellcheck disable=SC2086 # 행을 낱말로 나눈다.
    auth_mode::table_row ${row} || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 44 — @@@ 경계: 토큰 앞뒤에 @ 가 붙어도(@@@토큰@@@) 토큰 하나로 읽고 그
# 자리만 값으로 바꾼다 — 바깥 @ 는 그대로 남는다.
#######################################
auth_mode::case_at_boundary() {
  local ok=0 tmpl target
  fixture::new_case
  tmpl="${fixture_dir}.body.tmpl"
  auth_mode::write_body "${tmpl}" ingest-auth 1 0 0 1 1
  printf "edge: '@%s@'\n" "${AUTH_BASIC_TOKEN}" >>"${tmpl}"
  auth_mode::run "${tmpl}" || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}: $(harness::log_tail \
"${fixture_dir}/out")"
    return 1
  fi
  auth_mode::read_values || return 1
  target="${fixture_dir}/runtime/mediamtx.yml"
  auth_mode::check_render "${tmpl}" || ok=1
  if [[ "$(grep -c -F -x -- "edge: '@${auth_basic}@'" "${target}")" != 1 ]]
  then
    harness::add_reason "경계 줄이 「'@값@'」 이 아님"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 45 — 응답기 모드에서 생성 셋 가운데 하나라도 만들지 못하면(둘째 · 셋째 ·
# 넷째 생성 실패 · 꼴 밖 값) 「generated credential unavailable」 로 막고 아무
# 파일도 남기지 않는다. 옛 모드는 생성이 하나뿐이라 둘째 생성 실패에 영향이 없다.
#######################################
auth_mode::case_generation_failure() {
  local ok=0 row calls mode
  auth_mode::prod_template || return 1
  for row in '1 fail' '2 fail' '3 fail' '1 short' '3 upper'; do
    calls="${row% *}"
    mode="${row#* }"
    fixture::new_case
    ln -s -- "${TEST_DIR}/start_fakebin/od" "${fixture_dir}/bin/od"
    auth_mode::run "${auth_mode_tmpl}" "PCBENCH_FAKE_OD_PASS_CALLS=${calls}" \
      "PCBENCH_FAKE_OD_MODE=${mode}" || return 1
    fixture::expect_blocked "${AUTH_GENERATED_LINE}" || ok=1
    if [[ -e "${fixture_dir}/runtime" ]]; then
      harness::add_reason "${row}: RUNTIME_DIR 이 생김(생성 전에 막아야 함)"
      ok=1
    fi
    fixture::common_checks || ok=1
  done
  fixture::new_case
  ln -s -- "${TEST_DIR}/start_fakebin/od" "${fixture_dir}/bin/od"
  fixture::base_env
  fixture::run start-media.sh PCBENCH_FAKE_OD_PASS_CALLS=1 || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "옛 모드가 둘째 생성 실패에 막힘(종료 ${fixture_status})"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 설치 실패 행 하나를 준비하고 돌린다.
# Globals:
#   fixture_dir, fixture_env, auth_mode_tmpl, TEST_DIR
# Arguments:
#   행 이름.
#######################################
auth_mode::install_failure_run() {
  local ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}"
  case "$1" in
    dir-link)
      mkdir -p -- "${fixture_dir}/runtime" "${fixture_dir}/elsewhere"
      ln -s -- "${fixture_dir}/elsewhere" "${ia}"
      auth_mode::run "${auth_mode_tmpl}"
      ;;
    dir-chown)
      auth_mode::run "${auth_mode_tmpl}" \
        "PCBENCH_FAKE_CHOWN_FAIL_NAME=${AUTH_DIR_NAME}"
      ;;
    basic-tmpfile)
      ln -s -- "${TEST_DIR}/start_fakebin/fsfail" "${fixture_dir}/bin/mktemp"
      auth_mode::run "${auth_mode_tmpl}" \
        "PCBENCH_FAKE_FS_FAIL_GLOB=*/${AUTH_DIR_NAME}/.${AUTH_BASIC_NAME}.\
XXXXXX"
      ;;
    basic-chown)
      auth_mode::run "${auth_mode_tmpl}" \
        "PCBENCH_FAKE_CHOWN_FAIL_NAME=${AUTH_BASIC_NAME}"
      ;;
    api-target)
      mkdir -p -- "${ia}/${API_PASS_NAME}"
      auth_mode::run "${auth_mode_tmpl}"
      ;;
    api-rename)
      ln -s -- "${TEST_DIR}/start_fakebin/fsfail" "${fixture_dir}/bin/mv"
      auth_mode::run "${auth_mode_tmpl}" \
        "PCBENCH_FAKE_FS_FAIL_GLOB=*/${AUTH_DIR_NAME}/${API_PASS_NAME}"
      ;;
    resolve-target)
      mkdir -p -- "${fixture_dir}/runtime/${AUTH_RESOLVE_NAME}"
      auth_mode::run "${auth_mode_tmpl}"
      ;;
    resolve-rename)
      ln -s -- "${TEST_DIR}/start_fakebin/fsfail" "${fixture_dir}/bin/mv"
      auth_mode::run "${auth_mode_tmpl}" \
        "PCBENCH_FAKE_FS_FAIL_GLOB=*/runtime/${AUTH_RESOLVE_NAME}"
      ;;
    *) auth_mode::owner_run ;;
  esac
}

#######################################
# 응답기 소유 값이 숫자가 아닌 실행(owner 행) — base_env 에 템플릿과 소유 값을
# 함께 넣어 돌린다.
# Globals:
#   fixture_env, auth_mode_tmpl
#######################################
auth_mode::owner_run() {
  fixture::base_env MEDIA_MTX_TEMPLATE
  fixture_env+=("MEDIA_MTX_TEMPLATE=${auth_mode_tmpl}"
    'INGEST_AUTH_UID=pcbench')
  auth_input::add_env
  fixture::run start-media.sh
}

#######################################
# 사례 46 — 응답기 모드 설치 단계가 실패하면(디렉터리가 링크 · 디렉터리 chown ·
# Basic 임시 파일 · Basic chown · 응답기 API 비밀번호 대상이 디렉터리 · 그
# rename · 사이드카 4C 토큰 사본 대상이 디렉터리 · 그 rename · 소유 값) 기동을
# 막고 RUNTIME_DIR 에 파일을 남기지 않는다 — 대상 점검은 모든 rename 앞이고,
# 응답기 API 비밀번호 rename(여섯 가운데 넷째)이 실패하면 이번에 옮긴 셋을,
# 사이드카 4C 토큰 사본 rename(마지막)이 실패하면 다섯을 지운다.
#######################################
auth_mode::case_install_failure() {
  local ok=0 row want
  auth_mode::prod_template || return 1
  for row in 'dir-link ingest-auth-dir' 'dir-chown ingest-auth-dir' \
    'basic-tmpfile ingest-auth-basic-pass-tmpfile' \
    'basic-chown ingest-auth-basic-pass-chown' \
    'api-target ingest-auth-api-pass-target' \
    'api-rename ingest-auth-api-pass-rename' \
    'resolve-target resolve-token-target' \
    'resolve-rename resolve-token-rename' 'owner owner'; do
    want="MEDIA START FAIL render ${row#* }"
    fixture::new_case
    auth_mode::install_failure_run "${row% *}" || return 1
    fixture::expect_blocked "${want}" || ok=1
    if [[ "$(fixture::runtime_files)" != 0 ]] \
      || [[ -d "${fixture_dir}/elsewhere" \
        && -n "$(ls -A -- "${fixture_dir}/elsewhere")" ]]; then
      harness::add_reason "${row% *}: 파일이 남음"
      ok=1
    fi
    fixture::common_checks || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 47 — 응답기 모드 비밀을 담는 변수 이름이 부모 환경에서 export 된 채
# 들어와도 비밀 값이 가짜 명령 기록 · 출력에 나오지 않는다.
#######################################
auth_mode::case_inherited_export() {
  local ok=0
  auth_mode::prod_template || return 1
  fixture::new_case
  auth_mode::run "${auth_mode_tmpl}" basic_pass=inherited \
    publish_lock=inherited read_lock=inherited api_pass=inherited \
    file_bodies=inherited rendered=inherited content=inherited \
    value=inherited template_body=inherited resolve_token=inherited \
    || return 1
  if (( fixture_status != 0 )); then
    harness::add_reason "종료 코드 ${fixture_status}"
    return 1
  fi
  auth_mode::read_values || return 1
  auth_mode::check_contained || ok=1
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 48 — 응답기 모드를 다시 돌리면 비밀 넷이 모두 새 값이고, 응답기 파일은
# rename 으로 바뀐다(옛 inode 를 고쳐 쓰지 않는다).
#######################################
auth_mode::case_rerun_fresh() {
  local ok=0 ia first
  auth_mode::prod_template || return 1
  fixture::new_case
  auth_mode::run "${auth_mode_tmpl}" || return 1
  auth_mode::read_values || return 1
  first="${auth_basic} ${auth_publish} ${auth_read} ${auth_api}"
  ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}"
  ln -- "${ia}/${AUTH_BASIC_NAME}" "${fixture_dir}/old-basic"
  ln -- "${ia}/${API_PASS_NAME}" "${fixture_dir}/old-api"
  auth_mode::run "${auth_mode_tmpl}" || return 1
  auth_mode::read_values || return 1
  if [[ "${first}" == *"${auth_basic}"* || "${first}" == *"${auth_publish}"* \
    || "${first}" == *"${auth_read}"* || "${first}" == *"${auth_api}"* ]]; then
    harness::add_reason '다시 돌린 실행의 비밀이 앞 실행 값과 겹침'
    ok=1
  fi
  if [[ "$(auth_mode::line_value "${fixture_dir}/old-basic")" \
    != "${first%% *}" ]] \
    || [[ "$(auth_mode::line_value "${fixture_dir}/old-api")" \
      != "${first##* }" ]]; then
    harness::add_reason '옛 응답기 파일 inode 의 내용이 바뀜(제자리 쓰기)'
    ok=1
  fi
  auth_mode::check_files || ok=1
  return "${ok}"
}

#######################################
# 정지 사례 준비 — 응답기 디렉터리에 비밀 파일 셋 · 각자의 임시 파일 꼴 잔여와
# 남겨야 할 상태 디렉터리를 두고, RUNTIME_DIR 에 사이드카 4C 토큰 사본과 그
# 임시 파일 꼴 잔여를 둔다(RUNTIME_DIR 의 렌더 파일은 seed_runtime).
# Globals:
#   fixture_dir
#######################################
auth_mode::seed_ingest_auth() {
  local ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}" name
  local run="${fixture_dir}/runtime"
  seed_runtime
  printf '%s\n' "${FIXTURE_FAKE_SECRET}" >"${run}/${AUTH_RESOLVE_NAME}"
  printf '%s\n' "${FIXTURE_FAKE_SECRET}" >"${run}/.${AUTH_RESOLVE_NAME}.Qw3456"
  chmod 0400 "${run}/${AUTH_RESOLVE_NAME}"
  mkdir -p -- "${ia}/state"
  printf 'keep\n' >"${ia}/state/connlog.jsonl"
  for name in "${AUTH_BASIC_NAME}" "${API_PASS_NAME}" "${AUTH_RESOLVE_NAME}"
  do
    printf '%s\n' "${FIXTURE_FAKE_SECRET}" >"${ia}/${name}"
    printf '%s\n' "${FIXTURE_FAKE_SECRET}" >"${ia}/.${name}.AbC123"
    chmod 0400 "${ia}/${name}"
  done
}

#######################################
# 정지 뒤 응답기 디렉터리의 비밀 · 임시 파일 수를 낸다(상태 디렉터리는 세지
# 않는다).
# Globals:
#   fixture_dir
#######################################
auth_mode::ingest_auth_secrets_left() {
  local ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}"
  find "${ia}" -mindepth 1 -maxdepth 1 ! -type d | awk 'END { print NR }'
}

#######################################
# 사례 49 — stop(인자 없음 · --cleanup-only): 응답기 디렉터리의 비밀 · 임시
# 파일과 사이드카 4C 토큰 사본 · 그 임시 파일을 지우고, 상태 디렉터리와 그
# 안의 파일은 남긴다.
#######################################
auth_mode::case_stop_cleanup() {
  local ok=0 args ia
  for args in '' '--cleanup-only'; do
    fixture::new_case
    fixture::base_env
    auth_mode::seed_ingest_auth
    ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}"
    # shellcheck disable=SC2086 # 인자 목록을 낱말로 나눈다.
    fixture::run stop-media.sh -- ${args} || return 1
    if (( fixture_status != 0 )); then
      harness::add_reason "「${args}」 종료 코드 ${fixture_status}"
      ok=1
    fi
    if [[ "$(auth_mode::ingest_auth_secrets_left)" != 0 ]]; then
      harness::add_reason "「${args}」 응답기 디렉터리에 비밀 · 임시 파일이 남음"
      ok=1
    fi
    if [[ -n "$(find "${fixture_dir}/runtime" -maxdepth 1 \
      -name "*${AUTH_RESOLVE_NAME}*")" ]]; then
      harness::add_reason "「${args}」 사이드카 4C 토큰 사본 · 임시 파일이 남음"
      ok=1
    fi
    if [[ "$(cat -- "${ia}/state/connlog.jsonl" 2>/dev/null)" != keep ]]; then
      harness::add_reason "「${args}」 상태 디렉터리가 지워짐"
      ok=1
    fi
    if grep -q -F -- 'CLEANUP FAIL' "${fixture_dir}/out"; then
      harness::add_reason "「${args}」 CLEANUP FAIL 줄이 나옴"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 50 — stop: 응답기 디렉터리의 비밀을 지우지 못하면(디렉터리 쓰기 불가)
# 「MEDIA STOP CLEANUP FAIL <응답기 디렉터리>」 를 내고 0 아닌 코드로 끝난다.
# 쓰기 불가는 root 가 아닐 때만 흉내 낼 수 있다(root 면 판정 불가로 FAIL).
#######################################
auth_mode::case_stop_cleanup_fail() {
  local ok=0 ia status=0
  if [[ "$(id -u)" == 0 ]]; then
    harness::add_reason 'root 로 돌아 쓰기 불가 디렉터리를 흉내 낼 수 없음(판정 불가)'
    return 1
  fi
  fixture::new_case
  fixture::base_env
  auth_mode::seed_ingest_auth
  ia="${fixture_dir}/runtime/${AUTH_DIR_NAME}"
  chmod 0500 "${ia}"
  fixture::run stop-media.sh -- --cleanup-only || status=1
  chmod 0700 "${ia}"
  (( status == 0 )) || return 1
  if (( fixture_status == 0 )); then
    harness::add_reason '비밀이 남았는데 종료 코드 0'
    ok=1
  fi
  if ! grep -q -F -x -- "MEDIA STOP CLEANUP FAIL ${ia}" "${fixture_dir}/out"
  then
    harness::add_reason "출력에 「MEDIA STOP CLEANUP FAIL <응답기 디렉터리>」 \
없음: $(harness::log_tail "${fixture_dir}/out")"
    ok=1
  fi
  if [[ -e "${fixture_dir}/runtime/mediamtx.yml" ]]; then
    harness::add_reason '응답기 쪽 실패 때문에 RUNTIME_DIR 렌더를 지우지 않음'
    ok=1
  fi
  return "${ok}"
}
