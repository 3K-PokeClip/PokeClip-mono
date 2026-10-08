#!/usr/bin/env bash
#
# Terraform 트리의 금지 규약을 검사한다(README 「guard 규칙」).
#
# 규칙 R1 ~ R11 은 conftest(`--parser hcl2`)로 돌리는 Rego 정책
# (`../policy/guard`)이 판정한다. 이 스크립트는 얇은 실행기다 — 파서가 볼 수
# 없는 R0(`.tf.json` · 심볼릭 링크 `.tf`)를 직접 보고, 파일마다 파싱이 되는지
# 먼저 가린 뒤, 정책을 한 번에(`--combine`) 돌려 종료 코드를 정한다.
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

# `conftest test` 의 결과 줄. --combine 이라 파일 이름 자리가 Combined 다.
readonly FAIL_PREFIX='FAIL - Combined - main - '
# 요약 줄: 「N tests, N passed, N warnings, <실패> failures, <예외> exceptions」.
SUMMARY_PATTERN='^[0-9]+ tests?, [0-9]+ passed, [0-9]+ warnings?, '
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
# 현재 디렉터리(루트) 아래 *.tf · *.tf.json(심볼릭 링크 포함)을 상대 경로로
# 한 줄에 하나씩 낸다. fixture · provider 캐시는 뺀다.
# Outputs:
#   「./」로 시작하는 상대 경로(정렬).
#######################################
list_tf_files() {
  find . \
    \( -path ./scripts/testdata -o -name .terraform \) -prune \
    -o \( -type f -o -type l \) \( -name '*.tf' -o -name '*.tf.json' \) \
    -print |
    LC_ALL=C sort
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
  if ! error="$(conftest parse --parser hcl2 -- "${file}" 2>&1 \
    >/dev/null)"; then
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
#   검사할 상대 경로들.
#######################################
run_policy() {
  local output line
  local fails=0 summary=""
  output="$(conftest test --parser hcl2 --combine --no-fail --no-color \
    --strict --show-builtin-errors --policy "${POLICY_DIR}" -- "$@" 2>&1)" || {
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
      summary="${BASH_REMATCH[1]} ${BASH_REMATCH[2]}"
    else
      echo "${output}" >&2
      die "conftest 출력에 모르는 줄: ${line}"
    fi
  done
  [[ -n "${summary}" ]] || die "conftest 출력에 요약 줄이 없음: ${output}"
  [[ "${summary}" == "${fails} 0" ]] ||
    die "conftest 요약(실패 · 예외 ${summary})이 FAIL 줄 ${fails}개와 맞지 않음"
}

main() {
  local files file
  local parse_failed=0
  local -a tf_files=()
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
  # 루트로 옮겨 상대 경로로 넘긴다: 정책이 경로(modules/state_bucket/ 등)를
  # 루트 기준으로 본다.
  cd -P -- "${1:-${SCRIPT_DIR}/..}" 2>/dev/null ||
    die "디렉터리가 없음: ${1:-${SCRIPT_DIR}/..}"

  files="$(list_tf_files)" || die "파일 목록 실패: $(pwd)"
  [[ -n "${files}" ]] || die "검사할 .tf 파일이 없음: $(pwd)"
  split_lines "${files}"
  for file in "${LINES[@]}"; do
    file="${file#./}"
    # R0: 파서가 읽지 못하거나(JSON) 리뷰에 안 보이는(트리 밖 파일을 끌어
    # 들이는 링크) 꼴은 두지 않는다.
    if [[ -L "${file}" ]]; then
      report "R0 ${file}: 심볼릭 링크 .tf 금지(일반 파일만)"
    elif [[ "${file}" == *.tf.json ]]; then
      report "R0 ${file}: .tf.json 금지(이 트리는 HCL 만 — 정책이 JSON 구성을 읽지 않음)"
    elif parses "${file}"; then
      tf_files+=("${file}")
    else
      parse_failed=1
    fi
  done
  [[ "${parse_failed}" -eq 0 ]] || exit 2
  if [[ "${#tf_files[@]}" -gt 0 ]]; then
    run_policy "${tf_files[@]}"
  fi

  if [[ "${violations}" -ne 0 ]]; then
    echo "guard: 위반 ${violations}건" >&2
    exit 1
  fi
  echo "guard: 위반 0"
}

main "$@"
