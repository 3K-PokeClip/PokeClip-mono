import type { NextConfig } from 'next';

// 서버 주소는 env에만 둔다 (.env.example 참조). env가 없으면 해당 프록시를 걸지
// 않으므로 백엔드 없이도(CI 포함) 기동·빌드가 된다. afterFiles 단계라
// app/api/** 파일 라우트(/api/ping)가 항상 우선한다.
const proxies = [
  { source: '/api/auth/:path*', target: process.env.AUTH_API_URL },
  // 스트림키·페어링 코드(POK-102)도 auth 서버 소유다 (StreamKeyController).
  { source: '/api/stream-keys/:path*', target: process.env.AUTH_API_URL },
  // 치지직 채널 연동(POK-205)도 auth 서버 소유다 (ChzzkLinkController).
  // :path*는 빈 세그먼트도 잡으므로 이 한 줄이 /api/chzzk-link 자체(GET·POST·DELETE)와
  // /api/chzzk-link/start를 함께 덮는다 — dev 서버에 실측했다(둘 다 백엔드까지 도달해
  // 401, 매칭 안 되는 주소는 404).
  { source: '/api/chzzk-link/:path*', target: process.env.AUTH_API_URL },
  // 편집자 위임·초대(POK-208)도 auth 서버 소유다 (EditorDelegationController·
  // EditorInvitationController). chzzk-link처럼 :path*가 빈 세그먼트도 잡으므로
  // /api/editor-invitations 자체(POST)와 /sent·/{id}를 한 줄씩이 함께 덮는다.
  { source: '/api/editor-delegations/:path*', target: process.env.AUTH_API_URL },
  { source: '/api/editor-invitations/:path*', target: process.env.AUTH_API_URL },
  // 유튜브 채널 연동(POK-221)도 auth 서버 소유다 (YoutubeLinkController). chzzk-link처럼
  // :path*가 빈 세그먼트도 잡으므로 이 한 줄이 /api/youtube-link 자체(GET·POST·DELETE)와
  // /api/youtube-link/start를 함께 덮는다 — dev 서버에 실측했다(둘 다 백엔드까지 도달해
  // 401, 매칭 안 되는 주소는 404).
  { source: '/api/youtube-link/:path*', target: process.env.AUTH_API_URL },
  // SOOP 채널 연동(POK-396)도 auth 서버 소유다 (SoopLinkController). 같은 이유로 한 줄이 자체와 /start를 덮는다.
  { source: '/api/soop-link/:path*', target: process.env.AUTH_API_URL },
  // 오디오 트랙 이름(POK-240) 중 편집자가 읽는 문과 프로필 사진도 auth 서버 소유다(POK-251).
  // 🔴 이 줄이 없으면 편집기 오디오 탭이 404를 받아 트랙 이름이 「트랙 n」으로만 보인다(2026-09-17 실측).
  { source: '/api/streamers/:path*', target: process.env.AUTH_API_URL },
  { source: '/api/profile-photos/:path*', target: process.env.AUTH_API_URL },
  { source: '/api/clip/:path*', target: process.env.CLIP_API_URL },
];

// Referrer-Policy (POK-482). OAuth 콜백 주소에는 1회용 code·state가 실려, 그 화면에서 나가는 요청의 Referer에
// 주소 전체가 담기면 WAF 샘플·로그(다른 출처면 제3자)에 남는다. 콜백 화면은 아예 보내지 않고, 나머지는 브라우저
// 기본값을 명시해 다른 출처에는 origin만 보낸다. 같은 경로에 같은 키가 두 번 걸리면 뒤 항목이 이기므로 콜백이 뒤에 온다.
// 정책은 문서 단위라, 콜백에서 router.replace로 옮겨 간 화면도 새로고침 전까지 no-referrer로 남는다(더 엄격할 뿐이다).
// 🔴 WAF(edge)는 referer를 가리지 않으므로 이보다 느슨하게 바꾸지 말 것: unsafe-url·no-referrer-when-downgrade 금지 (POK-479).
// 경로는 각 redirect URI와 짝이다: googleOAuth.ts·chzzkOAuth.ts·soopOAuth.ts·youtubeOAuth.ts(next.config.test.ts가 대조).
const OAUTH_CALLBACK_PATHS = [
  '/auth/callback',
  '/oauth/chzzk/callback',
  '/oauth/soop/callback',
  '/oauth/youtube/callback',
];

const BRAND_ASSET_CACHE = [
  { key: 'Cache-Control', value: 'public, max-age=86400, stale-while-revalidate=604800' },
];

const nextConfig: NextConfig = {
  // 운영 이미지(Dockerfile)는 .next/standalone 의 server.js 만 싣는다 — node_modules 전체 없이 뜬다.
  // pnpm dev·pnpm build 는 그대로 동작하고, 빌드 산출물에 standalone 폴더가 하나 더 생길 뿐이다.
  output: 'standalone',
  // 라이브 카드 통로(SSE, text/event-stream)를 이 서버의 rewrite 프록시가 gzip으로 묶으면 조각이 버퍼에
  // 갇혀 브라우저에 한 바이트도 안 온다(2026-09-17 실측: 2초 동안 gzip 헤더 10바이트). 압축을 끈다(POK-251).
  compress: false,
  experimental: {
    // 기본값은 CSS를 라우트를 가로질러 묶어 로그인 화면까지 라이브 · 편집기 CSS(렌더 차단)를 받는다
    cssChunking: 'graph',
  },
  async rewrites() {
    return (
      proxies
        .filter((p): p is { source: string; target: string } => Boolean(p.target))
        // 백엔드 컨트롤러가 /api/auth·/api/clip 접두사까지 매핑하므로 destination에도 유지한다
        .map((p) => ({ source: p.source, destination: `${p.target}${p.source}` }))
    );
  },
  async headers() {
    const referrerPolicy = (value: string) => [{ key: 'Referrer-Policy', value }];
    return [
      { source: '/:path*', headers: referrerPolicy('strict-origin-when-cross-origin') },
      ...OAUTH_CALLBACK_PATHS.map((source) => ({ source, headers: referrerPolicy('no-referrer') })),
      // public 파일은 이름에 해시가 없어 immutable로 두면 교체가 안 보인다. 하루 쓰고 일주일은 뒤에서 새로 받는다
      { source: '/brand/:path*', headers: BRAND_ASSET_CACHE },
    ];
  },
};

export default nextConfig;
