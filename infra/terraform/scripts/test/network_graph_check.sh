#!/usr/bin/env bash
#
# assert_graph_path.sh 를 시험하고, 그것으로 network 모듈의 의존 계약 — 인터페이스
# 엔드포인트가 SG 인그레스 규칙 뒤에 생긴다(`depends_on`, POK-408 설계 보강 3-1) —
# 을 고정한다. terraform test 는 의존 그래프를 볼 수 없어 여기서 본다.
#
# terraform init 이 필요해 terraform 잡에서 돈다 — scripts/test 이름 규칙의
# 예외(kty 2026-11-07). 그래서 이름이 `*_test.sh` 가 아니다 — shell 잡의
# 「스크립트 시험」 글롭에 들지 않는다.
#
# Shell 가이드의 100줄 권고를 넘지만 셸로 둔다 — 같은 디렉터리 시험
# (put_secret_test · kvs_put_test)과 같은 틀(fail · pass · 사례 수 · 임시
# 디렉터리 잔여 단언)을 쓰고, 그 틀을 공용 함수 파일로 빼면 두 시험도 함께
# 바꿔야 해 이 변경의 범위를 넘는다.
#
# 사례는 두 묶음이다.
#   - 합성 그래프: 직접 간선 · 추이 경로 · 역방향 간선만 · 경로 없음 · 간선 없는
#     노드 · 노드 없음 · 빈 입력 · DOT 가 아닌 입력 · 인자 개수. 종료 코드와 출력 줄을 본다.
#   - 실제 모듈: init 된 모듈 디렉터리에서 `terraform graph` 를 그려 엔드포인트 →
#     인그레스 규칙 경로가 있는지 본다. 그 모듈의 .tf · lock 사본에서 `depends_on`
#     한 줄을 지우면(변이) 경로가 없어지는지(종료 1 — 노드 없음이 아님)도 본다.
#     사본은 원본의 데이터 디렉터리(TF_DATA_DIR, 없으면 <모듈>/.terraform)를 그대로
#     써서 다시 init 하지 않는다 — 네트워크에 닿지 않는다. 모듈이 init 되지 않았으면
#     두 사례 모두 실패다.
#
# 임시 디렉터리는 한 뿌리($TMPDIR/network_graph_check.*) 아래에 두고, 끝에 지운 뒤
# 남지 않았는지 확인한다(중단 신호에도 지운다). 끝에 통과 건수가 사례
# 수(EXPECTED_CASES)와 같은지 본다.
#
# 사용: terraform -chdir=infra/terraform/modules/network init -backend=false
#       bash infra/terraform/scripts/test/network_graph_check.sh [모듈 디렉터리]
#       모듈 디렉터리를 주지 않으면 이 저장소의 modules/network 다.
# 종료 코드: 0 전부 통과 / 1 하나라도 실패 · 모듈 디렉터리 없음

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly GRAPH_PATH="${SCRIPT_DIR}/../assert_graph_path.sh"
readonly DEFAULT_NETWORK_MODULE="${SCRIPT_DIR}/../../modules/network"

readonly ENDPOINT='aws_vpc_endpoint.interface'
readonly INGRESS_RULE='aws_vpc_security_group_ingress_rule.interface_endpoints_https'
# 변이로 지우는 줄(main.tf 에 정확히 한 번 있어야 한다).
readonly DEPENDS_ON_LINE="  depends_on = [${INGRESS_RULE}]"

readonly EXIT_NO_PATH=1
readonly EXIT_USAGE=2

readonly EXPECTED_CASES=11

failures=0
passes=0
tmp_root=""
out=""
err=""
status=0

#######################################
# 임시 디렉터리 뿌리를 지운다(EXIT 트랩 — INT · TERM 도 exit 로 여기 온다).
# Globals:
#   tmp_root
#######################################
remove_tmp_root() {
  if [[ -n "${tmp_root}" ]]; then
    rm -rf -- "${tmp_root}"
  fi
}

#######################################
# 시험 하나의 실패를 알리고 센다.
# Globals:
#   failures
# Arguments:
#   실패 설명.
#######################################
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

#######################################
# 시험 하나의 통과를 적고 센다.
# Globals:
#   passes
# Arguments:
#   사례 이름.
#######################################
pass() {
  printf 'ok: %s\n' "$1"
  passes=$((passes + 1))
}

#######################################
# 그래프(표준 입력)를 assert_graph_path.sh 에 넘겨 돌리고 결과를 받는다.
# Globals:
#   tmp_root, out, err, status
# Arguments:
#   assert_graph_path.sh 에 넘길 인자.
#######################################
run_graph_path() {
  bash "${GRAPH_PATH}" "$@" >"${tmp_root}/out" 2>"${tmp_root}/err"
  status=$?
  out="$(<"${tmp_root}/out")"
  err="$(<"${tmp_root}/err")"
}

