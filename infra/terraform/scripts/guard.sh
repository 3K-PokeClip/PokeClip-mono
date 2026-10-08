#!/usr/bin/env bash
#
# Terraform 트리의 금지 규약을 텍스트로 검사한다(README 「guard 규칙」).
#
# 규칙마다 함수 하나다. 위반은 표준 출력에 한 줄씩
# 「guard: <규칙> <파일>:<줄>: <설명>」으로 적는다.
# 검사 전에 주석(`#` · `//` · `/* */`)과 heredoc 본문을 빈 줄로 지운다.
# 줄 번호는 그대로다. 텍스트 검사라 다른 이름 · 동적 생성으로 우회하는 것은
# 막지 못한다 — 그것은 리뷰가 막는다.
#
# 사용: bash infra/terraform/scripts/guard.sh [Terraform 트리 루트]
#       루트를 주지 않으면 이 스크립트가 든 infra/terraform 이다.
# 종료 코드: 0 위반 0 / 1 위반 있음 / 2 사용법 오류 · 검사 불가(fail-closed)

set -uo pipefail

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# 주석과 heredoc 본문을 지운다. 문자열("...") 안의 # · // 는 남긴다.
# 줄 수는 그대로 둔다(지운 줄은 빈 줄). awk 프로그램이라 작은따옴표가 맞다.
# shellcheck disable=SC2016
readonly STRIP_COMMENTS='
{
  line = $0
  if (heredoc != "") {
    marker = line
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", marker)
    if (marker == heredoc) { heredoc = "" }
    print ""
    next
  }
  out = ""
  in_str = 0
  n = length(line)
  i = 1
  while (i <= n) {
    c = substr(line, i, 1)
    c2 = substr(line, i, 2)
    if (in_block) {
      if (c2 == "*/") { in_block = 0; i += 2 } else { i++ }
      continue
    }
    if (in_str) {
      out = out c
      if (c == "\\") { out = out substr(line, i + 1, 1); i += 2; continue }
      if (c == "\"") { in_str = 0 }
      i++
      continue
    }
    if (c == "\"") { in_str = 1; out = out c; i++; continue }
    if (c == "#" || c2 == "//") { break }
    if (c2 == "/*") { in_block = 1; i += 2; continue }
    out = out c
    i++
  }
  if (match(out, /<<-?[A-Za-z_][A-Za-z0-9_]*[[:space:]]*$/)) {
    heredoc = substr(out, RSTART, RLENGTH)
    sub(/^<<-?/, "", heredoc)
    gsub(/[[:space:]]/, "", heredoc)
  }
  print out
}
'

# 한 줄의 문자열 리터럴 내용을 비워 중괄호를 세지 않게 한다(R3 · R4 공용).
readonly BLANK_STRINGS='function bare_line(s) {
  gsub(/"([^"\\]|\\.)*"/, "\"\"", s)
  return s
}
'

violations=0

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
#   규칙 이름, 위치(파일:줄), 설명.
#######################################
report() {
  echo "guard: $1 $2: $3"
  violations=$((violations + 1))
}

#######################################
# 디렉터리 아래 *.tf · *.tf.json 을 한 줄에 하나씩 낸다.
# fixture · provider 캐시는 뺀다.
# Globals:
#   ROOT
# Arguments:
#   검사할 디렉터리(ROOT 아래 절대 경로).
# Outputs:
#   파일 경로(정렬).
#######################################
list_tf_files() {
  local dir="$1"
  [[ -d "${dir}" ]] || return 0
  find "${dir}" \
    \( -path "${ROOT}/scripts/testdata" -o -name .terraform \) -prune \
    -o -type f \( -name '*.tf' -o -name '*.tf.json' \) -print |
    LC_ALL=C sort
}

#######################################
# 파일의 주석 · heredoc 을 지운 내용을 낸다. 읽지 못하면 멈춘다.
# Arguments:
#   파일 경로.
# Outputs:
#   지운 내용(줄 번호 유지).
#######################################
strip_comments() {
  local file="$1"
  [[ -r "${file}" ]] || die "읽을 수 없음: ${file}"
  awk "${STRIP_COMMENTS}" "${file}" || die "주석 제거 실패: ${file}"
}

#######################################
# 정규식에 맞는 줄마다 위반을 적는다.
# Arguments:
#   규칙 이름, 파일 경로, 주석을 지운 내용, 확장 정규식, 설명.
#######################################
report_matches() {
  local rule="$1" file="$2" content="$3" pattern="$4" message="$5"
  local matches status line
  matches="$(grep -nE -- "${pattern}" <<<"${content}")"
  status=$?
  [[ "${status}" -le 1 ]] || die "grep 실패(${status}): ${file}"
  [[ -n "${matches}" ]] || return 0
  while IFS= read -r line; do
    report "${rule}" "${file}:${line%%:*}" "${message}"
  done <<<"${matches}"
}

# R0: JSON 구성은 텍스트 검사가 읽지 못하므로 이 트리에는 두지 않는다.
check_r0() {
  report R0 "$1:1" '.tf.json 금지(이 트리는 HCL 만 — guard 가 JSON 을 검사하지 못함)'
}

# R1: 비밀 값 자원 · 비밀 값 data source 는 값을 상태에 남긴다.
check_r1() {
  report_matches R1 "$1" "$2" \
    '^[[:space:]]*(resource|data)[[:space:]]+"aws_secretsmanager_secret_version"' \
    'aws_secretsmanager_secret_version 금지(비밀 값이 상태에 남음)'
}

