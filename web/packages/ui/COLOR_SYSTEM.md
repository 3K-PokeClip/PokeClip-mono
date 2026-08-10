# PokeClip 컬러 시스템

`@pokeclip/ui` 디자인 시스템의 컬러 토큰 정의. **다크 우선(dark-first)**, 메인 컬러는 **인디고(indigo)**, 포인트 컬러는 **마젠타(magenta)**.

## 아키텍처 — 3계층 토큰

컬러는 3계층으로 구성됩니다. 컴포넌트는 **반드시 시맨틱(Semantic) 토큰만** 소비하며, 원시(Primitive) 값을 직접 사용하지 않습니다.

| 계층                | 위치                                         | 역할                                       | 테마별 변화 |
| ------------------- | -------------------------------------------- | ------------------------------------------ | ----------- |
| **1. Primitive**    | `src/styles/primitives/color.css`            | 테마 독립적인 원시 색상값(hex)             | ❌ 고정     |
| **2. Semantic**     | `src/styles/semantic/theme-{dark,light}.css` | 역할 기반 토큰. 이 계층만 테마에 따라 전환 | ✅ 전환     |
| **3. JS Reference** | `src/tokens/color.ts`                        | 인라인 스타일/JS용 `var()` 참조 문자열     | —           |

접두사 규칙: 원시 토큰은 `--pc-{hue}-{scale}`, 시맨틱 토큰은 `--pc-color-{role}`.

---

## 1. Primitive 팔레트 (원시 색상)

> 테마 독립적인 raw 값. **컴포넌트에서 직접 사용 금지** — 시맨틱 토큰을 통해서만 소비.

### Brand: Primary (인디고 — 메인 컬러)

| 토큰               | HEX       |           |
| ------------------ | --------- | --------- |
| `--pc-primary-50`  | `#f0f3fc` |           |
| `--pc-primary-100` | `#e8edfa` |           |
| `--pc-primary-200` | `#cdd7f2` |           |
| `--pc-primary-300` | `#a8b9e8` |           |
| `--pc-primary-400` | `#7d93d9` |           |
| `--pc-primary-500` | `#586fc4` | **BRAND** |
| `--pc-primary-600` | `#4055a8` |           |
| `--pc-primary-700` | `#304184` |           |
| `--pc-primary-800` | `#233266` |           |
| `--pc-primary-900` | `#172554` |           |

RGB 채널(알파 합성용): `--pc-primary-400-rgb: 125 147 217` · `--pc-primary-500-rgb: 88 111 196`

### Point: Magenta (포인트 / 하이라이트 — 인디고 브랜드와 페어링)

| 토큰             | HEX       |           |
| ---------------- | --------- | --------- |
| `--pc-point-50`  | `#fdf1f9` |           |
| `--pc-point-100` | `#fbe3f4` |           |
| `--pc-point-200` | `#f6c4e6` |           |
| `--pc-point-300` | `#ef9bd3` |           |
| `--pc-point-400` | `#e568ba` |           |
| `--pc-point-500` | `#d63f9f` | **POINT** |
| `--pc-point-600` | `#bd2d86` |           |
| `--pc-point-700` | `#99226b` |           |
| `--pc-point-800` | `#761b53` |           |
| `--pc-point-900` | `#4d1638` |           |

RGB 채널: `--pc-point-400-rgb: 229 104 186` · `--pc-point-500-rgb: 214 63 159`

### Neutral: Cool Gray

| 토큰             | HEX       |                 |
| ---------------- | --------- | --------------- |
| `--pc-gray-50`   | `#eef0f3` |                 |
| `--pc-gray-100`  | `#e4e6e9` |                 |
| `--pc-gray-200`  | `#cdd1d4` |                 |
| `--pc-gray-300`  | `#aab0b6` |                 |
| `--pc-gray-400`  | `#7f8891` |                 |
| `--pc-gray-500`  | `#616871` |                 |
| `--pc-gray-600`  | `#494d55` |                 |
| `--pc-gray-700`  | `#3b4049` |                 |
| `--pc-gray-800`  | `#2d3036` |                 |
| `--pc-gray-900`  | `#25272c` |                 |
| `--pc-gray-950`  | `#1d1e22` |                 |
| `--pc-gray-1000` | `#141517` | base near-black |

RGB 채널: `--pc-gray-50-rgb: 238 240 243` · `--pc-gray-1000-rgb: 20 21 23`

