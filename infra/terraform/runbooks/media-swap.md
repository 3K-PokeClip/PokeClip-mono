# Media 교체 런북

prod Media 상자[^box]를 처음 띄우고(O7e), 다른 상자로 바꾸고(정상 교체 ·
설정 반영 교체), 방송 중에 바꾸는(force) 절차입니다. 설계 5-3 과 계획 M9 의
c3 · O7e 에 해당합니다. 상자는 `prod/media` 루트가 만들고
([prod/media](/infra/terraform/prod/media/README.md)), 상자 위 스크립트와 판정
줄은 [media-host README](/infra/media-host/README.md) 가 정본입니다.

*   실행자는 kty 입니다. **kty 로컬 터미널**에서 kty IAM 사용자 프로필로
    돌립니다. 출력에 계정 ID 가 든 ARN 이 섞일 수 있어 에이전트 세션이나
    세션의 `!` 로 돌리지 않습니다.
*   **SSM 명령 · apply · 상자 정지마다 실행 직전에 kty 가 승인합니다.**
*   SSM 명령 출력에는 판정 줄만 남깁니다. SSM 에서 `docker inspect` ·
    `docker exec … env` · `/run/pokeclip-media` 내용 출력은 하지 않습니다
    (media-host README 「운영 규칙」).
*   apply 가 `AccessDenied` 로 실패하면 원인이 무엇이든 계획 3절 「SCP 멈춤
    규칙」을 따릅니다 — 다시 시도하지 않고 kty 에게 보고합니다.

용어:

*   **활성 키**: `prod/media` 의 `ingest_active_key`(NLB `ingest`)와
    `http_active_key`(내부 ALB `live` · `control` · `playback`)입니다. 대상
    그룹에는 그 키의 상자 한 대만 등록됩니다.
*   **A · B**: 지금 활성인 상자의 키(A)와 새로 띄울 상자의 키(B)입니다
    (예: `a` · `b`). 키 이름은 소문자 · 숫자입니다.
*   **plan 범위**: apply 전에 plan 이 바꾸는 자원 목록이 그 걸음에서 기대한
    것과 정확히 같은지 보는 확인입니다(아래 「plan 범위」).
*   **설정 반영 교체**: 상자 설정(이미지 · 제어면 상류 · janitor · 판 등)을
    바꿀 때 떠 있는 상자를 고치지 않고 새 키의 상자로 바꾸는 교체입니다.
    떠 있는 상자의 user_data 는 `ignore_changes` 로 바뀌지 않습니다. 예외는
    아래 「제자리 전환」 하나입니다(공개 전 한정).
*   **제자리 전환**: 새 상자를 띄우지 않고, 떠 있는 상자 A 의 설정 파일 ·
    설치 묶음을 그 자리에서 바꾼 뒤 유닛을 다시 띄우는 절차입니다(아래
    「제자리 전환」). 재시작하는 동안 1–2분 끊깁니다.

## 언제 쓰나

| 상황 | 절 | 방송 |
|---|---|---|
| 처음 기동(O7e) | 「O7e — 처음 기동」 | 없음 |
| 상자 교체 · AMI 갱신 | 「정상 교체」 | 없는 시간대 |
| 설정을 바꿈(M15 · O8a · I1-6 반영 · 다이제스트 · 판 · janitor) | 「설정 반영 교체」 → 「정상 교체」 | 없는 시간대 |
| 새 상자를 띄울 수 없거나 설정 · 이미지만 바꿈 — 공개(O8) 전 한정 · 공개 뒤에는 1–2분 중단을 kty 가 감수할 때만 · 발행 목록(`lifecycle_publish_streams`) ID ≤ 1(옛 · 새 구성 각각 · 둘 다 차 있으면 같은 ID) | 「제자리 전환」 | 없는 시간대 · 송출 금지 구간 |
| O8 앞에 응답기 모드로 바꾸며 계약9 발행을 켬(kty 2026-11-03) | 「O8a 당김 교체」 — 정상 교체와 순서가 다름 | 없는 시간대 · 송출 금지 구간 |
| 방송이 있는데 바꿔야 함 | 「force」 | 있음 |
| apply 가 중간에 실패함 | 「부분 실패 복구」 | — |
| O7e 가 이미 끝나 하한 아래 사이드카가 도는 prod 에 M6 운영 SQL 을 적용함(대비 — 새 O7e 는 이 길로 가지 않음) | 「한 창 절차」 | 없는 시간대 |

**옛 모드 단서(모든 갈래):** 옛 모드 상자(`ingest_auth_image` 빈 구성 · M15
PR revert 판 포함)를 서비스에 넣는 모든 갈래(정상 · 설정 반영 · force · 부분
실패 복구의 되돌리기)는 O8a 5 뒤(SRT 인바운드 `0.0.0.0/0`)면 media-host
README 「되돌리기 순서」 1(인바운드를 팀 IP 로 축소 · 확인)을 먼저 하고, 교체
뒤 같은 절 3(팀 IP 밖 익명 송출 거부)을 확인합니다. 옛 모드는 익명 송출이라
인바운드가 열린 채면 누구나 송출 · 탈취 · DVR 리셋을 할 수 있습니다.

## 준비

### 작업 디렉터리와 상태

`prod/media` · `prod/platform` 을 `init -backend-config=backend.hcl` 로 연
체크아웃의 저장소 뿌리에서 돌립니다. 스크립트 · plan 파일은 저장소 밖 작업
디렉터리에 둡니다(plan 파일에는 계정 ID 가 든 ARN 이 있습니다). 교체가
끝나면 지웁니다.

```shell
umask 077
work="$(mktemp -d "${TMPDIR:-/tmp}/pokeclip-media-swap.XXXXXX")"
media_ids() { terraform -chdir=infra/terraform/prod/media output -json instance_ids; }
```

### SSM 명령 보내기

상자 스크립트를 파일로 쓰고 `AWS-RunShellScript` 로 보냅니다. 첫 줄
`set -- <인자>` 가 스크립트의 `$1` … 을 정합니다. 출력은 판정 줄과 SSM 상태
한 줄입니다.

```shell
media_ssm() {  # media_ssm <상자 키> <스크립트 파일> [인자 …]
  local id params cid cmd_state
  id="$(media_ids | jq -er --arg k "$1" '.[$k]')" || return 2
  params="${work}/params.json"
  jq -n --rawfile script "$2" --args \
    '{commands: [("set -- " + ($ARGS.positional | map(@sh) | join(" "))), $script]}' \
    "${@:3}" > "${params}"
  cid="$(aws ssm send-command --instance-ids "${id}" \
    --document-name AWS-RunShellScript --parameters "file://${params}" \
    --query Command.CommandId --output text)" || return 2
  while sleep 3; do
    cmd_state="$(aws ssm get-command-invocation --command-id "${cid}" \
      --instance-id "${id}" --query Status --output text)" || return 2
    case "${cmd_state}" in Pending | InProgress | Delayed) ;; *) break ;; esac
  done
  aws ssm get-command-invocation --command-id "${cid}" --instance-id "${id}" \
    --query StandardOutputContent --output text
  echo "SSM ${cmd_state}"
  [ "${cmd_state}" = Success ]
}
```

*   첫 조회가 `InvocationDoesNotExist` 로 실패하면 같은 `cid` 로 다시
    읽습니다(명령이 막 등록된 경우).
*   `media.env` 를 쓰는 상자 스크립트는 첫 줄에서 읽어 들입니다
    (`set -a; . /etc/pokeclip-media/media.env; set +a`). 그 파일에는 비밀 값이
    없습니다.
*   **`-` 로 시작하는 인자는 `media_ssm` 에 넘기지 않습니다.** jq 의
    `--args` 가 그 인자를 jq 옵션으로 읽어 실패합니다(2026-11-04
    `--passphrase` 실사고). 그런 인자는 상자 스크립트 안에 고정합니다(예:
    아래 「정상 교체 3」 의 `loopback-pp.sh`).
*   **`bin/lib/ops.sh` 를 읽는 상자 스크립트는 bash 로 넘깁니다.**
    `AWS-RunShellScript` 는 스크립트를 `sh` 로 돌립니다. `ops.sh` 의 함수
    이름(`ops::say` 꼴)은 sh(POSIX 모드)에서 쓸 수 없는 이름이라, 읽는
    순간 `not a valid identifier` 로 실패합니다(2026-11-04 상자에서 확인).

그런 스크립트는 본문을 아래 꼴로 감싸 bash 에 넘깁니다. `media_ssm` 이 첫
줄로 넣은 `set --` 인자가 그대로 bash 의 `$1` … 이 됩니다. 끝 줄 `B` 는
들여 쓰지 않습니다.

```shell
exec /bin/bash -s -- "$@" <<'B'
. /opt/pokeclip-media/bin/lib/ops.sh
# 본문
B
```

*   이 꼴에서는 표준 입력이 스크립트 본문 자체입니다. 표준 입력을 읽을 수
    있는 명령에는 `</dev/null` 을 붙입니다 — 붙이지 않으면 그 명령이 남은
    본문을 읽어 버려 뒤 명령이 깨집니다.

### plan 범위

apply 마다 plan 파일을 만들고, 바뀌는 관리 자원 목록이 그 걸음의 기대
목록과 같을 때만 apply 합니다. 하나라도 다르면 apply 하지 않고 kty 에게
보고합니다.

plan 과 apply 는 다른 블록입니다. 먼저 plan 블록으로 목록을 냅니다.

```shell
terraform -chdir=infra/terraform/prod/media plan -out="${work}/media.plan"
terraform -chdir=infra/terraform/prod/media show -json "${work}/media.plan" \
  | jq -r '.resource_changes[] | select(.mode == "managed")
      | select(.change.actions != ["no-op"])
      | "\(.change.actions | join(",")) \(.address)"'
```

목록이 아래 표의 그 걸음 기대 목록과 같은지 대조하고, kty 의 실행 직전
승인을 받은 뒤에 apply 블록을 돌립니다.

```shell
terraform -chdir=infra/terraform/prod/media apply "${work}/media.plan"
```

| 걸음 | 기대 목록(`<B>` · `<A>` 는 키) |
|---|---|
| B 기동 | `create module.media_host.aws_instance.this["<B>"]` 한 줄 |
| ingest 전환 | `delete,create module.media_host.aws_lb_target_group_attachment.ingest` 한 줄 — NLB 대상 등록 교체만 |
| http 전환 | `delete,create module.media_host.aws_lb_target_group_attachment.http["live"]` · `["control"]` · `["playback"]` 세 줄 — ALB 대상 등록 교체 3건만 |
| A 회수 | `delete module.media_host.aws_instance.this["<A>"]` 한 줄 |
| O8a 당김 B 기동 | `create module.media_host.aws_instance.this["<B>"]` 한 줄 + `update module.media_host.aws_iam_role_policy.media` 한 줄(`BroadcastPublish` — 「O8a 당김 교체」 걸음 0 의 Sid 목록에 `RewindDelete` 가 없으면 그 문도). user_data 차분은 「변경 있음」 만, 정책 차분은 Sid · 동작만 옮깁니다(주제 ARN 원문 금지) |
| 제자리 전환 | `update module.media_host.aws_instance.this["<A>"]` 한 줄 — 태그 `ConfigVersion` 만(user_data 는 `ignore_changes` 라 차분 없음). 그 밖의 속성이 바뀌면 apply 하지 않습니다 |

*   설정 반영 교체의 B 기동에서 공유 자원(인스턴스 역할 정책 · SG 규칙 ·
    거부 목록 파라미터)이 함께 바뀌면, 그 변경이 이번 설정 변경의 의도인지
    kty 가 보고 승인합니다. 공유 자원은 A 에도 곧바로 걸립니다.
*   A 의 `aws_instance` 에 `update` 가 보이면 apply 하지 않습니다 — 교체
    모델(`ignore_changes`) 밖의 변경입니다. 유일한 예외는 위 표의 「제자리
    전환」 태그 한 줄입니다.

### 대상 그룹 상태 읽기

네 대상 그룹의 등록 대상과 상태를 읽습니다(읽기 — 승인 불요).

```shell
tg_health() {
  local arns tg
  arns="$(terraform -chdir=infra/terraform/prod/platform output -json target_group_arns)"
  for tg in ingest live control playback; do
    printf '%s ' "${tg}"
    aws elbv2 describe-target-health \
      --target-group-arn "$(jq -r --arg k "${tg}" '.[$k]' <<<"${arns}")" \
      --query 'TargetHealthDescriptions[].[Target.Id,TargetHealth.State]' \
      --output text | paste -sd' ' -
  done
}
```

### 상자 스크립트

아래 셋을 작업 디렉터리에 써 둡니다.

**기동 판정**(`start-check.sh` — 인자: 떠 있어야 할 compose 서비스, 쉼표 ·
이름순). 이번 기동(유닛이 active 가 된 시각) 이후의 줄만 셉니다.

```shell
cat > "${work}/start-check.sh" <<'SH'
fail() { echo "MEDIA START CHECK FAIL $1"; exit 2; }
bad=""
judge() { echo "$1 $2"; [ "$2" = "$3" ] || bad="${bad} $1"; }
at_least() { echo "$1 $2"; [ "$2" -ge "$3" ] 2>/dev/null || bad="${bad} $1"; }
# count <로그> <조각> … — 조각(최대 다섯)을 모두 품은 줄 수.
count() {
  printf '%s\n' "$1" | awk -v a="$2" -v b="${3:-}" -v c="${4:-}" \
    -v d="${5:-}" -v e="${6:-}" '
    index($0, a) && (b == "" || index($0, b)) && (c == "" || index($0, c)) &&
      (d == "" || index($0, d)) && (e == "" || index($0, e)) { n++ }
    END { print n + 0 }'
}
[ "$#" -eq 1 ] || fail usage
set -a; . /etc/pokeclip-media/media.env || fail media-env; set +a
since="$(systemctl show -P ActiveEnterTimestamp pokeclip-media)" || fail since
[ -n "${since}" ] || fail since
since_log() { journalctl -q "$@" --since "${since}" -o cat --no-pager; }
# log_of <서비스> — 그 컨테이너가 실제로 쓰는 로그 드라이버와 tag.
log_of() {
  docker inspect --format \
    '{{.HostConfig.LogConfig.Type}} {{index .HostConfig.LogConfig.Config "tag"}}' \
    "$(docker ps -aq --filter label=com.docker.compose.project=pokeclip-media \
      --filter "label=com.docker.compose.service=$1")" 2>/dev/null
}
unit_log="$(since_log -u pokeclip-media)" || fail journal
sidecar_log="$(since_log -t pokeclip-media-sidecar)" || fail journal
janitor_log="$(since_log -t pokeclip-media-janitor)" || fail journal
boot_log="$(cat /var/log/cloud-init-output.log)" || fail cloud-init
judge UNIT "$(systemctl is-active pokeclip-media)" active
judge BOOTSTRAP_FAIL "$(count "${boot_log}" 'MEDIA BOOTSTRAP FAIL')" 0
judge BUNDLE "$(test -f /var/lib/pokeclip-media/bundle-installed && echo present)" present
judge PREREQ_FAIL "$(count "${unit_log}" 'MEDIA PREREQ FAIL')" 0
judge START_BLOCKED "$(count "${unit_log}" 'MEDIA START BLOCKED')" 0
judge COMPOSE "$(docker compose version --short)" "${MEDIA_EXPECTED_COMPOSE_VERSION}"
judge LOG_SIDECAR "$(log_of sidecar)" 'journald pokeclip-media-sidecar'
judge RUN_FS "$(findmnt -n -o FSTYPE /run)" tmpfs
judge SERVICES "$(docker ps --filter label=com.docker.compose.project=pokeclip-media \
  --filter status=running --format '{{.Label "com.docker.compose.service"}}' \
  | LC_ALL=C sort | paste -sd, -)" "$1"
iam='"auth":"iam"'; ssl='"sslmode":"verify-full"'; tls='"tls":true'
at_least SIDECAR_DB "$(count "${sidecar_log}" '"msg":"db_connected"' \
  '"pool":"indexer"' "${iam}" "${ssl}" "${tls}")" 1
for pool in indexer publisher; do
  at_least "SIDECAR_POOL_${pool}" "$(count "${sidecar_log}" \
    '"msg":"pg_pool_conn_discarded"' "\"pool\":\"${pool}\"" '"value":0' \
    '"reason":"startup"')" 1
done
judge SCHEMA_NOT_READY "$(count "${sidecar_log}" '"msg":"schema_not_ready')" 0
case ",$1," in
  *,janitor,*)
    judge LOG_JANITOR "$(log_of janitor)" 'journald pokeclip-media-janitor'
    at_least JANITOR_DB "$(count "${janitor_log}" '"msg":"db_connected"' \
      '"pool":"janitor"' "${iam}" "${ssl}" "${tls}")" 1
    at_least JANITOR_STARTED "$(count "${janitor_log}" '"msg":"janitor_started"')" 1
    at_least JANITOR_POOL "$(count "${janitor_log}" \
      '"msg":"pg_pool_conn_discarded"' '"pool":"janitor"' '"value":0' \
      '"reason":"startup"')" 1
    ;;
esac
echo "MAINPID $(systemctl show -P MainPID pokeclip-media)"
echo "WATCH FROM $(date +%s)"
if [ -z "${bad}" ]; then echo "MEDIA START OK"; exit 0; fi
echo "MEDIA START NOT OK${bad}"
exit 1
SH
```

| 줄 | 통과 | 뜻 |
|---|---|---|
| `UNIT` | `active` | 유닛이 떠 있음 |
| `BOOTSTRAP_FAIL` · `BUNDLE` | `0` · `present` | 첫 부팅 설치(묶음 포함)가 끝남 — 실패 줄은 cloud-init 출력 |
| `PREREQ_FAIL` · `START_BLOCKED` | `0` · `0` | 선행 점검 통과 · CDN 비밀 조회 성공 |
| `COMPOSE` | `media.env` 의 기대 판 | compose 플러그인 판 |
| `LOG_SIDECAR` · `LOG_JANITOR` | `journald pokeclip-media-sidecar` · `journald pokeclip-media-janitor` | 그 컨테이너가 실제로 journald 에 그 tag 로 로그를 냄(`docker inspect` 의 `HostConfig.LogConfig`) — 아래 `journalctl -t` 가 읽는 자리. 렌더가 json-file 갈래(`detached_log_driver`)로 떨어졌으면 여기서 걸린다. `LOG_JANITOR` 는 janitor 를 켰을 때만 |
| `RUN_FS` | `tmpfs` | 렌더 비밀이 메모리에만 있음 |
| `SERVICES` | 인자와 같음 | 떠 있는 컨테이너 집합 |
| `SIDECAR_DB` | 1 이상 | 사이드카 DB 연결 — IAM · `verify-full` · 실제 TLS(`media/README.md` 「DB 연결 성공 줄」) |
| `SIDECAR_POOL_indexer` · `_publisher` | 1 이상 | 풀마다 관측 시작 줄(`pg_pool_conn_discarded` · value 0 · `reason=startup`) |
| `SCHEMA_NOT_READY` | 0 | 사이드카가 기동 스키마 확인에서 멈추지 않음 — 하한 이상 이미지가 M6 운영 SQL 없는 DB 에 붙으면 `"msg":"schema_not_ready` 로 시작하는 줄을 남기고 끝난다(아래 「M6 운영 SQL 경계 · 롤백 하한」). M6-0 앞 이미지는 이 줄을 내지 않아 늘 0 |
| `JANITOR_DB` · `JANITOR_STARTED` · `JANITOR_POOL` | 1 이상 | janitor 를 켰을 때만 — DB 연결(pool=janitor) · 기동 · 풀 관측 시작 |

*   사이드카 · janitor 의 줄은 JSON 한 줄이라 메시지와 필드를 함께 봅니다
    (media-host README 「기동 확인 신호」). 기동 직후에는 DB 연결 줄이 아직
    없을 수 있습니다 — 2분 뒤 다시 돌립니다.
*   발행자 풀(`publisher`)은 rewind 발행을 조립했을 때만 열립니다. 그 줄만
    0 이면 사이드카 태그 저널의 `rewind_publish_degraded`(버킷 · 권한) 를
    상자 셸에서 봅니다 — 송출은 돌지만 되감기 목록이 발행되지 않습니다.
*   `SIDECAR_DB` 가 1 이상이어도 스키마가 없을 수 있습니다 — 스키마 확인은
    DB 에 붙은 뒤에 하므로 연결 줄이 먼저 남습니다. 그래서 `SCHEMA_NOT_READY`
    를 따로 셉니다. 0 이 아니면 그 상자를 서비스에 넣지 않습니다.
*   `SCHEMA_NOT_READY` 가 세는 줄은 설계 M6 의 ERROR
    `schema_not_ready missing=…`(종료 코드 1)입니다. JSON 의 메시지가
    `"msg":"schema_not_ready` 로 시작하는 줄을 셉니다 — 빠진 객체 목록이
    메시지에 붙든 필드로 따로 나오든 셉니다. M6-0 c3 시험이 이 문자열을
    고정합니다(두 PR 사이 계약). 문자열이 다르면 이 줄은 늘 0 이라 거짓
    통과가 됩니다.
*   `SIDECAR_DB` 가 1 이상이라는 것이 태그 저널이 살아 있다는 증거입니다.
    그래서 아래 관찰의 「종료 줄 0」 을 그대로 믿을 수 있습니다.
*   `MAINPID` · `WATCH FROM` 은 관찰 판정의 인자입니다.

**관찰 판정**(`watch-check.sh` — 인자: 기동 판정의 `MAINPID` · `WATCH FROM`).
기동 판정 5분 뒤에 돌립니다.

```shell
cat > "${work}/watch-check.sh" <<'SH'
fail() { echo "MEDIA WATCH FAIL $1"; exit 2; }
[ "$#" -eq 2 ] || fail usage
pid="$(systemctl show -P MainPID pokeclip-media)" || fail mainpid
elapsed=$(( $(date +%s) - $2 )) || fail usage
log="$(journalctl -q -t pokeclip-media-sidecar -t pokeclip-media-janitor \
  --since "@$2" -o cat --no-pager)" || fail journal
exits="$(printf '%s\n' "${log}" | awk 'index($0, "segment-indexer 종료:") ||
  index($0, "\"msg\":\"janitor_failed\"") { n++ } END { print n + 0 }')" || fail count
echo "MAINPID ${pid} was $1"
echo "ELAPSED ${elapsed}"
echo "EXIT_LINES ${exits}"
if [ "${pid}" = "$1" ] && [ "${elapsed}" -ge 300 ] && [ "${exits}" -eq 0 ]; then
  echo "MEDIA WATCH OK"; exit 0
fi
echo "MEDIA WATCH NOT OK"
exit 1
SH
```

*   **MainPID 무변화는 mediamtx · nginx 감시로만 읽습니다.** 유닛의 주
    프로세스(compose up)는 mediamtx · nginx 중 하나가 끝날 때만 바뀝니다.
    DB 장애로 사이드카가 죽으면 엔진이 사이드카만 다시 띄우고 MainPID 는
    그대로입니다(media-host README 「재기동 단위」).
*   그래서 사이드카 · janitor 는 **관찰 창(5분) 동안 종료 줄 0건**으로 따로
    봅니다. 사이드카는 실패하면 표준 오류에 `segment-indexer 종료: …` 를
    남기고 끝나고, janitor 는 `janitor_failed` 를 남깁니다.
*   한계: 줄을 남기지 못하고 죽는 경우(OOM 강제 종료 등)는 이 판정이 보지
    못합니다. 기동 판정을 관찰 뒤에 한 번 더 돌려 `SERVICES` 를 다시 봅니다.

**방송 경로 수**(`paths.sh`) — `PATHS <n>` 한 줄.

```shell
cat > "${work}/paths.sh" <<'SH'
set -a; . /etc/pokeclip-media/media.env; set +a
/opt/pokeclip-media/bin/paths-count.sh
SH
```

## O7e — 처음 기동

계획 M9 「O7e」 와 r6.1 · r6.2 추기입니다. 상자 키는 `a` 하나이고 활성 키도
`a` 입니다. CDN 비밀 걸음(3)은
[엣지 비밀 런북](/infra/terraform/runbooks/edge-secrets.md) 「O7e」 와 같은
걸음입니다.

### O7e 선행 0 — 단계 직전 점검 · 멈춤 자리 ①

[단계 직전 점검 런북](/infra/terraform/runbooks/stage-precheck.md)대로 합니다.

1.  SCP 재판정을 **둘로 나눠** 합니다 — 공통 묶음 `O7e`(ec2 · iam 인스턴스
    프로필 · elb 대상 등록 · ssm · secretsmanager)와 멈춤 자리 묶음
    `O7e-stop1`(`rds-db:connect`).
1.  2-3 판정과 이벤트 기록을 봅니다.
1.  아래 「선행 확인」 ①–③ 을 봅니다.

| 결과 | 갈래 |
|---|---|
| 공통 묶음 거부 · 변경 · 검사 불가 | O7e 를 시작하지 않습니다(전체 중단) |
| `O7e-stop1` 허용 | 아래 1–5 를 그대로 합니다 |
| `O7e-stop1` 거부 | **멈춤 자리 ①**(계획 3절 「부분 진행」 표 ①): 1(media apply)과 2(T9a)만 합니다 — DB 와 무관합니다. 그 뒤 `systemctl stop pokeclip-media` 로 멈추고(실행 직전 승인), 승인 지점 **A1** 에서 kty 가 「기다림 + 부분 진행」 또는 「password 대비 갈래(별도 PR)」 를 고릅니다. **3 이후(CDN 비밀 생성 · 입력 · 삭제 · 재시작 · 기동 확인)는 하지 않습니다** |

*   **A1 재개:** ⓐ `O7e-stop1` 재판정 허용 또는 ⓑ password 대비 갈래 별도
    PR 의 전환 apply 완료가 서면 3–5 를 합니다(직전 재판정 · 이미 끝낸
    번호의 세부는 계획 M10 뒤 「O7f 실행표」). 통과하면 A1 을 풀고 O7f 로
    돌아갑니다. 그동안의 O7f 는 「O7f 실행표」 의 A1 열을 따릅니다.

### O7e 선행 확인

하나라도 아니면 시작하지 않습니다.

1.  **O7c 완료**: mtxhook MediaMTX · I1-1 반영 사이드카 · 설치 묶음 · nginx ·
    루프백 송출기 다이제스트가 기록돼 있습니다
    ([prod 산출물 올리기 런북](/infra/terraform/runbooks/prod-artifacts.md)).
    `prod/registry` 재적용(저장소 둘 추가)도 끝났습니다.
