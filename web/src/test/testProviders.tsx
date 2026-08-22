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
