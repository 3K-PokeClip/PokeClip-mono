import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { Dock } from '@/components/app-shell/Dock';
import styles from '@/components/app-shell/Dock.module.css';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const { push } = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock('next/navigation', () => ({
  usePathname: () => '/broadcast/livenow',
  useRouter: () => ({ push }),
}));

describe('Dock', () => {
  it('독 4개 링크를 렌더하고 현재 경로를 활성으로 표시한다', () => {
    renderWithProviders(<Dock />);

    const links = screen.getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/home',
      '/broadcast',
      '/clips',
      '/settings',
    ]);

    expect(screen.getByRole('link', { name: /방송/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /홈/ })).not.toHaveAttribute('aria-current');
  });

  it('활성 항목에만 알약을 렌더한다', () => {
    // 알약이 둘이면 브라우저가 옛/새 한 쌍으로 보지 못해 이동 대신 각자 페이드된다
    const { container } = renderWithProviders(<Dock />);

    expect(container.querySelectorAll(`.${styles.pill}`)).toHaveLength(1);
    expect(
      screen.getByRole('link', { name: /방송/ }).querySelector(`.${styles.pill}`),
    ).not.toBeNull();
  });
});

describe('Dock: 꾹 눌러 끌기', () => {
  // 칸 폭이 제각각이다 — 지금 탭(방송)만 라벨이 펼쳐져 넓다
  const RECTS = [
    [0, 40],
    [45, 125],
    [130, 170],
    [175, 215],
  ] as const;
  const X = [20, 85, 150, 195] as const;
  const Y = 25;
  const animate = vi.fn();

  function rect(left: number, right: number) {
    const r = { left, right, top: 0, bottom: 50, width: right - left, height: 50, x: left, y: 0 };
    return { ...r, toJSON: () => r } as DOMRect;
  }

  function setup() {
    const { container } = renderWithProviders(<Dock />);
    const nav = screen.getByRole('navigation', { name: '주요 탐색' });
    vi.spyOn(nav, 'getBoundingClientRect').mockReturnValue(rect(0, 220));
    screen.getAllByRole('link').forEach((el, i) => {
      const [left, right] = RECTS[i]!;
      vi.spyOn(el, 'getBoundingClientRect').mockReturnValue(rect(left, right));
    });
    const pill = () => container.querySelector(`.${styles.pill}`)!;
    return { nav, pill, pillLink: () => pill().closest('a') };
  }

  const link = (name: RegExp) => screen.getByRole('link', { name });

  // jsdom에는 Web Animations와 matchMedia가 없다 — 알약이 미끄러지는 FLIP이 이 둘을 부른다
  beforeAll(() => {
    Object.defineProperty(Element.prototype, 'animate', {
      configurable: true,
      writable: true,
      value: animate,
    });
  });
  afterAll(() => {
    Reflect.deleteProperty(Element.prototype, 'animate');
  });
  beforeEach(() => {
    push.mockClear();
    animate.mockClear();
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('알약을 잡아 끌면 손가락 아래 칸으로 미끄러져 가고, 놓은 탭으로 간다', () => {
    const { nav, pill, pillLink } = setup();

    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[1], clientY: Y });
    expect(pill()).toHaveAttribute('data-grabbed');

    fireEvent.pointerMove(nav, { pointerId: 1, clientX: X[2], clientY: Y });
    // 끄는 중엔 알약만 옮기고 아직 탭은 그대로다
    expect(pillLink()).toBe(link(/클립/));
    expect(link(/클립/)).toHaveAttribute('data-pill');
    expect(link(/방송/)).toHaveAttribute('aria-current', 'page');
    expect(animate).toHaveBeenCalledTimes(1);
    expect(push).not.toHaveBeenCalled();

    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[2], clientY: Y });
    expect(push).toHaveBeenCalledTimes(1);
    expect(push).toHaveBeenCalledWith('/clips', { transitionTypes: ['dock-forward'] });
    // 주소가 바뀔 때까지 놓은 칸에 남는다 — 옛 칸으로 돌아갔다가 다시 오지 않는다
    expect(pillLink()).toBe(link(/클립/));
    expect(pill()).not.toHaveAttribute('data-grabbed');
  });

  it('다른 칸을 누르면 그 칸만 흐려지고, 떼면 그 탭으로 간다 — 알약은 붙들지 않는다', () => {
    const { nav, pillLink } = setup();

    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[0], clientY: Y });
    expect(link(/홈/)).toHaveAttribute('data-dimmed');
    expect(pillLink()).toBe(link(/방송/));

    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[0], clientY: Y });
    expect(push).toHaveBeenCalledWith('/home', { transitionTypes: ['dock-back'] });
    // 그냥 누른 것은 예전처럼 뷰 트랜지션이 알약을 옮긴다
    expect(pillLink()).toBe(link(/방송/));
    expect(link(/홈/)).not.toHaveAttribute('data-dimmed');
  });

  it('지금 탭을 그냥 누르고 떼면 예전 링크처럼 그 탭으로 간다', () => {
    const { nav } = setup();

    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[1], clientY: Y });
    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[1], clientY: Y });
    expect(push).toHaveBeenCalledWith('/broadcast', undefined);
  });

  it('끌다가 지금 탭에 도로 놓으면 취소다', () => {
    const { nav, pillLink } = setup();

    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[1], clientY: Y });
    fireEvent.pointerMove(nav, { pointerId: 1, clientX: X[2], clientY: Y });
    fireEvent.pointerMove(nav, { pointerId: 1, clientX: X[1], clientY: Y });
    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[1], clientY: Y });
    expect(push).not.toHaveBeenCalled();
    expect(pillLink()).toBe(link(/방송/));
  });

  it('위아래로 멀리 벗어나 놓거나 포인터를 놓치면 취소하고 알약은 제자리로 돌아간다', () => {
    const { nav, pill, pillLink } = setup();

    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[1], clientY: Y });
    fireEvent.pointerMove(nav, { pointerId: 1, clientX: X[3], clientY: Y });
    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[3], clientY: Y - 200 });
    expect(push).not.toHaveBeenCalled();
    expect(pillLink()).toBe(link(/방송/));
    expect(pill()).not.toHaveAttribute('data-grabbed');

    fireEvent.pointerDown(nav, { pointerId: 2, clientX: X[1], clientY: Y });
    fireEvent.pointerMove(nav, { pointerId: 2, clientX: X[0], clientY: Y });
    fireEvent.pointerCancel(nav, { pointerId: 2 });
    expect(push).not.toHaveBeenCalled();
    expect(pillLink()).toBe(link(/방송/));
  });

  it('놓은 뒤 링크로 오는 click은 막고, 키보드 Enter의 click은 링크에 맡긴다', () => {
    const { nav } = setup();

    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[0], clientY: Y });
    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[0], clientY: Y });
    expect(push).toHaveBeenCalledTimes(1);
    // 독이 막았는지 맨 바깥에서 읽고, jsdom이 실제로 문서를 옮기려 들지 않게 거기서 막는다
    let prevented: boolean | null = null;
    const readThenStop = (e: Event) => {
      prevented = e.defaultPrevented;
      e.preventDefault();
    };
    document.addEventListener('click', readThenStop);
    try {
      fireEvent.click(link(/홈/), { detail: 1 });
      expect(prevented).toBe(true);
      fireEvent.click(link(/클립/), { detail: 0 });
      expect(prevented).toBe(false);
    } finally {
      document.removeEventListener('click', readThenStop);
    }
  });

  it('⌘ 클릭은 끌기로 잡지 않는다 — 새 탭 열기는 링크가 맡는다', () => {
    const { nav } = setup();

    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[0], clientY: Y, metaKey: true });
    expect(link(/홈/)).not.toHaveAttribute('data-dimmed');
    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[0], clientY: Y, metaKey: true });
    expect(push).not.toHaveBeenCalled();
  });

  it('놓아서 옮기면 고른 칸으로 포커스가 간다 — 키보드로 와 있던 옛 칸에 남지 않는다(PR #346 리뷰)', () => {
    const { nav } = setup();
    link(/설정/).focus();

    // 그냥 누르기
    fireEvent.pointerDown(nav, { pointerId: 1, clientX: X[0], clientY: Y });
    fireEvent.pointerUp(nav, { pointerId: 1, clientX: X[0], clientY: Y });
    expect(link(/홈/)).toHaveFocus();

    // 끌어 놓기
    fireEvent.pointerDown(nav, { pointerId: 2, clientX: X[1], clientY: Y });
    fireEvent.pointerMove(nav, { pointerId: 2, clientX: X[2], clientY: Y });
    fireEvent.pointerUp(nav, { pointerId: 2, clientX: X[2], clientY: Y });
    expect(link(/클립/)).toHaveFocus();
  });

  it('끌어 놓은 이동을 다른 이동이 덮으면 붙든 칸을 놓는다 — 주소가 그대로여도(PR #346 리뷰)', () => {
    const { nav, pillLink } = setup();
    const dropOnClips = (pointerId: number) => {
      fireEvent.pointerDown(nav, { pointerId, clientX: X[1], clientY: Y });
      fireEvent.pointerMove(nav, { pointerId, clientX: X[2], clientY: Y });
      fireEvent.pointerUp(nav, { pointerId, clientX: X[2], clientY: Y });
      expect(pillLink()).toBe(link(/클립/));
    };

    // 이동이 끝나기 전에 지금 탭(방송)을 그냥 누른다 — 주소는 계속 /broadcast/livenow다
    dropOnClips(1);
    fireEvent.pointerDown(nav, { pointerId: 2, clientX: X[1], clientY: Y });
    fireEvent.pointerUp(nav, { pointerId: 2, clientX: X[1], clientY: Y });
    expect(push).toHaveBeenLastCalledWith('/broadcast', undefined);
    expect(pillLink()).toBe(link(/방송/));

    // 키보드 Enter로 링크가 옮겨도 같다. ⌘ 클릭은 다른 탭으로 열려 붙든 칸을 그대로 둔다
    dropOnClips(3);
    const stop = (e: Event) => e.preventDefault();
    document.addEventListener('click', stop);
    try {
      fireEvent.click(link(/홈/), { detail: 0, metaKey: true });
      expect(pillLink()).toBe(link(/클립/));
      fireEvent.click(link(/방송/), { detail: 0 });
      expect(pillLink()).toBe(link(/방송/));
    } finally {
      document.removeEventListener('click', stop);
    }
  });
});

