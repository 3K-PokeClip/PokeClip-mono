# shellcheck shell=bash
# 부품끼리 · principal-check.sh 와 전역 변수를 나눠 쓴다(각 함수 주석의
# Globals) — 파일 하나만 검사하면 정의 · 사용 쪽이 안 보인다.
# shellcheck disable=SC2034,SC2154
#
# 2-3 판정 데이터(data/principal_sim/)를 읽고 꼴을 검사한다(계획 M17b 2절).
# principal-check.sh 가 source 한다 — die 와 상수(STAGE_ORDER · 패턴)는
# 부르는 쪽에, precheck::is_plain · precheck::tsv_ok 는
# lib/precheck_common.sh 에 있다.
#
# 데이터 규약: 탭 구분, 「#」 으로 시작하는 줄은 주석, 빈 줄은 건너뜀, 계정
# 자리는 ${ACCOUNT} 뿐.
#   dev_principals.tsv    키 · 종류(instance-profile · role · user) · 이름
#   positive_controls.tsv 주체 키 · 동작 · 리전 · 자원 ARN(주체마다 한 행)
#   prod_targets.tsv      자원 키 · 첫 단계 · 해석 방법 · 자원 유형 · 리전 ·
#                         동작(쉼표 구분) · 정책 읽기 방법 · 추가 문맥
# 꼴이 틀리면 종료 2 다. 진단에는 파일 이름과 줄 번호만 적는다 — 이름 · ARN
# 이 표준 오류에 나오지 않게.

# 자원 정책을 가지는 서비스(이 서비스의 행은 정책 읽기 방법 none 을 쓸 수
# 없다).
readonly POLICY_SERVICES=' s3 secretsmanager ecr sns sqs kms lambda '
readonly CONTEXT_KEY_PATTERN='^[a-z0-9-]+:[A-Za-z0-9]+$'
readonly NAME_PATTERN='^[A-Za-z0-9+=,.@_/-]+$'
readonly TAG_TYPE_PATTERN='^[a-z0-9-]+:[a-z0-9-]+$'
readonly ZONE_PATTERN='^[a-z0-9-]+(\.[a-z0-9-]+)+\.?$'

# 주체 행(같은 색인).
p_key=()
p_kind=()
p_name=()
# 양성 대조 행(p_* 와 같은 색인).
c_action=()
c_region=()
c_arn=()
# 대상 행(같은 색인).
t_key=()
t_stage=()
t_method=()
t_region=()
t_actions=()
t_read=()
t_context=()
# principal::context_entries 가 남기는 추가 문맥 JSON(앞에 쉼표가 붙은 항목들).
context_json=""
# principal::read_data_file · principal::split_tsv 가 남기는 줄 · 칸.
data_rows=()
fields=()

#######################################
# 데이터 파일의 내용 줄(주석 · 빈 줄 뺌)을 data_rows 에 「줄 번호<탭>줄」 로
# 담는다. 명령 치환 · 프로세스 치환 없이 읽는다 — die 가 하위 셸에 갇히지
# 않게.
# Globals:
#   DATA_DIR, data_rows
# Arguments:
#   파일 이름.
#######################################
principal::read_data_file() {
  local file="${DATA_DIR}/$1" line n=0
  data_rows=()
  [[ -f "${file}" && ! -L "${file}" ]] || die "데이터 파일이 없음: $1"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    n=$((n + 1))
    [[ -z "${line}" || "${line}" == '#'* ]] && continue
    data_rows+=("${n}"$'\t'"${line}")
  done <"${file}" || die "데이터 파일을 읽지 못함: $1"
}

#######################################
# 탭 구분 줄을 fields 에 나눠 담는다(precheck::tsv_ok 로 칸 수를 먼저 본
# 줄).
# Globals:
#   fields
# Arguments:
#   줄.
#######################################
principal::split_tsv() {
  local rest="$1"$'\t'
  fields=()
  while [[ -n "${rest}" ]]; do
    fields+=("${rest%%$'\t'*}")
    rest="${rest#*$'\t'}"
  done
}

#######################################
# 단계 이름의 STAGE_ORDER 색인을 낸다(문자열 비교는 쓰지 않는다 — cc348-2).
# Globals:
#   STAGE_ORDER
# Arguments:
#   단계 이름.
# Outputs:
#   색인.
# Returns:
#   판정 단계가 아니면 1.
#######################################
principal::stage_index() {
  local i
  for i in "${!STAGE_ORDER[@]}"; do
    if [[ "${STAGE_ORDER[i]}" == "$1" ]]; then
      printf '%s\n' "${i}"
      return 0
    fi
  done
  return 1
}

