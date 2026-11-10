# prod/workers

일꾼 render · upload 를 prod ECS 클러스터 `pokeclip-prod` 에 서비스 하나씩
올리는 루트입니다(POK-408). clip 이 큐에 넣는 렌더 · 썸네일 · 업로드 주문을
이 두 서비스가 받습니다. 자원은
[modules/ecs_service](/infra/terraform/modules/ecs_service/main.tf) 가 만들고,
이 루트는 앞 루트의 출력을 그 모듈에 잇고 계약을 검사합니다.

*   **서비스:** `pokeclip-prod-render`(2 vCPU · 4GiB · 임시 디스크 40GiB) ·
    `pokeclip-prod-upload`(0.5 vCPU · 1GiB · 기본 디스크). 둘 다 1개 ·
    자동 확장 없음 · arm64(결정 146). 크기는 kty 확정(2026-11-04)이고 출시 뒤
    실측으로 조정합니다(`workers`).
*   **포트 없음:** 일꾼은 웹 서버가 없어 포트 · 대상 그룹 · 일꾼 SG 인그레스가
    없습니다. render 는 한 프로세스에서 렌더 줄과 사진 줄을 같이 돌립니다.
*   **주소:** `CLIP_BASE_URL` · upload 의 `AUTH_BASE_URL` 은 Cloud Map 이름
    `http://<clip|auth>.pokeclip.internal:<platform service_ports>` 입니다.
*   **이그레스:** render 는 AWS API 를 network 의 VPC 인터페이스 엔드포인트
    다섯(`ecr.api` · `ecr.dkr` · `logs` · `secretsmanager` · `sqs`)의 SG 443
    으로만 부르고, 그 밖에는 S3 게이트웨이 · clip 뿐입니다 — 사용자 영상을 다루는
    ffmpeg 가 인터넷의 아무 호스트로나 나가지 못하게 합니다. upload 는 YouTube
    때문에 NAT(`0.0.0.0/0:443`)를 그대로 씁니다.
*   **SG 짝 규칙:** 일꾼 이그레스와 clip 인그레스 ← render · upload, auth
    인그레스 ← upload 를 이 루트의 apply 가 만듭니다. Media ↔ auth 짝을 media
    루트가 갖는 것과 같은 꼴입니다(kty 결정 2026-10-25 (나)).
*   **역할:** 경로 `/pokeclip/prod/`. 실행 역할은 `INTERNAL_API_TOKEN` 그릇
    하나만 읽고, 작업 역할 문장은
    [CONTRACT-runtime.md](/infra/terraform/CONTRACT-runtime.md) 「일꾼 작업
    역할 대상」 표 그대로입니다.
*   **출력 없음:** 이 루트를 읽는 루트가 없습니다. 토큰 회전 런북은 서비스
    이름 규약(`pokeclip-prod-<키>`)으로 찾습니다.

이 루트에 **없는** 것은 다음과 같습니다.

| 자원 | 루트 |
|---|---|
| 클러스터 · Cloud Map 네임스페이스 · clip · auth 서비스와 SG | platform |
| 일 큐 · DLQ · clips 창고 · rewind 창고 · 토큰 그릇 | 런타임 1단 |
| 이미지 저장소 | registry |

## 앞 루트 출력

`terraform_remote_state` 로 읽습니다(키는 `state_keys` 기본값).

| 루트 | 출력 |
|---|---|
| network | `vpc_id` · `private_subnet_ids` · `s3_prefix_list_id` · `interface_endpoint_sg_id` · `interface_endpoint_services` · `interface_endpoint_private_dns` |
| platform | `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids` 의 `clip` · `auth` · `service_ports` 의 `clip` · `auth` |
| registry | `repo_urls` 의 `render` · `upload` |
| 런타임 1단 | `app_env` · `app_task_resource_arns` 의 `render` · `upload`, `app_secret_arns` 의 `render` · `upload` · `clip`(clip 은 대조에만) |

## 계약 검사

`terraform_data.runtime_contract` 의 precondition 이 앞 루트 출력을 env ·
정책 · SG 규칙에 쓰기 전에 봅니다. 하나라도 어긋나면 plan 이 멈추고, 모듈 ·
규칙은 이 검사를 기다립니다.

1.  런타임 `app_env` 의 이름이 계약과 **같다** — render 는
    `RENDER_QUEUE_URL` · `THUMBNAIL_QUEUE_URL` · `RENDER_VISIBILITY_TIMEOUT` ·
    `THUMBNAIL_VISIBILITY_TIMEOUT`, upload 는 `UPLOAD_QUEUE_URL` ·
    `UPLOAD_VISIBILITY_TIMEOUT`. 허용 목록 비교라 모르는 이름(비밀 포함)이
    평문 env 로 실리지 않습니다. 이름을 더하려면 이 루트와 한 PR 로 계약을
    고칩니다.
1.  숨김 시간이 초 꼴(`600s`)이고, 큐 URL 의 큐 이름이 같은 큐의 작업 역할
    대상 ARN 끝 조각과 같다.
1.  일꾼 비밀이 `INTERNAL_API_TOKEN` 하나이고, 그 ARN 이 **clip 의
    `INTERNAL_API_TOKEN` 과 같다.** 사진 보고가 401 을 받으면 일꾼이 메시지를
    그 자리에서 지워(DLQ 로 가지 않음) 카드 · 클립의 마지막 사본이 사라집니다.
1.  작업 역할 대상 키가 계약과 같고, 값이 와일드카드 없는 S3 버킷 ARN · 표준
    SQS 큐 ARN(`.fifo` 아님)이다.
1.  platform `service_sg_ids` 의 `clip` · `auth` 가 `sg-…` 다.
1.  사설 DNS 네임스페이스 `pokeclip.internal` 의 id 가 platform
    `discovery_namespace_id` 와 같다. platform 이 도메인 이름을 출력하지 않아
    주소의 도메인을 상수로 두고, 조회로 실물과 묶습니다.
1.  network `interface_endpoint_sg_id` 가 SG id(`sg-<16진수>`)다.
1.  network `interface_endpoint_services` 가 다섯을 모두 갖고
    `interface_endpoint_private_dns` 가 `true` 다. 하나라도 빠지거나 DNS 가
    꺼지면 render 가 기동 · 수신하지 못하는데 plan 은 조용히 통과하므로, 그
    전에 멈춥니다.

7 · 8 은 `render_egress_via_endpoints = true`(기본)일 때만 봅니다. network
출력은 `try()` 로 읽어 키가 없으면(엔드포인트 앞의 network 상태) 속성 오류가
아니라 이 검사의 문구로 멈춥니다. 검사가 멈추면 upload 를 포함한 이 루트
전체가 plan 불가입니다 — DNS 를 끈 동안 이 루트를 바꿔야 하면 아래
「render 이그레스 되돌리기」 대로 `false` 로 둡니다.

같은 그릇이어도 값을 회전한 직후면 clip 태스크와 일꾼의 값이 다를 수
있습니다. 그 창은 plan 이 볼 수 없어 아래 「apply 직전 읽기」 가 막습니다.

## Cloud Map 이름

모듈은 서비스마다 Cloud Map 이름을 등록하므로 `render.pokeclip.internal` ·
`upload.pokeclip.internal` 이 생깁니다. **부르지 않는 이름입니다** — 일꾼은
듣는 포트가 없고 SG 인그레스도 0개입니다. 서비스 등록은 platform 다섯
서비스와 같은 모듈 꼴을 지키려고 남겨 둡니다.

## 값과 적용

`image_digests` 는 기본값이 없습니다. `terraform.tfvars.example` 을
`terraform.tfvars`(추적 안 함)로 복사해 채웁니다. 백엔드는
`backend.hcl.example` 을 `backend.hcl` 로 복사하고 `<STATE_BUCKET_PROD>` 를
`pokeclip-tfstate-prod-apne2` 로 고친 뒤 `init -backend-config=backend.hcl`
로 엽니다. 상태 키는 `prod/workers/terraform.tfstate` 입니다.

plan · apply · 단계 직전 점검은 kty 로컬 터미널에서 합니다(출력에 계정 ID 가
든 ARN 이 찍힘). 세션에 옮기는 줄은 12자리 숫자열을 가립니다. 순서는 이렇습니다.

1.  **network 엔드포인트 먼저:** network 의 인터페이스 엔드포인트가 apply 되고
    [prod/network](/infra/terraform/prod/network/README.md) 의 확인을 통과한
    뒤에만 이 루트를 plan 합니다. 그 전에는 계약 검사 7 이 멈춥니다(의도).
1.  **이미지 동일성:** clip 이 실제로 도는 이미지와 같은 커밋인지 다이제스트로
    확인합니다. `workers/` 가 그 커밋 뒤에 바뀌었으면 env 이름 · 로그 키 ·
    작업 역할 근거를 그 판으로 대조합니다.
