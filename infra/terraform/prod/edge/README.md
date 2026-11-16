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
    남깁니다. `authorization` · `cookie` · 내부 토큰 헤더 셋
    (`x-internal-token` · `x-stream-key-resolve-token` ·
    `x-expired-broadcasts-token` — POK-551)은 웹 ACL 데이터 보호로 요청
    샘플 · 로그 모두에서 `REDACTED` 로 바꿉니다. 앞의 둘에는 로그
    가림(`redacted_fields`)도 겹쳐 둡니다. 이 겹침이 보장하는 범위는 그
    헤더를 단일 헤더로 보는 규칙까지라, 헤더 전체를 보는 규칙의 일치 세부와
    요청 샘플은 데이터 보호에만 기댑니다. 질의 인자와 `referer` 는 가리지
    않습니다 — 자격 증명이 실리는 `?code=`(OAuth 콜백) · `?token=`(프로필
    사진)과 `?code=` 가 든 Referer 는 app 호스트로만 가므로 공개 ALB
    WAF(platform)가 가립니다.
*   **apex 배포**(`modules/cdn_static_site`): `pokeclip.com` · `www.pokeclip.com`
    별칭, 서울 S3 버킷(접두 `pokeclip-prod-apex-`)을 OAC 로만 읽습니다.
*   **media 배포**(`modules/cdn_media`): `media.pokeclip.com` 별칭, KVS 그릇 ·
    함수 셋(live-auth · deny-default · /dvr 하한 dvr-floor) · 키 그룹 · VPC
    origin(내부 ALB) · rewind 버킷 오리진(OAC)을 포함합니다. dvr-floor 는
    스위치가 켜졌을 때만 `/dvr/*` 에 붙습니다(아래 「/dvr 하한(POK-525)」).
*   **표준 로그 로그 그룹**(`pokeclip-prod-media-access` · us-east-1): media
    배포의 표준 로그(v2)를 JSON 으로 받습니다. 보존은 `log_retention_days`
    (기본 7)입니다.
*   **함수 로그 그룹 넷**(`/aws/cloudfront/function/<함수 이름>` ·
    us-east-1): live-auth · deny-default · dvr-floor 의 `console.log` 를
    받습니다.
    apex-request 는 `console.log` 가 없어 그룹이 비어 있습니다 — 나중에
    로그를 넣을 때 보존 없는 그룹이 생기지 않게 미리 만드는 그릇입니다. 보존은
    WAF 차단 로그와 같은 30일입니다(PR 387 보안 L2).
*   **/dvr 하한 경보 셋 · 알림 주제**(us-east-1 · POK-525): dvr-floor 함수
    로그를 세는 지표 필터 둘과 경보 셋, 알림 주제
    `pokeclip-prod-edge-alarms` 입니다. 이메일 구독은 저장소 밖입니다(아래
    「경보(POK-525)」).
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
| rewind 버킷 정책(OAC 허용문) | 런타임 트랙 2단 [`prod/runtime-2`](../runtime-2/README.md)(출력 `media_distribution_arn` 을 씀 — 이 루트 apply 뒤) |
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
| runtime | `edge_bounds_kvs_arn` | dvr-floor 함수의 KVS 연결(POK-525) |

`terraform_data.remote_contract` 의 precondition 이 이 값들의 꼴(VPC id ·
us-east-1 ACM ARN · 공개키 id `K…` · ALB ARN · `internal-` 로 시작하는 내부
ALB DNS · SG id · S3 리전 도메인 · CloudFront KVS ARN)을 봅니다. 출력이
빠졌거나 꼴이 다르면 plan 이 멈춥니다. `edge_bounds_kvs_arn` 은 prod/runtime
을 POK-525 판으로 다시 apply 해야 생깁니다 — 그 전에는 이 검사의 문구로
멈춥니다.

## 출력

이름은 계약입니다 — 바꾸지 않습니다.

