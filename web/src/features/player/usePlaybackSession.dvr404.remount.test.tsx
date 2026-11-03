import { act, cleanup, render } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { assertTransitionInvariants } from './dvr404TestKit';
import type * as rewindSwitchModule from './rewindSwitch';
import { SESSION_NOTICES, transition, type XAnswer } from './rewindSwitch';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionOptions,
} from './usePlaybackSession';

// 세션에 들어간 사건을 보려고 상태기계를 감싼다 — 동작은 원본 그대로다
vi.mock('./rewindSwitch', async (importOriginal) => {
  const actual = await importOriginal<typeof rewindSwitchModule>();
  return { ...actual, transition: vi.fn(actual.transition) };
});

// 되감기 조각 404 의 세션 · 재생기 끝단(POK-528 c5 — 계획 4-1 · 4-2 · 8-3 · 8-3c). 세션 ·
// 실재생 훅 · hls.js 흉내를 한 화면에 묶는다. 404 는 흉내 재생기의 ERROR 로 넣어 재생기의
// 가로채기 → 세션 사건 → 효과 → 재생기 명령까지 잇는다.
// ① 리마운트 기동(U2 · 8-3c): 막힘 · 마지막 확인 · 제어면 다시 읽기(recheck) 중에 재생기가
//    내려갔다 다시 서면(출입증 회복 · 시계 key) 새 재생기는 hls.js 를 만들지 않는다. 풀리는
//    전이(다시보기 · (가))에서 세대가 올라 정상 기동한다.
// ② 사람 멈춤 → 404 → (가)(계획 3-3 · 부수 [cc] M1): 새 재생기가 스스로 틀어도 옛 축의
//    멈춘 자리로 세션에 옮겨 달라지 않는다 — 알림 합 1 · 시크 요청 0.

const fake = vi.hoisted(() => ({
  instances: [] as FakeHls[],
  /** 있으면 loadSource 가 곧바로 목록 · 조각 사건을 낸다 */
  bootList: null as Details | null,
}));

vi.mock('hls.js', () => ({ default: FakeHls }));

