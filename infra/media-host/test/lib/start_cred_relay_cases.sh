# shellcheck shell=bash
# 전역(fixture_* · FIXTURE_* · MEDIA_HOST_DIR · TEST_DIR 등)과 fixture::* 는
# start_fixture.sh 가, auth_mode::* · auth_input::* 와 그 상수는
# start_ingest_auth_cases.sh · start_ingest_input_cases.sh 가 정의한다.
# shellcheck disable=SC2154
#
# start_test 의 대리 창구 사례(77 – 86 — 계획 c5 의 CR1 – CR10). compose
# 렌더본에 cred-relay 서비스 키 줄(`  cred-relay:`)이 한 줄 있으면 켬이다. 켬이면
# 시작 스크립트가 렌더 묶음 뒤에 창구 토큰 묶음(소비자 둘 · 중계 사본 둘)을 두고,
# 가짜 링크 pokeclip-cred0 에 169.254.170.2/32 를 붙인 뒤 compose 를 올린다.
# 렌더본은 켬 렌더를 흉내 낸 짧은 고정물이다(compose 템플릿은 이 사례들의 대상이
# 아니다).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 렌더본의 사이드카 이미지 — 가짜 docker 의 라벨 기본값(null,2)이 판 2 라
# 기동 전 검사를 지나간다.
readonly CRED_SIDECAR_IMAGE='registry.pcbench-edge-proxy.invalid/'\
'media-sidecar@sha256:'\
'4444444444444444444444444444444444444444444444444444444444444444'
readonly CRED_LINK='pokeclip-cred0'
readonly CRED_ADDR='169.254.170.2/32'
readonly CRED_MISMATCH_LINE='MEDIA START BLOCKED: cred-relay mode mismatch'
readonly CRED_TOKEN_LINE='MEDIA START BLOCKED: cred-relay token unavailable'
readonly CRED_ADDRESS_LINE='MEDIA START FAIL cred-relay address'
readonly CRED_LIST_LINE='MEDIA START FAIL render cred-relay-list'
readonly CRED_DIR_LINE='MEDIA START FAIL render cred-relay-dir'
readonly CRED_TOKEN_PATTERN='^[0-9a-f]{64}$'
# 창구 파일 넷 — RUNTIME_DIR 아래 경로와 소유(uid:gid) 쌍.
readonly CRED_FILES=(
  'cred-indexer/token 10001:10001'
  'cred-relay/indexer 10001:10001'
  'cred-janitor/token 10002:10002'
  'cred-relay/janitor 10001:10001'
)
# 창구 디렉터리 셋 — 이름 · chown(-h) 소유 · 권한 문자열.
readonly CRED_DIRS=(
  'cred-indexer 0:0 drwx--x--x'
  'cred-janitor 0:0 drwx--x--x'
  'cred-relay 0:10001 drwxr-x---'
)

# 마지막으로 읽은 토큰 값(cred_relay::read_tokens 가 채운다).
cred_indexer=""
cred_janitor=""

#######################################
# 사례 디렉터리에 켬 렌더본을 둔다 — 사이드카(라벨 받는 image) · cred-relay
# 서비스, 응답기 모드면 응답기 서비스 키 줄도.
# Globals:
#   fixture_dir
# Arguments:
#   응답기 서비스 키 줄을 둘지(1|0 — 기본 0).
#######################################
cred_relay::write_compose() {
  {
    printf 'services:\n  mediamtx:\n    image: pcbench\n'
    if [[ "${1:-0}" == 1 ]]; then
      printf '  ingest-auth:\n    image: pcbench\n'
    fi
    printf '  sidecar:\n    image: %s\n' "${CRED_SIDECAR_IMAGE}"
    printf '  cred-relay:\n    image: %s\n' "${CRED_SIDECAR_IMAGE}"
  } >"${fixture_dir}/compose.yml"
}

