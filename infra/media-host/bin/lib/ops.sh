# shellcheck shell=bash
#
# Media 상자 운영 확인 스크립트(loopback-health · paths-count · paths-list ·
# secret-count)가 함께 쓰는 부품 — 상한 시간이 있는 실행 · 스크래치 디렉터리 ·
# compose 서비스 컨테이너 찾기 · Secrets Manager 비밀 읽기.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). 상자(AL2023 · bash 5)와 kty
# 로컬(macOS · bash 3.2) 양쪽에서 돈다 — bash 3.2 문법과 두 계열에 공통인 도구
# 옵션만 쓴다. macOS 에는 timeout 명령이 없어 상한은 SECONDS 로 직접 잰다.
#
# 읽는 변수:
#   MEDIA_OPS_TIMEOUT       외부 명령(docker · aws · journalctl) 한 번의 상한
#                           (초, 1–600). 기본 30.
#   MEDIA_COMPOSE_PROJECT   compose 프로젝트 이름. 기본 pokeclip-media(시작
#                           스크립트와 같은 기본값).

readonly OPS_DEFAULT_TIMEOUT=30
readonly OPS_MAX_TIMEOUT=600
# 상한을 넘긴 명령에 TERM 뒤 KILL 까지 주는 유예(초).
readonly OPS_KILL_GRACE=3
# 비밀 값 길이 상한(바이트).
readonly OPS_SECRET_MAX_BYTES=4096
# CDN 비밀(MediaMTX hlsCDNSecret)이 가질 수 있는 글자 — 시작 스크립트와 같다
# (MediaMTX 가 / · ~ 를 거부한다).
# shellcheck disable=SC2034 # 부르는 스크립트가 읽는다.
readonly OPS_CDN_SECRET_PATTERN='^[A-Za-z0-9._+=-]+$'
# 시험 스트림 키 passphrase 의 꼴 — SRT passphrase 길이(10–79) · URL 쿼리에
# 이스케이프 없이 실을 수 있는 글자.
# shellcheck disable=SC2034 # 부르는 스크립트가 읽는다.
readonly OPS_PASSPHRASE_PATTERN='^[A-Za-z0-9._~-]{10,79}$'
readonly OPS_PROJECT_LABEL='label=com.docker.compose.project='
readonly OPS_SERVICE_LABEL='label=com.docker.compose.service='

ops_scratch=""
ops_child_pid=""

#######################################
# 판정 줄을 낸다.
# Arguments:
#   줄.
#######################################
ops::say() {
  printf '%s\n' "$1"
}

#######################################
# 외부 명령 상한(초)을 낸다. 값이 틀리면(숫자 아님 · 범위 밖) 실패한다 — 기본값
# 으로 바꿔 쓰지 않는다.
# Globals:
#   MEDIA_OPS_TIMEOUT
# Outputs:
#   상한(초).
#######################################
ops::timeout() {
  local value="${MEDIA_OPS_TIMEOUT:-${OPS_DEFAULT_TIMEOUT}}"
  [[ "${value}" =~ ^[1-9][0-9]{0,2}$ ]] || return 1
  (( value <= OPS_MAX_TIMEOUT )) || return 1
  printf '%s\n' "${value}"
}

#######################################
# compose 프로젝트 이름을 낸다.
# Globals:
#   MEDIA_COMPOSE_PROJECT
#######################################
ops::project() {
  printf '%s\n' "${MEDIA_COMPOSE_PROJECT:-pokeclip-media}"
}

#######################################
# 명령을 상한 시간 안에서 돌린다. 넘기면 TERM → 유예 → KILL 로 끝내고 124 를
# 돌려준다. 표준 입출력은 부르는 쪽 것을 쓴다(배경 실행이라 표준 입력은 명시해
# 넘긴다). 신호로 스크립트가 끝날 때 정리할 수 있게 PID 를 ops_child_pid 에
# 둔다.
# Globals:
#   ops_child_pid
# Arguments:
#   상한(초), 명령 ….
# Returns:
#   명령의 종료 코드 / 124 상한 초과.
#######################################
ops::bounded() {
  local limit="$1" pid deadline status=0
  shift
  "$@" <&0 &
  pid=$!
  ops_child_pid="${pid}"
  deadline=$((SECONDS + limit))
  while kill -0 "${pid}" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      ops::stop_pid "${pid}"
      ops_child_pid=""
      return 124
    fi
    sleep 0.2
  done
  wait "${pid}" || status=$?
  ops_child_pid=""
  return "${status}"
}

