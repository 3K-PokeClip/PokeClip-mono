import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CaptureResult } from 'posthog-js';
import { PRIVATE_TEXT_CLASS } from '@/ui/privateText';
import {
  maskReplayText,
  REPLAY_BLOCK_SELECTOR,
  scrubAttribute,
  scrubEvent,
  scrubUrl,
} from './scrub';

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

  it('라이브 LL-HLS 재생목록·조각 주소의 송출 키 폴더를 가린다 — 우리 호스트라 경로가 남는다 (PR #382 리뷰)', () => {
    expect(scrubUrl('https://media.pokeclip.com/ab12cd34key/index.m3u8?_HLS_msn=3')).toBe(
      'https://media.pokeclip.com/_/index.m3u8',
    );
    expect(scrubUrl('https://media.pokeclip.com/ab12cd34key/0f2a_seg12.mp4')).toBe(
      'https://media.pokeclip.com/_/0f2a_seg12.mp4',
    );
    expect(scrubUrl('https://media.pokeclip.com/playback/S-1/index.m3u8')).toBe(
      'https://media.pokeclip.com/playback/_/index.m3u8',
    );
  });
});

describe('scrubUrl — 라이브 재생 base(NEXT_PUBLIC_MEDIA_LIVE_BASE_URL)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('base 뒤 첫 경로 조각(송출 키)을 가린다 — base 경로가 깊어도', async () => {
    vi.stubEnv('NEXT_PUBLIC_MEDIA_LIVE_BASE_URL', 'https://media.pokeclip.com/live/');
    vi.resetModules();
    const fresh = await import('./scrub');
    expect(fresh.scrubUrl('https://media.pokeclip.com/live/ab12cd34key/v1/index.m3u8?x=1')).toBe(
      'https://media.pokeclip.com/live/_/_/index.m3u8',
    );
    expect(fresh.scrubUrl('https://media.pokeclip.com/live/ab12cd34key')).toBe(
      'https://media.pokeclip.com/live/_',
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
            $initial_utm_campaign: '2026_11_abcdef0123456789abcdef0123456789',
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
      $initial_utm_campaign: 'other', // 모양만 맞는 캠페인(채널 ID 꼴)은 목록에 없으니 other다
    });
  });

  it('세션 첫 진입($session_entry_*)·Meta 식별자($fbc·$fbp)도 같은 규칙이다 — SDK가 모든 이벤트에 싣는다 (PR #382 3차 리뷰)', () => {
    // posthog-js 1.435.8 SessionPropsManager.getSessionProps()가 첫 진입 속성에 붙이는 이름 그대로
    const out = scrubEvent(
      event(
        'login_completed',
        {
          $session_entry_utm_source: 'person@example.com',
          $session_entry_utm_medium: 'profile',
          $session_entry_utm_term: '게임하는너구리',
          $session_entry_fbclid: 'IwAR-secret',
          $session_entry_gclid: 'Cj0-secret',
          bfcid: 'bf-secret',
          $session_entry_referring_domain: 'chzzk.naver.com',
        },
        {
          $set: { $fbc: 'fb.1.1700000000000.IwAR-secret', $fbp: 'fb.1.1700000000000.123' },
          $set_once: { $initial_bfcid: 'bf-secret' },
        },
      ),
    );
    expect(out?.properties).toEqual({
      $session_entry_utm_source: 'other',
      $session_entry_utm_medium: 'profile',
      $session_entry_referring_domain: 'chzzk.naver.com',
    });
    expect(out?.$set).toEqual({});
    expect(out?.$set_once).toEqual({});
    expect(JSON.stringify(out)).not.toMatch(/secret|person@|너구리/);
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

  it('우리 그림(public/brand/)의 src·srcset은 쿼리째 둔다 — 떼면 next/image 주소가 깨진다 (POK-572)', () => {
    const next = '/_next/image?url=%2Fbrand%2Fpoki-wave.webp&w=1080&q=75';
    expect(scrubAttribute('src', next)).toBe(next);
    expect(scrubAttribute('src', `http://localhost:3000${next}`)).toBe(
      `http://localhost:3000${next}`,
    );
    const srcset = `${next} 1x, /_next/image?url=%2Fbrand%2Fpoki-wave.webp&w=3840&q=75 2x`;
    expect(scrubAttribute('srcset', srcset)).toBe(srcset);
    expect(scrubAttribute('src', '/brand/poki-run.webp')).toBe('/brand/poki-run.webp');
  });

  it('우리 그림이 아니면 src·srcset 쿼리를 뗀다 — 하나라도 섞이면, 바깥 호스트의 같은 경로도', () => {
    expect(
      scrubAttribute('src', '/_next/image?url=https%3A%2F%2Fcdn.example.com%2Fa.jpg&w=640'),
    ).toBe('/_next/image');
    expect(
      scrubAttribute(
        'srcset',
        '/_next/image?url=%2Fbrand%2Fa.webp&w=1 1x, /api/profile-photos/7?token=t 2x',
      ),
    ).not.toContain('token');
    expect(
      scrubAttribute('src', 'https://evil.example.com/_next/image?url=%2Fbrand%2Fa.webp&sig=s'),
    ).toBe('https://evil.example.com');
  });

  it('보관함의 YouTube 링크는 영상 ID 없이 origin만 남긴다 — 처리방침 13절', () => {
    expect(scrubAttribute('href', 'https://youtu.be/dQw4w9WgXcQ')).toBe('https://youtu.be');
  });

  it('글 속성(aria-label·title·alt 등)은 글자 수만 남긴다 — 화면에 안 보이고 채널·회원 이름이 실린다', () => {
    expect(scrubAttribute('aria-label', '게임하는너구리 연동 해제')).toBe('*'.repeat(13));
    expect(scrubAttribute('title', 'a@b.c')).toBe('*****');
    expect(scrubAttribute('alt', '프로필')).toBe('***');
  });

  it('placeholder는 글자와 같은 규칙 — 가림 칸 밖이면 보이고, 안이거나 요소를 모르면 가린다 (POK-572)', () => {
    const plain = document.createElement('input');
    expect(scrubAttribute('placeholder', '검색어 입력', plain)).toBe('검색어 입력');
    const masked = document.createElement('div');
    masked.className = PRIVATE_TEXT_CLASS;
    const inner = document.createElement('input');
    masked.append(inner);
    expect(scrubAttribute('placeholder', '게임하는너구리', inner)).toBe('*******');
    expect(scrubAttribute('placeholder', '이메일')).toBe('***');
  });

  it('그 밖의 속성(class·role·data-*)은 그대로 둔다 — 화면을 다시 그리는 데 필요하다', () => {
    expect(scrubAttribute('class', 'chip ?x')).toBe('chip ?x');
    expect(scrubAttribute('role', 'dialog')).toBe('dialog');
  });
});

