# 사용 통계 (PostHog)

출시 뒤 **어디서 들어와서(유입), 어떤 기기로, 무엇을 했는지** 보려고 랜딩(pokeclip.com)과 대시보드(app.pokeclip.com)에 PostHog를 붙였다(POK-354). 둘은 **한 프로젝트**라서 랜딩 방문 → 로그인 → 연동 → 영상 만들기가 한 사람으로 이어진다.

가입자·연동·영상 수 같은 **운영 숫자는 DB가 정본**이다. 광고 차단기·추적 금지·무료 한도 때문에 PostHog 숫자는 실제보다 적게 나온다. PostHog로는 비율과 흐름을 본다.

> **처리방침과 한 쌍이다.** 처리방침(`landing/privacy/index.html`) 2·3·4·6·7·9·13절이 아래 설정을 그대로 적는다. 지역·쿠키·보존 기간·IP·녹화 설정을 바꾸면 처리방침을 **먼저** 고친다(개인정보 보호법 제30조). `landing/scripts/check-legal.mjs`가 PostHog 문장이 빠지면 실패한다.

## 결정 (2026-10-17, POK-354)

| 항목         | 값                                                                                                                                                                       | 처리방침                                      |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------- |
| 지역         | **US** (`https://us.i.posthog.com`)                                                                                                                                      | 6·7절 「미국」                                |
| 처리 근거    | 정당한 이익 + 처리방침 고지. **동의 창 없음**                                                                                                                            | 2절                                           |
| 거부 수단    | 브라우저 추적 금지(Do Not Track) 존중(`respect_dnt`), 스크립트 차단                                                                                                      | 7·9절                                         |
| 쿠키         | `ph_<키>_posthog`(익명 식별자)·`ph_<키>_posthog_cpm`(서브도메인 맞춤 표시) 두 개, 1년, `.pokeclip.com`에 두어 랜딩과 대시보드가 공유 — 실측 2026-10-17                   | 9절                                           |
| 플래그 요청  | **끔**(`advanced_disable_flags`) — `/flags` 본문은 `before_send`를 거치지 않고 첫 방문 주소를 쿼리째 싣는다(실측). 원격 설정도 안 받으므로 기능은 코드 설정으로만 정한다 | —                                             |
| IP           | **저장 안 함**(콘솔 설정 — US는 기본이 저장이라 꼭 끈다)                                                                                                                 | 2절                                           |
| 자동 수집    | **끔**. 행동은 `track()`으로만(POK-357)                                                                                                                                  | 2절 「누른 단추」                             |
| 사람 프로필  | 로그인한 회원만(`person_profiles: 'identified_only'`), 식별자는 **회원 번호**                                                                                            | 2절                                           |
| 세션 녹화    | 꺼 둠. 가리기를 확인한 뒤 켠다(POK-358), 보존 30일                                                                                                                       | 2·3절                                         |
| 이벤트 보존  | 무료 플랜 1년(조회 기간)                                                                                                                                                 | 3절 — 물리 삭제 보장은 `landing/README.md` 🔴 |
| 프록시       | 안 씀                                                                                                                                                                    | —                                             |
| 탈퇴 시 삭제 | **운영자 수동**, 7일 안 — 아래 절차                                                                                                                                      | 3·4절                                         |

## 켜는 법 · 꺼짐 조건

| 곳                            | 켜는 값                                                | 비어 있으면                                                                        |
| ----------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| 대시보드 (`web/`)             | `NEXT_PUBLIC_POSTHOG_KEY` + `NEXT_PUBLIC_POSTHOG_HOST` | `posthog-js`를 import조차 하지 않는다(로컬·CI·dev 기본)                            |
| 랜딩 (`landing/analytics.js`) | 파일 안의 `KEY`                                        | SDK를 내려받지 않는다. 키가 있어도 `pokeclip.com`·`www.pokeclip.com`이 아니면 끈다 |

- `NEXT_PUBLIC_*`는 **빌드 때 굳는다.** 운영 값을 바꾸면 다시 빌드한다.
- 프로젝트 키(`phc_…`)는 공개 키다 — 브라우저에 실려도 된다. **개인 API 키(`phx_…`)는 절대 코드·env에 넣지 않는다.**
- 프로젝트는 하나다. 로컬에서 실측할 때만 `.env.local`에 키를 넣고, PostHog 화면에서는 `$host`로 걸러 본다. 출시 전 시험으로 만든 사람은 Persons에서 지운다.
- 대시보드의 `posthog-js`와 랜딩의 CDN 판은 **같은 판(1.435.8)** 으로 고정한다. 올릴 때는 둘을 함께 올리고 랜딩의 `SDK_INTEGRITY`를 다시 계산한다(`landing/README.md` 「사용 통계」).

## 코드 구조

| 파일                                     | 역할                                                                                         |
| ---------------------------------------- | -------------------------------------------------------------------------------------------- |
| `src/instrumentation-client.ts`          | hydration 전에 `initAnalytics()` 한 번                                                       |
| `src/lib/analytics/client.ts`            | 설정(`ANALYTICS_CONFIG`), 동적 import·대기열(`withAnalytics`), 식별·초기화, auth 스토어 구독 |
| `src/lib/analytics/scrub.ts`             | `before_send` — 주소 정제                                                                    |
| `src/lib/analytics/index.ts`             | 화면 코드가 가져다 쓰는 곳. 화면은 `posthog-js`를 직접 import하지 않는다                     |
| `src/components/app-shell/AuthGuard.tsx` | 보호 화면 진입 때 `identifyUser(me.id)`                                                      |
| `landing/analytics.js`                   | 랜딩 세 페이지의 같은 설정                                                                   |

