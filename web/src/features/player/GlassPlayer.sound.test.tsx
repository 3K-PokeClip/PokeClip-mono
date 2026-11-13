import { act, type ComponentProps } from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '@/ui';
import { GlassPlayer } from '@/features/player/GlassPlayer';
import type { PlayerSound } from '@/features/player/playbackSound';
import { openSession } from '@/features/player/rewindSwitch';
import { sessionView } from '@/features/player/sessionView';
import type { PlaybackSession } from '@/features/player/usePlaybackSession';
import type { VodSource } from '@/features/player/useVodPlayback';

// 다시보기로 넘겨받은 소리 상태(POK-547). 다시보기 재생기는 영상 노드가 새로 서므로 넘겨받은 무음 · 음량을 첫 재생 전에
// 영상 노드와 화면 상태에 싣고, 브라우저가 소리 켠 재생을 막으면 음량은 두고 소리만 꺼서 한 번 더 튼다. 넘겨받은 소리가
// 없는 재생기(끝난 뒤 들어온 화면 · 라이브 · 재생 세션)는 지금 그대로다 — 무음 · 70 으로 서고 막혀도 다시 틀지 않는다.
// 실제 컴포넌트를 그려 <video muted> 정적 속성과 재생기의 마운트 효과가 어느 쪽이 이기는지까지 본다.

