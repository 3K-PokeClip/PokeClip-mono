# 사용 통계 (PostHog)

출시 뒤 **어디서 들어와서(유입), 어떤 기기로, 무엇을 했는지** 보려고 랜딩(pokeclip.com)과 대시보드(app.pokeclip.com)에 PostHog를 붙였다(POK-354). 둘은 **한 프로젝트**라서 랜딩 방문 → 로그인 → 연동 → 영상 만들기가 한 사람으로 이어진다.

가입자·연동·영상 수 같은 **운영 숫자는 DB가 정본**이다. 광고 차단기·추적 금지·무료 한도 때문에 PostHog 숫자는 실제보다 적게 나온다. PostHog로는 비율과 흐름을 본다.

> **처리방침과 한 쌍이다.** 처리방침(`landing/privacy/index.html`) 2·3·4·6·7·9·13절이 아래 설정을 그대로 적는다. 지역·쿠키·보존 기간·IP·녹화 설정을 바꾸면 처리방침을 **먼저** 고친다(개인정보 보호법 제30조). `landing/scripts/check-legal.mjs`가 PostHog 문장이 빠지면 실패한다.

## 결정 (2026-10-17, POK-354)

| 항목         | 값                                                                                                                                                                                                                                                                                                                         | 처리방침                                      |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| 지역         | **US** (`https://us.i.posthog.com`)                                                                                                                                                                                                                                                                                        | 6·7절 「미국」                                |
| 처리 근거    | 정당한 이익 + 처리방침 고지. **동의 창 없음**                                                                                                                                                                                                                                                                              | 2절                                           |
| 거부 수단    | 브라우저 추적 금지(Do Not Track) 존중(`respect_dnt`), 스크립트 차단                                                                                                                                                                                                                                                        | 7·9절                                         |
| 쿠키         | `ph_<키>_posthog`(익명 식별자)·`ph_<키>_posthog_cpm`(서브도메인 맞춤 표시) 두 개, 1년, `.pokeclip.com`에 두어 랜딩과 대시보드가 공유 — 실측 2026-10-17                                                                                                                                                                     | 9절                                           |
| 플래그 요청  | **끔** — 대시보드는 평가를 꺼(`advanced_disable_feature_flags`) `/flags` 요청이 나가지 않게 한다. 그 본문은 `before_send`를 거치지 않고 첫 방문 주소를 쿼리째 싣는다(실측). 원격 설정(토큰만 싣는 GET)은 받는다 — 녹화가 그 「녹화 켜짐」을 요구한다. 랜딩은 녹화를 안 해서 원격 설정까지 막는다(`advanced_disable_flags`) | —                                             |
| IP           | **저장 안 함**(콘솔 설정 — US는 기본이 저장이라 꼭 끈다)                                                                                                                                                                                                                                                                   | 2절                                           |
| 자동 수집    | **끔**. 행동은 `track()`으로만(POK-357)                                                                                                                                                                                                                                                                                    | 2절 「누른 단추」                             |
| 사람 프로필  | 로그인한 회원만(`person_profiles: 'identified_only'`), 식별자는 **회원 번호**                                                                                                                                                                                                                                              | 2절                                           |
| 세션 녹화    | 대시보드만, 글자·입력 전부 가림·이미지와 영상 차단(POK-358). 스위치는 둘 — env `NEXT_PUBLIC_POSTHOG_REPLAY=on`과 콘솔 「Record user sessions」. 보존 30일(무료). 아래 「세션 녹화」                                                                                                                                        | 2·3절                                         |
| 이벤트 보존  | 무료 플랜 1년(조회 기간)                                                                                                                                                                                                                                                                                                   | 3절 — 물리 삭제 보장은 `landing/README.md` 🔴 |
| 프록시       | 안 씀                                                                                                                                                                                                                                                                                                                      | —                                             |
| 탈퇴 시 삭제 | **운영자 수동**, 7일 안 — 아래 절차                                                                                                                                                                                                                                                                                        | 3·4절                                         |

