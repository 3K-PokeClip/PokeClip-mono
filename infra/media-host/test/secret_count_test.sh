#!/usr/bin/env bash
#
# bin/secret-count.sh 시험 — box 입력(유닛 저널 + 태그 저널 셋 + mediamtx ·
# nginx docker logs — 사이드카는 다시 뜬 앞 컨테이너 로그까지 남는 태그 저널로) ·
# passphrase 대조는 --passphrase 갈래에서만 · box 의 사이드카 API 비밀번호
# 대조(시작 스크립트가 RUNTIME_DIR 에 둔 이번 기동의 값 · Basic 꼴) · box 응답기
# 모드의 대조 넷(Basic 비밀번호 · 그 Basic 머리 값 · 4C 토큰 · 잠금 둘)과 표지
# 일부만 있음 · 꼴 밖의 실패 · 실제 템플릿 잠금 줄 · Basic 사용자 대조 · 4C
# 토큰의 JSON 이스케이프 꼴 · 새 지역 변수 이름의 상속 export.
#
# 스크립트를 빈 환경(env -i)에서 돌린다. PATH 맨 앞에는 가짜 docker · aws ·
# journalctl 과 grep 감시 래퍼(argv · 환경을 기록한 뒤 진짜 grep 실행)만 둔다.
# 시험 비밀은 가짜 aws 가 상태 파일에서 돌려준다.
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/secret_count_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
MEDIA_HOST_DIR="$(cd -- "${TEST_DIR}/.." && pwd)"
readonly MEDIA_HOST_DIR
# shellcheck source=lib/harness.sh
source "${TEST_DIR}/lib/harness.sh"
# shellcheck source=lib/ops_fixture.sh
source "${TEST_DIR}/lib/ops_fixture.sh"
# shellcheck source=../bin/lib/render_files.sh
source "${MEDIA_HOST_DIR}/bin/lib/render_files.sh"

readonly SCRIPT='bin/secret-count.sh'
readonly CASE_COUNT=29
# 사이드카 API 사용자 이름(compose MTX_API_USER) — Basic 값의 앞부분.
readonly SIDECAR_API_USER='pokeclip-sidecar'
readonly COMMAND_ID='0123abcd-4567-89ef-0123-456789abcdef'
readonly INSTANCE_A='i-0123456789abcdef0'
readonly INSTANCE_B='i-0fedcba9876543210'
readonly TAGS=(pokeclip-media-sidecar pokeclip-media-ingest-auth
  pokeclip-media-janitor)
# 응답기 모드 시험 값 — Basic 사용자(mediamtx.yml.tmpl authHTTPAddress ·
# 응답기 INGEST_AUTH_BASIC_USER), 16진 64자 셋(Basic 비밀번호 · 송출 잠금 ·
# 읽기 잠금 — 서로 다르고 API 비밀번호와도 다르다), 4C 토큰(안에 공백 — 꼴
# 안), 템플릿의 잠금 토큰 둘.
readonly IA_BASIC_USER='pokeclip-mediamtx'
IA_BASIC_PASS="$(printf 'fedcba9876543210%.0s' 1 2 3 4)"
readonly IA_BASIC_PASS
IA_PUBLISH_LOCK="$(printf '00112233445566778899aabbccddeeff%.0s' 1 2)"
readonly IA_PUBLISH_LOCK
IA_READ_LOCK="$(printf 'ffeeddccbbaa99887766554433221100%.0s' 1 2)"
readonly IA_READ_LOCK
readonly IA_RESOLVE_TOKEN='pcbench fake resolve token 4c'
readonly IA_PUBLISH_TOKEN='@@SRT_PUBLISH_LOCK@@'
readonly IA_READ_TOKEN='@@SRT_READ_LOCK@@'
# JSON 이스케이프 갈래(사례 28) — 4C 토큰과 slog JSON 처리기가 낼 그 꼴(Go
# log/slog json_handler.go appendEscapedJSONString: 「\」 → 「\\」 · 「"」 →
# 「\"」 · U+2028 · U+2029 → 「\u2028」 · 「\u2029」). 두 배열은 같은 자리끼리
# 짝이다. 마지막 짝은 역슬래시 뒤 따옴표 — 바꾸는 순서가 틀리면 꼴이 달라진다.
readonly IA_LS=$'\xe2\x80\xa8'
readonly IA_PS=$'\xe2\x80\xa9'
readonly IA_JSON_TOKENS=('pc"bench quote' 'pc\bench backslash'
  "pc${IA_LS}bench ls" "pc${IA_PS}bench ps" 'pc\"bench'"${IA_PS}mixed")
readonly IA_JSON_ESCAPED=('pc\"bench quote' 'pc\\bench backslash'
  'pc\u2028bench ls' 'pc\u2029bench ps' 'pc\\\"bench\u2029mixed')

#######################################
# box 모드 기본 상태 — 비밀 둘 · mediamtx · nginx 컨테이너 · 빈 저널.
#######################################
setup_box() {
  ops_fixture::new_case
  ops_fixture::secret "${OPS_FAKE_CDN_ARN}" "${OPS_FAKE_CDN_SECRET}"
  ops_fixture::secret "${OPS_FAKE_KEY_ARN}" "${OPS_FAKE_PASSPHRASE}"
  ops_fixture::container "${OPS_MEDIAMTX_ID}" mediamtx running
  ops_fixture::container "${OPS_NGINX_ID}" nginx running
  ops_fixture::container "${OPS_SIDECAR_ID}" sidecar running
  printf 'sidecar docker logs 줄 %s\n' "${OPS_FAKE_CDN_SECRET}" \
    >"${ops_dir}/state/logs.${OPS_SIDECAR_ID}.out"
  mkdir -p -- "${ops_dir}/runtime"
  printf '%s\n' "${OPS_FAKE_API_PASS}" >"${ops_dir}/runtime/mtx_api_pass"
  ops_env+=("CDN_SECRET_ARN=${OPS_FAKE_CDN_ARN}"
    "TEST_STREAM_KEY_SECRET_ARN=${OPS_FAKE_KEY_ARN}"
    "RUNTIME_DIR=${ops_dir}/runtime")
}

