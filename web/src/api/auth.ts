'use client';

import { apiFetch, ApiError } from './client';
import type { TokenPair } from '@/stores/auth';

// auth 서버 호출 (POK-101) — next.config.ts rewrites가 /api/auth/*를 AUTH_API_URL로
// 프록시한다. login·logout은 Bearer가 필요 없고 401 인터셉트가 끼면 안 되므로
// apiFetch가 아니라 생 fetch를 쓴다 (refresh는 client.ts의 refreshSession이 소유).

export interface Me {
  id: number;
  email: string;
  name: string;
  /**
   * 그림 태그에 그대로 넣는 주소. 구글 사진이거나, 직접 올린 사진이면 auth의
   * `/api/profile-photos/{id}?token=…`다(10분 단위로 안정, 사진을 바꾸면 즉시 달라진다).
   * `null`이면 구글이 사진을 안 줬거나 사진 창고가 꺼진 것 — 이니셜을 그린다.
   * 뒤의 token이 서명값이라 **직접 조립하지 않는다** (POK-207 계약).
   */
  profileImageUrl: string | null;
}

/** 구글 동의 화면에서 받은 authorization code를 우리 토큰 한 쌍으로 바꾼다. 처음 온 사용자는 자동 가입. */
export async function loginWithGoogle(code: string): Promise<TokenPair> {
  const res = await fetch('/api/auth/google', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  if (!res.ok) {
    // 401은 사유를 알려주지 않는 계약 — 문구를 일반화한다.
    throw new ApiError(res.status, '로그인에 실패했어요');
  }
  return res.json() as Promise<TokenPair>;
}

export async function fetchMe(): Promise<Me> {
  const res = await apiFetch('/api/auth/me');
  return res.json() as Promise<Me>;
}

/**
 * refresh 토큰을 서버에서 폐기한다. 없는 토큰이어도 204라(존재 비공개 계약)
 * 실패는 네트워크 장애뿐 — 호출부(useLogout)는 실패해도 로컬 로그아웃을 진행한다.
 */
export async function logoutSession(refreshToken: string): Promise<void> {
  await fetch('/api/auth/logout', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
}

/** 세션 판별 쿼리 — AuthGuard 부트스트랩과 헤더 프로필이 같은 캐시를 쓴다. */
export const meQueryOptions = {
  queryKey: ['auth', 'me'] as const,
  queryFn: fetchMe,
};
