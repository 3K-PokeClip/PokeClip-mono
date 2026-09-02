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

## 셸 · 본문 폭 (사이드바 화면)

방송·설정처럼 좌측 사이드바(`Side`)를 두는 화면의 본문 규칙이다. 사이드바는 펼침 244 · 접힘 68(`--pc-shell-u`)로 폭이 바뀌고, 접힘 상태는 두 그룹이 하나(`src/stores/sidebar.ts`)를 공유한다.

- **본문 폭은 가두지 않는다.** 사이드바를 접어 벌어진 176u는 본문이 그대로 쓴다. 화면 루트(`.screen`·`.container`)에 `max-width`를 두지 않는다 — 두면 접어서 번 폭이 오른쪽 빈 띠로만 남는다.
- **본문 패딩은 공용 토큰이다** (`src/app/shell.css`): `--pc-shell-body-pad-top`(30u) · `--pc-shell-body-pad-x`(32u) · `--pc-shell-body-pad-bottom`(96u, fixed Dock 여백). 단위는 콘텐츠 유닛 `--pc-u`다 — 본문 안 글자·카드와 같은 배율을 타야 그룹을 오가도 h1의 x 좌표가 같다. 설정은 그룹 레이아웃이, 방송은 각 화면(`LiveScreen`·`VodListScreen`)이 토큰을 적용한다.
- **읽기 폭 상한은 카드 단위로만 건다.** 폼(계정 카드 720u), 고정 열 격자(알림 카드 860u), 설명 위주 카드 묶음(플러그인 `.stack` 860u)처럼 늘어나면 읽기 힘든 블록에만 `max-width`를 준다. 행 카드(채널·편집자·지난 방송)는 전폭을 쓴다.
- 예외: 라이브 대시보드(1b)는 위 20u · 아래 120u를 자체로 갖는다(플레이어 상단 정렬). 좌우는 토큰을 따른다.
- 사이드바가 없는 화면(홈·클립)은 `ScreenContainer`(1200u 중앙 정렬)를 쓴다 — 이 규칙의 대상이 아니다.
