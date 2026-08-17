# services — 코어 API (Spring)

**담당: 3번 (`@kth4778`)**

## 무엇이 들어가나

**서버 4개**가 여기서 실행된다. `common/`·`web-support/`는 서버가 아니다.

| 모듈 | 포트 | 역할 |
|---|---|---|
| `common/` | — | 여러 서버가 같이 쓰는 **계약**(공유 엔티티·SQS DTO). 서버가 아니다 |
| `web-support/` | — | 여러 서버가 같이 쓰는 **웹 인프라**(CORS·상관 ID). 서버가 아니다 |
| `clip/` | 8081 | 방송 세션 · 세그먼트 인덱스 · 클립 · 승인 · SQS 잡 발행 |
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
| `clip` | `broadcasts` · `jump_cards` · `stream_segments` (**아직 없다** — 마이그레이션이 비어 있다) |
| chat 계열 | `chat_messages` (V301) — **collector가 쓰고 detector가 읽는다.** 같은 담당(3번)·같은 V3xx 대역의 공동 소유라, 아래 "서로의 표를 직접 읽지 않는다"의 예외가 아니라 한 소유자의 두 프로세스다 |

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
| `chat-collector/` | 방송 내내 연결을 **붙들고 있다** | 그 순간 채팅이 끊긴다 |
| `chat-detector/` | 주기적으로 **계산한다** | 아무 때나 올려도 된다 |

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

**`auth`는 환경변수 없이는 일부러 부팅에 실패한다. 열둘이고, 두 갈래로 나뉜다.**

| 갈래 | 변수 | 어디서 얻나 |
|---|---|---|
| **앱 시크릿 아홉** | `JWT_SECRET` · `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` · `CORS_ALLOWED_ORIGINS` · `SECRET_STORE_KEY`(base64 32바이트) · `INTERNAL_API_TOKEN` · `CHZZK_CLIENT_ID` · `CHZZK_CLIENT_SECRET` · `CHZZK_REDIRECT_URI` | **`.env.example`에 없다** — public 저장소라 예시 값도 두지 않는다. 각자 받아서 넣는다 |
| **DB 접속값 셋** | `POSTGRES_DB` · `POSTGRES_USER` · `POSTGRES_PASSWORD` | `.env`에 있다. 위 실행 절차의 `set -a && . ../.env` 줄이 싣는다 |

**막는 방식이 갈래마다 다르다.** 앱 시크릿 아홉은 빈 기본값(`${VAR:}`)을 주고 부팅 검증으로
잡는다 — 기본값을 아예 안 주면 리터럴 `"${VAR}"`이 바인딩돼 **서버는 뜨고 헬스체크도
통과하는데 그 기능만 전부 실패하기** 때문이다. DB 접속값 셋은 반대로 기본값 자체를 없앴다(POK-161).
그쪽은 서버가 실제로 접속을 시도하는 값이라 리터럴이 들어가도 접속 실패로 죽어 신호가 남는다.

**IDE로 띄운다면** 실행 구성의 환경변수에 위 열둘을 넣거나, `.env`를 읽어 주는 플러그인을 쓴다.
`application-local.yml`(gitignore) 프로파일만으로는 부족하다 — 그 파일은 앱 시크릿만 채우고
DB 접속값은 채우지 않는다.

**치지직 셋은 한 덩어리로 검증한다.** 개발자 센터에 등록한 앱 하나의 값이라 하나만 빠져도
연동이 통째로 안 되므로, 셋 중 무엇이 비든 같은 메시지 한 줄
(`치지직 앱 설정(CHZZK_CLIENT_ID·CHZZK_CLIENT_SECRET·CHZZK_REDIRECT_URI)이 비었다`)로 죽는다 —
원인을 세 갈래로 흩지 않는다. 값은 메시지에 넣지 않는다.

