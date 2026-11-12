'use client';

import { onSessionAdvance } from '@/stores/auth';
import { requestPlaybackAccess, type PlaybackAccessResult } from './clipEditor';
import { mediaCredentials, onPlaybackForbidden, playbackConfigured } from './mediaPlayback';

// 영상 출입증(POK-122 · POK-317)을 받아 두는 한 곳. 라이브 화면 · 편집기 · 다시보기가 모두 여기서 받는다.
// 받는 길이 둘이면(라이브 화면이 따로 받으면) 쿠키 셈과 403 처리가 한쪽만 보게 되어 고친 자리 옆에 구멍이 났다(PR #332 codex
// 1~3판). 그래서 받는 것 · 다시 쓰는 것 · 「쿠키가 지워졌다(403)」 처리가 전부 이 파일이다.

/** 받은 출입증을 만료 이만큼 전까지 다시 쓴다 */
const REUSE_MARGIN_MS = 5 * 60_000;
/** 만료를 모르는 출입증을 다시 쓰는 시간(운영 수명 60분보다 짧게) */
const DEFAULT_REUSE_MS = 50 * 60_000;

/**
 * 방송 번호 → 그 방송으로 받은 출입증(받는 중이면 끝날 때까지 다시 쓴다). 받을 때의 영상 키를 같이 둔다: 늦게 온 방송 편지로 영상 키가
 * 바뀌면 옛 키로 받은 쿠키는 새 주소에 안 실리므로 다시 받는다(PR #320 codex)
 */
const cache = new Map<
  string,
  { mediaKey: string; until: number; settled: boolean; result: Promise<PlaybackAccessResult> }
>();
/** 영상 키 → 그 키로 출입증을 받은 방송 번호. 쿠키 경로가 영상 키라 같은 키의 방송이면 어느 것으로 받아도 같다 */
const streamIdByMediaKey = new Map<string, string>();
/**
 * 「필요 없음」(503 playback_signing_unavailable)을 받은 방송. 방송 단위인 이유: 같은 사유 코드가 「서명 재료 없음」뿐 아니라 그 방송의
 * 영상 키가 정책에 못 들어갈 때(글자 검사 실패)도 와서, 한 방송의 답으로 다른 방송을 안 물으면 정상 방송의 쿠키까지 빠진다(PR #320 codex)
 */
const notNeeded = new Set<string>();
/** 보관 기간이 지난 방송(410, POK-335). 다시 받아도 같으므로 더 묻지 않는다 */
const expired = new Set<string>();
/** 쿠키가 지워져(403) 출입증을 버린 방송. 라이브 화면이 다시 받은 뒤 재생기를 새로 세울지 본다 */
const dropped = new Set<string>();

async function request(streamId: string): Promise<PlaybackAccessResult> {
  const result = await requestPlaybackAccess(streamId);
  if (result.kind === 'not_needed') notNeeded.add(streamId);
  if (result.kind === 'expired') expired.add(streamId);
  return result;
}

/** 받아 둔 것이 쓸 만하면 그것(reused), 아니면 새로 받는다. 받는 중이면 그 요청을 같이 기다린다 */
function obtain(
  streamId: string,
  mediaKey: string,
): { result: Promise<PlaybackAccessResult>; reused: boolean } {
  streamIdByMediaKey.set(mediaKey, streamId);
  const known = cache.get(streamId);
  if (known !== undefined && known.mediaKey === mediaKey && known.until > Date.now())
    return { result: known.result, reused: known.settled };
  const result = request(streamId);
  cache.set(streamId, { mediaKey, until: Number.POSITIVE_INFINITY, settled: false, result });
  void result.then((r) => {
    const until =
      r.kind === 'granted' ? (r.expiresAtMs ?? Date.now() + DEFAULT_REUSE_MS) - REUSE_MARGIN_MS : 0;
    // 받는 사이 다른 키로 다시 받기 시작했거나 버렸으면 그 줄을 덮지 않는다
    if (cache.get(streamId)?.result === result)
      cache.set(streamId, { mediaKey, until, settled: true, result });
  });
  return { result, reused: false };
}

/**
 * 녹화를 부르기 직전에 받아 둔다(편집기). 받아 둔 것이 쓸 만하거나 받을 필요가 없으면(녹화 재생 서버 주소가 없는 곳 포함) null이라
 * 부른 쪽이 기다리지 않고 바로 요청한다. 실패는 기억하지 않는다
 */
export function ensurePlaybackAccess(
  streamId: string,
  mediaKey: string,
): Promise<PlaybackAccessResult> | null {
  if (notNeeded.has(streamId) || expired.has(streamId) || !playbackConfigured()) {
    streamIdByMediaKey.set(mediaKey, streamId);
    return null;
  }
  const { result, reused } = obtain(streamId, mediaKey);
  return reused ? null : result;
}

