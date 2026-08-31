# services — 코어 API (Spring)

**담당: 3번 (`@kth4778`)**

## 무엇이 들어가나

**서버 4개**가 여기서 실행된다. `web-support/`는 서버가 아니다.

| 모듈 | 포트 | 역할 |
|---|---|---|
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
| `chat-detector` | `chat_metrics` (V401, POK-120) — **판별 서버 단독 소유다.** 위 `chat_messages`와 달리 공동 소유가 아니라 이 서버만 읽고 쓴다 |

**서로의 표를 직접 읽지 않는다.** 필요하면 계약4의 `POST /internal/stream-keys/resolve`로
묻는다. 이 선이 무너지면 따로 배포되는데 DB로 묶인 **분산 모놀리스**가 된다 —
장애도 배포도 같이 터지는, 가장 나쁜 조합이다. 리뷰에서 볼 항목이다.

## 두 서버가 같이 쓰는 것은 어디 두나

**자리는 `web-support/` 하나다** — 웹 인프라(CORS 허용 메서드 GET·POST·PUT·PATCH·DELETE · 상관 ID 필터).
테스트 도우미(`LogCaptor`)는 같은 모듈의 `testFixtures`에 있다.

`web-support`는 이 앱들의 패키지 밖이라 컴포넌트 스캔에 안 걸린다 —
각 `Application`이 `@Import`로 끌어온다.

**쓰는 곳이 모듈 이름만큼 넓지 않다.** 웹 설정은 `auth`·`clip` 둘뿐이고,
`LogCaptor`는 넷이 쓴다(`auth` 12 · `clip` 10 · `chat-collector` 31 · `chat-detector` 5).
**`chat-detector`는 `testFixtures`만 가져온다** — 컴파일 의존으로 걸면 `CorsProperties`가
딸려 와 `CORS_ALLOWED_ORIGINS` 없이는 부팅이 실패하는데, 그 서버는 브라우저 문이 없다.

## Gradle 루트는 저장소 루트가 아니라 여기다

`settings.gradle`과 `gradlew`가 **이 폴더**에 있다. IntelliJ로 열 때도 이 폴더를 연다.

저장소 루트에 두면 IDE가 `web/node_modules`까지 자바 프로젝트로 인덱싱하고,
`./gradlew build`가 Go·파이썬 폴더까지 훑는다.

## 계약을 담는 모듈은 두지 않는다

**`common/`을 지웠다**(2026-08-25). 자리만 잡아 두고 소스가 0개인 채로 오래 갔는데,
네 서버가 전부 의존을 걸고 있어 **아무것도 주지 않으면서 모두를 묶고 있었다.**

**후보가 없어서 비었던 게 아니다.** 방송 시작·종료 알림을 `clip`과 `chat-collector`가
각자 읽는데, 그 코드가 양쪽에 같은 이름으로 따로 있다(`broadcast/intake/` — `SqsIntakeRunner`
· `IntakeProperties` · `IntakeStatus` · `IntakeConfiguration`). 합칠 만한 자리는 이미 있다.

**그래도 지금 합치지 않는다.** 두 서버의 소비 방식이 아직 갈리는 중이고(clip은 백오프·health,
수집기는 세션 수립), 합쳐 둔 뒤 한쪽만 바뀌면 공용 모듈이 두 서버의 사정을 다 떠안는다.
**같은 코드를 두 번 고치는 부담이 실제로 아파질 때 모듈을 다시 만든다** — 만드는 데 5분이다.

그때 지킬 경계는 전과 같다. **계약만 둔다** — 공유 엔티티·SQS 메시지 형식.
로직·웹 계층은 두지 않는다. 여기가 두꺼워지면 서버들이 한 덩어리가 되고 나눈 의미가 사라진다.

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

**`auth`가 읽는 환경변수는 스물이고, 세 갈래로 나뉜다. 갈래마다 「없으면 어떻게 되나」가 다르다.**

| 갈래 | 변수 | 없으면 | 어디서 얻나 |
|---|---|---|---|
| **앱 시크릿 열둘** | `JWT_SECRET` · `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` · `CORS_ALLOWED_ORIGINS` · `SECRET_STORE_KEY`(base64 32바이트) · `INTERNAL_API_TOKEN` · `CHZZK_CLIENT_ID` · `CHZZK_CLIENT_SECRET` · `CHZZK_REDIRECT_URI` · `YOUTUBE_CLIENT_ID` · `YOUTUBE_CLIENT_SECRET` · `YOUTUBE_REDIRECT_URI` | **부팅 실패** | **`.env.example`에 없다** — public 저장소라 예시 값도 두지 않는다. 각자 받아서 넣는다 |
| **DB 접속값 셋** | `POSTGRES_DB` · `POSTGRES_USER` · `POSTGRES_PASSWORD` | **부팅 실패**(DB가 접속을 거절한다) | `.env`에 있다. 위 실행 절차의 `set -a && . ../.env` 줄이 싣는다 |
| **사진 창고 다섯**(POK-207) | `PROFILE_PHOTO_S3_BUCKET` · `PROFILE_PHOTO_TOKEN_SECRET` · `PROFILE_PHOTO_BASE_URL` · `PROFILE_PHOTO_S3_ENDPOINT` · `PROFILE_PHOTO_S3_FORCE_PATH_STYLE` | **그냥 뜬다. 사진 기능만 꺼진다** | 창고는 1번이 판다. 로컬은 가짜 저장소(LocalStack)를 띄워 쓴다 |

**🔴 셋째 갈래는 「조건부 필수」다.** 나머지 둘과 성격이 다르니 규칙을 정확히 적어 둔다.

| 규칙 | 안 지키면 |
|---|---|
| `PROFILE_PHOTO_S3_BUCKET`이 **비면 사진 기능만 꺼지고 나머지 넷은 아예 안 본다** | 로그인·이름 수정은 그대로 돈다. 사진 올리기만 **503**, 사진 꺼내기는 **404** |
| 차 있으면 `PROFILE_PHOTO_TOKEN_SECRET`·`PROFILE_PHOTO_BASE_URL`이 **필수가 된다** | **부팅이 죽는다.** 안 막으면 「사진을 올릴 수는 있는데 볼 수가 없는」 상태로 뜬다 |
| 🔴 `PROFILE_PHOTO_TOKEN_SECRET`은 **32바이트 이상**이어야 한다 | **부팅이 죽는다.** 사진 주소는 **서명과 그 재료를 함께 실어** 브라우저에 내보내므로, 주소 하나면 후보 키를 오프라인에서 무한히 시험할 수 있다. 맞히면 **아무 회원 번호로나 표를 만들어** 남의 사진을 연다 — 로그인 토큰과 키를 가른 것이 그 순간 무의미해진다. `JWT_SECRET`과 같은 기준이다 |
| 🔴 `PROFILE_PHOTO_BASE_URL`은 **`http(s)://호스트[:포트]` 절대 주소**여야 하고 **끝에 슬래시를 두지 않는다** | **부팅이 죽는다.** 스킴이 없으면 브라우저가 **화면의 주소를 기준으로 풀어** 엉뚱한 곳을 찾는다 — 올리기는 성공하고 저장도 되는데 **그림만 조용히 안 보이고** 서버 로그에도 흔적이 없다. 끝 슬래시는 주소를 `…8082//api/…`로 만들어 환경마다 갈린다 |
| `PROFILE_PHOTO_TOKEN_SECRET`은 **`JWT_SECRET`과 달라야 한다** | **부팅이 죽는다.** 같으면 사진 표가 로그인 토큰이 될 길이 열린다(아래 사진 절) |
| `PROFILE_PHOTO_BASE_URL`은 **브라우저가 붙는 주소**다 (예 `http://localhost:8082`) | 🔴 clip의 `AUTH_BASE_URL`(**서버끼리** 붙는 주소, compose 안에서는 `http://auth:8082`)과 **뜻이 반대다.** 여기에 컨테이너 이름을 적으면 브라우저가 못 붙어 **사진만 조용히 안 보인다** |
| `PROFILE_PHOTO_S3_ENDPOINT`·`_FORCE_PATH_STYLE`은 **가짜 저장소용**이다 | 비우면 진짜 AWS. LocalStack·MinIO는 가상 호스트 이름을 못 풀어 `_FORCE_PATH_STYLE=true`가 필요하다 |

**꺼진 것은 부팅 로그로 확인한다** — `auth.profile.photo.disabled reason=no_bucket`,
켜졌으면 `auth.profile.photo.enabled region=… endpointOverride=… forcePathStyle=…`.
창고 이름과 서명키는 안 찍는다.

🔴 **`PROFILE_PHOTO_S3_FORCE_PATH_STYLE`은 `DeploymentEnvVarsTest`가 못 잡는다.**
기본값이 `${…:false}`라 「빈 기본값」 정규식에 안 걸린다. **손으로 챙긴다.**
정규식을 넓히지 않는 이유는 `AWS_REGION`·`DB_HOST`처럼 일부러 기본값을 둔 것들이
필수로 잡혀 예외 목록이 따라오기 때문이다.

**막는 방식이 갈래마다 다르다.** 앱 시크릿 열둘은 빈 기본값(`${VAR:}`)을 주고 부팅 검증으로
잡는다 — 기본값을 아예 안 주면 리터럴 `"${VAR}"`이 바인딩돼 **서버는 뜨고 헬스체크도
통과하는데 그 기능만 전부 실패하기** 때문이다. DB 접속값 셋은 반대로 기본값 자체를 없앴다(POK-161).
그쪽은 서버가 실제로 접속을 시도하는 값이라 리터럴이 들어가도 접속 실패로 죽어 신호가 남는다.

**🔴 이 표에 값을 더하면 `docker-compose.dev.yml`의 `auth` 블록과 `.env.dev.example`에도 같이 넣어라.**
컨테이너는 호스트 셸의 변수를 자동으로 물려받지 않는다 — 표에만 적으면 **dev 배포만 부팅 실패 루프**에 빠지고
로컬·CI는 전부 초록이다(같은 함정을 POK-127이 chat-collector에서 실물로 겪었다. 아래 「나머지」 절 참고).
**POK-121이 이 규칙을 그대로 어겼고**(유튜브 셋을 표에만 적었다) 봇 리뷰가 잡았다 —
그래서 지금은 `DeploymentEnvVarsTest`가 auth의 필수 변수와 두 파일을 대조한다.

**IDE로 띄운다면** 실행 구성의 환경변수에 위 열다섯(부팅에 필요한 것)을 넣거나, `.env`를 읽어 주는 플러그인을 쓴다.
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
「환경변수 스물」 표를 본다.

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
`..._clip` · `..._chat` · `..._chat_detector`). 기본 이름을 쓰면 나중에 뜬 쪽이 남의 이력을 자기 것으로 읽고 부팅에 실패한다.
마이그레이션 번호는 모듈별 대역을 쓴다 — `V1xx` auth · `V2xx` clip · `V3xx` chat-collector · `V4xx` chat-detector.
지금까지 나간 것은 auth의 `V101`~`V107` · clip의 `V201`(`broadcasts`·`broadcast_events`)과
`V202`(`jump_cards`, POK-118)·`V203`(`broadcasts.vod_expires_at`, POK-117) · chat-collector의 `V301`(`chat_messages`) ·
chat-detector의 `V401`(`chat_metrics`, POK-120)이다.

**🔴 `V305`는 수집 서버 대역인데 그것을 전제로 도는 것은 판별 서버다.** 판별 서버는
「최근에 채팅이 온 방송」을 **매초** 뽑고 그 조회가 `idx_chat_messages_received`를 탄다 —
**수집 서버 배포가 밀려 그 인덱스가 없으면 판별 서버는 아무 신호 없이 전체 훑기를 한다**
(60만 행 실측: 인덱스 있을 때 1.1ms, 없을 때 19.5ms로 **약 18배**). 표가 있는 곳에
인덱스도 둔다는 소유 경계는 그대로지만, 대가가 이것이다 — **둘은 같이 배포한다.**

