'use client';

import { apiFetch } from '@/api/client';

// 지금 볼 방송 번호를 정한다(POK-251).
// 1) 지난 방송 상세 경로  2) 주소의 ?stream=  3) clip의 「방송 중」 목록의 첫 줄. 없으면 빈 문자열(방송 꺼짐)

let cached: Promise<string> | null = null;

/** 지난 방송 상세 경로면 그 방송 번호. 라이브 화면과 같은 컴포넌트를 쓰기 위한 분기다 */
export function vodStreamIdFromPath(): string | null {
  const m = window.location.pathname.match(/^\/broadcast\/vod\/([^/]+)/);
  return m?.[1] ? decodeURIComponent(m[1]) : null;
}

/** @param fresh 기억해 둔 답을 버리고 clip에 다시 묻는다 — 방송이 끝났는지 확인할 때 */
/** 기억해 둔 답을 버린다 — 시험 사이에 앞 시험의 방송이 남지 않게 */
export function forgetLiveStreamId() {
  cached = null;
}

export function resolveLiveStreamId(fresh = false): Promise<string> {
  if (fresh) cached = null;
  const fromPath = vodStreamIdFromPath();
  if (fromPath) return Promise.resolve(fromPath);
  const q = new URLSearchParams(window.location.search).get('stream');
  if (q) return Promise.resolve(q);
  if (cached) return cached;
  cached = (async () => {
    try {
      const r = await apiFetch('/api/clip/broadcasts?state=live&limit=1');
      const j = (await r.json()) as { broadcasts: { streamId: string }[] };
      return j.broadcasts[0]?.streamId ?? '';
    } catch {
      return '';
    }
  })();
  // 방송이 바뀔 수 있으니 30초만 기억한다
  setTimeout(() => {
    cached = null;
  }, 30_000);
  return cached;
}