1.  **빌드:** 깨끗한 작업 트리에서 `docker buildx build --platform linux/arm64
    --provenance=false --sbom=false --load` 로 두 이미지를 만들고, 빈 env 면
    idle · 토큰이 비면 부팅 거부인지와 `java -version` · `ffmpeg -version` 을
    기록합니다.
1.  **푸시(kty 승인):** 태그 = 커밋 짧은 SHA ·
    [prod 산출물 런북](/infra/terraform/runbooks/prod-artifacts.md) 대로.
    다이제스트를 `terraform.tfvars` 에 넣습니다. 태그가 이미 있어 재푸시가
    거부되면(태그 불변 — 정상) 그 태그가 이번 빌드인지 봅니다. ECR 의 그
    다이제스트를 `docker pull --platform linux/arm64 <저장소>@<다이제스트>` 로
    받아 로컬 빌드와 `docker image inspect --format '{{json .RootFS.Layers}}'`
    가 같은지 대조하고, 다르면 멈추고 kty 에게 넘깁니다(다른 빌드가 같은
    태그를 먼저 차지함).
1.  **PR · CI · 리뷰.**
1.  **관문 ① — platform 무변경:** develop 체크아웃과 PR 머리 체크아웃에서
    `terraform -chdir=infra/terraform/prod/platform plan -detailed-exitcode
    -input=false` 가 둘 다 종료 0 이어야 합니다. 두 체크아웃의 platform
    `terraform.tfvars` sha256 을 먼저 맞춥니다. 두 plan 은 같은 상태 잠금을
    쓰므로 develop 쪽을 끝낸 뒤 PR 머리 쪽을 돌립니다(동시에 돌리면 한쪽이
    잠금에 걸림). develop 쪽이 0 이 아니면 무관한 드리프트라 멈추고 kty 에게
    넘깁니다.
1.  **런타임 출력 · 첫 plan:** `terraform -chdir=infra/terraform/prod/runtime
    output -json app_env | jq 'keys'` 에 `render` · `upload` 가 있는지 보고,
    이 루트를 `plan -out` 해 아래 「plan 요지」 를 kty 가 봅니다. 이 루트의
    `terraform.tfvars` · `backend.hcl` sha256 을 기록합니다.
1.  **머지(kty 승인).**
1.  **단계 직전 점검:** 머지된 develop 체크아웃에서
    [단계 직전 점검](/infra/terraform/runbooks/stage-precheck.md) 의
    `workers` 단계를 돌립니다.
1.  **apply 직전 읽기**(아래 절).
1.  **plan 과 승인 한 묶음:** 머지 체크아웃의 `terraform.tfvars` ·
    `backend.hcl` sha256 이 첫 plan 때 기록과 같은지 먼저 대조합니다(다르면
    멈춤). 그 체크아웃에서 다시 `plan -out` 하고 요지가 앞의 것과 같은지
    봅니다. kty 가 apply(조건 = 요지 줄 수 · 주소 집합 ·
    plan 파일 sha256)와 긴급 정지 명령 둘의 조건부 실행을 한 번에 승인합니다
    (범위는 아래 「긴급 정지와 상태 어긋남」). 승인 전에 이 plan 파일로
    「apply 직전 읽기」 의 이그레스 스위치를 봅니다.
1.  **감시를 먼저 띄웁니다**(아래 「첫 소비 감시」). 띄웠다는 것을 알린 뒤
    kty 가 `terraform apply <plan 파일>` 을 돌립니다. 수신 감시 반복만은
    apply 를 시작한 뒤 띄우고, 기동 신호(`worker_started`)를 기다렸다가
    돕니다(아래 「수신 · 진행 판정」 의 쓰는 예).
1.  **첫 소비 판정(3분)** · 정지 신호면 긴급 정지.
1.  **기동 신호 · 관문 ②:** 서비스마다 실행 수 1 · 배포 하나 COMPLETED ·
    `*.worker_started`(render · thumbnail · upload)를 보고, 이 루트와 platform
    을 다시 plan 해 둘 다 종료 0 인지 봅니다. 이 루트가 0 이 아니면 ECS 가
    태스크 정의를 다르게 정규화한 것이고, platform 이 0 이 아니면 이 루트가 단
    인그레스가 platform 에 드리프트로 보이는 것입니다 — 둘 다 kty 에게 넘깁니다.
    `worker_started` 만으로는 기동을 판정하지 않습니다 — 아래 「수신 · 진행
    판정」 의 **로그 판정**(기동 뒤 3분 — 잠정 통과면 다음 걸음으로)과 **지표
    판정**(기동 뒤 20분 안)을 줄(render · thumbnail · upload)마다 함께 봅니다.
1.  **썸네일 대기분 소진 · 시험 잡:** 사진 DLQ 깊이가 「apply 직전 읽기」 ①
    에서 적은 기준 그대로 · `thumbnail.report_rejected kind=card` ·
    `kind=clip` 0 을 세고, 렌더 짧은 컷 하나 · 업로드 비공개 하나(채널은
    시험 직전 kty 가 정함)로 끝까지 확인합니다.

provider 의 `default_tags`(`TerraformRoot = prod/workers`)는 태그를 받는
자원에 붙습니다. mock 시험은 태그를 보지 못하므로 plan 의 `tags_all` 로
확인합니다.

### plan 요지

첫 apply 의 요지는 **create 29줄**이고 그 밖의 동작은 없습니다.

| 주소 | 수 |
|---|---|
| `module.render` — 서비스 · 태스크 정의 · Cloud Map 서비스 · SG · 이그레스 셋(엔드포인트 SG · S3 · clip) · 실행 · 작업 역할과 정책 넷 · 로그 그룹 | 12 |
| `module.upload` — 같은 꼴 · 이그레스 넷 | 13 |
| `aws_vpc_security_group_ingress_rule.clip_from_worker` 둘 · `auth_from_upload` | 3 |
| `terraform_data.runtime_contract` | 1 |

요지는 [prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md) 의
`plan_summary` 와 같은 jq 로 뽑습니다. 이 루트 밖의 주소가 한 줄이라도
나오면 멈춥니다. 런타임 상태가 일꾼 키를 아직 내지 않으면 계약 검사가
멈추는데, 그것은 의도된 멈춤입니다.

### apply 직전 읽기

다섯 다 읽기라 승인이 필요 없습니다. 하나라도 어긋나면 멈추고 kty 에게
넘깁니다.

1.  사진 · 렌더 · 업로드 큐와 DLQ 셋의 `ApproximateNumberOfMessages` ·
    `ApproximateNumberOfMessagesNotVisible`(큐 속성)과 가장 오래된 메시지
    나이(CloudWatch `AWS/SQS` 지표 `ApproximateAgeOfOldestMessage`)를
    기록합니다. 이 DLQ 셋의 깊이가 아래 정지 조건의 **기준**입니다. 셋 중
    하나라도 0 이 아니면 apply 하지 않고 멈추고 kty 에게 넘깁니다 — 기준이
    0 이 아니면 그 안에 무엇이 있는지 먼저 정해야 하고, kty 가 그 수를
    기준으로 진행을 정할 때만 계속합니다.
1.  렌더 · 업로드 큐가 0 인지 봅니다. 아니면 누구의 주문인지 보고, 실사용자
    주문이 있으면 멈춥니다 — 기동하자마자 처리돼 사용자 채널에 영상이 올라갑니다.
    주문자를 확인할 길이 없으면(읽기 경로 없음) 확인 불가로 보고 멈춥니다.
1.  **회전 창:** `aws secretsmanager describe-secret --secret-id
    pokeclip/prod/internal-api-token --query LastChangedDate` 가 clip 과
    auth 의 지금 태스크 `startedAt` 모두보다 앞이어야 합니다. 뒤면 그
    서비스가 회전 전 값을 들고 있어 일꾼의 보고(clip) · upload 의 연동
    조회(auth resolve — 같은 토큰)가 401 입니다.
