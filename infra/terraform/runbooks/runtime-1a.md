# prod 런타임 1단-가 런북

`infra/terraform/prod/runtime` 을 처음 적용하고, media DB 계정 · 앱 서버 DB
사용자 · `stream_*` 스키마 SQL(`prod/runtime/sql/`)을 RDS 에 적용하는
절차입니다. 계획 rt1a 의 「운영 순서와 승인 지점」 이 이 문서입니다.

*   실행자는 kty 입니다. kty 의 IAM 사용자 프로필로 **kty 로컬 터미널**에서
    돌립니다. 에이전트 세션이나 세션의 `!` 로 돌리지 않습니다 — 비밀 ARN ·
    plan 출력에 계정 ID 가 들어 있어 세션 기록에 남습니다. 출력도 세션 ·
    문서 · 채팅에 붙여 넣지 않습니다(SQL 결과 줄은 계정 ID 가 없어 기록에
    옮길 수 있습니다).
*   쓰기(apply · send-command)마다 실행 직전에 kty 가 승인합니다.

용어:

*   **SQL 실행기**: SQL 을 적용하는 동안만 켜는 작은 EC2 상자입니다
    (`sql_runner_enabled` — [prod/runtime](../prod/runtime/README.md)
    「SQL 실행기 스위치」). 키 · 공인 IP · 인그레스가 없고 SSM 으로만
    명령을 받습니다.
*   **send-command**: `aws ssm send-command` 입니다. 상자에 셸 명령을 보내고
    결과(표준 출력 · 오류 · 상태)를 SSM 에 남깁니다.
*   **관리형 마스터 비밀**: RDS 가 만들고 돌리는 마스터 비밀번호
    비밀(이름 `rds!db-…`)입니다. 값은 상자의 셸 변수로만 받습니다.
*   **verify-full**: TLS 로 붙고 서버 인증서와 호스트 이름을 RDS CA 번들로
    확인하는 psql 접속 방식입니다.

## 언제

prod/network(O7a) 뒤, platform(O7d) · media(O7e) 앞입니다. platform · media
가 이 루트의 출력을 읽고, media 사이드카는 이 런북 5 의 DB 계정이 있어야
접속합니다([런타임 트랙 인터페이스 계약](../CONTRACT-runtime.md)
「DB 접속(IAM DB 인증)」). 앱 서버 넷도 이 런북 5 의 DB 사용자가 있어야
처음 뜹니다 — platform 이 읽는 `app_db_users` 는 이 루트가 내고, 그 사용자는
5 의 `10_roles.sql` 이 만듭니다(POK-407 · kty 2026-10-24).

## 순서 한눈에

| 걸음 | 하는 일 | 승인 지점 |
|---|---|---|
| 1 | 직전 점검(`runtime-1a` — SCP · 2-3 · 이벤트) — 첫 SCP 판정은 CHANGED | ⑤ 보고 · `--accept` |
| 2 | 본 apply(실행기 끔) | plan 승인 |
| 3 | 운영 확인 — 엔진 실제 부판 기록 · 마스터 비밀 입력 파일 · 2-3 다시 | — |
| 4 | 직전 점검 다시 → 실행기 켬 → 2-3 다시(SQL 전) | plan 승인 |
| 5 | SQL 적용(00 → 10 → 20 → 30 → 40) | send-command 승인 |
| 6 | advisory 확인(50 → 40) | send-command 승인 |
| 7 | 직전 점검 다시(막지 않음) → 실행기 끔 · 잔여 0 | plan 승인 |
| 8 | 기록 | — |

4 부터 6 까지 어느 걸음이든 실패하거나 시간이 넘으면 곧바로 7 로 갑니다(아래
「실패 · 시간 초과」).

1단-가 SQL 이 이미 적용된 DB 에 1단-나 ①(앱별 스키마 · DB 권한)을 얹을 때는
아래 「1단-나 ①」 절의 순서를 따릅니다.

1단-다(일 큐 · 새 버킷 · auth 설정 · 값 넣기)는 아래 「1단-다」 절의 순서를
따릅니다. O7d 뒤 1단-다를 되돌리는 동안(clip 0개부터 다시 올리기까지 —
회부로 보류된 동안 포함) platform apply 는 다시 올리기 하나뿐입니다. 다른 PR
의 platform plan 에 `module.clip.aws_ecs_service.this` 의 `desired_count`
0 → 1 이 보이면 그 apply 는 거부합니다(「1단-다 되돌리기 — O7d 뒤」).

1단-가를 이미 적용한 RDS 에 M6 장부 스키마(POK-196)를 더하는 것은 아래
「M6 스키마 재실행」 절입니다 — 1단-나 ①을 끝낸 뒤 이 표의 4–7 을 다시
돕니다.

## 1. 직전 점검

[단계 직전 점검](stage-precheck.md)의 「순서」 를 묶음 · 단계
`runtime-1a` 로 합니다. POK-365 완료로 이 단계도 2-3 판정을 합니다(설계
K5 가 바뀜 — 처음 계획은 미판정이었습니다).

먼저 kty 가 **세션 밖 터미널**에서 `aws sts get-caller-identity` 로 호출
주체가 리스 계정의 kty IAM 사용자인지 눈으로 봅니다. 에이전트 세션이나
세션의 `!` 로 돌리지 않습니다 — 출력의 계정 ID · ARN 이 세션 기록에
남습니다.

```shell
bash infra/terraform/scripts/precheck/scp-recheck.sh runtime-1a
bash infra/terraform/scripts/precheck/principal-check.sh runtime-1a
bash infra/terraform/scripts/precheck/events.sh --since <비교 기준 판정 시각>
```

*   ① 첫 판정은 기준에 없는 행이라 종료 3(CHANGED)이 정상입니다. ⑤ 보고와
    한 번에 묻고, kty 의 답을 들은 뒤 `--accept` 하고 다시 판정합니다
    (stage-precheck 「첫 실행과 기준 승인」).
*   ② 는 stage-precheck 「2-3 판정의 결과와 갈래」 대로 봅니다. 런타임
    상태 객체 행은 이 단계부터 묻습니다. SNS 주제 · SQS 큐의 자원 정책은
    2-3 판정이 직접 읽습니다. runtime-1a 직전에는 자원이 없어 정책 없이
    판정하고 `report.txt` 에 「자원 없음」 을 남깁니다. 종료 3(UNDECIDED)이면 kty 가
    `approvals.tsv` 에 「진행」 을 적어야 다음으로 갑니다.
*   ② 에서 `prod-instance`(prod EC2 인스턴스 — 실행기가 꺼져 있고 다른 prod
    인스턴스도 없음) · `rds-master-secret`(관리형 마스터 비밀 — apply 전이라
    입력 파일 없음)의 `TARGET_NONE` 은 이 걸음에서 정상입니다.
    `prod-roles`(경로 `/pokeclip/prod/` 의 역할)의 `TARGET_NONE` 은
    O7d(platform apply) 전에만 정상입니다 — runtime 의 역할은 실행기
    역할뿐이라 켤 때만 생깁니다. 「진행」 의 이유로 그렇게 적습니다. O7d
    뒤에는 platform 역할이 늘 있어야 하므로 `prod-roles` 의 `TARGET_NONE` 은
    멈춤입니다.
*   ① 이 종료 0 이 아니거나(승인 뒤에도 DENIED 등) ② 가 종료 0 도, 「진행」
    이 적힌 종료 3 도 아니면 2 로 가지 않습니다.

## 2. 본 apply

1.  **plan**: 실행기는 끈 채(기본값) plan 을 파일로 남깁니다. 앞에 남은 plan
    파일은 먼저 지웁니다. `<상태 버킷 이름>` 은 kty 가 터미널에서 prod 상태
    버킷 이름(`prod/bootstrap` 의 `state_bucket_name`)으로 바꿔 넣고
    돌립니다 — 이 문서 · 세션에 값을 적지 않습니다.

    ```shell
    cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
    cp backend.hcl.example backend.hcl
    sed -i.orig 's/<STATE_BUCKET_PROD>/<상태 버킷 이름>/' backend.hcl
    rm backend.hcl.orig
    grep -c '^bucket' backend.hcl              # 1 이어야 함(값은 내지 않음)
    grep -c '<STATE_BUCKET_PROD>' backend.hcl  # 0 이어야 함
    grep -c '<상태 버킷 이름>' backend.hcl      # 0 이어야 함(1 이면 안 바꿔 넣음)
    rm -f runtime.tfplan
    terraform init -backend-config=backend.hcl
    terraform plan -out=runtime.tfplan
    ```

1.  **확인 · 승인**: 아래를 모두 확인하고 kty 승인을 받습니다. 하나라도
    다르면 apply 하지 않습니다.

    *   `Plan: 29 to add, 0 to change, 0 to destroy` — RDS 다섯(SG · 서브넷
        그룹 · 파라미터 그룹 · 로그 그룹 · 인스턴스) · 비밀 그릇 일곱 ·
        rewind 버킷 다섯 · 계약9 열둘(주제 정책 포함). 이 수는 1단-가
        머지본 기준입니다(뒤 단계가 그릇을 더함 — 1단-다 · 1단-나 ② 2).
        1단-다 코드가 들어간 판에서 처음부터 apply 하면 수가 다르고
        tfvars(`auth_oauth_apps`)가 먼저 있어야 plan 이 돕니다 — 「1단-다 2.
        tfvars 와 apply」 를 따릅니다.
    *   주제 정책(`aws_sns_topic_policy.broadcast_lifecycle`)은 명시
        정책입니다 — 문장이 하나이고, 주체가 계정 루트 하나, 동작이
        `SNS:GetTopicAttributes` 하나이며 `*` · `Condition` 이 없습니다.
        변경 동작(`SNS:SetTopicAttributes` · `SNS:AddPermission` ·
        `SNS:RemovePermission` · `SNS:DeleteTopic` · `SNS:Subscribe` ·
        `SNS:Publish`)이 없습니다.
    *   주소에 `sql_runner` 가 없습니다(실행기 꺼짐).
    *   `aws_secretsmanager_secret_version` 이 0 입니다.
    *   태그를 받는 자원의 `tags_all` 에 네 키(`TerraformRoot =
        prod/runtime`)가 있습니다.

1.  **apply**: 승인받은 plan 파일만 적용하고 지웁니다.

    ```shell
    terraform apply runtime.tfplan
    rm runtime.tfplan
    ```

1.  **적용 확인**: 다시 plan 해서 변경이 없는지 봅니다.

    ```shell
    if terraform plan -lock=false -detailed-exitcode; then
      printf 'PLAN NO CHANGES\n'
    else
      printf '멈춤: 종료 %s\n' "$?"
    fi
    ```

apply 가 거부로 실패하면 계획 3절 「SCP 멈춤 규칙」(전체 중단 · 재시도
금지 · 상태 대조)입니다. 관리형 마스터 비밀(`rds!db-*`)의
`secretsmanager:CreateSecret` 은 `runtime-1a` 묶음이 앱 그릇 경로
(`pokeclip/prod/*`)로 대신 판정합니다 — SCP 가 그 이름만 거부하면 1 을
통과해도 apply 가 거부로 멈춥니다(같은 규칙).

## 3. 운영 확인

```shell
aws rds describe-db-instances --region ap-northeast-2 \
  --db-instance-identifier pokeclip-prod-db \
  --query 'DBInstances[0].[DBInstanceStatus,EngineVersion,IAMDatabaseAuthenticationEnabled,DBParameterGroups[0].ParameterApplyStatus,MasterUserSecret.SecretStatus,CACertificateIdentifier]' \
  --output text
aws logs describe-log-groups --region ap-northeast-2 \
  --log-group-name-prefix /aws/rds/instance/pokeclip-prod-db/ \
  --query 'logGroups[].[logGroupName,retentionInDays]' --output text
```

*   첫 줄이 `available` · `17.<부판>` · `True` · `in-sync` · `active` ·
    `rds-ca-rsa2048-g1` 입니다. **`EngineVersion` 의 실제 값을 기록합니다** —
    판은 메이저만 고정해서 생성 부판은 AWS 의 17 기본값입니다(H4).
*   로그 그룹은 `/aws/rds/instance/pokeclip-prod-db/postgresql` 하나이고
    보관이 `30` 입니다.
*   관리형 마스터 비밀에 default_tags 가 붙는지는 미확인입니다. 아래
    `describe-secret` 으로 보고 결과를 기록합니다. 태그는 판정에 쓰지 않습니다
    — 2-3 판정은 이 비밀을 아래 입력 파일로 묻습니다.

### 3-1. 관리형 마스터 비밀을 2-3 판정에 넣기

이 비밀은 이름 끝이 생성 때 정해져 데이터 행에 ARN 을 적을 수 없습니다.
그래서 본 apply 직후 kty 가 `terraform output` 을 증거 루트의 입력 파일로
한 번 적고, 2-3 판정을 다시 돌립니다. ARN 은 화면에 내지 않고 파일로만
옮깁니다. 입력 파일은 다음 단계 판정도 읽으니 지우지 않습니다.

```shell
cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
if (
  umask 077
  mkdir -p ~/.pokeclip-precheck &&
    terraform output -raw rds_master_user_secret_arn \
      >~/.pokeclip-precheck/input-rds-master.txt
); then
  printf 'INPUT OK\n'
else
  printf '멈춤: 입력 파일을 쓰지 못함\n' >&2
fi
aws secretsmanager describe-secret --region ap-northeast-2 \
  --secret-id "$(cat ~/.pokeclip-precheck/input-rds-master.txt)" \
  --query Tags
cd "$(git rev-parse --show-toplevel)"
bash infra/terraform/scripts/precheck/principal-check.sh runtime-1a
```

