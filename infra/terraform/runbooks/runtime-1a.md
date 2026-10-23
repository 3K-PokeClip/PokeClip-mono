# prod 런타임 1단-가 런북

`infra/terraform/prod/runtime` 을 처음 적용하고, media DB 계정 · `stream_*`
스키마 SQL(`prod/runtime/sql/`)을 RDS 에 적용하는 절차입니다. 계획 rt1a 의
「운영 순서와 승인 지점」 이 이 문서입니다.

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
「DB 접속(IAM DB 인증)」). platform(O7d)은 런타임 1단-나 뒤입니다 —
platform 이 읽는 `app_db_users` 는 1단-나가 냅니다(kty 2026-10-23).

## 순서 한눈에

| 걸음 | 하는 일 | 승인 지점 |
|---|---|---|
| 1 | 직전 점검(`runtime-1a` — SCP · 2-3 · 이벤트) — 첫 SCP 판정은 CHANGED | ⑤ 보고 · `--accept` |
| 2 | 본 apply(실행기 끔) | plan 승인 |
| 3 | 운영 확인 — 엔진 실제 부판 기록 | — |
| 4 | 직전 점검 다시 → 실행기 켬 | plan 승인 |
| 5 | SQL 적용(00 → 10 → 20 → 30 → 40) | send-command 승인 |
| 6 | advisory 확인(50 → 40) | send-command 승인 |
| 7 | 직전 점검 다시(막지 않음) → 실행기 끔 · 잔여 0 | plan 승인 |
| 8 | 기록 | — |

4 부터 6 까지 어느 걸음이든 실패하거나 시간이 넘으면 곧바로 7 로 갑니다(아래
「실패 · 시간 초과」).

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
    상태 객체 행은 이 단계부터 묻습니다. 종료 3(UNDECIDED)이면 kty 가
    `approvals.tsv` 에 「진행」 을 적어야 다음으로 갑니다.
*   ① 이 종료 0 이 아니거나(승인 뒤에도 DENIED 등) ② 가 종료 0 도, 「진행」
    이 적힌 종료 3 도 아니면 2 로 가지 않습니다.

## 2. 본 apply

1.  **plan**: 실행기는 끈 채(기본값) plan 을 파일로 남깁니다. 앞에 남은 plan
    파일은 먼저 지웁니다.

    ```shell
    cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
    cp backend.hcl.example backend.hcl
    sed -i.orig 's/<STATE_BUCKET_PROD>/pokeclip-tfstate-prod-apne2/' backend.hcl
    rm backend.hcl.orig
    grep -c '^bucket' backend.hcl              # 1 이어야 함(값은 내지 않음)
    grep -c '<STATE_BUCKET_PROD>' backend.hcl  # 0 이어야 함
    rm -f runtime.tfplan
    terraform init -backend-config=backend.hcl
    terraform plan -out=runtime.tfplan
    ```

1.  **확인 · 승인**: 아래를 모두 확인하고 kty 승인을 받습니다. 하나라도
    다르면 apply 하지 않습니다.

    *   `Plan: 28 to add, 0 to change, 0 to destroy` — RDS 다섯(SG · 서브넷
        그룹 · 파라미터 그룹 · 로그 그룹 · 인스턴스) · 비밀 그릇 일곱 ·
        rewind 버킷 다섯 · 계약9 열하나.
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
*   관리형 마스터 비밀에 default_tags 가 붙는지는 미확인입니다. 아래로
    보고 결과를 기록합니다(붙지 않아도 멈추지 않습니다).

```shell
cd "$(git rev-parse --show-toplevel)/infra/terraform/prod/runtime"
aws secretsmanager describe-secret --region ap-northeast-2 \
  --secret-id "$(terraform output -raw rds_master_user_secret_arn)" \
  --query Tags
```

## 4. 실행기 켬

1.  1 의 점검을 다시 합니다(같은 묶음 · `--since` 는 그때의 비교 기준).
    1 의 통과 조건이 아니면 켜지 않습니다.
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
    `40_verify.sql` 머리). 멤버십(`membership_<계정>` ·
    `members_media_owner`)이나 직접 부여된 CREATE(`schema_create_<계정>`)로
    실패하면, 10 은 그것을 회수하지 않습니다 — 무엇이 왜 붙었는지 모르기
    때문입니다. 운영자가 원인을 확인한 뒤 손으로 회수하고 40 을 다시
    돌립니다.
*   파일 하나가 실패하면 그 파일만 롤백되고 앞 파일은 남습니다. 10 · 20 ·
    30 은 다시 돌려도 같은 상태로 수렴하므로, 원인을 고친 뒤 00 부터 다시
    돌립니다.

## 6. advisory 확인

