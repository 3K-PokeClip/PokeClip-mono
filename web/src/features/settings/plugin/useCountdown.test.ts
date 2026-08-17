import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatRemaining, useCountdown } from '@/features/settings/plugin/useCountdown';

const BASE = new Date('2026-08-17T12:00:00Z');
const EXPIRES_AT = new Date(BASE.getTime() + 10 * 60 * 1000).toISOString();

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(BASE);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('formatRemaining', () => {
  it('mm:ss로 표기하고 음수는 00:00으로 죈다', () => {
    expect(formatRemaining(10 * 60 * 1000)).toBe('10:00');
    expect(formatRemaining(599_000)).toBe('09:59');
    expect(formatRemaining(0)).toBe('00:00');
    expect(formatRemaining(-5_000)).toBe('00:00');
  });
});

describe('useCountdown', () => {
  it('매 틱 expiresAt - now를 재계산한다 — 틱을 건너뛰어도(탭 스로틀) 실제 시각을 따른다', () => {
    const { result } = renderHook(() => useCountdown(EXPIRES_AT));

    expect(result.current.label).toBe('10:00');

    act(() => vi.advanceTimersByTime(1_000));
    expect(result.current.label).toBe('09:59');

    // 5분을 한 번에 건너뛰어도 누적 감산이 아니라 재계산이라 정확하다
    act(() => vi.advanceTimersByTime(5 * 60 * 1000));
    expect(result.current.label).toBe('04:59');
    expect(result.current.expired).toBe(false);
  });

  it('만료되면 expired가 서고 00:00에 고정된다', () => {
    const { result } = renderHook(() => useCountdown(EXPIRES_AT));

    act(() => vi.advanceTimersByTime(10 * 60 * 1000 + 1_000));

    expect(result.current.expired).toBe(true);
    expect(result.current.label).toBe('00:00');
  });

  it('언마운트·만료 후에는 타이머가 남지 않는다', () => {
    const { unmount } = renderHook(() => useCountdown(EXPIRES_AT));
    unmount();
    expect(vi.getTimerCount()).toBe(0);

    const { result } = renderHook(() => useCountdown(EXPIRES_AT));
    act(() => vi.advanceTimersByTime(11 * 60 * 1000));
    expect(result.current.expired).toBe(true);
    expect(vi.getTimerCount()).toBe(0); // 만료 후 빈 인터벌이 계속 돌면 안 된다
  });

  it('expiresAt이 없으면 시간을 세지 않는다', () => {
    const { result } = renderHook(() => useCountdown(null));
    expect(result.current.expired).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('expiresAt이 늦게 도착해도 첫 렌더부터 만료로 보이지 않는다 (리뷰 #74)', () => {
    // 모달은 항상 마운트돼 있고(issued=null) 코드가 나중에 도착한다 — 그 첫 렌더에
    // 이전 상태(ms=0)가 그려지면 "만료됐어요"가 잠깐 표시·낭독된다
    const { result, rerender } = renderHook(({ at }: { at: string | null }) => useCountdown(at), {
      initialProps: { at: null as string | null },
    });

    rerender({ at: EXPIRES_AT });

    expect(result.current.expired).toBe(false);
    expect(result.current.label).toBe('10:00');
  });
});
