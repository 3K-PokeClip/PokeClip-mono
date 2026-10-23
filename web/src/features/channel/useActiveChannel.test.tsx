import { act, renderHook, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient, makeQueryWrapper } from '@/test/testProviders';
import { COVER_IN_MS, useSwitchCurtain } from './switchCurtain';
import { useActiveChannel, useSwitchChannel } from './useActiveChannel';

const ME_URL = '/api/auth/me';
const AS_EDITOR_URL = '/api/editor-delegations/as-editor';
const STORAGE_KEY = 'pc-channel';

const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
/** 서버 모양 그대로: counterpartId가 스트리머 번호다(api/editors.ts가 옮긴다). */
const BYEOLHA = {
  id: 21,
  counterpartId: 3,
  counterpartName: '별하',
  grantedAt: '2026-10-01T00:00:00Z',
};

type AsEditor = () => Response | Promise<Response>;

function stub(asEditor: AsEditor = () => jsonResponse(200, [BYEOLHA])) {
  return stubFetch((url) => {
    if (url === ME_URL) return jsonResponse(200, ME);
    if (url === AS_EDITOR_URL) return asEditor();
    throw new Error(`unexpected fetch: ${url}`);
  });
}

function renderActive() {
  return renderHook(() => useActiveChannel(), {
    wrapper: makeQueryWrapper(createTestQueryClient()),
  });
}

/** 저장값을 미리 심는다: 새로고침 뒤 첫 진입과 같은 모양(store는 아직 아무 회원으로도 안 읽음). */
function remembered(choices: Record<string, number>) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(choices));
}

beforeEach(() => {
  window.localStorage.clear();
  useChannelStore.setState({ userId: null, selected: null });
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('useActiveChannel: 지금 채널 해석', () => {
  it('고른 것이 없으면 내 채널이다', async () => {
    stub();
    const { result } = renderActive();

    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current).toEqual({
      ready: true,
      streamerId: 7,
      relation: 'OWNER',
      name: '나',
      gone: false,
    });
  });

  it('저장값 3이 as-editor에 있으면 그 채널이고 편집자다: 훅이 스스로 저장값을 읽는다', async () => {
    remembered({ '7': 3 });
    stub();
    const { result } = renderActive();

    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current).toEqual({
      ready: true,
      streamerId: 3,
      relation: 'EDITOR',
      name: '별하',
      gone: false,
    });
  });

  it('저장값 3이 as-editor에 없으면 내 채널로 풀고 「끊겼다」를 표시한다: 저장값 지우기·토스트는 여기서 안 한다', async () => {
    remembered({ '7': 3 });
    stub(() => jsonResponse(200, []));
    const { result } = renderActive();

    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current).toEqual({
      ready: true,
      streamerId: 7,
      relation: 'OWNER',
      name: '나',
      gone: true,
    });
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({ '7': 3 });
    expect(screen.queryByText(/내 채널로 돌아왔어요/)).not.toBeInTheDocument();
  });

  it('저장값이 있고 as-editor를 읽는 중이면 아직 준비 안 됨이다: 화면이 남의 번호·내 번호 어느 쪽으로도 먼저 부르지 않게', async () => {
    remembered({ '7': 3 });
    const spy = stub(() => new Promise<Response>(() => {}));
    const { result } = renderActive();

    await waitFor(() => expect(spy.mock.calls.some(([url]) => url === AS_EDITOR_URL)).toBe(true));
    expect(result.current).toEqual({
      ready: false,
      streamerId: null,
      relation: null,
      name: null,
      gone: false,
    });
  });

  it('as-editor를 못 읽으면 내 채널로 풀되 끊김이 아니다: 저장값을 남긴다', async () => {
    remembered({ '7': 3 });
    stub(() => jsonResponse(500, { error: 'boom' }));
    const { result } = renderActive();

    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current).toEqual({
      ready: true,
      streamerId: 7,
      relation: 'OWNER',
      name: '나',
      gone: false,
    });
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({ '7': 3 });
  });

  it('새로고침 뒤 저장값이 있으면 준비된 값은 처음부터 그 채널이다: 내 채널로 한 번이라도 그리지 않는다', async () => {
    remembered({ '7': 3 });
    stub();
    const seen: Array<number | null> = [];
    const { result } = renderHook(
      () => {
        const active = useActiveChannel();
        if (active.ready) seen.push(active.streamerId);
        return active;
      },
      { wrapper: makeQueryWrapper(createTestQueryClient()) },
    );

    await waitFor(() => expect(result.current.streamerId).toBe(3));
    expect(seen.length).toBeGreaterThan(0);
    expect(seen.every((id) => id === 3)).toBe(true);
  });

  it('남의 채널이 선 뒤 as-editor 다시 읽기가 실패해도 그 채널에 머문다: 남은 데이터를 버리지 않는다(감사 2회차 지적 2)', async () => {
    remembered({ '7': 3 });
    let fail = false;
    const spy = stub(() =>
      fail ? jsonResponse(500, { error: 'boom' }) : jsonResponse(200, [BYEOLHA]),
    );
    const client = createTestQueryClient();
    const { result } = renderHook(() => useActiveChannel(), { wrapper: makeQueryWrapper(client) });
    await waitFor(() => expect(result.current.streamerId).toBe(3));

    fail = true;
    await act(() => client.invalidateQueries({ queryKey: ['editor-delegations'] }));

    await waitFor(() =>
      expect(spy.mock.calls.filter(([url]) => url === AS_EDITOR_URL)).toHaveLength(2),
    );
    await waitFor(() =>
      expect(client.getQueryState(['editor-delegations', 'as-editor'])?.status).toBe('error'),
    );
    expect(result.current).toEqual({
      ready: true,
      streamerId: 3,
      relation: 'EDITOR',
      name: '별하',
      gone: false,
    });
  });

  it('다른 탭이 고른 채널이 낡은 as-editor에 없으면 끊김으로 단정하지 않고 기다린다(감사 2회차 지적 1)', async () => {
    let delegations: unknown[] = [];
    stub(() => jsonResponse(200, delegations));
    const client = createTestQueryClient();
    const { result } = renderHook(() => useActiveChannel(), { wrapper: makeQueryWrapper(client) });
    await waitFor(() => expect(result.current.streamerId).toBe(7));

    // 다른 탭에서 초대를 수락하고 별하로 전환했다: 이 탭의 as-editor는 수락 전의 []다
    delegations = [BYEOLHA];
    act(() => {
      remembered({ '7': 3 });
      window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));
    });

    expect(result.current.ready).toBe(false);
    expect(result.current.gone).toBe(false);

    await act(() => client.refetchQueries({ queryKey: ['editor-delegations', 'as-editor'] }));
    await waitFor(() => expect(result.current.streamerId).toBe(3));
    expect(result.current.relation).toBe('EDITOR');
  });

  it('me를 읽기 전에는 준비 안 됨이다', () => {
    stubFetch(() => new Promise<Response>(() => {}));
    const { result } = renderActive();

    expect(result.current.ready).toBe(false);
  });

  it('다른 회원(8)의 저장값은 안 쓴다', async () => {
    remembered({ '8': 3 });
    stub();
    const { result } = renderActive();

    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.streamerId).toBe(7);
  });
});

