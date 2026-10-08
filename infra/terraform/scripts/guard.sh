#!/usr/bin/env bash
#
# Terraform 트리의 금지 규약을 텍스트로 검사한다(README 「guard 규칙」).
#
# 규칙마다 함수 하나다. 위반은 표준 출력에 한 줄씩
# 「guard: <규칙> <파일>:<줄>: <설명>」으로 적는다.
# 검사 전에 주석(`#` · `//` · `/* */`)과 heredoc 본문을 빈 줄로 지운다.
# 문자열과 그 안의 보간(`${…}` · `%{…}`)은 중첩까지 따라가므로, 그 속의
# `#` · `<<` · 따옴표 · 중괄호는 코드로 읽지 않는다. 끝나지 않는 heredoc ·
# 블록 주석 · 문자열, 알아보지 못하는 `<<` 는 검사 불가로 멈춘다.
# 줄 번호는 그대로다. 텍스트 검사라 다른 이름 · 동적 생성으로 우회하는 것은
# 막지 못한다 — 그것은 리뷰가 막는다.
#
# 사용: bash infra/terraform/scripts/guard.sh [Terraform 트리 루트]
#       루트를 주지 않으면 이 스크립트가 든 infra/terraform 이다.
# 종료 코드: 0 위반 0 / 1 위반 있음 / 2 사용법 오류 · 검사 불가(fail-closed)
# 임시 파일 · 프로세스 치환을 쓰지 않는다. 입력은 변수에서 바로 쪼개거나,
# 끝까지 읽는 awk 에 파이프로 넘겨 pipefail 로 실패를 잡는다 — 입력을 만드는
# 쪽이 실패해 빈 입력으로 위반 0 을 내지 않게 한다.

set -uo pipefail

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# 주석과 heredoc 본문을 지운다. 줄 수는 그대로 둔다(지운 줄은 빈 줄).
# 문자열 · 보간은 스택으로 따라간다(S = 문자열, T = 보간). 문자열 · 보간
# 안의 중괄호는 「_」로 바꿔 블록 깊이 계산(R3 · R4 · R11)에 끼지 않게 한다.
# heredoc 종료자는 HCL 식별자(글자 · 숫자 · 밑줄 · 하이픈)이고, 코드 위치의
# `<<` 만 heredoc 으로 본다. 종료 코드 3 = 끝나지 않는 heredoc · 블록 주석 ·
# 줄을 넘는 문자열 · 보간, 보간 속 주석 · `<<`, 알아보지 못하는 `<<`.
# awk 프로그램이라 작은따옴표가 맞다.
# shellcheck disable=SC2016
readonly STRIP_COMMENTS='
function brace(ch) { return (ch == "{" || ch == "}") ? "_" : ch }
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
  code = ""
  n = length(line)
  i = 1
  while (i <= n) {
    c = substr(line, i, 1)
    c2 = substr(line, i, 2)
    c3 = substr(line, i, 3)
    if (in_block) {
      if (c2 == "*/") { in_block = 0; i += 2 } else { i++ }
      continue
    }
    if (sp > 0 && stack[sp] == "S") {
      if (c == "\\") { out = out c brace(substr(line, i + 1, 1)); i += 2; continue }
      if (c == "\"") { sp--; out = out c; i++; continue }
      if (c3 == "$${" || c3 == "%%{") { out = out "___"; i += 3; continue }
      if (c2 == "${" || c2 == "%{") {
        sp++
        stack[sp] = "T"
        depth[sp] = 0
        out = out "__"
        i += 2
        continue
      }
      out = out brace(c)
      i++
      continue
    }
    if (sp > 0 && stack[sp] == "T") {
      if (c == "\"") { sp++; stack[sp] = "S"; out = out c; i++; continue }
      if (c == "{") { depth[sp]++; out = out "_"; i++; continue }
      if (c == "}") {
        if (depth[sp] == 0) { sp-- } else { depth[sp]-- }
        out = out "_"
        i++
        continue
      }
      if (c == "#" || c2 == "//" || c2 == "/*" || c2 == "<<") { bad = 1; exit 3 }
      out = out c
      i++
      continue
    }
    if (c == "\"") { sp = 1; stack[1] = "S"; out = out c; code = code c; i++; continue }
    if (c == "#" || c2 == "//") { break }
    if (c2 == "/*") { in_block = 1; i += 2; continue }
    out = out c
    code = code c
    i++
  }
  if (sp > 0) { bad = 1; exit 3 }
  if (match(code, /<<-?[A-Za-z_][A-Za-z0-9_-]*[[:space:]]*$/)) {
    heredoc = substr(code, RSTART, RLENGTH)
    sub(/^<<-?/, "", heredoc)
    gsub(/[[:space:]]/, "", heredoc)
  } else if (index(code, "<<") > 0) {
    bad = 1
    exit 3
  }
  print out
}
END {
  if (!bad && (heredoc != "" || in_block)) { exit 3 }
}
'

