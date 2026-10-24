# shellcheck shell=bash
#
# start_test 의 실행 도구 — 사례마다 깨끗한 디렉터리를 만들고, 시작 · 정지
# 스크립트를 빈 환경(env -i)에 정해진 값만 넣어 돌리고, 가짜 명령(docker ·
# chown · aws)이 남긴 기록을 읽는다.
#
# 사례 디렉터리:
#   bin/            PATH 의 맨 앞 — start_fakebin 의 docker · chown 과 fakebin 의
#                   aws · docker-credential-ecr-login 을 가리키는 링크.
#
# PATH 의 뒤쪽은 호스트 디렉터리(/usr/bin · /bin)가 아니라 그 안의 명령에서 가짜가
# 맡는 이름(FIXTURE_FAKE_NAMES)을 뺀 링크 모음(fixture::make_sysbin)이다. 호스트에
# 실물 docker · aws · 자격 도우미가 깔려 있어도(GitHub ubuntu 러너는 자격 도우미가
# /usr/bin 에 있다) 가짜를 뺀 사례의 「없음」 이 실물로 가려지지 않는다.
#   log/            가짜 명령 기록(<명령>.<n>.argv · <명령>.<n>.env).
#   docker-config/  config.json(credHelpers 에 시험 레지스트리 호스트).
#   runtime/        RUNTIME_DIR(스크립트가 만든다).
#   out             스크립트의 stdout · stderr.
#
# 시험 비밀 · ARN · 호스트는 고정 가짜 문자열이다. 이 파일은 source 해서 쓴다.
# bash 3.2 에서도 돈다.

# 비밀 문자열을 품지 않게 짓는다(품으면 노출 검사가 ARN 을 비밀로 센다).
readonly FIXTURE_FAKE_ARN='pcbench-edge-proxy-fake-arn'
readonly FIXTURE_FAKE_ECR_HOST='pcbench-edge-proxy.invalid'
readonly FIXTURE_FAKE_SECRET='pcbench-edge-proxy-fake-cdn-secret'
# 응답기 모드 4C 토큰(가짜 aws 의 값) — 공통 노출 검사도 본다.
readonly FIXTURE_FAKE_RESOLVE_TOKEN='pcbench-ingest-auth-fake-resolve-token'
readonly FIXTURE_COMPOSE_VERSION='9.9.9-pcbench'
readonly FIXTURE_PROJECT='pcbench-edge-proxy'
readonly FIXTURE_RUN_TIMEOUT=20
# start_test 가 읽는다.
# shellcheck disable=SC2034
readonly FIXTURE_SECRET_TOKEN='@@HLS_CDN_SECRET@@'
# shellcheck disable=SC2034
readonly FIXTURE_API_PASS_TOKEN='@@MTX_API_PASS@@'
readonly FIXTURE_PREREQ_ORDER="compose-version ecr-credential-helper \
docker-config"
# 가짜가 맡는 명령 이름 — 호스트 명령 링크 모음에 넣지 않는다.
readonly FIXTURE_FAKE_NAMES='docker chown aws docker-credential-ecr-login'

fixture_root=""
fixture_sysbin=""
fixture_dir=""
fixture_status=0
fixture_env=()
fixture_cmd=()
fixture_value_file=""

#######################################
# 호스트 명령 링크 모음을 fixture_root 아래에 새로 만든다(이후 fixture_sysbin).
# 주어진 디렉터리 순서대로 실행 파일을 링크하고(앞 디렉터리가 이긴다 — PATH 와
# 같은 순서), 가짜가 맡는 이름은 넣지 않는다.
# Globals:
#   fixture_root, fixture_sysbin
# Arguments:
#   호스트 디렉터리 ….
#######################################
fixture::make_sysbin() {
  local dir f name
  fixture_sysbin="$(mktemp -d "${fixture_root}/sysbin.XXXXXX")"
  for dir in "$@"; do
    for f in "${dir}"/*; do
      name="${f##*/}"
      [[ -f "${f}" && -x "${f}" ]] || continue
      case " ${FIXTURE_FAKE_NAMES} " in
        *" ${name} "*) continue ;;
      esac
      [[ ! -e "${fixture_sysbin}/${name}" ]] || continue
      ln -s -- "${f}" "${fixture_sysbin}/${name}"
    done
  done
}