#######################################
# 종료 코드 · 표준 출력 · 표준 오류가 정확히 같은지 본다.
# Globals:
#   out, err, status
# Arguments:
#   사례 이름, 종료 코드, 표준 출력, 표준 오류.
#######################################
expect_result() {
  local name="$1"
  if [[ "${status}" -ne "$2" || "${out}" != "$3" || "${err}" != "$4" ]]; then
    fail "${name}: 종료 ${status} (기대 $2)" \
      "출력 [${out}] (기대 [$3]) 오류 [${err}] (기대 [$4])"
    return
  fi
  pass "${name}"
}

#######################################
# 노드 줄과 간선 줄로 terraform graph 꼴의 DOT 그래프를 낸다. 노드 줄은
# A · B · C · D 만 쓴다(간선에 나온 이름도 노드로 친다).
# Arguments:
#   간선 "출발>도착" 들.
#######################################
dot_graph() {
  local edge
  printf '%s\n' 'digraph G {' '  rankdir = "RL";' \
    '  node [shape = rect, fontname = "sans-serif"];'
  printf '  "%s" [label="%s"];\n' A A B B C C D D
  for edge in "$@"; do
    printf '  "%s" -> "%s";\n' "${edge%%>*}" "${edge#*>}"
  done
  printf '%s\n' '}'
}

#######################################
# 합성 그래프: 경로 있음 둘 · 경로 없음 셋 · 사용법 오류(종료 2) 넷.
#######################################
test_synthetic_graphs() {
  run_graph_path A B < <(dot_graph 'A>B')
  expect_result direct-edge 0 'graph-path: A -> B 경로 있음' ''

  # terraform graph 의 추이 축약이 직접 간선을 지운 꼴이다.
  run_graph_path A C < <(dot_graph 'A>B' 'B>C' 'D>A')
  expect_result transitive-path 0 'graph-path: A -> C 경로 있음' ''

  run_graph_path A B < <(dot_graph 'B>A')
  expect_result reverse-edge-only "${EXIT_NO_PATH}" '' \
    'graph-path: A -> B 경로 없음'

  run_graph_path A D < <(dot_graph 'A>B' 'B>C' 'D>C')
  expect_result no-path "${EXIT_NO_PATH}" '' 'graph-path: A -> D 경로 없음'

  # C 는 노드 줄로만 있고 간선이 없다 — 「노드 없음」(종료 2)이 아니다.
  run_graph_path A C < <(dot_graph 'A>B')
  expect_result isolated-node "${EXIT_NO_PATH}" '' 'graph-path: A -> C 경로 없음'

  run_graph_path A E < <(dot_graph 'A>B')
  expect_result missing-node "${EXIT_USAGE}" '' \
    'graph-path: 그래프에 노드가 없다: A 또는 E'

  run_graph_path A B </dev/null
  expect_result empty-input "${EXIT_USAGE}" '' \
    'graph-path: 입력이 terraform graph 의 DOT 그래프가 아니다'

  run_graph_path A B < <(printf '%s\n' '"A" -> "B";')
  expect_result not-dot "${EXIT_USAGE}" '' \
    'graph-path: 입력이 terraform graph 의 DOT 그래프가 아니다'

  run_graph_path A </dev/null
  expect_result usage "${EXIT_USAGE}" '' \
    '사용: terraform graph | assert_graph_path.sh <출발 주소> <도착 주소>'
}

#######################################
# 모듈(또는 그 사본) 디렉터리에서 terraform 을 돌린다. 데이터 디렉터리는 init 된
# 원본의 것이다. 입력을 받지 않고 갱신 확인을 끈다.
# Arguments:
#   디렉터리, 데이터 디렉터리, terraform 인자.
#######################################
run_terraform() {
  local dir="$1"
  local data_dir="$2"
  shift 2
  CHECKPOINT_DISABLE=1 TF_IN_AUTOMATION=1 TF_INPUT=0 TF_DATA_DIR="${data_dir}" \
    terraform -chdir="${dir}" "$@" </dev/null
}

