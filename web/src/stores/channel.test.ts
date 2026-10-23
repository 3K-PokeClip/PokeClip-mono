import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useChannelStore } from './channel';

const STORAGE_KEY = 'pc-channel';

function resetStore() {
  useChannelStore.setState({ userId: null, selected: null });
}

describe('useChannelStore', () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetStore();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('회원 7이 3을 고르면 회원 번호를 열쇠로 저장되고, 다시 hydrate해도 3이다', () => {
    useChannelStore.getState().select(7, 3);

    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({ '7': 3 });

    resetStore();
    useChannelStore.getState().hydrate(7);
    expect(useChannelStore.getState()).toMatchObject({ userId: 7, selected: 3 });
  });

  it('회원 8로 hydrate하면 7의 선택을 안 쓴다', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));

    useChannelStore.getState().hydrate(8);

    expect(useChannelStore.getState()).toMatchObject({ userId: 8, selected: null });
  });

  it('같은 탭에서 회원이 바뀌면 hydrate가 다시 읽는다', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3, '8': 5 }));

    useChannelStore.getState().hydrate(7);
    useChannelStore.getState().hydrate(8);

    expect(useChannelStore.getState()).toMatchObject({ userId: 8, selected: 5 });
  });

  it('내 채널(null)을 고르면 그 회원의 줄만 지우고 다른 회원 줄은 남긴다', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3, '8': 5 }));
    useChannelStore.getState().hydrate(7);

    useChannelStore.getState().select(7, null);

    expect(useChannelStore.getState().selected).toBeNull();
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({ '8': 5 });
  });

  it('다른 탭의 storage 이벤트가 오면 지금 회원의 선택이 바뀐다', () => {
    useChannelStore.getState().hydrate(7);

    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 4 }));
    window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));

    expect(useChannelStore.getState().selected).toBe(4);
  });

  it('다른 열쇠의 storage 이벤트는 무시한다', () => {
    useChannelStore.getState().hydrate(7);
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 4 }));

    window.dispatchEvent(new StorageEvent('storage', { key: 'pc-onboarding' }));

    expect(useChannelStore.getState().selected).toBeNull();
  });

  it('저장값이 숫자가 아니거나 JSON이 깨졌으면 고른 것 없음이다', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': '3' }));
    useChannelStore.getState().hydrate(7);
    expect(useChannelStore.getState().selected).toBeNull();

    resetStore();
    window.localStorage.setItem(STORAGE_KEY, '{깨짐');
    useChannelStore.getState().hydrate(7);
    expect(useChannelStore.getState().selected).toBeNull();
  });

  it('localStorage가 던져도 죽지 않고 메모리로만 기억한다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    useChannelStore.getState().hydrate(7);
    useChannelStore.getState().select(7, 3);

    expect(useChannelStore.getState()).toMatchObject({ userId: 7, selected: 3 });
  });
});
