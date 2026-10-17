# PokeClip 랜딩 인터랙티브 시안 (POK-269)

코드 이식 전에 레이아웃과 모션을 확정하기 위한 정적 프로토타입입니다. 웹 빌드(`web/`)와는 무관합니다.

## 실행

```bash
python3 -m http.server 8765 --directory landing
# → http://127.0.0.1:8765/
```

`file://`로 열어도 대부분 동작합니다. 다만 CDN 스크립트와 약관·처리방침의 루트 기준 경로 때문에 로컬 서버를 권장합니다.

## 모드

`index.html` `<head>`의 인라인 스크립트가 첫 페인트 전에 정합니다. 결과는 `html[data-mode]`에 들어갑니다.

| 모드 | 조건 | 동작 |
|---|---|---|
| `full` | `(min-width:1024px) and (min-height:640px) and (hover:hover) and (pointer:fine)` | Lenis 스무스 스크롤. 핀 + 스크럽(히어로·공감·감지·작동 방식·BGM·스트리머×편집자·기능 갤러리). 작동 방식과 스트리머×편집자 카드 스택은 스크롤이 멈추면 단계(카드) 자리로 스냅(휠 한 칸·방향키 두 번에 한 단계). 포키 스테이지. 고정 푸터 리빌. 최종 CTA는 한 화면에 들어올 때만 고정 |
| `lite` | 그 밖(태블릿·모바일·터치, 높이 640 미만 데스크톱) | 네이티브 스크롤. 스크럽과 진입 리빌. 공감·최종 CTA는 한 화면에 들어올 때만 가운데에서 잠깐 고정. 자동 재생 갤러리(만지면 멈춤) |
| `reduce` | `prefers-reduced-motion: reduce` | 모션 스크립트 없이 정적 상태. 마키·스피너 정지 |

URL 파라미터:
- `?mode=full|lite|reduce`: 모드를 강제합니다.

## 구조

| 파일 | 역할 |
|---|---|
| `index.html` | 섹션 11개 마크업(S1 히어로 → S11 최종 CTA)과 공통 크롬. 크롬은 글래스 pill 내비, 섹션 인디케이터, 모바일 하단 CTA 바 |
| `styles.css` | 토큰 → 베이스 → 컴포넌트 → 크롬 → 섹션 → reduce → 모션 상태(`.motion-full` / `.motion-lite`) 순서. 모바일 우선이며 768 · 1024에서 분기 |
| `main.js` | P1 기본 동작. GSAP 없이도 동작합니다(메뉴·내비·CTA 바·인디케이터·그래프·파형·단계 전환). `window.PC_LP`로 노출 |
| `motion.js` | P2 모션. GSAP 3.15 + ScrollTrigger + SplitText(cdnjs), Lenis 1.3(jsdelivr) |
| `assets/` | 폰트(`web/src/ui/assets/fonts`에서 복사), 로고(`web/public/brand`), 포키 임시 이미지, 플랫폼 로고 |

## 에셋

프롬프트는 `~/Downloads/PokeClip 디자인/landing/ASSET_PROMPTS.md`에 있습니다. 원본은 같은 폴더에 두 벌입니다.
- `assets/`: 첫 원본. 소품 9종·무대 배경·샘플 클립 3개는 지금도 이것을 씁니다.
- `assets-pocket-refined/`: **주머니 수정본(2026-10-03).** 주머니를 얕고 몸에 붙게 고친 판으로, 포즈 10종·히어로 키프레임·OG·히어로 영상·점프 영상을 이것으로 바꿨습니다. 샘플 클립도 들어 있지만 첫 원본과 내용이 같아서(PSNR 47~50dB, 포키가 안 나옴) 바꾸지 않았습니다.

변환 규칙입니다. 원본보다 크게 키우지 않습니다(수정본 README: 「임의 확대하지 않았습니다」).
- 포즈는 `cwebp`로 1024px(듀오 1536×864) 투명 WebP, 소품은 640px입니다.
- 히어로 키프레임은 PC가 원본 크기 1672×941, 모바일이 1080×1350입니다. OG는 1200×630 JPG입니다.
- 영상은 H.264(무음, faststart, CRF 23)입니다. 히어로 PC는 원본 크기 1672×942(0.87MB), 모바일은 1080×1350(0.81MB)입니다. 첫·끝 프레임 PSNR이 44dB라 반복 이음새가 보이지 않습니다.
- 점프는 그린 배경 `#01b140`을 키잉(`chromakey` + `despill`)한 뒤 960×540·20fps로 줄여 `img2webp`로 1회 재생 애니메이션 80프레임을 만들고, 마지막 프레임을 `poki-jump-end.webp`로 따로 둡니다.

