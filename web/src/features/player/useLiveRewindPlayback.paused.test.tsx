import { act, render } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { handedOff } from './handoffTestKit';
import { transition, type XAnswer } from './rewindSwitch';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionOptions,
} from './usePlaybackSession';

// 멈춘 채 넘김 확인이 깨질 때(설계 16절 6 · kty 결정) — 세션 · 재생기 · hls.js 흉내를 한 화면에 묶어 끝단을 잰다.
// 영상이 아직 붙지 않아(readyState 1) 위치 사건이 막힌 동안 멈춰도, 되돌린 되감기 재생기가 스스로 틀지 않아야 한다(c13f
// cx H1).

vi.mock('./rewindSwitch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./rewindSwitch')>();
  return { ...actual, transition: vi.fn(actual.transition) };
});

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static isSupported = () => true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    ERROR: 'hlsError',
  };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static ErrorDetails = { LEVEL_LOAD_ERROR: 'levelLoadError' };
  readonly handlers = new Map<string, Handler[]>();
  latestLevelDetails = null;
  liveSyncPosition = null;
  constructor() {
    fake.instances.push(this);
  }
  on(event: string, handler: Handler) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  once(event: string, handler: Handler) {
    this.on(event, handler);
  }
  trigger(event: string, data: unknown) {
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  stopLoad() {}
  destroy() {}
}

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const P = NOW - 6_000;
const E = NOW - 2_000;
/** 시한 = 넘긴 때 + (E − P) + 2 × TD */
const DEADLINE_AFTER_MS = 12_000;

const media = { readyState: 4, paused: false };
let session: PlaybackSession | null = null;

function Player({ playback }: { playback: PlaybackSession }) {
  const ref = useRef<HTMLVideoElement>(null);
  useLiveRewindPlayback(ref, playback);
  return <video ref={ref} data-testid="video" />;
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
    liveUrl: `${BASE}/live/key-1/index.m3u8`,
    controlKey: 'key-1',
    controlBase: BASE,
    resolveX: () => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }),
    onClose: vi.fn(),
  };
}

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

async function settle() {
  await act(async () => {
    await vi.dynamicImportSettled();
    for (let i = 0; i < 6; i += 1) await Promise.resolve();
  });
}

/** 되감기에서 P 를 보다가 라이브로 넘겨 확인 중인 화면. 넘김 행(c13h)이 들기 전이라 결과 모양만 끼운다 */
async function handedOffScreen() {
  const view = render(<Screen options={options()} />);
  await settle();
  act(() =>
    session?.dispatch({ type: 'seek', targetMs: P, view: { lowerMs: NOW - 5_000, endMs: NOW } }),
  );
  await settle();
  const list = {
    url: A1,
    firstMs: NOW - 3_600_000,
    lastEndMs: E,
    targetDurationS: 4,
    headSpanMs: 12_000,
  };
  act(() => session?.dispatch({ type: 'list-loaded', ...list }));
  act(() =>
    session?.dispatch({
      type: 'position',
      atMs: P,
      playing: true,
      runMs: 0,
      epoch: session!.positionEpoch(),
    }),
  );
  vi.mocked(transition).mockImplementationOnce((...[s, , now]) => ({
    state: handedOff(s, now),
    effects: [],
  }));
  act(() => session?.dispatch({ type: 'playing' }));
  await settle();
  expect(session?.state.source).toMatchObject({ kind: 'live', strictStart: true });
  return view.getByTestId('video');
}

/** 영상이 붙기 전(readyState 1)에 사람이 멈춘다 — 위치 사건은 막혀 세션 칸에 닿지 않는다 */
function pauseBeforeMedia(video: HTMLElement) {
  media.readyState = 1;
  media.paused = true;
  act(() => video.dispatchEvent(new Event('pause')));
}

/** 되돌린 되감기 재생기가 목록을 읽은 뒤 스스로 튼 횟수 */
async function playsAfterRevert(): Promise<number> {
  await settle();
  expect(session?.state.source.kind).toBe('rewind');
  const play = vi.mocked(HTMLMediaElement.prototype.play);
  play.mockClear();
  act(() => lastHls().trigger(FakeHls.Events.MANIFEST_PARSED, {}));
  return play.mock.calls.length;
}