1.  관문 ① 을 머지 체크아웃에서 다시 돌려 종료 0 인지 봅니다.
1.  **이그레스 스위치:** apply 할 저장 plan 에서 봅니다 — 이 읽기만 다음
    걸음 「plan 과 승인 한 묶음」 의 `plan -out` 뒤, 승인 전에 합니다.
    `terraform.tfvars` 줄만 보면 `TF_VAR_render_egress_via_endpoints` ·
    `*.auto.tfvars` · `-var` 로 들어온 `false` 를 놓치므로, plan 이 실제로 쓴
    값과 그 값이 만든 render 이그레스를 봅니다. `plan` 은 그 plan 파일의
    경로입니다 — `-chdir` 디렉터리(`infra/terraform/prod/workers`) 기준으로
    열리므로 절대 경로나 그 디렉터리 기준 경로로 적습니다. 첫 줄이 `true` 를
    내고 종료 0 이어야 하고, 둘째 줄의 출력이 정확히 `aws_endpoints` · `clip` ·
    `s3` 셋이어야 합니다(`aws_api` 가 보이면 render 가 NAT 로 나감). 어긋나면
    멈춥니다. `false` 는 되돌리기 전용입니다.

    ```
    terraform -chdir=infra/terraform/prod/workers show -json "${plan}" \
      | jq -e '.variables.render_egress_via_endpoints.value == true'
    terraform -chdir=infra/terraform/prod/workers show -json "${plan}" \
      | jq -r '.resource_changes[] | select(.change.after)
          | select(.address | startswith("module.render.aws_vpc_security_group_egress_rule.this["))
          | .index' | sort
    ```

### 첫 소비 감시

일꾼은 기동하자마자 쌓인 사진 주문부터 처리합니다. 그래서 감시는 apply 보다
먼저 붙고, apply 뒤의 plan 둘보다 먼저입니다. 수신 감시 반복만은 시작
epoch(그 줄의 첫 `worker_started`)가 있어야 돌므로 기동 신호 뒤에 시작합니다.
받기 루프는 기동 신호와 같은 때 돌기 시작하지만 첫 `receive_failed` 는 시도
넷이 끝난 뒤에 찍히고 — 주된 꼴인 연결 시간 초과면 기동 뒤 약 10초, 읽기 시간
초과 꼴이면 늦어도 약 120초 — 반복은 기동 시각부터 읽으므로 빠지는 줄이
없습니다. 기동 뒤 3분은 로그 판정(W13)이 함께 봅니다. 모든 감시 명령에
`--region ap-northeast-2` 를 고정합니다 — 기본 리전이 다르면 그룹 조회가
늘 0 이라 감시가 빈 채로 apply 가 진행됩니다.

*   로그 그룹 `pokeclip-prod-render` · `pokeclip-prod-upload` 마다, 그룹이
    생길 때까지 5초마다 `aws logs describe-log-groups --region
    ap-northeast-2 --log-group-name-prefix <그룹> --query
    'length(logGroups)'` 를 보고, 1 이 되면 `aws logs tail <그룹> --region
    ap-northeast-2 --follow --since 1m --format short` 를 아래 가림 필터를
    거쳐 기록 파일에 씁니다. 로그 줄에 큐 URL(계정 ID 포함)이 찍히고, 유출
    신호가 잡힌 줄에 토큰 값이 남으면 안 됩니다. 이름(`Bearer ` ·
    `X-Internal-Token` · `upload_id=`)은 남겨 정지 신호로 잡히게 하고 값만
    가립니다. `upload_id` 값은 유튜브 업로드 세션 주소의 권한값이라 토큰과
    같이 다룹니다. 값의 끝은 공백 · `"` · `,` · `}` · `&` 라 JSON 꼴
    (`{"X-Internal-Token":"값"}`)과 쿼리 문자열(`?upload_id=값&…`)도
    가립니다.

    ```
    mask() {
      sed -l -E \
        -e 's/[0-9]{12}/<계정>/g' \
        -e 's/([Bb]earer[[:space:]]+)[^"[:space:],}&]+/\1<가림>/g' \
        -e 's/([Xx]-[Ii]nternal-[Tt]oken"?[[:space:]]*[:=][[:space:]]*"?)[^"[:space:],}&]+/\1<가림>/g' \
        -e 's/(upload_id"?[[:space:]]*[:=][[:space:]]*"?)[^"[:space:],}&]+/\1<가림>/g'
    }
    ```

*   큐 깊이(사진 · DLQ 셋)를 30초마다 읽습니다.
*   수신 불능 판정을 2초 쉼으로 되풀이합니다(아래 「수신 · 진행 판정」 의
    `workers_receive_watch` — 로그 그룹마다 하나, 그 그룹의 `worker_started`
    가 보인 뒤부터 시험 · 관찰이 끝날 때까지 · 띄우는 법은 그 절의 쓰는 예).
*   apply 를 시작하고 120초 안에 기록 파일 하나라도 줄이 0 이거나 따라 읽기
    프로세스가 끝났으면 **감시 불능**입니다. 정지 신호와 같이 다룹니다. 단
    120초가 지났는데 그 서비스의 서비스 이벤트(`aws ecs describe-services`)에
    태스크 시작(「has started 1 tasks」)이 아직 없으면(이미지 받기가 느림)
    그 서비스의 빈 기록은 감시 불능으로 보지 않고, 시작 이벤트가 나온 뒤
    120초가 지나도 0 줄이면 감시 불능입니다. 따라 읽기 프로세스가 끝난 것은
    언제든 감시 불능입니다.

**정지 신호** — 하나라도 나오면 긴급 정지합니다.

*   사진 줄: `thumbnail.report_rejected`(보고 4xx — 메시지가 그 자리에서
    지워짐) · `thumbnail.report_unavailable` · `thumbnail.failed`(특히
    `retryable=true code=INTERNAL` — S3 403 · 끊김) ·
    `thumbnail.deadline_before_report` · 첫 `thumbnail.message_done` 뒤 2분
    안에 `thumbnail.captured` 0건.
*   렌더 줄: `render.started_rejected` · `render.report_rejected` ·
    `render.report_unavailable` · `render.unexpected`.
*   업로드: `upload.loop_error` · `upload.unavailable`.
*   공통: **수신 불능** — `*.receive_failed` 가 오류 종류와 상관없이 1분
    넘게 이어짐(아래 `workers_receive_watch` 가 되풀이하는
    `workers_receive_verdict` 가 정지 — 처음 몇 초의
    `AccessDenied` 는 IAM 전파 지연일 수 있어 1분을 기다립니다) · `status=401`
    · 사진 DLQ 깊이가 apply 직전 기준보다 커짐.
*   사진 · 렌더 큐: **무완료** — 아래 `workers_nodone_verdict` 가 정지(기동
    20분 뒤부터 판정합니다).
*   비밀 유출: `X-Internal-Token` · `Bearer ` · `upload_id=` 꼴이 로그에
    보이면 정지와 함께 토큰 회전 판단을 kty 에게 넘깁니다.

정지 대상은 사진 · 렌더 신호면 render, 업로드 신호면 upload, `status=401` ·
비밀 유출 · 사진 DLQ 는 둘 다입니다. 수신 불능은 그 줄의 서비스(render ·
thumbnail 줄 = render, upload 줄 = upload)입니다. 감시 불능은 그 기록 파일의 서비스이고,
따라 읽기가 둘 다 죽었으면 둘 다입니다. 첫 실패 신호에서 **2분 안**(늦어도
240초 — 수신 불능의 셈은 아래 「수신 감시 반복」)에 정지합니다 — 실패한
메시지는 약 120초마다 다시 받히고 세 번째 실패 뒤 DLQ 로 갑니다. 보고 4xx 의
즉시 삭제는 시한이 없어 계약 검사 3 과 회전 창 읽기가 주된 방어이고, 감시는
피해를 첫 몇 건으로 묶는 몫입니다.

**운영 중 멈출 조건** — 하나라도 나오면 긴급 정지합니다. 첫 소비 뒤에도
시험 · 관찰이 끝날 때까지 계속 적용합니다. 렌더 · 업로드 일꾼 결함이 보이면
소비를 멈추고 3번에 넘깁니다 — 띄워 둔 채 두면 렌더는 마지막 시도의 실패를
종결 보고하고 메시지를 지워, 들어오는 주문이 세 번씩 돈 뒤 「실패」 로
닫힙니다.

1.  **렌더:** 서로 다른 두 주문이 같은 `render.failed … code=<코드>` 로
    실패. 사용자 입력 탓 코드(`VALIDATION` · `SOURCE_*` · `SCHEMA_VERSION` ·
    `ENVELOPE_VALIDATION`)는 세지 않습니다. `INTERNAL` 은 세는 코드입니다
    (일시 실패가 늘 이 코드).
1.  **업로드:** 서로 다른 두 주문에서 같은 갈래 — `upload.unavailable …
    what=` 의 값이 같음(clip 문 경로 · auth `/internal/youtube-link/resolve` ·
    `S3 <예외>`). clip 문 경로에는 주문 번호가 들어가므로
    (`/internal/uploads/<번호>/start`) 숫자 경로 조각을 `<id>` 로 바꾼 뒤
    비교합니다(`sed -E 's#/[0-9]+(/|$)#/<id>\1#g'`). 또는
    `upload.loop_error` 1건(clip · auth 4xx). 사용자 ·
    외부 탓 결과(`FAILED YOUTUBE_{NOT_LINKED,UNLINKED,BROKEN}` ·
    `QUOTA_EXCEEDED` · `YOUTUBE_REJECTED`)는 세지 않습니다.