| 에셋 | 파일 | 위치 | 상태 |
|---|---|---|---|
| A1 히어로 키프레임 | `assets/img/hero-key-pc.webp` · `hero-key-mo.webp` | `.hero__poster` (모바일 ≤767은 mo) | 반영 — 영상 로드 전 포스터 · reduce 모드 대체 |
| A1 히어로 루프 영상 | `assets/video/hero-pc.mp4`(1672×942, 0.87MB) · `hero-mo.mp4`(1080×1350, 0.81MB) | `.hero__video` — 재생되면 포스터 위로 페이드인, 화면 밖이면 멈춤 | 반영 — 주머니 수정본 (첫·끝 프레임 PSNR 44dB라 이음새 없음) |
| A4 포키 점프 | `assets/poki/poki-jump.webp`(그린 키잉 → 20fps 1회 재생 애니메이션 WebP, 525KB) | S11 진입 시 뛰어 들어와 손 흔들기 → 축하 포즈로 전환 | 반영 — 주머니 수정본 |
| A5 샘플 클립 | `assets/video/clip-{arena,race,soccer}.mp4`(720×1280) + 정점 프레임 `assets/img/clip-*-peak.webp` | S8 쇼츠 폰 3대(재생) · S4 편집기 미리보기(재생) · S3 점프카드 · S4 라이브/업로드 썸네일(정지 프레임) | 반영 — 가까워질 때 불러와 재생 |
| P2 worried | `assets/poki/poki-worried.webp` | S2 · 스테이지 `worried` | 반영 |
| P3 detect | `assets/poki/poki-detect.webp` | S3 · 스테이지 `detect` | 반영 |
| P4 / P4b run | `poki-run.webp` · `poki-run-2.webp` | S4 진행선 라이더(2프레임 교대) | 반영 |
| P5 headphones | `assets/poki/poki-headphones.webp` | S5 · 스테이지 `headphones` | 반영 |
| P6 upload | `assets/poki/poki-upload.webp` | S8 플랫폼 제목 위 | 반영 |
| P7 cheer | `assets/poki/poki-cheer.webp` | S11 최종 CTA | 반영 |
| P8 duo | `assets/poki/poki-duo.webp` | S6 · 스테이지 `duo` | 반영 |
| P9 peek | `assets/poki/poki-peek.webp` | S10 FAQ 목록 위 | 반영 |
| P1 wave | `assets/poki/poki-wave.webp` | (예비) | 미사용 |
| 소품 | `assets/props/*.webp` | 말풍선 3종 → S3. 파형 → S7 음량 카드. 클립·재생 카드와 반짝이 → S11. 말풍선·클립·재생 카드·파형·반짝이 → S8 마키 띠 스티커 | 반영 (헤드폰·키캡은 미사용) |
| A7 무대 배경 | `assets/img/bg-stage.webp` | S3 배경 | 반영 |
| A6 OG | `assets/img/og.jpg`(원본 `og-base.jpg`) | `og:image` · `twitter:image` | 반영 — 원본 왼쪽에 로고·히어로 문구를 합성(`scripts/og/render.mjs`) |
| 스티커 그리드 | — | — | 회색 배경을 지워야 해서 보류(마키 띠는 소품 이미지로 대체) |

## 카피 가드레일

위키와 코드 기준입니다. 다음 표현은 쓰지 않습니다.
- AI 자막
- 「원클릭 자동 업로드」처럼 사람 손 없이 올라간다는 표현. 「영상 만들기」를 누르면 렌더 직후 YouTube에 올라간다(4단계 제목 「영상 만들기 한 번에, YouTube까지」, ADR-091)까지만 씁니다 — 업로드 정보(제목·설명·공개 범위)는 사람이 정합니다
- 근거 없는 절약 수치
- 「게임 이벤트 교차검증」
- 「저작권 걱정 없음」 같은 보장 표현. 약관 13조가 「보장하지 않는다」고 적었습니다.

**공개 시점 기준으로 바꾼 것 (2026-10-03)** — 지금 코드와 다르므로, 랜딩을 공개하기 전에 아래가 먼저 사실이 돼야 합니다.
- **SOOP 「지원」**: 사실 요약·FAQ·`llms.txt`·구조화 데이터까지 SOOP을 지원 플랫폼으로 씁니다(2026-10-17 결정). 지금 코드는 치지직만 수집합니다.
- **채팅·후원·목소리 반응 종합 분석(S3)**: 지금 판별기(chat-detector)는 5초 창의 채팅 건수만 봅니다. 후원·음성은 판별에 쓰지 않고, 카드에 점수도 싣지 않습니다. 판별이 이 신호들을 실제로 쓰게 되기 전에는 공개하지 않습니다. 쓰게 되면 처리방침 2·11·12절(처리 항목·자동화된 결정·인공지능)도 함께 고칩니다.
- **히어로 「AI를 활용해」**: 지금 판별기는 5초 창 채팅 건수가 평소보다 튀는지 보는 통계 규칙이라 AI 모델이 아닙니다. 판별에 AI가 실제로 들어가기 전에는 공개하지 않습니다. 들어가면 처리방침 11·12절(자동화된 결정·인공지능)을 고치고, AI 기본법 31조의 사전 고지(약관·화면)를 붙입니다.
- **푸터 사업자 정보**: 무료 베타라 사업자 정보(번호·주소) 표시 의무가 아직 없습니다. 유료화 때 푸터에 붙입니다(랜딩 HTML 주석에서 옮김 — 주석도 공개 소스에 보입니다).

단축키 기록(핫키 마킹)은 개발이 끝나 「준비 중」 없이 씁니다(2026-10-03).

