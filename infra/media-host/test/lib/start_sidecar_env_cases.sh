# shellcheck shell=bash
# 전역(fixture_dir · fixture_status 등)과 fixture::* 는 start_fixture.sh 가,
# auth_mode::* · auth_input::* 는 start_ingest_auth_cases.sh ·
# start_ingest_input_cases.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 상자 스위치 파일 사례(64). compose 파일 옆 sidecar.env(사이드카의
# required env_file — 새 꼴 쓰기 스위치 PLAYBACK_ROUND_KEYS)가 링크 아닌 일반
# 파일이고 정확히 PLAYBACK_ROUND_KEYS=true 또는 =false 한 줄이어야 기동한다.
# 아니면 compose 가 판정 줄 없이 재시작을 되풀이하므로(E28) 시작 스크립트가
# 비밀을 읽기 전에 막는다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly SIDECAR_ENV_NAME='sidecar.env'
readonly SIDECAR_BLOCKED_LINE='MEDIA START BLOCKED: sidecar settings unavailable'

#######################################
# 사례 디렉터리의 sidecar.env 를 행 이름의 꼴로 바꿔 둔다(fixture::new_case 가
# 둔 기본 파일을 지우고 시작한다).
# Globals:
#   fixture_dir
# Arguments:
#   행 이름.
#######################################
sidecar_env::seed() {
  local f="${fixture_dir}/${SIDECAR_ENV_NAME}"
  rm -f -- "${f}"
  case "$1" in
    missing) ;;
    link)
      printf 'PLAYBACK_ROUND_KEYS=false\n' >"${fixture_dir}/real.env"
      ln -s -- "${fixture_dir}/real.env" "${f}"
      ;;
    dir) mkdir -p -- "${f}" ;;
    two) printf 'PLAYBACK_ROUND_KEYS=false\nPLAYBACK_ROUND_KEYS=true\n' >"${f}" ;;
    on) printf 'PLAYBACK_ROUND_KEYS=on\n' >"${f}" ;;
    upper) printf 'PLAYBACK_ROUND_KEYS=TRUE\n' >"${f}" ;;
    crlf) printf 'PLAYBACK_ROUND_KEYS=true\r\n' >"${f}" ;;
    space) printf 'PLAYBACK_ROUND_KEYS=true \n' >"${f}" ;;
    blank-tail) printf 'PLAYBACK_ROUND_KEYS=true\n\n' >"${f}" ;;
    empty) : >"${f}" ;;
    nul) printf 'PLAYBACK_ROUND_KEYS=true\000\n' >"${f}" ;;
    true) printf 'PLAYBACK_ROUND_KEYS=true\n' >"${f}" ;;
    false) printf 'PLAYBACK_ROUND_KEYS=false\n' >"${f}" ;;
    false-no-eol) printf 'PLAYBACK_ROUND_KEYS=false' >"${f}" ;;
  esac
}

#######################################
# 막힘 행의 판정 — 판정 줄 · up 0 · 렌더 0 · aws 0(CDN 비밀을 읽기 전에 막는다).
# Arguments:
#   행 이름.
# Returns:
#   0 / 1 어긋남.
#######################################
sidecar_env::expect_blocked() {
  local ok=0 calls
  fixture::expect_blocked "${SIDECAR_BLOCKED_LINE}" || ok=1
  if [[ "$(fixture::runtime_files)" != 0 ]]; then
    harness::add_reason 'RUNTIME_DIR 에 파일이 남음'
    ok=1
  fi
  calls="$(fixture::count_records aws)"
  if (( calls != 0 )); then
    harness::add_reason "aws ${calls}회(기대 0 — 비밀을 읽기 전에 막음)"
    ok=1
  fi
  fixture::common_checks || ok=1
  (( ok == 0 )) || harness::add_reason "행 「$1」"
  return "${ok}"
}

#######################################
# 사례 64 — 상자 스위치 파일(compose 파일 옆 sidecar.env)이 없거나 · 링크 ·
# 디렉터리 · 꼴 밖이면 두 모드 모두 비밀을 읽기 전에 막는다. true · false 한
# 줄(끝 줄바꿈 없음 포함)은 받는다.
#######################################
sidecar_env::case_switch_file() {
  local ok=0 row
  for row in missing link dir two on upper crlf space blank-tail empty nul; do
    fixture::new_case
    sidecar_env::seed "${row}"
    fixture::base_env
    fixture::run start-media.sh || return 1
    sidecar_env::expect_blocked "${row}" || ok=1
  done
  # 응답기 모드도 같은 점검을 거친다(모드와 무관한 사이드카 설정).
  auth_mode::prod_template || return 1
  fixture::new_case
  auth_input::write_compose 1
  sidecar_env::seed missing
  auth_mode::run "${auth_mode_tmpl}" || return 1
  sidecar_env::expect_blocked 'missing(응답기 모드)' || ok=1
  for row in true false false-no-eol; do
    fixture::new_case
    sidecar_env::seed "${row}"
    fixture::base_env
    fixture::run start-media.sh || return 1
    if (( fixture_status != 0 )); then
      harness::add_reason "${row}: 종료 코드 ${fixture_status}: \
$(harness::log_tail "${fixture_dir}/out")"
      ok=1
    fi
  done
  return "${ok}"
}