describe('maskReplayText — 녹화 글자 가림 (POK-572)', () => {
  function el(html: string, selector: string): Element {
    const root = document.createElement('div');
    root.innerHTML = html;
    const found = root.querySelector(selector);
    if (found === null) throw new Error(selector);
    return found;
  }

  it('가림 칸 밖의 글자(단추·메뉴·안내문)는 그대로 둔다', () => {
    expect(maskReplayText('클립 만들기', el('<button>클립 만들기</button>', 'button'))).toBe(
      '클립 만들기',
    );
  });

  it('가림 칸 안의 글자는 공백만 남기고 가린다 — 조상에 달아도 된다', () => {
    const name = el(`<div class="${PRIVATE_TEXT_CLASS}"><p><b>게임 하는 너구리</b></p></div>`, 'b');
    expect(maskReplayText('게임 하는 너구리', name)).toBe('** ** ***');
  });

  it('가림 칸 밖이라도 이메일 꼴은 가린다 — 표시를 빠뜨린 칸의 그물', () => {
    const p = el('<p>초대 메일을 보냈어요</p>', 'p');
    expect(maskReplayText('raccoon.gamer@example.com 님께 보냈어요', p)).toBe(
      '************************* 님께 보냈어요',
    );
  });

  it('<title>은 늘 가린다 — 문서 제목·SVG 툴팁에 방송·채널 이름이 실린다', () => {
    const svgTitle = document.createElementNS('http://www.w3.org/2000/svg', 'title');
    expect(maskReplayText('너구리 방송', svgTitle)).toBe('*** **');
    expect(maskReplayText('PokeClip', document.createElement('title'))).toBe('********');
  });

  it('어느 요소의 글자인지 모르거나 판단이 실패하면 가린다', () => {
    expect(maskReplayText('너구리', null)).toBe('***');
    expect(maskReplayText('너구리', undefined)).toBe('***');
    const broken = {
      tagName: 'P',
      closest: () => {
        throw new Error('detached');
      },
    } as unknown as Element;
    expect(maskReplayText('너구리', broken)).toBe('***');
  });
});

describe('REPLAY_BLOCK_SELECTOR — 녹화에서 빼는 요소 (POK-572)', () => {
  function img(src: string | null): HTMLImageElement {
    const image = document.createElement('img');
    if (src !== null) image.setAttribute('src', src);
    return image;
  }

  it('우리 그림(public/brand/)만 남긴다 — next/image 주소 꼴도', () => {
    expect(img('/brand/poki-wave.webp').matches(REPLAY_BLOCK_SELECTOR)).toBe(false);
    expect(
      img('/_next/image?url=%2Fbrand%2Fpoki-wave.webp&w=1080&q=75').matches(REPLAY_BLOCK_SELECTOR),
    ).toBe(false);
  });

  it('썸네일·프로필 사진·src 없는 이미지는 뺀다', () => {
    expect(img('https://cdn.example.com/thumb.jpg?sig=x').matches(REPLAY_BLOCK_SELECTOR)).toBe(
      true,
    );
    expect(
      img('/_next/image?url=https%3A%2F%2Fcdn.example.com%2Fbrand%2Fa.jpg').matches(
        REPLAY_BLOCK_SELECTOR,
      ),
    ).toBe(true);
    expect(img('/api/profile-photos/7?token=t').matches(REPLAY_BLOCK_SELECTOR)).toBe(true);
    expect(img('data:image/png;base64,AAAA').matches(REPLAY_BLOCK_SELECTOR)).toBe(true);
    expect(img(null).matches(REPLAY_BLOCK_SELECTOR)).toBe(true);
  });

  it('영상·캔버스·iframe·picture는 늘 뺀다', () => {
    for (const tag of ['video', 'canvas', 'iframe', 'picture']) {
      expect(document.createElement(tag).matches(REPLAY_BLOCK_SELECTOR)).toBe(true);
    }
  });
});
