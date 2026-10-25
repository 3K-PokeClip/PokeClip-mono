# web(Next.js) 운영 이미지 — prod 에서 공개 ALB 뒤 ECS Fargate 태스크가 `node server.js`
# (next start 상당 · output: 'standalone')로 돌린다. 빌드 컨텍스트는 web/ 다:
#   docker build --platform linux/<태스크 아키텍처> --build-arg AUTH_API_URL=… … web
# 빌드 인자 목록과 뜻은 README 「컨테이너 이미지」 절이 정본이다.
#
# 서버 주소가 빌드 때 굳는다: next.config.ts 의 rewrites 목적지는 next build 가
# .next/routes-manifest.json 에 박고, 실행 시점 env 는 읽지 않는다(2026-10-23 실측 —
# next start · server.js 둘 다 빌드 때 값으로 프록시). NEXT_PUBLIC_* 도 번들에 인라이닝된다.
# 그래서 환경마다 빌드 인자를 따로 주고 다시 빌드한다.

# Node 판은 .node-version 과 같게 유지한다. 다이제스트는 멀티 아키텍처 인덱스의 것이라
# --platform 으로 amd64 · arm64 를 고를 수 있다(태그를 바꾸면 다이제스트도 같이 바꾼다).
FROM node:24.18.0-trixie-slim@sha256:ae91dcc111a68c9d2d81ff2a17bda61be126426176fde6fe7d08ab13b7f50573 AS base
ENV NEXT_TELEMETRY_DISABLED=1 \
    COREPACK_ENABLE_DOWNLOAD_PROMPT=0
# pnpm 판은 package.json 의 packageManager(pnpm@11.12.0)를 corepack 이 따른다.
RUN corepack enable pnpm
WORKDIR /app

# 의존만 먼저 받아 두면 소스만 바뀐 재빌드가 이 층을 다시 쓴다.
FROM base AS deps
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
RUN pnpm install --frozen-lockfile

FROM base AS build
# 필수 — 비면 아래 검사에서 빌드가 멈춘다. 비어 있으면 rewrites 가 아예 걸리지 않거나
# (AUTH · CLIP) 로그인 · 재생이 깨진 채로 이미지가 나오기 때문이다.
ARG AUTH_API_URL
ARG CLIP_API_URL
ARG NEXT_PUBLIC_GOOGLE_CLIENT_ID
ARG NEXT_PUBLIC_MEDIA_LIVE_BASE_URL
ARG NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL
# 되감기 제어면 — 이 판의 웹은 읽지 않지만, O7c 의 한 번 빌드 명령이 인자 여섯을 지금 확정한다(소비자는 M5 웹 전환 PR).
ARG NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL
# 선택 — 비우면 그 기능이 꺼지는 것이 정해진 동작이다(사용 통계 · 녹화 · 랜딩 주소 기본값).
ARG NEXT_PUBLIC_POSTHOG_KEY
ARG NEXT_PUBLIC_POSTHOG_HOST
ARG NEXT_PUBLIC_POSTHOG_REPLAY
ARG NEXT_PUBLIC_LANDING_URL
# NEXT_PUBLIC_MEDIA_STUB_URL 은 일부러 받지 않는다 — 배포 빌드에서는 반드시 비어야 한다
# (.env.example). .env* 파일도 .dockerignore 가 컨텍스트에서 뺀다.
RUN : "${AUTH_API_URL:?빌드 인자가 비었습니다}" \
      "${CLIP_API_URL:?빌드 인자가 비었습니다}" \
      "${NEXT_PUBLIC_GOOGLE_CLIENT_ID:?빌드 인자가 비었습니다}" \
      "${NEXT_PUBLIC_MEDIA_LIVE_BASE_URL:?빌드 인자가 비었습니다}" \
      "${NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL:?빌드 인자가 비었습니다}" \
      "${NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL:?빌드 인자가 비었습니다}"
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN pnpm build

FROM node:24.18.0-trixie-slim@sha256:ae91dcc111a68c9d2d81ff2a17bda61be126426176fde6fe7d08ab13b7f50573 AS run
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0 \
    KEEP_ALIVE_TIMEOUT=65000
# KEEP_ALIVE_TIMEOUT(ms) 가 없으면 server.js 가 Node 기본 5초를 쓴다. ALB idle timeout(기본 60초)보다
# 짧으면 서버가 먼저 연결을 닫아 간헐 502 가 난다 — ALB idle 보다 크게 둔다.
WORKDIR /app
# standalone 은 public · .next/static 을 싣지 않으므로 따로 옮긴다(Next output 문서).
# 실행 중 .next/cache 에 쓰므로 비root 사용자 node(이미지 기본 제공)가 소유한다.
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 3000
# /api/ping 은 앱이 직접 답하는 API 라우트라 백엔드 상태와 무관하다. 슬림 이미지에 curl 이
# 없어 node 로 묻는다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "-e", "fetch(`http://127.0.0.1:${process.env.PORT}/api/ping`).then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]
CMD ["node", "server.js"]