#######################################
# 주체 키의 p_* 색인을 낸다.
# Globals:
#   p_key
# Arguments:
#   주체 키.
# Outputs:
#   색인.
# Returns:
#   없으면 1.
#######################################
principal::principal_index() {
  local i
  for i in "${!p_key[@]}"; do
    if [[ "${p_key[i]}" == "$1" ]]; then
      printf '%s\n' "${i}"
      return 0
    fi
  done
  return 1
}

#######################################
# dev_principals.tsv 를 읽는다.
# Globals:
#   p_key, p_kind, p_name, KEY_PATTERN, NAME_PATTERN
#######################################
principal::load_principals() {
  local entry n line key kind name
  principal::read_data_file dev_principals.tsv
  for entry in ${data_rows[@]+"${data_rows[@]}"}; do
    n="${entry%%$'\t'*}"
    line="${entry#*$'\t'}"
    precheck::tsv_ok "${line}" 3 || die "dev_principals.tsv ${n}행의 칸 수가 틀림"
    principal::split_tsv "${line}"
    key="${fields[0]}"
    kind="${fields[1]}"
    name="${fields[2]}"
    [[ "${key}" =~ ${KEY_PATTERN} && "${name}" =~ ${NAME_PATTERN} ]] ||
      die "dev_principals.tsv ${n}행의 키 · 이름 꼴이 틀림"
    case "${kind}" in
      instance-profile | role | user) ;;
      *) die "dev_principals.tsv ${n}행의 주체 종류를 모름" ;;
    esac
    ! principal::principal_index "${key}" >/dev/null ||
      die "dev_principals.tsv ${n}행의 키가 겹침"
    p_key+=("${key}")
    p_kind+=("${kind}")
    p_name+=("${name}")
  done
  [[ "${#p_key[@]}" -gt 0 ]] || die "dev_principals.tsv 에 행이 없음"
}

#######################################
# 구체 ARN 꼴(계정 자리 외 와일드카드 · 따옴표 없음)인지 본다.
# Arguments:
#   ARN.
# Returns:
#   맞으면 0.
#######################################
principal::concrete_arn() {
  precheck::is_plain "$1" && [[ "$1" == arn:aws:* && "$1" != *'*'* &&
    "$1" != *[[:space:]]* ]]
}

#######################################
# positive_controls.tsv 를 읽는다. 주체마다 정확히 한 행이어야 한다.
# Globals:
#   p_key, c_action, c_region, c_arn, ACTION_PATTERN, REGION_PATTERN
#######################################
principal::load_controls() {
  local entry n line key action region arn i
  principal::read_data_file positive_controls.tsv
  for entry in ${data_rows[@]+"${data_rows[@]}"}; do
    n="${entry%%$'\t'*}"
    line="${entry#*$'\t'}"
    precheck::tsv_ok "${line}" 4 || die "positive_controls.tsv ${n}행의 칸 수가 틀림"
    principal::split_tsv "${line}"
    key="${fields[0]}"
    action="${fields[1]}"
    region="${fields[2]}"
    arn="${fields[3]}"
    i="$(principal::principal_index "${key}")" ||
      die "positive_controls.tsv ${n}행의 주체 키가 주체 목록에 없음"
    if ! [[ "${action}" =~ ${ACTION_PATTERN} &&
      "${region}" =~ ${REGION_PATTERN} ]] ||
      ! principal::concrete_arn "${arn}"; then
      die "positive_controls.tsv ${n}행의 꼴이 틀림"
    fi
    [[ -z "${c_action[i]:-}" ]] ||
      die "positive_controls.tsv ${n}행 — 한 주체에 행이 둘"
    c_action[i]="${action}"
    c_region[i]="${region}"
    c_arn[i]="${arn}"
  done
  for i in "${!p_key[@]}"; do
    [[ -n "${c_action[i]:-}" ]] ||
      die "양성 대조 행이 없는 주체: ${p_key[i]}"
  done
}