1.  **사진:** `thumbnail.report_unavailable` 이 서로 다른 대상으로 **연속
    3건**, 또는 `thumbnail.report_rejected` 1건(카드 · 클립이면 그 자리
    손실이라 운영 중에도 1건).
1.  `*.loop_error` · `render.unexpected` · `render.report_rejected` 가 한
    번이라도.
1.  DLQ 셋(사진 · 렌더 · 업로드) 중 하나라도 깊이가 apply 직전 기준보다
    커짐. 기준은 「apply 직전 읽기」 ① 의 값입니다. 다시 띄울 때(kty 승인)는
    그 직전 깊이를 새 기준으로 기록합니다 — 정지 사유가 된 증가분은 이미
    다뤘으므로, 새로 늘어난 것만 셉니다.
1.  비밀 유출 신호 — 이때는 정지와 함께 토큰 회전 판단을 kty 에게 넘깁니다.
1.  수신 불능(`workers_receive_verdict` 정지) · 사진 · 렌더 큐의
    무완료(`workers_nodone_verdict` 정지).

정지 대상은 첫 소비 정지 신호와 같습니다 — 사진 · 렌더 조건(렌더 DLQ
포함)이면 render, 업로드 조건(업로드 DLQ 포함)이면 upload, 사진 DLQ · 비밀
유출은 둘 다입니다.

### 수신 · 진행 판정

이그레스를 엔드포인트로 좁히면 render 가 실패하는 주된 모양은 「AWS 에 연결하지
못함」 입니다. 그것은 오류 줄이 아니라 `*.receive_failed` 의 반복으로만
보이고, `*.worker_started` 는 그와 상관없이 찍힙니다. 아래 판정은 **수신
불능** 과 **진행 중인 긴 작업** 을 가릅니다 — render 는 큐마다 스레드 하나로 한
통씩 처리하고 작업 상한은 15분(`job-timeout`)이며, `*.message_done` 은 처리가
끝난 뒤에 찍힙니다.

판정 함수는 kty 로컬 터미널(bash · zsh)에서 이 절의 블록을 차례로 붙여 넣어
씁니다. 블록 안에는 `#` 주석 · 꺾쇠 자리표시 · 글자가 바로 붙은 `!`(예:
`[!0-9]`)를 두지 않습니다 — 대화형 zsh 는 기본값으로 `#` 를 주석으로 읽지
않고(`interactivecomments` 꺼짐) 그런 `!` 를 이력 확장으로 읽어, 붙여 넣은
함수가 정의되지 않습니다. `if ! …` 처럼 공백이 뒤따르는 `!` 는 확장되지
않습니다. 함수의 인자는 블록 앞 본문에 적습니다. 종료 코드는 0 통과 · 1
정지(또는 실패) · 2 판정불가 · 3 대기이고, 판정은 마지막 줄입니다. 판정불가는 정지와 같이 kty 에게 넘깁니다. 로그 · 큐 · 지표
읽기만 하고(`logs:FilterLogEvents` · `sqs:GetQueueUrl` ·
`sqs:GetQueueAttributes` · `cloudwatch:GetMetricStatistics`) 큐 URL(계정 ID
포함)은 변수에만 담아 찍지 않습니다.

```shell
region=ap-northeast-2
work="$(mktemp -d "${TMPDIR:-/tmp}/pokeclip-workers.XXXXXX")"
```

공용 함수입니다. 부르는 꼴은 다음과 같습니다.

*   `workers_iso <epoch>` — ISO 8601(UTC) 시각을 냅니다.
*   `workers_events <로그 그룹> <시작 epoch> <거르기 식> <줄 정규식>` — 로그
    사건을 「초 메시지」 줄로 냅니다. 시작 시각과 줄 이름을 CloudWatch
    거르기와 별개로 한 번 더 거릅니다. 조회 하나에 시간 상한을 겁니다 — 시도
    1번(`AWS_MAX_ATTEMPTS=1` · 재시도 방식 `standard` 고정) · 연결 2초 · 읽기
    3초(`--cli-connect-timeout` · `--cli-read-timeout`) · 한 쪽만
    (`--no-paginate`). 응답에 이어 받기 표(`nextToken` — 결과가 한 쪽에 다
    담기지 않음)가 있으면 결과를 쓰지 않고 실패(2)로 돌아가므로, 줄을 놓친 채
    통과하지 않습니다. 이 함수를 쓰는 판정 넷(기동 · 수신 · 로그 · 무완료)이
    모두 이 상한을 받습니다.
*   `workers_started_at <로그 그룹> <줄 이름> <apply 시작 epoch>` — 판정의
    기준 시각(그 줄의 첫 `worker_started`)을 냅니다.
*   `workers_queue_depth <큐 이름>` — **보이는 깊이**
    (`ApproximateNumberOfMessages` — DELAY 처분으로 숨긴 메시지는 세지 않음)를
    냅니다.

```shell
workers_iso() {
  jq -rn --argjson t "$1" '$t | todate'
}

workers_events() {
  AWS_MAX_ATTEMPTS=1 AWS_RETRY_MODE=standard aws logs filter-log-events \
    --region "${region}" --log-group-name "$1" \
    --start-time "$(($2 * 1000))" --filter-pattern "$3" --no-paginate \
    --cli-connect-timeout 2 --cli-read-timeout 3 --output json \
    > "${work}/events.json" < /dev/null || return 2
  if ! jq -e '.nextToken == null' "${work}/events.json" > /dev/null; then
    echo "조회: $1 의 결과가 한 쪽에 다 담기지 않음(nextToken)" >&2
    return 2
  fi
  jq -r --argjson from "$(($2 * 1000))" --arg re "$4" \
    '.events | sort_by(.timestamp) | .[] | select(.timestamp >= $from)
      | select(.message | test($re)) | "\(.timestamp / 1000 | floor) \(.message)"' \
    "${work}/events.json" || return 2
}

workers_started_at() {
  local first
  case "${3-}" in
    '' | *[^0-9]*)
      echo "기동 $2: 판정불가 — apply 시작 epoch(셋째 인자)가 숫자가 아님"
      return 2
      ;;
  esac
  first="$(workers_events "$1" "$3" "\"$2.worker_started\"" \
    "$2\\.worker_started " | head -n 1 | cut -d ' ' -f 1)"
  if [ -z "${first}" ]; then
    echo "기동 $2: 판정불가 — $1 에 $2.worker_started 가 없거나 조회 실패"
    return 2
  fi
  echo "${first}"
}

workers_queue_depth() {
  local url
  url="$(aws sqs get-queue-url --region "${region}" --queue-name "$1" \
    --query QueueUrl --output text < /dev/null)" || return 2
  aws sqs get-queue-attributes --region "${region}" --queue-url "${url}" \
    --attribute-names ApproximateNumberOfMessages \
    --query Attributes.ApproximateNumberOfMessages --output text < /dev/null
}
```

**수신 불능(W12 · 운영 중).** 같은 줄 이름(render · thumbnail · upload)의
`receive_failed` 가 180초 이하의 틈으로 이어지며 첫 줄부터 60초를 넘었고, 그
이어짐이 **지금 진행 중** 이면 정지입니다. 진행 중이란 마지막 실패가 지금부터
180초 안이고 그 뒤 같은 줄의 정상 신호가 없다는 뜻입니다. 오류 종류는 보지
않습니다.

*   **정상 신호는 이어짐을 끊습니다.** 같은 줄의 처리 시작(`render.started` ·
    `upload.begin`)이나 완료(`*.message_done`)가 두 실패 사이에 있으면 그
    사이에 받기가 성공한 것이라, 뒤 실패부터 새로 셉니다. 일꾼은 큐마다 스레드
    하나로 받기 → 처리 → `message_done` → 다음 받기를 차례로 하므로(render 는
    `pollOnce` 가 처리를 바로 부름) 처리하는 동안에는 실패 줄이 없고, 줄
    이름이 다른 신호(예: 사진 줄의 완료)는 렌더 줄을 끊지 않습니다.
