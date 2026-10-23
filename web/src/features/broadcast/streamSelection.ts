'use client';

import { ApiError, apiFetch } from '@/api/client';
import { notifyChannelGone } from '@/stores/channel';

// 지금 볼 방송 번호를 정한다(POK-251).
// 1) 지난 방송 상세 경로  2) 주소의 ?stream=  3) clip의 「방송 중」 목록의 첫 줄. 없으면 빈 문자열(방송 꺼짐).
// 1)·2)는 화면 훅(useLiveStreamSelection)이 Next 주소 값으로 읽고, 여기서는 3)만 묻는다(POK-254)
//
// 답은 채널마다 따로 기억한다(POK-390): 하나로 두면 채널을 바꾼 뒤 30초 동안 앞 채널의 방송이 뜬다.

/** 열쇠는 채널 번호, 섞어서 물은 답은 'all' */
const cached = new Map<string, Promise<string | null>>();

/**
 * 지난 방송 상세 경로면 그 방송 번호(라이브 화면과 같은 컴포넌트를 쓰기 위한 분기다). Next의 usePathname 값을 넘긴다: 다른
 * 화면에서 넘어온 직후 첫 그리기의 window.location은 아직 옛 주소다
 */
export function vodStreamIdFromPathname(pathname: string): string | null {
  const m = pathname.match(/^\/broadcast\/vod\/([^/]+)/);
  return m?.[1] ? decodeURIComponent(m[1]) : null;
}

/** 기억해 둔 답을 전부 버린다: 시험 사이에 앞 시험의 방송이 남지 않게 */
export function forgetLiveStreamId() {
  cached.clear();
}

async function askLiveStreamId(streamerId: number | null): Promise<string | null> {
  try {
    const qs = new URLSearchParams({ state: 'live', limit: '1' });
    if (streamerId !== null) qs.set('streamerId', String(streamerId));
    const r = await apiFetch(`/api/clip/broadcasts?${qs}`);
    const j = (await r.json()) as { broadcasts: { streamId: string }[] };
    return j.broadcasts[0]?.streamId ?? '';
  } catch (e) {
    // 「모른다」로 접기 전에 끊김 신호부터: 접고 나면 화면은 404를 영영 모른다(api/clipEditor의 목록 함수와 같은 판정)
    if (streamerId !== null && e instanceof ApiError && e.status === 404)
      notifyChannelGone(streamerId);
    return null;
  }
}

/**
 * 지금 방송 중인 방송 번호. 빈 문자열은 「방송 중인 것이 없다」, null은 「지금은 모른다」(서버·네트워크 오류)다.
 * 오류를 빈 문자열로 접으면 방송 중에 clip이 잠깐 재시작해도 화면이 꺼짐으로 바뀐다(POK-251 리뷰).
 * 주소(지난 방송 경로·?stream=)는 보지 않는다: 화면 훅이 Next 주소 값으로 먼저 본다(주소창은 넘어온 직후 옛 주소다, POK-254)
 */
/**
 * @param streamerId 고른 채널(POK-390). null이면 섞어서 묻는다
 * @param fresh 그 채널의 기억해 둔 답을 버리고 clip에 다시 묻는다: 방송이 끝났는지 확인할 때
 */
export function resolveLiveStreamId(
  streamerId: number | null,
  fresh = false,
): Promise<string | null> {
  const key = streamerId === null ? 'all' : String(streamerId);
  if (fresh) cached.delete(key);
  const known = cached.get(key);
  if (known) return known;
  const mine = askLiveStreamId(streamerId);
  cached.set(key, mine);
  // 모르는 답(null)은 기억하지 않는다 — 다음 물음이 다시 간다
  void mine.then((id) => {
    if (id === null && cached.get(key) === mine) cached.delete(key);
  });
  // 방송이 바뀔 수 있으니 30초만 기억한다. 그사이 새로 물었으면 새 답은 건드리지 않는다
  setTimeout(() => {
    if (cached.get(key) === mine) cached.delete(key);
  }, 30_000);
  return mine;
}