#######################################
# 사이드카 API 자격의 Basic 값(base64 — 줄바꿈 없이)을 낸다.
#######################################
api_basic() {
  printf '%s:%s' "${SIDECAR_API_USER}" "${OPS_FAKE_API_PASS}" \
    | /usr/bin/base64 | tr -d '\n'
}

#######################################
# 파일에 비밀 값이 든 줄을 n 개 덧붙인다.
# Arguments:
#   파일, 값, n.
#######################################
plant() {
  local i
  for (( i = 1; i <= $3; i++ )); do
    printf '심은 줄 %d: …%s…\n' "${i}" "$2" >>"$1"
  done
}

#######################################
# 스크립트를 돌린다.
# Arguments:
#   스크립트 인자 ….
#######################################
run_count() {
  ops_fixture::run "${SCRIPT}" -- "$@"
}

#######################################
# 비밀 노출 0 · 스크래치 잔여 0 · grep 호출 꼴(LC_ALL=C · -a — 바이너리가
# 섞인 로그도 줄 단위로 센다) · 금지 docker 하위 명령 0 을 본다.
#######################################
expect_hygiene() {
  local ok=0 f sub left
  ops_fixture::expect_no_leak "${OPS_FAKE_CDN_SECRET}" 'CDN 비밀' || ok=1
  ops_fixture::expect_no_leak "${OPS_FAKE_PASSPHRASE}" '시험 passphrase' || ok=1
  ops_fixture::expect_no_leak "${OPS_FAKE_API_PASS}" 'API 비밀번호' || ok=1
  ops_fixture::expect_no_leak "$(api_basic)" 'API Basic 값' || ok=1
  left="$(ops_fixture::tmp_files)"
  if [[ "${left}" != 0 ]]; then
    harness::add_reason "스크래치 잔여 ${left}"
    ok=1
  fi
  while IFS= read -r f; do
    if [[ "$(head -n 5 "${f}" | paste -s -d ' ' -)" != '-a -F -c -f -' \
      || "$(awk 'END { print NR }' "${f}")" != 6 ]]; then
      harness::add_reason "grep argv 가 「-a -F -c -f - <파일>」 이 아님"
      ok=1
    fi
    if ! grep -q -x -F 'LC_ALL=C' "${f%.argv}.env"; then
      harness::add_reason 'grep 환경에 LC_ALL=C 가 없음'
      ok=1
    fi
  done < <(ops_fixture::calls grep)
  while IFS= read -r f; do
    sub="$(head -n 1 "${f}")"
    if [[ "${sub}" != ps && "${sub}" != logs ]]; then
      harness::add_reason "docker ${sub} 를 부름(ps · logs 만 허용)"
      ok=1
    fi
    if [[ "${sub}" == logs ]] \
      && grep -q -x -F "${OPS_SIDECAR_ID}" "${f}"; then
      harness::add_reason '사이드카를 docker logs 로 읽음(태그 저널로 읽어야 함)'
      ok=1
    fi
  done < <(ops_fixture::calls docker)
  return "${ok}"
}