#######################################
# 사례 디렉터리를 새로 만든다(이후 fixture_dir).
# Globals:
#   fixture_root, fixture_dir, TEST_DIR
# Arguments:
#   자격 도우미를 PATH 에 둘지(1|0).
#######################################
fixture::new_case() {
  local with_helper="${1:-1}"
  fixture_dir="$(mktemp -d "${fixture_root}/case.XXXXXX")"
  mkdir -p -- "${fixture_dir}/bin" "${fixture_dir}/log" \
    "${fixture_dir}/docker-config" "${fixture_dir}/home"
  ln -s -- "${TEST_DIR}/start_fakebin/docker" "${fixture_dir}/bin/docker"
  ln -s -- "${TEST_DIR}/start_fakebin/chown" "${fixture_dir}/bin/chown"
  ln -s -- "${TEST_DIR}/fakebin/aws" "${fixture_dir}/bin/aws"
  if (( with_helper )); then
    ln -s -- "${TEST_DIR}/fakebin/docker-credential-ecr-login" \
      "${fixture_dir}/bin/docker-credential-ecr-login"
  fi
  printf '{\n  "credHelpers": {\n    "%s": "ecr-login"\n  }\n}\n' \
    "${FIXTURE_FAKE_ECR_HOST}" >"${fixture_dir}/docker-config/config.json"
}

#######################################
# 가짜 aws 의 value 모드가 낼 값을 사례 디렉터리 밖(형제) 파일에 둔다 — 「기록 ·
# 출력에 값 0」 검사 대상 밖이고, 환경 변수로 넘기면 자식 기록에 섞인다.
# Globals:
#   fixture_dir, fixture_value_file
# Arguments:
#   값.
#######################################
fixture::value_file() {
  fixture_value_file="${fixture_dir}.value"
  printf '%s' "$1" >"${fixture_value_file}"
}

#######################################
# 모든 점검을 통과시키는 기본 값을 fixture_env 에 넣는다. 이름을 주면 그 값은
# 빼고 넣는다(주입하지 않은 상태 — prod 기본값 사례).
# Globals:
#   fixture_env, fixture_dir, TEST_DIR
# Arguments:
#   뺄 변수 이름 …(없어도 됨).
#######################################
fixture::base_env() {
  local entry name skip all
  all=(
    "MEDIA_EXPECTED_COMPOSE_VERSION=${FIXTURE_COMPOSE_VERSION}"
    "DOCKER_CONFIG=${fixture_dir}/docker-config"
    "ECR_REGISTRY_HOST=${FIXTURE_FAKE_ECR_HOST}"
    "CDN_SECRET_ARN=${FIXTURE_FAKE_ARN}"
    "MEDIA_COMPOSE_FILE=${fixture_dir}/compose.yml"
    "MEDIA_MTX_TEMPLATE=${TEST_DIR}/mediamtx.test.yml.tmpl"
    "RUNTIME_DIR=${fixture_dir}/runtime"
    "MEDIA_COMPOSE_PROJECT=${FIXTURE_PROJECT}"
  )
  fixture_env=()
  for entry in "${all[@]}"; do
    skip=0
    for name in "$@"; do
      [[ "${entry%%=*}" != "${name}" ]] || skip=1
    done
    (( skip )) || fixture_env+=("${entry}")
  done
}

#######################################
# 스크립트를 빈 환경(env -i)에 fixture_env 와 가짜 명령 설정만 넣어 부르는
# 명령 줄을 fixture_cmd 에 만든다(스크립트 인자는 부르는 쪽이 뒤에 붙인다).
# Globals:
#   fixture_cmd, fixture_dir, fixture_env, fixture_sysbin
# Arguments:
#   스크립트 경로, 가짜 명령 설정(NAME=값 …).
#######################################
fixture::command() {
  local script="$1"
  shift
  fixture_cmd=(env -i "HOME=${fixture_dir}/home"
    "PATH=${fixture_dir}/bin:${fixture_sysbin:?호스트 명령 링크 모음이 없다}"
    "PCBENCH_FAKE_LOG_DIR=${fixture_dir}/log"
    "PCBENCH_FAKE_COMPOSE_VERSION=${FIXTURE_COMPOSE_VERSION}"
    ${fixture_env[@]+"${fixture_env[@]}"} "$@" "${script}")
}

