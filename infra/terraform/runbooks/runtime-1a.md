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
    입력 파일 없음)의 `TARGET_NONE` 은 이 걸음에서 정상입니다. 「진행」 의
    이유로 그렇게 적습니다.
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

## 4. 실행기 켬

1.  1 의 점검을 다시 합니다(같은 묶음 · `--since` 는 그때의 비교 기준).
    1 의 통과 조건이 아니면 켜지 않습니다. 이번에는 아래도 멈춤입니다.
    *   2-3 판정의 `report.txt` 에 「자원 없음(첫 단계 — 정책 없이
        판정)」 줄이 있음 — 본 apply 뒤인데 주제 · 큐가 없다는 뜻이라
        이상입니다. 도구는 첫 단계라 통과로 셉니다. 사람이 이 줄을 보고
        멈춥니다.
    *   `rds-master-secret` 행이 `TARGET_NONE` 이거나 `ALLOWED` 임(3-1).

    `prod-instance` 의 `TARGET_NONE` 은 실행기가 아직 꺼져 있어 정상입니다.
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
    120분 안에 7(끔)을 시작합니다.** 준비 단계(5-3)가 상자에 120분 뒤 자동
    종료(`shutdown -h +120`)를 걸어 두지만, 상자가 멈춰도 자원 · 역할은
    남으므로 7 은 그대로 합니다.
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
# 자동 종료를 맨 앞에 건다 — 뒤 명령이 실패해도 상자는 120분 뒤 멈춘다.
prepare = ["set -eu", "umask 077", "shutdown -h +120",
           f"mkdir -p {box}/sql", f"cd {box}/sql"]
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
    실패합니다. 준비는 맨 앞에서 상자에 120분 뒤 자동 종료를 겁니다.
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
`40_verify.sql` 이 반드시 실패합니다). 손 SQL 은 새 페이로드이고 kty 승인을
새로 받습니다 — 같은 흐름 안에서 임의로 다시 보내거나 덧붙여 보내지
않습니다. 페이로드는 5-1 의 `conn` 명령 뒤에 그 SQL 을
`psql -X -1 -v ON_ERROR_STOP=1` 로 돌리고 이어서 `40_verify.sql` 을 돌리는
JSON 입니다. 보내기 전에 SQL 원문을 kty 에게
보이고 승인을 받습니다. 판정은 5 와 같습니다(상태 `Success` · `VERIFY OK`).
실패하면 이 절의 1부터 다시 따릅니다.

## 되돌리기 · 해체

*   **SQL**: 파일마다 트랜잭션이라 실패한 파일은 남지 않습니다. 적용을 통째로
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
    읽을 수 있습니다. 그래서 SQL 직후 끕니다. 120분 뒤 자동 종료는 준비
    단계(5-3)가 건 뒤에만 있고, 4(켬)에서 5-3 사이에는 시한이 없습니다. 그
    사이 멈추면 바로 7(끔)을 합니다.
*   명령 질문은 인스턴스 ARN 만 묻고 문서(`AWS-RunShellScript`) 쪽 허용은
    묻지 않습니다. dev 배포 주체 둘은 그 문서 허용이 양성 대조라 갈리는
    쪽이 인스턴스이기 때문입니다. dev 정책이 인스턴스를 태그 조건으로 열면
    시뮬레이터가 누락 문맥을 내고 판정 불가(`MISSING_CONTEXT`)가 됩니다.
*   자동화(`ssm:StartAutomationExecution`)로 켜진 상자에 명령을 보내는 길은
    묻지 않습니다. 이 동작은 인스턴스 자원 유형을 받지 않아(자동화 문서 ·
    실행만 — [서비스 승인 참조](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ssm.html))
    인스턴스 ARN 으로 물을 수 없습니다. 실행기가 켜진 동안(5-3 뒤로는 최대
    120분)만 열린 길이라 위의 짧게 켜 두기가 이 한계도 좁힙니다.
*   SQL 실적용 시험(CI)은 Go 메타 명령 처리기로 돌고 psql 자체가 아니며,
    대역은 `rds_superuser` 가 아닙니다. TLS · `rds.force_ssl` 은 CI 가 재지
    못합니다.