*   **틈 180초** 는 실패 줄 사이의 최악보다 깁니다. render 의 SQS
    클라이언트는 AWS SDK 2.46.7 의 기본 재시도(LEGACY)라 수신 한 번에 최대
    4번 시도하고(연결 시간 초과 2초 · 소켓 시간 초과 30초 · 재시도 사이 대기
    최대 약 1.4초), 실패를 찍은 뒤 5초 쉬고 다시 받습니다. 그래서 읽기 시간
    초과 꼴이면 실패 줄 간격이 최악 약 134초이고, 실측은 125초였습니다.
    재시도 쿼터(500 · 재시도마다 5)가 바닥나면 시도가 1번으로 줄어 간격이 약
    35초가 됩니다. thumbnail 줄은 render 와 같은 클라이언트이고, upload 도 같은
    SDK 판 · 기본 재시도 · 소켓 30초 · 실패 뒤 5초 쉼이라
    (`workers/upload` 의 `WorkerConfiguration.java` 46–50행 ·
    `QueueWorker.java` 102–103행) 상한이 같습니다. `AWS_NEW_RETRIES_2026`
    처럼 SDK 의 기본 재시도가 바뀌면 이 상한도 달라지므로 SDK 판을 올릴 때
    다시 잽니다.
*   **사이 신호가 없는 두 실패** 는 틈이 180초 이하면 이어진 것으로 보고
    정지합니다(예: 0초 · 170초). 성공한 빈 수신은 로그를 남기지 않아 사이의
    성공을 볼 길이 없습니다. 틈 134–180초는 SDK 모형으로는 사이에 성공이
    있었겠지만, 모형 밖의 지연을 위한 여유라 닫는 쪽으로 판정합니다 — 거짓
    정지의 값은 kty 승인 재기동 한 번이고 메시지 손실은 없습니다. 틈이
    180초를 넘으면 사이에 성공이 있었다고 봅니다.
*   **회복:** 마지막 실패 뒤 180초 동안 그 줄의 실패가 없으면 끝난 장애라
    정지하지 않습니다 — 이어지는 장애는 늦어도 약 134초마다 실패 줄을 남기기
    때문입니다. 그래서 기동 시각부터 조회해도 회복한 옛 장애로 정지가 다시
    나오지 않습니다. 기동 직후에는 기동 뒤의 실패가 모두 「지금」 안이라
    판정이 같습니다.

인자는 로그 그룹 · 시작 epoch · 지금 epoch 이고(지금 epoch 가 없으면
판정불가), 종료 코드는 통과 0 · 정지 1 · 판정불가 2 입니다.

```shell
workers_receive_verdict() {
  case "${2-}" in
    '' | *[^0-9]*)
      echo "수신: 판정불가 — 시작 epoch(둘째 인자)가 숫자가 아님"
      return 2
      ;;
  esac
  case "${3-}" in
    '' | *[^0-9]*)
      echo "수신: 판정불가 — 지금 epoch(셋째 인자)가 없음"
      return 2
      ;;
  esac
  if ! workers_events "$1" "$2" \
      '?"receive_failed" ?"started" ?"begin" ?"message_done"' \
      '[a-z]+\.(receive_failed|started|begin|message_done) ' \
      > "${work}/receive.txt"; then
    echo "수신: 판정불가 — $1 로그 조회 실패"
    return 2
  fi
  awk -v gap=180 -v limit=60 -v now="$3" '
    match($0, /[a-z]+\.(receive_failed|started|begin|message_done) /) {
      dot = index(substr($0, RSTART), ".")
      name = substr($0, RSTART, dot - 1)
      event = substr($0, RSTART + dot, RLENGTH - dot - 1)
      if (event == "receive_failed") {
        n++
        if ((name in last) && $1 - last[name] <= gap) {
          last[name] = $1
          next
        }
        first[name] = $1
        last[name] = $1
        next
      }
      delete last[name]
    }
    END {
      for (name in last) {
        if (now - last[name] <= gap && last[name] - first[name] > limit) {
          printf "수신: 정지 — %s.receive_failed 가 %d초 이어지는 중\n", name, last[name] - first[name]
          stop = 1
        }
      }
      if (stop) { exit 1 }
      printf "수신: 통과 — receive_failed %d건 · 지금 1분 넘게 이어지는 줄 없음\n", n
    }' "${work}/receive.txt"
}
```

**수신 감시 반복(W12).** 위 판정을 되풀이합니다 — 판정이 끝나면 **2초**
쉬고 다음 판정을 합니다. 서비스(로그 그룹)마다 터미널을 하나씩 따로 두고,
그 그룹의 `worker_started` 가 보인 뒤부터 시험 · 관찰이 끝날 때까지
돌립니다. 인자는 로그 그룹 · 시작 epoch · 끝 epoch 입니다.

*   시작 · 끝 epoch 가 숫자가 아니면(기동 전 `workers_started_at` 이 낸
    판정불가 문장 · 옮겨 적지 않은 빈 값) 판정하지 않고 판정불가(2)로
    끝납니다.
*   반복이 찍는 줄마다 앞에 **판정이 끝난 시각**(`HH:MM:SS UTC`)이 붙습니다.
*   판정이 정지(1)면 바로 끝나고 1 을 냅니다 — 긴급 정지 신호입니다.
*   **판정하지 못한 주기** 는 조회 실패(판정불가)와, 판정 한 번이 5초를 넘긴
    것(「늦음」 — 통과든 조회 실패든 결과와 상관없이 「수신 감시: 늦음」 줄을
    찍음 · 통과여도 그 사이의 줄을 못 봤을 수 있음)입니다. 한 번은 다음 주기에
    다시 보고, 두 번 이어지면 판정불가(2)로 끝납니다 — 정지와 같이 kty 에게
    넘깁니다.
*   끝 시각이 지난 뒤 판정이 통과하면 「끝」 과 함께 0 으로 끝납니다(끝 시각의
    주기를 판정하지 못했으면 한 주기 더 봅니다).
*   조회는 위 시간 상한으로 늦어도 약 5초 안에 돌아오지만, 상한이 자르지
    못하는 몫(CLI 기동 · DNS 이름 풀이 · 자격 증명 읽기)에서 멈추면 반복은
    아무것도 찍지 못합니다. 마지막 판정 줄의 시각에서 **10초** 가 지나도(지금
    시각은 `date -u +%T`) 새 줄이 없으면 판정불가로 보고 정지와 같이 다룹니다.
    평소 줄 사이는 판정(늦음이 아니면 6초 미만) + 쉼 2초라 10초를 넘지
    않습니다.

**정지 시한의 셈(판정 226초 + 명령 여유 = 240초 안).** 첫 `receive_failed`
줄을 0초로 둡니다.

1.  **조건이 서는 때 — 194초.** 정지는 같은 줄의 실패가 60초를 **넘게**
    이어져야 납니다. 둘째 실패가 60초에 찍히면(넘지 않음) 셋째 실패가 다음
    간격의 최악(약 134초) 뒤인 194초에 찍힐 때 섭니다.
1.  **판정이 볼 수 있는 때 — 204초.** 로그 수집 지연(줄이 찍힌 뒤 CloudWatch
    Logs 조회에 보이기까지)을 **10초 이하로 가정** 합니다. prod 24시간
    실측(2026-11-10)은 평소 p99 약 5초이고, 망 전환 순간에는 40–50초까지
    늘었습니다(아래 「알려진 한계」).
1.  **판정 한 번의 최악 — 6초.** 조회 하나는 시도 1번 · 연결 2초 · 읽기 3초 ·
    한 쪽만이라 망 쪽이 늦어도 5초에 끝나고(실패면 판정불가), CLI 기동 · jq ·
    awk 는 로컬 실측 0.2–0.6초입니다. 「늦음」 은 `date +%s`(초 단위)의 차가
    5초를 넘을 때라, 늦음 없이 지나는 판정은 실제로 6초 미만입니다.
1.  **반복이 정지를 내는 때 — 226초.** 204초 바로 앞에 시작한 판정은 그 줄을
    못 봅니다. 그 판정(6초)과 쉼 2초 뒤에 시작한 판정이 처음 보고, 그
    판정이 조회 실패면 쉼 2초 뒤 한 번 더 판정합니다(또 실패하면 판정불가로
    끝남). 판정 셋 18초 + 쉼 둘 4초 = 22초입니다. 셋째 판정이 상한 밖에서
    멈춰 줄이 끊긴 경우는 둘째 판정 줄(늦어도 218초) + 10초 = 228초입니다.

남는 12초 안에 kty 가 정지 명령을 돌립니다(명령 여유). 옛 꼴(쉼 5초 · 늦음
검사가 통과에만 · 조회 시간 상한 없음 — CLI 기본은 시도 3번 · 연결 · 읽기
각 60초)은 r3 의 시간열(실패 0 · 60 · 194초 · 수집 지연 10초 · 조회 196→198 ·
203→217 · 222→236초)에서 236초에 정지해 명령 여유를 넣으면 248초였습니다.
조회 수는 반복 하나가 약 2.5초에 한 번이라, 반복 둘과 따라 읽기 둘(5초에 한
번씩)을 합쳐 초당 약 1.2건입니다. 스로틀이 나면 조회 실패라 판정불가(닫는
쪽)이고 거짓 통과는 없습니다. 쉼 · 늦음 상한 · 시간 상한 · 수집 지연 가정 ·
끊김 10초를 바꾸면 이 셈과 시험의 시한 사례를 함께 고칩니다.