## 켜는 법 · 꺼짐 조건

| 곳                            | 켜는 값                                                                 | 비어 있으면                                                                        |
| ----------------------------- | ----------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| 대시보드 (`web/`)             | `NEXT_PUBLIC_POSTHOG_KEY` + `NEXT_PUBLIC_POSTHOG_HOST`                  | `posthog-js`를 import조차 하지 않는다(로컬·CI·dev 기본)                            |
| 랜딩 (`landing/analytics.js`) | 파일 안의 `KEY`                                                         | SDK를 내려받지 않는다. 키가 있어도 `pokeclip.com`·`www.pokeclip.com`이 아니면 끈다 |
| 대시보드 세션 녹화            | 위 둘 + `NEXT_PUBLIC_POSTHOG_REPLAY=on` + 콘솔 「Record user sessions」 | 녹화하지 않는다. env는 정확히 `on`일 때만(`true` 아님)                             |

- `NEXT_PUBLIC_*`는 **빌드 때 굳는다.** 운영 값을 바꾸면 다시 빌드한다.
- 프로젝트 키(`phc_…`)는 공개 키다 — 브라우저에 실려도 된다. **개인 API 키(`phx_…`)는 절대 코드·env에 넣지 않는다.**
- 프로젝트는 하나다. 로컬에서 실측할 때만 `.env.local`에 키를 넣고, PostHog 화면에서는 `$host`로 걸러 본다. 출시 전 시험으로 만든 사람은 Persons에서 지운다.
- 대시보드의 `posthog-js`와 랜딩의 CDN 판은 **같은 판(1.435.8)** 으로 고정한다. 올릴 때는 둘을 함께 올리고 랜딩의 `SDK_INTEGRITY`를 다시 계산한다(`landing/README.md` 「사용 통계」).

## 코드 구조

| 파일                                     | 역할                                                                                         |
| ---------------------------------------- | -------------------------------------------------------------------------------------------- |
| `src/instrumentation-client.ts`          | hydration 전에 `initAnalytics()` 한 번                                                       |
| `src/lib/analytics/client.ts`            | 설정(`ANALYTICS_CONFIG`), 동적 import·대기열(`withAnalytics`), 식별·초기화, auth 스토어 구독 |
| `src/lib/analytics/scrub.ts`             | `before_send` — 주소 정제(녹화 메타 주소 포함), 녹화 속 요소 속성 가리기(`scrubAttribute`)   |
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

페이지 조회(`$pageview`)·유입·기기는 SDK가 자동으로 모은다. 그 밖의 행동은 아래 이벤트만 보낸다(POK-357). 정본은 `src/lib/analytics/events.ts`의 `AnalyticsEvents` 타입이고, **새 이벤트는 이 표부터 고친다.**

- 이름은 `대상_동사`(snake_case). 속성은 **열거형·boolean·number만** — 이메일·채널 이름·채팅·토큰·스트림 키·페어링 코드·업로드 제목·설명·태그는 넣을 자리가 없다(타입이 막는다).
- **성공한 뒤에만** 보낸다(토스트 옆). 실패·취소는 세지 않는다.
- 화면 코드는 `track(이름, 속성)`만 부른다. 분석이 꺼져 있으면 아무 일도 하지 않는다.

