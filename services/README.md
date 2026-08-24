# services — 코어 API (Spring)

**담당: 3번 (`@kth4778`)**

## 무엇이 들어가나

**서버 4개**가 여기서 실행된다. `common/`·`web-support/`는 서버가 아니다.

| 모듈 | 포트 | 역할 |
|---|---|---|
| `common/` | — | 여러 서버가 같이 쓰는 **계약**(공유 엔티티·SQS DTO). 서버가 아니다 |
| `web-support/` | — | 여러 서버가 같이 쓰는 **웹 인프라**(CORS·상관 ID). 서버가 아니다 |
| `clip/` | 8081 | 방송 세션 · **점프카드 저장·SSE 전송** · 세그먼트 인덱스 · 클립 · 승인 · SQS 잡 발행 |
| `auth/` | 8082 | 로그인 · 스트림 키 · 채널 연동 · 유튜브 토큰 |
| `chat-collector/` | 8083 | 치지직·SOOP 채팅 실시간 수집 · 시차 보정 · S3 아카이브 |
| `chat-detector/` | 8084 | 채팅량 분석으로 하이라이트 후보(점프카드) 판별 |

Java 21 · Spring Boot 4.1 · Gradle 멀티모듈 · PostgreSQL · Redis

**IntelliJ 프로젝트는 하나다.** 이 폴더를 열면 서버 4개가 모듈로 잡힌다.

## 왜 auth와 clip을 나눴나

원래 둘은 `core` 한 서버였다. 부하 축·데이터·재배포 주기 세 축이 같았기 때문이다.
2026-08-03에 나눴고, 근거는 세 축이 아니라 **분리 시점 비용**이다 — clip 코드가
0줄이고 배포도 없던 그때가 가장 쌌다. 조건(결제 도입·로그인 트래픽 급증)을
기다렸다 나누면 코드·데이터·트래픽이 다 얹힌 뒤라 더 비싸다.