```shell
workers_receive_watch() {
  local arg begin now rc verdict stamp misses=0
  for arg in "${2-}" "${3-}"; do
    case "${arg}" in
      '' | *[^0-9]*)
        echo "수신 감시: 판정불가 — 시작 · 끝 epoch 중 숫자가 아닌 것이 있음"
        return 2
        ;;
    esac
  done
  while :; do
    begin="$(date -u +%s)"
    verdict="$(workers_receive_verdict "$1" "$2" "${begin}")"
    rc=$?
    now="$(date -u +%s)"
    stamp="$(jq -rn --argjson t "${now}" '$t | strftime("%H:%M:%S UTC")')"
    printf '%s\n' "${verdict}" | sed "s/^/${stamp} /"
    if [ "${rc}" -eq 1 ]; then
      return 1
    fi
    if [ "$((now - begin))" -gt 5 ]; then
      echo "${stamp} 수신 감시: 늦음 — 판정에 $((now - begin))초(상한 5초)"
      rc=2
    fi
    if [ "${rc}" -eq 0 ]; then
      misses=0
    else
      misses=$((misses + 1))
    fi
    if [ "${misses}" -ge 2 ]; then
      echo "${stamp} 수신 감시: 판정불가 — 두 주기 이어 판정하지 못함"
      return 2
    fi
    if [ "${misses}" -eq 0 ] && [ "${begin}" -ge "$3" ]; then
      echo "${stamp} 수신 감시: 끝 — $(workers_iso "$3") 에 이름"
      return 0
    fi
    sleep 2
  done
}
```

**무완료(W12 · 운영 중).** 사진 · 렌더 큐마다, 보이는 깊이 > 0 이고 가장 오래된
메시지 나이(`AWS/SQS` `ApproximateAgeOfOldestMessage` 의 최근 점)가 20분
(`job-timeout` 15분 + 5분)을 넘었는데 그 줄의 `message_done` · `failed` 가 지난
20분 동안 0 이면 정지입니다. 정상 장기 렌더(최대 15분)는 이 창 안에서 끝나
`message_done` 을 내므로 정지하지 않습니다. 기동 뒤 20분이 안 됐으면 대기입니다.
첫 소비 판정(3분)은 그대로 두고, 이 판정은 기동 20분 뒤부터 첫 소비 감시와
운영 중에 5분마다 돌립니다. 인자는 로그 그룹 · 줄 이름 · 큐 이름 · 기동 epoch ·
지금 epoch 입니다.

```shell
workers_nodone_verdict() {
  local depth age finished
  if [ "$(($5 - $4))" -lt 1200 ]; then
    echo "무완료 $2: 대기 — 기동 뒤 20분이 안 됨"
    return 3
  fi
  depth="$(workers_queue_depth "$3")"
  case "${depth}" in
    '' | *[^0-9]*)
      echo "무완료 $2: 판정불가 — $3 깊이 조회 실패"
      return 2
      ;;
  esac
  if [ "${depth}" -eq 0 ]; then
    echo "무완료 $2: 통과 — $3 의 보이는 깊이 0(숨긴 메시지는 세지 않음)"
    return 0
  fi
  if ! aws cloudwatch get-metric-statistics --region "${region}" \
      --namespace AWS/SQS --metric-name ApproximateAgeOfOldestMessage \
      --dimensions "Name=QueueName,Value=$3" \
      --start-time "$(workers_iso "$(($5 - 600))")" \
      --end-time "$(workers_iso "$5")" --period 60 --statistics Maximum \
      --output json > "${work}/age-$3.json" < /dev/null; then
    echo "무완료 $2: 판정불가 — $3 나이 지표 조회 실패"
    return 2
  fi
  age="$(jq -r '.Datapoints | sort_by(.Timestamp) | last | .Maximum // empty
    | floor' "${work}/age-$3.json")"
  if [ -z "${age}" ]; then
    echo "무완료 $2: 판정불가 — $3 의 나이 지표가 최근 10분에 없음"
    return 2
  fi
  if [ "${age}" -le 1200 ]; then
    echo "무완료 $2: 통과 — 깊이 ${depth} · 가장 오래된 메시지 ${age}초"
    return 0
  fi
  if ! workers_events "$1" "$(($5 - 1200))" \
      "?\"$2.message_done\" ?\"$2.failed\"" "$2\\.(message_done|failed) " \
      > "${work}/finished-$2.txt"; then
    echo "무완료 $2: 판정불가 — $1 로그 조회 실패"
    return 2
  fi
  finished="$(grep -c . "${work}/finished-$2.txt")"
  if [ "${finished}" -eq 0 ]; then
    echo "무완료 $2: 정지 — 깊이 ${depth} · 나이 ${age}초 · 지난 20분 완료 0"
    return 1
  fi
  echo "무완료 $2: 통과 — 지난 20분 완료 ${finished}건"
}
```

**로그 판정(W13 — 기동 뒤 3분).** 기동 뒤 `receive_failed` 가 0건이어야 하고,
보이는 깊이 > 0 이면 그 줄의 처리 시작 줄 또는 `message_done` 이 1건 이상이어야
합니다. 처리 시작 줄은 render 줄이 `render.started`(clip 의 시작 보고가 받아진
뒤 — 긴 렌더도 이 줄은 곧 찍힘)이고, upload 줄이 `upload.begin`(clip 시작 ·
연동 조회 · S3 받기가 끝나고 유튜브로 보내기 직전 — `UploadProcessor.java`
179행)입니다. 업로드는 조각 하나에 최대 5분을 기다리므로
(`ResumableUploader.java` 179행) 정상 업로드도 3분 안에 `message_done` 을
내지 못할 수 있어, 시작 줄로 봅니다. 사진 줄은 작업이 짧아 `message_done` 으로
봅니다. 통과는 **잠정 통과** 입니다 — 빈 큐면 수신이
성공했는지를 로그가 보여 주지 않기 때문입니다. 3분을 기다리는 것은 수신이
처음부터 막히면 첫 `receive_failed` 가 시도 4번이 끝난 뒤(늦어도 기동 뒤 약
120초)에야 찍히기 때문입니다. 인자는 로그 그룹 · 줄 이름 · 큐 이름 · 기동
epoch · 지금 epoch 입니다.

```shell
workers_start_log_verdict() {
  local depth
  if [ "$(($5 - $4))" -lt 180 ]; then
    echo "로그 $2: 대기 — 기동 뒤 3분이 안 됨"
    return 3
  fi
  if ! workers_events "$1" "$4" "\"$2.receive_failed\"" \
      "$2\\.receive_failed " > "${work}/start-failed-$2.txt"; then
    echo "로그 $2: 판정불가 — $1 로그 조회 실패"
    return 2
  fi
  if [ "$(grep -c . "${work}/start-failed-$2.txt")" -ne 0 ]; then
    echo "로그 $2: 실패 — 기동 뒤 receive_failed 가 있음"
    return 1
  fi
  depth="$(workers_queue_depth "$3")"
  case "${depth}" in
    '' | *[^0-9]*)
      echo "로그 $2: 판정불가 — $3 깊이 조회 실패"
      return 2
      ;;
  esac
  if ! workers_events "$1" "$4" \
      "?\"$2.started\" ?\"$2.begin\" ?\"$2.message_done\"" \
      "$2\\.(started|begin|message_done) " > "${work}/progress-$2.txt"; then
    echo "로그 $2: 판정불가 — $1 로그 조회 실패"
    return 2
  fi
  if [ "${depth}" -gt 0 ] && [ "$(grep -c . "${work}/progress-$2.txt")" -eq 0 ]; then
    echo "로그 $2: 실패 — $3 깊이 ${depth} 인데 시작 · 완료 줄이 없음"
    return 1
  fi
  echo "로그 $2: 잠정 통과 — receive_failed 0건 · 깊이 ${depth}"
}
```

**지표 판정(W13 — 기동 뒤 20분 안).** 빈 큐의 수신 성공은 render 코드가 로그를
남기지 않아 `AWS/SQS` `NumberOfEmptyReceives` 로만 봅니다. 기동한 분의 **다음
분부터** 0 보다 큰 점이 하나라도 있으면 통과입니다(기동한 분의 점은 옛 태스크의
빈 수신일 수 있어 세지 않음). SQS 지표는 1분 주기지만 쉬던 큐가 다시 움직이면
최대 15분 늦게 오므로, 점이 아직 없으면 대기이고 20분이 지나도 없으면
판정불가(수신 경로 미확인 — kty 회부)입니다. 큐가 비지 않았으면 로그 판정의
시작 · 완료 줄이 수신을 보여 주므로 이 판정은 빈 큐에만 씁니다. 인자는 큐
이름 · 기동 epoch · 지금 epoch 이고, 종료 코드는 통과 0 · 판정불가 2 · 대기
3 입니다.

