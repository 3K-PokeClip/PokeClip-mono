# PokeClip 랜딩 인터랙티브 시안 (POK-269)

코드 이식 전에 레이아웃과 모션을 확정하기 위한 정적 프로토타입입니다. 웹 빌드(`web/`)와는 무관합니다.

## 실행

```bash
python3 -m http.server 8765 --directory landing
# → http://127.0.0.1:8765/
```

`file://`로 열어도 대부분 동작합니다. 다만 폰트 preload와 CDN 스크립트 때문에 로컬 서버를 권장합니다.

## 모드

`index.html` `<head>`의 인라인 스크립트가 첫 페인트 전에 정합니다. 결과는 `html[data-mode]`에 들어갑니다.

| 모드 | 조건 | 동작 |
|---|---|---|
| `full` | `(min-width:1024px) and (hover:hover) and (pointer:fine)` | Lenis 스무스 스크롤. 핀 + 스크럽(히어로·공감·감지·작동 방식·BGM·스트리머×편집자·기능 갤러리). 포키 스테이지. 고정 푸터 리빌 |
| `lite` | 그 밖(태블릿·모바일·터치) | 네이티브 스크롤. 핀 없이 스크럽과 진입 리빌. 자동 재생 갤러리 |
| `reduce` | `prefers-reduced-motion: reduce` | 모션 스크립트 없이 정적 상태. 마키·스피너 정지 |

URL 파라미터:
- `?mode=full|lite|reduce`: 모드를 강제합니다.
- `?clean`: 에셋 자리 라벨(노란 점선)을 숨깁니다. 리뷰용 스크린샷에 씁니다.

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
| A6 OG | `assets/img/og-base.jpg` | `og:image` | 원본 그대로(로고·카피 합성 전) |
| 스티커 그리드 | — | — | 회색 배경을 지워야 해서 보류(마키 띠는 소품 이미지로 대체) |

## 카피 가드레일

위키와 코드 기준입니다. 다음 표현은 쓰지 않습니다.
- AI 자막, 원클릭 자동 업로드
- 근거 없는 절약 수치
- 「게임 이벤트 교차검증」
- 「저작권 걱정 없음」 같은 보장 표현. 약관 13조가 「보장하지 않는다」고 적었습니다.

**공개 시점 기준으로 바꾼 것 (2026-10-03)** — 지금 코드와 다르므로, 랜딩을 공개하기 전에 아래가 먼저 사실이 돼야 합니다.
- **SOOP 「지원」**: SOOP은 심사 중이고 공개 때 함께 나갑니다. 지금 코드는 치지직만 수집합니다.
- **채팅·후원·목소리 반응 종합 분석(S3)**: 지금 판별기(chat-detector)는 5초 창의 채팅 건수만 봅니다. 후원·음성은 판별에 쓰지 않고, 카드에 점수도 싣지 않습니다. 판별이 이 신호들을 실제로 쓰게 되기 전에는 공개하지 않습니다. 쓰게 되면 처리방침 2·11·12절(처리 항목·자동화된 결정·인공지능)도 함께 고칩니다.
- **히어로 「AI를 활용해」**: 지금 판별기는 5초 창 채팅 건수가 평소보다 튀는지 보는 통계 규칙이라 AI 모델이 아닙니다. 판별에 AI가 실제로 들어가기 전에는 공개하지 않습니다. 들어가면 처리방침 11·12절(자동화된 결정·인공지능)을 고치고, AI 기본법 31조의 사전 고지(약관·화면)를 붙입니다.
- **YouTube 공개 업로드**: PokeClip 보관함에서 확인한 뒤 공개로 올립니다. Google OAuth 심사 통과가 전제이고, 지금 업로드 코드는 비공개 고정(`privacyStatus=private`)입니다. 약관 7조·처리방침 13절은 공개 업로드로 먼저 고쳐 두었습니다(목표값, 두 문서 머리 주석의 🔴).

단축키 기록(핫키 마킹)은 개발이 끝나 「준비 중」 없이 씁니다(2026-10-03).

## 이용약관 · 개인정보 처리방침

