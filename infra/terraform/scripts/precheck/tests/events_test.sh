#!/usr/bin/env bash
#
# events.sh 를 시험한다 — 계획 M17a c1 사례 4(조회 리전 · 이벤트 ID 한 번 ·
# 출처 구분 · 90일 경고) · 5(유출) · 6(조회 실패).
#
# 사본 트리의 data/event_names.txt 에 고정 입력을 넣고, 가짜 aws 가 돌려줄
# 이벤트 표(FAKE_AWS_EVENTS)를 사례마다 정한다. 고정 입력의 행마다 조회
# 리전이 argv 의 --region 으로 그대로 가는지 본다. 실제 event_names.txt 의
# 리전 규칙(IAM · CloudFront = us-east-1 · STS = 둘 다 · 그 밖 = 서울)과
# SNS · SQS 정책 이름은 data_files_test.sh 가 본다.
#
# 사용: bash infra/terraform/scripts/precheck/tests/events_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly OLD_SINCE='2000-01-01T00:00:00Z'
# 고정 입력의 기대 출력(kty 59차 — 범주별 건수): 권한 변경 = ev-4 · ev-5,
# 역할 받기 = ev-1 · ev-2.
readonly WANT_EVENTS='EVENTS 4 (권한 변경 2 · 역할 받기 2)'

#######################################
# 사본 트리 · 고정 이벤트 이름 · 가짜 이벤트 표를 만든다.
#   AssumeRole 은 us-east-1 · 서울 두 행이고, 같은 ID(ev-1)가 두 리전에서
#   온다. AddPermission 은 SNS 행만 있고, 가짜 표는 Lambda 출처(ev-3)도
#   돌려준다. AttachUserPolicy 는 us-east-1 행만 있어 서울의 ev-6 은 조회
#   밖이다. 기대 건수 = ev-1 · ev-2 · ev-4 · ev-5 = 4 — 권한 변경(SNS ·
#   IAM 행) 2 · 역할 받기(STS 행) 2. 시각은 aws CLI 가 낼 수 있는 꼴을 모두
#   담고(Z · 서울 오프셋과 소수 초 · +00:00 · epoch 초), ev-5 의 주체는 결측
#   표기 None 이다(주체 칸은 None 도 받는다).
# Arguments:
#   사례 디렉터리.
# Returns:
#   만들지 못하면 1.
#######################################
setup_tree() {
  local case_dir="$1"
  testlib::make_tree "${case_dir}/tree" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'iam.amazonaws.com\tAttachUserPolicy\tus-east-1' \
    $'cloudfront.amazonaws.com\tUpdateDistribution\tus-east-1' \
    $'sts.amazonaws.com\tAssumeRole\tus-east-1' \
    $'sts.amazonaws.com\tAssumeRole\tap-northeast-2' \
    '' \
    $'sns.amazonaws.com\tAddPermission\tap-northeast-2' \
    $'sqs.amazonaws.com\tSetQueueAttributes\tap-northeast-2' \
    $'s3.amazonaws.com\tPutBucketPolicy\tap-northeast-2' \
    >"${case_dir}/tree/data/event_names.txt" || return 1
  {
    event_row us-east-1 AssumeRole ev-1 sts
    event_row ap-northeast-2 AssumeRole ev-1 sts
    event_row ap-northeast-2 AssumeRole ev-2 sts \
      '2026-10-13T12:00:00.250000+09:00'
    event_row ap-northeast-2 AddPermission ev-3 lambda
    event_row ap-northeast-2 AddPermission ev-4 sns '2026-10-13T05:30:00+00:00'
    event_row us-east-1 AttachUserPolicy ev-5 iam '1791874800.0' None
    event_row ap-northeast-2 AttachUserPolicy ev-6 iam
  } >"${case_dir}/in/events.tsv"
}

#######################################
# 가짜 이벤트 표의 행 하나. 시각은 aws CLI 가 낼 수 있는 꼴 — ISO 8601(Z ·
# ±HH:MM 오프셋 · 소수 초) 또는 epoch 초.
# Globals:
#   FAKE_USER
# Arguments:
#   리전, 이벤트 이름, 이벤트 ID, 서비스(출처 앞부분), [시각], [주체 —
#   없으면 가짜 사용자 이름].
# Outputs:
#   리전 · 이름 · ID · 출처 · 시각 · 주체(탭 구분).
#######################################
event_row() {
  testlib::tsv "$1" "$2" "$3" "$4.amazonaws.com" \
    "${5:-2026-10-13T03:00:00Z}" "${6:-${FAKE_USER}}"
}

