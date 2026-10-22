#!/usr/bin/env bash
#
# bin/loopback-health.sh 시험 — 비밀 노출 범위와 갈래: 공개 토큰은 인자, 기본은
# passphrase 없이 송출(송출 인증 반영 전 상자는 익명 publish), passphrase 갈래는
# --passphrase. 송출기는 다이제스트 고정 linuxserver/ffmpeg 을 docker run --rm.
#
# 스크립트를 빈 환경(env -i)에서 돌린다. PATH 맨 앞에는 가짜 docker(ps · run —
# run 은 송출 중 표식을 두고 TERM 까지 돈다) · aws · curl(--config - 의 표준
# 입력을 기록하고, 송출 중이면 200) 과 grep 감시 래퍼만 둔다.
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/loopback_test.sh
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

readonly SCRIPT='bin/loopback-health.sh'
readonly CASE_COUNT=14
readonly STREAM_ID='01JTESTSTREAMKEYABCDEFGHJK'
readonly LIVE_PORT=28181
FAKE_DIGEST="sha256:$(printf '4%.0s' {1..64})"
readonly FAKE_DIGEST
readonly SENDER_REPO='registry.pcbench-edge-proxy.invalid/media-sender'
readonly SENDER_IMAGE="${SENDER_REPO}@${FAKE_DIGEST}"
readonly IMAGES_FILE="${MEDIA_HOST_DIR}/images.json"

#######################################
# 기본 상태 — 비밀 둘 · mediamtx 컨테이너 · 설정 값.
#######################################
setup_loopback() {
  ops_fixture::new_case
  ops_fixture::secret "${OPS_FAKE_CDN_ARN}" "${OPS_FAKE_CDN_SECRET}"
  ops_fixture::secret "${OPS_FAKE_KEY_ARN}" "${OPS_FAKE_PASSPHRASE}"
  ops_fixture::container "${OPS_MEDIAMTX_ID}" mediamtx running
  ops_env+=("CDN_SECRET_ARN=${OPS_FAKE_CDN_ARN}"
    "TEST_STREAM_KEY_SECRET_ARN=${OPS_FAKE_KEY_ARN}"
    "LOOPBACK_SENDER_IMAGE=${SENDER_IMAGE}"
    "MEDIA_LIVE_PORT=${LIVE_PORT}"
    "LOOPBACK_WAIT_SECONDS=5")
}

#######################################
# 스크립트를 돌린다.
# Arguments:
#   가짜 설정(NAME=값 …), --, 스크립트 인자 ….
#######################################
run_loopback() {
  ops_fixture::run "${SCRIPT}" "$@"
}

#######################################
# docker run 기록 파일(송출기 호출)을 낸다.
#######################################
run_record() {
  local f
  while IFS= read -r f; do
    if [[ "$(head -n 1 "${f}")" == run ]]; then
      printf '%s\n' "${f}"
    fi
  done < <(ops_fixture::calls docker)
}

#######################################
# argv 파일에서 연속한 두 줄(옵션 · 값)이 있는지 본다.
# Arguments:
#   argv 파일, 옵션, 값.
#######################################
has_pair() {
  awk -v o="$2" -v v="$3" 'prev == o && $0 == v { found = 1 } { prev = $0 }
    END { exit !found }' "$1"
}