YouTube 업로드는 사실이 됐습니다(POK-291, 위키 ADR-091). 스트리머나 위임받은 편집자가 「영상 만들기」에서 업로드 정보(제목·설명·태그·공개 범위·아동용 여부·썸네일)를 정하면 렌더 직후 올라가고, 완성본을 확인하는 단계는 없습니다. 「감사 전에는 비공개로 잠긴다」는 2026-10-11 실측에서 틀린 것으로 드러났습니다. 고른 범위 그대로 올라가고, 공개면 구독자 알림이 갑니다. 약관 7조·처리방침 13절이 이 동작을 적습니다(2026-10-15).

## 검색 노출 (POK-349)

| 파일 | 역할 |
|---|---|
| `index.html` `<head>` | title·description·canonical(`https://pokeclip.com/`)·OG·Twitter 카드·파비콘, 네이버 소유 확인 메타(POK-353), 구조화 데이터(JSON-LD `@graph`: Organization·WebSite·SoftwareApplication·FAQPage) |
| `robots.txt` · `sitemap.xml` | 검색·답변용 크롤러는 허용, 학습용 AI 크롤러는 차단(아래 「AI 답변 엔진」) + 사이트맵 주소. 사이트맵은 현재 판 세 주소(`/`·`/terms/`·`/privacy/`)만 — 지난 판은 noindex라 넣지 않습니다 |
| `llms.txt` | AI 답변 엔진용 사실 요약과 주요 주소(아래 「AI 답변 엔진」) |
| `favicon.ico` · `icon.svg` · `apple-touch-icon.png` | web의 브랜드 아이콘(`web/src/app`)을 그대로 복사했습니다. 원본을 다시 만들면(`pnpm run build:brand`) 다시 복사합니다 |
| `assets/img/og.jpg` · `assets/brand/pokeclip-logo-512.png` | 공유 미리보기(1200×630)와 JSON-LD 로고. `node landing/scripts/og/render.mjs`가 `scripts/og/og.html`을 헤드리스 Chrome으로 찍고 web의 sharp로 줄여 만듭니다 |
| `scripts/check-seo.mjs` | 필수 메타, og:image 절대 URL, canonical·sitemap 주소 일치, 루트 정적 파일, JSON-LD 파싱, 화면 FAQ와 FAQPage 문장 일치, AI 크롤러 정책(학습용 차단·답변용 허용), 사실 요약이 FAQ 첫 문항·`SoftwareApplication.description`·`llms.txt`에서 같은지, 공개 파일(README·`scripts/`·`legal-versions.json` 밖)에 내부 티켓 번호(`POK-…`)가 없는지 — 주석도 소스 보기로 보입니다 |

```bash
node landing/scripts/check-seo.mjs
node --test 'landing/scripts/*.test.mjs'   # archive 회귀 테스트(임시 디렉터리에서 돈다)
```

- 구조화 데이터에는 보이는 본문에 있는 것만 씁니다. 가격 0원의 근거는 S9 「지금은 무료 베타예요」이고, FAQPage는 S10 FAQ와 글자까지 같습니다. FAQ를 고치면 JSON-LD도 같이 고칩니다(`check-seo`가 잡습니다).
- SoftwareApplication은 평점·리뷰가 없어 Google 리치 결과 자격이 없습니다. 리뷰를 지어 넣지 않고 기계 판독용으로 둡니다.
- 대시보드(`app.`·`dev.`)는 web 루트 metadata가 noindex를 냅니다. `robots.txt` Disallow로 막지 않습니다 — 막으면 크롤러가 noindex를 읽지 못해 주소만 색인될 수 있습니다.
- 시작 버튼은 `https://app.pokeclip.com/login`으로 바로 갑니다(시안 때의 안내 토스트는 지웠습니다).
- 배포 뒤에만 되는 일(Search Console·Bing 등록, 카카오톡·페이스북 미리보기, 슬래시 없는 주소의 301 확인)은 POK-349에 남깁니다.
- 네이버 서치어드바이저(POK-353): 발급받은 확인 메타 `naver-site-verification`을 홈 `<head>`에 넣어 두었습니다. 배포 뒤 서치어드바이저에서 소유 확인 → robots.txt 검증 → 사이트맵(`https://pokeclip.com/sitemap.xml`) 제출 → 수집 요청 순으로 합니다. 메타를 지우면 확인이 풀립니다(`check-seo`가 잡습니다).

### AI 답변 엔진 (POK-350)

ChatGPT 검색·Perplexity·Google AI 개요·네이버 AI 브리핑 같은 엔진이 PokeClip을 사실대로 설명하고 랜딩을 출처로 인용하게 합니다.

- **사실 요약은 FAQ 첫 문항**입니다(「PokeClip(포케클립)은 어떤 서비스인가요?」). 같은 문장을 JSON-LD `SoftwareApplication.description`과 `llms.txt`에도 씁니다. 셋 중 하나만 고치면 `check-seo`가 잡습니다. 히어로 아래에는 두지 않습니다.
- 사실 요약·FAQ·`llms.txt`에는 **지금 사실만** 씁니다. 판별은 채팅 통계 규칙이고, 후원·음성은 판별에 쓰지 않습니다. 위 「공개 시점 기준으로 바꾼 것」의 문장은 여기에 옮기지 않습니다. 예외는 SOOP 지원 하나입니다(2026-10-17 결정).
- FAQ 답의 첫 문장에 결론을 둡니다. 모든 문장은 JS 없이 받은 HTML에 있어야 하고, 크롤러에게만 다른 내용을 보여 주지 않습니다(클로킹 금지).
- `robots.txt` AI 크롤러 정책(이름은 각 사 문서로 확인, 2026-10-15):