beforeEach(async () => {
  vi.useFakeTimers({ now: NOW });
  fake.instances.length = 0;
  media.readyState = 4;
  media.paused = false;
  session = null;
  const actual = await vi.importActual<typeof import('./rewindSwitch')>('./rewindSwitch');
  vi.mocked(transition).mockReset().mockImplementation(actual.transition);
  stubFetch((url) =>
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
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => media.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(
    () => media.readyState,
  );
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('끝에 닿아 넘긴 뒤 확인이 깨진다 — ended 의 pause(c13f cc 한계 5)', () => {
  // 명세: 재생이 끝에 닿으면 paused 를 세우고 pause 사건을 낸 뒤 ended 를 낸다. 재생기는 사람의
  // 멈춤과 가리지 못한다. 라이브가 틀기 전에 확인이 깨지면 되돌린 되감기가 멈춘 채 선다 — 지금
  // 동작을 고정한다(사람 멈춤으로 볼지는 결정 사항)
  it('끝 도달 → 끝 넘김(E1) → 시한(H0) 되돌림이면 되감기 재생기가 스스로 틀지 않는다', async () => {
    render(<Screen options={options()} />);
    await settle();
    const view = { lowerMs: NOW - 5_000, endMs: NOW };
    act(() => session?.dispatch({ type: 'seek', targetMs: P, view }));
    await settle();
    const list = {
      url: A1,
      firstMs: NOW - 3_600_000,
      lastEndMs: E,
      targetDurationS: 4,
      headSpanMs: 12_000,
    };
    act(() => session?.dispatch({ type: 'list-loaded', ...list }));
    act(() =>
      session?.dispatch({
        type: 'position',
        atMs: P,
        playing: true,
        runMs: 0,
        epoch: session!.positionEpoch(),
      }),
    );
    const video = document.querySelector('video')!;
    media.paused = true;
    act(() => video.dispatchEvent(new Event('pause')));
    act(() => video.dispatchEvent(new Event('ended')));
    expect(session?.state.phase).toMatchObject({ kind: 'ending-wait', atEnd: true });
    // E1 의 탐침 결과(P1) 모양만 끼운다 — 탐침 받기는 이 시험 밖이다
    vi.mocked(transition).mockImplementationOnce((...[state, , now]) => ({
      state: handedOff(state, now),
      effects: [],
    }));
    act(() => session?.dispatch({ type: 'playing' }));
    await settle();
    expect(session?.state.source).toMatchObject({ kind: 'live', strictStart: true });
    await act(async () => vi.advanceTimersByTime(DEADLINE_AFTER_MS));
    expect(await playsAfterRevert()).toBe(0);
  });
});

describe('멈춘 채 넘김 확인이 깨진다 — 영상이 붙기 전의 멈춤(c13f cx H1)', () => {
  it('확인 시한(H0)이 되돌려도 되감기 재생기는 스스로 틀지 않는다', async () => {
    pauseBeforeMedia(await handedOffScreen());
    await act(async () => vi.advanceTimersByTime(DEADLINE_AFTER_MS));
    expect(await playsAfterRevert()).toBe(0);
  });

  it('라이브가 닫혀(H2 · 라이브 404) 되돌려도 되감기 재생기는 스스로 틀지 않는다', async () => {
    pauseBeforeMedia(await handedOffScreen());
    const notFound = {
      fatal: true,
      type: FakeHls.ErrorTypes.NETWORK_ERROR,
      details: FakeHls.ErrorDetails.LEVEL_LOAD_ERROR,
      response: { code: 404 },
    };
    act(() => lastHls().trigger(FakeHls.Events.ERROR, notFound));
    expect(await playsAfterRevert()).toBe(0);
  });

  it('연속 검사가 깨져(H1a · 건너뜀) 되돌려도 되감기 재생기는 스스로 틀지 않는다', async () => {
    pauseBeforeMedia(await handedOffScreen());
    const jumped = {
      type: 'position',
      atMs: P + 5_000,
      playing: false,
      runMs: 0,
      epoch: session!.positionEpoch(),
    } as const;
    act(() => session?.dispatch(jumped));
    expect(await playsAfterRevert()).toBe(0);
  });

  it('라이브 첫 목록이 시작을 잘라(H2t) 되돌려도 되감기 재생기는 스스로 틀지 않는다', async () => {
    pauseBeforeMedia(await handedOffScreen());
    // P 뒤에서 시작하는 라이브 목록 — 시작 하한(첫 조각 + 3 × TD)이 P 보다 뒤라 잘린다
    const fragments = [...Array(4).keys()].map((i) => ({
      start: i * 4,
      duration: 4,
      programDateTime: E + i * 4_000,
    }));
    const details = { fragments, targetduration: 4, live: true };
    act(() => lastHls().trigger(FakeHls.Events.LEVEL_UPDATED, { details }));
    expect(await playsAfterRevert()).toBe(0);
  });

  it('멈추지 않았으면 되돌린 되감기 재생기가 현행대로 스스로 튼다', async () => {
    await handedOffScreen();
    await act(async () => vi.advanceTimersByTime(DEADLINE_AFTER_MS));
    expect(await playsAfterRevert()).toBe(1);
  });
});
