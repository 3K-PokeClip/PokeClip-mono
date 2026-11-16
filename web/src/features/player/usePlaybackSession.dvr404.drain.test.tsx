import { useRef } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { loadHls } from './hlsLoader';
import type { XAnswer } from './rewindSwitch';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionOptions,
} from './usePlaybackSession';

// 다시보기 제안 중 되감기 조각 404 뒤의 리마운트(POK-547) — 받기를 멈춘 재생기가 내려가고 새 재생기가 서도 낡은 /dvr
// 목록을 다시 받지 않는다(hls.js 생성 0 · loadSource 0). 새 재생기의 본 효과와 리마운트 사건(bindPlayer 효과)과
// hls.js 읽기 약속의 차례가 실제 스케줄러에서 어떻게 엇갈리는지가 판정 대상이라 act 를 끄고 createRoot 를 실제
// 타이머로 기다린다 — act 는 다시 그리기와 효과를 약속보다 먼저 비워 순서 결함도 통과시킨다. 그래서 act 를 켜는 다른
// 시험과 섞지 않게 파일을 나눈다(useVodPlayback.test.ts 의 「버려진 전이 렌더 뒤 복귀」 와 같은 꼴).

const fake = vi.hoisted(() => ({
  instances: [] as FakeHls[],
  /** loadSource 가 곧바로 낼 목록 */
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
    if (event === FakeHls.Events.LEVEL_LOADED || event === FakeHls.Events.LEVEL_UPDATED) {
      this.latestLevelDetails = (data as { details: Details }).details;
    }
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
}

const BASE = 'https://media.pokeclip.com';
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const VOD = `${BASE}/vod/key-1/r-A/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
/** 옛 목록의 첫 조각 · 되감은 자리 — 시험이 시작할 때의 실제 시각에서 잰다(실제 타이머) */
const OLD_F = Date.now() - 700_000;
const TARGET = OLD_F + 100_000;

/** 첫 조각 firstMs · 4초 조각 n 개 목록 */
function listFrom(firstMs: number, n = 30): Details {
  const fragments = Array.from({ length: n }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: firstMs + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

function liveBody(): unknown {
  return {
    ready: true,
    mode: 'live',
    url: A1,
    firstPdt: new Date(OLD_F).toISOString(),
    headPdt: new Date(Date.now()).toISOString(),
  };
}

const media = { paused: false };

let session: PlaybackSession | null = null;

function current(): PlaybackSession {
  if (session === null) throw new Error('세션이 없다');
  return session;
}

function Player({ playback }: { playback: PlaybackSession }) {
  const ref = useRef<HTMLVideoElement>(null);
  useLiveRewindPlayback(ref, playback);
  return <video ref={ref} />;
}

/** playerKey 가 바뀌면 재생기가 내려갔다 다시 선다(출입증 회복 · 시계 key 와 같은 리마운트) */
function Screen({ options, playerKey }: { options: SessionOptions; playerKey: number }) {
  const playback = usePlaybackSession(options);
  session = playback;
  return playback === null ? null : <Player key={playerKey} playback={playback} />;
}

function options(onClose: SessionOptions['onClose']): SessionOptions {
  return {
    canOpen: true,
    roundId: 'r-A',
    accessKey: 'r-A|key-1',
    liveUrl: `${BASE}/live/key-1/index.m3u8`,
    controlKey: 'key-1',
    controlBase: BASE,
    resolveX: () => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }),
    onClose,
  };
}

/** 효과 · 약속 · 다시 그리기가 모두 끝나게 다른 매크로 작업을 여러 번 기다린다 */
async function drainTasks(times = 8) {
  for (let i = 0; i < times; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

async function until(check: () => boolean, what: string) {
  for (let i = 0; i < 100; i += 1) {
    if (check()) return;
    await drainTasks(1);
  }
  throw new Error(`기다림이 끝나지 않았다: ${what}`);
}

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** 지금 되감기 재생기가 영상 조각 404 를 받았다 — hls.js 가 앱에 올리는 꼴 */
function segmentGone(hls: FakeHls) {
  hls.trigger(FakeHls.Events.ERROR, {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details: FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
    fatal: false,
    frag: { type: 'main', sn: 7, initSegment: { url: `${BASE}/dvr/key-1/a1/init.mp4` } },
    response: { url: `${BASE}/dvr/key-1/a1/seg7.mp4`, code: 404, text: '' },
  });
}

function loadSources(): number {
  return fake.instances.reduce((sum, h) => sum + h.loadSource.mock.calls.length, 0);
}

const actScope = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };
let previousActEnvironment: boolean | undefined;
let root: Root | null = null;

beforeAll(async () => {
  // hls.js 읽기 약속을 미리 풀어 둔다 — 풀린 약속의 await 는 다음 마이크로과제에서 이어진다(실제 화면의 두 번째 재생기)
  await loadHls();
});

beforeEach(() => {
  previousActEnvironment = actScope.IS_REACT_ACT_ENVIRONMENT;
  actScope.IS_REACT_ACT_ENVIRONMENT = false;
  fake.instances.length = 0;
  fake.bootList = null;
  media.paused = false;
  session = null;
  stubFetch((url) =>
    url === STREAM ? jsonResponse(200, liveBody()) : jsonResponse(404, { error: 'not_found' }),
  );
  vi.spyOn(console, 'info').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => media.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(() => 0);
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (
    this: HTMLMediaElement,
  ) {
    media.paused = false;
    queueMicrotask(() => this.dispatchEvent(new Event('play')));
    return Promise.resolve();
  });
});

afterEach(() => {
  root?.unmount();
  root = null;
  actScope.IS_REACT_ACT_ENVIRONMENT = previousActEnvironment;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** 되감기 재생기로 TARGET 을 보던 중 선 다시보기 제안(띠 단추 · 아직 튼다) */
async function offeredScreen() {
  const onClose = vi.fn();
  const opts = options(onClose);
  const container = document.createElement('div');
  root = createRoot(container);
  const show = (playerKey: number) => root?.render(<Screen options={opts} playerKey={playerKey} />);
  show(1);
  await until(() => session?.state.rewindOpen === true, '스트림 폴링의 되감기 진입');
  fake.bootList = listFrom(OLD_F);
  const view = { lowerMs: Date.now() - 16_000, endMs: Date.now() };
  current().dispatch({ type: 'seek', targetMs: TARGET, view });
  await until(() => current().state.list !== null, '되감기 목록');
  current().dispatch({
    type: 'position',
    atMs: TARGET,
    playing: true,
    runMs: 0,
    epoch: current().positionEpoch(),
  });
  current().dispatch({ type: 'endlist' });
  current().dispatch({
    type: 'round-poll',
    result: {
      kind: 'ok',
      status: { ready: true, mode: 'vod', url: VOD, firstPdtMs: OLD_F, headPdtMs: Date.now() },
    },
  });
  await drainTasks();
  expect(current().state.phase).toMatchObject({ kind: 'vod-offered', stopped: null });
  expect(current().state.source.kind).toBe('rewind');
  return { rewindHls: lastHls(), show, onClose, container };
}

/** 리마운트 뒤 새 재생기가 hls.js 를 만들지도 받지도 않았고, 덮개 단추가 섰고, 단추는 앞 자리부터 넘긴다 */
function expectHeldCoverThenPress(
  created: number,
  loads: number,
  onClose: ReturnType<typeof vi.fn>,
) {
  expect({ created: fake.instances.length, loads: loadSources() }).toEqual({ created, loads });
  expect(current().view).toMatchObject({ cover: 'vod-ready', startHeld: true });
  current().dispatch({ type: 'button', which: 'vod' });
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(onClose.mock.calls[0]?.[0]).toMatchObject({
    reason: 'past',
    handoff: { resumeAtMs: TARGET, chosen: true },
  });
}

describe('받기를 멈춘 뒤 리마운트 — 실제 스케줄러(act 끔)', () => {
  it('제안 중 404 → 곧바로 리마운트 → 새 재생기는 hls.js 를 만들지 않고 받지 않는다 · 덮개 단추는 앞 자리부터', async () => {
    const { rewindHls, show, onClose } = await offeredScreen();
    const created = fake.instances.length;
    const loads = loadSources();

    segmentGone(rewindHls);
    show(2);
    await drainTasks();

    expectHeldCoverThenPress(created, loads, onClose);
  });

  it('사람이 멈춘 동안 404 → 다시 틀기 전 리마운트 → 새 재생기는 hls.js 를 만들지 않는다 · 덮개 단추', async () => {
    const { rewindHls, show, onClose, container } = await offeredScreen();
    const created = fake.instances.length;
    const loads = loadSources();
    // 사람이 멈춘다 — 재생기는 pause 사건으로만 안다
    media.paused = true;
    container.querySelector('video')?.dispatchEvent(new Event('pause'));

    segmentGone(rewindHls);
    show(2);
    await drainTasks();

    expectHeldCoverThenPress(created, loads, onClose);
  });
});
