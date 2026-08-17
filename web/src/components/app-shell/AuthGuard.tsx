'use client';

import { useEffect, type ReactNode } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useMe } from '@/features/auth/useSession';
import { useAuthHydration, useAuthStore } from '@/stores/auth';

// 보호 라우트 가드 (POK-101) — 독 4개 라우트를 (dock)/layout.tsx에서 한 번에 덮는다.
// 토큰이 localStorage에 있어 미들웨어(서버)는 판단 재료가 없다 — 클라이언트 가드가 정답.

const RETURN_KEY = 'pc-auth-return';

export function AuthGuard({ children }: { children: ReactNode }) {
  useAuthHydration();
  const hydrated = useAuthStore((s) => s.hydrated);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  const router = useRouter();
  const pathname = usePathname();

  // 세션 부트스트랩 — 화면은 낙관적으로 즉시 그리고(첫 페인트를 네트워크에 잡히지 않게),
  // me가 최종 401이면(내부의 refresh 회전 실패 = 만료·도난 감지) apiFetch가 clearTokens를
  // 부르고 아래 이펙트가 /login으로 보낸다. 완료조건 "만료·도난 시 자연 복귀"가 이 경로다.
  useMe();

  useEffect(() => {
    if (!hydrated || refreshToken !== null) return;
    try {
      // 로그인 후 가려던 화면으로 복원 — LoginScreen이 읽어 OAuth state에 실어 보낸다.
      sessionStorage.setItem(RETURN_KEY, pathname);
    } catch {
      /* 저장 실패 — /home으로 복원되는 것까지만 감수 */
    }
    router.replace('/login');
  }, [hydrated, refreshToken, pathname, router]);

  // hydrate 전 1프레임 + 비로그인 리다이렉트 중에는 보호 콘텐츠를 그리지 않는다(플래시 방지).
  if (!hydrated || refreshToken === null) return null;
  return <>{children}</>;
}

/** LoginScreen이 로그인 후 복원 경로를 읽을 때 쓴다 — 읽으면서 지운다(1회용). */
export function consumeReturnPath(): string | null {
  try {
    const path = sessionStorage.getItem(RETURN_KEY);
    sessionStorage.removeItem(RETURN_KEY);
    return path;
  } catch {
    return null;
  }
}
