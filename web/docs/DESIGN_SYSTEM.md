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

방송·클립·설정처럼 좌측 사이드바(`Side`)를 두는 화면의 본문 규칙이다. 사이드바는 펼침 244 · 접힘 68(`--pc-shell-u`)로 폭이 바뀌고, 접힘 상태는 세 그룹이 하나(`src/stores/sidebar.ts`)를 공유한다.

- **본문 폭은 가두지 않는 것이 기본이다.** 사이드바를 접어 벌어진 176u는 본문이 그대로 쓴다. 대시보드와 재배치되는 목록(라이브, 지난 방송의 `auto-fill` 2열)은 화면 루트(`.container`)에 `max-width`를 두지 않는다 — 두면 접어서 번 폭이 오른쪽 빈 띠로만 남는다.
- **본문 패딩은 공용 토큰이다** (`src/app/shell.css`): `--pc-shell-body-pad-top`(30u) · `--pc-shell-body-pad-x`(32u) · `--pc-shell-body-pad-bottom`(96u, fixed Dock 여백). 단위는 콘텐츠 유닛 `--pc-u`다 — 본문 안 글자·카드와 같은 배율을 타야 그룹을 오가도 h1의 x 좌표가 같다. 설정은 그룹 레이아웃이, 방송은 각 화면(`LiveScreen`·`VodListScreen`)이 토큰을 적용한다.
- **읽기 폭 상한은 늘어나면 읽기 힘든 블록에만 건다.** 폼(계정 `.card`·`.withdrawRow` 720u), 고정 열 격자(알림 `.card` 860u), 설명 위주 카드 묶음(플러그인 `.stack` 860u)은 **카드 단위**로 건다. 재배치되지 않는 행 카드 목록(채널 연동·편집자 관리)은 헤더의 액션 버튼이 행 끝과 맞아야 해 **화면 루트 `.screen`에 860u**(시안 1k·1l)를 둔다 — 이 두 화면은 접어도 행이 넓어지지 않고 오른쪽 여백이 는다(2026-09-02 결정). 새 화면은 「접어서 번 폭을 콘텐츠가 쓸 수 있는가」로 가른다.
- 예외: 라이브 대시보드(1b)는 위 20u · 아래 120u를 자체로 갖는다(플레이어 상단 정렬). 좌우는 토큰을 따른다.
- 보관함(1g)은 재배치되는 9:16 그리드라 상한이 없고, 우측 상세 패널(344u)은 본문의 flex 형제로 서서 열리면 그리드가 남은 폭을 다시 채운다(`LibraryScreen.module.css`). 그래서 클립 그룹 레이아웃은 방송처럼 패딩을 주지 않고 화면이 토큰을 적용한다.
- 사이드바가 없는 화면(홈)은 `ScreenContainer`(1200u 중앙 정렬)를 쓴다 — 이 규칙의 대상이 아니다.
