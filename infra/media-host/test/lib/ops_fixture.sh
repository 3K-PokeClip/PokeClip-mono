# shellcheck shell=bash
# 상수 · 상태 변수는 source 하는 시험이 읽고 쓴다.
# shellcheck disable=SC2034
#
# 운영 확인 스크립트 시험(loopback · paths_count · paths_list · secret_count)의
# 실행 도구. 사례마다 깨끗한 디렉터리를 만들고, 스크립트를 빈
# 환경(env -i)에 정해진 값만 넣어 돌리고, 가짜 명령(test/ops_fakebin)이 남긴
# 기록을 읽는다.
#
# 사례 디렉터리:
#   bin/            PATH 의 맨 앞 — 가짜 docker · aws · curl · journalctl 과
#                   grep 감시 래퍼 링크.
#   state/          가짜 명령의 답(컨테이너 목록 · 비밀 · 응답 · 저널 · 로그).
#   state/ctrbin/   가짜 docker exec 안의 PATH 맨 앞 — 가짜 nc 와 base64 · tr
#                   감시 래퍼.
#   ctr/            가짜 사이드카 컨테이너 안 파일(API 비밀번호 파일).
#   log/            가짜 명령 기록(<명령>.<n>.argv · .env · curl 의 .stdin ·
#                   nc 의 .request).
#   tmp/            스크립트의 TMPDIR(스크래치 잔여 확인).
#   out · err       스크립트의 표준 출력 · 표준 오류.
#
# 시험 비밀 · ARN 은 고정 가짜 문자열이다. 이 파일은 source 해서 쓴다. bash
# 3.2 에서도 돈다.

readonly OPS_FAKE_PROJECT='pcbench-edge-proxy'
readonly OPS_FAKE_CDN_ARN='pcbench-edge-proxy-fake-arn-cdn'
readonly OPS_FAKE_KEY_ARN='pcbench-edge-proxy-fake-arn-testkey'
readonly OPS_FAKE_CDN_SECRET='pcbench-edge-proxy-fake-cdn-secret'
readonly OPS_FAKE_PASSPHRASE='pcbenchFakePassphrase0123456789x'
readonly OPS_FAKE_API_USER='pcbench-sidecar'
# prod 꼴(16진 64자 — 시작 스크립트가 기동마다 만든다)이라 「사용자:비밀번호」
# 의 base64 가 76자를 넘는다(GNU · BusyBox base64 는 76자에서 줄을 바꾼다).
OPS_FAKE_API_PASS="$(printf '0123456789abcdef%.0s' 1 2 3 4)"
readonly OPS_FAKE_API_PASS
readonly OPS_SIDECAR_ID='pcbenchsidecar01'
readonly OPS_MEDIAMTX_ID='pcbenchmediamtx01'
readonly OPS_NGINX_ID='pcbenchnginx01'
readonly OPS_RUN_TIMEOUT=60

ops_root=""
ops_dir=""
ops_status=0
ops_env=()

#######################################
# 사례 디렉터리를 새로 만든다(이후 ops_dir).
# Globals:
#   ops_root, ops_dir, TEST_DIR
#######################################
ops_fixture::new_case() {
  local fake="${TEST_DIR}/ops_fakebin" name
  ops_dir="$(mktemp -d "${ops_root}/case.XXXXXX")"
  mkdir -p -- "${ops_dir}/bin" "${ops_dir}/state/ctrbin" "${ops_dir}/ctr" \
    "${ops_dir}/log" "${ops_dir}/tmp" "${ops_dir}/home"
  for name in docker aws curl journalctl; do
    ln -s -- "${fake}/${name}" "${ops_dir}/bin/${name}"
  done
  ln -s -- "${fake}/watch" "${ops_dir}/bin/grep"
  ln -s -- "${fake}/nc" "${ops_dir}/state/ctrbin/nc"
  ln -s -- "${fake}/watch" "${ops_dir}/state/ctrbin/base64"
  ln -s -- "${fake}/watch" "${ops_dir}/state/ctrbin/tr"
  ops_env=("MEDIA_COMPOSE_PROJECT=${OPS_FAKE_PROJECT}")
}

