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
    떠 있는 상자의 user_data 는 `ignore_changes` 로 바뀌지 않습니다.

## 언제 쓰나

| 상황 | 절 | 방송 |
|---|---|---|
| 처음 기동(O7e) | 「O7e — 처음 기동」 | 없음 |
| 상자 교체 · AMI 갱신 | 「정상 교체」 | 없는 시간대 |
| 설정을 바꿈(M15 · O8a · I1-6 반영 · 다이제스트 · 판 · janitor) | 「설정 반영 교체」 → 「정상 교체」 | 없는 시간대 |
| 방송이 있는데 바꿔야 함 | 「force」 | 있음 |
| apply 가 중간에 실패함 | 「부분 실패 복구」 | — |

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

### plan 범위

apply 마다 plan 파일을 만들고, 바뀌는 관리 자원 목록이 그 걸음의 기대
목록과 같을 때만 apply 합니다. 하나라도 다르면 apply 하지 않고 kty 에게
보고합니다.

```shell
terraform -chdir=infra/terraform/prod/media plan -out="${work}/media.plan"
terraform -chdir=infra/terraform/prod/media show -json "${work}/media.plan" \
  | jq -r '.resource_changes[] | select(.mode == "managed")
      | select(.change.actions != ["no-op"])
      | "\(.change.actions | join(",")) \(.address)"'
terraform -chdir=infra/terraform/prod/media apply "${work}/media.plan"
```

| 걸음 | 기대 목록(`<B>` · `<A>` 는 키) |
|---|---|
| B 기동 | `create module.media_host.aws_instance.this["<B>"]` 한 줄 |
| ingest 전환 | `delete,create module.media_host.aws_lb_target_group_attachment.ingest` 한 줄 — NLB 대상 등록 교체만 |
| http 전환 | `delete,create module.media_host.aws_lb_target_group_attachment.http["live"]` · `["control"]` · `["playback"]` 세 줄 — ALB 대상 등록 교체 3건만 |
| A 회수 | `delete module.media_host.aws_instance.this["<A>"]` 한 줄 |

*   설정 반영 교체의 B 기동에서 공유 자원(인스턴스 역할 정책 · SG 규칙 ·
    거부 목록 파라미터)이 함께 바뀌면, 그 변경이 이번 설정 변경의 의도인지
    kty 가 보고 승인합니다. 공유 자원은 A 에도 곧바로 걸립니다.
*   A 의 `aws_instance` 에 `update` 가 보이면 apply 하지 않습니다 — 교체
    모델(`ignore_changes`) 밖의 변경입니다.

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
| `JANITOR_DB` · `JANITOR_STARTED` · `JANITOR_POOL` | 1 이상 | janitor 를 켰을 때만 — DB 연결(pool=janitor) · 기동 · 풀 관측 시작 |

*   사이드카 · janitor 의 줄은 JSON 한 줄이라 메시지와 필드를 함께 봅니다
    (media-host README 「기동 확인 신호」). 기동 직후에는 DB 연결 줄이 아직
    없을 수 있습니다 — 2분 뒤 다시 돌립니다.
*   발행자 풀(`publisher`)은 rewind 발행을 조립했을 때만 열립니다. 그 줄만
    0 이면 사이드카 태그 저널의 `rewind_publish_degraded`(버킷 · 권한) 를
    상자 셸에서 봅니다 — 송출은 돌지만 되감기 목록이 발행되지 않습니다.
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
    `rds_sg_id` · `media_secret_arns` · `rewind_bucket_arn` 이 있습니다. 없으면
    plan 이 그 이름으로 실패합니다.
1.  **런타임의 DB 준비**: RDS IAM 인증 켬 · `media_indexer` · `media_janitor`
    (`rds_iam`) · DDL 역할로 스키마 배치 · GRANT 가 끝났다는 확인이 런타임
    스토리 티켓 코멘트에 있습니다([CONTRACT-runtime.md](/infra/terraform/CONTRACT-runtime.md)
    「DB 접속」). janitor 를 켜므로(기본) RDS PostgreSQL 17 이상과
    `media_janitor` 권한도 확인합니다(media-host README 「후속」 janitor 행).

### O7e 1 — media apply

`terraform.tfvars` 는 `terraform.tfvars.example` 대로 채웁니다(이미지 =
O7c 다이제스트 · `control_upstream` 빈 값 · 응답기 다이제스트 없음). 첫
apply 라 plan 은 생성뿐입니다 — 인스턴스 · Media SG 와 규칙 · RDS 인그레스 ←
Media · LB 이그레스 → Media · 인스턴스 역할 · 프로필 · 정책 · 관리형 정책
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
(Media → auth resolve)과 시험 스트림 키 입력(설계 5-4 의 11)이 끝난 뒤에만 할
수 있습니다.

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
media_ssm b "${work}/loopback.sh" <시험 ingestStreamId>
```

*   인자는 B 의 송출 인증 모드로 고릅니다(위 2 의 서비스 집합과 같은 기준).
    `ingest_auth_image` 가 빈 구성(옛 모드 — O7 · 되돌리기 포함)은 passphrase
    없이, 찬 구성(응답기 모드)은 `--passphrase` 를 앞에 붙입니다
    (`media_ssm b "${work}/loopback.sh" --passphrase <ID>` — media-host README
    「송출 인증 두 모드」 · 「루프백 헬스」). 응답기 모드에서는 기본 갈래가
    거부되는 것이 정상입니다.
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
A 로 되돌릴 때 A 가 옛 모드면 위 「언제 쓰나」 의 옛 모드 단서(README
「되돌리기 순서」 1 먼저 · 되돌린 뒤 3 확인)를 따릅니다.

## 설정 반영 교체

M15 · O8a · I1-6 반영(`control_upstream`)이나 이미지 다이제스트 · 도구 판 ·
`janitor_enabled` 를 바꿀 때입니다. 떠 있는 상자는 고치지 않습니다.

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

`AccessDenied` 로 실패했으면 다시 apply 하지 않고 SCP 멈춤 규칙을 따릅니다.

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
    (비밀 값 훑기 · 계정 ID · ARN · IP · 인스턴스 id 가림).

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
*   [엣지 비밀 런북](/infra/terraform/runbooks/edge-secrets.md) ·
    [단계 직전 점검 런북](/infra/terraform/runbooks/stage-precheck.md)

[^box]: **상자** — prod 미디어 서버를 돌리는 EC2 인스턴스 한 대.