```shell
workers_empty_receive_verdict() {
  local from=$(((($2 + 59) / 60) * 60))
  if ! aws cloudwatch get-metric-statistics --region "${region}" \
      --namespace AWS/SQS --metric-name NumberOfEmptyReceives \
      --dimensions "Name=QueueName,Value=$1" \
      --start-time "$(workers_iso "${from}")" --end-time "$(workers_iso "$3")" \
      --period 60 --statistics Sum --output json \
      > "${work}/empty-$1.json" < /dev/null \
    || ! jq -e '.Datapoints | type == "array"' "${work}/empty-$1.json" \
      > /dev/null; then
    echo "지표 $1: 판정불가 — NumberOfEmptyReceives 조회 실패"
    return 2
  fi
  if jq -e --argjson from "${from}" '[.Datapoints[]
      | select((.Timestamp | sub("(\\.[0-9]+)?(Z|\\+00:00)$"; "Z")
        | fromdateiso8601) >= $from) | .Sum] | any(. > 0)' \
      "${work}/empty-$1.json" > /dev/null; then
    echo "지표 $1: 통과 — 기동 뒤 분에 빈 수신이 있음"
    return 0
  fi
  if [ "$(($3 - $2))" -lt 1200 ]; then
    echo "지표 $1: 대기 — 기동 뒤 분의 빈 수신 점이 아직 없음"
    return 3
  fi
  echo "지표 $1: 판정불가 — 기동 20분 뒤에도 빈 수신 점이 없음"
  return 2
}
```

`NumberOfEmptyReceives` 는 큐 단위라 그 큐를 받는 태스크가 여럿이면 섞입니다.
render 가 혼자 받는 때(원하는 수 1 · 첫 기동이나 정지 뒤 다시 띄우기 — 롤링
겹침 없음)만 이 지표를 단독 신호로 쓰고, 이미지 교체처럼 옛 태스크와 겹치는
동안에는 보조로만 둡니다 — 그때는 로그 판정과 수신 불능 판정이 주 신호입니다.

쓰는 예입니다. `apply_at` 은 kty 가 apply 를 시작하기 직전에
`apply_at="$(date -u +%s)"` 로 적어 둔 값이고, `watch_end` 는 시험 · 관찰을
끝낼 시각(예: `watch_end=$((apply_at + 7200))`)입니다. 사진 줄은 `thumbnail` ·
`pokeclip-prod-jobs-thumbnail`, upload 는 `pokeclip-prod-upload` · `upload` ·
`pokeclip-prod-jobs-upload` 로 바꿔 부릅니다.

**감시 터미널 준비.** 수신 감시 반복은 로그 그룹마다 따로 연 터미널에서
돌립니다(render 그룹 하나가 render · thumbnail 줄을 함께 봄). 터미널마다 이 절의
블록(`region` · `work` 와 함수 아홉)을 먼저 붙여 넣고, apply 터미널의
`apply_at` · `watch_end` 값을 숫자 그대로 옮겨 적습니다. 한 셸에서 `&` 로 여럿을
띄우지 않습니다 — 같은 `${work}` 의 `events.json` · `receive.txt` 를 함께 써서
판정이 섞입니다.

아래 첫 줄은 `worker_started` 가 보여 `started` 에 숫자가 담길 때까지 10초마다
기다리며 아직인 까닭(판정불가 문장)을 찍으므로, apply 를 시작한 바로 뒤에 붙여
넣어도 됩니다. 관찰을 늘리면 끝 시각을 새로 주어 마지막 줄만 다시 띄웁니다.
관찰이 길어 조회 창(시작 epoch 부터 지금까지)이 커지면 이어 받기 표나 늦음이
잦아질 수 있으므로, 다시 띄울 때 시작 epoch 를 그때 시각(`$(date -u +%s)`)으로
주어도 됩니다. 새 반복은 그 시각 앞의 실패를 보지 않아, 이미 이어지던 장애도
새 시작 뒤의 실패부터 다시 세어 늦어도 위 시한(새 시작에서 240초) 안에 정지를
냅니다. 그 사이를 비우지 않도록 앞 반복이 끝나기 전에 다른 감시 터미널에서 새
반복을 띄우고, 새 반복의 첫 판정 줄이 나온 뒤 앞 반복을 끝냅니다.

```
until started="$(workers_started_at pokeclip-prod-render render "${apply_at}")"; do
  echo "기동 대기 — ${started}"
  sleep 10
done
workers_receive_watch pokeclip-prod-render "${started}" "${watch_end}"
```

판정 함수를 하나씩 부를 때도 `started` 가 숫자일 때(위 기다림이 끝난 뒤)
부릅니다.

```
workers_start_log_verdict pokeclip-prod-render render \
  pokeclip-prod-jobs-render "${started}" "$(date -u +%s)"
workers_receive_verdict pokeclip-prod-render "${started}" "$(date -u +%s)"
workers_empty_receive_verdict pokeclip-prod-jobs-render "${started}" \
  "$(date -u +%s)"
workers_nodone_verdict pokeclip-prod-render render \
  pokeclip-prod-jobs-render "${started}" "$(date -u +%s)"
```

끝나면 감시 터미널마다(블록을 붙여 넣은 셸마다 `mktemp -d` 가 하나씩
만들었음) 작업 디렉터리를 지웁니다. `events.json` 에는 가림 필터를 거치지 않은
원 메시지(큐 URL 의 계정 ID 가 들어갈 수 있음)가 남습니다.

```shell
rm -rf -- "${work:?}"
```

### 긴급 정지와 상태 어긋남

render 와 upload 의 두 명령입니다. 정지 대상인 서비스의 명령만 돌립니다.

```
aws ecs update-service --region ap-northeast-2 --cluster pokeclip-prod \
  --service pokeclip-prod-render --desired-count 0 \
  --query service.serviceName --output text
```

```
aws ecs update-service --region ap-northeast-2 --cluster pokeclip-prod \
  --service pokeclip-prod-upload --desired-count 0 \
  --query service.serviceName --output text
```

*   **사전 승인 범위:** apply 승인과 한 묶음으로 받는 긴급 정지 두 명령의
    조건부 실행 승인은 세 목록 모두에 걸립니다 — 첫 소비 정지 신호 하나 ·
    감시 불능 · 운영 중 멈출 조건 하나. 유효 범위는 그 apply 부터 시험 ·
    관찰이 끝날 때까지 한 번의 흐름이고, 시험 · 관찰이 끝나면 승인도
    끝납니다. 그 뒤의 운영 중 정지는 매번 kty 승인입니다.
*   상태는 `desired_count = 1` 고정이라 정지 뒤 상태와 실물이 어긋납니다.
    **어긋남 · 시각 · 사유를 기록합니다.** 다음 apply 가 1 로 되돌리므로,
    정지한 채로 이 루트를 apply 하지 않습니다(plan 에 desired 변경이 보임).
*   다시 띄우기(`--desired-count 1` 또는 apply)는 위 사전 승인에 들지
    않습니다. 원인을 고친 뒤 **한 번만**, 매번 kty 승인입니다. 띄우기 직전에
    DLQ 셋의 깊이를 읽어 정지 조건의 새 기준으로 기록합니다. 받은 횟수는
    메시지에 남아 있어, 또 정지 신호가 나오면 더 시도하지 않고 kty 에게
    넘깁니다.
*   멈춘 동안 주문은 큐에 남습니다(보관 4일).

### 되돌리기 · 내리기

*   **이미지 교체:** `terraform.tfvars` 다이제스트를 바꿔 plan · apply 합니다.
    롤링 동안 두 벌이 같은 큐를 읽지만 렌더 · 업로드는 겹쳐도 안전하게
    짜여 있습니다(배포 비율은 모듈 기본 — 공백보다 겹침).
*   **일꾼만 내리기:** [prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md)
    「0a. prod/workers 먼저 내리기」 를 따릅니다. 요지는 모두 delete 이고 이
    루트 주소만 나옵니다. platform 은 건드리지 않고, 주문은 큐에 남습니다.
*   **revert 전에 내립니다** — 되돌리면 이 루트의 코드가 사라져 destroy 할
    구성이 없어집니다. PR 은 머지 커밋이라 `git revert -m 1 <머지 커밋>` 으로
    되돌립니다. `modules/ecs_service` 의 포트 없는 꼴을 되돌리는 것도
    이 루트를 내린 뒤에만 합니다.