#######################################
# 새 사례 디렉터리와 사본 트리를 만든다. 명령 치환 안에서 불리므로 실패는
# 세지 않고 종료 코드로만 알린다(부르는 쪽이 센다).
# Outputs:
#   사례 디렉터리.
# Returns:
#   준비 실패면 1.
#######################################
new_tree_case() {
  local case_dir
  case_dir="$(testlib::new_case)" || return 1
  setup_tree "${case_dir}" || return 1
  printf '%s\n' "${case_dir}"
}

#######################################
# 사본 트리의 events.sh 를 돌린다. 「이름=값」 꼴 인자는 환경으로 넘긴다
# (FAKE_AWS_* · AWS_CLI_HISTORY_FILE).
# Arguments:
#   사례 디렉터리, 결과 이름, [이름=값 …] 스크립트 인자.
#######################################
run_events() {
  local case_dir="$1" name="$2"
  local -a envs=("FAKE_AWS_EVENTS=${case_dir}/in/events.tsv")
  shift 2
  while [[ "$#" -gt 0 && ( "$1" == FAKE_AWS_*=* ||
    "$1" == AWS_CLI_HISTORY_FILE=* ) ]]; do
    envs+=("$1")
    shift
  done
  testlib::run "${case_dir}" "${name}" env "${envs[@]}" \
    bash "${case_dir}/tree/events.sh" "$@"
}

#######################################
# 지금 시각(UTC)을 --since 꼴로 낸다 — 90일 경고가 나오지 않아야 하는 값.
# Outputs:
#   YYYY-MM-DDTHH:MM:SSZ.
#######################################
now_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

#######################################
# 가짜 aws argv 기록에서 한 이벤트 이름의 조회 리전들을 정렬해 낸다.
# Arguments:
#   argv 기록 파일, 이벤트 이름.
# Outputs:
#   리전(공백 구분, 정렬).
#######################################
regions_for() {
  local log="$1" name="$2" line regions=""
  while IFS= read -r line; do
    [[ "${line}" =~ AttributeValue=${name}( |$) ]] || continue
    [[ "${line}" =~ --region\ ([a-z0-9-]+) ]] || continue
    regions+="${BASH_REMATCH[1]}"$'\n'
  done <"${log}"
  printf '%s' "${regions}" | LC_ALL=C sort | tr '\n' ' '
}

#######################################
# 사례 4: 행마다 조회 리전 · 같은 ID 한 번 · 다른 출처 제외 · stdout 한 줄.
# Globals:
#   run_status, run_out, WANT_EVENTS
#######################################
expect_counts_and_regions() {
  local case_dir log name want got since
  case_dir="$(new_tree_case)" || {
    testlib::fail "count: 시험 준비 실패"
    return
  }
  since="$(now_utc)"
  run_events "${case_dir}" count --since "${since}"
  if [[ "${run_status}" -ne 0 ||
    "$(cat -- "${run_out}.stdout")" != "${WANT_EVENTS}" ]]; then
    testlib::fail "count: 종료 0 · 「${WANT_EVENTS}」 한 줄을 기대했으나" \
      "${run_status} · $(cat -- "${run_out}.stdout" "${run_out}.stderr")"
    return
  fi
  log="${case_dir}/out/aws.log"
  for want in \
    'AttachUserPolicy us-east-1 ' \
    'UpdateDistribution us-east-1 ' \
    'AssumeRole ap-northeast-2 us-east-1 ' \
    'AddPermission ap-northeast-2 ' \
    'SetQueueAttributes ap-northeast-2 ' \
    'PutBucketPolicy ap-northeast-2 '; do
    name="${want%% *}"
    got="${name} $(regions_for "${log}" "${name}")"
    if [[ "${got}" != "${want}" ]]; then
      testlib::fail "count: ${name} 조회 리전이 「${want}」 가 아니라 「${got}」"
      return
    fi
  done
  if [[ "$(testlib::count "${run_out}.stderr" '90일')" -ne 0 ]]; then
    testlib::fail "count: 최근 --since 인데 90일 경고가 나옴"
    return
  fi
  if grep 'cloudtrail lookup-events' -- "${log}" |
    grep -qv -- "--start-time ${since} "; then
    testlib::fail "count: --start-time 이 --since(${since})가 아닌 조회가 있음"
    return
  fi
  testlib::ok "count → EVENTS 4(두 리전 같은 ID 한 번 · 다른 출처 제외) ·" \
    "행마다 조회 리전 · --start-time = --since"
}

