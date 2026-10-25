# prod/edge

prod 의 CloudFront 엣지를 만드는 루트입니다(설계 3-1 의 ⑩ · 계획 M10 c8).
기본 provider 는 us-east-1 이고, 서울 자원은 별칭 `aws.apne2` 로 만듭니다.
상태 버킷과 앞 루트 상태는 서울입니다.

## 만드는 것

*   **CLOUDFRONT WAF**(`pokeclip-prod-cloudfront` · `modules/waf_acl`): apex ·
    media 두 배포가 같이 씁니다. 관리 그룹은 platform 공개 ALB WAF 와 같은
    둘(KnownBadInputs · IpReputation)이고, 속도 제한은 `waf_rate_limit`(기본
    20000 — LL-HLS 시청자가 같은 IP 뒤에 여럿)입니다. 차단된 요청만 로그
    그룹 `aws-waf-logs-pokeclip-prod-cloudfront`(보존 30일 — platform 과 같음)에
    남깁니다. `authorization` · `cookie` · 내부 토큰 헤더 둘
    (`x-internal-token` · `x-stream-key-resolve-token`)은 웹 ACL 데이터
    보호로 요청 샘플 · 로그 모두에서 `REDACTED` 로 바꿉니다. 앞의 둘에는 로그
    가림(`redacted_fields`)도 겹쳐 둡니다. 이 겹침이 보장하는 범위는 그
    헤더를 단일 헤더로 보는 규칙까지라, 헤더 전체를 보는 규칙의 일치 세부와
    요청 샘플은 데이터 보호에만 기댑니다. 질의 인자는 가리지 않습니다 —
    자격 증명이 실리는 `?code=`(OAuth 콜백) · `?token=`(프로필 사진)은 app
    호스트라 공개 ALB WAF(platform)가 가립니다.
*   **apex 배포**(`modules/cdn_static_site`): `pokeclip.com` · `www.pokeclip.com`
    별칭, 서울 S3 버킷(접두 `pokeclip-prod-apex-`)을 OAC 로만 읽습니다.
*   **media 배포**(`modules/cdn_media`): `media.pokeclip.com` 별칭, KVS 그릇 ·
    함수 둘 · 키 그룹 · VPC origin(내부 ALB) · rewind 버킷 오리진(OAC)을
    포함합니다.
*   **표준 로그 로그 그룹**(`pokeclip-prod-media-access` · us-east-1): media
    배포의 표준 로그(v2)를 JSON 으로 받습니다. 보존은 `log_retention_days`
    (기본 7)입니다.
*   **함수 로그 그룹 셋**(`/aws/cloudfront/function/<함수 이름>` ·
    us-east-1): live-auth · deny-default · apex-request 의 `console.log` 를
    받습니다. 보존은 WAF 차단 로그와 같은 30일입니다(PR 387 보안 L2).
*   **내부 ALB 인그레스 ← VPC origin**(서울): VPC origin 이 생긴 뒤
    CloudFront 가 그 VPC 에 만드는 SG `CloudFront-VPCOrigins-Service-SG` 를
    이름과 network `vpc_id` 로 조회하고, platform 내부 ALB SG 에 그 SG 출처
    TCP 443 규칙 하나를 둡니다.

**VPC origin**: CloudFront 가 VPC 안의 사설 자원(여기서는 내부 ALB)을 공개
주소 없이 원본으로 쓰는 기능입니다.

이 루트에 **없는** 것은 다음과 같습니다.

| 자원 | 루트 |
|---|---|
| apex 업로드 역할 · 정책 | 후속 승인 지점(설계 8절 — 40차). 지금 업로드는 kty IAM 사용자 |
| rewind 버킷 정책(OAC 허용문) | 런타임 트랙 2단(출력 `media_distribution_arn` 을 씀) |
| KVS 값(`cdn-secret`) | `scripts/kvs-put-cdn-secret.sh`(O7f 멈춤 자리 ②) |
| 운영 이름 레코드 | `prod/records` |

되감기 버킷의 CORS 규칙은 edge 가 두지 않습니다 — `/dvr/*` · `/vod/*` 의
CORS 는 응답 헤더 정책이 붙이고(플레이어의 사전 요청(OPTIONS)이 없을 때 —
G14 · T10 에서 OPTIONS 0건 확인), 버킷 자체의 규칙은 S3 직접 요청(편집기 미리
듣기)용으로 런타임이 둡니다([런타임 트랙 계약](../../CONTRACT-runtime.md)
「rewind 버킷 CORS」).

## 앞 루트 출력

`terraform_remote_state` 로 읽습니다. 키 기본값은
`prod/<루트>/terraform.tfstate` 입니다(`state_keys`).

| 루트 | 출력 | 쓰는 곳 |
|---|---|---|
| network | `vpc_id` | VPC origin 서비스 SG 조회 |
| certs | `cert_arn_use1` | 두 배포의 시청자 인증서 |
| secrets | `public_key_id` | media 키 그룹 |
| platform | `internal_alb_arn` · `internal_alb_dns_name` · `internal_alb_sg_id` | VPC origin · media-alb 원본 도메인 · 인그레스 규칙 |
| runtime | `rewind_bucket_regional_domain_name` | rewind-s3 오리진 |

