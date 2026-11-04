import { act, cleanup, render } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { assertTransitionInvariants, relistGuard, revisitGuard } from './dvr404TestKit';
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
// ① 리마운트 기동(U2 · 8-3c): 막힘 · 마지막 확인 중에 재생기가 내려갔다 다시 서면(출입증
//    회복 · 시계 key) 새 재생기는 hls.js 를 만들지 않는다. 풀리는 전이(다시보기)에서 세대가
//    올라 정상 기동한다. 목록을 다시 받는 중(relist)의 리마운트는 곧바로 선다(c7 · 계획 3-6).
// ② 404 → 목록 다시 받기 · 사람 멈춤(계획 3-3 (2) · P37): 새 재생기가 스스로 틀어도 옛 축의
//    멈춘 자리로 세션에 옮겨 달라지 않는다 — 알림 합 ≤ 1 · 시크 사건 0. 흉내는 hls.js 실물처럼
//    MANIFEST_PARSED · LEVEL_LOADED · LEVEL_UPDATED 를 loadSource 한 호출에서 차례로 내고
//    (playlist-loader.ts 573 · 594 · 805 · level-controller.ts 387), play() 의 play 사건은 그
//    호출 뒤(마이크로과제)에 낸다 — 시험 머리에서 이 순서를 단언으로 고정한다.
// ③ 사람이 멈춘 동안의 404(c9 · 계획 4-3 · 8-5): 재생기가 받기만 멈추고 세션에 올리지 않는다.
//    다시 틀 때 올려 세션이 국면 행대로 잇는다 — rewind 는 다시 받는 중이 아니면 같은 목록 다시
//    받기(이어 볼 자리 · 세션 시크 목표) · 다시 받는 중(화면 전)이면 막힘 · 끝남 대기는 녹화로 정하기 전이면 막힘 · 정한 뒤면 마지막 확인 · 다시보기 제안은
//    같은 자리 다시보기.