#######################################
# 가짜 컨테이너 하나를 둔다.
# Globals:
#   ops_dir
# Arguments:
#   id, compose 서비스, 상태(running|exited), 컨테이너 환경 줄(NAME=값) ….
#######################################
ops_fixture::container() {
  local id="$1" service="$2" status="$3"
  shift 3
  printf '%s %s\n' "${id}" "${status}" >>"${ops_dir}/state/ps.${service}"
  : >"${ops_dir}/state/ctr.${id}.env"
  if (( $# > 0 )); then
    printf '%s\n' "$@" >"${ops_dir}/state/ctr.${id}.env"
  fi
}

#######################################
# 사이드카 컨테이너를 둔다 — 자격 환경(MTX_API_USER · MTX_API_PASS_FILE)과
# 비밀번호 파일(0400, 끝 줄바꿈 하나 — 사이드카 계약 꼴).
# Globals:
#   ops_dir
# Arguments:
#   사용자 이름(빈 값이면 환경에 넣지 않음), 비밀번호 파일 처리(normal |
#   nonewline | empty | missing(경로만 있고 파일 없음) | unset(경로 환경 없음)).
#######################################
ops_fixture::sidecar() {
  local user="$1" pass_mode="${2:-normal}" file="${ops_dir}/ctr/mtx_api_pass"
  local lines=()
  [[ -z "${user}" ]] || lines+=("MTX_API_USER=${user}")
  [[ "${pass_mode}" == unset ]] || lines+=("MTX_API_PASS_FILE=${file}")
  case "${pass_mode}" in
    normal) printf '%s\n' "${OPS_FAKE_API_PASS}" >"${file}" ;;
    nonewline) printf '%s' "${OPS_FAKE_API_PASS}" >"${file}" ;;
    empty) : >"${file}" ;;
  esac
  [[ ! -f "${file}" ]] || chmod 0400 "${file}"
  ops_fixture::container "${OPS_SIDECAR_ID}" sidecar running \
    ${lines[@]+"${lines[@]}"}
}

#######################################
# 가짜 nc 가 낼 HTTP 응답을 둔다(줄 끝 CRLF · Content-Length 는 본문 바이트
# 수).
# Globals:
#   ops_dir
# Arguments:
#   본문, 상태 줄(기본 「HTTP/1.0 200 OK」), Content-Length 를 넣을지(1|0).
#######################################
ops_fixture::response() {
  local body="$1" status="${2:-HTTP/1.0 200 OK}" with_length="${3:-1}"
  local LC_ALL=C
  {
    printf '%s\r\n' "${status}" \
      'Content-Type: application/json; charset=utf-8' 'Server: mediamtx'
    if (( with_length )); then
      printf 'Content-Length: %d\r\n' "${#body}"
    fi
    printf '\r\n%s' "${body}"
  } >"${ops_dir}/state/nc.response"
}

#######################################
# 원문을 그대로 가짜 nc 응답으로 둔다(깨진 응답 사례).
# Arguments:
#   응답 원문(printf 형식 문자열이 아님).
#######################################
ops_fixture::raw_response() {
  printf '%s' "$1" >"${ops_dir}/state/nc.response"
}

#######################################
# 가짜 aws 가 돌려줄 비밀 값을 둔다.
# Arguments:
#   ARN, 값.
#######################################
ops_fixture::secret() {
  printf '%s' "$2" >"${ops_dir}/state/secret.$1"
}

