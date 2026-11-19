# prod/platform

prod 의 요청 입구와 앱 서비스를 만드는 루트입니다(설계 3-1 의 ⑧).

*   **공개 ALB**(`pokeclip-prod-public`): `app.pokeclip.com` → next,
    `api.pokeclip.com` → auth · clip-sse · clip. REGIONAL WAF 를 붙입니다.
*   **내부 ALB**(`pokeclip-prod-internal`): CloudFront VPC origin 뒤에서
    `/live/*` · `/api/streams/*/rewind` · `/api/ingest/*` · `/playback/*` 를
    Media 대상 그룹 셋(live · control · playback)으로 보냅니다.
*   **SRT NLB**: Media AZ 퍼블릭 서브넷 하나에 두고, UDP 8890 은 팀 IP 만
    받습니다.
*   **ECS**: 클러스터 `pokeclip-prod` · Fargate arm64 서비스 다섯(next ·
    auth · clip · chat-collector · chat-detector) · Cloud Map 네임스페이스
    `pokeclip.internal`(서비스 이름 그대로). chat 두 서버는 공개 ALB · 대상
    그룹 없이 Cloud Map 이름으로만 받습니다(아래 「chat 서비스」).
*   **SG 규칙**: 공개 ALB 인그레스 · 이그레스, 서비스 인그레스(clip ↔ auth
    두 방향 · chat ↔ auth · clip 포함), RDS 인그레스 ← auth · clip ·
    chat-collector · chat-detector(설계 5-5). 서비스 이그레스는 공통(443 →
    NAT · S3)과 부르는 상대뿐이고, chat-collector 만 SOOP 채팅 소켓 TCP
    8001 · 8041 · 8061 · 9001 → 0.0.0.0/0 을 더 둡니다(아래 「chat 서비스」).

두 ALB 리스너(HTTPS 443 · 서울 인증서)의 기본 동작은 고정 404 입니다. 규칙에
걸린 요청만 대상에 닿습니다 — api 호스트의 규칙 밖 경로와 모르는 Host 는
404 입니다.

이 루트에 **없는** 것은 다음과 같습니다(만드는 루트).

| 자원 | 루트 |
|---|---|
| 내부 ALB 인그레스 ← CloudFront VPC origin | edge |
| 내부 ALB · NLB 이그레스 → Media · Media 대상 등록 · Media ↔ RDS · Media ↔ auth(출력 `service_sg_ids.auth` · `service_ports.auth` 를 씀 — kty 결정 2026-10-25 (나)) | media |
| 일꾼 서비스 render · upload, clip · auth 인그레스 ← 일꾼 | `prod/workers`(POK-408 — 출력 `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids` · `service_ports` 를 씀) |

용어:

*   **Cloud Map**: VPC 안 사설 DNS 로 서비스 이름(`auth.pokeclip.internal`)을
    태스크 IP 로 풀어 주는 AWS 서비스입니다.
*   **작업 역할(task role)**: 앱 코드가 AWS 를 부를 때 쓰는 IAM 역할입니다.
    **실행 역할(execution role)** 은 ECS 가 이미지를 끌어오고 비밀을 읽을 때
    씁니다.

## 공개 ALB 규칙

| 우선순위 | 호스트 | 경로 | 대상 그룹 |
|---|---|---|---|
| 100–108 | api | auth 접두사 아홉 × {`/api/x`, `/api/x/*`} — 접두사마다 규칙 하나 | auth |
| 200 | api | `/api/clip/broadcasts/*/events` | clip-sse |
| 300 | api | `/api/clip/*` | clip |
| 400 | app | (없음) | next |

auth 접두사는 `web/next.config.ts` 에서 `AUTH_API_URL` 로 가는 줄과 같습니다
(`/api/auth` · `/api/stream-keys` · `/api/chzzk-link` ·
`/api/editor-delegations` · `/api/editor-invitations` · `/api/youtube-link` ·
`/api/soop-link` · `/api/streamers` · `/api/profile-photos`). 그 파일에 auth
줄이 늘면 `load_balancers.tf` 의 `auth_path_prefixes` 도 함께 늘립니다.
ALB 규칙 하나에는 조건 값이 다섯까지라(계획 P1) 접두사 하나가 규칙 하나입니다.

clip-sse 는 같은 clip 서비스입니다. 긴 SSE 연결이 clip 그룹의 응답 시간
지표를 흐리지 않게 대상 그룹만 뗍니다.

브라우저는 api 를 부르지 않습니다(ADR-089 ③ — 브라우저 API 는 app 같은
오리진 → Next rewrite). 그래서 CORS 는 실제로 쓰이지 않지만, auth · clip 은
허용 출처가 비면 부팅하지 않습니다(`web-support` `CorsProperties` 의
`@NotEmpty`). 계획(「CORS env 를 넣지 않는다」)과 달리
`CORS_ALLOWED_ORIGINS = https://app.pokeclip.com` 하나만 둡니다(kty 확정 —
변수 `cors_allowed_origins`).

## 공개 WAF 데이터 보호

공개 ALB 의 REGIONAL WAF 는 웹 ACL 데이터 보호로 아래 값을 요청 샘플 · 로그
모두에서 `REDACTED` 로 바꿉니다(`load_balancers.tf` 의 `module "waf"`).

*   **헤더**: `authorization` · `cookie` · `referer` · `x-internal-token` ·
    `x-stream-key-resolve-token` · `x-expired-broadcasts-token`. 앞의 둘은
    로그 가림(`redacted_fields`)도 겹쳐 둡니다.
*   **질의 인자**: `code`(OAuth 콜백) · `token`(프로필 사진 주소).

이 설정에는 운영 대가가 둘 있습니다. 차단 동작은 바뀌지 않습니다 — 데이터
보호는 저장되는 요청 데이터만 바꿉니다.

*   **질의 문자열 전체가 일치 세부에서 가려집니다.** 질의 인자를 하나라도
    가리면 로그의 `RuleMatchDetails` · `rateBasedRuleList` 에서는 질의 문자열
    전체가 `REDACTED` 입니다(AWS WAF 개발자 안내서 「Data protection
    limitations」). 그래서 질의를 보는 규칙(KnownBadInputs 의
    `*_QUERYSTRING` 규칙 등)에 걸린 차단 로그에서 공격 페이로드를 볼 수
    없습니다.
*   **출처를 잃습니다.** `referer` 를 가리므로 요청이 어느 화면에서 왔는지
    WAF 기록으로는 알 수 없습니다.

오탐을 볼 때는 가려지지 않는 자리를 씁니다.

