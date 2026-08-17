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
      { source: '/api/clip/:path*', destination: 'http://clip.internal:8081/api/clip/:path*' },
    ]);
  });

  it('env가 없으면 프록시를 걸지 않는다', async () => {
    vi.stubEnv('AUTH_API_URL', '');
    vi.stubEnv('CLIP_API_URL', '');
    expect(await loadRewrites()).toEqual([]);
  });
});