#######################################
# 켬 렌더본 · 옛 모드 기본 값으로 시작 스크립트를 돌린다(사례 디렉터리는
# 부르는 쪽이 만든다).
# Arguments:
#   가짜 명령 설정(NAME=값 …).
#######################################
cred_relay::run() {
  [[ -e "${fixture_dir}/compose.yml" ]] || cred_relay::write_compose
  fixture::base_env
  fixture::run start-media.sh "$@"
}

#######################################
# 가짜 ip 기록 가운데 `link show` 를 뺀 호출 argv 를 차례대로 한 줄씩 낸다.
# Globals:
#   fixture_dir
#######################################
cred_relay::ip_ops() {
  local n=1 f line
  while f="${fixture_dir}/log/ip.${n}.argv"; [[ -f "${f}" ]]; do
    line="$(paste -s -d ' ' - <"${f}")"
    [[ "${line}" == 'link show '* ]] || printf '%s\n' "${line}"
    n=$((n + 1))
  done
}

#######################################
# 기동이 끝까지 갔는지 본다 — 종료 0 · up 한 번 · 막힘 · 실패 줄 0.
# Globals:
#   fixture_dir, fixture_status
# Arguments:
#   행 이름.
#######################################
cred_relay::expect_started() {
  local ups
  ups="$(fixture::count_calls up)"
  if (( fixture_status != 0 || ups != 1 )) \
    || grep -q -E '^MEDIA START (BLOCKED|FAIL)' "${fixture_dir}/out"; then
    harness::add_reason "$1: 종료 ${fixture_status} · up ${ups}회(기대 0 · 1): \
$(harness::log_tail "${fixture_dir}/out")"
    return 1
  fi
}

#######################################
# 주소 걸음이 정한 셋(링크 만들기 → 주소 → 올리기)을 차례로 불렀고, 모두
# compose up 앞인지 본다(가짜 ip 의 state 는 그때까지의 docker 기록 수다).
# Globals:
#   fixture_dir
# Arguments:
#   링크가 이미 있었는지(1|0).
#######################################
cred_relay::check_address() {
  local ok=0 want got up n=1 before
  want="addr replace ${CRED_ADDR} dev ${CRED_LINK}"$'\n'
  want+="link set ${CRED_LINK} up"
  if [[ "$1" == 0 ]]; then
    want="link add ${CRED_LINK} type dummy"$'\n'"${want}"
  fi
  got="$(cred_relay::ip_ops)"
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "ip 호출 「$(printf '%s' "${got}" | tr '\n' '|')」(기대 \
「$(printf '%s' "${want}" | tr '\n' '|')」)"
    ok=1
  fi
  up="$(fixture::docker_calls up)"
  up="${up##*/docker.}"
  up="${up%.argv}"
  while [[ -f "${fixture_dir}/log/ip.${n}.state" ]]; do
    before="$(sed -n 's/^docker=//p' "${fixture_dir}/log/ip.${n}.state")"
    if [[ -z "${up}" ]] || (( before >= up )); then
      harness::add_reason "ip 호출 ${n} 이 compose up 앞이 아님(docker \
${before} · up ${up:-없음})"
      ok=1
    fi
    n=$((n + 1))
  done
  return "${ok}"
}