*   어느 규칙이 걸렸는지: 로그의 `terminatingRuleId` · `ruleGroupList` 의
    규칙 이름과 `conditionType` · `location` · `labels`.
*   요청 대상: `httpRequest.uri` · `httpRequest.args`. `args` 에서는 가린
    인자(`code` · `token`)만 `REDACTED` 이고 나머지 인자는 원문입니다(같은
    안내서 「Examples of data protection」).
*   요청 샘플(최근 3시간)에도 같은 보호가 걸립니다. 샘플이 로그보다 더
    보여 주지는 않습니다.

질의 원문이 꼭 필요하면 데이터 보호 예외(`exclude_rule_match_details`)를
잠시 켜야 합니다. `modules/waf_acl` 에 그 입력이 없으므로 코드 변경과 리뷰를
거칩니다.

## Next 이미지 빌드 인자

Next 는 rewrite 목적지를 **빌드 때** 굳힙니다(web README 「컨테이너
이미지」). prod 이미지는 아래 값으로 빌드합니다 — 이 루트가 만드는 Cloud
Map 이름과 서비스 포트가 계약입니다.

| 빌드 인자 | 값 |
|---|---|
| `AUTH_API_URL` | `http://auth.pokeclip.internal:8082` |
| `CLIP_API_URL` | `http://clip.pokeclip.internal:8081` |

이름(`auth` · `clip` · `pokeclip.internal`)이나 `services` 의 포트를 바꾸면
next 이미지를 다시 빌드해야 합니다.

## chat 서비스

chat-collector(채팅 수집 · 8083)와 chat-detector(하이라이트 판별 · 8084)는
auth · clip 과 같은 `ecs_service` 모듈로 둡니다(POK-306). 바깥에서 부르는
쪽이 없어 공개 ALB · 대상 그룹이 없고, 그래서 next · auth · clip 의 태스크
정의 · 대상 그룹 · 공개 ALB 규칙은 chat 이 생겨도 바뀌지 않습니다
(`tests/chat_services.tftest.hcl` 이 값으로 못 박음). 컨테이너 헬스체크는
auth · clip 처럼 두지 않습니다. 배포 비율은 chat-collector 가 ECS 기본값
(최소 100 · 최대 200)이고 chat-detector 만 최소 0 · 최대 100 입니다(아래).

| 부르는 쪽 → 받는 쪽 | 포트 | 왜 |
|---|---|---|
| auth → chat-collector | 8083 | 탈퇴 정리(`COLLECTOR_BASE_URL`) |
| clip → chat-collector | 8083 | 채팅 문 셋(`COLLECTOR_BASE_URL`) |
| chat-detector → chat-collector | 8083 | 채팅 읽기(`COLLECTOR_BASE_URL`) |
| chat-collector → auth | 8082 | 방송 편지의 열쇠 조회 · 링크(`AUTH_BASE_URL`) |
| chat-collector → clip | 8081 | 재부착 · 중계 · 방송 정보(`CLIP_BASE_URL`) |
| chat-detector → clip | 8081 | 하이라이트 카드(`CLIP_BASE_URL`) |
| chat 둘 → RDS | 5432 | 자기 스키마(IAM DB 인증 · verify-full) |
| chat-collector → SOOP 채팅 서버(0.0.0.0/0 · NAT) | 8001 · 8041 · 8061 · 9001 | SOOP 채팅 소켓(아래 「chat 서비스의 한계」) |

치지직 Open API · 채팅 소켓 · SOOP API · SDK · SQS · 채팅 원본 보관 창고(S3)는
공통 이그레스(443 → NAT · S3 게이트웨이 엔드포인트)로 나갑니다. SOOP 채팅
소켓만 443 밖이라 위 표의 네 포트로 나갑니다.

**chat 재배포는 수집 중인 방송을 잠시 끊습니다.** 시작 편지는 일회용이라 새
태스크는 재부착(`chat_collector_features.reattach` — 기본 켬)으로 진행 중
방송에 다시 붙습니다. 옛 태스크가 내려간 뒤 다시 붙기 전까지의 채팅은 받지
못합니다(첫 회차 5초 뒤 · 그 뒤 1분마다). 재부착을 끈 배포는 방송이 없는
시간에 합니다. 다이제스트 · env · 크기를 바꾸는 apply 도 재배포입니다.

**chat-detector 재배포는 옛 태스크를 먼저 내립니다**(배포 비율 최소 0 ·
최대 100). 사건 묶음(`SpikeEpisodes`)이 프로세스 메모리라, 옛 · 새 태스크가
겹치면 인접 창을 둘이 나눠 잡아 사건이 갈라지고, 시작 시각이 다른 카드 두
장이 clip 의 중복 방어를 통과할 수 있습니다. 그래서 겹침 대신 공백을 고릅니다 — 새 태스크가 뜰 때까지 잠깐
판정이 멈추고, 메모리에 열려 있던 사건의 카드를 잃습니다(`SpikeEpisodes.java`
34–36 의 의도된 대가 — 중복보다 손실).

### chat 기능 스위치

`chat_collector_features` 가 chat-collector 의 스위치 여섯을 env 로
넣습니다(서버 기본값은 모두 꺼짐).

| 키 | env | 기본값 | 뜻 |
|---|---|---|---|
| `reattach` | `CHAT_REATTACH_ENABLED` | `true` | 재배포 · 재시작으로 잃은 방송 다시 붙기 |
| `relay` | `CHAT_RELAY_ENABLED` | `true` | 받은 채팅을 clip 에 바로 밀기(SSE) |
| `liveinfo` | `BROADCAST_INFO_ENABLED` | `true` | 방송 정보(제목 · 시청자 수) 1분마다 |
| `soop_watch` | `SOOP_WATCH_ENABLED` | `true` | SOOP 방송 지켜보기 |
| `soop_chat` | `SOOP_CHAT_ENABLED` | `true` | SOOP 채팅 받기(Chromium 탭) |
| `detach` | `CHAT_DETACH_ENABLED` | `false` | clip 명부에 없는 방송의 세션 반납 |

*   기본값은 kty 결정(2026-10-26)입니다. 떼기는 clip 치우개(기본 켬 — kty
    2026-11-16 · POK-588, 아래 「clip 치우개 스위치」) 다음 차례로 운영자가
    켭니다.
*   부팅 거부 조합은 plan 이 막습니다 — `detach` 는 `reattach` 와,
    `soop_chat` 은 `soop_watch` 와 함께만 켭니다. 재부착이 요구하는 방송
    편지 받기(`BROADCAST_INTAKE_ENABLED`)는 이 루트가 늘 켭니다.