# 한 줄의 문자열 리터럴 내용을 비운다(R3 · R4 공용). 중괄호는 이미
# STRIP_COMMENTS 가 바꿔 두었으므로 깊이 계산에는 영향이 없다.
readonly BLANK_STRINGS='function bare_line(s) {
  gsub(/"([^"\\]|\\.)*"/, "\"\"", s)
  return s
}
'

# 자원 · data 선언의 머리: 키워드 뒤 공백이 없어도, 유형 라벨에 따옴표가
# 없어도 같은 선언이다(`resource"x"` · `resource x y {`).
readonly DECL='^[[:space:]]*(resource|data)[[:space:]]*"?'
readonly LABEL_END='("|[[:space:]]|[{]|$)'

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
#   규칙 이름, 위치(파일:줄), 설명.
#######################################
report() {
  echo "guard: $1 $2: $3"
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
# 디렉터리 아래 *.tf · *.tf.json(심볼릭 링크 포함)을 한 줄에 하나씩 낸다.
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
    -o \( -type f -o -type l \) \( -name '*.tf' -o -name '*.tf.json' \) \
    -print |
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
  awk "${STRIP_COMMENTS}" "${file}" ||
    die "주석 제거 실패(끝나지 않는 heredoc · 블록 주석 · 문자열, 알아보지 못하는 <<): ${file}"
}

#######################################
# 주석을 지운 내용에 awk 검사를 돌려 출력을 낸다. 입력을 넘기는 printf 와
# awk 중 하나라도 실패하면 멈춘다(awk 는 입력을 끝까지 읽는다).
# Arguments:
#   규칙 이름, 파일 경로, 주석을 지운 내용, awk 프로그램, awk -v 인자들.
# Outputs:
#   awk 출력.
#######################################
run_awk() {
  local rule="$1" file="$2" content="$3" program="$4"
  shift 4
  printf '%s\n' "${content}" | awk "$@" "${program}" ||
    die "${rule} 검사 실패: ${file}"
}

#######################################
# 줄 번호 목록의 줄마다 위반을 적는다.
# Arguments:
#   규칙 이름, 파일 경로, 줄 번호 목록(줄마다 하나), 설명.
#######################################
report_lines() {
  local rule="$1" file="$2" lines="$3" message="$4"
  local line
  [[ -n "${lines}" ]] || return 0
  split_lines "${lines}"
  for line in "${LINES[@]}"; do
    report "${rule}" "${file}:${line}" "${message}"
  done
}

#######################################
# 확장 정규식에 맞는 줄마다 위반을 적는다.
# Arguments:
#   규칙 이름, 파일 경로, 주석을 지운 내용, 확장 정규식, 설명.
#######################################
report_matches() {
  local rule="$1" file="$2" content="$3" pattern="$4" message="$5"
  local lines
  # shellcheck disable=SC2016
  lines="$(run_awk "${rule}" "${file}" "${content}" \
    '$0 ~ pattern { print FNR }' -v pattern="${pattern}")" || exit 2
  report_lines "${rule}" "${file}" "${lines}" "${message}"
}

# R0: JSON 구성은 텍스트 검사가 읽지 못하므로 이 트리에는 두지 않는다.
# 심볼릭 링크 .tf 도 두지 않는다(트리 밖 파일을 끌어들이고 리뷰에 안 보인다).
check_r0() {
  local file="$1"
  if [[ -L "${file}" ]]; then
    report R0 "${file}:1" '심볼릭 링크 .tf 금지(일반 파일만)'
    return
  fi
  report R0 "${file}:1" '.tf.json 금지(이 트리는 HCL 만 — guard 가 JSON 을 검사하지 못함)'
}

# R1: 비밀 값 자원 · 비밀 값 data source 는 값을 상태에 남긴다.
check_r1() {
  report_matches R1 "$1" "$2" \
    "${DECL}aws_secretsmanager_secret_version${LABEL_END}" \
    'aws_secretsmanager_secret_version 금지(비밀 값이 상태에 남음)'
}

# R2: KVS 키 자원은 비밀 값을 상태에 남긴다.
check_r2() {
  report_matches R2 "$1" "$2" \
    "${DECL}aws_cloudfrontkeyvaluestore_key" \
    'aws_cloudfrontkeyvaluestore_key* 금지(비밀 값이 상태에 남음)'
}