| | 뜻 |
|---|---|
| `CHZZK_CLIENT_ID` | 치지직 개발자 센터 앱의 Client ID. 동의 URL에 그대로 실린다 |
| `CHZZK_CLIENT_SECRET` | 그 앱의 Client Secret. 토큰 교환·갱신·철회 요청 본문에만 쓰고 URL·로그 어디에도 안 나간다 |
| `CHZZK_REDIRECT_URI` | 동의가 끝난 뒤 치지직이 code·state를 돌려줄 주소. **개발자 센터에 앱당 하나만 등록된다** — 그래서 환경마다 앱을 따로 파고, 로컬은 `http://localhost:8081/oauth/chzzk/callback`으로 등록된 앱을 쓴다 |

**`chat-collector`는 환경변수 없이도 뜨지만 수집을 시작하지 않는다.** `CHZZK_ENABLED`
기본값이 `false`다 — 켜진 채로 두면 부팅만으로 치지직 세션을 하나 먹는데, 동시 연결
상한이 **Access Token당 3개**라 아무도 모르는 사이에 한도가 소진된다. 실제로 받으려면
둘을 준다.

```bash
CHZZK_ENABLED=true CHZZK_ACCESS_TOKEN=<유저 Access Token> ./gradlew :chat-collector:bootRun
```

