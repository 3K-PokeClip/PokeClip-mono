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
  { source: '/api/clip/:path*', target: process.env.CLIP_API_URL },
];

const nextConfig: NextConfig = {
  async rewrites() {
    return (
      proxies
        .filter((p): p is { source: string; target: string } => Boolean(p.target))
        // 백엔드 컨트롤러가 /api/auth·/api/clip 접두사까지 매핑하므로 destination에도 유지한다
        .map((p) => ({ source: p.source, destination: `${p.target}${p.source}` }))
    );
  },
};

export default nextConfig;
