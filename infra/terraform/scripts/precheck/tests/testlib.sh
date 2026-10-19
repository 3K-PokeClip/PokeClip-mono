# shellcheck shell=bash
#
# 단계 직전 점검 시험(*_test.sh)이 함께 쓰는 부품 — 임시 디렉터리 뿌리 ·
# 실패 집계 · 사본 트리 · 명령 실행(표준 출력 · 표준 오류 · 종료 코드 —
# 제한 시간을 둔 실행 포함).
#
# 시험은 실제 $HOME 을 건드리지 않는다. 사례마다 임시 디렉터리를 만들어
# HOME 으로 주고, 가짜 aws(fakebin/aws)를 PATH 앞에 둔다. 가짜 aws 가 실행
# 가능하지 않으면 시작하지 않고, 시험 명령에는 부르는 쪽의 AWS_* 를 하나도
# 넘기지 않는다(설정 · 자격 증명 파일 /dev/null · 메타데이터 조회 끔) — 실행
# 비트가 빠져 진짜 aws 가 불려도 자격 증명이 없게. 임시 디렉터리는
# 모두 한 뿌리($TMPDIR/precheck_test.*) 아래에 만들고, 끝날 때와 중단 신호
# (INT · TERM)에 뿌리를 통째로 지운다. 시험이 SIGKILL 로 죽으면 트랩이 돌지
# 않아 0000 · 0600 디렉터리가 든 뿌리가 $TMPDIR/precheck_test.* 에 남을 수
# 있다 — 지우기 전에 chmod -R u+rwx 로 권한을 되돌린다.
#
# 가짜 계정 ID 는 12자리, 가짜 사용자 이름은 임시 경로 · 저장소 경로에
# 나올 수 없는 문자열이다 — 유출 단언(사례 5)이 경로 때문에 거짓 양성을 내지
# 않게.
#
# 시험(이 파일 · *_test.sh)은 점검 도구와 달리 프로세스 치환 · mktemp 를
# 쓴다. 다루는 것은 고정 입력 · 가짜 값뿐이고
# 모두 TMPDIR 아래 시험 뿌리 안이다. bash 는 프로세스 치환을 /dev/fd 로
# 잇고(임시 파일 없음), 생산자가 통째로 실패해 입력이 비면 그 루프의
# 단언(행 수 · 조회 수 · 필수 이름 · 디렉터리 있음)이 실패한다. mktemp 는
# 시험 뿌리와 사례 디렉터리(임시 HOME)를 만든다. here-string · here-doc 은
# 쓰지 않는다 — 임시 파일을 만들지 못해 읽기가 실패하면 「없음」 단언이
# 거짓으로 통과할 수 있어서다.

TESTS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TESTS_DIR
PRECHECK_DIR="$(cd -- "${TESTS_DIR}/.." && pwd)"
readonly PRECHECK_DIR
readonly FAKEBIN="${TESTS_DIR}/fakebin"
readonly FAKE_ACCOUNT='731942086655'
readonly FAKE_USER='zqx-fake-operator'
# testlib::run_limited 가 제한 시간을 넘긴 실행에 남기는 종료 코드(GNU
# timeout 과 같은 값).
readonly TIMED_OUT=124

failures=0
tmp_root=""
# testlib::run 이 남기는 마지막 실행의 결과 파일 접두와 종료 코드(부르는
# 시험이 읽는다).
run_out=""
# shellcheck disable=SC2034
run_status=0

#######################################
# 임시 디렉터리 뿌리를 지운다(EXIT 트랩 — INT · TERM 도 exit 로 여기 온다).
# 시험이 권한을 뺀 디렉터리(검색 불가 승인 디렉터리 변조)도 지워지게, 먼저
# 뿌리 아래 권한을 본인 읽기 · 쓰기 · 검색으로 되돌린다.
# Globals:
#   tmp_root
#######################################
testlib::remove_tmp_root() {
  if [[ -n "${tmp_root}" ]]; then
    chmod -R u+rwx "${tmp_root}" 2>/dev/null
    rm -rf -- "${tmp_root}"
  fi
}

