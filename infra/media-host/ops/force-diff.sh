#!/usr/bin/env bash
#
# force 수동 복구 대조 — kty 로컬에서 돈다(상자에 설치하지 않는다). 설계 5-3
# force 6 의 「A − B(송출 중) − 종료 확인 = ∅ 이면 복구 완료」 를 명령 하나로
# 고정한다.
#
# 사용: force-diff.sh <A> <B> <ended> <A 개수> <B 개수>
#   A      force 1 에서 A 상자의 paths-list.sh 표준 출력(force-<시각>-A.txt —
#          그 실행이 종료 0 일 때만 저장한다).
#   B      force 4 뒤 B 상자에서 같은 스크립트의 표준 출력(force-<시각>-B.txt).
#   ended  종료 확인 기록(force-<시각>-ended.txt) — 탭 구분 3열
#          「ingestStreamId<TAB>확인 시각<TAB>확인 경로」, 사람이 확인 순서대로
#          덧붙인다.
#   A 개수 · B 개수
#          그 두 실행의 표준 오류 「PATHS LIST <n>」 의 n. 파일의 ID 줄 수와
#          다르면(SSM 출력 한도 등으로 목록이 잘림) 대조하지 않는다.
#
# 처리: 세 입력의 첫 열만(cut -f1) 빈 줄을 빼고 C 정렬 · 중복 제거한 뒤
# comm -23 A B 로 미복귀 목록, 다시 comm -23 미복귀 ended 로 남은 목록을 만든다.
# 경로 이름 글자 밖의 줄(실패 판정 줄 · CR · 공백)이 있으면 그 입력을 거부한다
# — 남은 목록을 지어내지 않는다. A 가 비면(ID 0개) 대조할 것이 없으므로
# 거부한다 — 실패한 조회를 저장한 빈 파일을 「복구 완료」 로 읽지 않게.
#
# 출력(표준 출력):
#   FORCE REMAINING <n>            다음 줄부터 남은 ID(연락 대상). n 이 0 이면
#                                  복구 완료.
#   FORCE FAIL input <A|B|ended>   입력 파일 없음 · 못 읽음 · 꼴 밖의 줄.
#   FORCE FAIL count <A|B>         ID 줄 수가 개수 인자와 다름.
#   FORCE FAIL empty A             A 에 ID 가 없음.
#   FORCE FAIL usage               인자가 다섯이 아님 · 개수가 숫자가 아님.
# 종료 코드: 0 대조함 / 1 입력 · 개수 거부 / 2 인자 오류.

set -euo pipefail
umask 077
# sort · comm 이 같은 순서를 쓰게 한다.
export LC_ALL=C

readonly ID_PATTERN='^[-0-9A-Za-z_./~:]+$'
readonly INPUT_NAMES=(A B ended)
readonly COUNT_PATTERN='^(0|[1-9][0-9]{0,5})$'

scratch=""

#######################################
# 스크래치를 지운다(EXIT 트랩).
# Globals:
#   scratch
#######################################
remove_scratch() {
  if [[ -n "${scratch}" ]]; then
    rm -rf -- "${scratch}"
  fi
}

#######################################
# 입력 하나를 첫 열 · 빈 줄 제거 · 정렬 · 중복 제거한 목록으로 만든다. 정렬
# 전 ID 줄 목록은 <쓸 파일>.ids 에 남는다.
# Arguments:
#   입력 파일, 쓸 파일.
# Returns:
#   0 만듦 / 1 파일 없음 · 못 읽음 · 꼴 밖의 줄 · grep 오류.
#######################################
normalize() {
  local src="$1" out="$2" status=0
  [[ -f "${src}" && -r "${src}" ]] || return 1
  cut -f1 -- "${src}" | awk 'length($0) > 0' >"${out}.ids" || return 1
  # 꼴 밖의 줄이 없으면 grep 은 종료 1 이다. 0(있음)과 2 이상(오류)은 모두
  # 거부한다.
  grep -q -v -E -- "${ID_PATTERN}" "${out}.ids" || status=$?
  (( status == 1 )) || return 1
  sort -u -- "${out}.ids" >"${out}"
}

#######################################
# ID 줄 수(정렬 전)를 낸다.
# Arguments:
#   normalize 가 쓴 파일.
#######################################
id_lines() {
  awk 'END { print NR }' "$1.ids"
}

main() {
  local inputs=("$@") i remaining count=0
  if (( $# != 5 )) || [[ ! "$4" =~ ${COUNT_PATTERN} \
    || ! "$5" =~ ${COUNT_PATTERN} ]]; then
    printf 'FORCE FAIL usage\n'
    exit 2
  fi
  trap remove_scratch EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/pokeclip-force-diff.XXXXXX")"
  for (( i = 0; i < 3; i++ )); do
    if ! normalize "${inputs[i]}" "${scratch}/${INPUT_NAMES[i]}" 2>/dev/null
    then
      printf 'FORCE FAIL input %s\n' "${INPUT_NAMES[i]}"
      exit 1
    fi
  done
  if [[ "$(id_lines "${scratch}/A")" != "$4" ]]; then
    printf 'FORCE FAIL count A\n'
    exit 1
  fi
  if [[ "$(id_lines "${scratch}/B")" != "$5" ]]; then
    printf 'FORCE FAIL count B\n'
    exit 1
  fi
  if [[ ! -s "${scratch}/A" ]]; then
    printf 'FORCE FAIL empty A\n'
    exit 1
  fi
  comm -23 -- "${scratch}/A" "${scratch}/B" >"${scratch}/missing"
  remaining="$(comm -23 -- "${scratch}/missing" "${scratch}/ended")"
  if [[ -n "${remaining}" ]]; then
    count="$(printf '%s\n' "${remaining}" | awk 'END { print NR }')"
  fi
  printf 'FORCE REMAINING %d\n' "${count}"
  if [[ -n "${remaining}" ]]; then
    printf '%s\n' "${remaining}"
  fi
}

main "$@"
