# shellcheck shell=bash
# 부품끼리 · principal-check.sh 와 전역 변수를 나눠 쓴다(각 함수 주석의
# Globals) — 파일 하나만 검사하면 정의 · 사용 쪽이 안 보인다.
# shellcheck disable=SC2034,SC2154
#
# 2-3 판정의 주체 · 대상 해석(계획 M17b 2절 (3)(a) · (b)). 읽기 호출만 하고
# 모두 --cli-input-json file:// 로 넘긴다(aws_call). principal-check.sh 가
# source 한다 — aws_call · fail_call · die · 데이터 배열(principal_data.sh)은
# 부르는 쪽에 있다.
#
# 주체: instance-profile → iam get-instance-profile(역할 ARN — 같은 역할이면
# 한 단위로 합침), role → iam get-role, user → iam get-user. NoSuchEntity 와
# 「인스턴스 프로필에 역할 0개」 만 PRINCIPAL_MISSING 이고, 그 밖 오류는 종료
# 2 다(cc348-5). get-role · get-user 의 성공 응답은 ARN 이 정확히 하나여야
# 한다(빈 출력도 종료 2).
#
# 대상: 단계와 무관한 해석(tag: · arn: · zone: · prodrole)은 행마다 한 번,
# 주체에 매인 해석(self:group 의 그룹 · policy:attached 의 고객 관리형
# 정책)은 단위마다 한 번 한다. tag: · zone: · prodrole 이 0건이면
# TARGET_NONE 이다. zone: 은 뒤 점을 맞춘 이름이 같고 공개(PrivateZone =
# False)인 존이 정확히 1건이어야 하고, 2건 이상이면 종료 2 다(cc348-3). 존
# 목록이 잘렸으면(IsTruncated True · NextDNSName 있음 — 한 번에 최대 100개)
# 다음 쪽의 같은 이름 공개 존을 못 보므로 종료 2 다.

readonly PROD_ROLE_PATH='/pokeclip/prod/'
readonly AWS_MANAGED_POLICY='arn:aws:iam::aws:policy/'
readonly ZONE_ID_PATTERN='^/hostedzone/[A-Z0-9]+$'
# 존 목록 질의 — 첫 줄은 「IsTruncated<탭>NextDNSName」, 그 뒤 존마다 「이름<탭>
# ID<탭>PrivateZone」(text 출력은 스칼라를 먼저, 목록을 줄마다 낸다).
ZONE_QUERY='[IsTruncated,NextDNSName,'
ZONE_QUERY+='HostedZones[].[Name,Id,Config.PrivateZone]]'
readonly ZONE_QUERY

# 주체 단위(같은 ARN 을 합친 것 — 같은 색인).
u_arn=()
u_keys=()
u_kind=()
u_groups=()
u_attached=()
# PRINCIPAL_MISSING 인 주체 키.
missing_keys=()
# 대상 행의 단계 무관 해석 결과(t_* 와 같은 색인 — ARN 줄들, 0건이면 빈 값)
# 와 TARGET_NONE 여부(1).
tr_arns=()
tr_none=()
# prodrole 해석 결과(ARN 줄들)와 해석했는지.
prod_roles=""
prod_roles_done=0
# read_list · resolve_principal 이 남기는 값.
list_items=""
resolved_arn=""
merged_lines=""

#######################################
# ARN 의 마지막 「/」 뒤(이름)를 낸다.
# Arguments:
#   ARN.
# Outputs:
#   이름.
#######################################
arn_name() {
  printf '%s\n' "${1##*/}"
}

#######################################
# 마지막 읽기 호출의 탭 · 줄바꿈 구분 출력을 list_items(줄마다 하나)로 옮긴다.
# 항목마다 정규식을 통과해야 한다(아니면 응답 꼴 — 종료 2).
# Globals:
#   call_out, list_items, req_n
# Arguments:
#   항목 정규식.
#######################################
read_list() {
  local pattern="$1" rest item
  rest="${call_out//$'\t'/$'\n'}"$'\n'
  list_items=""
  while [[ -n "${rest}" ]]; do
    item="${rest%%$'\n'*}"
    rest="${rest#*$'\n'}"
    [[ -n "${item}" ]] || continue
    if ! [[ "${item}" =~ ${pattern} ]] || ! is_plain "${item}"; then
      die "읽기 응답의 꼴이 다름(res-${req_n}.txt)"
    fi
    list_items+="${item}"$'\n'
  done
}