/**
 * 보관 기간이 지나 서버가 410을 준 방송인가. 받아 둔 만료는 ensurePlaybackAccess가 null(묻지 않음)로 돌려줘 부른 쪽이 그것만으로는
 * 만료를 모른다: 같은 탭에서 다시 연 편집기가 녹화를 묻지 않게 이것으로 먼저 본다(PR #348 codex)
 */
export function isPlaybackExpired(streamId: string): boolean {
  return expired.has(streamId);
}

/**
 * 라이브 화면(라이브 · 끝난 방송 다시보기)의 출입증. 늘 답을 준다: 받아 둔 것이 쓸 만하면 그 답이고 요청은 없다. 라이브는 녹화 재생
 * 서버가 없어도 쿠키가 필요해 그 주소를 안 본다
 */
export function ensureLiveAccess(
  streamId: string,
  mediaKey: string,
): Promise<PlaybackAccessResult> {
  if (notNeeded.has(streamId)) return Promise.resolve({ kind: 'not_needed' });
  if (expired.has(streamId)) return Promise.resolve({ kind: 'expired' });
  return obtain(streamId, mediaKey).result;
}

/** 영상 키만 아는 자리(편집기 미리보기 · 다시보기)의 갱신. 그 키로 받은 방송이 없으면 묻지 않는다 */
export function ensureMediaAccess(mediaKey: string): Promise<PlaybackAccessResult> | null {
  const streamId = streamIdByMediaKey.get(mediaKey);
  return streamId === undefined ? null : ensurePlaybackAccess(streamId, mediaKey);
}

/**
 * 영상 요청(/list · /get · 라이브와 다시보기)이 403이면 그 키의 쿠키가 브라우저에 없다(다른 탭 · 쿠키 한도 · 사용자가 지움).
 * 받아 둔 출입증을 버려 다음에 다시 받는다. 받는 중인 것은 버리지 않는다(곧 새 쿠키가 온다). 어느 쪽이든 403을 들었다고
 * 적어 둔다: 라이브 화면은 그 표시를 보고 403에 멎은 재생기를 새로 세운다. 받는 중이라고 안 적으면 새 쿠키가 와도 멎은 채였다
 * (POK-337 로컬 리뷰)
 */
export function reportPlaybackForbidden(mediaKey: string): void {
  for (const [streamId, entry] of cache)
    if (entry.mediaKey === mediaKey) {
      dropped.add(streamId);
      if (!entry.settled) continue;
      cache.delete(streamId);
    }
}
onPlaybackForbidden(reportPlaybackForbidden);
onSessionAdvance(forgetPlaybackAccess);

/** 그 방송의 출입증을 403으로 버린 적이 있나(한 번 읽으면 지운다). 라이브 화면이 다시 받은 뒤 재생기를 새로 세울지 정한다 */
export function takePlaybackDrop(streamId: string): boolean {
  return dropped.delete(streamId);
}

/**
 * &lt;video&gt;는 응답 코드를 못 읽으므로 같은 주소의 첫 바이트를 물어 403인지 본다. 묻지 못했으면(네트워크 오류) 403이 아니다
 */
export async function isPlaybackForbidden(url: string): Promise<boolean> {
  try {
    const res = await fetch(url, {
      credentials: mediaCredentials(),
      headers: { range: 'bytes=0-0' },
    });
    void res.body?.cancel();
    return res.status === 403;
  } catch {
    return false;
  }
}

/**
 * 다시보기 &lt;video&gt;가 오류를 냈을 때: 403인지 물어(isPlaybackForbidden) 403이면 출입증을 버리고 다시 받은 뒤 true(부른 쪽이 그
 * 주소를 다시 붙인다). 403이 아니면(틈 · 서버 오류) false
 */
export async function recoverForbiddenRecording(url: string, mediaKey: string): Promise<boolean> {
  if (!(await isPlaybackForbidden(url))) return false;
  reportPlaybackForbidden(mediaKey);
  await ensureMediaAccess(mediaKey);
  return true;
}

/**
 * 받아 둔 것을 다 비운다: 계정이 바뀌면 앞 계정이 받은 출입증을 다음 계정 몫으로 세지 않는다. 화면 시험은 모듈을 새로 읽지 않아 시험
 * 사이에도 부른다. 쿠키 표시(mediaPlayback의 실음 여부)는 그대로 둔다
 */
export function forgetPlaybackAccess(): void {
  cache.clear();
  streamIdByMediaKey.clear();
  notNeeded.clear();
  expired.clear();
  dropped.clear();
}