#######################################
# kty 59차: 표준 출력 한 줄에 범주별 건수 — 권한 변경 a · 역할 받기 b ·
# a + b = n. 같은 이벤트 ID 가 두 범주 행에서 다 나와도(실제로는 없는 꼴)
# 한 번만 세고 권한 변경 쪽에 둔다(보수 쪽 — 권한 변경을 가리지 않게).
# 식별자(이름 · ARN · 계정)는 표준 출력에 없다.
# Globals:
#   run_out, run_status, FAKE_USER, FAKE_ACCOUNT
#######################################
expect_category_counts() {
  local case_dir out
  case_dir="$(new_tree_case)" || {
    testlib::fail "category: 시험 준비 실패"
    return
  }
  event_row us-east-1 AttachUserPolicy ev-2 iam >>"${case_dir}/in/events.tsv"
  run_events "${case_dir}" category --since "$(now_utc)"
  out="$(cat -- "${run_out}.stdout")"
  if [[ "${run_status}" -ne 0 ||
    "${out}" != 'EVENTS 4 (권한 변경 3 · 역할 받기 1)' ]]; then
    testlib::fail "category: 「EVENTS 4 (권한 변경 3 · 역할 받기 1)」 을" \
      "기대했으나 ${run_status} · ${out}"
    return
  fi
  if [[ "${out}" == *"${FAKE_USER}"* || "${out}" == *"${FAKE_ACCOUNT}"* ]]; then
    testlib::fail "category: 표준 출력에 식별자"
    return
  fi
  testlib::ok "category → 두 범주에 걸린 ID 는 한 번 · 권한 변경 쪽 · a + b = n"
}

#######################################
# 권한 변경 행의 응답에 같은 이벤트 ID 가 두 번 와도(페이지 겹침 등) 권한
# 변경 a 는 한 번만 센다. 역할 받기 쪽의 ID 한 번은 count 사례(ev-1 이 두
# 리전에서 옴)가 본다.
# Globals:
#   run_out, run_status, WANT_EVENTS
#######################################
expect_perm_duplicate_counted_once() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "perm-dup: 시험 준비 실패"
    return
  }
  event_row us-east-1 AttachUserPolicy ev-5 iam >>"${case_dir}/in/events.tsv"
  run_events "${case_dir}" perm-dup --since "$(now_utc)"
  if [[ "${run_status}" -ne 0 ||
    "$(cat -- "${run_out}.stdout")" != "${WANT_EVENTS}" ]]; then
    testlib::fail "perm-dup: 종료 0 · 「${WANT_EVENTS}」 를 기대했으나" \
      "${run_status} · $(cat -- "${run_out}.stdout" "${run_out}.stderr")"
    return
  fi
  testlib::ok "perm-dup → 권한 변경 행 응답에 두 번 온 ID 는 한 번"
}

#######################################
# 증거는 본인만 읽는다 — 부르는 쪽 umask 가 022 여도 증거 루트 아래 파일은
# 0600, 디렉터리는 0700 이다(events.sh 의 umask 077 · lib 의 0700).
# Globals:
#   run_out, run_status, WANT_EVENTS
#######################################
expect_evidence_private() {
  local case_dir root previous files wide
  case_dir="$(new_tree_case)" || {
    testlib::fail "private: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  previous="$(umask)"
  umask 022
  run_events "${case_dir}" private --since "$(now_utc)"
  umask "${previous}"
  files="$(find "${root}" -type f | wc -l | tr -d ' ')"
  wide="$(find "${root}" ! \( -type f -perm 0600 \) \
    ! \( -type d -perm 0700 \) -print)"
  if [[ "${run_status}" -ne 0 || "${files}" -eq 0 || -n "${wide}" ]]; then
    testlib::fail "private: 종료 0 · 0600 파일 · 0700 디렉터리만을 기대했으나" \
      "${run_status} · 파일 ${files}개 · 그 밖 — ${wide}"
    return
  fi
  testlib::ok "private → umask 022 에서 불러도 증거 파일 0600 · 디렉터리 0700"
}

#######################################
# 사례 4: --since 가 90일보다 이르면 표준 오류로 한계를 경고한다(조회는 함).
# Globals:
#   OLD_SINCE, run_status, run_out, WANT_EVENTS
#######################################
expect_ninety_day_warning() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "ninety: 시험 준비 실패"
    return
  }
  run_events "${case_dir}" ninety --since "${OLD_SINCE}"
  if [[ "${run_status}" -ne 0 ||
    "$(cat -- "${run_out}.stdout")" != "${WANT_EVENTS}" ]]; then
    testlib::fail "ninety: 종료 0 · ${WANT_EVENTS} 를 기대했으나 ${run_status}"
    return
  fi
  if [[ "$(testlib::count "${run_out}.stderr" '90일')" -lt 1 ]]; then
    testlib::fail "ninety: 90일 경고가 없음 — $(cat -- "${run_out}.stderr")"
    return
  fi
  testlib::ok "ninety → 90일 경고 · 조회는 그대로"
}

