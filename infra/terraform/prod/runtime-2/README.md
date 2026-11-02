# prod/runtime-2

prod 런타임 트랙 2단 루트입니다(트리 표의 ⑪). edge 뒤에 apply 하고, 지금은
rewind 버킷 정책 하나만 만듭니다 — media 배포가 OAC 로 되감기(`/dvr/*`) ·
다시보기(`/vod/*`) 객체를 읽게 하는 허용문입니다. 출력 이름과 소유 경계의
정본은 [런타임 트랙 인터페이스 계약](../../CONTRACT-runtime.md)입니다.

**OAC(Origin Access Control)**: CloudFront 가 S3 원본 요청에 SigV4 서명을
붙이는 방식입니다. 버킷은 공개하지 않고, 버킷 정책이 그 배포의 서명 요청만
허락합니다.

## 만드는 것

`aws_s3_bucket_policy.rewind` 하나입니다(`rewind_bucket_policy.tf`). 문장은
하나입니다.

| 칸 | 값 |
|---|---|
| Sid | `AllowMediaCloudFrontOacRead` |
| Effect · 주체 | Allow · 서비스 `cloudfront.amazonaws.com` |
| 동작 | `s3:GetObject` 만 |
| 자원 | `<rewind 버킷 ARN>/dvr/*` · `<rewind 버킷 ARN>/vod/*` |
| 조건 | `StringEquals` `AWS:SourceArn` = edge 출력 `media_distribution_arn` |

*   **자원 접두 둘**: media 배포의 `/dvr/*` · `/vod/*` 비헤이비어는 오리진
    경로 · 함수 없이 요청 경로를 그대로 객체 키로 씁니다
    (`modules/cdn_media/main.tf`). 사이드카가 올리는 키도 `dvr/<스트림>/…`
    (목록 · `seg/` 조각 · `init/` — `media/internal/playback/key.go`)와
    `vod/<스트림>/…`(장부의 VOD 목록 키)입니다. 원본 조각
    `streams/*` 는 CDN 으로 열지 않습니다 — 편집기 미리 듣기는 clip 이
    서명한 주소로 S3 에 직접 갑니다.
*   **`s3:ListBucket` 없음**: 없는 키는 404 가 아니라 403 입니다(apex 와
    같음).
*   **Deny 없음**: clip · 사이드카 작업 역할을 막지 않아야 합니다(계약
    「작업 역할 대상」). 공개 차단 넷은 1단이 켜 두었고, 이 허용문은 고정
    `AWS:SourceArn` 조건이라 공개 정책으로 보지 않습니다.

## 기존 정책과의 관계

버킷 정책은 버킷당 하나입니다. `aws_s3_bucket_policy` 는 버킷의 정책 전체를
덮어쓰므로, 두 곳에서 같은 버킷의 정책을 두면 apply 마다 서로를 지웁니다.

*   이 PR 앞에는 rewind 버킷에 정책이 없습니다. 1단
    (`prod/runtime/rewind_bucket.tf` 「두지 않는 것」)과 다른 루트 어디에도
    rewind 의 `aws_s3_bucket_policy` 가 없고, 1단의 `aws_s3_bucket` 도
    `policy` 인자를 쓰지 않습니다(1단 시험 `keeps_versioning_and_policy_off_the_bucket`).
    그래서 보존할 문장이 없습니다.
*   이 버킷에 문장을 더할 때(TLS 거부 K8 등)는 이 자원의 `Statement` 에
    더하고 시험의 문장 수 단언을 함께 고칩니다. 다른 루트에 정책 자원을 새로
    두지 않습니다.

## 왜 별도 루트인가

계약이 2단을 「edge 뒤에 적용하는 런타임 루트」 로 정했습니다(계약 「용어」).
상태 읽기는 한 방향으로만 흐릅니다.

```text
prod/runtime(1단) ──rewind_bucket_regional_domain_name──▶ prod/edge
prod/runtime(1단) ──rewind_bucket_name · rewind_bucket_arn──▶ prod/runtime-2
prod/edge ──media_distribution_arn──▶ prod/runtime-2
```

`terraform_remote_state` 는 apply 순서만 요구하고 Terraform 그래프의 간선을
만들지 않습니다. 그래도 이 정책을 1단에 두면 1단이 edge 를, edge 가 1단을
읽게 됩니다. 그러면 첫 apply(edge 상태가 없을 때) 1단 plan 이 멈추고, 철거도
어느 쪽을 먼저 내려도 다른 쪽 destroy plan 이 출력 이름으로 실패합니다.
별도 루트면 1단 → edge → 2단 의 순서 하나로 끝납니다.

## 앞 루트 출력

| 루트 | 출력 | 쓰는 곳 |
|---|---|---|
| runtime(1단) | `rewind_bucket_name` · `rewind_bucket_arn` | 정책을 붙일 버킷 · 허용문 자원 |
| edge | `media_distribution_arn` | 허용문 조건 `AWS:SourceArn` |

키 기본값은 `prod/runtime/terraform.tfstate` · `prod/edge/terraform.tfstate`
입니다(`state_keys`). `terraform_data.remote_contract` 의 precondition 이
꼴을 봅니다 — 버킷 이름(S3 이름 규칙) · 그 이름의 버킷 ARN
(`arn:aws:s3:::<이름>` — 객체 경로 없음) · CloudFront 배포 ARN
(`arn:aws:cloudfront::<계정>:distribution/<id>`). 출력이 빠졌거나 꼴이 다르면
plan 이 멈추고, 정책 자원은 그 precondition 을 기다립니다(`depends_on`).