기본 흑백: `--pc-white: #ffffff` · `--pc-black: #000000`

### Accent / Status Hues (상태 색상용 원시값)

| 토큰              | HEX       |     | 토큰              | HEX       |
| ----------------- | --------- | --- | ----------------- | --------- |
| `--pc-cyan-200`   | `#9efaff` |     | `--pc-yellow-500` | `#ffd607` |
| `--pc-cyan-500`   | `#00dfff` |     | `--pc-green-200`  | `#daffca` |
| `--pc-teal-500`   | `#0bb0c7` |     | `--pc-green-400`  | `#8cfa5a` |
| `--pc-pink-200`   | `#ffc6f2` |     | `--pc-green-600`  | `#30ac04` |
| `--pc-pink-400`   | `#ff5dd0` |     | `--pc-red-400`    | `#ff4d6a` |
| `--pc-pink-500`   | `#fa00ca` |     | `--pc-red-500`    | `#ff002f` |
| `--pc-purple-500` | `#c02cff` |     | `--pc-brown-500`  | `#ca4400` |

RGB 채널: `--pc-cyan-500-rgb: 0 223 255` · `--pc-yellow-500-rgb: 255 214 7` · `--pc-green-600-rgb: 48 172 4` · `--pc-red-400-rgb: 255 77 106`

---

## 2. Semantic 토큰 (역할 기반)

> 컴포넌트가 소비하는 유일한 계층. 다크가 기본값이며, 라이트는 명시적 opt-in(`[data-theme='light']`) 또는 OS 선호도 폴백.

각 시맨틱 토큰이 다크/라이트에서 어떤 원시값으로 매핑되는지:

### Backgrounds — 계층(elevation)은 점점 밝아지는 그레이로 표현

| 시맨틱 토큰                    | Dark               | Light              |
| ------------------------------ | ------------------ | ------------------ |
| `--pc-color-bg-canvas`         | `gray-1000`        | `gray-50`          |
| `--pc-color-bg-surface`        | `gray-900`         | `white`            |
| `--pc-color-bg-surface-hover`  | `gray-800`         | `gray-50`          |
| `--pc-color-bg-surface-raised` | `gray-800`         | `white`            |
| `--pc-color-bg-inset`          | `gray-950`         | `gray-100`         |
| `--pc-color-bg-overlay`        | `gray-1000 / 0.72` | `gray-1000 / 0.48` |

### Text

| 시맨틱 토큰                 | Dark       | Light      |
| --------------------------- | ---------- | ---------- |
| `--pc-color-text-primary`   | `gray-50`  | `gray-950` |
| `--pc-color-text-secondary` | `gray-300` | `gray-600` |
| `--pc-color-text-muted`     | `gray-400` | `gray-500` |
| `--pc-color-text-disabled`  | `gray-600` | `gray-300` |
| `--pc-color-text-on-accent` | `gray-50`  | `white`    |
| `--pc-color-text-on-danger` | `white`    | `white`    |

### Borders

| 시맨틱 토큰                 | Dark       | Light      |
| --------------------------- | ---------- | ---------- |
| `--pc-color-border-subtle`  | `gray-800` | `gray-100` |
| `--pc-color-border-default` | `gray-700` | `gray-200` |
| `--pc-color-border-strong`  | `gray-600` | `gray-300` |

### Accent (Brand — 인디고)

| 시맨틱 토큰                      | Dark                 | Light                |
| -------------------------------- | -------------------- | -------------------- |
| `--pc-color-accent`              | `primary-500`        | `primary-500`        |
| `--pc-color-accent-hover`        | `primary-400`        | `primary-600`        |
| `--pc-color-accent-active`       | `primary-600`        | `primary-700`        |
| `--pc-color-accent-subtle`       | `primary-500 / 0.16` | `primary-500 / 0.1`  |
| `--pc-color-accent-subtle-hover` | `primary-500 / 0.24` | `primary-500 / 0.16` |
| `--pc-color-accent-text`         | `primary-300`        | `primary-700`        |

### Point (마젠타 하이라이트)

