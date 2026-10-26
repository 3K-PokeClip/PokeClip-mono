import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSession, SessionOptions } from '@/features/player/usePlaybackSession';
import { useLiveSession, type LiveSessionInput } from './useLiveSession';

// useLiveSession 의 배선(cc c10 L2) — 세션에 넘기는 지금 방송 번호 묻기가 최신 방송|영상 키를 싣고, 그 기억이 렌더를 넘어 산다.
// 세션 훅 · 라우터 · 방송 조회는 흉내 낸다: 시험은 세션 훅이 받은 resolveX 를 직접 부른다.

const hoisted = vi.hoisted(() => ({
  search: '',
  options: null as SessionOptions | null,
  session: null as PlaybackSession | null,
  query: vi.fn(async () => 'r-A' as string | null),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/broadcast/livenow',
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));
vi.mock('@/features/channel/ChannelScope', () => ({ useChannelStreamerId: () => 'streamer-1' }));
vi.mock('@/features/broadcast/streamSelection', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/broadcast/streamSelection')>()),
  resolveLiveStreamId: hoisted.query,
}));
vi.mock('@/features/player/usePlaybackSession', () => ({
  usePlaybackSession: (options: SessionOptions) => {
    hoisted.options = options;
    return hoisted.session;
  },
}));
vi.mock('@/ui', () => ({ useToast: () => ({ toast: vi.fn() }) }));

function input(patch: Partial<LiveSessionInput> = {}): LiveSessionInput {
  return {
    liveStreamId: 'r-A',
    mediaKey: 'key-1',
    accessKey: 'r-A|key-1',
    accessReady: true,
    broadcastStatus: 'live',
    startedAt: null,
    endedAt: null,
    liveUrl: 'https://m/live/key-1/index.m3u8',
    reselect: { deferred: null, latest: 'r-A' },
    ...patch,
  };
}

/** 세션 훅이 받은 지금 방송 번호 묻기(가장 최근 렌더의 것) */
function resolveX(fresh: boolean) {
  const options = hoisted.options;
  if (options === null) throw new Error('세션 훅이 불리지 않았다');
  return options.resolveX(fresh);
}

beforeEach(() => {
  hoisted.search = '';
  hoisted.options = null;
  hoisted.session = null;
  hoisted.query.mockClear();
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('useLiveSession: 고정 화면의 지금 방송 번호 묻기 배선(cc c10 L2)', () => {
  it('기억은 렌더를 넘어 살고(같은 키면 다시 묻지 않음), 방송|영상 키가 바뀌면 그 키로 새로 묻는다', async () => {
    hoisted.search = '?stream=r-A';
    const { rerender } = renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input(),
    });
    await expect(resolveX(false)).resolves.toEqual({ kind: 'known', id: 'r-A' });
    expect(hoisted.query).toHaveBeenCalledTimes(1);

    // 같은 채널 · 같은 키의 다른 렌더 — 기억(10초)을 다시 쓴다
    rerender(input({ startedAt: Date.now() }));
    await resolveX(false);
    expect(hoisted.query).toHaveBeenCalledTimes(1);

    // 다른 방송|영상 키 — 앞 키의 기억을 쓰지 않는다
    rerender(input({ liveStreamId: 'r-B', accessKey: 'r-B|key-1' }));
    await resolveX(false);
    expect(hoisted.query).toHaveBeenCalledTimes(2);
  });
});

describe('useLiveSession: 띠는 하나만(cc L)', () => {
  function sessionWithBanner(banner: { goTo: string } | null): PlaybackSession {
    return {
      view: { banner },
      dispatch: vi.fn(),
    } as unknown as PlaybackSession;
  }

  it('세션 덮개가 다른 방송 띠를 세웠으면 화면의 「새 방송」 띠는 서지 않는다 — 없으면 선다', () => {
    const reselect = { deferred: 'r-B', latest: 'r-B' };
    hoisted.session = sessionWithBanner(null);
    const plain = renderHook(() => useLiveSession(input({ reselect })));
    expect(plain.result.current.band).toBe('r-B');
    plain.unmount();

    hoisted.session = sessionWithBanner({ goTo: 'r-B' });
    const covered = renderHook(() => useLiveSession(input({ reselect })));
    expect(covered.result.current.band).toBeNull();
  });
});
