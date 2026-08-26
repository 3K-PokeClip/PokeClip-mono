import { afterEach, describe, expect, it, vi } from 'vitest';

// proxies가 모듈 로드 시점에 env를 읽으므로 stubEnv 후 동적 import한다
async function loadRewrites() {
  vi.resetModules();
  const config = (await import('../next.config')).default;
  return config.rewrites?.() ?? [];
}

describe('next.config rewrites', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('destination이 /api 접두사를 유지한다 (백엔드 컨트롤러 매핑과 일치)', async () => {
    vi.stubEnv('AUTH_API_URL', 'http://auth.internal:8082');
    vi.stubEnv('CLIP_API_URL', 'http://clip.internal:8081');
    expect(await loadRewrites()).toEqual([
      { source: '/api/auth/:path*', destination: 'http://auth.internal:8082/api/auth/:path*' },
      // 스트림키도 auth 서버 소유 — /api/auth 접두사 밖이라 별도 프록시가 필요하다 (POK-102)
      {
        source: '/api/stream-keys/:path*',
        destination: 'http://auth.internal:8082/api/stream-keys/:path*',
      },
      // 치지직 연동도 auth 서버 소유 — :path*가 빈 세그먼트도 잡아 /api/chzzk-link 자체까지 덮는다 (POK-205)
      {
        source: '/api/chzzk-link/:path*',
        destination: 'http://auth.internal:8082/api/chzzk-link/:path*',
      },
      // 편집자 위임·초대도 auth 서버 소유 (POK-208)
      {
        source: '/api/editor-delegations/:path*',
        destination: 'http://auth.internal:8082/api/editor-delegations/:path*',
      },
      {
        source: '/api/editor-invitations/:path*',
        destination: 'http://auth.internal:8082/api/editor-invitations/:path*',
      },
      // 유튜브 연동도 auth 서버 소유 — 한 줄이 /api/youtube-link 자체와 /start를 덮는다 (POK-221)
      {
        source: '/api/youtube-link/:path*',
        destination: 'http://auth.internal:8082/api/youtube-link/:path*',
      },
      { source: '/api/clip/:path*', destination: 'http://clip.internal:8081/api/clip/:path*' },
    ]);
  });

  it('env가 없으면 프록시를 걸지 않는다', async () => {
    vi.stubEnv('AUTH_API_URL', '');
    vi.stubEnv('CLIP_API_URL', '');
    expect(await loadRewrites()).toEqual([]);
  });
});