# 사례 1 — box: 어디에도 심지 않음 → SECRET COUNT box 0(사이드카 docker logs
# 의 줄은 세지 않는다 — 사이드카는 태그 저널로 읽는다).
case_box_zero() {
  local ok=0
  setup_box
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 0' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 2 — box: 사이드카 태그 저널에 2건 → SECRET COUNT box 2(사이드카는
# docker logs 가 아니라 태그 저널로 센다).
case_box_tag_journal() {
  local ok=0
  setup_box
  plant "${ops_dir}/state/journal.t.pokeclip-media-sidecar" \
    "${OPS_FAKE_CDN_SECRET}" 2
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 2' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 3 — box: 입력마다 심은 건수를 모두 센다(유닛 1 · 태그 셋 각 1 ·
# mediamtx 표준 출력 1 · nginx 표준 오류 2 = 7).
case_box_all_inputs() {
  local ok=0 tag
  setup_box
  plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
  for tag in "${TAGS[@]}"; do
    plant "${ops_dir}/state/journal.t.${tag}" "${OPS_FAKE_CDN_SECRET}" 1
  done
  plant "${ops_dir}/state/logs.${OPS_MEDIAMTX_ID}.out" \
    "${OPS_FAKE_CDN_SECRET}" 1
  plant "${ops_dir}/state/logs.${OPS_NGINX_ID}.err" "${OPS_FAKE_CDN_SECRET}" 2
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 7' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 4 — 정지한 mediamtx 컨테이너(재기동 전)의 로그도 센다.
case_box_exited_container() {
  local ok=0
  setup_box
  ops_fixture::container pcbenchmediamtxold mediamtx exited
  plant "${ops_dir}/state/logs.pcbenchmediamtxold.out" \
    "${OPS_FAKE_CDN_SECRET}" 3
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 3' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 5 — passphrase 는 --passphrase 갈래에서만 대조한다.
case_passphrase_branch() {
  local ok=0
  setup_box
  plant "${ops_dir}/state/journal.t.pokeclip-media-ingest-auth" \
    "${OPS_FAKE_PASSPHRASE}" 2
  plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 1' 0 || ok=1
  if grep -q -x -F "${OPS_FAKE_KEY_ARN}" "${ops_dir}"/log/aws.*.argv; then
    harness::add_reason '기본 갈래에서 시험 키를 조회함'
    ok=1
  fi
  expect_hygiene || ok=1
  setup_box
  plant "${ops_dir}/state/journal.t.pokeclip-media-ingest-auth" \
    "${OPS_FAKE_PASSPHRASE}" 2
  plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
  run_count --passphrase box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 3' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 6 — ssm: 명령의 인스턴스마다 SSM 기록(표준 출력 · 오류)을 대조한다.
case_ssm() {
  local ok=0 f
  setup_box
  printf '%s\t%s\n' "${INSTANCE_A}" "${INSTANCE_B}" >"${ops_dir}/state/ssm.list"
  printf 'LOOPBACK OK\n' >"${ops_dir}/state/ssm.out.${INSTANCE_A}"
  plant "${ops_dir}/state/ssm.out.${INSTANCE_B}" "${OPS_FAKE_CDN_SECRET}" 2
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT ssm 2' 0 || ok=1
  expect_hygiene || ok=1
  if [[ "$(ops_fixture::count docker)" != 0 \
    || "$(ops_fixture::count journalctl)" != 0 ]]; then
    harness::add_reason 'ssm 모드인데 docker · journalctl 을 부름'
    ok=1
  fi
  for f in "${ops_dir}"/log/aws.*.argv; do
    if grep -q -x -F get-command-invocation "${f}" \
      && ! grep -q -x -F "${COMMAND_ID}" "${f}"; then
      harness::add_reason 'get-command-invocation 에 명령 ID 가 없음'
      ok=1
    fi
  done
  # 인스턴스마다 두 번 — 표준 출력 · 표준 오류를 각각 그 응답의 상태와 함께.
  if [[ "$(grep -l -x -F get-command-invocation "${ops_dir}"/log/aws.*.argv \
    | awk 'END { print NR }')" != 4 ]]; then
    harness::add_reason 'get-command-invocation 이 인스턴스마다 두 번이 아님'
    ok=1
  fi
  for f in "${ops_dir}"/log/aws.*.argv; do
    if grep -q -x -F get-command-invocation "${f}" \
      && ! grep -q -x -E \
      '\[Status,StatusDetails,Standard(Output|Error)Content\]' "${f}"; then
      harness::add_reason '출력 조회가 그 응답의 상태를 함께 묻지 않음'
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 7 — CDN 비밀 · 시험 키를 못 읽으면 0 이 아니라 실패 줄.
case_secret_unavailable() {
  local ok=0 value
  setup_box
  rm -f -- "${ops_dir}/state/secret.${OPS_FAKE_CDN_ARN}"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL cdn-secret' nonzero || ok=1
  for value in '' None $'two\nlines' 'has space'; do
    setup_box
    ops_fixture::secret "${OPS_FAKE_CDN_ARN}" "${value}"
    run_count box || return 1
    ops_fixture::expect_line 'SECRET COUNT FAIL cdn-secret' nonzero || ok=1
  done
  # NUL 이 든 값 — bash 변수에서 NUL 이 사라져 꼴 검사를 통과하면 안 된다.
  setup_box
  printf 'pcbench\000nul-secret' \
    >"${ops_dir}/state/secret.${OPS_FAKE_CDN_ARN}"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL cdn-secret' nonzero || ok=1
  setup_box
  rm -f -- "${ops_dir}/state/secret.${OPS_FAKE_KEY_ARN}"
  run_count --passphrase box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL test-key' nonzero || ok=1
  setup_box
  ops_env=("MEDIA_COMPOSE_PROJECT=${OPS_FAKE_PROJECT}"
    "TEST_STREAM_KEY_SECRET_ARN=${OPS_FAKE_KEY_ARN}")
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL cdn-secret' nonzero || ok=1
  return "${ok}"
}

# 사례 8 — 입력을 못 읽으면 0 이 아니라 실패 줄 · 스크래치 잔여 0.
case_input_failure() {
  local ok=0 left
  setup_box
  : >"${ops_dir}/state/journal.fail"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL journal' nonzero || ok=1
  setup_box
  : >"${ops_dir}/state/logs.fail"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL docker-logs' nonzero || ok=1
  setup_box
  rm -f -- "${ops_dir}/state/ps.nginx"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL nginx' nonzero || ok=1
  setup_box
  : >"${ops_dir}/state/ps.fail"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL mediamtx' nonzero || ok=1
  left="$(ops_fixture::tmp_files)"
  if [[ "${left}" != 0 ]]; then
    harness::add_reason "실패 뒤 스크래치 잔여 ${left}"
    ok=1
  fi
  return "${ok}"
}

# 사례 9 — ssm 기록을 못 읽거나 인스턴스가 없으면 실패 줄.
case_ssm_failure() {
  local ok=0
  setup_box
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-list' nonzero || ok=1
  setup_box
  : >"${ops_dir}/state/ssm.list"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-list' nonzero || ok=1
  setup_box
  printf '%s\n' "${INSTANCE_A}" >"${ops_dir}/state/ssm.list"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-output' nonzero || ok=1
  setup_box
  printf 'not-an-instance\n' >"${ops_dir}/state/ssm.list"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-list' nonzero || ok=1
  return "${ok}"
}

# 사례 10 — 인자 오류 → 사용법(종료 2) · aws · docker 0.
case_usage() {
  local ok=0 args
  for args in '' 'nope' 'ssm' "ssm not-a-uuid" "box extra" '--passphrase' \
    "ssm ${COMMAND_ID} extra"; do
    setup_box
    # shellcheck disable=SC2086 # 단어로 쪼개는 것이 뜻이다.
    run_count ${args} || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL usage' 2; then
      harness::add_reason "(인자 「${args}」)"
      ok=1
    fi
    if [[ "$(ops_fixture::count aws)" != 0 \
      || "$(ops_fixture::count docker)" != 0 ]]; then
      harness::add_reason "인자 「${args}」: aws · docker 를 부름"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 11 — 저널 · 로그 호출 꼴: 유닛 pokeclip-media · 태그 셋 · mediamtx ·
# nginx 각각 한 번씩.
case_box_inputs_called() {
  local ok=0 tag joined
  setup_box
  run_count box || return 1
  joined="$(cat "${ops_dir}"/log/journalctl.*.argv 2>/dev/null \
    | paste -s -d ' ' -)"
  if [[ "${joined}" != *'-u pokeclip-media -q --no-pager -o cat'* ]]; then
    harness::add_reason '유닛 저널을 읽지 않음'
    ok=1
  fi
  for tag in "${TAGS[@]}"; do
    if [[ "${joined}" != *"-t ${tag} -q --no-pager -o cat"* ]]; then
      harness::add_reason "태그 저널 ${tag} 를 읽지 않음"
      ok=1
    fi
  done
  if [[ "$(ops_fixture::docker_count logs)" != 2 ]]; then
    harness::add_reason "docker logs $(ops_fixture::docker_count logs)회(기대 2)"
    ok=1
  fi
  return "${ok}"
}

# 사례 12 — 상한 값이 틀리면 실패(기본값으로 바꿔 쓰지 않음).
case_config() {
  setup_box
  ops_fixture::run "${SCRIPT}" MEDIA_OPS_TIMEOUT=0 -- box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL config' nonzero
}

#######################################
# ssm 모드 사례 하나 — 인스턴스 A 하나 · 표준 출력에 비밀 1건.
#######################################
setup_ssm_one() {
  setup_box
  printf '%s\n' "${INSTANCE_A}" >"${ops_dir}/state/ssm.list"
  plant "${ops_dir}/state/ssm.out.${INSTANCE_A}" "${OPS_FAKE_CDN_SECRET}" 1
}

#######################################
# 파일을 정해진 바이트 수의 줄로 채우고, 그 뒤에 비밀 한 줄을 붙인다.
# Arguments:
#   파일, 앞 바이트 수.
#######################################
pad_then_secret() {
  local n="$2"
  head -c "${n}" /dev/zero | tr '\0' 'x' >"$1"
  printf '\n%s\n' "${OPS_FAKE_CDN_SECRET}" >>"$1"
}

# 사례 13 — 끝나지 않은 명령 · invocation 은 0 건이 아니라 ssm-status.
# 끝까지 돈 Failed 는 센다.
case_ssm_status() {
  local ok=0 status
  for status in $'InProgress\tIn Progress' $'Pending\tPending' \
    $'Delayed\tDelayed' $'Success\tIn Progress' 'Success'; do
    setup_ssm_one
    printf '%s' "${status}" >"${ops_dir}/state/ssm.status.${INSTANCE_A}"
    run_count ssm "${COMMAND_ID}" || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL ssm-status' nonzero; then
      harness::add_reason "(invocation 「${status}」)"
      ok=1
    fi
  done
  for status in InProgress Pending Cancelling; do
    setup_ssm_one
    printf '%s' "${status}" >"${ops_dir}/state/ssm.command"
    run_count ssm "${COMMAND_ID}" || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL ssm-status' nonzero; then
      harness::add_reason "(명령 「${status}」)"
      ok=1
    fi
  done
  setup_ssm_one
  : >"${ops_dir}/state/ssm.command.fail"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-command' nonzero || ok=1
  setup_ssm_one
  printf 'Failed\tFailed' >"${ops_dir}/state/ssm.status.${INSTANCE_A}"
  printf 'Failed' >"${ops_dir}/state/ssm.command"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT ssm 1' 0 || ok=1
  return "${ok}"
}

# 사례 14 — API 한도(표준 출력 24,000 · 표준 오류 8,000)에 닿은 기록은
# ssm-truncated. 한도 밑이면 센다.
case_ssm_truncated() {
  local ok=0
  setup_ssm_one
  pad_then_secret "${ops_dir}/state/ssm.out.${INSTANCE_A}" 25000
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-truncated' nonzero || ok=1
  setup_ssm_one
  pad_then_secret "${ops_dir}/state/ssm.err.${INSTANCE_A}" 9000
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-truncated' nonzero || ok=1
  setup_ssm_one
  # 표준 오류 7,900 + 줄바꿈 + 비밀 줄 = 한도 밑 → 셈(비밀 2건: 표준 출력 1 ·
  # 표준 오류 1).
  pad_then_secret "${ops_dir}/state/ssm.err.${INSTANCE_A}" 7900
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT ssm 2' 0 || ok=1
  return "${ok}"
}

# 사례 15 — 상태만 묻는 조회는 Success 인데 출력을 받는 조회가 오래된 진행 중
# 응답(빈 출력)을 받는 경합 — 출력 응답의 상태도 봐서 0 건이 아니라
# ssm-status.
case_ssm_status_race() {
  setup_ssm_one
  : >"${ops_dir}/state/ssm.race.${INSTANCE_A}"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL ssm-status' nonzero
}

# 사례 16 — box: 이번 기동의 사이드카 API 비밀번호(RUNTIME_DIR/mtx_api_pass)와
# 그 Basic 값(사용자:비밀번호의 base64)도 센다 — nginx 표준 오류의 비밀번호
# 1 · 유닛 저널의 Basic 값 1 = 2.
case_box_api_pass() {
  local ok=0
  setup_box
  plant "${ops_dir}/state/logs.${OPS_NGINX_ID}.err" "${OPS_FAKE_API_PASS}" 1
  plant "${ops_dir}/state/journal.u.pokeclip-media" "$(api_basic)" 1
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 2' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 17 — box: 비밀번호 파일이 없으면 0 건이 아니라 api-pass 로 실패한다
# (종료 1). 스택이 떠 있는데 파일이 없는 것은 이상 상태다.
case_box_api_pass_missing() {
  local ok=0
  setup_box
  rm -f -- "${ops_dir}/runtime/mtx_api_pass"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL api-pass' 1 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 18 — ssm: 기록을 읽는 변수 이름(first)이 부모 환경에서 export 된 채
# 들어와도(bash 의 local 이 export 속성을 이어받는 꼴) 그 변수에 담긴 기록 첫
# 줄(비밀이 든 줄)이 자식(wc · tail) 환경에 실리지 않는다.
case_ssm_inherited_export() {
  local ok=0 name
  setup_ssm_one
  for name in wc tail; do
    ln -s -- "${TEST_DIR}/ops_fakebin/watch" "${ops_dir}/bin/${name}"
  done
  ops_fixture::run "${SCRIPT}" first=inherited -- ssm "${COMMAND_ID}" \
    || return 1
  ops_fixture::expect_line 'SECRET COUNT ssm 1' 0 || ok=1
  if [[ "$(ops_fixture::count wc)" == 0 ]]; then
    harness::add_reason 'wc 를 부르지 않아 판정 불가'
    ok=1
  fi
  ops_fixture::expect_no_leak "${OPS_FAKE_CDN_SECRET}" 'CDN 비밀' || ok=1
  return "${ok}"
}

# 사례 19 — box: 비밀번호 파일이 없으면 로그에 CDN 비밀이 있어도 건수를 내지
# 않고 api-pass 로 실패한다(종료 1) — 일부만 센 수가 판정 줄로 나가지 않는다.
case_box_api_pass_missing_with_cdn() {
  local ok=0
  setup_box
  rm -f -- "${ops_dir}/runtime/mtx_api_pass"
  plant "${ops_dir}/state/journal.t.pokeclip-media-sidecar" \
    "${OPS_FAKE_CDN_SECRET}" 2
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL api-pass' 1 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

#######################################
# 비밀번호 파일 자리를 갈래 하나로 만든다(setup_box 뒤).
# Arguments:
#   갈래 — not-hex | empty | dir | dangling | symlink | no-newline | junk |
#   two-lines | upper | non-hex-64 | cr(뒤 셋은 정확히 65바이트 — 크기 검사를
#   지나 내용 검사만 겨눈다).
#######################################
write_api_pass_variant() {
  local file="${ops_dir}/runtime/mtx_api_pass"
  rm -f -- "${file}"
  case "$1" in
    not-hex) printf 'not-a-hex-password\n' >"${file}" ;;
    empty) : >"${file}" ;;
    dir) mkdir -- "${file}" ;;
    dangling) ln -s -- "${ops_dir}/runtime/absent" "${file}" ;;
    symlink)
      printf '%s\n' "${OPS_FAKE_API_PASS}" >"${ops_dir}/runtime/real"
      ln -s -- "${ops_dir}/runtime/real" "${file}"
      ;;
    no-newline) printf '%s' "${OPS_FAKE_API_PASS}" >"${file}" ;;
    junk) printf '%s\nJUNK' "${OPS_FAKE_API_PASS}" >"${file}" ;;
    two-lines)
      printf '%s\n%s\n' "${OPS_FAKE_API_PASS}" "${OPS_FAKE_API_PASS}" \
        >"${file}"
      ;;
    upper)
      printf '%s\n' "${OPS_FAKE_API_PASS}" | tr 'a-f' 'A-F' >"${file}"
      ;;
    non-hex-64) printf '%sg\n' "${OPS_FAKE_API_PASS:0:63}" >"${file}" ;;
    cr) printf '%s\r\n' "${OPS_FAKE_API_PASS:0:63}" >"${file}" ;;
    *) return 1 ;;
  esac
}