*   방송 정보 · SOOP 채팅의 앱 값(`CHZZK_CLIENT_ID` · `CHZZK_CLIENT_SECRET` ·
    `SOOP_CLIENT_ID` · `SOOP_CLIENT_SECRET`)은 런타임이 줍니다. 켰는데 비면
    서버가 부팅을 거부합니다.

### chat 서비스의 한계

*   **SOOP 채팅 소켓은 표본에서 본 포트 넷만 열었습니다.** 배포된 Chat
    SDK 는 WebSocket 주소를
    `wss://chat-<chat_ip 16진>.sooplive.com:<chat_port + 1>/Websocket/<방송인 ID>`
    로 만듭니다. SOOP 공개 플레이어 API 표본은 두 번 떴습니다. 2026-10-27
    표본(생방송 130)은 `CHPT` 8040 이 117 · 9000 이 7 이었고, 2026-11-19
    표본(생방송 80)은 8060 이 40 · 8000 이 30 · 8040 이 2 였습니다. 그래서
    포트는 8001 · 8041 · 8061 · 9001 이고, 채팅 호스트의 443 은 닫혀
    있습니다. collector 이그레스에 TCP 8041 · 9001 → 0.0.0.0/0 을
    더했고(kty 2026-10-27 · POK-306), 8001 · 8061 은 kty 2026-11-19 에
    더했습니다. 포트는 방송마다 SOOP 이 주는 값이라 표본 밖 포트를 받은 방송은
    `soop.chat.ready` 가 찍히지 않고 다시 붙기만 되풀이됩니다. 11-19 prod 에서는
    `soop.chat.opening` 뒤 131초 만에 `soop.chat.error code=objectEvent` 가
    찍혔습니다(그 방송의 포트는 미확인 · SG 차단 정황: 131초 ≈ TCP 연결 재시도
    한도). 표본 밖 포트 실패가 반복되면 공개 API 표본으로 더하거나 VPC 흐름
    로그로 목적지 포트를 실측합니다. 흐름 로그는 kty 가 정할 때
    씁니다(2026-11-19 는 표본으로 더함). 11-19 표본 80 가운데 chat 포트 값
    셋(8000 · 8040 · 8060)에 들지 않는 8 개가 무응답인지 다른 포트인지는
    모릅니다. 남은 실측은 첫 실제 SOOP 방송에서 `soop.chat.ready` 가 찍히는지
    확인하는 것입니다. 치지직 · SOOP 지켜보기 · 방송 정보는 443 이라 영향이
    없습니다.
*   판별 서버가 수집 서버의 Flyway(판별 계정에 `chat_messages` 읽기 권한을
    줌)보다 먼저 떠도 되는지는 3번 답(POK-306 Q5) 전까지 모릅니다. 이 루트는
    두 모듈 사이에 순서를 두지 않습니다(`services.tf` 의 `module
    "chat_detector"` 주석).

## 앱 env · 비밀

런타임 1단의 `app_env` · `app_secret_arns`(서비스 → 이름 → 값 · ARN)를
그대로 넣고, 아래 이름은 이 루트가 정합니다. 같은 이름이 `app_env` 에 있으면
이 루트 값이 이깁니다.

| 서비스 | 이름 | 값 |
|---|---|---|
| auth | `FORWARD_HEADERS_STRATEGY` | `native`(로드밸런서 뒤) |
| auth | `FORWARD_HEADERS_TRUSTED_PROXIES` | network `vpc_cidr`(ALB · next 가 VPC 안 — 빈 값이면 auth 가 아무 프록시도 안 믿는다) |
| auth | `STREAM_KEY_SECRET_STORE_TYPE` | `aws`(prod 는 처음부터 Secrets Manager — 앱 기본값 postgres) |
| auth | `STREAM_KEY_SECRET_RETIRE_FROM` | `aws`(PG 이관이 없어 옛 암호도 Secrets Manager 에만 있다) |
| auth | `STREAM_KEY_SECRET_REF_PREFIX` | `stream_key_secret_prefix`(작업 역할 정책과 같은 값) |
| clip | `FORWARD_HEADERS_STRATEGY` | `native`(로드밸런서 뒤 — 비우면 ECS 감지로 같은 동작이지만, 만료 창구 전용 토큰이 있으면 명시해야 부팅한다 · POK-551). 신뢰 대역은 clip 기본값 그대로 |
| clip | `CLOUDFRONT_KEY_PAIR_ID` | secrets `public_key_id` |
| clip | `MEDIA_BASE_URL` · `PLAYBACK_COOKIE_DOMAIN` | `https://media.pokeclip.com` · `.pokeclip.com` |
| auth | `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` | `http://clip.pokeclip.internal:8081` · `http://chat-collector.pokeclip.internal:8083`(탈퇴 정리 — 비면 부팅 거부) |
| auth · clip | `CORS_ALLOWED_ORIGINS` | `cors_allowed_origins`(기본 `https://app.pokeclip.com` — 위 「공개 ALB 규칙」) |
| clip | `AUTH_BASE_URL` | `http://auth.pokeclip.internal:8082`(clip → auth 자격 판정) |
| clip | `COLLECTOR_BASE_URL` | auth 와 같은 값(비어도 부팅은 되지만 채팅 문 셋이 503) |
| clip | `PLAYBACK_REQUIRED` | `true`(서명 값 셋 중 하나라도 빠지면 부팅 거부 — POK-321) |
| clip | `BROADCAST_REAPER_ENABLED` | `clip_broadcast_reaper_enabled`(기본 `true` — 아래 「clip 치우개 스위치」) |
| clip | `CLOUDFRONT_PRIVATE_KEY_PEM`(비밀) | secrets `signing_key_secret_arn` |
| chat-collector | `BROADCAST_INTAKE_ENABLED` | `true`(방송 편지로 세션을 연다 — 운영의 시작 경로) |
| chat-collector | `AUTH_BASE_URL` · `CLIP_BASE_URL` | clip 과 같은 auth 주소 · auth 와 같은 clip 주소 |
| chat-collector | 기능 스위치 여섯 | 위 「chat 기능 스위치」 |
| chat-detector | `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` | auth 와 같은 값 |
| auth · clip · chat 둘 | `POSTGRES_USER` | 런타임 `app_db_users` 의 자기 사용자 |
| auth · clip · chat 둘 | `POSTGRES_AUTH` · `POSTGRES_SSLROOTCERT` | `iam` · `postgres_sslrootcert` — `app_db_auth = "iam"` 일 때만 |