이 루트의 출력은 없습니다. 다른 루트는 이 루트를 읽지 않습니다.

## 적용(O7f 의 6)

[엣지 시험 런북](../../runbooks/edge-tests.md) 「O7f 순서」 의 6 입니다.
edge apply(1)가 끝나 `media_distribution_arn` 이 상태에 있어야 합니다. Media ·
KVS 값과는 무관해 A1 · A2 멈춤 자리에서도 합니다.

1.  **직전 점검**: O7f 의 선행 0(공통 묶음 `O7f` 의 SCP 재판정 · 2-3 판정 ·
    이벤트 기록 — [단계 직전 점검 런북](../../runbooks/stage-precheck.md))이
    덮습니다. 따로 묶음을 두지 않습니다. 이 apply 가 부르는 동작은
    `s3:PutBucketPolicy` · `s3:GetBucketPolicy`(서울)와 상태 버킷 읽기 ·
    쓰기 · 잠금(`s3:ListBucket` · `GetObject` · `PutObject` ·
    `DeleteObject`)이고, 모두 `O7f` 묶음에 있습니다. 2-3 판정 대상에는 이
    루트의 상태 객체(`state-object-runtime-2` — 첫 단계 O7f)가 있습니다.
1.  **init**: `backend.hcl.example` 을 `backend.hcl` 로 복사해 상태 버킷을
    넣습니다(추적하지 않음).

    ```shell
    terraform -chdir=infra/terraform/prod/runtime-2 init -backend-config=backend.hcl
    terraform -chdir=infra/terraform/prod/runtime-2 plan -out=runtime-2.plan
    ```
1.  **plan 기대값**(kty 승인 지점): `Plan: 2 to add, 0 to change, 0 to
    destroy.` — `terraform_data.remote_contract` 와
    `aws_s3_bucket_policy.rewind` 의 생성만입니다. 정책 본문은 위 표의 문장
    하나이고, `bucket` 이 1단의 rewind 버킷 이름입니다. 변경 · 교체 · 삭제가
    하나라도 보이거나 precondition 오류가 나면 apply 하지 않고 kty 에게
    보고합니다. plan 출력에는 계정 ID 가 든 배포 ARN 이 있습니다 — 보고에는
    요약 줄과 자원 주소만 옮깁니다.
1.  **apply**: `terraform -chdir=infra/terraform/prod/runtime-2 apply
    runtime-2.plan`. 끝나면 plan 파일을 지웁니다(계정 ID 가 든 ARN).
1.  **1단 무변경 확인**: 다음 1단 plan(`prod/runtime`)에서
    `aws_s3_bucket.rewind` 의 변경이 0 이어야 합니다. 변경이 보이면 1단이
    이 정책을 지우려는 것이니 apply 하지 않고 멈춥니다.
1.  **동작 확인**: 재생 경로 확인은 엣지 시험 런북 「런타임 뒤 시험」
    (O7f 의 12)이 합니다 — `/dvr/*` · `/vod/*` 는 서명 쿠키가 있어야 해
    (키 그룹) 이 apply 직후 단독으로 보지 않습니다.

### 2-3 판정에 미치는 영향

2-3 판정은 rewind 버킷 정책을 읽습니다(`prod_targets.tsv` 의 rewind 행 ·
정책 읽기 `s3`). 이 apply 뒤에는 정책에 Allow 문장이 생겨, 갈래 B 실행에서
역할 주체 × rewind 행이 `RESOURCE_POLICY_UNSIMULATED`(판정 불가)가 됩니다.
apex 버킷(OAC 허용문)과 같은 갈래입니다. 허용 주체는 CloudFront 서비스
하나라 dev 주체에 주는 권한이 아니지만, 판정 불가의 진행 여부는 평소처럼
kty 가 `approvals.tsv` 로 정합니다(단계 직전 점검 런북 「2-3 판정의 결과와
갈래」). 갈래 A 에서는 정책을 붙여 묻습니다.

## 내리기

이 루트는 edge 를 읽으므로 **edge 보다 먼저** 내립니다 —
[prod 철거 런북](../../runbooks/prod-teardown.md) 「지키는 순서」 2 가 이
루트를 먼저 내릴 다른 소비 루트로 셉니다(그 런북은 이 루트를 내리지
않습니다). `terraform plan -destroy` 의 기대값은 `aws_s3_bucket_policy.rewind`
· `terraform_data.remote_contract` 삭제 둘이고, 내린 뒤 `/dvr/*` ·
`/vod/*` 는 버킷에서 403 입니다. 삭제는 `s3:DeleteBucketPolicy` 를 부르고,
단계 직전 점검에는 철거 단계 묶음이 아직 없습니다(철거 런북 「시작 전」).

## 검증

```shell
terraform -chdir=infra/terraform/prod/runtime-2 init -backend=false -lockfile=readonly
terraform -chdir=infra/terraform/prod/runtime-2 validate
terraform -chdir=infra/terraform/prod/runtime-2 test
```

시험(`tests/runtime_2.tftest.hcl`)은 mock provider · `override_data` 로 AWS
를 부르지 않습니다. 허용문 전체 · 문장 수 · Deny 부재 · 조건이 edge 출력을
따름 · 앞 루트 출력 꼴 위반 여섯 가지를 단언합니다.