1.  **런타임 1단 출력**: `rds_endpoint` · `rds_db_name` · `rds_resource_id` ·
    `rds_sg_id` · `media_secret_arns` · `rewind_bucket_arn` ·
    `ingest_auth_resolve_token_secret_arn` 이 있습니다. 없으면 plan 이 그
    이름으로 실패합니다(옛 모드여도 — 루트가 늘 읽습니다). 마지막 출력은 런타임
    1단-나 ②(POK-429)의 apply 뒤에 생깁니다.
1.  **platform 출력 `service_ports`**: `terraform -chdir=infra/terraform/prod/platform
    output service_ports` 에 auth 포트가 있습니다. Media → auth resolve SG 짝이
    이 값을 씁니다(POK-307 · kty 결정 2026-10-25 (나)). 이 출력이 생기기 전
    커밋으로 platform 을 apply 했다면 platform 을 다시 apply 합니다(plan 은
    출력만 늘어남 · kty 승인). 없으면 prod/media plan 이 그 이름으로 실패합니다.
1.  **런타임의 DB 준비**: RDS IAM 인증 켬 · `media_indexer` · `media_janitor`
    (`rds_iam`) · DDL 역할로 스키마 배치 · GRANT 가 끝났다는 확인이 런타임
    스토리 티켓 코멘트에 있습니다([CONTRACT-runtime.md](/infra/terraform/CONTRACT-runtime.md)
    「DB 접속」). janitor 를 켜므로(기본) RDS PostgreSQL 17 이상과
    `media_janitor` 권한도 확인합니다(media-host README 「후속」 janitor 행).
1.  **M6 경계**: DB 에 M6 운영 SQL 이 들어갔는지는 이미지로 미루어 짐작하지
    않고, 런타임 1단-가 런북 「M6 스키마 재실행」 의 통과 기록(재실행
    SHA · `VERIFY OK` · Jira POK-196 코멘트)으로 가립니다. 기본 순서는
    M6-0 · M6-6a 머지 → 재실행 → 사이드카 재푸시 · 기록 갱신 → tfvars
    다이제스트 교체 → O7e 입니다(그 런북 「M6 스키마 재실행 순서」). 하한
    이상 · 아래 판정은 아래 「M6 운영 SQL 경계 · 롤백 하한」 의 명령입니다.

    | M6-0 | 재실행 통과 기록 | tfvars 사이드카 | 갈래 |
    |---|---|---|---|
    | 머지됨 | 있음 | 하한 이상 | 진행 |
    | 머지됨 | 있음 | 하한 아래 | 멈춤 — 재푸시 · tfvars 교체(순서 3 · 4)부터 합니다. 트리거 있는 DB 에 하한 아래 이미지를 띄우지 않습니다 |
    | 머지됨 | 없음 | 무엇이든 | 멈춤 — 재실행(순서 2)부터 합니다 |
    | 머지 전 | — | 하한 아래 | 멈춤 — 기본 순서대로 M6-0 머지 · 재실행 · 재푸시 · tfvars 교체를 먼저 끝냅니다. kty 가 이 순서(kty 확정 (가))에서 벗어나는 것을 명시해 승인한 경우에만 진행하고, 그때 「한 창 절차」 를 예약합니다 |

### O7e 1 — media apply

`terraform.tfvars` 는 `terraform.tfvars.example` 대로 채웁니다(이미지 =
O7c 다이제스트 · `control_upstream` 빈 값 · 응답기 다이제스트 없음). 첫
apply 라 plan 은 생성뿐입니다 — 인스턴스 · Media SG 와 규칙 · RDS 인그레스 ←
Media · auth 인그레스 ← Media(kty 결정 2026-10-25 (나)) · LB 이그레스 →
Media · 인스턴스 역할 · 프로필 · 정책 · 관리형 정책
연결 하나 · 거부 목록 파라미터 · 대상 등록 넷. 변경 · 삭제가 있으면 apply
하지 않습니다.

루트 시험이 보지 못하는 전달 선(prod/media README 「시험」)은 plan 에서
봅니다. 서브넷이 network 의 `media_private_subnet_id` 와 같아야 합니다.

```shell
terraform -chdir=infra/terraform/prod/media show -json "${work}/media.plan" \
  | jq -r '.resource_changes[] | select(.address | test("aws_instance\\.this"))
      | .change.after.subnet_id'
terraform -chdir=infra/terraform/prod/network output -raw media_private_subnet_id
```

대상 등록 넷의 `target_group_arn` 이 platform 의 `target_group_arns` 와,
RDS 인그레스 규칙의 `security_group_id` 가 런타임 `rds_sg_id` 와 같은지도 같은
방법으로 봅니다. Media ↔ RDS 5432 한 쌍과 `rds-db:connect` 도 이 apply 로
생깁니다.

### O7e 2 — T9a

CDN 비밀 값이 아직 없으므로 시작 스크립트가 `MEDIA START BLOCKED: CDN
secret unavailable` 로 실패하고, 유닛(`Restart=always` · `RestartSec=20`)이
20초 쉰 뒤 기동을 다시 시도해야 합니다. 다시 도는 것은 유닛 기동(drop-in 의
설치 표지 확인 · 시작 스크립트의 선행 점검 · CDN 비밀 조회)이고, 설치
(cloud-init 부트스트랩)는 다시 하지 않습니다. 20초는 시도 사이의 대기라
비밀 조회가 걸려 있으면 간격이 더 길어집니다.

```shell
cat > "${work}/t9a.sh" <<'SH'
fail() { echo "T9A CHECK FAIL $1"; exit 2; }
count() { printf '%s\n' "$1" | awk -v a="$2" 'index($0, a) { n++ } END { print n + 0 }'; }
state="$(systemctl show -P ActiveState pokeclip-media)" || fail state
restarts="$(systemctl show -P NRestarts pokeclip-media)" || fail state
log="$(journalctl -q -b -u pokeclip-media -o cat --no-pager)" || fail journal
boot="$(cat /var/log/cloud-init-output.log)" || fail cloud-init
blocked="$(count "${log}" 'MEDIA START BLOCKED: CDN secret unavailable')"
prereq="$(count "${log}" 'MEDIA PREREQ FAIL')"
bootfail="$(count "${boot}" 'MEDIA BOOTSTRAP FAIL')"
echo "UNIT ${state} restarts=${restarts}"
echo "BLOCKED ${blocked}"
echo "PREREQ_FAIL ${prereq}"
echo "BOOTSTRAP_FAIL ${bootfail}"
if [ "${prereq}" -gt 0 ] || [ "${bootfail}" -gt 0 ]; then echo "T9A NOT OK install"; exit 1; fi
if [ "${blocked}" -ge 1 ] && [ "${state}" != active ]; then echo "T9A OK"; exit 0; fi
echo "T9A NOT OK"
exit 1
SH
media_ssm a "${work}/t9a.sh"
```

*   통과 = `T9A OK`(차단 줄 1 이상 · 유닛이 active 아님 — 실패 · 백오프).
    유닛이 다시 시도하는 순간에는 `active` 로 보일 수 있습니다 — 20초 뒤 다시
    돌립니다.
*   `T9A NOT OK install`(`MEDIA PREREQ FAIL` · `MEDIA BOOTSTRAP FAIL`)이면
    T9a 가 아니라 설치 실패입니다. 중단하고 원인을 봅니다(cloud-init 출력의
    `MEDIA BOOTSTRAP FAIL <단계>` 는 상자 셸에서 직접 봅니다). AL2023 arm64
    저장소에 고정한 판이 없으면 `FAIL packages` 입니다 — 아래 「O7e 뒤 측정 ·
    기록」.
*   경보 검증은 보류입니다.

### O7e 3 — CDN 비밀

`O7e-stop1` 허용일 때만 합니다. [엣지 비밀 런북](/infra/terraform/runbooks/edge-secrets.md)
「O7e」 의 3(생성 → `put-secret-from-file.sh` 입력 → 로컬 파일 삭제 · 0건
확인)을 그대로 합니다.

### O7e 4 — 재시작

SSM 으로 `systemctl restart pokeclip-media` 를 보냅니다(실행 직전 승인).

```shell
printf 'systemctl restart pokeclip-media\n' > "${work}/restart.sh"
media_ssm a "${work}/restart.sh"
```

### O7e 5 — 기동 확인

```shell
media_ssm a "${work}/start-check.sh" janitor,mediamtx,nginx,sidecar
# 5분 뒤 — 위 출력의 MAINPID · WATCH FROM 값.
media_ssm a "${work}/watch-check.sh" <MAINPID> <WATCH FROM>
tg_health
```

*   통과 = `MEDIA START OK` · `MEDIA WATCH OK`, 그리고 `tg_health` 의
    `control` 줄이 `<a 의 id> healthy` 입니다(nginx 헬스 경로). janitor 를 끈
    tfvars 면 인자에서 `janitor` 를 뺍니다. O7 은 옛 모드(`ingest_auth_image`
    빈 값)라 응답기가 없습니다.
*   Media 인스턴스 역할로 하는 첫 RDS IAM 접속은 `SIDECAR_DB` 줄로
    확인합니다.
*   **DB 연결이 실패하면**(`SIDECAR_DB 0` · `EXIT_LINES` 가 0 보다 큼 —
    사이드카만 거듭 다시 뜨고 MainPID 는 그대로) `systemctl stop
    pokeclip-media` 로 멈추고(실행 직전 승인) 선행 확인 ② ③ 을 다시 봅니다.
    원인이 `rds-db:connect` 거부면(`O7e-stop1` 재판정으로 확인) 멈춤 자리 ①
    갈래(A1)로 갑니다. 그 밖의 원인이면 고친 뒤 4 부터 다시 합니다.

## 정상 교체

방송이 없는 시간대에 합니다(설계 5-3). 선행: 루프백 헬스(3)는 런타임 트랙
(auth 의 resolve 창구 — Media ↔ auth SG 짝은 이 루트 몫, kty 결정 2026-10-25
(나))과 시험 스트림 키 입력(설계 5-4 의 11)이 끝난 뒤에만 할 수 있습니다.

| # | 동작 | 통과 |
|---|---|---|
| 1 | 시간대를 고르고 스트리머에게 알린다(안내일 뿐 차단 아님) | — |
| 2 | B 기동(같은 AZ · 등록 안 함) | `MEDIA START OK` · `MEDIA WATCH OK` |
| 3 | B 루프백 헬스(대상 그룹 밖) | `LOOPBACK OK` |
| 4 | A · B 방송 경로 수 | A `PATHS 0` |
| 5 | ingest 전환 | NLB = B 1대 healthy |
| 6 | http 전환 | ALB 셋 = B 1대 healthy |
| 7 | 일관성 확인(읽기) | 네 그룹 모두 B 하나 · healthy |
| 8 | A 회수 | 하루 관찰 뒤 |

### 정상 교체 2 — B 기동

1.  `terraform.tfvars` 의 `instances` 에 B 를 더합니다. 설정 반영 교체면 B 의
    `config_version` 을 A 보다 올립니다.

    ```hcl
    instances = {
      a = { config_version = "1" }
      b = { config_version = "2" }
    }
    ```

1.  plan 범위 = B 인스턴스 생성 한 줄(위 「plan 범위」) → apply(승인).
1.  기동 판정 → 5분 뒤 관찰 판정. 인자는 그 구성의 서비스 집합입니다
    (응답기를 채웠으면 `ingest-auth` 를 넣습니다).

    ```shell
    media_ssm b "${work}/start-check.sh" janitor,mediamtx,nginx,sidecar
    media_ssm b "${work}/watch-check.sh" <MAINPID> <WATCH FROM>
    ```

기동 판정은 설계 5-3 갱신본의 네 조건입니다 — 선행 점검 통과
(`PREREQ_FAIL 0`) · CDN 비밀 조회 성공(`START_BLOCKED 0`) · 사이드카 DB 연결
줄 1 이상(`SIDECAR_DB`) · 5분 MainPID 무변화(`MEDIA WATCH OK`).

### 정상 교체 3 — B 루프백 헬스

B 상자 안에서 시험 신호를 보내고 프록시 경유 재생목록이 200 인지 봅니다.
출력은 판정 줄 하나입니다(media-host README 「루프백 헬스」).

```shell
cat > "${work}/loopback.sh" <<'SH'
set -a; . /etc/pokeclip-media/media.env; set +a
/opt/pokeclip-media/bin/loopback-health.sh "$@"
SH
cat > "${work}/loopback-pp.sh" <<'SH'
set -a; . /etc/pokeclip-media/media.env; set +a
/opt/pokeclip-media/bin/loopback-health.sh --passphrase "$1"
SH
media_ssm b "${work}/loopback.sh" <시험 ingestStreamId>      # 옛 모드
media_ssm b "${work}/loopback-pp.sh" <시험 ingestStreamId>   # 응답기 모드
```

*   스크립트는 B 의 송출 인증 모드로 고릅니다(위 2 의 서비스 집합과 같은
    기준). `ingest_auth_image` 가 빈 구성(옛 모드 — O7 · 되돌리기 포함)은
    passphrase 없이 `loopback.sh` 를, 찬 구성(응답기 모드)은
    `--passphrase` 를 붙이는 `loopback-pp.sh` 를 씁니다(media-host README
    「송출 인증 두 모드」 · 「루프백 헬스」). `--passphrase` 를 `media_ssm`
    인자로 넘기지 않습니다(위 「SSM 명령 보내기」). 응답기 모드에서는 기본
    갈래가 거부되는 것이 정상입니다.
*   `LOOPBACK FAIL <단계>` 면 교체를 멈추고 B 를 회수합니다(A 는 그대로).

### 정상 교체 4 — 방송 경로 수

```shell
media_ssm a "${work}/paths.sh"
media_ssm b "${work}/paths.sh"
```

*   A 가 `PATHS 0` 이어야 다음으로 갑니다. B 의 값은 기록만 합니다.
*   `PATHS FAIL <단계>` 는 0 이 아닙니다 — 조회 실패입니다. 진행하지 않습니다.
*   A 가 0 이 아니면 방송이 있는 것입니다. 기다리거나 「force」 로 갑니다.

### 정상 교체 5 — ingest 전환

1.  `ingest_active_key = "<B>"` 로 바꿉니다.
1.  plan 범위 = NLB 대상 등록 교체 한 줄.
1.  **apply 직전에 A 의 경로 수(4)를 다시 봅니다.** `PATHS 0` 일 때만
    apply 합니다(승인).
1.  `tg_health` 의 `ingest` 줄이 `<B 의 id> healthy` 한 대가 될 때까지
    봅니다. A 는 등록 해제 지연(30초) 동안 `draining` 으로 보입니다.

### 정상 교체 6 — http 전환

1.  `http_active_key = "<B>"` 로 바꿉니다.
1.  plan 범위 = ALB 대상 등록 교체 세 줄 → apply(승인).
1.  `tg_health` 의 `live` · `control` · `playback` 이 모두 `<B 의 id>
    healthy` 한 대가 될 때까지 봅니다.

### 정상 교체 7 — 일관성 확인

읽기만 합니다. 네 그룹(NLB 포함)이 모두 B 하나 · healthy 이고, 출력의 두
활성 키가 B 여야 합니다. 이것을 만족하기 전에는 운영 재개로 보지 않습니다.

```shell
tg_health
terraform -chdir=infra/terraform/prod/media output -raw ingest_active_key; echo
terraform -chdir=infra/terraform/prod/media output -raw http_active_key; echo
media_ids | jq -r '.b'
```

### 정상 교체 8 — A 회수

하루 관찰한 뒤 `instances` 에서 A 를 뺍니다. plan 범위 = A 인스턴스 삭제 한
줄 → apply(승인). 그 전에는 A 를 「부분 실패 복구」 의 되돌릴 곳으로 둡니다.
예외: 「한 창 절차」 를 도는 중이면 A 는 하한 아래 이미지라, 그 절의 「A 를
다시 띄울 때」 조건을 지킨 뒤에만 A 로 되돌립니다. 그때 A 회수는 「한 창
절차 7′ — A 정지」 끝의 회수 조건(업로드 적체 0)을 만족할 때만 합니다.
A 로 되돌릴 때 A 가 옛 모드면 위 「언제 쓰나」 의 옛 모드 단서(README
「되돌리기 순서」 1 먼저 · 되돌린 뒤 3 확인)를 따릅니다.

## POK-525 교체

POK-525(조각 키를 업로드 때부터 회차 경로에)를 prod 에 넣는 **한 번의**
교체입니다 — 옛 꼴만 읽는 이미지의 상자 A 를 POK-525 이미지의 상자 B 로
바꾸고, B 가 새 꼴을 쓰기 시작하게 합니다. 「정상 교체」 를 그대로 밟되,
A 를 배출 · 정지한 **뒤에만** B 의 새 꼴 쓰기를 켜고, 첫 새 꼴 행 뒤에
트리거를 깝니다. 설계 r9.4 9-3 · 9-4 와 계획 r2 4-7 · 8절입니다.

이 절(「POK-525 교체」 · 롤백 · 다음 교체)의 Q1 · Q2 · Q2-B · Q3 · Q4 는
runtime-1a 「POK-525 읽기 질의」 입니다. 「O8a 당김 교체」 절의 Q1 – Q4
(runtime-1a 「발행 잔여 질의」)와 이름만 같고 다른 질의입니다.

*   방송이 없는 시간대에 합니다. 실행자 · 승인 규칙은 이 런북 머리와 같고,
    걸음마다 kty 가 승인합니다.
*   DB 를 읽고 쓰는 걸음(Q1 · Q2 · Q2-B · Q3 · Q4 · 운영 SQL 2 · 롤백 짝)의 SQL 과
    보내는 법은 [런타임 1단-가 런북](/infra/terraform/runbooks/runtime-1a.md)
    「POK-525 운영 SQL 2 · 읽기 질의」 입니다. 이 절에서는 이름으로만
    부릅니다.

용어:

*   **새 꼴 · 옛 꼴**: 되감기 조각 키의 두 꼴입니다 — 새 꼴
    `dvr/{stream}/{round}/seg/{seq}.m4s` · 옛 꼴 `dvr/{stream}/seg/{seq}.m4s`.
    A 의 이미지는 새 꼴을 읽지 못합니다(조각 키 문법에서 거절).
*   **새 꼴 쓰기 스위치**: B 상자 파일 `/etc/pokeclip-media/sidecar.env` 한 줄
    (`PLAYBACK_ROUND_KEYS=true|false`)입니다. 상자가 처음 뜰 때 tfvars
    `playback_round_keys`(모듈 기본 false)로 쓰이고, 그 뒤에는 상자 안
    `sidecar-round-keys.sh on|off` 로만 바꿉니다(media-host README
    「판정 줄」). 사이드카는 이 파일을 **컨테이너를 만들 때만** 읽으므로,
    켜져 있는지는 파일이 아니라 실행 중 사이드카의 기동 신호
    `playback_key_layout`(`layout=legacy|round`)로 판정합니다.
*   **교체 걸음 4.N**: 아래 「POK-525 교체 걸음」 의 4.1–4.12 입니다(설계 9-3
    의 번호와 같음).
*   **POK-525 하한**: PR Ⓑ2(회차 키 · 스위치)의 머지 커밋 `fcb6e0dd` 를
    조상으로 둔 커밋으로 빌드한 사이드카 이미지입니다. 그 앞 커밋의 이미지(A
    의 이미지 · prod 선출시 판 d03980cc 포함)가 하한 아래입니다. 판정은 「M6
    운영 SQL 경계 · 롤백 하한」 과 같은 꼴입니다.

    ```shell
    git fetch origin
    git merge-base --is-ancestor fcb6e0dd <이미지의 빌드 커밋 SHA> \
      && echo 'POK-525 하한 이상' || echo 'POK-525 하한 아래'
    ```

*   **/dvr 하한 Writer**: 사이드카 안에서 열린 회차마다 /dvr 하한(엣지가 그
    아래 조각을 막는 seq)을 원장 표 `stream_edge_bounds` 에 올리고 CloudFront
    KVS 에 사본을 쓰는 몫입니다(PR Ⓒ). 회차가 닫히면 KVS 키를 지우고(해제)
    120초 유예 뒤 원장 행을 지웁니다 — 원장 행이 남은 회차는 국면이 `vod`
    로 넘어가지 않습니다. 스위치는 prod/media tfvars
    `edge_bounds_writer_enabled` 이고, 켜면 상자의 사이드카
    `REWIND_EDGE_BOUNDS_KVS_ARN` 에 런타임 1단 출력 KVS ARN 이 들어갑니다.
    user_data 로만 들어가 떠 있는 상자는 바뀌지 않습니다. 신호 · 사람 복구는
    media README 「/dvr 하한 Writer」 입니다.

*   **롤백 하한**: 어디까지 A 로 되돌릴 수 있는가입니다. 기준은 **첫 새 꼴
    행**(Q1 ≥ 1)입니다. 그 전(스위치 켜기 전 · 켰다 껐는데 새 꼴 행 0)이면 A
    로 곧바로 되돌릴 수 있고, 그 뒤면 POK-525 하한 이상 이미지끼리만
    되돌립니다 — 하한 아래 이미지는 이미 쓴 새 꼴 행을 읽지 못합니다. 아래
    「POK-525 롤백」 이 이 기준으로 갈래를 나눕니다.

### POK-525 교체 선행

1.  걸음 0 이 끝나 있습니다 — PR Ⓐ(상자 역할의 되감기 객체 삭제 권한
    `RewindDelete`) 머지본으로 `prod/media` apply(plan 변경 = 정책 문 하나)
    → SSM 으로 상자 역할의 put · delete 한 번(종료 0). 설계 r9.4 9-3 1 ·
    계획 r2 8절 걸음 0 이고, 이 절의 걸음이 아닙니다.
1.  PR Ⓑ1(회차 불변) · Ⓑ2(회차 키 · 스위치) · Ⓒ(하한 원장 · Writer)가 모두
    머지돼 있습니다. **Ⓒ 가 머지되기 전에는 이 절을 시작하지 않습니다.**
1.  B 의 사이드카 이미지 · 설치 묶음을 빌드한 커밋이 셋을 모두 조상으로
    둡니다([prod 산출물 런북](/infra/terraform/runbooks/prod-artifacts.md)
    기록의 빌드 커밋 SHA) — 위 판정이 「POK-525 하한 이상」 입니다.
1.  운영 SQL 1 — 런타임 1단-가 런북 「M6 스키마 재실행」 을 그 빌드 커밋 이상의
    재실행 SHA 로 돌아 `VERIFY OK`. `60_round_frozen.sql`(운영 SQL 2)은 넣지
    않습니다(교체 걸음 4.9 몫).
1.  `prod/runtime` 을 그 SHA 로 다시 apply 합니다 — PR Ⓒ 가 더한 /dvr 하한
    KVS 그릇과 출력 `edge_bounds_kvs_arn` 입니다. **Ⓒ 머지 뒤 이 apply 전에는
    `prod/media` 가 어떤 plan 이든 그 출력 이름으로 멈춥니다(긴급 apply
    포함)** — `prod/media` 가 그 출력을 필수로 읽기 때문입니다(prod/media
    README 「앞 루트 출력」). 그래서 Ⓒ 머지와 이 apply 사이를 짧게 두고, 그
    사이 `prod/media` 를 바꿔야 하면 이 apply 를 먼저 합니다.
1.  B 의 tfvars — `instances` 에 `config_version` 을 올린 새 키 B, 이미지
    다이제스트는 위 빌드, `playback_round_keys` 는 적지 않거나 `false`
    (B 는 **끈 채** 뜹니다), `edge_bounds_writer_enabled = true`(B 의 /dvr
    하한 Writer 켬 — KVS ARN 은 tfvars 칸이 없고 런타임 출력에서만
    옵니다). 이 스위치는 user_data 로만 들어가 A 는 바뀌지 않습니다.
1.  Ⓒ 머지 뒤 첫 `prod/media` apply(보통 4.1)의 plan 에는 공유 인스턴스
    역할 정책의 문 하나(`EdgeBoundsKvs` — 그 KVS 하나의 Describe ·
    UpdateKeys · GetKey)가 더해집니다. 스위치와 무관하게 늘 붙고, A 도 같은
    역할이라 그 순간부터 A 에도 이 권한이 생깁니다(A 의 이미지는 Writer 가
    없어 쓰지 않음). 승인 때 B 인스턴스와 함께 이 한 줄을 봅니다.
1.  사이드카 `LOG_LEVEL` 을 `info` 보다 높게(`warn` · `error`) 두지 않습니다.
    `playback_key_layout` 줄은 끔이면 INFO, 켬이면 트리거가 없을 때(4.7) WARN
    · 있을 때(4.9 뒤) INFO 입니다. 등급을 높이면 그 아래 줄이 사라져, 그 줄을
    기다리는 대조가 `journal` 로 실패합니다 — `warn` 이면 off · 트리거 뒤의 on
    · 복원 묶음의 legacy 대조, `error` 면 4.7 의 on 까지. 복원 대조가 실패하면
    `restore-failed` 이고, 아래 `layout-check.sh` 는 `LAYOUT none` 으로 NOT
    OK 를 냅니다. prod compose 는 `LOG_LEVEL` 을 적지 않아 `info` 입니다.

### POK-525 교체 상자 스크립트

「상자 스크립트」 셋(`start-check.sh` · `watch-check.sh` · `paths.sh`)과
「한 창 절차 7′ — A 정지」 의 `stop.sh` · `stopped-check.sh` 에 아래를 더해
작업 디렉터리에 써 둡니다.

```shell
cat > "${work}/round-keys.sh" <<'SH'
/opt/pokeclip-media/bin/sidecar-round-keys.sh "$@"
SH
printf 'systemctl disable pokeclip-media\n' > "${work}/disable.sh"
printf 'systemctl restart pokeclip-media\n' > "${work}/restart.sh"
printf 'systemctl enable pokeclip-media\n' > "${work}/enable.sh"
printf 'systemctl start pokeclip-media\n' > "${work}/start.sh"
printf '%s\n' \
  "pgrep -af 'sidecar-round-keys[.]sh' || echo 'ROUND KEYS PROC 0'" \
  > "${work}/round-keys-ps.sh"
```

*   `round-keys-ps.sh` 는 상자에서 아직 도는 `sidecar-round-keys.sh` 프로세스를
    줄마다 내고, 없으면 `ROUND KEYS PROC 0` 한 줄을 냅니다. 「POK-525 롤백」 의
    `lock` · 켬 중단 갈래가 씁니다.

