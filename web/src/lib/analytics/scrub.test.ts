import { describe, expect, it } from 'vitest';
import type { CaptureResult } from 'posthog-js';
import { scrubEvent, scrubUrl } from './scrub';

function event(
  name: string,
  properties: Record<string, unknown>,
  extra: Partial<CaptureResult> = {},
) {
  return { uuid: 'u-1', event: name, properties, ...extra } as CaptureResult;
}

describe('scrubUrl — 주소에서 비밀값을 뗀다(POK-356)', () => {
  it('쿼리와 해시를 통째로 뗀다', () => {
    expect(scrubUrl('https://app.pokeclip.com/clips/editor/studio?card=c-1&stream=S-1#t=3')).toBe(
      'https://app.pokeclip.com/clips/editor/studio',
    );
    expect(scrubUrl('/broadcast/livenow?stream=S-1')).toBe('/broadcast/livenow');
  });

  it('다시보기 주소의 방송 번호를 가린다 — 예전 방송 번호는 스트림 키와 같다', () => {
    expect(scrubUrl('https://app.pokeclip.com/broadcast/vod/live_abc123?x=1')).toBe(
      'https://app.pokeclip.com/broadcast/vod/_',
    );
    expect(scrubUrl('/broadcast/vod/S-20261017-120000-key-1')).toBe('/broadcast/vod/_');
    expect(scrubUrl('/broadcast/vod')).toBe('/broadcast/vod');
  });

  it('주소가 아닌 값은 그대로 둔다', () => {
    expect(scrubUrl('Chrome')).toBe('Chrome');
    expect(scrubUrl('')).toBe('');
  });
});

describe('scrubEvent — PostHog before_send', () => {
  it.each(['/auth/callback', '/oauth/chzzk/callback', '/oauth/youtube/callback'])(
    'OAuth 콜백 %s 의 페이지 조회는 보내지 않는다',
    (path) => {
      const url = `https://app.pokeclip.com${path}?code=secret&state=s`;
      expect(scrubEvent(event('$pageview', { $current_url: url, $pathname: path }))).toBeNull();
      expect(scrubEvent(event('$pageleave', { $current_url: url }))).toBeNull();
    },
  );

  it('콜백 화면에서 보낸 행동 이벤트는 남기되 주소의 code·state는 뗀다', () => {
    const out = scrubEvent(
      event('login_completed', {
        $current_url: 'https://app.pokeclip.com/auth/callback?code=secret&state=s',
        $pathname: '/auth/callback',
      }),
    );
    expect(out?.properties.$current_url).toBe('https://app.pokeclip.com/auth/callback');
    expect(JSON.stringify(out)).not.toContain('secret');
  });

  it('주소·경로 속성은 정제하고 utm_* 와 다른 값은 건드리지 않는다', () => {
    const out = scrubEvent(
      event('$pageview', {
        $current_url: 'https://pokeclip.com/?utm_source=discord&utm_campaign=launch&email=a@b.c',
        $pathname: '/',
        $referrer: 'https://www.google.com/search?q=pokeclip',
        $prev_pageview_pathname: '/broadcast/vod/S-1',
        utm_source: 'discord',
        utm_campaign: 'launch',
        $browser: 'Chrome',
        $device_type: 'Mobile',
      }),
    );
    expect(out?.properties).toMatchObject({
      $current_url: 'https://pokeclip.com/',
      $referrer: 'https://www.google.com/search',
      $prev_pageview_pathname: '/broadcast/vod/_',
      utm_source: 'discord',
      utm_campaign: 'launch',
      $browser: 'Chrome',
      $device_type: 'Mobile',
    });
  });

  it('사람 속성($set·$set_once)의 처음 주소도 정제한다 — 바깥 필드와 properties 안 둘 다', () => {
    const out = scrubEvent(
      event(
        '$identify',
        {
          $set_once: { $initial_current_url: 'https://app.pokeclip.com/auth/callback?code=secret' },
        },
        {
          $set_once: { $initial_pathname: '/broadcast/vod/S-1', $initial_referrer: '$direct' },
          $set: { $current_url: 'https://app.pokeclip.com/home?card=c-1' },
        },
      ),
    );
    expect(JSON.stringify(out)).not.toMatch(/secret|card=|S-1/);
    expect(out?.$set_once?.$initial_referrer).toBe('$direct');
  });

  it('null은 null로 돌려준다(앞선 before_send가 이미 버린 이벤트)', () => {
    expect(scrubEvent(null)).toBeNull();
  });
});
