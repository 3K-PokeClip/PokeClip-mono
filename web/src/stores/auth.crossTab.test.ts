import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushBroadcasts, holdBroadcasts } from '@/test/fakeBroadcastChannel';

// 두 "탭"의 상호 반응을 모사한다 (POK-211). 탭 하나 = auth 모듈 인스턴스 하나 — 같은 jsdom
// window·localStorage·가짜 BroadcastChannel을 공유한다. 정적 import는 숨은 세 번째 인스턴스가
// 되므로 타입만 가져온다. 기존 auth.test.ts의 storage 단발 테스트는 이 루프를 잡지 못한다.
type AuthModule = typeof import('@/stores/auth');

const STORAGE_KEY = 'pc-auth';

interface Tab {
  store: AuthModule['useAuthStore'];
  onSessionChange: (listener: () => void) => void;
  close: () => void;
}

const openTabs: Tab[] = [];

async function openTab(): Promise<Tab> {
  vi.resetModules();
  const mod: AuthModule = await import('@/stores/auth');
  const offs: Array<() => void> = [];
  const tab: Tab = {
    store: mod.useAuthStore,
    onSessionChange: (listener) => {
      offs.push(mod.onCrossTabSessionChange(listener));
    },
    // 모듈 스코프의 window 리스너는 떼어낼 수 없어 고아 인스턴스가 남는다. 수신·폴백 경로는
    // localStorage를 쓰지도 메시지를 보내지도 않으므로 무해하지만, 테스트가 건 구독자(회전을
    // 일으킨다)는 반드시 푼다 — 하나라도 남으면 다음 테스트가 핑퐁한다.
    close: () => offs.splice(0).forEach((off) => off()),
  };
  mod.useAuthStore.getState().hydrate(); // 화면의 useAuthHydration — 채널·storage 리스너 바인딩
  openTabs.push(tab);
  return tab;
}

/**
 * 같은 window라 다른 탭의 쓰기에 storage 이벤트가 저절로 뜨지 않는다 — 손으로 쏜다.
 * 쓴 탭도 받지만 stored === 자기 토큰이라 no-op이다.
 */
function fireStorage() {
  window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));
}

/** 두 탭이 같은 세션(refresh)을 들고 있는 상태 — access는 탭마다 다를 수 있다. */
function seedSession(refreshToken: string, tabs: Array<[Tab, string]>) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken }));
  for (const [tab, accessToken] of tabs) {
    tab.store.setState({ accessToken, refreshToken, hydrated: true });
  }
}

/**
 * Providers + useMe 흉내: 세션 변경 알림 = 캐시 비움 → me 재요청. access가 없으면 헤더 없는
 * 요청이라 401 → refresh 회전 → 저장. access가 있으면 me 200으로 끝난다.
 */
