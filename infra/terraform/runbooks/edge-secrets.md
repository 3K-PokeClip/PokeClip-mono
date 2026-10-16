# 엣지 비밀 런북

prod 엣지 비밀 세 개(서명 키 · CDN 비밀 · 시험용 스트림 키)와 CloudFront
서명 공개키를 처음 넣고 교체하는 절차입니다. 설계서 5-4 와 계획 M4 의 운영
단계 O5 · O7e · O7f 에 해당합니다. Terraform(`prod/secrets`)은 그릇과 공개키만
만들고, 값은 이 런북대로 `scripts/put-secret-from-file.sh` 로 넣습니다. 모든
쓰기는 kty 로컬에서 하고, 단계마다 kty 가 승인합니다.

용어:

*   **그릇**: 값(버전)이 없는 Secrets Manager 비밀입니다. `prod/secrets` 가
    만듭니다.
*   **스크래치 디렉터리**: 비밀 파일을 잠시 두는 저장소 밖 임시
    디렉터리입니다. 입력이 끝나면 지웁니다.
*   **KVS**: CloudFront KeyValueStore 입니다. `/live` 의 CloudFront Function
    이 원본 요청에 붙일 CDN 비밀을 여기서 읽습니다.
*   **SCP**: 운영 측 조직이 이 리스 계정에 건 서비스 제어 정책입니다. 막힌
    동작은 누구도 할 수 없습니다(설계 2-1).
*   **T6b · T9a 등**: 설계 5-6 의 검증 사례 번호입니다.

## 무엇을 언제 넣나

