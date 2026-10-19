# shellcheck shell=bash
# 부품끼리 · principal-check.sh 와 전역 변수를 나눠 쓴다(각 함수 주석의
# Globals) — 파일 하나만 검사하면 정의 · 사용 쪽이 안 보인다.
# shellcheck disable=SC2034,SC2154
#
# 2-3 판정의 시뮬레이터 질문 하나 — 요청 파일을 쓰고, aws iam
# simulate-principal-policy 를 부르고, 응답을 Q4 · Q5 로 읽는다(계획 M17b
# 2절 「질문 공통 규칙」). principal-check.sh 가 source 한다 — aws_call ·
# die · 상수는 부르는 쪽에 있다.
#
# 요청(한 줄 JSON — 값은 부르는 쪽이 검사한 것만, 큰따옴표 · 역슬래시 없음):
#   PolicySourceArn · ActionNames(하나) · ResourceArns(하나) ·
#   [자원 정책이 있으면 ResourceOwner(계정 루트) · CallerArn(주체)] ·
#   ContextEntries(aws:RequestedRegion string · aws:SecureTransport boolean
#   true · s3 동작이면 s3:TlsVersion numeric 1.3 · 행의 추가 문맥) ·
#   PolicyExclusionList(정확히 scp 하나 — SCP 제외, 결정 5 (가)).
# 자원 정책은 --resource-policy file://<정책 파일> 로 붙인다.
#
# 응답은 SIM_QUERY 로 뽑은 한 줄 「최상위 판정<탭>조직 판정<탭>최상위 누락
# 문맥<탭>자원별 판정<탭>자원별 누락 문맥」 이다(목록 칸은 쉼표 구분 · 빈 칸
# 가능, 조직 판정 칸이 없으면 None — SCP 제외 응답의 꼴). 꼴이 다르거나,
# 판정이 allowed · implicitDeny · explicitDeny 밖이거나, 조직 판정이 False
# (제외가 먹지 않음) · 불리언 아님이면 종료 2 다.

SIM_QUERY='EvaluationResults[0].[EvalDecision,'
SIM_QUERY+='OrganizationsDecisionDetail.AllowedByOrganizations,'
# shellcheck disable=SC2016 # 역따옴표는 JMESPath 리터럴이다.
SIM_QUERY+='join(`","`,MissingContextValues||`[]`),'
# shellcheck disable=SC2016 # 역따옴표는 JMESPath 리터럴이다.
SIM_QUERY+='join(`","`,ResourceSpecificResults[].EvalResourceDecision||`[]`),'
# shellcheck disable=SC2016 # 역따옴표는 JMESPath 리터럴이다.
SIM_QUERY+='join(`","`,ResourceSpecificResults[].MissingContextValues[]||`[]`)]'
readonly SIM_QUERY
readonly SCP_EXCLUSION='"PolicyExclusionList":[{"PolicyType":"scp"}]'
# 옛 CLI 가 요청 파일의 제외 목록을 거부할 때의 문구.
readonly CLI_TOO_OLD='Unknown parameter in input: "PolicyExclusionList"'

# simulate 가 남기는 결과: allow · missing · deny 와 최상위 판정.
sim_class=""
sim_decision=""

#######################################
# ContextEntries 항목 하나의 JSON 을 낸다.
# Arguments:
#   키, 값, 형.
# Outputs:
#   JSON 항목.
#######################################
context_entry() {
  local format='{"ContextKeyName":"%s","ContextKeyValues":["%s"],'
  format+='"ContextKeyType":"%s"}'
  # shellcheck disable=SC2059 # 형식 문자열은 위의 고정 값이다.
  printf "${format}" "$1" "$2" "$3"
}

#######################################
# 질문 하나의 요청 JSON 을 request_json 에 만든다. 자원 정책을 붙이는
# 질문(policy)이면 ResourceOwner(계정 루트) · CallerArn(주체)을, PC-A ⓐ
# (owner)면 ResourceOwner 만 넣는다.
# Globals:
#   request_json, account, SCP_EXCLUSION
# Arguments:
#   주체 ARN, 동작, 자원 ARN, 리전, 추가 문맥 JSON(앞에 쉼표 · 없으면 빈
#   값), policy · owner · -.
#######################################
build_request() {
  local source="$1" action="$2" resource="$3" region="$4" extra="$5"
  local entries
  entries="$(context_entry aws:RequestedRegion "${region}" string)"
  entries+=",$(context_entry aws:SecureTransport true boolean)"
  if [[ "${action}" == s3:* ]]; then
    entries+=",$(context_entry s3:TlsVersion 1.3 numeric)"
  fi
  request_json="{\"PolicySourceArn\":\"${source}\",\"ActionNames\":"
  request_json+="[\"${action}\"],\"ResourceArns\":[\"${resource}\"],"
  if [[ "$6" != - ]]; then
    request_json+="\"ResourceOwner\":\"arn:aws:iam::${account}:root\","
  fi
  if [[ "$6" == policy ]]; then
    request_json+="\"CallerArn\":\"${source}\","
  fi
  request_json+="\"ContextEntries\":[${entries}${extra}],${SCP_EXCLUSION}}"
}