`terraform_data.remote_contract` 의 precondition 이 이 값들의 꼴(VPC id ·
us-east-1 ACM ARN · 공개키 id `K…` · ALB ARN · `internal-` 로 시작하는 내부
ALB DNS · SG id · S3 리전 도메인)을 봅니다. 출력이 빠졌거나 꼴이 다르면
plan 이 멈춥니다.

## 출력

이름은 계약입니다 — 바꾸지 않습니다.

| 출력 | 소비자 |
|---|---|
| `apex_bucket_name` · `apex_distribution_id` | kty 로컬 랜딩 업로드(sync · 무효화) |
| `apex_distribution_domain` · `apex_distribution_zone_id` | records(apex · www) |
| `media_distribution_domain` · `media_distribution_zone_id` | records(media) |
| `media_distribution_arn` | 런타임 트랙 2단(rewind 버킷 OAC 허용문) |
| `cdn_kvs_arn` | `scripts/kvs-put-cdn-secret.sh --kvs-arn` |

## 적용 순서(O7f)

edge apply 는 [엣지 비밀 런북](../../runbooks/edge-secrets.md) 「초기화 순서」
의 8 입니다. 그 앞(network · 런타임 1단 · certs · secrets · platform · media)이
끝나 있어야 합니다.

1.  선행 0 — 단계 직전 점검(SCP 재판정 공통 묶음 `O7f` · 2-3 판정 · 이벤트
    기록). 공통 묶음이 거부 · 변경이면 시작하지 않습니다.
1.  edge apply.
1.  T6b(KVS 값 입력 전) — 쿠키 없는 `/live` 요청이 503 + 고정 문자열인지
    봅니다.
1.  KVS 값 입력(멈춤 자리 ② — 런북 「O7f」).
1.  엣지만 시험 · 랜딩 업로드 · 런타임 2단 순서는 계획 M10 O7f 를 따릅니다.

함수가 이 루트의 함수 로그 그룹보다 먼저 로그를 내면(apply 가 그룹 전에
멈춘 채 T6b 를 했을 때 등) AWS 가 그룹을 먼저 만들어 다음 apply 가 이미
있음(`ResourceAlreadyExistsException`)으로 실패합니다. 그 그룹을 import 로
받고 다시 apply 합니다(키는 `live_auth` · `deny_default` · `apex_request`).

```shell
terraform import 'aws_cloudwatch_log_group.cloudfront_function["live_auth"]' \
  /aws/cloudfront/function/pokeclip-prod-live-auth
```

## 적용 뒤 할 일

*   **hlsSegmentCount 900 → 7**(ADR-102): `/dvr` 비헤이비어 · 되감기 버킷
    오리진이 생기고, 2번 플레이어가 `/dvr` 로 진입한 뒤에 줄입니다.

## VPC origin TLS 전제

media-alb 원본 도메인은 내부 ALB DNS 이름이라 내부 ALB 인증서
(`*.pokeclip.com`)와 맞지 않습니다. 모든 media-alb 비헤이비어가 Host =
`media.pokeclip.com` 을 원본에 넘기므로, CloudFront 는 그 Host 로 인증서
이름을 맞춥니다(AWS 문서 using-https-cloudfront-to-custom-origin ·
cnames-and-https-requirements). 이 규칙이 VPC origin 경로에도 그대로
적용되는지는 첫 edge apply 뒤 원본까지 가는 첫 시험(T6b 의 end-signal ·
T11 404)에서 판정합니다. 502 가 나면 원인 1순위입니다.

502 가 나도 원본 프로토콜을 http-only 로 내리지 않습니다 — 원본 요청의
`Authorization: Bearer` 가 VPC 안에서 평문이 됩니다(kty 결정 없이 금지).

## 표준 로그 끄기

Budgets 경보 때 1차 대응은 `enable_standard_logs = false` 로 edge apply 하는
것입니다(설계 10절 · 36차). 전달 자원만 지우고 로그 그룹은 남깁니다 — plan
은 전달 자원 삭제만이고 그 밖 0 입니다(VPC origin 서비스 SG 조회가 VPC origin
하나만 기다려, 내부 ALB 인그레스 규칙에 변경 예고가 끼지 않습니다). 끈 동안은
설계 7절의 `/live` 302 · 401 신호를 쓸 수 없습니다.

모듈에 넘기는 로그 그룹 ARN 은 자원의 `arn` 속성이 아니라 계정 · 리전 ·
이름으로 짓습니다. 로그 그룹을 처음 만드는 plan 에서 `arn` 은 정해지지 않고,
모듈의 전달 자원 수가 그 값의 null 여부에 기대기 때문입니다.

## 백엔드

`backend.hcl.example` 을 `backend.hcl` 로 복사하고 `bucket` 자리표시자
`<STATE_BUCKET_PROD>` 를 `pokeclip-tfstate-prod-apne2` 로 고친 뒤
`init -backend-config=backend.hcl` 로 엽니다(운영 단계). 이 루트의 변수는
모두 기본값이 있어 `terraform.tfvars` 가 필요 없습니다.
