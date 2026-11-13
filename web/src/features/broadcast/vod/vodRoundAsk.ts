import { mediaStreamId, type BroadcastRow } from '@/api/clipEditor';
import { trackApiActivity } from '@/api/client';
import { fetchRewindStatus, roundRewindUrl, type RewindStatusResult } from '@/api/rewindStatus';
import { REWIND_REQUEST_TIMEOUT_MS } from '@/features/player/useRewindStatus';
import { ddayFor, isJustEnded } from './vodListView';
import { isRowVodSettled, pickRoundAsks, type RowVodMemory } from './vodRoundBadge';

// 끝난 방송의 회차 경로 묻기(POK-568) — 지난 방송 목록(useVodListMockState)과 홈 지난 방송 격자(useHomeMockState)가 같은
// 판정 · 상한으로 묻게 한 곳에 둔다. 답을 표지로 접는 규칙은 vodRoundBadge 다.

/**
 * 한 주기에 회차 경로를 묻는 오래된 미결 방송(끝난 지 한 시간이 지났고 표지가 아직 안 굳은 것) 수의 상한(POK-568 · kty X2).
 * 처음 보는 방송과 방금 끝난 방송은 이 상한 밖이라 다 묻는다 — 카드 수 다시 세기와 같은 꼴이다
 */
const ROUND_ASKS_PER_POLL = 20;
/** 방송마다 묻는 읽기의 동시 수. 방송이 많은 계정이 처음 들어올 때 전체 이력을 한꺼번에 묻지 않게 한다 */
export const ROW_READ_PARALLEL = 6;

/** 회차 경로를 물을지 가르는 데 쓰는 방송 줄의 칸 */
export type RoundAskRow = Pick<
  BroadcastRow,
  'streamId' | 'status' | 'endedAt' | 'vodExpiresAt' | 'ingestStreamId'
>;

export function isoMs(iso: string | null): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

/**
 * 회차 경로를 한 번 묻는다(POK-568). REWIND_REQUEST_TIMEOUT_MS 안에 답이 없으면 끊고 「잠시 불가」다 — 화면 폴링
 * (useRewindStatus)과 같은 시한이다. 쿼리가 끊기면(내려감 · 채널 전환) 요청도 끊고 던진다: react-query 가 그 주기를 버린다.
 * apiFetch 밖 요청이라 직접 알린다: 목록이 이 답을 기다리니 채널 전환 화면도 기다린다
 */
export async function askRound(
  url: string,
  controlBase: string,
  signal: AbortSignal,
): Promise<RewindStatusResult> {
  const request = new AbortController();
  let timedOut = false;
  const deadline = window.setTimeout(() => {
    timedOut = true;
    request.abort();
  }, REWIND_REQUEST_TIMEOUT_MS);
  const onAbort = () => request.abort();
  signal.addEventListener('abort', onAbort);
  if (signal.aborted) request.abort();
  try {
    return await trackApiActivity(() => fetchRewindStatus(url, controlBase, request.signal));
  } catch (e) {
    // fetchRewindStatus 는 끊긴 요청만 던진다. 시한이 끊었으면 「잠시 불가」, 쿼리가 끊었으면 그대로 던진다
    if (timedOut && !signal.aborted) return { kind: 'unavailable' };
    throw e;
  } finally {
    window.clearTimeout(deadline);
    signal.removeEventListener('abort', onAbort);
  }
}

/**
 * 이번 주기에 회차 경로를 물을 방송과 그 경로(POK-568). 물을 수 있는 방송은 ended · 보관 만료 아님 · 표지가 아직 안 굳음
 * (isRowVodSettled) · 경로를 만들 수 있음(제어면 주소 · 키 꼴)이고, 그중 pickRoundAsks 가 고른 것만 묻는다. 제어면 주소가
 * 없으면 비어 표지는 「VOD 준비 중」에 머문다
 */
export function pickRoundUrls(
  all: readonly RoundAskRow[],
  vodOf: (streamId: string) => RowVodMemory,
  controlBase: string | null,
  now: Date,
): Map<string, string> {
  const urls = new Map<string, string>();
  for (const b of all) {
    if (b.status !== 'ended' || isRowVodSettled(vodOf(b.streamId))) continue;
    if (ddayFor(b.vodExpiresAt, now).kind === 'expired') continue;
    const url = roundRewindUrl(controlBase, mediaStreamId(b), b.streamId);
    if (url !== null) urls.set(b.streamId, url);
  }
  const asks = pickRoundAsks(
    all
      .filter((b) => urls.has(b.streamId))
      .map((b) => ({
        streamId: b.streamId,
        justEnded: isJustEnded(b.endedAt, now),
        askedAt: vodOf(b.streamId).askedAt,
      })),
    ROUND_ASKS_PER_POLL,
  );
  return new Map([...urls].filter(([streamId]) => asks.has(streamId)));
}