describe('Dock: 관계는 해석된 값으로(로컬 리뷰 1라운드 지적 2)', () => {
  const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
  const BYEOLHA = {
    id: 21,
    counterpartId: 3,
    counterpartName: '별하',
    grantedAt: '2026-10-01T00:00:00Z',
  };

  beforeEach(() => {
    window.localStorage.clear();
    useChannelStore.setState({ userId: null, selected: null });
    useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function serve(asEditor: () => Response) {
    stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return asEditor();
      return jsonResponse(404);
    });
  }

  it('저장값 3이 as-editor에 있으면 설정 탭은 알림 설정이다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    serve(() => jsonResponse(200, [BYEOLHA]));
    renderWithProviders(<Dock />);

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /설정/ })).toHaveAttribute(
        'href',
        '/settings/notifications',
      ),
    );
  });

  it('저장값 3이 있어도 as-editor를 처음부터 못 읽으면 내 채널로 보이므로 설정 탭은 /settings다', async () => {
    // 저장소에는 남의 번호가 그대로 있다(못 읽었을 때 저장값을 지우지 않는다): 원시값으로 가르면 알림 설정으로 간다
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    useChannelStore.setState({ userId: 7, selected: 3 });
    const spy = stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return jsonResponse(500, { error: 'boom' });
      return jsonResponse(404);
    });
    renderWithProviders(<Dock />);

    await new Promise((r) => setTimeout(r, 100));
    expect(screen.getByRole('link', { name: /설정/ })).toHaveAttribute('href', '/settings');
    expect(spy.mock.calls.some(([url]) => url === '/api/editor-delegations/as-editor')).toBe(true);
    expect(useChannelStore.getState().selected).toBe(3);
  });
});

describe('Dock: 채널이 아직 안 섰을 때(PR #323 codex 2차)', () => {
  const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };

  beforeEach(() => {
    window.localStorage.clear();
    useChannelStore.setState({ userId: null, selected: null });
    useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('as-editor를 기다리는 동안 저장된 남의 채널이 있으면 설정 탭은 알림 설정이다: /settings면 막힌 플러그인으로 간다', async () => {
    window.localStorage.setItem('pc-channel', JSON.stringify({ '7': 3 }));
    stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return new Promise<Response>(() => {});
      return jsonResponse(404);
    });
    renderWithProviders(<Dock />);

    await waitFor(() =>
      expect(useChannelStore.getState()).toMatchObject({ userId: 7, selected: 3 }),
    );
    expect(screen.getByRole('link', { name: /설정/ })).toHaveAttribute(
      'href',
      '/settings/notifications',
    );
  });
});