| 이벤트                     | 언제                                                                                                                       | 보내는 곳                                                      | 속성                                                                                                                                       |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `landing_cta_clicked`      | 랜딩 시작 버튼 7곳 클릭(비콘으로 바로)                                                                                     | `landing/analytics.js`(`a[data-cta]`)                          | `position`: `nav`·`sheet`(모바일 메뉴)·`hero`·`pricing_streamer`·`pricing_editor`·`final`·`bar`(하단 고정 바)                              |
| `login_completed`          | Google 로그인 교환 성공                                                                                                    | `features/auth/OAuthCallbackScreen.tsx`                        | 없음                                                                                                                                       |
| `platform_linked`          | 치지직·YouTube 연동 성공                                                                                                   | `features/settings/channels/{Chzzk,Youtube}CallbackScreen.tsx` | `platform`: `chzzk`·`youtube`                                                                                                              |
| `platform_unlinked`        | 연동 해제 성공                                                                                                             | `features/settings/channels/use{Chzzk,Youtube}LinkState.ts`    | `platform`                                                                                                                                 |
| `pairing_code_issued`      | 페어링 코드 발급 성공(처음·재발급)                                                                                         | `features/settings/plugin/useStreamKeyState.ts`                | 없음                                                                                                                                       |
| `onboarding_tour_finished` | 처음 사용 안내가 끝남(한 번만)                                                                                             | `features/onboarding/TourOverlay.tsx`·`WelcomeDialog.tsx`      | `outcome`: `completed`(보이는 마지막 단계에서 「다음」)·`skipped`·`auto`(가리킬 요소가 없어 저절로)·`later`(환영 창 「나중에 볼게요」·ESC) |
| `clip_editor_opened`       | 편집기 진입(편집 대상 마운트당 한 번 — 첫 저장 뒤 `?recipe=` 로 주소만 바뀌는 것은 세지 않음)                              | `features/clips/editor/studio/StudioScreen.tsx`                | `source`: `card`·`recipe`                                                                                                                  |
| `video_render_requested`   | 「영상 만들기」 주문을 **서버가 받음**(렌더·업로드 성공 아님)                                                              | `StudioScreen.tsx`의 `submitUpload`                            | `privacy_status`: `private`·`unlisted`·`public`, `thumbnail_source`: `none`·`scene`·`file`, `made_for_kids`                                |
| `editor_invited`           | 편집자 초대 성공(기한 연장 재초대 포함)                                                                                    | `features/settings/editors/useEditorSettingsState.ts`          | 없음                                                                                                                                       |
| `plugin_download_clicked`  | 플러그인 설치 파일 링크를 **누름**(받은 수 아님 — 브라우저가 막았는지 웹은 모른다, POK-379)                                | `features/settings/plugin/PluginDownloadPanel.tsx`             | `os`: `windows`·`macos`, `kind`: `installer`(exe)·`pkg`                                                                                    |
| `faq_feedback_sent`        | 도움말 · 문의에서 「이 답변이 도움이 되었나요?」에 답함(도움말에 머무는 동안 질문마다 한 번 — 새로고침·재방문은 다시 센다) | `features/settings/help/FaqList.tsx`                           | `faq`: `FaqKey`(`events.ts` 열거 = `faqItems.ts`의 고정 `key`, 문구 아님), `helpful`                                                       |

- **「가입」은 사람별 첫 `login_completed`** 로 센다(PostHog 「First time for user」). 로그인 응답에 신규 가입 표시가 없어서다. PostHog를 붙이기 전에 가입한 회원도 처음 로그인할 때 「첫」으로 잡히므로, 화면 이름은 「첫 로그인(PostHog 기준)」으로 쓴다.
- `login_completed`는 식별 전에 익명으로 나간다. 다음 화면의 `AuthGuard`가 `identify`하면 그 회원에게 합쳐진다.
- **브라우저가 모르는 일**(플러그인 연결 완료·첫 송출·하이라이트 생성·YouTube 업로드 성공)은 보내지 않는다 — DB 숫자로 본다. 서버 SDK는 이번 범위 밖이다.

## 세션 녹화 (POK-358)

편집기 같은 화면에서 사용자가 어디서 헤매는지 본다. 녹화는 화면을 다시 그릴 만큼만 담고 **사람과 비밀값은 담지 않는다**(처리방침 2절 「화면의 글자와 입력 내용을 가린 채」). 랜딩은 녹화하지 않는다.