채팅 원본 보관 창고 이름 `S3_BUCKET` 은 런타임 `app_env` 가 줍니다(작업
역할의 창고와 같아야 함 — 아래 「작업 역할」). chat-collector 에 넣지 않는
것: `CHZZK_ENABLED`(옛 한 채널 경로 — 받기와 같이 켜면 부팅 거부) ·
`CHAT_SYNC_OFFSET_MS`(서버 기본 3900). chat-detector 의 `DETECTION_*` 은
서버 기본값입니다.

비밀은 ARN 만 받습니다. 실행 역할의 비밀 읽기 권한은 그 ARN 집합으로
한정됩니다(`ecs_service` 모듈).

### clip 치우개 스위치

clip 치우개(`StaleBroadcastReaper`)는 종료 편지를 놓친 live 방송을 닫습니다.
1분마다 돌며, 마지막 신호(Media 장부 `stream_segments` 의 마지막 조각 시각과
방송 시작 시각 가운데 늦은 쪽) 뒤로 30분 동안 조용한 방송을 ended 로
굳힙니다. 종료 편지가 제때 오면 아무것도 닫지 않습니다.

*   기본 켬입니다(kty 2026-11-16 결정 · POK-588). 서버 기본은 꺼짐이라 이
    루트가 `clip_broadcast_reaper_enabled` 로 env 를 넣습니다. 유예 · 주기는
    서버 기본값이라 넣지 않습니다.
*   처음 켜기 — 경보 · 구독이 선 뒤에 켭니다(kty 2026-11-17):
    1.  `terraform.tfvars` 에 `clip_broadcast_reaper_enabled = false` 를 둔
        채 첫 platform apply 를 합니다. 경보 자원 넷이 생기고 치우개는 꺼진
        채입니다(plan 은 아래 「plan 대조」 의 예외).
    1.  아래 「clip 치우개 경보」 의 구독을 등록하고, 메일의 확인 링크를
        누르지 않고 `confirm-subscription` 으로 확인합니다. 그 절의 확인
        읽기가 대기 0 · 확인 1 과 `true` 를 내야 다음으로 갑니다.
    1.  `true` 로 바꿔 아래 「끄기 · 다시 켜기」 순서로 켭니다. 이 plan 은
        env 한 줄(`"false" -> "true"`)뿐이어야 합니다.
*   끄기 · 다시 켜기: `terraform.tfvars` 의 `clip_broadcast_reaper_enabled`
    를 `false`(켤 때 `true`)로 바꾸고 plan 대조(아래) → apply 전 기준
    읽기(아래 「clip 치우개 반영 확인」 첫 블록) → apply(kty 승인) → 읽기 셋
    순서로 합니다. 끈 사유가 상자 · 장부 기록기
    정지였으면, 다시 켜기는 런북 「치우개 단서」 4 의 장부 확인을 통과한
    뒤에 합니다.
*   plan 대조: 바뀌는 자원은 `module.clip.aws_ecs_task_definition.this`
    교체와 `module.clip.aws_ecs_service.this` 갱신 둘뿐이어야 하고, 태스크
    정의의 차이는 `BROADCAST_REAPER_ENABLED` 값 하나뿐이어야 합니다. plan 은
    `container_definitions` 를 `jsonencode(` 꼴의 구조 차이로 보여 줍니다. 그
    블록 안에서 바뀐 줄은 `name = "BROADCAST_REAPER_ENABLED"` 항목의
    `~ value = "true" -> "false"`(켤 때는 반대) 하나여야 합니다. `image` 나
    다른 env 항목의 바뀜(`->`) · 더함(`+`) · 뺌(`-`)이 보이거나 다른 자원이
    바뀌면 apply 하지 않고 멈춥니다. 블록 밖에서 `arn` · `revision` 같은
    식별자가 `(known after apply)` 로, 서비스의 `task_definition` 이 새
    리비전으로 바뀌는 줄은 교체에 따라오는 것입니다. 예외는 「처음 켜기」
    1 의 plan 하나입니다 — 네 주소 `aws_sns_topic.platform_alarms` ·
    `aws_sns_topic_policy.platform_alarms` ·
    `aws_cloudwatch_log_metric_filter.clip_reaper_ended` ·
    `aws_cloudwatch_metric_alarm.clip_reaper_ended` 의 생성(create)과 출력
    `platform_alarm_topic_arn` 이 더해지고, 태스크 정의의 차이는 env 항목
    `BROADCAST_REAPER_ENABLED = "false"` 의 더함(`+`) 하나입니다(그 전
    판에는 이 env 가 없어 서버 기본 꺼짐이었음 — 동작은 같음). 그 밖이
    보이면 멈춥니다.
*   이미 닫힌 방송은 끄거나 다시 켜도 상태가 ended 그대로입니다. 진짜 종료
    편지가 뒤늦게 오면 종료 시각(`ended_at`)과 VOD 만료 시각(종료 + 60일)을
    편지 시각으로 바로잡습니다(`Broadcast.applyEnded`).
*   한계: Media 상자나 장부 기록기가 30분 넘게 멈추면 살아 있는 방송도
    닫습니다. 상자 교체 · 제자리 전환 전 끄기는
    [Media 교체 런북](/infra/terraform/runbooks/media-swap.md) 「치우개
    단서」 를 따릅니다. 잘못 닫혔을 때는 아래 「clip 치우개 오판 감지 ·
    복구」 를 봅니다.
*   chat-collector 의 떼기(`CHAT_DETACH_ENABLED`)는 이 스위치 다음 차례입니다
    (위 「chat 기능 스위치」).

#### clip 치우개 반영 확인

apply 성공은 반영이 아닙니다. 이 루트는 배포가 끝나기를 기다리지 않아
(`modules/ecs_service` 의 `aws_ecs_service` 에 `wait_for_steady_state` 없음)
apply 뒤에도 옛 값의 태스크가 남을 수 있습니다. apply 전에 기준을 하나
적어 두고, apply 뒤 읽기 셋을 모두 통과해야 바뀐 것으로 봅니다(kty 로컬
터미널 · 읽기).

이 블록은 apply 전에 읽습니다 — apply 뒤에 읽으면 기준이 새 리비전이 되어
아래 3 의 비교가 무의미해집니다. 지금 배포의 이미지 다이제스트를 적어
둡니다(아래 3 의 기준).
작업 정의 ARN 과 이미지 주소는 계정 ID 가 들어 있어 변수에만 담고, 출력은
다이제스트만 냅니다.

```shell
td="$(aws ecs describe-services --region ap-northeast-2 \
  --cluster pokeclip-prod --services pokeclip-prod-clip \
  --query 'services[0].deployments[0].taskDefinition' --output text)"
aws ecs describe-task-definition --region ap-northeast-2 \
  --task-definition "${td}" \
  --query 'taskDefinition.containerDefinitions[].image' --output text \
  | sed -n 's/^.*@\(sha256:[0-9a-f]\{64\}\)$/\1/p'
```

