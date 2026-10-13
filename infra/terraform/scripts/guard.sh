#!/usr/bin/env bash
#
# Terraform 트리의 금지 규약을 검사한다(README 「guard 규칙」).
#
# 규칙 R1 ~ R13 은 conftest(`--parser hcl2`)로 돌리는 Rego 정책
# (`../policy/guard`)이 판정한다. 이 스크립트는 얇은 실행기다 — 파서가 볼 수
# 없는 R0(`.tf.json` · 심볼릭 링크 · 숨김 · 줄바꿈 · 제어 문자 이름 · conftest
# 설정 파일)를 직접 보고, 설정 파일이 있으면 conftest 를 부르지 않고 멈추고,
# 파일마다 파싱이 되는지 먼저 가린 뒤, 정책을 한 번에(`--combine`) 돌려 종료
# 코드를 정한다. R12 의 모드 표지(fixture · operational)는 이 스크립트가 루트를 보고
# 골라 늘 `--data` 로 넘긴다.
# 위반은 표준 출력에 한 줄씩 「guard: R<번호> <상대 경로>: <설명>」으로 적는다.
#
# 사용: bash infra/terraform/scripts/guard.sh [Terraform 트리 루트]
#       루트를 주지 않으면 이 스크립트가 든 infra/terraform 이다. 정책은 루트와
#       상관없이 늘 이 저장소의 것을 쓴다(fixture 트리를 루트로 줘도 같다).
# 종료 코드: 0 위반 0 / 1 위반 있음 / 2 사용법 오류 · conftest 없음 · 검사
#            불가(파싱 실패 · 읽을 수 없는 파일 · 검사할 .tf 0건 · conftest
#            오류) — 위반 0 으로 넘어가지 않는다(fail-closed).
# 임시 파일 · 프로세스 치환을 쓰지 않는다. 명령 출력은 변수로 받고 종료
# 코드를 따로 본다 — 생산자가 실패해 빈 출력으로 위반 0 을 내지 않게 한다.

set -uo pipefail

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly POLICY_DIR="${SCRIPT_DIR}/../policy/guard"
# R12 모드 표지(키 하나 mode)의 저장소 고정 위치 — <표지>/mode.json.
readonly MARKER_DIR="${SCRIPT_DIR}/guard-data"
# conftest 를 부를 때마다 --config-file 로 넘기는 저장소 고정 빈 설정 — 검사
# 루트(작업 디렉터리)의 conftest.<확장자> 를 아예 찾지 않게 한다.
readonly EMPTY_CONFIG="${MARKER_DIR}/empty-config.toml"
# conftest 0.71.1(viper v1.21.0)이 검사 루트에서 conftest.<확장자> 로 찾는
# 설정 파일 확장자. 대소문자는 가리지 않는다(macOS 기본 파일 시스템).
CONFTEST_CONFIG_EXTS=(json toml yaml yml properties props prop hcl tfvars dotenv
  env ini)
readonly CONFTEST_CONFIG_EXTS

# `conftest test` 의 결과 줄. --combine 이라 파일 이름 자리가 Combined 다.
readonly FAIL_PREFIX='FAIL - Combined - main - '
# 요약 줄: 「<검사> tests, N passed, N warnings, <실패> failures, <예외> exceptions」.
SUMMARY_PATTERN='^([0-9]+) tests?, [0-9]+ passed, [0-9]+ warnings?, '
SUMMARY_PATTERN+='([0-9]+) failures?, ([0-9]+) exceptions?'
readonly SUMMARY_PATTERN

violations=0
LINES=()

#######################################
# 검사를 이어갈 수 없을 때 멈춘다(위반 0 으로 넘어가지 않는다).
# Arguments:
#   설명.
#######################################
die() {
  echo "guard: 검사 불가: $*" >&2
  exit 2
}

#######################################
# 위반 한 줄을 적고 센다.
# Globals:
#   violations
# Arguments:
#   「R<번호> <상대 경로>: <설명>」.
#######################################
report() {
  echo "guard: $1"
  violations=$((violations + 1))
}

#######################################
# 변수 내용을 줄 단위로 쪼갠다(빈 줄은 버림). 파이프 · 임시 파일을 쓰지 않는다.
# Globals:
#   LINES
# Arguments:
#   쪼갤 내용.
#######################################
split_lines() {
  local old_ifs="${IFS}"
  set -f
  IFS=$'\n'
  # shellcheck disable=SC2206
  LINES=($1)
  IFS="${old_ifs}"
  set +f
}