`CHZZK_ACCESS_TOKEN`은 **유저 Access Token**이다. 채팅 구독은 Client 인증으로 못 받는다.
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
# {"collectorHealth":{"details":{"status":"collecting"},"status":"UP"}}
```

**재연결 중에는 DOWN이다.** 끊긴 동안 채팅이 실제로 안 들어오므로 UP이면 거짓말이 된다.

> ⚠ **이 헬스체크를 liveness 프로브에 직접 걸면 재시작 루프가 된다.**
> 일시적 DOWN이 이제 **정상 운영 경로**다. readiness에 걸거나 DOWN 지속 시간으로 판단한다.

### 🔴 종료 유예를 20초 준다

**종료가 구독 반납 왕복과 마지막 채팅 저장을 기다린다.** 반납 도중에 인터럽트하면
세션 키는 이미 소모된 뒤라 **아무도 다시 못 보내고, 스트리머당 3개뿐인 자리가
하나 남는다.** 치지직이 느린 날 재시작을 반복하면 그대로 마른다.

최악 대기가 **9초**, 적재의 마지막 flush 대기가 **5초**로 합쳐 14초 + 저장
실행분이고(반납 REST 최악까지 겹치면 그 이상), 그 뒤 마지막 정리가 반납을
한 번 더 보낼 수 있다. **도커 기본 유예는 10초라 모자란다.**
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

**치지직 실서버에 붙어 보는 테스트는 기본으로 안 돈다.** 돌리려면
`CHZZK_LIVE_PROBE=true`를 **명시적으로** 준다.

토큰이 있다는 것과 "지금 실서버를 때려도 된다"는 것은 다르다. 토큰 환경변수의
존재로 열어 두면, **토큰 수명이 24시간이라 만료되는 순간 토큰을 가진 사람의
빌드가 매일 빨간불이 된다.**

**DB 접속 변수 이름을 compose의 `.env`와 맞춰 뒀다** (`POSTGRES_USER`·`POSTGRES_PASSWORD`·
`POSTGRES_DB`). 팀원이 `.env` 값을 바꿔도 앱이 따라간다. compose 네트워크 안에서
띄울 때만 `DB_HOST=postgres`를 준다(이쪽만 기본값이 있다).

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
지금까지 나간 것은 auth의 `V101`~`V107`과 chat의 `V301`(`chat_messages`)이다.

**모든 Flyway 서버(auth 포함)에 `baseline-on-migrate: true` + `baseline-version: 0`이 필수다.**
공유 DB에서는 어느 서버가 먼저 뜰지 정해져 있지 않다 — 다른 서버가 이미 표를
만들어 놓은 DB에 자기 이력 테이블 없이 뜨는 서버는 baseline 없이
`Found non-empty schema(s) "public" but no schema history table`로 부팅이 죽는다.
"두 번째 서버부터"가 아니다: 빈 DB에 chat-collector가 auth보다 먼저 뜨면 auth가
그 두 번째 서버다. chat-collector가 실물에서 밟았고(2026-08-15), auth도 같은
메시지로 재현했다(PR #56). Testcontainers·CI는 매번 빈 DB라 그냥은 안 잡히므로
auth·chat-collector의 `IntegrationTestSupport`가 남의 표를 먼저 심어 두고 부팅한다 —
두 줄을 지우면 그 모듈 테스트 전체가 빨강이다. **clip·chat-detector가 Flyway를
붙일 때도 같은 두 줄이 필요하다.** `baseline-version: 0`인 이유는 기본 1이면 V1
이하가 적용 대상에서 빠지기 때문이다.
1·2번이 읽을 스키마 설명서는 여기서 자동 생성해 [`contracts/db/`](../contracts/db/)로
내보낼 계획인데 **아직 안 만들었다** — 그 폴더는 비어 있다.

## 상태 (2026-08-05)

`auth`와 `chat-collector`에 내용이 있다.

| | |
|---|---|
| 인증 | 구글 로그인·자동가입 · 토큰 발급/회전/로그아웃 · `/api/auth/me` |
| 스트림키 | 발급 · 검증(계약4) · 페어링 코드 발급/교환 · 재발급 (POK-56) |
| 채널 연동 | 치지직 동의 왕복 · 토큰 보관(참조만) · 10분 주기 자동 갱신 · 수집기용 resolve · 해제·상태 조회 (POK-93) |
| 운영 | 이벤트 로깅 · 요청 상관 ID · CORS · 구글 호출 타임아웃 |

표는 일곱이다 — `users`·`refresh_tokens`(V101·V102) ·
`secrets`·`stream_keys`·`pairing_codes`·`pairing_exchange_attempts`(V103~V106) ·
`chzzk_channel_links`(V107).

엔드포인트 열: 스트림키 다섯(**계약4 = `POST /internal/stream-keys/resolve`** — 1번 Media가 SRT 연결을
받기 전에 한 번 부른다) · 치지직 연동 다섯(아래 절). `contracts/api/`에 정본이 아직 없어 여기 적어 둔다.

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

`resolve`는 **키가 틀려도 HTTP 200에 `valid:false`**로 답한다. Media에게
"키가 틀림"(연결 거절)과 "Auth 장애"(판단 불가)는 조치가 정반대라 둘 다 4xx면
Go 쪽에서 구분이 안 된다.

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

### chat-collector — 치지직 채팅 수신 (POK-85) · 자동 재연결 (POK-86) · 적재 (POK-84) · S3 원본 아카이브 (POK-116)

**받아서 세고, 끊기면 다시 붙고, 받은 채팅을 PG에 남기고, 원본을 S3에 쌓는 데까지** 한다.
영상 시각 매핑(POK-92)은 다음 카드다. 스트리머 채널 연동(POK-93)은 auth 쪽에서 됐다(위 절) —
수집기가 `POST /internal/chzzk-link/resolve`로 토큰을 받아 쓰는 배선은 아직 없다.

| | |
|---|---|
| 수신 | 세션 발급 → WebSocket → 구독 → `CHAT`. Engine.IO 3을 직접 다룬다 |
| 하트비트 | **전용 스케줄러에서만** `2`를 보낸다. 주기는 핸드셰이크의 `pingInterval`에서 파생 |
| **절단 감지** | 신호 **셋** — WS 종료 콜백 · ping 송신 실패 · **pong이 임계를 넘도록 안 옴**(좀비) |
| **재연결** | 세션 URL은 재사용이 안 되므로 **세션 발급부터 다시** 탄다. 두 배씩 늘려 상한에서 멈춘다 |
| **아카이브** | 받은 채팅 원본(치지직 안쪽 JSON 그대로 + 받은 시각)을 1분 파일로 묶어 S3에 올린다. 창고가 죽어도 수신은 안 멈춘다 |
| 관측 | 30초마다 `chat.summary` 한 줄 — 건수 · ping/pong 최대 공백 · 순서 위반 · 전달 지연 · 적재 카운터 넷(`persisted`/`conflicts`/`poisoned`/`dropped`) · 아카이브 카운터 여섯(`archived`/`archiveBufferDropped`/`uploaded`/`pending`/`droppedObjects`/`droppedMessages`). **`archiveRunId`는 요약 줄에 없다** — 시작 로그 `chat.archive.enabled runId=…`와 판정 줄에 실린다 |
| 종료 | 수신 게이트 내림 → **마지막 배치 저장 ‖ 열린 창 마지막 업로드** → 세션 줄 → 구독 반납 → 소켓 닫기 → **프로세스 생애 판정 한 줄** |

**"채팅이 안 온다"는 절단 신호로 안 쓴다.** 방송을 꺼도 세션은 살아 있고 채팅만
안 온다(361초 확인). 한산한 방송과 끊긴 연결을 그것으로는 못 가른다.

**재시도해도 안 풀리는 것만 포기한다** — 두 단계(발급·구독)의 401·403과 `revoked`다.
그 밖에는 **영원히 다시 붙는다.** 연결 상한 초과는 특별 취급하지 않는다 —
핸드셰이크 실패와 응답으로 구분되지 않는다.
**포기하면 판정 줄을 남기고 프로세스도 내린다(exit 1)** — 수집이 영영 안 되는
STOPPED로 살아 있을 이유가 없고, 버퍼에 남은 채팅은 그 프로세스가 죽는 순간
사라지기 때문이다. 내리기 전에 잔량이 있으면 DB 회복을 **최대 30초** 기다려 저장한다.
토큰이 잘못된 채 배포하면 재시작 루프가 되는데, 매 재시작의 판정 줄 `reason=`이
그것을 말한다.

**판정 줄은 프로세스 생애에 한 줄이다**(세션마다가 아니다). 재연결이 N번 돌아도
한 줄이고, 거기에 `reconnects=` · `outage=`(누적 절단 시간) · `lastOutageFrom/To`가 실린다.
**세션 하나의 값은 `chat.session.ended`가 따로 낸다.**

**끊긴 채로 끝나도 그 구간이 판정에 실린다** — 영구 정지(401·`revoked`)든 프로세스
종료든 마찬가지다. 그때는 `lastOutageTo=none`이고 `reconnects`는 안 오른다(다시 붙은
적이 없다). 즉 `lastOutageFrom`만 있고 `lastOutageTo=none`이면 **"이때 끊겨서 끝까지
못 돌아왔다"**는 뜻이고, `outage=`는 판정 시각까지의 **하한**이다.

**절단 구간은 `maxReceiveGap`에 안 섞인다.** 섞으면 "한산했을 뿐"과 "끊겨 있었다"가
같은 숫자로 보인다.

**받은 채팅은 `chat_messages` 표에 남는다**(V301). 수신 스레드는 버퍼에 넣기만
하고, 전용 스레드가 1초 배치로 INSERT 한다 — 멱등은 표의 지문 UNIQUE 제약이 진다.
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

`clip`은 `ClipApplication` 하나뿐이고 마이그레이션도 비어 있다.
`chat-detector`는 빈 껍데기다. `common`은 소스가 0개다 —
두 서버가 똑같이 쓰는 계약이 실제로 생기면 그때 채운다.

다음 작업 순서:

1. `clip`에 방송 생명주기 이벤트 FIFO 소비 스텁을 넣는다 (POK-26)
2. **`pairing_exchange_attempts` 청소 작업을 넣는다.** 교환이 `permitAll`이라
   미인증 트래픽이 행을 쌓는다 — 이게 없으면 운영에 올릴 수 없다
3. `POST /api/stream-keys/pairing-codes/exchange`의 `X-Forwarded-For` 처리.
   ALB 뒤로 가면 전 요청이 같은 IP로 보여 rate limit이 전역 한도가 된다
4. SQS 대역(ElasticMQ)을 루트 compose에 추가한다 — `clip`이 렌더 잡을 발행하려면 필요하다

> **ArchUnit 도입 계획은 없어졌다.** auth와 clip 경계를 코드 규칙으로 막으려던 것인데,
> 프로세스가 갈리면서(ADR-022) 물리적으로 분리됐다. 대신 지킬 것은 **DB 표 소유 경계**다.