1.  배포가 하나로 모였는지 봅니다. 기대 `1 PRIMARY COMPLETED <n> <n>`(배포
    하나 · 완료 · 실행 수 = 원하는 수 — 옛 태스크가 남지 않음)입니다.

    ```shell
    aws ecs describe-services --region ap-northeast-2 \
      --cluster pokeclip-prod --services pokeclip-prod-clip \
      --query 'services[0].[length(deployments),deployments[0].status,deployments[0].rolloutState,deployments[0].runningCount,desiredCount]' \
      --output text
    ```

1.  1 을 통과한 뒤 그 배포의 작업 정의 env 를 봅니다. 기대는 끌 때 `false`,
    켤 때 `true` 입니다.

    ```shell
    td="$(aws ecs describe-services --region ap-northeast-2 \
      --cluster pokeclip-prod --services pokeclip-prod-clip \
      --query 'services[0].deployments[0].taskDefinition' --output text)"
    aws ecs describe-task-definition --region ap-northeast-2 \
      --task-definition "${td}" \
      --query "taskDefinition.containerDefinitions[].environment[] | [?name=='BROADCAST_REAPER_ENABLED'].value" \
      --output text
    ```

1.  같은 `td` 의 이미지 다이제스트가 apply 전에 적은 값과 같은지 봅니다.
    다르면 스위치 밖의 변경이 함께 나간 것이므로 kty 에게 알립니다.

    ```shell
    aws ecs describe-task-definition --region ap-northeast-2 \
      --task-definition "${td}" \
      --query 'taskDefinition.containerDefinitions[].image' --output text \
      | sed -n 's/^.*@\(sha256:[0-9a-f]\{64\}\)$/\1/p'
    ```

1 이 아직 `COMPLETED` 가 아니면 몇 분 뒤 다시 읽습니다. `FAILED` 이거나
배포가 끝내 하나로 모이지 않으면(새 태스크가 뜨지 않음) 바뀌지 않은 상태로
봅니다. 끄던 중이면 「꺼지지 않음」 이므로 30분 넘는 정지 · 교체에 들어가지
않고 kty 에게 알립니다.

#### clip 치우개 오판 감지 · 복구

*   감지 첫 단계 — 그 줄이 진짜 치우개 종료인지 둘을 먼저 봅니다. ① 그
    `broadcast.reaper.ended` 줄의 로거가 `StaleBroadcastReaper` 인가(사용자
    입력이 다른 경고 줄에 되비쳐 지표 필터에 걸릴 수 있음) ② clip DB 에서 그
    방송(`streamId`)이 실제로 `ended` 인가. ② 는 ① 이 맞을 때만 봅니다.
    ② 의 읽기는 런북 runtime-1a 의 `app_clip` 읽기 전용 저장점 틀(「1단-다
    되돌리기 — O7d 뒤」 의 미종결 주문 블록)로 하고, 마스터가 틀 없이
    `SET ROLE` 로 읽지 않습니다. 로그의 `streamId` 는 DB 칸으로는 자유
    문자열(clip `V201` 의 `VARCHAR(128)`)이라 SQL 에 그대로 붙이지 않습니다.
    값은 Media 가 만드는 회차 꼴 `S-YYYYMMDD-HHMMSS-<물리 키>-<seq>`
    (`media/internal/session/registry.go` 의 `sessionID`)이고, 물리 키는
    `[A-Za-z0-9_-]{1,64}` 입니다(`media/internal/recording/name.go` 의
    `streamIDRe`). 그래서
    `^S-[0-9]{8}-[0-9]{6}-[A-Za-z0-9_-]{1,64}-[0-9]+$` 와 128자 이하를 통과한
    값만, 런북 `make_read` 처럼 SQL 원문을 base64 로 페이로드에 실어 `-f` 로
    돌리는 파일 안의 `\set sid '…'` 한 줄로 넣고 질의에서 `:'sid'` 로
    인용합니다(이 틀은 첫 줄이 `GRANT` 라 `make_read` 함수 자체는 쓰지
    않습니다) — 셸 줄 · `-v` 에는 값을 두지 않습니다. 이 앱 표 읽기는 POK-593 에서 일회용 SELECT
    계정으로 옮기고, 그 전까지 덫이 마스터 권한을 되찾을 때 남는 부작용(다른
    세션 끊기 · NOTICE · advisory 잠금 · 계획 단계 IMMUTABLE 색인 식)은 알려진
    한계입니다. 둘 다 맞을 때만 아래로 갑니다.
*   감지: 그 줄이 찍혔는데 그 방송의 송출이 이어지면 오판입니다 — 장부에 그
    방송의 조각이 계속 쌓이거나, 방송자가 송출 중이라고 합니다.
*   로그의 `streamId` 는 회차(장부 `stream_sessions.round_id`)이고, 장부
    조각의 `stream_id` 는 송출 물리 키입니다. 런북 「치우개 단서」 4 의 장부
    질의(`ledger-tail.sql`)가 live 세션마다 `round_id` 와 마지막 조각
    시각(`last_wall`)을 내므로, 그 회차의 행이 있고 `last_wall` 이 닫힌 시각
    뒤면 오판입니다.
*   되돌리는 코드 경로가 없습니다. 같은 회차로 송출이 이어지는 한 clip 은 그
    방송을 끝까지 ended 로 봅니다. OBS 단축키 마킹이 막히고, 새로 연
    대시보드에서 라이브 재생이 서지 않는 등입니다.
*   복구: 방송자에게 OBS 플러그인에서 송출을 정상 종료하고 10초쯤 뒤에 다시
    켜거나, 5분 넘게 쉬었다 켜라고 안내합니다. 그러면 Media 가 새 회차를
    열어 clip 에 새 방송 줄(live)이 생깁니다.
*   회차 규칙(Media 사이드카): 플러그인의 정상 종료는 의도적 종료 표식을
    남기고, 표식 + 8초 이후에 시작한 조각부터 새 회차입니다(`round_rule.go`
    의 `pastStopMark` · `session_wire.go` 의 `intentStopSlack`). 표식 없이
    끊었으면 세션이 송출 끊김 판정으로 먼저 끝나고, 마지막 조각 끝에서 재접속
    창 300초가 지난 뒤 시작한 조각부터 새 회차입니다(`registry.go` 의
    `joinable`). 그보다 일찍 다시 켜면 같은 회차에 붙어 여전히 ended 입니다.