# 사례 20 — box: 비밀번호 파일은 시작 스크립트가 쓰는 꼴(16진 64자 + 끝 줄바꿈
# 하나, 파일 전체가 그 한 줄) 하나만 받는다. 그 밖(16진 아님 · 빈 파일 ·
# 디렉터리 · 끊어진 링크 · 링크(대상이 그 꼴이어도) · 줄바꿈 없음 · 뒤에 남는
# 바이트 · 두 줄, 그리고 크기는 65바이트인데 내용이 틀린 대문자 16진 · 16진
# 아닌 글자 · 줄 끝 CR)은 모두 api-pass 로 실패하고(종료 1), 어느 갈래에서도
# 비밀이 새거나 스크래치가 남지 않는다.
case_box_api_pass_malformed() {
  local ok=0 variant
  for variant in not-hex empty dir dangling symlink no-newline junk \
    two-lines upper non-hex-64 cr; do
    setup_box
    write_api_pass_variant "${variant}" || return 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL api-pass' 1 \
      || ! expect_hygiene; then
      harness::add_reason "갈래 ${variant}"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 21 — box: 스크래치를 만들 수 없으면(TMPDIR 이 없는 자리) 판정 줄 없이
# 끝나지 않고 scratch 로 실패한다(종료 1).
case_scratch_failure() {
  local ok=0
  setup_box
  ops_env+=("TMPDIR=${ops_dir}/absent")
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL scratch' 1 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

#######################################
# 응답기 모드 Basic 머리 값(base64 — 줄바꿈 없이)을 낸다.
#######################################
ia_basic() {
  printf '%s:%s' "${IA_BASIC_USER}" "${IA_BASIC_PASS}" | /usr/bin/base64 \
    | tr -d '\n'
}

#######################################
# 실제 템플릿(mediamtx.yml.tmpl)에서 시작 스크립트와 같은 함수(bin/lib/
# render_files.sh 의 select_mode_blocks)로 응답기 모드 블록을 고르고, 토큰을
# 시작 스크립트처럼 문자열 치환으로 채워 렌더본 자리에 둔다. 잠금 두 줄은 응답기
# 모드 블록 안에 있다(사례 25 가 블록 안임을, render_test 가 고른 내용의 그
# 줄을 단언한다). 고르지 못하면 실패다.
# Arguments:
#   출력 파일.
#######################################
render_ia_mtx() {
  local body
  body="$(cat -- "${MEDIA_HOST_DIR}/mediamtx.yml.tmpl" && printf x)" \
    || return 1
  body="$(select_mode_blocks ingest-auth "${body%x}" && printf x)" || return 1
  body="${body//"${IA_PUBLISH_TOKEN}"/${IA_PUBLISH_LOCK}}"
  body="${body//"${IA_READ_TOKEN}"/${IA_READ_LOCK}}"
  body="${body//@@INGEST_AUTH_BASIC_PASS@@/${IA_BASIC_PASS}}"
  body="${body//@@HLS_CDN_SECRET@@/${OPS_FAKE_CDN_SECRET}}"
  printf '%s' "${body%x}" >"$1"
}

#######################################
# 응답기 모드 상태(setup_box 위에) — 응답기 파일 둘 · 잠금 두 줄이 든 렌더본.
# 값이 argv 로 가지 않는지 보도록 awk · head · wc · base64 · tr 도 감시
# 래퍼로 돌린다.
#######################################
setup_ingest_auth() {
  local ia name
  setup_box
  ia="${ops_dir}/runtime/ingest-auth"
  mkdir -p -- "${ia}"
  printf '%s\n' "${IA_BASIC_PASS}" >"${ia}/ingest_basic_pass"
  printf '%s\n' "${OPS_FAKE_API_PASS}" >"${ia}/mtx_api_pass"
  printf '%s\n' "${IA_RESOLVE_TOKEN}" >"${ia}/stream_key_resolve_token"
  render_ia_mtx "${ops_dir}/runtime/mediamtx.yml" \
    || harness::add_reason '응답기 모드 렌더본을 만들지 못함(모드 블록 고르기)'
  for name in awk head wc base64 tr; do
    ln -s -- "${TEST_DIR}/ops_fakebin/watch" "${ops_dir}/bin/${name}"
  done
}

#######################################
# expect_hygiene 에 응답기 모드 값 다섯의 노출 0 을 더한다.
#######################################
expect_ia_hygiene() {
  local ok=0
  expect_hygiene || ok=1
  ops_fixture::expect_no_leak "${IA_BASIC_PASS}" 'Basic 비밀번호' || ok=1
  ops_fixture::expect_no_leak "$(ia_basic)" 'Basic 머리 값' || ok=1
  ops_fixture::expect_no_leak "${IA_RESOLVE_TOKEN}" '4C 토큰' || ok=1
  ops_fixture::expect_no_leak "${IA_PUBLISH_LOCK}" '송출 잠금' || ok=1
  ops_fixture::expect_no_leak "${IA_READ_LOCK}" '읽기 잠금' || ok=1
  return "${ok}"
}

#######################################
# 값을 읽는 감시 명령(awk · head · base64)이 한 번 이상 불렸는지 본다 — 0 이면
# argv · 환경 노출 판정이 비어 있다.
#######################################
expect_watched_calls() {
  local ok=0 name
  for name in awk head base64; do
    if [[ "$(ops_fixture::count "${name}")" == 0 ]]; then
      harness::add_reason "${name} 를 부르지 않아 argv 판정 불가"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 22 — box 응답기 모드: 대조 넷(Basic 비밀번호 · 그 Basic 머리 값 ·
# 4C 토큰 · 잠금 둘)을 옛 셋(CDN · API 비밀번호 · 그 Basic 값)과 함께 센다 —
# nginx 표준 오류의 Basic 비밀번호 1 · 유닛 저널의 Basic 머리 값 2 · 응답기
# 태그 저널의 4C 토큰 1 · mediamtx 표준 출력의 송출 잠금 1 · 사이드카 태그
# 저널의 읽기 잠금 1 · janitor 태그 저널의 CDN 1 · API 비밀번호 1 = 8.
case_ia_count() {
  local ok=0
  setup_ingest_auth
  plant "${ops_dir}/state/logs.${OPS_NGINX_ID}.err" "${IA_BASIC_PASS}" 1
  plant "${ops_dir}/state/journal.u.pokeclip-media" "$(ia_basic)" 2
  plant "${ops_dir}/state/journal.t.pokeclip-media-ingest-auth" \
    "${IA_RESOLVE_TOKEN}" 1
  plant "${ops_dir}/state/logs.${OPS_MEDIAMTX_ID}.out" "${IA_PUBLISH_LOCK}" 1
  plant "${ops_dir}/state/journal.t.pokeclip-media-sidecar" \
    "${IA_READ_LOCK}" 1
  plant "${ops_dir}/state/journal.t.pokeclip-media-janitor" \
    "${OPS_FAKE_CDN_SECRET}" 1
  plant "${ops_dir}/state/journal.t.pokeclip-media-janitor" \
    "${OPS_FAKE_API_PASS}" 1
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 8' 0 || ok=1
  expect_watched_calls || ok=1
  expect_ia_hygiene || ok=1
  return "${ok}"
}

#######################################
# 응답기 모드 표지 넷(파일 둘 · 잠금 줄 둘) 중 비트가 0 인 것을 지운다.
# Arguments:
#   비트 마스크(1 Basic 파일 · 2 4C 파일 · 4 송출 잠금 줄 · 8 읽기 잠금 줄).
#######################################
drop_ia_marks() {
  local ia="${ops_dir}/runtime/ingest-auth"
  local mtx="${ops_dir}/runtime/mediamtx.yml" kept
  (( $1 & 1 )) || rm -f -- "${ia}/ingest_basic_pass"
  (( $1 & 2 )) || rm -f -- "${ia}/stream_key_resolve_token"
  kept="$(awk -v m="$1" '
    /^  srtPublishPassphrase:/ && !(m % 8 >= 4) { next }
    /^  srtReadPassphrase:/ && !(m >= 8) { next }
    { print }' "${mtx}")"
  printf '%s\n' "${kept}" >"${mtx}"
}

# 사례 23 — box: 표지 넷이 일부만 있으면(14갈래) 옛 모드로 내려가지 않고
# ingest-auth 로 실패한다(종료 1).
case_ia_partial() {
  local ok=0 mask
  for (( mask = 1; mask <= 14; mask++ )); do
    setup_ingest_auth
    drop_ia_marks "${mask}"
    plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL ingest-auth' 1 \
      || ! expect_ia_hygiene; then
      harness::add_reason "표지 ${mask}"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 응답기 모드 입력 하나를 꼴 밖으로 만든다(setup_ingest_auth 뒤).
# Arguments:
#   갈래 이름.
#######################################
write_ia_variant() {
  local ia="${ops_dir}/runtime/ingest-auth"
  local mtx="${ops_dir}/runtime/mediamtx.yml"
  local token="${ia}/stream_key_resolve_token" body
  case "$1" in
    basic-not-hex) printf 'not-a-hex-password\n' >"${ia}/ingest_basic_pass" ;;
    basic-upper)
      printf '%s\n' "${IA_BASIC_PASS}" | tr 'a-f' 'A-F' \
        >"${ia}/ingest_basic_pass"
      ;;
    basic-symlink)
      mv -- "${ia}/ingest_basic_pass" "${ia}/real"
      ln -s -- "${ia}/real" "${ia}/ingest_basic_pass"
      ;;
    token-empty) printf '\n' >"${token}" ;;
    token-no-newline) printf '%s' "${IA_RESOLVE_TOKEN}" >"${token}" ;;
    token-two-lines)
      printf '%s\n%s\n' "${IA_RESOLVE_TOKEN}" "${IA_RESOLVE_TOKEN}" >"${token}"
      ;;
    token-control) printf 'pcbench\ttoken\n' >"${token}" ;;
    token-none) printf 'None\n' >"${token}" ;;
    token-lead-space) printf ' pcbench\n' >"${token}" ;;
    token-trail-space) printf 'pcbench \n' >"${token}" ;;
    token-c1) printf 'pc\302\205bench\n' >"${token}" ;;
    token-too-large)
      head -c 4096 /dev/zero | tr '\0' 't' >"${token}"
      printf '\n' >>"${token}"
      ;;
    token-dir)
      rm -f -- "${token}"
      mkdir -- "${token}"
      ;;
    lock-double-quote)
      body="$(awk '/^  srtPublishPassphrase:/ { gsub("\047", "\"") }
        { print }' "${mtx}")"
      printf '%s\n' "${body}" >"${mtx}"
      ;;
    lock-twice)
      printf "  srtReadPassphrase: '%s'\n" "${IA_READ_LOCK}" >>"${mtx}"
      ;;
    lock-short)
      body="$(awk '/^  srtReadPassphrase:/ { sub("[0-9a-f]\047$", "\047") }
        { print }' "${mtx}")"
      printf '%s\n' "${body}" >"${mtx}"
      ;;
    mtx-symlink)
      mv -- "${mtx}" "${ops_dir}/runtime/real.yml"
      ln -s -- "${ops_dir}/runtime/real.yml" "${mtx}"
      ;;
    *) return 1 ;;
  esac
}

