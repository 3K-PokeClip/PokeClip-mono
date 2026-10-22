#!/usr/bin/env bash
#
# bin/secret-count.sh 시험 — box 입력(유닛 저널 + 태그 저널 셋 + mediamtx ·
# nginx docker logs — 사이드카는 다시 뜬 앞 컨테이너 로그까지 남는 태그 저널로) ·
# passphrase 대조는 --passphrase 갈래에서만 · box 의 사이드카 API 비밀번호
# 대조(시작 스크립트가 RUNTIME_DIR 에 둔 이번 기동의 값 · Basic 꼴).
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

readonly SCRIPT='bin/secret-count.sh'
readonly CASE_COUNT=20
# 사이드카 API 사용자 이름(compose MTX_API_USER) — Basic 값의 앞부분.
readonly SIDECAR_API_USER='pokeclip-sidecar'
readonly COMMAND_ID='0123abcd-4567-89ef-0123-456789abcdef'
readonly INSTANCE_A='i-0123456789abcdef0'
readonly INSTANCE_B='i-0fedcba9876543210'
readonly TAGS=(pokeclip-media-sidecar pokeclip-media-ingest-auth
  pokeclip-media-janitor)

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

# 사례 17 — box: 비밀번호 파일이 없으면(정지 뒤 — 정지 스크립트가 지운다)
# 실패하지 않고 CDN 비밀은 그대로 센다. 판정 줄의 모드 칸이 box 가 아니라
# box-api-pass-skipped 라, 「box 0」 으로 읽혀 전부 깨끗하다고 오독되지 않는다.
case_box_api_pass_missing() {
  local ok=0
  setup_box
  rm -f -- "${ops_dir}/runtime/mtx_api_pass"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box-api-pass-skipped 0' 0 || ok=1
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

# 사례 19 — box: 비밀번호 파일이 없어도 로그에 든 CDN 비밀은 센다(정지한
# 컨테이너 로그 1 · 사이드카 태그 저널 1 = 2).
case_box_api_pass_missing_counts_cdn() {
  local ok=0
  setup_box
  rm -f -- "${ops_dir}/runtime/mtx_api_pass"
  ops_fixture::container pcbenchmediamtxold mediamtx exited
  plant "${ops_dir}/state/logs.pcbenchmediamtxold.out" \
    "${OPS_FAKE_CDN_SECRET}" 1
  plant "${ops_dir}/state/journal.t.pokeclip-media-sidecar" \
    "${OPS_FAKE_CDN_SECRET}" 1
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT box-api-pass-skipped 2' 0 || ok=1
  expect_hygiene || ok=1
  return "${ok}"
}

# 사례 20 — box: 비밀번호 파일이 있는데 꼴(16진 64자) 밖 · 빈 파일 · 읽을 수
# 없는 자리(디렉터리)면 생략하지 않고 api-pass 로 실패한다(fail closed).
case_box_api_pass_malformed() {
  local ok=0
  setup_box
  printf 'not-a-hex-password\n' >"${ops_dir}/runtime/mtx_api_pass"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL api-pass' nonzero || ok=1
  setup_box
  : >"${ops_dir}/runtime/mtx_api_pass"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL api-pass' nonzero || ok=1
  setup_box
  rm -f -- "${ops_dir}/runtime/mtx_api_pass"
  mkdir -- "${ops_dir}/runtime/mtx_api_pass"
  run_count box || return 1
  ops_fixture::expect_line 'SECRET COUNT FAIL api-pass' nonzero || ok=1
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
  harness::run_case 17 'box API 비밀번호 파일 없음 → 생략 표시 · 종료 0' \
    case_box_api_pass_missing
  harness::run_case 18 'ssm 상속 export 에도 기록 첫 줄이 자식 환경에 0' \
    case_ssm_inherited_export
  harness::run_case 19 'box 파일 없음에도 CDN 비밀 건수' \
    case_box_api_pass_missing_counts_cdn
  harness::run_case 20 'box API 비밀번호 꼴 밖 · 빈 파일 · 디렉터리 → api-pass' \
    case_box_api_pass_malformed

  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
