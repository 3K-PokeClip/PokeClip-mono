import type { NextConfig } from 'next';

// 서버 주소는 env에만 둔다 (.env.example 참조). env가 없으면 해당 프록시를 걸지
// 않으므로 백엔드 없이도(CI 포함) 기동·빌드가 된다. afterFiles 단계라
// app/api/** 파일 라우트(/api/ping)가 항상 우선한다.
const proxies = [
  { source: '/api/auth/:path*', target: process.env.AUTH_API_URL },
  { source: '/api/clip/:path*', target: process.env.CLIP_API_URL },
];

const nextConfig: NextConfig = {
  async rewrites() {
    return proxies
      .filter((p): p is { source: string; target: string } => Boolean(p.target))
      .map((p) => ({ source: p.source, destination: `${p.target}/:path*` }));
  },
};

export default nextConfig;