#######################################
# 가짜 aws 가 실행 가능하고, PATH 앞에 두면 그것으로 풀리는지 본다.
# Globals:
#   PATH
# Arguments:
#   가짜 aws 가 든 디렉터리.
# Returns:
#   쓸 수 있으면 0.
#######################################
testlib::require_fakebin() {
  local dir="$1" resolved
  # command -v 는 실행 가능한 파일만 찾는다 — 실행 비트가 빠졌으면 다른 aws
  # 로 풀리거나 찾지 못한다.
  resolved="$(PATH="${dir}:${PATH}" command -v aws)" || return 1
  [[ "${resolved}" == "${dir}/aws" ]]
}

#######################################
# 트랩을 걸고 임시 디렉터리 뿌리를 만든다. 실패하면 시험 전체를 멈춘다.
# Globals:
#   FAKEBIN, tmp_root, TMPDIR
# Arguments:
#   시험 이름(오류 문구용).
#######################################
testlib::init() {
  if ! testlib::require_fakebin "${FAKEBIN}"; then
    # 저장소의 절대 경로(로컬 계정 이름)는 적지 않는다.
    printf '%s: 가짜 aws(precheck/tests/fakebin/aws)가 실행 가능하지 않음\n' \
      "$1" >&2
    exit 1
  fi
  trap testlib::remove_tmp_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/precheck_test.XXXXXX")" || {
    printf '%s: 임시 디렉터리를 만들지 못함\n' "$1" >&2
    exit 1
  }
  # 뿌리를 정규화해 둔다(macOS 의 /var → /private/var) — 증거 루트와 같은
  # 꼴의 경로로 비교하려고.
  tmp_root="$(cd -P -- "${tmp_root}" && pwd -P)" || exit 1
}

#######################################
# 사례 하나의 작업 디렉터리를 만든다 — home(HOME) · in(입력) · out(출력).
# Globals:
#   tmp_root
# Outputs:
#   새 디렉터리 경로.
#######################################
testlib::new_case() {
  local dir
  dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" || return 1
  mkdir -p -- "${dir}/home" "${dir}/in" "${dir}/out" || return 1
  printf '%s\n' "${dir}"
}

