'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { onCrossTabSessionChange } from '@/stores/auth';
import { ThemeProvider, ToastProvider } from '@/ui';

// DS 소스에는 'use client' 지시자가 없으므로 인터랙티브 DS 컴포넌트
// (ThemeProvider, useTheme, 훅/핸들러 사용 컴포넌트)는 반드시
// 'use client' 파일에서 렌더링해야 한다.
export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 60_000,
            retry: 1,
          },
        },
      }),
  );

  // 다른 탭이 세션을 바꾸면(로그아웃·재로그인·storage 폴백 동기화) 이전 세션의 캐시를 비운다 —
  // 공용 PC에서 이전 계정의 me·스트림키가 다음 세션 화면에 새면 안 된다. 같은 세션의 회전에는
  // 불리지 않는다 — 비우면 헤더 없는 me 401 → 재회전의 탭 간 핑퐁이 된다. (리뷰 #72 · POK-211)
  useEffect(() => onCrossTabSessionChange(() => queryClient.clear()), [queryClient]);

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider defaultTheme="dark">
        <ToastProvider>{children}</ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