| 출력 | 소비자 |
|---|---|
| `apex_bucket_name` · `apex_distribution_id` | kty 로컬 랜딩 업로드(sync · 무효화) |
| `apex_distribution_domain` · `apex_distribution_zone_id` | records(apex · www) |
| `media_distribution_domain` · `media_distribution_zone_id` | records(media) |
| `media_distribution_arn` | 런타임 트랙 2단 `prod/runtime-2`(rewind 버킷 OAC 허용문의 `AWS:SourceArn`) |
| `cdn_kvs_arn` | `scripts/kvs-put-cdn-secret.sh --kvs-arn` |
| `edge_alarm_topic_arn` | kty 의 이메일 구독(`aws sns subscribe` — 「경보(POK-525)」) |

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
    런타임 2단 apply 는 [prod/runtime-2](../runtime-2/README.md) 「적용
    (O7f 의 6)」 입니다. 그 apply 전에는 `/dvr/*` · `/vod/*` 가 버킷에서
    403 입니다(OAC 허용문 없음). 뒤에 media 배포가 교체돼
    `media_distribution_arn` 이 바뀌면 런타임 2단을 다시 plan · apply
    합니다. 그 전까지 허용문이 옛 ARN 을 가리켜, 서명 쿠키가 있어도
    `/dvr/*` · `/vod/*` 가 403 입니다.

함수가 이 루트의 함수 로그 그룹보다 먼저 로그를 내면(apply 가 그룹 전에
멈춘 채 T6b 를 했을 때 등) AWS 가 그룹을 먼저 만들어 다음 apply 가 이미
있음(`ResourceAlreadyExistsException`)으로 실패합니다. 그 그룹을 import 로
받고 다시 apply 합니다(키는 `live_auth` · `deny_default` · `apex_request` ·
`dvr_floor`).

```shell
terraform import 'aws_cloudwatch_log_group.cloudfront_function["live_auth"]' \
  /aws/cloudfront/function/pokeclip-prod-live-auth
```

## 적용 뒤 할 일