#######################################
# 쉼표 목록의 모든 항목이 판정 값인지 본다(빈 목록 가능).
# Arguments:
#   쉼표 목록.
# Returns:
#   맞으면 0.
#######################################
decisions_ok() {
  local rest="$1," value
  [[ "$1" == "" ]] && return 0
  while [[ -n "${rest}" ]]; do
    value="${rest%%,*}"
    rest="${rest#*,}"
    case "${value}" in
      allowed | implicitDeny | explicitDeny) ;;
      *) return 1 ;;
    esac
  done
}

#######################################
# 응답 한 줄을 Q4 · Q5 로 읽어 sim_class · sim_decision 에 남긴다.
# Globals:
#   sim_class, sim_decision, req_n
# Arguments:
#   응답 줄.
#######################################
read_response() {
  local out="$1" tab=$'\t' tabs orgs top_missing decisions resource_missing
  tabs="${out//[!${tab}]/}"
  [[ "${out}" != *$'\n'* && "${#tabs}" -eq 4 ]] ||
    die "시뮬레이터 응답의 꼴이 다름(res-${req_n}.txt)"
  sim_decision="${out%%"${tab}"*}"
  out="${out#*"${tab}"}"
  orgs="${out%%"${tab}"*}"
  out="${out#*"${tab}"}"
  top_missing="${out%%"${tab}"*}"
  out="${out#*"${tab}"}"
  decisions="${out%%"${tab}"*}"
  resource_missing="${out#*"${tab}"}"
  decisions_ok "${sim_decision}" && [[ -n "${sim_decision}" ]] ||
    die "시뮬레이터 판정을 모름(res-${req_n}.txt)"
  decisions_ok "${decisions}" ||
    die "시뮬레이터 자원별 판정을 모름(res-${req_n}.txt)"
  case "${orgs}" in
    None | True) ;;
    False) die "조직 판정 False — SCP 제외가 먹지 않음(res-${req_n}.txt)" ;;
    *) die "조직 판정이 불리언이 아님(res-${req_n}.txt)" ;;
  esac
  sim_class=deny
  if [[ "${sim_decision}" == allowed || ",${decisions}," == *,allowed,* ]]; then
    sim_class=allow
  elif [[ -n "${top_missing}${resource_missing}" ]]; then
    sim_class=missing
  fi
}

#######################################
# request_json 으로 시뮬레이터를 한 번 부르고 응답을 읽는다.
# Globals:
#   request_json, call_out, call_err, req_n, SIM_QUERY, CLI_TOO_OLD
# Arguments:
#   [자원 정책 파일].
#######################################
simulate() {
  local -a extra=()
  [[ -z "${1:-}" ]] || extra=(--resource-policy "file://$1")
  if ! aws_call "${request_json}" iam simulate-principal-policy \
    ${extra[@]+"${extra[@]}"} --query "${SIM_QUERY}"; then
    if grep -qF -- "${CLI_TOO_OLD}" "${call_err}"; then
      die "CLI 가 PolicyExclusionList 를 받지 않음(2.36.12 이상 필요)"
    fi
    if grep -qF 'AccessDenied' -- "${call_err}"; then
      die "시뮬레이터 호출 자체가 거부됨(AccessDenied — res-${req_n}.err)"
    fi
    die "시뮬레이터 호출 실패(res-${req_n}.err)"
  fi
  read_response "${call_out}"
}

#######################################
# 질문 하나를 만들고 묻는다(build_request + simulate).
# Arguments:
#   주체 ARN, 동작, 자원 ARN, 리전, 추가 문맥 JSON, [자원 정책 파일].
#######################################
ask() {
  local policy=-
  [[ -z "${6:-}" ]] || policy=policy
  build_request "$1" "$2" "$3" "$4" "$5" "${policy}"
  simulate "${6:-}"
}