#######################################
# 송출기 호출이 하나이고 그 argv 가 정한 꼴인지 본다 — 고유 이름 · 같은 값의
# 라벨 · mediamtx 망 칸 · 권한 축소 옵션 전부 · 금지 옵션 0 · 고정 이미지 ·
# ffmpeg 조용히 · srt 루프백 주소 · GOP.
# Arguments:
#   기대 SRT URL.
#######################################
expect_sender_argv() {
  local ok=0 f opt image_line pair name
  f="$(run_record)"
  if [[ -z "${f}" || "$(printf '%s\n' "${f}" | awk 'END { print NR }')" != 1 ]]
  then
    harness::add_reason "송출기 docker run 이 한 번이 아님"
    return 1
  fi
  image_line="$(grep -n -x -F -- "${SENDER_IMAGE}" "${f}" | head -n 1)"
  image_line="${image_line%%:*}"
  if [[ -z "${image_line}" ]]; then
    harness::add_reason 'docker run 에 고정 이미지가 없음'
    return 1
  fi
  name="$(awk 'prev == "--name" { print; exit } { prev = $0 }' "${f}")"
  if [[ ! "${name}" =~ ^pokeclip-loopback-[0-9]+-[0-9]+$ ]]; then
    harness::add_reason "송출기 이름이 고유 꼴이 아님: ${name:-없음}"
    ok=1
  fi
  for pair in "--label com.pokeclip.loopback=${name}" \
    "--network container:${OPS_MEDIAMTX_ID}" '--pull never' '--cap-drop ALL' \
    '--security-opt no-new-privileges:true' \
    '--tmpfs /tmp:rw,noexec,nosuid,size=16m' '--pids-limit 64' \
    '--memory 256m' '--user 65534:65534' \
    '--entrypoint /usr/local/bin/ffmpeg'; do
    if ! head -n "$((image_line - 1))" "${f}" >"${ops_dir}/run.options" \
      || ! has_pair "${ops_dir}/run.options" "${pair%% *}" "${pair#* }"; then
      harness::add_reason "docker run 에 「${pair}」 가 없음"
      ok=1
    fi
  done
  for opt in --rm --read-only; do
    if ! head -n "$((image_line - 1))" "${f}" | grep -q -x -F -- "${opt}"; then
      harness::add_reason "docker run 에 ${opt} 가 없음"
      ok=1
    fi
  done
  for opt in -e --env --env-file -v --volume --mount --privileged --cap-add \
    -p --publish --device --network=host; do
    if head -n "$((image_line - 1))" "${f}" | grep -q -x -F -- "${opt}"; then
      harness::add_reason "docker run 에 금지 옵션 ${opt}"
      ok=1
    fi
  done
  if [[ "$(sed -n "$((image_line + 1)),$((image_line + 3))p" "${f}" \
    | paste -s -d ' ' -)" != '-hide_banner -loglevel quiet' ]]; then
    harness::add_reason 'ffmpeg 이 -hide_banner -loglevel quiet 로 시작하지 않음'
    ok=1
  fi
  if [[ "$(tail -n 1 "${f}")" != "$1" ]]; then
    harness::add_reason "SRT URL 이 다름: $(tail -n 1 "${f}")"
    ok=1
  fi
  if ! has_pair "${f}" -g 60; then
    harness::add_reason 'GOP -g 60 이 없음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# curl 이 Bearer · URL 을 표준 입력(--config -)으로만 받았는지 본다.
#######################################
expect_curl_stdin() {
  local ok=0 f n=0
  for f in "${ops_dir}"/log/curl.*.stdin; do
    [[ -f "${f}" ]] || continue
    n=$((n + 1))
    if ! grep -q -x -F -- \
      "header = \"Authorization: Bearer ${OPS_FAKE_CDN_SECRET}\"" "${f}" \
      || ! grep -q -x -F -- \
      "url = \"http://127.0.0.1:${LIVE_PORT}/${STREAM_ID}/index.m3u8\"" "${f}"
    then
      harness::add_reason "curl 표준 입력에 Bearer · url 줄이 없음(${f##*/})"
      ok=1
    fi
    if ! grep -q -x -F -- 'noproxy = "*"' "${f}"; then
      harness::add_reason "curl 설정에 noproxy 가 없음(${f##*/})"
      ok=1
    fi
    if printf '%s\n' "${OPS_FAKE_PASSPHRASE}" | /usr/bin/grep -q -F -f - "${f}"
    then
      harness::add_reason 'curl 표준 입력에 passphrase'
      ok=1
    fi
  done
  if (( n == 0 )); then
    harness::add_reason 'curl 을 부르지 않음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 송출기가 끝까지 정리됐는지 본다 — docker run 이 TERM 을 받았고, 그 이름으로
# rm -f 했고, 컨테이너 · 송출 중 표식이 남지 않았다(남은 송출기가 상자에 쌓이지
# 않게).
#######################################
expect_sender_stopped() {
  local ok=0 f found=0 name
  if [[ ! -e "${ops_dir}/state/run.terminated" ]]; then
    harness::add_reason '송출기 docker run 이 TERM 을 받지 않음'
    ok=1
  fi
  if [[ -e "${ops_dir}/state/sending" ]] \
    || ls "${ops_dir}"/state/sender.* >/dev/null 2>&1; then
    harness::add_reason '송출기 컨테이너 · 송출 중 표식이 남음'
    ok=1
  fi
  name="$(awk 'prev == "--name" { print; exit } { prev = $0 }' \
    "$(run_record | head -n 1)")"
  while IFS= read -r f; do
    if [[ "$(paste -s -d ' ' - <"${f}")" == "rm -f ${name}" ]]; then
      found=1
    fi
  done < <(ops_fixture::calls docker)
  if (( !found )) || [[ -z "${name}" ]]; then
    harness::add_reason "송출기 이름(${name:-없음})으로 rm -f 하지 않음"
    ok=1
  fi
  return "${ok}"
}

# 사례 1 — 기본(passphrase 없음): LOOPBACK OK 한 줄 · 송출기 argv 꼴 · Bearer 는
# curl 표준 입력만 · 시험 키는 조회하지 않음 · 송출기 정리.
case_default_ok() {
  local ok=0
  setup_loopback
  run_loopback -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK OK' 0 || ok=1
  expect_sender_argv \
    "srt://127.0.0.1:8890?streamid=#!::r=${STREAM_ID},m=publish" || ok=1
  expect_curl_stdin || ok=1
  ops_fixture::expect_no_leak "${OPS_FAKE_CDN_SECRET}" 'CDN 비밀' || ok=1
  if grep -q -x -F "${OPS_FAKE_KEY_ARN}" "${ops_dir}"/log/aws.*.argv; then
    harness::add_reason '기본 갈래에서 시험 키를 조회함'
    ok=1
  fi
  if ops_fixture::leaks "${OPS_FAKE_PASSPHRASE}" | grep -q .; then
    harness::add_reason '기본 갈래에서 passphrase 가 어딘가에 실림'
    ok=1
  fi
  expect_sender_stopped || ok=1
  return "${ok}"
}

# 사례 2 — --passphrase: passphrase 는 송출기 argv 한 곳에만(받아들인
# 노출 범위), CDN 비밀은 그대로 0.
case_passphrase_ok() {
  local ok=0 found
  setup_loopback
  run_loopback -- --passphrase "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK OK' 0 || ok=1
  local base="srt://127.0.0.1:8890?streamid=#!::r=${STREAM_ID},m=publish"
  expect_sender_argv \
    "${base}&passphrase=${OPS_FAKE_PASSPHRASE}&pbkeylen=32" || ok=1
  expect_curl_stdin || ok=1
  ops_fixture::expect_no_leak "${OPS_FAKE_CDN_SECRET}" 'CDN 비밀' || ok=1
  found="$(ops_fixture::leaks "${OPS_FAKE_PASSPHRASE}" | paste -s -d ' ' -)"
  if [[ "${found}" != "$(run_record | sed "s#^${ops_dir}/##")" ]]; then
    harness::add_reason "passphrase 노출 지점이 송출기 argv 하나가 아님: ${found}"
    ok=1
  fi
  expect_sender_stopped || ok=1
  return "${ok}"
}

# 사례 3 — --passphrase 에서 시험 키를 못 읽으면 시험 실패 · 송출 0 · curl 0.
case_test_key_unavailable() {
  local ok=0 value
  for value in missing '' None 'short' 'has space in key value'; do
    setup_loopback
    if [[ "${value}" == missing ]]; then
      rm -f -- "${ops_dir}/state/secret.${OPS_FAKE_KEY_ARN}"
    else
      ops_fixture::secret "${OPS_FAKE_KEY_ARN}" "${value}"
    fi
    run_loopback -- --passphrase "${STREAM_ID}" || return 1
    if ! ops_fixture::expect_line 'LOOPBACK FAIL test-key' nonzero; then
      harness::add_reason "(${value:-빈 값})"
      ok=1
    fi
    if [[ "$(run_record)" != "" || "$(ops_fixture::count curl)" != 0 ]]; then
      harness::add_reason "${value:-빈 값}: 송출 · curl 을 부름"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 4 — CDN 비밀을 못 읽으면 실패 · 송출 0.
case_cdn_unavailable() {
  local ok=0 value
  for value in missing '' $'two\nlines' 'bad"quote'; do
    setup_loopback
    if [[ "${value}" == missing ]]; then
      rm -f -- "${ops_dir}/state/secret.${OPS_FAKE_CDN_ARN}"
    else
      ops_fixture::secret "${OPS_FAKE_CDN_ARN}" "${value}"
    fi
    run_loopback -- "${STREAM_ID}" || return 1
    ops_fixture::expect_line 'LOOPBACK FAIL cdn-secret' nonzero || ok=1
    if [[ "$(run_record)" != "" ]]; then
      harness::add_reason "송출함(${value:-빈 값})"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 5 — 재생 목록이 상한 안에 200 이 안 되면 playlist 실패 · 상한 안에
# 끝남 · 송출기 정리.
case_playlist_never() {
  local ok=0 start
  setup_loopback
  start="${SECONDS}"
  run_loopback PCBENCH_FAKE_CURL_MODE=never -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK FAIL playlist' nonzero || ok=1
  if (( SECONDS - start > 20 )); then
    harness::add_reason "대기 5초인데 $((SECONDS - start))초 걸림"
    ok=1
  fi
  expect_sender_stopped || ok=1
  setup_loopback
  run_loopback PCBENCH_FAKE_CURL_MODE=fail -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK FAIL playlist' nonzero || ok=1
  return "${ok}"
}

# 사례 6 — 송출기가 먼저 끝나면(이미지 받기 실패 등) sender 실패.
case_sender_fails() {
  setup_loopback
  run_loopback PCBENCH_FAKE_RUN_MODE=fail -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK FAIL sender' nonzero
}

# 사례 7 — mediamtx 컨테이너가 없거나 둘이면 송출하지 않는다.
case_mediamtx_lookup() {
  local ok=0
  setup_loopback
  rm -f -- "${ops_dir}/state/ps.mediamtx"
  run_loopback -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK FAIL mediamtx' nonzero || ok=1
  setup_loopback
  ops_fixture::container pcbenchmediamtx02 mediamtx running
  run_loopback -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK FAIL mediamtx' nonzero || ok=1
  if [[ "$(run_record)" != "" ]]; then
    harness::add_reason 'mediamtx 를 못 정했는데 송출함'
    ok=1
  fi
  return "${ok}"
}

# 사례 8 — 인자 오류 → 사용법(종료 2) · 아무것도 부르지 않음.
case_usage() {
  local ok=0 args
  for args in '' '01JTOOSHORT' '01JTESTSTREAMKEYABCDEFGHJI' \
    "${STREAM_ID} extra" '--passphrase' "--other ${STREAM_ID}" \
    'not/a/stream-id-at-all-xxxx'; do
    setup_loopback
    # shellcheck disable=SC2086 # 단어로 쪼개는 것이 뜻이다.
    run_loopback -- ${args} || return 1
    if ! ops_fixture::expect_line 'LOOPBACK FAIL usage' 2; then
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

# 사례 9 — 설정 값(송출기 이미지 · live 포트 · 대기 · 상한)이 틀리면 config
# 실패 · 비밀 조회 0.
case_config() {
  local ok=0 setting
  for setting in 'LOOPBACK_SENDER_IMAGE=' \
    'LOOPBACK_SENDER_IMAGE=linuxserver/ffmpeg:latest' \
    "LOOPBACK_SENDER_IMAGE=-x@${FAKE_DIGEST}" \
    'MEDIA_LIVE_PORT=' 'MEDIA_LIVE_PORT=0' 'MEDIA_LIVE_PORT=70000' \
    'MEDIA_LIVE_PORT=80a' 'LOOPBACK_WAIT_SECONDS=0' \
    'LOOPBACK_WAIT_SECONDS=301' 'MEDIA_OPS_TIMEOUT=x'; do
    setup_loopback
    run_loopback "${setting}" -- "${STREAM_ID}" || return 1
    if ! ops_fixture::expect_line 'LOOPBACK FAIL config' nonzero; then
      harness::add_reason "(${setting})"
      ok=1
    fi
    if [[ "$(ops_fixture::count aws)" != 0 ]]; then
      harness::add_reason "${setting}: 비밀을 조회함"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 10 — 송출기 이미지 정본: images.json 의 loopback_sender 항목이
# linuxserver/ffmpeg 태그와 멀티 아키텍처 인덱스 다이제스트를 가진다.
case_images_json() {
  local src digest
  if [[ ! -f "${IMAGES_FILE}" ]]; then
    harness::add_reason 'images.json 없음'
    return 1
  fi
  src="$(jq -r '.loopback_sender.source // empty' "${IMAGES_FILE}")" || src=""
  digest="$(jq -r '.loopback_sender.digest // empty' "${IMAGES_FILE}")" \
    || digest=""
  if [[ ! "${src}" =~ ^docker\.io/linuxserver/ffmpeg:[A-Za-z0-9._-]+$ ]]; then
    harness::add_reason "loopback_sender.source 가 linuxserver/ffmpeg 태그가 아님: \
${src:-없음}"
    return 1
  fi
  if [[ ! "${digest}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    harness::add_reason 'loopback_sender.digest 가 sha256 다이제스트가 아님'
    return 1
  fi
}

# 사례 11 — 긴 대기(60초) 중에 TERM 을 받으면 곧바로(8초 안) 송출기를 정리하고
# 끝난다(대기가 명령 치환 안이면 트랩이 대기 끝까지 미뤄진다).
case_signal_cleanup() {
  local ok=0 pid deadline sent_at
  setup_loopback
  harness::spawn_group env -i "HOME=${ops_dir}/home" \
    "PATH=${ops_dir}/bin:/usr/bin:/bin" "TMPDIR=${ops_dir}/tmp" \
    "PCBENCH_FAKE_LIB=${TEST_DIR}/ops_fakebin/record.sh" \
    "PCBENCH_FAKE_LOG_DIR=${ops_dir}/log" \
    "PCBENCH_FAKE_STATE_DIR=${ops_dir}/state" \
    "PCBENCH_FAKE_PROJECT=${OPS_FAKE_PROJECT}" "PCBENCH_FAKE_CURL_MODE=never" \
    "${ops_env[@]}" LOOPBACK_WAIT_SECONDS=60 \
    "${MEDIA_HOST_DIR}/${SCRIPT}" "${STREAM_ID}" \
    </dev/null >"${ops_dir}/out" 2>"${ops_dir}/err"
  pid="${harness_spawned_pid}"
  deadline=$((SECONDS + 10))
  while [[ ! -e "${ops_dir}/state/sending" ]] && (( SECONDS < deadline )); do
    sleep 0.2
  done
  if [[ ! -e "${ops_dir}/state/sending" ]]; then
    harness::add_reason '송출이 시작되지 않음'
    harness::terminate "${pid}"
    return 1
  fi
  # 대기 루프 안으로 들어간 뒤에 보낸다.
  sleep 2
  sent_at="${SECONDS}"
  kill -TERM "${pid}" 2>/dev/null || true
  if ! harness::wait_exit "${pid}" 8; then
    harness::add_reason 'TERM 뒤 8초 안에 안 끝남'
    harness::terminate "${pid}"
    ok=1
  elif (( SECONDS - sent_at > 8 )); then
    harness::add_reason "TERM 뒤 $((SECONDS - sent_at))초 걸림"
    ok=1
  fi
  expect_sender_stopped || ok=1
  return "${ok}"
}

# 사례 12 — 컨테이너가 TERM 에 안 끝나 docker run 클라이언트만 죽어도(stuck),
# 그 이름으로 rm -f 해 남기지 않는다.
case_sender_stuck_removed() {
  local ok=0
  setup_loopback
  run_loopback PCBENCH_FAKE_RUN_MODE=stuck -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK OK' 0 || ok=1
  if [[ ! -e "${ops_dir}/state/rm.done" ]]; then
    harness::add_reason 'rm -f 가 컨테이너를 지우지 않음'
    ok=1
  fi
  expect_sender_stopped || ok=1
  return "${ok}"
}

# 사례 13 — 송출기 컨테이너를 지우지 못하면 OK 가 아니라 sender-cleanup.
case_sender_cleanup_failure() {
  local ok=0
  setup_loopback
  run_loopback PCBENCH_FAKE_RUN_MODE=stuck PCBENCH_FAKE_RM_MODE=fail \
    -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK FAIL sender-cleanup' nonzero || ok=1
  return "${ok}"
}

# 사례 14 — 송출기 이미지는 대기 전에 따로 받는다: 받기 실패면 pull 실패 ·
# 송출 0. docker 는 DOCKER_CONFIG 기본값(/root/.docker)을 받고, 주입한 값은
# 그대로 받는다.
case_pull_and_docker_config() {
  local ok=0 f pull_n run_n n=0
  setup_loopback
  run_loopback PCBENCH_FAKE_PULL_MODE=fail -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK FAIL pull' nonzero || ok=1
  if [[ -n "$(run_record)" ]]; then
    harness::add_reason '받기 실패인데 송출함'
    ok=1
  fi
  setup_loopback
  run_loopback -- "${STREAM_ID}" || return 1
  ops_fixture::expect_line 'LOOPBACK OK' 0 || ok=1
  pull_n=0
  run_n=0
  while IFS= read -r f; do
    n=$((n + 1))
    case "$(head -n 1 "${f}")" in
      pull)
        pull_n="${n}"
        if [[ "$(paste -s -d ' ' - <"${f}")" \
          != "pull --quiet ${SENDER_IMAGE}" ]]; then
          harness::add_reason 'pull 꼴이 「pull --quiet <이미지>」 가 아님'
          ok=1
        fi
        ;;
      run) run_n="${n}" ;;
    esac
    if ! grep -q -x -F 'DOCKER_CONFIG=/root/.docker' "${f%.argv}.env"; then
      harness::add_reason "docker 환경에 기본 DOCKER_CONFIG 가 없음(${f##*/})"
      ok=1
    fi
  done < <(ops_fixture::calls docker)
  if (( pull_n == 0 || run_n == 0 || pull_n > run_n )); then
    harness::add_reason 'pull 이 run 보다 먼저가 아님'
    ok=1
  fi
  setup_loopback
  run_loopback "DOCKER_CONFIG=${ops_dir}/docker-config" -- "${STREAM_ID}" \
    || return 1
  if ! grep -q -x -F "DOCKER_CONFIG=${ops_dir}/docker-config" \
    "${ops_dir}/log/docker.1.env"; then
    harness::add_reason '주입한 DOCKER_CONFIG 를 docker 가 받지 않음'
    ok=1
  fi
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/loopback_test.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap ops_fixture::cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ops_fixture::init loopback

  harness::run_case 1 '기본(passphrase 없음) → OK · argv 꼴 · Bearer stdin' \
    case_default_ok
  harness::run_case 2 '--passphrase → passphrase 는 송출기 argv 한 곳만' \
    case_passphrase_ok
  harness::run_case 3 '시험 키 못 읽음 → test-key · 송출 0' \
    case_test_key_unavailable
  harness::run_case 4 'CDN 비밀 못 읽음 → cdn-secret · 송출 0' \
    case_cdn_unavailable
  harness::run_case 5 '재생 목록 200 아님 → playlist · 상한 · 정리' \
    case_playlist_never
  harness::run_case 6 '송출기 먼저 끝남 → sender' case_sender_fails
  harness::run_case 7 'mediamtx 0 · 2 → 송출 0' case_mediamtx_lookup
  harness::run_case 8 '인자 오류 → 사용법 · 호출 0' case_usage
  harness::run_case 9 '설정 값 오류 → config · 조회 0' case_config
  harness::run_case 10 'images.json loopback_sender 핀' case_images_json
  harness::run_case 11 '긴 대기 중 TERM → 8초 안 정리' case_signal_cleanup
  harness::run_case 12 '컨테이너가 안 끝나도 rm -f 로 정리' \
    case_sender_stuck_removed
  harness::run_case 13 '송출기 못 지움 → sender-cleanup' \
    case_sender_cleanup_failure
  harness::run_case 14 '이미지 따로 받기 · DOCKER_CONFIG 기본값' \
    case_pull_and_docker_config

  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