# 사례 24 — box 응답기 모드: 입력이 시작 스크립트가 쓰는 꼴 밖이면(Basic
# 파일 16진 64자 한 줄 아님 · 링크, 4C 파일 빔 · 끝 줄바꿈 없음 · 두 줄 · 제어
# 문자 · None · 앞 공백 · 뒤 공백 · C1 제어 문자(U+0085) · 4096바이트 초과 ·
# 디렉터리 — 시작 스크립트 read_resolve_token 이 거부하는 것과 같다, 잠금 줄
# 큰따옴표 · 두 번 · 63자, 렌더본 링크) ingest-auth 로 실패한다(종료 1). 4C
# 파일은 꼭 4096바이트까지 받는다.
case_ia_malformed() {
  local ok=0 variant token
  for variant in basic-not-hex basic-upper basic-symlink token-empty \
    token-no-newline token-two-lines token-control token-none \
    token-lead-space token-trail-space token-c1 token-too-large \
    token-dir lock-double-quote lock-twice lock-short mtx-symlink; do
    setup_ingest_auth
    write_ia_variant "${variant}" || return 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT FAIL ingest-auth' 1 \
      || ! expect_ia_hygiene; then
      harness::add_reason "갈래 ${variant}"
      ok=1
    fi
  done
  setup_ingest_auth
  token="$(head -c 4095 /dev/zero | tr '\0' 'm')"
  printf '%s\n' "${token}" \
    >"${ops_dir}/runtime/ingest-auth/stream_key_resolve_token"
  plant "${ops_dir}/state/journal.u.pokeclip-media" "${token}" 2
  run_count box || return 1
  if ! ops_fixture::expect_line 'SECRET COUNT box 2' 0; then
    harness::add_reason '4096바이트 4C 파일'
    ok=1
  fi
  return "${ok}"
}