SDK는 동적 import라 hydration 뒤에 붙을 수 있다(Next 16 `instrumentation-client` 「Execution timing」). 그 사이의 식별·이벤트는 대기열(최대 50개)에 두었다가 흘려보낸다. 분석이 던져도 화면은 멈추지 않는다.

## 식별 · 초기화

- **식별**: `AuthGuard`가 `/me`의 `id`로 `identify(String(id))`. 이메일·이름·사진·Google 식별자는 사람 속성에도 넣지 않는다. 랜딩에서 온 익명 기록은 이때 그 회원에게 합쳐진다.
- **로그아웃**: auth 스토어의 refreshToken이 값 → null이 되면 `reset()`. 명시 로그아웃·401 자동 로그아웃·탈퇴(`/goodbye`)·다른 탭 로그아웃이 모두 이 길이다.
- **새 로그인**인데 이미 누군가로 식별돼 있으면 먼저 `reset()` — SDK는 식별된 사람 위에 다른 번호로 identify하면 합치지 않고 번호만 바꿔서, 로그인 직후 이벤트가 앞사람에게 붙는다. 익명이면 지우지 않는다(지우면 랜딩 → 로그인이 끊긴다).
- 같은 세션의 토큰 회전·새로고침 hydrate에서는 지우지 않는다.

## 주소 정제 (`scrub.ts`)

| 대상                                                                                                | 처리               | 이유                                                                                  |
| --------------------------------------------------------------------------------------------------- | ------------------ | ------------------------------------------------------------------------------------- |
| 모든 주소·경로 속성(`$current_url`·`$referrer`·`$pathname`·`$initial_*`, `$set`·`$set_once` 안까지) | 쿼리·해시를 뗀다   | OAuth `code`·`state`, `?stream=`·`?card=`·`?recipe=`                                  |
| `/broadcast/vod/<번호>`                                                                             | `/broadcast/vod/_` | 예전 방송 번호는 스트림 키와 같다(`services/README.md` 「clip: 방송 번호와 물리 키」) |
| `/auth/callback`·`/oauth/chzzk/callback`·`/oauth/youtube/callback`의 페이지 조회                    | 보내지 않는다      | 담을 것이 없다. 그 화면에서 보낸 행동 이벤트는 남긴다(주소만 정제)                    |
| `utm_*`                                                                                             | 그대로             | SDK가 정제 전에 따로 뽑아 둔다 — 유입 분석용                                          |

## 이벤트

행동 이벤트 목록은 POK-357에서 이 절에 채운다. 규칙: 이름은 `대상_동사`(snake_case), 속성은 열거형·boolean·number만 — 이메일·채널 이름·채팅·토큰·스트림 키·페어링 코드·업로드 제목·설명은 넣지 않는다.

## 탈퇴한 회원의 기록 삭제 (운영자, 7일 안)

처리방침 3·4절은 「탈퇴하면 7일 안에 분석 도구의 그 회원 기록(이벤트·녹화)을 지운다」고 약속한다. 자동 연동은 없다(2026-10-17 결정). **주 2회 이상** 다음을 한다.

1. auth DB에서 지난 확인 뒤 탈퇴한 회원 번호를 뽑는다. 회원 줄은 익명화만 되고 `id`는 남는다.
   ```sql
   SELECT id, deleted_at FROM users
    WHERE deleted_at >= now() - interval '8 days'
    ORDER BY deleted_at;
   ```
2. PostHog → **Persons**에서 distinct ID가 그 회원 번호인 사람을 찾는다(없으면 그 회원은 식별된 적이 없다 — 할 일 없음).
3. **Delete person**에서 **이벤트와 녹화도 함께 지우기**를 고른다. 삭제는 PostHog 안에서 차례로 처리된다.
4. 지운 회원 번호와 날짜를 운영 기록에 남긴다(회원 번호 말고 다른 정보는 적지 않는다).

탈퇴가 늘면 auth 탈퇴 정리 장부(`withdrawal_purge_jobs`, `PurgeNotifier`)에 PostHog 대상을 더해 자동으로 바꾼다 — 대상 제약(`ck_withdrawal_purge_target`)을 넓히는 마이그레이션과 서버 전용 개인 API 키가 필요하다.

## PostHog 콘솔 체크리스트

- [ ] 조직·프로젝트를 **US**로 만든다(지역은 나중에 못 바꾼다)
- [ ] Settings › Project › Privacy — **IP 저장 끄기**. 끈 뒤 국가(`$geoip_country_code`)가 채워지는지 Live events로 확인한다(안 채워지면 처리방침 2절 「접속 국가」를 뺀다)
- [ ] Autocapture·Heatmaps·Surveys·Session replay 토글 끄기(코드와 같게)
- [ ] app.posthog.com/legal에서 DPA 서명(처리방침 6·7절 위탁 근거)
- [ ] 무료 한도(월 이벤트 100만·녹화 5천) 알림 메일 받는 사람 확인
- [ ] 프로젝트 키를 대시보드 운영 빌드 env와 `landing/analytics.js`에 넣는다
