# shellcheck shell=bash
# 전역(fixture_* · FIXTURE_* 등)과 fixture::* 는 start_fixture.sh 가, chown 기록
# 도우미 auth_mode::chown_is 는 start_ingest_auth_cases.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 60일 만료 창구 토큰 사례(70 – 74). 시작 스크립트는
# CLIP_EXPIRY_TOKEN_SECRET_ARN 이 찰 때만(두 모드 공통) RUNTIME_DIR/clip-expiry
# (root 0711)를 맞추고, 앞 기동이 남긴 토큰 파일을 먼저 지운 뒤 토큰을 받아
# 사이드카 실행 계정 소유 0400 파일(token)로 둔다. 받기가 실패하면 파일 없이
# 기동을 계속한다(사이드카가 만료만 끈다 — expiry_disabled reason=token_file).
# 디렉터리가 링크면 따라가지 않고 막는다(응답기 디렉터리와 같은 규칙). 정지
# 스크립트는 그 토큰을 지운다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 가짜 aws 와 같은 값(test/fakebin/aws) — 만료 토큰 ARN 은 값이 아니다.
readonly CLIP_EXPIRY_ARN='pcbench-clip-expiry-fake-arn'
readonly CLIP_EXPIRY_TOKEN='pcbench-clip-expiry-fake-token'
readonly CLIP_EXPIRY_DIR_NAME='clip-expiry'
readonly CLIP_EXPIRY_TOKEN_NAME='token'
readonly CLIP_EXPIRY_DIR_LINE='MEDIA START FAIL render clip-expiry-dir'
readonly CLIP_STALE_TOKEN='pcbench-clip-expiry-stale-token'

# 사례 디렉터리의 만료 토큰 디렉터리 경로를 낸다.
clip_expiry::dir() {
  printf '%s/runtime/%s' "${fixture_dir}" "${CLIP_EXPIRY_DIR_NAME}"
}

#######################################
# 앞 기동이 남긴 것을 흉내 낸다 — 0755 디렉터리에 낡은 토큰 파일과 끊긴
# 렌더의 임시 파일 하나.
# Globals:
#   fixture_dir
#######################################
clip_expiry::seed_stale() {
  local dir
  dir="$(clip_expiry::dir)"
  mkdir -p -- "${dir}"
  chmod 0755 "${dir}"
  printf '%s\n' "${CLIP_STALE_TOKEN}" >"${dir}/${CLIP_EXPIRY_TOKEN_NAME}"
  printf 'partial' >"${dir}/.${CLIP_EXPIRY_TOKEN_NAME}.pcbench"
}

#######################################
# 옛 모드 기본 값에 만료 토큰 ARN 을 더해 시작 스크립트를 돌린다.
# Globals:
#   fixture_env
# Arguments:
#   가짜 명령 설정(NAME=값 …).
#######################################
clip_expiry::run() {
  fixture::base_env
  fixture_env+=("CLIP_EXPIRY_TOKEN_SECRET_ARN=${CLIP_EXPIRY_ARN}")
  fixture::run start-media.sh "$@"
}

