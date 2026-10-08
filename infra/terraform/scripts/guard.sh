#!/usr/bin/env bash
#
# Terraform 트리의 금지 규약을 텍스트로 검사한다(README 「guard 규칙」).
#
# 규칙마다 함수 하나다. 위반은 표준 출력에 한 줄씩
# 「guard: <규칙> <파일>:<줄>: <설명>」으로 적는다.
# 텍스트 검사라 다른 이름 · 동적 생성으로 우회하는 것은 막지 못한다 — 그것은
# 리뷰가 막는다.
#
# 사용: bash infra/terraform/scripts/guard.sh [Terraform 트리 루트]
#       루트를 주지 않으면 이 스크립트가 든 infra/terraform 이다.
# 종료 코드: 0 위반 0 / 1 위반 있음 / 2 사용법 오류

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

violations=0

#######################################
# 위반 한 줄을 적고 센다.
# Globals:
#   violations
# Arguments:
#   규칙 이름, 위치(파일:줄), 설명.
#######################################
report() {
  echo "guard: $1 $2: $3"
  violations=$((violations + 1))
}

#######################################
# 루트 아래 *.tf 를 한 줄에 하나씩 낸다. fixture · provider 캐시는 뺀다.
# Arguments:
#   검사할 디렉터리.
# Outputs:
#   파일 경로(정렬).
#######################################
list_tf_files() {
  local dir="$1"
  [[ -d "${dir}" ]] || return 0
  find "${dir}" \
    \( -path "${ROOT}/scripts/testdata" -o -name .terraform \) -prune \
    -o -type f -name '*.tf' -print | LC_ALL=C sort
}

#######################################
# 정규식에 맞는 줄마다 위반을 적는다.
# Arguments:
#   규칙 이름, 검사할 디렉터리, 확장 정규식, 설명.
#######################################
report_matches() {
  local rule="$1" dir="$2" pattern="$3" message="$4"
  local file line
  while IFS= read -r file; do
    while IFS= read -r line; do
      report "${rule}" "${file}:${line%%:*}" "${message}"
    done < <(grep -nE "${pattern}" "${file}")
  done < <(list_tf_files "${dir}")
}

# R1: 비밀 값 자원 · 비밀 값 data source 는 값을 상태에 남긴다.
check_r1() {
  report_matches R1 "${ROOT}" \
    '^[[:space:]]*(resource|data)[[:space:]]+"aws_secretsmanager_secret_version"' \
    'aws_secretsmanager_secret_version 금지(비밀 값이 상태에 남음)'
}

# R2: KVS 키 자원은 비밀 값을 상태에 남긴다.
check_r2() {
  report_matches R2 "${ROOT}" \
    '^[[:space:]]*(resource|data)[[:space:]]+"aws_cloudfrontkeyvaluestore_key' \
    'aws_cloudfrontkeyvaluestore_key* 금지(비밀 값이 상태에 남음)'
}

# R3: aws_security_group 블록 안의 인라인 ingress · egress 블록.
# 중괄호 깊이를 세어 블록 끝을 찾는다(문자열 안 중괄호는 세지 않는 한계).
check_r3() {
  local file line
  while IFS= read -r file; do
    while IFS= read -r line; do
      report R3 "${file}:${line}" \
        'aws_security_group 인라인 ingress · egress 금지(별도 규칙 자원으로)'
    done < <(awk '
      /^[[:space:]]*resource[[:space:]]+"aws_security_group"[[:space:]]/ {
        in_sg = 1
        depth = 0
      }
      in_sg && /^[[:space:]]*(ingress|egress)[[:space:]]*\{/ { print FNR }
      in_sg {
        opens = gsub(/\{/, "{")
        closes = gsub(/\}/, "}")
        depth += opens - closes
        if (depth <= 0 && (opens + closes) > 0) { in_sg = 0 }
      }
    ' "${file}")
  done < <(list_tf_files "${ROOT}")
}

# R4: 상태 버킷 모듈에는 prevent_destroy = true 가 있어야 한다.
check_r4() {
  local dir="${ROOT}/modules/state_bucket"
  local file
  local found=0
  local any=0
  while IFS= read -r file; do
    any=1
    if grep -qE '^[[:space:]]*prevent_destroy[[:space:]]*=[[:space:]]*true' \
      "${file}"; then
      found=1
    fi
  done < <(list_tf_files "${dir}")
  if [[ "${any}" -eq 1 && "${found}" -eq 0 ]]; then
    report R4 "${dir}:0" 'prevent_destroy = true 없음'
  fi
}

main() {
  if [[ "$#" -gt 1 ]]; then
    echo "사용: guard.sh [Terraform 트리 루트]" >&2
    exit 2
  fi
  ROOT="${1:-$(cd -- "${SCRIPT_DIR}/.." && pwd)}"
  if [[ ! -d "${ROOT}" ]]; then
    echo "guard: 디렉터리가 없음: ${ROOT}" >&2
    exit 2
  fi
  readonly ROOT

  check_r1
  check_r2
  check_r3
  check_r4

  if [[ "${violations}" -ne 0 ]]; then
    echo "guard: 위반 ${violations}건" >&2
    exit 1
  fi
  echo "guard: 위반 0"
}

main "$@"
