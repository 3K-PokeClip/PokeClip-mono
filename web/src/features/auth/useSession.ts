'use client';

import { useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { logoutSession, meQueryOptions } from '@/api/auth';
import { markIntentionalLogout } from '@/components/app-shell/AuthGuard';
import { useAuthStore } from '@/stores/auth';

// 세션 훅 (POK-101) — me 쿼리와 로그아웃을 화면에서 쓰기 좋은 모양으로 감싼다.

/** 로그인한 사용자 정보. refresh 토큰이 서기 전(비로그인·하이드레이션 전)엔 요청하지 않는다. */
export function useMe() {
  const hydrated = useAuthStore((s) => s.hydrated);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  return useQuery({
    ...meQueryOptions,
    enabled: hydrated && refreshToken !== null,
  });
}

/**
 * 로그아웃 — 서버 폐기가 실패(네트워크 장애)해도 로컬 세션은 무조건 접는다.
 * 사용자가 "로그아웃했는데 로그인돼 있는" 상태가 그 반대보다 훨씬 나쁘다.
 */
export function useLogout() {
  const router = useRouter();
  const queryClient = useQueryClient();

  return useCallback(async () => {
    const { refreshToken, clearTokens } = useAuthStore.getState();
    try {
      if (refreshToken !== null) await logoutSession(refreshToken);
    } catch {
      /* 서버 폐기 실패 — 남은 refresh는 14일 뒤 만료된다. 로컬 로그아웃을 우선한다. */
    }
    // clearTokens가 가드 이펙트를 깨우기 전에 표식부터 — 로그아웃 위치가 복원 경로로 남지 않게
    markIntentionalLogout();
    clearTokens();
    queryClient.clear(); // 이전 계정의 캐시(me·스트림키 상태)가 다음 로그인에 새면 안 된다
    router.replace('/login');
  }, [queryClient, router]);
}