# 사례 25 — 렌더본 꼴 대조: 실제 템플릿의 잠금 줄이 각각 정확히 한 줄이고
# 응답기 모드 블록(표지 줄 사이) 안이며, 그 줄을 시작 스크립트처럼 채운 렌더본에서 잠금
# 둘을 각각 센다(송출 1 · 읽기 2). 템플릿 authHTTPAddress 의 Basic 사용자가
# IA_BASIC_USER 와 같다(사례 22 가 그 값과 제품 상수가 같음을 센다).
case_ia_render_form() {
  local ok=0 tmpl="${MEDIA_HOST_DIR}/mediamtx.yml.tmpl" line blocks
  line="authHTTPAddress: 'http://${IA_BASIC_USER}:@@INGEST_AUTH_BASIC_PASS@@@"
  if [[ "$(grep -c -F -- "${line}" "${tmpl}")" != 1 ]]; then
    harness::add_reason "템플릿 authHTTPAddress 사용자가 ${IA_BASIC_USER} 가 아님"
    ok=1
  fi
  # 잠금 줄마다 응답기 모드 블록 안이면 1.
  blocks="$(awk '
    /^# @mode ingest-auth begin$/ { inside = 1; next }
    /^# @mode (ingest-auth|legacy) (begin|end)$/ { inside = 0; next }
    /^  srt(Publish|Read)Passphrase:/ { print inside + 0 }' "${tmpl}" \
    | paste -s -d ' ' -)"
  if [[ "${blocks}" != '1 1' ]]; then
    harness::add_reason "잠금 줄이 응답기 모드 블록 안이 아님: ${blocks}"
    ok=1
  fi
  for line in "  srtPublishPassphrase: '${IA_PUBLISH_TOKEN}'" \
    "  srtReadPassphrase: '${IA_READ_TOKEN}'"; do
    if [[ "$(grep -c -x -F -- "${line}" "${tmpl}")" != 1 ]]; then
      harness::add_reason "템플릿에 「${line}」 이 정확히 한 줄이 아님"
      ok=1
    fi
  done
  setup_ingest_auth
  if [[ "$(grep -c -x -F -- "  srtPublishPassphrase: '${IA_PUBLISH_LOCK}'" \
    "${ops_dir}/runtime/mediamtx.yml")" != 1 ]]; then
    harness::add_reason '렌더본에 채운 송출 잠금 줄이 한 줄이 아님'
    ok=1
  fi
  plant "${ops_dir}/state/logs.${OPS_NGINX_ID}.out" "${IA_PUBLISH_LOCK}" 1
  plant "${ops_dir}/state/logs.${OPS_MEDIAMTX_ID}.err" "${IA_READ_LOCK}" 2
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 3' 0 || ok=1
  expect_ia_hygiene || ok=1
  return "${ok}"
}

