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

**서로의 표를 직접 읽지 않는다.** 필요하면 계약4의 `POST /internal/stream-keys/resolve`로
묻는다. 이 선이 무너지면 따로 배포되는데 DB로 묶인 **분산 모놀리스**가 된다 —
장애도 배포도 같이 터지는, 가장 나쁜 조합이다. 리뷰에서 볼 항목이다.

## 두 서버가 같이 쓰는 것은 어디 두나

| 모듈 | 무엇 |
|---|---|
| `common/` | 계약 — 공유 엔티티 · SQS 메시지 DTO. **아직 소스가 0개다** |
| `web-support/` | 웹 인프라 — CORS · 상관 ID 필터. 테스트 도우미(`LogCaptor`)는 `testFixtures`에 |

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
./gradlew :auth:bootRun      # 서버 하나 띄우기
```

| 서버 | 실행 | 헬스체크 |
|---|---|---|
| `clip` | `./gradlew :clip:bootRun` | http://localhost:8081/actuator/health |
| `auth` | `./gradlew :auth:bootRun` | http://localhost:8082/actuator/health |
| `chat-collector` | `./gradlew :chat-collector:bootRun` | http://localhost:8083/actuator/health |
| `chat-detector` | `./gradlew :chat-detector:bootRun` | http://localhost:8084/actuator/health |

**`auth`는 환경변수 없이는 일부러 부팅에 실패한다.** `JWT_SECRET` · `GOOGLE_CLIENT_ID` ·
`GOOGLE_CLIENT_SECRET` · `CORS_ALLOWED_ORIGINS` · `SECRET_STORE_KEY`(base64 32바이트) ·
`INTERNAL_API_TOKEN` 여섯이다. `.env.example`에는 없다 — **public 저장소라 예시 값도 두지 않는다.**
빈 기본값을 주고 검증으로 잡는 이유는, 안 주면 리터럴 `"${VAR}"`이 바인딩돼
**서버는 뜨고 헬스체크도 통과하는데 그 기능만 전부 실패하기** 때문이다.

**`chat-collector`는 환경변수 없이도 뜨지만 수집을 시작하지 않는다.** `CHZZK_ENABLED`
기본값이 `false`다 — 켜진 채로 두면 부팅만으로 치지직 세션을 하나 먹는데, 동시 연결
상한이 **Access Token당 3개**라 아무도 모르는 사이에 한도가 소진된다. 실제로 받으려면
둘을 준다.

```bash
CHZZK_ENABLED=true CHZZK_ACCESS_TOKEN=<유저 Access Token> ./gradlew :chat-collector:bootRun
```

`CHZZK_ACCESS_TOKEN`은 **유저 Access Token**이다. 채팅 구독은 Client 인증으로 못 받는다.
만료된 토큰(수명 24시간)은 빈 값이 아니라 검증에 안 걸리므로, 그때는 조용히 재시도하지
않고 `chat.session.stopped stage=AUTH reason=SESSION_AUTH_FAILED`를 남기고 멈춘다.

받고 있는지는 헬스체크에 나온다.

```bash
curl -s localhost:8083/actuator/health
# {"collectorHealth":{"details":{"status":"collecting"},"status":"UP"}}
```

**치지직 실서버에 붙어 보는 테스트는 기본으로 안 돈다.** 돌리려면
`CHZZK_LIVE_PROBE=true`를 **명시적으로** 준다.

토큰이 있다는 것과 "지금 실서버를 때려도 된다"는 것은 다르다. 토큰 환경변수의
존재로 열어 두면, **토큰 수명이 24시간이라 만료되는 순간 토큰을 가진 사람의
빌드가 매일 빨간불이 된다.**

**DB 접속 변수 이름을 compose의 `.env`와 맞춰 뒀다** (`POSTGRES_USER`·`POSTGRES_PASSWORD`·
`POSTGRES_DB`). 팀원이 `.env` 값을 바꿔도 앱이 따라간다. compose 네트워크 안에서
띄울 때만 `DB_HOST=postgres`를 준다.

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
`..._clip`). 기본 이름을 쓰면 나중에 뜬 쪽이 남의 이력을 자기 것으로 읽고 부팅에 실패한다.
마이그레이션 번호는 모듈별 대역을 쓴다 — `V1xx` auth · `V2xx` clip · `V3xx` chat.
지금까지 나간 것은 auth의 `V101`~`V106`뿐이다.
1·2번이 읽을 스키마 설명서는 여기서 자동 생성해 [`contracts/db/`](../contracts/db/)로
내보낼 계획인데 **아직 안 만들었다** — 그 폴더는 비어 있다.

## 상태 (2026-08-05)

`auth`와 `chat-collector`에 내용이 있다.

| | |
|---|---|
| 인증 | 구글 로그인·자동가입 · 토큰 발급/회전/로그아웃 · `/api/auth/me` |
| 스트림키 | 발급 · 검증(계약4) · 페어링 코드 발급/교환 · 재발급 (POK-56) |
| 운영 | 이벤트 로깅 · 요청 상관 ID · CORS · 구글 호출 타임아웃 |

표는 여섯이다 — `users`·`refresh_tokens`(V101·V102) ·
`secrets`·`stream_keys`·`pairing_codes`·`pairing_exchange_attempts`(V103~V106).

스트림키 엔드포인트 다섯. **`resolve`가 계약4다** — 1번 Media가 SRT 연결을 받기 전에
한 번 부른다. `contracts/api/`에 정본이 아직 없어 여기 적어 둔다.

| | 부르는 쪽 | 인증 |
|---|---|---|
| `GET /api/stream-keys` | 웹 | 사용자 JWT |
| `POST /api/stream-keys/rotate` | 웹 | 사용자 JWT |
| `POST /api/stream-keys/pairing-codes` | 웹 | 사용자 JWT |
| `POST /api/stream-keys/pairing-codes/exchange` | OBS 플러그인 | **없음** (코드 자체가 자격증명) |
| `POST /internal/stream-keys/resolve` | **Media(1번)** | `X-Internal-Token` 헤더 |

`resolve`는 **키가 틀려도 HTTP 200에 `valid:false`**로 답한다. Media에게
"키가 틀림"(연결 거절)과 "Auth 장애"(판단 불가)는 조치가 정반대라 둘 다 4xx면
Go 쪽에서 구분이 안 된다.

### chat-collector — 치지직 채팅 수신 (POK-85)

**받아서 세는 데까지** 한다. 적재(POK-84) · 영상 시각 매핑(POK-92) · 스트리머
채널 연동(POK-93)은 다음 카드다.

| | |
|---|---|
| 수신 | 세션 발급 → WebSocket → 구독 → `CHAT`. Engine.IO 3을 직접 다룬다 |
| 하트비트 | **전용 스케줄러에서만** `2`를 보낸다. 주기는 핸드셰이크의 `pingInterval`에서 파생 |
| 관측 | 30초마다 `chat.summary` 한 줄 — 건수 · ping/pong 최대 공백 · 순서 위반 · 전달 지연 |
| 종료 | 최종 판정 한 줄 → 구독 반납 → 소켓 닫기. 반납까지 약 1초 |

**DB를 안 쓴다.** 받은 채팅은 세기만 하고 아무 데도 안 남는다.

**개별 메시지를 로그로 남기지 않는다.** 본문·`senderChannelId`·닉네임·토큰이 어떤
레벨에서도 안 나가고 검사가 그것을 강제한다. 디버깅하려고 한 줄 찍으면 그 검사가
빨간불이 된다 — **레벨을 낮춰도 통과하지 않고, 그게 의도다.**

실측(실제 라이브 11분 30초): 186건 · ping 최대 간격 **20.0초**(임계 40초) ·
전달 지연 중앙 **약 175ms**(`messageTime` → 우리 수신).

> **175ms를 POK-92의 시차 보정 오프셋으로 쓰지 마라.** 그 오프셋은
> 치지직 방송 지연 + 시청자 반응 지연 + 이 전달 지연으로 갈리는데,
> **앞의 둘은 초 단위이고 아직 안 쟀다.** 이 값만으로 잡으면 자릿수가 어긋난다.

### 나머지

`clip`은 `ClipApplication` 하나뿐이고 마이그레이션도 비어 있다.
`chat-detector`는 빈 껍데기다. `common`은 소스가 0개다 —
두 서버가 똑같이 쓰는 계약이 실제로 생기면 그때 채운다.

다음 작업 순서:

1. **`chat-collector`에 재연결을 넣는다 (POK-86).** 지금은 끊기면 그걸로 끝이고,
   그 구간 채팅은 되돌릴 수 없다. 세션 URL은 재사용이 안 되므로 세션 발급부터 다시다
2. `clip`에 방송 생명주기 이벤트 FIFO 소비 스텁을 넣는다 (POK-26)
2. **`pairing_exchange_attempts` 청소 작업을 넣는다.** 교환이 `permitAll`이라
   미인증 트래픽이 행을 쌓는다 — 이게 없으면 운영에 올릴 수 없다
3. `POST /api/stream-keys/pairing-codes/exchange`의 `X-Forwarded-For` 처리.
   ALB 뒤로 가면 전 요청이 같은 IP로 보여 rate limit이 전역 한도가 된다
4. SQS 대역(ElasticMQ)을 루트 compose에 추가한다 — `clip`이 렌더 잡을 발행하려면 필요하다

> **ArchUnit 도입 계획은 없어졌다.** auth와 clip 경계를 코드 규칙으로 막으려던 것인데,
> 프로세스가 갈리면서(ADR-022) 물리적으로 분리됐다. 대신 지킬 것은 **DB 표 소유 경계**다.