describe('useSwitchChannel: 전환', () => {
  function renderBoth() {
    return renderHook(() => ({ active: useActiveChannel(), switchTo: useSwitchChannel() }), {
      wrapper: makeQueryWrapper(createTestQueryClient()),
    });
  }

  beforeEach(() => useSwitchCurtain.getState().reset());

  it('별하로 전환하면 전환 화면이 서고, 다 덮은 뒤에 저장되고 지금 채널이 바뀐다', async () => {
    stub();
    const { result } = renderBoth();
    await waitFor(() => expect(result.current.active.ready).toBe(true));

    act(() => result.current.switchTo({ streamerId: 3, name: '별하', relation: 'EDITOR' }));

    // 덮는 동안은 아직 그대로다: 바뀌는 모습이 덮개 너머로 비치지 않게
    expect(useSwitchCurtain.getState().curtain).toMatchObject({
      name: '별하',
      relation: 'EDITOR',
      photo: null,
    });
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
    await waitFor(() =>
      expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({ '7': 3 }),
    );
    await waitFor(() => expect(result.current.active.streamerId).toBe(3));
    // 알림은 전환 화면이 맡는다: 토스트는 없다
    expect(screen.queryByText(/전환했어요/)).not.toBeInTheDocument();
  });

  it('내 채널로 전환하면 저장된 줄을 지우고 전환 화면은 스트리머다', async () => {
    remembered({ '7': 3 });
    stub();
    const { result } = renderBoth();
    await waitFor(() => expect(result.current.active.streamerId).toBe(3));

    act(() => result.current.switchTo({ streamerId: 7, name: '나', relation: 'OWNER' }));

    expect(useSwitchCurtain.getState().curtain).toMatchObject({ name: '나', relation: 'OWNER' });
    await waitFor(() => expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({}));
    await waitFor(() => expect(result.current.active.streamerId).toBe(7));
  });

  it('연달아 전환하면 마지막 것만 바뀐다: 앞 전환은 덮는 동안 버려진다', async () => {
    stub();
    const { result } = renderBoth();
    await waitFor(() => expect(result.current.active.ready).toBe(true));

    act(() => result.current.switchTo({ streamerId: 3, name: '별하', relation: 'EDITOR' }));
    act(() => result.current.switchTo({ streamerId: 7, name: '나', relation: 'OWNER' }));

    expect(useSwitchCurtain.getState().curtain).toMatchObject({ name: '나' });
    await act(() => new Promise((resolve) => setTimeout(resolve, COVER_IN_MS + 50)));
    expect(useChannelStore.getState().selected).toBeNull();
    expect(result.current.active.streamerId).toBe(7);
  });
});