**사이드카 layout 판정**(`layout-check.sh` — 인자: 없음, 또는 Q1 의
`round_keys` 값). 실행 중 사이드카가 하나인지 보고, 그 컨테이너 ID 로 거른
태그 저널의 마지막 `playback_key_layout` 줄을 읽습니다.

```shell
cat > "${work}/layout-check.sh" <<'SH'
fail() { echo "LAYOUT CHECK FAIL $1"; exit 2; }
[ "$#" -le 1 ] || fail usage
if [ "$#" -eq 1 ]; then
  case "$1" in '' | *[!0-9]*) fail usage ;; esac
fi
ids="$(docker ps -q --no-trunc --filter status=running \
  --filter label=com.docker.compose.project=pokeclip-media \
  --filter label=com.docker.compose.service=sidecar)" || fail docker
running="$(printf '%s\n' "${ids}" | awk 'NF { n++ } END { print n + 0 }')"
layout=""
frozen=""
if [ "${running}" = 1 ]; then
  # 파일이 아니라 실행 중 컨테이너의 기동 줄로 본다(env_file 은 만들 때만 읽힌다).
  log="$(journalctl -t pokeclip-media-sidecar "CONTAINER_ID_FULL=${ids}" \
    -q --no-pager -o cat)" || fail journal
  line="$(printf '%s\n' "${log}" | grep -F '"msg":"playback_key_layout"' \
    | tail -n 1)"
  layout="$(printf '%s\n' "${line}" \
    | sed -n 's/.*"layout":"\([a-z]*\)".*/\1/p')"
  frozen="$(printf '%s\n' "${line}" \
    | sed -n 's/.*"round_frozen":"\([a-z]*\)".*/\1/p')"
fi
echo "SIDECAR_RUNNING ${running}"
echo "LAYOUT ${layout:-none}"
echo "ROUND_FROZEN ${frozen:-none}"
ok=0
# 트리거는 absent 만 통과다 — unknown(조회 실패)은 없음을 확인한 것이 아니다.
if [ "${running}" = 1 ] && [ "${layout}" = legacy ] \
  && [ "${frozen}" = absent ]; then
  ok=1
fi
if [ "$#" -eq 0 ]; then
  if [ "${ok}" = 1 ]; then echo "SIDECAR LEGACY OK"; exit 0; fi
  echo "SIDECAR LEGACY NOT OK"
  exit 1
fi
echo "Q1 $1"
if [ "${ok}" = 1 ] && [ "$1" = 0 ]; then echo "BEFORE ON OK"; exit 0; fi
echo "BEFORE ON NOT OK"
exit 1
SH
```

| 줄 | 통과 | 뜻 |
|---|---|---|
| `SIDECAR_RUNNING` | `1` | 실행 중 사이드카 컨테이너 수 |
| `LAYOUT` | `legacy` | 그 컨테이너의 마지막 기동 줄의 layout. 줄이 없으면 `none` |
| `ROUND_FROZEN` | `absent` | 그 기동 때 회차 불변 트리거 조회 결과. `present` 면 운영 SQL 2 가 이미 깔렸다 — 켜기 전 상태가 아니다. `unknown`(조회 실패) · `none`(줄 없음)이면 트리거가 없는지 모른다 — NOT OK |
| `Q1` | `0` | 인자로 준 Q1 값(인자가 있을 때만) |
| `SIDECAR LEGACY OK` · `NOT OK` | OK | 인자 없음 — 위 셋 |
| `BEFORE ON OK` · `NOT OK` | OK | 인자 있음 — 위 넷(「켜기 전」 선언 판정) |
| `LAYOUT CHECK FAIL <단계>` | — | `usage` · `docker`(컨테이너 조회 실패) · `journal`(저널 조회 실패) — 판정이 아니다. 다시 봅니다 |

`ROUND_FROZEN unknown` 은 그 컨테이너가 뜰 때 트리거 조회가 실패한
것입니다. 기동 줄은 컨테이너를 만들 때 한 번만 나와 다시 재도 같습니다 —
멈추고 kty 에게 보고합니다.

**정지 유지 판정**(`disabled-check.sh` — 인자 없음). A 유닛이 부팅 때 다시
뜨지 않게 됐는지 봅니다.

```shell
cat > "${work}/disabled-check.sh" <<'SH'
# is-enabled 는 disabled 에서도 종료 1 이라 종료 코드가 아니라 낱말로 가른다.
# 조회가 실패하면(빈 출력) unknown 이고 NOT OK 다.
state="$(systemctl is-enabled pokeclip-media 2>/dev/null)" || :
echo "ENABLED ${state:-unknown}"
if [ "${state}" = disabled ]; then
  echo "MEDIA DISABLED OK"; exit 0
fi
echo "MEDIA DISABLED NOT OK"
exit 1
SH
```

*   통과 = `MEDIA DISABLED OK`(`ENABLED disabled`). `enabled` 나 조회 실패
    (`ENABLED unknown`)는 NOT OK 입니다.
*   `layout-check.sh` · `disabled-check.sh` 는 `infra/media-host/test/
    sidecar_round_keys_ops_test.sh` 가 이 런북의 here-document 를 그대로
    뽑아 가짜 docker · journalctl · systemctl 로 돌립니다(사례 R16 · R17).

**하한 Writer 판정**(`edge-check.sh` — 인자 없음). 실행 중 사이드카 하나의
**지금 프로세스**가 낸 /dvr 하한 Writer 기동 줄을 읽습니다. 기동 줄은
프로세스마다 넷 가운데 하나입니다 — `edge_bounds_started`(Writer 켜짐) ·
`_disabled`(ARN 빔) · `_config_invalid`(ARN 꼴 밖 — 뒤이어 `_disabled`) ·
`_degraded`(KVS 클라이언트를 못 엶).

```shell
cat > "${work}/edge-check.sh" <<'SH'
fail() { echo "EDGE CHECK FAIL $1"; exit 2; }
ids="$(docker ps -q --no-trunc --filter status=running \
  --filter label=com.docker.compose.project=pokeclip-media \
  --filter label=com.docker.compose.service=sidecar)" || fail docker
running="$(printf '%s\n' "${ids}" | awk 'NF { n++ } END { print n + 0 }')"
[ "${running}" = 1 ] || fail "running ${running}"
# 같은 컨테이너가 다시 시작하면(on-failure) 앞 프로세스의 줄이 그 컨테이너
# 저널에 남는다 — 지금 프로세스의 시작 시각(State.StartedAt) 뒤 줄만 본다.
started_at="$(docker inspect --format '{{.State.StartedAt}}' "${ids}")" \
  || fail inspect
since="$(date -d "${started_at}" +%s)" || fail started-at
log="$(journalctl -t pokeclip-media-sidecar "CONTAINER_ID_FULL=${ids}" \
  --since "@${since}" -q --no-pager -o cat)" || fail journal
# 마지막 줄이 아니라 있는 줄로 고른다 — 꼴 밖 ARN 은 config_invalid 뒤
# disabled 를 낸다. 뒤에 오는 이름이 앞 이름을 덮는다.
msg=none
for m in disabled degraded config_invalid started; do
  if printf '%s\n' "${log}" | grep -qF "\"msg\":\"edge_bounds_${m}\""; then
    msg="edge_bounds_${m}"
  fi
done
echo "SINCE ${since}"
echo "EDGE ${msg}"
if [ "${msg}" = edge_bounds_started ]; then echo "EDGE STARTED OK"; exit 0; fi
echo "EDGE STARTED NOT OK"
exit 1
SH
```

*   통과 = `EDGE STARTED OK`(`EDGE edge_bounds_started`). 그 밖 낱말이면
    NOT OK 이고, 뜻과 처리는 media README 「/dvr 하한 신호」 입니다.
    `EDGE none` 은 지금 프로세스가 아직 그 줄까지 오지 못했거나(갓 뜸 ·
    기동 단계에서 죽음) 줄을 내기 전에 다시 시작한 것입니다 — 잠시 뒤
    다시 봅니다.
*   시작 시각은 `lifecycle-check.sh`(「O8a 당김 — 운영 감시」)와 같은 꼴로
    초 단위로 자릅니다. 앞 프로세스가 지금 프로세스와 같은 초에 기동 줄을
    냈으면 그 줄도 들어옵니다.
*   `EDGE CHECK FAIL <단계>` 는 판정이 아닙니다 — 다시 봅니다.
*   이 스크립트는 아직 시험이 뽑아 돌리지 않습니다(위 둘과 다름).

### POK-525 교체 걸음

| 걸음 | 동작 | 통과 |
|---|---|---|
| 4.1 | B 기동(**끈 채** · 하한 Writer 켬) — 「정상 교체」 2. apply 를 시작한 시각을 `<since>` 로 적음 | 기동 판정 넷(`PREREQ_FAIL 0` · `START_BLOCKED 0` · `SIDECAR_DB` · `MEDIA WATCH OK`) · `SCHEMA_NOT_READY 0` · B `layout-check.sh`(인자 없음) `SIDECAR LEGACY OK` · B `edge-check.sh` `EDGE STARTED OK` |
| 4.2 | B 루프백(옛 꼴) — 「정상 교체」 3 | `LOOPBACK OK` · Q1 = 0 |
| 4.3 | 경로 수 → ingest 전환 → http 전환 → 일관성 — 「정상 교체」 4–7. ingest 전환 뒤 `<cut>` 을 적음 | 각 걸음의 통과 |
| 4.4 | A 배출 — A `paths.sh` → Q2 | A `PATHS 0` · Q2 세 행 `waiting` 0 |
| 4.5 | A 정지 · 끔 — `stop.sh` → `disable.sh` | `MEDIA STOPPED OK` · `MEDIA DISABLED OK` |
| 4.6 | 켜기 전 확인 — Q1 다시 · A 판정 다시 · B 「켜기 전」 선언 판정 | Q1 0 · `MEDIA STOPPED OK` · `MEDIA DISABLED OK` · `BEFORE ON OK` |
| 4.7 | B `paths.sh` → `round-keys.sh on` | B `PATHS 0` · `SIDECAR ROUND KEYS OK on` |
| 4.8 | 켬 뒤 루프백(첫 새 꼴 쓰기 · 4.2 루프백 끝에서 6분 넘게 지난 뒤) — 「정상 교체」 3 과 같은 명령 → Q3 | `LOOPBACK OK` · Q3 `matched` 1 이상 · `mismatched` 0 |
| 4.9 | B `paths.sh` → 운영 SQL 2(`60_round_frozen.sql` → `40_verify.sql`) | B `PATHS 0` · `ROUND FROZEN OK` · `VERIFY OK` |
| 4.10 | 시험 방송(시험 키) → 끝낸 뒤 국면 `vod` → Q4 | Q3 `matched` 늘고 `mismatched` 0 · Q4 `pass` `t`(아래 4.10) |
| 4.11 | A 회수 — 시험 방송이 통과한 날 | Q2 0 · A `MEDIA STOPPED OK` · `MEDIA DISABLED OK` → plan 범위 A 삭제 한 줄 |
| 4.12 | 결정 9 확정 — prod tfvars `playback_round_keys = true` | plan 범위 0 줄 |

```shell
media_ssm b "${work}/layout-check.sh"                # 4.1
media_ssm b "${work}/edge-check.sh"
media_ssm a "${work}/paths.sh"                       # 4.4 · Q2 앞
media_ssm a "${work}/stop.sh"                        # 4.5
media_ssm a "${work}/disable.sh"
media_ssm a "${work}/stopped-check.sh"
media_ssm a "${work}/disabled-check.sh"
media_ssm a "${work}/stopped-check.sh"               # 4.6 — A 판정 다시
media_ssm a "${work}/disabled-check.sh"
media_ssm b "${work}/layout-check.sh" <Q1 값>        # 4.6
media_ssm b "${work}/paths.sh"                       # 4.7 · 4.9 직전
media_ssm b "${work}/round-keys.sh" on               # 4.7
```

*   **4.1 · 4.2 — 끈 채 시작.** B 가 끈 채 쓰는 행은 모두 옛 꼴이라 아직 도는
    A 의 업로더 · 발행 · 동결이 모두 읽습니다 — 겹침이 길어져도 안전합니다.
    4.1 의 `SIDECAR LEGACY OK` 를 보기 전에는 루프백을 하지 않습니다 — 켜진
    B 의 루프백은 A 가 못 읽는 새 꼴 행을 만듭니다. 4.2 의 Q1 이 0 이
    아니면 멈추고 kty 에게 보고합니다.
*   **4.1 — 하한 Writer.** `EDGE STARTED OK` 가 아니면 멈추고 kty 에게
    보고합니다. `EDGE edge_bounds_disabled` 면 tfvars 스위치를,
    `_config_invalid` 면 런타임 출력 ARN 을, `_degraded` 면 그 줄의 `err` 를
    봅니다. 끈 채인 동안은 옛 꼴만 써 Writer 가 하한을 올릴 회차가 없고(원장
    행 0) KVS 에 키도 없습니다. /dvr 하한 함수(`dvr_floor_enabled`)가 붙어
    있어도 키가 없는 회차는 통과라 시청자 영향은 없습니다.
*   **4.3 — A 는 아직 돕니다.** 전환 동안 A 가 살아 있어 제어면 502 창이
    없습니다. `<cut>` = `tg_health` 의 `ingest` 가 B 한 대 healthy 가 된
    시각 + 30초(등록 해제 지연)입니다.
*   **4.4 — A 배출.** A 를 멈추면 A 디스크에만 있는 조각의 업로드가
    멈추고, B 에는 그 파일이 없어 정산 창(15분) 뒤 그 조각은 VOD 의 GAP 으로
    굳습니다. 그래서 Q2 가 0 일 때만 4.5 로 갑니다. 0 이 아니면 기다렸다
    다시 잽니다. **창 안에 0 이 안 되면 여기서 멈춥니다** — 끈 채 B 로
    운영해도 안전하고, 다음 창에 4.4 부터 다시 합니다. `bytes_null` 이 0 이
    아니면 kty 에게 보고합니다.
*   **4.5 — 유닛으로 멈추고 끔.** 사이드카 컨테이너만 멈추면 mediamtx ·
    nginx 가 끝날 때 유닛(`Restart=always`)이 compose 전체를 다시 띄워
    되살아나고, 유닛이 enable 이면 재부팅에도 뜹니다. `stop` 은 정지를
    유지하고 `disable` 은 부팅 자동 기동을 끊습니다. `mask` 는 쓰지 않습니다
    — 유닛 파일이 `/etc/systemd/system` 에 직접 깔려 있어 실패합니다. 정지
    뒤 A 를 손으로 `start` 하지 않습니다(「POK-525 롤백」 의 「켜기 전」
    복귀만 예외).
*   **4.6 — 여기까지는 A 로 곧바로 돌아갈 수 있습니다**(「POK-525 롤백」 의
    「켜기 전」). 4.6 의 Q1 은 `<since>` 그대로입니다.
*   **4.7 — 스위치 켬.** 직전에 B `PATHS 0` 을 봅니다 — 켠 직후 실방송이
    첫 새 꼴 행을 만들면 A 복귀가 일찍 닫힙니다. 기대하는 신호:
    *   새 사이드카의 기동 줄은 `layout=round round_frozen=absent` WARN 한
        줄입니다(트리거는 4.9 에서 깔림 — 정상).
    *   사이드카만 다시 만들어 mediamtx · nginx 와 송출은 그대로이고, 재생성
        수 초 동안 제어면이 502 이며 그 사이 늦은 4D(종료 신호)는 503 ·
        502 로 재시도를 소진할 수 있습니다. `round-keys.sh on` 을 보낸
        시각과 OK 를 본 시각을 적습니다.
    *   OK 를 본 시각이 Q3 의 `<on>` 입니다.
    *   `SIDECAR ROUND KEYS FAIL …` 이면 「POK-525 롤백」 의 켬 실패 갈래
        (판정 줄 끝에 따라 셋)로, 판정 줄 없이 끝났으면(SSM 취소 · 시간
        초과) 켬 중단 갈래로 갑니다.
*   **4.8 — 이 순간부터 A 복귀는 닫힙니다**(롤백 하한). Q3 의 `mismatched`
    가 0 이 아니면 멈추고 kty 에게 보고합니다.
    *   4.8 루프백은 **4.2 루프백이 끝나고 6분이 넘게 지난 뒤** 보냅니다. 합류는
        앞 세션 마지막 조각 끝에서 재접속 창 300초 안에만 일어나고, 6분은 거기에
        여유를 둔 값입니다. 그 안이면 4.2 와 같은 시험 스트림이라 4.2 의 회차로
        이어질 수 있고, 그러면 한 회차에 옛 꼴(4.2)과 새 꼴(4.8 · 4.10) 조각이
        섞여 4.10 의 Q4 가 `not_round_keys` 1 이상으로 `f` 를 냅니다. 6분이
        지나도 4.2 세션이 아직 `live` 로 남아 있으면 같은 세션으로 이어지는데,
        그때도 Q4 는 `f` 를 냅니다(`<on>` 뒤에 시작한 세션이 없으면 `round_id`
        가 비고, 있으면 그 회차에 4.2 의 옛 꼴이 섞여 `not_round_keys` 1 이상) —
        4.11 로 가지 않고 kty 에게 보고합니다. [엣지 시험 런북](edge-tests.md)
        「/dvr 하한 걸음」 의 「새 시험 방송으로 재개」 도 같은 까닭으로 6분을
        기다리고, 새 회차가 직전 회차와 다른지 봅니다.
*   **4.9 — 운영 SQL 2.** 직전에 B `PATHS 0` 을 다시 봅니다(트리거를 만드는
    동안 세션 쓰기가 막힘). 보내는 법 · 실패 갈래는 런타임 1단-가 런북
    「POK-525 운영 SQL 2 적용」 입니다. B 는 다시 띄우지 않습니다 — 다음
    기동부터 `round_frozen=present` 입니다. A 는 4.5 에서 멈춰 트리거 있는
    DB 에서 돈 적이 없습니다(트리거에 거절돼 사라지는 A 의 늦은 4D 가
    없음).
*   **4.10 — 시험 방송.** 시험 키로 송출하고 끝냅니다. 시험 키의 스트림은 Q4 의
    `<test>`(4.2 · 4.8 루프백과 같은 시험 스트림)여야 합니다 — 다른 스트림이면
    Q4 가 이 방송이 아니라 4.8 루프백의 회차를 판정합니다. 통과는 셋입니다.
    1.  키 새 꼴 — Q3(`<on>` 그대로)의 `matched` 가 늘고 `mismatched` 0.
    1.  방송을 끝낸 뒤 그 회차의 국면이 `vod` 로 넘어감. 하한 Writer 는
        5분마다 원장에 올리므로, 시험 방송은 **10분 넘게** 보내 그 회차에
        원장 행이 생기게 합니다. 원장 행은 첫 발행 뒤 Margin(60초)이 지난
        다음 올림 틱(5분 주기)에 생겨 늦어도 약 6분이고, 10분은 여기에
        여유를 둔 값입니다. 그러면 B 사이드카 저널에 그 회차의
        `dvr_floor_released`(KVS 하한 키를 지움) 한 줄이 나오고, 120초 유예
        뒤 원장 행이 지워져야 넘어갑니다. 넘어가는 시각은 해제 · 재조회
        주기의 합(약 2–3.5분) 뒤이고 상한이 아닙니다. 해제도 30초는
        주기일 뿐이라, Writer 의 올림 패스(5분마다 — 해제와 한 작업에서
        차례로 돎)가 길면 그만큼 밀립니다. 재조회는 30초마다
        `ending` 스트림을 이름순으로 한 묶음에 담고, 보류 틱마다 커밋 표지가
        쓰고 남은 상태 읽기 칸(`REWIND_PLAYBACK_READ_MAX`)만큼 묶음 앞에서
        읽습니다. 칸이 모자라면 남은 스트림은 다음 보류 틱에 이어 읽고, 묶음을
        다 읽기 전에는 새 묶음을 담지 않습니다 — `ending` 스트림이 많으면 그만큼
        늦어집니다. 10분이 넘도록 `ending` 이면
        runtime-1a 「POK-525 원장 질의」 로 그 회차 행의 `state` 를 봅니다.
        `sample` 은 상태마다 앞 10개뿐이라 그 회차가 빠질 수 있으므로, 그
        절의 「한 스트림만 보기」 대로 `<test>` 로 좁혀 돌립니다.
        `released_stuck` 이면 같은 절의 「`released_stuck` 판정」 대로 5분
        뒤 다시 봅니다.
    1.  **업로드 · 발행 정상 · VOD 문서 URI 새 꼴** — 국면이 `vod` 가 된 뒤
        Q4(`<on>` · `<test>`)의 `pass` 가 `t`: 그 회차 조각(컷오프 이상)의
        ③(되감기 조각) 미업로드 0 · 그 회차 새 꼴이 아닌 키 0 · GAP 기록 0 · 최신 세션
        `vod_state` 가 `frozen`(`frozen_with_gaps` 아님)이고 VOD 문서 키
        있음 · 회차 닫힘.

    국면 `vod` 하나로는 넘어가지 않습니다 — `frozen_with_gaps` 도 국면이
    `vod` 이고, 정산 기한까지 못 올라간 조각은 GAP 줄로 마감됩니다. Q4 가
    `f` 면 4.11 로 가지 않고 어긋난 열(`not_uploaded` · `gaps` 등)을 kty
    에게 보고합니다. 4.8 뒤라 A 로 돌아가는 갈래는 없습니다 — 원인을 고쳐
    시험 방송을 다시 합니다(「POK-525 롤백」 의 「켠 뒤 · 새 꼴 행 1
    이상」). 어긋난 열이 `not_round_keys` 하나뿐이면 먼저 4.8 이 4.2 의
    회차로 이어졌는지(위 4.8 — 6분)를 봅니다. 그렇다면 배포 이상이 아니므로,
    그 회차가 닫힌 뒤(마지막 조각 끝에서 6분 넘게) 시험 방송을 새로 해 새
    회차의 Q4 를 봅니다.
*   **4.11 — A 회수를 앞당깁니다.** 4.8 뒤 A 는 되돌릴 곳이 아니라 「정상
    교체」 8 의 하루 관찰을 쓰지 않습니다. 회수 직전 Q2 · A 판정 둘을 다시
    봅니다.
*   **4.12 — 결정 9 (가) 확정.** 운영자의 실제 `prod/media/terraform.tfvars`
    (저장소 밖 — 저장소에는 `terraform.tfvars.example` 만)에
    `playback_round_keys = true` 를 적고 plan 을 봅니다. 떠 있는 상자의
    user_data 는 `ignore_changes` 라 B 는 바뀌지 않고, 다음 상자부터 켬으로
    뜹니다. plan 범위의 목록이 **비어 있어야** 합니다 — 한 줄이라도 있으면
    apply 하지 않고 kty 에게 보고합니다. 비어 있으면 적용할 것이 없습니다
    (tfvars 기록만 남음). 모듈 기본값은 false 그대로입니다.

### POK-525 롤백

**「켜기 전」 선언 판정** — 둘 다 참일 때만 「켜기 전」 으로 A 복귀를
엽니다. ⓐ B `layout-check.sh <Q1 값>` 이 `BEFORE ON OK` — 지금 실행 중
사이드카의 마지막 기동 줄이 `layout=legacy` · `round_frozen=absent` ⓑ 그
인자 Q1 이 방금 잰 값(`<since>` 그대로)으로 0. 파일 값은 보지 않습니다 —
파일을 끔으로 되돌려도 실행 중 컨테이너는 켬일 수 있습니다. Q1 ≥ 1 이면
「켠 뒤 · 새 꼴 행 1 이상」 입니다. `layout=legacy` 하나만으로는 A 복귀
근거가 아닙니다 — Q1 0 과 트리거 없음 확인(4.9 를 돌리지 않음 ·
`ROUND_FROZEN absent`)이 함께 필요합니다.

`rolled-back` 판정 줄은 이 판정을 대신하지 않습니다. 스크립트는 복원
재생성이 만든 컨테이너가 `layout=legacy` 줄을 내고 그 대조 직후 실행 중
사이드카 하나인 것까지 보지만, 판정 줄을 낸 뒤 그 컨테이너가 계속 도는지와
Q1 · 트리거는 보지 않습니다. 그래서 `rolled-back` 뒤에도 「실행 중 사이드카
`layout=legacy` · 트리거 `absent` ∧ Q1 재측정 0」 을 이 판정으로 따로
봅니다.

상태마다 판정과 방법입니다.

*   **켜기 전**(4.1–4.6)
    *   판정: 스위치 끔 · 트리거 없음 · 「켜기 전」 선언 판정.
    *   방법: 아래 「켜기 전 복귀」.
*   **켬 실패 · 바뀐 것 없음**(4.7)
    *   판정: `SIDECAR ROUND KEYS FAIL lock` · `not-running` · `write` ·
        `config`(종료 1) · `usage`(종료 2) — 뒤에 `rolled-back` ·
        `restore-failed` 가 붙지 않습니다. 스크립트가 스위치 파일을 바꾸기
        전에 멈춰 파일 · 실행 중 사이드카가 그대로입니다(media-host README
        「판정 줄」).
    *   방법: 「켜기 전」 그대로입니다.
        *   `lock` — 다른 `round-keys.sh` 실행이 잠금을 쥐었거나 잠금 파일을
            열지 못한 것입니다. 이 스크립트는 동시에 둘을 돌리지 않습니다 —
            B 에서 아직 도는 SSM 명령이 없는지(아래 명령이 `0`) 확인한 뒤
            같은 `on` 을 다시 보냅니다(승인). 다시 보내도 `lock` 이면 kty
            에게 보고합니다.

            ```shell
            aws ssm list-commands --instance-id "$(media_ids | jq -er .b)" \
              --filters key=Status,value=InProgress --query 'length(Commands)'
            media_ssm b "${work}/round-keys-ps.sh"
            ```

            SSM 명령이 0 이어도 상자에서 `sidecar-round-keys.sh` 프로세스가
            아직 도는지 `round-keys-ps.sh` 로 봅니다 — SSM 취소가 자식
            스크립트까지 끝내지 않아 취소된 명령의 스크립트가 계속 돌 수
            있습니다(로컬 연습 2026-11-03 에서 관찰 · prod 미확인). 돌면 끝날
            때까지 기다린 뒤(`ROUND KEYS PROC 0`) 다시 보냅니다.

        *   그 밖 — 판정 줄을 kty 에게 보고하고, 다시 켤지는 kty 가
            정합니다. 다시 켜면 4.6(Q1 · A 판정 · `BEFORE ON OK`)부터
            합니다.
