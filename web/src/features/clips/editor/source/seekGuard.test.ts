import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RemuxedWindow } from './remuxWindow';
import { clampSeekBlob, seekAndLand, seekLimitsOf, FIRST_FRAME_MARGIN_SECONDS } from './seekGuard';

// 창 Blob 시크의 화면 규칙 셋(kty M1 처분 (가) 2026-11-12 · 실측 runs/2026-11-12-prw2-m1). 출력 mp4 는 영상 앞에 빈 편집
// [0, PTS_out) 을 두고, Firefox 는 duration 에서 그 길이를 빼 끝 두 프레임에 닿지 못하며, WebKit 은 seeked 뒤에도 묵은 그림을
// 보일 수 있다.

/** M1 픽스처 꼴 — 소리가 79ms 먼저라 Blob 0초 = 소리 첫 PTS, 영상 첫 프레임 = 0.079초 */
function remuxed(): RemuxedWindow {
  const shift = 1234.5;
  const frame = 1 / 30;
  const seg = (seq: number, from: number, count: number) => {
    const frames = Array.from({ length: count }, (_, i) => ({ pts: from + i * frame, dur: frame }));
    return {
      seq,
      videoDts: from,
      frames,
      mediaStart: from,
      mediaEnd: from + count * frame,
    };
  };
  return {
    blob: new Blob(['mp4']),
    shiftSeconds: shift,
    segments: [seg(300, shift + 0.079, 120), seg(301, shift + 0.079 + 4, 120)],
  };
}

describe('seekLimitsOf', () => {
  it('창 첫 영상 프레임의 Blob 시각과 끝 프레임 길이의 반을 낸다', () => {
    const limits = seekLimitsOf(remuxed());

    expect(limits.firstFrame).toBeCloseTo(0.079, 9);
    expect(limits.halfFrame).toBeCloseTo(1 / 60, 9);
  });
});

describe('clampSeekBlob', () => {
  const limits = { firstFrame: 0.079, halfFrame: 1 / 60 };

  it('② 시작 · 정지 자리는 첫 영상 프레임 PTS_out 앞(빈 편집 구간)으로 가지 않는다 — Safari 는 거기서 앞 그림을 남긴다', () => {
    expect(clampSeekBlob(0, limits, 12.079)).toBeCloseTo(0.079 + FIRST_FRAME_MARGIN_SECONDS, 9);
    expect(clampSeekBlob(0.05, limits, 12.079)).toBeCloseTo(0.079 + FIRST_FRAME_MARGIN_SECONDS, 9);
    expect(clampSeekBlob(3, limits, 12.079)).toBe(3);
  });

  it('① 시크 목표는 video.duration − 반 프레임 안이다 — Firefox 가 빈 편집만큼 짧게 알려도 닿는 마지막 프레임을 보인다', () => {
    const firefoxDuration = 12.000295;

    expect(clampSeekBlob(12.06, limits, firefoxDuration)).toBeCloseTo(firefoxDuration - 1 / 60, 9);
    expect(clampSeekBlob(12.06, limits, 12.079299)).toBeCloseTo(12.06, 9);
  });

  it('길이를 아직 모르면(NaN) 끝은 묶지 않는다', () => {
    expect(clampSeekBlob(50, limits, Number.NaN)).toBe(50);
  });
});

/** 시크 착지를 흉내 내는 가짜 영상 노드 — 시험이 seeked · 프레임 콜백을 따로 낸다 */
function fakeVideo(withFrameCallback = true) {
  const frameCallbacks: (() => void)[] = [];
  const node = Object.assign(new EventTarget(), {
    currentTime: 0,
    requestVideoFrameCallback: withFrameCallback
      ? vi.fn((cb: () => void) => frameCallbacks.push(cb))
      : undefined,
    cancelVideoFrameCallback: vi.fn(),
  });
  return {
    node: node as unknown as HTMLVideoElement,
    seeked: () => node.dispatchEvent(new Event('seeked')),
    frame: () => frameCallbacks.splice(0).forEach((cb) => cb()),
  };
}

describe('seekAndLand — ③ 착지 확인 = seeked + 프레임 콜백 도착', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('seeked 만 오면 아직 착지가 아니다: 프레임 콜백까지 와야 착지다(mediaTime 은 보지 않는다)', async () => {
    const video = fakeVideo();
    let landed: boolean | null = null;
    void seekAndLand(video.node, 5.5, 4_000).then((ok) => {
      landed = ok;
    });

    video.seeked();
    await vi.advanceTimersByTimeAsync(0);
    const afterSeeked = landed;
    video.frame();
    await vi.advanceTimersByTimeAsync(0);

    expect(video.node.currentTime).toBe(5.5);
    expect(afterSeeked).toBeNull();
    expect(landed).toBe(true);
  });

  it('프레임 콜백은 시크 전에 건다: seeked 안에서 걸면 Chrome 은 이미 칠한 프레임이라 콜백이 안 온다', async () => {
    const video = fakeVideo();
    let landed: boolean | null = null;
    void seekAndLand(video.node, 2, 4_000).then((ok) => {
      landed = ok;
    });

    video.frame();
    video.seeked();
    await vi.advanceTimersByTimeAsync(0);

    expect(landed).toBe(true);
  });

  it('seeked 뒤 프레임 콜백이 시간 안에 안 오면 착지 실패다(WebKit 묵은 그림)', async () => {
    const video = fakeVideo();
    let landed: boolean | null = null;
    void seekAndLand(video.node, 2, 4_000).then((ok) => {
      landed = ok;
    });

    video.seeked();
    await vi.advanceTimersByTimeAsync(4_000);

    expect(landed).toBe(false);
  });

  it('프레임 콜백이 없는 브라우저는 seeked 로 착지한다', async () => {
    const video = fakeVideo(false);
    let landed: boolean | null = null;
    void seekAndLand(video.node, 2, 4_000).then((ok) => {
      landed = ok;
    });

    video.seeked();
    await vi.advanceTimersByTimeAsync(0);

    expect(landed).toBe(true);
  });
});