#######################################
# 스크립트를 빈 환경에 ops_env 와 가짜 명령 설정만 넣어 돌린다. 표준 출력은
# out, 표준 오류는 err 에 받는다. 스크립트의 표준 오류는 그 프로세스 안에서
# err 로 돌린다 — harness::bounded 를 띄우는 bash 의 작업 제어 경고(child
# setpgid 경쟁)가 err 에 섞이지 않게(harness.err 로 따로 받는다).
# Globals:
#   ops_dir, ops_env, ops_status, MEDIA_HOST_DIR, TEST_DIR
# Arguments:
#   스크립트(infra/media-host 기준 경로), 가짜 설정(NAME=값 …), --,
#   스크립트 인자 ….
#######################################
ops_fixture::run() {
  local script="${MEDIA_HOST_DIR}/$1" extra=()
  shift
  while (( $# > 0 )) && [[ "$1" != -- ]]; do
    extra+=("$1")
    shift
  done
  (( $# == 0 )) || shift
  ops_status=0
  if [[ ! -x "${script}" ]]; then
    harness::add_reason "${script#"${MEDIA_HOST_DIR}/"} 없음"
    ops_status=127
    return 1
  fi
  # shellcheck disable=SC2016 # $0 · $@ 는 sh -c 안에서 펼친다.
  harness::bounded "${OPS_RUN_TIMEOUT}" /bin/sh -c 'exec "$@" 2>"$0"' \
    "${ops_dir}/err" env -i "HOME=${ops_dir}/home" \
    "PATH=${ops_dir}/bin:/usr/bin:/bin" "TMPDIR=${ops_dir}/tmp" \
    "PCBENCH_FAKE_LIB=${TEST_DIR}/ops_fakebin/record.sh" \
    "PCBENCH_FAKE_LOG_DIR=${ops_dir}/log" \
    "PCBENCH_FAKE_STATE_DIR=${ops_dir}/state" \
    "PCBENCH_FAKE_PROJECT=${OPS_FAKE_PROJECT}" \
    ${ops_env[@]+"${ops_env[@]}"} ${extra[@]+"${extra[@]}"} \
    "${script}" "$@" </dev/null >"${ops_dir}/out" 2>"${ops_dir}/harness.err" \
    || ops_status=$?
  if (( ops_status == 124 )); then
    harness::add_reason "${script##*/} 가 ${OPS_RUN_TIMEOUT}초 안에 안 끝남"
    return 1
  fi
}

#######################################
# 명령 기록 argv 파일을 낸다(번호 순).
# Globals:
#   ops_dir
# Arguments:
#   명령 이름.
#######################################
ops_fixture::calls() {
  local n=1
  while [[ -f "${ops_dir}/log/$1.${n}.argv" ]]; do
    printf '%s\n' "${ops_dir}/log/$1.${n}.argv"
    n=$((n + 1))
  done
}

#######################################
# 명령 기록 수를 낸다.
# Arguments:
#   명령 이름.
#######################################
ops_fixture::count() {
  ops_fixture::calls "$1" | awk 'END { print NR }'
}

#######################################
# 첫 argv 가 주어진 하위 명령인 docker 기록 수를 낸다.
# Arguments:
#   하위 명령(ps · exec · logs · run).
#######################################
ops_fixture::docker_count() {
  local f n=0
  while IFS= read -r f; do
    [[ "$(head -n 1 -- "${f}")" != "$1" ]] || n=$((n + 1))
  done < <(ops_fixture::calls docker)
  printf '%s\n' "${n}"
}

#######################################
# 값이 든 파일을 낸다 — 가짜 명령 기록(argv · env) 전부와 스크립트 출력 · 오류.
# 의도된 통로(curl 의 .stdin · nc 의 .request)는 뺀다 — 부르는 쪽이 따로 본다.
# 값은 stdin 으로만 grep 에 넘긴다.
# Globals:
#   ops_dir
# Arguments:
#   값.
#######################################
ops_fixture::leaks() {
  local f
  for f in "${ops_dir}"/log/* "${ops_dir}/out" "${ops_dir}/err"; do
    [[ -f "${f}" ]] || continue
    case "${f}" in
      *.stdin | *.request) continue ;;
    esac
    if printf '%s\n' "$1" | /usr/bin/grep -q -F -f - -- "${f}"; then
      printf '%s\n' "${f#"${ops_dir}/"}"
    fi
  done
}

#######################################
# 값이 새지 않았는지 본다(의도된 통로 밖 0건).
# Arguments:
#   값, 이름(원인 문구).
#######################################
ops_fixture::expect_no_leak() {
  local found
  found="$(ops_fixture::leaks "$1" | paste -s -d ' ' -)"
  if [[ -n "${found}" ]]; then
    harness::add_reason "$2 이(가) 샘: ${found}"
    return 1
  fi
}

#######################################
# 표준 출력이 정확히 이 한 줄이고 종료 코드가 기대와 같은지 본다.
# Globals:
#   ops_dir, ops_status
# Arguments:
#   줄, 기대 종료 코드(0 | nonzero).
#######################################
ops_fixture::expect_line() {
  local ok=0 lines
  lines="$(awk 'END { print NR }' "${ops_dir}/out")"
  if [[ "${lines}" != 1 ]] || ! grep -q -x -F -- "$1" "${ops_dir}/out"; then
    harness::add_reason "출력이 「$1」 한 줄이 아님: $(harness::log_tail \
"${ops_dir}/out") / 오류: $(harness::log_tail "${ops_dir}/err")"
    ok=1
  fi
  ops_fixture::expect_status "$2" || ok=1
  return "${ok}"
}

#######################################
# 종료 코드를 본다.
# Globals:
#   ops_status
# Arguments:
#   0 | nonzero | 숫자.
#######################################
ops_fixture::expect_status() {
  case "$1" in
    nonzero) (( ops_status != 0 )) && return 0 ;;
    *) (( ops_status == $1 )) && return 0 ;;
  esac
  harness::add_reason "종료 코드 ${ops_status}(기대 $1)"
  return 1
}

#######################################
# TMPDIR(스크래치) 안 파일 수를 낸다.
# Globals:
#   ops_dir
#######################################
ops_fixture::tmp_files() {
  find "${ops_dir}/tmp" -mindepth 1 | awk 'END { print NR }'
}

#######################################
# 사례 디렉터리 뿌리를 만든다.
# Globals:
#   ops_root
# Arguments:
#   이름 조각.
#######################################
ops_fixture::init() {
  ops_root="$(mktemp -d "${TMPDIR:-/tmp}/pcbench-edge-proxy-$1.XXXXXX")"
}

#######################################
# 남은 시험 프로세스 그룹을 끝내고 사례 디렉터리 뿌리를 지운다(EXIT 트랩).
# Globals:
#   ops_root
#######################################
ops_fixture::cleanup() {
  local status=$?
  harness::end_last_group
  if [[ -n "${ops_root}" ]]; then
    chmod -R u+w -- "${ops_root}" 2>/dev/null || true
    rm -rf -- "${ops_root:?}"
  fi
  exit "${status}"
}
