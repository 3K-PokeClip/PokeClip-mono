import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '@/ui';

// 쿼리·토스트를 쓰는 화면 테스트용 래퍼 — 테스트마다 새 QueryClient를 만들어
// 캐시가 케이스 사이를 건너가지 않게 하고, retry를 꺼서 실패 케이스가 즉시 끝나게 한다.

export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

export function renderWithProviders(ui: ReactElement) {
  const queryClient = createTestQueryClient();
  const result = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

/** 훅 테스트용 래퍼 — 토스트만 필요한 훅을 renderHook에 걸 때 쓴다. */
export function withToastProvider({ children }: { children: ReactNode }) {
  return <ToastProvider>{children}</ToastProvider>;
}

/**
 * 쿼리까지 쓰는 훅용 래퍼를 만든다 — 화면을 렌더하지 않고 훅만 시계에 걸 때 쓴다.
 * 클라이언트를 밖에서 만들어 넘기는 이유: 래퍼 본문에서 만들면 **렌더마다 새 클라이언트**가
 * 생겨 캐시가 통째로 날아가고, 테스트가 클라이언트를 들여다볼 수도 없다.
 */
export function makeQueryWrapper(queryClient: QueryClient) {
  return function QueryWrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    );
  };
}
