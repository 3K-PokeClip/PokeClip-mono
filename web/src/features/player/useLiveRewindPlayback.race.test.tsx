import { act, render } from '@testing-library/react';
import { useLayoutEffect, useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import type { LivePosition } from './livePosition';
import type { SessionEvent, XAnswer } from './rewindSwitch';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionOptions,
} from './usePlaybackSession';
import { REWIND_POLL_MS } from './useRewindStatus';

// 커밋과 위치 효과 사이의 시크(POK-200 c13 · cx H1). 재생기가 잰 자리는 그리기에 커밋된 뒤
// 수동 효과(useEffect)가 세션에 올린다. 그 사이에 사람이 시크하면 효과는 시크 전에 잰 자리를
// 시크 뒤의 세션에 올린다. 시험은 레이아웃 효과에서 시크를 넣어 이 순서를 만든다 — 레이아웃
// 효과는 같은 커밋의 수동 효과보다 먼저 돈다. 재생기(useHlsPlayback)는 자리만 시험이 쥔다.

const player = vi.hoisted(() => ({
  livePosition: null as LivePosition | null,
  measuredAtMs: null as number | null,
}));

vi.mock('./useHlsPlayback', () => ({
  useHlsPlayback: () => ({
    livePosition: player.livePosition,
    positionMeasuredAtMs: player.measuredAtMs,
    seekToMs: () => {},
    seekToLiveEdge: () => {},
    keepPausedOnSwap: () => {},
  }),
}));

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const LIVE = `${BASE}/live/key-1/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
/** 되감기 목록 끝 E · 목표 길이 4초 — 띠 = E − 4000 부터 */
const E = NOW - 2_000;
const IN_BAND = E - 1_000;

let session: PlaybackSession | null = null;
/** 다음 커밋의 레이아웃 효과에서 한 번 낼 사건(커밋과 위치 효과 사이) */
let interlude: SessionEvent | null = null;

function Player({ playback }: { playback: PlaybackSession }) {
  const ref = useRef<HTMLVideoElement>(null);
  useLiveRewindPlayback(ref, playback);
  useLayoutEffect(() => {
    if (interlude === null) return;
    const event = interlude;
    interlude = null;
    playback.dispatch(event);
  });
  return <video ref={ref} />;
}

function Screen({ options }: { options: SessionOptions }) {
  const playback = usePlaybackSession(options);
  session = playback;
  return playback === null ? null : <Player playback={playback} />;
}

function options(): SessionOptions {
  return {
    canOpen: true,
    roundId: 'r-A',
    accessKey: 'r-A|key-1',
    liveUrl: LIVE,
    controlKey: 'key-1',
    controlBase: BASE,
    resolveX: () => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }),
    onClose: vi.fn(),
  };
}

async function settle() {
  await act(async () => {
    for (let i = 0; i < 6; i += 1) await Promise.resolve();
  });
}

/** 재생기가 atMs 를 잰 그리기를 커밋한다. 시각을 주지 않으면 지금 잰 것이다 */
function measure(view: ReturnType<typeof render>, atMs: number, measuredAtMs = Date.now()) {
  player.livePosition = { atMs, edgeAtMs: NOW, behindSeconds: 1, playing: true, runMs: 0 };
  player.measuredAtMs = measuredAtMs;
  act(() => view.rerender(<Screen options={options()} />));
}

const list: SessionEvent = {
  type: 'list-loaded',
  url: A1,
  firstMs: NOW - 3_600_000,
  lastEndMs: E,
  targetDurationS: 4,
  headSpanMs: 12_000,
};

/** 다음 폴링 한 번(문을 세운다) 뒤 라이브 창 탐침을 나간 횟수 */
let fetchSpy: ReturnType<typeof stubFetch>;
async function probesAfterPoll(): Promise<number> {
  vi.advanceTimersByTime(REWIND_POLL_MS);
  await settle();
  return fetchSpy.mock.calls.filter(([url]) => url === LIVE).length;
}

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  session = null;
  interlude = null;
  player.livePosition = null;
  player.measuredAtMs = null;
  fetchSpy = stubFetch((url) =>
    url === STREAM
      ? jsonResponse(200, {
          ready: true,
          mode: 'live',
          url: A1,
          firstPdt: new Date(NOW - 3_600_000).toISOString(),
          headPdt: new Date(E).toISOString(),
        })
      : jsonResponse(404),
  );
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('커밋과 위치 효과 사이의 시크 — 시크 전에 잰 자리를 버린다(cx H1)', () => {
  it('라이브 자리를 잰 커밋 뒤 되감으면, 새 재생기의 첫 위치 전에는 탐침을 청하지 않는다', async () => {
    const view = render(<Screen options={options()} />);
    await settle();
    expect(session?.state.rewindOpen).toBe(true);
    // 라이브 재생기가 띠 안 자리를 잰 커밋 — 그 위치 효과 전에 되감는다(세대 0 → 1)
    const target = { lowerMs: NOW - 5_000, endMs: NOW };
    interlude = { type: 'seek', targetMs: NOW - 30_000, view: target };
    measure(view, IN_BAND, Date.now() - 1);
    expect(session?.state.source).toMatchObject({ kind: 'rewind', generation: 1 });
    act(() => session?.dispatch(list));
    expect(await probesAfterPoll()).toBe(0);
    // 새 재생기가 띠 안 자리를 재면 현행대로 청한다
    vi.advanceTimersByTime(1);
    measure(view, IN_BAND + 500);
    expect(await probesAfterPoll()).toBe(1);
  });

  it('되감기 띠 안 자리를 잰 커밋 뒤 안에서 뒤로 시크하면, 시크 전 자리로 탐침을 청하지 않는다', async () => {
    const view = render(<Screen options={options()} />);
    await settle();
    const enter = { lowerMs: NOW - 5_000, endMs: NOW };
    act(() => session?.dispatch({ type: 'seek', targetMs: NOW - 30_000, view: enter }));
    act(() => session?.dispatch(list));
    vi.advanceTimersByTime(1);
    measure(view, E - 20_000);
    // 띠 안 자리를 잰 커밋 — 그 위치 효과 전에 같은 소스 안에서 뒤로 시크한다
    const within = { lowerMs: NOW - 3_600_000, endMs: E };
    interlude = { type: 'seek', targetMs: E - 60_000, view: within };
    measure(view, IN_BAND, Date.now() - 1);
    expect(session?.state.lastAtMs).toBe(E - 20_000);
    expect(await probesAfterPoll()).toBe(0);
  });
});