#######################################
# 모듈의 데이터 디렉터리 경로를 낸다 — terraform 과 같게 TF_DATA_DIR 이 상대
# 경로면 모듈 디렉터리 기준이고, 비었거나 없으면 <모듈>/.terraform 이다.
# Arguments:
#   모듈 디렉터리(절대 경로).
#######################################
data_dir_of() {
  local data_dir="${TF_DATA_DIR:-.terraform}"
  if [[ "${data_dir}" != /* ]]; then
    data_dir="$1/${data_dir}"
  fi
  printf '%s\n' "${data_dir}"
}

#######################################
# main.tf 에서 depends_on 줄을 지운다. 정확히 한 줄이 아니면 실패한다.
# Arguments:
#   main.tf 경로.
# Returns:
#   지운 줄이 하나가 아니면 1.
#######################################
remove_depends_on() {
  local file="$1"
  local kept removed
  kept="$(awk -v line="${DEPENDS_ON_LINE}" '$0 != line' "${file}")" || return 1
  removed=$(($(wc -l <"${file}") - $(printf '%s\n' "${kept}" | wc -l)))
  if [[ "${removed}" -ne 1 ]]; then
    return 1
  fi
  printf '%s\n' "${kept}" >"${file}"
}

#######################################
# 실제 모듈: 그래프에 엔드포인트 → 인그레스 규칙 경로가 있고, depends_on 을 지운
# 사본에는 없다(노드는 그대로라 종료 1).
# Globals:
#   tmp_root
# Arguments:
#   init 된 모듈 디렉터리(절대 경로).
#######################################
test_network_module() {
  local module="$1"
  local data_dir copy graph
  data_dir="$(data_dir_of "${module}")"
  if [[ ! -d "${data_dir}" || ! -f "${module}/.terraform.lock.hcl" ]]; then
    fail "module-endpoint-after-ingress: 모듈이 init 되지 않음(${module})" \
      "— 먼저 terraform -chdir=<모듈> init -backend=false"
    fail "module-without-depends-on: 모듈이 init 되지 않음"
    return
  fi

  if ! graph="$(run_terraform "${module}" "${data_dir}" graph \
    2>"${tmp_root}/graph.err")"; then
    fail "module-endpoint-after-ingress: terraform graph 실패" \
      "$(<"${tmp_root}/graph.err")"
  else
    run_graph_path "${ENDPOINT}" "${INGRESS_RULE}" <<<"${graph}"
    expect_result module-endpoint-after-ingress 0 \
      "graph-path: ${ENDPOINT} -> ${INGRESS_RULE} 경로 있음" ''
  fi

  copy="${tmp_root}/network"
  if ! mkdir -- "${copy}" ||
    ! cp -- "${module}"/*.tf "${module}/.terraform.lock.hcl" "${copy}/"; then
    fail "module-without-depends-on: 모듈 사본을 만들지 못함"
    return
  fi
  if ! remove_depends_on "${copy}/main.tf"; then
    fail "module-without-depends-on: main.tf 에 지울 줄이 정확히 하나가 아님" \
      "[${DEPENDS_ON_LINE}]"
    return
  fi
  if ! graph="$(run_terraform "${copy}" "${data_dir}" graph \
    2>"${tmp_root}/graph.err")"; then
    fail "module-without-depends-on: terraform graph 실패" \
      "$(<"${tmp_root}/graph.err")"
    return
  fi
  run_graph_path "${ENDPOINT}" "${INGRESS_RULE}" <<<"${graph}"
  expect_result module-without-depends-on "${EXIT_NO_PATH}" '' \
    "graph-path: ${ENDPOINT} -> ${INGRESS_RULE} 경로 없음"
}

main() {
  local module
  if [[ "$#" -gt 1 ]]; then
    printf '%s\n' "사용: network_graph_check.sh [모듈 디렉터리]" >&2
    exit 1
  fi
  module="$(cd -- "${1:-${DEFAULT_NETWORK_MODULE}}" 2>/dev/null && pwd)" || {
    printf 'network_graph_check: 모듈 디렉터리가 없음: %s\n' \
      "${1:-${DEFAULT_NETWORK_MODULE}}" >&2
    exit 1
  }
  # 트랩을 먼저 건다 — 뿌리를 만든 직후에 신호가 와도 남지 않게.
  trap remove_tmp_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  tmp_root="$(mktemp -d \
    "${TMPDIR:-/tmp}/network_graph_check.XXXXXX")" || {
    printf '%s\n' "network_graph_check: 임시 디렉터리를 만들지 못함" >&2
    exit 1
  }
  test_synthetic_graphs
  test_network_module "${module}"

  remove_tmp_root
  if [[ -e "${tmp_root}" ]]; then
    fail "임시 디렉터리가 남음: ${tmp_root}"
  fi
  tmp_root=""
  if [[ "${passes}" -ne "${EXPECTED_CASES}" ]]; then
    fail "사례 수가 다름 — 통과 ${passes} ≠ ${EXPECTED_CASES}"
  fi
  if [[ "${failures}" -ne 0 ]]; then
    printf 'network_graph_check: %s건 실패(통과 %s건)\n' \
      "${failures}" "${passes}" >&2
    exit 1
  fi
  printf 'network_graph_check: 전부 통과(%s건 · 임시 디렉터리 잔여 0)\n' \
    "${passes}"
}

main "$@"