#### clip 치우개 경보

치우개가 방송을 닫을 때마다 clip 로그에 남는 `broadcast.reaper.ended
streamId=` 줄을 지표 필터로 세어 경보를 울립니다(`reaper_alarms.tf` ·
POK-588). 한 줄이 방송 하나를 닫은 것입니다. 꼴은 prod/edge 「경보
(POK-525)」 를 따랐습니다.

| 경보 | 지표 | 뜻 |
|---|---|---|
| `pokeclip-prod-clip-reaper-ended` | `PokeClip/Platform` · `ClipReaperEnded`(clip 로그 그룹의 지표 필터 `"broadcast.reaper.ended streamId="`) | 치우개가 종료 편지를 놓친 live 방송을 닫음 |

*   문턱: 5분(300초) 합이 1 이상이면 `ALARM` 이고, 데이터가 없으면 정상으로
    봅니다(/dvr 하한 경보와 같음).
*   경보는 치우개 스위치와 상관없이 늘 있습니다. 꺼져 있으면 그 줄이 없어
    울리지 않습니다. 필터는 만든 뒤의 로그만 셉니다.
*   패턴은 `streamId=` 까지 넣어 `broadcast.reaper.ended_listener_failed`
    줄은 세지 않습니다. 같은 글자가 `StaleBroadcastReaper.java` 의 로그
    문자열에 있는지 시험이 봅니다. clip 로그는 평문(Spring Boot 기본 콘솔
    형식)이라 JSON 필터가 아니라 따옴표 구절입니다.
*   알림 주제 `pokeclip-prod-platform-alarms` 의 정책은 SNS 기본 정책 대신
    명시 정책입니다 — 계정 루트에는 읽기 하나이고, 이 자원 정책이 여는
    발행은 이 경보가 보내는 CloudWatch(`aws:SourceArn` ·
    `aws:SourceAccount`)뿐입니다. 같은 계정 주체의 신원 권한(발행 · 주제
    삭제)은 이 정책이 막지 않고, 직전 점검 principal_sim 의
    `platform-alarm-topic` 행이 봅니다.
*   알려진 한계: 그 대상 행들은 다음 동작 · 자원 유형을 묻지 않습니다(서비스
    권한 참조상 자원 유형은 SNS 구독 동작이 topic · PutLogEvents 가
    log-stream · PutMetricData 가 dataset) —
    `cloudwatch:PutMetricData`(같은 지표에 음수를 넣어 5분 합을 상쇄) ·
    `sns:Unsubscribe` · `sns:SetSubscriptionAttributes` ·
    `logs:PutLogEvents`(거짓 줄). 또 clip 로그 드라이버(`awslogs`)가 비차단
    모드면 로그가 몰릴 때 줄이 버려져, 경보 수는 완전한 수가 아닐 수
    있습니다(이 루트는 모드를 적지 않아 ECS 기본을 따름).
*   울리면: 그 시각 clip 로그의 `broadcast.reaper.ended` 줄에서
    `streamId`(회차)를 보고, 위 「clip 치우개 오판 감지 · 복구」 의 감지 첫
    단계(로거 · DB 상태)부터 따릅니다.

이메일 구독은 저장소 밖에서 kty 가 한 번 답니다(공개 저장소에 주소를 두지
않음). 위 「처음 켜기」 1 의 apply 뒤(치우개를 켜기 전) 이 루트에서 주제
ARN 을 읽어 구독하고, 받은 확인 메일의 `Token=` 값으로 구독을 확인합니다.
확인 전에는 알림이 가지 않습니다(`aws sns list-subscriptions-by-topic` 의
`SubscriptionArn` 이 `PendingConfirmation`). 메일의 확인 링크는 누르지
않습니다 — 링크의 `Token=` 값만 옮겨 `aws sns confirm-subscription --region
ap-northeast-2 --topic-arn … --token <토큰> --authenticate-on-unsubscribe true`
로 확인합니다(출력은 계정 ID 가 든 ARN 이라 `>/dev/null` 로 버림). 그래야
구독 해지에 인증이 걸려, 메일을 받은 누구나 해지 링크로 끊지 못합니다. 확인
뒤 아래 확인 읽기로 대기 · 확인 수와 그 구독의 `ConfirmationWasAuthenticated`
를 봅니다 — 주제 · 구독 ARN 은 계정 ID 가 들어 셸 변수에만 담고, 메일 주소와
함께 출력하지 않습니다. 기대는 대기 0 · 확인 1(탭으로 갈린 두 수)과 `true` 입니다.
`false` 면 메일 스캐너 등이 링크를 먼저 열어 인증 없이 확인된 것이니, 그
구독을 `aws sns unsubscribe` 로 해지하고 다시 구독해 위 명령으로 확인합니다.
구독은 Terraform 상태 밖이라 주제가 다시 만들어지면 다시 합니다.

```shell
aws sns subscribe --region ap-northeast-2 \
  --topic-arn "$(terraform output -raw platform_alarm_topic_arn)" \
  --protocol email --notification-endpoint '<받을 주소>'
```

```shell
topic="$(terraform output -raw platform_alarm_topic_arn)"
aws sns list-subscriptions-by-topic --region ap-northeast-2 \
  --topic-arn "${topic}" \
  --query "[length(Subscriptions[?SubscriptionArn == 'PendingConfirmation']), length(Subscriptions[?SubscriptionArn != 'PendingConfirmation'])]" \
  --output text
sub="$(aws sns list-subscriptions-by-topic --region ap-northeast-2 \
  --topic-arn "${topic}" \
  --query "Subscriptions[?Protocol == 'email' && SubscriptionArn != 'PendingConfirmation'].SubscriptionArn | [0]" \
  --output text)"
aws sns get-subscription-attributes --region ap-northeast-2 \
  --subscription-arn "${sub}" \
  --query 'Attributes.ConfirmationWasAuthenticated' --output text
```

## 작업 역할

코드가 실제로 부르는 AWS 동작만 줍니다(결정 135). 대상 ARN 은 런타임 1단
출력 `app_task_resource_arns` 에서 받고, 키가 없으면(기능 꺼짐) 그 문장을
만들지 않습니다. 근거는 `services/` 의 develop 코드입니다.

