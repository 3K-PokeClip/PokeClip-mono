import { afterEach, describe, expect, it, vi } from 'vitest';
import { buildMediaSourceUrl, mediaControlBaseUrl } from './mediaSource';

const env = {
  stubUrl: 'http://stub.test/live/stub/index.m3u8',
  liveBaseUrl: 'http://live.test',
};

describe('buildMediaSourceUrl', () => {
  it('stream 파라미터가 없으면 스텁 URL', () => {
    expect(buildMediaSourceUrl(null, env)).toBe(env.stubUrl);
  });

  it('유효한 stream id면 라이브 플레이리스트 URL을 조립한다', () => {
    expect(buildMediaSourceUrl('test', env)).toBe('http://live.test/test/index.m3u8');
    expect(buildMediaSourceUrl('cam_01-a', env)).toBe('http://live.test/cam_01-a/index.m3u8');
  });

  it('base의 트레일링 슬래시를 정규화한다 — 이중 슬래시 URL 방지', () => {
    const slashed = { ...env, liveBaseUrl: 'http://live.test/' };
    expect(buildMediaSourceUrl('test', slashed)).toBe('http://live.test/test/index.m3u8');
  });

  it('부정 형식 id는 스텁으로 폴백 — 경로 조작 방지', () => {
    expect(buildMediaSourceUrl('../secret', env)).toBe(env.stubUrl);
    expect(buildMediaSourceUrl('a/b', env)).toBe(env.stubUrl);
    expect(buildMediaSourceUrl('', env)).toBe(env.stubUrl);
  });

  it('라이브 base가 없으면 stream을 무시하고 스텁', () => {
    expect(buildMediaSourceUrl('test', { stubUrl: env.stubUrl })).toBe(env.stubUrl);
  });

  it('env가 모두 비면 null — 시뮬레이션 폴백 신호', () => {
    expect(buildMediaSourceUrl(null, {})).toBeNull();
    expect(buildMediaSourceUrl('test', {})).toBeNull();
  });
});

describe('mediaControlBaseUrl — 되감기 제어면 주소(POK-200)', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('값의 끝 / 를 떼어 돌려준다', () => {
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', 'https://media.pokeclip.com/');
    expect(mediaControlBaseUrl()).toBe('https://media.pokeclip.com');
  });

  it('비었거나 없으면 null — 되감기 세션을 열지 않고 지금 동작이다', () => {
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', '');
    expect(mediaControlBaseUrl()).toBeNull();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', undefined);
    expect(mediaControlBaseUrl()).toBeNull();
  });
});