#######################################
# 이 계정의 IAM ARN 정규식을 낸다.
# Globals:
#   account
# Arguments:
#   종류(role · user · group · policy).
# Outputs:
#   정규식.
#######################################
iam_pattern() {
  printf '^arn:aws:iam::%s:%s/[A-Za-z0-9+=,.@_/-]+$\n' "${account}" "$1"
}

#######################################
# 주체 하나를 ARN 으로 푼다. 없으면 resolved_arn 이 빈 값이다.
# Globals:
#   p_kind, p_name, resolved_arn, call_err, list_items
# Arguments:
#   p_* 색인.
#######################################
resolve_principal() {
  local i="$1" kind="${p_kind[$1]}" body query call field pattern
  case "${kind}" in
    instance-profile)
      call=get-instance-profile field=InstanceProfileName
      query='InstanceProfile.Roles[].Arn'
      pattern="$(iam_pattern role)"
      ;;
    role)
      call=get-role field=RoleName query=Role.Arn
      pattern="$(iam_pattern role)"
      ;;
    *)
      call=get-user field=UserName query=User.Arn
      pattern="$(iam_pattern user)"
      ;;
  esac
  body="{\"${field}\":\"${p_name[i]}\"}"
  resolved_arn=""
  if ! aws_call "${body}" iam "${call}" --query "${query}"; then
    grep -qF '(NoSuchEntity)' -- "${call_err}" && return 0
    fail_call "주체 해석"
  fi
  read_list "${pattern}"
  if [[ "${list_items}" == *$'\n'?* ||
    ( "${kind}" != instance-profile && -z "${list_items}" ) ]]; then
    die "주체 응답에 ARN 이 하나가 아님(res-${req_n}.txt)"
  fi
  resolved_arn="${list_items%$'\n'}"
}

#######################################
# 주체를 모두 풀어 단위로 합치고 principals.tsv 에 남긴다.
# Globals:
#   p_key, p_kind, u_arn, u_keys, u_kind, missing_keys, evidence_dir
#######################################
resolve_principals() {
  local i j kind record=""
  for i in "${!p_key[@]}"; do
    resolve_principal "${i}"
    record+="${p_key[i]}"$'\t'"${p_kind[i]}"$'\t'"${resolved_arn:-MISSING}"
    record+=$'\n'
    if [[ -z "${resolved_arn}" ]]; then
      missing_keys+=("${p_key[i]}")
      continue
    fi
    kind=role
    [[ "${p_kind[i]}" == user ]] && kind=user
    for j in ${u_arn[@]+"${!u_arn[@]}"}; do
      if [[ "${u_arn[j]}" == "${resolved_arn}" ]]; then
        u_keys[j]+=",${p_key[i]}"
        continue 2
      fi
    done
    u_arn+=("${resolved_arn}")
    u_keys+=("${p_key[i]}")
    u_kind+=("${kind}")
    u_groups+=("")
    u_attached+=("")
  done
  write_evidence "${evidence_dir}/principals.tsv" '%s' "${record}"
}

#######################################
# prod 역할(경로 /pokeclip/prod/)을 한 번만 푼다.
# Globals:
#   prod_roles, prod_roles_done, list_items, PROD_ROLE_PATH
#######################################
resolve_prod_roles() {
  [[ "${prod_roles_done}" -eq 1 ]] && return 0
  aws_call "{\"PathPrefix\":\"${PROD_ROLE_PATH}\"}" iam list-roles \
    --query 'Roles[].Arn' || fail_call "prod 역할 해석"
  read_list "$(iam_pattern role)"
  prod_roles="${list_items}"
  prod_roles_done=1
}