| 서비스 | 문장 | 동작 | 대상 | 근거 |
|---|---|---|---|---|
| auth | `StreamKeySecrets` | `secretsmanager:CreateSecret` · `PutSecretValue` · `GetSecretValue` · `DeleteSecret` | `secret:<stream_key_secret_prefix>*`(이름 = 접두 + UUID) | `SecretsManagerSecretStore.java:86` · `93` · `113` · `140` |
| auth | `ProfilePhotoObjects` | `s3:PutObject` · `GetObject` · `DeleteObject` | `profile_photo_bucket_arn/*` | `S3PhotoStorage.java:38` · `66` · `94` |
| clip | `BroadcastIntake` | `sqs:ReceiveMessage` · `DeleteMessage` | `broadcast_queue_arn` | `SqsIntakeRunner.java:212` · `427` |
| clip | `OrderQueues` | `sqs:SendMessage` | `render_queue_arn` · `upload_queue_arn` · `thumbnail_queue_arn` | `RenderQueueClient.java:30` · `UploadQueueClient.java:29` · `ThumbnailQueueClient.java:18` |
| clip | `DeadLetterQueues` | `sqs:ReceiveMessage` · `DeleteMessage` | `render_dlq_arn` · `upload_dlq_arn` | `RenderQueueClient.java:36` · `41` · `UploadQueueClient.java:34` · `39` |
| clip | `ClipObjects` | `s3:GetObject` · `PutObject` · `DeleteObject` | `clips_bucket_arn/*` | 서명 URL `ClipFileSigner.java:42` · `ThumbnailSigner.java:24` · `UploadThumbnailStore.java:69` · `82` · `S3PurgeStorage.java:57` |
| clip | `ClipList` | `s3:ListBucket` | `clips_bucket_arn` | `S3PurgeStorage.java:36` |
| clip | `SegmentDelete` | `s3:DeleteObject` | `segment_bucket_arn/*` | `S3PurgeStorage.java:57` |
| clip | `SegmentRead` | `s3:GetObject` | `segment_bucket_arn/*` | 서명 URL `SegmentAudioSigner.java:33`(트랙별 미리 듣기, POK-389) |
| chat-collector | `ChatBroadcastIntake` | `sqs:ReceiveMessage` · `DeleteMessage` · `GetQueueAttributes` | 런타임 `broadcast_queue_arns.chat` | `SqsIntakeRunner.java:254` · `457` · `152`(부팅 때 가시성 시한 관측) |
| chat-collector | `ChatArchiveObjects` | `s3:PutObject` · `DeleteObject` | 런타임 `chat_archive_bucket_arn` 의 `chat/*` | `S3ArchiveUploader.java:22` · `S3ArchivePurge.java:84`(묶음 지우기 — 객체마다 `DeleteObject`) |
| chat-collector | `ChatArchiveList` | `s3:ListBucket` | `chat_archive_bucket_arn`(버킷) | `S3ArchivePurge.java:50`(채널 접두 훑기) |
| auth · clip · chat 둘 | `DbConnect` | `rds-db:connect` | `dbuser:<rds_resource_id>/<자기 사용자>` — `app_db_auth = "iam"` 일 때만 | 공통 지시 6(POK-407) |

*   서명 URL 은 작업 역할 자격으로 서명되므로, 받는 사람이 그 URL 로 읽으려면
    역할에 `s3:GetObject` 가 있어야 합니다.
*   맨 `*` Resource 는 없습니다(`ecs_service` 검증). 런타임 출력 값은
    `terraform_data.runtime_contract` 가 먼저 봅니다 — 와일드카드 · 모르는 키 ·
    DB 사용자 이름 꼴이 틀리면 plan 이 멈춥니다.
*   chat 큐 ARN 은 와일드카드 없는 FIFO 큐(`.fifo`)여야 합니다. 보관 창고
    ARN 은 와일드카드 없는 버킷 ARN 이고, 그 버킷 이름이 chat-collector 의
    `S3_BUCKET` 과 같아야 합니다 — 아니면 plan 이 멈춥니다.
*   `ChatArchiveList` 는 버킷 전체 목록입니다. `ecs_service` 의 문장에 조건
    자리가 없어 `s3:prefix = chat/` 로 좁히지 않습니다 — 창고가 chat 전용이라
    목록이 넓어져도 보이는 것은 채팅 원본뿐입니다.
*   같은 자원이 앱의 부팅 거부 조건도 미리 봅니다 — `app_db_auth = "iam"` 인데
    DB 를 쓰는 서버 넷(auth · clip · chat 둘)에 `POSTGRES_PASSWORD` 가 오거나, auth 에
    `STREAM_KEY_RESOLVE_TOKEN` 이 오는데 `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` 가
    비면 plan 이 멈춥니다. clip 도 같은 꼴로 둘을 봅니다 —
    `EXPIRED_BROADCASTS_TOKEN` 이 오는데 `EXPIRED_BROADCASTS_ALLOWED_CIDRS` 가
    비거나, clip 의 `FORWARD_HEADERS_STRATEGY` 가 `none` · `native` 가 아니면
    plan 이 멈춥니다(POK-551 — 값은 `services.tf` 가 `native` 로 넣습니다).
*   런타임 `app_env` 는 평문 env 라 비밀은 `app_secret_arns` 로 와야 합니다.
    이름 어디에든 `PASSWORD` · `SECRET` · `TOKEN` · `KEY` 등이 들면 plan 이
    멈춥니다 — 비밀이 아닌 그런 이름은 `app_env_non_secret_names` 에 적습니다.
    이 목록으로도 풀리지 않는 검사가 넷 있습니다 — 이름 꼴(대문자 · 숫자 ·
    `_`), 값 운반 이름(`SPRING_APPLICATION_JSON` · `JAVA_TOOL_OPTIONS` ·
    `JDK_JAVA_OPTIONS` · `JAVA_OPTS`), URL 자격 증명(`user:pass@` ·
    `<토큰>@`), 값에 비밀을 싣는 꼴(키에 `passw` · `pwd` · `secret` · `token`
    이 든 쿼리 · 프래그먼트 매개변수 · 이름에 `secret` · `password` · `token`
    이 든 `-D` 속성). 값 검사는 이 낱말만 봐서 `?api_key=` · `-Ddb.passwd=` 는
    통과합니다 — 비밀은 검사에 기대지 말고 `app_secret_arns` 로 넘깁니다. 쿼리 ·
    프래그먼트 속 `@` 도 자격 증명으로 보고 멈출 수 있습니다(알려진 오탐) — 그런 값도
    `app_secret_arns` 로 옮깁니다.
*   next 는 AWS 를 부르지 않아 작업 역할에 정책이 없습니다. chat-detector
    는 DB 접속(`DbConnect`)뿐입니다.

## 값과 적용