| 크롤러 | 쓰임 | 정책 |
|---|---|---|
| `GPTBot`(OpenAI) · `ClaudeBot`(Anthropic) · `CCBot`(Common Crawl) · `Applebot-Extended`(Apple) | 모델 학습 | 막음 |
| `OAI-SearchBot` · `ChatGPT-User` | ChatGPT 검색 · 사용자 요청 | 허용 |
| `PerplexityBot` · `Perplexity-User` | Perplexity 검색 · 사용자 요청 | 허용 |
| `Claude-SearchBot` · `Claude-User` | Claude 검색 · 사용자 요청 | 허용 |
| `Google-Extended` | Gemini 학습과 Gemini 앱·Vertex AI grounding. Google 검색 노출·순위와는 무관 | 허용(2026-10-15 결정 — 막으면 Gemini 답변 인용이 줄 수 있다) |
| `Googlebot` · `bingbot` · `Yeti`(네이버) · `Applebot` | 검색 | 허용 |

  `ChatGPT-User`·`Perplexity-User`는 사용자가 요청한 방문이라 robots.txt를 따르지 않을 수 있습니다(각 사 문서). 막는 수단으로 기대하지 않습니다.
- `llms.txt`는 제안 단계 표준이고 주요 AI 검색 가운데 쓴다고 밝힌 곳이 없습니다. 비용이 작아 사실 요약과 주소만 둡니다.
- 측정: 같은 질문을 ChatGPT·Perplexity·Google·네이버에 던져 언급·인용·설명 정확성을 공개 전과 공개 4주 뒤에 POK-350 댓글로 남깁니다.

## 사용 통계 (POK-356)

`analytics.js`가 소개 페이지와 약관·처리방침 페이지에서 PostHog를 켭니다. 대시보드와 **한 프로젝트·같은 설정**이라 소개 페이지에서 대시보드로 넘어간 방문이 한 사람으로 이어집니다(`.pokeclip.com` 쿠키 공유). 설정·결정·운영 절차의 정본은 [`web/docs/ANALYTICS.md`](../web/docs/ANALYTICS.md)입니다.

- **켜는 조건**: 파일 안 `KEY`(PostHog 프로젝트 공개 키 `phc_…`)가 있고, 주소가 `pokeclip.com`·`www.pokeclip.com`일 때만. 그 밖(로컬 `http.server`·미리보기)에서는 SDK를 내려받지도 않습니다. 키는 2026-10-17에 넣었습니다(US 프로젝트 — `us-assets.i.posthog.com/array/<키>/config`가 200, EU는 404로 확인). 공개 키라 코드에 둡니다. 개인 API 키(`phx_…`)는 절대 넣지 않습니다.
- **SDK는 jsDelivr의 `posthog-js@1.435.8/dist/array.js`를 무결성 해시(SRI)와 함께** 불러옵니다. 다른 외부 스크립트와 같은 규칙입니다. 대시보드의 `posthog-js`와 같은 판을 쓰고, 올릴 때는 둘을 함께 올린 뒤 해시를 다시 계산합니다.
  ```bash
  curl -sL https://cdn.jsdelivr.net/npm/posthog-js@<판>/dist/array.js | openssl dgst -sha384 -binary | openssl base64 -A
  ```
- 자동 수집·녹화는 끄고, 주소의 쿼리·해시는 보내기 전에 뗍니다(`before_send`). 홍보 링크의 `utm_*`는 SDK가 먼저 따로 뽑아 두므로 남습니다.
- **설정을 바꾸면 처리방침 2·7·9절을 먼저 고칩니다.** 처리방침이 실제 동작과 다르면 위반입니다(아래 「문서 쓰는 원칙」).

## 홍보 링크와 UTM (POK-359)

홍보 링크에는 **꼭 UTM 꼬리표를 붙입니다.** 카카오톡·디스코드 같은 앱 안에서 누른 링크는 직전 페이지 주소가 남지 않아, 꼬리표가 없으면 PostHog에서 「직접 방문($direct)」으로 뭉개집니다. 사용 통계(PostHog)는 꼬리표를 주소에서 떼어 따로 저장하고(그 회원의 「처음 유입」으로도 남김), 주소의 나머지 쿼리는 보내기 전에 지웁니다.