**모든 Flyway 서버(auth 포함)에 `baseline-on-migrate: true` + `baseline-version: 0`이 필수다.**
공유 DB에서는 어느 서버가 먼저 뜰지 정해져 있지 않다 — 다른 서버가 이미 표를
만들어 놓은 DB에 자기 이력 테이블 없이 뜨는 서버는 baseline 없이
`Found non-empty schema(s) "public" but no schema history table`로 부팅이 죽는다.
"두 번째 서버부터"가 아니다: 빈 DB에 chat-collector가 auth보다 먼저 뜨면 auth가
그 두 번째 서버다. chat-collector가 실물에서 밟았고(2026-08-15), auth도 같은
메시지로 재현했다(PR #56). Testcontainers·CI는 매번 빈 DB라 그냥은 안 잡히므로
네 서버의 `IntegrationTestSupport`가 남의 표를 먼저 심어 두고 부팅한다 —
두 줄을 지우면 그 모듈 테스트 전체가 빨강이다. **chat-detector도 POK-120에서 같은 두 줄을 갖췄다 —
이제 Flyway를 도는 서버 넷이 전부 갖췄다.** `baseline-version: 0`인 이유는 기본 1이면 V1
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

## 상태 (2026-08-31)

`auth`·`clip`·`chat-collector`에 내용이 있다.

| | |
|---|---|
| 인증 | 구글 로그인·자동가입 · 토큰 발급/회전/로그아웃 · `/api/auth/me` |
| 스트림키 | 발급 · 검증(계약4) · 페어링 코드 발급/교환 · 재발급 (POK-56) |
| 채널 연동 | **치지직** — 동의 왕복 · 토큰 보관(참조만) · 10분 주기 자동 갱신 · 수집기용 resolve · 해제·상태 조회 (POK-93) · **유튜브** — 같은 왕복 모양 · **채널은 동의 때 확정(재선택 없음)** · 1시간 주기 철회 점검 · 워커용 resolve · 해제 (POK-121) |
| 편집자 위임 | 이메일 초대 · 초대함 수락/거절 · 보낸 초대 취소 · 위임 조회 · 양방향 해제 (POK-57) |
| 회원정보 수정 | 표시 이름(30자, 코드포인트로 센다) · 프로필 사진 업로드/내보내기 · 죽어 있던 `updated_at` 갱신 (POK-207) |
| **회원 탈퇴** | **개인정보 익명화 · 발급물 여섯 회수(갱신 토큰·스트림키·페어링 코드·연동 둘·편집자 관계) · 사진 파일 삭제 · 남은 접근 표 전면 차단 (POK-171)** |
| 운영 | 이벤트 로깅 · 요청 상관 ID · CORS(`GET`·`POST`·`PUT`·`PATCH`·`DELETE`) · 구글 호출 타임아웃 |

표는 열이다 — `users`·`refresh_tokens`(V101·V102) ·
`secrets`·`stream_keys`·`pairing_codes`·`pairing_exchange_attempts`(V103~V106) ·
`chzzk_channel_links`(V107) · `editor_invitations`·`editor_delegations`(V108) ·
`youtube_channel_links`(V109).
**`V110`·`V111`은 표를 만들지 않는다** — `V110`이 `users`에 사진 칸 둘(`profile_photo_key`·
`profile_photo_updated_at`)을, `V111`이 `deleted_at` 한 칸과 **그 사진 칸 둘이 반쪽만 차는 것을 막는
CHECK 제약**을 더한다(POK-171).

🔴 **`PATCH`·`PUT`은 POK-207이 CORS 허용 목록에 넣은 것이다.** 없으면 화면의 「저장」이
preflight에서 막혀 **창구는 멀쩡한데 브라우저만 못 부른다.** 자리는 `web-support/CorsConfig` 하나다.

아래 표의 창구는 **서른**이다 — 스트림키 다섯(**계약4 = `POST /internal/stream-keys/resolve`** —
1번 Media가 SRT 연결을 받기 전에 한 번 부른다) · 치지직 연동 다섯 · **유튜브 연동 다섯**(POK-121) ·
편집자 위임 아홉 · **clip용 내부 창구 둘**(POK-175, 아래 절) · **회원정보 수정 셋**(POK-207) ·
**회원 탈퇴 하나**(POK-171).
로그인·토큰 창구 넷(`/api/auth/google`·`/refresh`·`/logout`·`/me`)은 이 표에 없다 — 위 「인증」 줄이 그것이다.
**탈퇴는 주소가 `/api/auth/me`로 같지만 메서드가 달라 표에 넣었다** — 그 넷과 달리 이 카드가 새로 연 문이다.
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
| `PATCH /api/auth/me` | 웹 | 사용자 JWT |
| `PUT /api/auth/me/photo` | 웹 | 사용자 JWT (**multipart/form-data**) |
| `GET /api/profile-photos/{userId}?token=…` | **웹의 그림 태그** | **사진 표**(세 번째 `permitAll`) |
| **`DELETE /api/auth/me`** — 탈퇴 | 웹 | 사용자 JWT |

**표시 이름 규칙 셋** — 웹이 같은 판정을 화면에서 먼저 해야 왕복이 준다.

| | |
|---|---|
| 길이 | **30자. 코드 포인트로 센다** — JS에서 `[...name].length`다. `String.length`로 세면 이모지가 두 글자로 잡혀 화면과 서버가 갈린다 |
| 앞뒤 공백 | **잘라서 저장한다.** 가운데는 안 건드린다 — 접으면 "김 태현"이 "김태현"이 된다 |
| 🔴 보이지 않는 문자 | **전각 공백(U+3000)·NBSP(U+00A0)·ZWSP(U+200B) 등도 공백으로 본다.** 그것만으로 된 이름은 `NAME_BLANK`로 거절한다 — 안 막으면 **이름이 없는 것처럼 보이는 계정**이 생겨 편집자 목록이 누가 누군지 말해 주지 못한다. 화면 쪽 주의: JS `trim()`은 전각 공백·NBSP는 자르지만 **ZWSP(U+200B)는 못 자른다**(공백이 아니라 형식 문자라서다). 화면에서 먼저 거르려면 그 하나를 따로 봐야 한다 |

**실패는 사유를 갈라 알린다** — 오류 본문은 `{"reason": "<코드>"}` 한 필드다.
**로그인 실패(전부 401 한 가지)와 정책이 반대다**: 여기는 사용자가 직접 고칠 수 있는 실패라
감출 이익이 없다(스트림키 창구와 같은 계열).

| 사유 | 상태 | 언제 | 화면이 할 말 |
|---|---|---|---|
| `NAME_BLANK` | 400 | 이름이 비었거나 **보이지 않는 문자뿐** | 이름을 입력하세요 |
| `NAME_TOO_LONG` | 400 | 코드 포인트 30 초과 | 30자 이내로 |
| `PHOTO_TOO_LARGE` | **413** | 파일이 2MB 초과 | **줄여서 다시** |
| `PHOTO_NOT_AN_IMAGE` | **415** | 내용 앞머리가 PNG/JPEG/WEBP가 아님 | 그림 파일을 고르세요 |
| `PHOTO_STORAGE_DISABLED` | **503** | 창고 설정이 비어 사진 기능이 꺼짐 | **잠시 뒤에 다시** |

🔴 **셋을 400으로 뭉치지 않은 이유가 이 표의 마지막 칸이다** — 「줄여서 다시」와
「잠시 뒤에 다시」는 사용자가 할 행동이 다르다. **상태 코드만 보고 갈라도 되게** 만들어 뒀다.

**`PHOTO_TOO_LARGE`는 우리 코드가 바이트를 만지기 전에 난다** — 크기는 서블릿 층이 자른다.
그래서 그 갈래만 다른 예외에서 같은 사유로 옮겨 온다.

**마지막 줄만 인증이 다르다.** 그림 태그(`<img src=…>`)는 `Authorization` 헤더를 못 싣고
웹은 쿠키를 안 쓴다 — 자격을 **주소에 실린 사진 표**가 대신한다. 그 표로 열 수 있는 것은
그림 한 장뿐이고 10~20분이면 죽는다(아래 사진 절). `/api/auth/refresh`·페어링 코드 교환에 이은
**세 번째 `permitAll`**이라 같은 함정을 공유한다 — **이 경로의 실패 로그에 건수로 알람을 걸면 안 된다.**

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
| `GET /api/clip/broadcasts/{streamId}/events` (계약 2B, SSE) | Bearer JWT | **200** `text/event-stream` · 404 `{"error":"broadcast_not_found"}` · 401 · **503** `{"error":"stream_limit","scope":"user\|stream\|total"}` 또는 `{"error":"authorization_unavailable"}` |
| `POST /api/clip/jump-cards/{id}/claim` | Bearer JWT | 200 카드 · **409 본문이 현재 카드**(누가 잡고 있는지) · **400** `{"error":"invalid_request","field":"id"}`(번호가 숫자가 아니다) · 404 `{"error":"jump_card_not_found"}` · 503 `{"error":"authorization_unavailable"}` |
| `DELETE /api/clip/jump-cards/{id}/claim` | Bearer JWT | **204** · 403 `{"error":"not_claim_owner"}` · **400**(위와 같음) · 404 · 503 |
| `POST /api/clip/jump-cards/{id}/hide` | Bearer JWT | 200 카드(`hidden:true`) · **400**(위와 같음) · 404 · 503 |
| `DELETE /api/clip/jump-cards/{id}/hide` | Bearer JWT | 200 카드(`hidden:false`) · **400**(위와 같음) · 404 · 503 |

**🔴 POK-174부터 문 다섯이 자격 판정을 지난다**(통로 + 카드 문 넷). 그래서 404의 뜻이 넓어지고
503 갈래가 하나 늘었다 — **`jump_card_not_found`는 이제 「없는 카드」가 아니라
「없거나 볼 수 없는 카드」다.** 갈라 두면 카드 번호를 하나씩 넣어 보는 것만으로 그 카드의 실재를
알 수 있다(번호가 `bigserial`이라 연속이다). 통로의 404도 같은 이유로 「없는 방송」과
「자격 없음」이 한 본문이다. 판정 표와 그 근거는 아래 **「clip — 화면 목록과 자격 판정 (POK-174)」**.

**표의 400도 POK-174가 붙인 것이다** — 그 전에는 그 예외를 다루는 조언이 세그먼트 문으로 좁혀져
있어 스프링 기본 `/error` 봉투로 나갔다. 전문은 아래 POK-174 절 **「거절 봉투는 한 벌이다」**.

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
| `queue-capacity` | 1000 | 스트라이프당 대기 상한. 넘치면 버리고 WARN — 메우는 것은 **카드 목록 문**이다(POK-174 뒤로 재연결이 안 메운다). 초기 전송은 주석 한 줄 + 끝난 방송이면 `ended`가 전부라 **태스크 하나 = 큐 한 칸**이다 |
| `max-per-user` | 4 | 탭 몇 개 + 모바일 |
| `max-per-stream` | 50 | 스트리머 + 편집자. **편집자 정원은 아직 정해진 카드가 없다** — POK-207이 「편집자 관리 API」에서 회원정보 수정으로 범위를 바꾸면서 그 항목이 카드 밖으로 나갔다(POK-208도 아니다: 화면이 직접 셀 수 있어 미뤘다). 필요해지면 새 카드다 |
| `max-total` | 500 | 서버 한 대 전체 |

**측정값이 아니라 추정이다.** 동시 사용자 100명 전제에서 넉넉히 잡았고 실사용 후 조정한다.

**연결 수명 = min(4시간, 토큰 exp까지 남은 시간)**이다. 만료 시점에 닫히고 브라우저가 새 토큰으로
다시 붙는다. **`exp`가 이미 지났으면 아예 열지 않는다(401)** — 디코더의 clock skew 허용치(60초)
안쪽 토큰은 인증을 통과하는데, 그대로 열면 남은 수명이 음수가 되고 **서블릿 규약상 `timeout <= 0`은
「시한 없음」이라 만료된 토큰일수록 연결이 더 오래 산다**(실측: `-59311ms` → 45초 뒤에도 살아 있음).

**상한 검사는 스냅샷 조회 <u>앞</u>에서 한 번 더 한다.** 거절될 요청이 자물쇠 안에서 DB를 읽지
않게 하는 사전 검사다. 자리를 잡는 것과 원자적인 최종 판정은 그대로
`open()`에 있고, **둘 다 `checkLimits` 하나를 부른다** — 조건을 두 곳에 적으면 언젠가 갈리고,
갈리는 순간 사전 검사만 통과해 헛읽기가 그대로 돌아온다.
🔴 **아래 표는 그 안에서 카드를 전부 읽던 때의 값이다** — POK-174가 초기 전송을 없애 지금 읽는
것은 **방송 한 줄**이라 규모가 줄었다(다시 재지는 않았다). **비율 1.00은 그대로다**: 이 검사를
뒤로 옮기면 거절되는 요청마다 질의가 하나씩 다시 늘어난다.

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

**🔴 통로는 지난 카드를 안 보낸다(POK-174에서 없앴다).** 연결 직후에 나가는 것은 주석 한 줄과,
끝난 방송이면 `ended` **둘뿐**이다 — 그 뒤로는 **연 뒤에 생기거나 바뀐 카드만** 나간다.
전에는 그 방송의 카드를 전부(숨긴 것 포함) 순번 순으로 보냈다. **따라잡기는 이제 카드 목록 문이
맡는다**(`GET …/jump-cards`). 그래서 **화면이 읽는 순서가 계약이 됐다** — 아래
「clip — 화면 목록과 자격 판정 (POK-174)」의 **「통로 먼저, 목록 나중」**.
`Last-Event-ID`는 **받아서 로그에만 적고 쓰지 않는다** — 마진 방식으로 바꾸는 날 쓸 자리다.

**연결 직후 주석 한 줄(`: ok`)을 먼저 보낸다.** `SseEmitter`는 첫 쓰기가 있어야 응답을 커밋하는데,
여기서 아무것도 안 쓰면 헤더가 **다음 하트비트까지** 늦는다(실측 5.449초, 최악 20초).
받는 쪽에는 「느리다」가 아니라 **「연결이 안 된다」**로 보인다. **초기 전송을 없앤 뒤로는
진행 중인 방송에서 이 주석이 유일한 첫 쓰기다** — 전에는 카드가 있으면 그것이 대신했고,
그때는 「카드 0장인 방송」에서만 늦었다.

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

- **연결을 여는 쪽은 트랜잭션을 먼저 연다(`StreamOpener`의 `@Transactional(readOnly = true)`). 지우면 안 된다.**
  스냅샷 조회가 `openWithSnapshot`의 자물쇠 **안**에서 도는데(그래야 「읽은 뒤 ~ 명부에 오르기 전」
  창이 안 열린다), 트랜잭션이 없으면 그 조회가 **자물쇠 안에서** 커넥션을 새로 얻는다. 풀이 비면
  **자물쇠를 쥔 채** `connection-timeout`(운영 기본 **30초**)만큼 기다리고 그동안 카드 발행·연결
  열기·종료 알림이 전부 막힌다(실측 풀 2·시한 3초에서 발행 **3142ms**·열기 **3116ms**).
  🔴 **거기서 되먹임이 생긴다** — `afterCommit`은 커넥션 반납 **전**이라(`activeConnections=1` 실측)
  막힌 발행이 커넥션을 **쥔 채** 기다린다. 외부 점유자 없이 **발행 둘만으로** 풀이 마른 채 시한까지
  유지됐다. 트랜잭션을 먼저 열면 커넥션 획득이 자물쇠 **앞**에서 끝난다(최악 막힘 743~2022ms →
  **0~1ms**). **대가는 방향이 뒤집힌 것**이다 — 이제는 커넥션을 쥔 채 자물쇠를 기다리는데, 커넥션
  보유가 **카드 0장 11ms · 300장 26ms**로 짧다(연결이 살아 있는 동안은 `active=0`이다.
  **SSE 수명 4시간과 무관하다**). `OpenDoesNotBlockPublishTest`가 그물이다.
  🔴 **저 두 숫자는 자물쇠 안에서 카드를 전부 읽던 때의 것이다** — POK-174가 초기 전송을 없애
  지금 그 안에서 읽는 것은 **방송 한 줄**뿐이라 더 짧다(다시 재지는 않았다). **판정은 안 바뀐다**:
  조회가 자물쇠 안이라는 것도, 트랜잭션이 그보다 앞이어야 한다는 것도 그대로다.
  🔴 **자격 판정은 이 트랜잭션 밖이다** — auth 왕복이 최대 7초라 안에 들어오면 그동안 커넥션을
  쥔다. 컨트롤러가 아니라 `StreamOpener`라는 별도 빈이 트랜잭션을 여는 이유가 그것이다
  (자기 호출은 프록시를 안 타 한 클래스 안에서는 그 경계를 못 만든다)
- **안 읽는 구독자가 자기 스트라이프를 최대 약 61초 막는다.** send 시한 장치를 **일부러 두지
  않았다** — `send()`와 `completeWithError()`가 같은 락이라 끊으러 간 스레드가 같이 멈춘다(실측).
  막힌 연결은 서버의 write timeout이 `IOException`으로 푸는데 **그 값이 약 61초다**(2회 실측,
  60973ms·60988ms). 같은 스트라이프의 다른 연결은 그동안 이벤트가 밀린다(4개면 최악 1/4)
- **큐가 넘치면 그 연결은 다음 전송 실패(최대 61초)까지 그 카드를 못 받는다.** 넘친 이벤트는
  버리고 `jumpcard.stream.rejected` WARN만 남긴다 — 메우는 것은 **카드 목록 문**이다
  (아래 POK-174 절. 재연결만으로는 안 메워진다 — 통로가 지난 카드를 안 보내게 됐다).
  **여기 있던 「1,200장 초기 스냅샷에서 201건 영구 유실」과 「스냅샷 300장 전송이 같은
  스트라이프를 밀어낸다」 두 항목은 지웠다** — POK-174가 초기 전송 자체를 없애 **그 원인이
  사라졌다**(되살리면 그 둘도 같이 돌아온다). 지금 초기 전송은 주석 한 줄(+ 끝난 방송이면
  `ended`)이라 **태스크 하나가 큐 한 칸**을 쓴다.
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
- **🔴 「메운다」가 <u>언제</u>인지가 이 구멍의 크기다 — 이제 그것을 정하는 것은 화면이다.**
  버려진 카드는 통로로 다시 오지 않는다. `publish`가 거부돼도 **연결은 멀쩡히 살아 있어서
  브라우저가 다시 붙을 이유가 없고**, 2026-08-24 재현(PR #113 봇 지적 ①)에서 큐를 채워 카드
  하나를 버리게 한 뒤 큐를 풀었더니 **새 카드는 23ms에 오고 하트비트도 계속 왔는데**
  버려진 그 카드만 안 왔다. 클라이언트가 받은 것은 `:ok` · 새 카드 · ping · ping 넷이 전부이고
  **버려진 카드는 DB에만 있다.**
  🔴 **POK-174가 이 항목의 회복 경로를 바꿨다.** 전에는 재연결이 전체 스냅샷을 실어 와서
  「최대 30분(토큰 수명) 안 보임」이었다(5초짜리 토큰으로 재니 5,283ms에 끊겼고 재연결
  스냅샷에 그 카드가 들어 있었다). **지금은 통로가 지난 카드를 안 보내므로 재연결로는 안
  메워진다** — 메우는 것은 **카드 목록 문**이고, 그것을 부르는 시점은 화면이 정한다
  (새로 열거나 새로고침할 때. 「통로 먼저, 목록 나중」). **재연결만 도는 동안은 계속 안 보인다.**
  🔴 **화면이 실제로 언제 목록을 다시 부르는지는 2번(web) 몫이라 여기서 안 쟀다.**
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
  `initialize` → `sendInternal`). 스트라이프가 MVC보다 먼저 보내기 시작하면 초기 전송이
  전용 스레드가 아니라 요청 스레드에 실린다 — **실측 최대 454건 · 350,637자(약 342KB) ·
  4,024us**(카드 300장·안 읽는 소켓 10개, 2026-08-23). 같은 조건에서 열 연결 중 넷은 **0건**이라
  실행마다 갈린다.
  🔴 **저 규모는 초기 전송이 카드를 전부 싣던 때의 것이다.** POK-174가 그것을 없애 지금 여기로
  올 수 있는 것은 **최대 두 건**(주석 + `ended`)이다 — **자리 자체는 그대로 남아 있고**
  크기만 작아졌다. 다시 재지는 않았다.
  - **공개 훅이 없다.** Spring은 「handler가 붙었다」를 알리지 않는다. `initialize`는
    package-private이고 `extendResponse`(protected)는 그보다 **앞**이라 뒤에도 창이 남는다
  - **이 카드가 만든 것이 아니다.** 카드마다 태스크로 쪼개 봐도 규모가 같았다(**382건 · 294,813자**).
    창을 정하는 것은 태스크 경계가 아니라 **handler 부착 여부**다. 오히려 쪼갠 쪽에서 요청 스레드가
    `NioEndpoint.doWrite`에 서 있는 장면이 잡혔다
  - **커넥션은 안 쥔다.** 여는 쪽의 `@Transactional`(POK-174부터 `StreamOpener`)은 메서드 반환 시
    닫히고 `initialize`는 그 **뒤**다(연결이 사는 동안 `activeConnections=0` 실측).
    잃는 것은 **Tomcat 워커 하나**라 POK-93의 풀 고갈과 급이 다르다
  - **우회안(`initialize` 오버라이드)을 안 쓴다.** 첫 전송이 자물쇠 **밖**으로 나가
    **「새 카드가 `ended`를 앞지름」이 재발한다** — PR #109가 고친 바로 그 구멍이다.
    4ms를 아끼려고 그것을 되살리는 것은 손해다
- **끊긴 연결의 자리는 즉시 안 돌아온다.** 서버는 **다음 쓰기가 실패해야** 안다 —
  그런데 **작은 쓰기 한 번으로는 부족하다**: 트래픽 없이 재니 하트비트 **두 주기 = 39,267ms**
  뒤에 회수됐다(2026-08-24 실측). 자동 회수되지만
  **탭을 닫고 바로 다시 열면 `max-per-user` 4가 3처럼 느껴질 수 있다**
- **🔴 쓸기가 낡은 발행을 통과시키는 창이 하나 있다. 안 고쳤다 — 열리는 조건이 이 구멍의 크기다.**
  연결이 전부 끊긴 사이 하트비트의 순번 표 쓸기(`sweepIdleStreams`)가 그 방송 표를 버리는데,
  그때 **커밋은 됐지만 `afterCommit`이 아직 안 돈 낡은 발행**이 남아 있으면 그것이 빈 표를 만나
  통과한다 → 화면이 **최신 상태를 받은 뒤에** 낡은 값이 도착해 **화면이 뒤로 간다**
  (2026-08-24 재현, PR #114 봇 지적 ①. 클라이언트 도착 순서
  `card(id=204) · card(id=206,놓임) → card(id=205,집힘)`).
  🔴 **POK-174가 「최신 상태를 받는 자리」를 바꿨다** — 전에는 새 연결이 받는 초기 스냅샷이었고,
  지금 통로는 카드를 안 보내므로 그 자리는 **카드 목록 문의 응답**이다(「통로 먼저, 목록 나중」).
  🔴 **창의 크기가 그래서 커졌는지 작아졌는지는 아무도 안 쟀다** — 낡은 것은 아래 문장들이고,
  구멍 자체는 그대로 있다(`CardStreamRegistry`의 `lastPublishedSeq` javadoc이 잔여 창을 인정한다).
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
  - **회복된다 — 다만 재연결로는 안 된다.** 다음 갱신이 오면 **9ms**에 낫고, 오지 않으면
    **카드 목록을 다시 부를 때** 낫는다. 🔴 **「재연결 스냅샷은 정확」은 이제 거짓이다** —
    통로가 지난 카드를 안 실어 오므로(POK-174) 재연결만 도는 동안은 낡은 값이 그대로 남는다.
    목록을 언제 다시 부르는지는 화면이 정한다(2번 몫이라 여기서 안 쟀다).
  - **쓸기만의 문제가 아니다.** 쓸기가 전혀 안 돌아도 **발행 둘이 다 지연되면** 같은 순서가 난다.
    다만 그쪽은 최신이 **2.562ms 뒤 따라와 스스로 낫고**, 쓸기 경로만 **다음 갱신·목록 재조회까지** 남는다
  - **봇이 준 처방 둘의 판정**(다음에 고칠 사람의 출발점) — ①「쓸기가 신선도 상태를 남긴다」는
    **쓸기를 넣은 이유와 정면으로 충돌한다**(통로가 꺼진 배포에서 항목이 무한히 쌓이는 누수를
    막으려고 넣은 것이다). ②「연결마다 카드별 기준선」은 **`CardStreamRegistry.lastPublishedSeq`
    주석의 경고에 걸리지 않는다** — 그 경고가 막는 것은 「**공유** 표를 **연결별 재료**로 채우는
    변형」이다(POK-174 전에는 그 재료가 초기 스냅샷이었고 지금은 목록 문의 응답이 같은 자리다).
    연결별 상태는 남의 연결 판정에 끼어들 수 없다
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
- **auth로 나가는 HTTP는 리다이렉트를 안 따라간다**(POK-174에서 껐다). 안 껐을 때
  실측: `307`·`308`은 원 요청을 그대로 다시 보내 **`X-Internal-Token`과 본문이 리다이렉트가
  가리키는 출처에 그대로 도착**했고, **다섯 상태(`301`·`302`·`303`·`307`·`308`) 전부에서
  도착지의 답이 그대로 자격 판정이 됐다**(전부 `OWNER`). 🔴 **도달성은 실증하지 않았다** —
  오늘 auth가 3xx를 줄 이유는 없고, 막은 것은 「지금 샌다」가 아니라 **「리다이렉트가 가리키는
  곳에 우리 열쇠를 넘겨주고 그 답을 판정으로 읽는다」는 성질**이다(방아쇠가 auth 코드 안에
  갇혀 있지 않다 — 인그레스·리버스 프록시·서비스 메시·HTTP→HTTPS 강제).
  🔴 **한계: 같은 호스트의 다른 포트로 쟀다. 다른 호스트까지 잰 것이 아니다.**
  막은 뒤 3xx는 `UNAVAILABLE`로 접힌다(거절 쪽) — 다만 **로그 사유가 파싱 예외 이름으로
  찍혀 「리다이렉트를 만났다」가 안 보인다.** 알고 넣었다

**아직 없는 것:** 핫키 카드 생성(POK-119) · 만료 정리 배치 · Redis 팬아웃 ·
`clipped`·`expired` 상태. **카드 목록 API는 POK-174에서 생겼다**(아래 절).

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

### clip — 화면 목록과 자격 판정 (POK-174)

**편집자가 홈에서 「내가 볼 수 있는 방송」을 고르고, 그 방송의 카드를 받아 가는 문 둘.**
그리고 **clip의 사람 문 여덟 전부가 자격 판정을 지나게 됐다** — 그 전에는 세그먼트 조회 하나만
물었고 나머지는 「토큰이 유효한 사람인가」까지만 봤다.

표는 안 늘었다. `V204`가 **인덱스 둘**만 만든다
(`broadcasts (streamer_id, id DESC)` · `jump_cards (stream_id, stream_timestamp_ms, id)`).

#### 새 문 둘

| 문 | 인증 | 응답 |
|---|---|---|
| `GET /api/clip/broadcasts?state=&limit=&cursor=` | Bearer JWT | **200** `{"broadcasts":[…],"nextCursor":…}` · 400 `{"error":"invalid_request","field":"state\|limit\|cursor"}` · 401 · 404 `{"error":"broadcast_not_found"}`(토큰의 `sub`가 숫자가 아닐 때뿐 — 아래 「알려진 한계」) · **503** `{"error":"authorization_unavailable"}` |
| `GET /api/clip/broadcasts/{streamId}/jump-cards?includeHidden=&limit=&cursor=` | Bearer JWT | **200** `{"cards":[…],"nextCursor":…}` · 400 `{"error":"invalid_request","field":"limit\|cursor\|includeHidden"}` · **404** `{"error":"broadcast_not_found"}` · 401 · 503 |

**`state`는 필수이고 소문자다** — `live`(방송 중) 또는 `past`(끝난 것: 표의 `ended`·`vod_ready`).
**한 목록에 안 섞는다** — 섞으면 오래 켜둔 방송이 첫 장 밖으로 밀려 방송 중인데 라이브 띠가 안 뜬다.

**`limit`은 안 주면 기본, 넘겨 달라고 하면 상한에서 깎는다** — 방송 **20/100**, 카드 **50/200**.
**0 이하는 400이다**(조용히 기본값으로 봐 주면 웹의 계산 실수가 안 드러난다).

| 방송 한 줄 | |
|---|---|
| `streamId` | 카드 목록·통로·세그먼트 문이 받는 이름 |
| `status` | `live`·`ended`·`vod_ready` — **소문자 그대로.** `state`로 접지 않는다 |
| `relation` | `OWNER` 또는 `EDITOR`. `NONE`은 안 나온다(안 나오는 것이 곧 `NONE`이다) |
| `startedAt` · `endedAt` | 🔴 **`startedAt`은 `null`일 수 있다** — 아래 「알려진 한계」 |
| `vodExpiresAt` | 기한이 지난 방송도 목록에는 그대로 둔다. 영상은 못 봐도 방송 기록은 남는다 |

**카드 한 줄은 통로로 오는 카드 JSON과 칸 하나까지 같다**(`JumpCardListShapeTest`가 두 경로의
JSON 트리를 맞대어 지킨다). 화면이 같은 것을 두 벌로 처리하지 않게 하려는 것이다.

**방송 목록은 「그 방송을 처음 안 순서」의 역순이다 — 시작 시각 순이 아니다.** 시작·종료 시각은
뒤늦게 도착한 알림이 갱신하므로 정렬·이어받기 기준으로 쓰면 중복·누락이 난다.
**카드 목록은 방송 시간 오름차순이다** — 순번(`event_seq`)이 아니다. 그 값은 카드를 숨기면
트리거가 올려서 자리가 바뀌고, 트랜잭션 밖에서 증가해 커밋 순서와도 다를 수 있다.

#### 자격 판정 — 문 여덟이 무엇을 보나

| 문 | 무엇으로 판정하나 | 자격이 없으면 |
|---|---|---|
| `GET /api/clip/broadcasts` | auth `accessible` — **볼 수 있는 스트리머 번호를 먼저 받아 그 번호로만 조회한다** | 그 줄이 목록에 **안 나온다**(200) |
| `GET …/{streamId}/jump-cards` | `BroadcastAccessGuard` — 명부에서 스트리머 번호 → auth `resolve` | **404** `broadcast_not_found` |
| `GET …/{streamId}/events` (통로) | 같음 | **404** `broadcast_not_found` |
| `GET …/{streamId}/segments` | `SegmentQueryService` — 구간 검증 → 방송 → 자격 → 만료 | **404** `broadcast_not_found` |
| `POST …/jump-cards/{id}/claim` | 카드 → 그 카드의 방송 → `BroadcastAccessGuard` | **404** `jump_card_not_found` |
| `DELETE …/jump-cards/{id}/claim` | 같음 | 같음 |
| `POST …/jump-cards/{id}/hide` | 같음 | 같음 |
| `DELETE …/jump-cards/{id}/hide` | 같음 | 같음 |

**아홉 번째 문인 `POST /internal/broadcasts/{streamId}/highlights`에는 자격 판정이 없다** —
서버 간 토큰(`X-Internal-Token`)으로 들어오고 감출 상대가 없다. 판별기는 404를 재시도 상한으로
세므로 아래 25ms 바닥도 안 문다.

**어느 문에서든 auth에 못 물으면 `503 {"error":"authorization_unavailable"}`다.**
**판정 불가는 통과가 아니라 거절이다** — 통과로 접으면 auth가 죽은 동안 남남이 남의 방송을 본다.
목록 문에서 **빈 목록으로 접지도 않는다**: 화면이 「방송이 없다」고 단정하면 auth가 살아난 뒤에도
편집자가 다시 시도하지 않는다.

**순서가 계약이다 — 요청 칸 → 자격 → 조회.** 요청 칸이 맨 앞인 것은 형식 오류 하나가 auth 왕복
(최대 7초 = connect 2s + read 5s)을 태우고 그러고도 400이 나가지 않게 하려는 것이고, 자격이
조회보다 앞인 것은 남의 방송이 메모리에 올라오지 않게 하려는 것이다.
**카드를 만지는 문 넷에서는 자격이 「다른 모든 판정보다」 앞이다** — 뒤로 밀면 남이 잡은 카드를
집으려는 남남에게 **409 본문**이 나가는데 그 본문은 현재 카드 스냅샷이라 **누가 잡고 있는지**가 실린다.

🔴 **그 「최대 7초」가 참인 유일한 이유는 되걸기를 껐기 때문이다.** Apache HC5의 기본 재시도
전략은 **429·503만** 한 번 되거는데(5xx 전체가 아니다), 하필 그 둘이 auth가 힘들 때 나오는
상태라 **auth가 아플 때 정확히 요청을 두 배로** 보내고 최악 대기가 **14초**가 된다.
`HttpClientRetryConfig`가 `disableAutomaticRetries`로 막는다 — **이 빈을 「불필요한 중복」으로
지우면 이 문서의 7초가 세 자리에서 조용히 거짓이 된다.** `AuthRetryContractTest`가 스택이
무엇인지와 요청이 한 번인지를 **둘 다** 못박는다. 같은 빈이 리다이렉트 추종도 끈다(아래
「알려진 한계」).

**이 설정은 POK-174가 안 건드린 문의 동작도 바꿨다** — 세그먼트 조회(POK-117)가 같은
`DelegationResolveClient.resolve`를 쓰므로 auth가 429·503일 때 **요청이 둘에서 하나로, 최악
대기가 14초에서 7초로** 줄었다. 범위가 **clip의 모든 `RestClient`**다(자동설정 빌더를 바꾼다).
지금 나가는 HTTP가 auth 호출뿐이라 표적과 범위가 사실상 같고, 되걸기가 필요한 상대가 생기는
날에는 전역을 되돌리지 말고 **그 클라이언트에만** 전략을 다시 얹는다.

🔴 **auth 왕복은 전부 트랜잭션 <u>밖</u>에서 돈다.** 안에서 돌면 최대 7초 동안 DB 커넥션을 쥔다 —
사람이 기다리는 요청 하나가 풀에서 자리를 그만큼 뺏는다. **`readOnly`를 빼도, 판정을 먼저 하고
조회를 나중에 해도 안 풀린다**: 커넥션은 첫 질의가 아니라 **트랜잭션이 열릴 때** 잡힌다(실측).
그래서 통로는 `StreamOpener`라는 별도 빈이, 카드 문 넷은 `TransactionTemplate`이 경계를 긋는다
(자기 호출은 프록시를 안 타 한 클래스 안에서는 이 분리가 성립하지 않는다).
`BroadcastListTransactionTest`가 **왕복 중 활성 커넥션 수**를 재서 이 불변식을 지킨다 —
애너테이션이 아니라 효과를 재므로 순서나 획득 모드가 바뀌어도 빨간불이 된다.

#### 🔴 `OWNER`와 `EDITOR`를 가르지 않는다

**여덟 문 어디서도 주인과 편집자를 구분하지 않는다.** 자격이 있으면 카드를 집고, 숨기고,
되돌리고, 목록을 보고, 통로를 연다.

**근거는 「누가 매일 쓰나」다.** 이 제품은 **돈 내는 쪽(스트리머)과 매일 쓰는 쪽(전담 편집자)이
다르다.** 카드를 집어 편집하고 숨기는 것이 편집자의 본업인데, 스트리머만 되게 하면 스트리머가
매번 대신 눌러 줘야 하고 **편집자를 두는 의미가 없어진다.**

**가르고 싶어지면 그때 새 규칙을 얹는다** — 넓은 것을 좁히는 것보다 좁은 것을 넓히는 편이
안전해 보이지만, 여기서는 **좁게 시작하면 제품이 안 돌아간다.** 응답의 `relation` 칸은 화면이
「내 방송」과 「내가 편집하는 방송」을 다르게 그릴 재료로만 쓴다 — **권한이 아니라 표시다.**

#### 🔴 「통로 먼저, 목록 나중」 — 웹이 지켜야 하는 순서

**방송 화면을 열 때 통로(SSE)를 먼저 열고, 열린 뒤에 카드 목록을 부른다.**

**왜 그 순서인가.** POK-174가 통로의 초기 전송을 없앴다 — 통로는 **연 뒤에 생긴 것만** 보낸다.
목록을 먼저 받고 통로를 나중에 열면 **그 사이에 생긴 카드가 어디에도 없다**: 목록에는 아직
없었고, 통로는 그때 안 열려 있었다.

**어기면 무엇이 빠지나.** 목록 응답과 통로 연결 사이에 저장된 카드가 **그 화면에서 사라진다.**
통로가 살아 있는 동안에는 새로 안 오고(이미 지난 카드다), 재연결도 안 메운다(지난 카드를 안 보낸다).
**다음 새로고침까지 안 보인다.** 반대 순서로 하면 통로가 먼저 열려 있으므로 그 카드는 통로로 오고,
목록과 겹치는 카드는 같은 `id`로 합치면 된다(카드는 사건의 나열이 아니라 상태다).

🔴 **겹칠 때는 `id`만 보지 말고 `eventSeq`가 큰 쪽을 남긴다 — 「나중에 도착한 쪽」이 아니다.**
목록 질의가 카드를 읽은 뒤 그 응답이 화면에 닿기 전에 누가 그 카드를 집거나 숨기면,
**통로가 새 상태를 먼저 보내고 늦게 도착한 목록이 낡은 상태로 덮어쓴다.** 그러면 이미 남이
집어간 카드가 화면에는 「비어 있음」으로 보인다. `eventSeq`는 **목록 응답과 통로 이벤트가
같은 모양으로 싣는 값**이라 그대로 비교하면 된다.

**틀려도 데이터는 안 깨진다** — 그 카드를 누르면 `409`가 현재 상태를 본문에 실어 화면이 스스로
고쳐진다. 다만 누르기 전까지는 틀린 상태가 보인다.
🔴 **실제로 그 순서가 뒤집히는 빈도는 안 쟀다** — 계약이 그 경우를 안 정하고 있었다는 것만 확정했다
(PR #139 codex 지적).

`GapFreeHandoffTest`가 두 순서를 나란히 잰다 — 지키면 다섯 장이 다 오고, 뒤집으면 빠진다.
**그 두 시험은 짝이다**: 「뒤집으면 빠진다」가 초록인 동안에만 앞엣것이 「순서가 중요하다」를 증명한다.

#### 이어받기 표시(`nextCursor`)는 불투명하다

**웹은 그 문자열을 풀어 보지도, 만들지도 않는다.** 받은 것을 그대로 다음 요청의 `cursor`에
되돌려 넣기만 하고, 마지막 장이면 `null`이다. 우리가 준 것이 아닌 표시는
**400 `{"error":"invalid_request","field":"cursor"}`**로 거절한다 — 방송 목록의 표시를 카드 문에
넣는 것도 거절이다(문마다 종류 태그가 다르다).

**칸을 비워 보내는 것은 안 보낸 것과 같다 — 첫 장이 온다.** `?cursor=`(빈 문자열)도, 공백만 있는
값도 「안 줬다」로 접는다. 마지막 장에서 받은 `null`을 `cursor=${표시 ?? ''}` 같은 흔한 모양으로
그대로 실어 보내는 화면이 **첫 장에서 400을 받지 않게** 하려는 것이다. **`limit`도 같다**
(`?limit=`이면 기본값). 문 둘이 같은 규칙이다.

🔴 **`limit=0`은 다르다 — 그건 400이다.** 빈 값은 **값이 없는 것**이고 `0`은 **값을 준 것**이라,
0을 조용히 기본값으로 봐 주면 웹의 계산 실수가 안 드러난다.

**감추기가 아니다.** base64는 되돌릴 수 있고 담긴 것은 줄 번호와 방송 시각뿐이다. 이 감싸기가
실제로 사는 것은 **「우리가 준 표시만 받는다」**는 좁힘과, 조합 규칙을 웹이 몰라도 되게 하는 것이다.

#### 거절 봉투는 한 벌이다 — 그 400이 이제 clip의 문 <u>전부</u>에 걸린다

**요청 칸이 형식부터 안 읽히는 경우**(`limit=abc` · 없는 필수 칸 · 카드 번호가 숫자가 아님)의
400을 다루는 조언이 POK-174에서 **전역으로 옮겨 왔다.** 그 전에는 세그먼트 문으로 좁혀져 있어
**나머지 문은 스프링 기본 `/error` 봉투**(`timestamp`·`status`·`path`)로 나갔다 — 같은 400에
웹이 모양이 다른 본문 둘을 받고 있었다.

**그래서 `field` 값이 다섯이다** — `state` · `limit` · `cursor` · `includeHidden` · **`id`**.
마지막 것은 **카드 문 넷**(`/api/clip/jump-cards/{id}/…`)에서 나온다:
`POST …/jump-cards/abc/claim` → **400 `{"error":"invalid_request","field":"id"}`**.
값 자체는 안 싣는다(자유 입력을 되돌려주지 않는다). `id`는 우리 시그니처의 조각 이름이다.
(🔴 이 예시의 경로 앞을 `…`로 줄인 것은 **아래 문 대조 스크립트가 예시를 문으로 세기 때문**이다 —
온전히 적었더니 문서 쪽이 9에서 10이 됐다.)

🔴 **이 조언을 다시 좁히지 마라.** 좁히면 새 문이 생길 때마다 그 문만 조용히 기본 봉투로 돌아간다.
양쪽에 두는 것도 안 된다 — `assignableTypes`는 우선권을 주지 않아 전역이 이기고 좁힌 쪽이 죽는다.
`JumpCardControllerTest.카드_번호가_숫자가_아니면_문_넷이_같은_400_봉투를_낸다`가 문 넷을 한꺼번에 잰다.

#### 404는 25ms 바닥 뒤에 나간다 — 이제 사람 문 <u>일곱</u>이 나눠 쓴다

세그먼트 문에만 있던 장치(`NotFoundFloor`)가 `support` 패키지로 옮겨 왔다. 카드 목록·통로·카드
문 넷이 같은 바닥을 쓴다 — **「없는 것」은 명부 조회 하나로 끝나고 「실재하지만 볼 자격이 없다」는
auth 왕복을 타서 시간이 갈리기 때문이다**(세그먼트 문 실측 1,240회에서 중앙값 1.5ms 대 4.4ms,
한 번만 재도 99.5% 구분). 본문을 같게 하는 것만으로는 안 갈린다.

**방송 목록 문은 이 바닥을 안 문다** — 그 문의 응답에는 감출 「존재」가 없다(안 보이는 방송은
줄이 아예 안 나온다).

**카드 문의 시각 그물은 문턱 6ms**(심는 지연 12ms의 절반)**이고, 그 시험을 12회 돌려 잰 차이
최댓값이 1.380ms — 여유 4.3배다.** 🔴 **5회만 돌려 잰 「8배」를 쓰지 마라**: 표본을 늘리자
꼬리가 나왔다. 세그먼트 문 쪽은 15회에 최댓값 2.751ms(2.2배)다. 회귀 신호는 훨씬 크다 —
바닥을 지우면 차이가 **13.591ms**로 벌어진다(잡음 1.4 대 신호 13.6).

#### 알려진 한계

- 🔴 **선행 0이 붙은 스트리머 번호는 목록에서 못 찾는데 세그먼트 조회는 열어 준다.**
  `broadcasts.streamer_id`는 `VARCHAR`이고 auth는 **숫자**를 준다. 세그먼트 조회는
  `Long.parseLong("007")` = 7로 **문자열→숫자**라 관대한데, 목록은 `String.valueOf(7)` = `"7"`로
  **숫자→문자열**이라 `"007"`이 든 줄을 못 찾는다. **같은 방송을 목록에는 안 내보내고 직접 열면
  열어 주는 어긋남이다.**
  - **🔴 왜 지금 안 고치나.** 뿌리가 clip이 아니라 **안 닫힌 계약**이다 — 방송 알림이 싣고 오는
    스트리머 번호가 **우리 회원 번호와 같은 값인지가 아직 안 정해졌다**(1번에게 물어 둔 목록에
    올라가 있고 답을 기다리는 중이다. auth 절의 `NONE` 카운터가 그 미확인 가정을 드러내려고 있는
    장치다). 지금 한쪽 방향으로 맞춰 두면 계약이 다르게 나오는 날 **맞춘 쪽을 다시 뜯는다.**
    **여기서 clip 코드를 고쳐 해결하려 들면 잘못된 자리를 판다.**
  - **갚는 조건은 「그 값의 정본이 정해지면」이다.** 그때 세 자리(목록·세그먼트·판정기)의 변환을
    한꺼번에 맞춘다.
  - **한계로 둘 수 있는 이유는 어긋나는 방향이 안전한 쪽이라서다** — 목록에 **안 나오고** 직접
    열면 **열린다**. 반대였다면(목록에 나오는데 열면 거절) 이번에 고쳐야 했다.
    `BroadcastListQueryTest`가 이 동작을 시험으로 고정한다 — 고치는 날 그 시험이 알려 준다.
  - 🔴 **같은 뿌리에 더 나쁜 갈래가 있다 — `streamer_id`가 아예 숫자가 아니면 그 방송이 통째로
    막힌다.** 선행 0은 「목록에만 안 나오는」 어긋남이지만, 숫자로 못 읽는 값이면 **사람 문
    일곱이 전부 404**다(`BroadcastAccessGuard.parseNumeric` · `SegmentQueryService.parseNumeric`이
    `NumberFormatException`을 `NotViewableException`으로 접는다 — 카드 목록·통로·카드 문 넷·
    세그먼트). 여덟 번째인 방송 목록 문은 404가 아니라 **그 줄이 아예 안 나온다**
    (`String.valueOf(long)`로 조회하므로 못 찾는다). 합치면 **스트리머 본인도 자기 방송을 못 연다.**
    - **발견 수단은 ERROR 로그 하나다** — `clip.access.identity_not_numeric` ·
      `clip.segment.identity_not_numeric`에 `reason=streamer_id_not_numeric`으로 찍힌다.
      아래 「토큰의 `sub`가 숫자가 아니면 404다」와 **로그 이름이 겹치고 `reason`으로만 갈린다**
      (`clip.list.*`는 `sub` 쪽만 찍는다 — 목록 문은 `streamer_id`를 숫자로 안 읽는다).
    - **결함이 아니라 문서 누락이었다** — `BroadcastAccessGuardTest`·`SegmentQueryServiceTest`가
      이 갈래를 로그 레벨까지 잰다. 동작은 fail-closed라 방향은 안전한 쪽이다.
    - 🔴 **Media가 그런 값을 실제로 보내는지는 「없다」가 아니라 「모른다」다** — 1번의 발행 코드를
      본 적이 없고 실물 봉투로 확인한 적도 없다. **아무도 안 쟀다.**
    - **뿌리도 갚는 조건도 선행 0과 같다** — 「그 값의 정본이 정해지면」 세 자리(목록·세그먼트·
      판정기)를 한꺼번에 맞춘다. **여기서 clip 코드를 고쳐 해결하려 들면 잘못된 자리를 판다.**
- 🔴 **방송 알림의 발생 시각(`occurredAt`)에 검증이 없다 — 비면 시작 시각이 빈 방송 줄이 생긴다.**
  시작 알림의 그 칸이 비어 오면 `started_at`이 NULL인 `live` 줄이 만들어진다. **목록은 그 줄을
  그대로 내보내고 `startedAt`을 `null`로 싣는다**(감추거나 지어내지 않는다). 화면은 그것을
  **「시작 시각 미상」**으로 그린다.
  - 🔴 **비어 오는지 아닌지는 「없다」가 아니라 「모른다」다.** 1번의 발행 코드를 본 적이 없고
    실물 봉투로 확인한 적도 없다. 계약(ADR-016)은 이름만 나열한다.
  - **종료 선도착 placeholder도 `startedAt`이 NULL이다** — 그쪽은 알림이 역순으로 왔다는
    정상적인 표시이고(`V201` 주석), 화면에서는 위와 구분되지 않는다.
- **방송 목록은 auth가 「본인을 항상 넣어 준다」는 계약 위에 서 있다.** clip은 `accessible`이 준
  번호로만 조회하므로, auth가 본인을 안 넣게 되는 날 **위임을 안 준 스트리머의 목록이 0행**이 된다 —
  자기 방송이 자기 홈에서 사라지는데 **오류가 아니라 200이다.** 그 계약은 auth 절
  「목록에서 본인은 `relation == OWNER`로 찾는다」에 적혀 있고, **clip 쪽에서는 못 막는다.**
- **`accessible`은 없는 회원 번호에도 자기 자신을 `OWNER`로 돌려준다**(auth 절에 있는 계약).
  그 번호로 조회하면 방송이 0행이라 목록이 빌 뿐이라 지금은 무해하다 — **「이 번호가 실재하나」를
  이 창구에 묻지 않는다**는 뜻이다.
- **토큰의 `sub`가 숫자가 아니면 404다.** 우리가 발급한 토큰이면 생기지 않아야 하는 값이고,
  응답으로는 「없는 방송」과 구분되지 않는다. **유일한 발견 수단은 ERROR 로그다**
  (`clip.list.identity_not_numeric` · `clip.access.identity_not_numeric` ·
  `clip.segment.identity_not_numeric` — 문마다 이름을 갈라 뒀다).
- **auth로 나가는 HTTP의 리다이렉트 차단에는 한계 둘이 있다** — 도달성 미실증 · 같은 호스트의
  다른 포트로만 쟀다. 전문은 위 POK-118 절의 같은 항목에 있다.
- 🔴 **auth가 죽어 있는 동안에는 「있는 것」과 「없는 것」이 상태 코드로 갈린다.** 없는 것은
  명부 조회에서 끝나 **404**이고, 실재하는 것은 auth까지 가서 **503**이다. 시간이 아니라 코드라
  25ms 바닥으로 못 덮는다. **전문은 위 POK-117 절의 같은 항목에 있다**(거기서는 방송 이름이었다).
  - **POK-174가 그 성질을 문 다섯에 새로 태웠다**(카드 목록 · 통로 · 카드 문 넷 중 넷).
    **새로 새는 것은 카드 번호의 실재**이고, 카드 번호는 `bigserial`이라 **연속이다** —
    이 PR이 404 본문을 하나로 합친 근거로 든 바로 그 사실이다. 번호를 훑는 비용이 이름을
    맞히는 비용보다 훨씬 싸다.
  - **`503 → 404` 접기는 일부러 안 골랐다** — 화면이 「없다」고 단정하면 auth가 살아난 뒤에도
    다시 시도하지 않는다(목록 문에서 빈 목록으로 안 접는 것과 같은 판단).
  - 🔴 **재현 안 했다.** auth를 죽여 두 갈래의 상태 코드를 실제로 재 보지 않았고 **코드 경로로만
    확정했다**(자격 판정이 카드·방송 조회 **뒤**에만 auth를 부른다). auth가 살아 있는 동안은 안 샌다.
- 🔴 **방송 목록의 새 색인이 위임을 받은 편집자에게는 안 걸린다 — 비용이 자기 방송 수가 아니라
  표 전체 크기에 비례한다.**
  `V204`의 `idx_broadcasts_streamer_id_desc (streamer_id, id DESC)`는 **스트리머가 한 명일 때만**
  쓰인다. 둘 이상이면 계획이 `broadcasts_pkey` **역방향 스캔**으로 바뀌고, 조건에 안 맞는 행을
  전부 읽어 걸러 버린다.

  | 누가 | 계획 | 버퍼 | 걸러 버린 행 |
  |---|---|---|---|
  | 스트리머 **한 명**(자기 홈) | 새 색인 | **3** | 0 |
  | 스트리머 **둘 이상**(위임을 받은 편집자) | `pkey` 역방향 | **3,828** | **200,000** |

  느린 쪽이 **약 19ms**다(20만 행 시험대, 결과 0건). **위임을 하나라도 받은 편집자는 반드시 둘
  이상이다** — auth의 `accessible`이 본인을 조건 없이 `OWNER`로 맨 앞에 넣기 때문이다(auth 절의
  계약). 그래서 **그 사람은 언제나 느린 갈래로 간다.**
  - 🔴 **「색인을 잘못 만들어 느려졌다」가 아니다.** 색인을 지우고 같은 질의를 다시 재니 **다중
    스트리머는 계획도 시간도 그대로**고 **단일 스트리머만 크게 악화**했다. `V204`는 **어느 모양도
    느리게 만들지 않고**, 기존 질의 계획도 하나도 안 바꾼다(넷 실측 · 쓰기는 건당 약 +3μs).
    정확한 문장은 **「이 PR이 만든 새 질의의 한 모양을 이 PR의 색인이 못 받친다」**다 —
    `V204` 이전에는 이 질의 자체가 없었다.
  - **시간 배율은 일부러 안 적는다** — 빠른 쪽이 잡음 구간이라 배율이 크게 흔들린다
    (같은 현상을 두 사람이 따로 재서 300배와 1,100배가 나왔다). **로버스트한 신호는 버퍼 수다.**
  - 🔴 **안 쟀다 둘.** ① **처방은 양쪽 다 재 보지 않았다** — 질의를 어떻게 바꾸면 색인을 타는지
    확인한 적이 없다. ② **측정 분포가 인공이다**(스트리머 500명 × 방송 400개 균등 20만 행).
    실제 쏠림에서 어떻게 되는지는 모른다.
- 🔴 **`jump_cards`에 읽는 사람이 없어진 색인이 하나 남았다 — 이번에 못 지운다.**
  `V202`의 `idx_jump_cards_stream_seq (stream_id, event_seq)`는 **연결 직후 스냅샷**을 받치려고
  만든 것인데, POK-174가 그 전송을 없애 **`event_seq`로 정렬·필터하는 운영 질의가 0**이 됐다
  (`src/main` 전수: 그 정렬을 쓰는 메서드가 하나뿐이고 운영 호출자가 0이다. 나머지 `event_seq`
  사용처는 전부 **값 읽기**라 색인을 안 탄다). 같은 표에 `V204`가 색인을 하나 더 얹었으므로
  **쓰기 비용만 이고 간다.**
  - 🔴 **`V202` 파일을 고치지 마라 — 주석 두 줄만 손봐도 부팅이 거부된다.** 이미 적용된
    마이그레이션이라 **Flyway 체크섬이 바뀌고**, 그 이력이 남아 있는 DB(로컬·개발)에서 서버가
    안 뜬다(처방은 `services/CLAUDE.md`의 체크섬 절). 그래서 `V202:39`·`:43`의 「연결 직후
    스냅샷」·「따라잡기가 전체 스냅샷이라」 두 줄은 **낡은 채로 남아 있다** — 읽는 사람은 이 항목을
    정본으로 본다.
  - **지우는 것도 이번 범위가 아니다** — 새 마이그레이션(`V205`)이 필요하고, 그건 「목록을 넣는
    카드」가 아니라 별도 정리 작업이다.
  - 🔴 **비용은 안 쟀다.** 「읽는 질의가 0」만 확정했고, **이 색인을 지우면 쓰기가 얼마나 빨라지는지는
    대조를 안 걸었다.** `V204`가 잰 「건당 +3μs」는 **더한 색인 둘**의 값이지 이것을 뺀 값이 아니다.

#### 이 절의 문 목록은 코드와 기계로 대조한다

문서가 낡는 것을 사람 눈에 맡기지 않는다(POK-121 선례). **세는 기준은 「clip이 <u>여는</u> 문」이고,
코드 쪽은 메서드 수준 매핑 애노테이션, 문서 쪽은 clip 절에 적힌 「메서드 + 경로」다.**

```bash
# 코드 — 9
grep -rc '@GetMapping\|@PostMapping\|@DeleteMapping\|@PutMapping\|@PatchMapping' \
  services/clip/src/main/java --include='*.java' | awk -F: '{s+=$2} END {print s}'
# 문서 — 9 (clip이 *부르는* auth 창구는 뺀다)
sed -n '/^### clip — 방송 생명주기 수신/,/^### 치지직 채널 연동/p' services/README.md \
  | grep -o '\(GET\|POST\|DELETE\|PUT\|PATCH\) /\(api\|internal\)[A-Za-z0-9/{}_-]*' \
  | grep -v '/internal/editor-delegations' | sort -u | wc -l
```

🔴 **이 기준이 못 보는 것**(초록이어도 안 지켜지는 것들이다):
- **경로만 본다.** 인증 방식·상태 코드·거절 봉투가 코드와 같은지는 **안 본다** — 그 줄들은 사람이 읽어야 한다
- **문자열 결합만 읽는다.** 경로를 상수나 프로퍼티로 조립하면 코드 쪽 집계에서 사라진다
- **clip이 부르는 남의 문은 일부러 뺐다** — `/internal/editor-delegations/*`(auth)의 이름이 틀려도 초록이다
- **`SecurityConfig`를 안 본다.** 어느 문이 인증을 요구하는지, `/internal`이 다른 체인인지는 못 본다
- **개수가 같아도 짝이 맞는다는 뜻은 아니다** — 이번에는 양쪽 집합을 실제로 빼서 대조했고
  (양쪽 차집합 모두 비었다), 개수만 비교하면 「하나 지우고 하나 더한」 경우를 못 잡는다

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

**종료 유예 20초 이상**(`stop_grace_period` / `terminationGracePeriodSeconds`) — 커밋 뒤 정리(옛 토큰 삭제·치지직
revoke)가 전용 스레드 2개(`ChzzkCleanupExecutor`)에서 돌고 종료 시 최대 10초 기다린다. 도커 기본 10초에 잘리면
대기 중 삭제가 유실돼 고아 secret이 남는다(무해하지만 쌓인다). chat-collector의 종료 유예 20초와 같은 부류이며
`infra/`(1번)가 반영한다. **20초는 이 서버의 정리 풀 셋을 합한 값이다** — 스프링이 `@PreDestroy`를 순차로
부르므로 치지직 10초 + 유튜브 4초 + 탈퇴 6초가 그대로 더해진다(아래 유튜브 절).

로그는 `auth.chzzk.link.<event> userId=` 영어 한 줄이다(`created`·`relinked`·`unlinked`·`refreshed`·
`refresh_rejected`·`refresh_failed`·`refresh_tick_failed`·`rejected`·`unavailable`·`orphan_token`(WARN — 5xx·타임아웃,
치지직에 살아있을 수 있음)·`token_already_dead`(INFO — 4xx, 이미 무효. 429·408·`INVALID_CLIENT`는 제외 — 그 셋은
Unavailable → WARN `orphan_token causeType=Http429/408/InvalidClient`)·`resolve_rejected`·`failed`).
로그의 자리: `refreshed`만 요청 스레드 동기 afterCommit이고, `relinked`·`unlinked`·`refresh_rejected`는 정리 잡 안에서
secrets 삭제 뒤에 찍힌다(정리까지 끝났다는 순서 로그) — 큐가 거부되면 그 로그도 함께 사라지고 그때 `cleanup.rejected`
WARN이 신호다. requestId는 잡이 값으로 옮긴다. 정리 스레드 자체의 것은 `auth.chzzk.cleanup.<event>` —
`rejected`(큐 상한 초과, WARN)·`failed`·`shutdown_timeout`·`dropped`(종료에 잘려 버려진 잡의 회원 번호, WARN).
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

**종료 유예 20초 이상** — 치지직과 같은 이유다. 커밋 뒤 정리(secrets 삭제)가 전용 스레드
2개(`YoutubeCleanupExecutor`)에서 돌고 종료 시 최대 3초 기다린다(넘기면 인터럽트하고 1초 더).
정리 풀이 **셋**이고 각자 스레드 2개를 쓰며, **치지직 10초 + 유튜브 4초 + 탈퇴 6초 = 20초**가 위 20초의
근거다 — 스프링이 `@PreDestroy`를 순차로 부르므로 **합이 곧 예산**이다.
유튜브가 짧은 이유는 정리 잡에 외부 HTTP가 거의 없기 때문이고(구글 revoke를 걷어냈다),
탈퇴(`WithdrawalCleanupExecutor`, 5초 + 강제 1초)가 가장 긴 이유는 사진 창고 호출(최대 8초)을 기다리기
때문이다. `YoutubeShutdownBudgetTest`가 셋과 이 문장을 대조한다.

로그는 `auth.youtube.link.<event> userId=` 영어 한 줄이다(`created`·`relinked`·`unlinked`·`refreshed`·
`refresh_rejected`·`refresh_failed`·`check_tick_failed`·`check_batch_capped`(INFO — 후보가 틱당 상한 25를 넘어
잘렸다, 남은 후보는 다음 틱이 가져간다)·`rejected`·`unavailable`·`scope_missing`·`no_channel`·
`orphan_token`(WARN — 5xx·타임아웃)·`token_already_dead`(INFO — 4xx, 이미 무효)·`resolve_rejected`·`failed`).
**`orphan_token`·`token_already_dead`는 갱신 거부 경로에서만 난다** — 유일하게 revoke를 부르는 자리다.
정리 스레드 자체의 것은
`auth.youtube.cleanup.<event>` — `rejected`(큐 상한 초과, WARN)·`failed`·`shutdown_timeout`·`dropped`(종료에 잘려 버려진 잡의 회원 번호, WARN).
값은 userId·status·causeType·reason만 — 토큰·code·state·channelId는 찍지 않는다.

**마이그레이션은 `V109__create_youtube_channel_links.sql`이다.** 살아있는 행에만 걸리는 부분 유니크
둘(`channel_id`·`user_id`)이 최종 방어선이고, 점검 후보용 인덱스는 `last_refreshed_at` 축이다.

### 회원정보 수정 — 표시 이름·프로필 사진 (POK-207)

표시 이름은 표의 한 칸을 덮어쓰면 끝이다. **사진이 어려운 쪽이고, 어려운 이유가 저장이 아니라 공개 범위다.**

**왜 창고를 공개하지 않나.** 사진을 S3에 두고 그 주소를 그대로 내보내면, **주소만 아는 사람은
누구든 그 그림을 본다.** 이 제품에서 그 그림은 대개 **전담 편집자의 얼굴**이고, 그러면
「이 사람이 어느 스트리머와 일하는가」가 주소 하나로 새어 나간다. 편집자는 돈을 내는 쪽이 아니라
**매일 쓰는 쪽**이고, 그 사실이 새는 것을 스스로 막을 방법이 없다. 그래서 **창고를 열지 않고
auth가 직접 내보낸다**(PRD 결정).

🔴 **「비공개」의 정확한 범위를 오해하지 마라.** 영원히 나만 보는 그림이 아니다 —
**주소(사진 표)를 손에 넣은 사람은 그 표가 죽을 때까지 10~20분간 볼 수 있다.**
막는 것은 「주소를 모르는 사람이 우연히·무작위로 보는 것」이지 「주소를 받은 사람이 보는 것」이 아니다.
그 이상이 필요해지면 표에 권한을 얹을 것이 아니라 **설계를 다시 해야 한다.**

**사진 표는 계정 열쇠가 아니다 — 그런데 그것을 지키는 것이 무엇인지가 중요하다.**

| | |
|---|---|
| 모양 | `userId.exp.version.signature` — **네 칸**, 점으로 가른다(base64url 알파벳에 점이 없어 서명에 구분자가 섞일 수 없다) |
| 🔴 **오늘 실제로 갈리는 것** | **문법이다.** 로그인 토큰(JWT)은 세 칸이고 사진 표는 네 칸이라 **서로의 파서가 상대를 못 읽는다.** 감사자가 두 키를 같게 놓고 창구를 전부 두들겨 확인했다 — 하나도 안 뚫린다 |
| 그럼 키는 | `PROFILE_PHOTO_TOKEN_SECRET`이 `JWT_SECRET`과 같으면 **부팅이 죽는다**(`PhotoConfiguration`) |
| 🔴 **그래서 위험한 자리** | **표 형식을 JWT로 바꾸는 순간 문법 방어가 통째로 사라진다.** 가장 흔한 리팩터링이다. 그날 남는 것은 키 검증 하나뿐이고, 두 키가 같게 배포돼 있으면 **사진 표가 곧 로그인 토큰**이 된다 |
| 얹지 마라 | 이 표에 다른 권한을 더하는 순간 진짜 자격증명이 되고, 「자격증명을 주소에 안 싣는다」와 정면으로 부딪힌다 |

**주소가 언제 바뀌고 언제 안 바뀌나.** 회원 정보(`GET /api/auth/me`)는 60초마다·탭에 돌아올 때마다
다시 불린다. 부를 때마다 표를 새로 만들면 **주소가 매번 달라져 같은 그림을 계속 다시 받는다.**

- **만료를 10분 경계에 맞춘다** → 같은 창 안에서는 주소가 **글자까지 같다.** 남은 수명은 10~20분이다
- **사진을 바꾸면 즉시 달라진다** → 표의 `version`이 사진 수정일시다
- 🔴 **`version`은 밀리초다.** 초였을 때 **연달아 두 번 올린 10회가 10/10 같은 주소**였다(올리기 왕복 7ms).
  사람이 0.3초 간격으로 두 번 누르면 약 70%다. **서버는 새 그림을 내보내는데 브라우저가 옛 그림을 최대 10분 본다**
  (`Cache-Control: private, max-age=600`). 밀리초로 바꿔 닫았다
- **옛 주소는 안 깨진다** — `PhotoToken.verify`에는 「지금 사진의 version」을 받는 자리가 **아예 없다.**
  일부러 그렇게 뒀다: 조이면 사진을 바꾸는 순간 브라우저가 들고 있는 옛 주소가 전부 404가 된다

🔴 **파일 이름에 자리 번호가 붙고 자리는 둘뿐이다** — `profile-photos/{회원번호}/{0 또는 1}`.
올릴 때마다 **반대 자리**를 쓴다(주소에 실린 버전의 홀짝이 자리를 정한다).

**왜 하나로 안 두나** — 하나에 덮어쓰면 **창고에 쓴 뒤 표 갱신이 실패했을 때** 파일만 새것이 되고,
옛 주소가 그 새 그림을 준다. 「실패했다」는 응답을 받은 사용자의 화면에 새 사진이 뜬다(실측).
자리를 가르면 표가 안 바뀐 만큼 주소도 안 바뀌고, 그 주소는 **옛 자리**를 가리킨다.

**주인 없는 파일은 회원당 최대 하나이고 다음 업로드가 그 자리를 덮어쓴다** — 청소 작업이 필요 없다.
**탈퇴(POK-171)는 자리 둘을 모두 지운다**(없는 것을 지워도 S3는 성공으로 답한다).

🔴 **자리를 표에서 읽지 않는다** — 주소에 실린 버전에서 곧바로 얻는다. 표를 읽으면
「사진을 올렸는가」로 걸리는 시간이 갈려 존재가 샌다(아래 「거절은 전부 404」와 같은 이유).
올리는 순간 구글이 준 `profile_image_url`을 **비운다** — 되돌리기가 비목표라 영영 안 읽히고,
둘 다 남기면 어느 쪽을 보여줄지가 그날의 우연이 된다. 영구 손실은 아니다(구글은 로그인할 때마다 다시 보내온다).

**거절은 전부 404다.** 표가 틀렸든 만료됐든 그런 사진이 없든 그런 회원이 없든 **같은 한 줄에서**
만들어진다. 갈라 주면 「그 회원이 사진을 올렸는가」가 표 없이도 새어 나가고, 비공개로 둔 이유가
그 자리에서 무너진다. **본문만 같게 해서는 부족하다** — 표를 통과하기 전에는 창고에 안 가므로
갈래마다 걸리는 시간이 같다(감사자가 일곱 갈래를 섞어 각 200회 재서 확정했다).
**표 없이도 창고에 가는 갈래가 하나라도 생기면 그 성질이 즉시 무너진다** — 순서를 바꾸거나 앞에 조회를 끼우지 마라.

**형식은 내용의 앞머리로 가른다.** 올린 쪽이 밝힌 이름표를 믿지 않고, 나갈 때도 우리가 판정한 값을
싣는다(`X-Content-Type-Options: nosniff`와 함께). 이름표를 그대로 실으면 그림이 아닌 것이
보는 사람의 브라우저에서 실행된다.

**창고 호출은 트랜잭션 밖이다.** 표 갱신만 `PhotoAttacher`가 트랜잭션 안에서 한다 —
DB 커넥션을 쥔 채 외부 HTTP를 기다리면 풀이 마른다(auth가 이미 두 자리에서 밟았다).
**창고 먼저, 표 나중이다.** 뒤집으면 「표는 새 사진을 가리키는데 파일이 없는」 상태가 생긴다.

**실측값 (2026-08-26).**

| | |
|---|---|
| 512×512 PNG 최악 | **1,025KB** — 상한 2MB의 절반이다 |
| 사진 꺼내는 시간 | **중앙값 8.1ms** (1MB, 창고 왕복 포함) |
| 🔴 **되돌릴 조건** | 웹이 **자르는 크기가 약 723px을 넘으면** 2MB를 넘는다 — 그날 **정상적인 사진이 413으로 거부된다.** 자르는 크기를 키우려면 상한도 같이 본다 |

**크기 상한(2MB)은 서블릿 층이 자른다**(`spring.servlet.multipart.max-file-size`).
🔴 **짝인 `max-request-size`는 3MB로 더 크게 둔다** — 같은 값으로 두면 요청 쪽이 multipart
경계·파트 헤더까지 더해 재므로 **먼저 걸리고, 정확히 2MB인 파일이 413이 된다**(실측).
그러면 실효 상한이 위 숫자보다 작아진다. **파일 상한을 올릴 때 이 값도 같이 올린다.**
🔴 **MockMvc로는 그 상한을 못 잰다** — 이미 파싱된 요청을 넣어 DispatcherServlet이 재파싱을
건너뛰므로 **상한을 지워도 초록이다.** 진짜 톰캣을 띄우는 `ProfilePhotoSizeLimitTest`가 잰다.

**창고가 꺼진 배포에서 사진을 올려 둔 회원이 있으면** 회원 정보의 사진 주소가 `null`이 된다
(올릴 때 구글 주소를 비웠으니까). 화면은 이니셜을 그리고 **에러는 아무 데도 안 난다** —
**의도한 동작이고 데이터는 안 잃는다**(설정을 되돌리면 그대로 복구된다).
그 상태를 아무도 모르는 것이 유일한 문제라 요청마다 `auth.profile.photo.unreachable userId=`
WARN을 남긴다. **건수로 알람 걸지 마라** — 사진을 올린 회원 수 × 폴링 빈도에 비례한다.
한 줄이라도 뜨는 것 자체가 신호다.

**창고 이름이 틀렸거나 창고가 못 답하면** 사진을 꺼내는 쪽은 **500이 아니라 빈손(404)**을 낸다.
그 경로는 로그인 없이 닿으므로(그림 태그가 부른다) 예외를 그대로 흘리면 **설정 하나가 틀린 배포에서
사진 요청이 전부 500**이 되고 그 500은 아무나 만들 수 있다. 대신 `auth.profile.photo.read_failed
userId= causeType=` WARN을 남긴다 — `causeType`이 원인을 가른다(`NoSuchBucketException`이면 창고
이름, `SdkClientException`이면 그물·시한). **여기도 건수로 알람 걸지 마라, 같은 이유다.**
**「아직 안 올렸다」는 이 줄을 안 낸다** — 부재는 장애가 아니라서, 그것까지 찍으면 진짜 신호가 묻힌다.
**올리는 쪽은 반대로 삼키지 않는다** — 저장 실패를 빈손으로 바꾸면 사용자가 「저장됐다」고 믿는다.

**마이그레이션은 `V110__add_profile_photo_columns.sql`이다** — 표를 만들지 않고 `users`에
칸 둘을 더한다(`profile_photo_key`·`profile_photo_updated_at`).

🔴 **그 파일의 컬럼 주석은 「이름이 하나로 고정」이라고 적혀 있는데 지금은 자리가 둘이다.**
고치지 않았다 — **Flyway는 파일 내용 전체로 체크섬을 내므로 주석만 바꿔도 이미 적용된
환경의 부팅이 깨진다**(V106에서 겪은 것과 같은 자리). **파일 대신 여기가 정본이다.**

🔴 **`s3`를 넣으면서 기존 HTTP 스택이 바뀔 뻔했다.** AWS SDK가 **Apache5 HTTP 클라이언트를
runtime으로 딸려오고**, 그것이 클래스패스에 오르면 Boot가 `RestClient` 구현을 JDK에서 Apache5로
**오류 없이** 바꾼다. auth의 `connect-timeout: 2s`/`read-timeout: 5s`는 **구글·치지직·유튜브 호출 전부**에
걸린 실측값이라 스택이 바뀌면 어느 층에서 끊는지가 달라진다.
`spring.http.clients.imperative.factory: jdk` 한 줄로 막고 `RestClientFactoryTest`가 지킨다 —
**그 줄을 지우거나 새 SDK를 넣을 때 반드시 그 검사를 돌려라.**
같은 이유로 Apache5 wire 로거를 눌러 뒀다(DEBUG에서 요청 본문과 `Authorization` 값을 통째로 찍는다).

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

### 회원 탈퇴 — 개인정보 파기와 발급물 회수 (POK-171)

**요청 한 번으로 개인정보를 알아볼 수 없게 바꾸고, 그 사람에게 나갔던 발급물을 전부 회수한다.**
발급물은 여섯이다 — 갱신 토큰 · 스트림키(+비밀값) · 페어링 코드 · 치지직 연동 · 유튜브 연동 · 편집자 관계.
프로필 사진 파일도 창고에서 지운다.
**결제 해지·환불과 채팅·방송 데이터 삭제는 범위 밖**이다(다른 서버 소유).

**창구는 하나다 — `DELETE /api/auth/me` → 204.** 웹이 사용자 JWT로 부른다(위 창구 표의 마지막 줄).

- **본문이 없다.** 회원 번호를 받지 않는다 — 표의 주인만 자기 것을 지운다. 「남의 계정 지우기」라는 갈래 자체가 없다
- **성공은 204**(실기동 16~48ms). 몸통이 없다
- 🔴 **두 번째 요청은 204가 아니라 401이다.** 「없는 것을 지워도 성공」이 아니라 **탈퇴한 표로는 아무것도 못 한다**가
  이 서버의 규칙이라서다. 화면은 204를 받으면 그 자리에서 표를 버리면 된다
- **거절 조건이 없다** — 방송 중이어도, 편집자를 두고 있어도 받는다. 개인정보 파기 요청을 서비스 사정으로 막지 않는다

**회원 행을 지우지 않고 익명화한다.** 관계 이력은 남과 나눠 갖는 것이라 행을 지우면 상대 화면에서도 사라진다.

| 칸 | 탈퇴 뒤 값 | |
|---|---|---|
| `google_sub` | `withdrawn:<회원번호>` | **같은 구글 계정으로 다시 로그인하면 새 회원 번호**가 나온다 — 옛 연동·위임·키는 옛 번호에 묶여 있어 안 따라온다 |
| `email` | `withdrawn+<회원번호>@invalid` | `.invalid`는 실재할 수 없게 예약된 최상위 이름이라 **탈퇴 계정으로 남의 초대를 가로챌 수 없다** |
| `name` | `탈퇴한 사용자` | 비울 수 없는 칸이라 값이 필요하다 |
| `profile_image_url` · `profile_photo_key` · `profile_photo_updated_at` | 비운다 | 사진 칸 둘은 **항상 함께** 비운다(`V111`의 CHECK가 반쪽 상태를 DB에서 막는다) |
| `deleted_at` | 탈퇴 시각 | 남은 접근 표를 막는 열쇠다 |

#### 🔴 남은 접근 표는 최대 30분 산다 — auth가 요청마다 막는다

접근 표(access)는 **서버가 저장하지 않아 회수할 수단이 없다.** 탈퇴 뒤에도 최대 30분은 서명이 유효한 표가
사용자 손에 남는다. 그래서 auth는 **인증된 요청마다 회원 표를 확인해 401**로 끊는다.
안 막으면 그 30분 안에 이름·사진을 다시 채우고 스트림키를 새로 받고 채널을 재연동할 수 있다 —
**탈퇴가 사실상 되돌려진다.**

🔴 **「즉시 확정」은 auth 안에서만 참이다.** clip은 우리에게 묻지 않고 표를 독립으로 검증하므로(ADR-049)
**탈퇴 뒤 30분 동안 clip 창구는 그대로 열려 있다.** 그래도 목적은 지켜진다 —
**clip에는 개인정보를 다시 채우는 경로가 없다.** 넓히려면 clip이 매 요청 auth에 묻거나 표에 탈퇴 표시를
실어야 하는데 **둘 다 계약 변경**이라 이 카드가 혼자 정하지 않았다. 「탈퇴하면 모든 서버에서 즉시 막힌다」로
읽지 마라.

- 로그인이 필요 없는 경로(로그인·재발급·로그아웃·**페어링 교환**·사진 내보내기·헬스체크)는 이 확인에 안 걸린다.
  **다만 그 경로에 표를 실어 보내면 주체가 생겨 막힌다**(재발급·로그아웃에 `Authorization` 헤더를 달면 401,
  안 달면 200·204) — **「`permitAll`이면 안 걸린다」는 틀린 문장이다**
- 🔴 **페어링 교환은 이 확인으로 못 막는다** — 코드 자체가 자격증명이라 로그인이 없다.
  그래서 탈퇴가 **살아있는 페어링 코드를 함께 소비 처리**한다. 안 닫으면 교환이 부르는 `ensureKey`가
  「살아있는 키가 없네」를 **「아직 안 만들었다」로 읽고 탈퇴자 명의의 새 스트림키를 발급**한다.
  실기동: 탈퇴 뒤 교환 → **409**, 살아있는 키 **0건**
- **만료된 페어링 코드는 안 건드린다**(이미 못 쓴다). 「방금 썼다」로 덮으면 그 시각이 거짓이 된다
- `/internal/**`은 별도 체인이라 이 확인이 없다 — **그쪽은 이미 빈손이 된다**(키는 폐기, 위임은 닫힘)

#### 🔴 입구에서 한 번 막는 것으로는 부족하다 — 쓰기 직전에 한 번 더 본다

**입구 필터는 요청이 들어올 때 한 번 본다.** 그 뒤에 생기는 지연은 그대로 창이 된다 —
필터를 통과한 요청이 처리되는 동안 탈퇴가 커밋되면, 그 요청은 **탈퇴한 계정에 쓴다.**
창이 좁아 보이지만 **채널 연동은 그 앞에 외부 HTTP가 둘 있어 최대 십수 초**이고,
**사진 올리기는 창고 호출이 최대 8초**다.

🔴 **회원 행 락은 그것을 안 막는다.** 우리가 쓰는 `PESSIMISTIC_WRITE`가 PostgreSQL에서 내는 것은
`FOR UPDATE`가 아니라 **`FOR NO KEY UPDATE`**라 `FOR KEY SHARE`가 안 걸린다 —
**자식 표 INSERT(외래키 검사)가 이 락에 안 막힌다.** 스트림키·페어링 코드·연동·위임·갱신 표가 전부 거기 든다.
즉 **「탈퇴가 도는 동안에는 새 것이 안 생긴다」는 처음부터 성립하지 않았다**(PR #148에서 탐침으로 확정).

**락을 세게 바꾸지 않았다.** 그 락은 토큰 회전·스트림키 재발급·채널 갱신이 전부 쓰는 공유물이라
바꾸면 그 경로들의 경합 성질이 통째로 달라진다. 대신 **쓰기 직전에 탈퇴 표시를 본다** —
확인은 `ActiveUserGuard` 한 곳에 모여 있고 **전수 명부와 그것을 기계로 세는 검사**가 붙어 있다.

| 막는 자리 | 무엇이 안 생기나 |
|---|---|
| `StreamKeyService.ensureKey` | 스트림키 신규 발급 · 페어링 코드 발급 · **교환**(로그인이 없어 필터가 원리상 못 막는 경로다) |
| `PhotoAttacher.currentVersion` | 창고에 올라가는 사진 **파일** |
| `PhotoAttacher.attach` | `users`의 사진 칸 둘 |
| `ChzzkLinkWriter.create` · `YoutubeLinkWriter.create` | 연동 행 + OAuth 원문 둘 |
| `UserService.updateName` | 익명화된 이름의 **되돌리기** |
| `InvitationService.invite` | 탈퇴자가 **보내는** 초대 (**받는** 쪽은 `findAliveByEmail`이 조회에서 닫는다) |
| `TokenService.rotate` | 일괄 폐기를 **넘어 살아남은 갱신 표**의 회전 |

**응답은 401이고 사유는 로그에만 남는다**(`auth.withdrawn.write_blocked userId= site=`).
입구 필터가 한 발 먼저 막았을 요청이라 사용자가 볼 일이 거의 없고, 「그 계정이 탈퇴했다」를
응답으로 알려 줄 이익도 없다.

🔴 **연동 둘만 창이 완전히 닫힌다** — 거기는 어차피 회원 행 락을 잡던 자리라 확인을 락과 함께 하고,
탈퇴도 같은 락을 잡으므로 직렬화된다. **나머지는 「읽고 나서 쓴다」 사이가 남는다**(락을 새로 얹으면
잠금 순서가 생겨 교환↔탈퇴가 서로를 기다린다). 남은 창은 **그 트랜잭션 길이만큼**이고 그 안에
외부 호출이 없다.

🔴 **`TokenService.rotate`가 이 목록에서 가장 늦게 들어왔다**(사용자 결정 2026-08-31).
로그인이 도는 중에 탈퇴가 커밋되면 갱신 표 하나가 일괄 폐기를 넘어 태어난다. 그 표를 그대로 두면
**무기한** 새 접근 표를 찍어내고, **clip은 표를 독립으로 검증**하므로 위의 「최대 30분」이
**그 계정에서 거짓**이 된다. 그래서 재발급도 막는다 — 어차피 잡던 락과 함께 보므로 조회가 안 늘고
재사용 감지의 `noRollbackFor` 성질도 안 바뀐다.

**바뀐 것은 「탈퇴해도 재발급은 된다」**이다(전에는 200, 이제 401). 그래도 **입구 필터의 성질은
하나도 안 바뀌었다** — 「`permitAll` 경로는 주체가 없어 안 걸린다」는 여전히 참이고,
그것을 재는 자리는 **로그아웃**으로 옮겼다. 🔴 재발급에서 재면 이제 **필터를 통째로 지워도 초록**이라
그 검사가 아무것도 안 재게 된다.

🔴 **로그아웃에는 확인을 안 넣었다.** **끊는 동작**이라 탈퇴자에게 해가 없고, 오히려 살아남은 표를
스스로 죽이는 쪽이다.

#### 방송 중에 탈퇴하면 — 키는 즉시 죽고 나가던 방송은 안 끊긴다

| | 탈퇴 전 | 탈퇴 후 |
|---|---|---|
| `POST /internal/stream-keys/resolve` | `{"valid":true,…}` | **`{"valid":false,"reason":"REVOKED"}`** |
| 이미 이어져 있는 송출 | — | **그대로 이어진다** |

**Media(1번)는 방송을 시작할 때 한 번만 물어본다**(계약4). 그래서 막히는 것은 **다음 송출부터**다.
나가던 방송까지 끊으려면 Media 쪽에서 끊어야 하고 그것은 **1번 영역**이다 — 이 카드는 안 건드렸다.

#### 편집자 관계는 양방향으로 닫힌다

- **위임**은 `revoked_by = WITHDRAWAL`로 닫는다. 기존 `STREAMER`(내보냄)·`EDITOR`(나감)와 갈라 적는 이유는
  **사람이 한 행동과 계정이 사라진 것이 다른 사건**이라서다
- **살아있는 초대**는 보낸 쪽·받은 쪽 어느 쪽이 탈퇴하든 **`CANCELED`**다. `DECLINED`로 적으면
  스트리머 화면에 **「거절함」으로 보인다** — 위와 정확히 같은 혼동이다
- 🔴 **`V108`의 `revoked_by` 칸 주석은 값 둘만 적고 있어 낡았다 — 실제 값은 셋이다.**
  마이그레이션은 체크섬이 굳어 못 고치고, **주석 한 줄 때문에 새 마이그레이션을 파지 않았다**
  (서버 넷이 다 돌리고 이 카드의 「표 변경 하나」도 깨진다). **이 줄이 그 갈음이다**
- clip이 묻는 판정은 **편집 자격만 사라지고 본인 방송의 「주인」 판정은 그대로**다 —
  그 창구는 회원 표를 안 읽어 번호 둘이 같으면 실재하지 않아도 주인이다(ADR-047)

#### 커밋 뒤 정리 — 전용 스레드에서 돌고 5초에서 끊는다

**표 변경과 정리는 다른 사건이다.** 트랜잭션 안에서는 표만 바꾸고, **비밀값 삭제와 사진 파일 삭제는
커밋 뒤 전용 스레드**(`withdrawal-cleanup-N`)로 나간다 — 커넥션을 쥔 채 외부 호출을 기다리면 풀이 마른다.

- **종료 유예 20초** 중 마지막 **6초**가 이 풀 몫이다(대기 5 + 강제 1). 산수는 위 「종료 유예」 절에 있다
- 🔴 **5초에서 끊는 것이 감수하는 것** — 창고가 **죽었으면** 더 기다려도 결과가 같지만,
  **느리면**(6~8초) 8초를 기다렸을 때 지워졌을 **사진 파일이 남는다**(창고 호출 상한이 8초인데 대기가 5초다).
  그때 **표는 이미 바뀌어 있어 아무도 그 파일을 안 가리키고 지울 방법도 없다.**
  **5초인 이유는 종료 유예를 22초까지 밀지 않으려는 것**이고, 그 대가가 이 줄이다
- **고아 파일의 주인을 찾는 법은 아래 로그 절에 있다 — 봐야 할 줄이 둘이다**
- **창고 설정이 비어 있으면 자리지기가 아무것도 안 한다** — 탈퇴는 그대로 **204**고 예외도 안 난다.
  대신 **S3 객체가 남는다**(실기동 확인). 창고를 끈 배포에서 탈퇴를 받는다는 것은 그 뜻이다

#### 🔴 사진 주소는 파일이 지워져야 닫힌다 — 「남은 접근 표 30분」과 다른 창이다

사진 내보내기는 **표를 읽지 않는다.** 읽으면 「이 회원이 사진을 올렸는가」가 응답으로 새기 때문이다.
그래서 **이미 나간 사진 주소는 파일이 지워진 뒤에야 404**가 된다.

| | 언제 닫히나 |
|---|---|
| 평시 | 커밋 직후 정리가 지운다(실기동 13ms) |
| 정리가 **끊기거나 거부되면** | 그 사진 표가 죽을 때까지 — **최대 10~20분**(표 만료가 10분 경계다) |

**두 창을 헷갈리지 마라.** 「남은 접근 표 30분」은 **로그인 표** 이야기이고, 이것은 **그림 한 장을 여는 사진 표**
이야기다. 정리가 잘리면 그동안 **탈퇴자의 사진이 계속 공개로 나간다.**

#### 로그 — 표 변경과 정리는 이름이 갈린다

| 이벤트 | 레벨 | 뜻 |
|---|---|---|
| `auth.withdrawal.completed userId=` | INFO | 요청 스레드, 커밋 뒤. **표 변경이 끝났다** — 정리까지 끝났다는 뜻이 <b>아니다</b> |
| `auth.withdrawal.cleanup.started userId=` | INFO | 정리 잡이 비밀값·사진을 지우러 들어갔다 |
| `auth.withdrawal.cleanup.completed userId=` | INFO | 둘 다 지웠다 |
| `auth.withdrawal.cleanup.rejected userId= reason=` | **WARN** | 큐 상한 초과·종료 뒤 제출. **잡 본문이 아예 안 돈다** |
| `auth.withdrawal.cleanup.failed userId= causeType=` | **WARN** | 정리가 던졌다 |
| `auth.withdrawal.cleanup.shutdown_timeout dropped= interrupted=` · `…shutdown_forced …` | **WARN** | 종료 유예에 잘렸다. **둘을 갈라 센다** — `dropped`는 큐에서 버려진 것(`started`조차 없다), `interrupted`는 돌다 끊긴 것(`started`는 있다) |
| `auth.withdrawal.cleanup.dropped userId=` | **WARN** | 🔴 큐에서 버려진 잡 <b>한 건당 한 줄</b>. **그 회원 번호를 되찾을 유일한 실마리다** |
| `auth.withdrawn.blocked userId=` | INFO | 탈퇴한 표로 두드렸다가 **입구 필터**에 401을 맞았다 |
| `auth.withdrawn.write_blocked userId= site=` | **WARN** | 🔴 필터를 **지나서** 쓰기 직전에 막혔다. **경합이 실제로 일어났다는 신호다** — 이름이 위와 갈려야 둘을 구분한다 |

값은 `userId`·`dropped`·`interrupted`·`causeType`·`reason`·`site`만이다.
**이메일·이름·파일 이름·표는 어디에도 안 찍는다**(실기동에서 로그 전체 PII 검색 **0건**).
`site`는 코드에 박힌 상수다(`streamkey.ensure`·`profile.photo.attach` 등) — 사람이 넣는 값이 아니다.

🔴 **정리가 안 끝난 회원(= 남은 파일의 주인)을 찾는 법은 둘이고, 둘 다 봐야 전수다.**

> ① `cleanup.started`는 있는데 `cleanup.completed`가 **없는** `userId` — 시한에 끊겼거나 정리가 던졌다
> ② `cleanup.rejected`의 `userId` — **잡이 아예 안 돌아 `started`조차 없다.** ①만 보면 이 갈래를 놓친다
> ③ `cleanup.dropped`의 `userId` — **종료에 잘려 큐에서 버려졌다.** ②와 같은 이유로 `started`가 없고,
>   거부 핸들러도 안 타므로 ②로도 안 잡힌다(PR #148에서 메웠다 — 그전에는 이 갈래가 **아예 안 보였다**)

파일 이름은 `profile-photos/<회원번호>/0`과 `/1` **둘**이다.
`shutdown_timeout`은 「그런 일이 있었다」와 건수만 말하고 **누구인지는 `dropped` 줄이 말한다.**

🔴 **`auth.withdrawn.blocked`에 건수로 알람을 걸지 마라.** 이 줄은 `permitAll` 경로에서도 표만 실려 오면
나므로 **한 사람이 무한히 만들 수 있다**(표 하나로 재발급을 다섯 번 두드리면 다섯 줄).
`auth.failed`·`auth.token.reuse_detected`와 **같은 함정**이다 — 비율로 보거나 사람이 값을 보고 판단한다.

**탈퇴 뒤 갱신 재시도는 「탈취 의심」(`auth.token.reuse_detected`) WARN을 남긴다.** 탈퇴가 갱신 표를 전부
폐기하므로 10초 유예를 넘겨 오는 갱신이 재사용 갈래로 떨어진다. **알람은 안 울린다** — 끊을 세션이 없어
`revokedSessions=0`이고 운영 규칙이 「`>0`인 것에만 건다」이기 때문이다. 조사하는 사람이 헷갈리지 않게 적어 둔다.

#### 탈퇴가 안 지우는 것 — 알고 남긴 것 셋

**① `pairing_exchange_attempts.client_ip_hash`는 남는다 — 코드로 닫을 수 없는 자리다.**
그 표에는 **회원을 가리키는 칸이 하나도 없고**, 교환 창구가 로그인을 요구하지 않아
**행을 만든 쪽이 회원인지도 서버가 모른다.** 탈퇴가 「이 회원 몫」을 고를 열쇠 자체가 존재하지 않는다.
그 값은 소금 없는 SHA-256이라 IPv4 주소는 전수 대입으로 되돌릴 수 있다.
**이 자리는 탈퇴가 아니라 청소 작업과 서버 측 pepper로 갚는다 — 운영 전 필수다.**
🔴 **탈퇴 기능이 생겼다고 그 항목이 닫힌 것이 아니다.**

**② 두 연동 표의 채널 이름·채널 번호는 남는다.** 탈퇴는 `chzzk_channel_links`·`youtube_channel_links`의
행을 **닫기만** 하고(`revoked_at`·`revoke_reason`) `channel_name`·`channel_id`는 그대로 둔다(재현 확인).
치지직 채널 이름은 대개 **그 사람의 공개 활동명**이라, 익명화된 회원 행 옆에 그 이름이 남아 있으면
**되짚어 사람을 특정할 수 있다.**
**왜 아무도 안 셌나** — 회수 전수를 세는 기준 넷이 전부 「이 칸이 회원을 **가리키는가**」를 묻는데,
채널 이름은 회원을 가리키지 않고 **알아보게 할** 뿐이라 어디에도 안 걸렸다.
**이번엔 기록만 한다**: 기존 해제 경로를 손대지 않는 것이 이 카드의 결정이고(그래야 치지직은 토큰 무효화를
보내고 유튜브는 안 보내는 정책 차이가 자동으로 지켜진다), 나중에 **비우는 쪽으로 가는 것은 코드 한 줄**이지만
**지금 비웠다가 무르는 것은 못 되돌린다.**
**갚는 조건**: 개인정보 파기 요청에 답해야 하는 순간, 또는 그 두 칸을 읽는 화면이 생기는 순간.

**③ 구글·치지직 계정 쪽에 남는 허락은 우리가 못 지운다.** 유튜브는 구조상 revoke를 안 보내고
(그 이유는 위 유튜브 절), 로그인용 구글 동의도 우리가 지울 수단이 없다.
사용자에게는 웹이 구글 권한 페이지를 안내한다.

**되돌리는 수단은 만들지 않았다** — 탈퇴 유예·복구·탈퇴 이력 표가 전부 비목표다.
익명화된 회원 행 자체가 이력이고, 그 행은 영원히 남는다.

**환경변수는 스물 그대로다** — 이 카드가 더하는 것이 없다. 새 표도 없다(`V111`은 칸 하나 + 제약 하나).
`web-support/CorsConfig`도 안 고쳤다 — `DELETE`는 치지직 해제가 이미 열어 뒀다.

#### 실기동 확인 (2026-08-31)

전용 DB·전용 LocalStack에 실제로 띄워 재 봤다(공유 DB에는 아무것도 안 걸었다).

- **탈퇴 204 · 29ms.** 갱신 표·스트림키·비밀값·위임·초대·사진 칸이 전부 0
- **방송 중 탈퇴**: `valid:true` → 탈퇴 → **`valid:false reason=REVOKED`**. 나가던 방송은 안 끊긴다
- **탈퇴 전에 받아 둔 사진 주소**: 200(70바이트) → **404**(파일이 지워진 뒤)
- **살아있던 페어링 코드로 교환**: **409**, 그리고 살아있는 키 **0건**(`ensureKey`가 새 키를 안 만들었다)
- **남은 표로 창구 일곱을 두드림**: 전부 **401**
- **창고를 끈 배포**에서도 탈퇴 **204 · 예외 0건**. S3 객체는 그대로 남는다(자리지기 무동작)
- **로그 짝** `completed` → `cleanup.started` → `cleanup.completed`가 회원마다 맞고, 표 변경은 웹 스레드,
  정리는 `withdrawal-cleanup-N` 스레드로 갈려 나온다. **PII 검색 0건**
- **CORS preflight** `OPTIONS /api/auth/me`(`Access-Control-Request-Method: DELETE`) → **200**,
  `Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE`
- 못 잰 것 둘: **구글 재로그인**과 **refresh 원문 토큰** — 둘 다 실제 구글 왕복이 필요하다.
  대신 코드·DB로 확인했다(`google_sub`이 `withdrawn:N`으로 바뀌었고 `findOrCreate`는 그 값으로만 찾는다)

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

### chat-detector — 채팅 급증 하이라이트 판별 (POK-120)

**채팅이 평소보다 튀는 순간을 찾아 점프카드로 만들어 `clip`에 보낸다.** 이 서버의 첫 코드다.

```
① 최근 채팅이 온 방송을 고르고        (received_at 기준)
② 눈금 창마다 세어 chat_metrics 에 남기고   (message_time 기준)
③ 최근 15분 중앙값을 「평소」로 잡아 판정하고
④ 그 시각을 영상 위치로 바꾸고        (수집 서버 변환 창구, POK-92)
⑤ 카드를 보낸다                      (clip 계약 2A)
```

**HTTP 문을 하나도 열지 않는다.** 부르기만 하고 받지 않는다 — `actuator`의 `health`만
노출하고 `/internal/**`이 없으므로 `X-Internal-Token`은 **보낼 때만** 쓴다. 그래서 시큐리티
스타터도, `web-support`의 CORS도 안 붙인다(`CORS_ALLOWED_ORIGINS`가 필요 없는 유일한 서버다).

표는 하나다 — `chat_metrics`(`V401`). 한 줄이 「어느 방송의, 몇 초짜리 창의, 어느 눈금」이고
`UNIQUE (stream_id, window_size_ms, window_start_ms)`가 같은 창을 두 줄로 만들지 않는다.
`published_at`이 발행권이다 — **실패해도 원칙적으로 되돌리지 않는다**(늦게 도착한 카드는 편집자 화면을
과거로 오염시키므로 버리는 편이 낫다). **예외는 아래 「알려진 구멍」 ②-3에 있다.**
그래서 **`NULL`은 「한 번도 안 집혔다」와 「집었다가 놓았다」 둘 다**라는 것에 주의한다 —
표를 직접 들여다보며 지표를 셀 때 이 둘이 같은 모양이다.

🔴 **`first_claimed_at`은 되돌려도 안 바뀐다.** 그 창을 **처음** 집은 시각이고,
지연을 잴 때 **「집계에 쓰인 채팅」의 상한**으로 쓴다. `published_at`을 상한으로 쓰면
재시도마다 상한이 뒤로 밀려, **판정에 쓰이지도 않은** 늦은 채팅이 `max(received_at)`에 섞이고
우리 구간이 **실제보다 짧게(=낙관적으로)** 나온다.

**🔴 인덱스 하나가 이 서버 밖에 있다.** `activeStreams`가 쓰는
`idx_chat_messages_received`는 **수집 서버 대역의 `V305`**다(표가 있는 곳에 인덱스도 둔다).
**수집 서버 배포가 밀리면 판별 서버는 아무 신호 없이 매초 전체 훑기를 한다** —
60만 행 실측으로 **1.1ms → 19.5ms, 약 18배**다. **둘은 같이 배포한다.**

#### 🔴 시각 칸이 둘인데 쓰는 자리가 셋이다

`chat_messages`의 `message_time`(치지직이 찍은 시각)과 `received_at`(우리가 받은 시각) —
**둘 다 `TIMESTAMPTZ`라 바꿔 써도 컴파일되고 쿼리도 돌고 검사도 초록일 수 있다.**

| 자리 | 쓰는 칸 | 바꿔 쓰면 |
|---|---|---|
| 활성 방송 판단 | `received_at` | 치지직 시계가 어긋나면 방송이 통째로 안 보인다 |
| 집계 눈금 | `message_time` | 전달 지연만큼 창이 밀려 카드가 엉뚱한 지점을 가리킨다 |
| 우리 구간 지연 | `received_at` | 우리 탓이 아닌 구간이 우리 숫자에 섞인다 |

집계 SQL은 `WHERE`에 **둘 다** 건다 — `received_at`은 인덱스를 타려고(±5분 여유),
`message_time`은 창 경계를 정확히 자르려고.

**HTTP 문을 하나도 열지 않는다.** `actuator`의 `health`만 노출한다 — `/internal/**`이 없으므로
`X-Internal-Token`은 **보낼 때만** 쓴다(clip·수집 서버의 문을 여는 열쇠). 그래서 시큐리티
스타터도, `web-support`의 CORS도 안 붙인다(`CORS_ALLOWED_ORIGINS`가 필요 없는 유일한 서버다).

**`compose` 칸은 `docker-compose.dev.yml`의 `chat-detector` 블록이 실제로 넘기는지다.**
`—`는 넘기지 **않는다**는 뜻이고 그래도 정상이다 — yml 기본값이 dev에 그대로 쓸 만하다.

| 변수 | 기본값 | compose | |
|---|---|---|---|
| `DB_HOST` | `localhost` | ✅ `postgres` | compose 안에서는 서비스 이름이 곧 호스트다 |
| `DB_PORT` | `5432` | — | `.env`에 없는 값이라 기본값을 남긴다 |
| `POSTGRES_DB` | **없음** | ✅ | **기본값을 일부러 안 준다**(POK-161). 셸에 없으면 리터럴이 그대로 바인딩돼 `FATAL: password authentication failed`로 죽는다 |
| `POSTGRES_USER` | **없음** | ✅ | 위와 같다 |
| `POSTGRES_PASSWORD` | **없음** | ✅ | 위와 같다 |
| `INTERNAL_API_TOKEN` | 빈 값 | ✅ | clip·수집 서버의 `/internal/**`에 **보낼** 값. 그쪽에 준 것과 같아야 한다 |
| `CLIP_BASE_URL` | `http://localhost:8081` | ✅ `http://clip:8081` | 점프카드를 넣는 곳 |
| `CLIP_MAX_ATTEMPTS` | `3` | — | 카드 발행 총 시도 횟수(재시도 포함). **4xx는 이 횟수와 무관하게 한 번만 보낸다** — 같은 본문이라 다시 보내도 같은 답이다 |
| `COLLECTOR_BASE_URL` | `http://localhost:8083` | ✅ `http://chat-collector:8083` | 채팅 시각을 영상 위치로 바꿔 주는 창구(POK-92) |
| `DETECTION_SCHEDULER_POOL_SIZE` | `3` | — | 🔴 주기 작업이 셋인데 스프링 기본 풀이 **1**이다. 하나면 치우기가 도는 동안 판정이 통째로 멈춘다 |
| `DETECTION_CYCLE_INTERVAL` | `1s` | — | 한 바퀴 도는 간격. 발행 창보다 촘촘해야 창이 안 밀린다 |
| `DETECTION_WINDOW_SIZES_MS` | `3000,5000,10000` | — | 집계할 창 크기들 |
| `DETECTION_PUBLISH_WINDOW_MS` | `5000` | — | 판정·발행에 쓰는 창. **위 목록에 없으면 부팅이 죽는다** |
| `DETECTION_WINDOW_GRACE` | `2s` | — | 창이 지나고 더 기다리는 시간. **우리 구간 지연에 그대로 더해진다** |
| `DETECTION_LATE_REPORT_INTERVAL` | `10m` | — | 늦게 온 채팅을 세어 찍는 간격. 위 유예값을 조정할 근거를 모은다 |
| `DETECTION_ACTIVE_STREAM_WINDOW` | `60s` | — | 이 시간 안에 채팅이 온 방송만 센다 |
| `DETECTION_COLLECT_LOOKBACK` | `1m` | — | 한 바퀴가 되돌아보며 다시 집계하는 기간. **아래 베이스라인 기간과 다른 값이다** — 15분으로 두면 100 방송에서 1초 주기를 못 지킨다 |
| `DETECTION_BASELINE_WINDOW` | `15m` | — | "평소"를 보는 기간 |
| `DETECTION_WARMUP_WINDOWS` | `24` | — | 이만큼 안 쌓이면 카드를 안 낸다. 켜자마자는 아무 채팅이나 무한대 배율이다 |
| `DETECTION_SPIKE_RATIO` | `3.0` | — | 배율 임계(ADR-011). **바꿀 때는 높은 쪽이 안전하다** — 가짜 카드가 놓친 것보다 비싸다 |
| `DETECTION_MIN_COUNT` | `10` | — | 절대 최소 건수. 작은 채널의 2건→4건 같은 허수 배율을 자른다 |
| `DETECTION_METRIC` | `MESSAGE` | — | `MESSAGE` 또는 `CHATTER`. 사람 수는 지금 집계만 하고 판정엔 안 쓴다 |
| `DETECTION_RETENTION` | `24h` | — | 집계 줄 보관 기간 |
| `DETECTION_SWEEP_INTERVAL` | `10m` | — | 보관 기간이 지난 줄을 치우는 주기 |

**임계값 셋(`SPIKE_RATIO`·`MIN_COUNT`·`WARMUP_WINDOWS`)은 확정값이 아니다.** 멘토 협업이
미결이라 설정으로 빼 뒀다 — 실측 뒤에 다시 정한다.

#### 로그로 무엇을 보나

**이 서버는 창구가 없어 로그가 유일한 관측 통로다.** 열일곱 줄이고, 접두어는 전부 `detect.`다.

| 이벤트 | 레벨 | 언제 보나 |
|---|---|---|
| `detect.card_published` | INFO | **카드가 실제로 들어갔을 때만**(201·200). 지연 두 숫자가 여기 있다 — 아래 절. `count=`는 **판정에 실제로 쓴 지표**의 값이고 어느 지표인지는 `metric=`이 말한다 |
| `detect.card_skipped` | INFO | 판정은 급증인데 안 보냈다. **`reason=`이 처분을 가른다** — `NOT_YET_INDEXED`만 **발행권을 되돌려 되돌아보기 안에서 다시 시도**하고, `UNAVAILABLE`(창구가 죽었다)·`NO_FOOTAGE`(영영 없다)·`invalid_window`(위치가 음수 등)는 그대로 포기한다 |
| `detect.active_but_empty` | INFO | **활성인데 셀 창이 0줄인 방송이 있다.** 아래 「알려진 구멍」의 시계 어긋남을 보는 유일한 통로다. 바퀴마다 한 줄이고 개수 + 표본 5개를 싣는다 |
| `detect.late_arrivals` | INFO | 10분마다. **`DETECTION_WINDOW_GRACE`를 조정할 근거다** — `observed=`는 분모(그 기간 전체 채팅), `beyondGrace=`가 놓칠 수 있었던 상한, `beyondWindowAndGrace=`가 반드시 놓친 하한, `maxDelayMs=`가 얼마로 올릴지를 정한다. 잰 것이 없으면 `none`이다 |
| `detect.metrics_swept` | INFO | 보관 기간이 지난 줄을 지웠을 때만. **지운 것이 없으면 안 찍는다** |
| `detect.cycle_failed` | WARN | **한 바퀴가 통째로 터졌다.** 계속 나오면 판정이 사실상 멈춘 것이다 |
| `detect.stream_failed` | WARN | 방송 하나가 터졌다. 나머지 방송은 그 바퀴를 계속 돈다 |
| `detect.publish_threw` | WARN | 발행 작업이 터졌다. **발행권이 이미 잡혀 재시도가 없다** — 그 카드는 안 나간다 |
| `detect.latency_unmeasured` | WARN | **카드는 나갔는데 우리 구간을 못 쟀다**(발행 뒤 도는 조회가 터졌다). `detect.card_published`는 `ourLatencyMs=unknown`으로 그대로 남는다 — 이 줄이 그 `unknown`의 원인이다 |
| `detect.publish_dropped` | WARN | **발행 큐가 차서 버렸다.** 같은 이유로 그 카드는 안 나간다. clip이 오래 죽어 있다는 신호다 |
| `detect.publish_broadcast_missing` | INFO | **clip이 그 방송을 아직 모른다**(404). 방송 시작 알림을 수집기와 clip이 각자 다른 큐에서 받으므로 clip이 늦으면 온다. **발행권을 되돌려 다음 바퀴에 다시 보낸다** |
| `detect.publish_rejected` | WARN | clip이 **404가 아닌** 4xx를 줬다. **재시도하지 않는다** — 같은 본문이라 영영 같은 답이다 |
| `detect.publish_retrying` | WARN | clip이 5xx거나 안 떴다. `attempt=n/N` |
| `detect.publish_failed` | WARN | 시도 횟수를 다 쓰고 못 넣었다 |
| `detect.video_position_unavailable` | WARN | 변환 창구를 못 물었다. `cause=`가 예외 종류거나 `unknown_state`·`converted_without_position` |
| `detect.metrics_sweep_failed` | WARN | 치우기가 터졌다. **계속 나오면 표가 안 치워지고 있다** |
| `detect.late_arrivals_failed` | WARN | 관측이 터졌다. 판정에는 영향이 없다 |

**주기 작업 셋은 `Throwable`까지 잡는다.** `@Scheduled`는 태스크가 한 번이라도 던지면
**그 주기가 영영 안 돈다** — 판별이 통째로 멈추는데 아무 신호가 없다. 위 `*_failed` 세 줄이
그 포획 자리이고, **그 줄이 보인다는 것은 주기가 살아 있다는 뜻**이다.

#### 지연을 두 구간으로 나눠 적는다

`detect.card_published` 한 줄에 숫자가 둘이다. **시작점이 다르고, 그 차가 곧 전달 지연이다.**

| | 시작 | 끝 |
|---|---|---|
| `ourLatencyMs` | 그 창을 **우리가 다 받은 시각**(`max(received_at)`) | **카드가 나간 뒤** |
| `totalLatencyMs` | **창이 닫힌 시각** + 보정값(장면이 벌어진 때) | 같음 |

**목표 3초는 `ourLatencyMs`에만 건다** — 통제 못 하는 구간을 판정에 넣으면 시청자가 늦게 쳐도
우리 코드가 실패한 것이 된다(PRD 결정). 반대로 **총 시간에는 전달 지연이 들어가야 맞다.**
**둘을 같은 시작점으로 통일하면 한쪽이 반드시 틀린다.**

끝점은 **변환 창구·clip 호출이 끝난 뒤**다 — **clip 재시도가 우리 구간에 들어간다.**
clip이 느리면 우리 구간이 길어지는 것이 맞는 동작이다(그 왕복은 우리가 통제하는 시간이다).

값을 못 내면 **`unknown`으로 적는다.** 0을 적으면 「지연이 없었다」는 거짓이 남고 목표치를
정할 때 그 거짓이 표본에 섞인다.

#### 실기동 확인 (2026-08-26)

| 확인 | 결과 |
|---|---|
| 기동 | `Started DetectorApplication in 1.198s` |
| 주기 | 채팅 0건일 때도 **1.007~1.021초** 간격. 스레드 `scheduling-1/2/3` — **풀 3이 실제로 먹었다** |
| 워밍업 | 창 80개가 전부 최소 건수 위인데 **카드 0장 · 변환 창구 호출 0회** |
| 급증 | 165건 / 기준선 15 → 배율 11.0 → clip에 **201** |
| 지연 두 숫자 | `our=2357 / total=6186`. **DB에서 따로 재서 한 자리도 안 틀렸다** |
| `active_but_empty` | 통틀어 **3줄**, 전부 첫 채팅 직후. 3분간 초당 3건 넣는 동안 **0줄** |
| clip 먹통 | 발행이 **9.02초** 묶여도 판정 주기는 **1.014~1.024초** 유지 — 발행 스레드 분리가 실물에서 작동 |
| `read-timeout: 3s` | 정확히 **3.005초**에 끊긴다 |

> 연결 **거부**는 3ms라 「멈춤」 시험이 안 된다 — **연결만 받고 답을 안 하는 가짜 clip**을
> 따로 세워서 쟀다. 임계값은 하나도 안 낮췄다(채팅 있는 창 40개로 워밍업 24를 정직하게 넘겼다).

#### 설정을 바꿀 때 부팅이 막는 것

**값만 갈아 끼우게 설계한 서버라, 갈아 끼우다 조용히 죽는 조합을 부팅에서 막는다.**
아래 어느 하나라도 어기면 **서버는 뜨고 집계도 도는데 카드가 하나도 안 나가고 오류도 없다.**

| 관계 | 어기면 |
|---|---|
| `publish-window-ms` ∈ `window-sizes-ms` | 발행할 줄이 영영 안 생긴다 |
| `window-sizes-ms`의 원소가 전부 1 이상 | 눈금 계산이 터져 판정이 통째로 멈춘다 |
| `Duration` 칸 일곱이 전부 양수(`window-grace`만 0 허용) | `retention=0`이면 치우기가 **매 주기 표를 통째로 비운다** |
| `warmup-windows` ≤ `baseline-window` ÷ `publish-window-ms` | **영영 워밍업** — 기준선 조회가 그 기간 안의 창만 읽으므로 창 수가 물리적 상한이다 |
| `active-stream-window` ≥ `window-grace` + 가장 긴 창 | 급증 뒤 조용해진 방송이 **그 창을 집계하기 전에** 활성에서 빠진다 |
| `collect-lookback` ≥ 가장 긴 창 | 닫힌 창이 안 나와 집계가 0줄 |
| `retention` ≥ `baseline-window` | 치우기가 **기준선을 지운다** |

🔴 **`window-grace`를 올릴 때 활성 창을 같이 본다.** 유예는 `detect.late_arrivals`를 보고
튜닝하라고 만든 값인데, 60초로 올리면 활성 창 기본값 60초와 **정확히 부딪힌다.**

#### 알려진 구멍

**① 첫 바퀴가 예산의 68~92%다.** 정상 상태는 **206~527ms**인데 **찬 캐시 첫 바퀴가
680ms(실기동, 방송 102개) / 918ms(합성, 방송 100개·채팅 60만)**다. 예산은 1초(`CYCLE_INTERVAL`).
**재시작·장애 복구 직후가 그 상태**이고, `fixedDelay`라 넘으면 그만큼 주기가 밀린다.
실운영 규모가 이 합성 데이터보다 크면 첫 바퀴가 예산을 넘을 수 있다.

**② 바퀴 하나가 SQL 8,249문장이다**(방송 100개 실측). 그중 **2,276개가 대부분 아무것도 안 하는
`INSERT ... ON CONFLICT DO NOTHING`**이다 — `DETECTION_COLLECT_LOOKBACK`(1분)을 매 바퀴 다시
집계하기 때문이다. **지금 값에서는 예산 안이다.** 줄이려면 「이미 집계한 창은 건너뛴다」가
필요한데 그건 설계 변경이라 이 카드에서 안 했다.

**②-2 판정 대상에 시간 하한이 있다 — 없으면 두 군데가 같이 무너진다.**
「아직 발행권이 안 잡힌 창」 조회에 `window_start_ms >= 되돌아보기 첫 눈금`을 건다.
없을 때를 전용 DB(100방송·24시간치 **547만 줄**)에서 쟀다.

| 없으면 | 무엇이 |
|---|---|
| 상시 | 방송당 **17,280줄**을 훑고 필터로 버린다. 100방송 순회 **105~308ms** — 위 ①의 정상 바퀴 위에 그대로 얹힌다 |
| **발행 창을 바꾸는 날** | 발행 창이 아닌 크기(3·10초)는 발행권이 영영 안 잡혀 쌓인다. `DETECTION_PUBLISH_WINDOW_MS`를 그 크기로 바꾸면 첫 바퀴 판정 대상이 **288만 줄**·조회만 **1,024ms**가 되고, 줄마다 기준선 조회와 UPDATE가 붙어 **판별이 멈춘다** |

**하한은 집계가 되돌아보는 폭과 같은 산식**이라 「집계는 하는데 판정은 안 하는 창」이 안 생긴다.

**②-3 계약이 「재시도할 자리」로 명시한 것만 발행권을 되돌린다 — 재시도 폭은 되돌아보기까지다.**
발행권은 카드를 보내기 **전에** 잡으므로, 조각이 아직 장부에 안 온 것(`not_yet_indexed`)을
그대로 두면 그 창은 **영영 다시 안 집힌다.**
**채팅에는 백필이 없어 되찾을 방법도 없다** — 수집 서버 계약이 경고한 자리다.

되돌리는 것은 **둘**이다 — 변환 창구의 `not_yet_indexed`, 그리고 **clip의 404**.

🔴 **clip의 404를 다른 4xx와 같이 보면 안 된다**(봇 리뷰 1판, codex). 방송 시작 알림을
**수집기와 clip이 각자 다른 큐에서** 받으므로, clip이 늦으면 **채팅은 이미 쌓이는데
clip에는 방송 행이 없다.** 워밍업(최소 2분)이 시간을 벌지만 clip이 그보다 오래 죽어 있으면
그 사이 급증 카드가 전부 영구 유실된다. **clip이 재시도를 전제로 설계한 자리다** —
`JumpCardService.record`가 「FK 위반은 500이 되고, **판별기는 404를 받아야 재시도 상한을
센다**」라고 적어 뒀다.

되돌리지 **않는** 것: `no_footage` · 위치가 음수 · clip이 **404가 아닌** 4xx로 거절 ·
시도 소진 · **`UNAVAILABLE`(창구를 못 물었다)**. 다섯 다 다시 물어도 답이 같거나,
되물어 봐야 값이 없다.

🔴 **`UNAVAILABLE`은 한 번 재시도 쪽에 넣었다가 뺐다.** 그 상태는 원인 셋이 섞여 있고
(예외 · `unknown_state` · `converted_without_position`) 뒤의 둘은 **영영 같은 답**이다.
예외 쪽은 더 나쁘다 — 창구가 죽어 있으면 매 시도가 `read-timeout` 3초를 꺼내 쓰는데
발행 실행기는 core 2 · queue 100이다. **큐가 차면 그 뒤 발행이 전부 버려진다** —
되돌린 창도, 그 사이 정상적으로 보낼 수 있던 **다른 방송의 카드도 같이** 잃는다.
**가르는 축은 수집 서버 계약이 정한 것을 그대로 쓴다.**

🔴 **남는 대가**: 재시도는 되돌아보기(기본 1분) 안에서만 돈다. 조각 인덱싱이 그보다 오래
밀리면 그 창은 여전히 유실된다. **무한 재시도와 맞바꾼 것**이고, 늦은 카드는 되감기 창을
벗어나 가치가 없다는 PRD 결정과 같은 방향이다.

**③ 우리 구간 상한에 `chat_metrics.created_at`을 쓰지 않는다.** 「진짜 집계 시각」이라 그쪽이
맞아 보이지만 **방향이 고정되지 않는다.** PostgreSQL `now()`는 문장 시각이 아니라
**트랜잭션 시작 시각**이라, `created_at`의 위치가 **트랜잭션 경계**라는 무관해 보이는 결정에
딸려 움직인다(2026-08-26 실측, 서로 다른 컨테이너에서 교차 확인).

| 경계 | `created_at`의 위치 | 지연 오차 |
|---|---|---|
| 오토커밋(지금) | 집계 조회보다 **+2.003초 뒤** | **낙관**(우리 구간이 짧게) |
| `@Transactional`로 감싸면 | 집계 조회보다 **앞**(차이 0.000초) | 비관 |

🔴 **이 서버에는 `@Transactional`이 한 개도 없다 — 즉 그 경계가 아무 데도 안 적혀 있다.**
한 바퀴를 트랜잭션으로 묶는 것은 있을 법한 변경이고, 그때 **지연 숫자가 조용히 부호를 바꾼다.**
지금 상한은 그 결정과 무관하게 늘 비관 쪽이다.

**그 상한은 `first_claimed_at`이다** — 그 창을 **처음** 집은 시각이고 되돌렸다 다시 집어도
안 바뀐다. `published_at`을 쓰면 **재시도마다 상한이 뒤로 밀려** 같은 병이 되살아난다
(봇 리뷰 2판에서 codex가 그 경로를 짚었다).

**④ 시계가 크게 어긋난 방송은 영영 카드가 안 나간다.** 활성 판단은 `received_at`(우리 시계)인데
집계 범위는 우리 시계에서 만든 `COLLECT_LOOKBACK` 폭을 `message_time`에 건다. 그래서 치지직
시각이 그 폭보다 크게 어긋나면 **활성 목록에는 있는데 셀 창이 0줄**이 되고, 되돌아보는 폭이
계속 앞으로 가므로 **나중에 메워지지도 않는다.**

구조를 안 바꾼 이유 셋 — ① 폭을 15분으로 되돌리면 위 ②가 100 방송에서 1초 주기를 깬다
② 틀리는 방향이 **안전한 쪽**이다(카드를 안 낸다) ③ 1분 넘는 시계 어긋남의 실측이 없다
(저장소 실측은 중앙값 175ms · −39~−70ms). **`detect.active_but_empty`가 그 대가를 밖으로
내보내는 유일한 통로다.**

> 그 줄은 시계 어긋남만 켜는 것이 아니다 — **성긴 방송은 전달 지연 3초만 넘어도** 걸리고
> (활성 창 끝자락 채팅만 있는 경우), 촘촘한 방송은 62초를 넘어야 걸린다.
> **계속 찍히는 것이 신호이지 한 번 찍히는 것이 신호가 아니다.**

**⑤ 치우기 `DELETE`에 상한이 없다 — 재 봤고 지금은 문제가 아니다.** 보관 기간이 지난 줄을
한 문장으로 지운다. 시한(`socketTimeout` 10초)에 걸리면 트랜잭션이 통째로 되돌아가고 다음
주기가 같은 크기를 다시 시도하는데 그 사이 표는 더 커진다 — **한 번 못 넘기면 영영 못 넘긴다.**

| 시나리오 | 걸린 시간 |
|---|---|
| 140만 줄(PRD 추산 상한)을 한 문장으로 | **328~468 ms** |
| 같은 것을 **커밋까지** | **316 ms** |
| 정상 운영(보관 기간이 지난 한 주기치) | **9~63 ms** |

**10초까지 20배 넘는 여유**(366ms 기준 27배)다. 넘기려면 대략 **5,600만 줄** — 추산의 40배다.
**고리는 실재하지만 진입점이 없어** 배치 자르기를 안 넣었다. **안 잰 것 셋**: 로컬 디스크(운영
스토리지가 아니다) · 다른 부하와 겹칠 때 · 인덱스 정리(autovacuum 몫이라 이 시간에 안 들어간다).
표가 이 규모의 열 배를 넘기 시작하면 다시 잰다.

**⑥ 검사 하나의 바닥값이 미확인 전제 위에 있다.** 집계 여유(`RECEIVED_SLACK`, 5분)를 지키는
검사가 「전달 지연 65초」로 바닥을 잡는데, 그 65초는 수집 서버의 `reconnect-max-delay`(60초)에서
왔다. **그 값은 재시도 사이 백오프 상한이지 총 단절 시간의 상한이 아니고**(`ReconnectPolicy`에
시도 횟수 상한이 없다), 치지직이 재연결 때 놓친 채팅을 되돌려 주는지도 **아무도 모른다** —
`chat-collector`의 `CollectorRunner` 클래스 javadoc이 "끊겨 있는 동안의 채팅은 되돌릴 수 없고,
**백필이 되는지도 모른다**"라고 적어 뒀다. **65초는 「옳은 바닥」이 아니라 「근거 없는 5초보다
나은 바닥」이다.** 전달 지연을 실제로 재는 카드가 나오면 그 값으로 갈아 끼운다.

### 나머지

**네 서버에 전부 내용이 있다.** `chat-detector`가 POK-120으로 채워지면서 마지막 빈 껍데기가 없어졌다.

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
