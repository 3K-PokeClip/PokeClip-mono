import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RecipeOutputV2 } from '@/api/clipEditor';
import { useSceneFrames, type SceneFrameSource } from './useSceneFrames';

// 업로드 창의 장면 그림(POK-381). jsdom에는 영상 옮기기·캔버스가 없어 둘 다 흉내 낸다: 영상은 시각을 넣으면 곧 seeked(그 초가
// FAIL_SECONDS면 error), 캔버스는 그릴 때마다 다른 주소를 준다.

const FAIL_SECONDS = new Set<number>();
/** 참이면 조각을 못 읽는다: 첫 장면(loadeddata) 대신 error */
let loadFails = false;
let drawn = 0;

/** 만든 영상 노드 — 시험이 옮긴 시각 · 붙은 자리를 본다 */
const made: FakeVideo[] = [];
/** 프레임 콜백(requestVideoFrameCallback)을 흉내 낼 때 시험이 부를 콜백들 */
let frameCallbacks: (() => void)[] | null = null;

class FakeVideo extends EventTarget {
  muted = false;
  style: Record<string, string> = {};
  seeks: number[] = [];
  requestVideoFrameCallback = frameCallbacks === null ? undefined : this.onFrame.bind(this);
  cancelVideoFrameCallback = () => undefined;
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
    this.seeks.push(value);
    queueMicrotask(() =>
      this.dispatchEvent(new Event(FAIL_SECONDS.has(value) ? 'error' : 'seeked')),
    );
  }
  set src(_value: string) {
    queueMicrotask(() => this.dispatchEvent(new Event(loadFails ? 'error' : 'loadeddata')));
  }
  removeAttribute() {}
  load() {}
  remove() {}
  private onFrame(cb: () => void) {
    frameCallbacks?.push(cb);
    return frameCallbacks?.length ?? 0;
  }
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
    loadFails = false;
    drawn = 0;
    made.length = 0;
    frameCallbacks = null;
    vi.spyOn(document.body, 'append').mockImplementation(() => undefined);
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
      if (tag === 'video') {
        const video = new FakeVideo();
        made.push(video);
        return video as unknown as HTMLVideoElement;
      }
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

  it('조각을 처음부터 못 읽으면(첫 장면 전 error) 모든 시각이 「못 얻음」이다(PR #301 2차 리뷰)', async () => {
    loadFails = true;
    const { result } = renderHook(() => useSceneFrames(SOURCE, [0], 5_000));
    expect(result.current.failed(5_000)).toBe(false);
    await waitFor(() => expect(result.current.failed(5_000)).toBe(true));
    expect(result.current.failed(0)).toBe(true);
    expect(result.current.frameOf(5_000)).toBeNull();
  });

  it('띠에 올린 자리가 그대로여도 고른 장면을 따로 뽑는다(키로 고를 때, PR #301 2차 리뷰)', async () => {
    const { result, rerender } = renderHook(
      ({ keep }: { keep: number }) => useSceneFrames(SOURCE, [], 2_000, keep),
      { initialProps: { keep: 2_000 } },
    );
    await waitFor(() => expect(result.current.frameOf(2_000)).not.toBeNull());

    rerender({ keep: 3_500 });
    await waitFor(() => expect(result.current.frameOf(3_500)).not.toBeNull());
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

  it('뽑는 영상 노드는 문서에 보이지 않게 붙인다: 프레임 콜백은 그려지는(문서에 붙은) 영상에만 온다', async () => {
    const { result } = renderHook(() => useSceneFrames(SOURCE, [], 2_000));
    await waitFor(() => expect(result.current.frameOf(2_000)).not.toBeNull());

    expect(document.body.append).toHaveBeenCalledWith(made[0]);
    expect(made[0]!.style).toMatchObject({ position: 'fixed', opacity: '0' });
  });

  it('③ 프레임 콜백이 있는 브라우저는 seeked 만으로 그리지 않고 프레임 콜백까지 와야 그린다(WebKit 묵은 그림)', async () => {
    frameCallbacks = [];
    const { result } = renderHook(() => useSceneFrames(SOURCE, [], 2_000));
    await waitFor(() => expect(made[0]?.seeks).toEqual([2]));
    await new Promise((resolve) => setTimeout(resolve, 20));
    const beforeFrame = result.current.frameOf(2_000);

    for (const cb of frameCallbacks.splice(0)) cb();

    expect(beforeFrame).toBeNull();
    await waitFor(() => expect(result.current.frameOf(2_000)).not.toBeNull());
  });

  it('① ② 시크 목표를 창 첫 영상 프레임 뒤 · 길이 − 반 프레임 안으로 묶는다', async () => {
    const limited: SceneFrameSource = {
      ...SOURCE,
      seekLimits: { firstFrame: 0.079, halfFrame: 1 / 60 },
    };
    const { result } = renderHook(() => useSceneFrames(limited, [0, 700_000], null));
    await waitFor(() => expect(result.current.frameOf(700_000)).not.toBeNull());

    expect(made[0]!.seeks[0]).toBeCloseTo(0.08, 9);
    expect(made[0]!.seeks[1]).toBeCloseTo(600 - 1 / 60, 9);
  });

  it('E4b-3 r1 — 같은 조각이어도 배치(시각 변환)가 바뀌면 뽑아 둔 그림을 버리고 새 배치로 다시 뽑는다(머리 도착)', async () => {
    const { result, rerender } = renderHook(
      ({ source }: { source: SceneFrameSource }) => useSceneFrames(source, [2_000], null),
      { initialProps: { source: SOURCE } },
    );
    await waitFor(() => expect(result.current.frameOf(2_000)).not.toBeNull());
    const before = result.current.frameOf(2_000);

    rerender({ source: { ...SOURCE, videoSecondsAt: (offsetMs) => offsetMs / 1000 + 0.3 } });

    await waitFor(() => expect(made[0]!.seeks).toEqual([2, 2.3]));
    await waitFor(() => expect(result.current.frameOf(2_000)).not.toBe(before));
    expect(result.current.frameOf(2_000)).not.toBeNull();
    expect(made).toHaveLength(1);
  });
});
