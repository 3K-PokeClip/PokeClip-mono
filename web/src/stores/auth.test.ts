import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { onCrossTabSessionChange, useAuthStore } from '@/stores/auth';

const STORAGE_KEY = 'pc-auth';

beforeEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({
    accessToken: null,
    refreshToken: null,
    hydrated: false,
    rotatedFrom: null,
  });
});

describe('useAuthStore', () => {
  it('setTokens는 refresh만 저장한다 — access가 localStorage에 남으면 안 된다', () => {
    useAuthStore.getState().setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });

    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).toContain('refresh-1');
    expect(raw).not.toContain('access-1');
    expect(useAuthStore.getState().accessToken).toBe('access-1');
    // 콜백처럼 hydrate 없이 진입한 경로도 이 시점부터 판단 가능해야 한다
    expect(useAuthStore.getState().hydrated).toBe(true);
  });

  it('rotateTokens도 refresh만 저장하고, 소모된 이전 refresh를 rotatedFrom에 남긴다', () => {
    useAuthStore.getState().setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });

    useAuthStore.getState().rotateTokens({ accessToken: 'access-2', refreshToken: 'refresh-2' });

    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).toContain('refresh-2');
    expect(raw).not.toContain('access-2');
    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'access-2',
      refreshToken: 'refresh-2',
      rotatedFrom: 'refresh-1',
    });
  });

  it('hydrate는 저장된 refresh를 복원하고, 이중 호출에도 한 번만 읽는다', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken: 'stored-1' }));

    useAuthStore.getState().hydrate();
    // 두 번째 호출 사이에 스토어가 갱신됐다면 (StrictMode 이중 이펙트) 덮어쓰면 안 된다
    useAuthStore.setState({ refreshToken: 'rotated-1' });
    useAuthStore.getState().hydrate();

    expect(useAuthStore.getState().refreshToken).toBe('rotated-1');
    expect(useAuthStore.getState().hydrated).toBe(true);
  });

  it('손상 JSON은 비로그인으로 진행한다', () => {
    window.localStorage.setItem(STORAGE_KEY, '{broken');

    useAuthStore.getState().hydrate();

    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(useAuthStore.getState().hydrated).toBe(true);
  });

  it('clearTokens는 메모리와 저장소를 함께 비운다', () => {
    useAuthStore.getState().setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });

    useAuthStore.getState().clearTokens();

    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});

describe('탭 복귀 시 정본 동기화', () => {
  it('탭이 다시 보이면 지연 없이 정본과 맞춘다 — TanStack의 포커스 재요청보다 먼저', () => {
    useAuthStore.getState().hydrate();
    useAuthStore.setState({ accessToken: 'access-a', refreshToken: 'refresh-a', hydrated: true });
    const listener = vi.fn();
    const off = onCrossTabSessionChange(listener);
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken: 'refresh-b' }));

    document.dispatchEvent(new Event('visibilitychange'));

    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, refreshToken: 'refresh-b' });
    expect(listener).toHaveBeenCalledTimes(1);
    off();
  });

  it('정본을 읽을 수 없으면(접근 차단) 메모리 세션을 건드리지 않는다', () => {
    useAuthStore.getState().hydrate();
    useAuthStore.setState({ accessToken: 'access-a', refreshToken: 'refresh-a', hydrated: true });
    const listener = vi.fn();
    const off = onCrossTabSessionChange(listener);
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('차단', 'SecurityError');
    });

    document.dispatchEvent(new Event('visibilitychange'));

    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'access-a',
      refreshToken: 'refresh-a',
    });
    expect(listener).not.toHaveBeenCalled();
    getItem.mockRestore();
    off();
  });

  it('저장이 실패한 탭은 정본 동기화에서 빠진다 — 새로고침 시 재로그인까지만 감수한다는 계약', () => {
    useAuthStore.getState().hydrate();
    const listener = vi.fn();
    const off = onCrossTabSessionChange(listener);
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('용량 초과', 'QuotaExceededError');
    });
    useAuthStore.getState().setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' }); // 메모리만 남는다

    document.dispatchEvent(new Event('visibilitychange'));
    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
    });
    expect(listener).not.toHaveBeenCalled();

    // 저장이 다시 되면 정본 동기화도 돌아온다
    setItem.mockRestore();
    useAuthStore.getState().rotateTokens({ accessToken: 'access-2', refreshToken: 'refresh-2' });
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken: 'refresh-3' }));
    document.dispatchEvent(new Event('visibilitychange'));
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, refreshToken: 'refresh-3' });
    expect(listener).toHaveBeenCalledTimes(1);
    off();
  });
});

// 채널 메시지 없이 storage 이벤트만 온 경우의 폴백 — 두 탭의 상호 반응은 auth.crossTab.test.ts
describe('다른 탭 동기화 — storage 폴백', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('다른 값으로 교체되면 잠깐 뒤 access도 비우고 구독자에게 알린다 — 회전과 계정 교체를 구분할 수 없다', () => {
    useAuthStore.getState().hydrate(); // storage 리스너 바인딩
    useAuthStore.setState({ accessToken: 'access-a', refreshToken: 'refresh-a', hydrated: true });
    const listener = vi.fn();
    const off = onCrossTabSessionChange(listener);

    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken: 'refresh-b' }));
    window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));
    window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY })); // 연속 이벤트는 한 번으로

    // 채널 메시지가 먼저 도착할 시간을 준다 — 그 전에는 건드리지 않는다
    expect(useAuthStore.getState().refreshToken).toBe('refresh-a');
    vi.advanceTimersByTime(100);

    expect(useAuthStore.getState().refreshToken).toBe('refresh-b');
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(listener).toHaveBeenCalledTimes(1);
    off();
  });

  it('다른 탭의 로그아웃(null)은 양쪽 토큰을 비운다', () => {
    useAuthStore.getState().hydrate();
    useAuthStore.setState({ accessToken: 'access-a', refreshToken: 'refresh-a', hydrated: true });

    window.localStorage.removeItem(STORAGE_KEY);
    window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));
    vi.advanceTimersByTime(100);

    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});
