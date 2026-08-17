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