`image_digests` · `proxy_ports` · `media_health_path` · `ingest_allowed_cidrs`
는 기본값이 없습니다. `terraform.tfvars.example` 을
`terraform.tfvars`(추적 안 함)로 복사해 채웁니다. `proxy_ports` ·
`media_health_path` 는 Media 상자 nginx 포트 · 헬스 경로의 정본입니다 —
대상 그룹이 이 값을 쓰고, 같은 값을 출력(`media_proxy_ports` ·
`media_health_path`)으로 내보내 prod/media 가 remote_state 로 읽습니다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `public_alb_name` · `internal_alb_name` | `pokeclip-prod-public` · `pokeclip-prod-internal` | 대상 그룹 이름(`<ALB>-<키>`, 32자 상한)이 들어가게 짧게 둠 |
| `waf_managed_rule_groups` | KnownBadInputs · AmazonIpReputationList | CommonRuleSet 은 본문 8KB 규칙이 프로필 사진(최대 2MB) 업로드를 막아 뺌 |
| `waf_rate_limit` | 2000 | IP 하나당 5분 요청 수 |
| `services` | next 3000 `/api/ping` 512/1024 · 60초, auth 8082 · clip 8081 `/actuator/health` 1024/2048 · 180초 | 포트 · 헬스 경로 · CPU · 메모리(MiB) · 기동 유예(Flyway). 공개 ALB 대상 그룹 · 규칙의 원천이라 키는 이 셋뿐 |
| `chat_services` | chat-collector 8083 · 1024/2048, chat-detector 8084 · 512/1024 | 포트 · CPU · 메모리(MiB). 키는 이 둘뿐. 포트는 앱 `application.yml` 의 `server.port` 맨값이라 8083 · 8084 고정(다른 값은 plan 이 막음) — 바꾸려면 앱부터 고친다 |
| `chat_collector_features` | 위 「chat 기능 스위치」 | chat-collector 기능 스위치 여섯 |
| `clip_broadcast_reaper_enabled` | `true` | clip 치우개(`BROADCAST_REAPER_ENABLED` — 위 「clip 치우개 스위치」). 유예 30분 · 주기 1분은 앱 기본값 |
| `app_db_auth` | `iam` | `password` 면 `rds-db:connect` · `POSTGRES_AUTH` 없음 |
| `postgres_sslrootcert` | `/etc/pokeclip/rds-ca/global-bundle.pem` | 서버 이미지의 RDS CA 번들 경로(POK-407 코멘트 10715 · 커밋 2975805e) — iam 에서만 넣음 |
| `cors_allowed_origins` | `https://app.pokeclip.com` | auth · clip 부팅 필수 값(실사용 없음) |
| `ingest_public_approved` | `false` | `ingest_allowed_cidrs` 에 /16 보다 넓은 CIDR(0.0.0.0/0 등)을 받을지 — S1 게이트 · 응답기(M15) 반영 뒤 8a 단계에서만 `true` |
| `stream_key_secret_prefix` | `pokeclip/prod/stream-key/` | auth 스트림 키 비밀 이름 접두 |

앞 루트 상태는 `state_bucket`(`pokeclip-tfstate-prod-apne2`) · `state_keys`
(network · certs · secrets · registry · runtime)로 읽습니다. 런타임 1단 키는
`prod/runtime/terraform.tfstate` 로 고정입니다(CONTRACT-runtime.md).

**O7d 선행 확인 — IAM DB 인증**: PR 343 으로 auth · clip(chat-collector
포함)도 db-support 의 `POSTGRES_AUTH` 를 읽습니다 — `app_db_auth` 는 기본
`iam` 을 유지합니다. iam 에서는 런타임이 DB 비밀번호 그릇
(`POSTGRES_PASSWORD`)을 주지 않습니다. 그 키가 있으면 앱이 부팅을 거부합니다.
`password` 로 바꿀 때만 런타임이 그 그릇을 `app_secret_arns` 로 줍니다.

적용은 O7d(kty 승인)입니다. 그 전에 [단계 직전 점검](/infra/terraform/runbooks/stage-precheck.md)
과 [ALB 교차 영역 실측](/infra/terraform/runbooks/alb-cross-zone-bench.md)을
하고, 런타임 1단 출력이 있는지 확인합니다. 백엔드는 `backend.hcl.example` 을
`backend.hcl` 로 복사하고 `bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를
`pokeclip-tfstate-prod-apne2` 로 고친 뒤 `init -backend-config=backend.hcl`
로 엽니다(운영 단계).

provider 의 `default_tags` 네 키(`TerraformRoot = prod/platform`)는 태그를
받는 자원에 붙습니다. mock 시험은 태그를 보지 못하므로 plan 의 `tags_all` 로
확인합니다.

### apply 뒤 되돌리기

apply 한 뒤 이 루트를 내리거나 코드를 되돌릴(revert) 때는
[prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md)을 따릅니다.
**revert 전에** 내립니다 — 되돌리면 이 루트의 코드가 사라져 destroy 할
구성이 없어지고, 자원이 관리 밖에 남습니다. 이 루트의 출력을 읽는
prod/media 를 먼저 내리고(이 루트를 먼저 내리면 media 의 destroy plan 이
출력 이름으로 실패합니다), edge · records · 런타임 2단 같은 다른 소비
루트가 apply 돼 있으면 그 루트도 먼저입니다(아래 「출력」). destroy 마다
plan 요지를 kty 가 보고 승인합니다.

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `public_alb_{arn,dns_name,zone_id,sg_id}` | records(app · api 별칭) |
| `internal_alb_{arn,dns_name,sg_id}` | edge(VPC origin · 인그레스) · media(이그레스 → Media) |
| `nlb_{dns_name,zone_id,sg_id}` | records(ingest 별칭) · media(이그레스 → Media) |
| `target_group_arns`(ingest · live · control · playback) | media — Media 상자 등록 |
| `media_proxy_ports`(live · control · playback · health) · `media_health_path` | media — 상자 nginx 포트 · 헬스 경로(대상 그룹과 같은 값) |
| `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids`(auth · clip · next) | 런타임 2단 · 일꾼(POK-408) · media(`service_sg_ids.auth` · `.clip` — Media ↔ auth · Media ↔ clip 만료 창구, POK-551). chat 두 서비스의 SG 는 이 루트 안에서만 써서 내보내지 않음 |
| `service_ports`(auth · clip · next — `services` 의 포트) | media — Media ↔ auth 규칙의 포트(kty 결정 2026-10-25 (나)) · Media ↔ clip 규칙의 포트와 `clip_internal_url` 포트 교차 검증(POK-551) |
| `clip_execution_role_arn` | 런타임 2단 |
| `platform_alarm_topic_arn` | 소비 루트 없음 — kty 의 이메일 구독 명령(위 「clip 치우개 경보」) |