*   **hlsSegmentCount 900 → 7**(ADR-102): `/dvr` 비헤이비어 · 되감기 버킷
    오리진이 생기고, 2번 플레이어가 `/dvr` 로 진입한 뒤에 줄입니다. 템플릿
    값은 라이브 창 28초 설정 소PR 이 바꿨고, prod 반영은 상자 판 7(그 값을
    실은 설치 묶음으로 바꾸는 상자 반영 — 상자 MediaMTX 설정의
    `hlsSegmentCount` 가 7 이 됨)입니다.
    판 7 은 PDT c0 교체(media-swap 런북 「PDT 배포 순서」 3)와 한 창에서
    합니다(kty 2026-11-13). 머지 커밋으로 빌드한 묶음에는 PDT 생산자의 기동
    전 검사(`timing-preflight`)가 함께 실려, 같은 절의 1(운영 SQL) ·
    2(소비자 선배포 확인)와 검사 핀 `image_digests.timing_guard` 가 먼저입니다.
    그래서 전제(prod 웹이 `/dvr` 전환 판 · `/dvr` 실제 서빙)와 1 · 2 · 검사
    핀을 확인하고 c0 창에서 머지한 뒤, 그 머지 커밋으로 빌드한 묶음과 같은
    커밋 체크아웃의 plan 으로 판 7 과 c0 를 한 번에 교체합니다. 청소기 보관
    유예 23시간(V1 임시)은 c0 에서 그대로 둡니다 — 상자 녹화(`/playback`)를
    읽는 웹 경로(편집기 = POK-473 · 녹화 보기 · 지난 방송 목록 · 녹화
    재생기 = POK-547 · 568)가 모두 prod 에서 빠진 뒤 별도 걸음으로
    되돌립니다(kty 2026-11-13)
    ([media README](../../../../media/README.md) 「M4 ⓒ 에서 M5/M6 로 넘긴
    것」 ·
    [media-swap 런북 「PDT 배포 순서」](../../runbooks/media-swap.md#pdt-배포-순서)).

## /dvr 하한(POK-525)

dvr-floor(`pokeclip-prod-dvr-floor`)는 `/dvr/*` viewer-request 함수입니다.
회차 꼴 조각의 seq 가 그 회차 하한보다 작으면 본문 없는 404 로 막습니다.
하한은 런타임 1단 KVS(`edge_bounds_kvs_arn`)에서 읽고, 값은 media 사이드카
Writer 가 씁니다([media README](../../../../media/README.md) 「/dvr 하한
Writer」).
하한은 되감기 창 정책이고 1차 통제는 서명 쿠키라, 키가 없거나 KVS 읽기가
실패하거나 값이 이상하면 막지 않고 넘깁니다.

스위치는 `dvr_floor_enabled`(기본 `false`)입니다. 함수와 함수 로그 그룹은
스위치와 무관하게 생기고, 스위치는 `/dvr/*` 에 붙일지만 정합니다. 켜는 것은
두 단 apply 이고 걸음마다 kty 승인을 받습니다.

1.  선행 — prod/runtime 을 POK-525 판으로 apply 해 출력
    `edge_bounds_kvs_arn` 이 있고, POK-528(하한 404 를 받는 웹)이 prod 에
    반영돼 있어야 합니다. 이 함수를 더한 PR 의 머지도 이 선행이 다 참이 된
    뒤입니다 — 머지 뒤 첫 edge apply 는 스위치가 꺼져 있어도 함수 · 함수
    로그 그룹 · 경보 일곱 자원(「경보(POK-525)」)을 만듭니다.
1.  1단 — 스위치 기본값(`false`) 그대로 apply 합니다. plan 은 함수 하나 ·
    함수 로그 그룹 하나 · 경보 일곱 자원(「경보(POK-525)」) 생성이고 배포
    변경은 0 입니다. 게시된 함수를
    test-function 으로 시험합니다([엣지 시험 런북](../../runbooks/edge-tests.md)).
1.  2단 — `-var dvr_floor_enabled=true` 로 apply 합니다. plan 은 media 배포
    하나 변경입니다. 실제 엣지 요청으로 시험합니다(판정 C′ — 결과는 통과
    또는 보류 · 즉시 kty 보고). 보류면 함수를 붙인 채 걸음 6.6 을 열어 두고
    kty 에게 즉시 보고합니다. 런북은 끄기를 판정하지 않습니다 — 끌지는 kty
    가 정하고, 정하면 `false` 로 되돌립니다(실행 직전 승인 · 함수 · 로그
    그룹은 남김 — [엣지 시험 런북](../../runbooks/edge-tests.md) 「판정 C′」
    · 「/dvr 하한 끄기」).

켠 뒤의 edge apply(표준 로그 끄기 · WAF 속도 조정 포함)는 모두
`-var dvr_floor_enabled=true` 를 함께 넘깁니다. 빠뜨리면 plan 에 `/dvr/*` 의
`function_association` 제거가 끼고, 그대로 apply 하면 하한이 조용히
풀립니다(떼어진 함수는 로그를 내지 않아 경보도 울리지 않습니다). **plan 에
`function_association` 제거가 보이면 apply 하지 않고 멈춥니다.**

붙은 채로 함수 코드를 고칠 때는 먼저 `false` 로 떼고, 1단으로 새 코드를
게시해 시험한 뒤 다시 켭니다. 게시(PublishFunction)는 그 함수를 쓰는
비헤이비어에 곧바로 실립니다.

런타임 1단 KVS 그릇의 해체 · 교체는 지금 실행할 수단이 없습니다.

*   스위치는 `/dvr/*` 연결만 뗍니다. dvr-floor 의 KVS 연결은 스위치와
    무관하게 늘 남습니다.
*   그 연결을 떼려면 함수를 지워야 하는데, 단계 데이터 O7f
    (`scripts/precheck/data/stage_actions/O7f.tsv`)에 `cloudfront:DeleteFunction`
    이 없습니다.
*   연결이 남은 채로 그릇을 교체하거나 지우면 DeleteKeyValueStore 가 쓰는
    중인 그릇을 `CannotDeleteEntityWhileInUse`(409)로 거절합니다(API 참조
    2026-11-11 확인 · 함수 연결이 「쓰는 중」 에 드는지는 미실측).

해체 · 그릇 교체에는 함수 삭제 승인 경로(O7f 에 `DeleteFunction` 행을 더하고
따로 승인받음)가 먼저 있어야 합니다. 그 경로는 POK-525 범위 밖이라 아직
없습니다.

## 경보(POK-525)

dvr-floor 는 KVS 읽기가 실패하거나 하한 값이 이상해도 막지 않고 넘깁니다
(K5 — 1차 통제는 서명 쿠키). 그 대신 고정 로그 한 줄을 남기고, 이 루트가 그
줄을 세어 경보를 울립니다. 함수가 끝나지 못한 요청(503)은 CloudFront 기본
지표로 봅니다. 경보 셋과 알림 주제는 모두 us-east-1 입니다(함수 로그와
CloudFront 지표가 us-east-1 이라).

| 경보 | 지표 | 뜻 |
|---|---|---|
| `pokeclip-prod-dvr-floor-value-invalid` | `PokeClip/Edge` · `DvrFloorValueInvalid`(지표 필터 `"DVR FLOOR VALUE INVALID"`) | KVS 의 하한 값이 하한 문법 밖이라 넘김 |
| `pokeclip-prod-dvr-floor-kvs-error` | `PokeClip/Edge` · `DvrFloorKvsError`(지표 필터 `"DVR FLOOR KVS ERROR"`) | KVS 읽기가 실패해 넘김 |
| `pokeclip-prod-dvr-floor-execution-errors` | `AWS/CloudFront` · `FunctionExecutionErrors`(차원 `FunctionName` · `Region` = `Global`) | 함수가 끝나지 못함 — 그 요청은 503 |

문턱은 셋 모두 같습니다(K-D4): 5분(300초) 합이 1 이상이면 한 번에 `ALARM`
이고, 데이터가 없으면 정상으로 봅니다. 알림은 `ALARM` 으로 바뀔 때 한 번
가고, `ALARM` 이 이어지는 동안이나 `OK` 로 돌아올 때는 가지 않습니다. 문턱은
판정 C′(엣지 시험 런북 걸음 6.6)을 마친 뒤 실제 방송의 지표를 보고 다시
봅니다.

지표 필터는 dvr-floor 함수 로그 그룹에 걸리고, 패턴 글자는 함수 원문 상수
(`modules/cdn_media/functions/dvr-floor.js`)에서 모듈 출력
`dvr_floor_log_markers` 로 받습니다 — 로그 글자를 바꾸면 필터가 따라가고,
시험이 패턴 글자를 고정해 그 변경을 드러냅니다. 필터는 만든 뒤의 로그만
셉니다. 함수 로그 전달은 best-effort 라(드물게 늦거나 빠짐) 경보 수는 완전한
수가 아닙니다. test-function 은 CloudWatch 로 로그를 보내지 않아 경보를
울리지 않습니다 — 로그는 LIVE 단계 함수가 실제 요청에 돌 때만 보냅니다
([AWS 개발자 안내서 「Edge function logs」](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/edge-functions-logs.html)
의 주 · 2026-11-11 원문 확인). 알림 경로 확인(판정 K)은
[엣지 시험 런북](../../runbooks/edge-tests.md)을 따릅니다.

경보 · 주제 · 지표 필터(일곱 자원)는 스위치 `dvr_floor_enabled` 와 무관하게
생깁니다. 이 경보를 더한 커밋 뒤 첫 edge apply 의 plan 에는 이 일곱 생성도
낍니다.

주제 정책은 SNS 기본 정책(Principal `*` · `AWS:SourceOwner`) 대신 명시
정책입니다. 계정 루트에는 읽기(`SNS:GetTopicAttributes`) 하나만 열고, 발행은
이 루트의 경보 셋이 보내는 CloudWatch(`aws:SourceArn` · `aws:SourceAccount`)
뿐입니다. 관리 · 구독은 신원 정책(kty IAM 사용자)이 정합니다.

이 발행 문장의 조건 키(`aws:SourceArn` · `aws:SourceAccount`) 때문에 직전
점검 판정 래퍼(`principal-check.sh` — SimulatePrincipalPolicy)의
`edge-alarm-topic` 행이 문맥 누락(`MISSING_CONTEXT`)으로 판정 불가
(`UNDECIDED`)가 될 수 있습니다. prod/runtime `broadcast_events.tf` 는 같은
이유로 조건 키를 쓰지 않았습니다. 오프라인 시험으로는 가릴 수 없어, 첫 edge
apply 뒤 그 판정 행을 실제로 한 번 돌려 결과를 봅니다 — `UNDECIDED` 면
멈추고 kty 에게 회부합니다(걸음은 [엣지 시험 런북](../../runbooks/edge-tests.md)).

### 경보 · 권한 한계

*   `sns:Unsubscribe` 를 묻는 판정 행이 없습니다(`edge-alarm-topic` ·
    `broadcast-topic` 같음) — dev 주체가 구독을 끊을 수 있는지는 직전 점검이
    보지 않습니다.
*   `FunctionValidationErrors` · `FunctionThrottles` 경보는 없습니다. K-D5
    (a) 가 정한 것은 `FunctionExecutionErrors` 하나입니다.
*   O7f 의 `cloudfront:UpdateFunction` 은 자원 `*` 입니다. 기존
    `CreateFunction` · `PublishFunction` 행과 같은 수준입니다.
*   `edge_bounds_kvs_arn` 꼴 검사(`remote_state.tf` precondition · 모듈 변수
    검증)는 계정 자리가 12자리 숫자인지만 보고 호출자 계정과 같은지는 보지
    않습니다. 다른 계정 KVS 는 CloudFront 가 연결을 거절한다고 봅니다(보안
    리뷰 판단 · 미실측).
*   R6(KVS 연결에 드는 권한)은 함수 권한만 준 제한 역할로 만들기
    (CreateFunction — KVS 연결 포함) · 고치기(UpdateFunction)만 쟀습니다
    (엣지 시험 런북 「/dvr 하한 실험대 결과」 · 2026-11-11). 게시
    (PublishFunction)와 KVS 연결은 재지 않아, 권한이 모자라면 1단 apply 에서
    `AccessDenied` 로 드러납니다.

### 이메일 구독

주제는 이 루트가 만들지만 이메일 구독은 저장소 밖에서 kty 가 한 번 답니다
(K-D1) — 공개 저장소에 주소를 두지 않고, 확인 전 구독은 provider 가 지우지
못하기 때문입니다.

1.  edge apply 뒤 이 루트에서 주제 ARN 을 읽어 구독합니다.

    ```shell
    aws sns subscribe --region us-east-1 \
      --topic-arn "$(terraform output -raw edge_alarm_topic_arn)" \
      --protocol email --notification-endpoint '<받을 주소>'
    ```

1.  받은 확인 메일의 링크를 누릅니다. 누르기 전에는 알림이 가지 않습니다
    (`aws sns list-subscriptions-by-topic` 의 `SubscriptionArn` 이
    `PendingConfirmation`).
1.  구독은 Terraform 상태 밖이라 주제가 다시 만들어지면 사라집니다. 리스
    계정이 끝나 AWS Nuke 로 지워진 뒤 다시 만들었을 때나 주제를 교체했을 때는
    1 부터 다시 합니다.

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

/dvr 하한을 켠 뒤라면 `dvr_floor_enabled = true` 도 함께 넘깁니다. 이 함수를
더한 PR 의 머지 뒤 1단 apply 전이면 plan 에 함수 · 로그 그룹 · 경보 생성이
낍니다(「/dvr 하한(POK-525)」 의 선행).

모듈에 넘기는 로그 그룹 ARN 은 자원의 `arn` 속성이 아니라 계정 · 리전 ·
이름으로 짓습니다. 로그 그룹을 처음 만드는 plan 에서 `arn` 은 정해지지 않고,
모듈의 전달 자원 수가 그 값의 null 여부에 기대기 때문입니다.

## 백엔드

`backend.hcl.example` 을 `backend.hcl` 로 복사하고 `bucket` 자리표시자
`<STATE_BUCKET_PROD>` 를 `pokeclip-tfstate-prod-apne2` 로 고친 뒤
`init -backend-config=backend.hcl` 로 엽니다(운영 단계). 이 루트의 변수는
모두 기본값이 있어 `terraform.tfvars` 가 필요 없습니다. 다만 하한을 켠
뒤에는 apply 마다 `-var dvr_floor_enabled=true` 가 있어야 합니다(위
「/dvr 하한(POK-525)」 의 경고).