*   **render 이그레스 되돌리기:** `render_egress_via_endpoints = false` 는
    되돌리기 전용입니다. network 의 되돌리기
    ([prod/network](/infra/terraform/prod/network/README.md) 「되돌리기」)가
    부를 때 이 루트를 `-var render_egress_via_endpoints=false` 로 plan · apply
    합니다(kty 승인 — 요지는 render SG 의 엔드포인트 규칙 삭제 · NAT 규칙
    생성). `terraform.tfvars` 에는 적지 않습니다 — 첫 apply 의 「apply 직전
    읽기」 는 저장 plan 의 이 값이 `true` 인지 봅니다. 값이 `false` 면 계약
    검사 7 · 8 을 보지 않아(`main.tf` 의 두 검사가 `true` 일 때만 봄) DNS 가
    꺼진 동안에도 plan 이 돕니다. 엔드포인트가 다시 켜지고 network 의 확인을 지난
    뒤 `-var` 없이(기본 true) plan · apply 해 되돌립니다. apply 는 원하는 수 1
    을 되살리므로, 긴급 정지 중이었으면 다시 띄우기 승인을 함께 받습니다.
*   토큰을 회전할 때는 일꾼을 멈춘 채 회전합니다
    ([runtime-1a 런북](/infra/terraform/runbooks/runtime-1a.md)) — clip 의
    롤링이 옛 값 · 새 값 태스크를 겹쳐 두기 때문입니다.

## 알려진 한계

*   실행 · 작업 역할의 신뢰 정책은 혼동된 대리인 조건으로 `aws:SourceAccount`
    하나만 둡니다(모듈 공통). `aws:SourceArn`(이 클러스터의 ECS 자원)으로 더
    좁히는 것은 platform 다섯 서비스의 역할도 함께 바뀌는 모듈 변경이라
    platform apply 가 들어, 이 PR 에 넣지 않고 후속 후보로 둡니다.
*   역할 경로는 루트 지역값(`local.iam_path`)만 단언합니다. 모듈 블록이 실제로
    받은 경로는 모듈 출력에 경로가 없어 시험이 보지 못합니다. `/pokeclip/prod/`
    밖의 유효한 경로로 잘못 연결돼도 apply 는 성공하고, 그 역할이 2-3 판정의
    prod 역할 대상 해석(`prod-roles` 행)에서 빠집니다
    ([단계 직전 점검](/infra/terraform/runbooks/stage-precheck.md) A19). 이
    배선은 리뷰가 봅니다.
*   render 의 엔드포인트 이그레스가 닫는 것은 render 가 인터넷의 임의 HTTPS
    호스트에 직접 연결하는 길(SSRF · 원격 조종 서버 연결)입니다. 남는 길이
    둘 있습니다.
    *   **(a)** 공격자가 자기 자격 증명으로 서명한 요청을 허용된 AWS
        서비스(엔드포인트 다섯 · S3 게이트웨이)로 보내 **다른 계정** 의 자원에
        쓰는 길. 엔드포인트와 게이트웨이의 기본 정책은 `Principal *` ·
        `Action *` · `Resource *` 이고, S3 접두사 목록은 리전의 서비스 IP
        범위일 뿐 계정 허용 목록이 아닙니다. 엔드포인트 정책(데이터 경계)이
        막으며 후속 후보입니다.
    *   **(b)** VPC 리졸버에 보내는 DNS 질의 이름에 데이터를 실어 보내는 길.
        SG 는 Route 53 Resolver 질의를 거르지 못합니다. Route 53 DNS
        Firewall 이 막으며 후속 후보입니다.
*   이미지 교체(롤링 겹침) 동안 로그 · 수신 판정은 로그 그룹 전체를 보아 옛 ·
    새 태스크의 줄이 섞입니다. 태스크(로그 스트림)별로 가르는 것은 후속
    POK-560 입니다.
*   `NumberOfEmptyReceives` 는 큐 단위 지표라 롤링 겹침 동안 옛 태스크의 빈
    수신과 섞입니다(「수신 · 진행 판정」 의 지표 판정 끝 문단).
*   수신 불능의 정지 시한(240초)은 로그 수집 지연 10초 이하를 가정합니다. prod
    24시간 실측(2026-11-10 · `ingestionTime - timestamp`)은 평소 p99 약
    5초였고, network apply(인터페이스 엔드포인트 private DNS 켬)와 같은 한
    분에만 40–50초로 늘었습니다(10초 넘는 170건이 모두 그 분). 망 전환
    순간에는 그만큼 정지가 늦어져 시한을 넘길 수 있습니다. 그래서 **일꾼 apply · 감시는 network 등 망 변경 apply 와
    같은 시간대에 하지 않습니다.** 첫 apply 의 관찰이 끝나면 작업 디렉터리를
    지우기 전에 감시 터미널에서
    `jq '[.events[] | .ingestionTime - .timestamp] | max' "${work}/events.json"`
    (밀리초 · 마지막 조회의 줄만이라 하한)로 지연을 한 번 재어 기록하고, 10초를
    넘으면 셈과 쉼을 다시 봅니다.

## 시험

`terraform -chdir=infra/terraform/prod/workers test` 가 mock provider 로
봅니다(AWS 자격 증명 불요). 루트 시험은 하위 모듈 안을 볼 수 없어 모듈에
넘기는 지역값 · 루트 자원 · 모듈 출력을 단언합니다.

*   이미지 = `repo_urls[키]@다이제스트` · 크기 · 역할 경로 · 로그 보존 30일 ·
    서비스 이름.
*   env 맵 · 비밀 맵 · 작업 역할 문장 · 이그레스가 계약과 정확히 같음.
*   인그레스 셋의 대상 SG · 포트 · 상대 SG(apply).
*   모듈 블록 배선 — 모듈이 만든 태스크 정의 · 작업 역할 정책 · 이그레스가
    같은 일꾼의 지역값인지(apply · 모듈 출력 `task_definition` ·
    `task_role_policy` · `egress_rules`).
*   계약 검사 1 부터 6 이 어긋난 출력에서 plan 을 멈춤 · 태그 다이제스트와
    모르는 일꾼 키를 변수 검증이 막음.
*   render 이그레스 — 모듈이 만든 render 규칙에 CIDR 이 없고 AWS API 는
    엔드포인트 SG 443(apply) · upload 는 NAT 그대로 · 계약 검사 7(문자열 ·
    null · 키 없음) · 8(서비스 하나 빠짐 · DNS 꺼짐 · 키 없음)이 멈춤 ·
    `false` 는 엔드포인트 출력이 없거나 DNS 가 꺼져도 plan 이 돌고 render
    이그레스가 엔드포인트 앞의 값.

자원 수(create 29)는 `terraform test` 가 셀 수 없어 위 「plan 요지」 가 셉니다.

「수신 · 진행 판정」 의 셸 블록은
`bash infra/terraform/scripts/test/workers_watch_runbook_test.sh` 가 README
글자 그대로 뽑아 가짜 aws 로 봅니다 — 정상 6분 렌더 · 연결 시간 초과 반복 ·
빈 큐 + 지표 지연 · 빈 큐 연결 불능 · DELAY 숨김의 다섯과 경계 사례(정상
신호로 끊김 · 회복한 옛 장애 · 진행 중 업로드 · 감시 반복의 주기 · 종료 ·
조회 실패 · 늦은 판정(조회 실패도) · 판정 줄의 시각 · 숫자가 아닌 시각 인자 ·
이어 받기 표가 있는 조회), 「쓰는 예」 의 기동 대기 블록, 정지 시한(가짜 aws 가
CLI 의 시간 상한 · 시도 수를 따름 — r2 의 두 시간열 · 판정마다 5초 · r3 의
시간열 · 조회가 상한까지 걸려 실패 · 상한 밖에서 멈춰 줄이 끊김이 판정 줄의
시각 + 명령 여유 12초로 240초 안), 판정 한 줄을 뒤집은 변이, 블록에 `#`
주석이 없는지, 대화형 zsh(표준 입력 · PTY 붙여 넣기)에서 함수 아홉이 모두
정의되고, 그중 일곱을 불렀을 때(나머지 둘 `workers_events` ·
`workers_queue_depth` 는 그 안에서 불림) bash 와 같은 결과를 내는지(본문에 `#`
주석을 되돌린 음성 대조 포함).

## See also

*   [CONTRACT-runtime.md](/infra/terraform/CONTRACT-runtime.md) — 런타임 출력
    계약 · 일꾼 작업 역할 대상
*   [prod/platform](/infra/terraform/prod/platform/README.md) — 클러스터 ·
    네임스페이스 · clip · auth
*   [단계 직전 점검](/infra/terraform/runbooks/stage-precheck.md)
*   [prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md)
