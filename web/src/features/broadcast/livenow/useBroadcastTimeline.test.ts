import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BroadcastTimeline } from '@/api/clipEditor';
import { createTestQueryClient, makeQueryWrapper } from '@/test/testProviders';

// 방송 중 시점별 기록을 다시 읽는 주기(POK-552): 첫 카테고리 사진 전에는 짧게, 사진이 오면 30초다. 창구 답은 가로채 흉내 낸다
const api = vi.hoisted(() => ({
  reads: 0,
  timeline: { segments: [], truncated: false } as BroadcastTimeline,
  /** 참이면 다음 읽기는 release()까지 답하지 않는다. 답은 떠난 때의 기록이다 */
  holdNext: false,
  release: null as (() => void) | null,
}));
vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  fetchBroadcastTimeline: async () => {
    api.reads += 1;
    const snapshot = api.timeline;
    if (api.holdNext) {
      api.holdNext = false;
      await new Promise<void>((resolve) => {
        api.release = resolve;
      });
    }
    return snapshot;
  },
}));

import {
  TIMELINE_FIRST_IMAGE_MS,
  TIMELINE_FIRST_IMAGE_TRIES,
  TIMELINE_LIVE_MS,
  liveTimelineIntervalMs,
  refetchTimelineForFirstImage,
  useBroadcastTimeline,
} from './useBroadcastTimeline';

const segment = (
  categoryImageUrl: string | null,
  platform = 'CHZZK',
): BroadcastTimeline['segments'][number] => ({
  platform,
  from: '2026-11-04T00:00:00Z',
  until: null,
  title: '제목',
  category: 'talk',
  categoryImageUrl,
  tags: [],
});
const timelineOf = (...segments: BroadcastTimeline['segments']): BroadcastTimeline => ({
  segments,
  truncated: false,
});

describe('liveTimelineIntervalMs', () => {
  it('사진이 없으면 짧게 묻는다: 기록을 못 읽음 · 구간 없음 · 사진 칸이 빈 구간', () => {
    expect(liveTimelineIntervalMs(undefined, 0)).toBe(TIMELINE_FIRST_IMAGE_MS);
    expect(liveTimelineIntervalMs(timelineOf(), 1)).toBe(TIMELINE_FIRST_IMAGE_MS);
    expect(liveTimelineIntervalMs(timelineOf(segment(null)), 1)).toBe(TIMELINE_FIRST_IMAGE_MS);
  });

  it('사진이 생기면 30초다: 바뀐 카테고리는 지금처럼 1분 안에 따라온다', () => {
    expect(
      liveTimelineIntervalMs(timelineOf(segment(null), segment('https://img/talk.png')), 1),
    ).toBe(TIMELINE_LIVE_MS);
  });

  it('동시 송출이면 기록에 오른 플랫폼마다 사진이 생겨야 30초다: 정보 바는 시청자가 많은 플랫폼 사진을 쓴다', () => {
    const chzzkOnly = timelineOf(segment('https://img/talk.png'), segment(null, 'SOOP'));
    expect(liveTimelineIntervalMs(chzzkOnly, 1)).toBe(TIMELINE_FIRST_IMAGE_MS);
    const both = timelineOf(
      segment('https://img/talk.png'),
      segment('https://img/talk.png', 'SOOP'),
    );
    expect(liveTimelineIntervalMs(both, 1)).toBe(TIMELINE_LIVE_MS);
  });

  it('짧은 간격을 다 쓰면 사진이 없어도 30초다: 사진을 끝내 못 받는 방송이 5초로 계속 묻지 않게', () => {
    expect(liveTimelineIntervalMs(timelineOf(), TIMELINE_FIRST_IMAGE_TRIES - 1)).toBe(
      TIMELINE_FIRST_IMAGE_MS,
    );
    expect(liveTimelineIntervalMs(timelineOf(), TIMELINE_FIRST_IMAGE_TRIES)).toBe(TIMELINE_LIVE_MS);
  });
});