*   `INPUT OK` 가 아니면 4 로 가지 않습니다.
*   입력 파일의 줄은 이 계정 · 서울 리전의 비밀 **전체 ARN** 이어야 합니다
    — 이름이 `rds!db-` 로 시작하고 끝이 `-` + 영숫자 6자(Secrets Manager 가
    이름 뒤에 붙이는 접미)입니다. 부분 ARN(접미 없음) · 다른 비밀(예:
    `pokeclip/prod/` 앱 비밀)은 종료 2 입니다. 시뮬레이터는 없는 ARN 도
    평가해, 부분 ARN 으로 물으면 전체 ARN 에만 걸린 dev 정책을 놓치기
    때문입니다. 정책 읽기 응답의 ARN 이 입력 줄과 글자까지 같지 않아도 종료
    2 입니다([ARN 꼴](https://docs.aws.amazon.com/secretsmanager/latest/userguide/whats-in-a-secret.html)).
*   2-3 판정은 1 의 ② 대로 봅니다. 여기에 더해 `rds-master-secret` 행이
    `ALLOWED`(멈춤)이면 dev 주체가 마스터 비밀을 읽거나 바꿀 수 있다는
    뜻이니 4 로 가지 않고 보고합니다. 그 행이 아직 `TARGET_NONE` 이거나
    종료 2 면(입력 파일을 읽지 못함 · 꼴 밖 · 응답 ARN 다름) 원인을 고친 뒤
    다시 돌립니다.
    `prod-instance` 의 `TARGET_NONE` 은 실행기가 꺼져 있어 정상입니다.
    `prod-roles` 의 `TARGET_NONE` 은 O7d 전이면 정상, O7d 뒤면
    멈춤입니다(1 의 ②).

## 4. 실행기 켬

1.  1 의 점검을 다시 합니다(같은 묶음 · `--since` 는 그때의 비교 기준).
    1 의 통과 조건이 아니면 켜지 않습니다. 이번에는 아래도 멈춤입니다.
    *   2-3 판정의 `report.txt` 에 「자원 없음(첫 단계 — 정책 없이
        판정)」 줄이 있음 — 본 apply 뒤인데 주제 · 큐가 없다는 뜻이라
        이상입니다. 도구는 첫 단계라 통과로 셉니다. 사람이 이 줄을 보고
        멈춥니다.
    *   `rds-master-secret` 행이 `TARGET_NONE` 이거나 `ALLOWED` 임(3-1).

    `prod-instance` 의 `TARGET_NONE` 은 실행기가 아직 꺼져 있어 정상입니다.
    `prod-roles` 의 `TARGET_NONE` 은 O7d 전이면 정상, O7d 뒤면
    멈춤입니다(1 의 ②).
1.  **plan**: 실행기만 켜는 plan 입니다.

    ```shell
    cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
    rm -f runner-on.tfplan
    terraform plan -var sql_runner_enabled=true -out=runner-on.tfplan
    ```

1.  **확인 · 승인**: `Plan: 9 to add, 0 to change, 0 to destroy` 이고 주소가
    모두 실행기 자원(역할 · 정책 연결 · 인라인 정책 · 프로필 · SG · 이그레스
    둘 · `rds_from_sql_runner` · 인스턴스)입니다. 다르면 apply 하지 않습니다.
1.  **apply**: `terraform apply runner-on.tfplan && rm runner-on.tfplan`.
1.  **켠 시각 기록**: `date '+%Y-%m-%d %H:%M'` 를 적습니다(8). **이 시각부터
    120분 안에 7(끔)을 시작합니다.** 상자가 멈춰도 자원 · 역할은 남으므로
    7 은 그대로 합니다.
1.  **SSM 등록**: 아래가 `Online` 이 될 때까지 기다립니다(보통 몇 분).

    ```shell
    runner_id="$(terraform output -raw sql_runner_instance_id)"
    aws ssm describe-instance-information --region ap-northeast-2 \
      --filters "Key=InstanceIds,Values=${runner_id}" \
      --query 'InstanceInformationList[0].PingStatus' --output text
    ```

1.  **켬 뒤 2-3 판정(SQL 보내기 전)**: 켜진 실행기는 이제 `prod-instance`
    행으로 풀립니다(인스턴스 ID 는 켤 때 정해져 `Environment = prod` 태그로
    찾음). dev 주체가 이 상자에 명령(`ssm:SendCommand`) · 세션
    (`ssm:StartSession`) · 상태 관리자 연결(`ssm:CreateAssociation`)을 보낼
    수 있으면 상자의 역할로 마스터 비밀을 읽을 수 있습니다. 저장소 뿌리에서
    2-3 판정만 다시 돌립니다.

    ```shell
    cd "$(git rev-parse --show-toplevel)"
    bash infra/terraform/scripts/precheck/principal-check.sh runtime-1a
    ```

    1 의 ② 통과 조건(종료 0, 또는 「진행」 이 적힌 종료 3)이 아니거나 아래
    가운데 하나면 SQL 을 보내지 않고 곧바로 7(끔)로 갑니다.

    *   `prod-instance` 행이 `ALLOWED`(멈춤) — dev 주체에 그 인스턴스의
        명령 · 세션 · 연결 권한이 있음.
    *   `prod-instance` 행이 `TARGET_NONE` — 켰는데 태그로 풀리지 않음(한계
        A14). 판정하지 못했으니 보내지 않습니다.
    *   `prod-roles` 행이 `TARGET_NONE` — 켰는데 실행기 역할이 경로
        `/pokeclip/prod/` 로 풀리지 않음. 판정하지 못했으니 보내지 않습니다.
    *   `report.txt` 에 「자원 없음」 줄 · `rds-master-secret` 의
        `TARGET_NONE` 또는 `ALLOWED`(4 의 첫 걸음과 같음).

## 5. SQL 적용

SQL 원본은 **머지 커밋 SHA 에서 `git show`** 로 꺼내 send-command 에 인라인으로
싣습니다. 작업 트리의 파일을 쓰지 않습니다. 파일마다 gzip + base64 로 실어
상자에서 풀고, 상자가 푼 파일의 sha256 을 상자 출력으로 받아 로컬에서
`git show` 원본으로 계산한 목록과 대조합니다. 새 비밀이 생기지 않고
저장소 공개 여부에 기대지 않습니다. 실은 크기는 지금 약 15 KB 입니다. SSM
매개변수 크기 한계는 미확인입니다 — 넘어서 거부되면 파일마다 한 번씩
보냅니다.

### 5-1. 페이로드 만들기

로컬에서 돌리고 AWS 를 부르지 않습니다(`terraform output` 은 상태만
읽습니다). 저장소 뿌리에서 같은 셸에 함수를 정의하고 부릅니다. SHA 가
`origin/develop` 의 조상이 아니거나 어느 명령이든 실패하면 「멈춤」 을 내고
페이로드를 만들지 않습니다. SQL 파일을 더하거나 이름을 바꾸면 `names` 도
함께 고칩니다.

```shell
make_payload() {
  local sha="$1" rt=infra/terraform/prod/runtime
  git fetch origin || { printf '멈춤: git fetch 실패\n' >&2; return 1; }
  if ! git merge-base --is-ancestor "${sha}" origin/develop; then
    printf '멈춤: SHA 가 origin/develop 의 조상이 아님\n' >&2
    return 1
  fi
  printf 'SHA OK\n'
  if ! {
    rds_endpoint="$(terraform -chdir="${rt}" output -raw rds_endpoint)" &&
      secret_arn="$(terraform -chdir="${rt}" output -raw rds_master_user_secret_arn)" &&
      runner_id="$(terraform -chdir="${rt}" output -raw sql_runner_instance_id)" &&
      payload="$(mktemp -d)"
  }; then
    printf '멈춤: 출력 읽기 · 임시 디렉터리 실패\n' >&2
    return 1
  fi
  python3 - "${sha}" "${rds_endpoint}" "${secret_arn}" "${payload}" <<'PY' || {
import base64, gzip, hashlib, json, os, subprocess, sys

sha, endpoint, secret_arn, out = sys.argv[1:5]
src = "infra/terraform/prod/runtime/sql"
names = ["00_preflight.sql", "10_roles.sql", "stream_schema.sql",
         "20_schema.sql", "30_grants.sql", "40_verify.sql",
         "50_advisory_probe.sql"]
box = "/root/rt1a"
sums = []
prepare = ["set -eu", "umask 077", f"mkdir -p {box}/sql", f"cd {box}/sql"]
for n in names:
    raw = subprocess.run(["git", "show", f"{sha}:{src}/{n}"],
                         check=True, capture_output=True).stdout
    b64 = base64.b64encode(gzip.compress(raw, mtime=0)).decode()
    prepare.append(f"printf '%s' '{b64}' | base64 -d | gunzip > {n}")
    sums.append(f"{hashlib.sha256(raw).hexdigest()}  {n}")
# 상자가 푼 파일의 해시를 상자에서 계산해 내보낸다(로컬 목록과 대조 — 5-3).
# dnf 출력보다 먼저 내서 표준 출력 상한(앞 24,000자)에 들게 한다.
prepare += [
    f"sha256sum {' '.join(names)} > {box}/sql.sha256",
    f"cat {box}/sql.sha256",
    "dnf install -y postgresql17 || dnf install -y postgresql16",
    "psql --version",
    "curl -fsS -o /root/rt1a/rds-ca.pem "
    "https://truststore.pki.rds.amazonaws.com/ap-northeast-2/"
    "ap-northeast-2-bundle.pem",
    "grep -c 'BEGIN CERTIFICATE' /root/rt1a/rds-ca.pem",
]
conn = [
    "set -eu",
    f"cd {box}/sql",
    f"sha256sum -c {box}/sql.sha256",
    "PGPASSWORD=\"$(aws secretsmanager get-secret-value"
    " --region ap-northeast-2"
    f" --secret-id '{secret_arn}' --query SecretString --output text"
    " | python3 -c 'import json,sys; print(json.load(sys.stdin)[\"password\"])')\"",
    "export PGPASSWORD",
    f"export PGHOST='{endpoint}' PGPORT=5432 PGDATABASE=pokeclip"
    " PGUSER=pokeclip_admin PGSSLMODE=verify-full"
    f" PGSSLROOTCERT={box}/rds-ca.pem PGCONNECT_TIMEOUT=10",
]
apply = conn + [
    "for f in 00_preflight.sql 10_roles.sql 20_schema.sql 30_grants.sql"
    " 40_verify.sql; do echo \"== ${f}\";"
    " psql -X -1 -v ON_ERROR_STOP=1 -f \"${f}\"; done",
]
# VERBOSITY verbose — 오류 · 경고 줄에 SQLSTATE(42501 · 01006)가 붙는다.
probe = conn + [
    "echo '== 50_advisory_probe.sql'",
    "rc=0; psql -X -v ON_ERROR_STOP=1 -v VERBOSITY=verbose"
    " -f 50_advisory_probe.sql || rc=$?",
    "echo \"50 exit ${rc}\"",
    "echo '== 40_verify.sql'",
    "psql -X -1 -v ON_ERROR_STOP=1 -f 40_verify.sql",
]
for name, cmds, timeout in (("prepare", prepare, "600"),
                            ("apply", apply, "300"),
                            ("probe", probe, "300")):
    with open(os.path.join(out, f"{name}.json"), "w") as f:
        json.dump({"commands": cmds, "executionTimeout": [timeout]}, f)
with open(os.path.join(out, "sql.sha256"), "w") as f:
    f.write("\n".join(sums) + "\n")
PY
    printf '멈춤: 페이로드 만들기 실패\n' >&2
    return 1
  }
  cat "${payload}/sql.sha256"
}

cd "$(git rev-parse --show-toplevel)"
make_payload "<머지 커밋 SHA>"
```

마지막 줄이 `SHA OK` 와 sha256 일곱 줄을 냅니다. 일곱 줄을 기록합니다(8).
「멈춤」 이 나오면 보내지 않고 7(끔)로 갑니다. 비밀번호는 페이로드에
없습니다 — 상자가 실행 때 비밀에서 받아 셸 변수(`PGPASSWORD`)로만 씁니다.
psql 은 `-X`(개인 설정 무시) · `-1`(파일 하나 = 트랜잭션 하나) ·
`ON_ERROR_STOP` 으로 돌고, 첫 실패에서 멈춥니다.

### 5-2. 보내는 함수

같은 셸에 정의합니다. 시간 한계는 고정값입니다 — 전달 600초
(`--timeout-seconds`) · 실행은 준비 600초 · 적용과 확인 300초
(`executionTimeout`, 페이로드에 있음). 결과는 페이로드 디렉터리에 남기고
상태 · 출력을 보입니다. 실패해도 다시 보내지 않습니다.

결과 조회가 오류를 내면(거부 · 자격 만료 등) 그 오류를 그대로 보이고 곧바로
멈춥니다 — 다시 시도하지 않습니다(SCP 멈춤 규칙). 예외는 보낸 직후 아직
조회되지 않는 `InvocationDoesNotExist` 하나뿐이고, 보낸 뒤 60초까지만
기다립니다. `Pending` · `InProgress` · `Delayed` · `Cancelling` 은 아직 끝나지
않은 상태라 계속 기다립니다. 기다림 전체에도 상한(전달 600초 + 실행 최대
600초 + 여유 120초 = 1320초)을 두고, 보낸 시각부터 실제로 흐른 시간
(`date +%s`)으로 재어 넘으면 멈춥니다. 끝난 뒤 결과 JSON 을 받거나 읽지
못해도 멈춥니다. 함수가 「멈춤」 을 내면 7(실행기 끔)로 갑니다.

```shell
send_step() {
  local step="$1" cmd_id cmd_state started elapsed limit=1320
  cmd_id="$(aws ssm send-command --region ap-northeast-2 \
    --document-name AWS-RunShellScript --instance-ids "${runner_id}" \
    --timeout-seconds 600 --comment "rt1a ${step}" \
    --parameters "file://${payload}/${step}.json" \
    --query Command.CommandId --output text)" || {
    printf '멈춤: send-command 실패 — 다시 보내지 않고 7(끔)로 갑니다\n' >&2
    return 1
  }
  started="$(date +%s)"
  while :; do
    sleep 10
    elapsed=$(($(date +%s) - started))
    if [ "${elapsed}" -gt "${limit}" ]; then
      printf '멈춤: %s초 안에 끝나지 않음 — 7(끔)로 갑니다\n' "${limit}" >&2
      return 1
    fi
    if cmd_state="$(aws ssm get-command-invocation --region ap-northeast-2 \
      --command-id "${cmd_id}" --instance-id "${runner_id}" \
      --query Status --output text 2>&1)"; then
      case "${cmd_state}" in
        Pending | InProgress | Delayed | Cancelling) continue ;;
        *) break ;;
      esac
    fi
    case "${cmd_state}" in
      *InvocationDoesNotExist*)
        [ "${elapsed}" -le 60 ] && continue ;;
    esac
    printf '%s\n' "${cmd_state}" >&2
    printf '멈춤: 결과 조회 실패 — 다시 시도하지 않고 7(끔)로 갑니다\n' >&2
    return 1
  done
  if ! aws ssm get-command-invocation --region ap-northeast-2 \
    --command-id "${cmd_id}" --instance-id "${runner_id}" \
    --output json >"${payload}/${step}-result.json"; then
    printf '멈춤: 결과 JSON 조회 실패 — 다시 시도하지 않고 7(끔)로 갑니다\n' >&2
    return 1
  fi
  jq -r '.Status, "--- stdout", .StandardOutputContent,
    "--- stderr", .StandardErrorContent' "${payload}/${step}-result.json" || {
    printf '멈춤: 결과 JSON 을 읽지 못함 — 7(끔)로 갑니다\n' >&2
    return 1
  }
}
```

### 5-3. 준비와 적용

1.  **준비**(승인 뒤): `send_step prepare`. 상태 `Success` · `psql
    --version` · 인증서 수(0 이 아님)를 보고, 상자가 낸 sha256 일곱 줄을
    로컬 목록과 대조합니다 — `SHA256 OK` 가 아니면 7(끔)로 갑니다.

    ```shell
    jq -r '.StandardOutputContent' "${payload}/prepare-result.json" |
      grep -E '^[0-9a-f]{64}  [0-9a-z_]+\.sql$' >"${payload}/box.sha256"
    if cmp -s "${payload}/sql.sha256" "${payload}/box.sha256"; then
      printf 'SHA256 OK\n'
    else
      printf '멈춤: 상자의 sha256 이 로컬 목록과 다름 — 7(끔)로 갑니다\n' >&2
    fi
    ```

    psql 패키지 `postgresql17` 이 AL2023 에 있는지는 미확인이라 없으면
    16 을 깝니다(이 SQL 에는 16 클라이언트로 충분). CA 번들 주소는 RDS
    문서의 지역 번들 꼴이고 원문 대조는 미확인입니다 — 받지 못하면 준비가
    실패합니다.
1.  **적용**(승인 뒤): `send_step apply`. 통과는 상태 `Success` 이고 표준
    출력에 `PREFLIGHT OK` · `VERIFY OK` 가 있는 것입니다. 실패하면 「실패 ·
    시간 초과」 로 갑니다.

판정에서 볼 것:

*   `00` 은 실패한 점검을 모아 `preflight failed: <식별자>, …` 로 알립니다
    (식별자 뜻은 `00_preflight.sql` 머리). 00 은 마스터가 `rds_iam` 에
    ADMIN 을 가졌는지는 보지 않습니다. 그래서 `10` 의 `GRANT rds_iam` 에서
    실패할 수 있습니다 — 그러면 `psql -1` 이 10 전체를 롤백합니다.
    비superuser 마스터 대역 시험(CI)은 RDS 의 `rds_superuser` 와 같지
    않으므로 **첫 운영 적용이 최종 판정**입니다.
*   `40` 은 `verify failed: <식별자>, …` 로 실패합니다(식별자 뜻은
    `40_verify.sql` 머리). 계정은 media 두 계정과 앱 사용자 넷입니다.
    실패하면 다른 실패와 같이 「실패 · 시간 초과」 로 갑니다 — 같은
    흐름에서 다시 보내지 않고 실행기를 끕니다. 멤버십(`membership_<계정>` ·
    `members_media_owner`)이나 media 계정에 직접 부여된
    CREATE(`schema_create_<계정>`)로 실패했으면 10 은 그것을 회수하지
    않습니다 — 무엇이 왜 붙었는지 모르기 때문입니다. 운영자가 원인을
    확인하고, 회수하는 손 SQL 은 「실패 · 시간 초과」 의 손 SQL 규칙대로
    다음 흐름에서 보냅니다.
*   앱 서버 사용자 넷(`app_auth` · `app_clip` · `app_chat_collector` ·
    `app_chat_detector`)과 계정마다 이름이 같은 자기 스키마 · DB CONNECT 는
    10 이 만들고, `stream_*` 교차 권한은 30 이 줍니다. 판별 서버의
    `chat_messages` 읽기는 수집 서버 Flyway(`V316`)가 주므로 런타임은 주지
    않습니다. `app_chat_detector` 가 수집 서버의 첫 기동보다 늦게 생겼으면
    그 줄이 건너뛰어졌으니 아래를 마스터로 `psql -1` 로 손으로 돌립니다(표
    주인으로 바꿔서 주고 그 멤버십을 거둡니다 — 로컬 PG 비superuser 대역으로
    확인함). 표는 수집 서버 스키마에 있으므로 이름을 한정합니다. 보내는 길은
    「실패 · 시간 초과」 의 손 SQL 규칙과 같습니다.

    ```sql
    GRANT app_chat_collector TO CURRENT_USER WITH SET TRUE, INHERIT FALSE;
    SET ROLE app_chat_collector;
    GRANT SELECT ON app_chat_collector.chat_messages TO app_chat_detector;
    RESET ROLE;
    REVOKE app_chat_collector FROM CURRENT_USER;
    ```

*   파일 하나가 실패하면 그 파일만 롤백되고 앞 파일은 남습니다. 10 · 20 ·
    30 은 다시 돌려도 같은 상태로 수렴하므로, 「실패 · 시간 초과」 대로 끈
    뒤 원인을 고친 다음 흐름에서 00 부터 다시 돌립니다.

## 6. advisory 확인

`50_advisory_probe.sql` 은 마스터가 `pg_advisory_xact_lock(bigint)` 의
EXECUTE 를 PUBLIC 에서 회수할 수 있는지만 보고 ROLLBACK 합니다(K7 —
1단-나 ①이 회수하지 않기로 정했습니다). 5 와 떼어 한 번만, `-1` 없이
돌리고, 같은 명령 안에서 뒤이어 40 을 다시 돌려 무변경을 확인합니다.

승인 뒤 `send_step probe`. 아래 세 갈래가 **모두 정상 관측**입니다. 어느
쪽이든 뒤의 40 이 `VERIFY OK` 여야 하고(상태 `Success`), 아니면 「실패 · 시간
초과」 로 갑니다.

| 관측 | 뜻 |
|---|---|
| 표준 출력 `after` 행의 `public_execute` 가 `f` | 회수 가능(ROLLBACK 으로 되돌림) |
| 표준 오류에 `WARNING:  01006: no privileges could be revoked` · `after` 가 `t` | 회수 불가 |
| 표준 오류에 `ERROR:  42501:` · 표준 출력 `50 exit 3` | 회수 불가(트랜잭션째 끝남) |

50 은 `VERBOSITY=verbose` 로 돌아 오류 · 경고 줄에 SQLSTATE 가 붙습니다 —
`50 exit 3` 이 42501 이 아닌 다른 오류로 났으면 「실패 · 시간 초과」 로
갑니다. 이 출력은 운영 관측 기록입니다(8 에서 기록). K7 (가)라 갈래와
무관하게 회수하지 않습니다 — 사유는 [prod/runtime](../prod/runtime/README.md)
「1단-나 이월」 의 1 입니다.

## 7. 실행기 끔

1.  1 의 점검을 다시 합니다. **결과가 CHANGED · DENIED · STOP ·
    UNDECIDED 여도 끔은 진행하고** 결과만 보고합니다 — 끔은 잔여 정리라
    막지 않습니다. 단 2-3 판정의 `report.txt` 에 「자원 없음(첫 단계 —
    정책 없이 판정)」 줄이 있으면(본 apply 뒤인데 주제 · 큐가 없음 —
    이상), 끔과 잔여 0 까지는 하고 8 로 닫지 않고 멈춰 보고합니다.
1.  **지금 남은 실행기 자원 수 N**: 상태의 자원 주소 가운데 실행기 자원
    아홉의 주소(`runner_re` — 앵커 일치 허용 목록)만 셉니다(계정 ID · ARN
    은 나오지 않음). 4 가 끝까지 됐으면 9 이고, 4 가 중간에 실패했으면(예:
    인스턴스만 실패) 그보다 작습니다 — 이 걸음이 그런 부분 생성도 같은 길로
    정리합니다. N 이 0 이면 지울 것이 없으니 아래 「잔여 0」 으로 갑니다.
    `state list` 가 실패하면 N 을 0 으로 치지 않고 멈춥니다. 실행기 자원을
    더하거나 이름을 바꾸는 PR 은 `runner_re` 도 함께 고칩니다.

    ```shell
    cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
    runner_re='^(aws_iam_role\.sql_runner|aws_iam_role_policy_attachment\.sql_runner_ssm|aws_iam_role_policy\.sql_runner_master_secret|aws_iam_instance_profile\.sql_runner|aws_security_group\.sql_runner|aws_vpc_security_group_egress_rule\.sql_runner_(https|to_rds)|aws_vpc_security_group_ingress_rule\.rds_from_sql_runner|aws_instance\.sql_runner)\[0\]$'
    count_runner() {
      local addrs
      addrs="$(terraform state list)" || {
        printf '멈춤: state list 실패 — N 을 0 으로 치지 않습니다\n' >&2
        return 1
      }
      printf 'N=%s\n' "$(printf '%s\n' "${addrs}" | grep -cE "${runner_re}")"
    }
    count_runner
    ```

1.  **plan**: `-var` 없이(기본 꺼짐) plan 하고, 바뀌는 자원 수와 그 가운데
    실행기 자원 삭제 수를 셉니다(숫자만 냄).

    ```shell
    rm -f runner-off.tfplan
    terraform plan -out=runner-off.tfplan
    terraform show -json runner-off.tfplan | jq -r --arg re "${runner_re}" '
      [.resource_changes[] | select(.change.actions != ["no-op"])]
      | "바뀜 \(length) · 실행기 삭제 \(map(select(
          .change.actions == ["delete"] and (.address | test($re))
        )) | length)"'
    ```

1.  **확인 · 승인**: `Plan: 0 to add, 0 to change, N to destroy` 이고, 위
    줄이 「바뀜 N · 실행기 삭제 N」(둘 다 앞에서 센 N)일 때만 kty 승인을
    받습니다. 그 밖이면 apply 하지 않고 멈춥니다.
1.  **apply**: `terraform apply runner-off.tfplan && rm runner-off.tfplan`.
1.  **잔여 0**: 아래가 모두 0(첫 줄은 `N=0`, 마지막 둘은 `NoSuchEntity`
    오류)이어야 합니다.

    ```shell
    count_runner
    aws ec2 describe-instances --region ap-northeast-2 \
      --filters Name=tag:Name,Values=pokeclip-prod-sql-runner \
        Name=instance-state-name,Values=pending,running,stopping,stopped \
      --query 'length(Reservations[].Instances[])'
    aws ec2 describe-security-groups --region ap-northeast-2 \
      --filters Name=group-name,Values=pokeclip-prod-sql-runner \
      --query 'length(SecurityGroups)'
    aws ec2 describe-security-group-rules --region ap-northeast-2 \
      --filters "Name=group-id,Values=$(terraform output -raw rds_sg_id)" \
      --query "length(SecurityGroupRules[?Description=='PostgreSQL from SQL runner (only while enabled)'])"
    aws iam get-role --role-name pokeclip-prod-sql-runner \
      --query Role.RoleName --output text
    aws iam get-instance-profile --instance-profile-name pokeclip-prod-sql-runner \
      --query InstanceProfile.InstanceProfileName --output text
    ```

1.  로컬 페이로드 디렉터리를 지웁니다(결과 JSON 은 8 에서 옮긴 뒤):
    `rm -r "${payload}"`.

## 8. 기록

*   볼트 raw: 각 걸음의 명령 · 출력 · 판정 — 1 · 3-1 · 4(켬 전 · 켬 뒤) ·
    7 의 점검 첫 줄과 증거 디렉터리 이름, 3 의 `EngineVersion` 실제 값 · 비밀 태그, 5 의 sha256
    일곱 줄 · 머지 SHA · `prepare` · `apply` 결과, 6 의 `probe` 결과, 7 의
    잔여 0. 옮기기 전에 비밀 값 훑기를 합니다(계정 ID · ARN 은 가립니다).
*   Jira 코멘트: 6 의 출력과 판정 갈래 — 1단-가 흐름은 POK-304, 1단-나 ①
    재적용 흐름(아래 「1단-나 ①」 절)은 POK-428 입니다.

## 실패 · 시간 초과

send-command 상태가 `Success` 가 아니거나(`Failed` · `TimedOut` ·
`Cancelled` · `DeliveryTimedOut`) 판정이 어긋나면:

1.  같은 흐름에서 다시 보내지 않습니다.
1.  결과 JSON(`${payload}/<걸음>-result.json`)을 보관합니다.
1.  곧바로 7(실행기 끔)로 가서 잔여 0 을 확인합니다.
1.  원인을 고친 뒤 다음 흐름을 4(켬)부터 시작합니다. 코드가 원인이면 고치는
    PR 을 머지한 뒤입니다. DB 상태가 원인이면(예: 40 의 멤버십 · 직접
    CREATE) 손 SQL 을 씁니다. 5 는 00 부터 다시 돌려도 수렴합니다.

**손 SQL 규칙.** 이 규칙은 실패 · 시간 초과 뒤 원인을 고치는 교정용 손
SQL 에만 씁니다 — 해체 SQL 은 「되돌리기 · 해체」 를 따릅니다(지운 뒤라
`40_verify.sql` 이 반드시 실패합니다). 예외는 아래 「M6 읽기 질의」 입니다 —
교정이 아니라 읽기 전용(`SET TRANSACTION READ ONLY`) 판정 질의라 같은 흐름에
이어 보내지만, 보내는 꼴(새 페이로드 · SQL 원문을 보이고 새 승인)은
같습니다. 손 SQL 은 새 페이로드이고 kty 승인을 새로 받습니다 — 같은 흐름
안에서 임의로 다시 보내거나 덧붙여 보내지 않습니다. 페이로드는 5-1 의
`conn` 명령 뒤에 그 SQL 을 `psql -X -1 -v ON_ERROR_STOP=1` 로 돌리고 이어서
`40_verify.sql` 을 돌리는 JSON 입니다. 보내기 전에 SQL 원문을 kty 에게
보이고 승인을 받습니다. 판정은 5 와 같습니다(상태 `Success` · `VERIFY OK`).
실패하면 이 절의 1부터 다시 따릅니다.

## 되돌리기 · 해체

*   **SQL**: 파일마다 트랜잭션이라 실패한 파일은 남지 않습니다. 아래 해체
    SQL 은 M6 운영 SQL 적용 뒤 서비스 되돌리기 용도로 쓰지 않습니다(해체
    전용) — M6 결함은 앞으로 고칩니다([Media 교체 런북](media-swap.md) 「M6
    운영 SQL 경계 · 롤백 하한」). M6 장부 객체(새 표 · 열 · 트리거 · 함수)가
    있는 DB 에서는 아래 SQL 을 돌려 본 적이 없습니다 — 아래에 적은 확인은
    모두 M6-0 앞 판 SQL(10 · 20 · 30 · 40)에서 한 것입니다. 적용을 통째로
    되돌릴 때는 실행기를 켜고 마스터로 아래를 `psql -1` 로 돌립니다 — 표 ·
    함수(media_owner 소유 전부) → 앱 스키마 → DB 권한 → 앱 사용자 → 두
    계정 → media_owner 순서입니다. 앱 스키마 · 앱 사용자 줄은 앱 서버가
    처음 뜨기 전에만 씁니다. 뜬 뒤면 자기 스키마에 표가 있어 `DROP SCHEMA`
    (CASCADE 없음)가 실패합니다 — 앱 사용자가 자기 표의 소유자라 지우면 앱
    데이터가 함께 사라지므로 그 실패를 넘기지 않습니다. `DROP SCHEMA` 는
    `IF EXISTS` 라 앱 스키마가 없는 DB(1단-가 판만 적용 · 1단-나 ①
    되돌리기 뒤)에서도 막히지 않습니다. `REVOKE CREATE ON SCHEMA public`
    줄은 1단-가 판만 적용된 DB 몫이고 1단-나 ① 판 위에서는 아무것도 바꾸지
    않습니다. DB 권한 두 줄은 계정마다 준 CONNECT 를 거둬
    `DROP ROLE` 이 남은 권한으로 막히지 않게 하고, PUBLIC 의 CONNECT · TEMP
    를 RDS 기본으로 되돌립니다. `media_owner` 의 `public` USAGE · CREATE 는
    마스터가 준 것이라 `DROP OWNED` 가 거두지 못해 따로 거둡니다 — 없으면
    마지막 `DROP ROLE media_owner` 가 「privileges for schema public」 으로
    실패합니다. `DROP OWNED` 줄의 `WARNING:  no privileges could be revoked
    for "public"` 은 그 때문이고 정상입니다. 이 SQL 은 로컬 PG 17 에서
    superuser 대역과 RDS 마스터 꼴 대역(superuser 아님 · CREATEROLE ·
    CREATEDB · `rds_superuser` 멤버 · DB 소유자) 둘 다, 10 · 20 · 30 · 40
    적용 뒤와 1단-가 판만 적용한 뒤에 한 트랜잭션으로 끝까지 돌고 역할 · 앱
    스키마 · `public` 표가 0 이 되는 것까지만 확인했습니다(2026-10-25). 실제
    RDS 의 `rds_superuser` 특수 능력은 흉내 내지 못했고, 운영에서는 돌린 적이
    없습니다.

    ```sql
    SET ROLE media_owner;
    DROP OWNED BY media_owner;
    RESET ROLE;
    REVOKE USAGE, CREATE ON SCHEMA public FROM media_owner;
    DROP SCHEMA IF EXISTS
      app_auth, app_clip, app_chat_collector, app_chat_detector;
    REVOKE CREATE ON SCHEMA public
      FROM app_auth, app_clip, app_chat_collector, app_chat_detector;
    REVOKE CONNECT ON DATABASE pokeclip
      FROM media_indexer, media_janitor, app_auth, app_clip,
           app_chat_collector, app_chat_detector;
    GRANT CONNECT, TEMPORARY ON DATABASE pokeclip TO PUBLIC;
    DROP ROLE app_auth, app_clip, app_chat_collector, app_chat_detector;
    DROP ROLE media_indexer, media_janitor;
    DROP ROLE media_owner;
    ```

*   **실행기**: 7 입니다.
*   **자원**: RDS 는 삭제 보호가 켜져 있어, 보호를 끄는 PR 을 먼저 적용한 뒤
    destroy 합니다(최종 스냅숏은 남지 않습니다 — 결정 50). 비밀 그릇은
    지우면 30일 복구 대기에 들어가 같은 이름을 그동안 다시 만들 수 없습니다.
    rewind 버킷은 `force_destroy = false` 라 객체를 먼저 비워야 destroy
    됩니다. 리스 만료 때 AWS Nuke 가 삭제 보호를 어떻게 다루는지는
    미확인입니다.
*   **코드**: 머지 커밋을 `git revert -m 1` 합니다.

## 한계

*   2-3 판정은 dev 주체가 실행기 역할을 EC2 에 넘길 수 있는지
    (`iam:PassRole` · `iam:PassedToService` — 1 의 ②), 켜진 실행기에 명령 ·
    세션 · 연결을 보낼 수 있는지(`ssm:SendCommand` · `ssm:StartSession` ·
    `ssm:CreateAssociation` — 4 의 켬 뒤), 마스터 비밀을 읽을 수
    있는지(3-1)를 묻지만, 판정은 그 시점 한
    번씩입니다. 실행기가 켜진 동안 그 권한이 생기면 dev 주체가 마스터 비밀을
    읽을 수 있습니다. 그래서 SQL 직후 끕니다. 상자에 자동 종료는 없어
    7(끔)이 실행기를 닫는 유일한 길입니다. 절차가 어디서 멈추든 바로
    7(끔)을 합니다.
*   명령 질문은 인스턴스 ARN 만 묻고 문서(`AWS-RunShellScript`) 쪽 허용은
    묻지 않습니다. dev 배포 주체 둘은 그 문서 허용이 양성 대조라 갈리는
    쪽이 인스턴스이기 때문입니다. dev 정책이 인스턴스를 태그 조건으로 열면
    시뮬레이터가 누락 문맥을 내고 판정 불가(`MISSING_CONTEXT`)가 됩니다.
*   자동화(`ssm:StartAutomationExecution`)로 켜진 상자에 명령을 보내는 길은
    묻지 않습니다. 이 동작은 인스턴스 자원 유형을 받지 않아(자동화 문서 ·
    실행만 — [서비스 승인 참조](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ssm.html))
    인스턴스 ARN 으로 물을 수 없습니다. 실행기가 켜진 동안(4(켬)부터
    7(끔)까지)만 열린 길이라 위의 짧게 켜 두기가 이 한계도 좁힙니다.
*   SQL 실적용 시험(CI)은 Go 메타 명령 처리기로 돌고 psql 자체가 아니며,
    대역은 `rds_superuser` 가 아닙니다. TLS · `rds.force_ssl` 은 CI 가 재지
    못합니다.
*   연결 상한 20 · `statement_timeout` 60s 는 측정값이 아닙니다(O7e 에서
    다시 봅니다).

## M6 스키마 재실행

M6-0(POK-196 장부 스키마)이 고친 운영 SQL(`stream_schema.sql` ·
`20_schema.sql` · `30_grants.sql` · `40_verify.sql`)을, 1단-가 · 1단-나 ①을 적용한
RDS 에 다시 적용하는 절차입니다. 새 실행기는 없습니다 — 이 런북의 4–7 을
재실행 SHA 하나로 그대로 돕니다. 실행자 · 승인 규칙은 이 런북 머리와
같습니다.

용어:

*   **하한**(`stream_seq_floors`): 스트림마다 다음에 쓸 수 있는 가장 작은
    seq 입니다. 조각 행이 지워지면 트리거가 하한을 올리고, 하한보다 작은
    seq 의 INSERT 를 `seq_below_floor`(SQLSTATE 23514)로 거부합니다 — 지워진
    seq 를 다시 쓰지 못하게 합니다(seq 재사용 금지).
*   **하한 이상 이미지**: M6-0 머지 커밋을 조상으로 둔 커밋으로 빌드한 사이드카
    이미지입니다. 하한을 읽는 `LoadCursor`(M6-0 c2)와 기동 스키마
    확인(M6-0 c3)이 들어 있습니다. 경계 규칙은 [Media 교체 런북](media-swap.md)
    「M6 운영 SQL 경계 · 롤백 하한」 입니다.
*   **재실행 SHA**: 이 절을 도는 커밋입니다. 처음은 M6-0 머지 커밋이고, 그
    뒤 M6 운영 SQL 을 고친 전진 SQL PR 이 머지되면 그 머지 커밋입니다. 8 에
    기록된 SQL 적용 SHA(1단-가 · 1단-나 ① · 이 절의 재실행) 가운데 최신 것
    이상(그 SHA 를 조상으로 둔 커밋)이어야 합니다 — 아래 「다시 돌려도
    안전합니다」 의 함수 교체, 그리고 옛 판 10 이 앱 넷에 `public` CREATE 를
    다시 주기 때문입니다.

### M6 스키마 재실행 순서

기본 절차는 O7e(Media 처음 기동) **전에** 이 SQL 까지 끝내는 것입니다(kty
2026-10-26 확정). 그러면 하한 아래 사이드카가 트리거 있는 DB 에서 돈 적이
없어 교체 창이 없습니다.

| # | 하는 일 | 자리 |
|---|---|---|
| 1 | M6-0 · M6-6a 머지 | — |
| 2 | 이 절의 재실행(4–7 · 재실행 SHA = M6-0 머지 커밋) | 아래 「재실행 걸음」 |
| 3 | 사이드카 재푸시 · 기록 갱신 | [prod 산출물 런북](prod-artifacts.md) 「사이드카 재푸시(M6-0 뒤)」 |
| 4 | `prod/media` tfvars 의 `image_digests.sidecar` 를 3 의 다이제스트로 교체 | — |
| 5 | O7e | [Media 교체 런북](media-swap.md) 「O7e — 처음 기동」 |

O7e 가 이미 끝났으면(하한 아래 사이드카가 이미 prod 에서 돎) 이 순서
대신 Media 교체 런북 「한 창 절차」 를 따릅니다. 아직 O7e 전이면 이 순서를
건너뛰지 않습니다(kty 가 명시해 승인한 경우만 예외). 그 절차의 3 이 이 절의
재실행입니다.

*   M6-0 머지본 이후 사이드카는 기동 때 새 열 · 표 · 트리거를 확인하고,
    없으면 `schema_not_ready` 를 남기고 종료 코드 1 로 끝납니다. 그래서 2 를
    건너뛰고 3 의 이미지로 O7e 를 하면 사이드카가 뜨지 않습니다.
*   M6 재실행 뒤에는 「1단-나 ① 되돌리기」 의 스키마 권한 걸음(1단-가 머지
    커밋 SHA 로 5 를 돎)을 쓰지 않습니다. 그 SHA 는 위 용어의 재실행 SHA
    규칙에 어긋나고, 그 판 30 은 M6 권한(`media_indexer` 의 DELETE 등)을
    거두지 않아 그 판 40 의 `privilege:<역할>:<표>:<권한>` 단언이 남은
    권한으로 실패합니다(그 판 `40_verify.sql` 머리 주석 판독 · 돌려 보지는
    않음). M6 재실행 뒤 1단-나 ①을 되돌려야 하면 kty 에게 회부합니다.
*   다시 돌려도 안전합니다. M6 문장은 카탈로그 확인 DO 블록(열은
    `information_schema.columns` · 트리거는 `pg_trigger` 를 확인한 뒤
    만듦)과 `IF NOT EXISTS` 꼴이라 표 · 열 · 인덱스 · 트리거는 있으면
    건너뛰고 없는 것만 만듭니다(M6-0 이 `ddl.go` 와 같은 문장으로 두고
    `prod_sql_contract_test` 가 바이트를 대조). 함수는 다릅니다 — `CREATE OR
    REPLACE FUNCTION` 이라 그 SHA 의 본문으로 교체됩니다(트리거 함수 ·
    `SECURITY DEFINER` 함수 포함). 그래서 재실행 SHA 는 위 용어의 규칙대로
    8 에 기록된 최신 SHA 이상이어야 합니다 — 옛 SHA 로 돌리면 함수가 옛
    본문으로 돌아갑니다. 정의자 함수의 EXECUTE 회수(`REVOKE`)도 다시
    돌지만 멱등입니다. 10(1단-나 ① 판) · 20 · 30 도 다시 돌리면 같은
    상태로 수렴합니다(5-3 끝).
*   수렴한 DB 에 다시 돌리면 `20_schema.sql` 은 장부 표를 잠그지
    않습니다. 사본은 인덱스를 이름이 없을 때만 만들고(`to_regclass` 존재
    확인 DO 블록 — `CREATE INDEX IF NOT EXISTS` 는 이름을 확인하기 전에
    표를 잠급니다), 열 · 제약 · 트리거도 카탈로그를 확인한 뒤에만
    바꿉니다. 그래서 다시 돌리는 동안 장부 표에 ACCESS SHARE 보다 강한
    잠금이 없고, 송출 중인 사이드카와 어느 잠금 순서로도 서로 기다리지
    않습니다(`ledger_schema_pg_test` 의 재실행 무잠금 · 무교착 시험 — M6-0
    c5). 처음 적용이나 인덱스 · 열 · 제약 · 트리거가 빠진 DB 에서는 그것을
    만드는 동안 그 표를 잠그고, 그 표의 사이드카 · 앱 서버 질의는 파일
    끝까지 기다립니다. 새 인덱스가 든 SHA 의 첫 재실행도 빠진 DB 와
    같습니다. 예로 M6-3b 의 `stream_segments_uploaded_session_idx` 는
    비동시 `CREATE INDEX` 라 `stream_segments` 를 SHARE 로 잡고 힙 전체를
    훑는 동안 사이드카 INSERT · ③ 갱신을 막습니다(실측 300만 행 7.8초).
    사이드카는 잠금 대기 5초 뒤 백오프로 넘어가 유실은 없습니다. 그런
    재실행은 적용 직전 `stream_segments` 행 수를 기록에 한 줄 남깁니다.
    그래도 표준 오류에 `deadlock detected`(SQLSTATE
    40P01) 줄이 있으면 그 파일만 롤백되므로(`psql -1`) 아래 「재실행
    걸음」 의 `lock timeout` 과 같이 끄고 다음 흐름을 4 부터 다시 합니다.
    다만 그런 DB 에서는 교착의 희생자가 사이드카 쪽일 수도 있고, 그때
    재실행 SQL 은 끝까지 돌아 `VERIFY OK` 를 낼 수 있습니다 — `VERIFY OK`
    가 사이드카 무영향을 뜻하지 않습니다. 사이드카는 그 쓰기를 백오프
    재시도로 다시 해 회복합니다(클래스 40 재시도 — insert.go).

### 재실행 걸음

1.  **선행**: 재실행 SHA 가 `origin/develop` 에 있습니다. 5-1 의
    `make_payload` 가 조상인지 확인하고, 아니면 「멈춤」 을 냅니다.
1.  **선행(1단-나 ①)**: 8 의 기록(볼트 raw · Jira 코멘트)에 둘 중 하나가
    있습니다 — POK-428 코멘트의 「1단-나 ①」 절 통과, 또는 1단-가 첫 적용을
    그 절 걸음 1 경로(1–8 을 `a962a00f` 를 조상으로 둔 SHA 로 처음부터)로 한
    기록(POK-304 코멘트). 둘 다 없으면 이 절을 돌지 않고 그 절부터 끝냅니다 —
    재실행 SHA 의 10 은 1단-나 ① 판이라, 이 절을 먼저 돌면 그 절 걸음
    3(10 앞에 `public` 의 앱 계정 소유 객체가 0행인지 확인)을 건너뛰고 10
    이 적용됩니다. 그 절이 걸음 3 에서 멈추면 이 재실행도 멈추고 kty 에게
    회부합니다.
1.  **4(켬)**: 그대로 합니다 — 직전 점검 다시 · 실행기만 켜는 plan ·
    켠 시각 기록 · SSM 등록 · 켬 뒤 2-3 판정. 켠 시각부터 120분 안에 7 을
    시작합니다.
1.  **5-1(페이로드)**: `make_payload "<재실행 SHA>"`. M6-0 은 SQL 파일을
    더하지 않으므로 `names` 는 그대로입니다(전진 SQL PR 이 파일을 더했으면
    그 PR 이 `names` 도 고칩니다) — 이름이 다르면 `git show` 가 실패해
    「멈춤」 이 납니다.
1.  **잠금 대기 상한 넣기**: 적용 페이로드에 `lock_timeout` 을 겁니다.
    아래는 `apply.json` 의 마지막 명령(00 → 40 반복) 바로 앞에 한 줄을
    끼웁니다.

    ```shell
    jq '.commands |= (.[:-1] + ["export PGOPTIONS=\"-c lock_timeout=3s\""] + .[-1:])' \
      "${payload}/apply.json" >"${payload}/apply.json.new" &&
      mv "${payload}/apply.json.new" "${payload}/apply.json"
    jq -r '.commands[-2]' "${payload}/apply.json"   # export PGOPTIONS="-c lock_timeout=3s"
    ```

    `PGOPTIONS` 는 psql(libpq)이 접속할 때 서버에 넘기는 세션 설정이고,
    `lock_timeout` 은 잠금 하나를 기다리는 시간의 상한입니다 — 넘으면 그
    문장이 실패합니다. 파일 전체의 상한은 아닙니다. 한 파일(`psql -1` 의 한
    트랜잭션)이 잠금 여럿을 차례로 기다리면 대기가 쌓여 3초를 넘을 수 있고,
    잡은 잠금을 들고 있는 시간도 이 값이 제한하지 않습니다(prod 크기에서의
    보유 시간은 미확인). ALTER TABLE · CREATE TRIGGER 는 표에 강한 잠금을
    기다리는 동안 뒤에 오는 사이드카 · 앱 서버 질의를 줄 세웁니다. 3초는
    사이드카 핫 경로 트랜잭션 상한 `TxnDeadline`
    10초(`media/internal/index/store.go:168`)보다 짧게 고른 값입니다 — 잠금
    하나를 두고는 사이드카가 이 줄에 서서 시한에 걸리기 전에 SQL 쪽이 먼저
    물러납니다. 마지막 줄이 주석의 값과 다르면 보내지 않고 7 로 갑니다.
1.  **5-2 · 5-3(준비 · 적용)**: 그대로 합니다. 통과는 상태 `Success` 와
    표준 출력의 `PREFLIGHT OK` · `VERIFY OK` 입니다 — M6-0 판 `40_verify.sql`
    은 열 넷 · 표 둘 · 인덱스 · 트리거 둘 · M6 권한까지 봅니다.
    *   표준 오류에 `lock timeout` 줄이 있으면 잠금 상한에 걸린 것입니다.
        그 파일만 롤백됩니다(`psql -1`). 「실패 · 시간 초과」 대로 끄고
        다음 흐름을 4 부터 다시 합니다 — 같은 SHA 로 다시 돌리면 수렴합니다.
    *   「한 창 절차」 3 으로 온 흐름이면 끄기 전에 아래 「M6 읽기 질의」 의
        하한 질의를 같은 흐름에서 보냅니다(그 절차의 4).
1.  **6(advisory)**: 그대로 합니다. 뒤따르는 40 이 한 번 더 `VERIFY OK`
    인지가 무변경 확인입니다.
1.  **7(끔)**: 그대로 합니다 — 잔여 0 까지.
1.  **기록**: 8 의 항목에 재실행 SHA · `lock_timeout` 값 · sha256 일곱
    줄 · `apply` 결과를 더합니다. Jira POK-196 코멘트에 통과 시각과 SHA 를
    남깁니다. 이 기록이 사이드카 재푸시(위 순서 3)의 선행 조건입니다.

### M6 읽기 질의

M6 운영에서 DB 를 읽어 판정해야 하는 자리가 둘 있습니다. 둘 다 실행기를
켠 흐름 안에서 「실패 · 시간 초과」 의 손 SQL 규칙대로 보냅니다 — 새
페이로드 · SQL 원문을 kty 에게 보이고 승인 · 뒤이어 `40_verify.sql` ·
판정은 상태 `Success` 와 `VERIFY OK`. 질의는 읽기만 하고, 표 주인
`media_owner` 로 바꿔 읽습니다(마스터는 SET ROLE 만 가짐 — `10_roles.sql`).
표 주인은 쓰기 권한도 가지므로 질의 파일의 첫 줄은 언제나
`SET TRANSACTION READ ONLY;` 입니다 — `psql -1` 이 연 트랜잭션 안에서 SET
ROLE 앞에 둬, 질의에 쓰기 문장이 섞여도 서버가 거부합니다(SQLSTATE 25006).
아래 `make_read` 가 첫 줄을 확인하고 다르면 멈춥니다.

| 질의 | 쓰는 자리 | 통과 |
|---|---|---|
| 하한 질의(`floor.sql`) | 「한 창 절차」 4 · 6, 롤백 하한 사고 확인 | `(0 rows)` |
| 업로드 적체(`backlog.sql`) | 「한 창 절차」 7′ 앞 조건 · 정상 교체 8 의 회수 조건 | 세 행 모두 `pending` 0 · `failed` 0 |

**흐름.** 「M6 스키마 재실행」 과 같은 흐름이면 적용 뒤 이어서 보냅니다. 따로
읽을 때는 4(켬) → 5-1(기록된 최신 재실행 SHA) → 5-2 → 5-3 의 준비만 → 아래 읽기
→ 7(끔)입니다. 상자는 켤 때마다 새로 만들어지므로 준비를 건너뛰지 않습니다.

질의 원문을 페이로드 디렉터리에 씁니다. 하한 질의는 설계 M6 10절의 확인
질의입니다 — 남은 조각의 최댓값 + 1 이 하한보다 작은(꼬리 조각이 지워져
하한이 앞선) 스트림을 냅니다. 하한 아래 판이 그 스트림을 다시 색인하면
트리거가 거부하고 그 스트림 색인이 멈춥니다.

```shell
cat >"${payload}/floor.sql" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
SELECT f.stream_id, f.next_seq, m.max_seq
  FROM stream_seq_floors f
  LEFT JOIN LATERAL (SELECT pg_catalog.max(seq) AS max_seq FROM stream_segments s WHERE s.stream_id = f.stream_id) m ON true
 WHERE m.max_seq IS NULL OR m.max_seq + 1 < f.next_seq;
RESET ROLE;
SQL
```

업로드 적체는 사이드카 업로더가 세는 세 축 문장
(`media/internal/index/upload_store.go` 의 `countArchiveBacklogSQL` ·
`countPlaybackBacklogSQL` · `countInitBacklogSQL`)에 축 이름만 붙인
것입니다. 값은 차례로 대기 · 실패 · 크기 없음(앞 둘의 부분집합)입니다.

```shell
cat >"${payload}/backlog.sql" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
SELECT 'archive' AS axis, *
  FROM (SELECT pg_catalog.count(*) FILTER (WHERE upload_state = 'pending') AS pending,
               pg_catalog.count(*) FILTER (WHERE upload_state = 'failed') AS failed,
               pg_catalog.count(*) FILTER (WHERE upload_state IN ('pending','failed') AND bytes IS NULL) AS bytes_null
          FROM stream_segments WHERE local_path IS NOT NULL) a
UNION ALL
SELECT 'playback', *
  FROM (SELECT pg_catalog.count(*) FILTER (WHERE t.playback_upload_state = 'pending'),
               pg_catalog.count(*) FILTER (WHERE t.playback_upload_state = 'failed'),
               pg_catalog.count(*) FILTER (WHERE t.playback_upload_state IN ('pending','failed') AND t.bytes IS NULL)
          FROM stream_segments t
          JOIN stream_cutoffs  cut  ON cut.stream_id   = t.stream_id
         WHERE t.local_path IS NOT NULL AND t.session_id IS NOT NULL
           AND t.seq >= cut.cutoff_seq) p
UNION ALL
SELECT 'init', *
  FROM (SELECT pg_catalog.count(*), 0::bigint,
               pg_catalog.count(*) FILTER (WHERE NOT EXISTS (
                   SELECT 1 FROM stream_segments g
                    WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
                      AND g.local_path IS NOT NULL AND g.bytes IS NOT NULL))
          FROM stream_sessions s
         WHERE s.init_uploaded_at IS NULL AND s.state <> 'ended') i;
RESET ROLE;
SQL
```

읽기 페이로드는 적용 페이로드의 접속 명령(`apply.json` 에서 마지막 명령을
뺀 것)에 그 질의와 `40_verify.sql` 을 잇습니다. 질의 원문은 base64 로 실어
상자에서 풉니다.

```shell
make_read() {  # make_read <걸음 이름> <SQL 파일>
  [ "$(head -n 1 "$2")" = 'SET TRANSACTION READ ONLY;' ] ||
    { echo "멈춤: $2 의 첫 줄이 SET TRANSACTION READ ONLY; 가 아님" >&2; return 1; }
  jq --rawfile sql "$2" '
    .commands = .commands[:-1] + [
      "printf %s " + ($sql | @base64 | @sh) + " | base64 -d >/root/rt1a/read.sql",
      "echo == read",
      "psql -X -1 -v ON_ERROR_STOP=1 -f /root/rt1a/read.sql",
      "echo == 40_verify.sql",
      "psql -X -1 -v ON_ERROR_STOP=1 -f 40_verify.sql"
    ]
    | .executionTimeout = ["300"]' \
    "${payload}/apply.json" >"${payload}/$1.json"
}
make_read floor "${payload}/floor.sql" && send_step floor
make_read backlog "${payload}/backlog.sql" && send_step backlog
```

*   표준 출력의 `== read` 뒤가 질의 결과이고, 그 뒤 `VERIFY OK` 가 있어야
    합니다. 기록에 옮기는 것은 `== read` 뒤 결과 줄만입니다(하한 질의는
    아래대로 행 수만). 표준 오류는 계정 ID · ARN · RDS 끝점 · 사설 IP ·
    인스턴스 id 를 가린 뒤에만 옮깁니다([Media 교체 런북](media-swap.md) 「O7e 뒤 측정 · 기록」 의 가림
    목록).
*   하한 질의 결과는 psql 끝 줄의 행 수(`(0 rows)` · `(1 row)` 꼴)만
    기록합니다 — `stream_id` 목록은 볼트 raw · Jira 에 옮기지
    않습니다(kty 2026-10-26). 창 동안 탈퇴 정리된 스트림 목록이라 탈퇴자와
    이어질 수 있습니다.
*   하한 질의에 행이 나오면 그 스트림이 하한 아래 판에서 걸리는 스트림입니다.
    「한 창 절차」 에서는 그 자리의 갈래를 따르고, 사고 확인이면 Media 교체
    런북 「M6 운영 SQL 경계 · 롤백 하한」 의 복구로 갑니다.

### 발행 잔여 질의

[Media 교체 런북](media-swap.md) 「O8a 당김 교체」 의 계약9 발행 판정 질의
입니다(kty 2026-11-03). 흐름 · 승인 · 기록 규칙은 위 「M6 읽기 질의」 와
같습니다 — 읽기 전용 트랜잭션에서 표 주인으로 읽고, `make_read` 로 보내고,
`VERIFY OK` 를 함께 봅니다.

**시험 회차**는 머리 세션(`session_id = round_id`)의 `stream_id` 가 시험
ID 이고 `started_at` 이 「O8a 당김 교체」 걸음 8 시각 이후인 회차 전부입니다.

| 행 | 뜻 | 쓰는 자리 · 통과 |
|---|---|---|
| `Q1` | 시험 회차의 started 행(sequence 1 · `abandoned` 아님) 가운데 같은 회차의 ended 행(sequence 2)이 없는 수 | 종료 판정 · 롤백 · 교체 전 — 0 |
| `Q2` | 시험 회차 outbox 행 가운데 `pending` 수(SNS 재시도 중 포함) | 종료 판정 · 롤백 — 0 |
| `Q3` | 시험 회차 세션 가운데 `round_closed_at` 이 비었거나 `ended` 아닌 수 | 종료 판정 — 0 |
| `Q3_all` | 시험 ID 의 모든 세션 가운데 `ended` 아니거나 회차가 열린 수(`started_at` 조건 없음) | 걸음 13 직전 — 0 |
| `abandoned` | 시험 ID 의 outbox `abandoned` 행 수 | 종료 판정 — 0 |
| `rounds` | 시험 회차 수 | 종료 판정 · 롤백 · 교체 전 — 1 이상(회차 수는 고정하지 않음). 걸음 13 전, 또는 걸음 13 루프백이 회차를 만들지 못하고 실패했을 때(`LOOPBACK FAIL test-key` · `pull`, 인증 거부 · 연결 실패)는 시험 회차가 없어 0 이고, 그때는 Q4 로 봄 — 회차가 하나라도 생겼으면 1 이상 |
| `Q4_<상태>` | 상태별 outbox 행 수(전체) — 행이 없으면 이 줄도 없음 | 걸음 0 · 8 · 12 의 기준선 — `Q4_` 줄 0 |

*   `Q1 > 0 ∧ Q3 = 0` 은 자동 복구 경로가 없는 갈래입니다(Media 교체 런북
    「O8a 당김 — 롤백」 — kty 회부).
*   **`rounds` 가 0 이면 Q1 · Q2 · Q3 · `abandoned` 의 0 은 판정이
    아닙니다.** Q1 · Q2 · Q3 는 시험 회차 안에서만 세므로, 시험 회차를 하나도
    잡지 못하면 실제 상태와 무관하게 0 이 됩니다. `abandoned` 는 시험 ID 로만
    거르고 시각 조건이 없어 `rounds` 가 0 이어도 0 이 아닐 수 있지만(걸음 8
    전의 행도 셈), 시험 ID 가 틀리면 역시 0 이 됩니다.
*   걸음 13 뒤인데 `rounds` 가 0 이면 멈추고 시험 ID · 걸음 8 시각을 다시
    봅니다. 이 멈춤은 입력을 바로잡아 질의를 다시 보내는 자리라, Media 교체
    런북 「O8a 당김 교체 걸음」 의 **멈춤 자리**(kty 회부 · 보고 — 대안을
    고르지 않는 자리) 목록에는 들지 않습니다. 걸음 13 루프백이 회차를 만들지
    못하고 실패했으면 0 이 맞고, 그때는 걸음 13 전과 같이 Q4 로 봅니다(위
    `rounds` 행).
*   시험 ID · 걸음 8 시각을 질의에 넣기 전에 꼴을 봅니다 — 시험 ID 는 26자
    Crockford base32 대문자, 시각은 10자리 epoch 초입니다(밀리초 13자리는 꼴
    밖). 꼴 밖이면 함수가 「멈춤」 을 내고 질의를 만들지도 보내지도 않습니다
    (질의에 따옴표 · 문장이 끼지 않게).

```shell
make_publish_rest() {  # make_publish_rest <시험 ID> <걸음 8 시각 — epoch 초>
  local test_id="$1" since="$2"
  [[ "${test_id}" =~ ^[0-9A-HJKMNP-TV-Z]{26}$ && "${since}" =~ ^[0-9]{10}$ ]] ||
    { echo "멈춤: 시험 ID 또는 시각의 꼴이 틀림" >&2; return 1; }
  cat >"${payload}/publish-rest.tmpl" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
WITH test_rounds AS (
  SELECT h.round_id
    FROM stream_sessions h
   WHERE h.session_id = h.round_id AND h.stream_id = '@@TEST_ID@@'
     AND h.started_at >= pg_catalog.to_timestamp(@@SINCE@@)
)
SELECT 'Q1' AS q, pg_catalog.count(*) AS n
  FROM broadcast_lifecycle_outbox o
 WHERE o.round_id IN (SELECT round_id FROM test_rounds)
   AND o.sequence = 1 AND o.state <> 'abandoned'
   AND NOT EXISTS (SELECT 1 FROM broadcast_lifecycle_outbox e
                    WHERE e.round_id = o.round_id AND e.sequence = 2)
UNION ALL
SELECT 'Q2', pg_catalog.count(*)
  FROM broadcast_lifecycle_outbox o
 WHERE o.round_id IN (SELECT round_id FROM test_rounds) AND o.state = 'pending'
UNION ALL
SELECT 'Q3', pg_catalog.count(*)
  FROM stream_sessions s
 WHERE s.round_id IN (SELECT round_id FROM test_rounds)
   AND (s.round_closed_at IS NULL OR s.state <> 'ended')
UNION ALL
SELECT 'Q3_all', pg_catalog.count(*)
  FROM stream_sessions s
 WHERE s.stream_id = '@@TEST_ID@@'
   AND (s.state <> 'ended' OR (s.round_id IS NOT NULL AND s.round_closed_at IS NULL))
UNION ALL
SELECT 'abandoned', pg_catalog.count(*)
  FROM broadcast_lifecycle_outbox o
 WHERE o.ingest_stream_id = '@@TEST_ID@@' AND o.state = 'abandoned'
UNION ALL
SELECT 'rounds', pg_catalog.count(*) FROM test_rounds
UNION ALL
(SELECT 'Q4_' || o.state, pg_catalog.count(*)
   FROM broadcast_lifecycle_outbox o GROUP BY o.state ORDER BY o.state);
RESET ROLE;
SQL
  sed -e "s/@@TEST_ID@@/${test_id}/g" -e "s/@@SINCE@@/${since}/g" \
    "${payload}/publish-rest.tmpl" >"${payload}/publish-rest.sql" ||
    { echo "멈춤: 질의 파일을 만들지 못함" >&2; return 1; }
  make_read publish-rest "${payload}/publish-rest.sql"
}
make_publish_rest '<시험 ingestStreamId>' '<걸음 8 시각 — epoch 초>' &&
  send_step publish-rest
```

*   기록에 옮기는 것은 `== read` 뒤 `q | n` 줄뿐입니다. 회차 id · 행 원문은
    옮기지 않습니다.

## POK-525 운영 SQL 2 · 읽기 질의

POK-525(조각 키를 업로드 때부터 회차 경로에) 교체의 DB 몫입니다. 걸음
순서와 판정은 [Media 교체 런북](media-swap.md) 「POK-525 교체」 가 정본이고,
이 절은 그 걸음이 보내는 SQL 과 보내는 법입니다. 실행자 · 승인 규칙과 흐름은
「M6 읽기 질의」 와 같습니다 — 4(켬) → 5-1(아래 SHA) → 5-2 → 5-3 의 준비만 →
아래 보냄 → 7(끔). 보낼 때마다 SQL 원문을 kty 에게 보이고 승인을 받습니다.

용어:

*   **새 꼴 · 옛 꼴**: 되감기 조각 키의 두 꼴입니다 — 새 꼴
    `dvr/{stream}/{round}/seg/{seq}.m4s`(회차 경로) · 옛 꼴
    `dvr/{stream}/seg/{seq}.m4s`. 사이드카의 새 꼴 쓰기 스위치
    (`PLAYBACK_ROUND_KEYS`)가 켜져 있을 때만 새 꼴을 씁니다.
*   **② · ③ · init**: 사이드카 업로더가 올리는 세 갈래(축)입니다 — ② 는
    녹화 파일 그대로(`upload_state` · 질의의 `archive` 행), ③ 은 되감기용으로
    다시 짠 조각(`playback_upload_state` · `playback` 행), init 은 회차의 머리
    조각(`init_uploaded_at` · `init` 행)입니다. 뜻은 media README 「M4 ⓐ 용어」 의
    「②·③·init 축」 과 같습니다.
*   **운영 SQL 2**(`60_round_frozen.sql`): `stream_sessions.round_id` 를
    다른 값으로 바꾸는 UPDATE 를 거부하는 트리거(회차 불변 트리거
    `stream_sessions_round_frozen`)를 깝니다. 운영 SQL 1(00–40 — 「M6 스키마
    재실행」)과 다른 때, 스위치를 켜고 첫 새 꼴 행이 생긴 뒤(교체 걸음 4.9)
    혼자 돌립니다. 그 앞에 깔면 옛 상자 A 가 늦은 4D 를 처리하던 문장이
    거부돼 그 4D 가 사라집니다.
*   **SHA**: 이 절의 5-1 은 교체 걸음 3(운영 SQL 1 — 「M6 스키마 재실행」)에
    쓴 재실행 SHA 로 돕니다. 8 에 기록된 최신 SQL 적용 SHA 이고, 그 SHA 에
    `60_round_frozen.sql` 이 있어야 합니다(없으면 아래 `git show` 가
    실패합니다). 뒤따르는 `40_verify.sql` 도 그 SHA 의 판이라 DB 와 맞습니다.

### POK-525 SQL 걸음 페이로드

운영 SQL 2 와 롤백 짝은 같은 꼴로 보냅니다 — 적용 페이로드의 접속 명령
(`apply.json` 에서 마지막 명령을 뺀 것) 뒤에 SQL 파일 하나를 `psql -X -1 -v
ON_ERROR_STOP=1` 로 돌리고 이어서 `40_verify.sql` 을 돌립니다(「실패 · 시간
초과」 의 손 SQL 꼴). SQL 은 base64 로 실어 상자에서 풀고, 로컬에서 잰
sha256 과 상자에서 잰 값이 다르면 psql 을 부르기 전에 멈춥니다(`set -eu`).

```shell
make_sql_step() {  # make_sql_step <걸음 이름> <SQL 파일>
  local sum
  sum="$(shasum -a 256 "$2" | cut -d ' ' -f 1)" ||
    { echo "멈춤: $2 의 sha256 을 재지 못함" >&2; return 1; }
  jq --rawfile sql "$2" --arg sum "${sum}" '
    .commands = .commands[:-1] + [
      "printf %s " + ($sql | @base64 | @sh) + " | base64 -d >/root/rt1a/step.sql",
      "echo " + (($sum + "  /root/rt1a/step.sql") | @sh) + " | sha256sum -c -",
      "echo == step",
      "psql -X -1 -v ON_ERROR_STOP=1 -f /root/rt1a/step.sql",
      "echo == 40_verify.sql",
      "psql -X -1 -v ON_ERROR_STOP=1 -f 40_verify.sql"
    ]
    | .executionTimeout = ["300"]' \
    "${payload}/apply.json" >"${payload}/$1.json" || return 1
  printf 'STEP %s sha256 %s\n' "$1" "${sum}"
}
```

### POK-525 운영 SQL 2 적용

교체 걸음 4.9 입니다. 선행은 4.8 통과(켬 뒤 루프백 · Q3)와 **B 방송 경로 0**
(Media 교체 런북 「상자 스크립트」 의 `paths.sh` 로 B `PATHS 0` — 보내기
직전)입니다. 트리거를 만드는 동안 `stream_sessions` 쓰기가 잠깐 막히므로
방송 없는 창에서만 돌립니다.

```shell
git show "<SHA>:infra/terraform/prod/runtime/sql/60_round_frozen.sql" \
  >"${payload}/60_round_frozen.sql" &&
  make_sql_step round_frozen "${payload}/60_round_frozen.sql" &&
  send_step round_frozen
```

*   통과 = 상태 `Success` · 표준 출력의 `ROUND FROZEN OK` 와 그 뒤
    `VERIFY OK`. `40_verify.sql` 은 트리거를 단언하지 않습니다(운영 SQL 1
    뒤에도 돌기 때문) — 트리거 모양은 60 끝의 DO 블록이 단언합니다.
*   잠금 대기 상한은 60 파일이 스스로 겁니다(`SET LOCAL lock_timeout =
    '3s'`). `make_sql_step` 은 `apply.json` 의 마지막 명령만 빼고 나머지를
    물려받는데, 이 흐름의 5-1 이 새로 만든 `apply.json` 에는 「재실행
    걸음」 의 `PGOPTIONS` 줄이 없습니다(그 줄은 「재실행 걸음」 의 jq 가 끼울
    때만 생김). 표준 오류에
    `lock timeout`(SQLSTATE 55P03)이 있으면 파일 전체가 롤백돼 트리거는
    없습니다. 「실패 · 시간 초과」 대로 끄고, 다음 흐름에서 B `PATHS 0` 을
    다시 본 뒤 4(켬)부터 다시 합니다.
*   `round frozen failed: <식별자>` 면 같은 이름의 다른 모양 트리거가 이미
    있는 것입니다(식별자 뜻은 60 파일 머리). 지우지 않고 멈춰 kty 에게
    보고합니다.
*   다시 돌려도 같습니다 — 트리거는 이름이 없을 때만 만들고, 수렴한
    재실행은 표를 잠그지 않습니다.
*   B 를 다시 띄우지 않습니다. 다음 기동부터 사이드카의 기동 신호가
    `round_frozen=present` 입니다.
*   기록(8)에 SHA · 60 의 sha256(`STEP` 줄) · 통과 시각을 더합니다.

### POK-525 트리거 롤백 짝

트리거 결함 대응 전용입니다 — 교체 걸음 4.9 뒤 정당한 세션 UPDATE(상태
전이 · 표식 · 동결처럼 `round_id` 를 바꾸지 않는 문장)가 SQLSTATE 23514
`stream_sessions.round_id is frozen` 으로 거부될 때만 씁니다. **A 복귀
수단이 아닙니다** — 새 꼴 행이 생긴 뒤에는 트리거를 지워도 옛 이미지가 새
꼴 키를 읽지 못합니다(Media 교체 런북 「POK-525 롤백」).

```shell
cat >"${payload}/round_frozen_drop.sql" <<'SQL'
SET ROLE media_owner;
SET LOCAL lock_timeout = '3s';
DROP TRIGGER IF EXISTS stream_sessions_round_frozen ON stream_sessions;
RESET ROLE;
SQL
make_sql_step round_frozen_drop "${payload}/round_frozen_drop.sql" &&
  send_step round_frozen_drop
```

*   통과 = 상태 `Success` · `VERIFY OK`. 트리거 함수
    (`stream_sessions_reject_round_change`)는 지우지 않습니다.
*   `DROP TRIGGER` 는 `stream_sessions` 에 `AccessExclusiveLock`(읽기도
    막음)을 잡습니다(PG17 `pg_locks` 실측). 그래서 B `PATHS 0` 인 방송
    없는 창에서 보내고, 잠금 대기는 3초로
    끊습니다. `lock timeout` 이면 아무것도 지워지지 않습니다 — 「실패 ·
    시간 초과」 대로 끄고 다음 흐름에서 다시 합니다.
*   지운 뒤에는 고친 60 파일을 담은 PR 을 머지하고, 그 머지 SHA 로 위
    「POK-525 운영 SQL 2 적용」 을 다시 돕니다.
*   이 짝은 Media 교체 런북 「M6 운영 SQL 경계 · 롤백 하한」 의 「하지 않는
    것 셋」 과 대상이 다릅니다. 그 셋은 **seq 하한** 트리거
    (`seq_below_floor`) · 하한 행 · 하한 아래 이미지이고 지금도 하지
    않습니다. 이 짝은 **회차 불변** 트리거 하나만 지웁니다.
*   로컬(`ENSURE_SCHEMA` 켬)은 사이드카가 뜰 때마다 같은 트리거를 다시
    깝니다. 로컬에서 지운 것은 다음 기동까지만 가는 시험용이고 이 절차가
    아닙니다.

### POK-525 읽기 질의

이 절의 Q1 – Q4 는 위 「발행 잔여 질의」 의 Q1 – Q4 와 이름만 같고 다른
질의입니다. media-swap 「POK-525 교체」 · 롤백 · 다음 교체의 Q 는 이 절을
가리킵니다.

교체 걸음이 DB 를 읽어 판정하는 질의 다섯입니다. Q1 · Q2 · Q3 은 설계
(POK-525 r9.4 6절) 문장 그대로이고(Q2 init 행의 셋째 칸만 NULL), Q2-B 는
계획(r2 4-7)이, Q4 는 교체 걸음 4.10 의 「업로드 · 발행 정상 · VOD 문서 URI
새 꼴」(설계 9-3) 판정으로 더한 것입니다. 각 질의는 `-- BEGIN <이름>` · `-- END <이름>`
표지 안에 있고, `media/internal/index/runbook_query_pg_test.go` 가 표지 사이
글자를 그대로 뽑아 PG 에서 돌립니다 — 질의를 고치면 그 시험이 고친 글자를
잽니다. 보내는 꼴은 「M6 읽기 질의」 의 `make_read` 입니다(첫 줄
`SET TRANSACTION READ ONLY;` · 뒤이어 `40_verify.sql`).

| 질의 | 쓰는 자리(교체 걸음) | 통과 |
|---|---|---|
| Q1 새 꼴 행 수(`q1.sql`) | 4.2 · 4.6 · 「켜기 전」 선언 판정(롤백) | `round_keys` 0 |
| Q2 A 배출(`q2.sql`) | 4.4 · 4.11 · media-swap.md 「POK-525 다음 교체」 8 · 10(옛 상자 B 배출) | 세 행 모두 `waiting` 0 |
| Q2-B B 배출(`q2b.sql`) | 롤백 「켜기 전」 의 B 회수 앞 | 세 행 모두 `waiting` 0 |
| Q3 켬 뒤 키 꼴 대조(`q3.sql`) | 4.8 · 4.10 | `matched` 1 이상 · `mismatched` 0 |
| Q4 시험 방송 회차(`q4.sql`) | 4.10(방송을 끝내고 국면이 `vod` 가 된 뒤) | `pass` 가 `t` |

자리 표시는 그 걸음에서 적어 둔 값으로 채웁니다. 시각은 UTC 로 적습니다
(`date -u +%Y-%m-%dT%H:%M:%SZ`).

| 자리 | 값 |
|---|---|
| `<since>` | B 기동(교체 걸음 4.1)의 apply 를 시작한 시각 — 그 앞에는 새 꼴을 쓰는 이미지가 없다. 더 이른 값은 더 많이 셀 뿐이다 |
| `<cut>` | ingest 전환(4.3)에서 `tg_health` 의 `ingest` 가 B 한 대 healthy 가 된 시각 + 30초(등록 해제 지연). 「POK-525 다음 교체」 에서는 새 상자 C 한 대 |
| `<cut2>` | 롤백에서 ingest 를 A 로 되돌린 뒤 `ingest` 가 A 한 대 healthy 가 된 시각 + 30초 |
| `<on>` | `SIDECAR ROUND KEYS OK on` 을 본 시각(4.7) |
| `<test>` | 루프백 시험 스트림의 `ingestStreamId` |

```shell
cat >"${payload}/q1.sql" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
-- BEGIN Q1
-- Q1 새 꼴 행 수 — <since> = B 기동 시각(그 앞에는 새 꼴을 쓰는 이미지가 없다). 켜기 전 = 0 이어야 한다.
SELECT pg_catalog.count(*) AS round_keys
  FROM stream_segments
 WHERE start_wall_utc >= '<since>'::timestamptz
   AND playback_s3_key ~ '^dvr/[^/]+/[^/]+/seg/';
-- END Q1
RESET ROLE;
SQL
cat >"${payload}/q2.sql" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
-- BEGIN Q2
-- Q2 A 배출 — <cut> = ingest 전환 완료(tg_health B healthy) + 30초(등록 해제 지연), <test> = 루프백 시험 스트림.
-- 통과 = 세 행 모두 waiting 0. bytes_null 은 업로더가 집지 않는 행(참고 — 0 이 아니면 kty 보고). init 행은 bytes 개념이 없어 NULL.
SELECT 'archive' AS axis,
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NOT NULL) AS waiting,
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NULL)     AS bytes_null
  FROM stream_segments t
 WHERE t.upload_state IN ('pending','failed') AND t.local_path IS NOT NULL
   AND t.stream_id <> '<test>'
   AND t.start_wall_utc <  '<cut>'::timestamptz
   AND t.start_wall_utc >= pg_catalog.now() - interval '24 hours'
UNION ALL
SELECT 'playback',
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NOT NULL),
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NULL)
  FROM stream_segments t
  JOIN stream_sessions sess ON sess.session_id = t.session_id
  JOIN stream_cutoffs  cut  ON cut.stream_id   = t.stream_id
 WHERE t.playback_upload_state IN ('pending','failed') AND t.local_path IS NOT NULL
   AND t.playback_s3_key IS NOT NULL AND t.playback_pdt IS NOT NULL
   AND sess.state <> 'ended' AND t.seq >= cut.cutoff_seq
   AND t.stream_id <> '<test>'
   AND t.start_wall_utc <  '<cut>'::timestamptz
   AND t.start_wall_utc >= pg_catalog.now() - interval '24 hours'
UNION ALL
SELECT 'init', pg_catalog.count(*), NULL::bigint AS bytes_null
  FROM stream_sessions s
 WHERE s.init_uploaded_at IS NULL AND s.state <> 'ended'
   AND s.stream_id <> '<test>'
   AND s.started_at < '<cut>'::timestamptz;
-- END Q2
RESET ROLE;
SQL
cat >"${payload}/q2b.sql" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
-- BEGIN Q2-B
-- Q2-B B 배출 — <cut2> = ingest 를 A 로 되돌린 시각(tg_health A healthy) + 30초, <test> = 루프백 시험 스트림.
-- 아래쪽 시각 경계가 없다 — ingest 를 B 로 옮기기 전(Q2 의 cut 앞)에 B 가 만든 조각도 센다(② · ③ 은 녹화 보존
-- 24시간 창 안만). A 몫 행도 함께 세어 보수적이다(A 가 살아 있어 곧 0). init 은 세션의 최신 조각에서 다시 만들므로
-- 시작 시각 창을 두지 않는다(오래 이어진 방송의 init 도 B 디스크에 기댄다). 통과 = 세 행 모두 waiting 0.
SELECT 'archive' AS axis,
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NOT NULL) AS waiting,
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NULL)     AS bytes_null
  FROM stream_segments t
 WHERE t.upload_state IN ('pending','failed') AND t.local_path IS NOT NULL
   AND t.stream_id <> '<test>'
   AND t.start_wall_utc <  '<cut2>'::timestamptz
   AND t.start_wall_utc >= pg_catalog.now() - interval '24 hours'
UNION ALL
SELECT 'playback',
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NOT NULL),
       pg_catalog.count(*) FILTER (WHERE t.bytes IS NULL)
  FROM stream_segments t
  JOIN stream_sessions sess ON sess.session_id = t.session_id
  JOIN stream_cutoffs  cut  ON cut.stream_id   = t.stream_id
 WHERE t.playback_upload_state IN ('pending','failed') AND t.local_path IS NOT NULL
   AND t.playback_s3_key IS NOT NULL AND t.playback_pdt IS NOT NULL
   AND sess.state <> 'ended' AND t.seq >= cut.cutoff_seq
   AND t.stream_id <> '<test>'
   AND t.start_wall_utc <  '<cut2>'::timestamptz
   AND t.start_wall_utc >= pg_catalog.now() - interval '24 hours'
UNION ALL
SELECT 'init', pg_catalog.count(*), NULL::bigint AS bytes_null
  FROM stream_sessions s
 WHERE s.init_uploaded_at IS NULL AND s.state <> 'ended'
   AND s.stream_id <> '<test>'
   AND s.started_at < '<cut2>'::timestamptz;
-- END Q2-B
RESET ROLE;
SQL
cat >"${payload}/q3.sql" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
-- BEGIN Q3
-- Q3 켬 뒤 키 꼴 대조 — <on> = 켬 OK 시각. 통과 = matched 1 이상 · mismatched 0.
SELECT pg_catalog.count(*) FILTER (WHERE pg_catalog.split_part(t.playback_s3_key, '/', 3) = s.round_id) AS matched,
       pg_catalog.count(*) FILTER (WHERE pg_catalog.split_part(t.playback_s3_key, '/', 3) IS DISTINCT FROM s.round_id) AS mismatched
  FROM stream_segments t
  JOIN stream_sessions s ON s.session_id = t.session_id
 WHERE t.start_wall_utc >= '<on>'::timestamptz
   AND t.playback_s3_key ~ '^dvr/[^/]+/[^/]+/seg/';
-- END Q3
RESET ROLE;
SQL
cat >"${payload}/q4.sql" <<'SQL'
SET TRANSACTION READ ONLY;
SET ROLE media_owner;
-- BEGIN Q4
-- Q4 시험 방송 회차 — <test> = 시험 스트림, <on> = 켬 OK 시각. <on> 뒤 시작한 그 스트림의 가장 늦은 회차 하나를 본다.
-- 국면 vod 는 frozen_with_gaps 도 포함하고 미업로드 조각은 정산 기한 뒤 GAP 으로 마감되므로, 국면이 아니라 아래를 모두 본다.
-- 통과 = pass t — 회차 있음 · 끝나지 않은 세션(live · ending) 0 · 회차 닫힘 · 최신 세션 vod_state frozen(frozen_with_gaps 아님)과
-- VOD 문서 키 · 조각(컷오프 이상) 1 이상 · ③ 미업로드 0 · 그 회차 새 꼴이 아닌 키 0 · GAP 기록 0.
WITH r AS (
  SELECT s.round_id
    FROM stream_sessions s
   WHERE s.stream_id = '<test>' AND s.round_id IS NOT NULL
     AND s.started_at >= '<on>'::timestamptz
   ORDER BY s.started_at DESC, s.session_id DESC
   LIMIT 1
),
sess AS (
  SELECT s.session_id, s.state, s.started_at, s.round_closed_at, s.vod_state, s.vod_s3_key
    FROM stream_sessions s
    JOIN r ON s.round_id = r.round_id
   WHERE s.stream_id = '<test>'
),
latest AS (
  SELECT vod_state, vod_s3_key
    FROM sess
   ORDER BY (state = 'live') DESC, started_at DESC, session_id DESC
   LIMIT 1
),
seg AS (
  SELECT t.playback_upload_state, t.playback_s3_key
    FROM stream_segments t
    JOIN sess ON sess.session_id = t.session_id
    LEFT JOIN stream_cutoffs cut ON cut.stream_id = t.stream_id
   WHERE t.stream_id = '<test>' AND t.seq >= COALESCE(cut.cutoff_seq, 0)
),
v AS (
  SELECT (SELECT round_id FROM r) AS round_id,
         (SELECT pg_catalog.count(*) FROM sess WHERE state <> 'ended') AS not_ended,
         (SELECT pg_catalog.count(*) FROM sess WHERE round_closed_at IS NULL) AS not_closed,
         (SELECT vod_state FROM latest) AS vod_state,
         (SELECT vod_s3_key IS NOT NULL FROM latest) AS vod_doc,
         (SELECT pg_catalog.count(*) FROM seg) AS segments,
         (SELECT pg_catalog.count(*) FROM seg WHERE playback_upload_state <> 'uploaded') AS not_uploaded,
         (SELECT pg_catalog.count(*) FROM seg
           WHERE playback_s3_key IS NULL OR playback_s3_key !~ '^dvr/[^/]+/[^/]+/seg/'
              OR pg_catalog.split_part(playback_s3_key, '/', 3) IS DISTINCT FROM (SELECT round_id FROM r)) AS not_round_keys,
         (SELECT pg_catalog.count(*) FROM stream_published_gaps g
            JOIN sess ON sess.session_id = g.session_id
           WHERE g.stream_id = '<test>') AS gaps
)
SELECT v.*,
       COALESCE(v.round_id IS NOT NULL AND v.not_ended = 0 AND v.not_closed = 0
                AND v.vod_state = 'frozen' AND v.vod_doc
                AND v.segments > 0 AND v.not_uploaded = 0 AND v.not_round_keys = 0 AND v.gaps = 0, false) AS pass
  FROM v;
-- END Q4
RESET ROLE;
SQL
```

자리를 채운 사본을 만들어 보냅니다. `fill_read` 는 자리마다 받는 꼴이
정해져 있고, 아래 가운데 하나면 멈춥니다. `<SHA>` 처럼 꺾쇠로 적은 자리는
실제 값으로 바꿔 부릅니다.

*   시각 자리(`since` · `cut` · `cut2` · `on`) 값이 UTC 시각 꼴
    (`2026-11-03T01:02:03Z`)이 아님. PG 가 시각으로 받는 `infinity` ·
    `epoch` · `now` 도 멈춥니다 — 그런 값이 들어가면 Q1 · Q2 가 0 을 내
    「켜기 전」 · 배출을 거짓으로 통과시킬 수 있습니다.
*   `test` 값이 스트림 ID 꼴(`[A-Za-z0-9_-]`, 64자 이하)이 아님.
*   값에 개행 · 제어 문자가 있음(조건을 끼우는 여러 줄 값을 막음).
*   위 다섯이 아닌 자리 이름.
*   채운 뒤에도 채우지 않은 자리가 남음.

`fill_read` 는 꼴만 보고 범위는 보지 않습니다. `9999-12-31T00:00:00Z` ·
`0001-01-01T00:00:00Z` 같은 먼 시각도 꼴로는 통과해 Q1 · Q2 가 0 을 내
거짓으로 통과시킬 수 있습니다. 시각 값은 위 자리 표대로 그 걸음에서 처음 적어
둔 시각을 그대로 옮깁니다. 다음 창에서 이어 할 때도 그 값을 씁니다 — 특히
`<since>` 를 이어 하는 시각으로 새로 적지 않습니다(앞 창의 새 꼴 행을 놓칩니다).
보내기 전에는 값이 이번 교체 작업 기록에 적힌 시각과 같은지만 봅니다.

```shell
fill_read() {  # fill_read <원본> <결과> <자리=값> …
  local src="$1" out="$2" pair key val form
  shift 2
  cp -- "${src}" "${out}" || return 1
  for pair in "$@"; do
    key="${pair%%=*}"
    val="${pair#*=}"
    case "${key}" in
      since | cut | cut2 | on)
        form='[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z' ;;
      test) form='[A-Za-z0-9_-]{1,64}' ;;
      *) echo "멈춤: 모르는 자리 「${key}」" >&2; return 1 ;;
    esac
    case "${val}" in
      *[[:cntrl:]]*) echo "멈춤: ${key} 값에 개행 · 제어 문자" >&2; return 1 ;;
    esac
    printf '%s\n' "${val}" | grep -Eqx -- "${form}" ||
      { echo "멈춤: ${key} 값이 그 자리의 꼴(${form})이 아님" >&2; return 1; }
    sed "s/<${key}>/${val}/g" "${out}" >"${out}.new" &&
      mv -- "${out}.new" "${out}" || return 1
  done
  if grep -En '<[a-z][a-z0-9]*>' "${out}"; then
    echo "멈춤: ${out} 에 채우지 않은 자리가 남음" >&2
    return 1
  fi
}
fill_read "${payload}/q1.sql" "${payload}/q1-fill.sql" "since=<since 값>" &&
  make_read q1 "${payload}/q1-fill.sql" && send_step q1
fill_read "${payload}/q2.sql" "${payload}/q2-fill.sql" \
  "cut=<cut 값>" "test=<test 값>" &&
  make_read q2 "${payload}/q2-fill.sql" && send_step q2
fill_read "${payload}/q2b.sql" "${payload}/q2b-fill.sql" \
  "cut2=<cut2 값>" "test=<test 값>" &&
  make_read q2b "${payload}/q2b-fill.sql" && send_step q2b
fill_read "${payload}/q3.sql" "${payload}/q3-fill.sql" "on=<on 값>" &&
  make_read q3 "${payload}/q3-fill.sql" && send_step q3
fill_read "${payload}/q4.sql" "${payload}/q4-fill.sql" \
  "on=<on 값>" "test=<test 값>" &&
  make_read q4 "${payload}/q4-fill.sql" && send_step q4
```

*   한 흐름에서 그 걸음에 필요한 질의만 보냅니다(예: 4.4 는 `q2` 하나).
*   **Q2 · Q2-B 의 `waiting`** 은 업로더 스위퍼가 실제로 집는 술어
    (`media/internal/index/upload_store.go` 의 `pending*UploadsSQL`)를 그
    상자 몫으로 좁힌 것입니다. 꼬리 유예를 빼고 세어(마지막 조각도 셈)
    실제 대기보다 크거나 같습니다. 0 이 아니면 기다렸다 다시 잽니다.
*   **`bytes_null`** 은 크기가 없어 업로더가 집지 않는 행입니다. 배출로는
    0 이 되지 않으니 0 이 아니면 kty 에게 보고합니다. init 행은 크기
    개념이 없어 NULL 입니다.
*   **init 행이 줄지 않을 때.** init 행은 안 끝난 세션을 모두 셉니다.
    업로더는 경로 · 크기가 있는 조각이 하나도 없는 세션(만들 재료가 없음)은
    집지 않으므로, 그런 세션이 있으면 배출로는 0 이 되지 않습니다(질의는
    설계 원문 그대로 둡니다 — 막히는 쪽으로 틀림). 기다려도 같은 값이면 같은
    흐름에서 「M6 읽기 질의」 의 업로드 적체(`make_read backlog`)를 보냅니다.
    그 `init` 행의 셋째 값이 조각 없는 안 끝난 세션 수입니다(스트림 · 시각
    구분 없이 전체). 0 이 아니든 0 이든 기다림을 멈추고 두 결과를 kty 에게
    보고합니다 — 끝나지 못하고 남은 세션일 수 있습니다.
*   **색인 안 된 녹화는 세지 못합니다.** 상자 디스크에 녹화 파일이 있어도
    색인기가 행을 만들기 전이면(색인 지연 · 사이드카 정지 중) Q2 · Q2-B 에
    나오지 않습니다. 사이드카가 도는 상자를 방송 없는 창에서 재는 까닭입니다.
*   **Q1 · Q3 의 새 꼴 판별**은 키 앞부분(`dvr/` 뒤 경로 성분이 둘인지)만
    봅니다. Q3 은 회차 성분(셋째 성분)을 그 조각 세션의 `round_id` 와
    견줍니다.
*   **Q3 은 세션 없는 행을 세지 않습니다.** 조각을 세션과 이어 붙여(inner
    JOIN) 보므로 `session_id` 가 NULL 인 새 꼴 행은 `matched` 에도
    `mismatched` 에도 들지 않습니다. 새 꼴 행 전수는 Q1 이 셉니다(시각 하한이
    `<since>` 라 Q3 의 `<on>` 보다 넓음).
*   **Q1 · Q3 은 `stream_segments` 를 전부 훑습니다**(Q2 · Q2-B 의 ② · ③ 축도
    시작 시각 조건만 써서 같은 꼴일 수 있습니다 — 상한도 같습니다). 시작 시각만으로 거르는
    인덱스가 없고(`stream_segments_wall_idx` 는 `stream_id` 가 앞 열), 마스터
    계정에는 `statement_timeout` 이 없습니다(60초 상한은 `media_indexer` 역할
    몫 — `10_roles.sql`). 질의의 상한은 페이로드의 `executionTimeout` 300초뿐
    입니다. 읽기라 사이드카 쓰기를 막지는 않습니다. 시간 초과면 「실패 · 시간
    초과」 대로 끄고 kty 에게 보고합니다.
*   **Q4 가 국면을 쓰지 않는 까닭.** 국면 `vod` 는 `vod_state` 가 `frozen`
    이든 `frozen_with_gaps` 든 같고(`media/internal/index/round_playback.go`),
    정산 기한까지 올라가지 않은 조각은 GAP 줄로 마감됩니다
    (`media/internal/rewind/publish/vod.go` 의 `settle_deadline`). 그래서
    업로드 일부가 실패해도 국면은 `vod` 가 됩니다. Q4 는 회차 조각(컷오프
    이상)의 ③ 상태 · 키 꼴 · GAP 기록(`stream_published_gaps`) ·
    `vod_state = 'frozen'` 을 직접 봅니다. VOD 문서의 조각 URI 는 장부의 ③
    키 그대로라(`media/internal/rewind/render.go` — 기준 주소 뒤에 키를
    붙임) 키가 모두 그 회차 새 꼴이고 GAP 이 없으면 문서의 URI 도 새
    꼴입니다. `pass` 가 `f` 면 나머지 열(`not_uploaded` · `not_round_keys` ·
    `gaps` · `vod_state` 등)이 무엇이 어긋났는지 보여 줍니다.
*   기록(8)에 옮기는 것은 `== read` 뒤 결과 줄과 채운 자리 값입니다. Q4 는
    `round_id` 열을 빼고 옮깁니다(회차 id 를 옮기지 않는 「발행 잔여 질의」 와
    같은 규칙).

## 1단-다 — 일 큐 · 새 버킷 · auth 설정 · 값 넣기

`prod/runtime` 의 1단-다(POK-427 · POK-408 의 1번 몫)를 적용하고, 앱 비밀
그릇 여덟에 값을 넣는 절차입니다. 실행자 · 승인 규칙은 이 런북 머리와
같습니다(kty 로컬 터미널 · 쓰기마다 실행 직전 승인). 어느 걸음이든
AccessDenied(접근 거부 · SCP 거부)가 나면 다시 시도하지 않고 멈춰 kty
에게 보고합니다.

*   **언제**: 1단-가 뒤, platform(O7d) 앞입니다. auth 는 OAuth 네 덩어리 ·
    프로필 사진 셋이 없으면 prod 에서 부팅하지 못합니다 — 이 절이 그
    그릇 · env 와 값을 갖춥니다.
*   1단-가를 아직 적용하지 않았으면 1단-가 1-3 과 함께 갑니다(같은
    `runtime-1a` 묶음). 1단-가 2 의 plan 에 이 절의 자원이 함께 나옵니다.

### 1단-다 순서

| 걸음 | 하는 일 | 승인 지점 |
|---|---|---|
| 다-1 | 직전 점검 `runtime-1a` — 묶음이 바뀌어 SCP 판정 CHANGED 가 정상 | ⑤ 보고 · `--accept` |
| 다-2 | tfvars 준비 → plan → apply | plan 승인 |
| 다-3 | 운영 확인 — 큐 · 버킷 · 그릇 여덟 | — |
| 다-4 | apply 뒤 2-3 재판정(새 그릇 포함) | 멈춤 조건 |
| 다-5 | 값 넣기(그릇 여덟) | 값마다 실행 직전 |
| 다-6 | O7d 로 넘김 | — |
| 다-7 | 기록 · Jira | — |

### 1단-다 1. 직전 점검

1 과 같은 명령 셋을 돌립니다(주체 확인 · `scp-recheck.sh runtime-1a` ·
`principal-check.sh runtime-1a` · `events.sh`).

*   SCP 판정: 이 PR 이 묶음에 값 넣기 행(`secretsmanager:PutSecretValue`)을
    더해 종료 3(CHANGED)이 정상입니다. ⑤ 보고와 함께 kty 의 답을 들은 뒤
    `--accept` 하고 다시 판정해 종료 0 을 봅니다. 주석만 바뀐 묶음은
    CHANGED 가 아닐 수 있습니다.
*   2-3 판정: 새 일 큐 · DLQ 여섯은 이 단계가 첫 단계라 「자원 없음」 을
    정책 없이 판정합니다. clips · 프로필 사진 · 채팅 원본 버킷과 그 객체
    행은 O7d 부터라 묻지 않습니다. 종료 3 이면 kty 가 `approvals.tsv` 에 「진행」 을
    적어야 다음으로 갑니다.
*   진행 조건은 1 의 마지막 항목과 같습니다.

### 1단-다 2. tfvars 와 apply

1.  **tfvars**: kty 가 로컬에서 예시를 복사해 값을 넣습니다. 이 파일은
    커밋하지 않습니다(`infra/terraform/.gitignore`). 콘솔에 등록한 주소를
    넣습니다 — 등록 전에 값을 바꾸면 동의가 거부됩니다(services README
    「OAuth 리다이렉트 · 출처 값」).

    ```shell
    cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
    cp terraform.tfvars.example terraform.tfvars
    # 편집기로 <…_CLIENT_ID> 자리표시자 넷을 실제 값으로 바꾼다.
    grep -cE '<[A-Z_]+>' terraform.tfvars   # 0 이어야 함
    ```

1.  **plan**: 2 의 plan 과 같은 명령입니다(`backend.hcl` 이 이미 있으면
    다시 만들지 않습니다). 변수 검증(빈 client_id · https 아닌 주소 ·
    localhost · 사진 주소 끝 `/` · 쿼리 · render 와 upload 를 둘 다 끔)에
    걸리면 plan 이 멈춥니다 — tfvars 를 고칩니다.
1.  **확인 · 승인**: 아래를 모두 확인하고 kty 승인을 받습니다. 하나라도
    다르면 apply 하지 않습니다.
    *   1단-가를 이미 적용했으면 `Plan: 21 to add, 0 to change, 0 to
        destroy` — 일 큐 · DLQ 여섯 · 재처리 허용 셋 · clips 버킷 다섯 ·
        프로필 사진 버킷 다섯 · 그릇 하나(`pokeclip/prod/soop-client-secret`)
        · rewind 버킷 CORS 하나(`aws_s3_bucket_cors_configuration.rewind`).
        1단-가와 함께면 `50 to add`(29 + 21)입니다. 1단-나 ②(POK-429)가
        들어간 판이면 그릇이 하나 더(`pokeclip/prod/stream-key-resolve-token`)
        라 `22 to add` · 1단-가와 함께면 `51 to add` 이고, 출력에 「1단-나 ②
        2」 의 변화가 함께 나옵니다. 그 그릇의 값은 O7d 전에 「1단-나 ② 4」
        로 넣습니다.
    *   방송 주제 정책을 읽기 하나(`SNS:GetTopicAttributes`)로 줄인
        판(POK-304)이면 1단-가를 이미 적용한 경우 위 수에
        `1 to change`(`aws_sns_topic_policy.broadcast_lifecycle`)가
        더해집니다 — `21 to add, 1 to change` · `22 to add, 1 to change`.
        바뀌는 것은 그 정책의 `Sid` 와 동작 목록이고, 주체(계정 루트) ·
        `Condition` 없음은 그대로입니다(「2. 본 apply」 의 주제 정책 항목과
        같은 꼴). 1단-가와 함께면 처음부터 읽기 하나 정책으로 생겨 수가
        그대로입니다.
    *   채팅 원본 버킷(POK-306)이 들어간 판으로 1단-다를 **처음** 적용하면
        (새 환경 · 되돌린 뒤 다시 적용) 위의 모든 add 수에 `5` 를 더합니다
        — `26` · `27` · `55` · `56 to add`. 더해지는 다섯은 아래
        「POK-306 runtime apply 2」 의 주소 다섯과 같습니다. 출력에는 새
        출력 둘과 chat-collector 의 키 넷이 함께 나옵니다(같은 절). 1단-다를
        이미 적용한 환경(지금 prod)은 이 절이 아니라 「POK-306 runtime
        apply」 를 따릅니다.
    *   바뀐 출력이 `app_env` · `app_secret_arns` ·
        `app_task_resource_arns` 입니다. `app_env.auth` 에
        `STREAM_KEY_SECRET_REF_PREFIX` 가 없습니다(platform 이 넣음).
    *   ECS 변경 0 · 삭제 0 입니다(이 루트에는 ECS 자원이 없음).
    *   `aws_secretsmanager_secret_version` · `aws_sqs_queue_policy`(방송 큐
        밖) · `aws_s3_bucket_policy` 가 새로 생기지 않습니다.
1.  **apply · 적용 확인**: 2 의 apply · 적용 확인과 같습니다.

### 1단-다 3. 운영 확인

출력에 계정 ID · ARN 이 나오지 않게 `--query` 로 값만 뽑습니다. 큐 주소는
셸 변수로만 씁니다(계정 ID 를 품음).

```shell
for name in render thumbnail upload render-dlq thumbnail-dlq upload-dlq; do
  url="$(aws sqs get-queue-url --region ap-northeast-2 \
    --queue-name "pokeclip-prod-jobs-${name}" --query QueueUrl --output text)"
  printf '%s ' "${name}"
  aws sqs get-queue-attributes --region ap-northeast-2 --queue-url "${url}" \
    --attribute-names VisibilityTimeout MessageRetentionPeriod \
    SqsManagedSseEnabled \
    --query 'Attributes.[VisibilityTimeout,MessageRetentionPeriod,SqsManagedSseEnabled]' \
    --output text
  aws sqs get-queue-attributes --region ap-northeast-2 --queue-url "${url}" \
    --attribute-names RedrivePolicy --query Attributes.RedrivePolicy \
    --output text |
    grep -oE '"maxReceiveCount":"?[0-9]+"?|pokeclip-prod-jobs-[a-z]+-dlq'
done
for bucket in pokeclip-prod-clips-apne2 pokeclip-prod-profile-photos-apne2 \
  pokeclip-prod-chat-archive-apne2; do
  printf '%s\n' "${bucket}"
  aws s3api get-public-access-block --bucket "${bucket}" \
    --query 'PublicAccessBlockConfiguration' --output text
  aws s3api get-bucket-lifecycle-configuration --bucket "${bucket}" \
    --query 'Rules[].[ID,Status,Expiration.Days,AbortIncompleteMultipartUpload.DaysAfterInitiation]' \
    --output text
done
for kind in jwt-secret secret-store-key internal-api-token \
  google-client-secret youtube-client-secret chzzk-client-secret \
  profile-photo-token-secret soop-client-secret; do
  printf '%s ' "${kind}"
  aws secretsmanager describe-secret --region ap-northeast-2 \
    --secret-id "pokeclip/prod/${kind}" \
    --query "Tags[?Key=='Environment'].Value" --output text
done
```

*   원 큐: `render 600 345600 true` · `thumbnail 120 345600 true` ·
    `upload 900 345600 true` 이고, 각각 `"maxReceiveCount":3` 과 자기 DLQ
    이름(`pokeclip-prod-jobs-<일>-dlq`)이 나옵니다. DLQ 셋은 보관
    `1209600` 이고 redrive 줄이 없습니다.
*   버킷 둘: 공개 차단 넷이 `True` 입니다. clips 는
    `expire-after-60-days Enabled 60` 과
    `abort-incomplete-multipart-upload Enabled … 7`, 프로필 사진은
    `abort-incomplete-multipart-upload` 하나입니다.
*   채팅 원본 버킷: 공개 차단 넷이 `True` 이고
    `expire-chat-after-60-days Enabled 60` 과
    `abort-incomplete-multipart-upload Enabled … 7` 입니다. 만료는 `chat/`
    접두만 대상이라 아래 명령이 `chat/` 한 줄을 냅니다. 이 버킷은
    POK-306(PR-1) 머지본으로 1단-다를 처음 적용하면 이 apply 에서 함께
    생기고, 1단-다를 이미 적용한 환경이면 「POK-306 runtime apply」 가
    만듭니다. 그 apply 전의 판으로 돌리면 이 버킷 줄만 `NoSuchBucket`
    오류이고 나머지 줄은 그대로 봅니다.

    ```shell
    aws s3api get-bucket-lifecycle-configuration \
      --bucket pokeclip-prod-chat-archive-apne2 \
      --query 'Rules[?ID==`expire-chat-after-60-days`].Filter.Prefix' \
      --output text
    ```

*   rewind 버킷 CORS: 아래 명령이 `GET HEAD https://app.pokeclip.com`
    (`app_origin` 을 바꿨으면 그 값) 한 줄만 냅니다.

    ```shell
    aws s3api get-bucket-cors --bucket pokeclip-prod-rewind-apne2 \
      --query 'CORSRules[].[join(`" "`, AllowedMethods), join(`" "`, AllowedOrigins)]' \
      --output text
    ```

*   그릇 여덟(위 반복의 종류): 모두 `prod` 입니다. 2-3 판정의 `secrets`
    행이 이 태그로 그릇을 풉니다. 1단-나 ② 가 들어간 판이면 그릇은
    아홉이고, 아홉째(`stream-key-resolve-token`)는 이 걸음이 아니라
    「1단-나 ② 2」 의 2-3 재판정이 봅니다.
*   결과 줄을 볼트 raw 로 옮깁니다(비밀 값 훑기 뒤 · 계정 ID · ARN 가림).

### 1단-다 4. apply 뒤 2-3 재판정

1 의 2-3 판정은 apply 전이라 새 그릇 `soop-client-secret` 을 몰랐습니다 —
`secrets` 행은 판정 때 태그로 있는 그릇만 풉니다. 같은 날 · 같은 묶음 ·
SCP 이벤트 0 은 대상 집합이 같음을 보장하지 않습니다. 그래서 값을 넣기
전에 2-3 판정을 다시 돌립니다.

```shell
cd "$(git rev-parse --show-toplevel)"
bash infra/terraform/scripts/precheck/principal-check.sh runtime-1a
evidence="$(ls -dt "${HOME}"/.pokeclip-precheck/principal-runtime-1a-* |
  head -n 1)"
awk -F '\t' '$1 == "secrets" &&
  index($2, ":secret:pokeclip/prod/soop-client-secret-") > 0' \
  "${evidence}/targets.tsv" | grep -c .   # 1 이어야 함(ARN 은 내지 않음)
```

*   진행 조건: 1 의 ② 와 같습니다 — 종료 0, 또는 「진행」 이 적힌 종료 3.
*   추가 멈춤(1단-가 4 의 첫 걸음과 같은 꼴): `report.txt` 에 「자원 없음」
    줄이 있음(apply 뒤인데 주제 · 큐가 없다는 뜻 — 도구는 통과로 세므로
    사람이 봅니다) · `secrets` 행이 `TARGET_NONE` · `rds-master-secret` 행이
    `TARGET_NONE` 이거나 `ALLOWED` · 위 셈이 1 이 아님(새 그릇이 태그로
    풀리지 않음). 어느 행이든 `ALLOWED` 면 값을 넣지 않고 kty 에게
    보고합니다. `prod-instance` 의 `TARGET_NONE` 은 실행기가 꺼져 있어,
    `prod-roles` 의 것은 이 절이 O7d 앞이라(platform 역할이 아직 없음)
    정상입니다.
*   SCP 판정은 1 의 것을 씁니다 — 같은 날 · 같은 묶음 · 그 뒤 SCP 변화
    이벤트 0(`events.sh` 로 확인). 날이 바뀌었으면 1 을 다시 합니다.

### 1단-다 5. 값 넣기

재판정이 통과한 뒤 그릇마다 kty 가 **세션 밖 터미널**에서 값 파일을 만들고
[비밀 입력 스크립트](edge-secrets.md)를 돌립니다(실행 직전 승인). 값 규칙은
앱 부팅 검사에서 읽었습니다.

| 종류 | 값 | 근거 |
|---|---|---|
| `jwt-secret` | 32바이트 이상 — auth · clip 이 같은 값 | `JwtConfig` 의 `MIN_SECRET_BYTES`(auth · clip) |
| `secret-store-key` | base64 로 인코딩한 32바이트(AES-256) | auth `application.yml` 의 `secret-store.key` |
| `internal-api-token` | 비지 않은 한 줄 — 앱 서버 넷 · 일꾼 · 응답기가 같은 그릇 | 1단-가 K1 · 일꾼 README |
| `google-client-secret` · `youtube-client-secret` · `chzzk-client-secret` · `soop-client-secret` | 콘솔의 Client Secret 그대로 | services README 「OAuth」 |
| `profile-photo-token-secret` | 32바이트 이상 · `jwt-secret` 과 다른 값 | `PhotoProperties` 의 `MIN_SECRET_BYTES` |

```shell
cd "$(git rev-parse --show-toplevel)"
umask 077
value_file="$(mktemp)"
openssl rand -base64 48 >"${value_file}"   # 예: jwt-secret(무작위 값)
bash infra/terraform/scripts/put-secret-from-file.sh jwt-secret "${value_file}"
rm -f -- "${value_file}"
[ -e "${value_file}" ] && printf '멈춤: 값 파일이 남음\n' >&2
```

*   무작위 값(`jwt-secret` · `profile-photo-token-secret` ·
    `internal-api-token`)은 `openssl rand -base64 48`,
    `secret-store-key` 는 `openssl rand -base64 32`(32바이트의 base64)로
    만듭니다. 콘솔 값은 편집기로 파일에 붙여 넣습니다 — 셸 명령 인자 ·
    히스토리에 두지 않고, 클립보드는 넣은 뒤 비웁니다.
*   종료 0(`SECRET PUT OK <비밀 이름> <VersionId>`) → 값 파일을 지우고
    남지 않았는지 봅니다. 종료 2 · 3 → 종류 · 파일을 고칩니다. 종료 4
    (DENIED) → 다시 시도하지 않고 멈춥니다. 종료 5 → 멈추고 코드를
    보고합니다.
*   종료 1(`CLI HISTORY ENABLED` · `CLI HISTORY UNCHECKED` — 값 파일은 읽지
    않음) → `ENABLED` 면 기록을 끄고, `UNCHECKED` 면 사유 줄을 보고 설정 ·
    프로필 · lib 경로를 고친 뒤 다시 넣습니다(모르면 kty 에게 보고).
*   여덟을 넣은 뒤 그릇마다 버전이 있는지 봅니다(값은 나오지 않음):
    `aws secretsmanager describe-secret --region ap-northeast-2 --secret-id
    pokeclip/prod/<종류> --query 'length(keys(VersionIdsToStages))'` 가
    `1` 이상.

### 1단-다 6. O7d 로 넘김

platform 의 O7d 는 이 뒤입니다. O7d 운영 확인에서 아래를 봅니다.

*   auth 부팅 로그에 OAuth · 프로필 사진 설정 거부 문구가 없습니다.
*   clip 기동 로그 `clip.purge.storage_enabled … segments=true`
    (`PurgeConfiguration.java:55-56`)가 있고 `clip.purge.storage_unavailable`
    이 0 입니다. `segments=false` 면 탈퇴 정리가 녹화 조각을 지우지
    않습니다.
*   편집기 트랙별 미리 듣기가 조각을 받습니다. 「못 불러왔어요」 로만
    뜨면 브라우저 콘솔의 CORS 거부부터 봅니다 — rewind 버킷 CORS(1단-다 3)의
    출처가 앱 출처와 다른 경우입니다.
*   관측 신호(경보 후보): render · upload DLQ 깊이(clip 정리기가 1분마다
    비움) · 썸네일 DLQ 깊이(clip 이 읽지 않음 — 쌓이면 일꾼 문제) · 원 큐의
    가장 오래된 메시지 나이(`ApproximateAgeOfOldestMessage` — 일꾼 배치
    전에는 소비자가 없어 늘어남, 고착 알람 없음).
*   `clip.purge.segments_skipped`(`StreamerPurger.java:78`)는 손실
    사건입니다 — 한 줄이라도 나오면 그 스트리머의 녹화 조각이 지워지지 않은
    채 명부가 닫힌 것입니다. 그 줄의 스트리머 id 를 모아 kty 에게
    회부합니다(관측만 하고 넘기지 않습니다).

### 1단-다 7. 기록

*   볼트 raw: 각 걸음의 명령 · 출력 · 판정(1 · 2 의 plan 요약 · 3 · 4 의
    첫 줄과 셈 · 5 의 `SECRET PUT OK` 여덟 줄에서 VersionId 를 뺀 것).
    옮기기 전에 비밀 값 훑기를 합니다(계정 ID · ARN 은 가립니다).
*   Jira: POK-427 완료 전이 + 근거 코멘트 · POK-408 에 1번 몫 완료
    코멘트(일꾼 배치 PR 은 남음).

### 1단-다 되돌리기 — O7d 전

소비자가 없습니다.

1.  버킷 둘을 비웁니다(`force_destroy = false` 라 비어야 지워집니다).
1.  코드를 `git revert -m 1` 한 판에서 runtime plan 을 봅니다 — 삭제
    대상이 이 절의 자원뿐이고 ECS 변경 0 이어야 합니다(plan 승인).
1.  그 판으로 apply 합니다. 그릇은 30일 복구 대기에 들어갑니다. 값을 넣은
    그릇은 지우기 전에 kty 에게 확인합니다(되살리면 값을 다시 만들어야
    함).

삭제 동작은 `runtime-1a` 묶음 밖입니다(아래 「1단-다 한계」).

### 1단-다 되돌리기 — O7d 뒤(clip 먼저 0개)

kty 결정(2026-10-24): clip 을 먼저 내리고, 나머지를 되돌린 뒤, 조건을
확인하고 clip 을 다시 올립니다. 코드 수정은 없습니다.

*   **먼저 내리는 이유**: env 없는 clip 은 yml 기본값으로 render · upload 가
    둘 다 false 입니다. 그러면 창고 지우기가 없어, 정리기(15초마다 —
    clip `application.yml` 의 `purge.interval`)가 S3 를 지우지 않은 채
    명부를 비우고 완료합니다. rewind 녹화 조각이 영구히 남습니다.
*   **대가**: 되돌리는 동안 clip 이 멈춥니다(주문 · 조회 모두). 그동안 들어온
    탈퇴는 auth 장부에 쌓이고, auth 가 15초에서 시작해 두 배씩 · 상한
    1시간으로 다시 보냅니다(`PurgeDispatcher.java:35-36` · `86-88`).
*   **platform 동결**: 1걸음부터 4걸음까지(회부로 보류된 동안 포함) platform
    apply 는 4걸음 하나뿐입니다. platform `ecs_service` 모듈은
    `desired_count = 1` 을 고정하고 `ignore_changes` 가 없어
    (`modules/ecs_service/main.tf:181-186`), 어느 platform apply 든 clip 을
    다시 1 로 올립니다. 다른 PR 의 platform plan 에
    `module.clip.aws_ecs_service.this` 의 `desired_count` 0 → 1 이 보이면 그
    apply 는 거부합니다.

1.  **clip 0개**(ECS 쓰기 — 실행 직전 승인):

    ```shell
    aws ecs update-service --region ap-northeast-2 --cluster pokeclip-prod \
      --service pokeclip-prod-clip --desired-count 0 \
      --query 'service.desiredCount' --output text
    aws ecs describe-services --region ap-northeast-2 --cluster pokeclip-prod \
      --services pokeclip-prod-clip \
      --query 'services[0].[desiredCount,runningCount]' --output text
    ```

    둘 다 `0` 이 되고, 그 뒤 clip 로그 그룹에 `clip.purge.*` 줄이 새로
    생기지 않는지 봅니다. 도는 중에 끊긴 정리는 다음에 부르면 이어집니다.
1.  **진행 중 주문**: 먼저 갈래를 정합니다 — 일꾼이 배치돼 받고 있는가
    (`prod/workers` 의 apply 기록 · 일꾼 서비스 desired count. 보는 명령은
    「1단-나 ② 8」 의 「일꾼 멈춤」 기록 블록과 같습니다).
    *   **배치 갈래**: 원 큐 셋의 두 지표
        `ApproximateNumberOfMessages` · `ApproximateNumberOfMessagesNotVisible`
        가 둘 다 0 이 될 때까지 기다립니다(하나만 보면 일꾼이 쥔 메시지를
        놓칩니다). clip 이 없으니 이 0 은 소화가 아니라 DLQ 로 옮겨 간
        결과입니다 — render 는 clip 보고가 끝내 실패하면 메시지를 그대로
        두고(`ClipReporter` 의 재전송 뒤 `ReportUnavailable` →
        `JobProcessor.java:81-84`), upload 는 clip · auth · S3 무응답을
        60–120초 뒤 다시 받게 둡니다(`UploadProcessor.java:113-116` ·
        `retryLater` 381-383). 숨김 시간마다 다시 받혀 세 번째 뒤 DLQ 로
        갑니다. 상한은 render 3 × 600초 · upload 3 × (900초 또는 60–120초
        지연) · thumbnail 3 × 120초에 처리 시간을 더한 값입니다. 예외:
        썸네일 라이브(`kind = live`)는 clip 보고가 실패해도 메시지를
        지웁니다(`ThumbnailProcessor.java` `retryOrDrop` — 라이브는 다시
        하지 않음). 그 메시지는 DLQ 로 가지 않고 사라집니다.
    *   **미배치 갈래**: 0 을 기다리지 않습니다(아무도 받지 않는 메시지는
        DLQ 로 옮겨지지 않고 원 큐에 남다가 보관 4일 뒤 사라집니다). 원 큐
        셋의 두 지표 숫자를 기록합니다.
    *   어느 갈래든 보고에 둘을 따로 싣습니다: render · upload DLQ 메시지
        수(`ApproximateNumberOfMessages` — 근사치이고, 이미 종결된 주문의
        늦은 메시지가 섞임)와 clip DB 의 미종결 주문 수. 미배치 갈래면 원 큐
        숫자도 같은 보고에 싣습니다. 미종결 주문은 실행기(4 켬 · 5-1 꼴의
        페이로드 · 5-2 `send_step` · 7 끔 — send-command 승인)로 셉니다.
        clip 표는 1단-나 ① 뒤 `app_clip` 스키마에 있고 표 주인이 `app_clip`
        이라, 마스터는 경로에도 없고 읽을 권한도 없습니다. 그래서 5-3 의
        손 줄처럼 `app_clip` 으로 바꿔서 읽고 그 멤버십을 거둡니다.
        `psql -X -1 -v ON_ERROR_STOP=1` 로 돌리므로 중간에 실패하면 부여도
        롤백됩니다(로컬 PG 17 비superuser 마스터 대역으로 확인 —
        2026-10-25 · 경로만 바꾸면 42501, 그대로면 42P01).

        ```sql
        SET search_path TO app_clip;
        GRANT app_clip TO CURRENT_USER WITH SET TRUE, INHERIT FALSE;
        SET ROLE app_clip;
        SELECT 'render_jobs', pg_catalog.count(*) FROM render_jobs
          WHERE status IN ('queued', 'started')
        UNION ALL
        SELECT 'clip_uploads', pg_catalog.count(*) FROM clip_uploads
          WHERE status IN ('queued', 'uploading')
        UNION ALL
        SELECT 'purged_streamers', pg_catalog.count(*) FROM purged_streamers
          WHERE completed_at IS NULL;
        RESET ROLE;
        REVOKE app_clip FROM CURRENT_USER;
        ```

        상태 값은 clip 마이그레이션(`V207` · `V209` · `V212`)의 CHECK
        제약입니다. 썸네일 주문은 이 표 밖입니다.
    *   3걸음이 큐를 지우는 되돌림이고 미배치 갈래면, 원 큐에 남은 메시지의
        주문은 clip DB 에 미종결로 남습니다 — DLQ 에 메시지가 없어 다시 올린
        clip 의 DLQ 정리기도 닫지 못합니다. 그 수를 kty 에게 회부하고, 회부
        답 전에는 3걸음을 하지 않습니다.
    *   DLQ 보관은 14일입니다 — 그 안에 clip 이 다시 올라와야 정리기가 DLQ
        메시지를 닫습니다. 원 큐 보관은 4일입니다.
1.  **런타임 되돌림**(되돌리는 범위만큼): 버킷 비우기 → `git revert -m 1`
    판에서 runtime plan(ECS 변경 0 — plan 승인) → apply. 그릇은 30일 복구
    대기입니다. clip 은 계속 `runningCount` 0 입니다.
1.  **clip 다시 올리기** = platform apply(plan 승인). 먼저 조건을 봅니다.

    ```shell
    cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
    terraform output -json app_env | jq -r '.clip |
      "RENDER_ENABLED=\(.RENDER_ENABLED // "없음")",
      "UPLOAD_ENABLED=\(.UPLOAD_ENABLED // "없음")",
      "CLIPS_BUCKET 있음=\((.CLIPS_BUCKET // "") != "")",
      "SEGMENT_BUCKET 있음=\((.SEGMENT_BUCKET // "") != "")"'
    ```

    *   `RENDER_ENABLED` · `UPLOAD_ENABLED` 가운데 하나 이상이 `true` 이고
        두 창고가 `있음=true` 이면 올립니다. 기동 로그
        `clip.purge.storage_enabled … segments=true` 를 확인 신호로 봅니다
        — `segments=false` 면 조각을 지우지 않습니다.
    *   관측 창은 **1시간 이상**입니다 — 1걸음 뒤 쌓인 탈퇴가 auth 의
        재전송(상한 1시간)으로 모두 다시 들어오고 정리기가 돌 때까지
        `clip.purge.*` 를 봅니다.
    *   이번에 비워질 DLQ 를 적습니다 — render DLQ 는 render 가 켜진 clip
        만, upload DLQ 는 upload 가 켜진 clip 만 읽습니다(큐 클라이언트가
        없으면 0 을 내고 끝남 — `DlqReconciler.java:54` ·
        `UploadDlqReconciler.java:48-50`). 썸네일 DLQ 는 clip 이 읽지
        않습니다.
    *   `clip.purge.segments_skipped` 가 나오면 손실 사건으로 회부합니다(6).
    *   1단-다 전체를 되돌린 판이면(두 이름이 `없음` · `false`) clip 을 0 으로
        둔 채 platform apply 를 보류하고 kty 에게 회부합니다. 싣는 것: 위
        출력 · 미완료 명부 수 · 2걸음 숫자. 언제 · 어떤 판으로 올릴지는 회부
        때 정합니다.

### 1단-다 한계

*   `runtime-1a` 묶음에는 삭제 동작이 없습니다(삭제 계열은 실행기 끔의 ec2 ·
    iam 과 상태 버킷 `s3:DeleteObject` 뿐). 되돌리기의 큐 · 버킷 삭제
    (`sqs:DeleteQueue` · `s3:DeleteBucket` 등)는 직전 점검의 SCP 판정
    밖입니다 — 거부되면 다시 시도하지 않고 멈춥니다.
*   2-3 판정의 객체 행은 버킷마다 표본 키 하나입니다. 접두로 열린 dev
    정책만 잡습니다.
*   O7d 부터 일꾼 배치까지 주문이 소비자 없이 쌓입니다(고착 알람 없음).
*   `clip_job_features` 거부 규칙은 런타임 변수 값만 막습니다. 코드
    되돌리기 길은 위 「clip 먼저 0개」 절차(platform 동결 포함)가 막습니다.

## 1단-나 ① — 앱별 스키마 · DB 권한(POK-428)

1단-나 ①의 SQL 판(`10_roles.sql` · `40_verify.sql` · `50_advisory_probe.sql`
주석)을 RDS 에 적용하는 절차입니다. 앱 사용자 넷의 `public` CREATE 를 거두고
계정과 이름이 같은 자기 스키마를 주며, `pokeclip` 의 CONNECT · TEMP 를
PUBLIC 에서 거두고 CONNECT 만 계정 여섯에 줍니다(결정 정본
[ADR-0007](../../../docs/decisions/2026-10-24-app-schemas.md)). 계획 rt1b 의
「prod 운영 순서와 승인 지점」 이 이 절입니다. 머리의 실행 규칙(kty 로컬
터미널 · 쓰기마다 직전 승인 · 출력을 세션에 붙이지 않음)이 그대로 걸립니다.

1단-나 ①은 Terraform 자원을 바꾸지 않습니다 — 바뀌는 것은 5 가 보내는 SQL
파일뿐입니다. **앱 서버(auth · clip)가 처음 뜨기(O7d) 전에** 합니다. prod
에는 옮길 앱 자료가 없습니다.

### 1단-나 ① 순서

| 걸음 | 하는 일 | 승인 지점 |
|---|---|---|
| 1 | 1단-가 SQL 적용 여부 확인(볼트 raw · POK-304 코멘트) | — |
| 2 | 이 런북 1 · 4 — 직전 점검 → 실행기 켬 → 켬 뒤 2-3 | ⑤ 보고 · plan 승인 |
| 3 | 10 보다 먼저 — public 에 앱 계정 소유 객체 0행 확인(아니면 멈춤) | send-command 승인 |
| 4 | 이 런북 5 — 이 PR 의 머지 SHA 로 00 → 10(새 판) → 20 → 30 → 40 | send-command 승인 |
| 5 | 이 런북 6 — 50 → 40(갈래 기록만) | send-command 승인 |
| 6 | 이 런북 7 · 8 — 실행기 끔 · 잔여 0 · 기록(POK-428) | plan 승인 |
| 7 | O7d · POK-306 — 서버가 처음 떠 자기 스키마에 표를 만듦 | 그 단계 몫 |
| 8 | 기동 뒤 확인 | send-command 승인 |

1.  **걸음 1 —** 1단-가 SQL 이 아직 적용되지 않았으면 이 절 대신 이 런북 1
    부터 8 을 이 PR 의 머지 SHA 로 처음부터 돕니다(같은 SQL 판입니다). 이미
    적용됐으면 걸음 2 부터 갑니다.
1.  **걸음 3 —** 10 을 보내기 **전에** public 에 앱 계정(`app_%`) 소유
    객체가 0행인지 읽습니다. 1단-가 판은 앱 넷에 public CREATE 를 주므로,
    O7d 가 이 절보다 먼저 됐으면 앱 표가 public 에 있습니다. 그 위에 10 을
    적용하면 옛 이미지는 다음 마이그레이션을 만들지 못해 42501 로 멈추고,
    이 PR 의 이미지는 빈 자기 스키마에 표를 새로 만들어 자료가 갈립니다. 40 의
    `public_owned_by_app:<계정>` 도 잡지만 그때는 10 이 이미 커밋된
    뒤입니다(파일마다 트랜잭션). 보내는 길은 「실패 · 시간 초과」 의 손
    SQL 규칙과 같되(새 페이로드 · SQL 원문을 보이고 kty 승인) 40 을 잇지
    않습니다 — 10 전이라 새 판 40 은 반드시 실패합니다. 1단-가 판의 public
    CREATE 아래에서 마스터로 돌리므로 함수 · 연산자 · 형을 모두 `pg_catalog`
    로 한정합니다. 0행이면 걸음 4 로 갑니다. 한 행이라도 나오면 10 을 보내지
    않고 곧바로 이 런북 7(끔)로 가서 잔여 0 을 확인한 뒤, 그 출력을 kty 에게
    회부합니다.

    ```sql
    WITH pub(ns) AS (
      SELECT oid FROM pg_catalog.pg_namespace
       WHERE nspname OPERATOR(pg_catalog.=) 'public'
    ), owned(kind, o) AS (
      SELECT 'pg_class', relowner FROM pg_catalog.pg_class, pub
       WHERE relnamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_proc', proowner FROM pg_catalog.pg_proc, pub
       WHERE pronamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_type', typowner FROM pg_catalog.pg_type, pub
       WHERE typnamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_operator', oprowner FROM pg_catalog.pg_operator, pub
       WHERE oprnamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_opclass', opcowner FROM pg_catalog.pg_opclass, pub
       WHERE opcnamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_opfamily', opfowner FROM pg_catalog.pg_opfamily, pub
       WHERE opfnamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_collation', collowner FROM pg_catalog.pg_collation, pub
       WHERE collnamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_conversion', conowner FROM pg_catalog.pg_conversion, pub
       WHERE connamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_ts_config', cfgowner FROM pg_catalog.pg_ts_config, pub
       WHERE cfgnamespace OPERATOR(pg_catalog.=) ns
      UNION ALL
      SELECT 'pg_ts_dict', dictowner FROM pg_catalog.pg_ts_dict, pub
       WHERE dictnamespace OPERATOR(pg_catalog.=) ns
    )
    SELECT kind, pg_catalog.pg_get_userbyid(o) AS owner,
           pg_catalog.count(*) AS n
      FROM owned
     WHERE pg_catalog.starts_with(
             pg_catalog.pg_get_userbyid(o)::pg_catalog.text, 'app_')
     GROUP BY 1, 2;
    ```

1.  **걸음 4 —** 이 런북 5 는 1단-가 판이 이미 적용된 DB 에서 그대로 다시
    돕니다. 10 · 20 · 30 은 그 위에서도 같은 상태로 수렴합니다 — media 시험
    `TestProdSQLConvergesOverRuntime1aRoles`
    (`media/internal/index/prod_sql_apply_pg_test.go`)가 1단-가 판 10 의
    사본(`testdata/prod_sql_runtime_1a_10_roles.sql`) · 20 · 30 위에 새
    10 · 20 · 30 · 40 을 돌려 `VERIFY OK` 를 냅니다. 40 의 새 식별자(이름
    · 뜻은 `40_verify.sql` 머리)로 실패하면 「실패 · 시간 초과」 로
    갑니다. 앱 서버가 뜨기 전이라 `public_owned_by_app:<계정>` 이 실패하면
    이상입니다 — 무엇이 있는지 확인하고 kty 에게 회부합니다.
1.  **걸음 5 —** 이 런북 6 의 50 은 K7 (가)라 갈래와 무관하게 회수하지
    않습니다. 출력은 볼트 raw 와 POK-428 코멘트에 기록합니다.
1.  **걸음 7 —** 서버 넷(auth · clip · 수집 서버 · 판별 서버)은 platform
    apply(O7d) 한 번에 생깁니다(POK-306). 수집 서버의 Flyway(`V316`)가 판별
    계정에 `chat_messages` SELECT 를 주는데, platform 은 두 chat 서버 사이에
    순서를 두지 않습니다(platform `services.tf` 의 `module "chat_detector"`
    주석). 판별 서버가 그 권한보다 먼저 떠도 되는지는 3번 답(POK-306 Q5)
    전까지 모릅니다 — 판별 서버 태스크가 실패 · 재시작하면 수집 서버의 Flyway
    줄을 본 뒤 판별 서버가 다시 떠 steady 인지 확인합니다.

### 1단-나 ① 기동 뒤 확인

*   서버마다 기동 로그의 Flyway 줄이 자기 스키마를 가리킵니다 — 꼴
    `Successfully applied <N> migrations to schema "app_auth"`(clip 은
    `"app_clip"` 등 서버마다 자기 스키마 이름).
*   실행기를 다시 켜는 흐름(4 → 손 SQL → 7)으로 아래를 「실패 · 시간
    초과」 의 손 SQL 규칙대로 보냅니다. 첫째는 0행이어야 하고, 둘째는 수집
    서버가 처음 뜬 뒤 `t` 여야 합니다(수집 서버가 뜨기 전에는 표가 없어
    42P01 로 실패합니다). 둘째가 `f` 면 5-3 의 손 줄을 돌립니다. 뒤이은 40
    의 `public_owned_by_app:<계정>` 이 표뿐 아니라 함수 · 형 · 연산자 등
    (걸음 3 의 카탈로그 열)까지 같은 것을 잽니다.

    ```sql
    SELECT n.nspname, c.relname
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND pg_catalog.pg_get_userbyid(c.relowner) LIKE 'app\_%';
    SELECT pg_catalog.has_table_privilege('app_chat_detector',
             'app_chat_collector.chat_messages', 'SELECT');
    ```

### 1단-나 ① 되돌리기

*   **앱 서버가 뜨기 전**: 되돌리기는 둘로 나뉩니다. 1단-가 판 10 을 다시
    돌려도 PUBLIC 의 CONNECT · TEMP 는 돌아오지 않기 때문입니다.
    1.  스키마 권한 — 1단-가 머지 커밋(`9e008743`)의 SHA 로 5 를
        돕니다(1단-가 판 10 이 앱 넷에 `public` CREATE 를 다시 줍니다).
    1.  DB 권한과 빈 스키마 — 같은 SHA 로 만든 페이로드로 아래를 「실패 ·
        시간 초과」 의 손 SQL 규칙대로 보냅니다. 뒤이은 1단-가 판 40 이
        `VERIFY OK` 여야 합니다. `DROP SCHEMA` 는 CASCADE 없이 씁니다 —
        비어 있지 않으면(앱이 이미 떴으면) 크게 실패하고 아무것도 바뀌지
        않습니다. 계정마다 준 CONNECT 는 남아도 무해합니다. 로컬 PG 17
        superuser 대역에서 1단-가 판 → 이 판 → 1단-가 판 10 · 20 · 30 · 40 →
        아래 SQL · 1단-가 판 40 이 모두 `VERIFY OK` 인 것을 확인했습니다
        (2026-10-25 — 운영 마스터 대역은 아님).

        ```sql
        DROP SCHEMA app_auth, app_clip, app_chat_collector, app_chat_detector;
        GRANT CONNECT, TEMPORARY ON DATABASE pokeclip TO PUBLIC;
        ```

*   **앱 서버가 뜬 뒤**: 표가 자기 스키마에 있으므로 코드 · SQL 을 되돌리지
    않고 앞으로 고칩니다.
*   dev 공유 DB 는 이전이 끝나(2026-10-26) 되돌리기 장치를 지웠습니다 —
    [services/README.md](../../../services/README.md) 「dev 앱별 스키마 이전」.

## 1단-나 ② — Media 전용 토큰(POK-429)

Media 상자가 읽는 auth 출입증을 공용 `INTERNAL_API_TOKEN` 에서 resolve 전용
토큰(그릇 `pokeclip/prod/stream-key-resolve-token`) 하나로 줄이고, 줄인 뒤
공용 토큰을 한 번 바꾸는 절차입니다. 계획 rt1d 의 「운영 순서와 승인
지점」 이 이 절입니다. 머리의 실행 규칙(kty 로컬 터미널 · 쓰기마다 직전
승인 · 출력을 세션에 붙이지 않음)이 그대로 걸립니다.

*   **전용 토큰**: auth 의 `POST /internal/stream-keys/resolve` 하나만 여는
    토큰입니다. 헤더 `X-Stream-Key-Resolve-Token` 으로 싣고, 출발지가 Media
    프라이빗 서브넷 대역(`STREAM_KEY_RESOLVE_ALLOWED_CIDRS`)이어야 합니다.
*   **공용 토큰**: `INTERNAL_API_TOKEN` 입니다. auth 의 `/internal/**` 전부를
    열고 앱 서버들이 함께 씁니다.
*   **응답기**: Media 상자의 `ingest-auth` 컨테이너입니다. 렌더 · 명령은
    M15 몫이고, 상자 명령은 [Media 교체 런북](media-swap.md) 「준비」 의
    작업 디렉터리 · `media_ssm` 으로 보냅니다.

어느 걸음이든 AccessDenied 가 나오면 그 자리에서 멈추고 kty 에게
보고합니다(다시 시도 · 우회하지 않음).

### 1단-나 ② 순서 한눈에

| 걸음 | 하는 일 | 승인 지점 |
|---|---|---|
| 1 | 직전 점검(`runtime-1a`) — SCP 판정은 CHANGED(새 행) | ⑤ 보고 · `--accept` |
| 2 | runtime plan · apply → 2-3 다시 | plan 승인 |
| 3 | M15 선행 조건 기록 | — |
| 4 | 전용 토큰 값 넣기(O7d 전 필수) | 값 입력 |
| 5 | platform(O7d 또는 그 뒤) | plan 승인 |
| 6 | prod/media 적용 모드 확인 | — |
| 7 | prod/media plan · apply(권한 축소) · 실물 확인 | plan 승인 |
| 8 | 공용 토큰 1회 회전(조건 없음) · 일꾼은 멈춘 채 | send-command · 일꾼 멈춤 · 값 입력 · 재배포 · 일꾼 복원 |
| 9 | 응답기 기동 · 교체 | send-command |
| 10 | 음성 확인(전용 토큰 · 옛 공용 토큰 401) | send-command · 포트 포워딩 |
| 11 | 기록 | — |

### 1단-나 ② 직전 점검 규칙

새 규칙을 만들지 않습니다. runtime apply 는 이 런북 1 · 3-1 · 4 의 첫
걸음, platform apply 는 O7d, prod/media apply 는 O7e 단계의 직전 점검을
[단계 직전 점검](stage-precheck.md) 「순서」 · 「단계별 점검」 대로 돕니다.
아래 1 의 결과별 멈춤 조건은 세 단계에 똑같이 걸립니다.

### 1단-나 ② 1. 직전 점검(runtime-1a)

kty 가 세션 밖에서 caller identity 를 본 뒤 1 의 세 명령을 그 순서로
돌리고 ⑤ 보고를 합니다.

*   ① 종료 3(CHANGED)은 예상됩니다 — 묶음에 `ec2:DescribeSubnets` 행이
    늘었습니다(런타임 `data "aws_subnet"`). ⑤ 보고와 함께 묻고, 답을 들은 뒤
    `--accept` 하고 다시 판정해 종료 0 이어야 다음입니다. 종료 1(DENIED) ·
    2(검사 불가)면 2 로 가지 않습니다.
*   ② 는 1단-가 apply 뒤 기준입니다(4 의 첫 걸음 — 1 의 「apply 전이라
    정상」 은 맞지 않음). 늘 정상인 `TARGET_NONE` 은 `prod-instance`
    하나입니다. `prod-roles` 는 O7d 전이면 정상이고(실행기가 꺼져 있고
    platform 역할이 아직 없음), O7d 뒤에 돌면 `TARGET_NONE` 이 멈춤입니다.
    `rds-master-secret` 이 `TARGET_NONE` 이거나 `ALLOWED` 이면 멈춤,
    `report.txt` 에 「자원 없음(첫 단계 — 정책 없이 판정)」 줄이 있으면
    멈춤입니다. 입력 파일 `~/.pokeclip-precheck/input-rds-master.txt` 가 없거나
    비었으면 3-1 로 먼저 만들고 `INPUT OK` 를 본 뒤 ② 를 돌립니다.
    UNDECIDED(3)는 kty 가 그 증거 `approvals.tsv` 에 「진행」 을 적어야
    다음입니다.
*   ③ 종료 2 면 시작하지 않습니다. `summary.tsv` 에 역할 받기 밖의 0 아닌
    행이 있으면 kty 에게 알립니다.
*   시작 조건은 stage-precheck 「2-3 판정」 그대로입니다(멈춤 0 · 판정 불가는
    0 이거나 모두 「진행」 기록).

### 1단-나 ② 2. runtime plan · apply

2 의 plan · 확인 · apply · 적용 확인 꼴로 합니다. 기대 변화는 아래뿐입니다
— 그 밖의 변화가 있으면 apply 하지 않습니다.

*   `Plan: 1 to add, 0 to change, 0 to destroy`(1단-다를 적용한 뒤 기준 —
    함께 적용하면 「1단-다 2」 의 수를 봅니다).
*   추가: 그릇 하나(`module.secret_shells` 의
    `pokeclip/prod/stream-key-resolve-token`).
*   출력: `app_secret_arns.auth` 에 `STREAM_KEY_RESOLVE_TOKEN` ·
    `media_secret_arns` 원소 교체(공용 그릇 → 전용 그릇) · `app_env.auth` 에
    `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` · 새 출력
    `ingest_auth_resolve_token_secret_arn`.

apply 뒤 2-3 판정을 3-1 과 4 의 첫 걸음 그대로 다시 합니다. 입력 파일은
1단-가 때 만든 것을 씁니다. `principal-check.sh runtime-1a` 의 증거
`targets.tsv` 에서 새 그릇이 `secrets` 태그 행으로 대상에 들어왔는지 봅니다.
멈춤 조건은 위 1 의 ② 와 같습니다.

### 1단-나 ② 3. M15 선행 조건 기록

2 직후 「응답기 모드(`ingest_auth_image` 를 채운) prod/media apply 는 M15 의
전환(`media.env` 의 `STREAM_KEY_RESOLVE_TOKEN_SECRET_ARN` · compose 의
`STREAM_KEY_RESOLVE_CHANNEL: resolve-token`)이 들어간 판으로만 한다」 를 장부
rt1d 행과 POK-301 코멘트에 남깁니다. 7 의 보류 조건이 이 기록을 봅니다.

### 1단-나 ② 4. 전용 토큰 값 넣기

**auth 배포(O7d) 전 필수**입니다. 값 없는 그릇을 참조한 태스크가 어떻게
뜨는지는 미확인이라 앞에 둡니다.

```shell
cd "$(git rev-parse --show-toplevel)"
# 하위 셸 — 추적 · allexport 끔과 umask 가 밖으로 새지 않고, 값 파일은
# put-secret 이 실패(종료 1 · 2 · 3 · 4 · 5)하거나 중간에 멈춰도 지워진다.
(
  set +o xtrace +o allexport
  umask 077
  value_file="$(mktemp)" || exit 1
  trap 'rm -f "${value_file}"' EXIT
  openssl rand -hex 32 >"${value_file}" || exit 1
  bash infra/terraform/scripts/put-secret-from-file.sh \
    stream-key-resolve-token "${value_file}"
)
printf 'put-secret 종료 %s\n' "$?"
```

*   종류 `stream-key-resolve-token` 은 1단-다(POK-427)가 넓힌 허용 목록에 이
    PR 이 한 줄을 더한 것입니다. 인자 꼴(`<종류> <파일>`)은 1단-다 머지본
    그대로입니다. 종료 2(모르는 종류)면 그 판이 빠진 것이니 멈춥니다.
*   값은 `INTERNAL_API_TOKEN` 과 달라야 합니다 — 같으면 auth 가 부팅을
    거부합니다. 화면 · 세션에 값을 내지 않습니다. 종료 4(DENIED)는 다시
    시도하지 않고 멈춥니다.

### 1단-나 ② 5. platform(O7d 또는 그 뒤)

O7d 직전 점검(위 규칙) → plan → auth 태스크 정의의 secrets 에
`STREAM_KEY_RESOLVE_TOKEN`, environment 에 `STREAM_KEY_RESOLVE_ALLOWED_CIDRS`
가 들어감을 확인 → **plan 승인** → apply. auth 부팅 로그에 부팅 거부 문구가
없고 health 가 통과해야 다음입니다.

### 1단-나 ② 6. prod/media 적용 모드 확인

이번 prod/media apply 가 응답기 모드인지(`ingest_auth_image` 가 비었는지 —
값이 아니라 비었는지만)를 봅니다. 첫 O7e 인지는 따로 가리지 않습니다 — 7 의
plan 이 역할을 새로 만드는지 고치는지로 드러나고, 어느 쪽이든 8 을 합니다.

### 1단-나 ② 7. prod/media plan · apply(권한 축소)

*   **보류**: 응답기 모드이고 M15 전환 전 판(`internal-token` 창구 · 공용
    그릇 키)으로 렌더되는 판이면 미룹니다(3 의 기록 대조). 옛 모드
    O7e(`ingest_auth_image` 빈 값 — 응답기가 뜨지 않음)는 막지 않습니다.
*   O7e 직전 점검(멈춤 자리 묶음 포함) → plan → 인스턴스 역할 정책
    `SecretsRead` 의 Resource 에서 `internal-api-token` 그릇이 빠지고
    `stream-key-resolve-token` 그릇이 들어가는 변화만 있는지 확인 → **plan
    승인** → apply → 완료 직후 `date '+%Y-%m-%d %H:%M'` 를 찍어 8 (c) 의
    시작 시각으로 적습니다. O7e 전이면 O7e 의 첫 media apply 가 이 걸음을
    겸하고, plan 에 `internal-api-token` 이 없음을 확인합니다.
*   **실물 확인(읽기)**: Media 인스턴스 역할(`pokeclip-prod-media`)의 정책
    문장 전부에서 `internal-api-token` 이 0 건이고 `stream-key-resolve-token`
    이 `SecretsRead` 에 있어야 합니다. sid 하나로 한정하지 않습니다. 화면에는
    건수와 이름 끝 토막만 냅니다.

    ```shell
    # 새 bash 프로세스에서 돈다 — zsh 는 조건 문맥 안의 set -e 를 무시하고
    # $(…) 결과를 단어로 나누지 않아, 조회 실패 · 정책 둘 이상이 「0 건」 으로
    # 읽힌다. 조회마다 실패하면 멈춘다. 오류 원문(역할 · 계정 · ARN)은 버린다.
    bash -c '
    role=pokeclip-prod-media
    stop() { printf "멈춤: %s 읽기 실패\n" "$1"; exit 1; }
    inline="$(aws iam list-role-policies --role-name "${role}" \
      --query "PolicyNames[]" --output text 2>/dev/null)" || stop "인라인 목록"
    read -r -d "" -a names <<<"${inline}"
    attached="$(aws iam list-attached-role-policies --role-name "${role}" \
      --query "AttachedPolicies[].PolicyArn" --output text 2>/dev/null)" ||
      stop "관리형 목록"
    read -r -d "" -a arns <<<"${attached}"
    docs=""
    for p in "${names[@]}"; do
      doc="$(aws iam get-role-policy --role-name "${role}" \
        --policy-name "${p}" --query PolicyDocument --output json \
        2>/dev/null)" || stop "인라인 정책"
      docs+="${doc}"
    done
    for a in "${arns[@]}"; do
      v="$(aws iam get-policy --policy-arn "${a}" \
        --query Policy.DefaultVersionId --output text 2>/dev/null)" ||
        stop "관리형 정책 판"
      doc="$(aws iam get-policy-version --policy-arn "${a}" \
        --version-id "${v}" --query PolicyVersion.Document --output json \
        2>/dev/null)" ||
        stop "관리형 정책"
      docs+="${doc}"
    done
    printf "policies %s\n" "$(( ${#names[@]} + ${#arns[@]} ))"
    printf "internal-api-token %s\n" \
      "$(printf "%s" "${docs}" | grep -c "internal-api-token")"
    printf "stream-key-resolve-token %s\n" \
      "$(printf "%s" "${docs}" | grep -c "stream-key-resolve-token")"
    '
    ```

    `internal-api-token 0` 이 아니면(공용 그릇이 다른 문장 · 변수 · 정책으로
    붙은 fail-open 갈래) 멈추고 kty 에게 회부합니다.
    `stream-key-resolve-token` 이 0 이면(양성 대조 — 역할을 잘못 읽음) ·
    「정책 읽기 실패」 가 나오면 0 건으로 읽지 않고 멈춥니다.
*   **공용 그릇 자원 정책(읽기)**: 아래가 `None` 이면 닫힘입니다. 런타임은
    그릇에 읽기 정책을 두지 않습니다. 정책이 있으면 내용을 화면에 내지 말고
    멈춥니다.

    ```shell
    if none="$(aws secretsmanager get-resource-policy --region ap-northeast-2 \
      --secret-id pokeclip/prod/internal-api-token \
      --query 'ResourcePolicy == null' --output text 2>/dev/null)"; then
      printf 'no-resource-policy %s\n' "${none}"
    else
      printf '멈춤: 자원 정책 읽기 실패\n'
    fi
    ```

    정책 내용은 내지 않고 「없음」 여부만 냅니다. `no-resource-policy True`
    가 아니면(`False` · 읽기 실패) 멈춥니다.

### 1단-나 ② 8. 공용 토큰 1회 회전

7 뒤 **늘** 합니다(kty 「무조건 1회 회전」). Media 역할이 공용 그릇을 읽은
적이 있는지는 따지지 않습니다. 아래 (a) · (b) · (b2) · (c) 를 이 순서로 모두
통과해야 값을 넣습니다. (a) 의 정책 원문 · 자원 정책 확인과 완료 조건의
옛 태스크 교체 완료 · 옛 토큰 401 확인은 kty 결정이 아니라 계획이 더한
것입니다(계획 rt1d 처분 R5 · R10 · P7).

*   **(a)** 7 의 실물 확인 둘(정책 문장 전부에서 `internal-api-token` 0 건 ·
    공용 그릇 자원 정책 없음)이 통과했습니다. 아니면 멈춥니다.
*   **(b) 시뮬레이터**(읽기 · 승인 불요 · 오류 원문에 ARN 이 섞여 세션의
    `!` 로 돌리지 않음): Media 역할 → `secretsmanager:GetSecretValue` 를 두
    그릇에 묻습니다. ARN 은 셸 변수와 요청 파일로만 다룹니다
    ([scp-recheck.sh](../scripts/precheck/scp-recheck.sh) 의 요청 파일 꼴).
    문맥 키는 `aws:RequestedRegion` · `aws:SecureTransport` 입니다(2-3 판정
    관례).

    ```shell
    cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
    sim="$(mktemp -d)"
    (
      umask 077
      role_arn="$(aws iam get-role --role-name pokeclip-prod-media \
        --query Role.Arn --output text)" || exit 1
      shared="$(terraform output -json app_secret_arns |
        jq -er '.auth.INTERNAL_API_TOKEN')" || exit 1
      resolve="$(terraform output -raw ingest_auth_resolve_token_secret_arn)" ||
        exit 1
      ctx='{"ContextKeyName":"aws:RequestedRegion","ContextKeyValues":'
      ctx+='["ap-northeast-2"],"ContextKeyType":"string"},'
      ctx+='{"ContextKeyName":"aws:SecureTransport","ContextKeyValues":'
      ctx+='["true"],"ContextKeyType":"boolean"}'
      for name in shared resolve; do
        arn="${shared}"
        [ "${name}" = resolve ] && arn="${resolve}"
        printf '{"PolicySourceArn":"%s","ActionNames":%s,"ResourceArns":["%s"],"ContextEntries":[%s]}\n' \
          "${role_arn}" '["secretsmanager:GetSecretValue"]' "${arn}" "${ctx}" \
          >"${sim}/${name}.json"
        if ! decision="$(aws iam simulate-principal-policy \
          --cli-input-json "file://${sim}/${name}.json" \
          --query 'EvaluationResults[0].EvalDecision' --output text \
          2>"${sim}/${name}.err")" || [ -z "${decision}" ]; then
          printf '멈춤: %s 판정 실패\n' "${name}"
          exit 1
        fi
        printf '%s %s\n' "${name}" "${decision}"
      done
    )
    rm -r "${sim}"
    # (b2) 의 media_ssm 은 저장소 뿌리 기준 경로를 쓴다 — 뿌리로 돌아간다.
    cd "$(git rev-parse --show-toplevel)"
    ```

    *   통과 = `shared implicitDeny` · `resolve allowed`. 전용은 양성
        대조입니다 — 역할 · ARN 을 잘못 넣어 생기는 거짓 거부를 가릅니다.
    *   `shared allowed`(권한이 남음) · `shared explicitDeny`(예상 밖 거부
        문장) · `resolve` 가 `allowed` 아님 → 멈춤 · kty 회부.
    *   「판정 실패」 는 거부로 읽지 않고 멈춥니다.
    *   한계: 시뮬레이터는 실제로 호출하지 않고, 자원 정책을 스스로 읽지
        않으며, 실환경과 다를 수 있습니다. 자원 정책은 (a), 전파는 (b2) ·
        (c) 가 맡습니다.
*   **(b2) 상자 직접 측정**(상자 명령 — 실행 직전 **kty 승인**): Media
    상자에서 인스턴스 역할로 공용 그릇을 실제로 불러 거부되는지 잽니다. 한
    묶음 = 측정 셋 · 5분 간격을 한 send-command 안에서 돕니다. 값은 표준
    출력째 버리고, 종료 코드와 오류 코드(`An error occurred (<코드>)` 의
    괄호 안)만 냅니다. 오류 원문에는 역할 · 계정 · ARN 이 있어 내지 않습니다.
    성공(값을 받음)이 나오면 그 자리에서 묶음을 끝냅니다.

    ```shell
    cat >"${work}/probe.sh" <<'EOF'
    denied=0
    for i in 1 2 3; do
      err="$(aws secretsmanager get-secret-value --region ap-northeast-2 \
        --secret-id pokeclip/prod/internal-api-token 2>&1 >/dev/null)"
      rc=$?
      code="$(printf '%s' "${err}" |
        sed -n 's/.*An error occurred (\([A-Za-z0-9]*\)).*/\1/p' | head -n 1)"
      unset err
      printf 'probe %s %s rc=%s code=%s\n' "${i}" "$(date -u '+%H:%M:%S')" \
        "${rc}" "${code:-NONE}"
      [ "${rc}" -ne 0 ] && [ "${code}" = AccessDeniedException ] &&
        denied=$((denied + 1))
      [ "${rc}" -eq 0 ] && break
      [ "${i}" -lt 3 ] && sleep 300
    done
    # 종료 코드가 판정이다 — 마지막 줄의 종료 코드(sleep 을 건너뛴 [ ] 의 1)가
    # 남지 않게 끝을 명시한다. 셋 다 거부면 0(SSM Success), 그 밖은 1.
    if [ "${denied}" -eq 3 ]; then
      printf 'verdict pass\n'
      exit 0
    fi
    printf 'verdict stop\n'
    exit 1
    EOF
    media_ssm <상자 키> "${work}/probe.sh"
    ```

    *   셋 다 `code=AccessDeniedException`(`rc` 는 0 이 아님) · `verdict
        pass` · `SSM Success` → 통과. (c) 는 셋째 측정 시각에서 끝난 것으로
        봅니다. 그 밖의 갈래는 모두 `verdict stop` · `SSM Failed` 입니다.
    *   한 번이라도 `rc=0`(성공) → 즉시 멈춤. 권한이 남았거나 전파 전입니다.
        회전하지 않고 (c) 30분을 다 채운 뒤 묶음을 다시 돕니다.
    *   측정 실패(send-command 실패 · 상자 무응답 · 다른 오류 코드 · 종료
        코드를 못 읽음) → 거부로 읽지 않습니다. (c) 30분을 다 채운 뒤 다시
        돕니다.
    *   다시 돈 묶음도 셋 다 `AccessDeniedException` 이 아니면 멈춤 · kty
        회부.
*   **(c) 대기**: 기본은 7 의 apply 완료 시각부터 30분이고, 지났음을 새로
    찍은 `date` 출력으로 확인합니다. (b2) 첫 묶음이 셋 다 AccessDenied 면
    셋째 측정 시각에서 끝납니다. AWS 는 IAM 변경이 모든 곳에 보이기까지
    시간이 걸린다고만 하고 수치를 주지 않습니다 — 30분 · 3회 · 5분은 kty
    확정 값입니다.
*   **일꾼 멈춤**(쓰기 — 실행 직전 **kty 승인**): 일꾼 render · upload 를
    값 넣기 전에 멈추고, 앱 서버 재배포가 끝난 뒤에 되살립니다. 앱 서버
    롤링 동안 옛 값 · 새 값 태스크가 겹쳐 일꾼의 내부 호출 일부가 401 을
    받고, 사진 보고가 401 을 받으면 그 주문 메시지가 그 자리에서 지워집니다
    (DLQ 를 거치지 않는 손실). 일꾼을 clip 보다 먼저 재배포해도 clip 의 롤링
    자체가 겹침을 만들어 이 창은 없어지지 않습니다. 멈춘 동안 주문은 큐에
    남고(보관 4일) 받은 횟수도 오르지 않습니다. 단 멈추는 순간 처리 중이던
    메시지는 처분 없이 남아 숨김 시간 뒤 다시 보이고, 되살린 뒤 다시 받을 때
    받은 횟수가 한 번 오릅니다(SQS 는 받을 때마다 셈).

    먼저 두 서비스의 지금 상태와 원하는 수를 기록합니다(읽기). 긴급 정지로
    이미 0 인 서비스를 복원 때 되살리지 않으려는 기록입니다.

    ```shell
    umask 077
    for k in render upload; do
      printf '%s %s\n' "${k}" "$(aws ecs describe-services \
        --region ap-northeast-2 --cluster pokeclip-prod \
        --services "pokeclip-prod-${k}" \
        --query 'services[0].[status,desiredCount]' --output text)"
    done | tee "${work}/workers-before.txt"
    ```

    먼저 키마다 지금 태스크 ARN 을 기록합니다(읽기). 서비스의 실행 수는
    `RUNNING` 만 세어, 내려가는 중(`DEACTIVATING` · `STOPPING`)인 태스크는
    `0 0 0` 뒤에도 남을 수 있습니다 — 그 태스크가 옛 값으로 보고하다 401 을
    받으면 메시지가 지워집니다([태스크 수명 주기](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-lifecycle-explanation.html)).
    목록은 서비스가 아니라 태스크 정의 family 로 뽑아 서비스가 사라진
    뒤에도 잡히게 하고, 원하는 상태 `RUNNING` · `STOPPED` 둘 다 받습니다(내려가는
    중인 태스크의 원하는 상태는 `STOPPED`). ARN 에 계정 ID 가 있어 화면에
    내지 않고 파일에만 둡니다.

    ```shell
    record_tasks() {  # record_tasks <키>
      local d out list="${work}/workers-tasks-$1.txt"
      : >"${list}"
      for d in RUNNING STOPPED; do
        out="$(aws ecs list-tasks --region ap-northeast-2 \
          --cluster pokeclip-prod --family "pokeclip-prod-$1" \
          --desired-status "${d}" --query 'taskArns[]' --output text)" ||
          return 1
        printf '%s\n' "${out}" | tr '\t' '\n' |
          sed -n '/^arn:aws:ecs:/p' >>"${list}"
      done
      sort -u -o "${list}" "${list}"
      printf '%s 태스크 %s개 기록\n' "$1" "$(wc -l <"${list}" | tr -d ' ')"
    }
    ```

    *   `<키> ACTIVE 1` → `record_tasks <키>` 뒤 0 으로 내립니다(쓰기 —
        **kty 승인**). 내리기 직전 시각을 키와 함께 남깁니다(복원 때
        CloudTrail 읽기의 시작 시각).

        ```shell
        stopped_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf '%s %s\n' "<키>" "${stopped_at}" >>"${work}/workers-stopped-at.txt"
        aws ecs update-service --region ap-northeast-2 --cluster pokeclip-prod \
          --service pokeclip-prod-<키> --desired-count 0 \
          --query service.serviceName --output text
        ```

    *   `<키> ACTIVE 0` → 이미 멈춤(긴급 정지 등)입니다. 원하는 수는
        그대로 두고 `record_tasks <키>` 만 합니다. 정지 직후면 태스크가
        아직 내려가는 중일 수 있어(남은 render 가 옛 값으로 보고하다 401 →
        메시지 삭제) 이 갈래도 아래 확인을 돌립니다.
    *   `<키> INACTIVE 0` → 서비스가 지워졌습니다(`prod/workers` 를 내린 뒤
        한동안 이 값입니다). `record_tasks <키>` 를 하고 아래 확인으로 남은
        태스크가 없는지만 봅니다. 복원 대상이 아닙니다.
    *   `<키> None` → 서비스 없음(`prod/workers` 를 apply 한 적이 없거나
        지운 지 오래됨)입니다. 기록만 합니다.
    *   그 밖의 값(`ACTIVE 2` · `DRAINING` 등) → 멈춤 · kty 회부.

    확인은 둘입니다(읽기 · 10초 간격 · 각 10분 상한). `wait_zero` 는 원하는
    수 · 실행 수 · 대기 수가 모두 0 이 될 때까지(`ACTIVE` 만), `wait_stopped`
    는 기록한 태스크가 모두 `lastStatus=STOPPED` 이고 그 family 에 원하는
    상태 `RUNNING` 인 태스크가 새로 없을 때까지 읽습니다. `workers-before.txt`
    에서 기록이 `ACTIVE` · `INACTIVE` 인 키만 돌고, 모두 통과해야 값을
    넣습니다. 하나라도 종료 1(상한 · 읽기 실패)이면 멈춤 · kty 회부입니다.

    ```shell
    wait_zero() {  # wait_zero <키>
      local i=0 counts
      while [ "${i}" -lt 60 ]; do
        counts="$(aws ecs describe-services --region ap-northeast-2 \
          --cluster pokeclip-prod --services "pokeclip-prod-$1" \
          --query 'services[0].[desiredCount,runningCount,pendingCount]' \
          --output text)" || return 1
        counts="$(printf '%s' "${counts}" | tr -s '\t ' ' ')"
        printf '%s %s\n' "$1" "${counts}"
        [ "${counts}" = '0 0 0' ] && return 0
        i=$((i + 1))
        sleep 10
      done
      printf '멈춤: %s 이 10분 안에 0 0 0 이 되지 않음\n' "$1"
      return 1
    }

    wait_stopped() {  # wait_stopped <키>
      local i=0 left fresh list="${work}/workers-tasks-$1.txt"
      while [ "${i}" -lt 60 ]; do
        left=0
        if [ -s "${list}" ]; then
          # shellcheck disable=SC2046 # ARN 은 공백 없는 한 낱말씩이다.
          left="$(aws ecs describe-tasks --region ap-northeast-2 \
            --cluster pokeclip-prod --tasks $(cat "${list}") \
            --query 'length(tasks[?lastStatus!=`STOPPED`])' \
            --output text)" || return 1
        fi
        fresh="$(aws ecs list-tasks --region ap-northeast-2 \
          --cluster pokeclip-prod --family "pokeclip-prod-$1" \
          --desired-status RUNNING --query 'length(taskArns)' \
          --output text)" || return 1
        printf '%s 안 멈춘 기록 태스크 %s · 새 RUNNING %s\n' "$1" "${left}" "${fresh}"
        [ "${left}" = 0 ] && [ "${fresh}" = 0 ] && return 0
        i=$((i + 1))
        sleep 10
      done
      printf '멈춤: %s 의 태스크가 10분 안에 모두 STOPPED 가 되지 않음\n' "$1"
      return 1
    }

    ok=1
    while read -r k state _; do
      case "${state}" in
        ACTIVE) { wait_zero "${k}" && wait_stopped "${k}"; } || ok=0 ;;
        INACTIVE) wait_stopped "${k}" || ok=0 ;;
      esac
    done <"${work}/workers-before.txt"
    if [ "${ok}" = 1 ]; then
      printf '일꾼 멈춤 확인 — 값 넣기로\n'
    else
      printf '멈춤: 일꾼 멈춤 확인 실패 — kty 회부\n'
    fi
    ```

*   **값 넣기**(실행 직전 **kty 승인**): 4 와 같은 꼴로 종류
    `internal-api-token` 에 `openssl rand -hex 32` 값을 넣습니다. 전용 토큰
    값과 달라야 합니다. 옛 값은 `AWSPREVIOUS` 로 남고 10 이 그것을 씁니다.
    종료 4(DENIED)는 다시 시도하지 않고 멈춥니다.
*   **앱 전원 강제 재배포**: 대상 = 공용 그릇을 읽는 서비스 키 중 일꾼
    render · upload 를 뺀 전부입니다(일꾼은 「일꾼 멈춤」 · 「일꾼 복원」 이
    맡습니다). 손으로 적지 않고 출력에서 뽑습니다(키만 냄 · ARN 은 내지
    않음).

    ```shell
    # 값 넣기가 저장소 뿌리로 왔다 — 출력은 하위 셸에서 runtime 으로 가 읽고,
    # 9 · 10 의 media_ssm 이 쓰는 뿌리 cwd 는 그대로 둔다. 출력 실패나 auth ·
    # clip 이 빠진 목록은 조용히 지나가지 않고 종료 1 로 멈춘다. 일꾼 둘은
    # 멈춘 채 두므로 필터가 걸러 낸다.
    (
      set -o pipefail
      cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime" ||
        exit 1
      filter='to_entries[] | select(.value | has("INTERNAL_API_TOKEN")) | .key'
      filter+=' | select(. != "render" and . != "upload")'
      keys="$(terraform output -json app_secret_arns | jq -r "${filter}")" ||
        exit 1
      for want in auth clip; do
        printf '%s\n' "${keys}" | grep -qx "${want}" && continue
        printf '멈춤: 재배포 대상에 %s 없음\n' "${want}" >&2
        exit 1
      done
      printf '%s\n' "${keys}"
    )
    ```

    키 `<키>` 는 클러스터 `pokeclip-prod` 의 ECS 서비스
    `pokeclip-prod-<키>` 입니다(`modules/ecs_service` 의
    `<name_prefix>-<name>`). 키마다 상태를 읽습니다.

    ```shell
    aws ecs describe-services --region ap-northeast-2 \
      --cluster pokeclip-prod --services "pokeclip-prod-<키>" \
      --query 'services[0].status' --output text
    ```

    *   `ACTIVE` → 강제 재배포(쓰기 — **kty 승인**):
        `aws ecs update-service --region ap-northeast-2 --cluster
        pokeclip-prod --service pokeclip-prod-<키> --force-new-deployment
        --query service.serviceName --output text`. chat-collector 의
        재배포는 수집 중인 방송을 잠시 끊습니다 — 재부착(platform
        `chat_collector_features.reattach`, 기본 켬)이 다시 붙기 전까지의
        채팅은 받지 못하므로 되도록 방송이 없는 시간에 합니다(platform
        README 「chat 서비스」). chat-detector 는 옛 태스크를 먼저
        내립니다(겹침 대신 공백) — 새 태스크가 뜰 때까지 판정이 멈추고
        메모리에 열려 있던 사건의 카드를 잃습니다(같은 절).
    *   `None`(서비스 없음 — 응답의 `failures` 에 들어감) → 다음에 뜨는
        태스크가 새 값을 받습니다. 키와 함께 기록합니다. platform 은
        next · auth · clip · chat-collector · chat-detector 를 두므로(O7d
        뒤) 앱 서버 키는 이 갈래가 아닙니다. 일꾼은 위 필터가 대상에서
        빼고, `prod/workers` 를 내렸을 때 일꾼이 이 갈래입니다(「일꾼 멈춤」
        기록의 `INACTIVE` · `None`).
    *   그 밖의 값(`DRAINING` · `INACTIVE` 등) → 멈춤 · kty 회부.
    *   규칙의 전제: 클러스터는 platform 의 `pokeclip-prod` 하나이고,
        서비스 정의는 platform(앱 서버)과 `prod/workers`(일꾼 render ·
        upload) 두 루트가 `modules/ecs_service` 로 만듭니다. 다른 클러스터 ·
        루트가 생기면 이 규칙을 그 판으로 고칩니다.
*   **완료 조건**: ① `ACTIVE` 였던 서비스마다 옛 태스크 교체 완료 — 아래
    읽기가 `1 COMPLETED <n> <n>`(배포 하나 · 완료 상태 · 실행 수 = 원하는
    수 — 옛 배포의 태스크가 남지 않음)이어야 합니다 ② 옛 공용 토큰
    401 — 상자 → auth 8082 SG 짝이 있어야 해서 10 에서 합니다(그 전에는
    「401 미확인」 으로 적음). 롤링 동안 옛 값 · 새 값 태스크가 섞여 내부
    호출 일부가 401 입니다(거부 창). 창의 길이를 11 에 적습니다.

    ```shell
    aws ecs describe-services --region ap-northeast-2 \
      --cluster pokeclip-prod --services "pokeclip-prod-<키>" \
      --query 'services[0].[length(deployments),deployments[0].rolloutState,deployments[0].runningCount,desiredCount]' \
      --output text
    ```

*   **일꾼 복원**(쓰기 — 실행 직전 **kty 승인**): 앱 서버 키가 모두 완료
    조건 ① 의 `1 COMPLETED <n> <n>` 이 된 뒤에 합니다. `workers-before.txt`
    의 기록값으로 되돌립니다 — 이 절차가 내린 것(`<키> ACTIVE 1`)만 1 로
    올립니다.

    올리기 직전에 그 서비스를 다시 읽습니다(읽기). 올리는 조건은 둘입니다.

    1.  아래 읽기가 `ACTIVE 0` 입니다. 그 밖이면(누가 다시 띄웠거나 서비스가
        사라짐) 올리지 않고 멈춤 · kty 회부입니다.
    1.  멈춘 뒤 그 서비스의 긴급 정지 기록([prod/workers](../prod/workers/README.md)
        「긴급 정지와 상태 어긋남」 의 어긋남 · 시각 · 사유)이 없습니다.
        이 절차가 이미 0 으로 내렸으므로, 그사이의 긴급 정지는 원하는 수로는
        보이지 않습니다 — 기록이 있으면 0 을 유지하고 그 정지의 절차를
        따릅니다. 보조 신호로 멈춘 시각 뒤 그 서비스의 `UpdateService`
        호출을 CloudTrail 에서 봅니다(아래 읽기 — `stopped_at` 은 「일꾼
        멈춤」 이 `workers-stopped-at.txt` 에 남긴 그 키의 시각, 몇 분 늦게
        보임). ECS
        서비스 이벤트는 원하는 수 변경 자체를 적지 않고 태스크 시작 · 정지만
        적어, 원하는 수 변경의 기록은 이쪽입니다. 이 절차가 낸 호출(멈춤 한
        번) 밖의 호출이 있으면 기록이 없어도 0 을 유지하고 kty 회부입니다.

    ```shell
    aws ecs describe-services --region ap-northeast-2 \
      --cluster pokeclip-prod --services "pokeclip-prod-<키>" \
      --query 'services[0].[status,desiredCount]' --output text
    stopped_at="$(awk '$1 == "<키>" { print $2 }' "${work}/workers-stopped-at.txt")"
    aws cloudtrail lookup-events --region ap-northeast-2 \
      --lookup-attributes AttributeKey=EventName,AttributeValue=UpdateService \
      --start-time "${stopped_at}" \
      --query "Events[?contains(CloudTrailEvent, 'pokeclip-prod-<키>')].EventTime" \
      --output text | tr '\t' '\n'
    ```

    ```shell
    aws ecs update-service --region ap-northeast-2 --cluster pokeclip-prod \
      --service "pokeclip-prod-<키>" --desired-count 1 \
      --query service.serviceName --output text
    ```

    *   기록이 `ACTIVE 0` 이던 서비스는 0 을 유지하고 기록합니다(다시
        띄우는 것은 그 정지의 절차 · 승인 몫).
    *   기록이 `INACTIVE` · `None` 이던 서비스는 할 일이 없습니다(지워졌거나
        없음 — 복원 대상 아님).
    *   완료: 올린 서비스마다 `wait_zero` 와 같은 읽기
        (`services[0].[desiredCount,runningCount,pendingCount]`)가 `1 1 0`
        입니다. 새 태스크가 새 값을 읽습니다.

### 1단-나 ② 9. 응답기 기동 · 교체

선행 조건: Media ↔ auth 8082 SG 짝(prod/media 가 만듦 — kty 결정 2026-10-25
(나), [계약](../CONTRACT-runtime.md) 「platform → media」 `service_ports.auth`
행)이 적용됨. 없으면 기다립니다. M15 의
렌더 · `config_version` 으로 응답기를 다시 띄우고(send-command **kty
승인**) 아래를 봅니다.

*   started 줄에 `auth_channel=resolve-token auth_host_kind=name
    auth_port=8082`.
*   시험 송출이 `publish_allowed`, `ingestauth_auth_degraded` 0.

### 1단-나 ② 10. 음성 확인

선행 조건은 9 와 같습니다.

*   **전용 토큰**: 상자에서 전용 토큰으로 `POST /internal/youtube-link/status`
    → 401(send-command **kty 승인**). 상자가 전용 그릇의 값을 셸 변수로 받아
    셸 내장 `printf` 파이프로만 curl 헤더에 싣고, 상태 코드만 냅니다. 이
    스크립트는 긴급 차단 · 되돌리기도 씁니다.

    ```shell
    cat >"${work}/token-probe.sh" <<'EOF'
    # token-probe.sh <경로> <버전 단계>… — 전용 그릇의 그 단계 값으로 auth 의
    # <경로> 를 부르고 코드만 낸다. 종료 0 = 모든 단계를 재어 봄(판정은 줄).
    set +o xtrace +o allexport
    unset token
    trap 'unset token; exit 130' INT
    api_path="$1"  # path 는 zsh 에서 PATH 와 묶인 특수 변수라 피한다.
    shift
    fail=0
    for stage in "$@"; do
      token="$(aws secretsmanager get-secret-value --region ap-northeast-2 \
        --secret-id pokeclip/prod/stream-key-resolve-token \
        --version-stage "${stage}" --query SecretString --output text \
        2>/dev/null)"
      if [ "$?" -ne 0 ] || [ -z "${token}" ]; then
        unset token
        printf 'token-probe %s 멈춤: 읽지 못함\n' "${stage}"
        fail=1
        continue
      fi
      code="$(printf 'X-Stream-Key-Resolve-Token: %s\n' "${token}" |
        curl -q -sS -o /dev/null -w '%{http_code}' -H @- \
          -H 'Content-Type: application/json' \
          -d '{"streamid":"#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish"}' \
          "http://auth.pokeclip.internal:8082${api_path}")"
      rc=$?
      unset token
      [ "${rc}" -eq 0 ] || fail=1
      printf 'token-probe %s rc=%s code=%s\n' "${stage}" "${rc}" "${code:-NONE}"
    done
    exit "${fail}"
    EOF
    media_ssm <상자 키> "${work}/token-probe.sh" \
      /internal/youtube-link/status AWSCURRENT
    ```

    `token-probe AWSCURRENT rc=0 code=401` · `SSM Success` 이면 통과입니다.
    `curl` 에 `-v` · `--trace` 를 붙이지 않습니다(헤더가 찍힘). `-q` 는
    첫 인자여야 `.curlrc` 를 읽지 않으므로 앞에 둡니다. 「멈춤」 줄 ·
    `rc` 가 0 아님 · 401 밖의 코드는 판정 불가 · 멈춤입니다.
*   **옛 공용 토큰**(8 의 완료 조건 ②): 상자는 공용 그릇을 못 읽으므로
    상자에서 보내지 않습니다. kty 가 세션 밖 터미널에서 Media 상자를 거치는
    포트 포워딩(`aws ssm start-session` · 문서
    `AWS-StartPortForwardingSessionToRemoteHost` · 대상 auth 의 Cloud Map
    이름과 포트 8082 — 상자 세션이라 **kty 승인**)을 열고, 다른 터미널에서
    보냅니다. 값은 셸 내장 `printf` 파이프로만 넘깁니다(put-secret 과 같은
    꼴).

    ```shell
    # 하위 셸 — 값 변수 · 추적 끔 · INT 트랩이 터미널 셸에 남지 않는다.
    # token 은 export 하지 않고, curl 에 -v · --trace 를 붙이지 않는다.
    # curl 의 -q 는 첫 인자라야 .curlrc 를 읽지 않는다.
    (
      set +o xtrace +o allexport
      unset token
      trap 'unset token; exit 130' INT
      for stage in AWSPREVIOUS AWSCURRENT; do
        token="$(aws secretsmanager get-secret-value \
          --region ap-northeast-2 --secret-id pokeclip/prod/internal-api-token \
          --version-stage "${stage}" --query SecretString --output text \
          2>/dev/null)"
        if [ "$?" -ne 0 ] || [ -z "${token}" ]; then
          unset token
          printf '멈춤: %s 를 읽지 못함\n' "${stage}"
          break
        fi
        code="$(printf 'X-Internal-Token: %s\n' "${token}" |
          curl -q -s -o /dev/null -w '%{http_code}' -H @- \
            -H 'Content-Type: application/json' \
            -d '{"streamid":"#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish"}' \
            "http://localhost:<로컬 포트>/internal/stream-keys/resolve")"
        unset token
        printf '%s %s\n' "${stage}" "${code}"
      done
    )
    ```

    *   `AWSPREVIOUS 401` 이고 `AWSCURRENT 200`(공용 창구 resolve 는 출발지
        잠금이 없음 · 본문은 없는 키라 `valid:false`)이면 통과입니다.
    *   양성 대조(`AWSCURRENT`)가 200 이 아니면 판정 불가 · 멈춤 · kty 회부.
    *   포트 포워딩은 위 블록을 돌리는 동안만 열어 둡니다. 끝나면 바로 닫고
        잔여 0(`pgrep -f session-manager-plugin`)을 확인합니다.

### 1단-나 ② 11. 기록

*   볼트 raw(비밀 값 훑기 뒤 · 계정 ID · ARN 은 가림): 각 걸음의 명령 ·
    출력 · 판정, 8 (b) 판정, (b2) 측정마다 시각 · 종료 코드 · 오류 코드,
    (c) 대기 시작 · 끝 시각, 서비스 키마다 재배포 또는 「서비스 없음」,
    일꾼의 멈춤 전 기록(`workers-before.txt`) · 멈춘 시각 · 복원 결과,
    거부 창 길이, 옛 공용 토큰 401.
*   POK-429 코멘트와 완료 전이.

### 1단-나 ② 긴급 차단

전용 토큰이 샜을 때입니다.

1.  그릇 `stream-key-resolve-token` 에 새 난수 값을 넣습니다(4 와 같은 꼴 ·
    **kty 승인**).
1.  auth 를 강제 재배포합니다(8 의 `update-service` 꼴 · **kty 승인**).
    ECS 는 비밀이 바뀌면 새 배포 · 새 태스크라야 새 값을 받습니다.
1.  재배포 요청만으로는 끝나지 않습니다 — 롤링 동안 옛 태스크는 옛 값을
    계속 받습니다. **완료 조건**: auth 의 옛 태스크가 모두 새 태스크로
    교체됨(8 의 완료 조건 ① 과 같은 읽기) **그리고** 상자에서 옛 토큰으로
    resolve 를 불러 401(send-command **kty 승인** · 10 의 `token-probe.sh`).
    옛 값은 `AWSPREVIOUS`, 새 값은 `AWSCURRENT` 입니다.

    ```shell
    media_ssm <상자 키> "${work}/token-probe.sh" \
      /internal/stream-keys/resolve AWSPREVIOUS AWSCURRENT
    ```

    `AWSPREVIOUS` 가 `code=401` 이고 `AWSCURRENT` 가 `code=200`(양성 대조 —
    없는 키라 본문은 `valid:false`)이면 통과입니다. `AWSCURRENT` 가 200 이
    아니면 판정 불가 · 멈춤 · kty 회부.

새 송출은 응답기를 다시 렌더하기 전까지 거부됩니다(fail-closed). 응답기
창구를 `internal-token` 으로 되돌리는 길은 쓰지 않습니다 — 공용 열쇠가
상자로 돌아갑니다.

### 1단-나 ② 복구

긴급 차단 뒤 원인을 확인하고, 응답기를 다시 렌더합니다(시작 스크립트가
그릇을 다시 읽음 · M15 몫의 명령 · send-command **kty 승인**). started
`auth_channel=resolve-token` 과 시험 송출 `publish_allowed` 를 확인합니다.

### 1단-나 ② 전용 토큰 회전

순서는 값 넣기 → auth 재배포(옛 태스크 교체 완료까지) → 응답기 재렌더입니다.
auth 롤링 동안 옛 태스크 · 새 태스크가 섞여, 어느 토큰이든 일부 요청이
401 입니다(거부 창 — 진행 중인 송출은 무사). 창의 길이를 기록합니다.

### 1단-나 ② 되돌리기

*   **코드**: 커밋 단위 revert, 머지 뒤에는 `git revert -m 1`. 위키는 위키 PR
    revert.
*   **runtime apply 뒤 · platform 전**: 코드 revert → runtime plan 이 그릇을
    지우고(30일 복구 대기) `media_secret_arns` 가 공용 그릇으로 돌아갑니다.
    **Media 에 공용 열쇠를 다시 줍니다** — 다음 prod/media apply 전에 kty
    확인. 그 상태로 prod/media apply 를 했으면, 다시 앞으로 갈 때 7 뒤 8 을
    다시 합니다.
*   **platform apply 뒤(창구 열림)**: 긴급 차단이 먼저입니다. 창구를 닫는
    되돌림은 두 단계입니다 — 그릇을 먼저 지우면 auth 태스크 정의가 지워질
    그릇을 가리킨 채 남아, 그 사이에 뜨는 새 태스크가 비밀을 못 받습니다.
    1.  `app_secret_arns.auth` 의 `STREAM_KEY_RESOLVE_TOKEN` 과 `app_env.auth`
        의 `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` 를 빼는 커밋(그릇 ·
        `media_secret_arns` 는 그대로) → runtime plan · apply → platform
        plan · apply → auth 옛 태스크 교체 완료 → 전용 헤더 요청 401
        확인(새 송출 거부 — 10 의 `token-probe.sh` 를
        `/internal/stream-keys/resolve AWSCURRENT` 로 · **kty 승인**).
    1.  그릇을 지우는 커밋 → runtime plan · apply. `media_secret_arns` 를
        공용 그릇으로 되돌릴지는 따로 kty 확인(공용 열쇠를 상자에 다시 줌).
*   **prod/media apply 뒤**: 정책을 되돌리면 응답기가 다음 기동에서 그릇을
    못 읽어 기동이 막힙니다(fail-closed). 되돌리기는 응답기 렌더 → media
    정책 → runtime 출력 한 방향으로만 합니다.

## POK-306 runtime apply — 채팅 원본 버킷 · chat-collector 값

1단-다를 이미 적용한 환경(지금 prod)에서 POK-306 의 런타임 몫(PR-1)을
적용하는 절차입니다. 채팅 원본 버킷 하나(자원 다섯)를 만들고, chat-collector
몫 출력(앱 ID 둘 · SOOP 비밀 · `S3_BUCKET`)을 냅니다. 1단-다를 처음
적용하는 경우는 이 절이 아니라 「1단-다 2」 의 `+5` 기준을 봅니다. 머리의
실행 규칙(kty 로컬 터미널 · 쓰기마다 직전 승인 · 출력을 세션에 붙이지
않음)이 그대로 걸립니다. AccessDenied 가 나오면 그 자리에서 멈추고 kty
에게 보고합니다.

*   **언제**: PR-1 머지 뒤, POK-306 의 platform apply(O7d 묶음) 앞입니다.
    platform 은 이 루트의 `chat_archive_bucket_arn` 과 chat-collector 의
    `app_env` 를 읽으므로 런타임이 먼저입니다.
*   **값 넣기 없음**: 새 그릇이 없습니다. chat-collector 가 받는
    `SOOP_CLIENT_SECRET` 은 auth 와 같은 그릇이고, 값은 「1단-다 5」 에서
    이미 넣었습니다.

### POK-306 runtime apply 순서

| 걸음 | 하는 일 | 승인 지점 |
|---|---|---|
| 1 | 직전 점검(`runtime-1a`) | ⑤ 보고 |
| 2 | runtime plan · apply | plan 승인 |
| 3 | 운영 확인 — 채팅 원본 버킷 | — |
| 4 | O7d 묶음 직전 점검 → platform apply | O7d 규칙 |

### POK-306 runtime apply 1. 직전 점검

1 의 세 명령(`scp-recheck.sh runtime-1a` · `principal-check.sh runtime-1a`
· `events.sh`)을 그 순서로 돌리고 ⑤ 보고를 합니다.

*   ① PR-1 은 `runtime-1a` 묶음을 바꾸지 않습니다 — 종료 0 이 기대값입니다.
    CHANGED(3)면 다른 PR 의 묶음 변경이니 ⑤ 보고와 함께 묻고, 답을 들은
    뒤에만 `--accept` 합니다.
*   ② 판정 · 멈춤 조건은 「1단-나 ② 1」 의 ② 와 같습니다(1단-가 apply 뒤
    기준). 채팅 원본 버킷 · 객체 행은 O7d 부터라 여기서 묻지 않습니다.
*   ③ 종료 2 면 시작하지 않습니다.

### POK-306 runtime apply 2. plan · apply

2 의 plan · 확인 · apply · 적용 확인 꼴로 합니다. tfvars 는 그대로입니다
(새 변수 `chat_archive_bucket_name` 은 기본값). 기대 변화는 아래뿐입니다 —
그 밖의 변화(다른 주소 · change · destroy)가 있으면 apply 하지 않습니다.

*   `Plan: 5 to add, 0 to change, 0 to destroy`.
*   추가 주소 다섯:
    *   `aws_s3_bucket.chat_archive`
    *   `aws_s3_bucket_public_access_block.chat_archive`
    *   `aws_s3_bucket_ownership_controls.chat_archive`
    *   `aws_s3_bucket_server_side_encryption_configuration.chat_archive`
    *   `aws_s3_bucket_lifecycle_configuration.chat_archive`
*   출력 변경:
    *   새 출력 `chat_archive_bucket_name` · `chat_archive_bucket_arn`.
    *   `app_env["chat-collector"]` 에 `CHZZK_CLIENT_ID` · `SOOP_CLIENT_ID` ·
        `S3_BUCKET` 이 더해집니다.
    *   `app_secret_arns["chat-collector"]` 에 `SOOP_CLIENT_SECRET` 이
        더해집니다.
    *   다른 서비스(auth · clip · chat-detector · 일꾼)의 출력은 그대로입니다.
*   `aws_s3_bucket_policy` · `aws_s3_bucket_versioning` ·
    `aws_s3_bucket_cors_configuration` 이 생기지 않습니다.

kty 승인을 받은 뒤 apply 합니다. 새 자원의 첫 판정 단계가 O7d 라 apply 뒤
`runtime-1a` 2-3 재판정은 하지 않습니다 — 4 의 O7d 점검이 묻습니다.

### POK-306 runtime apply 3. 운영 확인

「1단-다 3」 의 버킷 반복(세 버킷)과 채팅 원본 접두 확인 명령을 돌립니다.
기대 출력은 그 절의 「채팅 원본 버킷」 항목입니다. 결과 줄은 볼트 raw 로
옮깁니다(계정 ID · ARN 가림).

### POK-306 runtime apply 4. O7d 로 넘김

[단계 직전 점검](stage-precheck.md) 의 O7d 묶음 점검을 돌린 뒤 POK-306 의
platform apply 로 갑니다. 이때 2-3 판정이 처음으로 채팅 원본 버킷 · 객체
행을 묻습니다 — 버킷이 있으므로 정책 읽기가 「정책 없음」 이어야 합니다.
platform 의 plan · 확인은 [prod/platform](../prod/platform/README.md) 을
따릅니다.

## See also

*   [prod/runtime](../prod/runtime/README.md) — 출력 · 실행기 스위치 · 대안 ·
    1단-나 이월
*   [런타임 트랙 인터페이스 계약](../CONTRACT-runtime.md) ·
    [단계 직전 점검](stage-precheck.md)
*   [ADR-0001 stream_segments DDL 소유권](../../../docs/decisions/2026-08-01-stream-segments-ddl-ownership.md)
