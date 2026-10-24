import { act, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchAllBroadcasts } from '@/api/clipEditor';
import { useAuthStore } from '@/stores/auth';
import { notifyChannelGone, useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { ChannelScope, useChannelStreamerId } from './ChannelScope';
import { useSwitchCurtain } from './switchCurtain';

const nav = vi.hoisted(() => ({ pathname: '/home', replace: vi.fn() }));
vi.mock('next/navigation', () => ({
  usePathname: () => nav.pathname,
  useRouter: () => ({ replace: nav.replace, push: vi.fn() }),
}));

const ME_URL = '/api/auth/me';
const AS_EDITOR_URL = '/api/editor-delegations/as-editor';
const STORAGE_KEY = 'pc-channel';
const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
const BYEOLHA = {
  id: 21,
  counterpartId: 3,
  counterpartName: '별하',
  grantedAt: '2026-10-01T00:00:00Z',
};

let mounts = 0;

/** 화면 훅 흉내: 마운트 때 한 번만 목록을 부른다(홈·지난 방송 훅의 빈 의존 효과와 같은 모양). */
function Probe() {
  const streamerId = useChannelStreamerId();
  useEffect(() => {
    mounts += 1;
    void fetchAllBroadcasts('past', streamerId).catch(() => null);
    // 마운트 한 번만: ChannelScope가 채널이 바뀌면 다시 마운트해 준다는 것을 잰다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return <p>채널 {streamerId}</p>;
}

interface Stub {
  asEditor?: () => Response | Promise<Response>;
}

function stub({ asEditor = () => jsonResponse(200, [BYEOLHA]) }: Stub = {}) {
  return stubFetch((url) => {
    if (url === ME_URL) return jsonResponse(200, ME);
    if (url === AS_EDITOR_URL) return asEditor();
    if (url.startsWith('/api/clip/broadcasts?'))
      return jsonResponse(200, { broadcasts: [], nextCursor: null });
    throw new Error(`unexpected fetch: ${url}`);
  });
}

const listCalls = (spy: ReturnType<typeof stubFetch>) =>
  spy.mock.calls
    .map(([url]) => String(url))
    .filter((url) => url.startsWith('/api/clip/broadcasts?'))
    .map((url) => new URL(url, 'http://localhost').searchParams.get('streamerId'));

const asEditorCalls = (spy: ReturnType<typeof stubFetch>) =>
  spy.mock.calls.filter(([url]) => url === AS_EDITOR_URL).length;

function renderScope() {
  return renderWithProviders(
    <ChannelScope>
      <Probe />
    </ChannelScope>,
  );
}

beforeEach(() => {
  mounts = 0;
  nav.pathname = '/home';
  nav.replace.mockReset();
  window.localStorage.clear();
  window.history.pushState({}, '', '/home');
  useChannelStore.setState({ userId: null, selected: null });
  useSwitchCurtain.getState().reset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.pushState({}, '', '/');
});

describe('ChannelScope', () => {
  it('지금 채널이 서기 전에는 자식을 안 그리고, 서면 그 번호를 내려 준다', async () => {
    stub();
    renderScope();

    expect(screen.queryByText(/채널/)).not.toBeInTheDocument();
    expect(await screen.findByText('채널 7')).toBeInTheDocument();
  });

  it('지금 채널이 서기 전에는 자식 대신 fallback을 그리고, 서면 걷는다(POK-442)', async () => {
    let release: (r: Response) => void = () => {};
    stubFetch((url) => {
      if (url === ME_URL) return new Promise<Response>((r) => (release = r));
      if (url === AS_EDITOR_URL) return jsonResponse(200, [BYEOLHA]);
      if (url.startsWith('/api/clip/broadcasts?'))
        return jsonResponse(200, { broadcasts: [], nextCursor: null });
      throw new Error(`unexpected fetch: ${url}`);
    });
    renderWithProviders(
      <ChannelScope fallback={<p>본문 뼈대</p>}>
        <Probe />
      </ChannelScope>,
    );

    expect(screen.getByText('본문 뼈대')).toBeInTheDocument();
    expect(screen.queryByText(/채널/)).not.toBeInTheDocument();
    expect(mounts).toBe(0);

    act(() => release(jsonResponse(200, ME)));

    expect(await screen.findByText('채널 7')).toBeInTheDocument();
    expect(screen.queryByText('본문 뼈대')).not.toBeInTheDocument();
  });

  it('방송을 못 박은 화면에서 바꿔 홈으로 떠나는 동안에도 fallback을 그린다(POK-442)', async () => {
    nav.pathname = '/broadcast/vod/s-1';
    stub();
    renderWithProviders(
      <ChannelScope fallback={<p>본문 뼈대</p>}>
        <Probe />
      </ChannelScope>,
    );
    await screen.findByText('채널 7');

    act(() => useChannelStore.getState().select(7, 3));

    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/home'));
    expect(screen.getByText('본문 뼈대')).toBeInTheDocument();
    expect(screen.queryByText('채널 3')).not.toBeInTheDocument();
  });

  it('me를 못 읽으면 화면을 막지 않고 번호 없이(섞어서) 그린다: 예전 동작', async () => {
    stubFetch((url) => {
      if (url === ME_URL) return jsonResponse(500, { error: 'boom' });
      if (url.startsWith('/api/clip/broadcasts?'))
        return jsonResponse(200, { broadcasts: [], nextCursor: null });
      throw new Error(`unexpected fetch: ${url}`);
    });
    renderScope();

    expect(await screen.findByText('채널')).toBeInTheDocument();
  });

  it('새로고침 뒤 저장값 3이고 as-editor가 늦게 오면 목록은 정확히 한 번, 3으로 부른다', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    let release: (r: Response) => void = () => {};
    const spy = stub({ asEditor: () => new Promise<Response>((r) => (release = r)) });
    renderScope();

    await waitFor(() => expect(asEditorCalls(spy)).toBe(1));
    expect(listCalls(spy)).toEqual([]);
    act(() => release(jsonResponse(200, [BYEOLHA])));

    expect(await screen.findByText('채널 3')).toBeInTheDocument();
    await waitFor(() => expect(listCalls(spy)).toEqual(['3']));
    expect(mounts).toBe(1);
  });

  it('채널을 바꾸면 자식이 다시 서고 새 번호로 부른다', async () => {
    const spy = stub();
    renderScope();
    await screen.findByText('채널 7');

    act(() => useChannelStore.getState().select(7, 3));

    expect(await screen.findByText('채널 3')).toBeInTheDocument();
    expect(mounts).toBe(2);
    await waitFor(() => expect(listCalls(spy)).toEqual(['7', '3']));
  });

  it('지난 방송 상세 주소에서 채널을 바꾸면 홈으로 간다: 첫 진입에서는 안 간다', async () => {
    nav.pathname = '/broadcast/vod/s-1';
    stub();
    renderScope();
    await screen.findByText('채널 7');
    expect(nav.replace).not.toHaveBeenCalled();

    act(() => useChannelStore.getState().select(7, 3));

    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/home'));
  });

  it('?stream=으로 방송을 못 박은 화면에서 바꿔도 홈으로 간다', async () => {
    nav.pathname = '/broadcast/livenow';
    window.history.pushState({}, '', '/broadcast/livenow?stream=s-1');
    stub();
    renderScope();
    await screen.findByText('채널 7');

    act(() => useChannelStore.getState().select(7, 3));

    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/home'));
  });

  it('목록 화면에서 바꾸면 그 자리에 머문다', async () => {
    nav.pathname = '/clips/library';
    window.history.pushState({}, '', '/clips/library');
    stub();
    renderScope();
    await screen.findByText('채널 7');

    act(() => useChannelStore.getState().select(7, 3));

    await screen.findByText('채널 3');
    expect(nav.replace).not.toHaveBeenCalled();
  });

  it('기억한 채널의 위임이 끊겼으면 내 채널로 돌리고 저장값을 지우고 토스트를 한 번 띄운다', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    stub({ asEditor: () => jsonResponse(200, []) });
    renderScope();

    expect(await screen.findByText('채널 7')).toBeInTheDocument();
    expect(await screen.findAllByText('편집 권한이 없어져 내 채널로 돌아왔어요')).toHaveLength(1);
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({});
  });

  it('목록 404 신호가 오면 as-editor를 다시 읽고, 끊겼으면 내 채널로 돌아간다', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    let delegations = [BYEOLHA];
    const spy = stub({ asEditor: () => jsonResponse(200, delegations) });
    renderScope();
    await screen.findByText('채널 3');
    const before = asEditorCalls(spy);

    delegations = [];
    act(() => notifyChannelGone(3));

    await waitFor(() => expect(asEditorCalls(spy)).toBe(before + 1));
    expect(await screen.findByText('채널 7')).toBeInTheDocument();
    expect(screen.getByText('편집 권한이 없어져 내 채널로 돌아왔어요')).toBeInTheDocument();
  });

  it('방금 스스로 나간 채널의 끊김은 경고하지 않고 내 채널로만 돌아간다(PR #342 리뷰 4)', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    let delegations = [BYEOLHA];
    const spy = stub({ asEditor: () => jsonResponse(200, delegations) });
    renderScope();
    await screen.findByText('채널 3');
    const before = asEditorCalls(spy);

    // 계정 전환 창에서 별하에서 나가 전환 화면이 덮는 중이다(아직 내 채널로 바꾸기 전). 그 사이 별하 요청이 404를 받았다
    act(() =>
      useSwitchCurtain.setState({
        curtain: {
          key: 1,
          name: '나',
          relation: 'OWNER',
          photo: null,
          shownAt: Date.now(),
          leftStreamerId: 3,
        },
      }),
    );
    delegations = [];
    act(() => notifyChannelGone(3));

    await waitFor(() => expect(asEditorCalls(spy)).toBe(before + 1));
    expect(await screen.findByText('채널 7')).toBeInTheDocument();
    expect(screen.queryByText('편집 권한이 없어져 내 채널로 돌아왔어요')).not.toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({});
  });
});