#######################################
# 사례 5: 이벤트 원본(주체 이름)은 증거 디렉터리에만 있고, 표준 출력 ·
# 표준 오류 · argv 에는 가짜 계정 ID · 사용자 이름이 0건.
# Globals:
#   FAKE_USER
#######################################
expect_no_leak() {
  local case_dir leaked raw
  case_dir="$(new_tree_case)" || {
    testlib::fail "leak: 시험 준비 실패"
    return
  }
  run_events "${case_dir}" leak --since "$(now_utc)"
  leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
  raw="$(grep -rlF -- "${FAKE_USER}" "${case_dir}/home/.pokeclip-precheck")"
  if [[ -n "${leaked}" || -z "${raw}" ]]; then
    testlib::fail "leak: 증거 루트 밖 유출(${leaked}) 또는 증거 안 원본 없음"
    return
  fi
  testlib::ok "leak → 원본은 증거 디렉터리에만 · 출력 · argv 유출 0"
}

#######################################
# 조회 응답의 꼴이 깨지면 0건으로 넘기지 않고 종료 2 —
# 빈 칸 접힘 · 칸 모자람 · 칸 남음, 이벤트 ID(꼴 밖 · 빔 · 결측 표기 None),
# 출처(꼴 밖 · None), 이름(꼴 밖 · None), 시각(꼴 밖 · 초 · 오프셋 · epoch
# 뒤에 덧붙은 글 · None). 마지막 줄 뒤에 줄바꿈이 없어도 그 이벤트를 센다.
# Globals:
#   FAKE_USER, run_status, run_out, WANT_EVENTS
#######################################
expect_malformed_lookup_rejected() {
  local case_dir kind env_arg leaked events
  for kind in empty-source bad-id none-id empty-id bad-source none-source \
    bad-time suffix-time suffix-zone suffix-epoch none-time short \
    extra-field bad-name none-name; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "lookup-${kind}: 시험 준비 실패"
      return
    }
    events="${case_dir}/in/events.tsv"
    env_arg=FAKE_AWS_LOOKUP=ok
    case "${kind}" in
      empty-source)
        testlib::tsv us-east-1 AssumeRole ev-9 '' 2026-10-13T03:00:00Z \
          "${FAKE_USER}" >>"${events}"
        ;;
      bad-id) event_row us-east-1 AssumeRole 'ev/9' sts >>"${events}" ;;
      none-id) event_row us-east-1 AssumeRole None sts >>"${events}" ;;
      bad-source)
        testlib::tsv us-east-1 AssumeRole ev-9 sts.example.com \
          2026-10-13T03:00:00Z "${FAKE_USER}" >>"${events}"
        ;;
      none-source)
        testlib::tsv us-east-1 AssumeRole ev-9 None 2026-10-13T03:00:00Z \
          "${FAKE_USER}" >>"${events}"
        ;;
      bad-time)
        event_row us-east-1 AssumeRole ev-9 sts yesterday >>"${events}"
        ;;
      suffix-time)
        event_row us-east-1 AssumeRole ev-9 sts '2026-10-13T03:00:00garbage' \
          >>"${events}"
        ;;
      suffix-zone)
        # 꼴이 맞는 오프셋 뒤에 덧붙은 글 — 끝 앵커만 잡는다.
        event_row us-east-1 AssumeRole ev-9 sts '2026-10-13T03:00:00Zgarbage' \
          >>"${events}"
        ;;
      suffix-epoch)
        event_row us-east-1 AssumeRole ev-9 sts '1791874800.0x' >>"${events}"
        ;;
      none-time) event_row us-east-1 AssumeRole ev-9 sts None >>"${events}" ;;
      *) env_arg="FAKE_AWS_LOOKUP=${kind}" ;;
    esac
    run_events "${case_dir}" "${kind}" "${env_arg}" --since "$(now_utc)"
    if [[ "${run_status}" -ne 2 ]] ||
      grep -q '^EVENTS ' -- "${run_out}.stdout"; then
      testlib::fail "lookup-${kind}: 종료 2 · EVENTS 없음을 기대했으나" \
        "${run_status} — $(cat -- "${run_out}.stdout")"
      return
    fi
    leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
    if [[ -n "${leaked}" ]]; then
      testlib::fail "lookup-${kind}: 증거 루트 밖 유출 — ${leaked}"
      return
    fi
  done
  case_dir="$(new_tree_case)" || return
  run_events "${case_dir}" no-final-newline FAKE_AWS_LOOKUP=no-final-newline \
    --since "$(now_utc)"
  if [[ "${run_status}" -ne 0 ||
    "$(cat -- "${run_out}.stdout")" != "${WANT_EVENTS}" ]]; then
    testlib::fail "lookup-no-final-newline: ${WANT_EVENTS} 를 기대했으나" \
      "${run_status} · $(cat -- "${run_out}.stdout")"
    return
  fi
  testlib::ok "lookup-format → 깨진 응답 15종 종료 2 · 줄바꿈 없는 마지막 줄도 셈"
}