#######################################
# zone: 행을 존 ARN 으로 푼다(뒤 점을 맞춘 이름 일치 · 공개 · 정확히 1건).
# 목록이 잘렸으면 멈춘다.
# Globals:
#   call_out, list_items, req_n, ZONE_ID_PATTERN, ZONE_QUERY
# Arguments:
#   존 이름.
#######################################
resolve_zone() {
  local want="${1%.}." rest line name id private found=""
  aws_call "{\"DNSName\":\"${want}\"}" route53 list-hosted-zones-by-name \
    --query "${ZONE_QUERY}" || fail_call "존 해석"
  line="${call_out%%$'\n'*}"
  case "${line}" in
    $'False\tNone') ;;
    True$'\t'* | False$'\t'?*) die "존 목록이 잘림(res-${req_n}.txt)" ;;
    *) die "존 목록 응답의 꼴이 다름(res-${req_n}.txt)" ;;
  esac
  rest="${call_out#*$'\n'}"$'\n'
  [[ "${call_out}" == *$'\n'* ]] || rest=""
  list_items=""
  while [[ -n "${rest}" ]]; do
    line="${rest%%$'\n'*}"
    rest="${rest#*$'\n'}"
    [[ -n "${line}" ]] || continue
    tsv_ok "${line}" 3 || die "존 목록 응답의 꼴이 다름(res-${req_n}.txt)"
    name="${line%%$'\t'*}"
    private="${line##*$'\t'}"
    id="${line#*$'\t'}"
    id="${id%%$'\t'*}"
    [[ "${id}" =~ ${ZONE_ID_PATTERN} ]] ||
      die "존 목록 응답의 꼴이 다름(res-${req_n}.txt)"
    [[ "${name%.}." == "${want}" && "${private}" == False ]] || continue
    [[ -z "${found}" ]] || die "같은 이름의 공개 존이 둘 이상(res-${req_n}.txt)"
    found="arn:aws:route53:::hostedzone/${id#/hostedzone/}"
  done
  [[ -z "${found}" ]] || list_items="${found}"$'\n'
}

#######################################
# 단계 무관 해석 하나(tag: · arn: · zone: · prodrole)를 list_items 로 낸다.
# Globals:
#   t_method, t_region, list_items, account, ACCOUNT_PLACEHOLDER, prod_roles
# Arguments:
#   t_* 색인.
#######################################
resolve_target_arns() {
  local t="$1" method="${t_method[$1]}" body
  list_items=""
  case "${method}" in
    arn:*)
      list_items="${method#arn:}"
      list_items="${list_items//${ACCOUNT_PLACEHOLDER}/${account}}"$'\n'
      ;;
    tag:*)
      body='{"TagFilters":[{"Key":"Environment","Values":["prod"]}],'
      body+="\"ResourceTypeFilters\":[\"${method#tag:}\"]}"
      aws_call "${body}" resourcegroupstaggingapi get-resources \
        --region "${t_region[t]}" \
        --query 'ResourceTagMappingList[].ResourceARN' ||
        fail_call "태그 해석"
      read_list '^arn:aws:[a-z0-9-]+:[a-z0-9-]*:[0-9]*:[^*[:space:]]+$'
      ;;
    zone:*) resolve_zone "${method#zone:}" ;;
    prodrole)
      resolve_prod_roles
      list_items="${prod_roles}"
      ;;
    *) ;;
  esac
}

#######################################
# 이번 단계에서 도는 대상 행의 단계 무관 해석을 하고 targets.tsv 에 남긴다.
# Globals:
#   t_key, t_method, t_active, tr_arns, tr_none, list_items, evidence_dir
#######################################
resolve_targets() {
  local t rest record=""
  for t in "${!t_key[@]}"; do
    tr_arns[t]=""
    tr_none[t]=0
    [[ "${t_active[t]}" -eq 1 ]] || continue
    case "${t_method[t]}" in
      self:* | policy:attached) continue ;;
      *) ;;
    esac
    resolve_target_arns "${t}"
    tr_arns[t]="${list_items}"
    if [[ -z "${list_items}" ]]; then
      tr_none[t]=1
      record+="${t_key[t]}"$'\t'"TARGET_NONE"$'\n'
      continue
    fi
    rest="${list_items}"
    while [[ -n "${rest}" ]]; do
      record+="${t_key[t]}"$'\t'"${rest%%$'\n'*}"$'\n'
      rest="${rest#*$'\n'}"
    done
  done
  write_evidence "${evidence_dir}/targets.tsv" '%s' "${record}"
}

