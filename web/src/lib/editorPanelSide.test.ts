import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 편집기 패널 위치 저장소(POK-614) — 설정 카드와 편집기가 같이 쓴다. 저장 실패 값은 모듈 상태라 시험마다 새로 불러온다

const KEY = 'pc-editor-panel-side';

async function load() {
  vi.resetModules();
  return import('./editorPanelSide');
}

beforeEach(() => window.localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe('editorPanelSide', () => {
  it('저장하면 같은 탭의 구독자가 바로 그 쪽으로 선다', async () => {
    const { storePanelSide, useStoredPanelSide } = await load();
    const { result } = renderHook(() => useStoredPanelSide());
    expect(result.current).toBe('left');

    let saved = false;
    act(() => {
      saved = storePanelSide('right');
    });
    expect(saved).toBe(true);
    expect(result.current).toBe('right');
    expect(window.localStorage.getItem(KEY)).toBe('right');
  });

  it('다른 탭이 저장소를 비우면(key가 null인 storage 이벤트) 기본으로 돌아온다', async () => {
    window.localStorage.setItem(KEY, 'right');
    const { useStoredPanelSide } = await load();
    const { result } = renderHook(() => useStoredPanelSide());
    expect(result.current).toBe('right');

    act(() => {
      window.localStorage.clear();
      window.dispatchEvent(new StorageEvent('storage', { key: null }));
    });
    expect(result.current).toBe('left');
  });

  it('다른 키의 storage 이벤트에는 다시 읽지 않는다', async () => {
    const { useStoredPanelSide } = await load();
    const getItem = vi.spyOn(Storage.prototype, 'getItem');
    renderHook(() => useStoredPanelSide());
    const before = getItem.mock.calls.length;

    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'pc-theme' }));
    });
    expect(getItem.mock.calls.length).toBe(before);
  });

  it('저장소가 막혀 저장에 실패하면 false지만 이번 접속 동안은 그 쪽으로 선다 — 저장이 다시 되면 저장소 값을 따른다', async () => {
    const { storePanelSide, useStoredPanelSide } = await load();
    const { result } = renderHook(() => useStoredPanelSide());
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    let saved = true;
    act(() => {
      saved = storePanelSide('right');
    });
    expect(saved).toBe(false);
    expect(result.current).toBe('right');
    expect(window.localStorage.getItem(KEY)).toBeNull();

    setItem.mockRestore();
    act(() => {
      saved = storePanelSide('left');
    });
    expect(saved).toBe(true);
    expect(result.current).toBe('left');

    // 이후 다른 탭의 값도 다시 따른다(실패 때 고른 값이 남아 가리지 않는다)
    act(() => {
      window.localStorage.setItem(KEY, 'right');
      window.dispatchEvent(new StorageEvent('storage', { key: KEY, newValue: 'right' }));
    });
    expect(result.current).toBe('right');
  });

  it('저장소를 읽지 못하면 기본(왼쪽)이다', async () => {
    const { readStoredPanelSide, useStoredPanelSide } = await load();
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    expect(readStoredPanelSide()).toBeNull();
    const { result } = renderHook(() => useStoredPanelSide());
    expect(result.current).toBe('left');
  });
});
