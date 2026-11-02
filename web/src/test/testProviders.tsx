import { useState, type ReactElement, type ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider, ToastProvider } from '@/ui';

// 쿼리·토스트·테마를 쓰는 화면 테스트용 래퍼 — 테스트마다 새 QueryClient를 만들어
// 캐시가 케이스 사이를 건너가지 않게 하고, retry를 꺼서 실패 케이스가 즉시 끝나게 한다.
// 테마는 저장소를 읽지도 쓰지도 않게 꺼 둔다(케이스 사이에 pc-theme가 새지 않게).

export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

/**
 * 클라이언트를 넘기는 경우: 운영 기본값(providers.tsx의 staleTime 60초)이 동작을 가르는 테스트다.
 * 기본 테스트 클라이언트는 staleTime이 0이라 「60초 안에 다시 열어도 다시 읽는다」가 늘 참으로 나온다.
 */
export function renderWithProviders(
  ui: ReactElement,
  queryClient: QueryClient = createTestQueryClient(),
) {
  const result = render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider enablePersistence={false}>
        <ToastProvider>{ui}</ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

/**
 * 훅 테스트용 래퍼 — 토스트가 필요한 훅을 renderHook에 걸 때 쓴다. 쿼리 클라이언트도 마운트마다 새로 준다: 목록 훅이 react-query를
 * 쓰므로 주입한 값으로 띄우는 시험도 클라이언트가 있어야 선다.
 */
export function withToastProvider({ children }: { children: ReactNode }) {
  return <HookProviders>{children}</HookProviders>;
}

function HookProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createTestQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
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