#######################################
# 붙은 관리형 정책 목록 호출 하나의 고객 관리형 정책을 list_items 로 낸다
# (AWS 관리형은 뺀다).
# Globals:
#   list_items, AWS_MANAGED_POLICY
# Arguments:
#   호출(list-attached-*-policies), 요청 칸, 이름.
#######################################
attached_policies() {
  local call="$1" field="$2" name="$3" item rest
  aws_call "{\"${field}\":\"${name}\"}" iam "${call}" \
    --query 'AttachedPolicies[].PolicyArn' || fail_call "붙은 정책 해석"
  read_list '^arn:aws:iam::[a-z0-9]+:policy/[A-Za-z0-9+=,.@_/-]+$'
  rest="${list_items}"
  list_items=""
  while [[ -n "${rest}" ]]; do
    item="${rest%%$'\n'*}"
    rest="${rest#*$'\n'}"
    [[ "${item}" == "${AWS_MANAGED_POLICY}"* ]] && continue
    list_items+="${item}"$'\n'
  done
}

#######################################
# merged_lines(줄 목록)에 없는 줄들만 더한다(순서 유지). 명령 치환을 쓰지
# 않는다 — 끝 줄바꿈이 잘려 목록이 깨지지 않게.
# Globals:
#   merged_lines
# Arguments:
#   더할 줄들.
#######################################
merge_lines() {
  local rest="$1" item
  while [[ -n "${rest}" ]]; do
    item="${rest%%$'\n'*}"
    rest="${rest#*$'\n'}"
    [[ $'\n'"${merged_lines}" == *$'\n'"${item}"$'\n'* ]] ||
      merged_lines+="${item}"$'\n'
  done
}

#######################################
# 이번 단계의 대상 행 가운데 해석 방법이 하나라도 그것인지 본다.
# Globals:
#   t_method, t_active
# Arguments:
#   해석 방법.
# Returns:
#   있으면 0.
#######################################
uses_method() {
  local t
  for t in "${!t_method[@]}"; do
    [[ "${t_active[t]}" -eq 1 && "${t_method[t]}" == "$1" ]] && return 0
  done
  return 1
}

#######################################
# 단위 하나의 고객 관리형 정책(자기 · 사용자의 그룹 · prod 역할에 붙은
# 것)을 u_attached 에 둔다.
# Globals:
#   u_arn, u_kind, u_groups, u_attached, prod_roles, list_items,
#   merged_lines
# Arguments:
#   u_* 색인.
#######################################
resolve_attached() {
  local j="$1" rest group role
  merged_lines=""
  if [[ "${u_kind[j]}" == role ]]; then
    attached_policies list-attached-role-policies RoleName \
      "$(arn_name "${u_arn[j]}")"
  else
    attached_policies list-attached-user-policies UserName \
      "$(arn_name "${u_arn[j]}")"
  fi
  merge_lines "${list_items}"
  rest="${u_groups[j]}"
  while [[ -n "${rest}" ]]; do
    group="${rest%%$'\n'*}"
    rest="${rest#*$'\n'}"
    attached_policies list-attached-group-policies GroupName \
      "$(arn_name "${group}")"
    merge_lines "${list_items}"
  done
  resolve_prod_roles
  rest="${prod_roles}"
  while [[ -n "${rest}" ]]; do
    role="${rest%%$'\n'*}"
    rest="${rest#*$'\n'}"
    attached_policies list-attached-role-policies RoleName \
      "$(arn_name "${role}")"
    merge_lines "${list_items}"
  done
  u_attached[j]="${merged_lines}"
}

#######################################
# 단위마다 주체에 매인 해석(사용자의 그룹 · 붙은 고객 관리형 정책)을 한다.
# 이번 단계에 self:group · policy:attached 행이 없으면 부르지 않는다.
# Globals:
#   u_arn, u_kind, u_groups, list_items
#######################################
resolve_unit_targets() {
  local j need_attached=0 need_groups=0
  uses_method policy:attached && need_attached=1
  uses_method self:group && need_groups=1
  [[ "${need_attached}" -eq 1 ]] && need_groups=1
  for j in ${u_arn[@]+"${!u_arn[@]}"}; do
    if [[ "${need_groups}" -eq 1 && "${u_kind[j]}" == user ]]; then
      aws_call "{\"UserName\":\"$(arn_name "${u_arn[j]}")\"}" iam \
        list-groups-for-user --query 'Groups[].Arn' ||
        fail_call "그룹 해석"
      read_list "$(iam_pattern group)"
      u_groups[j]="${list_items}"
    fi
    if [[ "${need_attached}" -eq 1 ]]; then
      resolve_attached "${j}"
    fi
  done
}
