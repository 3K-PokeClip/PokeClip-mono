import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RecipeOutputV2 } from '@/api/clipEditor';
import { useSceneFrames, type SceneFrameSource } from './useSceneFrames';

// 업로드 창의 장면 그림(POK-381). jsdom에는 영상 옮기기·캔버스가 없어 둘 다 흉내 낸다: 영상은 시각을 넣으면 곧 seeked(그 초가
// FAIL_SECONDS면 error), 캔버스는 그릴 때마다 다른 주소를 준다.

const FAIL_SECONDS = new Set<number>();
let drawn = 0;

class FakeVideo extends EventTarget {
  muted = false;
  playsInline = false;
  preload = '';
  duration = 600;
  videoWidth = 1920;
  videoHeight = 1080;
  private seconds = 0;
  get currentTime() {
    return this.seconds;
  }
  set currentTime(value: number) {
    this.seconds = value;
    queueMicrotask(() =>
      this.dispatchEvent(new Event(FAIL_SECONDS.has(value) ? 'error' : 'seeked')),
    );
  }
  set src(_value: string) {
    queueMicrotask(() => this.dispatchEvent(new Event('loadeddata')));
  }
  removeAttribute() {}
  load() {}
}

const context = new Proxy({}, { get: () => () => undefined, set: () => true });

const SOURCE: SceneFrameSource = {
  src: 'blob:recording',
  videoSecondsAt: (offsetMs) => offsetMs / 1000,
  output: { layers: [] } as unknown as RecipeOutputV2,
};

describe('useSceneFrames', () => {
  beforeEach(() => {
    FAIL_SECONDS.clear();
    drawn = 0;
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
      if (tag === 'video') return new FakeVideo() as unknown as HTMLVideoElement;
      if (tag === 'canvas') {
        return {
          width: 0,
          height: 0,
          getContext: () => context,
          toDataURL: () => `data:image/jpeg;frame-${(drawn += 1)}`,
        } as unknown as HTMLCanvasElement;
      }
      return create(tag);
    }) as typeof document.createElement);
  });
  afterEach(() => vi.restoreAllMocks());

  it('재료가 없으면 모든 시각이 「못 얻음」이다: 창이 「불러오는 중」에 머물지 않는다', () => {
    const { result } = renderHook(() => useSceneFrames(null, [0], 5_000));
    expect(result.current.failed(5_000)).toBe(true);
    expect(result.current.frameOf(5_000)).toBeNull();
  });

  it('옮기기가 실패한 시각은 「못 얻음」으로 남고, 된 시각은 그림이 있다', async () => {
    FAIL_SECONDS.add(5);
    const { result, rerender } = renderHook(
      ({ urgent }: { urgent: number }) => useSceneFrames(SOURCE, [], urgent),
      { initialProps: { urgent: 5_000 } },
    );
    await waitFor(() => expect(result.current.failed(5_000)).toBe(true));
    expect(result.current.frameOf(5_000)).toBeNull();

    rerender({ urgent: 6_000 });
    await waitFor(() => expect(result.current.frameOf(6_000)).not.toBeNull());
    expect(result.current.failed(6_000)).toBe(false);
  });

  it('띠를 훑으며 뽑은 그림은 최근 24장만 남기고, 추천·띠 칸과 고른 장면은 계속 둔다', async () => {
    const { result, rerender } = renderHook(
      ({ urgent }: { urgent: number }) => useSceneFrames(SOURCE, [0], urgent, 1_000),
      { initialProps: { urgent: 1_000 } },
    );
    await waitFor(() => expect(result.current.frameOf(1_000)).not.toBeNull());
    await waitFor(() => expect(result.current.frameOf(0)).not.toBeNull());

    // 30자리를 차례로 올린다(2초 … 31초)
    for (let ms = 2_000; ms <= 31_000; ms += 1_000) {
      rerender({ urgent: ms });
      await waitFor(() => expect(result.current.frameOf(ms)).not.toBeNull());
    }

    expect(result.current.frameOf(0)).not.toBeNull();
    expect(result.current.frameOf(1_000)).not.toBeNull();
    // 앞의 6장(2~7초)은 지웠다: 다시 올리면 다시 뽑는다(「못 얻음」이 아니다)
    expect(result.current.frameOf(7_000)).toBeNull();
    expect(result.current.failed(7_000)).toBe(false);
    expect(result.current.frameOf(8_000)).not.toBeNull();
    expect(result.current.frameOf(31_000)).not.toBeNull();
  });
});