| 꼬리표 | 값(영문 소문자 snake_case만) | 뜻 |
|---|---|---|
| `utm_source` | `youtube` · `chzzk` · `soop` · `discord` · `kakao` · `x` · `naver` · `instagram` | 어디서 왔나(플랫폼·서비스) |
| `utm_medium` | `video_description`(영상 설명) · `profile`(채널·프로필 소개) · `community`(커뮤니티 글) · `post`(SNS 글) · `dm`(1:1 메시지) · `ad`(광고) | 그 안의 어떤 자리인가 |
| `utm_campaign` | `launch_2026_10`(출시) · 이후 `<yyyy>_<mm>_<이름>` | 어떤 홍보 묶음인가 |
| `utm_content` | 선택. 같은 자리에 링크가 여럿일 때 `pinned_comment`·`banner`처럼 고정값 | 같은 자리 안의 구분 |

```text
https://pokeclip.com/?utm_source=chzzk&utm_medium=profile&utm_campaign=launch_2026_10
```

- **사람을 알아볼 수 있는 값은 넣지 않습니다** — 스트리머 이름·채널명·이메일·받는 사람 ID 대신 위 고정값만 씁니다. 꼬리표 값은 분석 도구에 그대로 저장됩니다.
- 홍보 링크는 **랜딩**(`https://pokeclip.com/`)을 가리킵니다. 대시보드로 바로 보내면 랜딩 → 시작 버튼 단계가 빠집니다. 랜딩 안의 시작 버튼 링크에는 꼬리표를 붙이지 않습니다(처음 유입을 덮어씁니다).
- 새 값이 필요하면 이 표부터 고칩니다. 같은 뜻을 다른 철자로 쓰면(`yt`·`youtube`) PostHog에서 따로 셉니다.

**홍보 링크 목록** — 링크를 걸 때마다 한 줄씩 더합니다. 아직 걸지 않은 링크는 「예정」.

| 채널 | 자리 | 링크 | 건 날 | 담당 | 상태 |
|---|---|---|---|---|---|
| (예) 치지직 | 채널 소개 | `https://pokeclip.com/?utm_source=chzzk&utm_medium=profile&utm_campaign=launch_2026_10` | — | — | 예정 |

### PostHog 화면 (출시 첫날부터 보는 것)

콘솔에서 만들어 두고 링크를 Jira POK-359에 남깁니다. 시간대는 **Asia/Seoul**, 공개 공유 링크는 만들지 않습니다(팀원 권한으로만).

| 화면 | 정의 |
|---|---|
| 웹 분석(기본 제공) | 일별 방문자, 유입 경로(`utm_source`·`utm_medium`·`$referring_domain`), 기기(`$device_type`: Mobile·Desktop·Tablet), 국가. 랜딩과 대시보드는 `$host`로 나눠 본다 |
| 가입 퍼널 | `$pageview`(`$host` = pokeclip.com) → `landing_cta_clicked` → `login_completed` → `platform_linked` → `pairing_code_issued` → `video_render_requested`. 사람 기준·순서대로·전환 기간 14일. **처음 유입**(`$initial_utm_source`)과 기기로 나눠 본다 |
| 주간 재방문 | 시작 `login_completed`, 재방문 대시보드 `$pageview`(`$host` = app.pokeclip.com), 주 단위 8주 |
| 출시 대시보드 | 위 셋 + `onboarding_tour_finished` 결과 분포 + `platform_unlinked`·`editor_invited` 추이 |

대시보드 설명에 그대로 적어 둡니다.

- **가입자·연동·영상 수는 DB가 정본**입니다. 여기 숫자는 광고 차단기·추적 금지 설정·무료 한도 때문에 더 적게 나옵니다. 비율과 흐름을 보는 화면입니다.
- 「가입」은 사람별 **첫** `login_completed`(PostHog 기준)입니다. PostHog를 붙이기 전에 가입한 회원도 처음 로그인할 때 여기 잡힙니다.
- 편집자는 연동·페어링 단계를 거치지 않으니 퍼널 중간에서 빠지는 것이 정상입니다.
- 이벤트 정의는 [`web/docs/ANALYTICS.md`](../web/docs/ANALYTICS.md) 「이벤트」(POK-357)입니다.

## 이용약관 · 개인정보 처리방침

랜딩(pokeclip.com)이 문서의 **원본**입니다. 대시보드(app.pokeclip.com)는 문서를 갖지 않고 `https://pokeclip.com/terms/`·`/privacy/`로 잇기만 합니다(`web/src/features/legal/legalInfo.ts`). Google OAuth 동의 화면의 처리방침 주소도 여기입니다. 랜딩을 SSG로 옮길 때 두 문서와 검사 스크립트를 함께 옮깁니다.