*   **켬 실패 · `rolled-back`**(4.7)
    *   판정: `SIDECAR ROUND KEYS FAIL <단계> rolled-back` — 스크립트가 복원
        묶음(파일 끔 → 재생성 → 그 컨테이너 legacy 대조)을 마쳤습니다.
    *   방법: Q1 을 다시 재고 「켜기 전」 선언 판정 → `BEFORE ON OK` 면
        「켜기 전」. Q1 ≥ 1 이면 「켠 뒤 · 새 꼴 행 1 이상」, Q1 0 인데 NOT
        OK 면 아래 「restore-failed 처리」.
*   **켬 실패 · `restore-failed`**(4.7)
    *   판정: `SIDECAR ROUND KEYS FAIL <단계> restore-failed` — 파일 · 실행
        상태 미확정.
    *   방법: 아래 「restore-failed 처리」.
*   **켬 중단**(4.7)
    *   판정: 판정 줄 없이 끝남 — 종료 130 · 143(INT · TERM) · SSM
        `Cancelled` · `TimedOut`. 스크립트는 신호를 받으면 복원 묶음 없이
        끝나고(강제 종료면 트랩도 돌지 않음), 파일은 이미 켬일 수 있고 실행 중
        사이드카는 미확정입니다.
    *   방법: `on` 을 다시 보내지 않습니다. 먼저 `round-keys-ps.sh` 로 상자에서
        `sidecar-round-keys.sh` 프로세스가 아직 도는지 봅니다 — SSM 취소가
        자식 스크립트까지 끝내지 않을 수 있습니다(위 `lock` 갈래). 돌면 끝날
        때까지(`ROUND KEYS PROC 0`) 기다립니다. 그 실행의 판정 줄은 표준
        출력에만 나와(저널에 남기지 않음) 취소된 명령의 SSM 출력에 실리지
        않을 수 있습니다 — 판정 줄이 없으면 이 갈래 그대로 갑니다. 그다음
        아래 「restore-failed 처리」 를 그대로 — `off` → 결과 갈래대로 손 처리
        → Q1 재측정 · 「켜기 전」 선언 판정.
*   **켠 뒤 · 새 꼴 행 0**(4.7 OK · 4.8 전)
    *   판정: Q1 0 · 트리거 없음.
    *   방법: `round-keys.sh off` → `OK off` 면 「켜기 전」 선언 판정. 그
        밖이면 「restore-failed 처리」 의 `off` 결과 갈래.
*   **켠 뒤 · 새 꼴 행 1 이상**(4.8 뒤)
    *   판정: Q1 ≥ 1.
    *   방법: A 로 돌아가지 않습니다. 고친 이미지로 「설정 반영
        교체」(POK-525 하한 이상끼리). 새 꼴 쓰기만 멈추려면 `round-keys.sh
        off`(이미 쓴 새 꼴 행은 같은 이미지가 계속 읽음). A 의 이미지 ·
        d03980cc · 그 밖의 하한 아래 이미지 금지.
*   **트리거 결함**(4.9 뒤)
    *   판정: 정당한 세션 UPDATE 가 23514 `stream_sessions.round_id is
        frozen`.
    *   방법: 런타임 1단-가 런북 「POK-525 트리거 롤백 짝」(손 SQL · 승인) →
        고친 60 파일 PR → 다시 4.9. A 복귀 수단이 아닙니다.

/dvr 하한 갈래는 위 상태와 따로, 어느 걸음에서든 생길 수 있습니다. 상태
판정은 media README 「/dvr 하한 신호」, 절차는 「/dvr 하한 사람 복구」
입니다.

*   **엣지 거절**(/dvr 하한 함수를 붙인 뒤 — 시청자 404 ·
    `dvr_floor_blocks_list`)
    *   방법: `dvr_floor_enabled = false` 로 prod/edge apply 해 `/dvr/*` 에서
        함수를 뗍니다(1순위 · 실행 직전 승인). plan 범위는
        `update module.media.aws_cloudfront_distribution.this` 한 줄이고,
        함수 · 로그 그룹 · 경보는 남깁니다. 원인을 고친 뒤 다시 켭니다 —
        [엣지 시험 런북](edge-tests.md) 「/dvr 하한 시험(POK-525)」 의
        2단 · 판정 C′ 입니다(C′ 결과 셋 — 「통과」 면 켠 채 두고, 「끄기」 면
        다시 끄며, 「보류」 면 kty 회부). 함수 코드가 원인이면 같은 런북의
        「붙은 채 함수 코드 고치기」 차례(고친 PR 머지 전에 끔)를 따릅니다.

        ```shell
        terraform -chdir=infra/terraform/prod/edge plan -input=false \
          -var dvr_floor_enabled=false -out="${work}/edge-dvr-off.plan"
        terraform -chdir=infra/terraform/prod/edge show -json \
          "${work}/edge-dvr-off.plan" \
          | jq -r '.resource_changes[] | select(.mode == "managed")
              | select(.change.actions != ["no-op"])
              | "\(.change.actions | join(",")) \(.address)"'
        ```

        목록이 위 한 줄과 같을 때만, kty 실행 직전 승인 뒤 apply 합니다.

        ```shell
        terraform -chdir=infra/terraform/prod/edge apply \
          "${work}/edge-dvr-off.plan"
        ```

    *   apply 뒤 떼어졌는지는 [엣지 시험 런북](edge-tests.md) 「/dvr 하한
        끄기」 의 확인 둘(배포 설정의 `/dvr/*` 연결 · 표지 요청)로 봅니다.
        하한 값 · 회차와 무관해 회차가 닫힌 뒤에도 성립합니다.

*   **Writer 만 문제**(`edge_bounds_write_failing` 이 풀리지 않음 ·
    `dvr_floor_release_overdue` · `edge_bounds_degraded`)
    *   `edge_bounds_degraded` 는 먼저 그 줄의 `err` 를 고치고 사이드카를
        재기동합니다(media README 「/dvr 하한 사람 복구」). 못 고칠 때만
        아래 방법으로 갑니다.
    *   방법: 엣지를 먼저 끔(함수가 붙어 있을 때만 — 위 「엣지 거절」 의
        `dvr_floor_enabled = false` apply) → `edge_bounds_writer_enabled =
        false` 로 「설정 반영 교체」 → 고친 뒤 `true` 로 다시 교체. 꺼진
        동안 닫힌 미해제 회차는 `ending` 에 묶이고
        (`edge_bounds_unreleased_without_writer`) Writer 가 돌아오면
        풀립니다.
    *   엣지는 Writer 를 되살린 교체 뒤 다시 켭니다 — [엣지 시험
        런북](edge-tests.md) 「/dvr 하한 시험(POK-525)」 의 2단 · 판정 C′.
    *   `dvr_floor_release_overdue` 만이면(`pass=release` 의
        `edge_bounds_write_failing` 이 함께 나와도) 엣지는 끄지 않습니다 —
        피해는 그 회차 VOD 가 열리지 않는 것뿐입니다.
    *   **Writer 없는 상자로 바꾸기 전에 해제 뒤 120초를 기다립니다.**
        옛 상자의 Writer 가 KVS 키를 지우고 해제를 적은 행은 120초 유예 뒤에
        지워집니다. 그 안에 옛 상자를 멈추면 지울 몫이 Writer 없는 상자의
        감시만 남습니다(그 감시도 유예가 지난 해제 행은 지움). 옛 상자를
        멈추기 직전 runtime-1a 「POK-525 원장 질의」 에서
        `released_settling` 0 을 봅니다.
*   원장 표 · KVS 그릇 · IAM 문은 어느 갈래에서도 지우지 않습니다.
*   **해체 · KVS 그릇 교체는 지금 할 수단이 없습니다.** 스위치
    (`dvr_floor_enabled = false`)는 `/dvr/*` 부착만 떼고, dvr-floor 함수의
    KVS 연결은 남습니다.
    연결이 남은 그릇의 교체 · 삭제는 DeleteKeyValueStore 가 쓰는 중인
    그릇으로 거절합니다(409 — [prod/edge](../prod/edge/README.md)
    「/dvr 하한(POK-525)」 · 함수 연결이 「쓰는 중」 에 드는지는 미실측).
    연결을 떼려면 함수를 지워야 하는데 `O7f` 묶음에
    `cloudfront:DeleteFunction` 이 없습니다. 그래서 해체 · 그릇 교체 앞에
    함수 삭제 승인 경로(`O7f` 에 DeleteFunction 행 추가 · 별도 승인)가 먼저
    있어야 하고, 이것은 POK-525 PR 범위 밖입니다.

**켜기 전 복귀**(A 로 돌아감 — 실행 직전마다 승인):

1.  A 를 멈췄으면(4.5 뒤) A 에서 `systemctl enable pokeclip-media` →
    `systemctl start pokeclip-media` → A 기동 판정(「정상 교체」 2 의
    `start-check.sh`). 4.5 전이면 A 는 아직 돌고 있어 이 걸음이 없습니다.

    ```shell
    media_ssm a "${work}/enable.sh"
    media_ssm a "${work}/start.sh"
    media_ssm a "${work}/start-check.sh" <A 의 서비스 집합>
    ```

1.  활성 키를 A 로 — `ingest_active_key` · `http_active_key` 를 A 로(「정상
    교체」 5 · 6 의 역 · plan 범위 같음) → 일관성(「정상 교체」 7 — 네 그룹
    A 하나).
1.  `<cut2>` = `tg_health` 의 `ingest` 가 A 한 대 healthy 가 된 시각 +
    30초를 적습니다.
1.  Q2-B — 세 행 모두 `waiting` 0 이 될 때까지 기다립니다. B 디스크에만
    있는 미완료 업로드를 셉니다(`<cut>` 앞에 B 가 만든 조각도 셈). 창 안에
    0 이 안 되면 B 를 **끈 채** 그대로 둡니다 — A · B 둘 다 옛 꼴만 써서
    안전합니다. 0 이 되기 전에는 B 를 회수하지 않습니다.
1.  B 회수 — plan 범위 = B 인스턴스 삭제 한 줄 → apply. B 는 끈 채였으므로
    정지 · disable 없이 회수합니다.

**restore-failed 처리**(파일 · 실행 상태 미확정):

1.  `round-keys.sh off` 를 보냅니다. 결과마다:
    *   `SIDECAR ROUND KEYS OK off` — 새 사이드카가 끔으로 떴습니다. 3 으로.
    *   `SIDECAR ROUND KEYS FILE off` — 실행 중 사이드카를 확인하지 못해
        파일만 끔으로 썼습니다. 2 로.
    *   `SIDECAR ROUND KEYS FAIL recreate` · `journal` · `verify` — 파일은
        이미 끔이고 실행 중 사이드카가 미확정입니다. 2 로.
    *   `SIDECAR ROUND KEYS FAIL write` — 파일이 그대로입니다. SSM 으로
        `/etc/pokeclip-media/sidecar.env` 를 손으로 정확히
        `PLAYBACK_ROUND_KEYS=false` 한 줄(`root:root 0644`)로 씁니다(명령
        원문을 kty 에게 보이고 승인). 스크립트와 같은 차례로 씁니다 — 자리가
        링크가 아닌 일반 파일인지 먼저 보고, 같은 디렉터리의 임시 파일에 써
        `chown 0:0` · `chmod 0644` 한 뒤 `mv -f` 로 바꿉니다(제자리에 `>` 로
        덮어쓰지 않습니다). 2 로.
    *   `SIDECAR ROUND KEYS FAIL lock` — 다른 실행이 잠금을 쥐어(또는 잠금
        파일을 열지 못해) 아무것도 바꾸지 않았습니다. 동시에 둘을 돌리지
        않습니다 — 아직 도는 SSM 명령 · 상자 프로세스가 없는지 위 「켬 실패
        · 바뀐 것 없음」 의 `list-commands` · `round-keys-ps.sh` 로 확인한 뒤
        1 을 다시 합니다. 다시 해도 `lock` 이면 kty 에게 보고합니다.
    *   `SIDECAR ROUND KEYS FAIL config` · `usage` — 스크립트가 설정 · 인자
        검사에서 멈춰 파일 · 사이드카가 그대로입니다. kty 에게 보고하고,
        설정 · 인자를 고친 뒤 1 을 다시 합니다.
1.  B 유닛 재시작 — **방송 없는 창에서만** 합니다. 시작 스크립트가 compose
    전체를 `--force-recreate` 로 다시 띄워 mediamtx 도 내려가므로(`start-
    media.sh` 머리 9) 진행 중 송출이 끊깁니다. 직전에 B `PATHS 0` 을 보고
    `restart.sh` → B 기동 판정(`start-check.sh`).
1.  Q1 을 다시 재고 「켜기 전」 선언 판정 → `BEFORE ON OK` 면 「켜기 전」.
    Q1 ≥ 1 이면 「켠 뒤 · 새 꼴 행 1 이상」. `LAYOUT legacy` 인데
    `ROUND_FROZEN` 이 `absent` 가 아니면 멈추고 kty 에게 보고합니다.
1.  그래도 `LAYOUT legacy` 가 아니면 B 사이드카만 멈추고 kty 에게
    회부합니다. 송출은 mediamtx 가 계속하고, 색인은 사이드카가 다시 뜰 때
    디스크의 녹화를 따라잡습니다. 컨테이너 단독 정지는 media-host README
    「운영 규칙」 1 이 금지하는 꼴이라, 이 자리의 예외로 kty 승인을 따로
    받습니다(`on-failure` 정책이라 다음 유닛 재기동까지 사이드카가 멈춘 채
    있습니다).

```shell
media_ssm b "${work}/round-keys.sh" off
media_ssm b "${work}/paths.sh"          # 재시작 직전 PATHS 0
media_ssm b "${work}/restart.sh"
media_ssm b "${work}/start-check.sh" <4.1 과 같은 서비스 집합>
media_ssm b "${work}/layout-check.sh" <Q1 값>
printf 'docker compose -p pokeclip-media -f /etc/pokeclip-media/compose.yml stop sidecar\n' \
  > "${work}/sidecar-stop.sh"           # 4 — 승인 뒤에만
media_ssm b "${work}/sidecar-stop.sh"
```

### POK-525 다음 교체

이 교체 뒤의 교체(옛 상자 B → 새 상자 C)입니다. C 는 처음부터 켬으로
뜨고(4.12 의 tfvars), 트리거는 이미 있습니다. 순서는 **「정상 교체」 1–7 →
옛 상자 배출 → 정지 → 회수** 이고, 아래 표가 그 전부입니다. 번호는 이 표
안의 것입니다.

「POK-525 교체」 의 걸음 표(4.1–4.12)를 따라 밟지 않습니다. 그 표의 통과
줄은 끈 채 뜨는 새 상자와 트리거 없는 DB 를 전제로 해서, 켬으로 뜨는 C 에
대면 교체가 막힙니다 — 4.1 의 `SIDECAR LEGACY OK` 는 늘 NOT OK, 4.2 의 Q1 은
C 루프백이 새 꼴이라 1 이상, 4.10 의 Q3 · Q4 는 `<on>` 이 없습니다. 이 교체가
그 표에서 빌려 쓰는 것은 옛 상자 배출(4.4)과 정지(4.5) 둘뿐입니다.

| # | 동작 | 통과 | 바탕 |
|---|---|---|---|
| 0 | 선행 조건 확인(아래) | 하한 이상 둘 · 트리거 있음 | — |
| 1 | 시간대를 고르고 스트리머에게 알린다 | — | 정상 교체 1 |
| 2 | C 기동(**켬** · 같은 AZ · 등록 안 함) | 기동 판정 넷(`PREREQ_FAIL 0` · `START_BLOCKED 0` · `SIDECAR_DB` · `MEDIA WATCH OK`) | 정상 교체 2 |
| 3 | C 루프백(대상 그룹 밖) | `LOOPBACK OK` | 정상 교체 3 |
| 4 | B · C 방송 경로 수 | B `PATHS 0` | 정상 교체 4 |
| 5 | ingest 전환 — `<cut>` 을 적음 | NLB = C 1대 healthy | 정상 교체 5 |
| 6 | http 전환 | ALB 셋 = C 1대 healthy | 정상 교체 6 |
| 7 | 일관성 확인(읽기) | 네 그룹 모두 C 하나 · healthy | 정상 교체 7 |
| 8 | B 배출 — B `paths.sh` → Q2 | B `PATHS 0` · Q2 세 행 `waiting` 0 | POK-525 교체 4.4 |
| 9 | B 정지 · 끔 — `stop.sh` → `disable.sh` | `MEDIA STOPPED OK` · `MEDIA DISABLED OK` | POK-525 교체 4.5 |
| 10 | B 회수 — **하루 관찰 뒤** | 회수 직전 Q2 0 · B `MEDIA STOPPED OK` · `MEDIA DISABLED OK` → plan 범위 B 삭제 한 줄 | 정상 교체 8 · 회수 직전 확인은 4.11 |

```shell
media_ssm b "${work}/paths.sh"            # 8 · Q2 앞
media_ssm b "${work}/stop.sh"             # 9
media_ssm b "${work}/disable.sh"
media_ssm b "${work}/stopped-check.sh"
media_ssm b "${work}/disabled-check.sh"
```

상자 스크립트는 「POK-525 교체 상자 스크립트」 와 같이 작업 디렉터리에 써
둡니다.

**쓰지 않는 것** — 「POK-525 교체」 의 다음 걸음 · 판정은 이 교체에
없습니다.

*   `layout-check.sh` 의 끝 판정(4.1 · 4.6). 켬으로 뜬 C 에는 늘 `SIDECAR
    LEGACY NOT OK` 입니다. C 의 켬 여부는 통과 조건이 아닙니다 — 보려면 그
    출력의 `LAYOUT` 줄(`round`)만 읽습니다.
*   Q1(4.2 · 4.6) — C 는 처음부터 새 꼴을 씁니다.
*   4.6–4.9(켜기 전 확인 · 켬 · 켬 뒤 루프백 · 운영 SQL 2) — 결정 9 (가)로
    이번 교체에서 끝난 일회성 걸음입니다.
*   4.10 시험 방송(Q3 · Q4) — 「정상 교체」 에 없는 걸음이고, 판정 질의가 이
    교체에 없는 `<on>` 을 씁니다.
*   4.11 의 회수 앞당김과 4.12(tfvars 는 이미 켬).
*   「POK-525 롤백」 의 갈래 — 모두 A → B 교체의 켬 전후를 가르는 갈래입니다.

걸음마다:

*   **8 · 9 — 옛 상자 배출 · 정지는 빠지지 않습니다.** B 의 이미지가 새 꼴을
    읽어도 B 디스크에만 있는 미완료 업로드는 C 가 대신 올리지 못합니다(C 에는
    그 파일이 없음). 그래서 7 뒤 Q2(`<cut>` = `tg_health` 의 `ingest` 가 C 한
    대 healthy 가 된 시각 + 30초 · `<test>` = 3 의 C 루프백 시험 스트림)가 0
    이 된 뒤에만 B 를 멈춥니다. 창 안에 0 이 안 되면 B 를 띄운 채 절차를
    여기서 멈추고, 다음 창에 8 부터 다시 합니다(4.4 와 같음). 정지는 4.5 와
    같이 유닛으로 `stop` → `disable` 이고 `mask` 는 쓰지 않습니다.
*   **10 — 회수는 「정상 교체」 8 그대로 하루 관찰 뒤입니다.** B 는 POK-525
    하한 이상이라 C 가 쓴 새 꼴 행을 읽습니다 — 되돌릴 곳으로 남습니다. 4.11
    이 하루 관찰을 쓰지 않은 까닭(A 는 새 꼴을 못 읽음)이 여기에는 없습니다.
    관찰 동안 B 로 되돌려야 하면, B 는 9 에서 멈춰 있으니 B 에서 `enable.sh`
    → `start.sh` → 기동 판정(`start-check.sh`)을 먼저 보고 활성 키를 B 로
    돌립니다(「정상 교체」 5 · 6 의 역 → 7). 그 뒤 C 를 회수하기 전의 배출
    판정은 이 런북에 아직 없습니다 — kty 에게 보고합니다.

**선행 조건**(0) — 하나라도 아니면 이 절이 아니라 「POK-525 교체」 를
처음부터 씁니다.

1.  B · C 의 이미지가 모두 POK-525 하한 이상(위 「POK-525 하한」 판정)입니다.
1.  트리거가 이미 있습니다. 첫 다음 교체(B → C)에서는 4.9 의 기록(`ROUND
    FROZEN OK` · `VERIFY OK`)으로 판정합니다 — B 는 4.9 뒤 다시 뜨지 않아
    사이드카 기동 줄이 늘 `round_frozen=absent` 입니다. 트리거 뒤에 뜬 옛
    상자는 기동 줄로도 볼 수 있는데, 옛 상자에 `layout-check.sh` 를 돌려 끝
    판정(`SIDECAR LEGACY …`)이 아니라 `ROUND_FROZEN` 줄만 읽습니다
    (`present` 가 통과).

## 설정 반영 교체

M15 · O8a · I1-6 반영(`control_upstream`)이나 이미지 다이제스트 · 도구 판 ·
`janitor_enabled` 를 바꿀 때입니다. 떠 있는 상자는 고치지 않습니다. 예외는
아래 「제자리 전환」 입니다(공개 전 한정).

1.  tfvars 의 설정을 바꿉니다.
1.  B 가 옛 모드면 위 「언제 쓰나」 의 옛 모드 단서를 따릅니다 — README
    「되돌리기 순서」 1 을 이 자리(B 를 더하기 전)에서 하고, 3 은 「정상
    교체」 5(ingest 전환) 뒤에 확인합니다.
1.  `instances` 에 `config_version` 을 올린 새 키 B 를 더합니다.
1.  「정상 교체」 1–8 을 돕니다. 2 의 plan 범위에서 공유 자원 변경이
    보이면 위 「plan 범위」 의 주의를 따릅니다.

*   `control_upstream` 을 채운 뒤 제어면 대상 그룹 헬스는 여전히 nginx
    경로라, 사이드카가 다시 뜨는 동안에도 healthy 로 보이고 제어면은 502 를
    냅니다(media-host README 「후속」 I1-6 행).
*   compose 판을 바꾸면 `compose_sha256` 도 함께 바꿉니다. 하나만 바꾸면 B 가
    `MEDIA PREREQ FAIL compose-version` 으로 막힙니다 — 그대로 B 를 회수하고
    고칩니다.
