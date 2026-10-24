import { describe, expect, it } from 'vitest';
import type { CaptureResult } from 'posthog-js';
import { scrubAttribute, scrubEvent, scrubUrl } from './scrub';

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

  it('바깥 주소는 origin만 남긴다 — 영상 ID·채널 ID가 경로에 실린다', () => {
    expect(scrubUrl('https://youtu.be/dQw4w9WgXcQ')).toBe('https://youtu.be');
    expect(scrubUrl('https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share')).toBe(
      'https://www.youtube.com',
    );
    expect(scrubUrl('https://chzzk.naver.com/abcdef0123456789')).toBe('https://chzzk.naver.com');
  });

  it('우리 주소(하위 도메인·지금 페이지 호스트)는 경로를 남긴다', () => {
    expect(scrubUrl('https://pokeclip.com/terms/')).toBe('https://pokeclip.com/terms/');
    expect(scrubUrl('https://dev.pokeclip.com/home')).toBe('https://dev.pokeclip.com/home');
    expect(scrubUrl(`${window.location.origin}/home?card=1`)).toBe(
      `${window.location.origin}/home`,
    );
  });

  it('API·재생 주소의 방송 번호를 가린다 — 녹화 네트워크 기록에 남는다', () => {
    expect(scrubUrl('https://app.pokeclip.com/api/clip/broadcasts/S-1/recipes/31/renders')).toBe(
      'https://app.pokeclip.com/api/clip/broadcasts/_/recipes/31/renders',
    );
    expect(scrubUrl('/api/clip/broadcasts/S-1/chat-messages?after=3')).toBe(
      '/api/clip/broadcasts/_/chat-messages',
    );
    expect(scrubUrl('/api/clip/broadcasts?state=live')).toBe('/api/clip/broadcasts');
    expect(scrubUrl('https://media.pokeclip.com/playback/S-1/index.m3u8')).toBe(
      'https://media.pokeclip.com/playback/_/index.m3u8',
    );
  });
});

describe('scrubEvent — PostHog before_send', () => {
  it.each([
    '/auth/callback',
    '/oauth/chzzk/callback',
    '/oauth/soop/callback',
    '/oauth/youtube/callback',
  ])('OAuth 콜백 %s 의 페이지 조회는 보내지 않는다', (path) => {
    const url = `https://app.pokeclip.com${path}?code=secret&state=s`;
    expect(scrubEvent(event('$pageview', { $current_url: url, $pathname: path }))).toBeNull();
    expect(scrubEvent(event('$pageleave', { $current_url: url }))).toBeNull();
  });

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
        $current_url:
          'https://pokeclip.com/?utm_source=discord&utm_campaign=launch_2026_10&email=a@b.c',
        $pathname: '/',
        $referrer: 'https://www.google.com/search?q=pokeclip',
        $prev_pageview_pathname: '/broadcast/vod/S-1',
        utm_source: 'discord',
        utm_campaign: 'launch_2026_10',
        $browser: 'Chrome',
        $device_type: 'Mobile',
      }),
    );
    expect(out?.properties).toMatchObject({
      $current_url: 'https://pokeclip.com/',
      $referrer: 'https://www.google.com',
      $prev_pageview_pathname: '/broadcast/vod/_',
      utm_source: 'discord',
      utm_campaign: 'launch_2026_10',
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

  it('홍보 링크 값은 README에 적은 값만 두고 나머지는 other — utm_term·광고 클릭 식별자는 지운다', () => {
    const out = scrubEvent(
      event(
        '$pageview',
        {
          utm_source: 'chzzk',
          utm_medium: 'profile',
          utm_campaign: 'launch_2026_10',
          utm_content: 'pinned_comment',
          utm_term: '게임하는너구리',
          gclid: 'Cj0-secret',
          fbclid: 'IwAR-secret',
        },
        {
          $set_once: {
            $initial_utm_source: 'a@b.c',
            $initial_utm_campaign: '2026_11_winter_event',
            $initial_utm_term: 'secret',
            $initial_gclid: 'Cj0-secret',
          },
        },
      ),
    );
    expect(out?.properties).toEqual({
      utm_source: 'chzzk',
      utm_medium: 'profile',
      utm_campaign: 'launch_2026_10',
      utm_content: 'pinned_comment',
    });
    expect(out?.$set_once).toEqual({
      $initial_utm_source: 'other',
      $initial_utm_campaign: '2026_11_winter_event',
    });
  });

  it('홍보 링크 값이 비었거나 없으면(null) 그대로 둔다', () => {
    const out = scrubEvent(event('$pageview', { utm_source: null, utm_medium: '' }));
    expect(out?.properties).toEqual({ utm_source: null, utm_medium: '' });
  });

  it('null은 null로 돌려준다(앞선 before_send가 이미 버린 이벤트)', () => {
    expect(scrubEvent(null)).toBeNull();
  });
});

describe('세션 녹화 정제(POK-358)', () => {
  it('녹화 이벤트의 rrweb 메타(시작 페이지 주소)에서 쿼리와 방송 번호를 뗀다', () => {
    const out = scrubEvent(
      event('$snapshot', {
        $snapshot_data: [
          {
            type: 4,
            data: { href: 'https://app.pokeclip.com/auth/callback?code=secret', width: 1 },
          },
          { type: 4, data: { href: 'https://app.pokeclip.com/broadcast/vod/S-1?stream=S-1' } },
          { type: 3, data: { source: 0, adds: [] } },
        ],
      }),
    );
    expect(JSON.stringify(out)).not.toMatch(/secret|S-1/);
    expect(out?.properties.$snapshot_data[0].data).toEqual({
      href: 'https://app.pokeclip.com/auth/callback',
      width: 1,
    });
  });

  it('요소 주소 속성(href·src 등)은 쿼리와 방송 번호를 뗀다', () => {
    expect(scrubAttribute('href', '/clips/editor/studio?stream=S-1&card=5')).toBe(
      '/clips/editor/studio',
    );
    expect(scrubAttribute('src', 'https://app.pokeclip.com/api/profile-photos/7?token=t')).toBe(
      'https://app.pokeclip.com/api/profile-photos/7',
    );
    expect(scrubAttribute('href', '/broadcast/vod/S-1')).toBe('/broadcast/vod/_');
  });

  it('보관함의 YouTube 링크는 영상 ID 없이 origin만 남긴다 — 처리방침 13절', () => {
    expect(scrubAttribute('href', 'https://youtu.be/dQw4w9WgXcQ')).toBe('https://youtu.be');
  });

  it('글 속성(aria-label·title·alt·placeholder 등)은 글자 수만 남긴다 — 채널·회원 이름이 실린다', () => {
    expect(scrubAttribute('aria-label', '게임하는너구리 연동 해제')).toBe('*'.repeat(13));
    expect(scrubAttribute('title', 'a@b.c')).toBe('*****');
    expect(scrubAttribute('alt', '프로필')).toBe('***');
    expect(scrubAttribute('placeholder', '이메일')).toBe('***');
  });

  it('그 밖의 속성(class·role·data-*)은 그대로 둔다 — 화면을 다시 그리는 데 필요하다', () => {
    expect(scrubAttribute('class', 'chip ?x')).toBe('chip ?x');
    expect(scrubAttribute('role', 'dialog')).toBe('dialog');
  });
});
