# web — 웹 대시보드

**담당: 2번 (`@jaehwan-space`)**

## 무엇이 들어가나

편집자가 매일 여는 화면. React · TypeScript.

| 화면                   | 내용                                                           |
| ---------------------- | -------------------------------------------------------------- |
| 방송 › 라이브 대시보드 | 방송을 실시간으로 보면서 되감는다 (hls.js LL-HLS + DVR 시크바) |
| 점프카드               | 하이라이트 후보가 실시간으로 뜬다                              |
| 에디터                 | 구간 지정 · 화면 비율 · 오디오 트랙 선택 · 자막                |
| 보관함                 | 만든 클립 목록 · 승인 · VOD                                    |
| 온보딩/설정            | 스트림 키 발급 · 채널 연동                                     |

## 시작하기

Node.js **24.18.0** (`.node-version`) · pnpm **11.12.0** 기준. pnpm이 없으면 `corepack enable`
또는 `npm i -g pnpm@11.12.0`.

```bash
cd web
pnpm install
cp .env.example .env.local   # 최초 1회 — 값은 로컬 기본값 그대로면 된다
pnpm dev                     # http://localhost:3000 → /home 으로 리다이렉트
```

| 명령                                         | 설명                                      |
| -------------------------------------------- | ----------------------------------------- |
| `pnpm dev`                                   | `next dev`                                |
| `pnpm build`                                 | `next build` (타입 체크 포함) — CI와 동일 |
| `pnpm lint` / `pnpm typecheck` / `pnpm test` | ESLint(flat config) / 타입 체크 / Vitest  |
| `pnpm storybook`                             | DS Storybook (port 6006)                  |
| `pnpm build-storybook`                       | Storybook 정적 빌드                       |
| `pnpm format` / `pnpm format:check`          | Prettier 적용 / 검사                      |

## 구조

```
web/                     # 단일 Next.js 앱 (App Router + TanStack Query + Zustand)
├── src/
│   ├── app/             # 라우트
│   ├── components/      # 앱 공용 UI (앱 셸 등)
│   └── ui/              # 디자인 시스템 (React + TS + CSS Modules, Storybook)
├── .storybook/          # Storybook 설정 (src/ui 대상)
├── docs/                # DS·브랜드 문서
└── public/              # 정적 자산 (brand 등)
```

### 앱 폴더 컨벤션 (`src/`)

| 폴더                  | 역할                                                                            |
| --------------------- | ------------------------------------------------------------------------------- |
| `app/`                | 라우트. **페이지는 얇게** — 화면 본문은 `features/`에 두고 페이지는 조립만 한다 |
| `app/api/**/route.ts` | Next 서버 핸들러 (패턴 앵커: `app/api/ping/route.ts`)                           |
| `features/<도메인>/`  | 도메인별 화면·훅·스토어 (예: `features/broadcast/`, `features/clips/`)          |
| `components/`         | 도메인을 넘어 재사용하는 공용 UI (예: `components/app-shell/`)                  |
| `lib/`                | 공용 유틸·설정 (도메인 무관)                                                    |
| `api/`                | 백엔드 API 클라이언트 계층 (fetcher·쿼리 정의)                                  |
| `stores/`             | 전역 클라이언트 상태 (Zustand — 서버 데이터는 TanStack Query가 담당)            |

**새 코드는 어디에 만드는가:**

- **새 화면** → `app/<경로>/page.tsx`(얇게) + 본문은 `features/<도메인>/`
- **2개 이상 도메인에서 쓰는 컴포넌트** → `components/` (한 도메인 전용이면 그 `features/` 안에)
- **범용 함수·상수** → `lib/` · **백엔드 호출 코드** → `api/`
- 시각적 기본 요소(버튼·입력 등)는 만들지 말 것 — 먼저 `src/ui/`(`@/ui`)에서 찾는다

### 라우트 맵

