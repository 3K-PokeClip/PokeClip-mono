import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  followVideo,
  SETTLE_MS,
  SETTLE_POLL_MS,
  TrackAudioPlayer,
  type AudioContextLike,
} from './trackAudioPlayer';
import type { ClockMark, VideoClock } from './videoFrameClock';
import type { TrackPiece } from './loadTrackAudio';

// 가짜 AudioContext: 시계(currentTime)를 손으로 돌리고, 만든 소리 노드가 어디에 이어져 언제 무엇부터 트는지를 적는다.
interface FakeSource {
  buffer: AudioBuffer | null;
  playbackRate: { value: number };
  target: FakeGain | null;
  startedAt: { when: number; offset: number } | null;
  stopped: boolean;
}
interface FakeGain {
  gain: { value: number };
  index: number;
}

function fakeContext() {
  const sources: FakeSource[] = [];
  const gains: FakeGain[] = [];
  const ctx = {
    currentTime: 100,
    state: 'running',
    destination: {},
    resume: vi.fn(() => Promise.resolve()),
    createGain() {
      const node = {
        gain: { value: 1 },
        index: gains.length,
        connect: vi.fn(),
        disconnect: vi.fn(),
      };
      gains.push(node);
      return node;
    },
    createBufferSource() {
      const node: FakeSource & Record<string, unknown> = {
        buffer: null,
        playbackRate: { value: 1 },
        target: null,
        startedAt: null,
        stopped: false,
        connect(target: FakeGain) {
          node.target = target;
        },
        disconnect: vi.fn(),
        start(when: number, offset = 0) {
          node.startedAt = { when, offset };
        },
        stop() {
          node.stopped = true;
        },
      };
      sources.push(node);
      return node;
    },
  };
  return { ctx: ctx as unknown as AudioContextLike & { currentTime: number }, sources, gains };
}

const buffer = (duration: number) => ({ duration }) as AudioBuffer;
// 트랙 둘: 0번은 10초부터 4초, 20초부터 4초 두 덩어리 · 1번은 10초부터 14초 한 덩어리
const TRACKS: TrackPiece[][] = [
  [
    { startSeconds: 10, buffer: buffer(4) },
    { startSeconds: 20, buffer: buffer(4) },
  ],
  [{ startSeconds: 10, buffer: buffer(14) }],
];

describe('TrackAudioPlayer', () => {
  it('멈춰 둔 소리 문맥을 깨우지 못하면 알린다: 영상 소리를 꺼 둔 채면 아무 소리도 안 난다(PR #341 codex)', async () => {
    const { ctx } = fakeContext();
    Object.assign(ctx, {
      state: 'suspended',
      resume: vi.fn(() => Promise.reject(new DOMException('closed', 'InvalidStateError'))),
    });
    const onBroken = vi.fn();
    new TrackAudioPlayer(ctx, TRACKS, onBroken).play(0, 1);
    await vi.waitFor(() => expect(onBroken).toHaveBeenCalledTimes(1));
  });

  it('지금 자리부터 덩어리마다 틀 시각·시작점을 맞춰 그 트랙의 음량 노드에 잇는다', () => {
    const { ctx, sources } = fakeContext();
    const player = new TrackAudioPlayer(ctx, TRACKS);
    player.play(12, 1);

    const live = sources.filter((s) => s.startedAt !== null);
    expect(live.map((s) => [s.target?.index, s.startedAt])).toEqual([
      [0, { when: 100, offset: 2 }], // 10~14초 덩어리의 2초 자리부터 지금
      [0, { when: 108, offset: 0 }], // 20초 덩어리는 8초 뒤
      [1, { when: 100, offset: 2 }],
    ]);
  });

  it('이미 지나간 덩어리는 안 튼다 · 배속이면 기다리는 시간도 배속만큼 줄어든다', () => {
    const { ctx, sources } = fakeContext();
    new TrackAudioPlayer(ctx, TRACKS).play(15, 2);
    const zero = sources.filter((s) => s.target?.index === 0);
    expect(zero.map((s) => s.startedAt)).toEqual([{ when: 102.5, offset: 0 }]);
    expect(zero[0]!.playbackRate.value).toBe(2);
  });

  it('트랙마다 음량을 따로 건다: 끈 트랙은 0이다', () => {
    const { ctx, gains } = fakeContext();
    const player = new TrackAudioPlayer(ctx, TRACKS);
    player.setGains([0, 1.3]);
    expect(gains.map((g) => g.gain.value)).toEqual([0, 1.3]);
  });

  it('멈추면 튼 노드를 전부 세우고 위치가 없다 · 다시 틀면 앞 노드를 세우고 새로 건다', () => {
    const { ctx, sources } = fakeContext();
    const player = new TrackAudioPlayer(ctx, TRACKS);
    player.play(12, 1);
    ctx.currentTime = 101.5;
    expect(player.position()).toBeCloseTo(13.5);
    player.play(20, 1);
    expect(sources.slice(0, 3).every((s) => s.stopped)).toBe(true);
    player.stop();
    expect(sources.every((s) => s.stopped || s.startedAt === null)).toBe(true);
    expect(player.position()).toBeNull();
  });
});

