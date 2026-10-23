import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { followVideo, TrackAudioPlayer, type AudioContextLike } from './trackAudioPlayer';
import type { TrackPiece } from './loadTrackAudio';

// 가짜 AudioContext — 시계(currentTime)를 손으로 돌리고, 만든 소리 노드가 어디에 이어져 언제 무엇부터 트는지를 적는다.
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
// 트랙 둘 — 0번은 10초부터 4초, 20초부터 4초 두 덩어리 · 1번은 10초부터 14초 한 덩어리
const TRACKS: TrackPiece[][] = [
  [
    { startSeconds: 10, buffer: buffer(4) },
    { startSeconds: 20, buffer: buffer(4) },
  ],
  [{ startSeconds: 10, buffer: buffer(14) }],
];

describe('TrackAudioPlayer', () => {
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

  it('트랙마다 음량을 따로 건다 — 끈 트랙은 0이다', () => {
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

// 가짜 영상 노드 — 사건(재생·멈춤·이동·배속)을 손으로 쏜다
function fakeVideo() {
  const target = new EventTarget();
  return Object.assign(target, {
    paused: true,
    seeking: false,
    currentTime: 0,
    playbackRate: 1,
    muted: false,
  }) as unknown as HTMLVideoElement & EventTarget;
}

describe('followVideo — 소리를 영상 노드에 맞춰 튼다', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function setup() {
    const { ctx } = fakeContext();
    const player = new TrackAudioPlayer(ctx, TRACKS);
    const play = vi.spyOn(player, 'play');
    const video = fakeVideo();
    // 영상 노드의 0초 = 편집기 10초(창 시작)
    const stopFollowing = followVideo(player, video, () => 10 + video.currentTime);
    return { ctx, player, play, video, stopFollowing };
  }

  it('따라가는 동안 영상 소리는 끄고, 그만두면 원래대로 돌린다', () => {
    const { video, stopFollowing } = setup();
    expect(video.muted).toBe(true);
    stopFollowing();
    expect(video.muted).toBe(false);
  });

  it('영상이 재생되면 그 자리·배속으로 틀고, 멈추거나 옮기기 시작하면 세운다', () => {
    const { player, play, video } = setup();
    Object.assign(video, { paused: false, currentTime: 3, playbackRate: 1.5 });
    video.dispatchEvent(new Event('playing'));
    expect(play).toHaveBeenLastCalledWith(13, 1.5);

    Object.assign(video, { seeking: true });
    video.dispatchEvent(new Event('seeking'));
    expect(player.position()).toBeNull();
    Object.assign(video, { seeking: false, currentTime: 1 });
    video.dispatchEvent(new Event('seeked'));
    expect(play).toHaveBeenLastCalledWith(11, 1.5);

    Object.assign(video, { paused: true });
    video.dispatchEvent(new Event('pause'));
    expect(player.position()).toBeNull();
  });

  it('소리와 영상이 0.08초 넘게 벌어지면 영상 자리로 다시 맞춘다 — 그 안이면 그대로 둔다', () => {
    const { ctx, play, video } = setup();
    Object.assign(video, { paused: false, currentTime: 0 });
    video.dispatchEvent(new Event('playing'));
    expect(play).toHaveBeenCalledTimes(1);

    // 소리 시계 1초, 영상 1.05초 — 0.05초 차이는 둔다
    ctx.currentTime += 1;
    video.currentTime = 1.05;
    vi.advanceTimersByTime(250);
    expect(play).toHaveBeenCalledTimes(1);

    // 영상이 버벅여 0.9초 — 0.1초 차이면 다시 맞춘다
    video.currentTime = 0.9;
    vi.advanceTimersByTime(250);
    expect(play).toHaveBeenCalledTimes(2);
    expect(play).toHaveBeenLastCalledWith(10.9, 1);
  });

  it('그만두면 사건을 더 안 듣고 소리를 세운다', () => {
    const { player, play, video, stopFollowing } = setup();
    Object.assign(video, { paused: false });
    video.dispatchEvent(new Event('playing'));
    play.mockClear();
    stopFollowing();
    expect(player.position()).toBeNull();
    video.dispatchEvent(new Event('playing'));
    vi.advanceTimersByTime(1000);
    expect(play).not.toHaveBeenCalled();
  });
});