# 사례 26 — box 옛 모드: 표지가 하나도 없는 렌더본(옛 갈래 — 잠금 줄 없음)이
# 있어도 지금처럼 센다(CDN 1 · API 비밀번호 1 = 2).
case_legacy_with_render() {
  local ok=0
  setup_box
  printf 'hlsCDNSecret: "%s"\npathDefaults:\n  record: yes\n' \
    "${OPS_FAKE_CDN_SECRET}" >"${ops_dir}/runtime/mediamtx.yml"
  plant "${ops_dir}/state/journal.u.pokeclip-media" "${OPS_FAKE_CDN_SECRET}" 1
  plant "${ops_dir}/state/logs.${OPS_NGINX_ID}.err" "${OPS_FAKE_API_PASS}" 1
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 2' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 27 — ssm: 응답기 파일은 읽지 않는다(알려진 한계) — 표지가 일부만 있어도
# 실패하지 않고 SSM 기록만 센다.
case_ssm_ignores_ingest_auth() {
  local ok=0
  setup_ssm_one
  mkdir -p -- "${ops_dir}/runtime/ingest-auth"
  printf '%s\n' "${IA_BASIC_PASS}" \
    >"${ops_dir}/runtime/ingest-auth/ingest_basic_pass"
  run_count ssm "${COMMAND_ID}" || return 1
  ops_fixture::expect_line 'SECRET COUNT ssm 1' 0 || ok=1
  return "${ok}"
}

#######################################
# 값이 든 줄을 slog JSON 기록 꼴로 덧붙인다.
# Arguments:
#   파일, 이스케이프한 값.
#######################################
plant_json() {
  printf '{"level":"ERROR","msg":"resolve failed","token":"%s"}\n' "$2" >>"$1"
}

# 사례 28 — box 응답기 모드: 4C 토큰을 원문으로도 JSON 이스케이프 꼴(slog
# JSON 처리기 — 따옴표 · 역슬래시 · U+2028 · U+2029)로도 센다. 갈래마다 응답기
# 태그 저널의 JSON 줄 1 · nginx 표준 오류의 원문 1 = 2.
case_ia_json_escaped() {
  local ok=0 i token escaped
  for (( i = 0; i < ${#IA_JSON_TOKENS[@]}; i++ )); do
    token="${IA_JSON_TOKENS[i]}"
    escaped="${IA_JSON_ESCAPED[i]}"
    setup_ingest_auth
    printf '%s\n' "${token}" \
      >"${ops_dir}/runtime/ingest-auth/stream_key_resolve_token"
    plant_json "${ops_dir}/state/journal.t.pokeclip-media-ingest-auth" \
      "${escaped}"
    plant "${ops_dir}/state/logs.${OPS_NGINX_ID}.err" "${token}" 1
    run_count box || return 1
    if ! ops_fixture::expect_line 'SECRET COUNT box 2' 0 \
      || ! expect_ia_hygiene \
      || ! ops_fixture::expect_no_leak "${token}" '4C 토큰(갈래)' \
      || ! ops_fixture::expect_no_leak "${escaped}" '4C 토큰 JSON 꼴'; then
      harness::add_reason "갈래 ${i}"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 29 — box 응답기 모드: 값을 담는 새 지역 변수 이름(token · found · line ·
# locks)이 부모 환경에서 export 된 채 들어와도 그 이름이 자식 환경에 남지
# 않는다(쓰기 전에 지워 export 속성을 끊는다 — 남으면 local 이 그 속성을 이어
# 받는다). 값 노출 0 · 건수도 그대로(4C 토큰 1).
case_ia_inherited_export() {
  local ok=0 found_in
  setup_ingest_auth
  plant "${ops_dir}/state/journal.t.pokeclip-media-ingest-auth" \
    "${IA_RESOLVE_TOKEN}" 1
  ops_fixture::run "${SCRIPT}" token=inherited found=inherited \
    line=inherited locks=inherited -- box || return 1
  ops_fixture::expect_line 'SECRET COUNT box 1' 0 || ok=1
  expect_watched_calls || ok=1
  expect_ia_hygiene || ok=1
  found_in="$(cd -- "${ops_dir}/log" \
    && grep -l -E '^(token|found|line|locks)=' -- *.env | paste -s -d ' ' -)" \
    || true
  if [[ -n "${found_in}" ]]; then
    harness::add_reason "상속 export 이름이 자식 환경에 남음: ${found_in}"
    ok=1
  fi
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/secret_count_test.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap ops_fixture::cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ops_fixture::init secret-count

  harness::run_case 1 'box 심지 않음 → 0 · 노출 0 · grep stdin' case_box_zero
  harness::run_case 2 'box 태그 저널 2건 → 2' case_box_tag_journal
  harness::run_case 3 'box 입력마다 심은 건수 합' case_box_all_inputs
  harness::run_case 4 'box 정지한 컨테이너 로그도 셈' case_box_exited_container
  harness::run_case 5 'passphrase 는 --passphrase 에서만' case_passphrase_branch
  harness::run_case 6 'ssm 인스턴스마다 기록 대조' case_ssm
  harness::run_case 7 '비밀 못 읽음 → 실패 줄' case_secret_unavailable
  harness::run_case 8 '입력 못 읽음 → 실패 줄 · 스크래치 0' case_input_failure
  harness::run_case 9 'ssm 기록 못 읽음 → 실패 줄' case_ssm_failure
  harness::run_case 10 '인자 오류 → 사용법 · 호출 0' case_usage
  harness::run_case 11 'box 입력 호출 꼴' case_box_inputs_called
  harness::run_case 12 '상한 값 오류 → config' case_config
  harness::run_case 13 'ssm 끝나지 않은 명령 → ssm-status · Failed 는 셈' \
    case_ssm_status
  harness::run_case 14 'ssm 한도에 닿은 기록 → ssm-truncated' \
    case_ssm_truncated
  harness::run_case 15 'ssm 상태 · 출력 조회 경합 → ssm-status' \
    case_ssm_status_race
  harness::run_case 16 'box API 비밀번호 · Basic 값도 셈' case_box_api_pass
  harness::run_case 17 'box API 비밀번호 파일 없음 → api-pass · 종료 1' \
    case_box_api_pass_missing
  harness::run_case 18 'ssm 상속 export 에도 기록 첫 줄이 자식 환경에 0' \
    case_ssm_inherited_export
  harness::run_case 19 'box 파일 없음이면 CDN 이 로그에 있어도 api-pass' \
    case_box_api_pass_missing_with_cdn
  harness::run_case 20 'box API 비밀번호 파일 전체가 64 hex 한 줄이 아니면 api-pass' \
    case_box_api_pass_malformed
  harness::run_case 21 'TMPDIR 없음 → scratch' case_scratch_failure
  harness::run_case 22 'box 응답기 모드 대조 넷 심은 건수 · argv 0' case_ia_count
  harness::run_case 23 'box 응답기 표지 일부만 → ingest-auth' case_ia_partial
  harness::run_case 24 'box 응답기 입력 꼴 밖 → ingest-auth' case_ia_malformed
  harness::run_case 25 '렌더본 잠금 줄 꼴 = 실제 템플릿' case_ia_render_form
  harness::run_case 26 'box 옛 렌더본은 지금처럼' case_legacy_with_render
  harness::run_case 27 'ssm 은 응답기 파일을 읽지 않음' \
    case_ssm_ignores_ingest_auth
  harness::run_case 28 'box 4C 토큰 JSON 이스케이프 꼴도 셈' \
    case_ia_json_escaped
  harness::run_case 29 'box 새 지역 변수 이름 상속 export 가 자식 환경에 0' \
    case_ia_inherited_export

  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