| 경로                                                              | 내용                                                               |
| ----------------------------------------------------------------- | ------------------------------------------------------------------ |
| `/`                                                               | `/home` 리다이렉트 — 세션 분기는 `(dock)`의 AuthGuard가 한다       |
| `/login`                                                          | 로그인 진입 (셸 없음) — 세션이 있으면 `/home`으로 역가드           |
| `/auth/callback`                                                  | 구글 OAuth 복귀 — code를 토큰으로 교환 (백엔드 redirect_uri)       |
| `/oauth/chzzk/callback`                                           | 치지직 동의 복귀 — code·state를 연동으로 교환 (아래 참조)          |
| `/home` `/broadcast` `/clips` `/settings`                         | 독 4개 — `(dock)` 그룹 공유 셸(AuthGuard + 하단 Dock)              |
| `/broadcast`                                                      | `/broadcast/livenow` 리다이렉트 — 방송 그룹은 좌측 `Side`를 갖는다 |
| `/broadcast/livenow`                                              | 라이브 대시보드 (지난 방송 `/broadcast/vod`는 별도 티켓)           |
| `/settings`                                                       | `/settings/plugin` 리다이렉트 — 설정 그룹도 좌측 `Side`를 갖는다   |
| `/settings/channels` `/settings/plugin` `/settings/notifications` | 채널 연동 · 플러그인 · 알림 설정 (나머지 설정 화면은 별도 티켓)    |
| `/dev`                                                            | 개발용 데모 (테마 전환 · Zustand 카운터 · TanStack Query 예시)     |
| `/api/ping`                                                       | 서버 핸들러 앵커                                                   |
| 그 밖의 모든 주소                                                 | 404 폴백 (`app/not-found.tsx`) — 아래 참조                         |

**치지직 콜백 계약.** `/oauth/chzzk/callback`은 `(dock)` **밖**이라 `AuthGuard`가 덮지 않는다 — 세션 판정은 화면이 직접 하고, 세션이 없으면 로그인으로 보낸 뒤 `/settings/channels`로 되돌아온다. 이 경로는 백엔드 `CHZZK_REDIRECT_URI`, 그리고 **치지직 개발자 센터에 등록된 redirect URI**와 **정확히 같아야 한다** — 개발자 센터는 앱당 하나만 등록하므로 환경마다 앱을 따로 판다. 프론트에 치지직용 env는 없다(동의 URL을 백엔드가 조립한다). 어긋나면 개발 빌드 콘솔에 경고가 뜬다(`chzzkOAuth.warnIfCallbackMismatch`) — 그게 없으면 증상이 "동의를 다 마친 뒤 낯선 주소에서 막힘"으로만 나타난다.

**404 계약.** `app/not-found.tsx` 하나가 두 경우를 다 받는다 — 어떤 경로에도 안 걸린 주소, 그리고 상세 화면이 `notFound()`를 던진 경우. **자원이 없으면(만료·삭제·권한 회수) 상세 화면은 `notFound()`를 던져 이 화면을 재사용한다** — 「없음」과 「만료」를 문구로 가르지 않는다 (POK-204 · ADR-045). 루트에 두는 게 조건이다: `(dock)` 안에 두면 `AuthGuard`가 먼저 걸려 비로그인 사용자에게 404 대신 `/login`이 뜬다.

### 환경변수 (`.env.example` → `.env.local`)