const fake = vi.hoisted(() => ({
  instances: [] as FakeHls[],
  /** 있으면 loadSource 가 곧바로 목록 · 조각 사건을 낸다 */
  bootList: null as Details | null,
  /** 흉내가 낸 사건 · 미디어 play 사건의 차례(흉내 순서 단언의 재료) */
  order: [] as string[],
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
  static readonly isSupported = () => true;
  static readonly Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    FRAG_LOADED: 'hlsFragLoaded',
    ERROR: 'hlsError',
  };
  static readonly ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static readonly ErrorDetails = {
    FRAG_LOAD_ERROR: 'fragLoadError',
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    MANIFEST_LOAD_TIMEOUT: 'manifestLoadTimeOut',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  readonly handlers = new Map<string, Handler[]>();
  latestLevelDetails: Details | null = null;
  readonly liveSyncPosition = null;
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  readonly destroy = vi.fn();
  readonly loadSource = vi.fn(() => {
    const list = fake.bootList;
    if (list === null) return;
    this.trigger(FakeHls.Events.MANIFEST_PARSED, {});
    this.trigger(FakeHls.Events.LEVEL_LOADED, { details: list });
    this.trigger(FakeHls.Events.LEVEL_UPDATED, { details: list });
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
    fake.order.push(event);
    // 목록은 목록 사건에서만 갈아 끼운다 — ERROR 자료의 details 는 오류 갈래 문자열이다(hls.js ErrorData)
    if (event === FakeHls.Events.LEVEL_LOADED || event === FakeHls.Events.LEVEL_UPDATED) {
      // 목록 사건의 자료는 늘 목록을 싣는다(hls.js LevelLoadedData · LevelUpdatedData 의 details)
      this.latestLevelDetails = (data as { details: Details }).details;
    }
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
}

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const VOD = `${BASE}/vod/key-1/r-A/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const ROUND = `${BASE}/api/streams/key-1/rounds/r-A/rewind`;
/** 404 를 받은 옛 목록의 첫 조각 · 되감은 자리 · 다시 받은 새 목록의 첫 조각(하한이 2분 밀림) */
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

/** 재생기 끊김 알림(useHlsPlayback STOPPED_NOTES.broken) */
const BROKEN_NOTE = '영상이 끊겼어요. 새로고침하면 다시 연결해요';

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

/** 출입증 키 — 403 갈래(출입증 버림 · 재생기 내림)를 타게 한다 */
const MEDIA_KEY = 'key-1';

/** 재생기가 멎은 까닭(끊김 알림 등)은 영상 옆 글로 내보인다 — GlassPlayer 의 덮개 글과 같은 값 */
function Player({ playback }: { playback: PlaybackSession }) {
  const ref = useRef<HTMLVideoElement>(null);
  const sim = useLiveRewindPlayback(ref, playback, {}, undefined, MEDIA_KEY);
  return (
    <>
      <video ref={ref} data-testid="video" />
      <p data-testid="stopped">{sim.playbackError ?? ''}</p>
    </>
  );
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
    for (let i = 0; i < 10; i += 1) {
      await Promise.resolve();
    }
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

/** 세션 알림 가운데 「가장 오래된 지점으로 옮겼어요」 의 수 */
function oldestNotices(onNotice: ReturnType<typeof vi.fn>): number {
  return onNotice.mock.calls.filter(([text]) => text === SESSION_NOTICES.oldest).length;
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

/**
 * 세션을 열고 되감은 화면 — 되감기 재생기는 옛 목록으로 서고, 목록 끝을 세션이 안다. watched 면
 * 되감은 자리의 위치도 받았다(확인된 위치). 아니면 첫 위치 전이다
 */
async function rewindScreen(watched = true) {
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
  if (watched) {
    act(() =>
      current().dispatch({
        type: 'position',
        atMs: TARGET,
        playing: true,
        runMs: 0,
        epoch: current().positionEpoch(),
      }),
    );
  }
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
  const guards = [relistGuard(), revisitGuard()];
  return calls.map(([before, event], i) => {
    const result = results[i];
    if (result?.type !== 'return') throw new Error(`transition ${i} 이 돌려주지 않았다`);
    assertTransitionInvariants(before, event, result.value);
    for (const guard of guards) {
      guard.observe(before, event, result.value.state);
    }
    return event.type;
  });
}

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  vi.mocked(transition).mockClear();
  fake.instances.length = 0;
  fake.bootList = null;
  fake.order.length = 0;
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
    // 브라우저는 play 사건을 큐에 넣는다 — 같은 동기 흐름의 목록 사건(LEVEL_LOADED · LEVEL_UPDATED) 뒤에 난다
    queueMicrotask(() => {
      fake.order.push('play');
      this.dispatchEvent(new Event('play'));
    });
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
  it('막힘 기원: 404 → 다시 받기 → 화면 전 404 → 막힘 · 멈춤 → 리마운트 → 생성 0 · 재생 0 → 다시보기가 나오면 생성 1 · 재생 1', async () => {
    const { rewindHls, remount } = await rewindScreen();
    segmentGone(rewindHls);
    expect(rewindHls.stopLoad).toHaveBeenCalledTimes(1);
    await settle();
    const relistHls = lastHls();
    expect(relistHls).not.toBe(rewindHls);
    expect(current().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'paint' } });
    segmentGone(relistHls);
    expect(relistHls.stopLoad).toHaveBeenCalledTimes(1);
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

  it('다시 받기 기원: 404 → 첫 목록 전 리마운트 → 세우지 않음 아님 · 새 재생기 생성 · 재생 1 · 새 목록 판정 알림 1', async () => {
    const { rewindHls, remount, onNotice } = await rewindScreen();
    fake.bootList = null;
    segmentGone(rewindHls);
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'list' } });
    expect(current().view).toMatchObject({ cover: null, startHeld: false });
    const created = fake.instances.length;
    const played = playCalls();
    onNotice.mockClear();
    fake.bootList = listFrom(NEW_F, true);
    await remount();
    expect(fake.instances.length).toBeGreaterThan(created);
    expect(lastHls().loadSource).toHaveBeenCalledTimes(1);
    expect(playCalls()).toBe(played + 1);
    expect(current().state.source.startAtMs).toBe(TARGET);
    expect(oldestNotices(onNotice)).toBe(1);
    checkedEvents();
  });

  it('첫 위치 전 다시 받기의 첫 목록 403(키 있음) → 재생기 내림 → 회복 리마운트 → 시작 = 청한 자리 · 하한에서 받기 · 알림 1(R3 · P41)', async () => {
    const { rewindHls, remount, onNotice } = await rewindScreen(false);
    fake.bootList = null;
    segmentGone(rewindHls);
    await settle();
    const relistHls = lastHls();
    expect(current().state.source).toMatchObject({ url: A1, startAtMs: TARGET });
    act(() =>
      relistHls.trigger(FakeHls.Events.ERROR, {
        type: FakeHls.ErrorTypes.NETWORK_ERROR,
        details: FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR,
        fatal: true,
        response: { url: A1, code: 403, text: '' },
      }),
    );
    expect(relistHls.destroy).toHaveBeenCalledTimes(1);
    onNotice.mockClear();
    fake.bootList = listFrom(NEW_F, true);
    await remount();
    const recovered = lastHls();
    expect(recovered).not.toBe(relistHls);
    expect(current().state.source).toMatchObject({ url: A1, startAtMs: TARGET });
    // 새 목록 첫 조각 NEW_F 는 청한 자리 뒤 — 창 하한(영상 12초)에서 받는다
    expect(recovered.startLoad.mock.calls).toEqual([[12]]);
    expect(oldestNotices(onNotice)).toBe(1);
    checkedEvents();
  });
});

// 다시 받은 재생기의 첫 목록 치명은 재생기가 곧바로 끊김을 알리고 내린다(c8). 세션에 올리는 사건이 없으므로 세션은
// 다시 받는 중(relist 'list')에 그대로 남는다 — 막힘이 아니고 자동 회복도 없다(계획 0-2 의 3 · P30). 출구는 LIVE ·
// 새로고침 · 다음 폴링의 새 url 교체(그 url 이 이어 볼 자리를 품을 때만 — 계획 0-2 의 3)다. 같은 url 의 다음 폴링은
// 아무것도 바꾸지 않는다
describe('목록 다시 받기의 첫 목록 실패 — 끊김 · 세션은 다시 받는 중 그대로(c8 결합 뒤 · 계획 8-4)', () => {
  it.each<[string, string, number | null]>([
    ['404', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 404],
    ['503', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 503],
    ['시한', FakeHls.ErrorDetails.MANIFEST_LOAD_TIMEOUT, null],
  ])(
    '404 → 다시 받기 → 첫 목록 %s → 끊김 1 · startLoad 0 · 세션 rewind · relist list · 사건 0 · 다음 폴링에도 그대로',
    async (_name, details, code) => {
      const { view, rewindHls } = await rewindScreen();
      fake.bootList = null;
      segmentGone(rewindHls);
      await settle();
      const relistHls = lastHls();
      expect(relistHls).not.toBe(rewindHls);
      const relisting = current().state;
      expect(relisting.source).toMatchObject({ kind: 'rewind', url: A1, startAtMs: TARGET });
      expect(relisting.phase).toEqual({
        kind: 'rewind',
        pendingNext: null,
        relist: { stage: 'list', noticed: false },
      });
      const eventsBefore = checkedEvents().length;
      act(() =>
        relistHls.trigger(FakeHls.Events.ERROR, {
          type: FakeHls.ErrorTypes.NETWORK_ERROR,
          details,
          fatal: true,
          response: code === null ? undefined : { url: A1, code, text: '' },
        }),
      );
      await settle();
      expect(view.getByTestId('stopped').textContent).toBe(BROKEN_NOTE);
      expect(relistHls.startLoad).not.toHaveBeenCalled();
      expect(relistHls.destroy).toHaveBeenCalledTimes(1);
      expect(checkedEvents().slice(eventsBefore)).toEqual([]);
      expect(current().state.phase).toEqual(relisting.phase);
      expect(current().state.source).toEqual(relisting.source);
      const created = fake.instances.length;
      await act(async () => vi.advanceTimersByTime(10_000));
      await settle();
      expect(checkedEvents().slice(eventsBefore)).toContain('stream-poll');
      expect(current().state.phase).toEqual(relisting.phase);
      expect(current().state.source).toEqual(relisting.source);
      expect(fake.instances).toHaveLength(created);
      expect(view.getByTestId('stopped').textContent).toBe(BROKEN_NOTE);
      checkedEvents();
    },
  );
});

describe('404 → 목록 다시 받기 · 사람 멈춤 — 옛 축의 멈춘 자리로 시크하지 않는다(계획 3-3 (2) · P37)', () => {
  /** 사람이 멈춘다 — 재생기는 pause 사건으로만 안다 */
  function userPause(view: ReturnType<typeof render>) {
    media.paused = true;
    view.getByTestId('video').dispatchEvent(new Event('pause'));
  }

  it('① 404 → 정리 뒤 · 새 재생기 첫 목록 전 사람 멈춤(축이 없어 기록 없음) → 새 재생기가 스스로 틂 → 시크 사건 0 · 알림 합 ≤ 1', async () => {
    const { view, rewindHls, onNotice } = await rewindScreen();
    const eventsBefore = checkedEvents().length;
    onNotice.mockClear();
    fake.bootList = null;
    segmentGone(rewindHls);
    await settle();
    const relistHls = lastHls();
    expect(relistHls).not.toBe(rewindHls);
    act(() => userPause(view));
    const list = listFrom(NEW_F, true);
    act(() => {
      relistHls.trigger(FakeHls.Events.MANIFEST_PARSED, {});
      relistHls.trigger(FakeHls.Events.LEVEL_LOADED, { details: list });
      relistHls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: list });
    });
    await settle();
    expect(media.paused).toBe(false);
    expect(checkedEvents().slice(eventsBefore)).not.toContain('seek');
    expect(oldestNotices(onNotice)).toBeLessThanOrEqual(1);
  });

  it('② 404 와 같은 act 안 커밋 전 사람 멈춤(옛 재생기 · 옛 목록 축이 기록을 적음) → 첫 목록 판정이 기록을 버림 → 시크 사건 0 · 알림 합 ≤ 1', async () => {
    const { view, rewindHls, onNotice } = await rewindScreen();
    const eventsBefore = checkedEvents().length;
    onNotice.mockClear();
    fake.bootList = listFrom(NEW_F, true);
    fake.order.length = 0;
    act(() => {
      rewindHls.trigger(FakeHls.Events.ERROR, {
        type: FakeHls.ErrorTypes.NETWORK_ERROR,
        details: FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
        fatal: false,
        frag: { sn: 7, initSegment: { url: `${BASE}/dvr/key-1/a1/init.mp4` } },
        response: { url: `${BASE}/dvr/key-1/a1/seg7.mp4`, code: 404, text: '' },
      });
      userPause(view);
    });
    await settle();
    // 흉내 순서 — 새 재생기의 첫 목록 사건이 한 호출에서 차례로 나고 play 사건은 그 뒤다(판별력의 전제)
    const boot = [
      FakeHls.Events.MANIFEST_PARSED,
      FakeHls.Events.LEVEL_LOADED,
      FakeHls.Events.LEVEL_UPDATED,
      'play',
    ];
    expect(fake.order.filter((event) => boot.includes(event))).toEqual(boot);
    expect(current().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'paint' } });
    expect(media.paused).toBe(false);
    expect(checkedEvents().slice(eventsBefore)).not.toContain('seek');
    expect(oldestNotices(onNotice)).toBeLessThanOrEqual(1);
  });
});

describe('사람이 멈춘 동안의 되감기 조각 404 — 다시 틀 때 세션이 잇는다(c9 · 계획 4-3 · 8-5)', () => {
  /** 사람이 멈춘다 — 재생기는 pause 사건으로만 안다(멈춘 자리는 옛 목록 축에 적힌다) */
  function userPause(view: ReturnType<typeof render>) {
    act(() => {
      media.paused = true;
      view.getByTestId('video').dispatchEvent(new Event('pause'));
    });
  }

  /** 사람이 다시 튼다 — play 사건은 큐에서 난다 */
  async function userPlay(view: ReturnType<typeof render>) {
    act(() => {
      void (view.getByTestId('video') as HTMLVideoElement).play();
    });
    await settle();
    await settle();
  }

  it('rewind: 멈춤 → 404 → 고리 0 · 교체 0 · 화면 그대로 → 재생 → 같은 url 다시 받기(세대 + 1 · 이어 볼 자리) · 새 목록 하한 알림 1', async () => {
    const { view, rewindHls, onNotice } = await rewindScreen();
    userPause(view);
    const eventsBefore = checkedEvents().length;
    const before = current().state;
    const created = fake.instances.length;
    onNotice.mockClear();
    fake.bootList = listFrom(NEW_F, true);
    segmentGone(rewindHls);
    await settle();
    expect(rewindHls.stopLoad).toHaveBeenCalledTimes(1);
    expect(current().state.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(current().state.source).toEqual(before.source);
    expect(fake.instances).toHaveLength(created);
    expect(media.paused).toBe(true);
    expect(checkedEvents().slice(eventsBefore)).not.toContain('dvr-segment-gone');
    expect(onNotice).not.toHaveBeenCalled();
    const played = playCalls();
    // 기동 때의 받기(armStart) 뒤로 옛 재생기는 더 받지 않는다 — 제자리 다시 받기 없음
    const loads = rewindHls.startLoad.mock.calls.length;
    await userPlay(view);
    expect(current().state.source).toMatchObject({
      kind: 'rewind',
      url: A1,
      startAtMs: TARGET,
      generation: before.source.generation + 1,
    });
    expect(fake.instances).toHaveLength(created + 1);
    expect(current().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'paint' } });
    expect(oldestNotices(onNotice)).toBe(1);
    // 사람이 한 번 · 다시 받은 새 재생기가 한 번
    expect(playCalls() - played).toBe(2);
    expect(rewindHls.startLoad).toHaveBeenCalledTimes(loads);
    const after = checkedEvents().slice(eventsBefore);
    expect(after.filter((event) => event === 'dvr-segment-gone')).toHaveLength(1);
    expect(after).not.toContain('seek');
  });

  it('rewind: 404 없이 멈춤 → 제어면 첫 조각이 늦어져도 재생 때 멈춘 자리(목록 하한 안)에서 그대로 — 시크 0 · 알림 0(P35)', async () => {
    const { view, rewindHls, onNotice } = await rewindScreen();
    const video = view.getByTestId('video') as HTMLVideoElement;
    video.currentTime = 16;
    userPause(view);
    const eventsBefore = checkedEvents().length;
    const created = fake.instances.length;
    onNotice.mockClear();
    control.stream = () => jsonResponse(200, liveBody(OLD_F + 40_000));
    await act(async () => vi.advanceTimersByTime(10_000));
    await settle();
    expect(current().state.entry?.firstPdtMs).toBe(OLD_F + 40_000);
    await userPlay(view);
    expect(video.currentTime).toBe(16);
    expect(oldestNotices(onNotice)).toBe(0);
    expect(rewindHls.stopLoad).not.toHaveBeenCalled();
    expect(fake.instances).toHaveLength(created);
    expect(checkedEvents().slice(eventsBefore)).not.toContain('seek');
  });

  it('rewind: 멈춤 → 404 → 붙잡은 채 세션 시크(목록 안) → 재생 → 다시 받기가 시크 목표에서 선다(P34 · 이어 볼 자리 둘째 단)', async () => {
    const { view, rewindHls } = await rewindScreen();
    userPause(view);
    const loads = rewindHls.startLoad.mock.calls.length;
    fake.bootList = null;
    segmentGone(rewindHls);
    await settle();
    const asked = TARGET + 40_000;
    act(() =>
      current().dispatch({
        type: 'seek',
        targetMs: asked,
        view: { lowerMs: OLD_F + 12_000, endMs: NOW - 2_000 },
      }),
    );
    await settle();
    expect(current().state.pendingSeekMs).toBe(asked);
    expect(rewindHls.startLoad).toHaveBeenCalledTimes(loads);
    const generation = current().state.source.generation;
    await userPlay(view);
    expect(current().state.source).toMatchObject({
      kind: 'rewind',
      url: A1,
      startAtMs: asked,
      generation: generation + 1,
    });
    expect(current().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'list' } });
    checkedEvents();
  });

  it('끝남 대기(rewind 기원 · 막힘 아님): 멈춤 → 404 → 국면 그대로 → 재생 → 막힘 · 지금 프레임에서 멈춤(c2 행)', async () => {
    const { view, rewindHls } = await rewindScreen();
    act(() => current().dispatch({ type: 'endlist' }));
    await settle();
    const ending = current().state.phase;
    expect(ending).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: false });
    expect(ending).not.toHaveProperty('blocked');
    userPause(view);
    const eventsBefore = checkedEvents().length;
    segmentGone(rewindHls);
    await settle();
    expect(current().state.phase).toEqual(ending);
    expect(checkedEvents().slice(eventsBefore)).not.toContain('dvr-segment-gone');
    await userPlay(view);
    expect(current().state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(media.paused).toBe(true);
    checkedEvents();
  });

  it('다시보기 제안: 멈춤 → 404 → 새 재생기 0 → 재생 → 같은 자리 다시보기 1(W4)', async () => {
    const { view, rewindHls } = await rewindScreen();
    act(() => current().dispatch({ type: 'endlist' }));
    await settle();
    control.round = () => jsonResponse(200, vodBody());
    await act(async () => vi.advanceTimersByTime(10_000));
    await settle();
    expect(current().state.phase).toMatchObject({ kind: 'vod-offered', url: VOD });
    userPause(view);
    const created = fake.instances.length;
    segmentGone(rewindHls);
    await settle();
    expect(fake.instances).toHaveLength(created);
    expect(current().state.source.kind).toBe('rewind');
    await userPlay(view);
    expect(current().state.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs: TARGET });
    expect(fake.instances).toHaveLength(created + 1);
    checkedEvents();
  });
});
