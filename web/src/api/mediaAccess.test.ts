import { afterEach, describe, expect, it, vi } from 'vitest';

// 모듈 안 기억(쿠키 표시)과 빌드 때 굳는 주소 값이 시험끼리 새지 않게 매번 새로 읽는다
async function load(base: string) {
  vi.resetModules();
  vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', base);
  return import('./mediaAccess');
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('영상 요청의 쿠키 싣기 (POK-317)', () => {
  it('출입증이 발급된 뒤에만 쿠키를 싣는다: CDN 뒤 영상은 쿠키가 없으면 403이다', async () => {
    const m = await load('https://media.test');

    expect(m.mediaCredentials()).toBe('same-origin');
    m.markPlaybackCookiesIssued();
    expect(m.mediaCredentials()).toBe('include');
  });
});

describe('출입증을 받을 구성인가 (playbackConfigured)', () => {
  it('주소 값이 비면 거짓이다: 출입증을 받지 않는다', async () => {
    expect((await load('')).playbackConfigured()).toBe(false);
  });

  it('주소 값이 있으면 끝 / 와 무관하게 참이다', async () => {
    expect((await load('https://media.test')).playbackConfigured()).toBe(true);
    expect((await load('https://media.test/')).playbackConfigured()).toBe(true);
  });
});