| 비밀 이름 | 값 | 만들고 넣는 때 | 읽는 쪽 |
|---|---|---|---|
| `pokeclip/prod/signing-key` | CloudFront 서명 개인키(RSA 2048 · PKCS#8 PEM) | O5(설계 5-4 의 1 · 3) | clip 실행 역할 |
| `pokeclip/prod/cdn-secret` | CloudFront 가 원본 요청에 붙이는 Bearer | O7e — T9a 관찰 직후(5-4 의 7) | Media 인스턴스 역할 · KVS 값(5-4 의 9 에서 복사) |
| `pokeclip/prod/test-stream-key` | 루프백 시험용 스트림 키 | O7f — prod auth 가 발급한 뒤(5-4 의 11) | Media 인스턴스 역할(루프백 스크립트) |

*   **5단계(O5)에서는 서명 키만** 만들고 넣고 로컬 파일을 지웁니다.
*   **CDN 비밀은 7단계(O7e)에서 T9a 를 관찰한 뒤에** 만들고 넣고, 곧바로
    로컬 파일을 지우고 확인합니다.
*   그래서 단계 사이에 로컬에 보관하는 비밀은 없습니다. 입력이 실패해 단계를
    멈출 때도 지웁니다(아래 「로컬 파일 규칙」).

## 초기화 순서(설계 5-4 의 1–12)

| # | 단계 | 이 런북 | 계획 단계 |
|---|---|---|---|
| 1 | 서명 키 쌍 생성(스크래치에서만) | 「O5」 의 2 | O5 |
| 2 | `prod/secrets` apply — 공개키 · 그릇 세 개 | 「O5」 의 3 | O5 |
| 3 | 서명 키 입력(파일에서 · 빈 값 · 형식 검사) | 「O5」 의 4 | O5 |
| 4 | 로컬 비밀 파일 삭제 · 삭제 확인 | 「O5」 의 5 | O5 |
| 5 | `prod/network` → 런타임 트랙 1단 | 이 런북 밖 | O7a 와 런타임 트랙 |
| 6 | platform apply — 실행 역할 · 읽기 정책 · auth · clip 태스크 생성 | 이 런북 밖 | O7d |
| 7 | media apply → **T9a 미기동 관찰** → (`O7e-stop1` 허용일 때) CDN 비밀 생성 · 입력 · 로컬 삭제 → Media 재시작 → 기동 | 「O7e」 | O7e |
| 8 | edge apply — KVS 그릇 · 함수 · 키 그룹 · VPC origin | 이 런북 밖 | O7f |
| 9 | 단계 직전 SCP 재판정 허용일 때: T6b → KVS 값 입력 | 「O7f」 | O7f(멈춤 자리 ②) |
| 10 | 런타임 2단 → auth · clip · next 정상 기동 확인 | 이 런북 밖 | 런타임 트랙 |
| 11 | 시험용 스트림 키 발급 · 입력(루프백 시험 직전) | 「O7f」 | O7f |
| 12 | 검증(설계 5-6) | 이 런북 밖 | O7f · 8단계 |

## 로컬 파일 규칙

*   비밀 파일은 저장소 밖 스크래치 디렉터리에만 둡니다. `umask 077` 을 먼저
    걸고 `mktemp -d` 로 만듭니다 — 디렉터리 0700 · 파일 0600 이 됩니다.
*   값은 파일로만 다룹니다. 명령문 · 클립보드 · 셸 기록 · 채팅에 두지
    않습니다.
*   입력이 끝나면 곧바로 파일을 지우고, `ls -A` 출력이 0건인지 확인한 뒤
    디렉터리를 지웁니다.
*   입력이 실패하면 종료 코드로 정합니다.
    *   같은 셸에서 바로 고칠 수 있는 실패(종료 2 · 3 — 인자 · 입력 파일 지정
        오류 등, 원인이 분명한 종료 5)는 파일을 그대로 두고 고쳐 다시
        넣습니다.
    *   단계를 멈추면(종료 4 · 그날 못 고침 · 셸을 닫을 때) 셸을 닫기 전에
        파일을 지우고 0건을 확인합니다 — 셸을 닫으면 스크래치 경로
        (`scratch`)를 잃습니다. 재개 때 값을 새로 만듭니다(서명 키는 「O5」 의
        4).
*   공개키는 비밀이 아니지만 저장소에 커밋하지 않습니다. 추적하지 않는
    `prod/secrets/terraform.tfvars` 에 둡니다(아래 「공개키 PEM 넘기기」).

## 비밀 입력 스크립트

```shell
bash infra/terraform/scripts/put-secret-from-file.sh <종류> <파일>
```

*   종류 `signing-key` · `cdn-secret` · `test-stream-key` → 비밀
    `pokeclip/prod/<종류>`. 이름으로 지정합니다(ARN 은 계정 ID 를 품으므로
    쓰지 않습니다). 리전은 `ap-northeast-2` 고정입니다.
*   값은 파일 → 셸 변수 → 표준 입력(`--cli-input-json file:///dev/stdin`)으로만
    지나갑니다. 명령 인자 · 환경 변수 · 임시 파일 · 출력에 값이 남지
    않습니다.
*   aws 를 부르기 전에 검사합니다 — 파일 없음 · 일반 파일 아님 · 읽기 불가 ·
    빈 파일 · 공백만 · NUL. 서명 키는 머리 줄 `BEGIN PRIVATE KEY` · 꼬리 줄
    `END PRIVATE KEY`(PKCS#8)만 받고, PKCS#1(`BEGIN RSA PRIVATE KEY`) ·
    암호화된 PKCS#8 · CRLF · ASCII 밖 문자를 거부합니다. 파일 내용 그대로
    넣습니다. 한 줄 비밀(CDN 비밀 · 시험 키)은 끝 줄바꿈 하나만 떼고, 남은
    줄바꿈 · 공백 · 제어 문자 · ASCII 밖 문자를 거부합니다.
*   같은 비밀에 다시 넣으면 새 버전이 되고, 옛 값은 `AWSPREVIOUS` 로
    남습니다(덮어쓰기를 막지 않습니다 — 교체 경로).

| 종료 코드 | 출력(값은 어느 줄에도 없음) | 할 일 |
|---|---|---|
| 0 | 표준 출력 `SECRET PUT OK <비밀 이름> <VersionId>` | 다음 걸음 |
| 2 | 표준 오류 `사용: …` | 인자를 고쳐 다시 |
| 3 | 표준 오류 `SECRET PUT REJECTED: <이유>` | 파일을 고쳐 다시(aws 는 불리지 않음) |
| 4 | 표준 오류 `SECRET PUT DENIED` | **다시 시도하지 않고 멈춥니다**(아래 「AccessDenied 가 나면」) |
| 5 | 표준 오류 `SECRET PUT FAILED <AWS 오류 코드>`(못 읽으면 `UNKNOWN`) | **멈추고 원인을 확인합니다** — 다시 시도는 원인을 고친 뒤입니다. 진단은 아래 「실패해도 하지 않는 것」 대로 값을 내지 않는 것만 씁니다. aws 원문은 ARN · 계정 ID 를 품어 내보내지 않습니다 |

### 실패해도 하지 않는 것

입력이 실패해도 다음은 하지 않습니다 — 값이 화면 · 셸 기록 · 프로세스 목록에
남습니다.

*   `aws … --debug`(요청 본문이 그대로 찍힙니다).
*   `bash -x` 로 스크립트 다시 돌리기(값이 든 줄이 추적 출력에 찍힙니다).
*   값이 든 인자 — `--secret-string "$(cat …)"` 같은 꼴과
    `--cli-input-json '{…}'` 인라인 JSON.

원인은 값을 내지 않는 진단으로 찾습니다 — 첫 줄은 그릇이 있는지와 버전
단계(값은 나오지 않음)를, 둘째 줄은 어느 주체로 부르는지를 보여 줍니다. 다른
비밀이면 이름만 바꿉니다. 두 출력은 ARN · 계정 ID 를 품으므로 화면에서만
확인하고, 보고 · 채팅에는 옮기지 않습니다.

```shell
aws secretsmanager describe-secret --secret-id pokeclip/prod/signing-key \
  --region ap-northeast-2
aws sts get-caller-identity
```

## O5 — 서명 키(5단계)

번호는 계획 M4 의 O5 와 같습니다. 명령은 저장소 뿌리에서 시작합니다.

0.  **선행 0 — 단계 직전 점검**: 단계 직전 점검 런북
    `runbooks/stage-precheck.md`(M17a 머지 뒤 링크)대로 SCP 재판정(5 묶음) ·
    2-3 자동 주체 판정(실패 0 · 미판정 0 또는 모두 kty 「진행」) · 이벤트
    기록을 봅니다. 태그 키(`tag:GetTagKeys`)를 읽어
    계정에 있는 키와 `default_tags` 네 키(`Project` · `Environment` ·
    `ManagedBy` · `TerraformRoot`)가 겹치지 않는지 확인합니다.

    AWS CLI 명령 기록(`cli_history`)이 켜져 있지 않은지도 봅니다 — 켜져 있으면
    요청 본문(값)이 `~/.aws/cli/history/history.db` 에 평문으로 남습니다. 아래
    블록은 판정 줄만 냅니다. 비어 있거나 `disabled` 면 `CLI HISTORY OK` 이고,
    `CLI HISTORY ENABLED` 면 기록을 끈 뒤 다시 봅니다.

    ```shell
    if [ "$(aws configure get cli_history)" = enabled ]; then
      printf 'CLI HISTORY ENABLED\n'
    else
      printf 'CLI HISTORY OK\n'
    fi
    ```

1.  **prod/bootstrap**: `infra/terraform/prod/bootstrap` 디렉터리에서
    [prod/bootstrap 2단 절차](/infra/terraform/prod/bootstrap/README.md)로 상태
    버킷을 만들고 이관합니다.
1.  **서명 키 쌍 생성**(스크래치에서만):

    ```shell
    umask 077
    scratch="$(mktemp -d)"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
      -out "${scratch}/signing-key.pem"
    openssl pkey -in "${scratch}/signing-key.pem" -pubout \
      -out "${scratch}/public-key.pem"
    head -n 1 "${scratch}/signing-key.pem"
    ```

    마지막 줄은 머리 줄 하나(`BEGIN PRIVATE KEY` 가 든 줄)만 냅니다.

1.  **prod/secrets apply**: 저장소 뿌리로 돌아가 `prod/secrets` 로 옮기고,
    `backend.hcl` 의 `bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를 고정 이름
    `pokeclip-tfstate-prod-apne2` 로 바꿉니다(`grep` 이 그 한 줄을 냄). 공개키를
    추적하지 않는 `terraform.tfvars` 에 heredoc 으로 옮기고(아래 「공개키 PEM
    넘기기」) plan 을 봅니다.

    ```shell
    cd "$(git rev-parse --show-toplevel)"
    cd infra/terraform/prod/secrets
    cp backend.hcl.example backend.hcl
    sed -i.orig 's/<STATE_BUCKET_PROD>/pokeclip-tfstate-prod-apne2/' backend.hcl
    rm backend.hcl.orig
    grep '^bucket' backend.hcl
    {
      printf 'public_key_pem = <<-EOT\n'
      sed 's/^/  /' "${scratch}/public-key.pem"
      printf 'EOT\n'
    } > terraform.tfvars
    terraform init -backend-config=backend.hcl
    terraform plan -out=secrets.tfplan
    terraform apply secrets.tfplan
    rm secrets.tfplan
    ```

    plan 범위 = 그릇 3 · 공개키 1 생성, 그 밖 0. `tags_all` 에 네 키
    (`TerraformRoot = prod/secrets`)가 있고 `aws_secretsmanager_secret_version`
    은 없어야 합니다.
1.  **서명 키 입력**:

    ```shell
    bash ../../scripts/put-secret-from-file.sh signing-key \
      "${scratch}/signing-key.pem"
    ```

    `SECRET PUT OK pokeclip/prod/signing-key <VersionId>` 한 줄이 나와야
    합니다. 아니면 종료 코드로 정합니다(위 「로컬 파일 규칙」).

    *   같은 셸에서 바로 고칠 수 있는 실패(종료 2 · 3 — 인자 · 입력 파일 지정
        오류 등, 원인이 분명한 종료 5)는 키 쌍을 그대로 두고 고쳐 다시
        넣습니다.
    *   단계를 멈추면(종료 4 · 그날 못 고침 · 셸을 닫을 때) 셸을 닫기 전에
        5 대로 키 쌍을 지우고 0건을 확인한 뒤 멈춥니다. 종료 4 는 아래
        「AccessDenied 가 나면」 도 따릅니다.
    *   재개 때는 2 부터 키 쌍을 새로 만들고, 공개키가 바뀌는 `prod/secrets`
        apply(교체)를 합니다. 키 그룹이 공개키를 쓰기 전 — O7f edge apply
        앞까지만 됩니다(아래 「공개키 PEM 넘기기」).
1.  **로컬 파일 삭제 · 확인**: `ls -A` 는 아무것도 내지 않아야 합니다(0건).

    ```shell
    rm "${scratch}/signing-key.pem" "${scratch}/public-key.pem"
    ls -A "${scratch}"
    rmdir "${scratch}" && unset scratch
    ```

1.  **상태 확인**: `terraform state list` 에 version 자원이 0건인지 봅니다
    (`terraform state list | grep -c secret_version` → `0`).
1.  **보고**: 설계 13-1 덧붙임 줄과 2-3 판정 요약(가린 것 — 계정 ID · ARN ·
    사람 IAM 사용자 이름 0건)을 kty 에게 냅니다.

### 공개키 PEM 넘기기

공개키는 `public_key_pem` 변수로 받습니다. O5 의 3 은 추적하지 않는
`terraform.tfvars` 에 heredoc 으로 둡니다(`terraform.tfvars.example` 꼴).

*   셸에서만 넘기려면 `export TF_VAR_public_key_pem="$(cat <파일>)"` 도
    됩니다. 명령 치환이 끝 줄바꿈을 떼지만, 모듈이 끝 줄바꿈을 늘 하나로
    맞추므로(`trimspace` + 줄바꿈 하나) 어느 쪽이든 같은 `encoded_key` 가
    됩니다.
*   **첫 apply 뒤의 plan** 도 같은 PEM 이 필요합니다. `terraform.tfvars` 를
    남겨 두면 그대로 읽힙니다. 잃어버렸으면 CloudFront 에서 다시 받습니다
    (읽기).

    ```shell
    aws cloudfront get-public-key-config \
      --id "$(terraform output -raw public_key_id)" \
      --query PublicKeyConfig.EncodedKey --output text > public-key.pem
    ```

    받은 파일로 `terraform.tfvars` 를 다시 만들고 plan 이 「No changes」인지
    봅니다. 키 그룹이 생긴 뒤(O7f edge apply 뒤)에는 공개키가 바뀌는 plan 을
    apply 하지 않습니다 — CloudFront 공개키는 comment 밖의 값을 바꿀 수
    없어(UpdatePublicKey) 교체(삭제 뒤 생성)가 되고, 키 그룹이 쓰는 키는
    지워지지 않습니다. 키 그룹 전에는 교체가 됩니다(「O5」 의 4 — 재개 때 새
    키 쌍).
    `public-key.pem` 은 tfvars 로 옮긴 뒤 지웁니다 — 루트 `.gitignore` 의
    `*.pem` 이 무시해 추적되지는 않지만, 파일은 남기지 않습니다.

## O7e — CDN 비밀(7단계)

번호는 계획 M9 의 O7e 와 같습니다. 명령은 저장소 뿌리에서 돌립니다.

0.  **선행 0 — 단계 직전 점검**: 단계 직전 점검 런북
    `runbooks/stage-precheck.md`(M17a 머지 뒤 링크)대로 공통 묶음 `O7e` 와
    멈춤 자리 묶음 `O7e-stop1`(사이드카 DB 연결 `rds-db:connect`)을 따로
    판정하고, 2-3 판정 · 이벤트 기록을 봅니다.
    *   공통 묶음이 거부 · 변경이면 O7e 를 시작하지 않습니다(전체 중단).
    *   **`O7e-stop1` 이 거부면** 계획 3절 「부분 진행」 표 ① 을 따릅니다.
        1 · 2(media apply · T9a 관찰)만 하고 `systemctl stop pokeclip-media`
        (아래 「Media 재시작」 — 실행 직전 승인)로 멈춥니다. **CDN 비밀은
        만들지 않습니다**(3 이후 안 함). 승인 지점 A1 에서 kty 가 갈래를
        고릅니다. 재개 조건은 계획 3절 「부분 진행」 표 ① 과 M10 뒤 「O7f
        실행표」 입니다.
1.  **media apply**(이 런북 밖).
1.  **T9a 관찰**: Media 가 미기동인지 봅니다. CDN 비밀 값이 아직 없으므로
    시작 스크립트가 다음 고정 문자열로 멈춰야 합니다(문자열 정본 — M2 머지 뒤
    링크).

    ```text
    MEDIA START BLOCKED: CDN secret unavailable
    ```

1.  **CDN 비밀 생성 · 입력 · 로컬 삭제**(`O7e-stop1` 허용일 때만): `ls -A` 는
    아무것도 내지 않아야 합니다(0건).

    ```shell
    umask 077
    scratch="$(mktemp -d)"
    openssl rand -hex 32 > "${scratch}/cdn-secret"
    bash infra/terraform/scripts/put-secret-from-file.sh cdn-secret \
      "${scratch}/cdn-secret"
    rm "${scratch}/cdn-secret"
    ls -A "${scratch}"
    rmdir "${scratch}" && unset scratch
    ```

    `SECRET PUT OK pokeclip/prod/cdn-secret <VersionId>` 한 줄이 나와야
    합니다. 아니면(종료 4 · 5) 정리 · 0건 확인까지만 하고 재시작으로 가지
    않습니다(종료 코드 표 · 아래 「AccessDenied 가 나면」).
1.  **Media 재시작**: 아래 「Media 재시작」 대로 합니다(실행 직전 승인).
1.  **기동 확인**: 계획 M9 의 O7e 5 대로 판정 줄만 봅니다.

## O7f — KVS 값 · 시험 키

*   **KVS 값 입력**(설계 5-4 의 9 · 멈춤 자리 ②): 단계 직전 재판정
    (`O7f-stop2`)이 허용일 때만 합니다. 먼저 T6b(KVS 키 없음 → `/live` 503)를
    봅니다. 값은 `kvs-put-cdn-secret.sh`(M10 c9 — M10 머지 뒤 링크)가 Secrets
    Manager 의 CDN 비밀을 읽어 넣습니다 — 로컬 파일이 다시 생기지 않습니다.
*   **시험용 스트림 키**(5-4 의 11 — 루프백 시험 직전): 부분 진행 중(A1 ·
    A2)이면 시작 직전에 `testkey` 묶음을 다시 판정하고, 허용이 아니면(거부 ·
    변경 · 검사 불가 종료 2) 시작하지 않고 kty 에게 보고합니다. prod auth 에
    시험 계정을 만들어 받은 키를 아래 「시험용 스트림 키 넣기」 대로 파일로
    옮겨 `test-stream-key` 로 넣고, O7e 와 같이 지우고 0건을 확인합니다.

### 시험용 스트림 키 넣기

받은 키는 명령문에 쓰지 않고 셸 내장 `read` · `printf` 로만 파일에 옮깁니다 —
화면 · 셸 기록 · 프로세스 목록에 값이 남지 않습니다.

1.  스크래치를 만듭니다.

    ```shell
    umask 077
    scratch="$(mktemp -d)"
    ```

1.  키를 받습니다. 이 한 줄은 **따로** 실행합니다(블록으로 붙이면 다음 줄이
    키로 읽힐 수 있음). 기다리면 키를 붙여 넣고 Enter 를 누릅니다 — 화면에
    나오지 않습니다.

    ```shell
    IFS= read -rs test_key
    ```

1.  파일로 옮겨 넣고 지웁니다. 붙여 넣는 데 쓴 클립보드도 비웁니다
    (`pbcopy < /dev/null`). `ls -A` 는 아무것도 내지 않아야 합니다(0건).

    ```shell
    printf '%s' "${test_key}" > "${scratch}/test-stream-key"
    unset test_key
    pbcopy < /dev/null
    bash infra/terraform/scripts/put-secret-from-file.sh test-stream-key \
      "${scratch}/test-stream-key"
    rm "${scratch}/test-stream-key"
    ls -A "${scratch}"
    rmdir "${scratch}" && unset scratch
    ```

    `SECRET PUT OK pokeclip/prod/test-stream-key <VersionId>` 한 줄이 나와야
    합니다. 아니면(종료 4 · 5) 정리 · 0건 확인까지만 하고 다음 단계(루프백
    헬스)로 가지 않습니다.

### T6b 대체 절차(서명이 먼저일 때)

쿠키 없는 요청이 403 이면 함수보다 서명 검증이 먼저라, KVS 키가 없는 상태의
503 을 엣지만으로 볼 수 없습니다. 그때는 런타임 뒤에 다음 운영 단계로
합니다. **걸음마다 kty 승인**을 받습니다. KVS 값 동작이라 시작 직전에
`O7f-stop2` 를 다시 판정하고, 허용이 아니면(거부 · 변경 · 검사 불가 종료 2)
시작하지 않고 kty 에게 보고합니다.

1.  승인된 KVS 키를 지웁니다(현재 ETag 로).
1.  유효한 서명 쿠키로 `/live` 가 503 이고 함수 로그에 고정 문자열이
    나오는지 봅니다.
1.  `kvs-put-cdn-secret.sh`(M10 c9 — 이름 풀이 사전 확인 포함, M10 머지 뒤
    링크)로 값을 되돌립니다.
1.  `/live` 가 200 인지 봅니다.

## 기동 차단 표

값이 없거나 읽지 못하면 소비자가 기동하지 않습니다(설계 5-4 표 + Media 의
두 실패 구분).

| 소비자 | 차단 동작 | 복구 |
|---|---|---|
| clip(ECS) | 비밀 버전 없음 · 읽기 실패면 태스크 미시작(`ResourceInitializationError` · 버전 · 값 없음의 거동은 미확인 — 별도 확인 실험 예정). 서명 세 설정 중 일부만 차면 기동 거부 | 값 입력 → `update-service --force-new-deployment`(ECS 는 기동 때만 읽음) |
| Media — 선행 점검 | 시작 스크립트의 선행 점검(CDN 비밀 밖)이 실패하면 `MEDIA PREREQ FAIL` 로 멈춤(M2 머지 뒤 링크) | 원인을 고친 뒤 Media 재시작 |
| Media — CDN 비밀 | CDN 비밀 조회 실패 · 빈 값이면 MediaMTX 를 띄우지 않고 `MEDIA START BLOCKED: CDN secret unavailable` 로 실패 종료(서비스 관리자 백오프 재시도 — M2 머지 뒤 링크). 시험 키는 기동 조건이 아님 | 값 입력 → Media 재시작 |
| 루프백 스크립트 | 시험 키 조회 실패 · 빈 값이면 시험 실패로 끝냄(서비스 영향 없음) | 5-4 의 11 수행 뒤 재실행 |
| CloudFront Function(`/live`) | KVS 조회 실패 · 빈 값이면 503(fail-closed). 영향 = `/live` 만 | 값 입력 → 전파 뒤 자동 복구 |

## Media 재시작

*   재시작 · 정지는 `systemctl restart pokeclip-media` ·
    `systemctl stop pokeclip-media`(compose 전체)만 씁니다. SSM 으로
    실행하고, **실행 직전에 kty 승인**을 받습니다.
*   MediaMTX 단독 재시작과 `docker compose restart <서비스>` 단독은
    금지합니다 — 시작 스크립트가 비밀을 읽는 경로를 건너뜁니다.

## SSM 출력 규칙

*   SSM 에서 `docker inspect` · `docker exec env` · `/run/pokeclip-media` 내용
    출력을 하지 않습니다 — 비밀이 환경 · 파일에 들어 있습니다.
*   SSM 명령 출력은 판정 줄(`LOOPBACK …` · `PATHS …` · `SECRET COUNT …`)만
    남깁니다(Media 상자 런북 — M2 머지 뒤 링크).

## 교체

*   방송이 없는 시간대에 새 값 → Secrets Manager → KVS → Media 재시작 순으로
    합니다. MediaMTX 의 복수 비밀 지원은 **미확인**이라 무중단 교체는 하지
    않습니다.
*   새 값은 처음 넣을 때와 같은 스크립트로 넣습니다. 새 버전이
    `AWSCURRENT` 가 되고 옛 값은 `AWSPREVIOUS` 로 남습니다.
*   서명 키의 공개키 id(`public_key_id`)는 소비자가 둘입니다 — edge 의 키
    그룹과 platform 의 clip `CLOUDFRONT_KEY_PAIR_ID`(설계 4-3 · 계획 M8).
    교체 순서는 다음과 같습니다. 지금 `prod/secrets` 는 공개키가 하나라, 새
    공개키를 더하는 것은 별도 변경(PR)입니다.
    1.  새 공개키를 더합니다(`prod/secrets`).
    1.  키 그룹에 새 공개키를 더합니다(edge).
    1.  platform 의 key id 를 새 `public_key_id` 로 바꾸고, 새 개인키를
        `signing-key` 로 넣습니다.
    1.  clip 을 `update-service --force-new-deployment` 로 다시 띄웁니다(ECS
        는 기동 때만 읽음).
    1.  옛 쿠키가 만료되기를 기다립니다.
    1.  옛 키를 뺍니다.
*   키 그룹이 생긴 뒤에는 공개키 PEM 만 바꾸는 apply 를 하지 않습니다(위
    「공개키 PEM 넘기기」).

## 접근 통제

*   KVS 값을 쓰고 읽는 운영 작업(입력 · 교체 · 확인)은 kty IAM 사용자만
    합니다. 함수는 연결로 KVS 를 읽으므로 IAM 이 필요 없습니다.
*   Secrets Manager 읽기: 서명 키 = clip 실행 역할, CDN 비밀 · 시험 키 =
    Media 인스턴스 역할, 그리고 kty. 그릇에 리소스 정책은 두지 않습니다 —
    모듈 안은 guard R6 가 막고, 다른 루트가 비밀에 정책을 붙이는 것은 리뷰가
    막습니다. 읽기 권한은 읽는 쪽 루트가 역할 정책으로 붙입니다.
*   한 계정 안이라 관리자 셋은 IAM 으로 Secrets Manager · KVS 에 모두
    닿습니다. 사람 쪽 경계는 관례입니다(설계 5-4 · 2-3).
*   읽기 전용 사용자(멘토용 — ReadOnlyAccess)는 KVS 값(CDN 비밀)과 prod 상태
    버킷을 읽을 수 있고, Secrets Manager 값(서명 키 · 시험 키)은 못 읽습니다.
    kty 판단은 「조치 없음」입니다(39차).

## AccessDenied 가 나면

*   값 입력이 `SECRET PUT DENIED`(종료 4)로 끝나면 계획 3절 「SCP 멈춤
    규칙」을 따릅니다. 다시 시도하지 않고, 거부된 동작 · 비밀 이름 · 시각을
    kty 에게 보고합니다.
*   KVS 값 입력은 멈춤 자리 ②입니다(M10 의 O7f 3). 거부면 계획 3절 「부분
    진행」 표를 따릅니다.
*   apply 가 거부로 실패하면 원인이 무엇이든 SCP 멈춤 규칙(전체 중단 ·
    상태 대조)입니다.

## prod 에서 하지 않는 시험(T6a · T9b)

T6a(비밀 불일치 → 원본 302/401)와 T9b(clip 빈 서명 키 기동)는 prod 에서
하지 않습니다(kty 20차 · 설계 5-6). 근거:

*   T6a: M2 c6 로컬 compose 실측 — 틀린 Bearer → 302/401(M2 머지 뒤 링크).
*   T9b: 3번 clip 코드 시험(I3-10 — 3번 확인 뒤 링크).
*   T9b: AWS 문서
    [Troubleshooting Amazon ECS ResourceInitializationError errors](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/resource-initialization-error.html)
    (취득 2026-10-17)의 「The task can't pull the secret from Secrets
    Manager … can't retrieve the secret with ARN」 절 — 원인 셋(Secrets
    Manager 와의 네트워크 · 실행 역할 권한 · 비밀 ARN 없음)에서 태스크가
    시작되지 않습니다. **버전(값)이 없는 비밀도 같은 오류인지는 원문에
    없습니다 — 미확인, 별도 확인 실험(AWS 쓰기 · kty 승인 · M4 밖 — 일정은
    kty 결정).**

## 확인하지 못한 것

*   AWS CLI v2 가 파이프로 넘긴 `file:///dev/stdin` 을 실제로 읽는지 — O5
    서명 키 입력에서 실증합니다. 시험은 가짜 aws 로만 했습니다.
*   CloudFront 가 `encoded_key` 의 끝 줄바꿈을 어떻게 저장 · 반환하는지 —
    O5 첫 apply 뒤 plan 「No changes」로 확인합니다.
*   `default_tags` 가 `tags_all` 에 들어가는지 — mock 시험이 보지 못해 O5
    plan 에서 확인합니다.
*   버전 없는 비밀에서 ECS 가 같은 `ResourceInitializationError` 를 내는지 —
    별도 확인 실험(AWS 쓰기 · kty 승인 · M4 밖 — 일정은 kty 결정).
*   MediaMTX 의 복수 비밀 지원(무중단 교체 가능 여부).

## 근거

*   설계서 5-4(초기화 · 기동 차단 · 교체 · 접근 통제) · 5-6(T6a · T6b ·
    T9a · T9b) · 3-2(`prod/secrets` 출력 계약). 계획 M4(c5 런북 내용 ·
    O5) · 3절(SCP 멈춤 규칙 · 부분 진행 · 한 계정 규약).
*   AWS 문서(취득 2026-10-17):
    *   [ECS ResourceInitializationError](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/resource-initialization-error.html)
        — 위 T9b 근거.
    *   [CloudFront UpdatePublicKey](https://docs.aws.amazon.com/cloudfront/latest/APIReference/API_UpdatePublicKey.html)
        — 「the only value you can change is the comment」.
    *   [Secrets Manager PutSecretValue](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_PutSecretValue.html)
        — AWSCURRENT 가 옮겨 가면 옛 버전에 AWSPREVIOUS 가 붙음.
