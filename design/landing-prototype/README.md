# PokeClip 랜딩 인터랙티브 시안 (POK-269)

코드 이식 전에 레이아웃과 모션을 확정하기 위한 정적 프로토타입입니다. 웹 빌드(`web/`)와는 무관합니다.

## 실행

```bash
python3 -m http.server 8765 --directory design/landing-prototype
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

프롬프트는 `~/Downloads/PokeClip 디자인/landing/ASSET_PROMPTS.md`에 있고, 원본은 같은 폴더의 `assets/`에 있습니다.
- 원본은 2048px PNG입니다. `cwebp`로 줄여서 이 폴더에 넣었습니다.
  - 포즈: 1024px
  - 소품: 640px
  - 히어로: 2560 / 1080px
- 영상은 H.264(무음, faststart)로 줄였습니다(히어로 원본 4K 8MB → 1080p 1.0MB).

| 에셋 | 파일 | 위치 | 상태 |
|---|---|---|---|
| A1 히어로 키프레임 | `assets/img/hero-key-pc.webp` · `hero-key-mo.webp` | `.hero__poster` (모바일 ≤767은 mo) | 반영 — 영상 로드 전 포스터 · reduce 모드 대체 |
| A1 히어로 루프 영상 | `assets/video/hero-pc.mp4`(1920×1080, 1.0MB) · `hero-mo.mp4`(1080×1350, 0.7MB) | `.hero__video` — 재생되면 포스터 위로 페이드인, 화면 밖이면 멈춤 | 반영 (첫·끝 프레임 PSNR 46dB라 이음새 없음) |
| A4 포키 점프 | `assets/poki/poki-jump.webp`(그린 키잉 → 20fps 1회 재생 애니메이션 WebP, 516KB) | S11 진입 시 뛰어 들어와 손 흔들기 → 축하 포즈로 전환 | 반영 |
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
- SOOP 「지원」. 「준비 중」으로 씁니다.
- 「바로 공개」. 기본은 비공개 업로드입니다.
- 근거 없는 절약 수치
- 「게임 이벤트 교차검증」
- 「저작권 걱정 없음」 같은 보장 표현. 약관 13조가 「보장하지 않는다」고 적었습니다.

핫키 마킹은 서버 창구(POK-119)가 없어서 「준비 중」으로 표기합니다.

## 이용약관 · 개인정보 처리방침

랜딩(pokeclip.com)이 문서의 **원본**입니다. 대시보드(app.pokeclip.com)는 문서를 갖지 않고 `https://pokeclip.com/terms`·`/privacy`로 잇기만 합니다(`web/src/features/legal/legalInfo.ts`). Google OAuth 동의 화면의 처리방침 주소도 여기입니다. 랜딩을 SSG로 옮길 때 두 문서와 검사 스크립트를 함께 옮깁니다.

| 파일 | 역할 |
|---|---|
| `terms/index.html` | 이용약관 1.0 → `/terms` |
| `privacy/index.html` | 개인정보 처리방침 1.0 → `/privacy` |
| `legal.css` | `styles.css`의 토큰·푸터 위에 문서 레이아웃(본문 폭 760, 표만 가로 스크롤)만 얹습니다 |
| `scripts/check-legal.mjs` | 빠지면 법 위반이 되는 문장을 지킵니다. 처리방침 필수 절 16개, YouTube 정책 주소, 14세 제한, 고의·중과실 면책 금지, 목차 링크 |

```bash
node design/landing-prototype/scripts/check-legal.mjs            # 내용 검사
node design/landing-prototype/scripts/check-legal.mjs --release  # 자리표시 〔 〕가 남으면 실패 — 공개 배포 전에 돌린다
```

- **〔 〕는 공개 전에 채울 자리표시입니다.** 운영자 대표·보호책임자·저작권 침해 신고 담당자 이름과 시행일입니다.
- **처리방침은 실제 동작과 같아야 합니다.** 3절 보유 기간의 세 행(시청자 채팅·후원, 탈퇴 시 회원 콘텐츠, 연동 채널 정보)은 아직 코드가 따르지 않는 목표값입니다. 구현되기 전에는 공개 배포하지 않습니다(POK-269 「추가 범위」 출시 게이트).
- 문서를 고치면 개정 이력 표에 판을 추가하고 이전 판을 보존합니다. 가입 시각으로 그 회원에게 적용된 판을 찾는 근거라서 지우지 않습니다.
- 경로는 `/terms`(디렉터리 index)입니다. `python3 -m http.server`는 `/terms`를 `/terms/`로 돌려 보내고, 정적 호스팅도 디렉터리 index를 켜면 그대로 동작합니다.