interface Details {
  fragments: Array<{ start: number; duration: number; programDateTime: number }>;
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static isSupported = () => true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    FRAG_LOADED: 'hlsFragLoaded',
    ERROR: 'hlsError',
  };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static ErrorDetails = {
    FRAG_LOAD_ERROR: 'fragLoadError',
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  readonly handlers = new Map<string, Handler[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition = null;
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  readonly destroy = vi.fn();
  readonly loadSource = vi.fn(() => {
    const list = fake.bootList;
    if (list === null) return;
    this.trigger(FakeHls.Events.MANIFEST_PARSED, {});
    this.trigger(FakeHls.Events.LEVEL_LOADED, { details: list });
    this.trigger(FakeHls.Events.FRAG_LOADED, {});
  });
  constructor() {
    fake.instances.push(this);
  }
  on(event: string, handler: Handler) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  off() {}
  once(event: string, handler: Handler) {
    const wrapped: Handler = (e, data) => {
      this.handlers.set(
        event,
        (this.handlers.get(event) ?? []).filter((h) => h !== wrapped),
      );
      handler(e, data);
    };
    this.on(event, wrapped);
  }
  trigger(event: string, data: unknown) {
    const { details } = (data ?? {}) as { details?: Details };
    if (details !== undefined) this.latestLevelDetails = details;
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  attachMedia() {}
}

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const VOD = `${BASE}/vod/key-1/r-A/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const ROUND = `${BASE}/api/streams/key-1/rounds/r-A/rewind`;
/** 404 를 받은 옛 목록의 첫 조각 · 되감은 자리 · (가) 답의 첫 조각(하한이 2분 밀림) */
const OLD_F = NOW - 700_000;
const TARGET = NOW - 600_000;
const NEW_F = OLD_F + 120_000;

/** 첫 조각 firstMs · 4초 조각 n 개 목록 */
function listFrom(firstMs: number, live: boolean, n = 30): Details {
  const fragments = Array.from({ length: n }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: firstMs + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live };
}

function statusBody(patch: Record<string, unknown>): unknown {
  return { ready: false, mode: null, url: null, firstPdt: null, headPdt: null, ...patch };
}

/** 같은 회차의 방송 중 목록 — 첫 조각이 floorMs */
function liveBody(floorMs: number): unknown {
  return statusBody({
    ready: true,
    mode: 'live',
    url: A1,
    firstPdt: new Date(floorMs).toISOString(),
    headPdt: new Date(Date.now()).toISOString(),
  });
}

/** 본 구간과 겹치는 회차 다시보기 */
function vodBody(): unknown {
  return statusBody({
    ready: true,
    mode: 'vod',
    url: VOD,
    firstPdt: new Date(OLD_F).toISOString(),
    headPdt: new Date(NOW - 1_000).toISOString(),
  });
}

type Reply = () => Response | Promise<Response>;

/** 시험이 정하는 제어면 답 — 스트림 · 회차별 */
const control = {
  stream: (() => jsonResponse(200, liveBody(OLD_F))) as Reply,
  round: (() => jsonResponse(404, { error: 'not_found' })) as Reply,
};

/** 시험이 풀어 줄 때까지 기다리는 답 */
function heldReply(): { reply: Reply; release: (body: unknown) => void } {
  let release: (body: unknown) => void = () => {};
  const pending = new Promise<Response>((resolve) => {
    release = (body) => resolve(jsonResponse(200, body));
  });
  return { reply: () => pending, release };
}

/** 재생 여부를 시험이 쥔다 — pause() 는 멈추고 pause 사건, play() 는 틀고 play 사건(큐에 넣어 나중에) */
const media = { paused: false };

let session: PlaybackSession | null = null;

function current(): PlaybackSession {
  if (session === null) throw new Error('세션이 없다');
  return session;
}

function Player({ playback }: { playback: PlaybackSession }) {
  const ref = useRef<HTMLVideoElement>(null);
  useLiveRewindPlayback(ref, playback);
  return <video ref={ref} data-testid="video" />;
}

function Screen({ options, playerShown }: { options: SessionOptions; playerShown: boolean }) {
  const playback = usePlaybackSession(options);
  session = playback;
  return playback !== null && playerShown ? <Player playback={playback} /> : null;
}

function options(onNotice: (text: string) => void): SessionOptions {
  return {
    canOpen: true,
    roundId: 'r-A',
    accessKey: 'r-A|key-1',
    liveUrl: `${BASE}/live/key-1/index.m3u8`,
    controlKey: 'key-1',
    controlBase: BASE,
    resolveX: () => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }),
    onClose: vi.fn(),
    onNotice,
  };
}

async function settle() {
  await act(async () => {
    await vi.dynamicImportSettled();
    for (let i = 0; i < 10; i += 1) await Promise.resolve();
  });
}

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

function playCalls(): number {
  return vi.mocked(HTMLMediaElement.prototype.play).mock.calls.length;
}

/** 되감기 재생기가 미디어 조각 404 를 받았다 — hls.js 가 앱에 올리는 꼴 */
function segmentGone(hls: FakeHls) {
  act(() =>
    hls.trigger(FakeHls.Events.ERROR, {
      type: FakeHls.ErrorTypes.NETWORK_ERROR,
      details: FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
      fatal: false,
      frag: { sn: 7, initSegment: { url: `${BASE}/dvr/key-1/a1/init.mp4` } },
      response: { url: `${BASE}/dvr/key-1/a1/seg7.mp4`, code: 404, text: '' },
    }),
  );
}

/** 세션을 열고 되감은 화면 — 되감기 재생기는 옛 목록으로 서고, 본 자리 · 목록 끝을 세션이 안다 */
async function rewindScreen() {
  const onNotice = vi.fn();
  const opts = options(onNotice);
  const view = render(<Screen options={opts} playerShown />);
  await settle();
  fake.bootList = listFrom(OLD_F, true);
  act(() =>
    current().dispatch({
      type: 'seek',
      targetMs: TARGET,
      view: { lowerMs: NOW - 16_000, endMs: NOW },
    }),
  );
  await settle();
  expect(current().state.source.kind).toBe('rewind');
  act(() =>
    current().dispatch({
      type: 'list-loaded',
      url: A1,
      firstMs: OLD_F,
      lastEndMs: NOW - 2_000,
      targetDurationS: 4,
      headSpanMs: 12_000,
    }),
  );
  act(() =>
    current().dispatch({
      type: 'position',
      atMs: TARGET,
      playing: true,
      runMs: 0,
      epoch: current().positionEpoch(),
    }),
  );
  const rewindHls = lastHls();
  const remount = async () => {
    view.rerender(<Screen options={opts} playerShown={false} />);
    await settle();
    view.rerender(<Screen options={opts} playerShown />);
    await settle();
  };
  return { view, rewindHls, remount, onNotice };
}

/** 감싼 transition 의 모든 호출에 감싸개 불변식을 단언하고 사건 꼴을 돌려준다(계획 8-0) */
function checkedEvents(): string[] {
  const { calls, results } = vi.mocked(transition).mock;
  return calls.map(([before, event], i) => {
    const result = results[i];
    if (result?.type !== 'return') throw new Error(`transition ${i} 이 돌려주지 않았다`);
    assertTransitionInvariants(before, result.value.state);
    return event.type;
  });
}

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  vi.mocked(transition).mockClear();
  fake.instances.length = 0;
  fake.bootList = null;
  media.paused = false;
  session = null;
  control.stream = () => jsonResponse(200, liveBody(OLD_F));
  control.round = () => jsonResponse(404, { error: 'not_found' });
  stubFetch((url) => {
    if (url === STREAM) return control.stream();
    if (url === ROUND) return control.round();
    return jsonResponse(404, { error: 'not_found' });
  });
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => media.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(() => 0);
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(function (
    this: HTMLMediaElement,
  ) {
    media.paused = true;
    this.dispatchEvent(new Event('pause'));
  });
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (
    this: HTMLMediaElement,
  ) {
    media.paused = false;
    // 브라우저는 play 사건을 큐에 넣는다 — 같은 동기 흐름의 목록 사건(LEVEL_LOADED) 뒤에 난다
    queueMicrotask(() => this.dispatchEvent(new Event('play')));
    return Promise.resolve();
  });
});
afterEach(() => {
  // 재생기 정리(video.load)가 흉내를 푼 뒤에 돌지 않게 먼저 내린다
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('리마운트 기동 — 「세우지 않음」 동안 새 재생기는 hls.js 를 만들지 않는다(U2 · 계획 8-3c)', () => {
  it('막힘 기원: 404 → (나) 막힘 · 멈춤 → 리마운트 → 생성 0 · 재생 0 → 다시보기가 나오면 생성 1 · 재생 1', async () => {
    const { rewindHls, remount } = await rewindScreen();
    control.stream = () => jsonResponse(503, { error: 'unavailable' });
    segmentGone(rewindHls);
    expect(rewindHls.stopLoad).toHaveBeenCalledTimes(1);
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(media.paused).toBe(true);
    const created = fake.instances.length;
    const played = playCalls();
    const generation = current().state.source.generation;
    await remount();
    expect(current().state.source.generation).toBe(generation + 1);
    expect(current().state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(current().view).toMatchObject({ cover: 'preparing', startHeld: true });
    expect(fake.instances).toHaveLength(created);
    expect(playCalls()).toBe(played);
    control.round = () => jsonResponse(200, vodBody());
    await act(async () => vi.advanceTimersByTime(10_000));
    await settle();
    expect(current().state.source).toMatchObject({ kind: 'vod', url: VOD });
    expect(fake.instances).toHaveLength(created + 1);
    expect(lastHls().loadSource).toHaveBeenCalledTimes(1);
    expect(playCalls()).toBe(played + 1);
    checkedEvents();
  });

  it('마지막 확인 기원: 녹화로 정한 끝남 대기 → 404 → 리마운트 → 생성 0 → 회차별 답이 다시보기면 생성 1 · 재생 1', async () => {
    const { rewindHls, remount } = await rewindScreen();
    control.round = () => jsonResponse(200, statusBody({ mode: 'ended' }));
    act(() => current().dispatch({ type: 'endlist' }));
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'ending-wait', recordOnEnd: true });
    const finalAnswer = heldReply();
    control.round = finalAnswer.reply;
    segmentGone(rewindHls);
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'ending-wait', finalCheck: true });
    const created = fake.instances.length;
    const played = playCalls();
    await remount();
    expect(current().view.startHeld).toBe(true);
    expect(fake.instances).toHaveLength(created);
    expect(playCalls()).toBe(played);
    finalAnswer.release(vodBody());
    await settle();
    expect(current().state.source).toMatchObject({ kind: 'vod', url: VOD });
    expect(fake.instances).toHaveLength(created + 1);
    expect(playCalls()).toBe(played + 1);
    checkedEvents();
  });

  it('recheck 기원: 404 → 제어면 답 대기 중 리마운트 → 생성 0 → (가) 답이면 세대가 올라 생성 1 · 재생 1', async () => {
    const { rewindHls, remount } = await rewindScreen();
    const answer = heldReply();
    control.stream = answer.reply;
    segmentGone(rewindHls);
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'rewind', recheck: true });
    // 덮개 없는 국면 — 덮개 · 주소가 그대로여도 세대만으로 풀려야 한다(K3)
    expect(current().view).toMatchObject({ cover: null, startHeld: true });
    const created = fake.instances.length;
    const played = playCalls();
    await remount();
    expect(fake.instances).toHaveLength(created);
    expect(playCalls()).toBe(played);
    fake.bootList = listFrom(NEW_F, true);
    answer.release(liveBody(NEW_F));
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'rewind', movedFloorMs: NEW_F });
    expect(current().view).toMatchObject({ cover: null, startHeld: false });
    expect(fake.instances).toHaveLength(created + 1);
    expect(playCalls()).toBe(played + 1);
    checkedEvents();
  });
});

describe('사람 멈춤 → 404 → (가) — 멈춘 자리 기록을 버린다(계획 3-3 · 부수 [cc] M1)', () => {
  it('새 재생기가 스스로 틀어도 옛 축의 멈춘 자리로 시크를 청하지 않는다 — 알림 합 1 · 시크 사건 0', async () => {
    const { view, rewindHls, onNotice } = await rewindScreen();
    act(() => {
      media.paused = true;
      view.getByTestId('video').dispatchEvent(new Event('pause'));
    });
    const eventsBefore = checkedEvents().length;
    onNotice.mockClear();
    fake.bootList = listFrom(NEW_F, true);
    control.stream = () => jsonResponse(200, liveBody(NEW_F));
    segmentGone(rewindHls);
    await settle();
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'rewind', movedFloorMs: NEW_F });
    // 새 재생기가 MANIFEST_PARSED 에서 틀고 play 사건이 났다
    expect(media.paused).toBe(false);
    const after = checkedEvents().slice(eventsBefore);
    expect(after).not.toContain('seek');
    expect(onNotice.mock.calls.filter(([text]) => text === SESSION_NOTICES.oldest)).toHaveLength(1);
  });
});
