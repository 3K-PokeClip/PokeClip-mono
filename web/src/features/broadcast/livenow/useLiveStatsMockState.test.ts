import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { publishLiveData, resetLiveData } from './liveDataStore';
import { useLiveStatsMockState } from './useLiveStatsMockState';

afterEach(() => {
  act(() => resetLiveData());
});

describe('useLiveStatsMockState', () => {
  it('재생 위치만 바뀌면 같은 값을 돌려준다 — 지난 방송을 트는 동안 그래프가 매초 다시 그려지지 않는다', () => {
    // 저장소 전체에 묶으면 재생 위치(playheadMs)가 매초 바뀔 때마다 새 값이라 그래프(memo)가 매초 다시 그려졌다(PR #316 리뷰 2라운드)
    const { result } = renderHook(() => useLiveStatsMockState('ended'));
    const before = result.current;
    act(() => publishLiveData({ playheadMs: 1_000 }));
    act(() => publishLiveData({ playheadMs: 2_000 }));
    expect(result.current).toBe(before);
  });

  it('볼 수 없게 된 방송(명부에 없다)은 그래프를 불러온다고 하지 않는다', () => {
    const { result } = renderHook(() => useLiveStatsMockState('ended'));
    // 받기 전에는 불러오는 중이다: 그래프 자리에 스켈레톤이 선다(POK-445)
    expect(result.current.chartLoading).toBe(true);
    act(() => publishLiveData({ status: 'offline' }));
    expect(result.current.chartLoading).toBe(false);
    expect(result.current.noChart).toBe('볼 수 없는 방송이에요');
  });

  it('그래프를 받으면 불러오는 중이 아니다', () => {
    const { result } = renderHook(() => useLiveStatsMockState('live'));
    act(() =>
      publishLiveData({
        chart: {
          bucketSeconds: 30,
          appliedOffsetMs: 0,
          scope: 'recent',
          buckets: [{ start: '2026-09-26T12:00:00Z', chats: 3, donations: 0 }],
        },
      }),
    );
    expect(result.current.chart).not.toBeNull();
    expect(result.current.chartLoading).toBe(false);
  });
});
