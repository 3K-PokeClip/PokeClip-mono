import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackBounds } from './editorPlayback';
import { useEditorPlaybackSimulation } from './useEditorPlaybackSimulation';

const DURATION = 600;
const BOUNDS: PlaybackBounds = { startSeconds: 100, endSeconds: 112.4, loop: true };

function renderSimulation(initialSeconds = BOUNDS.startSeconds) {
  return renderHook(() =>
    useEditorPlaybackSimulation({ durationSeconds: DURATION, initialSeconds }),
  );
}

describe('useEditorPlaybackSimulation', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('정지 상태로 시작하고 주어진 위치에 선다', () => {
    const { result } = renderSimulation(120);

    expect(result.current.playing).toBe(false);
    expect(result.current.currentSeconds).toBe(120);
    expect(result.current.durationSeconds).toBe(DURATION);
    expect(result.current.error).toBeNull();
  });

  it('정지 중에는 시간이 흐르지 않는다', () => {
    const { result } = renderSimulation();

    act(() => vi.advanceTimersByTime(1000));

    expect(result.current.currentSeconds).toBe(BOUNDS.startSeconds);
  });

  it('재생하면 100ms마다 0.1초씩 흐른다', () => {
    const { result } = renderSimulation();
    act(() => result.current.setBounds(BOUNDS));
    act(() => result.current.togglePlay());

    act(() => vi.advanceTimersByTime(300));

    expect(result.current.currentSeconds).toBeCloseTo(BOUNDS.startSeconds + 0.3, 5);
  });

  it('배속을 올리면 같은 시간에 그만큼 더 간다', () => {
    const { result } = renderSimulation();
    act(() => result.current.setBounds(BOUNDS));
    act(() => result.current.setRate(2));
    act(() => result.current.togglePlay());

    act(() => vi.advanceTimersByTime(300));

    expect(result.current.currentSeconds).toBeCloseTo(BOUNDS.startSeconds + 0.6, 5);
  });

  it('배속을 바꿔도 진행이 밀리지 않는다 — 인터벌을 다시 걸지 않는다', () => {
    const { result } = renderSimulation();
    act(() => result.current.setBounds(BOUNDS));
    act(() => result.current.togglePlay());

    act(() => vi.advanceTimersByTime(50)); // 틱 중간
    act(() => result.current.setRate(2)); // 여기서 인터벌이 재시작하면 이 50ms가 버려진다
    act(() => vi.advanceTimersByTime(50)); // 첫 틱 완성

    expect(result.current.currentSeconds).toBeCloseTo(BOUNDS.startSeconds + 0.2, 5);
  });

  it('구간 반복이면 끝에서 시작으로 되감는다', () => {
    const { result } = renderSimulation(BOUNDS.endSeconds - 0.15);
    act(() => result.current.setBounds(BOUNDS));
    act(() => result.current.togglePlay());

    act(() => vi.advanceTimersByTime(200));

    expect(result.current.currentSeconds).toBeCloseTo(BOUNDS.startSeconds, 5);
    expect(result.current.playing).toBe(true);
  });

  it('구간 반복이 꺼져 있으면 끝에서 멈춘다', () => {
    const { result } = renderSimulation(BOUNDS.endSeconds - 0.05);
    act(() => result.current.setBounds({ ...BOUNDS, loop: false }));
    act(() => result.current.togglePlay());

    act(() => vi.advanceTimersByTime(100));

    expect(result.current.playing).toBe(false);
    // 지나친 자리가 아니라 구간 끝에 선다
    expect(result.current.currentSeconds).toBe(BOUNDS.endSeconds);
    // 멈춘 뒤로는 더 흐르지 않는다
    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.currentSeconds).toBe(BOUNDS.endSeconds);
  });

  it('구간 앞에서 재생하면 반복이 구간 안으로 데려온다', () => {
    const { result } = renderSimulation(BOUNDS.startSeconds - 20);
    act(() => result.current.setBounds(BOUNDS));
    act(() => result.current.togglePlay());

    act(() => vi.advanceTimersByTime(100));

    expect(result.current.currentSeconds).toBe(BOUNDS.startSeconds);
  });

  it('시킹은 소스 밖으로 나가지 않는다', () => {
    const { result } = renderSimulation();

    act(() => result.current.seekTo(-30));
    expect(result.current.currentSeconds).toBe(0);

    act(() => result.current.seekTo(DURATION + 30));
    expect(result.current.currentSeconds).toBe(DURATION);
  });

  it('seekBy는 지금 위치에 더한다', () => {
    const { result } = renderSimulation(200);

    act(() => result.current.seekBy(-5));
    act(() => result.current.seekBy(1));

    expect(result.current.currentSeconds).toBe(196);
  });
});