| 파일 | 역할 |
|---|---|
| `terms/index.html` | 이용약관 현재 판 → `/terms/` |
| `privacy/index.html` | 개인정보 처리방침 현재 판 → `/privacy/` |
| `terms/<YYYY-MM-DD>/index.html` | 지난 판(그 시행일 판을 얼려 둔 복사본) → `/terms/2026-10-15/`. 처리방침도 같은 모양 |
| `legal-versions.json` | **판 목록의 정본입니다.** 문서별로 새 판이 맨 앞이고, 시행일 드롭다운이 이 목록을 그립니다 |
| `scripts/legal-versions.mjs` | 모든 판의 드롭다운을 목록대로 다시 쓰고, 개정 때 지금 판을 얼려 둡니다(`archive`). 얼릴 때 그 판의 대표 주소(canonical)를 그 판 주소로 바꾸고 `noindex`를 단 **뒤** 드롭다운 블록을 뺀 본문의 sha256을 목록에 적어, 나중에 본문이 바뀌면 `--check`가 실패합니다. 회귀 테스트는 `legal-versions.test.mjs` |
| `legal.css` · `legal.js` | 문서 레이아웃과 시행일 드롭다운입니다. `legal.js`는 바깥 클릭·Esc로 닫기만 합니다. 드롭다운은 `<details>`라 JS 없이도 열립니다 |
| `scripts/check-legal.mjs` | 빠지면 법 위반이 되는 문장을 지킵니다. 처리방침 필수 절 17개, YouTube 정책 주소, YouTube 연동 해제 시 Google 권한 취소·30일 갱신·7일 삭제 문장, 12절의 「Google API를 AI NCII에 쓰지 않음」과 13절의 권한별 용도(Google 로그인·youtube.upload·youtube.readonly — Google 브랜드 확인 반려 대응), 치지직·SOOP 약관 준수, 플랫폼별 연동 해제 문장(치지직은 보관 기간대로, SOOP은 지체 없이 파기), 분석 도구(PostHog) 위탁·국외 이전·거부 방법과 13절의 「Google 사용자 데이터는 분석 도구에 보내지 않음」, 바뀐 약속의 옛 문장(탈퇴 후 5일·확인 뒤 업로드·해제해도 남는 Google 권한·분석 도구를 안 쓴다던 9절)이 없는지, 14세 제한, 고의·중과실 면책 금지, 목차 링크, 드롭다운과 목록의 일치, 본문 시행일 문장과 목록의 일치, 얼린 판의 본문 지문, HTML에 내부 메모(🔴·「목표값」)가 없는지 |

```bash
node landing/scripts/check-legal.mjs            # 내용 검사
node landing/scripts/check-legal.mjs --release  # 자리표시 〈 〉가 남으면 실패 — 공개 배포 전에 돌린다
```

**개정하는 법** — 토스 약관처럼 제목 아래 시행일 드롭다운에서 지난 판을 고를 수 있습니다. 지난 판은 **지우지도 고치지도 않습니다.** 가입 시각으로 그 회원에게 적용된 판을 찾는 근거입니다.

```bash
node landing/scripts/legal-versions.mjs archive terms 2026.11.01
# ① 지금 판(예: 2026.10.15)을 terms/2026-10-15/index.html로 얼려 둔다 — 대표 주소도 그 판 주소로 바꾸고,
#    검색 결과에 현재 판 대신 나오지 않게 noindex를 단다(얼린 뒤 손으로 넣으면 지문 검사가 깨진다)
# ② legal-versions.json 맨 앞에 2026.11.01을 올리고 모든 판의 드롭다운을 다시 쓴다
#    (지난 판에는 「이 약관은 ○○부터 ○○ 전까지 적용된 지난 판입니다 · 현재 판 보기」 안내가 붙는다)
# ③ 그다음 terms/index.html 본문과 본문 속 시행일 문장(부칙)을 고치고 check-legal을 돌린다
```

- 드롭다운 블록(`legal-versions:start`~`end` 사이)은 손으로 고치지 않습니다. 목록을 고쳤으면 `node landing/scripts/legal-versions.mjs`로 다시 씁니다.
- 시행일이 자리표시인 판은 `archive`가 거절합니다. 공개 전에 첫 판의 시행일을 `legal-versions.json`과 본문에 함께 채웁니다.
- 자산 경로는 루트 기준(`/styles.css`, `/assets/...`)입니다. 지난 판이 한 단계 깊은 폴더에 있어도 그대로 열립니다. 그래서 문서 페이지는 `file://`로는 깨지고, 로컬 서버로 열어야 합니다.
- **〈… 확정 후 기재〉·〈… 지정 후 기재〉는 공개 전에 채울 자리표시입니다.** 운영자 법인명·대표자명, 개인정보 보호책임자, 복제·전송 중단 요청 담당자, 시행일입니다. 채울 때 뒤 조사(은·는, 이·가)를 이름에 맞추고, 법인명이 정해지면 세 페이지 푸터의 「운영 PokeClip 팀」도 함께 고칩니다.
- 경로는 `/terms/`(디렉터리 index)이고 **끝 슬래시 꼴이 정본**입니다. canonical·sitemap·푸터·대시보드 링크가 모두 이 꼴입니다. `python3 -m http.server`는 `/terms`를 `/terms/`로 돌려 보내고, 정적 호스팅에서는 슬래시 없는 주소를 301로 보내게 합니다(Google OAuth 동의 화면 주소도 이 꼴로 맞춥니다).
- **`landing/` 폴더를 통째로 정적 호스팅에 올리지 않습니다.** 이 README와 `scripts/`까지 공개됩니다. 배포에는 HTML·CSS·JS·`assets/`와 루트의 `robots.txt`·`sitemap.xml`·`llms.txt`·`favicon.ico`·`icon.svg`·`apple-touch-icon.png`만 올립니다.

### 문서 쓰는 원칙 (약관·처리방침 머리 주석에서 옮김)