#######################################
# 스크립트를 빈 환경에 fixture_env 와 가짜 명령 설정만 넣어 돌린다.
# Globals:
#   fixture_dir, fixture_env, fixture_status, MEDIA_HOST_DIR
# Arguments:
#   스크립트(bin 아래 이름), 가짜 명령 설정(NAME=값 …), --, 스크립트 인자 ….
#######################################
fixture::run() {
  local script="${MEDIA_HOST_DIR}/bin/$1" fakes=()
  shift
  while (( $# > 0 )) && [[ "$1" != -- ]]; do
    fakes+=("$1")
    shift
  done
  (( $# == 0 )) || shift
  fixture_status=0
  if [[ ! -x "${script}" ]]; then
    harness::add_reason "bin/${script##*/} 없음"
    fixture_status=127
    return 1
  fi
  fixture::command "${script}" ${fakes[@]+"${fakes[@]}"}
  harness::run_bounded "${FIXTURE_RUN_TIMEOUT}" "${fixture_dir}/out" \
    "${fixture_cmd[@]}" "$@" || fixture_status=$?
  if (( fixture_status == 124 )); then
    harness::add_reason "bin/${script##*/} 가 ${FIXTURE_RUN_TIMEOUT}초 안에 안 끝남"
    return 1
  fi
}

#######################################
# 가짜 명령 호출 기록 가운데 compose 하위 명령이 같은 것의 argv 파일을 낸다.
# Globals:
#   fixture_dir
# Arguments:
#   하위 명령(version · up · down).
# Outputs:
#   argv 파일 경로(한 줄에 하나).
#######################################
fixture::docker_calls() {
  local f
  for f in "${fixture_dir}"/log/docker.*.argv; do
    [[ -f "${f}" ]] || continue
    if [[ "$(fixture::compose_subcommand "${f}")" == "$1" ]]; then
      printf '%s\n' "${f}"
    fi
  done
}

#######################################
# argv 파일 하나에서 compose 하위 명령 이름을 낸다(전역 옵션 --ansi · -p · -f 와
# 그 값은 건너뛴다 — 가짜 docker 와 같은 규칙).
# Arguments:
#   argv 파일.
#######################################
fixture::compose_subcommand() {
  awk 'NR == 1 { if ($0 != "compose") exit; next }
       skip { skip = 0; next }
       $0 == "--ansi" || $0 == "-p" || $0 == "-f" { skip = 1; next }
       /^-/ { next }
       { print; exit }' "$1"
}

#######################################
# 호출 수를 센다.
# Arguments:
#   compose 하위 명령.
# Outputs:
#   건수.
#######################################
fixture::count_calls() {
  fixture::docker_calls "$1" | awk 'END { print NR }'
}

#######################################
# 명령 기록 파일 수를 센다.
# Globals:
#   fixture_dir
# Arguments:
#   명령 이름(docker · chown · aws).
#######################################
fixture::count_records() {
  local f n=0
  for f in "${fixture_dir}"/log/"$1".*.argv; do
    [[ -f "${f}" ]] && n=$((n + 1))
  done
  printf '%s\n' "${n}"
}

#######################################
# 파일 · 디렉터리의 권한 문자열(ls 의 앞 10글자)을 낸다. stat 은 BSD · GNU 의
# 옵션이 달라 쓰지 않는다 — 권한 확인은 이 한 곳에서만 한다.
# Arguments:
#   경로.
#######################################
fixture::perm() {
  local line
  line="$(ls -ld -- "$1")" || return 1
  printf '%s\n' "${line:0:10}"
}

#######################################
# 파일 · 디렉터리 아래(재귀)에서 문자열이 든 파일을 낸다. 문자열은 stdin 으로만
# grep 에 넘긴다.
# Arguments:
#   문자열, 찾을 경로 ….
# Outputs:
#   파일 경로(한 줄에 하나).
#######################################
fixture::files_with() {
  local needle="$1"
  shift
  printf '%s\n' "${needle}" | grep -r -l -F -f - -- "$@" 2>/dev/null || true
}

#######################################
# 출력에서 선행 점검 기록 줄의 항목 이름 순서를 낸다.
# Globals:
#   fixture_dir
# Outputs:
#   항목 이름을 공백으로 이은 한 줄.
#######################################
fixture::prereq_order() {
  awk '/^MEDIA PREREQ (OK|FAIL) / { printf "%s%s", sep, $4; sep = " " }
       END { print "" }' "${fixture_dir}/out"
}

#######################################
# 이번 실행의 공통 단언 — 인자가 없는 실행이면 선행 점검 세 항목이 정한
# 순서로 기록되고, 시험 비밀(CDN · 4C 토큰)이 가짜 명령 기록 · 스크립트 출력
# 어디에도 없다.
# Globals:
#   fixture_dir
#######################################
fixture::common_checks() {
  local ok=0 order leaked secret
  order="$(fixture::prereq_order)"
  if [[ "${order}" != "${FIXTURE_PREREQ_ORDER}" ]]; then
    harness::add_reason "선행 점검 기록 순서 「${order}」(기대 \
「${FIXTURE_PREREQ_ORDER}」)"
    ok=1
  fi
  for secret in "${FIXTURE_FAKE_SECRET}" "${FIXTURE_FAKE_RESOLVE_TOKEN}"; do
    leaked="$(fixture::files_with "${secret}" "${fixture_dir}/log" \
      "${fixture_dir}/out" | awk 'END { print NR }')"
    if (( leaked != 0 )); then
      harness::add_reason "시험 비밀이 가짜 명령 기록 · 출력 ${leaked}곳에 있음"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# up 호출이 0건이고 출력에 고정 문자열 줄이 있는지 본다(기동 차단 사례).
# Globals:
#   fixture_dir, fixture_status
# Arguments:
#   기대 고정 문자열(줄 전체 또는 앞머리).
#######################################
fixture::expect_blocked() {
  local ok=0 ups
  if (( fixture_status == 0 )); then
    harness::add_reason '종료 코드 0(기대: 0 아님)'
    ok=1
  fi
  ups="$(fixture::count_calls up)"
  if (( ups != 0 )); then
    harness::add_reason "compose up ${ups}회(기대 0)"
    ok=1
  fi
  if ! grep -q -F -x -- "$1" "${fixture_dir}/out"; then
    harness::add_reason "출력에 「$1」 줄 없음: $(harness::log_tail \
"${fixture_dir}/out")"
    ok=1
  fi
  return "${ok}"
}

#######################################
# RUNTIME_DIR 안의 파일 수(숨은 임시 파일 포함)를 낸다. 디렉터리가 없으면 0.
# Globals:
#   fixture_dir
#######################################
fixture::runtime_files() {
  local dir="${fixture_dir}/runtime"
  [[ -d "${dir}" ]] || { printf '0\n'; return 0; }
  find "${dir}" -mindepth 1 -type f | awk 'END { print NR }'
}

#######################################
# 템플릿에서 한 모드의 블록만 남긴 기대 본문을 파일에 쓴다 — 시작 스크립트의
# 모드 블록 고르기(bin/lib/render_files.sh)를 awk 로 따로 구현한 것이다(같은
# 함수로 기대값을 만들면 그 함수의 결함이 보이지 않는다). 표지 줄(「# @mode
# <모드> begin|end」)과 다른 모드 블록을 빼고 나머지 줄은 그대로 둔다. 표지 꼴
# 검사는 하지 않는다 — 정상 꼴 템플릿에만 쓴다.
# Arguments:
#   모드(legacy | ingest-auth), 템플릿, 출력 파일.
#######################################
fixture::mode_body() {
  awk -v want="$1" '
    /^# @mode (legacy|ingest-auth) begin$/ { block = $3; next }
    /^# @mode (legacy|ingest-auth) end$/ { block = ""; next }
    block == "" || block == want' "$2" >"$3"
}

#######################################
# 두 모드 블록을 둔 짧은 템플릿 본문을 낸다(시작 스크립트는 두 모드 블록이 모두
# 있는 템플릿만 받는다). 빈 본문이면 그 모드 블록은 표지 둘뿐이다.
# Arguments:
#   옛 모드 블록 본문, 응답기 모드 블록 본문(없어도 됨).
# Outputs:
#   본문.
#######################################
fixture::marked_template() {
  printf '# @mode legacy begin\n'
  [[ -z "$1" ]] || printf '%s\n' "$1"
  printf '# @mode legacy end\n# @mode ingest-auth begin\n'
  [[ -z "${2:-}" ]] || printf '%s\n' "$2"
  printf '# @mode ingest-auth end\npaths:\n  all_others:\n'
}
