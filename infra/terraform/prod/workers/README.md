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
| network | `vpc_id` · `private_subnet_ids` · `s3_prefix_list_id` |
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
    (범위는 아래 「긴급 정지와 상태 어긋남」).
1.  **감시를 먼저 띄웁니다**(아래 「첫 소비 감시」). 띄웠다는 것을 알린 뒤
    kty 가 `terraform apply <plan 파일>` 을 돌립니다.
1.  **첫 소비 판정(2분)** · 정지 신호면 긴급 정지.
1.  **기동 신호 · 관문 ②:** 서비스마다 실행 수 1 · 배포 하나 COMPLETED ·
    `*.worker_started`(render · thumbnail · upload)를 보고, 이 루트와 platform
    을 다시 plan 해 둘 다 종료 0 인지 봅니다. 이 루트가 0 이 아니면 ECS 가
    태스크 정의를 다르게 정규화한 것이고, platform 이 0 이 아니면 이 루트가 단
    인그레스가 platform 에 드리프트로 보이는 것입니다 — 둘 다 kty 에게 넘깁니다.
1.  **썸네일 대기분 소진 · 시험 잡:** 사진 DLQ 깊이가 apply 직전 기준(0)
    그대로 · `thumbnail.report_rejected kind=card` · `kind=clip` 0 을 세고,
    렌더 짧은 컷 하나 · 업로드 비공개 하나(채널은 시험 직전 kty 가 정함)로
    끝까지 확인합니다.

provider 의 `default_tags`(`TerraformRoot = prod/workers`)는 태그를 받는
자원에 붙습니다. mock 시험은 태그를 보지 못하므로 plan 의 `tags_all` 로
확인합니다.

### plan 요지

첫 apply 의 요지는 **create 29줄**이고 그 밖의 동작은 없습니다.

| 주소 | 수 |
|---|---|
| `module.render` — 서비스 · 태스크 정의 · Cloud Map 서비스 · SG · 이그레스 셋 · 실행 · 작업 역할과 정책 넷 · 로그 그룹 | 12 |
| `module.upload` — 같은 꼴 · 이그레스 넷 | 13 |
| `aws_vpc_security_group_ingress_rule.clip_from_worker` 둘 · `auth_from_upload` | 3 |
| `terraform_data.runtime_contract` | 1 |

요지는 [prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md) 의
`plan_summary` 와 같은 jq 로 뽑습니다. 이 루트 밖의 주소가 한 줄이라도
나오면 멈춥니다. 런타임 상태가 일꾼 키를 아직 내지 않으면 계약 검사가
멈추는데, 그것은 의도된 멈춤입니다.

### apply 직전 읽기

넷 다 읽기라 승인이 필요 없습니다. 하나라도 어긋나면 멈추고 kty 에게
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

### 첫 소비 감시

일꾼은 기동하자마자 쌓인 사진 주문부터 처리합니다. 그래서 감시는 apply 보다
먼저 붙고, apply 뒤의 plan 둘보다 먼저입니다. 모든 감시 명령에
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
*   공통: `*.receive_failed` 의 `AccessDenied` 가 1분 넘게 이어짐(처음 몇
    초는 IAM 전파 지연일 수 있음) · `status=401` · 사진 DLQ 깊이가 apply
    직전 기준보다 커짐.
*   비밀 유출: `X-Internal-Token` · `Bearer ` · `upload_id=` 꼴이 로그에
    보이면 정지와 함께 토큰 회전 판단을 kty 에게 넘깁니다.

정지 대상은 사진 · 렌더 신호면 render, 업로드 신호면 upload, `status=401` ·
비밀 유출 · 사진 DLQ 는 둘 다입니다. 첫 실패 신호에서 **2분 안**(늦어도
240초)에 정지합니다 — 실패한 메시지는 약 120초마다 다시 받히고 세 번째
실패 뒤 DLQ 로 갑니다. 보고 4xx 의 즉시 삭제는 시한이 없어 계약 검사 3 과
회전 창 읽기가 주된 방어이고, 감시는 피해를 첫 몇 건으로 묶는 몫입니다.

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

정지 대상은 첫 소비 정지 신호와 같습니다 — 사진 · 렌더 조건(렌더 DLQ
포함)이면 render, 업로드 조건(업로드 DLQ 포함)이면 upload, 사진 DLQ · 비밀
유출은 둘 다입니다.

### 긴급 정지와 상태 어긋남

```
aws ecs update-service --region ap-northeast-2 --cluster pokeclip-prod \
  --service pokeclip-prod-<render|upload> --desired-count 0 \
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
*   토큰을 회전할 때는 일꾼을 멈춘 채 회전합니다
    ([runtime-1a 런북](/infra/terraform/runbooks/runtime-1a.md)) — clip 의
    롤링이 옛 값 · 새 값 태스크를 겹쳐 두기 때문입니다.

## 알려진 한계

*   실행 · 작업 역할의 신뢰 정책은 혼동된 대리인 조건으로 `aws:SourceAccount`
    하나만 둡니다(모듈 공통). `aws:SourceArn`(이 클러스터의 ECS 자원)으로 더
    좁히는 것은 platform 다섯 서비스의 역할도 함께 바뀌는 모듈 변경이라
    platform apply 가 들어, 이 PR 에 넣지 않고 후속 후보로 둡니다.

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

자원 수(create 29)는 `terraform test` 가 셀 수 없어 위 「plan 요지」 가 셉니다.

## See also

*   [CONTRACT-runtime.md](/infra/terraform/CONTRACT-runtime.md) — 런타임 출력
    계약 · 일꾼 작업 역할 대상
*   [prod/platform](/infra/terraform/prod/platform/README.md) — 클러스터 ·
    네임스페이스 · clip · auth
*   [단계 직전 점검](/infra/terraform/runbooks/stage-precheck.md)
*   [prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md)