#######################################
# 프로세스를 TERM 으로 끝내고, 유예 안에 안 끝나면 KILL 한다. 0 을 돌려준다.
# Arguments:
#   PID.
#######################################
ops::stop_pid() {
  local pid="$1" deadline
  kill -TERM "${pid}" 2>/dev/null || true
  deadline=$((SECONDS + OPS_KILL_GRACE))
  while (( SECONDS < deadline )) && kill -0 "${pid}" 2>/dev/null; do
    sleep 0.2
  done
  kill -KILL "${pid}" 2>/dev/null || true
  wait "${pid}" 2>/dev/null || true
  return 0
}

#######################################
# 스크래치 디렉터리를 만든다(0700 — 부르는 스크립트가 umask 077). EXIT 트랩에
# ops::on_exit 를 걸어 두어야 지워진다.
# Globals:
#   ops_scratch, TMPDIR
#######################################
ops::make_scratch() {
  ops_scratch="$(mktemp -d "${TMPDIR:-/tmp}/pokeclip-media-ops.XXXXXX")"
}

#######################################
# 끝날 때 상한 실행 중인 자식을 끝내고 스크래치를 지운다(EXIT 트랩).
# Globals:
#   ops_child_pid, ops_scratch
#######################################
ops::on_exit() {
  if [[ -n "${ops_child_pid}" ]]; then
    ops::stop_pid "${ops_child_pid}"
  fi
  if [[ -n "${ops_scratch}" ]]; then
    rm -rf -- "${ops_scratch}"
  fi
}

#######################################
# compose 서비스의 컨테이너 ID 를 낸다(한 줄에 하나). docker 조회가 실패하거나
# ID 꼴이 아닌 줄이 있으면 실패한다 — 「없음」 과 「조회 실패」 를 가른다.
# Arguments:
#   서비스 이름, running | all, 상한(초).
# Outputs:
#   ID(0 개 이상).
#######################################
ops::containers() {
  local service="$1" which="$2" limit="$3" project out id
  local filters=()
  project="$(ops::project)"
  filters=(--filter "${OPS_PROJECT_LABEL}${project}"
    --filter "${OPS_SERVICE_LABEL}${service}")
  if [[ "${which}" == running ]]; then
    out="$(ops::bounded "${limit}" docker ps -q --no-trunc \
      --filter status=running "${filters[@]}" </dev/null 2>/dev/null)" \
      || return 1
  else
    out="$(ops::bounded "${limit}" docker ps -a -q --no-trunc "${filters[@]}" \
      </dev/null 2>/dev/null)" || return 1
  fi
  for id in ${out}; do
    [[ "${id}" =~ ^[0-9A-Za-z]+$ ]] || return 1
    printf '%s\n' "${id}"
  done
}

#######################################
# 실행 중인 서비스 컨테이너가 정확히 하나면 그 ID 를 낸다.
# Arguments:
#   서비스 이름, 상한(초).
# Outputs:
#   ID.
#######################################
ops::one_container() {
  local ids
  ids="$(ops::containers "$1" running "$2")" || return 1
  [[ -n "${ids}" && "${ids}" != *$'\n'* ]] || return 1
  printf '%s\n' "${ids}"
}

#######################################
# Secrets Manager 비밀을 읽는다(argv 에는 ARN 만). aws text 출력이 붙이는 끝
# 줄바꿈 하나만 떼고, 값 자체의 끝 줄바꿈은 남겨 꼴 검사에 걸리게 한다. 값의
# NUL 은 bash 변수에서 사라지므로 변수에 담기 전에 다른 제어 문자로 바꿔 꼴
# 검사에 걸리게 한다. 쓸 수 없는 값(빈 값 · None · 꼴 밖 · 길이 초과)도
# 실패다.
# Arguments:
#   ARN, 값이 맞아야 할 정규식, 상한(초).
# Outputs:
#   값(성공했을 때만).
#######################################
ops::read_secret() {
  local arn="$1" pattern="$2" limit="$3" value
  [[ -n "${arn}" ]] || return 1
  value="$({ ops::bounded "${limit}" aws secretsmanager get-secret-value \
    --secret-id "${arn}" --query SecretString --output text </dev/null \
    2>/dev/null && printf x; } | LC_ALL=C tr '\000' '\001')" || return 1
  [[ "${value}" == *x ]] || return 1
  value="${value%x}"
  value="${value%$'\n'}"
  if [[ "${value}" == None || ! "${value}" =~ ${pattern} ]] \
    || (( ${#value} > OPS_SECRET_MAX_BYTES )); then
    return 1
  fi
  printf '%s' "${value}"
}
