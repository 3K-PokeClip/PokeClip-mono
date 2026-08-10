# @pokeclip/ui

PokeClip 디자인 시스템 — 다크 우선(dark-first), 접근성을 갖춘 React 컴포넌트 라이브러리.

- **스택**: React 18 + TypeScript(strict) + CSS Modules
- **토큰**: 손수 작성한 CSS 커스텀 프로퍼티(단일 소스) + 얇은 TS 상수 레이어
- **테마**: 다크 우선, `[data-theme]` 기반 라이트/다크 전환
- **문서**: Storybook

## 사용

```ts
import '@pokeclip/ui/tokens.css'; // 1) 디자인 토큰 (필수, 먼저)
import '@pokeclip/ui/styles.css'; // 2) 컴포넌트 스타일
import { ThemeProvider } from '@pokeclip/ui';
```

## 개발

```bash
pnpm install
pnpm storybook     # 컴포넌트/토큰 문서 (http://localhost:6006)
pnpm build         # dist/ 라이브러리 빌드
pnpm typecheck     # 타입 체크
pnpm test          # 유닛/접근성 테스트
```

## 토큰 아키텍처 (3계층)

1. **Primitive** (`--pc-violet-500`, `--pc-space-4` …) — 테마 불변 원시 값
2. **Semantic** (`--pc-color-bg-surface`, `--pc-color-accent` …) — 역할 기반, 테마별 전환
3. **Component** (`--pc-button-bg` …) — 컴포넌트별 지연 도입

디자인 무드: 다크 우선(dark-first) · 인디고 메인 컬러 + 마젠타 포인트 컬러.