#######################################
# 현재 디렉터리(루트) 아래 검사 대상을 상대 경로로 NUL 구분해 낸다 —
# 이름에 줄바꿈이 있어도 한 항목이 둘로 쪼개지지 않게. 대상은 일반 파일
# *.tf · *.tf.json, conftest.*(설정 파일 후보 — 대소문자 무관), 그리고 이름과
# 상관없이 모든 심볼릭 링크다(find 는 링크 디렉터리 안으로 들어가지 않으므로
# 링크 자체를 R0 로 막아야 그 너머가 검사에서 빠지지 않는다). fixture 와
# provider 캐시는 진짜 디렉터리일 때만 뺀다 — 그 이름의 링크는 R0 로 잡힌다.
# Outputs:
#   「./」로 시작하는 상대 경로(정렬 · NUL 구분).
#######################################
list_tf_files() {
  find . \
    \( -path ./scripts/testdata -type d -o -name .terraform -type d \) -prune \
    -o -type l -print0 \
    -o -type f \( -name '*.tf' -o -name '*.tf.json' -o -iname 'conftest.*' \) \
    -print0 |
    LC_ALL=C sort -z
}

#######################################
# conftest 가 검사 루트에서 설정으로 읽는 이름인지 본다 — conftest.<확장자>,
# 확장자는 CONFTEST_CONFIG_EXTS, 대소문자 무관.
# Arguments:
#   파일 이름(경로 없이).
# Returns:
#   설정 파일 이름이면 0, 아니면 1.
#######################################
is_conftest_config() {
  local name="$1"
  local ext status=1
  shopt -s nocasematch
  for ext in "${CONFTEST_CONFIG_EXTS[@]}"; do
    if [[ "${name}" == "conftest.${ext}" ]]; then
      status=0
      break
    fi
  done
  shopt -u nocasematch
  return "${status}"
}

#######################################
# list_tf_files 의 NUL 구분 목록을 읽어 항목마다 한 줄로 분류한다. 부모에
# 상태를 넘기지 않는 순수 필터라 파이프로 잇고, 종료 코드는 부모가
# pipefail 로 본다. Terraform 이 읽는 파일 집합과 guard 가 보는 집합이
# 갈리는 꼴(숨김 이름 · 줄바꿈 · 제어 문자 · 링크)은 여기서 R0 가 된다.
# 줄바꿈 · 제어 문자가 든 경로는 %q 로 적으므로 결과 줄에 줄바꿈이 끼지
# 않는다.
# Outputs:
#   한 줄에 하나 — 「r0 <위반>」 · 「config <위반>」 · 「tf <상대 경로>」.
#######################################
classify_files() {
  local file name
  while IFS= read -r -d '' file; do
    file="${file#./}"
    name="${file##*/}"
    if is_conftest_config "${name}"; then
      # 링크든(conftest 는 링크를 따라 읽는다) 경로에 제어 문자가 있든,
      # 종류와 상관없이 그 이름이면 설정 파일이다. 경로는 %q 로 적는다.
      printf 'config R0 %q: conftest 설정 파일 금지(%s)\n' "${file}" \
        "정책 · R12 표지를 바꿀 수 있음"
    elif [[ "${file}" == *[[:cntrl:]]* ]]; then
      printf 'r0 R0 %q: 이름에 줄바꿈 · 제어 문자 금지(%s)\n' "${file}" \
        "줄 단위 처리 · 리뷰에서 숨음"
    elif [[ -L "${file}" ]]; then
      printf 'r0 R0 %s: 심볼릭 링크 금지(%s)\n' "${file}" \
        "파일 · 디렉터리 — 링크 너머가 검사에서 빠짐"
    elif [[ "${file}" == *.tf.json ]]; then
      printf 'r0 R0 %s: .tf.json 금지(%s)\n' "${file}" \
        "이 트리는 HCL 만 — 정책이 JSON 구성을 읽지 않음"
    elif [[ "${file}" == *.tf && "${name}" == .* ]]; then
      printf 'r0 R0 %s: 숨김 .tf 금지(%s)\n' "${file}" \
        "Terraform 은 읽지 않는데 guard 는 셈"
    elif [[ "${file}" == *.tf ]]; then
      printf 'tf %s\n' "${file}"
    fi
  done
}