#######################################
# 토큰 파일 넷을 읽어 cred_indexer · cred_janitor 에 둔다 — 파일마다 64바이트 ·
# 끝 개행 없음 · 16진 64자 · 0400 이고, 소비자 파일과 중계 사본이 같으며,
# indexer 와 janitor 가 다르다.
# Globals:
#   fixture_dir, cred_indexer, cred_janitor
#######################################
cred_relay::read_tokens() {
  local ok=0 run="${fixture_dir}/runtime" entry path value size
  local values=()
  for entry in "${CRED_FILES[@]}"; do
    path="${run}/${entry% *}"
    size="$(wc -c <"${path}" 2>/dev/null)" || size=""
    value="$(cat -- "${path}" 2>/dev/null && printf x)" || value=""
    value="${value%x}"
    if [[ "$((size + 0))" != 64 ]]; then
      harness::add_reason "토큰 파일 크기 64 아님: ${entry% *}(${size:-없음})"
      ok=1
    fi
    if [[ "${value}" == *$'\n'* || ! "${value}" =~ ${CRED_TOKEN_PATTERN} ]]
    then
      harness::add_reason "토큰 파일이 끝 개행 없는 16진 64자가 아님: \
${entry% *}"
      ok=1
    fi
    if [[ "$(fixture::perm "${path}" 2>/dev/null)" != -r-------- ]]; then
      harness::add_reason "토큰 파일 권한 $(fixture::perm "${path}" \
2>/dev/null): ${entry% *}(기대 -r--------)"
      ok=1
    fi
    values+=("${value}")
  done
  cred_indexer="${values[0]}"
  cred_janitor="${values[2]}"
  if [[ "${values[0]}" != "${values[1]}" || "${values[2]}" != "${values[3]}" ]]
  then
    harness::add_reason '쌍 불일치 — 소비자 토큰과 중계 사본이 다름'
    ok=1
  fi
  if [[ "${cred_indexer}" == "${cred_janitor}" ]]; then
    harness::add_reason 'indexer 와 janitor 토큰이 같음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# chown 기록 가운데 「소유 대상」 이 기대와 같은 것의 수를 낸다(대상 끝이 * 이면
# 그 앞까지 앞머리 대조).
# Globals:
#   fixture_dir
# Arguments:
#   기대 argv 한 줄.
#######################################
cred_relay::chown_count() {
  local f got n=0 want="$1"
  for f in "${fixture_dir}"/log/chown.*.argv; do
    [[ -f "${f}" ]] || continue
    got="$(paste -s -d ' ' - <"${f}")"
    if [[ "${want}" == *'*' && "${got}" == "${want%'*'}"* \
      || "${got}" == "${want}" ]]; then
      n=$((n + 1))
    fi
  done
  printf '%s\n' "${n}"
}

#######################################
# 창구 디렉터리 셋의 chown(-h) · 권한과 파일 넷의 임시 파일 chown(그 순간 0400 ·
# 최종 경로 없음)을 본다.
# Globals:
#   fixture_dir
#######################################
cred_relay::check_owners() {
  local ok=0 run="${fixture_dir}/runtime" entry name owner perm path f
  for entry in "${CRED_DIRS[@]}"; do
    read -r name owner perm <<<"${entry}"
    if [[ "$(cred_relay::chown_count "-h ${owner} ${run}/${name}")" != 1 ]]
    then
      harness::add_reason "디렉터리 chown 「-h ${owner} ${name}」 이 한 번이 아님"
      ok=1
    fi
    if [[ "$(fixture::perm "${run}/${name}" 2>/dev/null)" != "${perm}" ]]; then
      harness::add_reason "디렉터리 권한 $(fixture::perm "${run}/${name}" \
2>/dev/null): ${name}(기대 ${perm})"
      ok=1
    fi
  done
  for entry in "${CRED_FILES[@]}"; do
    path="${entry% *}"
    owner="${entry#* }"
    f="${run}/${path%/*}/.${path##*/}.*"
    if [[ "$(cred_relay::chown_count "${owner} ${f}")" != 1 ]]; then
      harness::add_reason "파일 chown 「${owner} ${path}」 이 한 번이 아님"
      ok=1
    fi
  done
  for f in "${fixture_dir}"/log/chown.*.argv; do
    [[ -f "${f}" ]] || continue
    [[ "$(tail -n 1 -- "${f}")" == "${run}/cred-"*/.* ]] || continue
    if [[ "$(cat -- "${f%.argv}.state")" \
      != 'perm=-r-------- final=absent core=0' ]]; then
      harness::add_reason "창구 파일 chown 순간 상태 「$(cat -- \
"${f%.argv}.state")」(기대 0400 · 최종 경로 없음)"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# RUNTIME_DIR 의 창구 디렉터리 아래 파일 수(숨은 임시 파일 포함)를 낸다.
# Globals:
#   fixture_dir
#######################################
cred_relay::cred_files() {
  local d n=0 c
  for d in "${fixture_dir}"/runtime/cred-*; do
    [[ -d "${d}" ]] || continue
    c="$(find "${d}" -mindepth 1 -type f | awk 'END { print NR }')"
    n=$((n + c))
  done
  printf '%s\n' "${n}"
}

#######################################
# 사례 77(CR1) — 켬 기본: 주소 걸음 셋이 차례로 · up 앞 · 토큰 넷(64바이트 ·
# 개행 0 · 쌍 동일 · indexer ≠ janitor) · 디렉터리 셋 소유 · 권한(소비자 root
# 0711 · 중계 root:10001 0750) · 파일 0400 · 소유. up argv 는 끔과 같다.
#######################################
cred_relay::case_default() {
  local ok=0 argv want
  fixture::new_case
  cred_relay::run || return 1
  cred_relay::expect_started '켬 기본' || return 1
  cred_relay::check_address 0 || ok=1
  cred_relay::read_tokens || ok=1
  cred_relay::check_owners || ok=1
  if [[ "$(fixture::count_records chown)" != 9 ]]; then
    harness::add_reason "chown $(fixture::count_records chown)회(기대 9 — 옛 \
모드 둘 · 창구 디렉터리 셋 · 파일 넷)"
    ok=1
  fi
  argv="$(paste -s -d ' ' - <"$(fixture::docker_calls up)")"
  want="compose --ansi never -p ${FIXTURE_PROJECT} -f \
${fixture_dir}/compose.yml up --force-recreate --remove-orphans \
--abort-on-container-exit --attach mediamtx --attach nginx"
  if [[ "${argv}" != "${want}" ]]; then
    harness::add_reason "up argv 「${argv}」"
    ok=1
  fi
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 사례 78(CR2) — 끔(렌더본에 cred-relay 줄 없음 · 렌더본 없음): ip 호출 0 · 창구
# 디렉터리 0 · up argv 는 그대로.
#######################################
cred_relay::case_off() {
  local ok=0 row want
  for row in none sidecar; do
    fixture::new_case
    if [[ "${row}" == sidecar ]]; then
      printf 'services:\n  sidecar:\n    image: %s\n' "${CRED_SIDECAR_IMAGE}" \
        >"${fixture_dir}/compose.yml"
    fi
    fixture::base_env
    fixture::run start-media.sh || return 1
    cred_relay::expect_started "끔 ${row}" || ok=1
    if [[ "$(fixture::count_records ip)" != 0 ]]; then
      harness::add_reason "끔 ${row}: ip $(fixture::count_records ip)회(기대 0)"
      ok=1
    fi
    if compgen -G "${fixture_dir}/runtime/cred-*" >/dev/null; then
      harness::add_reason "끔 ${row}: 창구 디렉터리가 생김"
      ok=1
    fi
    want="compose --ansi never -p ${FIXTURE_PROJECT} -f \
${fixture_dir}/compose.yml up --force-recreate --remove-orphans \
--abort-on-container-exit --attach mediamtx --attach nginx"
    if [[ "$(paste -s -d ' ' - <"$(fixture::docker_calls up)")" != "${want}" ]]
    then
      harness::add_reason "끔 ${row}: up argv 가 다름"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 79(CR3) — 렌더본의 cred-relay 키 줄이 둘 · 뒤 공백 · 같은 줄 값이면 막는다.
# 비밀을 읽기 전이다(aws 0) · ip 0 · 창구 디렉터리 0.
#######################################
cred_relay::case_mode_mismatch() {
  local ok=0 row
  for row in two trailing value; do
    fixture::new_case
    {
      printf 'services:\n  sidecar:\n    image: %s\n' "${CRED_SIDECAR_IMAGE}"
      case "${row}" in
        two) printf '  cred-relay:\n    image: x\n  cred-relay:\n' ;;
        trailing) printf '  cred-relay: \n    image: x\n' ;;
        value) printf '  cred-relay: {}\n' ;;
      esac
    } >"${fixture_dir}/compose.yml"
    cred_relay::run || return 1
    fixture::expect_blocked "${CRED_MISMATCH_LINE}" || ok=1
    if [[ "$(fixture::count_records aws)" != 0 \
      || "$(fixture::count_records ip)" != 0 ]] \
      || compgen -G "${fixture_dir}/runtime/cred-*" >/dev/null; then
      harness::add_reason "${row}: aws $(fixture::count_records aws) · ip \
$(fixture::count_records ip) · 창구 디렉터리(기대 0 · 0 · 없음)"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 응답기 모드 파일 여섯(렌더 묶음)의 경로 · 소유(임시 파일 chown 한 번 · 그 순간
# 0400) · 권한을 본다.
# Globals:
#   fixture_dir
#######################################
cred_relay::check_responder_files() {
  local ok=0 run="${fixture_dir}/runtime" ia entry path owner f
  ia="${run}/${AUTH_DIR_NAME}"
  for entry in "10002:10002 ${run}/mediamtx.yml" \
    "10001:10001 ${run}/${API_PASS_NAME}" \
    "${AUTH_OWNER} ${ia}/${AUTH_BASIC_NAME}" \
    "${AUTH_OWNER} ${ia}/${API_PASS_NAME}" \
    "${AUTH_OWNER} ${ia}/${AUTH_RESOLVE_NAME}" \
    "10001:10001 ${run}/${AUTH_RESOLVE_NAME}"; do
    owner="${entry%% *}"
    path="${entry#* }"
    f="${path%/*}/.${path##*/}.*"
    if [[ "$(cred_relay::chown_count "${owner} ${f}")" != 1 \
      || "$(fixture::perm "${path}" 2>/dev/null)" != -r-------- ]]; then
      harness::add_reason "응답기 모드 파일 소유 · 경로가 기대와 다르다: \
${path#"${run}/"}"
      ok=1
    fi
  done
  for f in "${run}/${AUTH_RESOLVE_NAME}" "${ia}/${AUTH_RESOLVE_NAME}"; do
    if [[ "$(cat -- "${f}" 2>/dev/null && printf x)" \
      != "${AUTH_RESOLVE_TOKEN}"$'\n'x ]]; then
      harness::add_reason "4C 토큰 파일 내용이 다름: ${f#"${run}/"}"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 80(CR4) — 응답기 모드 + 켬 한 기동: 렌더 묶음 파일 여섯과 창구 토큰 넷,
# 파일 열의 경로 · 소유 · 내용이 정확하다(두 번째 install_files 가 앞 묶음의
# 목록을 이어받지 않는다).
#######################################
cred_relay::case_responder_mode() {
  local ok=0
  auth_mode::prod_template || return 1
  fixture::new_case
  cred_relay::write_compose 1
  auth_mode::run "${auth_mode_tmpl}" || return 1
  cred_relay::expect_started '응답기 모드 + 켬' || return 1
  auth_mode::read_values || ok=1
  auth_mode::check_render "${auth_mode_tmpl}" || ok=1
  auth_mode::check_files || ok=1
  cred_relay::check_responder_files || ok=1
  cred_relay::read_tokens || ok=1
  cred_relay::check_owners || ok=1
  if [[ "$(fixture::count_records chown)" != 17 ]]; then
    harness::add_reason "chown $(fixture::count_records chown)회(기대 17 — \
응답기 모드 열 · 창구 일곱)"
    ok=1
  fi
  cred_relay::check_address 0 || ok=1
  fixture::common_checks || ok=1
  return "${ok}"
}

#######################################
# 토큰 묶음을 단독으로 부른다 — 시작 스크립트의 렌더 · 토큰 lib 를 source 하고,
# 파일 목록 배열 하나에 앞 묶음이 남긴 것처럼 항목 하나를 둔 채
# install_cred_tokens 를 부른다. 시작 스크립트 전역(say · generate_credential ·
# 렌더 전역)은 같은 꼴의 대역이다.
# Globals:
#   fixture_dir, fixture_sysbin, MEDIA_HOST_DIR
# Arguments:
#   비어 있지 않게 둘 배열 이름(file_paths · file_owners · file_steps ·
#   file_bodies).
#######################################
cred_relay::install_with_stale() {
  mkdir -p -- "${fixture_dir}/runtime"
  # 대역 본문은 자식 bash 가 읽는다(이 셸에서 펼치지 않는다).
  # shellcheck disable=SC2016
  env -i "PATH=${fixture_dir}/bin:${fixture_sysbin}" \
    "PCBENCH_FAKE_LOG_DIR=${fixture_dir}/log" bash -c '
    set -euo pipefail
    source "$1/lib/render_files.sh"
    source "$1/lib/cred_relay.sh"
    say() { printf "%s\n" "$1"; }
    generate_credential() {
      od -A n -N 32 -t x1 /dev/urandom | tr -d " \n"
    }
    render_tmps=()
    staged_tmp=""
    render_step=""
    sidecar_owner="10001:10001"
    file_paths=()
    file_owners=()
    file_steps=()
    file_bodies=()
    case "$3" in
      file_paths) file_paths=("$2/stale") ;;
      file_owners) file_owners=("10001:10001") ;;
      file_steps) file_steps=("stale-") ;;
      file_bodies) file_bodies=("stale") ;;
    esac
    install_cred_tokens "$2"
  ' _ "${MEDIA_HOST_DIR}/bin" "${fixture_dir}/runtime" "$1" \
    >"${fixture_dir}/out" 2>&1
}

