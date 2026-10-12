import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { publishLiveData, resetLiveData, useLiveData } from './liveDataStore';

// 라이브 화면 저장소(POK-251). 화면 훅들이 useSyncExternalStore로 구독한다: 저장소가 새 값을 알리면 구독하는 화면이 다시 그려진다

// 시험 앞에서 비운다: 뒤에서 비우면 아직 붙어 있는 훅에 알림이 가 act 경고가 났다(정리는 시험 뒤에 돈다)
beforeEach(() => resetLiveData());

describe('publishLiveData', () => {
  it('바뀐 칸이 없으면 새 값을 만들지 않는다: 라이브 재생기가 실시간에 있는 동안 매초 싣는 같은 null로 화면이 다시 그려지지 않는다(POK-326)', () => {
    publishLiveData({ streamId: 'live-1', liveRewind: null });
    let renders = 0;
    const { result } = renderHook(() => {
      renders += 1;
      return useLiveData();
    });
    const first = result.current;
    const before = renders;

    act(() => {
      publishLiveData({ liveRewind: null });
      publishLiveData({ streamId: 'live-1' });
    });
    expect(result.current).toBe(first);
    expect(renders).toBe(before);

    act(() =>
      publishLiveData({ liveRewind: { atMs: 1_000, edgeAtMs: 11_000, behindSeconds: 10 } }),
    );
    expect(result.current.liveRewind).toEqual({ atMs: 1_000, edgeAtMs: 11_000, behindSeconds: 10 });
    expect(renders).toBe(before + 1);
  });

  it('방송 번호가 바뀌면 같은 값을 실어도 앞 방송의 값을 통째로 버린다', () => {
    publishLiveData({ streamId: 'live-1', startedAt: 5 });
    publishLiveData({ streamId: 'live-2' });
    const { result } = renderHook(() => useLiveData());
    expect(result.current.streamId).toBe('live-2');
    expect(result.current.startedAt).toBeNull();
  });
});
