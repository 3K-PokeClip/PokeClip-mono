import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { createPlaybackClock, usePlaybackValue } from './playbackClock';

describe('createPlaybackClock', () => {
  it('값이 바뀔 때만 알린다: 멈춘 영상의 같은 위치로 구독자를 깨우지 않는다', () => {
    const clock = createPlaybackClock(10);
    const listener = vi.fn();
    clock.subscribe(listener);

    clock.publish(10);
    expect(listener).not.toHaveBeenCalled();

    clock.publish(11);
    expect(listener).toHaveBeenCalledOnce();
    expect(clock.getSeconds()).toBe(11);
  });

  it('구독을 끊으면 더 알리지 않는다', () => {
    const clock = createPlaybackClock(0);
    const listener = vi.fn();
    const unsubscribe = clock.subscribe(listener);

    unsubscribe();
    clock.publish(1);

    expect(listener).not.toHaveBeenCalled();
  });
});

describe('usePlaybackValue', () => {
  it('고른 값이 그대로면 다시 그리지 않는다', () => {
    const clock = createPlaybackClock(0);
    let renders = 0;
    const { result } = renderHook(() => {
      renders += 1;
      return usePlaybackValue(clock, (seconds) => Math.floor(seconds));
    });
    const before = renders;

    act(() => {
      clock.publish(0.2);
      clock.publish(0.9);
    });
    expect(renders).toBe(before);

    act(() => clock.publish(1.1));
    expect(renders).toBe(before + 1);
    expect(result.current).toBe(1);
  });

  it('내려가면 구독을 끊는다', () => {
    const clock = createPlaybackClock(0);
    const select = vi.fn((seconds: number) => seconds);
    const { unmount } = renderHook(() => usePlaybackValue(clock, select));

    unmount();
    select.mockClear();
    clock.publish(1);

    expect(select).not.toHaveBeenCalled();
  });
});