| 대상                                                         | 처리                                                                            | 설정                                                                                    |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| 모든 글자                                                    | `*`로 가림(글자 수만 남음) — 채팅·채널 이름·이메일·업로드 제목·코드가 다 글자다 | `maskTextSelector: '*'`                                                                 |
| 모든 입력값                                                  | 가림(입력 중인 값도)                                                            | `maskAllInputs: true`                                                                   |
| 이미지·영상·캔버스·iframe                                    | 통째로 뺌(크기만 남음) — 사진 주소의 서명 토큰, 방송 화면의 얼굴                | `blockSelector`                                                                         |
| 링크·이미지 주소 속성(`href`·`src` 등)                       | 쿼리 제거, `/broadcast/vod/<번호>` → `_`                                        | `maskAttributeFn` = `scrubAttribute`                                                    |
| 글 속성(`aria-label`·`title`·`alt`·`placeholder`·`value` 등) | `*`로 가림 — 글자 가리기는 속성을 안 건드리는데 채널·회원 이름이 실린다         | `maskAttributeFn` = `scrubAttribute`                                                    |
| 녹화 시작 페이지 주소(rrweb 메타)                            | 쿼리 제거                                                                       | `before_send`(`$snapshot`)                                                              |
| 네트워크 요청                                                | 헤더·본문을 담지 않고 주소만 정제                                               | `recordHeaders`·`recordBody`·`streamNetworkBody: false`, `maskCapturedNetworkRequestFn` |
| 브라우저 콘솔 로그                                           | 담지 않음 — 새 프로젝트는 원격 설정에서 켜져 있어(2026-10-17 확인) 코드로 끈다  | `enable_recording_console_log: false`                                                   |
| 채팅 패널·영상 위 채팅·페어링 코드 상자                      | 통째로 뺌(구조·자릿수도 안 남김)                                                | `REPLAY_BLOCK_CLASS`(`ph-no-capture`)                                                   |

**켜는 순서** — 처리방침(POK-355) 머지·시행 뒤에.

1. 콘솔 Replay 설정: **URL 차단 목록**에 `/login`·`/auth/callback`·`/oauth/`·`/goodbye`를 넣는다(로그인·탈퇴 화면은 녹화하지 않는다). 보존 기간 **30일**(처리방침 3절). 표본 비율·최소 길이는 첫 주 사용량을 보고 정한다(무료 월 5천 건).
2. 콘솔에서 「Record user sessions」를 켠다.
3. 대시보드 운영 빌드 env에 `NEXT_PUBLIC_POSTHOG_REPLAY=on`을 넣고 다시 빌드한다.
4. 첫 녹화 몇 개를 재생해 글자·사진·채팅이 안 보이는지 눈으로 확인한다. 하나라도 보이면 env를 비우고 다시 빌드한다(콘솔 토글만 꺼도 새 녹화는 멈춘다).

**새 화면을 만들 때** — 글자·입력·이미지는 저절로 가려진다. 사람 정보가 **글자 아닌 모양**(배지 색·개수, 목록 줄 수)으로 드러나는 영역이면 `REPLAY_BLOCK_CLASS`를 단다. 사람 정보를 `data-*` 속성에 넣지 않는다(가리지 않는다).

**녹화는 원격 설정이 있어야 켜진다** — SDK가 원격 설정의 「녹화 켜짐」을 요구해서, 피처 플래그는 평가만 끄고(`advanced_disable_feature_flags`) 원격 설정은 받는다. `advanced_disable_flags`로 바꾸면 녹화가 영영 시작되지 않는다.

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
- [ ] Autocapture·Heatmaps·Surveys 토글 끄기(코드와 같게). Session replay는 꺼 둔 채로 시작해 「세션 녹화 · 켜는 순서」대로 켠다
- [ ] app.posthog.com/legal에서 DPA 서명(처리방침 6·7절 위탁 근거)
- [ ] 무료 한도(월 이벤트 100만·녹화 5천) 알림 메일 받는 사람 확인
- [ ] 프로젝트 키를 대시보드 운영 빌드 env와 `landing/analytics.js`에 넣는다