#######################################
# 파일 하나가 hcl2 로 파싱되는지 본다. 실패하면 이유를 표준 오류에 적는다.
# Arguments:
#   상대 경로.
# Returns:
#   파싱되면 0, 아니면 1.
#######################################
parses() {
  local file="$1"
  local error
  if [[ ! -r "${file}" ]]; then
    echo "guard: 검사 불가: 읽을 수 없음: ${file}" >&2
    return 1
  fi
  if ! error="$(conftest parse --config-file "${EMPTY_CONFIG}" --parser hcl2 \
    -- "${file}" 2>&1 >/dev/null)"; then
    echo "guard: 검사 불가: 파싱 실패: ${file}: ${error}" >&2
    return 1
  fi
}

#######################################
# 정책을 돌려 위반을 적는다. --no-fail 이라 위반이 있어도 conftest 는 0 으로
# 끝나므로, 0 이 아닌 종료 코드는 모두 conftest 자체 오류(정책 컴파일 ·
# 내장 함수 오류 등)다. 결과 줄의 FAIL 수가 요약 줄의 failures 와 다르거나
# 모르는 줄 · 예외가 있으면 검사 불가로 멈춘다.
# Arguments:
#   R12 표지 파일 절대 경로, 검사할 상대 경로들.
#######################################
run_policy() {
  local marker="$1" output line
  shift
  # 설정 파일이 있으면 main 이 conftest 를 부르기 전에 멈춘다. 겹으로, 빈
  # 설정을 --config-file 로 명시해 검사 루트의 설정을 아예 읽지 않고, 판정에
  # 닿는 키(ignore · namespace · policy · data)도 명시 인자로 덮는다
  # (--ignore '^$' = 아무 파일도 빼지 않음).
  local fails=0 summary="" tests=""
  output="$(conftest test --config-file "${EMPTY_CONFIG}" --parser hcl2 \
    --combine --no-fail --no-color \
    --strict --show-builtin-errors --namespace main --ignore '^$' \
    --policy "${POLICY_DIR}" --data "${marker}" -- "$@" 2>&1)" || {
    echo "${output}" >&2
    die "conftest 실행 실패"
  }
  [[ -n "${output}" ]] || die "conftest 출력이 비었음"
  split_lines "${output}"
  for line in "${LINES[@]}"; do
    if [[ "${line}" == "${FAIL_PREFIX}"* ]]; then
      report "${line#"${FAIL_PREFIX}"}"
      fails=$((fails + 1))
    elif [[ "${line}" =~ ${SUMMARY_PATTERN} && -z "${summary}" ]]; then
      tests="${BASH_REMATCH[1]}"
      summary="${BASH_REMATCH[2]} ${BASH_REMATCH[3]}"
    else
      echo "${output}" >&2
      die "conftest 출력에 모르는 줄: ${line}"
    fi
  done
  [[ -n "${summary}" ]] || die "conftest 출력에 요약 줄이 없음: ${output}"
  # 규칙이 하나도 안 돌았으면(패키지 이름 · deny 철자 실수) 「위반 0」이 아니다.
  [[ "${tests}" -gt 0 ]] || die "정책 규칙이 하나도 실행되지 않음(0 tests)"
  [[ "${summary}" == "${fails} 0" ]] ||
    die "conftest 요약(실패 · 예외 ${summary})이 FAIL 줄 ${fails}개와 맞지 않음"
}

#######################################
# 셸에 남은 CONFTEST_* 설정(다른 프로젝트의 namespace · policy 등)을 지운다 —
# 이 저장소 정책이 조용히 꺼지지 않게 한다.
#######################################
clear_conftest_env() {
  local name
  for name in $(compgen -e); do
    if [[ "${name}" == CONFTEST_* ]]; then
      unset "${name}"
    fi
  done
}

#######################################
# 실제 트리(infra/terraform)의 하위 디렉터리를 루트로 주면 경로 규칙
# (R4 · R11 — 루트 기준 상대 경로)이 조용히 빠지므로 멈춘다. fixture 트리
# (scripts/testdata 아래)와 저장소 밖 트리는 허용한다.
# Arguments:
#   정규화한 루트 절대 경로.
#######################################
check_root() {
  local root="$1" tree
  tree="$(cd -P -- "${SCRIPT_DIR}/.." && pwd)" || die "트리 위치를 못 찾음"
  case "${root}" in
    "${tree}" | "${tree}/scripts/testdata/"*) ;;
    "${tree}/"*) die "하위 디렉터리를 루트로 줄 수 없음(경로 규칙이 빠짐): ${root}" ;;
    *) ;;
  esac
}