| 시맨틱 토큰               | Dark               | Light             |
| ------------------------- | ------------------ | ----------------- |
| `--pc-color-point`        | `point-500`        | `point-500`       |
| `--pc-color-point-hover`  | `point-400`        | `point-600`       |
| `--pc-color-point-active` | `point-600`        | `point-700`       |
| `--pc-color-point-subtle` | `point-500 / 0.16` | `point-500 / 0.1` |
| `--pc-color-point-text`   | `point-300`        | `point-700`       |

### Focus

| 시맨틱 토큰             | Dark          | Light         |
| ----------------------- | ------------- | ------------- |
| `--pc-color-focus-ring` | `primary-400` | `primary-500` |

### Status

| 시맨틱 토큰                 | Dark                | Light               |
| --------------------------- | ------------------- | ------------------- |
| `--pc-color-danger`         | `red-400`           | `red-500`           |
| `--pc-color-danger-hover`   | `red-500`           | `#d40028`           |
| `--pc-color-danger-subtle`  | `red-400 / 0.16`    | `red-400 / 0.12`    |
| `--pc-color-success`        | `green-600`         | `green-600`         |
| `--pc-color-success-subtle` | `green-600 / 0.16`  | `green-600 / 0.12`  |
| `--pc-color-warning`        | `yellow-500`        | `#b8860b`           |
| `--pc-color-warning-subtle` | `yellow-500 / 0.16` | `yellow-500 / 0.16` |
| `--pc-color-info`           | `cyan-500`          | `teal-500`          |
| `--pc-color-info-subtle`    | `cyan-500 / 0.16`   | `cyan-500 / 0.12`   |

> **라이트 테마 참고:** `[data-theme='light']`로 명시 지정 시 위 라이트 값이 적용됩니다. 앱이 테마를 명시하지 않은 경우(`:root:not([data-theme])`)에는 `@media (prefers-color-scheme: light)` 폴백이 일부 토큰을 라이트로 전환합니다.

---

## 3. JS 참조 (`src/tokens/color.ts`)

인라인 스타일이나 JS에서 사용하는 `var()` 참조 문자열. 시맨틱 토큰을 그대로 매핑합니다.

```ts
export const color = {
  bg: {
    canvas: 'var(--pc-color-bg-canvas)',
    surface: 'var(--pc-color-bg-surface)',
    surfaceHover: 'var(--pc-color-bg-surface-hover)',
    surfaceRaised: 'var(--pc-color-bg-surface-raised)',
    inset: 'var(--pc-color-bg-inset)',
    overlay: 'var(--pc-color-bg-overlay)',
  },
  text: {
    primary: 'var(--pc-color-text-primary)',
    secondary: 'var(--pc-color-text-secondary)',
    muted: 'var(--pc-color-text-muted)',
    disabled: 'var(--pc-color-text-disabled)',
    onAccent: 'var(--pc-color-text-on-accent)',
    onDanger: 'var(--pc-color-text-on-danger)',
  },
  border: {
    subtle: 'var(--pc-color-border-subtle)',
    default: 'var(--pc-color-border-default)',
    strong: 'var(--pc-color-border-strong)',
  },
  accent: {
    base: 'var(--pc-color-accent)',
    hover: 'var(--pc-color-accent-hover)',
    active: 'var(--pc-color-accent-active)',
    subtle: 'var(--pc-color-accent-subtle)',
    subtleHover: 'var(--pc-color-accent-subtle-hover)',
    text: 'var(--pc-color-accent-text)',
  },
  point: {
    base: 'var(--pc-color-point)',
    hover: 'var(--pc-color-point-hover)',
    active: 'var(--pc-color-point-active)',
    subtle: 'var(--pc-color-point-subtle)',
    text: 'var(--pc-color-point-text)',
  },
  focusRing: 'var(--pc-color-focus-ring)',
  danger: 'var(--pc-color-danger)',
  success: 'var(--pc-color-success)',
  warning: 'var(--pc-color-warning)',
  info: 'var(--pc-color-info)',
} as const;
```

---

## 사용 가이드

- **컴포넌트 CSS**: 시맨틱 토큰만 사용 → `color: var(--pc-color-text-primary);`
- **인라인/JS**: `color.ts` 참조 사용 → `style={{ color: color.text.primary }}`
- **원시 팔레트(`--pc-primary-*` 등) 직접 사용 금지** — 테마 전환이 깨집니다.
- **알파 합성**: `-rgb` 채널 토큰 + `rgb(... / 알파)` 패턴 → `rgb(var(--pc-primary-500-rgb) / 0.16)`
