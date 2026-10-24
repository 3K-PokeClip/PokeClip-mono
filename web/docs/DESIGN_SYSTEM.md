# PokeClip 디자인 시스템 (`src/ui/`)

PokeClip 디자인 시스템 — 다크 우선(dark-first), 접근성을 갖춘 React 컴포넌트 모음.
앱 소스(`web/src/ui/`)에 함께 살며 `@/ui`로 직접 import한다 (별도 패키지/빌드 없음).

- **스택**: React + TypeScript(strict) + CSS Modules
- **토큰**: 손수 작성한 CSS 커스텀 프로퍼티(단일 소스) + 얇은 TS 상수 레이어
- **테마**: 다크 우선, `[data-theme]` 기반 라이트/다크 전환
- **문서**: Storybook

## 사용

```ts
// 루트 레이아웃에서 1회 — 토큰·폰트·리셋
import '@/ui/styles/global.css';

// 컴포넌트·훅
import { ThemeProvider, Button } from '@/ui';
```

컴포넌트 스타일(CSS Modules)은 각 컴포넌트가 스스로 import하므로 별도 임포트가 필요 없다.

## 개발

```bash
pnpm install
pnpm storybook     # 컴포넌트/토큰 문서 (http://localhost:6006)
pnpm typecheck     # 타입 체크
pnpm test          # 유닛/접근성 테스트
```

## 토큰 아키텍처 (3계층)

1. **Primitive** (`--pc-violet-500`, `--pc-space-4` …) — 테마 불변 원시 값
2. **Semantic** (`--pc-color-bg-surface`, `--pc-color-accent` …) — 역할 기반, 테마별 전환
3. **Component** (`--pc-button-bg` …) — 컴포넌트별 지연 도입

디자인 무드: 다크 우선(dark-first) · 인디고 메인 컬러 + 마젠타 포인트 컬러.

## 테마 (다크 · 라이트)

- **고르는 곳:** 설정 › 계정 「화면 테마」(`시스템 · 라이트 · 다크`, `features/settings/account/ThemeCard.tsx`). 기본은 **다크** — 라이트는 사용자가 켠다.
- **저장:** 이 브라우저의 `localStorage` `pc-theme`만(서버 저장 없음). 다른 탭에서 바꾸면 `storage` 이벤트로 따라온다.
- **적용:** 서버는 `<html data-theme="dark">`로 그리고, `<head>`의 init 스크립트(`ui/theme/getThemeInitScript.ts`)가 첫 페인트 전에 저장값으로 덮는다. `system`은 스크립트·`ThemeProvider`가 `prefers-color-scheme`으로 푼다 — CSS에는 OS 폴백이 없다. 키·기본값·유효값은 `ui/theme/themeConfig.ts` 한곳.
- **테마에 따라 JS로 다르게 그리지 않는다** — 서버 렌더(다크)와 어긋난다. 전부 CSS `[data-theme]`로 한다.

### CSS 작성 규칙

1. 시맨틱 토큰(`--pc-color-…`)을 먼저 쓴다. 상태색을 작은 글자·아이콘으로 쓸 땐 `--pc-color-{success,warning,info,danger}-text`(라이트에서 흰 바탕 4.5:1 이상).
2. 토큰으로 못 그리는 재질(유리·틴트·차트색)은 모듈 변수로 두고 `:global([data-theme='light']) .x { --var: … }`로 덮는다(`Dock.module.css`·`HomeScreen.module.css` `.hero`). 브랜드 색을 옅게 섞을 땐 `color-mix(in srgb, var(--pc-color-accent) N%, transparent)`.
3. 영상·썸네일 위처럼 **통째로 어두워야 하는 곳**은 요소에 `data-theme="dark"`를 달아 안의 토큰을 다크로 묶는다(편집기 무대 `PreviewCanvas.tsx`, 플레이어 설정 팝오버 — 포털이라 내용에 직접).
4. 색을 직접 박은 줄에는 같은 줄에 표식을 단다 — `/* 테마 고정 */`(두 테마 같은 색), `/* 라이트 덮음 */`(다크 값, 라이트 규칙이 덮음), `/* 라이트 값 */`(라이트 규칙 안). `ui/styles/themeGuard.test.ts`가 표식 없는 hex·`rgb(숫자)`·프리미티브 참조를 잡는다.
5. 다크 값은 바꾸지 않는다 — 라이트 대응은 라이트 규칙을 **더해서** 한다.

## 셸 · 본문 폭 (사이드바 화면)

방송·클립·설정처럼 좌측 사이드바(`Side`)를 두는 화면의 본문 규칙이다. 사이드바는 펼침 244 · 접힘 68(`--pc-shell-u`)로 폭이 바뀌고, 접힘 상태는 세 그룹이 하나(`src/stores/sidebar.ts`)를 공유한다.

- **본문 폭은 가두지 않는 것이 기본이다.** 사이드바를 접어 벌어진 176u는 본문이 그대로 쓴다. 대시보드와 재배치되는 목록(라이브, 지난 방송의 `auto-fill` 2열)은 화면 루트(`.container`)에 `max-width`를 두지 않는다 — 두면 접어서 번 폭이 오른쪽 빈 띠로만 남는다.
- **본문 패딩은 공용 토큰이다** (`src/app/shell.css`): `--pc-shell-body-pad-top`(30u) · `--pc-shell-body-pad-x`(32u) · `--pc-shell-body-pad-bottom`(96u, fixed Dock 여백). 단위는 콘텐츠 유닛 `--pc-u`다 — 본문 안 글자·카드와 같은 배율을 타야 그룹을 오가도 h1의 x 좌표가 같다. 설정은 그룹 레이아웃이, 방송은 각 화면(`LiveScreen`·`VodListScreen`)이 토큰을 적용한다.
- **읽기 폭 상한은 늘어나면 읽기 힘든 블록에만 건다.** 폼(계정 `.card`·`.withdrawRow` 720u), 고정 열 격자(알림 `.card` 860u), 설명 위주 카드 묶음(플러그인 `.stack` 860u)은 **카드 단위**로 건다. 재배치되지 않는 행 카드 목록(채널 연동·편집자 관리)은 헤더의 액션 버튼이 행 끝과 맞아야 해 **화면 루트 `.screen`에 860u**(시안 1k·1l)를 둔다 — 이 두 화면은 접어도 행이 넓어지지 않고 오른쪽 여백이 는다(2026-09-02 결정). 새 화면은 「접어서 번 폭을 콘텐츠가 쓸 수 있는가」로 가른다.
- 예외: 라이브 대시보드(1b)는 위 20u · 아래 120u를 자체로 갖는다(플레이어 상단 정렬). 좌우는 토큰을 따른다.
- 보관함(1g)은 재배치되는 9:16 그리드라 상한이 없고, 우측 상세 패널(344u)은 본문의 flex 형제로 서서 열리면 그리드가 남은 폭을 다시 채운다(`LibraryScreen.module.css`). 그래서 클립 그룹 레이아웃은 방송처럼 패딩을 주지 않고 화면이 토큰을 적용한다.
- 사이드바가 없는 화면(홈)은 `ScreenContainer`(1200u 중앙 정렬)를 쓴다 — 이 규칙의 대상이 아니다.
