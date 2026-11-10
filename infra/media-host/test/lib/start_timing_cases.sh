# shellcheck shell=bash
# 전역(fixture_dir · fixture_status · FIXTURE_PROJECT 등)과 fixture::* 는
# start_fixture.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 기동 전 검사 사례(65 – 69 — POK-196 PDT 설계 r7 9.4). 렌더 뒤 ·
# compose up 앞에서 사이드카 이미지(compose 렌더본의 sidecar 서비스 image)를 받아
# 라벨 com.pokeclip.timing.readable 을 읽는다. 라벨에 판 2 가 있으면 검사 서비스를
# 부르지 않고 up 한다(받기가 실패하면 다이제스트로 고정한 로컬 이미지의 라벨).
# 없으면(라벨 없음 · 꼴 밖 · 로컬에도 없음 포함) timing-preflight
# 를 run --rm --no-deps 로 한 번 돌려 0 일 때만 up 하고, 그 밖(3 거부 · 4 검사 못
# 함 · 검사 명령 없는 이미지의 다른 코드)은 BLOCKED 줄을 남기고 up 하지 않는다.
#
# 가짜 docker 의 답은 PCBENCH_FAKE_TIMING_LABEL(라벨 — 기본 null,2) ·
# PCBENCH_FAKE_PULL_STATUS · PCBENCH_FAKE_INSPECT_STATUS ·
# PCBENCH_FAKE_RUN_STATUS 로 정한다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly TIMING_SIDECAR_IMAGE='registry.pcbench-edge-proxy.invalid/'\
'media-sidecar@sha256:'\
'3333333333333333333333333333333333333333333333333333333333333333'
readonly TIMING_BLOCKED_PREFIX='MEDIA START BLOCKED: sidecar cannot read'\
' ledger timing v2'
readonly TIMING_LABEL_KEY='com.pokeclip.timing.readable'

#######################################
# 사례 디렉터리에 사이드카 서비스 하나뿐인 compose 렌더본을 둔다(옛 모드 — 응답기
# 서비스 키 줄 없음).
# Globals:
#   fixture_dir
#######################################
timing::write_compose() {
  printf 'services:\n  sidecar:\n    image: %s\n' "${TIMING_SIDECAR_IMAGE}" \
    >"${fixture_dir}/compose.yml"
}

#######################################
# 이번 실행의 docker 호출 가운데 첫 낱말(compose 면 하위 명령)이 같은 것의 argv 를
# 한 줄씩(인자 사이 공백) 낸다.
# Globals:
#   fixture_dir
# Arguments:
#   pull | image | run | up.
#######################################
timing::calls() {
  local f
  for f in "${fixture_dir}"/log/docker.*.argv; do
    [[ -f "${f}" ]] || continue
    if [[ "$(head -n 1 -- "${f}")" == "$1" \
      || "$(fixture::compose_subcommand "${f}")" == "$1" ]]; then
      paste -s -d ' ' - <"${f}"
    fi
  done
}

#######################################
# 기동 전 검사를 한 번 돌린다(사례 디렉터리 · 렌더본 · 기본 값 · 가짜 설정).
# Arguments:
#   가짜 명령 설정(NAME=값 …).
#######################################
timing::run() {
  fixture::new_case
  timing::write_compose
  fixture::base_env
  fixture::run start-media.sh "$@"
}