#######################################
# 사례 81(CR5) — 토큰 묶음은 들어갈 때 목록 넷이 모두 비었는지 본다. 하나라도
# 남아 있으면 cred-relay-list 로 실패하고 토큰 파일을 두지 않는다.
#######################################
cred_relay::case_list_not_empty() {
  local ok=0 name status
  for name in file_paths file_owners file_steps file_bodies; do
    fixture::new_case
    status=0
    cred_relay::install_with_stale "${name}" || status=$?
    if (( status == 0 )) \
      || ! grep -q -F -x -- "${CRED_LIST_LINE}" "${fixture_dir}/out"; then
      harness::add_reason "${name} 남음: 종료 ${status} · 출력 \
「$(harness::log_tail "${fixture_dir}/out")」(기대 cred-relay-list)"
      ok=1
    fi
    if [[ "$(cred_relay::cred_files)" != 0 ]]; then
      harness::add_reason "${name} 남음: 토큰 파일 $(cred_relay::cred_files)개 \
(기대 0)"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 82(CR6) — 창구 디렉터리 셋 가운데 하나가 링크면 따라가지 않고 막는다.
# 링크 대상의 파일 · 권한은 그대로다.
#######################################
cred_relay::case_dir_link() {
  local ok=0 name elsewhere
  for name in cred-indexer cred-janitor cred-relay; do
    fixture::new_case
    elsewhere="${fixture_dir}/elsewhere"
    mkdir -p -- "${elsewhere}" "${fixture_dir}/runtime"
    chmod 0755 "${elsewhere}"
    printf 'keep\n' >"${elsewhere}/token"
    ln -s -- "${elsewhere}" "${fixture_dir}/runtime/${name}"
    cred_relay::run || return 1
    fixture::expect_blocked "${CRED_DIR_LINE}" || ok=1
    if [[ "$(cat -- "${elsewhere}/token")" != keep \
      || "$(fixture::perm "${elsewhere}")" != drwxr-xr-x \
      || "$(find "${elsewhere}" -mindepth 1 | awk 'END { print NR }')" != 1 ]]
    then
      harness::add_reason "${name} 링크: 링크 대상의 파일 · 권한이 바뀜"
      ok=1
    fi
    if grep -q -F -x -- "${fixture_dir}/runtime/${name}" \
      "${fixture_dir}"/log/chown.*.argv 2>/dev/null; then
      harness::add_reason "${name} 링크: 링크를 chown 함"
      ok=1
    fi
    fixture::common_checks || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 83(CR7) — 주소 걸음(링크 만들기 · 주소 · 올리기) 가운데 하나가 실패하면
# 막는다(up 0).
#######################################
cred_relay::case_address_failure() {
  local ok=0 op
  for op in link-add addr-replace link-set; do
    fixture::new_case
    cred_relay::run "PCBENCH_FAKE_IP_FAIL=${op}" || return 1
    fixture::expect_blocked "${CRED_ADDRESS_LINE}" || ok=1
    if [[ "$(fixture::count_records ip)" == 0 ]]; then
      harness::add_reason "${op}: ip 를 부르지 않음"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 84(CR8) — 토큰 생성이 실패하면(첫째 · 둘째) 막고 창구 파일 · 주소 걸음 ·
# up 은 0 이다. 옛 모드 렌더의 생성(API 비밀번호)은 진짜 od 로 넘긴다.
#######################################
cred_relay::case_token_unavailable() {
  local ok=0 calls
  for calls in 1 2; do
    fixture::new_case
    ln -s -- "${TEST_DIR}/start_fakebin/od" "${fixture_dir}/bin/od"
    cred_relay::run "PCBENCH_FAKE_OD_PASS_CALLS=${calls}" || return 1
    fixture::expect_blocked "${CRED_TOKEN_LINE}" || ok=1
    if [[ "$(cred_relay::cred_files)" != 0 \
      || "$(fixture::count_records ip)" != 0 ]]; then
      harness::add_reason "생성 ${calls}번째 뒤 실패: 창구 파일 \
$(cred_relay::cred_files)개 · ip $(fixture::count_records ip)회(기대 0 · 0)"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 85(CR9) — 두 번째 기동(링크 있음): 링크를 다시 만들지 않고 주소 · 올리기만
# 한 번씩 한다(멱등). 토큰은 새 값으로 rename 된다.
#######################################
cred_relay::case_rerun() {
  local ok=0 first_indexer first_janitor
  fixture::new_case
  cred_relay::run || return 1
  cred_relay::expect_started '첫 기동' || return 1
  cred_relay::read_tokens || return 1
  first_indexer="${cred_indexer}"
  first_janitor="${cred_janitor}"
  rm -f -- "${fixture_dir}"/log/*
  cred_relay::run PCBENCH_FAKE_IP_LINK=present || return 1
  cred_relay::expect_started '두 번째 기동' || ok=1
  cred_relay::check_address 1 || ok=1
  cred_relay::read_tokens || ok=1
  if [[ "${cred_indexer}" == "${first_indexer}" \
    || "${cred_janitor}" == "${first_janitor}" ]]; then
    harness::add_reason '두 번째 기동의 토큰이 첫 기동과 같음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 86(CR10) — 토큰 값이 제 파일(소비자 · 중계 사본) 밖 어디에도 없다 —
# 가짜 명령 argv · 환경 기록 · 스크립트 출력 포함. 토큰을 담는 변수
# 이름(cred_token)이 부모 환경에서 export 된 채 들어와도 같다.
#######################################
cred_relay::case_no_leak() {
  local ok=0 inherit run
  for inherit in '' 'cred_token=inherited'; do
    fixture::new_case
    run="${fixture_dir}/runtime"
    cred_relay::run ${inherit:+"${inherit}"} || return 1
    cred_relay::expect_started "상속 「${inherit}」" || ok=1
    cred_relay::read_tokens || { ok=1; continue; }
    auth_mode::check_only_in "${cred_indexer}" 'indexer 토큰' \
      "${run}/cred-indexer/token" "${run}/cred-relay/indexer" || ok=1
    auth_mode::check_only_in "${cred_janitor}" 'janitor 토큰' \
      "${run}/cred-janitor/token" "${run}/cred-relay/janitor" || ok=1
  done
  return "${ok}"
}
