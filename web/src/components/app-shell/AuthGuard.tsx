'use client';

import { useEffect, type ReactNode } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useMe } from '@/features/auth/useSession';
import { useAuthHydration, useAuthStore } from '@/stores/auth';

// 보호 라우트 가드 (POK-101) — 독 4개 라우트를 (dock)/layout.tsx에서 한 번에 덮는다.
// 토큰이 localStorage에 있어 미들웨어(서버)는 판단 재료가 없다 — 클라이언트 가드가 정답.

const RETURN_KEY = 'pc-auth-return';
const LOGOUT_KEY = 'pc-auth-logout';

/**
 * 의도적 로그아웃 표식 — useLogout이 clearTokens 직전에 부른다. 이것이 없으면
 * 토큰이 비는 순간 가드 이펙트가 "로그아웃한 사람이 보던 화면"을 복원 경로로
 * 저장해, 같은 브라우저의 다음 사용자가 그 화면으로 복원된다. (리뷰 #72)
 */
export function markIntentionalLogout() {
  try {
    sessionStorage.setItem(LOGOUT_KEY, '1');
    sessionStorage.removeItem(RETURN_KEY);
  } catch {
    /* 저장 실패 — 복원 경로가 남는 것까지만 감수 */
  }
}

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
      // 만료·도난 같은 비자발 종료만 복원 경로를 남긴다 — 재로그인 후 보던 화면으로
      // 돌아가는 게 맞다. 의도적 로그아웃(표식 존재)은 남기지 않는다. 표식은 1회용.
      const intentional = sessionStorage.getItem(LOGOUT_KEY) !== null;
      sessionStorage.removeItem(LOGOUT_KEY);
      // 로그인 후 가려던 화면으로 복원 — LoginScreen이 읽어 OAuth state에 실어 보낸다.
      if (!intentional) sessionStorage.setItem(RETURN_KEY, pathname);
    } catch {
      /* 저장 실패 — /home으로 복원되는 것까지만 감수 */
    }
    router.replace('/login');
  }, [hydrated, refreshToken, pathname, router]);

  // hydrate 전 1프레임 + 비로그인 리다이렉트 중에는 보호 콘텐츠를 그리지 않는다(플래시 방지).
  if (!hydrated || refreshToken === null) return null;
  return <>{children}</>;
}

/** consumeReturnPath로 꺼낸 경로를 되돌린다 — OAuth 진입이 실패해 이동하지 못한 경우. */
export function restoreReturnPath(path: string) {
  try {
    sessionStorage.setItem(RETURN_KEY, path);
  } catch {
    /* 저장 실패 — /home으로 복원되는 것까지만 감수 */
  }
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