*   연결 상한 20 · `statement_timeout` 60s 는 측정값이 아닙니다(O7e 에서
    다시 봅니다).

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
    정책 없이 판정합니다. clips · 프로필 사진 버킷과 그 객체 행은 O7d
    부터라 묻지 않습니다. 종료 3 이면 kty 가 `approvals.tsv` 에 「진행」 을
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
for bucket in pokeclip-prod-clips-apne2 pokeclip-prod-profile-photos-apne2; do
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
    보고합니다. `prod-instance` 의 `TARGET_NONE` 은 실행기가 꺼져 있어
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
    (일꾼 배치 PR 의 apply 기록 · 일꾼 서비스 desired count. 보는 명령은
    배치 PR 이 채웁니다).
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

        ```sql
        SELECT 'render_jobs', pg_catalog.count(*) FROM render_jobs
          WHERE status IN ('queued', 'started')
        UNION ALL
        SELECT 'clip_uploads', pg_catalog.count(*) FROM clip_uploads
          WHERE status IN ('queued', 'uploading')
        UNION ALL
        SELECT 'purged_streamers', pg_catalog.count(*) FROM purged_streamers
          WHERE completed_at IS NULL;
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
| 3 | 이 런북 5 — 이 PR 의 머지 SHA 로 00 → 10(새 판) → 20 → 30 → 40 | send-command 승인 |
| 4 | 이 런북 6 — 50 → 40(갈래 기록만) | send-command 승인 |
| 5 | 이 런북 7 · 8 — 실행기 끔 · 잔여 0 · 기록(POK-428) | plan 승인 |
| 6 | O7d · POK-306 — 서버가 처음 떠 자기 스키마에 표를 만듦 | 그 단계 몫 |
| 7 | 기동 뒤 확인 | send-command 승인 |

1.  1단-가 SQL 이 아직 적용되지 않았으면 이 절 대신 이 런북 1 부터 8 을 이
    PR 의 머지 SHA 로 처음부터 돕니다(같은 SQL 판입니다). 이미 적용됐으면
    2 부터 갑니다.
1.  5 는 1단-가 판이 이미 적용된 DB 에서 그대로 다시 돕니다. 10 · 20 · 30
    은 그 위에서도 같은 상태로 수렴합니다 — media 시험
    `TestProdSQLConvergesOverRuntime1aRoles`
    (`media/internal/index/prod_sql_apply_pg_test.go`)가 1단-가 판 10 의
    사본(`testdata/prod_sql_runtime_1a_10_roles.sql`) · 20 · 30 위에 새
    10 · 20 · 30 · 40 을 돌려 `VERIFY OK` 를 냅니다. 40 의 새 식별자(이름
    · 뜻은 `40_verify.sql` 머리)로 실패하면 「실패 · 시간 초과」 로
    갑니다. 앱 서버가 뜨기 전이라 `public_owned_by_app:<계정>` 이 실패하면
    이상입니다 — 무엇이 있는지 확인하고 kty 에게 회부합니다.
1.  6 의 50 은 K7 (가)라 갈래와 무관하게 회수하지 않습니다. 출력은 볼트
    raw 와 POK-428 코멘트에 기록합니다.
1.  서버가 처음 뜨는 순서는 auth · clip(O7d) → 수집 서버(POK-306) → 판별
    서버입니다. 수집 서버의 Flyway(`V316`)가 판별 계정에 `chat_messages`
    SELECT 를 주므로 판별 서버는 그 뒤에 뜹니다.

### 1단-나 ① 기동 뒤 확인

*   서버마다 기동 로그의 Flyway 줄이 자기 스키마를 가리킵니다 — 꼴
    `Successfully applied <N> migrations to schema "app_auth"`(clip 은
    `"app_clip"` 등 서버마다 자기 스키마 이름).
*   실행기를 다시 켜는 흐름(4 → 손 SQL → 7)으로 아래를 「실패 · 시간
    초과」 의 손 SQL 규칙대로 보냅니다. 첫째는 0행이어야 하고, 둘째는 수집
    서버가 처음 뜬 뒤 `t` 여야 합니다(수집 서버가 뜨기 전에는 표가 없어
    42P01 로 실패합니다). 둘째가 `f` 면 5-3 의 손 줄을 돌립니다. 뒤이은 40
    의 `public_owned_by_app:<계정>` 이 표뿐 아니라 함수 · 형까지 같은 것을
    잽니다.

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
*   dev 공유 DB 의 되돌리기는 [services/README.md](../../../services/README.md)
    「dev 앱별 스키마 이전과 되돌리기」 입니다.

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
| 8 | 공용 토큰 1회 회전(조건 없음) | send-command · 값 입력 · 재배포 |
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
    정상」 은 맞지 않음). 정상인 `TARGET_NONE` 은 `prod-instance` 하나뿐입니다.
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
*   **값 넣기**(실행 직전 **kty 승인**): 4 와 같은 꼴로 종류
    `internal-api-token` 에 `openssl rand -hex 32` 값을 넣습니다. 전용 토큰
    값과 달라야 합니다. 옛 값은 `AWSPREVIOUS` 로 남고 10 이 그것을 씁니다.
    종료 4(DENIED)는 다시 시도하지 않고 멈춥니다.
*   **앱 전원 강제 재배포**: 대상 = 공용 그릇을 읽는 서비스 키 전부.
    손으로 적지 않고 출력에서 뽑습니다(키만 냄 · ARN 은 내지 않음).

    ```shell
    # 값 넣기가 저장소 뿌리로 왔다 — 출력은 하위 셸에서 runtime 으로 가 읽고,
    # 9 · 10 의 media_ssm 이 쓰는 뿌리 cwd 는 그대로 둔다. 출력 실패나 auth ·
    # clip 이 빠진 목록은 조용히 지나가지 않고 종료 1 로 멈춘다.
    (
      set -o pipefail
      cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime" ||
        exit 1
      filter='to_entries[] | select(.value | has("INTERNAL_API_TOKEN")) | .key'
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
        --query service.serviceName --output text`.
    *   `None`(서비스 없음 — 응답의 `failures` 에 들어감) → 다음에 뜨는
        태스크가 새 값을 받습니다. 키와 함께 기록합니다. 지금 platform 은
        next · auth · clip 셋만 두므로 chat-collector · chat-detector 와
        일꾼(render · upload)이 이 갈래입니다.
    *   그 밖의 값(`DRAINING` · `INACTIVE` 등) → 멈춤 · kty 회부.
    *   규칙의 전제: ECS 서비스 · 클러스터 자원은 `modules/ecs_service` 와
        platform 의 클러스터 하나뿐입니다. 다른 클러스터 · 배치 루트가
        생기면 이 규칙을 그 판으로 고칩니다.
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

## See also

*   [prod/runtime](../prod/runtime/README.md) — 출력 · 실행기 스위치 · 대안 ·
    1단-나 이월
*   [런타임 트랙 인터페이스 계약](../CONTRACT-runtime.md) ·
    [단계 직전 점검](stage-precheck.md)
*   [ADR-0001 stream_segments DDL 소유권](../../../docs/decisions/2026-08-01-stream-segments-ddl-ownership.md)
