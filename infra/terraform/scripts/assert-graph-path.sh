#!/usr/bin/env bash
#
# `terraform graph` 출력(DOT)에서 한 자원이 다른 자원에 기대는지 — 출발 노드에서
# 도착 노드로 가는 간선 경로가 있는지 — 단언한다. 시험이 `depends_on` 같은 의존
# 계약을 고정할 때 쓴다(terraform test 는 의존 그래프를 볼 수 없다).
#
# 직접 간선만이 아니라 경로를 본다. `terraform graph` 는 추이 축약을 해서, 다른
# 경로로 이미 이어진 직접 간선을 지운다 — 직접 간선만 보면 의존이 그대로인데도
# 실패할 수 있다.
#
# 사용: terraform graph | bash infra/terraform/scripts/assert-graph-path.sh \
#         <출발 주소> <도착 주소>
#       주소는 그래프의 노드 이름 그대로다(예: aws_vpc_endpoint.interface).
# 종료 코드: 0 경로 있음 / 1 경로 없음 / 2 사용법 오류 · 입력이 DOT 그래프가 아님 ·
#            출발이나 도착 노드가 그래프에 없음 — 이름이 바뀐 자원을 「경로 없음」
#            이나 통과로 넘기지 않는다.

set -uo pipefail

readonly EXIT_NO_PATH=1
readonly EXIT_USAGE=2

# find_path 의 awk 종료 코드.
readonly FIND_FOUND=0
readonly FIND_NO_PATH=1
readonly FIND_NOT_GRAPH=2
readonly FIND_NO_NODE=3

#######################################
# 표준 입력의 DOT 그래프에서 출발 → 도착 경로를 너비 우선으로 찾는다. 노드 줄은
# `"이름" [label=...]`, 간선 줄은 `"출발" -> "도착";` 꼴이다(Terraform 1.16 의
# `terraform graph` 출력).
# Arguments:
#   출발 주소, 도착 주소.
# Returns:
#   FIND_FOUND · FIND_NO_PATH · FIND_NOT_GRAPH · FIND_NO_NODE.
#######################################
find_path() {
  awk -v from="$1" -v to="$2" '
    BEGIN { FS = "\"" }
    NR == 1 { is_graph = match($0, /^digraph [^ ]+ \{$/) }
    NF == 3 && match($3, /^ \[label=/) { node[$2] = 1 }
    NF == 5 && $3 == " -> " && $5 == ";" {
      node[$2] = 1
      node[$4] = 1
      out[$2, ++out_count[$2]] = $4
    }
    END {
      if (!is_graph) exit 2
      if (!(from in node) || !(to in node)) exit 3
      queue[1] = from
      tail = 1
      seen[from] = 1
      for (head = 1; head <= tail; head++) {
        current = queue[head]
        for (i = 1; i <= out_count[current]; i++) {
          next_node = out[current, i]
          if (next_node == to) exit 0
          if (!(next_node in seen)) {
            seen[next_node] = 1
            queue[++tail] = next_node
          }
        }
      }
      exit 1
    }
  '
}

main() {
  if [[ "$#" -ne 2 || -z "$1" || -z "$2" ]]; then
    printf '%s\n' \
      "사용: terraform graph | assert-graph-path.sh <출발 주소> <도착 주소>" >&2
    exit "${EXIT_USAGE}"
  fi
  local from="$1"
  local to="$2"
  local found
  find_path "${from}" "${to}"
  found=$?
  case "${found}" in
    "${FIND_FOUND}")
      printf 'graph-path: %s -> %s 경로 있음\n' "${from}" "${to}"
      ;;
    "${FIND_NO_PATH}")
      printf 'graph-path: %s -> %s 경로 없음\n' "${from}" "${to}" >&2
      exit "${EXIT_NO_PATH}"
      ;;
    "${FIND_NOT_GRAPH}")
      printf '%s\n' \
        "graph-path: 입력이 terraform graph 의 DOT 그래프가 아니다" >&2
      exit "${EXIT_USAGE}"
      ;;
    "${FIND_NO_NODE}")
      printf 'graph-path: 그래프에 노드가 없다: %s 또는 %s\n' "${from}" "${to}" >&2
      exit "${EXIT_USAGE}"
      ;;
    *)
      printf 'graph-path: 그래프를 읽지 못했다(awk 종료 %s)\n' "${found}" >&2
      exit "${EXIT_USAGE}"
      ;;
  esac
}

main "$@"
