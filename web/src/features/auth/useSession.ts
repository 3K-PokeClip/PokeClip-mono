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
 * 로그아웃 — 로컬 세션부터 즉시 접고, 서버 폐기는 기다리지 않는다. auth 서버가 행에
 * 걸리면 브라우저 fetch 한도(수 분)까지 버튼이 무반응이 되고, 그 사이 "로그아웃했다"고
 * 믿은 사용자가 자리를 뜬다 — 폐기 실패는 원래도 무시하는 계약이었다(남은 refresh는
 * 14일 뒤 만료로 수렴). (리뷰 #72)
 */
export function useLogout() {
  const router = useRouter();
  const queryClient = useQueryClient();

  return useCallback(() => {
    const { refreshToken, clearTokens } = useAuthStore.getState();
    // clearTokens가 가드 이펙트를 깨우기 전에 표식부터 — 로그아웃 위치가 복원 경로로 남지 않게
    markIntentionalLogout();
    clearTokens();
    queryClient.clear(); // 이전 계정의 캐시(me·스트림키 상태)가 다음 로그인에 새면 안 된다
    if (refreshToken !== null) {
      void logoutSession(refreshToken).catch(() => {
        /* 서버 폐기 실패 — 남은 refresh는 14일 뒤 만료된다. 로컬 로그아웃은 이미 끝났다. */
      });
    }
    router.replace('/login');
  }, [queryClient, router]);
}