정본은 [ADR-022](https://github.com/3K-PokeClip/PokeClip-LLM-WIKI/blob/main/adr/ADR-022_auth_clip%EC%84%9C%EB%B9%84%EC%8A%A4%EB%B6%84%EB%A6%AC.md)다.

**DB는 그대로 공유한다.** 그래서 규율이 하나 붙는다.

| | 소유 |
|---|---|
| `auth` | `users` · `refresh_tokens` · `secrets` · `stream_keys` · `pairing_codes` · `pairing_exchange_attempts` |
| `clip` | `broadcasts` · `broadcast_events` (V201) · `jump_cards` (V202) · `broadcasts.vod_expires_at` (V203, POK-117) |
| chat 계열 | `chat_messages` (V301 · `stream_id` 칸은 V302) · `chat_ended_streams` (V303) — **collector가 쓰고 detector가 읽는다.** 같은 담당(3번)·같은 V3xx 대역의 공동 소유라, 아래 "서로의 표를 직접 읽지 않는다"의 예외가 아니라 한 소유자의 두 프로세스다 |

**서로의 표를 직접 읽지 않는다.** 필요하면 계약4의 `POST /internal/stream-keys/resolve`로
묻는다. 이 선이 무너지면 따로 배포되는데 DB로 묶인 **분산 모놀리스**가 된다 —
장애도 배포도 같이 터지는, 가장 나쁜 조합이다. 리뷰에서 볼 항목이다.

## 두 서버가 같이 쓰는 것은 어디 두나

| 모듈 | 무엇 |
|---|---|
| `common/` | 계약 — 공유 엔티티 · SQS 메시지 DTO. **아직 소스가 0개다** |
| `web-support/` | 웹 인프라 — CORS(허용 메서드 GET·POST·DELETE) · 상관 ID 필터. 테스트 도우미(`LogCaptor`)는 `testFixtures`에 |

`common`에 웹 계층을 두지 않는 규칙이 있어 둘로 나눴다. `web-support`는 이 앱들의
패키지 밖이라 컴포넌트 스캔에 안 걸린다 — 각 `Application`이 `@Import`로 끌어온다.

## Gradle 루트는 저장소 루트가 아니라 여기다

`settings.gradle`과 `gradlew`가 **이 폴더**에 있다. IntelliJ로 열 때도 이 폴더를 연다.

저장소 루트에 두면 IDE가 `web/node_modules`까지 자바 프로젝트로 인덱싱하고,
`./gradlew build`가 Go·파이썬 폴더까지 훑는다.

## common에 무엇을 두나

**두 서버 이상이 똑같이 쓰는 것만** 둔다. 지금은 공유 엔티티가 첫 후보다.

여기를 고치면 모든 모듈이 다시 빌드된다. 로직이 쌓이기 시작하면 서버들이 한 덩어리처럼
굴러가고, 나눈 의미가 사라진다. **계약만 둔다**는 경계를 지킨다.

## 반대로 채팅 둘은 왜 나눴나

| | 성격 | 재배포하면 |
|---|---|---|
| `chat-collector/` | 방송 내내 연결을 **붙들고 있다** | **그 순간 붙어 있던 방송이 전부 끊긴다** |
| `chat-detector/` | 주기적으로 **계산한다** | 아무 때나 올려도 된다 |

**🔴 POK-127로 이 칸의 값이 커졌고, 스스로 안 돌아온다.** 전에는 한 프로세스가 채널 하나를
붙들었는데 이제는 **켜져 있는 방송 전부**를 한 프로세스가 붙든다 — 재배포 한 번이 그 전부를 끊는다.
그리고 **새 프로세스가 그것들을 다시 열지 않는다.** 세션을 여는 것은 `STARTED` 편지 하나뿐인데,
그 편지들은 이미 처리돼 큐에서 지워졌기 때문이다. 부팅 시 복구 경로는 **없다**(코드에 없다).
방송 중이던 스트리머는 **다음 방송을 켤 때까지** 채팅이 안 쌓인다.

**여기서 막을 방법이 없다.** 배포 창을 방송 없는 시간대로 잡거나, 1번 Media가 방송 중인
스트리머에게 `broadcast.started`를 다시 보내 주어야 한다 — **둘 다 우리 폴더 밖이다.**
후자는 계약으로 정할 일이라 아직 합의되지 않았다.

`auth`·`clip`과 정반대다. 부하 축도(방송 수 vs 사용자 수), 재배포 주기도 다르다.

한 서버에 두면 계산이 무거워질 때 채팅 연결이 끊긴다. 끊기면 그 구간 채팅이
통째로 날아가고 하이라이트도 못 잡는다.

**채팅을 "시청자의 실시간 반응 투표"로 쓰는 것이 이 서비스의 핵심 차별점이다.**
팀 실측(실경기 채팅 18,112건)에서 확인한 것 — 메시지 수보다 **고유 채터 수의 급증**이
더 깨끗한 신호다. 1인 도배는 메시지 수만 올리고, 진짜 하이라이트는 눈팅층이
동시에 입을 여는 순간이다.

판별 알고리즘의 정본은 `PokeClip-LLM-WIKI`의 ADR-011과 하이라이트 연구노트다.

## 돌리는 법

인프라(PostgreSQL·Redis)는 **저장소 루트의 `docker-compose.yml`**이 띄운다.

```bash
# 저장소 루트에서 — 한 번만
cp .env.example .env
docker compose up -d postgres redis

# 여기(services/)에서
./gradlew build              # 전체 빌드 + 테스트

# 서버 하나 띄우기 — .env를 셸에 먼저 싣는다
set -a && . ../.env && set +a
./gradlew :auth:bootRun
```

**`.env`를 싣는 줄을 빼먹으면 DB 접속이 실패한다.** `docker compose`는 `.env`를 자동으로
읽지만 **Gradle은 읽지 않는다.** DB 접속값 셋은 기본값이 없어(아래) 셸에 없으면 리터럴
`${POSTGRES_USER}`가 그대로 사용자 이름이 되고 `FATAL: password authentication failed`로 죽는다.

| 서버 | 실행 | 헬스체크 |
|---|---|---|
| `clip` | `./gradlew :clip:bootRun` | http://localhost:8081/actuator/health |
| `auth` | `./gradlew :auth:bootRun` | http://localhost:8082/actuator/health |
| `chat-collector` | `./gradlew :chat-collector:bootRun` | http://localhost:8083/actuator/health |
| `chat-detector` | `./gradlew :chat-detector:bootRun` | http://localhost:8084/actuator/health |

**`auth`는 환경변수 없이는 일부러 부팅에 실패한다. 열다섯이고, 두 갈래로 나뉜다.**

| 갈래 | 변수 | 어디서 얻나 |
|---|---|---|
| **앱 시크릿 열둘** | `JWT_SECRET` · `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` · `CORS_ALLOWED_ORIGINS` · `SECRET_STORE_KEY`(base64 32바이트) · `INTERNAL_API_TOKEN` · `CHZZK_CLIENT_ID` · `CHZZK_CLIENT_SECRET` · `CHZZK_REDIRECT_URI` · `YOUTUBE_CLIENT_ID` · `YOUTUBE_CLIENT_SECRET` · `YOUTUBE_REDIRECT_URI` | **`.env.example`에 없다** — public 저장소라 예시 값도 두지 않는다. 각자 받아서 넣는다 |
| **DB 접속값 셋** | `POSTGRES_DB` · `POSTGRES_USER` · `POSTGRES_PASSWORD` | `.env`에 있다. 위 실행 절차의 `set -a && . ../.env` 줄이 싣는다 |

**막는 방식이 갈래마다 다르다.** 앱 시크릿 열둘은 빈 기본값(`${VAR:}`)을 주고 부팅 검증으로
잡는다 — 기본값을 아예 안 주면 리터럴 `"${VAR}"`이 바인딩돼 **서버는 뜨고 헬스체크도
통과하는데 그 기능만 전부 실패하기** 때문이다. DB 접속값 셋은 반대로 기본값 자체를 없앴다(POK-161).
그쪽은 서버가 실제로 접속을 시도하는 값이라 리터럴이 들어가도 접속 실패로 죽어 신호가 남는다.

**🔴 이 표에 값을 더하면 `docker-compose.dev.yml`의 `auth` 블록과 `.env.dev.example`에도 같이 넣어라.**
컨테이너는 호스트 셸의 변수를 자동으로 물려받지 않는다 — 표에만 적으면 **dev 배포만 부팅 실패 루프**에 빠지고
로컬·CI는 전부 초록이다(같은 함정을 POK-127이 chat-collector에서 실물로 겪었다. 아래 「나머지」 절 참고).
**POK-121이 이 규칙을 그대로 어겼고**(유튜브 셋을 표에만 적었다) 봇 리뷰가 잡았다 —
그래서 지금은 `DeploymentEnvVarsTest`가 auth의 필수 변수와 두 파일을 대조한다.

**IDE로 띄운다면** 실행 구성의 환경변수에 위 열다섯을 넣거나, `.env`를 읽어 주는 플러그인을 쓴다.
`application-local.yml`(gitignore) 프로파일만으로는 부족하다 — 그 파일은 앱 시크릿만 채우고
DB 접속값은 채우지 않는다.

> **⚠ 등록값 정리 대기 (POK-205).** 위 값은 프론트 콜백 라우트가 생기면서 정한 **목표 상태**다.
> 실제 개발자 센터 등록값은 아직 로컬 `http://localhost:8081/oauth/chzzk/callback`(8081은 clip 서버 포트다 —
> 프론트가 없던 시절에 박힌 값), dev `http://dev.pokeclip.com/auth/chzzk/callback`(경로가 `auth`)이다.
> **두 앱 다 재등록해야 동의 왕복이 성립한다.** 재등록 전에는 동의를 마쳐도 우리 화면으로 돌아오지 못한다.

**치지직 셋은 한 덩어리로 검증한다.** 개발자 센터에 등록한 앱 하나의 값이라 하나만 빠져도
연동이 통째로 안 되므로, 셋 중 무엇이 비든 같은 메시지 한 줄
(`치지직 앱 설정(CHZZK_CLIENT_ID·CHZZK_CLIENT_SECRET·CHZZK_REDIRECT_URI)이 비었다`)로 죽는다 —
원인을 세 갈래로 흩지 않는다. 값은 메시지에 넣지 않는다.

| | 뜻 |
|---|---|
| `CHZZK_CLIENT_ID` | 치지직 개발자 센터 앱의 Client ID. 동의 URL에 그대로 실린다 |
| `CHZZK_CLIENT_SECRET` | 그 앱의 Client Secret. 토큰 교환·갱신·철회 요청 본문에만 쓰고 URL·로그 어디에도 안 나간다 |
| `CHZZK_REDIRECT_URI` | 동의가 끝난 뒤 치지직이 code·state를 돌려줄 주소. **웹 프론트의 콜백 라우트**(`/oauth/chzzk/callback`)를 가리켜야 한다 — 백엔드가 받는 주소가 아니다. **개발자 센터에 앱당 하나만 등록된다**(그래서 환경마다 앱을 따로 판다): 로컬 `http://localhost:3000/oauth/chzzk/callback` · dev `http://dev.pokeclip.com/oauth/chzzk/callback` |

**유튜브 셋도 한 덩어리다.** 같은 이유로 셋 중 무엇이 비든 한 메시지
(`유튜브 앱 설정(YOUTUBE_CLIENT_ID·YOUTUBE_CLIENT_SECRET·YOUTUBE_REDIRECT_URI)이 비었다`)로 죽는다.
**로그인용 구글 앱(`GOOGLE_*`)과 다른 GCP 앱이다** — 업로드 권한은 프로젝트 단위 심사 대상이라
폭발 반경을 나눴다. 로그인 앱이 심사에 걸려도 로그인은 계속 돌아야 한다.

| | 뜻 |
|---|---|
| `YOUTUBE_CLIENT_ID` | GCP 콘솔 OAuth 클라이언트의 Client ID. 동의 URL에 그대로 실린다 |
| `YOUTUBE_CLIENT_SECRET` | 그 앱의 Client Secret. 토큰 교환·갱신·철회 요청 본문에만 쓰고 URL·로그 어디에도 안 나간다 |
| `YOUTUBE_REDIRECT_URI` | 동의가 끝난 뒤 구글이 code·state를 돌려줄 주소. **웹 프론트의 콜백 라우트**(`/oauth/youtube/callback`)다 — 백엔드 주소가 아니다. GCP 콘솔에는 여러 개를 등록할 수 있어 치지직과 달리 환경마다 앱을 나눌 필요는 없다. 🔴 **비-localhost는 `https`여야 한다** — 구글이 http 리디렉션을 `localhost`·`127.0.0.1`에만 허용한다. (`GOOGLE_REDIRECT_URI`가 아직 http인 것은 별개 항목이다 — POK-205에서 함께 정리한다) |

🔴 **테스트 모드에서는 refresh 토큰이 7일이면 죽는다.** 앱이 「테스트」 상태인 동안 구글이 주는
refresh는 **7일 −1초**(실측: 교환 응답에 `refresh_token_expires_in: 604799`가 실려 온다)만 산다.
그 뒤로는 갱신이 `invalid_grant`로 거부돼 연동이 `BROKEN`이 되고, 사용자가 **재동의**해야 풀린다.
데모 계정은 주 1회 재연동이 필요하다는 뜻이다. 해소 수단은 **OAuth 동의 화면 심사 통과**뿐이다
(우리 코드로 늘릴 수 없다). 우리는 그 필드를 읽지 않는다 — 판정은 갱신 거부로만 한다.

**`clip`은 환경변수 없이는 부팅에 실패한다. 일곱이고, auth와 같은 두 갈래다.**

| 갈래 | 변수 | 어디서 얻나 |
|---|---|---|
| **앱 시크릿 넷** | `CORS_ALLOWED_ORIGINS` · `JWT_SECRET` · `INTERNAL_API_TOKEN` · **`AUTH_BASE_URL`**(POK-117) | **`.env.example`에 값이 없다** — auth의 앱 시크릿 아홉과 같은 규칙이다. `.env`에 직접 넣는다. 다만 `AUTH_BASE_URL`은 시크릿이 아닌 주소라 예시 값이 적혀 있다(`http://localhost:8082`, compose 안에서는 `http://auth:8082`) |
| **DB 접속값 셋** | `POSTGRES_DB` · `POSTGRES_USER` · `POSTGRES_PASSWORD` | `.env`에 있다(POK-82에서 POK-161의 규칙을 옮겼다) |

**`JWT_SECRET`·`INTERNAL_API_TOKEN`은 auth와 <u>같은 값·같은 이름</u>이어야 한다**(POK-118).
같은 이름인 이유는 `.env` 한 줄로 두 서버가 뜨게 하려는 것이고, 같은 값이어야 하는 이유는
**auth가 서명한 토큰을 clip이 검증**하기 때문이다(대칭키 HS256, ADR-048). 값이 갈리면
서버는 멀쩡히 뜨고 **사람 API만 전부 401**이 된다 — 가장 찾기 어려운 종류의 실패다.
`INTERNAL_API_TOKEN`도 같다: 서버 간 토큰은 하나이고, 갈리면 판별기가 넣는 카드가 전부 401이 된다.
**clip은 토큰을 발급하지 않는다** — 검증만 한다(발급자는 auth 하나다).

**`cp .env.example .env`만 하고 `clip`을 띄우면 실패한다** — 그 파일에는 이름만 있고
`CORS_ALLOWED_ORIGINS`는 아예 없다. 실제 실패 원문(2026-08-18 실측):

```
APPLICATION FAILED TO START
    Property: pokeclip.cors.allowedOrigins
    Reason: 비어 있을 수 없습니다
```

빈 값을 허용하지 않는 이유는 auth와 같다 — 기본값으로 localhost를 주면 운영 배포에
localhost가 딸려가고, 로컬에선 되고 운영에서만 막히는데 로그는 조용하다.

**환경변수를 갖춰도 방송 이벤트는 안 받는다.** `BROADCAST_INTAKE_ENABLED`
기본값이 `false`다 — 켜진 채로 두면 CI와 남의 로컬이 뜰 때마다 없는 큐에 붙으려 한다.
`chat-collector`의 `CHZZK_ENABLED`와 같은 규칙이다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BROADCAST_INTAKE_ENABLED` | `false` | 켜면 SQS 폴링 루프가 돈다 |
| `BROADCAST_QUEUE_URL` | 빈 값 | 생명주기 FIFO 큐 주소. **켜져 있을 때만 필수** |
| `AWS_REGION` | `ap-northeast-2` | **켜짐과 무관하게 필수** — 비면 부팅이 죽는다 |
| `BROADCAST_QUEUE_ENDPOINT` | 빈 값 | 비면 진짜 AWS. LocalStack 실측 때만 준다 |

`wait-time`(20초)·`max-messages`(10)는 yml에만 있고 환경변수가 없다. **둘 다 SQS가 정한
상한이 있어 넘기면 부팅이 거부된다**(각각 0~20초, 1~10). 상한을 안 막으면 부팅은
성공하고 호출만 거부돼 폴링이 매 회차 실패한다.

**켜는 값을 큐 주소와 따로 둔 이유:** 주소가 비었다고 저절로 꺼지면 "로컬에서 일부러
안 켬"과 "운영에서 설정을 깜빡함"이 똑같이 보인다. 켜져 있는데 주소가 없으면 그건
실수이므로 부팅을 거부한다. 자격증명은 환경변수에 없다 — SDK 표준 체인(환경변수·프로파일·역할)이 찾는다.

받고 있는지는 헬스체크에 나온다. **꺼둔 것은 실패가 아니라 설정이라 UP이되 상세에 적힌다.**

```bash
curl -s localhost:8081/actuator/health
# "broadcastIntake":{"status":"UP","details":{"status":"disabled"}}
```

켜졌는데 2분 넘게 폴링 성공이 없으면 **DOWN**이고 상세에 `stalled`과 마지막 성공 시각·실패
사유가 실린다. `management.endpoint.health.show-details: always`가 없으면 이 상세가 응답에서
잘려 밖에서는 `{"status":"UP"}`만 보인다 — "서버는 떴는데 수신은 죽은" 상태를 구분할 수 없게 된다.

**`chat-collector`는 환경변수 없이도 뜨지만 수집을 시작하지 않는다.** 붙는 길이 둘이고
**둘 다 기본이 꺼짐**이며, **같이 켜면 부팅을 거부한다**(POK-127).

| 길 | 스위치 | 세션을 여는 것 | 토큰 |
|---|---|---|---|
| **운영** | `BROADCAST_INTAKE_ENABLED` | 1번 Media가 큐에 넣는 **방송 시작 편지** | **스트리머마다 다르다** — auth에 물어서 받는다 |
| 실측·디버깅 | `CHZZK_ENABLED` | 부팅 그 자체. 채널 하나 | `CHZZK_ACCESS_TOKEN` 하나 |

**같이 못 켜는 이유:** 옛 경로 세션이 영구 정지(`revoked`·401·403)하면 프로세스가 `exit 1`로
내려가는데, 그러면 **편지로 연 다른 방송의 세션이 전부 같이 끊긴다.** 그것들을 되살릴
`STARTED` 편지는 이미 소비돼 큐에 없다. 한쪽이 다른 쪽의 방송을 죽이는 배선이라 부팅에서 막는다.

```bash
# 운영 — 편지를 받아 스트리머마다 붙는다
BROADCAST_INTAKE_ENABLED=true BROADCAST_QUEUE_URL=<FIFO 큐 주소> \
AUTH_BASE_URL=http://localhost:8082 INTERNAL_API_TOKEN=<auth와 같은 값> \
./gradlew :chat-collector:bootRun

# 실측·디버깅 — 내 토큰 하나로 내 채널만
CHZZK_ENABLED=true CHZZK_ACCESS_TOKEN=<유저 Access Token> ./gradlew :chat-collector:bootRun
```

**`compose` 칸은 `docker-compose.dev.yml`의 `chat-collector` 블록이 실제로 넘기는지다.**
`—`는 넘기지 **않는다**는 뜻이고 그래도 정상이다 — yml 기본값이 dev에 그대로 쓸 만한 값이라
일부러 뺐다. 표를 compose와 같은 것으로 읽지 마라.

| 변수 | 기본값 | compose | |
|---|---|---|---|
| `BROADCAST_INTAKE_ENABLED` | `false` | ✅ `CHAT_`\* | 켜면 편지 폴링 루프가 돈다 |
| `BROADCAST_QUEUE_URL` | 빈 값 | ✅ `CHAT_`\* | 생명주기 FIFO 큐 주소. **켜져 있는데 비면 부팅이 죽는다** |
| `BROADCAST_QUEUE_ENDPOINT` | 빈 값 | — | 비면 진짜 AWS. LocalStack 실측 때만 준다 |
| `BROADCAST_QUEUE_WAIT` | `20s` | — | 롱폴링 대기. **SQS 상한이 20초**라 넘기면 부팅이 죽는다 |
| `BROADCAST_QUEUE_MAX_MESSAGES` | `10` | — | 한 회차에 꺼낼 최대 편지 수. **SQS 상한이 10**이다 |
| `AUTH_BASE_URL` | `http://localhost:8082` | ✅ | 스트리머 토큰을 물을 auth 주소. 컨테이너 안에서는 서비스 이름 |
| `INTERNAL_API_TOKEN` | 빈 값 | ✅ | auth의 `/internal/**`이 `X-Internal-Token`으로 보는 값. **auth에 준 것과 같아야 한다.** POK-128부터는 이 서버의 `/internal/*`도 같은 값으로 잠근다 — **필터가 접두 매핑이라 창구를 새로 열면 자동으로 걸린다**(지금 둘: 수집 상태 창구 · 영상 위치 창구). 비면 그 창구들이 전부 401이다 |
| `BROADCAST_ENDED_SWEEP_INTERVAL` | `PT1H` | — | 끝난 방송 메모를 치우는 주기 |
| `BROADCAST_ENDED_RETENTION` | `PT24H` | — | 그 메모의 보관 기간(ADR-016의 TTL 24h) |
| `AWS_REGION` | `ap-northeast-2` | ✅ | 큐·S3 공용 |
| `CHAT_SYNC_OFFSET_MS` | `3900` | — | 채팅 시각에서 빼는 보정값(ms). **2026-08-24 로컬 실측값이다**(표본 20개 중앙값 3,884ms를 반올림). **음수를 허용하고 크기는 ±600000(10분)까지다.** 운영에서는 다시 잰다 — 아래 참고 |

**🔴 `CHAT_`\*는 「호스트 쪽 이름이 다르다」는 표시다.** 서버가 컨테이너 안에서 읽는 이름은
표에 적힌 그대로이고, `.env`에서 받는 이름만 `CHAT_BROADCAST_INTAKE_ENABLED`·
`CHAT_BROADCAST_QUEUE_URL`이다. **`clip`이 같은 두 이름을 이미 쓰기 때문이다** — 한 변수로
묶으면 두 서버가 같은 큐를 가리키고, SQS는 한 편지를 한 소비자에게만 주므로 서로 편지를
뺏는다. 아래 「나머지」가 적은 대로 **팬아웃이라 큐가 둘**이어야 한다.

**켜는 값을 큐 주소와 따로 둔 이유는 `clip`과 같다** — 주소가 비었다고 저절로 꺼지면
"로컬에서 일부러 안 켬"과 "운영에서 설정을 깜빡함"이 똑같이 보인다. 자격증명은 환경변수에
없다(SDK 표준 체인).

**`CHAT_SYNC_OFFSET_MS`의 `CHAT_`은 위 표시와 무관하다** — 그쪽은 `compose` 칸에 붙어
「`.env`에서 받는 이름만 다르다」는 뜻이고, 이쪽은 **서버가 읽는 이름 자체**가 그렇다.

**기본값 `3900`은 실측이다 — 2026-08-24, 표본 20개.** 화면에 20초마다 흰 번쩍임을 만들어
같은 비트스트림을 치지직과 우리 미디어 서버에 동시 송출하고, 스트리머가 **치지직 방송 화면을
보고** 채팅을 쳤다. 창구가 답한 위치(P₁)와 녹화 조각에서 검출한 번쩍임의 실제 위치(P₂)의
차가 한 회차의 보정값이다.

| | |
|---|---|
| 중앙값 | **3,884ms** → 반올림 `3900` |
| 평균 · 범위 | 4,100ms · 3,746~4,977ms |
| 수렴 | 표본 15개일 때 3,893 → 20개일 때 3,884 |
| P₂ 판독 오차 | **±33ms** — 사람이 재생기로 읽지 않고 30fps 프레임 단위로 자동 검출했다 |
| 측정 장소 | **로컬 개발 노트북**(EC2에는 조각 장부를 쓰는 구성이 없다) |

**배제한 표본은 셋이다** — 스트리머가 측정 중 「늦게 쳤다」고 먼저 신고한 1건과, 송출 재시작
직후라 채팅이 화면보다 먼저인(물리적으로 불가능한) 음수 2건. **셋을 다 포함해도 중앙값은
3,844로 40ms만 움직인다** — 배제가 결론을 만들지 않았다.

**🔴 이 값에서 시계 오프셋 4.0초를 이미 뺐다.** 측정 기계의 시계가 정확한 시각보다 그만큼
느렸고(애플·구글 NTP 둘이 `+4.005`~`+4.017`초로 일치), 채팅 시각은 치지직 서버가 찍고 영상
조각 시각은 우리 기계가 찍으므로 그 차이가 측정값에 통째로 섞여 든다. 안 뺐으면 여기 약
**7.9초**가 적혔을 것이다. 전달 지연(`received_at − message_time`)이 그 자체로 시계 오프셋의
측정기다 — 시계를 맞추자 `−3,9xx ms`가 `+43·+58ms`로 돌아왔다.

**🔴 운영으로 옮길 때는 반드시 다시 잰다.** 이 값이 보증하지 않는 것 다섯:

1. **로컬 노트북에서 쟀다** — EC2 dev에는 `segment-indexer`도 조각 장부 표도 없다
2. **시계 오프셋을 손으로 뺀 값이다** — 시계가 맞는 기계에는 그 항이 아예 없다
3. **테스트 패턴 송출이다** — 실제 게임 화면은 인코딩 난이도가 달라 지연이 다를 수 있다
4. **시청자 한 명(스트리머 본인)의 반응이다** — 여러 시청자의 분포는 더 넓다
5. **치지직 단독이다** — SOOP은 전달 지연조차 다를 수 있다(ADR-034)

**이 값에는 (방송 지연 + 시청자 반응 지연 + 전달 지연) − 우리 인제스트 지연이 합으로 들어
있다.** 부수로 잰 우리 인제스트 지연은 785ms였다. 같은 코드로 잰 전달 지연이 2026-08-05에
`+175ms`, 08-15에 `−39~−70ms`였다 — **부호조차 환경이 정하므로 음수를 허용한다.**

**`compose` 칸이 `—`인 것은 위 표 규칙 그대로다 — 「yml 기본값을 그대로 쓴다」는 뜻이다.**
**「dev에는 안 쓴다」가 아니다**: 빼 두면 그 compose로 띄운 수집기가 `3900`을 그대로 쓴다.
운영에서 다른 값을 쓰기로 하면 그때 compose·환경변수에 넣고 이 칸을 `✅`로 바꾼다.

**🔴 이 값은 채널이 아니라 「세션과 시점」에 따라서도 변한다.** 같은 채널·같은 방송 안에서도
움직였다 — 송출 재시작 직후 5초 넘게 튀었다가 8분 뒤 정상으로 돌아왔고, 매 회차의 처음
한두 번이 리듬을 탄 뒤보다 1초쯤 느렸다(다음 사건을 예상하는가에 따라 반응 지연이 갈린다).
그래서 아래 **채널별 덮어쓰기만으로는 부족할 수 있다.** 기본값을 「리듬 탄 상태」의 3,800
(각 회차의 처음 한두 번을 뺀 열한 표본의 중앙값)이 아니라 첫 회차를 포함한 전체 중앙값으로
고른 것도 같은 이유다 — **실제 하이라이트는 시청자가 예상하지 못한 순간에 터진다.**

**🔴 크기가 ±600000ms(10분)를 넘으면 부팅이 거부된다** — 기본값도 채널별 값도 같다.
상한을 10분에 둔 근거는 이 값이 무엇의 합인지다 — 지배 항인 방송 지연·반응 지연이
**초 단위**이므로 합의 현실적 크기는 수 초~수십 초이고, 10분은 거기에 10배 이상의 여유를
준 자리다. 그때 그 「초 단위」는 **추정**이었는데 **위 실측(3.9초)이 그것을 확인했다** —
그래도 상한은 안 좁힌다. 여유가 목적이고, 위 한계 다섯 때문에 운영 실측이 이보다 클 수 있다.

**🔴 이 그물은 「위쪽」만 자른다 — 자릿수 착각이 전부 걸리는 것이 아니다.** ms를 마이크로초로
착각한 값(1.5초 → 25분)도 나노초로 착각한 값(1.5초 → 17일)도 걸리지만, **반대 방향은 안
걸린다**: 3.9초를 `3900` 대신 `4`(초 단위)로 적으면 4ms가 그대로 통과하고 서버는 멀쩡히 뜬다.
**하한을 둘 수 없기 때문이다** — `0`이 「보정 안 함」이라는 유효한 값이고 이 설정의 자리 표시
상태이기도 하다. 결말이 가벼운 것도 이유다(위치가 그만큼 덜 정확할 뿐, 아래 큰 쪽 착각처럼
`no_footage`로 가지 않는다). 다만 **`0`으로의 되돌림 하나는 검사가 잡는다**
(`VideoPositionCalculatorTest.실물_기본_보정값이_자리_표시_0이_아니다()`).

**부팅에서 막는 이유는 그 착각이 조용하기 때문이다.** 창구의 `messageTime` 그물(1970~2200)은
**보정을 빼기 전** 값에 걸리므로 보정 후 값에는 아무 그물이 없다. 17일짜리 보정을 넣으면
입력은 정상이라 400에 안 걸리고, 보정된 시각이 첫 조각보다 이르러 `no_footage`가 나간다 —
부르는 쪽은 「영영 없음」으로 읽고 재시도를 그만두며, 채팅에는 백필이 없어 그 방송의
하이라이트가 전부 사라진다. **그때 서버는 정상으로 뜨고 로그도 조용하다.**

**채널별 덮어쓰기는 환경변수가 아니라 yml이다** — `pokeclip.sync.channel-offset-ms.<채널ID>: <ms>`.
채널마다 값이 달라 변수 이름을 미리 정할 수 없다. 🔴 **빈 맵(`channel-offset-ms: {}`)을
적으면 부팅이 통째로 죽는다** — 빈 문자열로 평탄화돼 `Map`으로 바인딩되지 않고, 이 설정은
모든 스프링 컨텍스트에 올라가므로 검사 전부와 운영 부팅이 같이 죽는다. **줄을 아예 안 적으면**
빈 맵이 된다.

**🔴 채널 키만 적고 값을 빠뜨리면(`streamer-a:`) 그 줄이 조용히 사라진다**(2026-08-24 실측).
빈 문자열로 평탄화된 값을 Binder가 엔트리째 버려서 **부팅은 멀쩡하고 그 채널만 기본 보정값으로
떨어진다** — 죽지 않으므로 아무 신호가 없다. 결말은 「그 채널의 위치가 덜 정확하다」로 가볍지만
(위 자릿수 착각처럼 `no_footage`로 가지 않는다), **적어 놓고 안 걸리는 상태**라 오해하기 쉽다.
엔트리가 사라진 뒤라 설정 쪽에서는 잡을 수 없다. `SyncPropertiesValidationTest`가 이 동작을
못박아 둔다.

**🔴 이 표에 값을 더하면 `docker-compose.dev.yml`의 `chat-collector` 블록에도 같이 넣어라 —
`compose` 칸이 `—`인 것처럼 yml 기본값으로 충분한 값은 빼도 된다. 빼면 그 칸에 `—`를 적어라.
그것을 지키는 검사가 저장소에 하나도 없다**(`grep -rn "docker-compose" --include="*.java"` → 0건).
실제로 POK-127이 편지 변수 넷을 표에만 적고 compose에 안 넣어, **이 compose로 띄운 수집기가 편지를
한 통도 안 먹으면서 health는 초록**이었다(2026-08-23에 실물로 재현: 부팅 WARN `chat.internal_api.locked` +
올바른 토큰으로도 창구 401, `/actuator/health`만 200). **위험이 배포 시점에만 생기는 모양이라
로컬·CI는 전부 초록이다.**

> 「dev로 띄운」이 아니라 **「이 compose로 띄운」**이다 — `services-deploy.yml`이 dev EC2에 올리는 것은
> `postgres`·`auth`·`clip` 셋뿐이라 **dev에는 수집기가 아예 없다**(critic round3b). 이 구멍이 실제로
> 드러나는 자리는 개발자 로컬의 `docker-compose.dev.yml`과, 수집기를 배포에 넣는 날이다.

**`AUTH_BASE_URL`·`INTERNAL_API_TOKEN`이 비면 부팅이 죽는데, 켜져 있을 때만 그렇다.**
둘을 쓰는 부품이 **큐가 켜졌을 때만 만들어지기** 때문이다 — 안 그러면 CI와 남의 로컬이
쓰지도 않는 토큰이 없다고 매번 죽는다. 반대로 켜졌는데 비면 반드시 죽여야 한다:
안 막으면 서버는 뜨고 열쇠 조회만 전부 401이 되는데, **401은 재시도 갈래라 편지가
큐에서 영원히 돈다.**

`CHZZK_ACCESS_TOKEN`은 **유저 Access Token**이다. 채팅 구독은 Client 인증으로 못 받는다.
**편지 경로에서는 이 값을 안 쓴다** — 스트리머마다 토큰이 다르므로 방송이 열릴 때
`POST /internal/chzzk-link/resolve`로 그 스트리머의 것을 받아 온다.
만료된 토큰(수명 24시간)은 빈 값이 아니라 검증에 안 걸린다. 그때는 **재시도해도 영원히
안 풀리므로 다시 붙지 않고** `chat.session.stopped stage=AUTH reason=SESSION_AUTH_REJECTED`를
남기고 멈춘다 — 401·403이 여기다. 반대로 5xx·타임아웃은 `SESSION_AUTH_FAILED`로 갈라
**재연결이 계속 시도한다.** 하나로 뭉치면 5xx 한 번에 영구 정지하거나 만료 토큰으로
영원히 재시도한다.

**구독 단계도 같은 규칙이다** — `stage=SUBSCRIBE reason=SUBSCRIBE_REJECTED`(401·403,
영구 정지) 대 `SUBSCRIBE_FAILED`(그 밖, 계속 재시도). **발급은 200인데 구독만 401인
토큰이 실제로 있다** — 토큰은 살아 있고 `채팅 메시지 조회` Scope나 동의만 빠진 경우다.
그때 사유를 안 가르면 발급이 매번 성공하므로 **세션 발급 API를 영원히 두들긴다.**
단계를 값으로 나눠 둔 이유는 조치가 다르기 때문이다 — 발급 거부는 토큰을 다시 받고,
구독 거부는 앱의 Scope와 동의를 본다.

**재연결 간격은 실측으로 정했다** — 첫 **1초**, 상한 **60초**(두 배씩 증가).
`CHZZK_RECONNECT_FIRST_DELAY`·`CHZZK_RECONNECT_MAX_DELAY`로 바꿀 수 있지만
**평소에는 건드리지 않는다.** 발급만 하고 안 붙은 세션은 연결 자리를 안 먹고(그래서
1초여도 재시도가 스스로 한도를 태우지 않는다), 서버가 자리를 놓아주는 시한이 최악
85초라 상한 60초면 상시 점유가 1개 남짓이다.

**바꿀 때 `1ms ≤ 첫 간격 ≤ 상한`을 벗어나면 부팅에서 죽는다.** 0이나 음수는 대기가
통째로 사라져 백오프 없이 세션 발급 API를 두들기고, 첫 간격이 상한보다 크면 그 첫
간격이 조용히 버려져 **시도마다 상한 하나만** 돈다 — 적어 둔 값과 실제로 도는 값이
달라진다. 둘 다 놔두면 **서버는 뜨고 헬스체크도 통과하는데 재연결만 잘못 돈다.**

받고 있는지는 헬스체크에 나온다.

```bash
curl -s localhost:8083/actuator/health
# {"collectorHealth":{"status":"UP","details":{
#   "status":"disabled","activeSessions":2,"reconnectingSessions":0,
#   "letterIntake":"ok","lastLetterPollAt":"2026-08-18T12:00:00Z","letterFailure":"none",
#   "unreadableStreamerIds":0,"unknownTypes":0,"malformedEnvelopes":0}}}
```

**방송 둘이 붙어 있는데 `"status":"disabled"`인 것이 정상이다 — 고장이 아니다.**
그 칸은 **옛 경로(`CHZZK_ENABLED`) 전용**이라 편지 경로에서는 **언제나 `disabled`**다.
편지로 연 세션은 방송마다 상태를 따로 들고, 그 수가 `activeSessions`·`reconnectingSessions`다.
**편지 경로가 도는지는 `status`가 아니라 `letterIntake`로 본다**(`ok`·`starting`·`failing`·`disabled`).

**DOWN의 뜻이 POK-127에서 바뀌었다.**

| | 전 | 후 |
|---|---|---|
| 세션 하나가 재연결 중 | **서버 전체 DOWN** | **UP** — 나머지 아홉은 멀쩡히 받고 있다 |
| 붙어 있는 세션 0개 | UP(옛 경로가 꺼져 있어 우연히) | **UP** — 방송 없는 시간대가 정상이다 |
| 편지를 아예 못 꺼냄 | **health에 안 보였다** | **DOWN** + `letterIntake=failing`·`letterFailure` |

전체 DOWN은 이제 **"이 프로세스가 새 방송을 하나도 못 받는 상태"**를 뜻한다. 방송 하나가
끊긴 것은 그 방송의 문제이지 프로세스의 문제가 아니라서 `reconnectingSessions`로만 드러낸다.
**옛 경로(`CHZZK_ENABLED`)의 DOWN 규칙은 안 바뀌었다** — 거기엔 세션이 하나뿐이라
"세션 하나 = 이 프로세스가 할 일 전부"라는 옛 전제가 여전히 참이다.

`unreadableStreamerIds`·`unknownTypes`·`malformedEnvelopes`는 **판정기가 버린 편지 수**다.
셋을 안 합쳤다 — 1번이 고칠 자리가 각각 다르다(식별자 체계 / 모르는 종류 / 봉투의 칸).
**이 셋은 DOWN을 만들지 않는다.** 편지는 계속 오고 폴링도 성공하므로 "못 받는 상태"가
아니고, 임계를 health가 정하면 계약 밖 종류가 하나 섞이는 정상 운영에서도 빨간불이 뜬다.

> ⚠ **이 헬스체크를 liveness 프로브에 직접 걸면 재시작 루프가 된다.**
> 큐 장애로 DOWN이 되는 것도, 옛 경로의 재연결 DOWN도 **재시작으로 안 풀린다.**
> readiness에 걸거나 DOWN 지속 시간으로 판단한다.

### 🔴 종료 유예를 20초 준다

**종료가 구독 반납 왕복과 마지막 채팅 저장을 기다린다.** 반납 도중에 인터럽트하면
세션 키는 이미 소모된 뒤라 **아무도 다시 못 보내고, 스트리머당 3개뿐인 자리가
하나 남는다.** 치지직이 느린 날 재시작을 반복하면 그대로 마른다.

**예산 산수가 경로마다 다르다. 둘 다 20초 안이다.**

| 항 | 편지 경로 | 옛 경로 | |
|---|---|---|---|
| 마지막 편지 회차 join | **2초** | — | 롱폴링 회차(최대 20초)는 **안 기다린다** — 빗장을 걸어 세션을 못 열게 한다 |
| 재연결 스레드 대기 | — | **9초** | 옛 경로 러너 전용 |
| 세션 닫기(반납+소켓) | **8초** — flush **앞** | 뒤에 따로 | 반납 REST 시한(접속 2 + 읽기 5) + 소켓 닫기 1. **세션 수와 무관하다 — 나란히 나간다** |
| 마지막 flush 대기 | **5초** | **5초** | 적재·아카이브가 같이 쓴다 |
| **합** | **15초** | **14초 + 저장 실행분 + 마지막 정리** | |

**순서가 두 경로에서 다르다.** 편지 경로는 **세션을 먼저 다 닫고** 그 뒤에 flush 한다.
옛 경로는 반대로 flush가 먼저이고 **마지막 정리가 반납을 한 번 더 보낼 수 있다** —
그래서 옛 경로의 합에는 꼬리가 하나 더 붙는다.

**세션이 몇 개든 8초다.** 반납이 배치로 나가지 않는 것을 실계정으로 쟀다 — 세션 셋을
동시에 반납하면 **총 소요가 최대 개별 소요와 같다**(67ms·69ms, 전 구간 HTTP/2).
검사에서 열 개를 800ms씩 붙들어도 전체 **0.88초**로 끝나고, 순차 루프로 되돌리면 **8.08초**다.
**8초는 관측이 아니라 시한이다** — 평시 왕복 55~69ms는 근처도 안 간다.

**도커 기본 유예는 10초라 모자란다.**
저장 실행분은 DB가 연결만 받고 답을 안 하는 반개방 스톨이면 JDBC `socketTimeout`
**10초**까지 늘어나는데, 그때는 5초 대기가 먼저 끊겨 유예는 지켜지고 **그 배치는
잃는다**(`chat.persist.close_timeout`이 단서).
**S3 아카이브의 마지막 업로드는 DB 저장기와 나란히 닫혀 예산이 안 는다** — 제출만
하고 돌아온 뒤 저장기의 5초 동안 같이 돈다. 알려진 한계 하나: 대기 줄이 수십 개
남은 채 창고가 살아 있으면(장애 직후 회복된 순간 종료) 파일 수 × 왕복 시간이 5초를
넘길 수 있다 — 그때 `chat.archive.close_timeout`이 남고 못 올린 나머지는 잃는다
(사용자 지적, 2026-08-16).

```yaml
stop_grace_period: 20s            # compose
terminationGracePeriodSeconds: 20 # k8s
```

**급사시키면 서버가 죽은 전송을 알아챌 때까지 자리가 남는다** — 우아하게 끊으면
약 1초, 급사하면 10초에서 4분 42초 사이로 튄다(실측).

> **이 「약 1초」를 반납 REST 예산으로 쓰지 마라.** 순수 왕복은 **55~69ms**다(실계정 2회, POK-127).
> 1초 쪽은 소켓 닫기까지 포함한 종료 절차 전체를 잰 값으로 보이는데, **그때 항을 나눠 재지
> 않았으므로 추정이다.** 위 예산표의 8초는 이 1초가 아니라 REST 시한(접속 2 + 읽기 5)에서 왔다.

**치지직 실서버에 붙어 보는 테스트는 기본으로 안 돈다.** 돌리려면
`CHZZK_LIVE_PROBE=true`를 **명시적으로** 준다.

토큰이 있다는 것과 "지금 실서버를 때려도 된다"는 것은 다르다. 토큰 환경변수의
존재로 열어 두면, **토큰 수명이 24시간이라 만료되는 순간 토큰을 가진 사람의
빌드가 매일 빨간불이 된다.**

**DB 접속 변수 이름을 compose의 `.env`와 맞춰 뒀다** (`POSTGRES_USER`·`POSTGRES_PASSWORD`·
`POSTGRES_DB`). 팀원이 `.env` 값을 바꿔도 앱이 따라간다. compose 네트워크 안에서
띄울 때만 `DB_HOST=postgres`를 준다 — **기본값이 있는 것은 `DB_HOST`·`DB_PORT` 둘뿐**이고
(`localhost`·`5432`), 그 둘은 `.env`에 없어서 지우면 로컬 기동이 즉시 깨진다.

**셋에는 기본값이 없다**(POK-161). 커밋되는 파일에 비밀번호 기본값을 두면 **public
저장소에 공개된 값으로 DB에 붙는 창**이 열리기 때문이다. 실행에 필요한 것은 위
「환경변수 열둘」 표를 본다.

**이 방식을 다른 시크릿에 확대하지 않는다.** 여기가 통하는 것은 서버가 실제로 접속을
시도하는 값이라서다 — 값이 없으면 리터럴 `${POSTGRES_PASSWORD}`가 그대로 비밀번호가
되고 DB가 거절해 죽는다(2026-08-17 실측: `FATAL: password authentication failed`).
**접속을 시도하지 않는 값(서명키·내부 토큰)에 같은 모양을 쓰면 서버는 그냥 뜨고
그 기능만 조용히 전부 실패한다.**

### 도커 이미지

**빌드 컨텍스트는 저장소 루트가 아니라 `services/`다.** Gradle 루트가 여기라
루트에서 빌드하면 `settings.gradle`을 못 찾는다.

```bash
docker build -f services/auth/Dockerfile -t pokeclip-auth services/
```

베이스 이미지는 네 서버 모두 `eclipse-temurin:21-jdk`(빌드) →
`eclipse-temurin:21-jre`(실행)로 통일했다.

**테스트도 팀과 같은 이미지를 쓴다** — Testcontainers가 `postgres:17`을 띄운다.
compose와 메이저 버전이 갈리면 로컬·CI만 통과하고 실제 DB에서 깨지는 차이를 못 잡는다.

## DB 마이그레이션

Flyway 마이그레이션은 앱이 뜰 때 실행돼야 하므로 **코드 옆(`auth/src/main/resources/db/migration/`)에 둔다.**

서버마다 자기 Flyway를 돌리고 **이력 테이블을 나눈다**(`flyway_schema_history_auth` ·
`..._clip` · `..._chat`). 기본 이름을 쓰면 나중에 뜬 쪽이 남의 이력을 자기 것으로 읽고 부팅에 실패한다.
마이그레이션 번호는 모듈별 대역을 쓴다 — `V1xx` auth · `V2xx` clip · `V3xx` chat.
지금까지 나간 것은 auth의 `V101`~`V107` · clip의 `V201`(`broadcasts`·`broadcast_events`)과
`V202`(`jump_cards`, POK-118)·`V203`(`broadcasts.vod_expires_at`, POK-117) · chat의 `V301`(`chat_messages`)이다.

**모든 Flyway 서버(auth 포함)에 `baseline-on-migrate: true` + `baseline-version: 0`이 필수다.**
공유 DB에서는 어느 서버가 먼저 뜰지 정해져 있지 않다 — 다른 서버가 이미 표를
만들어 놓은 DB에 자기 이력 테이블 없이 뜨는 서버는 baseline 없이
`Found non-empty schema(s) "public" but no schema history table`로 부팅이 죽는다.
"두 번째 서버부터"가 아니다: 빈 DB에 chat-collector가 auth보다 먼저 뜨면 auth가
그 두 번째 서버다. chat-collector가 실물에서 밟았고(2026-08-15), auth도 같은
메시지로 재현했다(PR #56). Testcontainers·CI는 매번 빈 DB라 그냥은 안 잡히므로
auth·clip·chat-collector의 `IntegrationTestSupport`가 남의 표를 먼저 심어 두고 부팅한다 —
두 줄을 지우면 그 모듈 테스트 전체가 빨강이다. **clip도 POK-82에서 같은 두 줄을 갖췄다**
(남는 것은 chat-detector뿐이다). `baseline-version: 0`인 이유는 기본 1이면 V1
이하가 적용 대상에서 빠지기 때문이다.

**이력이 한 줄이 아니라 두 줄인 것이 정상이다.** 남의 표가 있는 DB에 처음 뜨면
`<< Flyway Baseline >>`(version 0, type `BASELINE`)이 먼저 찍히고 그다음에 자기
마이그레이션이 온다. clip 실기동 확인(2026-08-18):

```
 installed_rank | version |              description               |   type   | success
----------------+---------+----------------------------------------+----------+---------
              1 | 0       | << Flyway Baseline >>                  | BASELINE | t
              2 | 201     | create broadcasts and broadcast events | SQL      | t
```
1·2번이 읽을 스키마 설명서는 여기서 자동 생성해 [`contracts/db/`](../contracts/db/)로
내보낼 계획인데 **아직 안 만들었다** — 그 폴더는 비어 있다.

## 상태 (2026-08-18)

`auth`·`clip`·`chat-collector`에 내용이 있다.

| | |
|---|---|
| 인증 | 구글 로그인·자동가입 · 토큰 발급/회전/로그아웃 · `/api/auth/me` |
| 스트림키 | 발급 · 검증(계약4) · 페어링 코드 발급/교환 · 재발급 (POK-56) |
| 채널 연동 | 치지직 동의 왕복 · 토큰 보관(참조만) · 10분 주기 자동 갱신 · 수집기용 resolve · 해제·상태 조회 (POK-93) |
| 편집자 위임 | 이메일 초대 · 초대함 수락/거절 · 보낸 초대 취소 · 위임 조회 · 양방향 해제 (POK-57) |
| 운영 | 이벤트 로깅 · 요청 상관 ID · CORS · 구글 호출 타임아웃 |

표는 아홉이다 — `users`·`refresh_tokens`(V101·V102) ·
`secrets`·`stream_keys`·`pairing_codes`·`pairing_exchange_attempts`(V103~V106) ·
`chzzk_channel_links`(V107) · `editor_invitations`·`editor_delegations`(V108).

엔드포인트 열: 스트림키 다섯(**계약4 = `POST /internal/stream-keys/resolve`** — 1번 Media가 SRT 연결을
받기 전에 한 번 부른다) · 치지직 연동 다섯 · 편집자 위임 아홉 · **clip용 내부 창구 둘**(POK-175, 아래 절).
`contracts/api/`에 정본이 아직 없어 여기 적어 둔다.

| | 부르는 쪽 | 인증 |
|---|---|---|
| `GET /api/stream-keys` | 웹 | 사용자 JWT |
| `POST /api/stream-keys/rotate` | 웹 | 사용자 JWT |
| `POST /api/stream-keys/pairing-codes` | 웹 | 사용자 JWT |
| `POST /api/stream-keys/pairing-codes/exchange` | OBS 플러그인 | **없음** (코드 자체가 자격증명) |
| `POST /internal/stream-keys/resolve` | **Media(1번)** | `X-Internal-Token` 헤더 |
| `POST /api/chzzk-link/start` | 웹 | 사용자 JWT |
| `POST /api/chzzk-link` | 웹 | 사용자 JWT |
| `GET /api/chzzk-link` | 웹 | 사용자 JWT |
| `DELETE /api/chzzk-link` | 웹 | 사용자 JWT |
| `POST /internal/chzzk-link/resolve` | **chat-collector** | `X-Internal-Token` 헤더 |
| `POST /api/youtube-link/start` | 웹 | 사용자 JWT |
| `POST /api/youtube-link` | 웹 | 사용자 JWT |
| `GET /api/youtube-link` | 웹 | 사용자 JWT |
| `DELETE /api/youtube-link` | 웹 | 사용자 JWT |
| `POST /internal/youtube-link/resolve` | **clip·업로드 워커** | `X-Internal-Token` 헤더 |
| `POST /api/editor-invitations` | 웹 | 사용자 JWT |
| `GET /api/editor-invitations/sent` | 웹 | 사용자 JWT |
| `GET /api/editor-invitations/received` | 웹 | 사용자 JWT |
| `DELETE /api/editor-invitations/{id}` | 웹 | 사용자 JWT |
| `POST /api/editor-invitations/{id}/accept` | 웹 | 사용자 JWT |
| `POST /api/editor-invitations/{id}/decline` | 웹 | 사용자 JWT |
| `GET /api/editor-delegations/as-streamer` | 웹 | 사용자 JWT |
| `GET /api/editor-delegations/as-editor` | 웹 | 사용자 JWT |
| `DELETE /api/editor-delegations/{id}` | 웹 | 사용자 JWT |
| `POST /internal/editor-delegations/resolve` | **clip** | `X-Internal-Token` 헤더 |
| `POST /internal/editor-delegations/accessible` | **clip** | `X-Internal-Token` 헤더 |

`resolve`는 **키가 틀려도 HTTP 200에 `valid:false`**로 답한다. Media에게
"키가 틀림"(연결 거절)과 "Auth 장애"(판단 불가)는 조치가 정반대라 둘 다 4xx면
Go 쪽에서 구분이 안 된다.

### clip — 방송 생명주기 수신 (POK-82)

1번 Media가 SQS FIFO 큐에 넣는 **계약9 봉투**(ADR-016)를 받아 방송 명부에 기록한다.
표는 둘이다 — `broadcasts`(방송 한 회당 한 줄) · `broadcast_events`(받은 편지 기록, `V201`).

| | |
|---|---|
| 수신 | SQS FIFO 롱폴링(20초) · 꺼둠이 기본 |
| 멱등 | 같은 편지가 두 번 와도 한 번만 처리 (POK-87) |
| 순서 | 역순 도착을 견디고 상태를 되돌리지 않음 (POK-88) |
| 운영 | health에 수신 상태 노출 |

**멱등의 방어선은 `INSERT … ON CONFLICT (event_id) DO NOTHING`의 영향 행 수다.**
0이면 중복, 1이면 새 편지다. 조회 후 삽입은 동시 요청에 뚫리고, **예외 타입으로 가르는
방식도 안 된다** — `event_id` 중복과 `streamer_id` NOT NULL 위반이 같은
`DataIntegrityViolationException`이라 **저장 실패가 중복으로 보고돼 러너가 메시지를 지운다.**
반환값으로 가르면 진짜 실패만 예외로 올라가 러너가 메시지를 남긴다.
편지 기록과 명부 갱신은 **한 트랜잭션**이다 — 갈라 두면 "기록은 남았는데 명부는 안 바뀐"
줄이 생기고, 재전송돼도 중복으로 걸러져 영영 반영되지 않는다.

**종료가 시작보다 먼저 와도 죽지 않는다.** 시작 시각을 모르는 채로 줄이 생기고
(`started_at`이 NULL인 것이 곧 "역순으로 도착했다"는 표시다) 경고가 남는다.
이미 반영한 `last_sequence`보다 낮은 번호가 뒤늦게 오면 무시한다 —
**순서를 바로잡는 것이 아니라 견디는 것**이 목표다. 끝난 방송은 더 높은 번호의 시작이
와도 `ENDED`로 남고 시작 시각만 채운다.

**러너가 편지를 지우는 기준은 "성공"이 아니라 "더 볼 일 없음"이다.** 처리됨·중복·낡음
셋 다 지운다. 읽을 수 없는 편지와 **모르는 `eventType`도 지운다** — 재시도해도 계속
실패하는데 안 지우면 FIFO라 **같은 방송의 뒤 편지가 전부 막힌다.** 로그 키는 갈라
뒀다(`unreadable_dropped` 대 `unknown_type_dropped`) — 후자는 1번이 새 이벤트를 냈다는
신호라 형식 오류와 섞으면 안 된다. 반대로 **처리가 예외로 끝나면 안 지운다** —
가시성 타임아웃이 지나면 다시 오고, 처리가 멱등이라 두 번 와도 안전하다.

`streamer_id`에 FK를 걸지 않는다 — 그 표는 auth 소유이고, 서로의 표를 직접 참조하지
않는 것이 ADR-022의 경계다.

**아직 없는 것:** 렌더 잡 발행(계약1), 세그먼트 인덱스 수신(계약2), 클립·승인.

### clip — 점프카드 저장·SSE 전송 (POK-118)

판별기(`chat-detector`)가 「이 순간 채팅이 터졌다」고 보낸 지점을 **점프카드**로 보관하고,
그 방송을 보고 있는 웹 화면에 **SSE로 실시간으로 밀어 넣으며**, 편집자 둘이 같은 카드를
동시에 집지 못하게 **점유**를 건다. 표는 `jump_cards` 하나(`V202`).

**clip의 첫 사람용 API이고, 첫 시큐리티이며, 첫 비동기 전송이다.**

| 문 | 인증 | 응답 |
|---|---|---|
| `POST /internal/broadcasts/{streamId}/highlights` (계약 2A) | `X-Internal-Token` | **201** 새 카드 · **200** 같은 창이 이미 있음(기존 카드 그대로) · 404 `{"error":"broadcast_not_found"}` · 400 `{"error":"invalid_request","field":…}` · 401 |
| `GET /api/clip/broadcasts/{streamId}/events` (계약 2B, SSE) | Bearer JWT | **200** `text/event-stream` · 404 · 401 · **503** `{"error":"stream_limit","scope":"user\|stream\|total"}` |
| `POST /api/clip/jump-cards/{id}/claim` | Bearer JWT | 200 카드 · **409 본문이 현재 카드**(누가 잡고 있는지) · 404 `{"error":"jump_card_not_found"}` |
| `DELETE /api/clip/jump-cards/{id}/claim` | Bearer JWT | **204** · 403 `{"error":"not_claim_owner"}` · 404 |
| `POST /api/clip/jump-cards/{id}/hide` | Bearer JWT | 200 카드(`hidden:true`) · 404 |
| `DELETE /api/clip/jump-cards/{id}/hide` | Bearer JWT | 200 카드(`hidden:false`) · 404 |

**중복 방어선은 `(stream_id, source, window_start_ms)` UNIQUE와 `ON CONFLICT … DO NOTHING`의
영향 행 수 하나뿐이다.** POK-82와 같은 이유로 예외가 아니라 반환값으로 가른다 — 예외로 가르면
FK·CHECK 위반이 중복으로 보고돼 판별기가 "성공"으로 읽고 다시 안 보낸다. `eventId`는 **추적용**이라
UNIQUE가 아니다(재전송 때 값이 달라도 같은 창이면 같은 카드다).
**`eventId`는 128자 이내다** — 넘으면 400 `{"error":"invalid_request","field":"eventId"}`.
칸이 `VARCHAR(128)`이고, 검증이 없으면 DB까지 가서 **500**이 나가는데 그러면 판별기가 같은
payload로 영영 재시도한다(2026-08-23 실측). **판별기 세션에 전할 것.**

**점유는 집을 때 판정한다.** 만료를 치우는 배경 작업이 없다 — `UPDATE … WHERE id = ? AND
(claimed_by IS NULL OR claimed_by = ? OR claimed_at < now() - interval)` 한 줄이 원자적이라
동시 요청 둘 중 하나만 영향 행 1을 받는다. 시각 비교는 **DB 시계**다(앱 시계는 서버마다 다르다).
본인 재호출은 **연장**이고, 아무도 안 잡은 카드를 놓는 것은 **성공**이다(멱등 — 403을 주면
웹이 새로고침 뒤 놓기를 눌렀을 때 오류를 본다).

**`claim-ttl`은 100년(`P36500D`)을 넘으면 부팅을 거부한다.** 상한이 없으면 아주 큰 값이 통과하고
**서버는 멀쩡히 뜨는데 claim만 전부 500**이 된다 — `toSeconds()`가 넘긴 값을 DB가 못 받는다
(`ERROR: interval out of range` · SQLState **22008**). 하류가 셋이고 한계가 다르다(실측):
점유 SQL이 약 **6738년**에서 가장 먼저 터지고, `make_interval` 자체와 `claimedAt.plus(ttl)`은
그보다 훨씬 위다. **가장 좁은 것이 PostgreSQL 구현 세부라 그 숫자 대신 67배 아래인 100년으로
잡았다** — 편집자가 카드 하나를 100년 붙들 일은 없다. 셋의 정확한 경계는
`JumpCardProperties.MAX_CLAIM_TTL` 주석에 있다.

**SSE 이벤트는 둘뿐이다 — `card`와 `ended`. 둘 다 `data:` 줄이 반드시 있다.**

| 이름 | 나가는 바이트 |
|---|---|
| `card` | `id:{eventSeq}` · `event:card` · `data:{카드 JSON}` |
| `ended` | `event:ended` · `data:{}` — 방송이 끝났다. 서버가 곧 연결을 닫는다 |
| 주석 | `:ok`(연결 직후 한 번) · `:ping`(하트비트). **이벤트가 아니라 무시된다** |

🔴 **`data:` 줄이 없으면 브라우저가 그 이벤트를 통째로 버린다.** WHATWG HTML 9.2.6
「dispatch the event」 2단계가 *"If the data buffer is an empty string, set the data buffer and
the event type buffer to the empty string and return"* 이라 `MessageEvent`를 만드는 4단계에
도달하지 못한다 — `addEventListener("ended", …)`가 **안 불린다**.
`ended`가 실제로 `event:ended\n\n`로 나가고 있었고 **Chrome 148과 undici(WHATWG 구현)가
둘 다 버렸다**(2026-08-23 재현). 시험 파서가 규약보다 관대해 **`ended` 시험 여섯 개가
헛통과하고 있었다** — 그 파서(`SseReader`)도 규약대로 고쳤다.
**새 이벤트를 만드는 사람은 `data`를 반드시 넣는다.**

**같은 카드의 낡은 갱신은 안 나간다.** 이미 더 큰 `eventSeq`를 보낸 카드에 대해 낮거나 같은
순번이 오면 버린다. 자바 모니터가 공정하지 않아 두 수정의 `afterCommit`이 뒤집힌 순서로
자물쇠를 얻을 수 있고, 그러면 **낡은 스냅샷이 새 상태를 덮는다** — 대기 순서를 강제하면
**100회 중 100회** 뒤집혔고 놓은 카드가 집힌 것으로 남았다(2026-08-23 재현).
**웹이 알아야 할 것: 중간 상태가 빠질 수 있다.** 카드는 사건의 나열이 아니라 **상태**라
최신이 맞으면 화면이 맞다.

**카드가 생겼다/바뀌었다를 알리는 출구는 `CardStreamRegistry.publish` 하나다.**
발행은 **커밋 뒤**에만 한다(`afterCommit`에서 제출만) — 커밋 전에 보내면 되감긴 카드가 화면에
뜨고 지울 방법이 없다. 전송은 요청 스레드가 아니라 **전용 스레드**가 한다(POK-93에서 커밋 뒤
처리를 요청 스레드에 이어 붙였다가 커넥션 풀 데드락을 낸 자리와 같은 분리).

#### 🔴 clip은 한 대여야 한다

**연결 보관소가 프로세스 안 메모리다.** 2대로 띄우면 카드를 받은 인스턴스에 붙어 있는 사람만
받고 **나머지 절반은 아무것도 못 받는데 에러도 안 난다** — 화면이 조용히 낡는다.
여러 대로 가려면 `publish` 안을 Redis 발행/구독으로 갈아끼워야 하고, 그것은 별도 카드다.
갈아끼울 자리를 한 곳에 모아 둔 이유가 이것이다.

#### SSE 운영값 — 전부 추정이다

| 값 | 기본 | 근거 |
|---|---|---|
| `heartbeat` | 20초 | 앞단 프록시가 조용한 연결을 끊지 않게. **로컬엔 프록시가 없어 배포 후에만 드러난다.** 1ms 미만과 **ms로 자를 수 없을 만큼 큰 값**(`PT2562047788016H` 이상)은 기본값으로 덮는다 — 양쪽 끝이 다 부팅을 죽인다 |
| `timeout` | 4시간 | 연결 수명 상한 |
| `stripes` | 4 | 전송 스레드 수. 연결은 스트라이프 하나에 고정돼 순서가 지켜진다. **1024를 넘으면 기본값으로 덮는다** — 스트라이프 하나가 스레드 하나이고 연결 상한(`max-total` 500)보다 많으면 영영 안 쓰이는 스레드다. 안 막으면 `OutOfMemoryError: Requested array size exceeds VM limit`로 **부팅이 죽는다**(실기동) |
| `queue-capacity` | 1000 | 스트라이프당 대기 상한. 넘치면 버리고 WARN — **실시간 발행만** 재연결이 메운다(초기 스냅샷은 태스크 하나라 큐를 한 칸만 쓴다) |
| `max-per-user` | 4 | 탭 몇 개 + 모바일 |
| `max-per-stream` | 50 | 스트리머 + 편집자(정원은 POK-207 미정) |
| `max-total` | 500 | 서버 한 대 전체 |

**측정값이 아니라 추정이다.** 동시 사용자 100명 전제에서 넉넉히 잡았고 실사용 후 조정한다.

**연결 수명 = min(4시간, 토큰 exp까지 남은 시간)**이다. 만료 시점에 닫히고 브라우저가 새 토큰으로
다시 붙는다. **`exp`가 이미 지났으면 아예 열지 않는다(401)** — 디코더의 clock skew 허용치(60초)
안쪽 토큰은 인증을 통과하는데, 그대로 열면 남은 수명이 음수가 되고 **서블릿 규약상 `timeout <= 0`은
「시한 없음」이라 만료된 토큰일수록 연결이 더 오래 산다**(실측: `-59311ms` → 45초 뒤에도 살아 있음).

**상한 검사는 스냅샷 조회 <u>앞</u>에서 한 번 더 한다.** 거절될 요청이 자물쇠 안에서 그 방송
카드를 전부 읽지 않게 하는 사전 검사다. 자리를 잡는 것과 원자적인 최종 판정은 그대로
`open()`에 있고, **둘 다 `checkLimits` 하나를 부른다** — 조건을 두 곳에 적으면 언젠가 갈리고,
갈리는 순간 사전 검사만 통과해 헛읽기가 그대로 돌아온다.

고치기 전에는 **503 한 번마다 조회가 정확히 한 번** 돌았다(1615회에 1615회, 비율 1.00).
재연결 루프 한 스레드가 5초 중 **41~72%** 동안 자물쇠를 잡고 있었고, 그동안 카드 발행이 밀렸다.
고친 뒤(2026-08-24 실측, 같은 장치):

| | 고치기 전 | 고친 뒤 |
|---|---|---|
| 503 1회당 스냅샷 조회 | **1.00회** | **0회** |
| 5초 중 조회에 쓴 시간 | 2,056ms(300장) · 3,594ms(1200장) | **0ms** |
| `publish` 막힘 중앙값 | 499us(300장) · **2,010us**(1200장) | **41us · 40us** |
| `publish` 막힘 최대 | **32,125us**(300장) | 368us · 1,972us |
| `broadcastEnded` 막힘 중앙값 | 499us · 1,967us | **1us · 1us** |

**막힘이 카드 수를 따라가지 않게 된 것이 요점이다** — 300장 41us와 1200장 40us가 같다.
자물쇠 안에 DB가 없어졌다는 직접 증거다(전에는 4배 규모에 4배 밀렸다).
거절되는 쪽은 전에도 안 아팠다(왕복 2~4ms). 아팠던 것은 **같은 자물쇠를 기다리는 남의 화면**이다.

**따라잡기는 전체 스냅샷이다.** 연결 직후 그 방송의 카드를 **전부**(숨긴 것 포함) 순번 순으로
보낸다. `Last-Event-ID`는 **받아서 로그에만 적고 쓰지 않는다** — 마진 방식으로 바꾸는 날 쓸 자리다.

**연결 직후 주석 한 줄(`: ok`)을 먼저 보낸다.** `SseEmitter`는 첫 쓰기가 있어야 응답을 커밋하는데,
카드가 0장인 방송(=방금 시작한 방송)에서는 쓸 것이 없어 헤더가 **다음 하트비트까지** 늦는다
(실측 5.449초, 최악 20초). 받는 쪽에는 「느리다」가 아니라 **「연결이 안 된다」**로 보인다.

#### 실기동 확인 (2026-08-23)

| 무엇 | 결과 |
|---|---|
| 2A 저장 → SSE 도착 | **0.079초** (기준 3초의 1/38) |
| 같은 창 재전송 | `201` 다음 **`200`**, 본문은 기존 카드 그대로, 행 하나 |
| 응답 헤더 | `Content-Type: text/event-stream` · `X-Accel-Buffering: no` |
| 하트비트 | 20초 간격으로 `:ping` |
| 토큰 없이 / 사람 토큰으로 `/internal` | `401` · `401` |
| 실행 중 WARN·ERROR | **0줄** |

#### 알려진 구멍

- **🔴 자격 판정을 아직 안 한다.** 문 여섯은 「토큰이 유효한 사람인가」까지만 본다 —
  **로그인만 하면 남의 방송 통로를 열고 남의 카드를 집고 숨길 수 있다.** PRD가 일부러 감수한
  것이고 「POK-175 전까지」라는 단서가 붙어 있었는데, **그 POK-175가 머지됐다**(PR #106).
  물어볼 창구가 생겼다 — `POST /internal/editor-delegations/resolve`·`/accessible`이
  `OWNER`·`EDITOR`·`NONE`을 돌려준다(ADR-047). **그래서 이유가 바뀌었다: 「창구가 없다」가
  아니라 「아직 안 붙였다」다.** 붙이는 것은 별도 카드다
- **연결을 여는 문은 트랜잭션을 먼저 연다(`@Transactional(readOnly = true)`). 지우면 안 된다.**
  스냅샷 조회가 `openWithSnapshot`의 자물쇠 **안**에서 도는데(그래야 「읽은 뒤 ~ 명부에 오르기 전」
  창이 안 열린다), 트랜잭션이 없으면 그 조회가 **자물쇠 안에서** 커넥션을 새로 얻는다. 풀이 비면
  **자물쇠를 쥔 채** `connection-timeout`(운영 기본 **30초**)만큼 기다리고 그동안 카드 발행·연결
  열기·종료 알림이 전부 막힌다(실측 풀 2·시한 3초에서 발행 **3142ms**·열기 **3116ms**).
  🔴 **거기서 되먹임이 생긴다** — `afterCommit`은 커넥션 반납 **전**이라(`activeConnections=1` 실측)
  막힌 발행이 커넥션을 **쥔 채** 기다린다. 외부 점유자 없이 **발행 둘만으로** 풀이 마른 채 시한까지
  유지됐다. 트랜잭션을 먼저 열면 커넥션 획득이 자물쇠 **앞**에서 끝난다(최악 막힘 743~2022ms →
  **0~1ms**). **대가는 방향이 뒤집힌 것**이다 — 이제는 커넥션을 쥔 채 자물쇠를 기다리는데, 커넥션
  보유가 **카드 0장 11ms · 300장 26ms**로 짧다(연결이 살아 있는 동안은 `active=0`이다.
  **SSE 수명 4시간과 무관하다**). `OpenDoesNotBlockPublishTest`가 그물이다
- **안 읽는 구독자가 자기 스트라이프를 최대 약 61초 막는다.** send 시한 장치를 **일부러 두지
  않았다** — `send()`와 `completeWithError()`가 같은 락이라 끊으러 간 스레드가 같이 멈춘다(실측).
  막힌 연결은 서버의 write timeout이 `IOException`으로 푸는데 **그 값이 약 61초다**(2회 실측,
  60973ms·60988ms). 같은 스트라이프의 다른 연결은 그동안 이벤트가 밀린다(4개면 최악 1/4)
- **큐가 넘치면 그 연결은 다음 전송 실패(최대 61초)까지 그 카드를 못 받는다.** 넘친 이벤트는
  버리고 `jumpcard.stream.rejected` WARN만 남긴다 — **실시간 발행이라면** 재연결이 전체
  스냅샷으로 메운다. **초기 스냅샷은 그 말이 통하지 않아서** 카드 수와 무관하게 태스크 하나로
  묶었다: 쪼개 제출하면 큐를 카드 수만큼 먹고, 넘친 카드는 **재연결해도 같은 자리에서 또
  잘린다**(2026-08-23 실측 — 1200장에서 201건 유실이 2회차에도 그대로, 처음 잘리는 것이
  `ended`라 **연결이 안 닫힌 채 남기까지** 했다). **대가는 밀림이다** — 스냅샷 300장을
  보내는 동안 같은 스트라이프의 다른 연결이 밀린다. **기계 부하가 값을 통째로 바꾸므로 두 조건을
  나란히 적는다**(전부 2026-08-23, 각 5회 실측) — **부하 중**(load 148, 같은 기계에 `yes` 20개)
  전송 **71~86ms**·밀림 **32~49ms** / **깨끗**(load 2.4~4.9) 전송 **32~114ms**·밀림 **15~24ms**.
  깨끗한 쪽 전송은 3회차 하나가 114ms로 튀어 부하 중 범위와 겹친다. 최악 밀림은 PRD 도착 기준
  3초의 **1.6%**(부하 중 49ms) · **0.8%**(깨끗 24ms)다.
  **전수 실행 중**(`:clip:test` 전체, 같은 JVM에서 앞선 시험이 먼저 돈 뒤)에는 전송 **17~24ms**·
  밀림 **5ms**로 또 달랐다 — 같은 기계·같은 부하라도 **단독이냐 전수냐**로 갈린다.
  숫자를 비교할 때 이 셋 중 어느 조건인지부터 맞춰야 한다
  **종료 알림만 예외다** — `ended`가 거부되면 메울 것이 없으므로(재연결 전까지 그 화면은
  끝난 방송에 남는다) **그 연결을 명부에서 뺀다**(`jumpcard.stream.ended_dropped`).
  안 빼면 죽은 연결이 상한을 먹고, 하트비트마저 같은 큐에서 거부돼 **회복 계기 자체가 큐에
  못 들어간다**(2026-08-23 재현: `connectionCount`가 60초 뒤에도 1). 빼도 emitter는 살아 있고
  클라이언트는 **재연결 때 스냅샷에서 `ended`를 받는다**.
  **연결을 끊지 않는 이유**는 거부 처리기가 `execute()`를 부른 스레드, 즉 카드를 저장한
  **요청 스레드**에서 돌기 때문이다. 거기서 `completeWithError`를 부르면 막힌 `send`의 락을
  기다리며 **요청 스레드가 JDBC 커넥션을 쥔 채 잠긴다**(실측 저장 한 건 59초 → 풀 고갈).
  큐가 차는 상황은 그 연결이 **이미 안 읽고 있다**는 뜻이고, 정상 클라이언트가 순간 밀리는 것은
  큐 1000이 흡수한다
- **🔴 「재연결이 메운다」가 <u>언제</u>인지가 이 구멍의 크기다 — 그 연결의 남은 수명이다.**
  위 항목이 기대는 회복은 **재연결**인데, `publish`가 거부돼도 **연결은 멀쩡히 살아 있어서
  브라우저가 다시 붙을 이유가 없다.** 2026-08-24 재현(PR #113 봇 지적 ①): 큐를 채워 카드
  하나를 버리게 한 뒤 큐를 풀었더니 **새 카드는 23ms에 오고 하트비트도 계속 왔는데**
  버려진 그 카드만 안 왔다. 클라이언트가 받은 것은 `:ok` · 새 카드 · ping · ping 넷이 전부이고
  **버려진 카드는 DB에만 있다.** 자동으로 끊기는 계기는 **emitter 시한 하나뿐**이고
  그 값은 `min(설정 4시간, 토큰 exp)`, 즉 실질 **auth의 `access-token-ttl` 30분**이다.
  5초짜리 토큰으로 재니 **5,283ms에 끊겼고 재연결 스냅샷에 그 카드가 들어 있었다.**
  → **「영영 유실」이 아니라 「최대 30분 안 보임」이다.**
  - **🔴 고치겠다고 「명부에서 뺀다」를 쓰면 안 된다.** 종료 알림(`ended_dropped`)에 쓴 그
    처방을 `publish`에 주입해 재 봤더니 **결과가 뒤집힌다** — `connectionCount=0`이 되는데
    **소켓은 열린 채**라 클라이언트는 연결이 살아 있다고 믿고, 그 뒤 **새 카드도 하트비트도
    영영 안 온다**(받은 것이 `:ok` 하나뿐). 하트비트가 끊기면 **앞단 프록시가 끊어 줄 계기까지
    사라진다.** 두 자리의 사정이 다르다: `broadcastEnded`는 **뺀 뒤 갈 것이 없지만**
    `publish`는 **있다.** 잃는 것이 「카드 한 장」에서 **「남은 수명 전부」**로 커진다
- **🔴 막힌 전송이 있으면 종료가 최대 20초 밀린다. 「JVM이 안 죽는다」가 아니다.**
  `CardStreamExecutor`의 종료 유예는 스트라이프마다 `awaitTermination(5초)`이고, 넘겨도
  **로그만 남기고 `shutdownNow()`를 부르지 않는다.** 워커는 non-daemon이라 유예 뒤에도
  `SocketDispatcher.write0`에서 살아남는다(2026-08-24 재현, PR #113 봇 지적 ③).
  - **그래도 SIGTERM이면 죽는다** — 신호로 시작된 종료는 훅이 끝나면 halt하므로 남은
    non-daemon을 안 기다린다. 막힌 워커를 안고도 **5.4초에 `exit 143`**이었다.
    `docker stop`·k8s가 그 경로다
  - **실제로 남는 피해는 지연이다.** 유예가 **스트라이프마다 직렬**이라 운영 기본 `stripes: 4`가
    전부 막히면 **20,018ms**(SIGTERM→종료 20,411ms 실측). k8s 기본
    `terminationGracePeriodSeconds` 30초의 2/3을 먹는다
  - **`shutdownNow()`로는 안 풀린다.** 막힌 소켓 write는 인터럽트에 반응하지 않는다 —
    `shutdownNow()` 2초 뒤에도 `write0`에서 `RUNNABLE`이었다. 인터럽트로 풀리는 대기
    (`latch.await`)에서만 효과가 있었다(대조군에서 확인). 푸는 것은 위에 적은
    **서버의 write timeout(약 61초)**뿐이다
  - **재현 못 한 것**: 위에서 막은 것은 **생 소켓 write**이지 서블릿 출력 스트림이 아니다.
    스택 최상단과 인터럽트 불감성은 같지만 서블릿 계층까지 포함해 잰 것은 아니다
- **초기 스냅샷이 요청 스레드에서 나갈 수 있다 — 못 고친다.** `SseEmitter`는 MVC가 emitter를
  받기 전(`ResponseBodyEmitter.initialize`)의 `send`를 **early-send 버퍼**에 쌓고, 그 버퍼를
  비우는 것이 **요청 스레드**다(`ResponseBodyEmitterReturnValueHandler.handleReturnValue` →
  `initialize` → `sendInternal`). 스트라이프가 MVC보다 먼저 보내기 시작하면 스냅샷 전송이
  전용 스레드가 아니라 요청 스레드에 실린다 — **실측 최대 454건 · 350,637자(약 342KB) ·
  4,024us**(카드 300장·안 읽는 소켓 10개, 2026-08-23). 같은 조건에서 열 연결 중 넷은 **0건**이라
  실행마다 갈린다.
  - **공개 훅이 없다.** Spring은 「handler가 붙었다」를 알리지 않는다. `initialize`는
    package-private이고 `extendResponse`(protected)는 그보다 **앞**이라 뒤에도 창이 남는다
  - **이 카드가 만든 것이 아니다.** 카드마다 태스크로 쪼개 봐도 규모가 같았다(**382건 · 294,813자**).
    창을 정하는 것은 태스크 경계가 아니라 **handler 부착 여부**다. 오히려 쪼갠 쪽에서 요청 스레드가
    `NioEndpoint.doWrite`에 서 있는 장면이 잡혔다
  - **커넥션은 안 쥔다.** 컨트롤러의 `@Transactional`은 메서드 반환 시 닫히고 `initialize`는 그
    **뒤**다(연결이 사는 동안 `activeConnections=0` 실측). 잃는 것은 **Tomcat 워커 하나**라
    POK-93의 풀 고갈과 급이 다르다
  - **우회안(`initialize` 오버라이드)을 안 쓴다.** 첫 전송이 자물쇠 **밖**으로 나가
    **「새 카드가 스냅샷을 앞지름」이 재발한다** — PR #109가 고친 바로 그 구멍이다.
    4ms를 아끼려고 그것을 되살리는 것은 손해다. PRD 「따라잡기」로 옮기는 날 같이 사라진다
- **끊긴 연결의 자리는 즉시 안 돌아온다.** 서버는 **다음 쓰기가 실패해야** 안다 —
  그런데 **작은 쓰기 한 번으로는 부족하다**: 트래픽 없이 재니 하트비트 **두 주기 = 39,267ms**
  뒤에 회수됐다(2026-08-24 실측). 자동 회수되지만
  **탭을 닫고 바로 다시 열면 `max-per-user` 4가 3처럼 느껴질 수 있다**
- **🔴 쓸기가 낡은 발행을 통과시키는 창이 하나 있다. 안 고쳤다 — 열리는 조건이 이 구멍의 크기다.**
  연결이 전부 끊긴 사이 하트비트의 순번 표 쓸기(`sweepIdleStreams`)가 그 방송 표를 버리는데,
  그때 **커밋은 됐지만 `afterCommit`이 아직 안 돈 낡은 발행**이 남아 있으면 그것이 빈 표를 만나
  통과한다 → 새 연결이 최신 스냅샷을 받은 **뒤에** 낡은 값이 도착해 **화면이 뒤로 간다**
  (2026-08-24 재현, PR #114 봇 지적 ①. 클라이언트 도착 순서
  `card(id=204) · card(id=206,놓임) → card(id=205,집힘)`).
  - **창은 「커밋 ~ `afterCommit` 도달」이고 쓸기가 그것을 늘리지 않는다.** n=200을 세 번 재니
    「커밋 가시성 → `afterCommit`」 중앙값이 **음수(−60~−79us)**였다 — 200회 중 **171~183회**에서
    `afterCommit`이 폴러가 커밋을 관측하기도 전에 끝났다. 상한으로 잡아도
    (`beforeCommit`→`afterCommit`) **732~1083us**다
  - **그런데 열리려면 그 발행이 40~60초를 버텨야 한다.** 끊김 감지가 **39,267ms**(위 항목)이고,
    **그 주기의 쓸기는 아직 못 버린다** — 감지 시점 `trackedStreamCount=1`이었다(전송 제출이
    먼저, 실패는 전용 스레드에서 나중이라). **다음 주기(≤20초)**가 더 필요하다.
    **창 1ms 미만 대 필요 지연 40~60초 — 4~5자릿수 차이다**
  - **정상 재연결에서는 안 열린다.** 브라우저 `EventSource` 기본 재시도가 3초라 보통 **감지(39초)
    전에** 새 연결이 붙고, 그러면 그 방송이 「살아 있음」이라 쓸기가 지우지 않는다.
    사람이 탭을 닫고 **40초 넘게 안 돌아와야** 열린다
  - **회복된다** — 다음 갱신으로 **9ms**, 재연결 스냅샷은 정확
  - **쓸기만의 문제가 아니다.** 쓸기가 전혀 안 돌아도 **발행 둘이 다 지연되면** 같은 순서가 난다.
    다만 그쪽은 최신이 **2.562ms 뒤 따라와 스스로 낫고**, 쓸기 경로만 **다음 갱신·재연결까지** 남는다
  - **봇이 준 처방 둘의 판정**(다음에 고칠 사람의 출발점) — ①「쓸기가 신선도 상태를 남긴다」는
    **쓸기를 넣은 이유와 정면으로 충돌한다**(통로가 꺼진 배포에서 항목이 무한히 쌓이는 누수를
    막으려고 넣은 것이다). ②「연결마다 카드별 기준선」은 **`CardStreamRegistry.lastPublishedSeq`
    주석의 경고에 걸리지 않는다** — 그 경고는 「**공유** 표를 연결 스냅샷으로 채우는 변형」만
    막는다. 연결별 상태는 남의 연결 판정에 끼어들 수 없다
- **503이 두 뜻으로 나간다.** 상한 초과는 `{"error":"stream_limit","scope":…}` JSON이지만,
  토큰 잔여가 아주 짧아 헤더가 나가기 전에 시한이 오면 **본문 없는 503**이 된다
  (`AsyncRequestTimeoutException`). **웹이 둘을 구분할 수 없다** — 프론트에 알릴 것
- **실제 토큰 수명은 `exp + 60초`다.** 디코더의 clock skew 허용치가 기본 60초다
- **health 상세가 익명에게 통째로 나간다**(POK-82부터). `show-details: always` + permitAll
- **`jwt.getSubject()`가 null이면 상한 집계가 NPE**다. 「불가능」이 아니라
  **auth `TokenService`가 `subject(user.getId().toString())`를 넣는 한 줄에 걸려 있다** —
  남의 서버 코드다
- **편집자 이름이 응답에 없다.** `claimedBy`는 사용자 번호다. **이름을 물어볼 창구는
  생겼지만**(POK-175가 `develop`에 머지됐다, PR #106) **붙이는 것은 별도 카드다**
- 내부 토큰에 길이 하한이 없고 `iss`·`typ`을 검증하지 않는다 — auth와 같은 상태다

**아직 없는 것:** 핫키 카드 생성(POK-119) · 카드 목록 API · 만료 정리 배치 · Redis 팬아웃 ·
`clipped`·`expired` 상태.

### clip — 구간→세그먼트 변환 (POK-117)

**「3분 20초부터 3분 50초까지」를 실제 영상 조각 목록으로 바꿔 준다.** 편집기 미리보기가
이 문으로 「지금 어디까지 볼 수 있나」를 묻고, 나중에 렌더 잡(POK-125)이 같은 판정을
서비스 계층에서 재사용한다.

```
GET /api/clip/broadcasts/{streamId}/segments?startMs=&endMs=     Bearer JWT
```

| 응답 칸 | 뜻 |
|---|---|
| `segments[]` | `seq` · `startPtsMs` · `durationMs` · `discontinuity`. **`s3Key`는 안 나간다** |
| `availableFromMs` | 목록 첫 조각의 시작. 목록이 비면 `startMs` |
| `availableUntilMs` | 목록 마지막 조각의 끝. 목록이 비면 `startMs` |
| `complete` | `availableFromMs <= startMs && availableUntilMs >= endMs` |

**🔴 목록과 `availableFromMs`·`availableUntilMs`는 조각 경계라 요청 구간보다 넓어질 수 있다.**
조각이 4초 단위라 그 경계로만 잘린다 — 요청 `[5000,9000)`에 `availableUntilMs=12000`이 정상이다
(실기동 확인). **요청한 구간으로 정확히 자르는 것은 호출자(플레이어) 몫이다** —
`availableUntilMs`를 그대로 재생 끝점으로 쓰면 요청보다 긴 영상을 튼다.

**목록은 「가장 앞의 끊김 없는 구간」 하나다.** 겹치는 조각 중 `upload_state='uploaded'`인
첫 조각부터 `seq`가 이어지는 동안만 싣고, 끊기면 거기서 멈춘다(그 뒤에 올라온 조각이 또 있어도
버린다). 가운데가 빈 목록을 주면 화면이 이어 붙여 영상이 튀기 때문이다.

**요청 머리가 비어 있어도 뒤를 보여준다.** 방송이 끊겨 앞 조각이 없으면 `availableFromMs`가
요청 시작보다 커지고 `complete=false`가 된다 — 아무것도 안 주면 「아직 안 올라옴」과
「영영 없음」이 똑같이 막히는데, 이 API는 둘을 구분하지 못한다.

**`upload_state='failed'`는 `pending`과 같이 「아직 아님」으로 본다** — 계약-세그먼트인덱스
3절이 `failed → uploaded` 역전이를 명시한다(재기동·주기 스위퍼가 다시 집어 올린다).

| 상태 | 언제 |
|---|---|
| **400** | `startMs<0` · `endMs<=startMs` · 구간이 30분 초과 · 파라미터 형식 오류 |
| **404** | 없는 방송 **그리고** 볼 자격이 없는 방송 — **본문이 바이트 단위로 같다**(구분되면 남의 방송 존재가 샌다). 실제 사유는 로그에만. **본문만 같아서는 안 갈렸다** — 자격 판정은 auth 왕복을 타고 없는 방송은 안 타서 중앙값이 1.5ms 대 4.4ms로 벌어졌다(실측 1,240회, 한 번만 재도 99.5% 구분). 그래서 **이 404는 요청 도착 뒤 25ms가 차야 나간다**(200·400·410·503은 안 늦춘다) |
| **410** | 보관 기한(`vod_expires_at`)이 지난 방송. 자격이 있는 호출자에게만 도달한다 |
| **503** | auth 자격 창구에 못 닿음. **판정 불가는 통과가 아니라 거절이다** |

자격은 auth에 물어서 판정한다(`POST /internal/editor-delegations/resolve`, POK-175).
그래서 **`AUTH_BASE_URL`이 새 필수 환경변수다** — 없으면 부팅을 거부한다.

**보관 기한은 종료 이벤트를 받을 때 `ended_at + 60일`로 채운다**(ADR-004의 VOD 60일).
`V203`이 그 칸을 만들고 이미 끝난 방송에 소급 적용한다. 라이브 중인 방송은 NULL이다.

**알려진 한계 다섯**
- **404의 시간 맞추기는 평상시만 막는다.** auth가 아파서 자격 창구가 느려지면(시한이 connect 2초 +
  read 5초) 자격 판정을 타는 404가 25ms를 훌쩍 넘어, 그 시간차로 방송의 실재가 다시 구분된다.
  고정 지연으로는 못 덮는다 — 왕복 자체를 없애야 닫힌다
- **auth가 죽어 있으면 상태 코드 자체가 실재를 가른다.** 그동안 없는 방송은 404, 실재하는 방송은
  **503**이다 — 자격 판정이 방송 조회에 성공한 뒤에만 도달하기 때문이다. 시간이 아니라 코드라
  25ms 바닥으로 못 덮는다. **503을 404로 접는 길은 일부러 안 골랐다** — 화면이 「없는 방송」으로
  단정하면 auth가 살아난 뒤에도 편집자가 다시 시도하지 않는다. 즉 이 구멍은 **auth 장애 중에만**
  열리고, 그때는 미리보기가 어차피 아무에게도 안 열린다
- **개발용 compose에는 `stream_segments`가 없다**(그 파일에 media·인덱서가 없다). 거기서 이 API를
  부르면 500이다. media가 뜨면 표가 생긴다 — 로컬에서는 media 컨테이너를 띄우거나 정본 DDL을 심는다
- **종료 이벤트가 유실된 방송은 기한이 영영 안 채워진다** — 만료 판정을 못 한다
- **계약이 인정한 `failed` 고착 하나** — 실패한 조각이 그 스트림의 마지막인 채로 방송이 끝나면
  후속이 없어 자동 회복되지 않는다. 그 구간을 포함한 요청은 영원히 `complete=false`이고,
  처치는 1번 쪽 운영자 수동 개입이다

### 치지직 채널 연동 (POK-93)

**구글 로그인과 별개다.** 로그인한 스트리머가 자기 치지직 채널을 한 번 묶어 두면, 수집기가
그 스트리머의 유저 Access Token을 auth에서 받아 채팅 세션을 연다. 왕복은 구글과 같은 모양 —
`POST /api/chzzk-link/start`가 준 `authorizeUrl`로 프론트가 사용자를 보내고, 치지직이
`CHZZK_REDIRECT_URI`로 돌려준 `code`·`state`를 프론트가 `POST /api/chzzk-link {code, state}`로
넘긴다. 채널은 본문이 아니라 치지직 `users/me`로 확정한다.

| | 응답 |
|---|---|
| `POST /api/chzzk-link/start` | 200 `{authorizeUrl}` — `state`는 URL 안에 있다(표 없이 서명, 10분) |
| `POST /api/chzzk-link` `{code, state}` | 201 `{channelId, channelName, linkedAt}` · 400 `INVALID_STATE` · 400 `INVALID_CODE`(치지직이 교환·me를 4xx로 거부 — code 소모·만료·scope 부족, **동의부터 다시**) · 409 `CHANNEL_ALREADY_LINKED` · 502 `CHZZK_UNAVAILABLE`(5xx·타임아웃·429·408·`INVALID_CLIENT` — 잠시 후 재시도) |
| `GET /api/chzzk-link` | 200 `{linked:false}` 또는 `{linked, channelId, channelName, status, linkedAt, lastRefreshedAt, accessExpiresAt}`. `status` ∈ `ACTIVE`·`EXPIRED`·`BROKEN`·`UNLINKED`(컬럼이 아니라 파생). `linked`는 `ACTIVE`·`EXPIRED`일 때만 true — `BROKEN`·`UNLINKED`도 채널 이름은 준다(화면이 "끊겼다"를 보여줄 수 있게) |
| `DELETE /api/chzzk-link` | 204 (없어도 204). 행은 남고(`revoked_at`+`USER_UNLINKED`) 커밋 뒤에 secrets 삭제·치지직 revoke |
| `POST /internal/chzzk-link/resolve` `{userId}` | **항상 200** — 아래 |

오류 본문은 `{"reason": "<위 코드>"}` 한 필드다. 토큰·code·state·채널 ID는 응답 오류·로그
어디에도 안 남는다(`SecretLeakTest`) — 예외 하나: 두 계정이 같은 채널을 동시에 묶어 사전 조회를 지나
DB 유니크 위반까지 간 경우 Hibernate 오류 로그에 `channel_id`가 한 줄 남을 수 있다(우리 로거가 아니다, 평시 0건).

**`resolve`(수집기용) 계약.** `POST /internal/chzzk-link/resolve {userId}` — `X-Internal-Token`
헤더, `/internal/**` 체인(스트림키 `resolve`와 같은 문). **우리 회원 번호(`users.id`)만 받는다** —
남의 식별자 체계를 auth가 떠안지 않는다. 스트림키 `resolve`와 같은 이유로 **항상 HTTP 200**이다:
`{valid:true, channelId, accessToken, expiresAt}` 또는 `{valid:false, reason}`. `reason`은 넷 —
`NOT_LINKED`(연동한 적 없음) · `UNLINKED`(사용자가 해제) · `BROKEN`(치지직이 갱신을 4xx로
거부 — 철회·만료, 스트리머가 재동의해야 풀린다) · `REFRESH_UNAVAILABLE`(즉석 갱신이 5xx·타임아웃으로
일시 실패 — 임박한 토큰은 주지 않는다, 잠시 뒤 다시 부르면 된다). 거절 응답에는 `accessToken`
필드가 아예 없다. **남은 수명이 12시간(`resolve-min-remaining`)보다 짧으면 넘기기 전에 즉석
갱신한다** — 수집기는 한 번 받은 토큰으로 방송 끝까지 붙어 있으므로, 방송 길이보다 긴 수명을 보장한다.

**자동 갱신 스케줄러.** 유저 Access Token 수명은 24시간이다. `@Scheduled`가 **10분마다**
(`pokeclip.chzzk.refresh.interval=PT10M`) 살아있는 연동 중 만료가 **6시간**(`refresh-ahead=PT6H`)
안으로 남은 것만 골라 회원마다 하나씩 갱신한다. 회원 행 `FOR UPDATE` 락 뒤 다시 읽어 이미
갱신됐으면 치지직을 안 부른다(인스턴스 둘이 같은 목록을 읽어도 두 번째는 건너뛴다). 4xx면 영구
(`revoke_reason=REFRESH_REJECTED` → `BROKEN`, 재시도 없음 — 429·408·`INVALID_CLIENT`(앱 자격증명 오류, 우리 설정 문제)는
일시로 보고 재시도) · 5xx·타임아웃이면
행을 두고 다음 틱에 재시도한다. `pokeclip.chzzk.refresh.enabled`는 **기본 켜짐**이고 프로퍼티를 빠뜨려도 켜진다
(`matchIfMissing`) — 테스트 프로파일만 명시적으로 끈다. 꺼지면 갱신이 영영 안 돌고 증상은 24시간
뒤에야 나온다.

**종료 유예 15초 이상**(`stop_grace_period` / `terminationGracePeriodSeconds`) — 커밋 뒤 정리(옛 토큰 삭제·치지직
revoke)가 전용 스레드 2개(`ChzzkCleanupExecutor`)에서 돌고 종료 시 최대 10초 기다린다. 도커 기본 10초에 잘리면
대기 중 삭제가 유실돼 고아 secret이 남는다(무해하지만 쌓인다). chat-collector의 종료 유예 20초와 같은 부류이며
`infra/`(1번)가 반영한다.

로그는 `auth.chzzk.link.<event> userId=` 영어 한 줄이다(`created`·`relinked`·`unlinked`·`refreshed`·
`refresh_rejected`·`refresh_failed`·`refresh_tick_failed`·`rejected`·`unavailable`·`orphan_token`(WARN — 5xx·타임아웃,
치지직에 살아있을 수 있음)·`token_already_dead`(INFO — 4xx, 이미 무효. 429·408·`INVALID_CLIENT`는 제외 — 그 셋은
Unavailable → WARN `orphan_token causeType=Http429/408/InvalidClient`)·`resolve_rejected`·`failed`).
로그의 자리: `refreshed`만 요청 스레드 동기 afterCommit이고, `relinked`·`unlinked`·`refresh_rejected`는 정리 잡 안에서
secrets 삭제 뒤에 찍힌다(정리까지 끝났다는 순서 로그) — 큐가 거부되면 그 로그도 함께 사라지고 그때 `cleanup.rejected`
WARN이 신호다. requestId는 잡이 값으로 옮긴다. 정리 스레드 자체의 것은 `auth.chzzk.cleanup.<event>` —
`rejected`(큐 상한 초과, WARN)·`failed`·`shutdown_timeout`.
값은 userId·status·hint·causeType·reason·pending만 — 토큰·code·state·channelId는 찍지 않는다.

### 유튜브 채널 연동 (POK-121)

**로그인용 구글과 별개 앱이다.** 스트리머가 자기 유튜브 채널을 한 번 묶어 두면, 업로드 워커가
그 스트리머의 access token을 auth에서 받아 클립을 올린다. 왕복 모양은 치지직과 같다 —
`POST /api/youtube-link/start`가 준 `authorizeUrl`로 프론트가 사용자를 보내고, 구글이
`YOUTUBE_REDIRECT_URI`로 돌려준 `code`·`state`를 프론트가 `POST /api/youtube-link {code, state}`로 넘긴다.

🔴 **채널은 동의 시점에 정해지고, 나중에 바꿀 수 없다 (2026-08-24 실측).** 구글 동의 화면이
계정·브랜드 계정을 고르게 하고, 그 토큰으로 `channels.list?mine=true`를 부르면 **고른 채널 하나만**
돌아온다(브랜드 계정 `PokeClip2`·개인 계정 `PokeClip1` 둘 다 `totalResults: 1`). 다른 채널은 그 토큰으로
**보이지도 접근되지도 않는다.** 그래서 **채널 목록·재선택 API가 없다** — 만들려다 실측으로 접었다.
**채널을 바꾸는 수단은 재연동뿐이다**(같은 `POST /api/youtube-link`가 옛 행을 닫고 새 행을 만든다).
하위 티켓 POK-142의 「채널이 둘 이상이면 고를 수 있다」는 구조적으로 성립하지 않는다.

동의 URL에는 `access_type=offline`·`prompt=consent`가 **둘 다** 있어야 refresh 토큰이 온다.
scope는 둘 — 업로드(`youtube.upload`)와 채널 조회(`youtube.readonly`). **upload만으로는
`channels.list`가 403 `insufficientPermissions`다**(실측). 받은 scope에 upload가 없으면 400
`SCOPE_MISSING`으로 거절한다(응답의 scope 순서는 요청과 반대로 온다 — 우리 대조는 포함 여부다).

| | 응답 |
|---|---|
| `POST /api/youtube-link/start` | 200 `{authorizeUrl}` — `state`는 URL 안에 있다(표 없이 서명, 10분) |
| `POST /api/youtube-link` `{code, state}` | 201 `{channelId, channelName, linkedAt}` · 400 `INVALID_STATE` · 400 `INVALID_CODE`(구글이 교환·채널 조회를 4xx로 거부 — code 소모·만료·권한 부족, **동의부터 다시**) · 400 `SCOPE_MISSING`(업로드 권한 미동의) · 400 `NO_CHANNEL`(구글 계정에 유튜브 채널이 없다 — 먼저 만들어야 한다) · 409 `CHANNEL_ALREADY_LINKED` · 502 `YOUTUBE_UNAVAILABLE`(5xx·타임아웃·429·408·`invalid_client`·**403 할당량**) |
| `GET /api/youtube-link` | 200 `{linked:false}` 또는 `{linked, channelId, channelName, status, linkedAt, lastRefreshedAt, accessExpiresAt}`. `status` ∈ `ACTIVE`·`BROKEN`·`UNLINKED`(파생). **치지직과 달리 `EXPIRED`가 없다** — 구글 access는 1시간짜리라 늘 만료돼 있고 갱신으로 항상 해소되므로 상태가 아니다. `linked`는 `ACTIVE`일 때만 true |
| `DELETE /api/youtube-link` | 204 (없어도 204). 행은 남고(`revoked_at`+`USER_UNLINKED`) 커밋 뒤에 secrets 삭제. 🔴 **구글에는 revoke를 보내지 않는다** — 아래 |
| `POST /internal/youtube-link/resolve` `{userId}` | **항상 200** — 아래 |

오류 본문은 `{"reason": "<위 코드>"}` 한 필드다. 토큰·code·state·채널 ID는 응답·로그 어디에도 안 남는다
(`SecretLeakTest`가 왕복 전체를 태워 확인한다).

🔴 **구글 revoke는 「그 토큰 쌍」이 아니라 그 사용자가 이 앱에 준 동의 전부를 죽인다.**
공식 문서대로이고 실측으로도 확인했다(1차 refresh만 revoke했더니 직전까지 갱신되던 2차 refresh가
`400 invalid_grant`로 죽었다). **치지직의 「쌍 무효화」와 근본이 다르다.**

🔴 **그래서 revoke를 부르는 자리가 하나뿐이다.** 처음엔 갈래를 넷으로 갈라 조건을 붙였는데,
봇 리뷰 세 판에 걸쳐 **조건으로는 못 막는다**는 것이 재현으로 드러나 결국 뺐다(2026-08-24).
근본 원인은 하나다 — **revoke의 영향 범위는 「그 구글 계정」인데 우리가 가진 판별자는 「회원·채널」뿐**이고,
그것조차 없는 경로(scope 미달)와 표에 아직 없는 순간(교환 직후·저장 전)이 있다.

| 경로 | revoke | 왜 |
|---|---|---|
| **갱신 거부 → `BROKEN`** | **부른다**(1회) | 그 토큰은 이미 `invalid_grant`로 죽어 있다 — 살아있는 grant에 닿지 않으므로 남을 해칠 수 없다. **유일하게 남은 자리다** |
| **사용자 해제 `DELETE`** | **안 부른다** | 계정 단위라 같은 채널을 방금 연동한 **다른 회원**의 연동까지 끊는다. 대신 secrets를 지워 **우리가 못 쓰게** 하고, 사용자는 구글 계정 화면에서 직접 지운다(아래 「웹에 필요한 것」) |
| **재연동** | **안 부른다** | 새 동의가 옛 grant를 대체한다. 부르면 방금 저장한 새 토큰이 죽는다 |
| **연동 실패 정리**(scope 없음·채널 0개·409·5xx) | **안 부른다** | scope 미달은 **채널을 읽기도 전에** 갈려 판별자가 아예 없다. 버려진 access는 1시간이면 스스로 죽는다 |

**`resolve`(업로드 워커용) 계약.** `POST /internal/youtube-link/resolve {userId}` — `X-Internal-Token`
헤더, `/internal/**` 체인(치지직 `resolve`와 같은 문). **우리 회원 번호(`users.id`)만 받는다.**
**항상 HTTP 200**이다: `{valid:true, channelId, accessToken, expiresAt}` 또는 `{valid:false, reason}`.
`reason`은 넷 — `NOT_LINKED`(연동한 적 없음) · `UNLINKED`(사용자가 해제) · `BROKEN`(구글이 갱신을
거부 — 철회·테스트 모드 7일 만료, 재동의해야 풀린다) · `REFRESH_UNAVAILABLE`(즉석 갱신이 일시 실패 —
임박한 토큰은 주지 않는다, 잠시 뒤 다시 부르면 된다). 거절 응답에는 `accessToken` 필드가 아예 없다.
**남은 수명이 30분(`resolve-min-remaining`)보다 짧으면 넘기기 전에 즉석 갱신한다** — 구글 access가
1시간짜리라 치지직(12시간)과 자릿수가 다르다.

🔴 **해제해도 구글 쪽 허락은 남는다 — 웹이 안내해야 한다(2번 몫).** `DELETE`는 우리 표의 행을 닫고
**secrets의 토큰 원문을 지운다**(우리는 다시 못 쓴다). 그러나 구글에 `revoke`는 보내지 않는다 —
구글 revoke는 **그 구글 계정이 우리 앱에 준 동의 전부**를 죽이므로, 같은 채널을 방금 연동한 **다른 회원**의
연동까지 끊는다. 조건으로 막으려 세 판을 썼지만 「확인과 발사 사이」 창이 남았고, 그것을 닫으려면
revoke를 DB 락 안에 넣어야 해서(=트랜잭션 안 외부 호출) 포기했다. 근거는 `services/auth/CLAUDE.md` 「알려진 구멍」 20번.

> **웹에 필요한 것**: 해제 완료 화면에 「구글 계정에서도 권한을 지우려면」 안내와
> <https://myaccount.google.com/permissions> 링크. 이것이 사용자가 구글 쪽 허락을 지우는 유일한 수단이다.

**refresh는 재사용형이다.** 갱신 응답에 `refresh_token`이 **없는 것이 정상**이고 그때는 기존 것을
계속 쓴다(있으면 교체). 치지직 코드를 그대로 베껴 무조건 덮어쓰면 `null`을 써 넣어 연동이 통째로 죽는다.

**철회 점검 스케줄러.** 치지직의 「만료 임박 선갱신」과 **축이 다르다** — 구글 access는 1시간이라
그 기준으로는 살아있는 행이 늘 전부 걸린다. 대신 `@Scheduled`가 **1시간마다**
(`pokeclip.youtube.check.interval=PT1H`) **24시간 넘게 확인 안 한**(`staleness=PT24H`) 살아있는 연동만
골라 갱신을 한 번 시도한다. 사용자가 구글 쪽에서 권한을 끊은 것을 **업로드 직전이 아니라 미리**
드러내는 것이 목적이다. 회원당 구글 호출은 하루 1회다. `pokeclip.youtube.check.enabled`는 **기본
켜짐**이고 프로퍼티를 빠뜨려도 켜진다(`matchIfMissing`) — 테스트 프로파일만 명시적으로 끈다.

**종료 유예 15초 이상** — 치지직과 같은 이유다. 커밋 뒤 정리(secrets 삭제)가 전용 스레드
2개(`YoutubeCleanupExecutor`)에서 돌고 종료 시 최대 10초 기다린다(넘기면 인터럽트하고 2초 더).
두 서버가 각자 스레드 2개를 쓴다.

로그는 `auth.youtube.link.<event> userId=` 영어 한 줄이다(`created`·`relinked`·`unlinked`·`refreshed`·
`refresh_rejected`·`refresh_failed`·`check_tick_failed`·`check_batch_capped`(INFO — 후보가 틱당 상한 25를 넘어
잘렸다, 남은 후보는 다음 틱이 가져간다)·`rejected`·`unavailable`·`scope_missing`·`no_channel`·
`orphan_token`(WARN — 5xx·타임아웃)·`token_already_dead`(INFO — 4xx, 이미 무효)·`resolve_rejected`·`failed`).
**`orphan_token`·`token_already_dead`는 갱신 거부 경로에서만 난다** — 유일하게 revoke를 부르는 자리다.
정리 스레드 자체의 것은
`auth.youtube.cleanup.<event>` — `rejected`(큐 상한 초과, WARN)·`failed`·`shutdown_timeout`.
값은 userId·status·causeType·reason만 — 토큰·code·state·channelId는 찍지 않는다.

**마이그레이션은 `V109__create_youtube_channel_links.sql`이다.** 살아있는 행에만 걸리는 부분 유니크
둘(`channel_id`·`user_id`)이 최종 방어선이고, 점검 후보용 인덱스는 `last_refreshed_at` 축이다.

### 편집자 초대 (POK-57)

**돈 내는 쪽(스트리머)과 매일 쓰는 쪽(편집자)이 다르다.** 스트리머가 이메일로 편집자를
초대하고, 편집자가 수락하면 **위임**이 생긴다.

**auth가 하는 일은 「위임이 있다」는 사실을 만들고 보관하는 것, 그리고 물으면 답하는 것까지다.**
그 위임으로 편집자가 실제로 무엇을 할 수 있는지는 각 서비스가 **아래 내부 창구 둘로 물어서** 정한다
(POK-175, 2026-08-23). auth 안에 위임을 보고 권한을 내주는 사용자용 엔드포인트는 없다 —
위임 행은 초대 중복 검사 · **수락 시 생성** · 목록 · 해제 · 내부 창구 응답에 쓰인다.
`contracts/api/`는 3인 공동이라 정본은 여기 둔다.

**수락 전에는 편집자의 위임 목록에 그 스트리머가 나오지 않고 그 데이터에 접근할 수 없다**
(PRD 성공기준). 초대함에는 **누가 보냈는지(이름과 id)만** 보인다 — 그게 없으면 응답할 수 없다.

**초대는 7일 살아 있다.** 같은 상대에게 **살아있는 초대가 있는 동안** 다시 초대하면 새 행이
생기지 않고 **그 초대의 기한만 7일로 다시 밀린다** — 한 쌍당 살아있는 초대는 하나다.
버튼을 연타하거나 같은 요청이 두 번 와도 결과가 같다.
**거절·취소된 이력은 지워지지 않고 그대로 쌓인다** — 그 뒤에 다시 초대하면 새 행이 생긴다.
「하나」는 살아있는 초대에만 해당하지 그 쌍의 전체 행 수가 아니다.

**살아있는 초대는 스트리머당 20개까지.** 자리를 비우려면 보낸 초대를 취소한다.
**이 상한은 근사값이다** — 동시에 여러 요청이 들어오면 잠깐 넘을 수 있다. 세는 것과 만드는
것 사이를 락으로 묶지 않았기 때문이다. 일부러 그렇게 뒀다: 상한은 자원 가드지 보안 경계가
아니고, 정확히 막으려면 로그인·스트림키 회전이 이미 쓰는 계정 행 락을 초대까지 끌어와야 해서
얻는 것보다 치르는 값이 크다. 넘더라도 권한 없는 편집자가 생기지는 않는다.

**상태는 다섯이다.** `PENDING` · `ACCEPTED` · `DECLINED` · `CANCELED` · `EXPIRED`.
**`EXPIRED`는 DB에 없다** — 조회 시점에 기한을 보고 만들어 준다. 만료 시각에 값을 바꿔주는
배치를 두지 않으려는 선택이다(치지직 연동과 같은 원칙). 기한과 **같은 시각은 아직 살아 있고**,
지난 뒤부터 만료다.

**목록 둘의 범위가 다르다.** `sent`는 거절·만료까지 **전부** 최신순으로 준다(스트리머가 이력을
봐야 한다. 페이징은 아직 없다). `received`는 **응답할 수 있는 것만** — `PENDING`이면서 기한이
남은 것. 이미 처리했거나 만료된 초대를 초대함에 남기면 눌러도 실패한다.

**해제는 엔드포인트 하나다.** `DELETE /api/editor-delegations/{id}`를 스트리머가 부르면
내보내기, 편집자가 부르면 나가기다. **행을 지우지 않고** `revoked_at`·`revoked_by`로 닫는다 —
내보낸 것과 나간 것은 다른 사건이라 구분해 남긴다. 한 번 정해진 `revoked_by`는 안 바뀐다.
해제한 뒤 다시 초대·수락하면 **새 행이 쌓인다.**

**실패 사유 여덟.** 남의 초대·위임은 존재 여부를 알려주지 않는다 — 없는 것과 **같은 404**다.
가려서 답하면 id를 훑어 남의 초대가 있는지 알아낼 수 있다.

| 사유 | 코드 | 언제 |
|---|---|---|
| `INVITEE_NOT_FOUND` | 404 | 그 이메일로 가입한 계정이 없다 |
| `SELF_INVITE` | 400 | 자기 자신을 초대했다 |
| `ALREADY_EDITOR` | 409 | 이미 살아있는 위임이 있다 |
| `TOO_MANY_PENDING` | 409 | 살아있는 초대가 20개다 |
| `INVITATION_NOT_FOUND` | 404 | 없거나 남의 초대다 |
| `INVITATION_EXPIRED` | 410 | 7일이 지났다 |
| `INVITATION_NOT_PENDING` | 409 | 이미 수락·거절·취소됐다 |
| `DELEGATION_NOT_FOUND` | 404 | 없거나 내 위임이 아니다 |

**우리 코드는 이메일을 로그에 찍지 않는다.** 개인정보이고, 실패 로그로 쌓이면 "이 주소가
가입돼 있나"를 훑은 흔적이 파일에 남는다. 로그에는 id와 사유 코드만 찍는다.
DB 제약 위반 메시지에 주소가 실려 오던 경로도 닫았다(`org.hibernate.orm.jdbc.error`를 낮췄다).

**다만 한 곳은 프레임워크가 찍는다** — 요청 본문의 이메일이 형식·길이 검증에 걸리면 스프링이
거부된 값을 WARN으로 남긴다(`DefaultHandlerExceptionResolver`). **거기 남는 것은 가입될 수
없는 값뿐이다** — 형식이 틀렸거나 320자를 넘는 주소. 열거는 유효하고 등록 가능한 주소로 하는데
그런 값은 검증을 통과해 이 경로에 오지 않으므로, 걱정한 열거 흔적은 쌓이지 않는다.

**같은 이메일에 다른 구글 계정이 로그인하면 409로 거절한다** (2026-08-18).
POK-57이 `users.email`에 유일 제약을 걸면서 생긴 경로다 — 구글 계정을 지웠다 같은 주소로
다시 만들면 계정 식별자(`sub`)가 바뀌어 여기 온다. 이메일이 초대의 열쇠라 두 계정이 같은
주소를 나눠 가질 수 없다. 응답은 `{"reason":"EMAIL_ALREADY_REGISTERED"}`이고, **어느 주소인지는
응답에도 로그에도 싣지 않는다.** 다른 인증 실패와 달리 이유를 알려 주는 것은, 사용자가 직접
풀어야 하는 상태 충돌이라 안 알려주면 재시도만 반복하기 때문이다.

#### clip용 내부 창구 둘 (POK-175)

clip이 「이 사람이 이 스트리머의 방송을 봐도 되나」를 물을 때 쓴다. `/internal/**` 체인이라
`X-Internal-Token` 헤더가 필수이고 사용자 JWT로는 못 들어온다(스트림키·치지직 `resolve`와 같은 문).

**🔴 그 잠금은 이 문의 것이 아니라 내부 창구 셋이 나눠 쓰는 것이다.** 이 카드는 새 잠금을 만들지
않고 이미 있는 것에 올라탔다 — `InternalSecurityConfig`(경로를 잡는 체인)와 `InternalTokenFilter`
(열쇠를 대조하는 필터)를 고치면 **스트림키·치지직 창구가 같이 영향을 받는다.** 결함 주입으로 실측했다
(2026-08-23): 체인에서 이 경로를 빼면 범위 밖 **11건**이 같이 깨지고(대부분 `200→401` — 멀쩡한 창구가
막힌다), 열쇠 검사를 무력화하면 **5건**이 깨진다(전부 `401→200` — 잠긴 창구가 열린다).
**그 두 파일을 고치는 사람은 세 창구의 테스트를 다 돌린다.** 한 창구만 돌리면 이 사실이 안 보인다.

| 문 | 요청 | 응답 |
|---|---|---|
| `POST /internal/editor-delegations/resolve` — 한 쌍 판정 | `{"userId":7,"streamerUserId":3}` | `{"relation":"OWNER"\|"EDITOR"\|"NONE"}` |
| `POST /internal/editor-delegations/accessible` — 볼 수 있는 스트리머 목록 | `{"userId":7}` | `{"streamers":[{"streamerUserId":7,"relation":"OWNER"},{"streamerUserId":3,"relation":"EDITOR"}]}` |

- **회원 번호는 숫자다**(`users.id`). `streamerId`가 아니라 `streamerUserId`인 이유 — `streamerId`는
  Media→clip 편지에서 **문자열**(`broadcasts.streamer_id VARCHAR`)로 쓰인다. 그 값을 그대로 넣으면 안 된다
- **정수로 보내라. 검증이 생각보다 헐겁다** — Jackson이 조용히 바꿔 준다. 규칙은 하나다:
  **`Long`으로 읽히면 200, 못 읽으면 400.** 통과하는 갈래는 넷뿐이다 — 정수 · 정수 문자열(`"7"`→7) ·
  소수(`12.9`→**12**. 반올림이 아니라 **절삭**이다. `20.9999`도 20) · 지수 표기(`1.1e1`→11).
  부호는 안 본다 — 음수 번호(`-11`)도 통과한다.
  **그 밖은 전부 400이다** — `"abc"`·`true`·`null`·`[11]`·`{"v":11}`·빈 문자열·`Long` 상한 초과·
  번호 누락·깨진 JSON.
- **🔴 따옴표 하나로 갈린다.** `20.9`는 200(20으로 잘림)인데 **`"20.9"`는 400**이다.
  즉 「문자열로 보내도 통한다」는 **정수 문자열에만** 참이고, 소수를 문자열로 감싸면 거부된다.
  실측으로 전수 확인했다(2026-08-23, 두 문 모두 같다)
- **그래서 틀린 번호가 400이 아니라 조용히 다른 사람 판정으로 갈 수 있다** — 통과하는 네 갈래
  안에서는 아무 검사도 없다. 기존 `/internal/chzzk-link/resolve`도 같은 동작이라 일부러 맞춰 뒀다
  (고치려면 세 창구를 같이 고쳐야 한다)
- **🔴 모르는 필드는 조용히 버려진다.** `{"userId":11,"streamerUserId":11,"streamerId":"abc"}`는
  400이 아니라 **200**이고 `streamerId`는 없던 값이 된다. **POK-127의 타입 불일치가 정확히 여기로 온다** —
  clip이 옛 이름 `streamerId`를 **같이** 실어 보내면 오타를 알려 주지 않고 통과한다.
  `streamerId`**만** 보내면 그때는 `streamerUserId` 누락으로 400이다
- **판정 결과는 항상 200.** `NONE`은 「없는 회원 번호」·「해제된 편집자」·「초대만 받음」·「방향 반대」를
  구분하지 않는다 — clip이 할 일(안 보여줌)이 같다. 요청 형식이 틀리면(번호 누락·숫자 아님) **400**
- **`OWNER`는 번호 둘이 같을 때다.** 회원 표를 읽지 않는다
- **목록에서 본인은 `relation == OWNER`로 찾는다.** 첫 줄에 두지만 순서에 의존하지 말 것
- **목록 응답에 `NONE`은 나오지 않는다.** 목록에 없는 것이 곧 `NONE`이다
- **다만 그 역은 참이 아니다.** `accessible`은 **없는 회원 번호에도 자기 자신을 `OWNER`로 돌려준다** —
  `{"userId":999999}` → `{"streamers":[{"streamerUserId":999999,"relation":"OWNER"}]}`.
  회원 표를 안 읽는다는 계약 그대로다. 이 목록을 그대로 「이 사람이 볼 방송 목록」으로 쓰면
  **없는 번호가 한 줄 섞인다.**
- **`resolve`도 없는 번호를 걸러 주지 않는다.** `{"userId":999999,"streamerUserId":999999}`는
  번호가 같으므로 **`OWNER`**다 — 회원 표를 안 보기 때문이다. 없는 번호가 `NONE`이 되는 것은
  **짝이 안 맞을 때뿐이다**(`{"userId":999999,"streamerUserId":7}` → `NONE`).
  **「없는 회원 번호면 NONE」이 아니라 「모르는 사이면 NONE」이다** — 둘은 다르고,
  「이 번호가 실재하나」를 이 창구에 묻지 마라. 그 판정은 여기 없다
- **목록은 번호와 관계만.** 이름이 필요하면 따로 묻는다
- **목록 상한 없음.** 길이는 편집자 본인이 못 늘린다(스트리머가 초대해야 한 줄이 는다)
- 관찰: `NONE` 판정마다 카운터 `pokeclip.delegation.resolve.none` +1, INFO `auth.delegation.resolve_none userId= streamerUserId=`.
  회원 표를 안 읽어 「없는 번호가 왔다」를 모르므로 **이 숫자가 튀면** Media가 보내는 스트리머 번호가
  우리 회원 번호인지부터 본다(POK-127의 미확인 가정)
- **🔴 그런데 지금 배포되는 설정으로는 그 카운터를 밖에서 못 읽는다.** 막는 것이 둘이다 —
  ① `application.yml`의 `management.endpoints.web.exposure.include`가 `health`뿐이라 엔드포인트가
  아예 없고 ② `SecurityConfig`가 `/actuator/health`만 열어 둬 익명은 **401**이다(노출을 켜도 401은 그대로).
  읽으려면 **셋 다** 필요하다 — `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics`로 띄우고 ·
  유효한 사용자 JWT를 달고 · **`NONE`이 한 번은 난 뒤에** 부른다(카운터는 첫 판정 때 등록돼서
  그 전에는 노출·인증이 맞아도 **404**다. 설정이 틀린 것으로 오해하기 쉬운 자리다).
  **이 카드는 설정을 바꾸지 않았다** — 운영 노출 정책은 auth 전체와 다른 서버에 걸린 별도 결정이다.
  그때까지 같은 숫자를 보는 방법은 로그의 `auth.delegation.resolve_none` 줄을 세는 것이다(판정마다 한 줄)

### chat-collector — 치지직 채팅 수신 (POK-85) · 자동 재연결 (POK-86) · 적재 (POK-84) · S3 원본 아카이브 (POK-116) · **자동 시작·다중 스트리머 (POK-127)** · **수집 상태 창구 (POK-128)** · **영상 위치 창구 (POK-92)**

**방송이 켜지면 저절로 붙어서 받아 세고, 끊기면 다시 붙고, 받은 채팅을 PG에 남기고,
원본을 S3에 쌓고, 채팅 시각을 영상 안의 위치로 바꿔 주는 데까지** 한다.

**POK-127로 바뀐 것 셋:**

1. **사람이 켜지 않는다.** 1번 Media가 SQS FIFO 큐에 넣는 방송 시작·종료 편지(계약9,
   ADR-016)가 세션을 열고 닫는다. `clip`이 받는 것과 **같은 편지를 팬아웃으로 같이 받는다**
2. **스트리머마다 토큰이 다르다.** 방송이 열릴 때 auth의 `POST /internal/chzzk-link/resolve`로
   그 스트리머의 치지직 토큰을 받아 온다(POK-93이 auth 쪽에 만들어 둔 것) — 설정에 박힌
   토큰 하나로 붙던 배선이 여기서 끝났다
3. **한 프로세스가 방송 여럿을 동시에 붙든다.** 세션이 스트리머마다 따로 서고, 상태·지표·
   정지 신호도 세션마다 따로다. 하나가 끊겨도 나머지는 안 흔들린다

**채팅에 `stream_id`가 붙는다.** 어느 방송의 채팅인지가 표에 남고, 로그·요약 줄도
`stream=`을 단다(옛 경로는 `stream=none`).

| | |
|---|---|
| **자동 시작** | `STARTED` 편지 → 신원 확인 → auth에 토큰 요청 → 세션 수립. `ENDED`면 그 세션만 닫는다 |
| **멱등·순서** | 같은 편지가 두 번 와도 한 번만 연다(SQS는 at-least-once). 끝난 방송의 늦은 `STARTED`는 메모를 보고 무시한다 |
| 수신 | 세션 발급 → WebSocket → 구독 → `CHAT`. Engine.IO 3을 직접 다룬다 |
| 하트비트 | **전용 스케줄러에서만** `2`를 보낸다. 주기는 핸드셰이크의 `pingInterval`에서 파생 |
| **절단 감지** | 신호 **셋** — WS 종료 콜백 · ping 송신 실패 · **pong이 임계를 넘도록 안 옴**(좀비) |
| **재연결** | 세션 URL은 재사용이 안 되므로 **세션 발급부터 다시** 탄다. 두 배씩 늘려 상한에서 멈춘다 |
| **아카이브** | 받은 채팅 원본(치지직 안쪽 JSON 그대로 + 받은 시각)을 1분 파일로 묶어 S3에 올린다. 창고가 죽어도 수신은 안 멈춘다 |
| 관측 | 30초마다 `chat.summary` 한 줄 — 건수 · ping/pong 최대 공백 · 순서 위반 · 전달 지연 · 적재 카운터 넷(`persisted`/`conflicts`/`poisoned`/`dropped`) · 아카이브 카운터 여섯(`archived`/`archiveBufferDropped`/`uploaded`/`pending`/`droppedObjects`/`droppedMessages`). **`archiveRunId`는 요약 줄에 없다** — 시작 로그 `chat.archive.enabled runId=…`와 판정 줄에 실린다 |
| 종료 | 편지 그만 받기 → 새 세션 빗장 → **세션 전부를 나란히 닫기**(반납·소켓) → 수신 게이트 내림 → **마지막 배치 저장 ‖ 열린 창 마지막 업로드** → **프로세스 생애 판정 한 줄** |

**수집 상태 창구 (POK-128).** 방송 하나가 지금 채팅을 받고 있는지를 밖에서 묻는 문이다.
웹 라이브 화면의 「채팅 수집이 잠시 끊겼어요」 배너가 이 답으로 켜진다 — **웹이 직접 부르지 않고
clip이 대신 물어 SSE(계약2B)에 싣는다.** 그래서 문은 `X-Internal-Token`으로만 잠근다(auth의
`/internal/**`과 같은 값 `INTERNAL_API_TOKEN`, 새 환경변수 없음). `/actuator/health`와 경로가 갈려 있다 —
health는 프로세스를, 창구는 방송 하나를 말한다.

| | 부르는 쪽 | 인증 |
|---|---|---|
| `GET /internal/streams/{streamId}/chat-collection` | **clip** | `X-Internal-Token` 헤더. 토큰 설정이 비면 전부 401 |

**항상 200이다.** 모르는 방송도 `unknown`으로 답한다 — 404면 clip이 「그런 방송 없음」과 「수집 서버 장애」를
못 가른다(계약4C와 같은 이유).

**🔴 예외 하나 — DB가 죽으면 500이다.** 메모 표(`chat_ended_streams`)를 못 읽으면 그 예외가 그대로 나가
500이 된다(2026-08-22 실측. 본문·로그 어디에도 DB 주소·계정·예외 메시지는 안 실린다). **그것이 옳은 신호다** —
삼켜서 `unknown`을 답하면 장애 중에 배너가 꺼진다. **clip은 500을 「모름」으로 접지 말고 「수집 서버 장애」로
읽어야 한다.**

```json
{"streamId":"live-A-001","state":"reconnecting","since":"2026-08-22T10:12:30Z",
 "attempt":3,"needsRelink":false,"observedAt":"2026-08-22T10:13:05Z"}
```

| `state` | 뜻 | `since` | 웹 배너 |
|---|---|---|---|
| `establishing` | 방송 시작 직후, 붙는 중 | null | 끔 |
| `collecting` | 지금 받는 중 | null | 끔 |
| `reconnecting` | 끊겨서 다시 붙는 중 (`attempt`=몇 번째 시도. **끊긴 직후 최대 5초는 `0`이다** — 아래) | 끊긴 시각 | **켬** |
| `stopped` | 포기함 — `needsRelink`가 true면 스트리머가 치지직 연동을 다시 해야 한다. **붙어 보고 거부당한 것과 치지직 연동이 없어 붙어 보지도 못한 것이 같은 칸이다** — 아래 한계 ③ | 포기한 시각. **메모가 남기 전 짧은 구간은 `null`** — 아래 | **켬** |
| `ended` | 방송 종료 편지로 닫음 | 편지의 종료 시각 | 끔 |
| `unknown` | 모르는 방송 (24시간 지난 것 포함) | null | 끔 |

**🔴 `attempt >= 1`을 전제로 배선하지 마라 — 끊긴 직후에는 `0`이 나간다.** 절단 뒤 뒷정리(구독 반납 REST +
소켓 닫기)를 도는 동안은 아직 한 번도 다시 붙어 보지 않은 상태라 `0`이 맞다. `null`이 아니라 **`"attempt":0`**이다.
창의 폭은 반납 왕복이 정한다 — 평시 21ms, 치지직 반납이 느리면(반개방·과부하) **최대 5초**(REST 읽기 시한)다.
그리고 이 창은 예외가 아니라 규칙이다: 절단이 나면 **매번 지나간다**(2026-08-22 실측, 표본 16,333/16,335).
**배너는 `state`로 켠다** — `attempt`는 옆에 보여 주는 숫자일 뿐이다.

**포기로 가는 길이 둘인데 답은 같다.** ⓐ 붙어서 걷다가(또는 재연결 중에) 거부당한 것과
ⓑ **방송 시작 직후 첫 수립부터 영구 실패한 것**(발급 401·403) 둘 다 `stopped`다. ⓑ는 세션이
소켓을 한 번도 못 세운 경우인데, 그 자리가 한때 `establishing`을 답했다 — **배너를 끄는 값이라
포기한 방송이 「붙는 중」으로 보였다**(봇 1판에서 잡혔다). 지금은 알림을 보내기 전에 그 자리를
멈춤으로 표시해 두 길이 같은 답을 준다. **`state`만 보고 배너를 켜면 두 길이 다 잡힌다.**

**🔴 `stopped`의 `since`가 잠깐 `null`로 온다 — 「모른다」는 뜻이지 「방금」이 아니다.** 포기 순간부터
포기 메모가 DB에 남기까지의 구간에는 그 시각을 아무도 들고 있지 않다(세션 스냅숏에 없는 값이다).
지어내지 않고 비워서 보낸다. 창의 폭은 메모 INSERT 한 번이라 평시 밀리초이고, DB가 반개방이면
**최대 10초**(`socketTimeout`)다. 메모가 남는 순간부터는 진짜 값(`created_at`)이 실린다.
**clip은 `since`가 없을 때 `observedAt`으로 대신 채우지 마라** — 그러면 「멈춘 지 0초」가 되어
그 구간 내내 0이다가 메모가 남은 뒤 갑자기 10초로 뛴다. `state`만 보고 배너를 켜고, 경과 시간은
`since`가 올 때까지 안 보여 주는 것이 맞다. **메모를 끝내 못 남기면 `unknown`이 된다**(아래 한계 ②).

**`since`는 정상일 땐 안 온다(null).** 내부 사유 이름은 밖에 내보내지 않는다 — `needsRelink` 하나로 줄였다
(내부 사유가 바뀔 때마다 3인 계약을 다시 열지 않으려고). 포기한 방송은 끝난 방송 메모(`chat_ended_streams`)에
`stop_reason` 칸으로 남고(V304) 24시간 뒤 같은 치우기로 사라진다. 설정 토큰으로 붙는 옛 경로(`CHZZK_ENABLED`)의
방송은 번호가 없어 이 창구로 못 본다.

**알려진 한계 셋 — 이번 카드에서 고치지 않았다.**

① **`needsRelink=true`를 보고 스트리머가 치지직 연동을 다시 해도, 그 방송은 24시간 동안 다시 안 붙는다.**
수집을 다시 시작하는 트리거가 없고(POK-127 미결 — 일회용 시작 편지뿐), 포기 메모가 남아 있는 동안은 같은 방송의
시작 편지가 다시 와도 「끝난 방송 뒤의 시작」으로 지워진다. 다시 붙는 것은 **새 방송을 켰을 때**(새 방송 번호)다.
**배너 문구를 정하는 2번이 알아야 한다** — 「연동을 다시 하면 바로 복구된다」고 쓰면 거짓이다.

② **포기 메모를 못 남기면 그 방송은 영구 `unknown`이다.** 포기 순간 DB가 죽어 있으면 메모가
안 남고(재시도 없음, 경고 `chat.broadcast.stopped_memo_failed` 한 줄), 등록부에서 지워진 뒤로는 창구가
`unknown`(배너 끔)을 답한다 — 스트리머는 수집이 멈춘 것을 화면에서 못 본다.

③ **🔴 연동을 한 적이 없는 방송에도 `needsRelink=true`가 간다.** auth가 열쇠를 영구히 거절하는
갈래 넷(`UNLINKED` 해제 · `BROKEN` 재동의 필요 · **`NOT_LINKED` 연동한 적 없음** · 계약 위반)을
**안 가르고** 전부 `stopped` + `needsRelink=true`로 보낸다. **배너 문구를 정하는 2번이 알아야 한다** —
문구가 「**다시**」를 전제하면(「채팅 수집이 잠시 끊겼어요」·「연동을 다시 해 주세요」) 한 번도 연동한
적 없는 스트리머에게는 어색해진다.

*왜 안 갈랐나*: 스트리머가 할 일이 넷 다 「치지직 연동을 손봐라」로 같고, 그것이 `needsRelink`의
뜻이다. 그리고 **안 보내면 그 스트리머는 왜 채팅이 안 걷히는지 영영 모른다**(배너가 꺼진 채
방송이 끝난다). 가르려면 auth 계약(`POST /internal/chzzk-link/resolve`)의 응답이 사유를 들고
와야 하는데 지금은 「재시도 가능한가」 한 칸뿐이라 **3인 계약을 여는 일**이고 이 카드의 범위를
넘었다. 계약 위반은 health 카운터(`unreadableStreamerIds`)가 이미 따로 드러낸다.
**사유를 세분화해 배너를 가르는 것은 다음 카드 후보다.**

**영상 위치 창구 (POK-92).** 채팅 한 건이 찍힌 시각을 그 방송 **영상 안의 위치(ms)**로 바꿔 준다.
하이라이트 순간을 클립의 시작·끝으로 옮기는 계산의 첫 단계다 — 판별기(POK-59)가 붙으면
**채팅마다** 부른다. 문은 위 수집 상태 창구와 같은 `X-Internal-Token` 하나다(새 환경변수 없음).

| | 부르는 쪽 | 인증 |
|---|---|---|
| `GET /internal/streams/{streamId}/video-position?messageTime=<값>&channelId=<선택>` | **clip · 판별기** | `X-Internal-Token` 헤더. 토큰 설정이 비면 전부 401 |

**`messageTime`은 두 형식을 받는다** — epoch ms(`1787529601000`, **치지직이 주는 형식**)와
ISO-8601(`2026-08-24T12:00:00Z`). 숫자로만 이뤄졌으면 epoch ms로 읽는다. 오프셋 표기(`+09:00`)도
되지만 **쿼리에서 `+`는 공백으로 디코드되므로 `%2B`로 인코딩해야 한다** — 그대로 치면 400이다.
`channelId`는 보정값을 고르는 열쇠이고, 없으면 기본 보정값(`CHAT_SYNC_OFFSET_MS`)이 쓰인다.

```json
{"streamId":"live-A-001","state":"converted","positionMs":5500,"segmentSeq":2,"appliedOffsetMs":3900}
```

| `state` | 뜻 | `positionMs`·`segmentSeq` | 다시 물으면 |
|---|---|---|---|
| `converted` | 그 시각의 영상 위치를 찾았다 | 값이 있다 | — |
| `not_yet_indexed` | 조각이 아직 장부에 안 들어왔다 | **둘 다 `null`** | **답이 바뀔 수 있다** |
| `no_footage` | 그 시각의 영상이 **영영 없다** — 첫 조각 이전 · 조각 사이의 진짜 공백 · 벽시계가 역행한 구간 | **둘 다 `null`** | 안 바뀐다 |

**🔴 방송 시작 직후 「보정값만큼」은 `no_footage`다 — 결함이 아니라 정의다.** 기본 보정값이
양수(3900)이므로 채팅 시각에서 그만큼을 빼면 방송이 시작하고 처음 3.9초 동안의 채팅은 첫
조각보다 이른 시각이 된다. 그 채팅이 반응한 화면은 **녹화가 시작되기 전**이라 영상에 정말
없다. 즉 **모든 방송이 시작할 때마다** 이 구간이 나가므로 clip·판별기는 그것을 알고 배선한다.
「위치 0으로 접어 달라」고 고치면 없던 화면을 가리키는 클립이 만들어진다.

**🔴 `not_yet_indexed`와 `no_footage`를 뭉치지 마라.** 앞은 재시도할 자리이고 뒤는 포기할
자리다. 뭉치면 영영 안 올 것을 영원히 다시 묻거나, 곧 올 것을 포기한다. **모호한 자리에서는
「다시 물으면 되는 쪽」이 안전한 실패다** — 포기한 쪽은 그 채팅의 하이라이트를 영영 잃는다
(채팅에는 백필이 없다). 수집 상태 창구의 「모호하면 배너를 켜는 쪽」과 같은 결의 결정이다.

**🔴 `positionMs`는 영상 전체 기준 절대 위치이고 `segmentSeq`는 참고값이다.** 둘을 「이 조각
파일 안에서 seek할 오프셋」 쌍으로 쓰면 경계에서 어긋난다 — 미세 어긋남 구간에서 `positionMs`가
**다음 조각의 시작과 같아지는데** `segmentSeq`는 여전히 앞 조각이다.

**`appliedOffsetMs`는 판정과 무관하게 늘 실린다.** 「왜 이 위치가 나왔나」를 밖에서 재현하는 값이다.

**🔴 400과 500의 뜻이 다르다.** `messageTime`이 없거나, 두 형식 어느 쪽으로도 안 읽히거나,
**다룰 수 있는 범위(1970~2200) 밖이면 400**이고 본문은 `{"error":"…"}`다(스프링 기본 본문이
아니라 우리가 정한다 — 형식이 둘이라 그 안내가 곧 진단이다). **범위를 따로 자르는 이유**는
`long`에는 들어가는데 PostgreSQL `timestamptz`에는 안 들어가는 구간이 있어서다 — 그대로 던지면
DB가 거절해 500이 되고, **입력 오류가 장애로 오인된다**(epoch 단위를 나노초로 착각하면 정확히
이 구간이다). 반면 `stream_segments` 표가 없거나 DB가 죽으면 **500을 그대로 낸다** — 삼켜서
`not_yet_indexed`를 답하면 부르는 쪽이 영영 안 올 것을 영원히 다시 묻는다.
**clip은 500을 「모름」으로 접지 말고 「수집 서버 장애」로 읽어야 한다**(수집 상태 창구와 같은 결정).

**이 창구는 `stream_segments`(1번 Media 소유)를 읽기만 한다.** 운영에서 그 표를 만드는 것은
`segment-indexer`(저장소 루트 `docker-compose.yml`, `ENSURE_SCHEMA=true`)뿐이다 —
`services/docker-compose.dev.yml`에는 그것이 없으므로 **그 compose로만 띄운 프로세스에서는
이 창구가 500이다.** 표가 없어서지 장애가 아니다 — 500을 보면 이것부터 본다.

> **「dev 환경에서」라고 쓰지 않는다** — 위 compose 절과 같은 이유다. `services-deploy.yml`이
> dev EC2에 올리는 것은 `postgres`·`auth`·`clip` 셋뿐이라 **dev에는 수집기가 아예 없고**,
> 없는 서버는 500도 못 낸다. 이것이 드러나는 자리는 **개발자 로컬**에서 루트 compose 없이
> `services/docker-compose.dev.yml`만 띄웠을 때다.

**"채팅이 안 온다"는 절단 신호로 안 쓴다.** 방송을 꺼도 세션은 살아 있고 채팅만
안 온다(361초 확인). 한산한 방송과 끊긴 연결을 그것으로는 못 가른다.

**재시도해도 안 풀리는 것만 포기한다** — 두 단계(발급·구독)의 401·403과 `revoked`다.
그 밖에는 **영원히 다시 붙는다.** 연결 상한 초과는 특별 취급하지 않는다 —
핸드셰이크 실패와 응답으로 구분되지 않는다.
**포기했을 때의 조치가 경로마다 다르다 — POK-127에서 갈렸다.**

| | 무엇을 내리나 |
|---|---|
| **편지 경로** | **그 방송의 세션만** 닫고 등록부에서 지운다(`chat.registry.stopped reason=`). 프로세스는 산다 |
| 옛 경로 | 판정 줄을 남기고 **프로세스를 내린다**(`exit 1`) |

**편지 경로에서 프로세스를 내리면 안 되는 이유:** 한 스트리머가 동의를 철회했다고
**나머지 아홉 방송의 채팅까지 끊긴다.** 그리고 그것들을 되살릴 `STARTED` 편지는 이미
소비돼 큐에 없다. 옛 경로에서 내리는 것은 여전히 맞다 — 거기엔 세션이 하나뿐이라
수집이 영영 안 되는 STOPPED로 살아 있을 이유가 없고, 버퍼에 남은 채팅은 그 프로세스가
죽는 순간 사라진다. 내리기 전에 잔량이 있으면 DB 회복을 **최대 30초** 기다려 저장한다.
토큰이 잘못된 채 배포하면 재시작 루프가 되는데, 매 재시작의 판정 줄 `reason=`이 그것을 말한다.

**판정 줄은 프로세스 생애에 한 줄이다**(세션마다가 아니다). 재연결이 N번 돌아도
한 줄이고, 거기에 `reconnects=` · `outage=`(누적 절단 시간) · `lastOutageFrom/To`가 실린다.
**세션 하나의 값은 `chat.session.ended`가 따로 낸다** — `stream=`·`maxReceiveGap=`이 거기 실린다.

**여러 세션의 값을 판정 줄에 합칠 때 항의 성격을 따랐다** — 아무거나 더하면 조용히 틀린 값이 나간다.

| 성격 | 항 | 판정 줄에 |
|---|---|---|
| 더할 수 있다 | `received` · 세션 수(`registrySessions=`) | **싣는다.** 닫힌 세션 몫도 걷어 둔다 |
| 프로세스 누계라 합칠 것이 없다 | `persisted`·`conflicts`·`poisoned`·`dropped` | 싣는다(원래 하나뿐이다) |
| **최댓값이라 못 더한다** | 최대 수신 공백 · ping/pong 최대 간격 | **안 싣는다.** 세션별 `chat.session.ended`가 낸다 |
| **합칠 수 없다** | 지연 중앙값 · 마지막 절단 시각 | 안 싣는다. 표본을 버린 뒤라 다시 못 낸다 |

**`session=`은 편지 경로에서 0이다** — 그것은 *러너 자신이* 연 세션 번호이고 편지 경로에서
러너는 세션을 안 연다. 숫자를 바꾸면 그게 거짓말이 되므로 뜻을 안 바꾸고 `registrySessions=`을
옆에 새로 실었다. **편지 경로가 켜졌는데 채팅을 한 건도 못 받은 프로세스는 판정 줄이 없다** —
등식 다섯 항이 전부 0이라 실을 것이 없다. 세션을 열고 닫은 사실은 `chat.registry.*` 줄이 든다.

**끊긴 채로 끝나도 그 구간이 판정에 실린다** — 영구 정지(401·`revoked`)든 프로세스
종료든 마찬가지다. 그때는 `lastOutageTo=none`이고 `reconnects`는 안 오른다(다시 붙은
적이 없다). 즉 `lastOutageFrom`만 있고 `lastOutageTo=none`이면 **"이때 끊겨서 끝까지
못 돌아왔다"**는 뜻이고, `outage=`는 판정 시각까지의 **하한**이다.

**절단 구간은 `maxReceiveGap`에 안 섞인다.** 섞으면 "한산했을 뿐"과 "끊겨 있었다"가
같은 숫자로 보인다.

**받은 채팅은 `chat_messages` 표에 남는다**(V301). 수신 스레드는 버퍼에 넣기만
하고, 전용 스레드가 1초 배치로 INSERT 한다 — 멱등은 표의 지문 UNIQUE 제약이 진다.

**`stream_id` 칸이 붙었다**(V302, `VARCHAR(128)` · NULL 허용 · `(stream_id, received_at)` 부분 인덱스).
NULL이 곧 **"어느 방송인지 모른다"**는 표시다 — 이미 쌓인 옛 채팅과 편지 없이 붙는 옛 경로가 그렇다.
폭은 `clip`의 `broadcasts.stream_id`와 **같아야 한다** — 좁으면 22001로 거절당하고, 그것은
SQLSTATE 22류라 격리 폐기되어 **채팅이 조용히 사라진다.**
**지문 UNIQUE에는 안 넣었다** — 넣으면 방송 경계에서 같은 채팅이 두 번 들어간다(번호만
갈아끼우고 소켓은 그대로라, 겹치는 프레임이 서로 다른 번호를 달고 온다).

`persisted`(저장) · `conflicts`(지문 충돌로 접힘) · `poisoned`(영구 데이터 오류 격리
폐기) · `dropped`(버퍼 상한 초과 전용) 네 카운터가 요약·판정 줄에 실려
`received = persisted + conflicts + poisoned + dropped`로 검산한다.
**DB가 죽어도 수집은 계속된다** — 버퍼가 보존되고 복구되면 밀린 것을 저장하며,
부팅 시점에 DB가 죽어 있어도 마이그레이션을 백오프 재시도로 넘기고 수집부터
시작한다. 연결만 받고 응답을 안 하는 **반개방 스톨**도 JDBC `socketTimeout` 10초에
끊겨 같은 보존·재시도로 돌아온다 — HikariCP `connection-timeout`과 JdbcTemplate
`queryTimeout`은 이것을 못 끊는다(실측, 2026-08-15). **재시도하는 것은 연결 장애뿐이다**(SQLState 08·57P·53 또는 접속 예외 —
죽은 포트·없는 호스트·DB 기동 중·접속 초과). 그 밖은 전부 프로세스를 죽인다 —
검증(체크섬)·SQL 실행 실패는 물론, **비밀번호 오류(28P01)·권한 없음(42501)처럼
접속은 됐는데 안 되는 것도** 재시도로 안 풀리기 때문이다(부팅 중이면 부팅 실패,
재시도 중이면 **exit 1**로 내려 `restart: on-failure` 재시작이 부팅 fail-fast
경로를 타게 한다). 모르면 죽어서 드러나는 쪽이 안전하다.

**개별 메시지를 로그로 남기지 않는다.** 본문·`senderChannelId`·닉네임·토큰이 어떤
레벨에서도 안 나가고 검사가 그것을 강제한다. 디버깅하려고 한 줄 찍으면 그 검사가
빨간불이 된다 — **레벨을 낮춰도 통과하지 않고, 그게 의도다.** 이 보장은 우리 코드
밖의 **JDBC 파라미터 로거까지 덮는다** — `LOGGING_LEVEL_ROOT=trace`로 내려도
스프링(`org.springframework.jdbc`)·pgjdbc(`org.postgresql`)의 바인딩 값 로그는
`application.yml`이 info로 박아 안 찍히고, 그 검사가 root를 TRACE로 올린 채 적재를
돌려 이것을 강제한다.

실측(실제 라이브 11분 30초): 186건 · ping 최대 간격 **20.0초**(임계 40초) ·
전달 지연 중앙 **약 175ms**(`messageTime` → 우리 수신).

> **175ms를 POK-92의 시차 보정 오프셋으로 쓰지 마라.**
>
> ```
> 오프셋 = (치지직 방송 지연 + 시청자 반응 지연 + 전달 지연) − 우리 인제스트 지연
>           미측정·초 단위      미측정·초 단위      175ms      인코딩+SRT 버퍼 0.3s~
> ```
>
> `start_wall_utc`는 **우리 서버가 조각을 받은** 시각이라 그 시점의 영상은 이미
> 인제스트 지연만큼 이전 사건이다. 채팅 쪽만 빼면 그만큼 어긋난다
> (ADR-020 4절 지연 예산). **이 값 하나로 잡으면 자릿수가 어긋난다.**
>
> **2026-08-24에 이 합을 통째로 쟀다 — 3.9초다**(위 `CHAT_SYNC_OFFSET_MS`).
> 항별로 나눠 재지 않았으므로 위 표의 「미측정」 둘은 그대로다. 우리 인제스트 지연만
> 부수로 785ms가 나왔다.

#### S3 원본 아카이브 (POK-116)

**받은 채팅의 원본을 그대로 S3에 쌓는다.** 표(`chat_messages`)는 4필드(채널·보낸 사람·
본문·시각) 요약이고 원본 프레임은 버린다 — 닉네임·이모티콘·배지처럼 지금 안 뽑는 필드가
판별 기준값(고유 채터 수 등) 산출에 쓰이는데, 원본이 없으면 되돌릴 수 없다.

**원본 = 치지직 `CHAT` 봉투의 안쪽 JSON 문자열 글자 그대로 + 우리가 받은 시각(epoch ms) + 채널 ID.**
파싱해서 다시 직렬화하지 않는다 — 필드 순서·공백까지 바이트가 같아야 "원본"이다.

**파일 형식은 JSON Lines다.** 한 줄이 채팅 한 건, `raw`는 **JSON 문자열 값으로 감싼다**(객체로
끼워 넣지 않는다). 원문에 줄바꿈이 있어도 한 줄이 유지되고, 읽는 쪽이 파싱하면 바이트 하나
안 틀리는 원문이 나온다.

```
{"receivedAtMillis":1786852111229,"raw":"{\"channelId\":\"…\",\"content\":\"ㅋㅋ\",\"messageTime\":1754300000000}"}
```

**키 규칙:**

```
chat/{channelId}/{yyyy-MM-dd}/{HH}/{HHmm}-{runId}.jsonl
```

- **UTC · 우리가 받은 시각 기준 1분 창.** `messageTime`(치지직 시각)이 아니다 — 1번 media의
  세그먼트 키(`streams/{streamId}/{date}/{HH}/seg_NNNNNN.m4s`)와 같은 시계·같은 층위라 나중에
  영상과 채팅을 나란히 놓는다
- **`runId`는 프로세스 시작 시 만든 8자 무작위 표식.** 10:23:10에 껐다가 10:23:40에 다시 켜면
  두 프로세스가 같은 `1023` 파일을 쓰려다 나중 것이 앞 것을 덮어쓴다 — 표식이 그것을 막는다.
  판정 줄의 `archiveRunId=`가 같은 값이라 "이 프로세스가 올린 파일"을 S3에서 찾을 수 있다
- **드물게 `-2`·`-3` 접미가 붙는다**(`1023-a1b2c3d4-2.jsonl`). 창이 닫힌 뒤 그 분의 채팅이
  뒤늦게 와 같은 창이 다시 열린 것이다(바구니가 2초 넘게 밀리거나 시계가 역행할 때). 같은 키로
  덮어쓰는 대신 나란히 둔다
- **읽는 쪽 규칙은 하나다 — 그 분의 파일을 전부 읽어라.** runId·접미로 고르지 않는다
- **방송 식별자는 채널이다.** `stream_id`(POK-82)가 생겨도 키를 안 바꾼다 — 방송 시간표가
  생기면 채널 + 시각 범위로 찾는다. 채널 ID에 경로 문자가 섞이면 `_`로 바꾼다

**켜는 법 — `S3_BUCKET`이 비면 통째로 꺼진다**(1번 media의 관례와 같다). CI·팀원 로컬의 기본
상태다. 자격증명은 코드·설정에 없다 — **SDK 표준 체인**(환경변수 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`
· 프로파일 · IAM 역할)이 찾는다.

| 환경변수 | 기본 | |
|---|---|---|
| `S3_BUCKET` | (빈 값 = 꺼짐) | 창고 이름 |
| `AWS_REGION` | `ap-northeast-2` | |
| `S3_ENDPOINT` | (빈 값 = 진짜 AWS) | LocalStack·MinIO 등 호환 스토리지 주소 |
| `S3_FORCE_PATH_STYLE` | `false` | 호환 스토리지는 대개 `true` |
| `CHAT_ARCHIVE_PENDING_MAX` | `60` | 못 올린 1분 파일 대기 줄 상한(파일 수 ≈ 60분치) |
| `CHAT_ARCHIVE_BUFFER_MAX` | `10000` | 수신→아카이브 바구니 상한(채팅 수). DB 바구니와 같다 |

**상한 둘 다 넘치면 오래된 것부터 버리고 센다** — 메모리 상한이 거짓말이 안 되게. 최근 것이
판별에 더 가치 있다는 판단은 DB 바구니와 같다.

**시한 넷을 전부 명시했다 — 기본값에 맡기지 않는다.** 접속 **2초** · 소켓 **3초** · 시도 **4초** ·
호출 **4초**(`S3Clients`). 연결만 받고 응답을 안 하는 **반개방**에서 시한이 없으면 아카이브
스레드가 무기한 매달려 대기 줄이 상한까지 차고 버리기만 한다 — 적재가 JDBC에서 밟은 것과 같은
함정이다. SDK 자체 재시도는 끈다 — 재시도는 아카이버의 백오프(1초 → 두 배 → 60초)가 맡고,
둘이 겹치면 한 번의 실패가 몇 배로 늘어진다. **반개방 실측: `SdkClientException` 3001~3022ms**
(소켓 3초 층이 시도 4초보다 먼저 끊는다) · 죽은 포트는 커널이 RST로 **3~19ms**에 거부(접속 시한을
재는 것이 아니다) · 접속 시한 2초는 블랙홀 주소(TEST-NET-1)로 2004ms 실측 — egress 없는 CI에서
반대로 빨강이라 상시 테스트에는 안 넣었다.

**창고가 막혀도 수신은 안 멈춘다.** 백오프는 잠들지 않고 "다음 시도 시각"만 기억한다 — 잠들면
그동안 퍼가기가 멈춰 바구니가 차고 창이 안 닫힌다. 반개방 중에는 시도 한 번이 최대 4초 매달리고
그동안 틱이 못 도는데(같은 스레드), 그 사이는 바구니(상한 1만)가 받는다 — 백오프 간격마다
4초씩 퍼가기가 멈추는 것이 정직한 한계다(실측: 스톨 → 매달림이 풀려 퍼가기 재개까지 약 3.0초).
반개방 → 실패 → 백오프 → 회복 → 밀린 파일 전부 업로드를 가짜 S3(LocalStack) 앞에 응답 삼키는
TCP 중계기를 세워 잰다.

**카운터 여섯이 요약·판정 줄에 실린다** — `archived`(창에 들어간 채팅) · `archiveBufferDropped`
(바구니 상한 초과) · `uploaded`(올린 파일) · `pending`(대기 중 파일) · `droppedObjects`/
`droppedMessages`(대기 줄 상한 초과 + 종료 시 못 올려 버린 파일·그 안의 채팅). 등식 둘로 검산한다:
`received = archived + archiveBufferDropped`(채팅 단위) · `uploaded + pending + droppedObjects =
닫힌 창 수`(파일 단위). 켜졌는데 못 올리는 것은 health가 아니라 이 카운터로 드러낸다.

**등식 둘은 아카이브가 <u>켜져 있을 때</u>의 검산이다.** `S3_BUCKET`이 비면(기본값 — CI·팀원 로컬·
버킷을 넣기 전 운영이 전부 여기다) 여섯 항이 계속 0이라 `received=348 archived=0`처럼 나가고
첫째 등식이 성립하지 않는다. **그것은 유실이 아니라 꺼짐이다.** 가르는 법 둘 — 시작 로그
`chat.archive.disabled reason=no_bucket`, 판정 줄 `archiveRunId=none`(켜져 있으면 8자 hex).

또 **30초 요약 줄의 둘째 등식은 순간값이다.** 여섯 항을 따로 읽는 사이 업로드 한 건이 끝나면
`uploaded`는 옛 값, `pending`은 새 값이라 합이 1 모자라 보일 수 있다(다음 줄에서 회복된다).
**정본은 판정 줄이다** — 아카이버가 닫힌 뒤에 나가므로 그 시점엔 움직이는 값이 없다.

**카운터에 안 잡히는 사건 둘은 로그가 유일한 단서다** — 같은 (채널,분) 창이 다시 열린 것은
`chat.archive.window_reopened`(첫 건만), 한 줄 인코딩 실패는 `chat.archive.encode_failed`(첫 건만)로만
보인다. 업로드 실패·회복은 `chat.archive.upload_failed causeType= pending=` / `upload_recovered
afterFailures= pending=`이고, 종료 쪽은 `close_timeout` · `close_flush_failed causeType= pending=
bufferSize=` · `close_dropped objects= messages=`다. **본문·발신자·S3 키는 어느 줄에도 안 싣는다.**

**종료 — DB 저장기와 나란히 닫는다.** 열린 창을 전부 닫아 대기 줄에 세우고 앞에서부터 한 번씩
올린다. 아카이브는 마지막 flush를 자기 스레드에 제출만 하고 돌아오므로 저장기의 5초 대기 동안
저쪽도 돈다 — 종료 예산이 안 는다. **창고가 죽어 있으면 1회 실패 뒤 대기 줄 전부(최대 60분치)를
버리고 센다**(`chat.archive.close_dropped objects= messages=`) — 파일마다 4초 시한을 쓰면 유예
20초를 넘기기 때문이다. 알려진 한계로 받아들였다.

**개별 메시지 로그 금지는 아카이브 경로에도 걸린다.** 본문·raw·**S3 키**가 어떤 레벨에서도
안 나가고, 실패 로그는 `causeType=` 타입 이름과 건수뿐이다. AWS SDK가 끌어오는 Apache 5의 wire
로거는 DEBUG에서 PUT 본문을 바이트째 찍으므로 `software.amazon.awssdk` · `org.apache.hc.client5.http`
· `io.netty` 셋을 `application.yml`이 info로 박았다 — `LOGGING_LEVEL_ROOT=trace`로 내려도
안 뚫리고, `ArchiveLogLeakTest`가 root를 TRACE로 올린 채 실제 PUT을 나가게 해서 강제한다(wire 로거를
직접 TRACE로 밀면 새는 것도 같은 검사가 양성 대조로 확인한다).

**RestClient는 JDK로 고정했다**(`spring.http.clients.imperative.factory: jdk`). AWS SDK가 끌어오는
httpclient5가 클래스패스에 있으면 Boot가 치지직 REST(세션 발급·구독·반납)의 구현체를 오류 없이
Apache HC5로 바꾼다(실측: `JdkClientHttpRequestFactory` → `HttpComponentsClientHttpRequestFactory`).
그러면 재연결·종료 예산 산수의 근거(접속 2초·읽기 5초가 어느 층에서 끊는가)를 통째로 다시 재야
한다. `CollectorConfigTest`가 구현체를 단언한다.

**알려진 한계 — 동작에 영향 없음, 다음 아카이브 카드에서 재검토:**

| 무엇 | 왜 그대로 뒀나 |
|---|---|
| 창고 사망 시 종료에서 대기 줄 전부(최대 60분치) 폐기 | 파일마다 4초 시한 × 60개면 유예 20초를 넘긴다. 카운터로 센다 |
| 대기 줄이 수십 개 남은 채 창고가 살아 있으면(장애 직후 회복된 순간 종료) 파일 수 × 왕복 시간이 5초를 넘길 수 있다 — 그때 `chat.archive.close_timeout`이 남고 못 올린 나머지는 잃는다 | "성공이 이어지면 끝까지"가 예산 안에서 최대한 올리는 길이다(사용자 지적, 2026-08-16) |
| 영구 정지 경로(401·403·`revoked`)는 DB 회복은 30초 기다리지만 **S3 회복은 안 기다린다** | 위 "1회 시도 → 실패면 전부 버림"으로 간다. 대기 줄이 메모리라 프로세스가 내려가면 어차피 사라진다 |
| 창 닫기 조건 ①은 "다음 분"이 아니라 **"다른 분"**이다 — 시계 역행으로 이전 분 채팅이 오면 열린 창을 닫고 이전 분 창을 새로 연다(채널당 창 하나) | 유실 없음, 그 분 파일이 조각날 뿐이다(아래 `-N`이 덮어쓰기를 막는다). NTP slew에선 안 난다 |
| 업로드 백오프의 "다음 시도 시각"이 벽시계다 — 시계가 N초 뒤로 가면 업로드가 N초 더 멈춘다(수집·창 닫기는 계속) | NTP는 보통 slew, 역행은 VM 복원류. 창 열쇠·유예도 벽시계라 백오프만 monotonic으로 바꿔도 반쪽이다 |
| RestClient JDK 고정 | httpclient5 클래스패스 부작용. 위 문단 |
| 순번 접미 `-N`(재열림 덮어쓰기 방지) 기억은 **닫는 창 기준 2시간**이다 — 시계가 2시간 넘게 앞으로 튀었다가 되돌아오면 그 사이 잊힌 분의 순번이 1부터 다시 시작해 앞 파일을 덮을 수 있다 | 앞점프와 복원이 겹쳐야 하는 이중 이상(VM 스냅샷 복원류)이다. 개수 기준(채널당 최근 N개)으로 바꾸면 시계와 무관해지지만 구조 변경이다. 읽는 쪽 규칙("그 분의 파일 전부")은 그대로다 |

**운영에 넘긴 것 — S3 수명 규칙(만료·계층 이동)은 `infra/`(1번) 몫이다.** 여기서는 올리기만 한다.

**LocalStack으로 손으로 확인하는 법** — 테스트가 쓰는 것과 같은 이미지다(**`4.14.0`**, 커뮤니티
이미지의 마지막 SemVer. `2026.x`는 유료 인증 이미지라 토큰 없이는 즉시 종료한다. 기동 1.7~2.5초):

```bash
docker run --rm -p 4566:4566 localstack/localstack:4.14.0
S3_BUCKET=pokeclip-chat S3_ENDPOINT=http://localhost:4566 S3_FORCE_PATH_STYLE=true \
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
CHZZK_ENABLED=true CHZZK_ACCESS_TOKEN=<유저 Access Token> ./gradlew :chat-collector:bootRun
```

버킷은 먼저 만들어야 한다(`aws --endpoint-url=http://localhost:4566 s3 mb s3://pokeclip-chat`).
Ctrl+C 뒤 판정 줄의 `uploaded=`와 버킷의 `chat/` 아래 파일 수가 같아야 한다.

### 나머지

`chat-detector`는 빈 껍데기다. `common`은 소스가 0개다 —
두 서버가 똑같이 쓰는 계약이 실제로 생기면 그때 채운다.

다음 작업 순서:

1. **`pairing_exchange_attempts` 청소 작업을 넣는다.** 교환이 `permitAll`이라
   미인증 트래픽이 행을 쌓는다 — 이게 없으면 운영에 올릴 수 없다
2. `POST /api/stream-keys/pairing-codes/exchange`의 `X-Forwarded-For` 처리.
   ALB 뒤로 가면 전 요청이 같은 IP로 보여 rate limit이 전역 한도가 된다
3. SQS 대역(ElasticMQ)을 루트 compose에 추가한다 — `clip`이 렌더 잡을 발행하려면 필요하다.
   생명주기 수신은 실측을 LocalStack으로 했고, compose에는 아직 큐가 없다.
   **이제 그 편지를 받는 서버가 둘이다**(`clip` POK-82 · `chat-collector` POK-127) —
   팬아웃이라 **큐도 둘**이다

> **ArchUnit 도입 계획은 없어졌다.** auth와 clip 경계를 코드 규칙으로 막으려던 것인데,
> 프로세스가 갈리면서(ADR-022) 물리적으로 분리됐다. 대신 지킬 것은 **DB 표 소유 경계**다.
