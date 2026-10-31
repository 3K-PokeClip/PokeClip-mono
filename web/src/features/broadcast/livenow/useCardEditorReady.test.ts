import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CardEditorPrefetch } from '@/features/clips/editor/prefetchCardEditor';
import { useCardEditorReady } from './useCardEditorReady';

type Prefetch = (s: string, c: string, signal: AbortSignal) => Promise<CardEditorPrefetch>;

let intersect: (isIntersecting: boolean) => void = () => {};

class FakeIntersectionObserver {
  constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
    intersect = (isIntersecting) => callback([{ isIntersecting }]);
  }
  observe() {}
  disconnect() {}
}

function renderReady(prefetch: Prefetch) {
  const view = renderHook(() => useCardEditorReady('7', '23', true, prefetch));
  act(() => view.result.current.ref(document.createElement('article')));
  return view;
}

const show = (isIntersecting: boolean) => act(() => intersect(isIntersecting));
const never = () => vi.fn<Prefetch>(() => new Promise<CardEditorPrefetch>(() => {}));

describe('useCardEditorReady', () => {
  beforeEach(() => {
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('화면에 들어오기 전에는 받지 않는다', () => {
    const prefetch = never();
    renderReady(prefetch);

    expect(prefetch).not.toHaveBeenCalled();
    show(true);
    expect(prefetch).toHaveBeenCalledTimes(1);
  });

  it('받는 중에 화면을 벗어나면 끊는다: 훑고 지나간 카드까지 받으면 맡겨 둘 자리를 넘긴다', () => {
    const prefetch = never();
    renderReady(prefetch);
    show(true);
    const signal = prefetch.mock.calls[0]?.[2];
    expect(signal?.aborted).toBe(false);

    show(false);

    expect(signal?.aborted).toBe(true);
  });

  it('화면을 떠나면 끊는다', () => {
    const prefetch = never();
    const { unmount } = renderReady(prefetch);
    show(true);

    unmount();

    expect(prefetch.mock.calls[0]?.[2].aborted).toBe(true);
  });

  it('끊긴 뒤 다시 들어오면 다시 받고, 그 끊김을 실패로 알리지 않는다', async () => {
    const abort = new DOMException('prefetch abandoned', 'AbortError');
    const prefetch = vi
      .fn<Prefetch>()
      .mockImplementationOnce(
        (_s, _c, signal) =>
          new Promise((_resolve, reject) => {
            signal.addEventListener('abort', () => reject(abort));
          }),
      )
      .mockResolvedValueOnce('ready');
    const { result } = renderReady(prefetch);
    show(true);
    show(false);
    expect(result.current.state).toBe('loading');

    show(true);

    await waitFor(() => expect(result.current.state).toBe('ready'));
  });

  it('다 받은 카드는 화면을 드나들어도 다시 받지 않는다', async () => {
    const prefetch = vi.fn<Prefetch>(() => Promise.resolve('ready'));
    const { result } = renderReady(prefetch);
    show(true);
    await waitFor(() => expect(result.current.state).toBe('ready'));

    show(false);
    show(true);

    expect(prefetch).toHaveBeenCalledTimes(1);
  });

  it('실패한 카드는 저절로 다시 받지 않고 다시 시도로만 받는다', async () => {
    const prefetch = vi
      .fn<Prefetch>()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce('ready');
    const { result } = renderReady(prefetch);
    show(true);
    await waitFor(() => expect(result.current.state).toBe('failed'));

    show(false);
    show(true);
    expect(prefetch).toHaveBeenCalledTimes(1);

    act(() => result.current.retry());

    await waitFor(() => expect(result.current.state).toBe('ready'));
    expect(prefetch).toHaveBeenCalledTimes(2);
  });
});
