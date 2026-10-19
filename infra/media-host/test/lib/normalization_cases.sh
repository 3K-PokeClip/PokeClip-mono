# shellcheck shell=bash
#
# 앞단 프록시 계약 시험의 정규화 우회 사례 — 사례 4 · 15 에 더하는 확인과
# 덧붙임 사례 1b(live).
#
# nginx 는 경로의 %XX 를 풀고 점 구간 · 겹 슬래시를 접은 경로($uri)로 location
# 을 고른다. 앞단 인가(CloudFront 서명 쿠키 정책)는 원문 경로를 본다. 둘이
# 다르면 /A/..%2fB/list 처럼 A 의 쿠키로 B 를 고르는 요청이 생긴다. 상류도 원문을
# 받으면 제 나름으로 풀고 접는다(MediaMTX LL-HLS 가 그렇다). 여기 사례는 그런
# 원문을 요청 줄에 그대로 실어(curl --request-target) 보내고, nginx 가 거절하고
# 상류로 넘기지 않는지 본다.
#
# proxy_contract.sh 가 source 해서 돌린다. harness.sh · proxy_requests.sh 를
# 먼저 source 하고, proxy_contract.sh 의 expect_blocked_without_upstream(응답
# 코드 · 상류 호출 0 단언)을 쓴다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 정규화 우회 원문 목록(normalization::set_bypass_targets 가 채운다).
normalization_targets=()

#######################################
# A 의 경로 아래에서 B 를 고르게 만드는 원문 목록을 채운다. 모두 nginx 가 풀고
# 접으면 /B/<끝 조각> 이 되는 꼴이다. 마지막 것은 절대 URI 다.
# Globals:
#   normalization_targets
# Arguments:
#   A 번호, B 번호, 끝 조각(예: list?path=B), 절대 URI 의 기준 URL.
#######################################
normalization::set_bypass_targets() {
  local a="$1" b="$2" tail="$3" base="$4"
  normalization_targets=(
    "/${a}/..%2f${b}/${tail}"
    "/${a}/..%2F${b}/${tail}"
    "/${a}%2F..%2F${b}/${tail}"
    "/${a}/%2e%2e/${b}/${tail}"
    "/${a}/%2e%2e%2f${b}/${tail}"
    "/${a}/../${b}/${tail}"
    "/${a}//..//${b}/${tail}"
    "/./${b}/${tail}"
    "//${b}/${tail}"
    "${base}/${a}/../${b}/${tail}"
  )
}

#######################################
# 사례 4 에 더하는 확인 — playback 정규화 우회 원문이 모두 403 · 상류 호출 0.
# 경로에 % 가 든 동작 이름(lis%74)도 같다.
# Arguments:
#   playback 기준 URL, A 번호, B 번호.
#######################################
normalization::check_playback() {
  local base="$1" a="$2" b="$3"
  normalization::set_bypass_targets "${a}" "${b}" "list?path=${b}" "${base}"
  expect_blocked_without_upstream 403 "${base}" "${normalization_targets[@]}" \
    "/${a}/lis%74?path=${a}"
}

#######################################
# 사례 1b 의 거절 쪽 — live 정규화 우회 원문(인코딩한 \ · % 포함)이 모두 400 ·
# 상류 호출 0.
# Arguments:
#   live 기준 URL, A 번호, B 번호.
#######################################
normalization::check_live_rejected() {
  local base="$1" a="$2" b="$3"
  normalization::set_bypass_targets "${a}" "${b}" index.m3u8 "${base}"
  expect_blocked_without_upstream 400 "${base}" "${normalization_targets[@]}" \
    "/live/${a}/..%2F${b}/index.m3u8" "/${a}/..%5c${b}/index.m3u8" \
    "/${a}/%252e%252e/${b}/index.m3u8" "/${a}/."
}

#######################################
# 사례 1b 의 통과 쪽 — LL-HLS 블로킹 쿼리 · 조각 · 파트 · init 요청이 200 이고
# 상류(8888)가 받은 요청 대상이 보낸 원문과 같다.
# Arguments:
#   live 기준 URL, 번호.
#######################################
normalization::check_live_passed() {
  local base="$1" id="$2" ok=0 target
  for target in \
    "/${id}/index.m3u8?_HLS_msn=12&_HLS_part=3" \
    "/live/${id}/video1_stream.m3u8?_HLS_msn=12&_HLS_part=3&_HLS_skip=YES" \
    "/${id}/abc_video1_seg12.mp4" "/${id}/abc_video1_part7.mp4" \
    "/${id}/abc_video1_init.mp4"; do
    proxy::get_raw "${base}" "${target}"
    if ! proxy::expect_code 200; then
      ok=1
      continue
    fi
    proxy::expect_field .listener '127.0.0.1:8888' '상류' || ok=1
    proxy::expect_field .request_uri "${target}" '요청 대상' || ok=1
    proxy::expect_auth || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 1b 본체.
# Arguments:
#   live 기준 URL, A 번호, B 번호.
#######################################
normalization::check_live() {
  local ok=0
  normalization::check_live_rejected "$1" "$2" "$3" || ok=1
  normalization::check_live_passed "$1" "$2" || ok=1
  return "${ok}"
}

#######################################
# 사례 15 에 더하는 확인 — 제어면(상류 = 에코)에서 허용 경로 밖을 풀고 접어
# 허용 경로로 만드는 원문이 400 · 상류 호출 0.
# Arguments:
#   제어면 기준 URL.
#######################################
normalization::check_control() {
  expect_blocked_without_upstream 400 "$1" '/admin/..%2Fapi/ingest/x' \
    '/debug/pprof/%2e%2e/%2e%2e/api/streams/y/rewind'
}