describe('ChannelScope: 감사 2회차', () => {
  it('다른 탭이 방금 고른 채널이 이 탭의 낡은 as-editor에 없으면 새로 읽어 확인한다: 끊김으로 단정하지 않는다(지적 1)', async () => {
    let delegations: unknown[] = [];
    const spy = stub({ asEditor: () => jsonResponse(200, delegations) });
    renderScope();
    await screen.findByText('채널 7');
    const before = asEditorCalls(spy);

    // 다른 탭에서 초대를 수락하고 별하로 전환했다
    delegations = [BYEOLHA];
    act(() => {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
      window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));
    });

    expect(await screen.findByText('채널 3')).toBeInTheDocument();
    expect(asEditorCalls(spy)).toBe(before + 1);
    expect(screen.queryByText('편집 권한이 없어져 내 채널로 돌아왔어요')).not.toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({ '7': 3 });
  });

  it('다른 탭의 선택이 낡은 as-editor와 같은 밀리초에 와도 끊김으로 단정하지 않는다: 시각 비교에 기대지 않는다', async () => {
    // 위 시험이 20회 중 1회 흔들린 원인을 결정적으로 재현한다: 시계를 한 값으로 고정하면 「고른 시각」과 「목록을 받은
    // 시각」이 늘 같다. 운영에서도 같은 ms에 들어오면 같은 일이 난다(빈도가 낮을 뿐).
    vi.spyOn(Date, 'now').mockReturnValue(1_800_000_000_000);
    let delegations: unknown[] = [];
    const spy = stub({ asEditor: () => jsonResponse(200, delegations) });
    renderScope();
    await screen.findByText('채널 7');
    const before = asEditorCalls(spy);

    delegations = [BYEOLHA];
    act(() => {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
      window.dispatchEvent(new StorageEvent('storage', { key: STORAGE_KEY }));
    });

    expect(await screen.findByText('채널 3')).toBeInTheDocument();
    expect(asEditorCalls(spy)).toBe(before + 1);
    expect(screen.queryByText('편집 권한이 없어져 내 채널로 돌아왔어요')).not.toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!)).toEqual({ '7': 3 });
    vi.restoreAllMocks();
  });

  it('남의 채널이 선 뒤 as-editor 다시 읽기가 실패해도 그대로다: 다시 서지도, 홈으로 가지도 않는다(지적 2)', async () => {
    nav.pathname = '/broadcast/vod/s-1';
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    let fail = false;
    const spy = stub({
      asEditor: () => (fail ? jsonResponse(500, { error: 'boom' }) : jsonResponse(200, [BYEOLHA])),
    });
    renderScope();
    await screen.findByText('채널 3');
    const before = asEditorCalls(spy);

    fail = true;
    act(() => notifyChannelGone(3));

    await waitFor(() => expect(asEditorCalls(spy)).toBe(before + 1));
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByText('채널 3')).toBeInTheDocument();
    expect(mounts).toBe(1);
    expect(nav.replace).not.toHaveBeenCalled();
  });

  it('방송을 못 박은 주소에서 바꾸면 홈으로 가는 동안 자식을 새 채널로 세우지 않는다(지적 3)', async () => {
    nav.pathname = '/broadcast/vod/s-1';
    const spy = stub();
    renderScope();
    await screen.findByText('채널 7');

    act(() => useChannelStore.getState().select(7, 3));

    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/home'));
    expect(screen.queryByText('채널 3')).not.toBeInTheDocument();
    expect(mounts).toBe(1);
    expect(listCalls(spy)).toEqual(['7']);
  });
});

