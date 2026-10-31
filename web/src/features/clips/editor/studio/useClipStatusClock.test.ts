import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { nextClockChange, useClipStatusClock } from './useClipStatusClock';

// 영상 상태 말이 시각 때문에 바뀌는 순간(예약 공개 시각 · 자정)에 상태 확인 답 없이도 다시 그리는가(PR #454 3차 리뷰).
// 시험은 Asia/Seoul로 돈다: 2026-09-20T12:00Z = 한국 21:00, 그날 자정 = 15:00Z.

const NOW = new Date('2026-09-20T12:00:00Z');
const MIDNIGHT = Date.parse('2026-09-20T15:00:00Z');

describe('nextClockChange: 말이 다음에 바뀌는 순간', () => {
  it.each([
    ['아직 안 온 예약 시각(자정 전)', '2026-09-20T13:00:00Z', Date.parse('2026-09-20T13:00:00Z')],
    ['이미 지난 예약 시각 → 자정', '2026-09-20T11:30:00Z', MIDNIGHT],
    ['자정 뒤 예약 시각 → 자정이 먼저', '2026-09-21T01:00:00Z', MIDNIGHT],
    ['예약 없음 → 자정', null, MIDNIGHT],
    ['못 읽는 시각 → 자정', '언제', MIDNIGHT],
  ] as const)('%s', (_, publishAt, expected) => {
    expect(nextClockChange(NOW, publishAt)).toBe(expected);
  });

  it('시각이 여럿이면 아직 안 온 것 가운데 가장 이른 쪽이다', () => {
    const publishAt = '2026-09-20T14:00:00Z';
    const expiresAt = '2026-09-20T13:00:00Z';
    expect(nextClockChange(NOW, publishAt, expiresAt)).toBe(Date.parse(expiresAt));
    expect(nextClockChange(NOW, expiresAt, publishAt)).toBe(Date.parse(expiresAt));
    expect(nextClockChange(NOW, publishAt, '2026-09-20T11:00:00Z')).toBe(Date.parse(publishAt));
    expect(nextClockChange(NOW, null, null)).toBe(MIDNIGHT);
  });
});

describe('useClipStatusClock', () => {
  afterEach(() => vi.useRealTimers());

  it('상태 확인이 멈춘 채로 예약 시각이 지나면 그 순간 「지금」을 새로 준다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const { result } = renderHook(() => useClipStatusClock(true, '2026-09-20T13:00:00Z'));
    expect(result.current.getTime()).toBe(NOW.getTime());

    act(() => vi.advanceTimersByTime(60 * 60 * 1000 - 10));
    expect(result.current.getTime()).toBe(NOW.getTime());
    act(() => vi.advanceTimersByTime(20));
    expect(result.current.getTime()).toBeGreaterThan(Date.parse('2026-09-20T13:00:00Z'));
  });

  it('원본이 만료되는 순간 「지금」을 새로 준다 — 만료된 뒤에도 다시 올릴 수 있다고 하지 않게(PR #462 6차 리뷰)', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const expiresAt = '2026-09-20T13:00:00Z';
    const { result } = renderHook(() => useClipStatusClock(true, null, expiresAt));

    act(() => vi.advanceTimersByTime(60 * 60 * 1000 - 10));
    expect(result.current.getTime()).toBe(NOW.getTime());
    act(() => vi.advanceTimersByTime(20));
    expect(result.current.getTime()).toBeGreaterThan(Date.parse(expiresAt));
  });

  it('쥔 「지금」이 낡았으면(그 사이 예약 시각이 지났으면) 곧바로 새로 준다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const { result, rerender } = renderHook(
      ({ publishAt }: { publishAt: string | null }) => useClipStatusClock(true, publishAt),
      { initialProps: { publishAt: null as string | null } },
    );
    // 「지금」은 21:00에 쥐었는데, 22:30에 22:00 예약 영상이 들어왔다(타이머를 돌리지 않고 시계만 옮긴다)
    vi.setSystemTime(Date.parse('2026-09-20T13:30:00Z'));
    rerender({ publishAt: '2026-09-20T13:00:00Z' });
    act(() => vi.advanceTimersByTime(1));
    expect(result.current.getTime()).toBe(Date.parse('2026-09-20T13:30:00.001Z'));
  });

  it('그릴 영상이 없으면 깨지 않는다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    renderHook(() => useClipStatusClock(false, '2026-09-20T13:00:00Z'));
    expect(vi.getTimerCount()).toBe(0);
  });
});
