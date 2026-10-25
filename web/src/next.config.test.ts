import { afterEach, describe, expect, it, vi } from 'vitest';
import { CHZZK_CALLBACK_PATH } from '@/features/settings/channels/chzzkOAuth';
import { SOOP_CALLBACK_PATH } from '@/features/settings/channels/soopOAuth';
import { YOUTUBE_CALLBACK_PATH } from '@/features/settings/channels/youtubeOAuth';

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
      // SOOP 연동도 auth 서버 소유: 한 줄이 /api/soop-link 자체와 /start를 덮는다 (POK-396)
      {
        source: '/api/soop-link/:path*',
        destination: 'http://auth.internal:8082/api/soop-link/:path*',
      },
      // 편집자가 읽는 스트리머 오디오 트랙 이름(POK-240)과 프로필 사진도 auth 서버 소유 (POK-251)
      {
        source: '/api/streamers/:path*',
        destination: 'http://auth.internal:8082/api/streamers/:path*',
      },
      {
        source: '/api/profile-photos/:path*',
        destination: 'http://auth.internal:8082/api/profile-photos/:path*',
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

async function loadHeaders() {
  vi.resetModules();
  const config = (await import('../next.config')).default;
  return (await config.headers?.()) ?? [];
}

describe('next.config headers: Referrer-Policy (POK-482)', () => {
  it('전역은 strict-origin-when-cross-origin, OAuth 콜백 네 곳은 no-referrer 다', async () => {
    const policy = (value: string) => [{ key: 'Referrer-Policy', value }];
    // 같은 경로에 같은 키가 두 번 걸리면 뒤 항목이 이긴다(Next headers 문서). 콜백이 전역 뒤에 와야 한다.
    expect(await loadHeaders()).toEqual([
      { source: '/:path*', headers: policy('strict-origin-when-cross-origin') },
      // 구글 로그인 redirect_uri (googleOAuth.ts): 경로 상수가 따로 없다
      { source: '/auth/callback', headers: policy('no-referrer') },
      { source: CHZZK_CALLBACK_PATH, headers: policy('no-referrer') },
      { source: SOOP_CALLBACK_PATH, headers: policy('no-referrer') },
      { source: YOUTUBE_CALLBACK_PATH, headers: policy('no-referrer') },
    ]);
  });

  it('어떤 경로도 다른 출처로 전체 주소를 보내는 값을 쓰지 않는다: WAF가 referer를 가리지 않는다 (POK-479)', async () => {
    const values = (await loadHeaders())
      .flatMap((rule) => rule.headers)
      .filter((header) => header.key.toLowerCase() === 'referrer-policy')
      .map((header) => header.value);
    expect(values.length).toBeGreaterThan(0);
    for (const value of values) {
      expect([
        'no-referrer',
        'same-origin',
        'strict-origin',
        'strict-origin-when-cross-origin',
      ]).toContain(value);
    }
  });
});
