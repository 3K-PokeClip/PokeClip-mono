# prod/records

prod 운영 이름의 별칭 레코드를 만드는 루트입니다(설계 3-1 의 ⑫ · 계획
M11). 8단계(O8)에서 이름마다 하나씩 엽니다.

## 레코드

*   공개 존 `pokeclip.com` 은 `nonprod/dns-legacy` 가 소유합니다. 이 루트는
    존을 이름으로 조회합니다(`data "aws_route53_zone"` · 공개 존). 같은 이름의
    공개 존이 둘이면 조회가 실패합니다.
*   레코드는 그 존 안의 별도 `aws_route53_record` 라 dns-legacy 의 dev ·
    media-dev 레코드를 건드리지 않습니다.
*   모두 별칭(alias)이고 `evaluate_target_health = false` 입니다.
    `allow_overwrite` 는 쓰지 않습니다 — 공유 존의 기존 레코드를 덮는 길이
    되기 때문입니다.
*   provider 는 서울 하나입니다. Route 53 은 전역 서비스라 리전 별칭이 필요
    없습니다.

| 이름(`enabled_records`) | 레코드 | 별칭 대상 |
|---|---|---|
| `apex` | `pokeclip.com` A · AAAA | apex 배포(edge) |
| `www` | `www.pokeclip.com` A · AAAA | apex 배포(edge) — www → apex 301 은 배포의 함수가 함 |
| `app` | `app.pokeclip.com` A | 공개 ALB(platform) |
| `api` | `api.pokeclip.com` A | 공개 ALB(platform) |
| `media` | `media.pokeclip.com` A | media 배포(edge) |
| `ingest` | `ingest.pokeclip.com` A | SRT NLB(platform) — 열어도 NLB 는 팀 IP 한정 |

`enabled_records` 의 기본값은 `[]`(레코드 0)입니다. 머지 뒤 실수 apply 로
공개 이름이 열리지 않게 하려는 것입니다. 표 밖의 이름이나 같은 이름을 두 번
적으면 변수 검증이 멈춥니다.

## 앞 루트 출력

`terraform_remote_state` 로 읽습니다. 키 기본값은
`prod/platform/terraform.tfstate` · `prod/edge/terraform.tfstate` 입니다.

| 루트 | 출력 |
|---|---|
| platform | `public_alb_dns_name` · `public_alb_zone_id` · `nlb_dns_name` · `nlb_zone_id` |
| edge | `apex_distribution_domain` · `apex_distribution_zone_id` · `media_distribution_domain` · `media_distribution_zone_id` |

`terraform_data.remote_contract` 의 precondition 이 이 여덟 값의 꼴(ALB ·
NLB DNS 이름 · CloudFront 도메인 · 존 id `Z…`)을 봅니다. 출력이 빠졌거나 꼴이
다르면 레코드보다 먼저 멈춥니다. 레코드가 0 인 기본값에서도 봅니다.

## 적용 — 이름마다 apply(O8)

여러 이름을 한 번에 열지 않습니다. 한 번에 한 이름씩 `enabled_records` 에
더하고, 그때마다 아래를 거칩니다(절차 정본은 계획 M11 O8).

1.  선행 0 — 단계 직전 점검(SCP 재판정 `8` 묶음 · 2-3 판정 · 이벤트 기록).
1.  선행 — 7단계 시험 통과(T11 엣지 404 · T2b 기본 거절 · 경로 우회
    시험(T2b · T2) 확인 필수 —
    [엣지 시험 런북](../../runbooks/edge-tests.md) 「경로 우회 시험」)와 설계
    12절 1 ① · 2 의 답, 또는 답 없이 진행한다는 kty 의 수용 기록. 경로 우회
    시험 통과는 개통 조건이라 실패면 O8 을 중단합니다(공개 이름이 없어도
    `--resolve` 로 배포에 닿으므로 DNS 미등록은 노출을 막지 못합니다). 여기가
    실사용자 데이터가 리스 계정에 쌓이기 시작하는 지점입니다.
1.  고유 확인 — 공유 존 레코드 목록(읽기)에서 그 이름 · 유형이 이미 있는지
    봅니다. dns-legacy 가 import 한 레코드와 겹치면 멈추고, 어느 루트가
    소유할지 kty 가 정합니다.
1.  `media` · `app` 은 승인 요청에 「T10 보류 중이면 prod 에서 VOD · 편집기
    재생이 동작하지 않음」 · 「제어면 서버(I1-6) 전이면 종료 신호 · rewind
    API 가 404」 를 적습니다(T11 202 · T10 보류 허용 갈래).
1.  plan 이 「추가 = 그 이름의 레코드 수(apex · www 2, 나머지 1) · 그 밖 0」
    인지 확인한 뒤 kty 승인을 받아 apply 합니다. 단 이 루트의 첫 apply(처음
    여는 이름)는 `terraform_data.remote_contract` 가 함께 생성되어 「추가 =
    그 이름의 레코드 수 + 1 · 그 밖 0」 입니다. 그 뒤 이름부터는 레코드 수만
    더해집니다.
1.  개통 뒤 관찰 — apply 직후와 24시간 뒤에 ① 공용 리졸버 셋 이상에서 기대
    대상으로 풀림 ② 그 이름을 쓰는 7단계 시험을 `--resolve` 없이 다시 돌려
    같은 판정 ③ 공유 존의 dev · media-dev 해석이 개통 전 기록과 같음을
    봅니다.
1.  하나라도 어긋나면 그 이름을 `enabled_records` 에서 빼는 plan(「삭제 = 그
    이름의 레코드 수 · 그 밖 0」)을 SCP 재판정(`8` 묶음)만 거쳐 kty 승인 뒤
    apply 합니다. 노출을 줄이는 되돌림이라 2-3 판정은 걸지 않습니다.

값은 추적하지 않는 `terraform.tfvars` 에 둡니다.

```hcl
enabled_records = ["apex"]
```

백엔드는 `backend.hcl.example` 을 `backend.hcl` 로 복사하고 `bucket`
자리표시자 `<STATE_BUCKET_PROD>` 를 `pokeclip-tfstate-prod-apne2` 로 고친 뒤
`init -backend-config=backend.hcl` 로 엽니다(운영 단계).

## 출력

없습니다. 이 루트를 읽는 소비 루트가 없습니다.
