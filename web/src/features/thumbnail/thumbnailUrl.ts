// 사진 주소 고르기(POK-277). 서버는 목록을 줄 때마다 사진 주소를 새로 서명한다(60분짜리 S3 미리서명). 화면은 목록을 3~60초마다
// 다시 받으므로 그대로 쓰면 같은 사진을 그때마다 다시 받는다(서명이 바뀌면 브라우저 캐시가 안 맞는다). 그래서 같은 사진이면 보던
// 주소를 이어 쓰고, 일정 시간이 지나면 새 주소로 바꾼다. 라이브 사진은 서버가 같은 자리에 1분마다 덮어쓰므로 1분이 지나면 바꾼다.

/** 사진이 고정인 자리(카드·지난 방송·보관함). 주소 수명(60분)보다 짧아야 만료된 주소를 안 쥔다 */
export const STILL_REFRESH_MS = 50 * 60_000;

/** 라이브 사진. 서버가 1분마다 새 장면으로 덮는다 */
export const LIVE_REFRESH_MS = 60_000;

/** 미리서명 주소의 서명 시각(X-Amz-Date, 예: 20261004T101500Z). 모르면 null */
export function signedAtMs(url: string): number | null {
  try {
    const value = new URL(url).searchParams.get('X-Amz-Date');
    const m = value?.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/);
    if (!m) return null;
    const [, y, mo, d, h, mi, se] = m.map(Number) as [
      number,
      number,
      number,
      number,
      number,
      number,
      number,
    ];
    return Date.UTC(y, mo - 1, d, h, mi, se);
  } catch {
    return null;
  }
}

function pathOf(url: string): string | null {
  try {
    const u = new URL(url);
    return u.origin + u.pathname;
  } catch {
    return null;
  }
}

/** 보던 주소를 이어 쓸지 새 주소로 바꿀지. 같은 사진(같은 경로)이고 서명한 지 refreshMs가 안 지났으면 보던 것을 쓴다 */
export function pickThumbnailUrl(
  shown: string | null,
  incoming: string | null,
  now: number,
  refreshMs: number,
): string | null {
  if (!incoming) return null;
  if (!shown || shown === incoming) return incoming;
  const path = pathOf(shown);
  if (path === null || path !== pathOf(incoming)) return incoming;
  const signedAt = signedAtMs(shown);
  if (signedAt === null || now - signedAt >= refreshMs) return incoming;
  return shown;
}

/**
 * 카드 하나를 새 값으로 바꾸되 사진은 잃지 않는다. 통로(SSE)로 오는 카드는 사진 칸이 비어 오므로(서버가 통로에는 안 싣는다) 그대로
 * 덮으면 목록에서 받아 둔 사진이 집기·숨기기 때마다 사라졌다가 다음 목록에서 돌아온다.
 */
export function keepKnownThumbnail<T extends { thumbnailUrl?: string | null }>(
  prev: T | undefined,
  next: T,
): T {
  if (next.thumbnailUrl || !prev?.thumbnailUrl) return next;
  return { ...next, thumbnailUrl: prev.thumbnailUrl };
}