| 변수                              | 값(로컬)                 | 용도                                                                            |
| --------------------------------- | ------------------------ | ------------------------------------------------------------------------------- |
| `AUTH_API_URL`                    | `http://localhost:8082`  | auth 서버 — `/api/auth/*` rewrites 프록시 대상                                  |
| `CLIP_API_URL`                    | `http://localhost:8081`  | clip 서버 — `/api/clip/*` rewrites 프록시 대상                                  |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID`    | 구글 OAuth 클라이언트 ID | 로그인 동의 URL 조립 — 백엔드 `GOOGLE_CLIENT_ID`와 같은 값                      |
| `NEXT_PUBLIC_MEDIA_STUB_URL`      | 스텁 m3u8 주소           | 플레이어 개발용 정적 세그먼트 ([`infra/compose/stub/`](../infra/compose/stub/)) |
| `NEXT_PUBLIC_MEDIA_LIVE_BASE_URL` | LL-HLS 베이스            | 진짜 미디어 서버 (`{base}/{streamId}/index.m3u8`)                               |

서버 주소는 **코드에 하드코딩하지 않는다** — env 참조만. 백엔드 서버가 안 떠 있어도
rewrites는 env가 있을 때만 걸리므로 앱 기동에는 지장이 없다. 백엔드 로컬 기동은
[`docs/dev-environment.md`](../docs/dev-environment.md).

### 세션·토큰 저장과 다중 탭

- access(30분)는 **메모리에만**, refresh(14일)만 `localStorage`(`pc-auth`)에 둔다 — `stores/auth.ts`. localStorage의 refresh가 **정본**이다.
- 탭 사이 전파는 `BroadcastChannel('pc-auth')`: `login`(새 세션 — 받는 탭은 쿼리 캐시를 비운다) · `rotate`(같은 세션의 refresh 회전 — 받는 탭은 **내 refresh의 직계 후속일 때만** access까지 이어받고 캐시는 그대로 둔다) · `logout`. 받는 탭은 localStorage를 쓰지 않는다.
- `storage` 이벤트는 **폴백**이다 — 100ms 뒤 정본을 다시 읽어 채널이 이미 맞췄으면 아무것도 하지 않고, 아니면 이전 계약대로 access를 비우고 캐시를 지운다(회전인지 계정 교체인지 알 수 없으므로). 탭이 다시 보일 때(`visibilitychange`·`pageshow`)와 **회전 요청 직전**에는 지연 없이 같은 동기화를 한다 — 메시지를 놓친 탭이 묵은 refresh로 회전하면 10초 유예 밖 재사용이라 서버가 전 세션을 끊는다. 정본을 읽을 수 없거나 저장이 실패한 탭은 동기화에서 빠진다(메모리 세션은 새로고침까지 유지).
- 회전은 `navigator.locks`(`pc-auth:refresh`)로 **탭 사이에서도 한 번에 하나만** 돈다 — 두 탭이 같은 refresh로 동시에 회전하면(탭 여러 개를 한꺼번에 복원) 진 쪽이 401을 받는데 클라이언트는 그걸 만료와 구분할 수 없기 때문이다. 락을 얻은 뒤 정본이 바뀌어 있으면 보내지 않거나 정본 토큰으로 회전한다. 락이 없는 환경에서만 401 뒤 0.5초 동안 옆 탭의 `rotate`를 기다리고, 그래도 정본이 바뀌어 있으면 세션을 접는 대신 정본을 채택한다.
- **알려진 한계**: BroadcastChannel이 없는 환경과 구버전 번들 탭이 섞인 창에서는 폴백만 동작해 회전 핑퐁(POK-211)이 재발할 수 있다.

## 규칙

- **DS 소비 방식**: 디자인 시스템은 `src/ui/` 소스를 `@/ui`로 직접 import한다.
  별도 빌드 없음 — DS 수정은 `next dev` HMR로 즉시 반영된다.
- **전역 CSS**: 토큰·폰트·리셋은 `@/ui/styles/global.css` 하나만 루트 레이아웃에서
  임포트한다. 컴포넌트 스타일(CSS Modules)은 각 컴포넌트가 스스로 import한다.
- **클라이언트 경계**: DS 소스에는 `'use client'`가 없다. 인터랙티브 DS 컴포넌트
  (ThemeProvider, 훅/핸들러 사용 컴포넌트)는 `'use client'` 파일에서 렌더링한다.
- **design-sync**: 기존 도구는 `packages/ui/dist/design-system.css`를 소비했다 —
  플랫화로 dist가 사라졌으므로 다음 사용 시 config 재설정이 필요하다.

## 재생에서 조심할 것

LL-HLS 플레이어는 **catch-up을 꺼야 한다.** 켜져 있으면 되감기 중에 플레이어가
자기 마음대로 라이브 끝으로 점프한다.

재생 규약의 정본은 [`contracts/`](../contracts/) 계약3이다.

## 편집자가 하는 일의 본질

**화면에서 "여기부터 여기까지"를 고르면, 그 시각 두 개가 서버로 간다.**
영상을 보내는 게 아니다. 그래서 편집을 오래 붙잡고 있어도, 라이브 버퍼에서 그 구간이
빠져나가도 문제가 없다 — 원본은 전부 S3에 있다.

이게 되려면 플레이어가 **지금 재생 중인 지점의 절대 시각**을 알아야 한다.

## 문서

- [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) — 디자인 시스템 사용법
- [docs/COLOR_SYSTEM.md](docs/COLOR_SYSTEM.md) — 컬러 토큰 시스템
- [docs/BRAND.md](docs/BRAND.md) — 브랜드 가이드 (로고·파비콘·팔레트·타이포)