describe('ChannelScope: as-editor 쿼리가 지워졌다 다시 생길 때(감사 4회차 지적 1)', () => {
  /** 목록을 받은 뒤에 3을 고른다. 그래서 store가 「고를 때 본 목록」을 기억한다. */
  async function pickedWhileListLoaded(server: { delegations: unknown[] }) {
    const spy = stub({ asEditor: () => jsonResponse(200, server.delegations) });
    const { queryClient } = renderScope();
    await screen.findByText('채널 7');
    act(() => useChannelStore.getState().select(7, 3));
    await screen.findByText('채널 3');
    return { spy, queryClient };
  }

  it('지워진 뒤 새로 받은 목록에 없으면 끊김이다: 내 채널로 돌아오고 토스트 한 번(영영 준비 안 됨이 아니다)', async () => {
    const server = { delegations: [BYEOLHA] as unknown[] };
    const { queryClient } = await pickedWhileListLoaded(server);

    server.delegations = [];
    act(() => queryClient.clear());
    act(() => notifyChannelGone(3));

    expect(await screen.findByText('채널 7')).toBeInTheDocument();
    expect(await screen.findAllByText('편집 권한이 없어져 내 채널로 돌아왔어요')).toHaveLength(1);
  });

  it('지워진 뒤 새로 받은 목록에 있으면 그 채널에 머문다', async () => {
    const server = { delegations: [BYEOLHA] as unknown[] };
    const { spy, queryClient } = await pickedWhileListLoaded(server);
    const before = asEditorCalls(spy);

    act(() => queryClient.clear());
    act(() => notifyChannelGone(3));

    await waitFor(() => expect(asEditorCalls(spy)).toBeGreaterThan(before));
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByText('채널 3')).toBeInTheDocument();
    expect(screen.queryByText('편집 권한이 없어져 내 채널로 돌아왔어요')).not.toBeInTheDocument();
  });
});