*   **MediaMTX 설정은 컨테이너 재시작(유닛 stop · start)으로만 바꿉니다.**
    다음 셋은 하지 않습니다 — ① `/run/pokeclip-media/mediamtx.yml` 고치기
    ② Control API 의 설정 변경(`/v3/config/…`) ③ 그 밖의 핫 리로드(설정을
    떠 있는 프로세스에 다시 읽히는 것). `hlsAlwaysRemux`(시청자가 없어도
    송출 시점부터 HLS 목록을 만드는 설정)가 켜진 상태에서 설정을 바꿔
    경로를 다시 만들면, 그 중의 경합으로 muxer(HLS 목록 · 조각을 만드는
    MediaMTX 내부 부품)가 없어져 그 경로에 500 이 이어질 수 있습니다
    (bluenviron/mediamtx#6315 — 열림(2026-11-07 확인) · 우리 판 해당). 그렇게 되면 요청이
    와도 스스로 복구하지 않습니다. 새 상자는 기동이 곧 재시작이고, 제자리 전환도 stop →
    교체 → start 로 이 규칙을 지킵니다.

## 제자리 전환 — 떠 있는 상자의 설정을 바꿀 때

새 상자를 띄우지 않고, 떠 있는 상자 A 의 설정 파일 · 설치 묶음(스크립트 ·
MediaMTX 템플릿이 든 이미지)을 그 자리에서 바꾼 뒤 유닛을 다시 띄웁니다.
2026-11-04 O8a 때 새 상자 재고가 없어 A 를 제자리에서 바꾼 일(kty 「그냥
바로 여기다가 prod 띄워」)을 일반화한 절차입니다. 실행자 · 승인 규칙은 이
런북 머리와 같습니다.

**쓰는 조건:**

*   **공개(O8) 전에만 씁니다.** 공개 뒤에는 재시작 동안 1–2분 끊기는 것을
    kty 가 감수할 때만 씁니다.
*   새 상자를 띄울 수 없을 때(재고 부족 등)나, 설정 · 이미지만 바꿀 때
    (POK-529 사이드카 배포 등)입니다. AMI · 인스턴스 유형 · 공유 자원(역할
    정책 · SG 규칙 · 거부 목록 파라미터) 변경은 이 절로 하지 않습니다 —
    「설정 반영 교체」 입니다.
*   방송 0(A 의 `PATHS 0`) · 송출 금지 구간을 지킵니다.
*   MediaMTX 설정의 반영도 이 절의 재시작으로만 합니다(「설정 반영 교체」
    의 bluenviron/mediamtx#6315 규칙).
*   계약9 발행(broadcast.started · ended)이 켜진 구성이면 아래 「계약9 회차
    확인」 을 지킵니다.

**계약9 회차 확인:** `PATHS 0` 이어도, started 를 발행한 회차가 아직
닫히는 중(정산 창 15분)일 수 있습니다. 그때 사이드카를 재기동하면 게이트
준비가 실패해 꺼진 게이트로 강등되고, 그 회차가 ended 없이 닫힐 수
있습니다(「O8a 당김 — 운영 감시」 의 재기동 강등). 그래서 옛 구성이나 새
구성의 발행 목록(`lifecycle_publish_streams`)이 차 있으면 아래 셋을
지킵니다. 둘 다 비어 있으면 이 확인은 하지 않습니다.

**적용 범위 — 시험 ID 하나일 때만.** 발행 잔여 질의는 시험 ID 하나의
회차만 셉니다. 그래서 옛 구성 · 새 구성의 발행 목록이 각각 비었거나 그
시험 ID 하나만 담을 때만 이 절차로 전환합니다. 목록에 ID 가 둘 이상이거나
`*` 가 있으면, 다른 ID 의 열린 회차가 질의에 잡히지 않은 채 재기동 강등으로
ended 없이 닫힐 수 있습니다. 그 구성이면 제자리 전환을 하지 않고 kty 에게
회부합니다(멈춤 자리). 옛 구성과 새 구성이 서로 다른 시험 ID 를 하나씩
담을 때(예: 옛 `[X]` · 새 `[Y]`)도 같습니다. 질의 한 번은 시험 ID 하나만
보므로 어느 ID 로 물을지 정할 수 없습니다 — kty 회부입니다.

1.  **재기동 전 — 걸음 2 직전 · 원복 직전.** 발행 잔여 질의
    ([런타임 1단-가 런북](/infra/terraform/runbooks/runtime-1a.md) 「발행
    잔여 질의」)로 Q1 = Q2 = Q3 = 0 · `rounds` ≥ 1 을 봅니다. 시험 회차가
    생기기 전이면 Q4 0 행입니다. 「O8a 당김 교체」 의 종료 감시 · 롤백과
    같은 판정입니다. 하나라도 아니면 멈춥니다.
    *   Q3 > 0(회차가 아직 열림): 재기동하지 않습니다. 게이트가 켜진 지금
        사이드카(M-B 통과)가 회차를 닫고 ended 를 발행할 때까지 기다렸다가
        다시 봅니다.
    *   Q1 > 0 ∧ Q3 = 0(회차 닫힘 · ended 없음): kty 회부(멈춤 자리 —
        「O8a 당김 — 롤백」 표의 같은 행).
    *   Q2 > 0(SNS 재시도 중): 「O8a 당김 — 롤백」 표의 Q2 행을 따릅니다.
1.  **POK-529 사이드카가 배포되기 전 구성**(재기동 강등이 남은 판)이면,
    그 구성의 선행 조건과 판정을 그대로 따릅니다. 송출 금지 구간을 열고
    Q1 = 0 · `rounds` ≥ 1(시험 회차 전이면 Q4 0 행)을 보는 「O8a 당김
    교체」 의 게이트 공존 규칙과, 기동 뒤의 M-B 판정입니다.
1.  **전환 뒤.** 걸음 3 의 기동 판정 · 관찰 판정 뒤에 M-B(`lifecycle-check.sh`
    — 「O8a 당김 — 상자 스크립트」)를 봅니다. **M-B 통과가 루프백의 선행
    조건입니다** — 루프백은 시험 회차를 새로 만들므로, 게이트가 꺼진
    사이드카 앞에서 회차를 열지 않습니다(「O8a 당김 교체」 걸음 13 과 같은
    순서). `M-B NOT OK` 면 루프백 없이 걸음 3 의 실패 갈래로 갑니다. 루프백
    뒤에는 「O8a 당김 — 운영 감시」 의 종료 감시를 돕니다. 위 1 의 질의가
    Q1 = Q2 = Q3 = 0 · `rounds` ≥ 1 이고 ended 가 발행될 때까지입니다.
    상한(30분)을 넘기거나 Q1 > 0 ∧ Q3 = 0 이면 kty 회부입니다. 송출이 다시
    붙으면 그 절의 송출 감시 주기로 돌아갑니다.
    *   **이 절의 감시는 M-B 만 봅니다.** 운영 감시 절의 주기는 M-B · M-A
        를 함께 보지만, M-A(옛 상자 정지 유지)는 정지 · 차단한 별도 퇴역
        상자가 있을 때의 판정입니다. 제자리 전환은 상자 하나(`a_key`)를 그
        자리에서 바꾸므로 퇴역 상자가 없습니다. 그래서 M-B 는 지금
        `a_key` 에서 보고, M-A 는 보지 않습니다. 운영 감시 절의 「M-A
        불통과 → 정지 · 차단」 갈래를 떠 있는 A 에 적용하지 않습니다 — A 는
        활성 상자입니다. 별도 퇴역 상자가 따로 있을 때만 그 상자에 M-A 를
        봅니다.

이 절은 그 판정들을 가리키기만 합니다. 질의 본문 · 입력(시험 ID · 시각)과
시험 회차의 뜻은 런타임 1단-가 런북 「발행 잔여 질의」 가, M-B 의 통과 조건
· `lifecycle-check.sh` 본문은 「O8a 당김 — 운영 감시」 · 「O8a 당김 — 상자
스크립트」 가 정본입니다.

**기록과 실물의 어긋남:** A 의 user_data 는 첫 부팅 판 그대로입니다
(`ignore_changes`). 제자리 전환 뒤에는 상자 안 파일 · 태그 `ConfigVersion`
과 user_data 가 어긋납니다. 이 어긋남의 정본은 이월 기록(K11 — 2026-11-04
첫 제자리 전환)과 이 절차의 실행 기록입니다.

**재부팅해도 바꾼 파일이 남는 근거:** user_data 는 cloud-init
`#cloud-config` 의 `write_files` · `runcmd` 입니다
(`modules/media_host/user_data.tf`). cloud-init 은 이 둘을 인스턴스당 한 번만
돕니다. `ignore_changes = [ami, user_data_base64]`(`modules/media_host/main.tf`)
라 인스턴스 속성도 바뀌지 않습니다. 「인스턴스당 한 번」 은 cloud-init 문서
근거이고, 이 상자에서 재부팅으로 확인한 실측은 아직 없습니다 — 첫 실행 뒤
여유가 있으면 재부팅해 해시를 대조합니다(kty 선택).

### 제자리 전환 선행

1.  **바꾸기 전 렌더본을 뽑습니다.** tfvars 를 고치기 **전에** A 의 지금
    렌더본(user_data 가 상자에 쓰는 `write_files` 파일)을 파일별로 씁니다.
    `terraform console` 이 모듈 출력 `rendered_user_data` 를 읽습니다(읽는
    동안 상태를 잠그고 바로 풉니다). 파일 이름은 경로의 `/` 를 `__` 로 바꾼
    것입니다. 값에 비밀은 없지만 ARN 이 있어 작업 디렉터리 밖에 두지
    않습니다.
1.  tfvars 에서 `instances.<A>.config_version` 을 올리고, 바뀐 다이제스트
    (사이드카 · 묶음 `image_digests.bundle` 등)를 넣습니다.
1.  **새 렌더본을 뽑아 비교합니다.** 다른 파일은 아래 허용 집합 안이어야
    합니다. 그 밖의 파일(journald 설정 · docker 설정)이 다르거나 한쪽에만
    있으면 멈추고 kty 에게 회부합니다. 허용 집합 안이어도 **옛 렌더본에만
    있는 파일**(`diff -rq` 의 `Only in …/render-old`)이면 멈추고 kty 에게
    회부합니다 — 상자 스크립트는 새 렌더본에 있는 파일만 쓰므로 그 파일은
    상자에 옛 내용 그대로 남습니다(아래 `only_old` 가 `STOP only-old` 를
    냄).
    *   `etc__pokeclip-media__*`(compose · media.env · nginx · env 파일들)
    *   `etc__systemd__system__pokeclip-media.service` · 그 drop-in
    *   `usr__local__sbin__pokeclip-media-bootstrap` — 묶음이 바뀔 때만
        다릅니다. 첫 부팅에만 도는 파일이라 제자리에 쓰지 않습니다.
    *   **허용 집합 안이어도 내용 diff 는 kty 가 읽고 승인합니다.**
        compose · nginx · media.env 처럼 상자에 실제로 쓰는 파일의 바뀐 줄을
        `diff -ru` 로 보입니다(아래 명령). tfvars PR 리뷰로 갈음하지
        않습니다 — PR 은 tfvars 줄만 보이고, 템플릿을 거친 렌더 결과는
        보이지 않기 때문입니다. 렌더본에는 계정 ID 가 든 ARN 이 있어 diff 는
        이 기계 화면에서만 보이고 채팅 · 티켓에 붙이지 않습니다. 승인 전에는
        apply · 상자 스크립트로 가지 않습니다.
1.  **묶음이 바뀌면 무엇을 싣는지 확인하고 kty 가 승인합니다.** 새
    렌더본의 `MEDIA_BUNDLE_IMAGE` 가 옛 렌더본과 다르면 묶음이 바뀌는
    것입니다.
    *   빌드 커밋은 이 변경의 머지 커밋(또는 kty 가 정한 커밋)으로
        고정합니다. 고정해야 아래 diff · 승인한 내용과 실제로 싣는 내용이
        같아집니다. 머지 커밋도 그 앞 develop 이력을 다 담으므로, 실제
        방어선은 아래 diff · 승인입니다.
    *   빌드 · 필수 파일 대조는
        [prod 산출물 런북](/infra/terraform/runbooks/prod-artifacts.md)
        「media-host-bundle」 그대로입니다. 필수 파일 목록의 정본은
        `modules/media_host/templates/bootstrap.sh.tftpl` 의
        `REQUIRED_FILES` 이고, 이 절은 사본을 두지 않습니다.
    *   옛 묶음(옛 렌더본의 `MEDIA_BUNDLE_IMAGE` — 상자 표지
        `bundle-installed` 와 같아야 함)과 새 묶음을 이 기계에서 꺼내
        설치 범위의 해시 목록을 만듭니다(아래 `bundle_sums`).
    *   바뀐 파일 목록이 「옛 묶음 커밋 → 빌드 커밋」 의 git 누적 변경과
        같아야 합니다. 옛 묶음 커밋은 그 다이제스트를 빌드한 커밋입니다
        (prod 산출물 런북 「기록」 — 2026-11-04 의 A 는 `8e7b329c`). 다르면
        멈춥니다.
    *   **승인 범위는 그 누적 변경 전부입니다.** 예: 2026-11-04 develop
        기준으로 이미 POK-525 몫(`bin/lib/ops.sh` · `bin/start-media.sh` ·
        새 `bin/sidecar-round-keys.sh` — 3파일 366줄)이 있고, 여기에 O8a
        결함 묶음의 `mediamtx.yml.tmpl` 한 줄(`hlsAlwaysRemux`)이
        더해집니다. POK-529 가 그보다 먼저 머지됐으면 그 몫도 더해지고,
        늦게 머지되면 그 몫은 없습니다. kty 가 diff 를 읽고 승인하기 전에는
        묶음을 바꾸지 않습니다.
1.  plan 범위 = 「제자리 전환」 한 줄(위 「plan 범위」 표) → apply(승인).
    그 밖의 차분은 공유 자원 주의를 따릅니다.

렌더본 · 묶음을 뽑는 함수입니다. 저장소 뿌리에서 돌립니다.

```shell
a_key="<A>"   # 제자리로 바꿀 상자의 키(예: a)

# render_files <디렉터리> — 상자 a_key 의 렌더본을 파일별로 쓴다.
render_files() {
  local file_path expr
  expr='base64encode(jsonencode({for f in yamldecode('
  expr+="module.media_host.rendered_user_data[\"${a_key}\"]).write_files"
  expr+=' : f.path => f}))'
  mkdir -m 0700 "$1" || return 2
  printf '%s\n' "${expr}" \
    | terraform -chdir=infra/terraform/prod/media console | tr -d '"\n' \
    | base64 -d >"$1.json" || return 2
  jq -e 'length > 0' "$1.json" >/dev/null || return 2
  while IFS= read -r file_path; do
    jq -j --arg p "${file_path}" '.[$p].content' "$1.json" \
      >"$1/$(printf '%s' "${file_path#/}" | sed 's|/|__|g')" || return 2
  done < <(jq -r 'keys[]' "$1.json")
}

# bundle_sums <묶음 이미지> <디렉터리> — 묶음을 꺼내 설치 범위(bin · bin/lib
# 의 파일 · 템플릿)의 해시 목록을 <디렉터리>.sums 에 쓴다.
bundle_sums() {
  local cid rc
  docker pull --quiet --platform linux/arm64 "$1" >/dev/null || return 2
  cid="$(docker create --platform linux/arm64 "$1" /bundle-not-run)" || return 2
  mkdir -m 0700 "$2" && docker cp "${cid}:/bundle/." "$2/"
  rc=$?
  docker rm "${cid}" >/dev/null
  [ "${rc}" -eq 0 ] || return 2
  (cd "$2" && find bin bin/lib -maxdepth 1 -type f | LC_ALL=C sort \
    | xargs shasum -a 256 && shasum -a 256 mediamtx.yml.tmpl) >"$2.sums"
}
```

선행 1 · 3 · 4 의 명령입니다. 1 은 tfvars 를 고치기 전, 3 · 4 는 고친 뒤에
돌립니다.

```shell
render_files "${work}/render-old" || { echo "STOP render-old"; }   # 선행 1
render_files "${work}/render-new" || { echo "STOP render-new"; }   # 선행 3
diff -rq "${work}/render-old" "${work}/render-new"      # 허용 집합 안인지
# only_old — 옛 렌더본에만 있는 허용 집합 파일마다 STOP 줄을 낸다.
only_old() {
  local f
  for f in "${work}/render-old"/etc__pokeclip-media__* \
    "${work}/render-old"/etc__systemd__system__pokeclip-media.service*; do
    [ -e "${work}/render-new/${f##*/}" ] || echo "STOP only-old ${f##*/}"
  done
}
only_old                                                # 빈 출력
diff -ru "${work}/render-old" "${work}/render-new"      # kty 가 읽고 승인
jq -r 'to_entries[]
  | select(.key | test("^/etc/(pokeclip-media|systemd/system)/"))
  | select(.value.permissions != "0644") | .key' \
  "${work}/render-new.json"                             # 빈 출력
bundle_of() {
  sed -n 's/^MEDIA_BUNDLE_IMAGE=//p' "$1/etc__pokeclip-media__media.env"
}
old_bundle="$(bundle_of "${work}/render-old")"
new_bundle="$(bundle_of "${work}/render-new")"
if [ "${old_bundle}" != "${new_bundle}" ]; then          # 선행 4
  bundle_sums "${old_bundle}" "${work}/bundle-old" \
    || { echo "STOP bundle-old"; }
  bundle_sums "${new_bundle}" "${work}/bundle-new" \
    || { echo "STOP bundle-new"; }
  cat "${work}/bundle-old.sums" "${work}/bundle-new.sums" | LC_ALL=C sort \
    | uniq -u | awk '{ print $2 }' | LC_ALL=C sort -u \
    >"${work}/bundle-changed.txt"
  git diff --name-only --no-renames <옛 묶음 커밋> <빌드 커밋> -- \
    infra/media-host/bin infra/media-host/mediamtx.yml.tmpl \
    | sed 's|^infra/media-host/||' | LC_ALL=C sort >"${work}/bundle-git.txt"
  diff "${work}/bundle-changed.txt" "${work}/bundle-git.txt"   # 빈 출력
  diff -ru "${work}/bundle-old" "${work}/bundle-new"          # kty 가 읽고 승인
fi
```

*   **`STOP …` 줄이 나오면 멈춥니다.** 렌더본 · 묶음을 뽑지 못한 것이라 그
    뒤의 diff · 승인은 빈 입력이나 지난 결과를 볼 수 있습니다. 원인을 고쳐
    그 함수부터 다시 돌립니다. 다시 돌리기 전에 그 함수가 만든
    `<디렉터리>` 와 `<디렉터리>.json` · `<디렉터리>.sums` 를 지웁니다 —
    `mkdir -m 0700` 에 `-p` 가 없어, 남아 있으면 다시 돌려도 실패합니다.
*   `git diff` 의 `--no-renames` 는 옮긴 파일을 옛 이름 삭제 · 새 이름
    추가 두 줄로 냅니다. 해시 목록 비교(`bundle-changed.txt`)도 두 이름을
    모두 내므로 같은 꼴로 맞춥니다.
*   설정 파일의 쓰기 모드는 모두 `0644` 입니다(`user_data.tf` 의
    `write_files`). 위 `jq` 가 빈 출력이 아니면 상자 스크립트의 `0644` 가
    틀린 것이라 멈춥니다.
*   묶음 이미지는 ECR 에 있습니다. 꺼내기 전에 ECR 에 로그인합니다(prod
    산출물 런북과 같은 방법).

### 제자리 전환 상자 스크립트

상자 스크립트는 둘로 만듭니다. 본문(`inplace-body.sh`)은 고정이고, 이
기계가 렌더본 · 해시 목록을 머리에 붙여 `inplace.sh` 를 만듭니다. 본문은
bash 로 돕니다(위 「SSM 명령 보내기」 의 bash 규칙 — 새 묶음의
`check_env_line` 이 bash 함수입니다).

본문이 하는 일은 차례대로 이렇습니다.

1.  `PATHS 0` 이 아니면 멈춥니다.
1.  **설정 파일 기준 대조.** 허용 집합의 파일마다 상자 파일의 해시를 봅니다.
    새 렌더본과 같으면 그대로(`SAME`), 옛 렌더본과 같으면 바꿀
    대상(`WRITE`)입니다. 둘 다 아니면(없음 포함) 멈추고 kty 에게
    회부합니다(`INPLACE FAIL baseline …`) — 운영 중 상자에서 바뀌는 파일은
    렌더본으로 덮지 않습니다. 병합 규칙은 미리 두지 않습니다.
    *   `ingest-auth.env` 도 상자에서 바뀌는 파일입니다. IP 한도 끄기 ·
        켜기(media-host README 「IP 한도 끄기 · 켜기」 의
        `ingest-auth-ip-limit.sh`)가 이 파일을 다시 씁니다. 그 스크립트를
        쓴 적이 있으면 이 파일이 `baseline` 에 걸릴 수 있습니다.
    *   `INPLACE FAIL baseline <이름>` 이면 상자는 그대로입니다(stop 전).
        그 줄과 상자 파일의 지금 값(비밀 없는 설정 파일만 · `cat`)을 kty
        에게 보이고, 「지금 값을 살릴지 · 새 렌더본으로 덮을지」 를 kty 가
        정합니다. 정하기 전에는 다시 보내지 않습니다. 선행 5 의 태그 apply
        를 이미 했으면 아래 「stop 전에 포기할 때」 도 봅니다.
1.  **`sidecar.env` 는 따로 다룹니다.** 첫 부팅 뒤 정본은 상자 안
    파일입니다(`PLAYBACK_ROUND_KEYS` — POK-525 스위치 · `prod/media`
    `variables.tf` 의 `playback_round_keys` 설명). 새 `start-media.sh` 는 이
    파일이 `check_env_line` 을 통과하지 않으면
    `MEDIA START BLOCKED: sidecar settings unavailable` 로 기동을 막습니다.
    통과 조건은 링크 아닌 일반 파일 · 64바이트 이하 · 내용이 정확히
    `PLAYBACK_ROUND_KEYS=true` 또는 `=false` 한 줄입니다. 본문은 기동할
    `start-media.sh`(묶음이 바뀌면 새 묶음의 것)에서 그 함수를 그대로 꺼내
    stop 전에 같은 판정을 합니다.
    *   있고 통과: 건드리지 않습니다(`KEEP`).
    *   없음: 새 렌더본 값으로 씁니다(`WRITE … absent`). 다른 파일처럼
        백업 · 원복을 탑니다.
    *   있는데 통과하지 않음: 쓰기 전에 멈춥니다(`INPLACE FAIL
        sidecar-env` — kty 회부).
    *   새 묶음의 `start-media.sh` 에서 함수를 찾지 못하면 멈춥니다
        (`INPLACE FAIL check-env-line` — 함수 꼴이 바뀐 것이라 본문의 `sed`
        를 고칩니다). 꺼낸 함수를 `eval` 로 읽지 못한 때도 같습니다.
    *   묶음이 그대로이면 지금 시작 스크립트를 봅니다. `check_env_line`
        이라는 이름이 파일에 아예 없을 때만 판정 없이 둡니다(`KEEP …
        unchecked` — 이 판정이 없는 구판). 이름은 있는데 함수를 꺼내지
        못했거나, 파일이 없거나 읽지 못하면 구판인지 추출 실패인지 가를 수
        없으므로 멈춥니다(`INPLACE FAIL check-env-line`).
1.  **묶음이 바뀌면** 표지가 옛 묶음인지, 상자의 `bin/` 트리 ·
    `mediamtx.yml.tmpl` 해시가 옛 묶음과 같은지 봅니다. 어긋나면
    멈춥니다. 그다음 새 묶음을 `docker pull` · `docker create` ·
    `docker cp` 로 꺼내, 해시 목록이 이 기계에서 승인한 새 묶음과 같은지
    봅니다(`BUNDLE CHANGE`).
1.  바꿀 설정 파일을 같은 파일 시스템의 임시 디렉터리에 써서 해시를
    확인합니다. 여기까지 실패하면 상자는 그대로입니다. 바꿀 것이 하나도
    없으면 `INPLACE NOTHING` 으로 끝납니다(재시작 없음).
1.  `systemctl stop pokeclip-media`(옛 `stop-media.sh` 로 멈춤).
1.  **백업** `/etc/pokeclip-media/rollback-v<옛 판>-<UTC>/`(0700) — 바꿀
    파일(없던 파일은 `<이름>.absent` 표지) · 묶음이 바뀌면
    `/opt/pokeclip-media/bin` 트리 · `mediamtx.yml.tmpl` ·
    `bundle-installed`. `BACKUP <경로>` 줄을 기록해 둡니다(원복에 씁니다).
1.  임시 파일을 `mv` 로 제자리에 놓습니다. 묶음은 bootstrap 과 같은 소유 ·
    모드(bin 0755/0755 · lib 0755/0644 · 템플릿 0644)로 새 트리에 설치해
    해시를 다시 본 뒤 바꾸고, 표지에 새 이미지를 씁니다. 유닛 · drop-in 이
    바뀌었으면 `systemctl daemon-reload`.
1.  `systemctl start pokeclip-media` → 5초 뒤 `UNIT <상태>`. `active` 일
    때만 `INPLACE OK` 이고, 그 밖의 상태면 `INPLACE FAIL unit stopped` 로
    0 이 아니게 끝납니다. 유닛이 `Type=simple` 이라 `systemctl start` 는
    성공한 뒤에도 곧 죽을 수 있기 때문입니다. 5초 뒤에 죽는 경우는 걸음 3
    의 판정이 잡습니다.

stop 뒤의 실패는 `INPLACE FAIL <단계> stopped` 입니다 — 유닛이 멈춘
채이므로 바로 「제자리 전환 원복」 으로 갑니다(`BACKUP` 줄이 없으면 백업
전 실패라 바뀐 파일이 없습니다 — `systemctl start` 만 합니다).

`BACKUP` 줄이 없는 `stopped` 실패의 명령입니다(실행 직전 승인).
`start.sh` 는 「POK-525 교체 상자 스크립트」 가 만드는 것과 같은 한 줄
파일입니다. 기동 판정은 옛
구성의 서비스 집합으로 봅니다.

```shell
printf 'systemctl start pokeclip-media\n' > "${work}/start.sh"
media_ssm "${a_key}" "${work}/start.sh"
media_ssm "${a_key}" "${work}/start-check.sh" <옛 구성의 서비스 집합>
```

```shell
cat > "${work}/inplace-body.sh" <<'SH'
set -u
readonly C=/etc/pokeclip-media
readonly BIN=/opt/pokeclip-media/bin
readonly MARKER=/var/lib/pokeclip-media/bundle-installed
export DOCKER_CONFIG=/root/.docker   # SSM 환경에 HOME 이 없을 수 있음
# fail — 임시 디렉터리(prep · stage · new_root)를 지우고 판정 줄을 낸다(stop
# 뒤면 원본은 백업에 있다).
fail() {
  [ -z "${prep:-}" ] || rm -rf -- "${prep}"
  [ -z "${stage:-}" ] || rm -rf -- "${stage}"
  [ -z "${new_root:-}" ] || rm -rf -- "${new_root}"
  echo "INPLACE FAIL $1"
  exit 2
}
sha() { sha256sum "$1" </dev/null | cut -d' ' -f1; }
# tree_sums <뿌리> <템플릿> — 묶음 설치 범위의 해시 목록(이 기계의
# bundle_sums 와 같은 꼴).
tree_sums() {
  (cd "$1" && find bin bin/lib -maxdepth 1 -type f | LC_ALL=C sort \
    | xargs sha256sum) </dev/null || return 1
  (cd "$(dirname "$2")" && sha256sum "$(basename "$2")") </dev/null
}
# install_tree <원천> <대상> <디렉터리 모드> <파일 모드> — bootstrap 과 같음.
install_tree() {
  local f
  install -d -o root -g root -m "$3" "$2" || return 1
  for f in "$1"/*; do
    [ -f "${f}" ] || continue
    install -o root -g root -m "$4" "${f}" "$2/${f##*/}" || return 1
  done
}
set -a; . "${C}/media.env" </dev/null || fail media-env; set +a
p="$("${BIN}/paths-count.sh" </dev/null)"; echo "${p}"
[ "${p}" = "PATHS 0" ] || fail paths

prep="$(mktemp -d "${C}/.inplace-new.XXXXXX")" || fail prep
writes="" sidecar=""
while read -r name old new b64; do
  [ -n "${name}" ] || continue
  path="/${name//__//}"
  if [ "${name}" = etc__pokeclip-media__sidecar.env ] \
    && { [ -e "${path}" ] || [ -L "${path}" ]; }; then
    sidecar=present
    continue
  fi
  if [ "${name}" = etc__pokeclip-media__sidecar.env ] && [ ! -e "${path}" ]
  then
    echo "WRITE ${name} absent"
  elif [ ! -f "${path}" ] || [ -L "${path}" ]; then
    fail "baseline ${name}"
  elif [ "$(sha "${path}")" = "${new}" ]; then
    echo "SAME ${name}"
    continue
  elif [ "$(sha "${path}")" = "${old}" ]; then
    echo "WRITE ${name}"
  else
    fail "baseline ${name}"
  fi
  [ "${b64}" != - ] || fail "content ${name}"
  printf '%s' "${b64}" | base64 -d >"${prep}/${name}" \
    && chown 0:0 "${prep}/${name}" && chmod 0644 "${prep}/${name}" \
    && [ "$(sha "${prep}/${name}")" = "${new}" ] || fail "prep ${name}"
  writes="${writes} ${name}"
done <<<"${FILES}"

stage=""
if [ "${NEW_BUNDLE}" != "$(cat "${MARKER}" </dev/null)" ]; then
  [ "$(cat "${MARKER}" </dev/null)" = "${OLD_BUNDLE}" ] || fail bundle-marker
  [ "$(tree_sums /opt/pokeclip-media "${C}/mediamtx.yml.tmpl")" \
    = "${OLD_SUMS}" ] || fail bundle-baseline
  stage="$(mktemp -d)" || fail stage
  docker pull --quiet "${NEW_BUNDLE}" </dev/null >/dev/null || fail pull
  cid="$(docker create "${NEW_BUNDLE}" /bundle-not-run </dev/null)" \
    || fail create
  docker cp "${cid}:/bundle/." "${stage}/" </dev/null
  rc=$?
  docker rm "${cid}" </dev/null >/dev/null
  [ "${rc}" -eq 0 ] || fail copy
  [ "$(tree_sums "${stage}" "${stage}/mediamtx.yml.tmpl")" = "${NEW_SUMS}" ] \
    || fail bundle-new
  echo "BUNDLE CHANGE"
else
  echo "BUNDLE SAME"
fi

if [ -n "${sidecar}" ]; then
  start="${stage:-/opt/pokeclip-media}/bin/start-media.sh"
  [ -f "${start}" ] || fail check-env-line
  fn="$(sed -n '/^check_env_line() {$/,/^}$/p' "${start}" </dev/null)" \
    || fail check-env-line
  # 0 = 이름이 있음 · 1 = 이름이 없음 · 그 밖 = 읽기 실패.
  grep -q check_env_line "${start}" </dev/null
  named=$?
  if [ -n "${fn}" ]; then
    { eval "${fn}" && declare -F check_env_line >/dev/null; } \
      || fail check-env-line
    check_env_line "${C}/sidecar.env" PLAYBACK_ROUND_KEYS=true \
      PLAYBACK_ROUND_KEYS=false || fail sidecar-env
    echo "KEEP etc__pokeclip-media__sidecar.env"
  elif [ -z "${stage}" ] && [ "${named}" -eq 1 ]; then
    # 묶음이 그대로이고 지금 시작 스크립트에 이 판정이 아예 없다 — 막히지
    # 않는다. 이름은 있는데 함수를 못 꺼냈으면 아래 fail 이다.
    echo "KEEP etc__pokeclip-media__sidecar.env unchecked"
  else
    fail check-env-line
  fi
fi
if [ -z "${writes}" ] && [ -z "${stage}" ]; then
  rmdir "${prep}"; echo "INPLACE NOTHING"; exit 0
fi

systemctl stop pokeclip-media </dev/null || fail "stop stopped"
echo "UNIT_STOPPED $(systemctl is-active pokeclip-media)"
bk="${C}/rollback-v${OLD_VERSION}-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 0700 "${bk}" || fail "backup stopped"
for name in ${writes}; do
  path="/${name//__//}"
  if [ -e "${path}" ]; then
    cp -p "${path}" "${bk}/${name}" || fail "backup stopped"
  else
    : >"${bk}/${name}.absent" || fail "backup stopped"
  fi
done
if [ -n "${stage}" ]; then
  cp -a "${BIN}" "${bk}/bin" && cp -p "${C}/mediamtx.yml.tmpl" "${bk}/" \
    && cp -p "${MARKER}" "${bk}/" || fail "backup stopped"
fi
echo "BACKUP ${bk}"

for name in ${writes}; do
  mv -f "${prep}/${name}" "/${name//__//}" || fail "write stopped"
done
rmdir "${prep}"; prep=""
if [ -n "${stage}" ]; then
  new_root="$(mktemp -d /opt/pokeclip-media/.bin-new.XXXXXX)" \
    && install_tree "${stage}/bin" "${new_root}/bin" 0755 0755 \
    && install_tree "${stage}/bin/lib" "${new_root}/bin/lib" 0755 0644 \
    && install -o root -g root -m 0644 "${stage}/mediamtx.yml.tmpl" \
      "${new_root}/mediamtx.yml.tmpl" \
    && [ "$(tree_sums "${new_root}" "${new_root}/mediamtx.yml.tmpl")" \
      = "${NEW_SUMS}" ] || fail "bundle-install stopped"
  rm -rf -- "${BIN}" && mv "${new_root}/bin" "${BIN}" \
    && mv -f "${new_root}/mediamtx.yml.tmpl" "${C}/mediamtx.yml.tmpl" \
    && printf '%s\n' "${NEW_BUNDLE}" >"${MARKER}" || fail "bundle-swap stopped"
  rmdir "${new_root}"; new_root=""; rm -rf -- "${stage}"; stage=""
  echo "BUNDLE INSTALLED"
fi
case "${writes}" in
  *etc__systemd__*)
    systemctl daemon-reload </dev/null || fail "daemon-reload stopped" ;;
esac
echo "WRITE OK"
systemctl start pokeclip-media </dev/null || fail "start stopped"
sleep 5
state="$(systemctl is-active pokeclip-media </dev/null)"
echo "UNIT ${state}"
[ "${state}" = active ] || fail "unit stopped"
echo "INPLACE OK"
SH
```

이 기계에서 `inplace.sh` 를 만듭니다. 머리에는 옛 판 · 옛 · 새 묶음 ·
해시 목록과 허용 집합 파일의 「이름 · 옛 해시 · 새 해시 · 내용」 줄이
들어갑니다. 내용(base64)은 옛 · 새가 다른 파일과 `sidecar.env` 에만
싣습니다.

```shell
# 없는 파일은 - 다. echo - 는 zsh 에서 빈 줄이라 printf 로 낸다.
sha_of() {
  if [ -f "$1" ]; then shasum -a 256 "$1" | cut -d' ' -f1
  else printf '%s\n' -; fi
}
env_of() { sed -n "s/^$1=//p" "$2/etc__pokeclip-media__media.env"; }
{
  printf '%s\n' "exec /bin/bash -s -- \"\$@\" <<'B'"
  printf 'OLD_VERSION=%s\n' \
    "$(env_of MEDIA_CONFIG_VERSION "${work}/render-old")"
  printf 'OLD_BUNDLE=%s\n' "$(env_of MEDIA_BUNDLE_IMAGE "${work}/render-old")"
  printf 'NEW_BUNDLE=%s\n' "$(env_of MEDIA_BUNDLE_IMAGE "${work}/render-new")"
  printf "OLD_SUMS='%s'\n" "$(cat "${work}/bundle-old.sums" 2>/dev/null)"
  printf "NEW_SUMS='%s'\n" "$(cat "${work}/bundle-new.sums" 2>/dev/null)"
  printf "FILES='\n"
  for f in "${work}/render-new"/etc__pokeclip-media__* \
    "${work}/render-new"/etc__systemd__system__pokeclip-media.service*; do
    n="${f##*/}"
    o="$(sha_of "${work}/render-old/${n}")"
    h="$(sha_of "${f}")"
    b=-
    if [ "${o}" != "${h}" ] \
      || [ "${n}" = etc__pokeclip-media__sidecar.env ]; then
      b="$(base64 <"${f}" | tr -d '\n')"
    fi
    printf '%s %s %s %s\n' "${n}" "${o}" "${h}" "${b}"
  done
  printf "'\n"
  cat "${work}/inplace-body.sh"
  printf 'B\n'
} >"${work}/inplace.sh"
# inplace.sh 는 첫 줄(exec … <<'B')과 끝 줄(B) 사이가 heredoc 이라 그대로
# bash -n 하면 본문을 보지 않는다. 앞뒤 줄을 떼고 본다.
bash -n "${work}/inplace-body.sh" \
  && sed '1d;$d' "${work}/inplace.sh" | bash -n && echo SYNTAX OK
only_old   # 빈 출력 — STOP only-old 가 나오면 inplace.sh 를 보내지 않는다
wc -c "${work}/inplace.sh"   # 결과 기록에 옮긴다
# 크기 시험용 — 첫 줄 true 뒤에 inplace.sh 전부를 주석으로 단다(더 크다).
{ printf 'true\n'; sed 's/^/# /' "${work}/inplace.sh"; } \
  >"${work}/size-probe.sh"
wc -c "${work}/size-probe.sh"
```

*   **SSM 인자 크기:** `inplace.sh` 는 바뀐 파일 내용을 base64 로 실어
    커집니다. 보내기 전에 위 `wc -c` 값을 기록하고, 아래 「제자리 전환
    걸음」 2 에서 크기 시험(`size-probe.sh`)을 먼저 보냅니다.
*   `size-probe.sh` 는 상자에서 `true` 만 하고 아무것도 쓰지 않습니다.
    나머지 줄은 `inplace.sh` 의 줄마다 `# ` 를 붙인 주석이라, 크기와
    따옴표 · 줄바꿈의 꼴이 `inplace.sh` 이상입니다. 이것이 받아지면
    `inplace.sh` 도 크기로 거절되지 않습니다.
*   `send-command` 가 크기로 거절하면 상자에서는 아무것도 돌지 않습니다.
    그때는 나눠 보내지 말고 멈춰 kty 에게 회부합니다. SSM 의 상한 값은 이
    런북에서 확인하지 않았습니다.

### 제자리 전환 걸음

1.  「제자리 전환 선행」 1–5 를 끝냅니다(렌더본 내용 diff 의 kty 승인 ·
    묶음이 바뀌면 묶음 승인까지).
    발행 목록이 찬 구성이면 이 걸음 바로 뒤에 위 「계약9 회차 확인」 1 · 2
    를 봅니다. 통과하지 않으면 2 로 가지 않습니다.
1.  크기 시험 → 전환입니다(둘 다 실행 직전 승인). 위 만드는 블록에서
    `SYNTAX OK` 가 나오고 `STOP only-old` 가 없어야 여기로 옵니다.
    *   `media_ssm "${a_key}" "${work}/size-probe.sh"` → 끝 줄이 `SSM
        Success` 여야 합니다. 거절 · 실패면 `inplace.sh` 를 보내지
        않고 kty 에게 회부합니다(위 「SSM 인자 크기」).
    *   `media_ssm "${a_key}" "${work}/inplace.sh"`. 줄마다 읽는 법은 위
        「제자리 전환 상자 스크립트」 입니다. `BACKUP <경로>` 줄을 결과
        기록에 옮깁니다. `INPLACE FAIL … stopped` 면 바로 아래 원복으로
        갑니다. `stopped` 가 없는 `INPLACE FAIL` 은 상자가 그대로이므로
        원인을 고쳐 다시 만듭니다. 고치지 않고 접으면 아래 「stop 전에
        포기할 때」 입니다.
1.  기동 판정 → 5분 뒤 관찰 판정(인자는 그 구성의 서비스 집합 — 위 「정상
    교체 2」 와 같은 기준) → (발행 목록이 찬 구성이면) M-B → 루프백
    차례입니다. 응답기 모드면 루프백은 `loopback-pp.sh` 입니다(위 「정상
    교체 3」). 발행 목록이 찬 구성이면 M-B 와 질의 재확인(위 「계약9 회차
    확인」 3)도 이 걸음의 판정이고, **`M-B OK` 가 나와야 루프백을
    보냅니다.** 루프백 뒤의 종료 감시도 M-B 만 봅니다(위 「계약9 회차 확인」
    3 — 퇴역 상자가 없어 M-A 없음).

    ```shell
    media_ssm "${a_key}" "${work}/start-check.sh" <서비스 집합>
    media_ssm "${a_key}" "${work}/watch-check.sh" <MAINPID> <WATCH FROM>
    # 발행 목록이 찬 구성만. M-B OK 가 아니면 루프백으로 가지 않는다.
    media_ssm "${a_key}" "${work}/lifecycle-check.sh" <전환 시각 — epoch 초>
    # 옛 모드(ingest_auth_image 빈 구성)면 loopback-pp.sh 대신 loopback.sh
    # (passphrase 없음 — 위 「정상 교체 3」).
    media_ssm "${a_key}" "${work}/loopback-pp.sh" <시험 ingestStreamId>
    ```

    *   **실패 갈래:** 기동 판정 · 관찰 판정 · 루프백 · M-B 가운데 하나라도
        통과하지 않으면 진행하지 않습니다. 기록한 `BACKUP` 경로를 명시해 아래
        「제자리 전환 원복」 을 하고, 판정 줄 그대로 kty 에게 보고합니다.
        `INPLACE FAIL unit stopped` 도 같은 갈래입니다. 원복 전 확인은 그
        절 머리에 있습니다.

1.  기록: 이월 기록(K11 형식)에 백업 경로 · 옛 판 → 새 판 · 태그 · 묶음
    다이제스트 · 어긋남(위 「기록과 실물의 어긋남」)을 남깁니다.
1.  (kty 선택) 재부팅 대조 — 재부팅 뒤 바꾼 파일의 해시가 새 렌더본과
    같은지, 기동 판정이 다시 통과하는지 봅니다.
1.  작업 디렉터리의 `render-*` · `bundle-*` 는 아래 「정리」 에서 함께
    지웁니다.

**stop 전에 포기할 때:** 크기 거절 · `stopped` 없는 `INPLACE FAIL` ·
`baseline` 에서 kty 가 포기를 고른 경우처럼 상자를 바꾸기 전에 전환을
접으면, 상자는 옛 구성 그대로입니다. 그런데 선행 5 의 apply 로 태그
`ConfigVersion` 과 tfvars 는 이미 새 판입니다. tfvars 의 `config_version` ·
다이제스트를 옛 값으로 되돌려 apply 합니다(plan 범위는 「제자리 전환」 태그
한 줄 · 승인 — 아래 원복 절의 같은 줄과 같음). 상자 원복 · 재기동은 하지
않습니다.

### 제자리 전환 원복

**원복 전 확인:** 발행 목록이 찬 구성(옛 · 새 어느 쪽이든)이면 먼저 위
「계약9 회차 확인」 1 · 2 를 봅니다. 원복도 사이드카를 재기동하기 때문입니다.
통과하지 않으면 원복하지 않고 그 갈래를 따릅니다. 다만 Q3 > 0 의 「기다림」
은 게이트가 켜진 사이드카(M-B 통과)가 떠 있을 때만입니다. 전환이 실패해
그런 사이드카가 없으면 기다리지 않고 kty 에게 회부합니다.

기록해 둔 `BACKUP` 경로를 **명시해서** 되돌립니다. 「가장 최근 백업」 을
자동으로 고르지 않습니다 — 백업이 여럿이면(2026-11-04 의
`rollback-v1-…` 등) 틀린 판을 고를 수 있습니다. 순서는 stop → 복원 →
daemon-reload → start → 기동 판정입니다.

```shell
cat > "${work}/inplace-rollback.sh" <<'SH'
exec /bin/bash -s -- "$@" <<'B'
set -u
readonly C=/etc/pokeclip-media
readonly BIN=/opt/pokeclip-media/bin
readonly MARKER=/var/lib/pokeclip-media/bundle-installed
fail() { echo "ROLLBACK FAIL $1"; exit 2; }
[ "$#" -eq 1 ] || fail usage
bk="$1"
case "${bk}" in *..*) fail usage ;; "${C}"/rollback-v*) ;; *) fail usage ;; esac
[ -d "${bk}" ] && [ ! -L "${bk}" ] || fail no-backup
systemctl stop pokeclip-media </dev/null || fail stop
for f in "${bk}"/*; do
  name="${f##*/}"
  case "${name}" in
    bin | mediamtx.yml.tmpl | bundle-installed) ;;
    *.absent)
      gone="${name%.absent}"
      rm -f -- "/${gone//__//}" || fail "remove ${name}" ;;
    *) cp -p "${f}" "/${name//__//}" || fail "copy ${name}" ;;
  esac
done
if [ -d "${bk}/bin" ]; then
  rm -rf -- "${BIN}" && cp -a "${bk}/bin" "${BIN}" \
    && cp -p "${bk}/mediamtx.yml.tmpl" "${C}/mediamtx.yml.tmpl" \
    && cp -p "${bk}/bundle-installed" "${MARKER}" || fail bundle
fi
systemctl daemon-reload </dev/null || fail daemon-reload
systemctl start pokeclip-media </dev/null || fail start
sleep 5
echo "RESTORED ${bk}"
echo "UNIT $(systemctl is-active pokeclip-media)"
B
SH
media_ssm "${a_key}" "${work}/inplace-rollback.sh" <BACKUP 경로>
```

*   그 뒤 기동 판정 · 관찰 판정을 다시 돕니다(되돌린 구성의 서비스 집합).
    발행 목록이 찬 구성이면 「계약9 회차 확인」 3 도 봅니다.
*   tfvars 의 `config_version` · 다이제스트도 되돌려 apply 합니다(plan
    범위는 「제자리 전환」 태그 한 줄).
*   되돌린 구성이 옛 모드면 위 「언제 쓰나」 의 옛 모드 단서를 따릅니다.
*   `ROLLBACK FAIL` 이면 유닛이 멈춘 채일 수 있습니다. 그 줄 그대로 kty
    에게 보고하고, 다음 동작은 kty 가 정합니다.

## O8a 당김 교체 — 발행 켠 B 로 옮길 때

O8 앞(kty 2026-11-03 — O8a 1–4 를 O8 앞으로 당김)에 옛 모드 A 를 응답기
모드 B 로 바꾸면서, 같은 교체에서 사이드카 계약9 발행(broadcast.started ·
ended → SNS FIFO → clip · chat 큐)을 시험 계정 하나에만 켭니다. NLB 공개
(O8a 5) · `8a` 묶음 · 주기 판정은 O8 뒤 그대로입니다. 실행자 · 승인 규칙은
이 런북 머리와 같고, 직전 점검은
[단계 직전 점검 런북](/infra/terraform/runbooks/stage-precheck.md) 의 「O8a
당김 교체」 행입니다.

**정상 교체와 다른 점 넷:**

1.  **루프백은 A 정지 뒤입니다**(정상 교체 3 은 전환 전). 루프백도 시험
    회차를 만들고, 게이트가 꺼진 A 사이드카가 그 회차를 닫으면 ended 가 영영
    적재되지 않습니다.
1.  **A 는 전환 직후 정지하고 유닛을 disable 합니다.** 인스턴스는 롤백
    대상으로 하루 남기되 띄워 두지 않습니다(정상 교체 8 의 「하루 관찰」 동안
    A 가 떠 있지 않음).
1.  **롤백은 발행 잔여 질의 Q1–Q3 를 본 뒤에만** 합니다(질의는
    [런타임 1단-가 런북](/infra/terraform/runbooks/runtime-1a.md) 「발행 잔여
    질의」 · 순서는 아래 「O8a 당김 — 롤백」).
1.  **발행 목록 계정이 송출하는 동안 운영 감시(M-B · M-A)를 돕니다**(아래
    「운영 감시」).

**게이트 공존 규칙:** 같은 DB 에 붙은 사이드카는 모두 게이트가 켜져 있어야
합니다 — media-host README 「계약9 발행」. 꺼졌을 수 있는 사이드카(옛 모드 ·
빈 목록 · 기동 판정 전)를 DB 에 붙이기 전에는 아래 송출 금지 구간을 열고
Q1 = 0 · `rounds` ≥ 1 을 봅니다(시험 회차가 생기기 전이면 Q4 0 행).

**송출 금지 구간:** kty · 시험 계정을 포함해 팀 IP 에서 송출하는 누구도
송출하지 않습니다(O8a 5 전이라 NLB 인바운드는 팀 IP 뿐). 기계 차단이 없는
사람의 규칙이라 시작 · 끝 시각을 `date` 출력으로 적고, 구간 안의 판정
직전에 B 의 `PATHS` 를 한 번 더 봅니다.

*   (ㄱ) 걸음 8(B 기동 판정) – 12(A 정지 · 차단).
*   (ㄴ) 걸음 32(목록 `*` 인 C 로 교체)의 Q1 확인부터 B 정지 · 차단까지.
*   (ㄷ) 롤백 · 발행 끄기 교체의 Q1 확인부터 옛 상자 정지 · 차단까지.

### O8a 당김 교체 걸음

| 걸음 | 동작 | 통과 | 실패 시 · 멈춤 |
|---|---|---|---|
| 0-a | 읽기 확인 — A 사이드카 다이제스트가 M6 하한 이상인지(M6 정산 · 회차 닫힘 스캔을 도는 판인지) | 기록 | 다르면 kty 보고. 하한 아래면 롤백에 「한 창 절차 — A 를 다시 띄울 때」 조건을 더함(아래 롤백 표) |
| 0-b | 런타임 출력 `broadcast_topic_arn` 실재(이름만 · 값 가림) | 출력 이름 있음 | 없으면 런타임 재적용 필요 → kty 회부 |
| 0-c | 지금 적용된 역할 정책의 Sid 목록 | Sid 목록 기록 | 걸음 6 plan 기대를 이 결과로 정함 |
| 0-d | 상자 root `cli_history` 꺼짐 | 꺼짐 | 켜져 있으면 kty 보고(비밀 동작 전) |
| 0-e | Q4 기준선 | Q4 0 행 | 0 이 아니면 출처 확인 뒤 kty 보고 |
| 0-f | P47 진입 경로 — 미디어 ALB 의 scheme · 리스너 · SG 출발지 · CloudFront 원본 검증 상태 · NLB SG 가 팀 IP 송출을 받는지 | 경로 기록(값 가림) | 팀 IP → NLB 송출 경로가 없으면 걸음 14 · 15 는 kty 회부(대안 설계 안 함) |
| 0-g | 사이드카 시험의 「게이트 꺼짐이면 ended 생략」 사례 이름 | 사례 이름 | 없으면 POK-196 세션 통지(차단 아님) |
| 0-h | POK-196 세션 통지 · 확인 — 발행 켜기 착수 · 게이트 공존 규칙 · 중복 여부 · 후속(사이드카 강등 근본 수정) 요청 | 회신(후속 수락 여부 포함) | 중복이면 kty 회부. 후속 거절이면 kty 회부(O8a 5 개통 조건이라) |
| 0-i | A 차단 수단(아래 「A 차단」) | disable 만 · disable + mask 중 하나로 확정 | 판정 불가면 disable + mask(보수 — 롤백 ④ 에서 둘 다 되돌림). mask 가 실패하면 아래 「A 차단」 대로 kty 회부 |
| 1–3 | PR 머지 → 응답기 이미지(재빌드 권장 · arm64) → 직전 점검(`O7e` 묶음 · `sns:Publish` 손 판정 · 2-3 · 이벤트) | 허용 · PASS · 예상 밖 이벤트 0 | 2 실패면 432e3495 사용. 3 이 거부 · 변경이면 멈추고 kty 보고 |
| 4 | A 에서 resolve 미리 부르기(media-host README 「O8a 2 전 필수 조건」 5) | `code=200` | 실패면 4C 원인 해소 전 진행 금지 |
| 5–6 | tfvars(응답기 다이제스트 · `stream_key_resolve_url` · `lifecycle_publish_streams = [<시험 ID>]` · `instances.b`) → plan 범위(위 표 「O8a 당김 B 기동」) → apply | 기대와 일치 | 기대 밖 차분이면 apply 안 함 |
| 7 | (관찰) A 태그 저널 `lifecycle_disabled reason=no_topic` 건수 — 공유 역할 정책이 A 에도 걸렸지만 env 가 없어 발행하지 않음 | 기록 | — |
| 8 | B 기동 판정(`start-check.sh ingest-auth,janitor,mediamtx,nginx,sidecar` · 관찰 판정) · 응답기 `ingestauth_started` 의 `"auth_channel":"resolve-token"` · `end_signal_disabled` 0 · `lifecycle-check.sh <걸음 8 시각>` · Q4 · 사이드카 컨테이너 id 기록. **(ㄱ) 시작** | `MEDIA START OK` · `MEDIA WATCH OK` · `M-B OK` · Q4 0 행 | `lifecycle_disabled` 면 B 에 송출하지 않고 B 를 고쳐 재교체. outbox 행이 없어 A 는 그대로 안전 |
| 9–11 | 정상 교체 4(A · B `PATHS 0`) → 5(ingest) → 6 · 7(http · 일관성) | 정상 교체와 같음 | 9 가 0 이 아니면 멈춤. 10 · 11 의 되돌림 = 전환 역적용(outbox 0 행이라 조건 없음) |
| 12 | A 정지 · 차단 — A 업로드 적체 0(「한 창 절차 7′」 의 앞 조건) → A `PATHS 0` → `stop.sh` → `stopped-check.sh` → `disable.sh` → `blocked-check.sh disabled` → Q4. **(ㄱ) 끝** | `M-A OK` · Q4 0 행 — DB 에 붙은 사이드카 = B 하나 | 적체가 안 빠지면 송출하지 않고 기다림 · 창을 넘기면 kty 보고 |
| 13 | M-A · M-B · **`Q3_all` = 0**(시험 ID 의 열린 회차 — `started_at` 조건 없음) → B 루프백 `--passphrase` · 상자 안 익명 SRT 거부. 끝나면 종료 감시 | `LOOPBACK OK` · 익명 거부 | 실패면 아래 롤백 표 「걸음 12 뒤」 |
| 14 | O8a 3 거부 셋(B `PATHS 0` 일 때 — 잘못된 passphrase · 익명 · 다른 사용자 경로) | 셋 다 거부 · outbox 행 수 변화 0 | 0-f 에서 송출 경로가 없었으면 kty 회부. 하나라도 수락되면 즉시 kty 보고 · O8 금지 · 그 회차도 시험 회차로 종료 판정 |
| 15 | 유지 시험 송출(팀 IP → NLB → B · O8a 3 정상 키 · O8a 4 출발지 보존) — 22 끝까지. 송출 감시 시작 | B `PATHS` 1 | 0-f 에서 송출 경로가 없었거나 붙지 않으면 kty 회부 |
| 16 | 상자 루프백 `…/<시험 ID>/index.m3u8?cookieCheck=1` — Bearer 없이 · 있게 | 401 · 200 쌍 | 401 아니면 실패 → O8 금지 · kty 보고 |
| 17 | started 판정 — 시험 회차 sequence 1 `published` · B `lifecycle_published` · clip `broadcast.intake.handled` · chat `LINK_REFUSED` · DLQ 0(상한 5분) | 전부 | `published` 아님 → kty 보고 · 원인 수정 뒤 `published` 확인(송출은 유지) |
| 18–20 | O7f 10 · 11(7-R) · 12 — [엣지 시험 런북](/infra/terraform/runbooks/edge-tests.md) · [엣지 비밀 런북](/infra/terraform/runbooks/edge-secrets.md) 「T6b 대체 절차」 | 런북 판정 | 18 실패면 kty 보고. 19 는 재판정이 허용 아니면 시작 안 함 · 복원 실패면 즉시 kty 보고(영향 = `/live` 만). 20 은 실패 항목 kty 보고 |
| 21 | O8a 3 팀 IP 밖 송출 거부 | 거부 | 허용되면 즉시 보고 · O8 금지 |
| 22 | P47-가 — 0-f 결과에 따른 진입 경로에서 유지 송출 중 부하(media-host README 「O8a 2 전 필수 조건」 2). 새 송출 수락은 여기서 재지 않음 — 같은 시험 ID 는 `overridePublisher: no` 로 거부되고, 기존 HLS 의 200 은 수락 증거가 아님 | 도달률 95% 이상(아래) · `budget_exhausted` 수 · 유지 송출 끊김 여부 · auth 지표 기록 | 외부에서 Bearer 없이 닿는 경로가 없으면 「외부 진입 경로 없음」 과 증거(scheme · SG · 직접 연결 실패)를 보고하고, 상자 안 부하는 kty 결정 없이 하지 않음. 응답기 장애가 보이면 부하 중단 · 보고. 유지 송출이 끊기면 그것도 결과 — 종료 감시로 바꾸고 걸음 24 로 |
| 23 | **송출 끄기 직전** M-B · M-A → kty 가 유지 송출을 끈다(시각 기록) | `M-B OK` · `M-A OK` | M-B 불통과면 끄지 않음 → 「운영 감시」 (가). M-A 불통과면 A 차단 재적용 뒤 다시 판정 |
| 24 | 1차 종료 대기 — 종료 감시 · Q1 = Q2 = Q3 = 0 · `rounds` ≥ 1 · ended `lifecycle_published` · clip ended 처리 · clip 기록 종료(상한 30분) | 전부 | 상한을 넘기면 kty 보고 · A 재기동 · B 정지 · 발행 끄기 금지. 강등 · 부활은 「운영 감시」 (나). **Q1 > 0 ∧ Q3 = 0 = 자동 복구 경로 없음 → kty 회부(멈춤 자리)** |
| 25 | 1단-나 ② 9 · 10([런타임 런북](/infra/terraform/runbooks/runtime-1a.md)) — ② 9 는 이 절의 증거와 대조표로 갈음 | 런북 판정 | 갈음 불가 항목이 재기동 · 송출이면 감시 규칙 적용 |
| 26 | P47-나 — 앞 회차 전부 닫힌 뒤 같은 진입 경로 · 같은 부하에서 루프백으로 새 송출 수락 지연. 직전 M-B · M-A, 끝나면 종료 감시 | 도달률 95% 이상 · 새 송출 수락 지연 · `budget_exhausted` 수 → 걸음 22 결과와 함께 O8 전 kty 보고 | 새 송출 장애가 보이면 부하 중단 · 보고 |
| 27 | 최종 종료 판정 — 시험 회차 전부(Q1 = Q2 = Q3 = 0 · `rounds` ≥ 1 · ended published · clip 처리) · 감시 기록(상한 30분) | 전부 | 걸음 24 와 같음(Q1 > 0 ∧ Q3 = 0 = kty 회부) |
| 28–29 | 종료 뒤 시험 · `secret-count --passphrase` box · ssm | 런북 판정 · 둘 다 0 | 29 가 0 아니면 정리 · 보고 |
| 30 | 하루 관찰 뒤 A 회수 — `blocked-check.sh` · A 업로드 적체 0 · Q1 = 0 · `rounds` ≥ 1 | `M-A OK` · 회수 완료 | 조건 미충족이면 보류. A 가 되살아났으면 정지 · 차단 뒤 Q1 · Q3(「운영 감시」 M-A 불통과) |
| 31 | O8 | O8 런북 | — |
| 32 | (O8a 5 직전 · D4) 목록 `*` 인 C 로 교체 — (ㄴ) 시작 → Q1 = Q2 = Q3 = 0 · `rounds` ≥ 1 → C 기동 · `lifecycle-check.sh`(C) → 전환 → **B 정지 · disable · `blocked-check.sh`(B)** → (ㄴ) 끝 → 하루 뒤 회수 직전 `blocked-check.sh`(B) 다시 | `M-B OK`(C) · `M-A OK`(B) | C 가 꺼진 채 뜨면 C 를 즉시 정지 · Q1 확인 · 금지 유지. 후속 수정이 배포 전이면 C 도 운영 감시 대상 |

*   **시험 회차** = 머리 세션의 `stream_id` 가 시험 ID 이고 `started_at` 이
    걸음 8 시각 이후인 회차 전부입니다. 회차 수를 고정하지 않습니다 — 루프백
    회차가 끝나고 300초 안에 유지 송출이 붙으면 같은 회차로 이어집니다.
*   **`rounds` ≥ 1**(발행 잔여 질의의 시험 회차 수): 걸음 13 뒤에 Q1 · Q2 ·
    Q3 를 판정으로 쓰는 자리는 모두 이 조건을 함께 봅니다. 시험 ID · 시각
    입력이 틀려 시험 회차를 하나도 잡지 못하면 Q1 · Q2 · Q3 가 전부 0 으로
    나오기 때문입니다. 0 이면 멈추고 입력을 다시 봅니다. 걸음 13 루프백이
    회차를 만들지 못하고 실패한 경우만 0 이 맞고, 그때는 걸음 13 전과 같이
    Q4 로 봅니다(아래 롤백 표 「걸음 12 뒤」 행).
*   **도달률**(걸음 22 · 26)은 B nginx live 접근 줄 수 · 응답기 read 판정
    줄 수를 보낸 요청 수로 나눈 값이고, 95% 이상이어야 합니다. 미달이면 그
    측정은 무효입니다 — 부하 요청이 엣지(CloudFront 함수 · 서명)에서 막히면
    응답기 부하로 재지 못하기 때문입니다.
*   걸음 0-f · 0-h · 14 · 15 · 22 · 24 · 27 의 「kty 회부 · 보고」 와 아래
    「Q1 > 0 ∧ Q3 = 0」 은 대안을 고르지 않는 **멈춤 자리**입니다.

### O8a 당김 — 운영 감시

사이드카는 재기동 때 게이트 준비(`privilege_check` · `privilege` ·
`sns_client` · `assembly`)가 실패하면 꺼진 게이트로 정산을 계속합니다(media-host
README 「계약9 발행」 의 재기동 강등). 후속 수정이 배포될 때까지 발행 목록
계정이 송출하는 동안 아래를 돕니다(kty 2026-11-03 (다)).

*   **M-B**(그 사이드카 게이트가 지금 켜져 있음 — `lifecycle-check.sh`):
    통과는 **마지막 기동 시각 뒤** `lifecycle_started` 1줄 ∧ 그 뒤
    `lifecycle_disabled` 0 입니다. 마지막 기동 시각은 사이드카 컨테이너의
    `State.StartedAt` 이고, 강등을 고쳐 다시 띄웠으면 감시의 기준 시각이 그
    시각으로 넘어갑니다.
    *   이력(걸음 8 이후 `lifecycle_started` · `lifecycle_disabled` 건수 ·
        `RestartCount` · 수동 재기동 수 · 컨테이너 id)은 기록만 하고 판정에
        쓰지 않습니다. 강등이 한 번 있었다고 복구 뒤에도 불통과로 남지 않게
        하기 위해서입니다.
    *   줄을 남기기 전에 죽은 기동(DB 연결 · 기동 단계 실패)은 정산을 돌기
        전에 끝난 것이라 판정에서 뺍니다. 다만 지금 떠 있는 사이드카가 없거나
        갓 떠서 줄이 아직 없으면 `LIFECYCLE CHECK FAIL no-sidecar` · `M-B NOT
        OK` 입니다 — 1분 뒤 다시 보고, 그래도 같으면 강등으로 다룹니다.
*   **M-A**(옛 상자 정지 유지 — `blocked-check.sh <disabled|masked>`): 유닛
    `inactive` · 프로젝트 컨테이너 0 · 유닛이 그 차단 상태입니다.
*   **주기:**
    *   **송출 감시** — 시험 ID 송출이 살아 있는 동안 **5분마다** M-B · M-A ·
        B `PATHS` 를 봅니다. 5분은 창 안 갈래의 회차 닫힘(마지막 조각
        끝부터 재접속 창 300초 + 여유 60초 — 아래 상한 근거)보다 짧은
        간격입니다. 창을 기다리지 않고 곧바로 닫히는 4D 종료 신호 · 정지
        표식 갈래는 이 주기로 막지 못하고, 걸음 23(송출 끄기 직전)의 M-B
        판정으로만 막습니다. `PATHS` 가 0 이 되면(송출 끊김) 곧바로 종료
        감시로 바꿉니다.
    *   **송출 끄기 직전**(걸음 23) — 통과해야 끕니다.
    *   **종료 감시** — 송출이 끝난 뒤(끈 경우 · 끊긴 경우 · 루프백 13 · 26
        뒤 모두) **2분마다** M-B · M-A 를 봅니다. **끝나는 조건은 회차 닫힘이
        아니라 그 회차들의 완결**입니다 — Q1 = Q2 = Q3 = 0 ∧ `rounds` ≥ 1
        ∧ ended `lifecycle_published` ∧ clip ended 처리. 그 전에 다음 송출이
        붙으면 송출 감시로 돌아갑니다.
    *   발행 목록 계정이 송출하는 동안은 언제든 같은 주기입니다(걸음 30 의
        하루 관찰 · O8 뒤 O8a 5 전 포함). 후속 수정 배포 전까지입니다.
*   **강등 · 부활을 발견하면:**
    *   (가) 송출이 살아 있음 — 송출을 유지한 채 B 사이드카만 다시 띄우고
        (`sidecar-restart.sh` · 실행 직전 승인 · 수동 재기동으로 기록) M-B 를
        다시 봅니다. 다시 강등되면 `reason` 을 kty 에게 보고하고 **송출을
        끄지 않습니다** — 끄면 강등된 사이드카가 회차를 닫습니다.
    *   (나) 송출이 이미 끝남 — 곧바로 다시 띄워 M-B 를 본 뒤 Q1 · Q3 를
        봅니다. Q3 > 0(회차 열림)이면 통과한 사이드카가 닫으므로 종료 감시를
        이어 갑니다. **Q1 > 0 ∧ Q3 = 0 이면 자동 복구 경로가 없습니다**(닫히고
        전원 ended 인 회차는 다시 평가되지 않음) — kty 회부(멈춤 자리)이고, 그
        방송의 손 복구는 kty 결정입니다.
    *   M-A 불통과(옛 상자가 되살아남) — 곧바로 정지 · 차단을 다시 하고 Q1 ·
        Q3 를 봅니다(같은 두 갈래).
*   **사이드카만 다시 띄우는 근거(kty D7 = (ㄱ)):** media-host README 운영
    규칙 ① 은 재기동 · 정지를 유닛으로만 하라고 정합니다. 그 근거는 정지
    쪽입니다 — 사이드카만 멈추면 mediamtx · nginx 가 끝날 때 유닛이 compose
    전체를 다시 띄워 사이드카가 되살아납니다. 재기동은 엔진의 자동 재기동
    (`restart: on-failure`)과 같은 동작이라 형식상 예외일 뿐입니다. 이 예외는
    O8a 당김 시험에만 씁니다. 유닛 재기동은 MediaMTX 를 내려 송출을 끊으므로
    쓰지 않습니다.
*   **남는 위험:** 마지막 감시 뒤 · 회차 닫힘 전에 강등 재기동이 끼면 그
    방송 하나의 clip 기록이 live 로 남습니다(감시 사이 창). 근본 수정
    (ended 적재를 발행 게이트에서 떼어 강등 중에도 적재 · 발행 준비 재시도 —
    설계 진행 중)은 POK-301 아래 후속이고 O8a 5 개통 조건입니다.

**종료 대기 상한 30분의 근거**(사이드카 코드 수치):

*   회차 닫힘은 회차 **마지막 조각 끝**부터 재접속 창 300초 + 여유 60초가
    지난 뒤입니다(`closeSlack` · `closableRoundsSQL`). 스캔은 30초마다 돌지만
    그 안에서 도는 것이라 최소 대기를 늘리지 않습니다. **플러그인의 의도적
    종료 신호(4D) · 정지 표식 갈래는 창을 기다리지 않고 곧바로 닫힙니다** —
    그래서 송출을 끄기 직전(걸음 23)의 M-B 가 마지막 방어선입니다.
*   마지막 세션의 ended 는 ③ 착지 또는 정산 창 15분(그 세션 `ending_at`
    기준 · `SettleWindow`) 뒤라, ended 행 적재는 최악 약 21분 30초입니다.
*   발행은 2초 주기이고, 일시 실패면 1 · 2 · 4 · 8 · 16 · 32초(누적 63초) 뒤
    60초 간격입니다(`retryCap`). 실패가 한 번 끼면 약 22분 30초에
    `published` 입니다.
*   상한 30분은 그 위에 60초 간격 재시도 7회분을 여유로 둡니다. 넘기면 kty
    보고 — A 재기동 · B 정지 · 발행 끄기는 하지 않습니다.

### O8a 당김 — 상자 스크립트

출력은 판정 줄 · 건수 · 짧은 컨테이너 id 뿐입니다. `lifecycle_started` 줄은
주제 ARN 을 찍으므로 건수만 셉니다. 「준비」 의 `media_ssm` 으로 보냅니다.

**M-B**(`lifecycle-check.sh` — 인자: 걸음 8 시각의 epoch 초):

```shell
cat > "${work}/lifecycle-check.sh" <<'SH'
fail() { echo "LIFECYCLE CHECK FAIL $1"; exit 2; }
# count_msg <로그> <메시지> — 그 메시지 이름의 JSON 줄 수.
count_msg() {
  printf '%s\n' "$1" | awk -v m="\"msg\":\"$2\"" 'index($0, m) { n++ } END { print n + 0 }'
}
[ "$#" -eq 1 ] || fail usage
cid="$(docker ps -q --filter label=com.docker.compose.project=pokeclip-media \
  --filter label=com.docker.compose.service=sidecar)" || fail docker
[ -n "${cid}" ] || fail no-sidecar
meta="$(docker inspect --format '{{.RestartCount}} {{.State.StartedAt}}' "${cid}")" \
  || fail inspect
last="$(date -d "${meta#* }" +%s)" || fail started-at
history="$(journalctl -q -t pokeclip-media-sidecar --since "@$1" -o cat --no-pager)" \
  || fail journal
current="$(journalctl -q -t pokeclip-media-sidecar --since "@${last}" -o cat \
  --no-pager)" || fail journal
started="$(count_msg "${current}" lifecycle_started)"
disabled="$(count_msg "${current}" lifecycle_disabled)"
echo "SIDECAR_ID ${cid}"
echo "RESTART_COUNT ${meta%% *}"
echo "LAST_START ${last}"
for m in lifecycle_started lifecycle_disabled lifecycle_enqueued \
  lifecycle_published lifecycle_publish_retry lifecycle_backlog \
  lifecycle_abandoned lifecycle_resolve_failed; do
  echo "HISTORY_${m} $(count_msg "${history}" "${m}")"
done
echo "STARTED_SINCE_LAST ${started}"
echo "DISABLED_SINCE_LAST ${disabled}"
if [ "${started}" = 1 ] && [ "${disabled}" = 0 ]; then echo "M-B OK"; exit 0; fi
echo "M-B NOT OK"
exit 1
SH
```

**A 차단 · M-A**(`blocked-check.sh` — 인자: `disabled` 또는 `masked`):

```shell
printf 'systemctl disable pokeclip-media\n' > "${work}/disable.sh"
cat > "${work}/blocked-check.sh" <<'SH'
[ "$#" -eq 1 ] || { echo "M-A CHECK FAIL usage"; exit 2; }
state="$(systemctl show -P ActiveState pokeclip-media)" || state=unknown
enabled="$(systemctl is-enabled pokeclip-media 2>/dev/null)"
ids="$(docker ps -q --filter label=com.docker.compose.project=pokeclip-media)" \
  || { echo "M-A CHECK FAIL docker"; exit 2; }
running="$(printf '%s\n' "${ids}" | awk 'NF { n++ } END { print n + 0 }')"
echo "UNIT ${state}"
echo "RUNNING ${running}"
echo "ENABLED ${enabled:-unknown}"
if [ "${state}" = inactive ] && [ "${running}" = 0 ] && [ "${enabled}" = "$1" ]; then
  echo "M-A OK"; exit 0
fi
echo "M-A NOT OK"
exit 1
SH
```

*   정지는 「한 창 절차 7′」 의 `stop.sh` · `stopped-check.sh` 를 그대로
    쓰고, 그 뒤 `disable.sh` 를 보냅니다.
*   **A 차단 — disable 로 되는지:** 부트스트랩(유닛 `enable` 을 부르는 곳)은
    cloud-init 이 첫 부팅에 한 번만 돌립니다(`modules/media_host/user_data.tf`
    머리 주석). 그래서 disable 이 재부팅을 넘는다고 봅니다 — 걸음 0 에서
    상자의 cloud-init 실행 기록으로 확인합니다. 부팅마다 enable 이 다시 돈다고
    판정되면 mask 가 필요합니다. 다만 유닛 파일이 `/etc/systemd/system` 에
    직접 있어 `systemctl mask` 는 그 자리에 링크를 만들지 못해 실패할 것으로
    보입니다(상자 실측 전). 그때는 대안을 고르지 않고 kty 에 회부합니다.
*   롤백 ④ 에서 되돌릴 때는 `systemctl enable pokeclip-media`(mask 했으면
    `unmask` 먼저) 뒤 `systemctl start pokeclip-media` 입니다.

**사이드카만 다시 띄우기**(`sidecar-restart.sh` — 운영 감시 (가) · (나)만 ·
실행 직전 승인):

```shell
cat > "${work}/sidecar-restart.sh" <<'SH'
cid="$(docker ps -aq --filter label=com.docker.compose.project=pokeclip-media \
  --filter label=com.docker.compose.service=sidecar)" \
  || { echo "SIDECAR RESTART FAIL docker"; exit 2; }
[ -n "${cid}" ] || { echo "SIDECAR RESTART FAIL no-sidecar"; exit 2; }
docker restart "${cid}" >/dev/null || { echo "SIDECAR RESTART FAIL restart"; exit 1; }
echo "SIDECAR RESTARTED $(date +%s)"
SH
```

*   찍힌 시각을 수동 재기동 기록에 적고, 1분 뒤 `lifecycle-check.sh` 를
    돌립니다.

### O8a 당김 — 롤백

A 로 되돌리기입니다. O8a 5 전이라 옛 모드 A 복귀가 허용됩니다(옛 모드
단서 — 위 「언제 쓰나」).

| 시점 · 조건 | 순서 |
|---|---|
| 걸음 6–11(A 기동 중 · 송출 없음) · Q4 = 0 | 전환 역적용 → B 정지 · 회수 |
| 걸음 12 뒤 · Q1 = Q2 = Q3 = 0 · `rounds` ≥ 1(걸음 13 전이거나, 걸음 13 루프백이 회차를 만들지 못하고 실패했으면 — `LOOPBACK FAIL test-key` · `pull`, 인증 거부 · 연결 실패 — `rounds` 0 · Q4 0 행. 회차가 하나라도 생겼으면 `rounds` ≥ 1 이라 이 예외에 들지 않음) · B `PATHS 0` · 마지막 M-B 통과 | ① 송출 중지 확인 · **(ㄷ) 시작** ② Q1–Q3 ③ B 유닛 정지(인스턴스 유지 — 새 started 원천 제거) ④ A `enable`(mask 했으면 `unmask` 먼저) · `start` → A 기동 판정 ⑤ 전환을 A 로 → **(ㄷ) 끝** ⑥ B 회수는 하루 뒤. A 가 하한 아래면(걸음 0) 「한 창 절차 — A 를 다시 띄울 때」 조건을 더함 |
| Q1 > 0 ∧ Q3 > 0(회차가 아직 열림) | **A 를 띄우지 않습니다.** M-B 를 통과한 게이트 켜진 사이드카(B 재기동 뒤 통과, 또는 같은 설정의 C 가 기동 판정 통과)가 회차를 닫고 ended 를 발행한 뒤 위 순서. B 를 살리지 못하면 kty 보고 |
| Q1 > 0 ∧ Q3 = 0(회차 닫힘 · ended 없음) | **자동 복구 경로 없음 — kty 회부(멈춤 자리).** 롤백 · 교체 · 발행 끄기를 하지 않습니다 |
| Q2 > 0(SNS 재시도 중) | B 를 떠 있게 두고 원인(신원 정책 · 주제 정책 · SCP)을 kty 승인으로 고친 뒤(다음 재시도 — 최대 60초 뒤 — 에 `published`) 위 순서. outbox 행은 손대지 않습니다 |
| 발행만 끄기 | 걸음 12 뒤 행과 같은 조건. (ㄷ) = Q1 확인부터 B 정지까지 — ① 송출 중지 · 시작 ② Q1–Q3 ③ 빈 목록 상자 D 기동(목록은 user_data 라 상자 교체) ④ 전환 ⑤ B 정지 · 차단 → 끝. D 의 회차에는 started 가 없어 ended 도 필요 없습니다 |

## force

방송이 있는데 바꿔야 할 때입니다(설계 5-3 — kty 5차 허용 + 수동 복구).
B 는 「정상 교체」 2 · 3 으로 미리 띄워 둡니다. force 파일은 저장소 밖 kty
로컬 디렉터리(`${HOME}/.pokeclip-media-force/`, 0700)에 두고 복구가 끝날
때까지 지우지 않습니다.

**옛 모드 B 갈래:** B 가 옛 모드면 위 「언제 쓰나」 의 옛 모드 단서를
따르고, 순서는 아래 1(목록 저장) → README 「되돌리기 순서」 1(인바운드
축소 · 확인) → 아래 2 입니다. README 3 은 4 뒤에 확인합니다. A 가 응답기
모드면 진행 중 방송이 B 에 자동으로 붙지 않습니다 — 응답기 모드의 송출은
passphrase 를 싣고, 옛 모드 B 는 그 송출을 끊습니다(루프백 기본 갈래와 같은
까닭 · README 「운영 확인 스크립트」). 2 의 재연결 · 5 의 자동 복귀는 없고,
수동 복구는 연락 뒤 종료 확인(ended)으로 6 을 닫습니다. 인바운드를 다시
열지 않습니다(README 「되돌리기 순서」 · 위 옛 모드 단서).

```shell
force_dir="${HOME}/.pokeclip-media-force"; mkdir -p -m 0700 "${force_dir}"
stamp="$(date +%Y%m%dT%H%M%S)"   # 재개면 처음 값을 다시 넣는다(아래)
ended="${force_dir}/force-${stamp}-ended.txt"
[ -e "${ended}" ] || : > "${ended}"
cat > "${work}/paths-list.sh" <<'SH'
set -a; . /etc/pokeclip-media/media.env; set +a
/opt/pokeclip-media/bin/paths-list.sh
SH
```

`paths-list.sh` 는 표준 출력에 방송 목록(공개 식별자), 표준 오류에
`PATHS LIST <n>` 을 냅니다. 목록은 SSM 출력 원문 그대로 저장해야 하므로
`media_ssm` 대신 아래처럼 받습니다(`--output json | jq -j .` — 끝 줄바꿈을
더하지 않습니다).

```shell
force_list() {  # force_list <상자 키> <A|B>
  local id cid cmd_state out
  id="$(media_ids | jq -er --arg k "$1" '.[$k]')" || return 2
  jq -n --rawfile script "${work}/paths-list.sh" '{commands: [$script]}' \
    > "${work}/params.json"
  cid="$(aws ssm send-command --instance-ids "${id}" \
    --document-name AWS-RunShellScript --parameters "file://${work}/params.json" \
    --query Command.CommandId --output text)" || return 2
  while sleep 3; do
    cmd_state="$(aws ssm get-command-invocation --command-id "${cid}" \
      --instance-id "${id}" --query Status --output text)" || return 2
    case "${cmd_state}" in Pending | InProgress | Delayed) ;; *) break ;; esac
  done
  echo "SSM ${cmd_state}"
  [ "${cmd_state}" = Success ] || return 1
  out="${force_dir}/force-${stamp}-$2.txt"
  aws ssm get-command-invocation --command-id "${cid}" --instance-id "${id}" \
    --query StandardOutputContent --output json | jq -j . > "${out}"
  aws ssm get-command-invocation --command-id "${cid}" --instance-id "${id}" \
    --query StandardErrorContent --output text
  printf 'BYTES %s\n' "$(wc -c < "${out}" | tr -d ' ')"
}
```

*   `SSM Success` 가 아니면 파일을 쓰지 않습니다 — 실패한 표준 출력은 비어
    있어 「송출 중 0」 처럼 읽힙니다.
*   `BYTES` 가 24,000 에 닿으면(SSM 표준 출력 한도) 잘린 목록입니다. 그
    파일은 쓰지 않습니다(지웁니다). 놓쳐도 `force-diff.sh` 가 줄 수 · 끝
    줄바꿈으로 거부합니다.
*   표준 오류 줄의 `PATHS LIST <n>` 의 n 을 함께 적어 둡니다.
*   **`ended.txt` 는 없을 때만 빈 파일로 만듭니다.** `force-diff.sh` 는 A ·
    B · ended 세 파일이 모두 있어야 대조합니다(없으면 `FORCE FAIL input
    ended`). 첫 대조는 종료 확인이 0 건인 것이 보통이라 빈 파일이 필요합니다.
*   **재개**(셸을 다시 열어 이어 할 때)는 `ls "${force_dir}"` 로 처음
    `stamp` 를 찾아 다시 넣고 위 블록을 돌립니다 — 이미 적은 종료 확인을
    지우지 않고, A 파일 이름도 그대로 맞습니다. 새 `date` 값으로 돌리면 A
    파일이 없는 새 묶음이 됩니다.

| # | 동작 | 통과 · 중단 |
|---|---|---|
| 1 | `force_list <A> A` — A 의 진행 중 방송 **목록**을 저장(건수가 아니라 목록 — 수동 복구 대조용) | `PATHS LIST INCOMPLETE` 면 **force 중단** |
| 2 | **ingest 전환 먼저**(「정상 교체」 5 의 plan 범위 · apply — 4 의 재확인은 건너뜀). A 의 SRT 흐름은 등록 해제 지연(30초)이 끝날 때 연결 종료로 끊기고, 최대 30초 뒤 재연결로 B 에 붙습니다(옛 모드 B 갈래는 위 force 머리 참고) | NLB = B 1대 healthy |
| 3 | A 에서 `systemctl stop pokeclip-media`(compose 전체 정지 — `Restart=always` 여도 stop 은 정지 유지 · 실행 직전 승인). 지연이 끝나기 전에 흐름을 끊어 2 의 최대 30초를 줄입니다 | 유닛 `inactive` |
| 4 | http 전환 → 일관성 확인(「정상 교체」 6 · 7) | 네 그룹 B 하나 |
| 5 | 플러그인이 재시도 20회 안에 B 에 붙은 방송은 자동 복귀입니다(소진하면 플러그인이 출력을 해제 — 소진 시간은 미확인, T8b 에서 잼) — 옛 모드 B 갈래는 위 force 머리 참고 | — |
| 6 | `force_list <B> B` → 종료를 확인한 방송을 `force-<stamp>-ended.txt` 에 탭 구분 3열(`ingestStreamId` · 확인 시각 · 확인 경로)로 덧붙임 → `force-diff.sh` | `FORCE REMAINING 0` |

```shell
printf 'systemctl stop pokeclip-media\n' > "${work}/stop.sh"
media_ssm <A> "${work}/stop.sh"                       # force 3
bash infra/media-host/ops/force-diff.sh \
  "${force_dir}/force-${stamp}-A.txt" "${force_dir}/force-${stamp}-B.txt" \
  "${force_dir}/force-${stamp}-ended.txt" <A 개수> <B 개수>   # force 6
```

*   **수동 복구:** `FORCE REMAINING <n>` 의 남은 ID 가 연락 대상입니다.
    운영자가 스트리머를 찾아(ingestStreamId → 스트리머, auth 조회) 연락하고
    재송출을 요청합니다. 다시 송출하면 6 을 다시 돌립니다(옛 모드 B 갈래는
    위 force 머리 참고).
*   **복구 완료 = `A − B − 종료 확인 = ∅`**(`FORCE REMAINING 0`)입니다. 서버
    healthy 나 시험 송출 성공만으로 완료로 보지 않습니다.
*   2 와 4 사이에 B 로 붙은 방송의 `/live` 는 아직 A 를 봅니다(apply 한 번
    길이).

## 부분 실패 복구

ingest · http 전환(정상 교체 5 · 6, force 2 · 4)의 apply 가 중간에 실패하면:

1.  `terraform plan` 과 `tg_health` 로 대상 그룹별 등록 상태를 읽습니다.
1.  같은 키로 다시 apply 하거나, 두 활성 키를 A 로 되돌려 apply 합니다
    (승인). force 갈래에서 되돌릴 때는 먼저 A 에서 `systemctl start
    pokeclip-media`(실행 직전 승인)로 A 의 MediaMTX 를 다시 띄우고 기동 판정을
    봅니다. A 가 옛 모드면 위 「언제 쓰나」 의 옛 모드 단서(README 「되돌리기
    순서」 1 먼저 · 되돌린 뒤 3 확인)를 따릅니다.
1.  「정상 교체」 7 의 일관성을 만족하기 전에는 운영 재개로 보지 않습니다.
1.  force 중이었다면 끝난 뒤 수동 복구를 돕니다.

「한 창 절차」 를 도는 중에 A 로 되돌리거나 A 를 다시 띄울 때는 그 절의
「A 를 다시 띄울 때」 를 먼저 따릅니다.

`AccessDenied` 로 실패했으면 다시 apply 하지 않고 SCP 멈춤 규칙을 따릅니다.

## M6 운영 SQL 경계 · 롤백 하한

M6-0(POK-196 장부 스키마)은 사이드카 이미지를 운영 SQL 에 묶습니다. 운영
SQL 적용 절차는 [런타임 1단-가 런북](/infra/terraform/runbooks/runtime-1a.md) 「M6 스키마 재실행」 이고,
하한 · 하한 이상 이미지의 뜻도 그 절의 용어입니다.

**롤백 하한 = 하한 이상 이미지.** M6-0 머지 커밋을 조상으로 둔 커밋으로 빌드한
사이드카입니다(M6-0 c2 의 하한을 읽는 `LoadCursor` · c3 의 기동 스키마
확인이 들어 있음). 그 앞 커밋으로 빌드한 이미지가 하한 아래 이미지입니다. 판정은
이미지를 빌드한 커밋으로 합니다([prod 산출물 런북](/infra/terraform/runbooks/prod-artifacts.md) 기록의
빌드 커밋 SHA).

```shell
git fetch origin
git merge-base --is-ancestor <M6-0 머지 커밋 SHA> <이미지의 빌드 커밋 SHA> \
  && echo '하한 이상' || echo '하한 아래'
```

**경계는 두 방향입니다.**

| 방향 | 규칙 | 어기면 |
|---|---|---|
| 정방향 | 하한 이상 이미지는 M6 운영 SQL 을 적용한 DB 에서만 띄웁니다 | 사이드카가 `schema_not_ready` 를 남기고 종료 코드 1 로 끝납니다(엔진이 거듭 다시 띄움) — 기동 판정 `SCHEMA_NOT_READY` 가 0 이 아님 |
| 역방향 | M6 운영 SQL 을 적용한 DB 에서는 하한 아래 이미지로 기동 · 재기동 · 롤백하지 않습니다 | 하한을 모르는 `LoadCursor` 가 지워진 조각의 seq 를 다시 쓰려 하고, 트리거가 `seq_below_floor`(23514)로 거부해 그 스트림 색인이 멈춥니다. 스위치로는 풀리지 않습니다 |

*   역방향 사고는 오염을 만들지 않습니다(거부되므로 CDN · 장부에 틀린 줄이
    들어가지 않음 — fail-closed). 대신 그 스트림의 색인이 섭니다. 사이드카를
    다시 띄우지 않아도 일어납니다 — 사이드카의 주기 전수 점검이 매번
    `LoadCursor` 를 다시 적재하고, 탈퇴 정리로 행이 지워진 녹화 파일을 미기록으로
    보고 다시 색인하려 하기 때문입니다.
*   **롤백은 하한 이상 이미지끼리만 합니다.** 사이드카 이미지를 되돌릴 때는
    「설정 반영 교체」 로 `image_digests.sidecar` 를 하한 이상 이미지의
    다이제스트로 바꿉니다. 위 판정이 「하한 아래」 면 그 다이제스트로 되돌리지
    않습니다.
*   **트리거를 지워 옛 이미지를 살리는 길은 두지 않습니다.** 그 길이 seq 재사용을
    엽니다(인덱스 불변식 4). 하지 않는 것은 셋입니다 — 트리거 DROP ·
    DISABLE, 하한 행(`stream_seq_floors`) DELETE · 값 낮춤, 하한 아래
    이미지로 되돌림. 런타임 1단-가 런북 「실패 · 시간 초과」 의 손 SQL
    규칙으로 보내는 교정 SQL 에도 이 셋이 그대로 듭니다. 고치는 길은
    앞으로만 갑니다(fix-forward). 이미지
    결함이면 새 하한 이상 이미지로 설정 반영 교체를 하고, 운영 SQL 결함이면
    고친 문장을 더하는 전진 SQL PR 을 머지한 뒤 런타임 1단-가 런북 「M6
    스키마 재실행」 을 그 머지 SHA 로 다시 돕니다. 그 런북의 「되돌리기 ·
    해체」 SQL 은 이 용도로 쓰지 않습니다(해체 전용). 같은 런북의
    「POK-525 트리거 롤백 짝」 은 회차 불변 트리거
    (`stream_sessions_round_frozen`) 하나만 지우는 별개 절차라 이 셋에 들지
    않고, 이 셋을 여는 근거도 되지 않습니다.
*   기능만 끄고 싶으면 이미지가 아니라 스위치로 끕니다. prod 의 되감기 제어면
    스위치는 `prod/media` tfvars 의 `control_upstream` 이고(비우면 nginx 가
    제어면 경로를 404 로 답함) 설정 반영 교체로 들어갑니다. 사이드카 env
    `CONTROL_LISTEN` 은 prod compose 템플릿의 고정값(`127.0.0.1:8086`)이라
    prod 스위치가 아닙니다.

**하한 아래 이미지가 잘못 배포됐을 때.**

1.  걸리는 스트림을 봅니다 — 런타임 1단-가 런북 「M6 읽기 질의」 의 하한
    질의입니다. 나온 스트림이 색인이 멈췄거나 멈출 스트림입니다.
1.  하한 이상 이미지로 「설정 반영 교체」 를 합니다. 새 사이드카의
    `LoadCursor` 가 하한부터 이어 갑니다.
1.  SQL · 트리거 · 하한 행은 그대로 둡니다(위의 하지 않는 것 셋).

## 한 창 절차

**O7e 가 이미 끝난 상태의 대비입니다.** 기본 절차는 O7e 전에 M6 운영 SQL
까지 끝내는 것이라(런타임 1단-가 런북 「M6 스키마 재실행 순서」) 이 절이
필요 없습니다. O7e 가 이미 끝나 하한 아래 이미지가 prod 에서 도는데 M6 운영
SQL 을 적용해야 할 때만 씁니다 — O7e 이미지가 M6-0 앞 커밋이었거나, M6-0 앞
커밋으로 고정한 M5 이미지를 반영했거나, M6-0 머지를 M5 이미지 푸시 뒤로
미뤘을 때입니다. 아직 O7e 를 하지 않았으면 이 절을 고르지 않습니다 — 「O7e
선행 확인」 의 M6 경계에서 멈추고 기본 순서를 끝냅니다(kty 가 그 순서에서
벗어나는 것을 명시해 승인한 경우만 예외).

**창**은 하한 아래 이미지가 트리거 있는 DB 에서 도는 시간입니다 — 3 의 SQL
적용부터 7′ 의 A 정지까지(수 시간)입니다. 창 동안 꼬리 조각이 지워지면 A 가
그 스트림을 다시 색인하려다 멈추므로, 창 동안에는 조각 행을 지우는 쪽(3번의
탈퇴 정리)을 멈춰 둡니다. 실행자 · 승인 규칙은 이 런북 머리와 같고, 걸음마다
kty 가 승인합니다.

| # | 동작 | 통과 |
|---|---|---|
| 0 | 런타임 1단-가 런북 「재실행 걸음」 의 선행(1단-나 ①) — 그 런북 8 의 기록에 그 근거가 있는지 봅니다. 없으면 3 의 재실행이 그 선행에서 멈춰 2 의 탈퇴 정리 정지가 헛걸리므로, 이 절을 시작하지 않고 1단-나 ① 절부터 끝냅니다 | 기록 있음 |
| 1 | B 의 tfvars 를 준비합니다 — `image_digests.sidecar` = 하한 이상 이미지(arm64 · [prod 산출물 런북](/infra/terraform/runbooks/prod-artifacts.md) 기록) · `instances` 에 B. **apply 는 아직 하지 않습니다**(B 는 3 뒤에만 뜸) | 위 판정 「하한 이상」 |
| 2 | O7 세션 · 3번에게 통지합니다 — 창 시각 · 정지 기간(방송 없는 시간대 안 · 수 시간) · 정지 수단 질문. 3번에게 그 기간 동안 탈퇴 정리(app_clip 의 `stream_segments` DELETE) 정지를 요청하고, 정지 수단이 clip 설정 변경(ECS 재배포)인지 잡 일정인지 묻습니다. 수동 삭제도 하지 않게 합니다 | 3번의 정지 확인 |
| 3 | 방송 없는 시간대에 런타임 1단-가 런북 「M6 스키마 재실행」(잠금 대기 상한 포함) | `VERIFY OK` |
| 4 | **교체 직전 단언** — 3 과 같은 실행기 흐름에서 하한 질의(「M6 읽기 질의」) | `(0 rows)` — 창 동안 꼬리 삭제가 없었음 |
| 5 | 「설정 반영 교체」 → 「정상 교체」 1–7. B 기동 판정 인자는 그 구성의 서비스 집합 | 정상 교체 각 걸음의 통과 · B 의 `SCHEMA_NOT_READY 0` · `SIDECAR_DB` 1 이상 · `MEDIA WATCH OK` |
| 6 | (규칙) 창 동안 A 를 다시 띄우거나 7′ 뒤 A 로 되돌려야 하면 아래 「한 창 절차 — A 를 다시 띄울 때」 | — |
| 7 | **창 닫기 조건** — B 판정 둘(5 의 기동 판정 · 관찰 판정)과 정상 교체 7 의 일관성이 모두 통과. 그러면 곧바로 7′ 로 갑니다. 정상 교체 8(A 회수)은 이 창과 별개로 런북 일정(하루 관찰 뒤)대로 하되, 아래 7′ 의 업로드 적체 0 을 회수 조건으로 겁니다 | 셋 다 통과 |
| 7′ | A 정지 — 아래 「한 창 절차 7′ — A 정지」. 정지를 확인하면 창을 닫고 3번에게 탈퇴 정리 재개를 알립니다 | `MEDIA STOPPED OK` |
| 8 | (규칙) 어느 걸음이 실패해도 위 「M6 운영 SQL 경계 · 롤백 하한」 의 하지 않는 것 셋(트리거 DROP · DISABLE · 하한 행 DELETE · 낮춤 · 하한 아래 이미지)은 하지 않습니다. B 를 새 이미지로 고치거나, 전진 SQL PR + 「M6 스키마 재실행」 으로 앞으로 갑니다. 예외는 6 의 조건부 A 되돌림(탈퇴 정리 정지 중 · 하한 질의 0행 — 아래 「A 를 다시 띄울 때」)뿐입니다 | — |

*   `docker inspect` · `/run/pokeclip-media` 내용은 SSM 출력에 내지 않습니다
    (이 런북 머리의 운영 규칙). 아래 판정 스크립트도 판정 줄만 냅니다.
*   4 의 질의에 행이 나오면 창 동안 꼬리 삭제가 있었던 것입니다. 5 로 가지
    않고 3번의 정지가 지켜졌는지 확인해 kty 에게 보고합니다 — 진행은 kty 가
    정합니다. A 는 그 스트림에서 색인이 멈췄거나 멈춥니다(오염은 없음). 이
    경우 6 의 A 되돌림은 하지 않습니다.

### 한 창 절차 7′ — A 정지

**앞 조건 — A 업로드 적체 0.** A 를 멈추면 A 의 사이드카 안 업로더도
멈춥니다. A 디스크에만 있는 조각의 업로드(②(아카이브) · ③(되감기) · init 세
축)가 남아 있으면 그 조각은 다시 올릴 곳이 없습니다(A 는 8 에서 디스크째
회수됨). 그래서 정지 전에 런타임 1단-가 런북 「M6 읽기 질의」 의 업로드 적체
질의를 봅니다.

*   세 행 모두 `pending` 0 · `failed` 0 이면 통과입니다. 방송이 없는 창이고
    정상 교체 4 에서 A 의 방송 경로가 0 이었으므로(정지 직전에 아래에서 다시
    확인), DB 전체의 대기 수가 0 이면 A 몫도 0 입니다.
*   0 이 아니면(B 에서 방송이 시작됨 · 실패 행이 남음) 기다렸다 다시 잽니다.
    창 시간 안에 0 이 되지 않으면 7′ 를 미루고 아래 「7′ 를 못 하면」 으로
    갑니다.

**정지.** A 의 사이드카를 멈추는 수단은 **A 유닛 정지**입니다(실행 직전 승인).
「force」 3 과 같은 명령입니다. 정지 명령 직전에 정상 교체 4 의 `paths.sh` 를
A 에 다시 돌려 `PATHS 0` 인지 봅니다. 0 이 아니거나 `PATHS FAIL` 이면 정지하지
않고 kty 에게 보고합니다 — 정상 교체 7 뒤라 A 에 방송 경로가 있으면 예상
밖입니다.

```shell
media_ssm <A> "${work}/paths.sh"   # PATHS 0 일 때만 아래로
printf 'systemctl stop pokeclip-media\n' > "${work}/stop.sh"
media_ssm <A> "${work}/stop.sh"
cat > "${work}/stopped-check.sh" <<'SH'
state="$(systemctl show -P ActiveState pokeclip-media)" || state=unknown
# 조회가 실패하면 컨테이너 0 으로 세지 않고 판정을 실패시킨다.
ids="$(docker ps -q --filter label=com.docker.compose.project=pokeclip-media)" \
  || { echo "MEDIA STOPPED CHECK FAIL docker"; exit 2; }
running="$(printf '%s\n' "${ids}" | awk 'NF { n++ } END { print n + 0 }')"
echo "UNIT ${state}"
echo "RUNNING ${running}"
if [ "${state}" = inactive ] && [ "${running}" = 0 ]; then
  echo "MEDIA STOPPED OK"; exit 0
fi
echo "MEDIA STOPPED NOT OK"
exit 1
SH
media_ssm <A> "${work}/stopped-check.sh"
```

*   통과 = `MEDIA STOPPED OK`(유닛 `inactive` · 프로젝트 컨테이너 0).
    `MEDIA STOPPED CHECK FAIL docker`(종료 2)는 컨테이너 조회 실패라 0 이
    아닙니다 — 창을 닫지 않고 다시 봅니다.
*   유닛으로 멈추는 근거는 media-host README 운영 규칙 ①(재기동 · 정지는 이
    유닛으로만 — 「시작 · 정지」)입니다. 사이드카 컨테이너만 멈추면 A 의
    mediamtx · nginx 가 끝날 때 유닛(`Restart=always`)이 compose 전체를
    `--force-recreate` 로 다시 띄워 사이드카가 되살아납니다(`start-media.sh`
    머리 9). 유닛 정지는 ExecStop 이 compose 를 내리고, `Restart=always`
    여도 정지를 유지합니다(유닛 머리 주석). 정상 교체 7 뒤라 A 는 어느 대상
    그룹에도 등록돼 있지 않습니다.
*   유닛은 enable 돼 있어 **A 가 재부팅되면 다시 뜹니다.** 8(A 회수) 전에 A 가
    다시 떴으면(유지보수 재부팅 등) 곧바로 이 정지를 다시 하고 하한 질의를
    봅니다.

**7′ 를 못 하면**(적체가 빠지지 않음 · 정지 명령이 승인되지 않음 등) 창은
정상 교체 8(하루 관찰 뒤 A 회수)까지 이어지고, 탈퇴 정리 정지가 24시간을
넘습니다. 3번에게 그 연장을 알립니다. 탈퇴 정리 정지의 상한은 **7일**
입니다(보안 M3(a) 결정(kty 2026-10-26) — 탈퇴자 자료 파기를 오래 미루지
않음). 7일은 위 표 2 의 「3번의 정지 확인」 시각부터 누적해 셉니다. 정지가
7일을 넘기 전에 7′ 를 하지 못하면 절차를 접습니다 — 3번에게 탈퇴 정리 재개를
요청하고 kty 에게 보고합니다. 재개 뒤 A 는 탈퇴 정리된 스트림에서 색인이
멈출 수 있습니다(오염은 없음 — 위 「경계는 두 방향입니다」 의 역방향).
A 의 정지 · 회수는 그대로 7′ · 정상 교체 8 의 조건을 따릅니다.

**정상 교체 8 의 회수 조건.** 「한 창 절차」 를 돈 A 는 8 직전에 하한 질의와
업로드 적체 질의를 함께 봅니다. 적체가 세 축 모두 `pending` 0 · `failed` 0
이 아니면 **회수를 보류합니다** — A 디스크에만 있는 미업로드 조각을 지우면
어디서도 서빙할 수 없습니다(인덱스 불변식 2 — 미업로드 삭제 금지). 적체는
방송 없는 시간대에 잽니다 — B 에서 방송이 돌면 DB 전체 수가 0 이 되지 않을
수 있습니다. 0 이면 아직 A 가 떠 있을 때 위 정지(7′)부터 하고
`MEDIA STOPPED OK` 를 본 뒤 회수합니다.

### 한 창 절차 — A 를 다시 띄울 때

창 동안(3 부터 7′ 까지) A 를 다시 띄우거나, 7′ 뒤 A 로 되돌려야 할 때입니다
(부분 실패 복구의 A 되돌림 · force 갈래의 A `systemctl start` · 정상 교체 8
전의 되돌림 포함). A 는 하한 아래 이미지입니다.

1.  되돌리기 **전에** 탈퇴 정리 정지 7일 누적 상한의 남은 기간을 먼저
    확인합니다(기산점 · 넘길 때의 처리는 위 「7′ 를 못 하면」). 그다음
    3번에게 탈퇴 정리 정지를 다시 요청하고 정지를 확인합니다.
1.  하한 질의가 `(0 rows)` 인지 봅니다.
1.  둘 다 되면 A 를 띄웁니다(`systemctl start pokeclip-media` 후 기동 판정).
    A 가 다시 도는 동안은 창이 다시 열린 것이라, A 를 다시 멈출 때(7′)까지
    탈퇴 정리 정지를 이어 갑니다.
1.  둘 중 하나라도 안 되면 A 로 되돌리지 않고 B 를 고쳐 전진합니다.

## MVP 한계

kty 가 받아들인 한계입니다(설계 5-3 — kty 5차).

*   4 와 5 사이, 5 와 6 사이에 스트리머가 송출을 시작하면 ingest 는 B,
    `/live` 는 A 를 보는 구간이 생길 수 있습니다. 막는 기능이 없습니다.
*   교체 동안 새 송출이 실패하거나 다시 연결해야 할 수 있습니다.
*   force 는 시청 끊김과 재송출 연락을 동반합니다.
*   이 한계를 걷어내는 일은 설계 1-2 비목표와 11-3 후속 후보입니다.

## O7e 뒤 측정 · 기록

상자에서 재야 정할 수 있는 값입니다. 이번 코드에는 값을 넣지 않았습니다.
O7e 5 를 통과한 뒤 잽니다. 값은 후속 PR 에서 모듈 · 템플릿에 넣고 설정 반영
교체로 들어갑니다.

*   **결과 원문**(명령 · 출력 · 판정)은 증거 루트 `~/.pokeclip-precheck/`
    아래 이 측정 전용 디렉터리(0700)에 둡니다 — 규칙은
    [단계 직전 점검 런북](/infra/terraform/runbooks/stage-precheck.md)
    「증거」 입니다(저장소 · 볼트 표지가 있는 경로 · 심볼릭 링크 밖). 계정
    ID · 사설 IP · 인스턴스 id 가 들어 있어 저장소 · 볼트에 그대로 올리지
    않습니다.
*   볼트로 옮기는 것은 kty 가 「저장해」 할 때만이고, 가린 요약만 옮깁니다
    (비밀 값 훑기 · 계정 ID · ARN · RDS 끝점 · IP · 인스턴스 id 가림).

```shell
umask 077
measure_dir="${HOME}/.pokeclip-precheck/media-o7e-measure-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "${measure_dir}" && chmod 700 "${measure_dir}"
```

*   **읽기**만 하는 항목은 SSM 명령(판정 값만 출력)으로 재도 됩니다. 원문이
    필요한 것은 상자 셸(Session Manager)에서 직접 보고 SSM 명령 출력으로 내지
    않습니다. **상태를 바꾸는 항목**(drop-in 재시도 시험)은 등록하지 않은
    상자에서만, 실행 직전 승인을 받고 합니다.

| 항목 | 재는 법 | 정할 것 |
|---|---|---|
| 비특권 포트 하한 | mediamtx 망 칸에서 `nsenter -t "$(pgrep -o -x mediamtx)" -n sysctl -n net.ipv4.ip_unprivileged_port_start` | 프록시 포트 넷이 그 값 이상인지 — 낮으면 nginx(`NET_BIND_SERVICE` 없음)가 뜨지 못함 |
| 하드닝 — `read_only` · `pids_limit` · 메모리 | `docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.PIDs}}'` · 서비스마다 `docker diff <컨테이너>`(쓰는 자리 — 경로만) | 상시 서비스의 읽기 전용 루트 · tmpfs 자리 · pids · 메모리 상한(`render_test.sh` 에 단언 추가) |
| 유닛 하드닝 지시어 | `systemd-analyze security pokeclip-media` | docker CLI · `/run` 쓰기와 부딪히지 않는 지시어 후보 — 등록하지 않은 상자에서 실측 뒤 채택 |
| `/tmp` | `findmnt -n -o FSTYPE,SIZE /tmp` · `df -h /tmp` | 루프백 · 스크립트 스크래치 자리가 충분한지 |
| journald 크기 | `journalctl --disk-usage` 를 하루 간격으로 · `/var/log/journal` 존재(영속) | `SystemMaxUse` · 보존 상한(지금은 `Storage=persistent` 만) |
| AL2023 arm64 판 docker 25.0.16 | `rpm -q docker amazon-ecr-credential-helper` · `dnf list --showduplicates docker` | 고정 판이 arm64 저장소에 있는지 — 없으면 `BOOTSTRAP_FAIL`(`FAIL packages`)로 드러남, 있는 판으로 tfvars 를 바꿔 설정 반영 교체 |
| 자격 도우미 존재 | `command -v docker-credential-ecr-login` · 유닛 저널의 `MEDIA PREREQ OK ecr-credential-helper` 건수 | — |
| drop-in 재시도 | 등록하지 않은 상자에서 설치 표지를 잠시 옮기고(`mv` → `systemctl restart`) 1분 뒤 `MEDIA PREREQ FAIL bundle` 건수가 2 이상인지 → 표지를 되돌리고 재시작 → 기동 판정 | 묶음 표지 확인이 유닛 기동마다 20초 간격으로 다시 도는지(`failed` 로 굳지 않음 · 설치는 다시 하지 않음 — 표지를 되돌리기 전까지 매 시도가 같은 줄로 실패) |
| 동시 방송 부하(c8gn.large 30방송 가정) | 공개 전(NLB 가 팀 IP 만 받을 때) 팀 IP 에서 목표 비트레이트의 시험 송출 30개를 10분 · CloudWatch `CPUUtilization` · `NetworkIn` · `NetworkPacketsIn` · EBS 쓰기 바이트 · 상자의 `ethtool -S <ENA 장치>` 의 `bw_in_allowance_exceeded` · `pps_allowance_exceeded` 증가분 · `df -h /var/lib/docker` 증가율 | CPU 여유 · 대역폭 허용치 초과 0 · 1시간 DVR × 30 이 150 GiB 안인지 — 아니면 유형 · 디스크 재결정(kty) |

## 정리

교체가 끝나면 작업 디렉터리를 지웁니다. force 파일은 복구 완료 뒤에
지웁니다.

```shell
rm -r -- "${work}" && unset work
```

## See also

*   설계 5-3(교체 모델) · 5-4(비밀 초기화) · 5-5(SG) · 3-3-1(상자 구성)
*   계획 M9 「c3 런북 내용」 · 「O7e」 · r6.1 · r6.2 추기 · 3절 「단계 직전
    점검」 · 「부분 진행」
*   [media-host README](/infra/media-host/README.md) — 판정 줄 · 운영 확인
    스크립트 · 운영 규칙
*   [런타임 1단-가 런북](/infra/terraform/runbooks/runtime-1a.md) 「M6 스키마
    재실행」 · 「M6 읽기 질의」
*   [엣지 비밀 런북](/infra/terraform/runbooks/edge-secrets.md) ·
    [단계 직전 점검 런북](/infra/terraform/runbooks/stage-precheck.md)

[^box]: **상자** — prod 미디어 서버를 돌리는 EC2 인스턴스 한 대.