#######################################
# R12 모드 표지 파일을 고른다. 루트가 이 트리의 scripts/testdata 아래일 때만
# fixture 이고, 그 밖(실제 트리 · 저장소 밖 트리)은 operational 이다.
# Arguments:
#   정규화한 루트 절대 경로.
# Outputs:
#   표지 파일 절대 경로.
# Returns:
#   트리 위치를 못 찾으면 1.
#######################################
marker_file() {
  local root="$1" tree mode=operational
  tree="$(cd -P -- "${SCRIPT_DIR}/.." && pwd)" || return 1
  case "${root}" in
    "${tree}/scripts/testdata/"*) mode=fixture ;;
    *) ;;
  esac
  echo "${MARKER_DIR}/${mode}/mode.json"
}

main() {
  local entries entry file marker
  local parse_failed=0 configs=0
  local -a candidates=() tf_files=()
  if [[ "$#" -gt 1 ]]; then
    echo "사용: guard.sh [Terraform 트리 루트]" >&2
    exit 2
  fi
  if ! command -v conftest >/dev/null 2>&1; then
    echo "guard: conftest 가 없습니다. 설치: brew install conftest" \
      "(CI 는 0.71.1 고정)" >&2
    exit 2
  fi
  [[ -d "${POLICY_DIR}" ]] || die "정책 디렉터리가 없음: ${POLICY_DIR}"
  [[ -f "${EMPTY_CONFIG}" ]] || die "빈 설정 파일이 없음: ${EMPTY_CONFIG}"
  # 루트로 옮겨 상대 경로로 넘긴다: 정책이 경로(modules/state_bucket/ 등)를
  # 루트 기준으로 본다.
  cd -P -- "${1:-${SCRIPT_DIR}/..}" 2>/dev/null ||
    die "디렉터리가 없음: ${1:-${SCRIPT_DIR}/..}"
  check_root "$(pwd -P)"
  marker="$(marker_file "$(pwd -P)")" || die "트리 위치를 못 찾음"
  clear_conftest_env

  entries="$(list_tf_files | classify_files)" ||
    die "파일 목록 실패: $(pwd)"
  [[ -n "${entries}" ]] || die "검사할 .tf 파일이 없음: $(pwd)"
  split_lines "${entries}"
  # R0: 파서가 읽지 못하거나(JSON) 리뷰 · Terraform 과 보는 것이 갈리는(링크 ·
  # 숨김 · 줄바꿈 이름) 꼴, 그리고 conftest 설정 파일은 두지 않는다.
  for entry in "${LINES[@]}"; do
    case "${entry}" in
      "tf "*) candidates+=("${entry#tf }") ;;
      "config "*)
        report "${entry#config }"
        configs=$((configs + 1))
        ;;
      "r0 "*) report "${entry#r0 }" ;;
      *) die "분류 결과에 모르는 줄: ${entry}" ;;
    esac
  done
  # conftest 는 검사 루트의 설정 파일을 읽어 정책을 내려받기(update)까지
  # 하므로, 설정 파일이 하나라도 있으면 conftest 를 한 번도 부르지 않는다.
  if [[ "${configs}" -gt 0 ]]; then
    echo "guard: conftest 설정 파일이 있어 conftest 를 부르지 않음 —" \
      "위반 ${violations}건" >&2
    exit 1
  fi
  if [[ "${#candidates[@]}" -gt 0 ]]; then
    for file in "${candidates[@]}"; do
      if parses "${file}"; then
        tf_files+=("${file}")
      else
        parse_failed=1
      fi
    done
  fi
  [[ "${parse_failed}" -eq 0 ]] || exit 2
  if [[ "${#tf_files[@]}" -gt 0 ]]; then
    run_policy "${marker}" "${tf_files[@]}"
  fi

  if [[ "${violations}" -ne 0 ]]; then
    echo "guard: 위반 ${violations}건" >&2
    exit 1
  fi
  echo "guard: 위반 0"
}

main "$@"
