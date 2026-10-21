'use client';

import type { PluginReleaseResponse } from '@/lib/pluginRelease/types';

// 플러그인 설치 파일 정보 (POK-379) — 이 웹 서버의 라우트(app/api/plugin-release)가 GitHub Release를 정리해 준다.
// apiFetch를 쓰지 않는다: 로그인과 무관한 공개 정보라 Bearer를 붙일 이유가 없고, 401 회전에 엮일 이유도 없다.

export async function fetchPluginRelease(): Promise<PluginReleaseResponse> {
  const res = await fetch('/api/plugin-release');
  if (!res.ok) throw new Error(`plugin-release ${res.status}`);
  return res.json() as Promise<PluginReleaseResponse>;
}

export const pluginReleaseQueryOptions = {
  queryKey: ['pluginRelease'] as const,
  queryFn: fetchPluginRelease,
  // 서버가 5분 메모로 묶으니 화면도 그만큼은 다시 묻지 않는다
  staleTime: 5 * 60_000,
  // 자동 재시도 대신 「다시 시도」 버튼 — 실패한 GitHub를 같은 순간에 두드려도 바뀌지 않는다
  retry: false,
};