#######################################
# 기동이 계속됐는지 본다 — 종료 0 · up 한 번 · 판정 줄에 막힘 · 실패 0.
# Globals:
#   fixture_dir, fixture_status
# Arguments:
#   행 이름.
#######################################
clip_expiry::expect_started() {
  local ok=0 ups
  ups="$(fixture::count_calls up)"
  if (( fixture_status != 0 || ups != 1 )); then
    harness::add_reason "$1: 종료 ${fixture_status} · up ${ups}회(기대 0 · 1): \
$(harness::log_tail "${fixture_dir}/out")"
    ok=1
  fi
  if grep -q -E '^MEDIA START (BLOCKED|FAIL)' "${fixture_dir}/out"; then
    harness::add_reason "$1: 막힘 · 실패 줄이 있음"
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 만료 토큰 ARN 조회 수를 낸다.
# Globals:
#   fixture_dir
#######################################
clip_expiry::aws_queries() {
  local f n=0
  for f in "${fixture_dir}"/log/aws.*.argv; do
    [[ -f "${f}" ]] || continue
    if grep -q -F -x -- "${CLIP_EXPIRY_ARN}" "${f}"; then
      n=$((n + 1))
    fi
  done
  printf '%s\n' "${n}"
}

#######################################
# 사례 70 — ARN 이 비면 토큰을 받지 않고 디렉터리도 만들지 않는다.
#######################################
clip_expiry::case_arn_empty() {
  local ok=0
  fixture::new_case
  fixture::base_env
  fixture::run start-media.sh || return 1
  clip_expiry::expect_started 'ARN 빔' || ok=1
  if [[ -e "$(clip_expiry::dir)" ]]; then
    harness::add_reason 'ARN 이 빈데 만료 토큰 디렉터리가 생김'
    ok=1
  fi
  if [[ "$(fixture::count_records aws)" != 1 ]]; then
    harness::add_reason "aws $(fixture::count_records aws)회(기대 1 — CDN 만)"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 71 — 받기 성공: 디렉터리 root 0711(-h) · 낡은 토큰과 임시 파일을 지운 뒤
# 사이드카 소유 0400 임시 파일(그 순간 최종 경로 없음)을 rename 한다. argv 에는
# ARN 만 오른다.
#######################################
clip_expiry::case_installed() {
  local ok=0 dir n state want
  fixture::new_case
  clip_expiry::seed_stale
  dir="$(clip_expiry::dir)"
  clip_expiry::run || return 1
  clip_expiry::expect_started '받기 성공' || ok=1
  if [[ "$(cat -- "${dir}/${CLIP_EXPIRY_TOKEN_NAME}" 2>/dev/null \
    && printf x)" != "${CLIP_EXPIRY_TOKEN}"$'\n'x ]]; then
    harness::add_reason '토큰 파일 내용이 「값 한 줄」 이 아님'
    ok=1
  fi
  if [[ "$(fixture::perm "${dir}/${CLIP_EXPIRY_TOKEN_NAME}" 2>/dev/null)" \
    != -r-------- || "$(fixture::perm "${dir}")" != drwx--x--x ]]; then
    harness::add_reason "권한 토큰 $(fixture::perm \
"${dir}/${CLIP_EXPIRY_TOKEN_NAME}" 2>/dev/null) · 디렉터리 \
$(fixture::perm "${dir}")(기대 -r-------- · drwx--x--x)"
    ok=1
  fi
  if [[ "$(find "${dir}" -mindepth 1 | awk 'END { print NR }')" != 1 ]]; then
    harness::add_reason '디렉터리에 토큰 밖 파일(앞 기동 임시 파일)이 남음'
    ok=1
  fi
  # 옛 모드 렌더의 chown 둘(MediaMTX · 사이드카) 뒤가 만료 칸이다.
  n="$(fixture::count_records chown)"
  if [[ "${n}" != 4 ]]; then
    harness::add_reason "chown ${n}회(기대 4)"
    ok=1
  fi
  auth_mode::chown_is 3 "-h 0:0 ${dir}" || ok=1
  auth_mode::chown_is 4 "10001:10001 ${dir}/.${CLIP_EXPIRY_TOKEN_NAME}.*" \
    || ok=1
  state="$(cat -- "${fixture_dir}/log/chown.4.state" 2>/dev/null)" || state=""
  if [[ "${state}" != 'perm=-r-------- final=absent '* ]]; then
    harness::add_reason "토큰 chown 순간 「${state}」(기대 0400 · 최종 없음)"
    ok=1
  fi
  want="secretsmanager get-secret-value --secret-id ${CLIP_EXPIRY_ARN} \
--query SecretString --output text"
  if [[ "$(paste -s -d ' ' - <"${fixture_dir}/log/aws.2.argv" \
    2>/dev/null)" != "${want}" || "$(fixture::count_records aws)" != 2 ]]; then
    harness::add_reason "aws 2 가 만료 토큰 조회 하나가 아님"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 72 — 받기 실패 · 쓸 수 없는 값: 낡은 토큰 · 임시 파일은 지워지고 새
# 파일도 없으며, 판정 줄 없이 기동을 계속한다.
#######################################
clip_expiry::case_fetch_failed() {
  local ok=0 row dir left
  for row in fail empty none space; do
    fixture::new_case
    clip_expiry::seed_stale
    dir="$(clip_expiry::dir)"
    if [[ "${row}" == space ]]; then
      fixture::value_file " ${CLIP_EXPIRY_TOKEN}"
      clip_expiry::run PCBENCH_FAKE_AWS_CLIP_EXPIRY_MODE=value \
        "PCBENCH_FAKE_AWS_CLIP_EXPIRY_VALUE_FILE=${fixture_value_file}" \
        || return 1
    else
      clip_expiry::run "PCBENCH_FAKE_AWS_CLIP_EXPIRY_MODE=${row}" || return 1
    fi
    clip_expiry::expect_started "${row}" || ok=1
    left="$(find "${dir}" -mindepth 1 | awk 'END { print NR }')"
    if [[ "${left}" != 0 ]]; then
      harness::add_reason "${row}: 만료 토큰 디렉터리에 ${left}개가 남음(기대 0)"
      ok=1
    fi
    if [[ "$(clip_expiry::aws_queries)" != 1 ]]; then
      harness::add_reason "${row}: 만료 토큰 조회 $(clip_expiry::aws_queries)회"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 73 — 디렉터리가 링크면 따라가지 않는다: 막고(up 0), 토큰을 받지 않으며,
# 링크 대상의 파일 · 권한은 그대로다.
#######################################
clip_expiry::case_dir_link() {
  local ok=0 elsewhere
  fixture::new_case
  elsewhere="${fixture_dir}/elsewhere"
  mkdir -p -- "${elsewhere}" "${fixture_dir}/runtime"
  chmod 0755 "${elsewhere}"
  printf 'keep\n' >"${elsewhere}/${CLIP_EXPIRY_TOKEN_NAME}"
  ln -s -- "${elsewhere}" "$(clip_expiry::dir)"
  clip_expiry::run || return 1
  fixture::expect_blocked "${CLIP_EXPIRY_DIR_LINE}" || ok=1
  if [[ "$(cat -- "${elsewhere}/${CLIP_EXPIRY_TOKEN_NAME}")" != keep \
    || "$(fixture::perm "${elsewhere}")" != drwxr-xr-x \
    || "$(find "${elsewhere}" -mindepth 1 | awk 'END { print NR }')" != 1 ]]
  then
    harness::add_reason '링크 대상의 파일 · 권한이 바뀜'
    ok=1
  fi
  if [[ "$(clip_expiry::aws_queries)" != 0 ]]; then
    harness::add_reason '링크인데 만료 토큰을 받음'
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 74 — 정지: 만료 토큰과 그 임시 파일을 지우고 디렉터리는 남긴다.
# 디렉터리가 링크면 따라가지 않고 CLEANUP FAIL 로 적는다.
#######################################
clip_expiry::case_stop() {
  local ok=0 dir elsewhere
  fixture::new_case
  fixture::base_env
  clip_expiry::seed_stale
  dir="$(clip_expiry::dir)"
  fixture::run stop-media.sh -- --cleanup-only || return 1
  if (( fixture_status != 0 )) || [[ ! -d "${dir}" \
    || "$(find "${dir}" -mindepth 1 | awk 'END { print NR }')" != 0 ]]; then
    harness::add_reason "정지 뒤 종료 ${fixture_status} · 디렉터리에 남은 \
것이 있거나 디렉터리가 없음: $(harness::log_tail "${fixture_dir}/out")"
    ok=1
  fi
  fixture::new_case
  fixture::base_env
  elsewhere="${fixture_dir}/elsewhere"
  mkdir -p -- "${elsewhere}" "${fixture_dir}/runtime"
  printf 'keep\n' >"${elsewhere}/${CLIP_EXPIRY_TOKEN_NAME}"
  dir="$(clip_expiry::dir)"
  ln -s -- "${elsewhere}" "${dir}"
  fixture::run stop-media.sh -- --cleanup-only || return 1
  if (( fixture_status == 0 )) || ! grep -q -F -x -- \
    "MEDIA STOP CLEANUP FAIL ${dir}" "${fixture_dir}/out"; then
    harness::add_reason "링크인데 CLEANUP FAIL 이 아님(종료 \
${fixture_status}): $(harness::log_tail "${fixture_dir}/out")"
    ok=1
  fi
  if [[ "$(cat -- "${elsewhere}/${CLIP_EXPIRY_TOKEN_NAME}")" != keep ]]; then
    harness::add_reason '링크 대상의 토큰이 지워짐'
    ok=1
  fi
  return "${ok}"
}
