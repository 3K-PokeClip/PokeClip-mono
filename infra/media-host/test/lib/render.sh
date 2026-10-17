# shellcheck shell=bash
#
# infra/media-host 템플릿 렌더 도우미 — 시험이 prod 와 같은 렌더 함수로 템플릿을
# 렌더한다(proxy_contract.sh 가 쓰고, render_test 도 같이 쓴다).
#
# prod 에서는 M9 user_data 가 Terraform templatefile() 로 compose · nginx ·
# mediamtx 템플릿을 렌더한다. 시험도 같은 함수를 쓰도록 `terraform console` 로
# templatefile() 을 부른다. console 은 부를 때마다 새로 만든 임시 디렉터리에서
# 돈다 — 설정은 변수 둘과 locals 하나뿐이고(backend · provider · data source
# 없음), TF_DATA_DIR 도 그 안으로 격리한다. 운영 루트에서 console 을 돌리면 원격
# 상태 잠금을 잡기 때문이다.
#
# 템플릿 변수 값은 공용 시험값 파일 test/render_vars.json 하나에서 온다
# (RENDER_VARS_FILE). 값 하나를 바꾼 렌더가 필요한 시험은 그 파일을 jq 로 고친
# 사본을 만들어 넘긴다 — 시험 쪽에 값을 따로 적지 않는다.
#
# 렌더 결과는 console 에서 base64 로 받아 푼다. console 은 문자열을 HCL 따옴표 ·
# 이스케이프로 찍으므로, 그대로 받으면 템플릿의 따옴표 · 백슬래시 · 줄바꿈이
# 바뀐다.
#
# 실패 원인은 표준 오류에 그대로 쓴다(경로 포함). 부르는 쪽이 파일로 받아
# harness::log_tail 로 보인다 — 거기서 홈 경로를 가린다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

RENDER_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly RENDER_LIB_DIR
# 부르는 시험이 render::template 의 변수 파일로 넘긴다.
RENDER_VARS_FILE="$(cd -- "${RENDER_LIB_DIR}/.." && pwd)/render_vars.json"
# shellcheck disable=SC2034
readonly RENDER_VARS_FILE

#######################################
# console 이 읽을 설정(변수 둘 · locals 하나)을 쓴다.
# Arguments:
#   설정 디렉터리.
# Returns:
#   0 씀 / 그 밖 실패.
#######################################
render::write_config() {
  mkdir -p -- "$1" || return
  cat >"$1/main.tf" <<'EOF'
variable "template_path" {
  type = string
}

variable "vars_path" {
  type = string
}

locals {
  rendered = templatefile(var.template_path, jsondecode(file(var.vars_path)))
}
EOF
}

#######################################
# 템플릿 하나를 templatefile() 로 렌더해 파일에 쓴다.
# Globals:
#   TMPDIR, PWD
# Arguments:
#   템플릿 경로, 변수 JSON 파일 경로, 출력 파일 경로.
# Outputs:
#   실패하면 원인을 표준 오류에 쓴다.
# Returns:
#   0 렌더함 / 1 실패(실패하면 출력 파일을 남기지 않는다).
#######################################
render::template() {
  local template="$1" vars="$2" out="$3"
  local work encoded status=0

  # console 은 -chdir 로 옮겨 가 돌므로 상대 경로를 절대 경로로 바꿔 둔다.
  [[ "${template}" == /* ]] || template="${PWD}/${template}"
  [[ "${vars}" == /* ]] || vars="${PWD}/${vars}"
  if [[ ! -f "${template}" || ! -f "${vars}" ]]; then
    printf 'render: 템플릿 또는 변수 파일이 없다: %s · %s\n' \
      "${template}" "${vars}" >&2
    return 1
  fi
  work="$(mktemp -d "${TMPDIR:-/tmp}/pcbench-edge-proxy-render.XXXXXX")" \
    || return 1
  if ! render::write_config "${work}/config"; then
    rm -rf -- "${work:?}"
    return 1
  fi

  # TF_CLI_ARGS 류는 부르는 쪽 셸에서 console 인자를 몰래 더할 수 있어 지운다.
  if ! encoded="$(printf 'base64encode(local.rendered)\n' \
    | TF_DATA_DIR="${work}/data" env -u TF_CLI_ARGS -u TF_CLI_ARGS_console \
      terraform -chdir="${work}/config" console -no-color \
      -var "template_path=${template}" -var "vars_path=${vars}" \
      2>"${work}/console.err")"; then
    printf 'render: terraform console 실패:\n' >&2
    cat -- "${work}/console.err" >&2
    status=1
  elif [[ ! "${encoded}" =~ ^\"[A-Za-z0-9+/=]*\"$ ]]; then
    printf 'render: console 출력이 base64 문자열 한 줄이 아니다\n' >&2
    status=1
  else
    encoded="${encoded#\"}"
    encoded="${encoded%\"}"
    if ! printf '%s' "${encoded}" | base64 -d >"${out}"; then
      printf 'render: base64 풀기 실패\n' >&2
      rm -f -- "${out}"
      status=1
    fi
  fi
  rm -rf -- "${work:?}"
  return "${status}"
}