HTML 주석도 소스 보기로 그대로 보이므로 문서 안에는 내부 메모를 두지 않습니다. `check-legal.mjs`는 🔴나 「목표값」이 HTML(지난 판 포함)에 남아 있으면 실패합니다.

- **실제 동작만 적습니다.** 보관 기간·업로드 방식은 코드 값과 같아야 합니다. 처리방침을 실제 동작과 다르게 쓰면 그 자체가 위반입니다. 항목·기간을 바꾸려면 코드부터 바꾸고 문서를 따라 고칩니다(처리방침 개정은 시행 7일 전에 공지).
- **「일체 책임지지 않는다」를 쓰지 않습니다.** 고의·중과실 면책은 약관규제법 제7조로 무효입니다.
- **가입은 로그인 화면 고지 + 로그인으로 성립합니다**(체크박스 없음). 지난 판을 지우지 않는 것이 동의의 증거입니다.
- 처리방침 항목 순서는 개인정보위 「개인정보 처리방침 작성지침」(2025-04)을 따릅니다. 13절에는 YouTube API Services Developer Policies III.A.2가 요구하는 고지를 한곳에 모읍니다. 기기에 쿠키를 두고 정보를 모은다는 고지(III.A.2.g, 2026-10-15판)도 13절에서 9절·7절로 잇습니다.
- 분석 도구(PostHog, 2026-10-17 POK-354)는 **정당한 이익 + 처리방침 고지**로 처리하고 동의 창을 두지 않습니다. 거부 수단은 브라우저 추적 금지(Do Not Track) 존중과 스크립트 차단입니다. 지역은 미국, IP는 저장하지 않습니다(콘솔 설정). 국외 이전을 처리방침 공개(제28조의8 ①3호)로 보는 해석에는 위험이 남습니다 — 법무 검토에서 동의가 필요하다고 나오면 동의 창을 붙이고 2절 「동의를 받아 처리하는 항목은 현재 없습니다」를 고칩니다. 7절의 PostHog 연락처(privacy@posthog.com)·법인명(PostHog Inc.)은 [PostHog 개인정보처리방침](https://posthog.com/privacy)에서 2026-10-17에 확인했습니다.
- 처리방침 14절은 치지직·SOOP에서 받는 정보·목적·권한을 한 표로 쓰되, 연동을 해제한 뒤의 처리는 각 플랫폼 약관을 따라 나눕니다(2026-10-15).
  - SOOP 개발자 이용약관(2026-08-24 시행본): 제11조 ⑤(필요한 권한만), ④(판매·제3자 제공 금지), ⑰(동의 철회를 주기적으로 확인하고 지체 없이 파기), ⑭(탈퇴 시 모든 개인정보 파기), ③(받은 정보를 사전 승낙 없이 복제·사용 금지). 그래서 해제하면 바로 지웁니다.
  - 치지직 개발자 센터 이용약관(2024-12-19 시행본): 최종 사용자 데이터 의무가 제7조 ① 법령 준수, ② 11호(토큰 제3자 공유 금지)·12호 마(개인정보 침해 금지)뿐이고 보관 기한·해제 시 삭제 의무가 없습니다. 그래서 ADR-090대로 해제해도 받은 지 60일까지 둡니다. 한계는 개인정보 보호법 제21조(목적에 필요한 기간)입니다.
- 처리방침 3·13절의 YouTube 보관 기준은 YouTube API Services Developer Policies(2026-09-14판)를 따릅니다. III.E.4.c(토큰 밖 API 데이터는 30일마다 갱신하거나 삭제), III.D.2.c(서비스 안에서 해제하면 토큰을 바로 revoke하고 7일 안에 삭제, 갱신할 수 없는 사용자의 데이터는 삭제), III.E.4.g(탈퇴하면 7일 안에 삭제)입니다. 위키 법무 검토의 「III.D.2.b」는 옛 번호입니다.

### 🔴 문서에 목표값으로 쓴 것 — 공개 배포 전에 사실이 돼야 합니다

처리방침이 실제 동작과 다르면 그 자체가 위반입니다(개인정보 보호법 제30조). 플랫폼 정책이 요구하는 보관 기준은 2026-10-15 결정으로 **문서를 먼저 정책대로 고치고, 코드가 못 따라가는 곳을 이 표로 관리합니다**(위 「코드부터 바꾸고 문서를 따라 고친다」의 예외). **이 표가 빌 때까지 랜딩을 공개 배포하지 않고, Google OAuth 검증·YouTube API 감사에도 내지 않습니다.**

| 문서 | 목표값 | 지금 코드 — 고칠 곳 | 근거 |
|---|---|---|---|
| 처리방침 3·13절 | YouTube 채널 ID·이름을 연동 중 30일마다 다시 받아 갱신 | 연동 때 한 번 받고 다시 받지 않습니다(auth `V109`). 매일 도는 철회 점검(`YoutubeRevocationCheckScheduler`)에 `channels.list(mine=true)`를 붙여 갱신해야 합니다 | YouTube III.E.4.c |
| 처리방침 3·13절 | YouTube 채널 ID·이름을 서비스 안 해제·탈퇴 뒤 7일 안에, Google 철회·갱신 불가 뒤 30일 안에 삭제 | 해제·탈퇴·갱신 거부(`REFRESH_REJECTED`) 뒤에도 `youtube_channel_links`의 `channel_id`·`channel_name`이 남습니다(NOT NULL, 행 유지). auth 정리 작업과 마이그레이션이 필요합니다 | YouTube III.D.2.c · III.E.4.g |
| 처리방침 3·13절 | 업로드한 YouTube 영상 ID를 30일마다 확인해 갱신(지워진 영상은 삭제), 해제·탈퇴 뒤 7일·철회·갱신 불가 뒤 30일 안에 삭제 | `clip_uploads.youtube_video_id`를 갱신하지 않고, 해제해도 지우지 않습니다(탈퇴 때만 지움). clip에 `videos.list` 갱신 작업, auth→clip 해제 알림, `ck_clip_uploads_video` 제약 변경이 필요합니다 | YouTube III.E.4.c · III.D.2.c |
| 처리방침 13절 · 약관 제9조 | 서비스에서 YouTube 연동을 해제하면 Google에 권한 취소(revoke)를 바로 요청 | 앱 안 해제 때 revoke를 보내지 않습니다. **ADR-052가 일부러 막았습니다** — 구글 revoke는 「그 계정이 이 프로젝트에 준 동의 전부」를 끊어, 해제 직후 다시 연동한 grant까지 죽인다는 실측 때문입니다. 정책과 충돌하므로 고쳐야 하고, revoke가 끝날 때까지 재연동을 막는 식의 보완이 필요합니다 | YouTube III.D.2.c |
| 처리방침 10절 | YouTube에서 받은 정보의 삭제 요구를 7일 이내에 처리 | 요구를 받아 지우는 운영 절차가 정해져 있지 않습니다 | YouTube III.E.4.g |
| 처리방침 3절 | 치지직 채널 ID·이름을 해제 뒤 60일 보관 후 삭제, 탈퇴하면 그 채널 정보를 지운 뒤 지체 없이 삭제 | 해제·탈퇴 뒤에도 `chzzk_channel_links`에 영구히 남습니다. 탈퇴 정리가 과거 채널 ID로 채팅을 찾으므로, 지우는 때는 그 채널 채팅이 지워진 뒤여야 합니다 | 개인정보 보호법 제21조(치지직 약관엔 상한 없음) |
| 처리방침 14절 | 치지직 연동을 해제하면 그 채널 정보를 더 받지 않음 | 해제 뒤 진행 중 수집이 멈추는 것은 치지직의 `revoked` 이벤트에 기댑니다(미확인) | 처리방침 14절 |
| 처리방침 3·14절 | SOOP 해제·동의 철회·탈퇴 시 지체 없이 파기, 받은 정보는 SOOP이 승낙한 범위에서 보관(받은 지 60일) | SOOP 연동 코드가 없습니다(SOOP API 심사 제출용 문장). 연동을 붙일 때 철회 확인·지체 없는 파기를 구현하고, 심사(제휴 제안)에서 보관 승낙(채팅 받은 지 60일)을 받아야 합니다 | SOOP 제11조 ③ · ⑭ · ⑰ |
| 처리방침 3절 · 약관 제8조 | 다시보기·클립·썸네일로 올린 이미지를 60일 뒤 삭제 | 코드는 조회만 막고(`vod_expires_at`) 파일 삭제는 창고 수명 규칙에 맡깁니다. 지금 수명 규칙은 dev 창고 `pokeclip-clips-2557`(버킷 전체 60일, 썸네일 이미지 포함)뿐이고 운영 창고는 아직 없습니다. 운영 창고를 만들 때 녹화 조각·채팅 원본(`chat/`)까지 같은 규칙을 걸어야 합니다 | 처리방침 3절 |
| 처리방침 1·2·6·7·9·13절 | 소개 페이지·대시보드에서 PostHog로 이용 분석 정보를 모음(US, IP 저장 안 함, 브라우저 추적 금지 존중) | 랜딩은 키를 넣었습니다(2026-10-17, US 프로젝트). **대시보드는 운영 빌드 env(`NEXT_PUBLIC_POSTHOG_KEY`·`NEXT_PUBLIC_POSTHOG_HOST`)가 아직 없어 수집하지 않습니다** — 운영 빌드 자리(app.pokeclip.com)가 생기면 넣습니다. 콘솔의 **IP 저장 끄기**도 확인 전입니다(원격 설정으로는 보이지 않음). 둘 다 되면 이 행을 지웁니다 | 개인정보 보호법 제30조 |
| 처리방침 3절 | 이용 분석 기록을 수집한 날부터 1년 뒤 삭제 | PostHog 무료 플랜의 1년은 **조회 기간**이고, 그 뒤 실제로 지워진다는 보장은 문서에 없습니다(「retention is not a deletion tool」, [events retention](https://posthog.com/docs/data/events-retention)). privacy@posthog.com에 물리 삭제 여부를 확인하고, 보장이 없으면 1년 지난 기록을 지우는 운영 절차를 정합니다 | 개인정보 보호법 제21조 |
| 두 문서 | 〈 〉 자리표시(법인명·대표자명·보호책임자·담당자·시행일) | `check-legal.mjs --release`가 실패합니다 | — |
