'use client';

import { apiFetch } from './client';

// 회원 탈퇴 (POK-171) — DELETE /api/auth/me. 204 로 답하고 그 계정의 토큰·연동·키를 서버가
// 전부 회수한다. 되돌릴 수 없다 — 호출부는 반드시 재확인 모달 뒤에서만 부른다.
export async function withdrawMe(): Promise<void> {
  await apiFetch('/api/auth/me', { method: 'DELETE' });
}
