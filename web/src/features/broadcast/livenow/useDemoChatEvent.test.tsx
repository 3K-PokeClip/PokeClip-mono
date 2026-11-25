import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { useAuthStore } from '@/stores/auth';
import { ToastProvider } from '@/ui';
import { isDemoChatEventKey, useDemoChatEvent } from './useDemoChatEvent';

// 발표 데모의 「채팅 이벤트」 단축키(⌃⇧C). 데모 계정이 아니면 키를 눌러도 아무 요청이 안 나간다

const wrapper = ({ children }: { children: ReactNode }) => (
  <ToastProvider>{children}</ToastProvider>
);

function press(init: KeyboardEventInit) {
  document.body.dispatchEvent(
    new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }),
  );
}

const posts = (spy: ReturnType<typeof stubFetch>) =>
  spy.mock.calls.filter(([, init]) => init?.method === 'POST').map(([url]) => url);

describe('isDemoChatEventKey', () => {
  it('Control + Shift + C 자리만 받는다. 한글 입력 중(ㅊ)이어도 키 자리로 본다', () => {
    expect(isDemoChatEventKey({ code: 'KeyC', key: 'C', ctrlKey: true, shiftKey: true })).toBe(
      true,
    );
    expect(isDemoChatEventKey({ code: 'KeyC', key: 'ㅊ', ctrlKey: true, shiftKey: true })).toBe(
      true,
    );
    expect(
      isDemoChatEventKey({ code: 'KeyC', key: 'C', ctrlKey: true, shiftKey: true, repeat: true }),
    ).toBe(false);
    expect(
      isDemoChatEventKey({ code: 'KeyC', key: 'C', ctrlKey: true, shiftKey: true, metaKey: true }),
    ).toBe(false);
    expect(isDemoChatEventKey({ code: 'KeyC', key: 'c', ctrlKey: true })).toBe(false);
    expect(isDemoChatEventKey({ code: 'F1', key: 'F1', ctrlKey: true, shiftKey: true })).toBe(
      false,
    );
  });
});

describe('useDemoChatEvent', () => {
  beforeEach(() => useAuthStore.setState({ refreshToken: 'a-계정' }));
  afterEach(() => {
    vi.unstubAllGlobals();
    useAuthStore.setState({ refreshToken: null });
  });

  it('데모 계정이면 라이브 방송에서 누를 때 그 방송에 채팅 이벤트를 보낸다', async () => {
    const spy = stubFetch((url) =>
      url === '/api/clip/demo/chat-event'
        ? new Response(null, { status: 204 })
        : jsonResponse(202, { started: true }),
    );
    const { result } = renderHook(() => useDemoChatEvent('live-1', true), { wrapper });
    await waitFor(() => expect(result.current).toBe(true));

    press({ code: 'KeyC', key: 'C', ctrlKey: true, shiftKey: true });

    await waitFor(() =>
      expect(posts(spy)).toEqual(['/api/clip/broadcasts/live-1/demo/chat-event']),
    );
  });

  it('데모 계정이 아니면 눌러도 아무것도 안 보낸다', async () => {
    const spy = stubFetch(() => jsonResponse(404));
    const { result } = renderHook(() => useDemoChatEvent('live-1', true), { wrapper });
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));

    press({ code: 'KeyC', key: 'C', ctrlKey: true, shiftKey: true });

    expect(result.current).toBe(false);
    expect(posts(spy)).toEqual([]);
  });

  it('끝난 방송이나 글 쓰는 칸에서는 안 보낸다', async () => {
    const spy = stubFetch(() => new Response(null, { status: 204 }));
    const { result, rerender } = renderHook(({ live }) => useDemoChatEvent('s-1', live), {
      wrapper,
      initialProps: { live: false },
    });
    await waitFor(() => expect(result.current).toBe(true));
    press({ code: 'KeyC', key: 'C', ctrlKey: true, shiftKey: true });

    rerender({ live: true });
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.dispatchEvent(
      new KeyboardEvent('keydown', {
        bubbles: true,
        cancelable: true,
        code: 'KeyC',
        ctrlKey: true,
        shiftKey: true,
      }),
    );
    input.remove();

    expect(posts(spy)).toEqual([]);
  });
  it('쓸 수 있는지 묻다가 서버가 잠깐 아프면 끄지 않고 다시 묻는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let down = true;
    stubFetch(() => (down ? jsonResponse(503) : new Response(null, { status: 204 })));
    act(() => useAuthStore.setState({ refreshToken: 'a-계정' }));
    const { result } = renderHook(() => useDemoChatEvent('live-1', true), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(result.current).toBe(false);

    down = false;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(result.current).toBe(true);
    vi.useRealTimers();
  });

  it('같은 화면에서 계정이 바뀌면 다시 묻고, 보통 계정이면 키를 뗀다', async () => {
    let demo = true;
    const spy = stubFetch((url) =>
      url === '/api/clip/demo/chat-event'
        ? demo
          ? new Response(null, { status: 204 })
          : jsonResponse(404)
        : jsonResponse(202, { started: true }),
    );
    act(() => useAuthStore.setState({ refreshToken: 'a-계정' }));
    const { result } = renderHook(() => useDemoChatEvent('live-1', true), { wrapper });
    await waitFor(() => expect(result.current).toBe(true));

    demo = false;
    act(() => useAuthStore.setState({ refreshToken: 'b-계정' }));
    await waitFor(() => expect(result.current).toBe(false));
    press({ code: 'KeyC', key: 'C', ctrlKey: true, shiftKey: true });

    expect(posts(spy)).toEqual([]);
  });
});