#######################################
# 사례 6: 조회 실패 · 호출 자체의 AccessDenied · 잘못된 --since 는 종료 2 ·
# EVENTS 줄 없음 · 유출 0. 조회 실패와 AccessDenied 는 원인 문구가 다르다
# (「AccessDenied」 든 줄은 후자에만 하나). --since 자리의 입력은 되울리지
# 않는다(절대 경로를 주어도 표준 오류에 절대 경로 0).
# Globals:
#   run_status, run_out
#######################################
expect_unverifiable_cases() {
  local case_dir spec name leaked paths denied
  case_dir="$(new_tree_case)" || {
    testlib::fail "unverifiable: 시험 준비 실패"
    return
  }
  for spec in fail denied-call; do
    run_events "${case_dir}" "lookup-${spec}" FAKE_AWS_LOOKUP="${spec}" \
      --since "$(now_utc)"
    if [[ "${run_status}" -ne 2 ]] ||
      grep -q '^EVENTS ' -- "${run_out}.stdout"; then
      testlib::fail "lookup-${spec}: 종료 2 · EVENTS 없음을 기대했으나" \
        "${run_status}"
      return
    fi
    denied=0
    [[ "${spec}" == denied-call ]] && denied=1
    testlib::expect_stderr_count "lookup-${spec}" '조회 실패(' \
      "$((1 - denied))" &&
      testlib::expect_stderr_count "lookup-${spec}" AccessDenied \
        "${denied}" || return
  done
  for spec in 'bad-format 2026-10-13' 'future 2999-01-01T00:00:00Z' \
    'no-value' 'no-option'; do
    name="${spec%% *}"
    case "${name}" in
      bad-format | future)
        run_events "${case_dir}" "${name}" --since "${spec#* }"
        ;;
      no-value) run_events "${case_dir}" "${name}" --since ;;
      *) run_events "${case_dir}" "${name}" ;;
    esac
    if [[ "${run_status}" -ne 2 ]]; then
      testlib::fail "${name}: 종료 2 를 기대했으나 ${run_status}"
      return
    fi
  done
  run_events "${case_dir}" path-since --since "${case_dir}/home"
  paths="$(testlib::abs_path_lines "${run_out}.stderr")"
  if [[ "${run_status}" -ne 2 || -n "${paths}" ]]; then
    testlib::fail "path-since: 종료 2 · 입력 되울림 없음을 기대했으나" \
      "${run_status} — ${paths}"
    return
  fi
  leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
  if [[ -n "${leaked}" ]]; then
    testlib::fail "unverifiable: 증거 루트 밖 유출 — ${leaked}"
    return
  fi
  # 빈 칸이 탭 IFS 로 접혀 세 칸처럼 읽히는 event_names.txt 행.
  testlib::tsv sts.amazonaws.com '' AssumeRole us-east-1 \
    >>"${case_dir}/tree/data/event_names.txt"
  run_events "${case_dir}" collapsed-names --since "$(now_utc)"
  if [[ "${run_status}" -ne 2 ]]; then
    testlib::fail "collapsed-names: 종료 2 를 기대했으나 ${run_status}"
    return
  fi
  testlib::ok "unverifiable → 조회 실패 · AccessDenied · 잘못된 · 미래 --since ·" \
    "접힌 이름 행 모두 종료 2 · 유출 0 · --since 입력 되울림 0"
}