const hlsFake = vi.hoisted(() => ({ supported: true, instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

class FakeHls {
  static isSupported = () => hlsFake.supported;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    ERROR: 'hlsError',
  };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static ErrorDetails = {
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
  latestLevelDetails: null = null;
  liveSyncPosition: number | null = null;
  constructor() {
    hlsFake.instances.push(this);
  }
  on(event: string, handler: (event: string, data: unknown) => void) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  off() {}
  trigger(event: string, data: unknown) {
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  stopLoad() {}
  recoverMediaError() {}
  destroy() {}
}

/** play() 가 차례로 낼 결과 — 'ok' 면 재생, 그 밖은 그 이름의 DOMException 으로 거절 */
type PlayResult = 'ok' | 'NotAllowedError' | 'AbortError';

/** play() 를 부를 때마다 그 순간 영상 노드의 소리 값 */
const seen: Array<{ muted: boolean; volume: number }> = [];
/** 다음 play() 들이 낼 결과(다 쓰면 'ok') */
let results: PlayResult[] = [];

const PDT0 = Date.parse('2026-11-04T10:00:00.000Z');
const VOD_URL = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
const VOD: VodSource = {
  url: VOD_URL,
  firstPdtMs: PDT0,
  headPdtMs: PDT0 + 7_200_000,
  axisZeroMs: PDT0,
  originAxis: true,
};
/** 세션 재생기에서 소리를 켜고 음량 30 으로 보다가 넘겼다 */
const LOUD_30: PlayerSound = { muted: false, volume: 30 };

beforeEach(() => {
  hlsFake.supported = true;
  hlsFake.instances.length = 0;
  seen.length = 0;
  results = [];
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (
    this: HTMLMediaElement,
  ) {
    seen.push({ muted: this.muted, volume: this.volume });
    const result = results[seen.length - 1] ?? 'ok';
    return result === 'ok'
      ? Promise.resolve()
      : Promise.reject(new DOMException('play() 거절', result));
  });
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined);
  vi.spyOn(console, 'info').mockImplementation(() => undefined);
});
afterEach(() => {
  vi.restoreAllMocks();
});

function lastHls(): FakeHls {
  const h = hlsFake.instances[hlsFake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

async function renderPlayer(props: Partial<ComponentProps<typeof GlassPlayer>>) {
  const rendered = render(
    <ToastProvider>
      <GlassPlayer
        channelName="게임하는너구리"
        viewersNote="시청자 수 수집 전"
        broadcastStatus="ended"
        embed
        {...props}
      />
    </ToastProvider>,
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  return { ...rendered, video: rendered.container.querySelector('video')! };
}

/** hls.js 재생기가 목록을 읽었다 — 재생기가 첫 play() 를 부른다. 거절 → 다시 시도의 약속 사슬까지 기다린다 */
async function manifestParsed() {
  await act(async () => {
    lastHls().trigger(FakeHls.Events.MANIFEST_PARSED, {});
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

/** 기본 HLS 재생기는 소스를 붙이자마자 첫 play() 를 부른다 — 약속 사슬까지 기다린다 */
async function settlePlays() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

const volumeSlider = () => screen.getByRole('slider', { name: '볼륨' });

describe('다시보기 재생기 — 넘겨받은 소리 상태를 첫 재생 전에 싣는다', () => {
  it('hls.js: 넘겨받은 소리(켬 · 30)가 <video muted> 를 이기고, 첫 play() 는 그 값을 본다 · 화면도 그 값이다', async () => {
    const { video } = await renderPlayer({ vod: VOD, vodSound: LOUD_30 });

    expect(video.muted).toBe(false);
    expect(video.volume).toBeCloseTo(0.3);
    await manifestParsed();

    expect(seen).toEqual([{ muted: false, volume: expect.closeTo(0.3) }]);
    expect(volumeSlider()).toHaveValue('30');
    expect(screen.getByRole('button', { name: '음소거' })).toBeInTheDocument();
  });

  it('기본 HLS: 첫 play() 전에 같은 값이고, 막히면 소리만 꺼서 한 번 더 튼다', async () => {
    hlsFake.supported = false;
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    results = ['NotAllowedError'];

    const { video } = await renderPlayer({ vod: VOD, vodSound: LOUD_30 });
    await settlePlays();

    expect(seen).toEqual([
      { muted: false, volume: expect.closeTo(0.3) },
      { muted: true, volume: expect.closeTo(0.3) },
    ]);
    expect(video.muted).toBe(true);
  });

  it('넘겨받은 무음은 무음 · 음량 그대로다(70 으로 돌리지 않는다)', async () => {
    const { video } = await renderPlayer({ vod: VOD, vodSound: { muted: true, volume: 30 } });
    await manifestParsed();

    expect(seen).toEqual([{ muted: true, volume: expect.closeTo(0.3) }]);
    expect(video.muted).toBe(true);
    expect(video.volume).toBeCloseTo(0.3);
    expect(screen.getByRole('button', { name: '음소거 해제' })).toBeInTheDocument();
  });

  it('소리 켠 첫 재생이 막히면(NotAllowedError) 음량은 두고 무음으로 한 번 더 튼다 · 화면도 무음이다', async () => {
    results = ['NotAllowedError'];
    const { video } = await renderPlayer({ vod: VOD, vodSound: LOUD_30 });
    await manifestParsed();

    expect(seen).toEqual([
      { muted: false, volume: expect.closeTo(0.3) },
      { muted: true, volume: expect.closeTo(0.3) },
    ]);
    expect(video.muted).toBe(true);
    expect(video.volume).toBeCloseTo(0.3);
    expect(screen.getByRole('button', { name: '음소거 해제' })).toBeInTheDocument();
  });

  it('무음으로도 막히면 두 번에서 멈추고 재생 단추가 남는다', async () => {
    results = ['NotAllowedError', 'NotAllowedError'];
    await renderPlayer({ vod: VOD, vodSound: LOUD_30 });
    await manifestParsed();

    expect(seen).toHaveLength(2);
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('소스 교체로 취소된 재생(AbortError)은 다시 틀지 않는다', async () => {
    results = ['AbortError'];
    const { video } = await renderPlayer({ vod: VOD, vodSound: LOUD_30 });
    await manifestParsed();

    expect(seen).toHaveLength(1);
    expect(video.muted).toBe(false);
  });

  it('무음 대체는 그 재생기의 첫 재생 하나뿐이다 — 같은 재생기가 다른 목록을 다시 틀면 막혀도 다시 틀지 않는다', async () => {
    const view = await renderPlayer({ vod: VOD, vodSound: LOUD_30 });
    await manifestParsed();
    expect(seen).toHaveLength(1);

    results = ['ok', 'NotAllowedError'];
    view.rerender(
      <ToastProvider>
        <GlassPlayer
          channelName="게임하는너구리"
          viewersNote="시청자 수 수집 전"
          broadcastStatus="ended"
          embed
          vod={{ ...VOD, url: `${VOD_URL}?b` }}
          vodSound={LOUD_30}
        />
      </ToastProvider>,
    );
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    await manifestParsed();

    expect(seen).toHaveLength(2);
    expect(view.video.muted).toBe(false);
  });
});

describe('넘겨받은 소리가 없는 재생기 — 지금 그대로(무음 · 70 · 막히면 재생 단추)', () => {
  function fakeSession(): PlaybackSession {
    const state = openSession({
      roundId: 'r-A',
      accessKey: 'r-A|key-1',
      liveUrl: 'https://m/live/key-1/index.m3u8',
    });
    return {
      state,
      view: sessionView(state, Date.now()),
      dispatch: vi.fn(),
      liveButton: vi.fn(),
      bindPlayer: vi.fn(),
      notice: vi.fn(),
      positionEpoch: () => 0,
      isKnownDvrHole: () => false,
      isStartHeld: () => false,
      watchesDrain: () => false,
    };
  }

  const players: Array<[string, Partial<ComponentProps<typeof GlassPlayer>>]> = [
    ['끝난 뒤 들어온 화면의 다시보기', { vod: VOD }],
    ['라이브', { src: 'https://m/live/key-1/index.m3u8', broadcastStatus: 'live' }],
    ['재생 세션', { session: fakeSession(), broadcastStatus: 'live' }],
  ];

  it.each(players)(
    '%s: 무음 · 70 으로 서고, 첫 재생이 막혀도 다시 틀지 않는다',
    async (_name, props) => {
      results = ['NotAllowedError'];
      const { video } = await renderPlayer(props);

      expect(video.muted).toBe(true);
      expect(video.volume).toBeCloseTo(0.7);
      await manifestParsed();

      expect(seen).toEqual([{ muted: true, volume: expect.closeTo(0.7) }]);
      expect(screen.getByRole('button', { name: '음소거 해제' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
    },
  );
});