#######################################
# 시험 하나의 실패를 알리고 센다.
# Globals:
#   failures
# Arguments:
#   실패 설명.
#######################################
testlib::fail() {
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

#######################################
# 시험 하나의 통과를 적는다.
# Arguments:
#   통과 설명.
#######################################
testlib::ok() {
  printf 'ok: %s\n' "$*"
}

#######################################
# 집계를 적고 끝낸다.
# Globals:
#   failures
# Arguments:
#   시험 이름.
#######################################
testlib::finish() {
  if [[ "${failures}" -ne 0 ]]; then
    printf '%s: %s건 실패\n' "$1" "${failures}" >&2
    exit 1
  fi
  printf '%s: 전부 통과\n' "$1"
}

#######################################
# 점검 스크립트 사본 트리를 만든다(데이터는 비워 둔다 — 시험이 고정 입력을
# 넣는다). 스크립트는 자기 위치의 data/ 를 읽으므로 사본은 사본의 data/ 를
# 읽는다.
# Globals:
#   PRECHECK_DIR
# Arguments:
#   만들 디렉터리.
# Returns:
#   복사하지 못하면 1.
#######################################
testlib::make_tree() {
  local dest="$1"
  mkdir -p -- "${dest}/lib" "${dest}/data/stage_actions" || return 1
  cp -- "${PRECHECK_DIR}/scp-recheck.sh" "${PRECHECK_DIR}/events.sh" \
    "${dest}/" || return 1
  cp -- "${PRECHECK_DIR}/lib/evidence.sh" "${dest}/lib/" || return 1
}

#######################################
# 2-3 판정 래퍼까지 든 사본 트리를 만든다 — testlib::make_tree 에 더해
# principal-check.sh · lib/principal_*.sh · data/principal_sim/(저장소에
# 있는 파일은 복사, 시험이 고정 입력으로 덮는다). 래퍼가 없으면 실패한다.
# Globals:
#   PRECHECK_DIR
# Arguments:
#   만들 디렉터리.
# Returns:
#   복사하지 못하면 1.
#######################################
testlib::make_principal_tree() {
  local dest="$1" file
  testlib::make_tree "${dest}" || return 1
  mkdir -p -- "${dest}/data/principal_sim" || return 1
  cp -- "${PRECHECK_DIR}/principal-check.sh" "${dest}/" || return 1
  for file in "${PRECHECK_DIR}"/lib/principal_*.sh \
    "${PRECHECK_DIR}"/data/principal_sim/*; do
    [[ -e "${file}" ]] || continue
    if [[ "${file}" == */lib/* ]]; then
      cp -- "${file}" "${dest}/lib/" || return 1
    else
      cp -- "${file}" "${dest}/data/principal_sim/" || return 1
    fi
  done
}

#######################################
# 사례 디렉터리의 home 을 HOME 으로, 가짜 aws 를 PATH 앞에 두고 명령을
# 돌린다. 부르는 쪽 환경의 AWS_* 는 모두 지우고 설정 · 자격 증명 파일은
# /dev/null, 인스턴스 메타데이터 조회는 끈다. 표준 출력 · 표준 오류는
# <사례>/out/<이름>.stdout · .stderr 에, 가짜 aws argv 는
# <사례>/out/aws.log 에 쌓인다. FAKE_AWS_* 의 나머지는 부르는 쪽이 넘긴다.
# Globals:
#   run_out, FAKEBIN, PATH, FAKE_ACCOUNT, FAKE_USER, run_status
# Arguments:
#   사례 디렉터리, 결과 이름, 명령과 인자.
#######################################
testlib::run() {
  local case_dir="$1" name="$2" variable
  local -a scrub=()
  shift 2
  run_out="${case_dir}/out/${name}"
  for variable in $(compgen -e); do
    if [[ "${variable}" == AWS_* ]]; then
      scrub+=(-u "${variable}")
    fi
  done
  HOME="${case_dir}/home" PATH="${FAKEBIN}:${PATH}" \
    env ${scrub[@]+"${scrub[@]}"} AWS_CONFIG_FILE=/dev/null \
    AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true \
    FAKE_AWS_LOG="${case_dir}/out/aws.log" FAKE_AWS_ACCOUNT="${FAKE_ACCOUNT}" \
    FAKE_AWS_USER="${FAKE_USER}" \
    "$@" >"${run_out}.stdout" 2>"${run_out}.stderr"
  # shellcheck disable=SC2034 # 부르는 시험이 읽는다.
  run_status=$?
}

#######################################
# testlib::run 과 같되 제한 시간(초) 안에서 돌린다. 넘기면 명령과 그 자손을
# 모두 끝내고 run_status 를 124 로 남기며, 표준 오류 파일에 그 사실을 덧붙인다.
# macOS 에는 timeout 명령이 없어 백그라운드 감시자(sleep 뒤 kill)로 잰다 —
# 가드가 회귀해 판정이 FIFO 를 읽다 걸려도 시험이 멈추지 않게.
# Globals:
#   run_status, run_out, TIMED_OUT
# Arguments:
#   제한 초, 사례 디렉터리, 결과 이름, 명령과 인자.
#######################################
testlib::run_limited() {
  local seconds="$1" case_dir="$2" name="$3" job watcher mark
  shift 3
  mark="${case_dir}/out/${name}.timeout"
  # set -m 은 배경 작업마다 제 프로세스 묶음을 준다. 묶음째 끝내야 명령 치환
  # 안에서 FIFO 에 걸린 손자 프로세스까지 끝난다. 두 작업의 표준 입출력은
  # 닫아 둔다 — 이 함수가 명령 치환 안에서 불려도 기다리지 않게.
  set -m
  (
    testlib::run "${case_dir}" "${name}" "$@"
    exit "${run_status}"
  ) </dev/null >/dev/null 2>&1 &
  job=$!
  (
    sleep "${seconds}"
    : >"${mark}"
    kill -TERM -- "-${job}"
  ) </dev/null >/dev/null 2>&1 &
  watcher=$!
  set +m
  # 끝낸 작업에 대한 bash 의 「Terminated」 알림은 버린다.
  wait "${job}" 2>/dev/null
  run_status=$?
  kill -TERM -- "-${watcher}" 2>/dev/null
  wait "${watcher}" 2>/dev/null
  run_out="${case_dir}/out/${name}"
  if [[ -e "${mark}" ]]; then
    run_status="${TIMED_OUT}"
    printf '시험: %s초 제한을 넘겨 끝냄\n' "${seconds}" >>"${run_out}.stderr"
  fi
}

#######################################
# 인자를 탭으로 이어 한 줄로 낸다(고정 입력 TSV 행).
# Arguments:
#   칸들.
# Outputs:
#   탭 구분 한 줄.
#######################################
testlib::tsv() {
  local IFS=$'\t'
  printf '%s\n' "$*"
}

#######################################
# 파일에 고정 문자열이 몇 줄 나오는지 센다(파일이 없으면 0).
# Arguments:
#   파일, 문자열.
# Outputs:
#   줄 수.
#######################################
testlib::count() {
  if [[ ! -f "$1" ]]; then
    printf '0\n'
    return 0
  fi
  grep -cF -- "$2" "$1"
}

#######################################
# 마지막 실행의 표준 오류에 고정 문자열이 든 줄이 몇 개인지 본다 — 종료 2 의
# 원인 문구를 가를 때 쓴다.
# Globals:
#   run_out
# Arguments:
#   사례 이름, 문자열, 기대 줄 수.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
testlib::expect_stderr_count() {
  local name="$1" text="$2" want="$3" got
  got="$(testlib::count "${run_out}.stderr" "${text}")"
  if [[ "${got}" -ne "${want}" ]]; then
    testlib::fail "${name}: 표준 오류에 「${text}」 든 줄 ${want}개를" \
      "기대했으나 ${got}개 — $(cat -- "${run_out}.stderr")"
    return 1
  fi
}

#######################################
# 파일에서 절대 경로가 든 줄을 낸다. 「~/」 로 가린 경로와 상대 경로
# (scp-…/result.tsv 등)는 걸리지 않는다 — 사람에게 내는 문장에 HOME · 저장소
# 위치 · 로컬 계정 이름이 든 절대 경로가 나오는지 본다.
# Arguments:
#   파일.
# Outputs:
#   걸린 줄(없으면 빈 출력). 파일이 없으면 그 사실 한 줄.
#######################################
testlib::abs_path_lines() {
  if [[ ! -f "$1" ]]; then
    printf '%s\n' "(파일이 없음)"
    return
  fi
  grep -E -- '(^|[^~[:alnum:]._-])/[[:alnum:]._-]' "$1"
}

#######################################
# 사례 디렉터리에서 증거 루트 밖의 파일 가운데 가짜 계정 ID · 사용자 이름이
# 든 것을 찾는다(입력 in/ 은 시험이 넣은 고정 입력이라 뺀다). 원본은 증거
# 디렉터리에만 있어야 한다(사례 5).
# Globals:
#   FAKE_ACCOUNT, FAKE_USER
# Arguments:
#   사례 디렉터리.
# Outputs:
#   걸린 파일 경로(없으면 빈 출력). 훑을 디렉터리가 없으면 그 사실 한 줄.
#######################################
testlib::leaks_outside_evidence() {
  local case_dir="$1" file
  local root="${case_dir}/home/.pokeclip-precheck"
  # 훑을 디렉터리가 없으면 아무것도 못 찾은 것을 「유출 0」 으로 내지 않는다.
  if [[ ! -d "${case_dir}/home" || ! -d "${case_dir}/out" ]]; then
    printf '%s\n' "(훑을 사례 디렉터리가 없음)"
    return
  fi
  while IFS= read -r -d '' file; do
    case "${file}" in
      "${root}"/*) continue ;;
      *) ;;
    esac
    if grep -qF -e "${FAKE_ACCOUNT}" -e "${FAKE_USER}" -- "${file}"; then
      printf '%s\n' "${file}"
    fi
  done < <(find "${case_dir}/home" "${case_dir}/out" -type f -print0)
}
