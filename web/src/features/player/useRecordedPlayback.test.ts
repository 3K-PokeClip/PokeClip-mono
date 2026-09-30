import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { recordingTimeline } from './recordingTimeline';
import { useRecordedPlayback, type RecordedSource } from './useRecordedPlayback';

// 끝난 방송 다시보기(POK-253). 재생 서버는 틈을 건너 주지 않으므로(실측) 한 구간씩 달라 하고 이어 튼다.

const T0 = Date.parse('2026-09-30T01:50:00Z');

function source(spans: [offsetSeconds: number, durationSeconds: number][]): RecordedSource {
  const timeline = recordingTimeline(
    spans.map(([offset, duration]) => ({ startMs: T0 + offset * 1000, durationSeconds: duration })),
    null,
    null,
  )!;
  return { streamId: 'gaptest', ...timeline };
}

function fakeVideo() {
  const video = document.createElement('video');
  const play = vi.fn(() => Promise.resolve());
  Object.defineProperty(video, 'play', { value: play });
  return { video, play };
}

/** 요청한 줄기의 시작(첫 구간 기준 초)·길이 */
function requested(video: HTMLVideoElement) {
  const url = new URL(video.src, 'http://localhost');
  return {
    fromSeconds: (Date.parse(url.searchParams.get('start')!) - T0) / 1000,
    duration: Number(url.searchParams.get('duration')),
  };
}

describe('useRecordedPlayback — 구간 여럿', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('틈을 건너 달라 하지 않는다 — 첫 줄기는 첫 구간 끝까지다', () => {
    const { video } = fakeVideo();
    renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    expect(requested(video)).toEqual({ fromSeconds: 0, duration: 14 });
  });

  it('한 구간이 끝나면 다음 구간을 이어 튼다 — 전에는 첫 구간 뒤를 못 봤다', () => {
    const { video, play } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    play.mockClear();

    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });

    expect(requested(video)).toEqual({ fromSeconds: 24, duration: 14 });
    expect(play).toHaveBeenCalled();
    expect(result.current.behindSeconds).toBe(14);
  });

  it('마지막 구간이 끝나면 더 받지 않는다', () => {
    const { video, play } = fakeVideo();
    renderHook(() => useRecordedPlayback({ current: video }, source([[0, 14]])));
    const before = video.src;
    play.mockClear();

    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });

    expect(video.src).toBe(before);
    expect(play).not.toHaveBeenCalled();
  });

  it('틈 안을 누르면 다음 구간 시작으로 간다 — 틈에서 시작하면 재생 서버가 404다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );

    // 전체 38초 중 18초 자리 = 끝에서 20초 앞
    act(() => result.current.seekToFraction(18 / 38));
    act(() => vi.advanceTimersByTime(300));

    expect(requested(video)).toEqual({ fromSeconds: 24, duration: 14 });
  });

  it('멈춰 둔 채 끝까지 본 게 아니면 이어 틀 때 사용자가 멈춘 상태를 지킨다', () => {
    const { video, play } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    act(() => video.dispatchEvent(new Event('pause')));
    play.mockClear();

    act(() => result.current.seekToFraction(30 / 38));
    act(() => vi.advanceTimersByTime(300));

    expect(requested(video).fromSeconds).toBe(30);
    expect(play).not.toHaveBeenCalled();
  });
});

describe('useRecordedPlayback — 틈 앞뒤로 오가기(PR #205 codex)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function endPiece(video: HTMLVideoElement) {
    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });
  }

  it('다음 구간 시작에서 뒤로 가면 앞 구간으로 간다 — 전에는 제자리였다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    endPiece(video);
    expect(requested(video).fromSeconds).toBe(24);
    Object.defineProperty(video, 'ended', { value: false, configurable: true });

    act(() => result.current.seekBy(5));
    act(() => vi.advanceTimersByTime(300));

    expect(requested(video)).toEqual({ fromSeconds: 13, duration: 1 });
  });

  it('다 본 뒤 재생을 누르면 처음부터 다시 튼다 — 전에는 마지막 구간만 다시 나왔다', () => {
    const { video, play } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    endPiece(video);
    endPiece(video);
    play.mockClear();

    act(() => result.current.togglePlay());

    expect(requested(video)).toEqual({ fromSeconds: 0, duration: 14 });
    expect(play).toHaveBeenCalled();
  });
});

describe('useRecordedPlayback — 1시간 넘는 녹화', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('시크바 왼쪽 끝이 녹화 처음이다 — 전에는 마지막 1시간만 이동했다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback({ current: video }, source([[0, 9000]])),
    );
    expect(result.current.windowSeconds).toBe(9000);

    act(() => result.current.seekToFraction(0.5));
    act(() => vi.advanceTimersByTime(300));
    expect(requested(video).fromSeconds).toBe(4500);

    act(() => result.current.seekToFraction(0));
    act(() => vi.advanceTimersByTime(300));
    expect(requested(video).fromSeconds).toBe(0);
  });
});