describe('ChannelScope: 내 채널 목록이 404일 때(세션이 어긋난 오래된 탭)', () => {
  const ME16 = { id: 16, email: 'a@example.com', name: '열여섯', profileImageUrl: null };
  const ME9 = { id: 9, email: 'b@example.com', name: '아홉', profileImageUrl: null };

  /**
   * 이 탭은 me를 16으로 쥐고 있는데 토큰은 다른 탭에서 로그인한 9번 것이다. 그래서 16의 목록은 404이고 9의 목록은 된다.
   * meAnswers: me를 물을 때마다 차례로 줄 답(다 쓰면 마지막을 반복)
   */
  function serveStaleSession(meAnswers: unknown[]) {
    let meCalls = 0;
    const spy = stubFetch((url) => {
      if (url === ME_URL)
        return jsonResponse(200, meAnswers[Math.min(meCalls++, meAnswers.length - 1)]);
      if (url === AS_EDITOR_URL) return jsonResponse(200, []);
      if (url.startsWith('/api/clip/broadcasts?')) {
        const id = new URL(url, 'http://localhost').searchParams.get('streamerId');
        return id === '9'
          ? jsonResponse(200, { broadcasts: [], nextCursor: null })
          : jsonResponse(404, { error: 'broadcast_not_found' });
      }
      throw new Error(`unexpected fetch: ${url}`);
    });
    const count = (u: string) => spy.mock.calls.filter(([url]) => url === u).length;
    return { spy, meCalls: () => count(ME_URL), asEditorCalls: () => count(AS_EDITOR_URL) };
  }

  it('내 채널 목록이 404면 me를 다시 읽고, 다른 계정이면 그 계정의 채널로 다시 선다', async () => {
    const server = serveStaleSession([ME16, ME9]);
    renderScope();

    expect(await screen.findByText('채널 9')).toBeInTheDocument();
    expect(server.meCalls()).toBe(2);
    expect(listCalls(server.spy)).toEqual(['16', '9']);
  });

  it('me를 다시 읽어도 같은 계정이고 계속 404면 다시 읽기는 한 번뿐이다: 갱신마다 me·as-editor를 두드리지 않는다', async () => {
    const server = serveStaleSession([ME16]);
    renderScope();
    await screen.findByText('채널 16');
    await waitFor(() => expect(server.meCalls()).toBe(2));
    const asEditorBefore = server.asEditorCalls();

    // 홈·라이브의 갱신 주기가 같은 404를 여러 번 받는다
    for (let i = 0; i < 3; i++) {
      await act(() => fetchAllBroadcasts('past', 16).catch(() => null));
    }
    await new Promise((r) => setTimeout(r, 50));

    expect(server.meCalls()).toBe(2);
    expect(server.asEditorCalls()).toBe(asEditorBefore);
    expect(screen.getByText('채널 16')).toBeInTheDocument();
  });

  it('남의 채널 목록 404는 그대로 as-editor를 다시 읽고 me는 안 읽는다', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': 3 }));
    const spy = stub();
    renderScope();
    await screen.findByText('채널 3');
    const meBefore = spy.mock.calls.filter(([url]) => url === ME_URL).length;
    const before = asEditorCalls(spy);

    act(() => notifyChannelGone(3));

    await waitFor(() => expect(asEditorCalls(spy)).toBe(before + 1));
    await new Promise((r) => setTimeout(r, 50));
    expect(spy.mock.calls.filter(([url]) => url === ME_URL).length).toBe(meBefore);
  });
});

describe('useChannelStreamerId', () => {
  it('ChannelScope 밖이면 null이다: 화면 훅은 옛 호출(섞어서)처럼 부른다', () => {
    function Outside() {
      return <p>값 {String(useChannelStreamerId())}</p>;
    }
    renderWithProviders(<Outside />);
    expect(screen.getByText('값 null')).toBeInTheDocument();
  });
});
