import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/stores/auth';

const STORAGE_KEY = 'pc-auth';

beforeEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: false });
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