랜딩(pokeclip.com)이 문서의 **원본**입니다. 대시보드(app.pokeclip.com)는 문서를 갖지 않고 `https://pokeclip.com/terms`·`/privacy`로 잇기만 합니다(`web/src/features/legal/legalInfo.ts`). Google OAuth 동의 화면의 처리방침 주소도 여기입니다. 랜딩을 SSG로 옮길 때 두 문서와 검사 스크립트를 함께 옮깁니다.

| 파일 | 역할 |
|---|---|
| `terms/index.html` | 이용약관 현재 판 → `/terms/` |
| `privacy/index.html` | 개인정보 처리방침 현재 판 → `/privacy/` |
| `terms/<YYYY-MM-DD>/index.html` | 지난 판(그 시행일 판을 얼려 둔 복사본) → `/terms/2026-10-15/`. 처리방침도 같은 모양 |
| `legal-versions.json` | **판 목록의 정본입니다.** 문서별로 새 판이 맨 앞이고, 시행일 드롭다운이 이 목록을 그립니다 |
| `scripts/legal-versions.mjs` | 모든 판의 드롭다운을 목록대로 다시 쓰고, 개정 때 지금 판을 얼려 둡니다(`archive`) |
| `legal.css` · `legal.js` | 문서 레이아웃과 시행일 드롭다운입니다. `legal.js`는 바깥 클릭·Esc로 닫기만 합니다. 드롭다운은 `<details>`라 JS 없이도 열립니다 |
| `scripts/check-legal.mjs` | 빠지면 법 위반이 되는 문장을 지킵니다. 처리방침 필수 절 17개, YouTube 정책 주소, 치지직·SOOP 약관 준수·연동 해제 시 수집 중단과 삭제 문장, 14세 제한, 고의·중과실 면책 금지, 목차 링크, 드롭다운과 목록의 일치 |

```bash
node landing/scripts/check-legal.mjs            # 내용 검사
node landing/scripts/check-legal.mjs --release  # 자리표시 〔 〕가 남으면 실패 — 공개 배포 전에 돌린다
```

**개정하는 법** — 토스 약관처럼 제목 아래 시행일 드롭다운에서 지난 판을 고를 수 있습니다. 지난 판은 **지우지도 고치지도 않습니다.** 가입 시각으로 그 회원에게 적용된 판을 찾는 근거입니다.

```bash
node landing/scripts/legal-versions.mjs archive terms 2026.11.01
# ① 지금 판(예: 2026.10.15)을 terms/2026-10-15/index.html로 얼려 둔다 — 대표 주소도 그 판 주소로 바꾼다
# ② legal-versions.json 맨 앞에 2026.11.01을 올리고 모든 판의 드롭다운을 다시 쓴다
#    (지난 판에는 「이 약관은 ○○부터 ○○ 전까지 적용된 지난 판입니다 · 현재 판 보기」 안내가 붙는다)
# ③ 그다음 terms/index.html 본문과 본문 속 시행일 문장(부칙)을 고치고 check-legal을 돌린다
```

- 드롭다운 블록(`legal-versions:start`~`end` 사이)은 손으로 고치지 않습니다. 목록을 고쳤으면 `node landing/scripts/legal-versions.mjs`로 다시 씁니다.
- 시행일이 자리표시인 판은 `archive`가 거절합니다. 공개 전에 첫 판의 시행일을 `legal-versions.json`과 본문에 함께 채웁니다.
- 자산 경로는 루트 기준(`/styles.css`, `/assets/...`)입니다. 지난 판이 한 단계 깊은 폴더에 있어도 그대로 열립니다. 그래서 문서 페이지는 `file://`로는 깨지고, 로컬 서버로 열어야 합니다.
- **〔 〕는 공개 전에 채울 자리표시입니다.** 운영자 대표·보호책임자·저작권 침해 신고 담당자 이름과 시행일입니다.
- **처리방침은 실제 동작과 같아야 합니다.** 3절 보유 기간의 세 행(시청자 채팅·후원, 탈퇴 시 회원 콘텐츠, 연동 채널 정보)은 아직 코드가 따르지 않는 목표값입니다. 구현되기 전에는 공개 배포하지 않습니다(POK-269 「추가 범위」 출시 게이트).
- 경로는 `/terms/`(디렉터리 index)입니다. `python3 -m http.server`는 `/terms`를 `/terms/`로 돌려 보내고, 정적 호스팅도 디렉터리 index를 켜면 그대로 동작합니다.