// 가짜 영상 노드: 사건(재생·멈춤·이동·배속)을 손으로 쏜다
function fakeVideo() {
  const target = new EventTarget();
  return Object.assign(target, {
    paused: true,
    seeking: false,
    currentTime: 0,
    playbackRate: 1,
    muted: false,
    readyState: 4,
  }) as unknown as HTMLVideoElement & EventTarget;
}

/**
 * 가짜 영상 시계: 자리는 at(정하지 않으면 currentTime), 표지는 frames(받은 장면 수)와 currentTime 이다. 실물(frameClock)처럼
 * restart 하면 currentTime 으로 돌아간다. 실물은 videoFrameClock.test.ts 가 본다
 */
function fakeClock(video: HTMLVideoElement) {
  const clock = {
    at: null as number | null,
    frames: 0,
    position: () => clock.at ?? video.currentTime,
    mark: (): ClockMark => ({ frames: clock.frames, currentTime: video.currentTime }),
    restart: vi.fn(() => {
      clock.at = null;
    }),
    dispose: vi.fn(),
  };
  return clock satisfies VideoClock;
}

describe('followVideo: 소리를 영상 노드에 맞춰 튼다', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function setup(state = 'running') {
    const { ctx } = fakeContext();
    Object.assign(ctx, { state });
    const player = new TrackAudioPlayer(ctx, TRACKS);
    const play = vi.spyOn(player, 'play');
    const video = fakeVideo();
    const clock = fakeClock(video);
    const stopFollowing = followVideo(player, video, clock);
    /** 영상과 소리 시계가 함께 seconds 만큼 흐른다(장면도 하나 온다). 영상 자리는 배속만큼 나아간다 */
    const flow = (seconds: number) => {
      ctx.currentTime += seconds;
      video.currentTime += seconds * video.playbackRate;
      if (clock.at !== null) clock.at += seconds * video.playbackRate;
      clock.frames += 1;
      vi.advanceTimersByTime(seconds * 1000);
    };
    /** 소리 시계만 seconds 만큼 흐르고 영상은 선다(장면도 currentTime 도 그대로) */
    const stand = (seconds: number) => {
      ctx.currentTime += seconds;
      vi.advanceTimersByTime(seconds * 1000);
    };
    /** 재생을 시작하고 한 번 더 맞추는 때까지 흘린다: 첫 살핌(SETTLE_POLL_MS)에 나아간 것을 보고 SETTLE_MS 뒤 */
    const startAndSettle = (at: number) => {
      Object.assign(video, { paused: false, currentTime: at });
      video.dispatchEvent(new Event('playing'));
      for (let ms = 0; ms < SETTLE_POLL_MS + SETTLE_MS; ms += 50) flow(0.05);
    };
    return { ctx, player, play, video, clock, flow, stand, startAndSettle, stopFollowing };
  }

  it('영상이 버퍼링 중이면(waiting, 다음 장면 없음) 어긋남 검사가 소리를 다시 틀지 않는다(로컬 리뷰 1라운드)', () => {
    const { play, video } = setup();
    Object.assign(video, { paused: false, currentTime: 2 });
    video.dispatchEvent(new Event('playing'));
    expect(play).toHaveBeenCalledTimes(1);

    // 영상이 멈춰 기다린다: paused·seeking은 false인데 장면이 안 나간다
    Object.assign(video, { readyState: 2 });
    video.dispatchEvent(new Event('waiting'));
    vi.advanceTimersByTime(1000);
    expect(play).toHaveBeenCalledTimes(1);

    // 다시 흐르면 그 자리부터
    Object.assign(video, { readyState: 4, currentTime: 2.1 });
    video.dispatchEvent(new Event('playing'));
    expect(play).toHaveBeenLastCalledWith(2.1, 1);
  });

  it('소리 문맥이 아직 멈춰 있으면(suspended) 0.25초마다 노드를 새로 걸지 않는다(로컬 리뷰 1라운드)', () => {
    const { play, video } = setup('suspended');
    Object.assign(video, { paused: false });
    video.dispatchEvent(new Event('playing'));
    // 영상은 흐르는데 소리 시계는 멈춰 있다
    for (let i = 1; i <= 4; i++) {
      video.currentTime = i * 0.25;
      vi.advanceTimersByTime(250);
    }
    expect(play).toHaveBeenCalledTimes(1);
  });

  it('영상이 재생되면 그 자리·배속으로 틀고, 멈추거나 옮기기 시작하면 세운다', () => {
    const { player, play, video } = setup();
    Object.assign(video, { paused: false, currentTime: 3, playbackRate: 1.5 });
    video.dispatchEvent(new Event('playing'));
    expect(play).toHaveBeenLastCalledWith(3, 1.5);

    Object.assign(video, { seeking: true });
    video.dispatchEvent(new Event('seeking'));
    expect(player.position()).toBeNull();
    Object.assign(video, { seeking: false, currentTime: 1 });
    video.dispatchEvent(new Event('seeked'));
    expect(play).toHaveBeenLastCalledWith(1, 1.5);

    Object.assign(video, { paused: true });
    video.dispatchEvent(new Event('pause'));
    expect(player.position()).toBeNull();
  });

  it('소리와 영상이 0.08초 넘게 벌어지면 영상 자리로 다시 맞춘다: 그 안이면 그대로 둔다', () => {
    const { ctx, play, video, clock, startAndSettle } = setup();
    startAndSettle(0);
    const settled = video.currentTime;
    play.mockClear();

    // 소리 시계 1초, 영상 0.05초 더: 0.05초 차이는 둔다
    ctx.currentTime += 1;
    video.currentTime += 1.05;
    clock.frames += 1;
    vi.advanceTimersByTime(250);
    expect(play).not.toHaveBeenCalled();

    // 영상이 버벅여 0.15초 뒤처졌다: 0.1초 차이면 다시 맞춘다
    video.currentTime -= 0.15;
    clock.frames += 1;
    vi.advanceTimersByTime(250);
    expect(play).toHaveBeenCalledTimes(1);
    expect(play.mock.lastCall?.[0]).toBeCloseTo(settled + 0.9, 6);
  });

  // 실측 M6 r2(2026-11-13): Firefox currentTime 은 낡은 값이 와서(다시 건 뒤 20ms 에 영상이 64–444ms 앞섬 ·
  // results/summary.txt Firefox v2 「닻 뒤」 줄) 그 값과 견주어 어긋났다고 보고 다시 건 소리가 영상보다 85–177ms 늦었다
  // (README 47줄)
  it('어긋남은 영상 시계(그려진 프레임)로 재고 그 자리로 다시 건다: 낡은 currentTime 은 안 본다', () => {
    const { ctx, play, video, clock, startAndSettle } = setup();
    startAndSettle(0);
    play.mockClear();

    // 프레임은 제때 오는데 currentTime 이 0.15초 낡았다: 소리는 영상과 맞다
    clock.at = 1.3;
    ctx.currentTime += 1;
    video.currentTime += 0.85;
    clock.frames += 1;
    vi.advanceTimersByTime(250);
    expect(play).not.toHaveBeenCalled();

    // 영상이 정말 뒤처지면(프레임 기준) 프레임 자리로 건다
    clock.at = 1.15;
    clock.frames += 1;
    vi.advanceTimersByTime(250);
    expect(play).toHaveBeenCalledTimes(1);
    expect(play.mock.lastCall?.[0]).toBeCloseTo(1.15, 6);
  });

  // 실측 M6 r2(README 41줄): WebKit · Safari 는 재생 시작 뒤 5–16ms 에 timeupdate 가 오고 영상 자리도 1–8ms 움직였지만,
  // 그 뒤 50–200ms 사이에 영상이 55–106ms 더 뒤처졌다. 이른 timeupdate 에서 맞추면 그 뒤처짐이 그대로 굳었다
  it('재생을 시작하면 영상이 흐르기 시작한 뒤 SETTLE_MS 에 한 번 더 건다: 이른 timeupdate 로는 걸지 않는다', () => {
    const { play, video, clock, flow } = setup();
    Object.assign(video, { paused: false, currentTime: 3 });
    video.dispatchEvent(new Event('playing'));
    expect(play).toHaveBeenCalledTimes(1);

    // 시작 직후 영상 자리가 움직인 timeupdate
    clock.at = 3;
    flow(0.01);
    video.dispatchEvent(new Event('timeupdate'));
    expect(play).toHaveBeenCalledTimes(1);

    // 그 뒤 영상이 0.06초 뒤처진다(문턱 안). 나아간 것은 첫 살핌(SETTLE_POLL_MS)에 보고, 그때부터 SETTLE_MS 뒤 건다
    clock.at -= 0.06;
    for (let ms = 10; ms < SETTLE_POLL_MS + SETTLE_MS - 50; ms += 50) {
      flow(0.05);
      video.dispatchEvent(new Event('timeupdate'));
    }
    expect(play).toHaveBeenCalledTimes(1);
    flow(0.05);
    expect(play).toHaveBeenCalledTimes(2);
    expect(play.mock.lastCall?.[0]).toBeCloseTo(clock.at, 6);

    // 한 번뿐이다: 재조정마다 소리가 끊긴다
    for (let i = 0; i < 10; i++) flow(0.05);
    expect(play).toHaveBeenCalledTimes(2);
  });

  // 리뷰(M6 2차 r1): 시작 뒤 280ms 에 처음 나아가면 300ms 타이머는 나아간 뒤 20ms 만 기다린 셈이었다
  it('한 번 더 거는 때는 영상이 처음 나아간 것을 본 때부터 SETTLE_MS 뒤다', () => {
    const { play, video, clock, flow } = setup();
    Object.assign(video, { paused: false, currentTime: 3 });
    video.dispatchEvent(new Event('playing'));
    // 280ms 동안 영상도 소리 시계도 아직 출발 전이다
    vi.advanceTimersByTime(280);
    for (let ms = 280; ms < 280 + SETTLE_MS - 10; ms += 10) flow(0.01);
    expect(clock.frames).toBeGreaterThan(0);
    expect(play).toHaveBeenCalledTimes(1);

    // 처음 나아간 것을 늦어도 SETTLE_POLL_MS 안에 본다
    for (let ms = 0; ms < SETTLE_POLL_MS + 10; ms += 10) flow(0.01);
    expect(play).toHaveBeenCalledTimes(2);
  });

  it('시작 뒤 SETTLE_MS 가 지나도 영상이 아직 서 있으면 흐르기 시작할 때까지 미룬다', () => {
    const { play, video, flow } = setup();
    Object.assign(video, { paused: false, currentTime: 3 });
    video.dispatchEvent(new Event('playing'));
    vi.advanceTimersByTime(SETTLE_MS);
    expect(play).toHaveBeenCalledTimes(1);

    flow(0.05);
    vi.advanceTimersByTime(SETTLE_MS);
    expect(play).toHaveBeenCalledTimes(2);
  });

  it('옮긴 뒤(seeked)에도 SETTLE_MS 에 한 번 더 건다 · 그 전에 멈추면 걸지 않는다', () => {
    const { play, video, startAndSettle, flow } = setup();
    startAndSettle(3);
    expect(play).toHaveBeenCalledTimes(2);

    Object.assign(video, { seeking: true });
    video.dispatchEvent(new Event('seeking'));
    Object.assign(video, { seeking: false, currentTime: 1 });
    video.dispatchEvent(new Event('seeked'));
    expect(play).toHaveBeenLastCalledWith(1, 1);
    for (let ms = 0; ms < SETTLE_POLL_MS + SETTLE_MS; ms += 50) flow(0.05);
    expect(play).toHaveBeenCalledTimes(4);

    // 옮기고 곧 멈춘다: 맞추려던 것도 거둔다
    video.dispatchEvent(new Event('seeked'));
    expect(play).toHaveBeenCalledTimes(5);
    Object.assign(video, { paused: true });
    video.dispatchEvent(new Event('pause'));
    vi.advanceTimersByTime(SETTLE_MS * 2);
    expect(play).toHaveBeenCalledTimes(5);
  });

  // 실측 M6 r2(README 59 · 62줄): WebKit 은 재생 중 최대 1925ms 장면이 섰는데 readyState 는 3 아래로 안 내려가고 waiting
  // 도 없었다. 그동안 0.25초마다 같은 자리로 다시 걸어 소리가 202–266ms 앞서고 같은 0.25초를 되풀이했다(삑 하나가 세 번 들림)
  it('흐른다던 영상이 주기 검사 사이 전혀 안 나아가면 소리를 세우고, 다시 흐르면 그 자리부터 다시 건다', () => {
    const { ctx, player, play, video, clock, startAndSettle, flow } = setup();
    startAndSettle(2);
    play.mockClear();

    // 장면도 currentTime 도 선다(소리 시계만 흐른다). 시계는 마지막 장면에서 계속 민다 — 멈춤을 알아채기 전까지는 소리와 맞다
    clock.at = video.currentTime;
    for (let i = 0; i < 8; i++) {
      ctx.currentTime += 0.25;
      if (clock.at !== null) clock.at += 0.25;
      vi.advanceTimersByTime(250);
    }
    expect(play).not.toHaveBeenCalled();
    expect(player.position()).toBeNull();
    expect(clock.restart).toHaveBeenCalled();

    // 다시 흐른다: 그 자리부터 건다
    flow(0.25);
    expect(play).toHaveBeenCalledTimes(1);
    expect(play.mock.lastCall?.[0]).toBeCloseTo(video.currentTime, 6);
  });

  // 리뷰(M6 2차 r1): 멈춤으로 세운 뒤 배속을 바꾸면 소리만 다시 흘렀고, 그 뒤 검사는 이미 멈춤이라 다시 세우지 않았다
  it('멈춤으로 세운 동안에는 배속을 바꿔도 소리를 켜지 않는다: 영상이 다시 나아갈 때 새 배속으로 건다', () => {
    const { player, play, video, startAndSettle, flow, stand } = setup();
    startAndSettle(2);
    stand(1);
    expect(player.position()).toBeNull();
    play.mockClear();

    Object.assign(video, { playbackRate: 2 });
    video.dispatchEvent(new Event('ratechange'));
    expect(player.position()).toBeNull();
    stand(1);
    expect(play).not.toHaveBeenCalled();
    expect(player.position()).toBeNull();

    flow(0.25);
    expect(play).toHaveBeenCalledTimes(1);
    expect(play.mock.lastCall?.[1]).toBe(2);
  });

  // 리뷰(M6 2차 r1): 프레임 콜백이 없는 정상 재생에서 currentTime 이 검사 간격(0.25초)보다 드물게 바뀌면 멈춤으로 보고 소리를 껐다
  it('장면을 아직 하나도 못 받았으면(프레임 콜백 없음) currentTime 이 드물게 바뀌어도 멈춤으로 보지 않는다', () => {
    const { ctx, player, video } = setup();
    Object.assign(video, { paused: false, currentTime: 2 });
    video.dispatchEvent(new Event('playing'));
    for (let i = 0; i < 8; i++) {
      ctx.currentTime += 0.25;
      if (i % 2 === 1) video.currentTime += 0.5; // 0.5초마다 한 번 갱신
      vi.advanceTimersByTime(250);
      expect(player.position(), `검사 ${i}`).not.toBeNull();
    }
  });

  it('숨은 탭이면(장면 콜백이 안 돈다) 장면도 currentTime 도 안 움직여도 멈춤으로 보지 않는다', () => {
    const { player, startAndSettle, stand } = setup();
    startAndSettle(2);
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    try {
      stand(1);
      expect(player.position()).not.toBeNull();
    } finally {
      visibility.mockRestore();
    }
  });

  it('장면은 오는데 currentTime 만 서 있거나(Firefox 의 거친 갱신), currentTime 은 흐르는데 장면이 안 오면(숨은 탭) 멈춤이 아니다', () => {
    const { ctx, player, video, clock, startAndSettle } = setup();
    startAndSettle(2);

    for (let i = 0; i < 4; i++) {
      ctx.currentTime += 0.25;
      clock.at = (clock.at ?? video.currentTime) + 0.25;
      clock.frames += 1;
      vi.advanceTimersByTime(250);
    }
    expect(player.position()).not.toBeNull();

    clock.at = null;
    video.currentTime = clock.position();
    for (let i = 0; i < 4; i++) {
      ctx.currentTime += 0.25;
      video.currentTime += 0.25;
      vi.advanceTimersByTime(250);
    }
    expect(player.position()).not.toBeNull();
  });

  it('배속만 바꾸면(ratechange) 시계가 이미 흐르니 한 번 더 걸지 않는다 · 멈춘 영상의 timeupdate 로 틀지 않는다', () => {
    const { player, play, video, startAndSettle, flow } = setup();
    startAndSettle(3);
    expect(play).toHaveBeenCalledTimes(2);

    Object.assign(video, { playbackRate: 2 });
    video.dispatchEvent(new Event('ratechange'));
    expect(play).toHaveBeenCalledTimes(3);
    for (let ms = 0; ms < SETTLE_MS * 2; ms += 50) flow(0.05);
    expect(play).toHaveBeenCalledTimes(3);

    // 멈춘 뒤 옮긴 자리의 timeupdate 는 소리를 틀지 않는다
    Object.assign(video, { paused: true });
    video.dispatchEvent(new Event('pause'));
    video.currentTime = 7;
    video.dispatchEvent(new Event('timeupdate'));
    vi.advanceTimersByTime(1000);
    expect(play).toHaveBeenCalledTimes(3);
    expect(player.position()).toBeNull();
  });

  it('그만두면 사건을 더 안 듣고 소리를 세우고 시계를 버린다', () => {
    const { player, play, video, clock, stopFollowing } = setup();
    Object.assign(video, { paused: false });
    video.dispatchEvent(new Event('playing'));
    play.mockClear();
    stopFollowing();
    expect(player.position()).toBeNull();
    expect(clock.dispose).toHaveBeenCalled();
    video.dispatchEvent(new Event('playing'));
    vi.advanceTimersByTime(1000);
    expect(play).not.toHaveBeenCalled();
  });
});
