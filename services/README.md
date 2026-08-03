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
| `auth` | `users` · `refresh_tokens` · `stream_keys` |
| `clip` | `broadcasts` · `jump_cards` · `stream_segments` |

**서로의 표를 직접 읽지 않는다.** 필요하면 계약4의 `POST /internal/stream-keys/verify`로
묻는다. 이 선이 무너지면 따로 배포되는데 DB로 묶인 **분산 모놀리스**가 된다 —
장애도 배포도 같이 터지는, 가장 나쁜 조합이다. 리뷰에서 볼 항목이다.

## 두 서버가 같이 쓰는 것은 어디 두나

| 모듈 | 무엇 |
|---|---|
| `common/` | 계약 — 공유 엔티티 · SQS 메시지 DTO |
| `web-support/` | 웹 인프라 — CORS · 상관 ID 필터 |

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
1·2번이 읽을 스키마 설명서는 여기서 자동 생성해 [`contracts/db/`](../contracts/db/)로 내보낸다.

## 상태

`auth`만 내용이 있다 — 구글 로그인·토큰 발급/회전·`/api/auth/me`, 그리고 운영 로깅·
상관 ID·CORS·구글 호출 타임아웃. 표는 `users`·`refresh_tokens` 둘이다.

`clip`은 `ClipApplication` 하나뿐이고 마이그레이션도 비어 있다.
`chat-collector`·`chat-detector`는 빈 껍데기다.

다음 작업 순서:

1. `clip`에 방송 생명주기 이벤트 FIFO 소비 스텁을 넣는다 (POK-26)
2. `broadcasts`·`stream_keys` 마이그레이션을 각 소유 서버에 넣는다
3. SQS 대역(ElasticMQ)을 루트 compose에 추가한다 — `clip`이 렌더 잡을 발행하려면 필요하다

> **ArchUnit 도입 계획은 없어졌다.** auth와 clip 경계를 코드 규칙으로 막으려던 것인데,
> 프로세스가 갈리면서(ADR-022) 물리적으로 분리됐다. 대신 지킬 것은 **DB 표 소유 경계**다.