describe('useBroadcastTimeline: 방송 중 다시 읽는 주기', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    api.reads = 0;
    api.timeline = timelineOf();
    api.holdNext = false;
    api.release = null;
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const advance = (ms: number) =>
    act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });

  function mount() {
    return renderHook(() => useBroadcastTimeline('s1', 'live'), {
      wrapper: makeQueryWrapper(createTestQueryClient()),
    });
  }

  it('첫 사진 전에는 5초마다 다시 읽고, 사진이 오면 30초 주기로 돌아간다', async () => {
    mount();
    await advance(0);
    expect(api.reads).toBe(1);
    await advance(TIMELINE_FIRST_IMAGE_MS);
    expect(api.reads).toBe(2);

    // 수집기의 첫 관측이 기록에 적혔다
    api.timeline = timelineOf(segment('https://img/talk.png'));
    await advance(TIMELINE_FIRST_IMAGE_MS);
    expect(api.reads).toBe(3);

    // 사진을 받은 뒤로는 30초 주기다
    await advance(TIMELINE_LIVE_MS - 1_000);
    expect(api.reads).toBe(3);
    await advance(1_000);
    expect(api.reads).toBe(4);
  });

  it('사진을 끝내 못 받으면 짧은 간격을 다 쓴 뒤 30초 주기로 돌아간다', async () => {
    mount();
    await advance(0);
    for (let i = 1; i < TIMELINE_FIRST_IMAGE_TRIES; i += 1) await advance(TIMELINE_FIRST_IMAGE_MS);
    expect(api.reads).toBe(TIMELINE_FIRST_IMAGE_TRIES);

    await advance(TIMELINE_LIVE_MS - 1_000);
    expect(api.reads).toBe(TIMELINE_FIRST_IMAGE_TRIES);
    await advance(1_000);
    expect(api.reads).toBe(TIMELINE_FIRST_IMAGE_TRIES + 1);
  });

  it('통로가 사진을 싣고 오면 기록에 사진이 없을 때만 바로 다시 읽는다', async () => {
    const client = createTestQueryClient();
    renderHook(() => useBroadcastTimeline('s1', 'live'), { wrapper: makeQueryWrapper(client) });
    await advance(0);
    expect(api.reads).toBe(1);

    // 사진을 안 실은 관측(SOOP이 사진을 못 빌림 · 옛 서버)은 다시 읽어도 사진이 없다
    act(() => refetchTimelineForFirstImage(client, 's1', null));
    act(() => refetchTimelineForFirstImage(client, 's1', undefined));
    await advance(0);
    expect(api.reads).toBe(1);

    // 첫 관측: 수집기가 기록에 적은 뒤 통로로 민다
    api.timeline = timelineOf(segment('https://img/talk.png'));
    act(() => refetchTimelineForFirstImage(client, 's1', 'https://img/talk.png'));
    await advance(0);
    expect(api.reads).toBe(2);

    // 사진이 생긴 뒤로는 통로가 와도 30초 주기를 따른다: 첫 사진만 앞당긴다
    act(() => refetchTimelineForFirstImage(client, 's1', 'https://img/game.png'));
    await advance(0);
    expect(api.reads).toBe(2);
  });

  it('동시 송출이면 그 관측의 플랫폼 구간에 사진이 없을 때 다시 읽는다: 다른 플랫폼 사진으로 갈음하지 않는다', async () => {
    const client = createTestQueryClient();
    // 치지직 관측이 먼저 적혔다. SOOP은 아직 사진이 없다
    api.timeline = timelineOf(segment('https://img/talk.png'), segment(null, 'SOOP'));
    renderHook(() => useBroadcastTimeline('s1', 'live'), { wrapper: makeQueryWrapper(client) });
    await advance(0);
    expect(api.reads).toBe(1);

    // 치지직 관측은 이미 사진이 있다
    act(() => refetchTimelineForFirstImage(client, 's1', 'https://img/talk.png', 'CHZZK'));
    await advance(0);
    expect(api.reads).toBe(1);

    // SOOP 관측이 빌린 사진을 싣고 왔다: 정보 바가 SOOP을 보면 이 사진을 기다린다
    api.timeline = timelineOf(
      segment('https://img/talk.png'),
      segment('https://img/talk.png', 'SOOP'),
    );
    act(() => refetchTimelineForFirstImage(client, 's1', 'https://img/talk.png', 'SOOP'));
    await advance(0);
    expect(api.reads).toBe(2);
  });

  it('첫 답을 기다리는 사이 통로가 오면 떠난 읽기를 버리고 다시 읽는다: 그 읽기는 관측이 적히기 전 기록일 수 있다', async () => {
    api.holdNext = true;
    const client = createTestQueryClient();
    const { result } = renderHook(() => useBroadcastTimeline('s1', 'live'), {
      wrapper: makeQueryWrapper(client),
    });
    await advance(0);
    expect(api.reads).toBe(1);

    api.timeline = timelineOf(segment('https://img/talk.png'));
    act(() => refetchTimelineForFirstImage(client, 's1', 'https://img/talk.png'));
    await advance(0);
    expect(api.reads).toBe(2);
    expect(result.current?.segments[0]?.categoryImageUrl).toBe('https://img/talk.png');

    // 버린 읽기의 늦은 답(관측 전 기록)은 사진을 지우지 못한다
    act(() => api.release?.());
    await advance(0);
    expect(result.current?.segments[0]?.categoryImageUrl).toBe('https://img/talk.png');
  });

  it('끝난 방송은 한 번만 읽는다(그대로)', async () => {
    renderHook(() => useBroadcastTimeline('s1', 'ended'), {
      wrapper: makeQueryWrapper(createTestQueryClient()),
    });
    await advance(0);
    await advance(TIMELINE_LIVE_MS * 2);
    expect(api.reads).toBe(1);
  });
});