function mountMe(tab: Tab, name: string, rotations: string[]) {
  tab.onSessionChange(() => {
    const s = tab.store.getState();
    if (s.refreshToken === null || s.accessToken !== null) return;
    rotations.push(name);
    if (rotations.length > 4) throw new Error('탭 간 회전 핑퐁');
    const n = rotations.length;
    tab.store
      .getState()
      .rotateTokens({ accessToken: `${name}-access-${n}`, refreshToken: `${name}-refresh-${n}` });
    fireStorage();
  });
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  openTabs.splice(0).forEach((tab) => tab.close());
  if (vi.isFakeTimers()) {
    vi.clearAllTimers();
    vi.useRealTimers();
  }
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('다중 탭 세션 동기화 (POK-211)', () => {
  it('한 탭의 회전을 다른 탭이 조용히 이어받는다 — 캐시 비움·재요청·재회전이 없다', async () => {
    const a = await openTab();
    const b = await openTab();
    vi.useFakeTimers(); // 동적 import가 끝난 뒤에 켠다
    seedSession('refresh-0', [
      [a, 'a-access-0'],
      [b, 'b-access-0'],
    ]);
    const rotations: string[] = [];
    mountMe(a, 'a', rotations);
    mountMe(b, 'b', rotations);

    // A: access 만료 → me 401 → 회전 성공
    a.store.getState().rotateTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });
    fireStorage();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(b.store.getState()).toMatchObject({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
    });
    expect(a.store.getState()).toMatchObject({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
    });
    expect(rotations).toEqual([]);
  });

  it('storage 이벤트가 채널 메시지보다 먼저 와도 폴백이 회전을 덮어쓰지 않는다', async () => {
    const a = await openTab();
    const b = await openTab();
    vi.useFakeTimers();
    seedSession('refresh-0', [
      [a, 'a-access-0'],
      [b, 'b-access-0'],
    ]);
    const listener = vi.fn();
    b.onSessionChange(listener);

    holdBroadcasts();
    a.store.getState().rotateTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });
    fireStorage(); // 폴백 재확인 타이머 시작
    await vi.advanceTimersByTimeAsync(50);
    expect(b.store.getState().refreshToken).toBe('refresh-0'); // 아직 아무것도 바꾸지 않았다
    flushBroadcasts(); // 채널 도착
    await vi.advanceTimersByTimeAsync(100); // 재확인: 정본과 같다 → no-op

    expect(b.store.getState()).toMatchObject({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
    });
    expect(listener).not.toHaveBeenCalled();
  });

  it('한 탭의 로그아웃은 다른 탭의 토큰을 모두 비우고 캐시를 지우게 한다', async () => {
    const a = await openTab();
    const b = await openTab();
    vi.useFakeTimers();
    seedSession('refresh-0', [
      [a, 'a-access-0'],
      [b, 'b-access-0'],
    ]);
    const listener = vi.fn();
    b.onSessionChange(listener);

    a.store.getState().clearTokens();
    fireStorage();
    await vi.advanceTimersByTimeAsync(200);

    expect(b.store.getState()).toMatchObject({
      accessToken: null,
      refreshToken: null,
      hydrated: true,
    });
    expect(listener).toHaveBeenCalledTimes(1); // 채널로 한 번 — 폴백은 정본과 같아 no-op
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('로그아웃을 놓친 탭이 다른 계정의 로그인을 받으면 토큰을 통째로 바꾸고 캐시를 지운다 (리뷰 #72)', async () => {
    const a = await openTab();
    const b = await openTab();
    vi.useFakeTimers();
    // B는 프리즈로 로그아웃을 놓쳐 이전 계정을 들고 있다
    b.store.setState({ accessToken: 'old-access', refreshToken: 'old-refresh', hydrated: true });
    const listener = vi.fn();
    b.onSessionChange(listener);

    a.store.getState().setTokens({ accessToken: 'new-access', refreshToken: 'new-refresh' });
    fireStorage();
    await vi.advanceTimersByTimeAsync(200);

    expect(b.store.getState()).toMatchObject({
      accessToken: 'new-access',
      refreshToken: 'new-refresh',
    });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('내 사슬이 아닌 회전은 무시하고, 폴백이 정본(localStorage)으로 맞춘다', async () => {
    const b = await openTab();
    vi.useFakeTimers();
    seedSession('refresh-0', [[b, 'b-access-0']]);
    const listener = vi.fn();
    b.onSessionChange(listener);

    // 소모된 토큰일 수 있는 낯선 사슬의 회전 — 받으면 재사용 감지에 걸린다
    new BroadcastChannel('pc-auth').postMessage({
      v: 1,
      type: 'rotate',
      prev: 'someone-else',
      pair: { accessToken: 'stale-access', refreshToken: 'stale-refresh' },
    });
    expect(b.store.getState()).toMatchObject({
      accessToken: 'b-access-0',
      refreshToken: 'refresh-0',
    });
    expect(listener).not.toHaveBeenCalled();

    // 정본이 바뀌어 있으면(다른 탭이 그 사슬로 저장) 회전인지 교체인지 알 수 없으니 이전 계약대로
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, refreshToken: 'refresh-9' }));
    fireStorage();
    await vi.advanceTimersByTimeAsync(100);

    expect(b.store.getState()).toMatchObject({ accessToken: null, refreshToken: 'refresh-9' });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('수신 탭은 localStorage를 쓰지 않고, access는 어느 경로로도 localStorage에 남지 않는다', async () => {
    const a = await openTab();
    const b = await openTab();
    seedSession('refresh-0', [
      [a, 'a-access-0'],
      [b, 'b-access-0'],
    ]);
    const setItem = vi.spyOn(Storage.prototype, 'setItem');

    a.store.getState().rotateTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });

    expect(b.store.getState().accessToken).toBe('access-1');
    expect(setItem).toHaveBeenCalledTimes(1); // 보낸 탭의 persist 한 번뿐 — 전파가 전파를 낳지 않는다
    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).toContain('refresh-1');
    expect(raw).not.toContain('access-1');
  });

  it('BroadcastChannel이 없으면 storage 폴백만 동작한다 — 이전 계약(access 비움 + 캐시 비움)', async () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const a = await openTab();
    const b = await openTab();
    vi.useFakeTimers();
    seedSession('refresh-0', [
      [a, 'a-access-0'],
      [b, 'b-access-0'],
    ]);
    const listener = vi.fn();
    b.onSessionChange(listener);

    a.store.getState().rotateTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });
    expect(b.store.getState().refreshToken).toBe('refresh-0'); // 채널이 없으니 즉시 전파는 없다
    fireStorage();
    await vi.advanceTimersByTimeAsync(100);

    expect(b.store.getState()).toMatchObject({ accessToken: null, refreshToken: 'refresh-1' });
    expect(listener).toHaveBeenCalledTimes(1);
  });
});
