import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionMutation } from '@/lib/useSessionMutation';
import { useAuthStore } from '@/stores/auth';

// 세션 세대를 확인하는 뮤테이션 (POK-424). 화면별 회귀 시험(연동 · 페어링 코드)과 별개로 훅의 약속을 잰다:
// 세대가 바뀐 뒤 온 응답은 성공 · 실패 콜백을 건너뛰고 끝맺음은 부르며 오류로도 남지 않는다. 회전은 걸리지 않는다.

function setup<T>(answer: () => Promise<T>) {
  // 요청이 실제로 나갔는지 본다: 계정은 요청이 나간 뒤에 바꿔야 실제 경합이다(다른 탭 메시지는 따로 도착한다)
  const send = vi.fn(answer);
  const onSuccess = vi.fn();
  const onError = vi.fn();
  const onSettled = vi.fn();
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(
    () => useSessionMutation<T, Error, void>({ mutationFn: send, onSuccess, onError, onSettled }),
    { wrapper },
  );
  const sent = () => waitFor(() => expect(send).toHaveBeenCalled());
  return { result, onSuccess, onError, onSettled, sent };
}

function held<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const login = (n: number) =>
  useAuthStore.getState().setTokens({ accessToken: `a-${n}`, refreshToken: `r-${n}` });

beforeEach(() => {
  window.localStorage.clear();
  login(1);
});

describe('useSessionMutation (POK-424)', () => {
  it('같은 세션이면 성공 콜백을 부른다', async () => {
    const answer = held<string>();
    const { result, onSuccess, onSettled } = setup(() => answer.promise);
    act(() => result.current.mutate());
    answer.resolve('ok');
    await waitFor(() => expect(onSettled).toHaveBeenCalled());
    expect(onSuccess).toHaveBeenCalledWith('ok', undefined, undefined, expect.anything());
  });

  it('같은 세션의 실패는 실패 콜백에 원래 오류를 준다', async () => {
    const answer = held<string>();
    const { result, onError, onSettled } = setup(() => answer.promise);
    act(() => result.current.mutate());
    const boom = new Error('boom');
    answer.reject(boom);
    await waitFor(() => expect(onSettled).toHaveBeenCalled());
    expect(onError).toHaveBeenCalledWith(boom, undefined, undefined, expect.anything());
  });

  it('보낸 뒤 다른 계정이 로그인하면 성공을 버린다: 끝맺음만 부르고 오류로도 남기지 않는다', async () => {
    const answer = held<string>();
    const { result, onSuccess, onError, onSettled, sent } = setup(() => answer.promise);
    act(() => result.current.mutate());
    await sent();
    login(2);
    answer.resolve('a의 결과');
    await waitFor(() => expect(onSettled).toHaveBeenCalled());
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
    await waitFor(() => expect(result.current.status).toBe('idle'));
  });

  it('보낸 뒤 로그아웃하면 실패도 버린다', async () => {
    const answer = held<string>();
    const { result, onError, onSettled, sent } = setup(() => answer.promise);
    act(() => result.current.mutate());
    await sent();
    useAuthStore.getState().clearTokens();
    answer.reject(new Error('boom'));
    await waitFor(() => expect(onSettled).toHaveBeenCalled());
    expect(onError).not.toHaveBeenCalled();
    await waitFor(() => expect(result.current.isError).toBe(false));
  });

  it('재시도를 하지 않는다: 재시도는 세션을 새로 적어 계정이 바뀐 뒤 앞 사람의 동작을 다음 사람 토큰으로 보낸다(로컬 리뷰 1라운드)', async () => {
    const send = vi.fn(() => Promise.reject(new Error('network')));
    const onSettled = vi.fn();
    const client = new QueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(
      () =>
        useSessionMutation<never, Error, void>({
          mutationFn: send,
          retry: 3,
          retryDelay: 0,
          onSettled,
        }),
      { wrapper },
    );
    act(() => result.current.mutate());
    await waitFor(() => expect(onSettled).toHaveBeenCalled());
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('같은 세션의 토큰 회전에는 걸리지 않는다: 30분마다 정상 왕복이 결과를 잃으면 안 된다', async () => {
    const answer = held<string>();
    const { result, onSuccess, onSettled, sent } = setup(() => answer.promise);
    act(() => result.current.mutate());
    await sent();
    useAuthStore.getState().rotateTokens({ accessToken: 'a-1b', refreshToken: 'r-1b' });
    answer.resolve('ok');
    await waitFor(() => expect(onSettled).toHaveBeenCalled());
    expect(onSuccess).toHaveBeenCalled();
  });
});