#######################################
# CLI 기록 — 프로필의 cli_history 가 enabled 이거나 AWS_CLI_HISTORY_FILE 이
# 설정돼 있으면 첫 AWS 호출 전에 종료 2(값 · 경로를 되울리지 않고 증거
# 디렉터리도 만들지 않음), 설정 읽기 실패도 종료 2, disabled 면 정상이고
# 설정 조회가 첫 조회보다 먼저다.
# Globals:
#   run_status, run_out, WANT_EVENTS
#######################################
expect_cli_history() {
  local case_dir spec name log want calls dirs leaked
  for spec in enabled file fail disabled; do
    name="cli-history-${spec}"
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    log="${case_dir}/out/aws.log"
    case "${spec}" in
      file)
        run_events "${case_dir}" "${name}" \
          "AWS_CLI_HISTORY_FILE=${case_dir}/in/qxhistory.db" \
          --since "$(now_utc)"
        ;;
      *)
        run_events "${case_dir}" "${name}" "FAKE_AWS_CLI_HISTORY=${spec}" \
          --since "$(now_utc)"
        ;;
    esac
    if [[ "${spec}" == disabled ]]; then
      if [[ "${run_status}" -ne 0 ||
        "$(cat -- "${run_out}.stdout")" != "${WANT_EVENTS}" ||
        "$(sed -n 1p -- "${log}")" != 'configure get cli_history' ||
        "$(sed -n 2p -- "${log}")" != 'cloudtrail lookup-events'* ]]; then
        testlib::fail "${name}: 종료 0 · 설정 조회 뒤 조회를 기대했으나" \
          "${run_status} — $(cat -- "${run_out}.stderr" "${log}")"
        return
      fi
      continue
    fi
    if [[ "${run_status}" -ne 2 || -s "${run_out}.stdout" ]]; then
      testlib::fail "${name}: 종료 2 · 표준 출력 없음을 기대했으나" \
        "${run_status} — $(cat -- "${run_out}.stdout" "${run_out}.stderr")"
      return
    fi
    calls="$(testlib::count "${log}" ' ')"
    want=1
    [[ "${spec}" == file ]] && want=0
    if [[ "${calls}" -ne "${want}" || ( "${want}" -eq 1 &&
      "$(cat -- "${log}")" != 'configure get cli_history' ) ]] ||
      grep -qF -e qxhistory -e enabled -- "${run_out}.stderr"; then
      testlib::fail "${name}: AWS 호출 ${calls}건(기대 ${want})이거나 값 ·" \
        "경로를 되울림 — $(cat -- "${run_out}.stderr")"
      return
    fi
    if [[ "${spec}" == fail ]]; then
      testlib::expect_stderr_count "${name}" \
        'CLI 기록 설정(cli_history)을 읽지 못함' 1 || return
    else
      testlib::expect_stderr_count "${name}" \
        'CLI 기록이 켜져 있음 — 증거 밖에 원문이 남음' 1 || return
    fi
    dirs="$(find "${case_dir}/home" -name 'events-*' -type d | wc -l)"
    leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
    if [[ "${dirs}" -ne 0 || -n "${leaked}" ]]; then
      testlib::fail "${name}: 멈췄는데 증거 디렉터리 ${dirs}개 또는 유출" \
        "(${leaked})"
      return
    fi
  done
  testlib::ok "cli-history → enabled · 기록 파일 환경 변수 · 설정 읽기 실패는" \
    "첫 AWS 호출 전 종료 2 · disabled 는 설정 조회 뒤 정상"
}

main() {
  testlib::init events_test
  expect_counts_and_regions
  expect_category_counts
  expect_perm_duplicate_counted_once
  expect_evidence_private
  expect_ninety_day_warning
  expect_no_leak
  expect_malformed_lookup_rejected
  expect_unverifiable_cases
  expect_cli_history
  testlib::finish events_test
}

main "$@"