#######################################
# 추가 문맥(「-」 또는 「키:형:값[,값]」 항목을 「;」 로 이음)을 시뮬레이터
# ContextEntries 항목 JSON 으로 옮긴다.
# Globals:
#   context_json, CONTEXT_KEY_PATTERN
# Arguments:
#   추가 문맥 칸.
# Returns:
#   꼴이 틀리면 1.
#######################################
principal::context_entries() {
  local rest="$1" item key type values value list
  context_json=""
  [[ "${rest}" == - ]] && return 0
  rest+=';'
  while [[ -n "${rest}" ]]; do
    item="${rest%%;*}"
    rest="${rest#*;}"
    key="${item%%:*}:"
    item="${item#*:}"
    key+="${item%%:*}"
    item="${item#*:}"
    type="${item%%:*}"
    values="${item#*:},"
    [[ "${key}" =~ ${CONTEXT_KEY_PATTERN} && "${item}" == *:* ]] || return 1
    case "${type}" in
      string | stringList | boolean | numeric) ;;
      *) return 1 ;;
    esac
    list=""
    while [[ -n "${values}" ]]; do
      value="${values%%,*}"
      values="${values#*,}"
      precheck::is_plain "${value}" || return 1
      list+="${list:+,}\"${value}\""
    done
    context_json+=",{\"ContextKeyName\":\"${key}\",\"ContextKeyValues\":"
    context_json+="[${list}],\"ContextKeyType\":\"${type}\"}"
  done
}

#######################################
# 대상 행의 해석 방법 · 정책 읽기 방법 · 동작을 검사한다.
# Globals:
#   TAG_TYPE_PATTERN, ZONE_PATTERN, ACTION_PATTERN, POLICY_SERVICES
# Arguments:
#   해석 방법, 정책 읽기 방법, 동작(쉼표 구분).
# Returns:
#   맞으면 0.
#######################################
principal::target_fields_ok() {
  local method="$1" read="$2" actions="$3," action
  case "${method}" in
    tag:*) [[ "${method#tag:}" =~ ${TAG_TYPE_PATTERN} ]] || return 1 ;;
    arn:*) principal::concrete_arn "${method#arn:}" || return 1 ;;
    zone:*) [[ "${method#zone:}" =~ ${ZONE_PATTERN} ]] || return 1 ;;
    self:role | self:user | self:group | prodrole | policy:attached)
      [[ "${read}" == none || "${read}" == unread ]] || return 1
      ;;
    *) return 1 ;;
  esac
  case "${read}" in
    s3 | secretsmanager | ecr | sns | sqs | unread | none) ;;
    *) return 1 ;;
  esac
  while [[ -n "${actions}" ]]; do
    action="${actions%%,*}"
    actions="${actions#*,}"
    [[ "${action}" =~ ${ACTION_PATTERN} ]] || return 1
    [[ "${read}" != none ||
      "${POLICY_SERVICES}" != *" ${action%%:*} "* ]] || return 1
  done
}

#######################################
# prod_targets.tsv 를 읽는다.
# Globals:
#   t_key, t_stage, t_method, t_region, t_actions, t_read, t_context,
#   KEY_PATTERN, REGION_PATTERN
#######################################
principal::load_targets() {
  local entry n line key stage method type region actions read context
  local seen=$'\n'
  principal::read_data_file prod_targets.tsv
  for entry in ${data_rows[@]+"${data_rows[@]}"}; do
    n="${entry%%$'\t'*}"
    line="${entry#*$'\t'}"
    precheck::tsv_ok "${line}" 8 || die "prod_targets.tsv ${n}행의 칸 수가 틀림"
    principal::split_tsv "${line}"
    key="${fields[0]}"
    stage="${fields[1]}"
    method="${fields[2]}"
    type="${fields[3]}"
    region="${fields[4]}"
    actions="${fields[5]}"
    read="${fields[6]}"
    context="${fields[7]}"
    if ! [[ "${key}" =~ ${KEY_PATTERN} && "${type}" =~ ^[a-z0-9-]+$ &&
      "${region}" =~ ${REGION_PATTERN} ]]; then
      die "prod_targets.tsv ${n}행의 키 · 유형 · 리전 꼴이 틀림"
    fi
    principal::stage_index "${stage}" >/dev/null ||
      die "prod_targets.tsv ${n}행의 첫 단계를 모름"
    principal::target_fields_ok "${method}" "${read}" "${actions}" ||
      die "prod_targets.tsv ${n}행의 해석 방법 · 동작 · 정책 읽기 꼴이 틀림"
    principal::context_entries "${context}" ||
      die "prod_targets.tsv ${n}행의 추가 문맥 꼴이 틀림"
    [[ "${seen}" != *$'\n'"${key}"$'\n'* ]] ||
      die "prod_targets.tsv ${n}행의 키가 겹침"
    seen+="${key}"$'\n'
    t_key+=("${key}")
    t_stage+=("${stage}")
    t_method+=("${method}")
    t_region+=("${region}")
    t_actions+=("${actions}")
    t_read+=("${read}")
    t_context+=("${context}")
  done
  [[ "${#t_key[@]}" -gt 0 ]] || die "prod_targets.tsv 에 행이 없음"
}
