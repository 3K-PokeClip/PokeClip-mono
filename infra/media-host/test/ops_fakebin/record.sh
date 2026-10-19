# shellcheck shell=bash
#
# 운영 확인 스크립트 시험(c5)의 가짜 명령이 함께 쓰는 기록 부품 — 받은 argv 와
# 환경을 <이름>.<n>.argv · <이름>.<n>.env 에 남긴다. 번호는 noclobber 로 파일을
# 먼저 만들어 잡는다 — 배경에서 도는 가짜(송출기)와 다른 가짜가 같은 번호를 잡지
# 않게.
#
# 가짜 명령이 source 한다(경로는 PCBENCH_FAKE_LIB). bash 3.2 에서도 돈다.

# 부르는 가짜 명령이 읽는다.
# shellcheck disable=SC2034
fake_record_n=""

#######################################
# argv · 환경을 기록한다.
# Globals:
#   PCBENCH_FAKE_LOG_DIR, fake_record_n
# Arguments:
#   이름, 받은 argv ….
#######################################
fake::record() {
  local name="$1" dir="${PCBENCH_FAKE_LOG_DIR:?가짜 명령: PCBENCH_FAKE_LOG_DIR 없음}"
  local n=1
  shift
  until (set -C; : >"${dir}/${name}.${n}.argv") 2>/dev/null; do
    n=$((n + 1))
  done
  printf '%s\n' "$@" >"${dir}/${name}.${n}.argv"
  env >"${dir}/${name}.${n}.env"
  # shellcheck disable=SC2034 # 부르는 가짜 명령이 읽는다.
  fake_record_n="${n}"
}

#######################################
# 모르는 호출을 알리고 끝낸다.
# Arguments:
#   가짜 명령 이름.
#######################################
fake::unknown() {
  printf '가짜 %s: 모르는 호출\n' "$1" >&2
  exit 2
}
