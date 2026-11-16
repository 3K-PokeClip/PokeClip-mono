import { describe, expect, it, vi } from 'vitest';
import { frameClock, sameMark } from './videoFrameClock';

// 가짜 영상 노드: 프레임 콜백(requestVideoFrameCallback)을 손으로 부르고, 사건(옮김 · 멈춤 · 재생)을 손으로 쏜다
function fakeVideo(withFrameCallback = true) {
  const pending: VideoFrameRequestCallback[] = [];
  const target = Object.assign(new EventTarget(), {
    currentTime: 0,
    playbackRate: 1,
    cancelVideoFrameCallback: vi.fn(),
  });
  if (withFrameCallback) {
    Object.assign(target, {
      requestVideoFrameCallback(callback: VideoFrameRequestCallback) {
        pending.push(callback);
        return pending.length;
      },
    });
  }
  /** 새 프레임 하나: 미디어 시각 mediaTime 장면이 expectedDisplayTime(ms)에 보인다. 콜백은 callbackNow(ms)에 돈다 */
  const present = (
    mediaTime: number,
    expectedDisplayTime: number,
    callbackNow = expectedDisplayTime,
  ) => {
    const callback = pending.shift();
    callback?.(callbackNow, { mediaTime, expectedDisplayTime } as VideoFrameCallbackMetadata);
  };
  return { video: target as unknown as HTMLVideoElement, present, pending };
}