# R2: KVS 키 자원은 비밀 값을 상태에 남긴다.
check_r2() {
  report_matches R2 "$1" "$2" \
    '^[[:space:]]*(resource|data)[[:space:]]+"aws_cloudfrontkeyvaluestore_key' \
    'aws_cloudfrontkeyvaluestore_key* 금지(비밀 값이 상태에 남음)'
}

# R3: aws_security_group 블록 바로 안의 인라인 ingress · egress(블록 · 속성형).
# 중괄호 깊이를 세어 블록 끝을 찾는다. 주석 · heredoc · 문자열 속 중괄호는
# 세지 않는다.
check_r3() {
  local file="$1" content="$2"
  local lines line
  lines="$(awk "${BLANK_STRINGS}"'
    /^[[:space:]]*resource[[:space:]]+"aws_security_group"[[:space:]]/ {
      in_sg = 1
      depth = 0
    }
    in_sg {
      bare = bare_line($0)
      if (depth == 1 &&
          bare ~ /^[[:space:]]*(ingress|egress)[[:space:]]*[{=]/) {
        print FNR
      }
      opens = gsub(/\{/, "{", bare)
      closes = gsub(/\}/, "}", bare)
      depth += opens - closes
      if (depth <= 0 && (opens + closes) > 0) { in_sg = 0 }
    }
  ' <<<"${content}")" || die "R3 검사 실패: ${file}"
  [[ -n "${lines}" ]] || return 0
  while IFS= read -r line; do
    report R3 "${file}:${line}" \
      'aws_security_group 인라인 ingress · egress 금지(별도 규칙 자원으로)'
  done <<<"${lines}"
}

#######################################
# 파일 하나의 aws_s3_bucket 자원마다 lifecycle 블록 안의
# prevent_destroy = true 여부를 낸다.
# Arguments:
#   주석을 지운 내용(표준 입력).
# Outputs:
#   자원마다 「ok <줄>」 또는 「missing <줄>」.
#######################################
scan_bucket_lifecycle() {
  awk "${BLANK_STRINGS}"'
    {
      bare = bare_line($0)
      if (depth == 0 &&
          $0 ~ /^[[:space:]]*resource[[:space:]]+"aws_s3_bucket"[[:space:]]/) {
        in_bucket = 1
        protected = 0
        start = FNR
      }
      if (in_bucket && depth == 1 &&
          bare ~ /^[[:space:]]*lifecycle[[:space:]]*\{/) {
        in_lc = 1
      }
      if (in_lc && depth == 2 &&
          bare ~ /^[[:space:]]*prevent_destroy[[:space:]]*=/ &&
          bare ~ /=[[:space:]]*true[[:space:]]*$/) {
        protected = 1
      }
      opens = gsub(/\{/, "{", bare)
      closes = gsub(/\}/, "}", bare)
      depth += opens - closes
      if (in_lc && depth <= 1) { in_lc = 0 }
      if (in_bucket && depth <= 0 && (opens + closes) > 0) {
        print (protected ? "ok " : "missing ") start
        in_bucket = 0
      }
    }
  '
}

# R4: 상태 버킷 모듈의 aws_s3_bucket 자원마다 lifecycle 블록 안에
# prevent_destroy = true 가 있어야 한다. 모듈이 없는 트리(fixture)는 건너뛴다.
check_r4() {
  local dir="${ROOT}/modules/state_bucket"
  local files file content results result
  local buckets=0
  files="$(list_tf_files "${dir}")" || die "파일 목록 실패: ${dir}"
  [[ -n "${files}" ]] || return 0
  while IFS= read -r file; do
    [[ "${file}" == *.tf ]] || continue
    content="$(strip_comments "${file}")" || exit 2
    results="$(scan_bucket_lifecycle <<<"${content}")" ||
      die "R4 검사 실패: ${file}"
    [[ -n "${results}" ]] || continue
    while IFS= read -r result; do
      buckets=$((buckets + 1))
      if [[ "${result}" == missing* ]]; then
        report R4 "${file}:${result#missing }" \
          '상태 버킷 lifecycle 블록에 prevent_destroy = true 없음'
      fi
    done <<<"${results}"
  done <<<"${files}"
  if [[ "${buckets}" -eq 0 ]]; then
    report R4 "${dir}:0" 'aws_s3_bucket 자원 없음'
  fi
}

#######################################
# 파일 하나에 파일 단위 규칙(R0 ~ R3)을 적용한다.
# Arguments:
#   파일 경로.
#######################################
check_file() {
  local file="$1"
  local content
  if [[ "${file}" == *.tf.json ]]; then
    check_r0 "${file}"
    return
  fi
  content="$(strip_comments "${file}")" || exit 2
  check_r1 "${file}" "${content}"
  check_r2 "${file}" "${content}"
  check_r3 "${file}" "${content}"
}

main() {
  local files file
  if [[ "$#" -gt 1 ]]; then
    echo "사용: guard.sh [Terraform 트리 루트]" >&2
    exit 2
  fi
  # 절대 경로로 정규화한다: 끝 슬래시 · 「-」로 시작하는 인자를 없앤다.
  ROOT="$(cd -P -- "${1:-${SCRIPT_DIR}/..}" 2>/dev/null && pwd)" ||
    die "디렉터리가 없음: ${1:-${SCRIPT_DIR}/..}"
  readonly ROOT

  files="$(list_tf_files "${ROOT}")" || die "파일 목록 실패: ${ROOT}"
  [[ -n "${files}" ]] || die "검사할 .tf 파일이 없음: ${ROOT}"
  while IFS= read -r file; do
    check_file "${file}"
  done <<<"${files}"
  check_r4

  if [[ "${violations}" -ne 0 ]]; then
    echo "guard: 위반 ${violations}건" >&2
    exit 1
  fi
  echo "guard: 위반 0"
}

main "$@"