# R3: aws_security_group 블록 바로 안의 인라인 ingress · egress(블록 · 속성형 ·
# dynamic 블록). 중괄호 깊이를 세어 블록 끝을 찾는다.
check_r3() {
  local file="$1" content="$2"
  local lines
  # shellcheck disable=SC2016
  lines="$(run_awk R3 "${file}" "${content}" "${BLANK_STRINGS}"'
    $0 ~ ("^[[:space:]]*resource[[:space:]]*\"?aws_security_group" end) {
      in_sg = 1
      depth = 0
    }
    in_sg {
      bare = bare_line($0)
      if (depth == 1 &&
          (bare ~ /^[[:space:]]*(ingress|egress)[[:space:]]*[{=]/ ||
           $0 ~ /^[[:space:]]*dynamic[[:space:]]*"?(ingress|egress)("|[[:space:]]|\{)/)) {
        print FNR
      }
      opens = gsub(/\{/, "{", bare)
      closes = gsub(/\}/, "}", bare)
      depth += opens - closes
      if (depth <= 0 && (opens + closes) > 0) { in_sg = 0 }
    }
  ' -v end="${LABEL_END}")" || exit 2
  report_lines R3 "${file}" "${lines}" \
    'aws_security_group 인라인 ingress · egress 금지(별도 규칙 자원으로)'
}

# 파일 하나에서 주어진 유형의 resource 마다 lifecycle 블록 안의
# prevent_destroy = true 여부를 내는 awk 프로그램(-v types=…).
# 자원마다 「ok <유형> <줄>」 또는 「missing <유형> <줄>」.
# shellcheck disable=SC2016
readonly SCAN_PREVENT_DESTROY="${BLANK_STRINGS}"'
  BEGIN {
    n = split(types, list, " ")
    for (k = 1; k <= n; k++) { wanted[list[k]] = 1 }
  }
  {
    bare = bare_line($0)
    if (depth == 0 && match($0, /^[[:space:]]*resource[[:space:]]*"?[A-Za-z0-9_-]+/)) {
      type = substr($0, RSTART, RLENGTH)
      sub(/^[[:space:]]*resource[[:space:]]*"?/, "", type)
      if (type in wanted) {
        in_res = 1
        protected = 0
        start = FNR
        found = type
      }
    }
    if (in_res && depth == 1 &&
        bare ~ /^[[:space:]]*lifecycle[[:space:]]*\{/) {
      in_lc = 1
    }
    # 한 줄 꼴: lifecycle { prevent_destroy = true }
    if (in_res && depth == 1 &&
        bare ~ /^[[:space:]]*lifecycle[[:space:]]*\{[[:space:]]*prevent_destroy[[:space:]]*=[[:space:]]*true[[:space:]]*\}[[:space:]]*$/) {
      protected = 1
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
    if (in_res && depth <= 0 && (opens + closes) > 0) {
      print (protected ? "ok " : "missing ") found " " start
      in_res = 0
    }
  }
'

#######################################
# 디렉터리의 주어진 유형 자원이 모두 prevent_destroy = true 인지 본다.
# 유형마다 자원이 하나 이상 있어야 한다. 디렉터리가 없는 트리(다른 규칙의
# fixture)는 건너뛴다.
# Arguments:
#   규칙 이름, 검사할 디렉터리, 자원 유형 목록(공백 구분).
#######################################
check_prevent_destroy() {
  local rule="$1" dir="$2" types="$3"
  local files file content results result type
  local all=$'\n'
  local -a file_list result_list
  files="$(list_tf_files "${dir}")" || die "파일 목록 실패: ${dir}"
  [[ -n "${files}" ]] || return 0
  split_lines "${files}"
  file_list=("${LINES[@]}")
  for file in "${file_list[@]}"; do
    # .tf.json · 심볼릭 링크는 R0 가 막는다.
    [[ "${file}" == *.tf && ! -L "${file}" ]] || continue
    content="$(strip_comments "${file}")" || exit 2
    results="$(run_awk "${rule}" "${file}" "${content}" \
      "${SCAN_PREVENT_DESTROY}" -v types="${types}")" || exit 2
    [[ -n "${results}" ]] || continue
    all="${all}${results}"$'\n'
    split_lines "${results}"
    result_list=("${LINES[@]}")
    for result in "${result_list[@]}"; do
      [[ "${result}" == missing* ]] || continue
      type="${result#missing }"
      report "${rule}" "${file}:${type##* }" \
        "${type% *} 의 lifecycle 블록에 prevent_destroy = true 없음"
    done
  done
  for type in ${types}; do
    if [[ "${all}" != *$'\n'"ok ${type} "* &&
          "${all}" != *$'\n'"missing ${type} "* ]]; then
      report "${rule}" "${dir}:0" "${type} 자원 없음"
    fi
  done
}

# R4: 상태 버킷은 삭제 방지(prevent_destroy = true)여야 한다.
check_r4() {
  check_prevent_destroy R4 "${ROOT}/modules/state_bucket" aws_s3_bucket
}

# R11: import 한 dev 상자(EC2 · EIP · SG)는 삭제 방지여야 한다. 규칙 자원은
# M12 가 지우므로 대상이 아니다. R5 ~ R10 은 계획의 후속 PR 이 쓴다.
check_r11() {
  check_prevent_destroy R11 "${ROOT}/nonprod/dev" \
    'aws_instance aws_eip aws_security_group'
}

#######################################
# 파일 하나에 파일 단위 규칙(R0 ~ R3)을 적용한다.
# Arguments:
#   파일 경로.
#######################################
check_file() {
  local file="$1"
  local content
  if [[ "${file}" == *.tf.json || -L "${file}" ]]; then
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
  local -a file_list
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
  split_lines "${files}"
  file_list=("${LINES[@]}")
  for file in "${file_list[@]}"; do
    check_file "${file}"
  done
  check_r4
  check_r11

  if [[ "${violations}" -ne 0 ]]; then
    echo "guard: 위반 ${violations}건" >&2
    exit 1
  fi
  echo "guard: 위반 0"
}

main "$@"