describe('frameClock: 영상이 지금 화면에 내는 자리', () => {
  it('프레임 콜백이 없는 브라우저는 currentTime 으로 잰다', () => {
    const { video } = fakeVideo(false);
    video.currentTime = 4.2;
    expect(frameClock(video, () => 0).position()).toBe(4.2);
  });

  // 실측 M6 r2(2026-11-13): Firefox currentTime 에 소리를 다시 건 뒤 20ms 에 잰 영상이 건 자리보다 64–444ms 앞서 있었다
  // (results/summary.txt Firefox v2 「닻 뒤」 줄)
  it('프레임이 오면 그 장면의 미디어 시각에서 보인 뒤 흐른 시간만큼 민다: 낡은 currentTime 은 안 본다', () => {
    const { video, present } = fakeVideo();
    let now = 1000;
    const clock = frameClock(video, () => now);
    present(5.0, 1000);
    video.currentTime = 4.9; // 거칠게 늦게 갱신된 값
    now = 1150;
    expect(clock.position()).toBeCloseTo(5.15, 6);
  });

  // 리뷰(M6 2차 r1): 마지막 프레임 뒤 흐른 시간 전체에 새 배속을 곱해, 배속을 바꾸는 순간 자리가 뛰고 그대로 굳었다
  it('배속을 바꾸면 그때까지는 옛 배속으로 셈한 자리에서 새 배속으로 민다: 자리가 뛰지 않는다', () => {
    const { video, present } = fakeVideo();
    let now = 1000;
    const clock = frameClock(video, () => now);
    present(5.0, 1000);
    now = 1150;
    // 값이 바뀌고 사건(ratechange)은 뒤에 온다: 그 사이에도 옛 배속으로 잰다
    video.playbackRate = 2;
    expect(clock.position()).toBeCloseTo(5.15, 6);
    video.dispatchEvent(new Event('ratechange'));
    expect(clock.position()).toBeCloseTo(5.15, 6);
    now = 1250;
    expect(clock.position()).toBeCloseTo(5.35, 6);

    // 바꾸기 전에 보인 장면의 콜백이 늦게 돌면 쓰지 않는다: 그 장면 뒤 바꾸기까지는 옛 배속이었다
    present(5.14, 1140, 1260);
    now = 1300;
    expect(clock.position()).toBeCloseTo(5.45, 6);

    // 바꾼 뒤 보인 장면부터는 새 배속으로 민다
    present(5.5, 1320);
    now = 1400;
    expect(clock.position()).toBeCloseTo(5.66, 6);
  });

  it('프레임 콜백이 오래 안 돈 뒤 배속을 바꿔도 지난 시간에 새 배속을 곱하지 않는다', () => {
    const { video, present } = fakeVideo();
    let now = 0;
    const clock = frameClock(video, () => now);
    present(10, 0);
    now = 10_000;
    video.playbackRate = 2;
    video.dispatchEvent(new Event('ratechange'));
    expect(clock.position()).toBeCloseTo(20, 6);
    now = 11_000;
    expect(clock.position()).toBeCloseTo(22, 6);
  });

  it('콜백이 늦게 돌아도 프레임이 보인 시각(expectedDisplayTime)을 기준으로 민다', () => {
    const { video, present } = fakeVideo();
    let now = 1000;
    const clock = frameClock(video, () => now);
    // 1000ms 에 보인 장면의 콜백이 주 스레드가 막혀 1100ms 에야 돈다
    present(5.0, 1000, 1100);
    now = 1150;
    expect(clock.position()).toBeCloseTo(5.15, 6);
  });

  it('옮기기 · 멈춤 · 재생 시작 · 기다림 · 끝 · 다시 받기 뒤에는 새 프레임이 올 때까지 currentTime 으로 잰다', () => {
    for (const name of ['seeking', 'pause', 'play', 'waiting', 'ended', 'emptied']) {
      const { video, present } = fakeVideo();
      let now = 1000;
      const clock = frameClock(video, () => now);
      present(5.0, 1000);
      now = 1100;
      video.currentTime = 9;
      video.dispatchEvent(new Event(name));
      expect(clock.position(), name).toBe(9);

      // 끊긴 뒤 보인 프레임부터 다시 쓴다
      present(9.0, 1120);
      now = 1170;
      expect(clock.position(), name).toBeCloseTo(9.05, 6);
    }
  });

  // 리뷰(M6 2차 r1): 버린 장면도 장면 수를 늘려, 멈춘 영상이 다시 흐른다고 보고 소리를 다시 켰다
  it('끊긴 뒤 늦게 돈 콜백이 끊기기 전에 보인 프레임이면 쓰지 않고 장면 수에도 넣지 않는다', () => {
    const { video, present } = fakeVideo();
    let now = 2000;
    const clock = frameClock(video, () => now);
    video.currentTime = 3;
    clock.restart();
    const before = clock.mark();
    present(1.0, 1990, 2010); // 끊기기 전(1990ms)에 보인 장면
    now = 2050;
    expect(clock.position()).toBe(3);
    expect(sameMark(before, clock.mark())).toBe(true);
  });

  it('표지는 새 장면이나 currentTime 이 움직였을 때만 달라진다: 같은 장면이 다시 와도 서 있는 것이다', () => {
    const { video, present } = fakeVideo();
    const clock = frameClock(video, () => 0);
    const first = clock.mark();
    expect(sameMark(first, clock.mark())).toBe(true);

    present(1.0, 10);
    const framed = clock.mark();
    expect(sameMark(first, framed)).toBe(false);

    present(1.0, 43); // 미디어 시각이 같은 콜백 — 장면이 안 바뀌었다
    expect(sameMark(framed, clock.mark())).toBe(true);

    video.currentTime = 0.5; // 프레임이 안 와도(숨은 탭 · 콜백 없음) currentTime 이 움직이면 흐른다
    expect(sameMark(framed, clock.mark())).toBe(false);
  });

  it('버리면 프레임 콜백과 사건을 더 받지 않는다', () => {
    const { video, present, pending } = fakeVideo();
    let now = 1000;
    const clock = frameClock(video, () => now);
    present(5.0, 1000);
    clock.dispose();
    expect(video.cancelVideoFrameCallback).toHaveBeenCalledWith(pending.length);

    video.currentTime = 7;
    video.dispatchEvent(new Event('seeking'));
    now = 1100;
    expect(clock.position()).toBeCloseTo(5.1, 6);
  });
});