`50_advisory_probe.sql` 은 마스터가 `pg_advisory_xact_lock(bigint)` 의
EXECUTE 를 PUBLIC 에서 회수할 수 있는지만 보고 ROLLBACK 합니다(K7 — 실제
회수는 런타임 1단-나). 5 와 떼어 한 번만, `-1` 없이 돌리고, 같은 명령 안에서
뒤이어 40 을 다시 돌려 무변경을 확인합니다.

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
갑니다. 이 출력이 1단-나의 재료입니다(8 에서 기록). 1단-나에 넘길 내용:

*   쓰는 쪽이 둘입니다. 사이드카 `media_indexer` 가 컷오프 경로에서
    `pg_advisory_xact_lock(hashtext('pc_cutoff_' || …))` 을 씁니다
    (`media/internal/index/store.go:206` 의 `lockCutoffStreamSQL`). 권한
    정본은 「advisory 잠금은 PUBLIC 기본으로 충분」 을 전제로 GRANT 를 두지
    않습니다(`media/internal/pgtest/role.go:41`). 3번 서버 넷의 Flyway 도
    advisory 잠금으로 직렬화합니다(Flyway 일반 거동 — 3번 코드 미대조).
*   회수한다면 재부여 대상에 `media_indexer` 를 넣고 `role.go` 전제 주석 ·
    권한 시험을 함께 고칩니다. 재부여를 잊으면 40 의
    `advisory_xact_lock_media_indexer` 단언이 깨집니다.
*   목록 전문은 [prod/runtime](../prod/runtime/README.md) 「1단-나 이월」 의
    1 입니다.

## 7. 실행기 끔

1.  1 의 점검을 다시 합니다. **결과가 CHANGED · DENIED · STOP ·
    UNDECIDED 여도 끔은 진행하고** 결과만 보고합니다 — 끔은 잔여 정리라
    막지 않습니다.
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

*   볼트 raw: 각 걸음의 명령 · 출력 · 판정 — 1 · 4 · 7 의 점검 첫 줄과 증거
    디렉터리 이름, 3 의 `EngineVersion` 실제 값 · 비밀 태그, 5 의 sha256
    일곱 줄 · 머지 SHA · `prepare` · `apply` 결과, 6 의 `probe` 결과, 7 의
    잔여 0. 옮기기 전에 비밀 값 훑기를 합니다(계정 ID · ARN 은 가립니다).
*   Jira POK-304 코멘트: 6 의 출력과 판정 갈래(1단-나 재료).

## 실패 · 시간 초과

send-command 상태가 `Success` 가 아니거나(`Failed` · `TimedOut` ·
`Cancelled` · `DeliveryTimedOut`) 판정이 어긋나면:

1.  같은 흐름에서 다시 보내지 않습니다.
1.  결과 JSON(`${payload}/<걸음>-result.json`)을 보관합니다.
1.  곧바로 7(실행기 끔)로 가서 잔여 0 을 확인합니다.
1.  원인을 고치는 PR 을 머지한 뒤 4(켬)부터 다시 합니다. 5 는 00 부터
    다시 돌려도 수렴합니다.

## 되돌리기 · 해체

*   **SQL**: 파일마다 트랜잭션이라 실패한 파일은 남지 않습니다. 적용을 통째로
    되돌릴 때는 실행기를 켜고 마스터로 아래를 `psql -1` 로 돌립니다 — 표 ·
    함수(media_owner 소유 전부) → 두 계정 → media_owner 순서입니다. 이
    SQL 은 시험하지 않았습니다(쓰기 전에 로컬 PG 비superuser 대역으로
    확인합니다).

    ```sql
    SET ROLE media_owner;
    DROP OWNED BY media_owner;
    RESET ROLE;
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

*   2-3 판정(1 의 ②)은 prod 역할의 권한 변경은 묻지만 실행기 역할의
    `iam:PassRole` 은 묻지 않습니다. 실행기가 켜진 동안 `iam:PassRole` 을
    가진 dev 주체가 이 프로필로 상자를 띄워 마스터 비밀을 읽을 수 있습니다.
    그래서 SQL 직후 끄고, 잊어도 상자는 120분 뒤 스스로 멈춥니다.
*   SQL 실적용 시험(CI)은 Go 메타 명령 처리기로 돌고 psql 자체가 아니며,
    대역은 `rds_superuser` 가 아닙니다. TLS · `rds.force_ssl` 은 CI 가 재지
    못합니다.
*   연결 상한 20 · `statement_timeout` 60s 는 측정값이 아닙니다(O7e 에서
    다시 봅니다).

## See also

*   [prod/runtime](../prod/runtime/README.md) — 출력 · 실행기 스위치 · 대안 ·
    1단-나 이월
*   [런타임 트랙 인터페이스 계약](../CONTRACT-runtime.md) ·
    [단계 직전 점검](stage-precheck.md)
*   [ADR-0001 stream_segments DDL 소유권](../../../docs/decisions/2026-08-01-stream-segments-ddl-ownership.md)