#######################################
# 받기 · 라벨 읽기 호출이 렌더본의 사이드카 이미지 하나씩인지 본다.
# Returns:
#   0 / 1 어긋남.
#######################################
timing::expect_label_read() {
  local ok=0 want_inspect
  local want_pull="pull --quiet ${TIMING_SIDECAR_IMAGE}"
  if [[ "$(timing::calls pull)" != "${want_pull}" ]]; then
    harness::add_reason "pull 호출 「$(timing::calls pull)」"
    ok=1
  fi
  want_inspect="image inspect --format {{ index .Config.Labels \
\"${TIMING_LABEL_KEY}\" }} ${TIMING_SIDECAR_IMAGE}"
  if [[ "$(timing::calls image)" != "${want_inspect}" ]]; then
    harness::add_reason "image inspect 호출 「$(timing::calls image)」"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 검사 서비스 run 호출이 정확히 한 번이고 그 argv 가 판 목록 readable 인 꼴인지,
# up 보다 앞인지 본다.
# Arguments:
#   기대 판 목록.
# Returns:
#   0 / 1 어긋남.
#######################################
timing::expect_preflight() {
  local want got
  want="compose --ansi never -p ${FIXTURE_PROJECT} -f \
${fixture_dir}/compose.yml run --rm --no-deps -T \
-e SIDECAR_TIMING_READABLE=$1 timing-preflight"
  got="$(timing::calls run)"
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "run 호출 「${got}」(기대 「${want}」)"
    return 1
  fi
}

#######################################
# 사례 65 — 라벨에 판 2 가 있으면(null,2 · 2) 검사 서비스를 부르지 않고 up 한다.
# 받기 · 라벨 읽기는 렌더본의 사이드카 이미지로 한 번씩이다.
#######################################
timing::case_label_two() {
  local ok=0 label
  for label in 'null,2' '2'; do
    timing::run "PCBENCH_FAKE_TIMING_LABEL=${label}" || return 1
    timing::expect_label_read || ok=1
    if [[ -n "$(timing::calls run)" || "$(fixture::count_calls up)" != 1 \
      || "${fixture_status}" != 0 ]]; then
      harness::add_reason "라벨 ${label}: run 「$(timing::calls run)」 · up \
$(fixture::count_calls up)회 · 종료 ${fixture_status}"
      ok=1
    fi
    fixture::common_checks || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 66 — 라벨에 판 2 가 없으면 검사 서비스를 run --rm --no-deps -T 로 한 번
# 돌리고 0 이면 up 한다. 라벨 없음(출시 이미지) · 받기 실패 · 라벨 읽기 실패 ·
# 렌더본에 사이드카 image 줄 없음은 판 목록 null 로, 2 없는 꼴 맞는 라벨은 그
# 글자로, 꼴 밖 라벨은 null 로 넘긴다.
#######################################
timing::case_preflight_pass() {
  local ok=0
  timing::run 'PCBENCH_FAKE_TIMING_LABEL=' || return 1
  timing::expect_label_read || ok=1
  timing::expect_preflight null || ok=1
  timing::expect_up_after_run '라벨 없음' || ok=1
  timing::run 'PCBENCH_FAKE_TIMING_LABEL=null,3' || return 1
  timing::expect_preflight 'null,3' || ok=1
  timing::run 'PCBENCH_FAKE_TIMING_LABEL=null;2' || return 1
  timing::expect_preflight null || ok=1
  # 2 가 든 꼴 밖 라벨은 지름길로 지나가지 않는다 — 꼴 검사가 먼저다.
  for label in 'abc,2' 'null,,2,garbage'; do
    timing::run "PCBENCH_FAKE_TIMING_LABEL=${label}" || return 1
    timing::expect_preflight null || ok=1
  done
  timing::run PCBENCH_FAKE_PULL_STATUS=1 PCBENCH_FAKE_INSPECT_STATUS=1 \
    || return 1
  timing::expect_preflight null || ok=1
  timing::run PCBENCH_FAKE_INSPECT_STATUS=1 || return 1
  timing::expect_preflight null || ok=1
  timing::expect_up_after_run '라벨 읽기 실패' || ok=1
  fixture::new_case
  printf 'services:\n  sidecar:\n    entrypoint: ["x"]\n' \
    >"${fixture_dir}/compose.yml"
  fixture::base_env
  fixture::run start-media.sh || return 1
  timing::expect_preflight null || ok=1
  if [[ -n "$(timing::calls pull)" ]]; then
    harness::add_reason 'image 줄이 없는데 받음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# up 이 한 번이고 run 보다 뒤이며 종료 코드가 0 인지 본다.
# Arguments:
#   행 이름.
#######################################
timing::expect_up_after_run() {
  local run_n up_n f n=0
  for f in "${fixture_dir}"/log/docker.*.argv; do
    n="${f##*/docker.}"
    n="${n%.argv}"
    case "$(fixture::compose_subcommand "${f}")" in
      run) run_n="${n}" ;;
      up) up_n="${n}" ;;
    esac
  done
  if [[ -z "${run_n:-}" || -z "${up_n:-}" ]] || (( up_n < run_n )) \
    || [[ "$(fixture::count_calls up)" != 1 || "${fixture_status}" != 0 ]]; then
    harness::add_reason "$1: run ${run_n:-없음} · up ${up_n:-없음} · 종료 \
${fixture_status}"
    return 1
  fi
}

#######################################
# 사례 67 — 검사 서비스가 0 이 아니면(3 거부 · 4 검사 못 함 · 검사 명령 없는
# 이미지의 1) BLOCKED 줄(사이드카 라벨 · 검사 종료 코드)을 남기고 up 하지 않는다.
#######################################
timing::case_preflight_blocked() {
  local ok=0 code
  for code in 3 4 1; do
    timing::run 'PCBENCH_FAKE_TIMING_LABEL=' "PCBENCH_FAKE_RUN_STATUS=${code}" \
      || return 1
    fixture::expect_blocked \
      "${TIMING_BLOCKED_PREFIX} label=none check=${code}" || ok=1
    fixture::common_checks || ok=1
  done
  timing::run 'PCBENCH_FAKE_TIMING_LABEL=null' PCBENCH_FAKE_RUN_STATUS=3 \
    || return 1
  fixture::expect_blocked "${TIMING_BLOCKED_PREFIX} label=null check=3" || ok=1
  timing::run 'PCBENCH_FAKE_TIMING_LABEL=null;2' PCBENCH_FAKE_RUN_STATUS=3 \
    || return 1
  fixture::expect_blocked "${TIMING_BLOCKED_PREFIX} label=invalid check=3" \
    || ok=1
  return "${ok}"
}

#######################################
# 사례 69 — 받기(docker pull)가 실패해도 다이제스트로 고정한 이미지가 로컬에
# 있으면 그 라벨을 읽는다(같은 다이제스트 = 같은 이미지). 라벨에 판 2 가 있으면
# 검사 없이 up 한다 — ECR 이 잠깐 막혀도 c0 하한 이상 상자가 DB 에 기대지
# 않는다. 로컬에도 없으면(라벨 읽기 실패) 판 목록 null 로 검사한다. 다이제스트
# 없는 참조(태그)는 로컬 사본이 다른 이미지일 수 있어 읽지 않는다.
#######################################
timing::case_pull_fallback() {
  local ok=0
  timing::run PCBENCH_FAKE_PULL_STATUS=1 || return 1
  if [[ "$(timing::calls image)" != "image inspect --format {{ index \
.Config.Labels \"${TIMING_LABEL_KEY}\" }} ${TIMING_SIDECAR_IMAGE}" ]] \
    || [[ -n "$(timing::calls run)" ]] \
    || [[ "$(fixture::count_calls up)" != 1 || "${fixture_status}" != 0 ]]; then
    harness::add_reason "받기 실패 · 로컬 c0: image 「$(timing::calls image)」 · \
run 「$(timing::calls run)」 · up $(fixture::count_calls up)회 · 종료 \
${fixture_status}"
    ok=1
  fi
  timing::run PCBENCH_FAKE_PULL_STATUS=1 PCBENCH_FAKE_INSPECT_STATUS=1 \
    PCBENCH_FAKE_RUN_STATUS=3 || return 1
  fixture::expect_blocked "${TIMING_BLOCKED_PREFIX} label=none check=3" || ok=1
  fixture::new_case
  printf 'services:\n  sidecar:\n    image: %s\n' \
    'registry.pcbench-edge-proxy.invalid/media-sidecar:c0' \
    >"${fixture_dir}/compose.yml"
  fixture::base_env
  fixture::run start-media.sh PCBENCH_FAKE_PULL_STATUS=1 || return 1
  timing::expect_preflight null || ok=1
  if [[ -n "$(timing::calls image)" ]]; then
    harness::add_reason '태그 참조 받기 실패인데 로컬 라벨을 읽음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 68 — 기동 전 검사를 건너뛰는 변수 · 인자는 없다(정적). 시작 스크립트의 검사
# 걸음은 렌더(prepare_config) 뒤 · exec docker compose up 앞에 있다.
#######################################
timing::case_order_static() {
  local script="${MEDIA_HOST_DIR}/bin/start-media.sh" order
  order="$(awk '/^  if ! prepare_config / { print "render" }
    /^  if ! check_timing_preflight / { print "timing" }
    /^  exec docker compose / { print "up" }' "${script}" | paste -s -d ' ' -)"
  if [[ "${order}" != 'render timing up' ]]; then
    harness::add_reason "main 의 걸음 순서 「${order}」(기대 「render timing up」)"
    return 1
  fi
}
