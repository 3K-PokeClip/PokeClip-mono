'use client';

import { apiFetch } from '@/api/client';

// 지금 볼 방송 번호를 정한다(POK-251).
// 1) 지난 방송 상세 경로  2) 주소의 ?stream=  3) clip의 「방송 중」 목록의 첫 줄. 없으면 빈 문자열(방송 꺼짐)

let cached: Promise<string | null> | null = null;

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

async function askLiveStreamId(): Promise<string | null> {
  try {
    const r = await apiFetch('/api/clip/broadcasts?state=live&limit=1');
    const j = (await r.json()) as { broadcasts: { streamId: string }[] };
    return j.broadcasts[0]?.streamId ?? '';
  } catch {
    return null;
  }
}

/**
 * 지금 볼 방송 번호. 빈 문자열은 「방송 중인 것이 없다」, null은 「지금은 모른다」(서버·네트워크 오류)다.
 * 오류를 빈 문자열로 접으면 방송 중에 clip이 잠깐 재시작해도 화면이 꺼짐으로 바뀐다(POK-251 리뷰).
 */
export function resolveLiveStreamId(fresh = false): Promise<string | null> {
  if (fresh) cached = null;
  const fromPath = vodStreamIdFromPath();
  if (fromPath) return Promise.resolve(fromPath);
  const q = new URLSearchParams(window.location.search).get('stream');
  if (q) return Promise.resolve(q);
  if (cached) return cached;
  const mine = askLiveStreamId();
  cached = mine;
  // 모르는 답(null)은 기억하지 않는다 — 다음 물음이 다시 간다
  void mine.then((id) => {
    if (id === null && cached === mine) cached = null;
  });
  // 방송이 바뀔 수 있으니 30초만 기억한다. 그사이 새로 물었으면 새 답은 건드리지 않는다
  setTimeout(() => {
    if (cached === mine) cached = null;
  }, 30_000);
  return mine;
}
