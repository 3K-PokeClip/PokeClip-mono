import { mediaCredentials } from '@/api/mediaPlayback';
import type { DatedFragment } from './dvrWindow';
import { liveStartFloorMs } from './playbackEdge';

// 라이브 창 탐침(설계 r9 2절, POK-200 c13). 되감기에서 라이브로 넘기기 전에 라이브 목록을 한 번 받아 시작 하한 S_live 를 잰다.
// 하한이 넘길 위치 P 보다 뒤면 못 본 녹화가 잘리므로 넘기지 않는다. 하한은 재생기의 첫 목록 재검사와 같은 정의(liveStartFloorMs)다.
// 탐침도 CloudFront 를 거쳐 만료 사본을 받을 수 있다. 그때의 안전은 넘김 확인의 H2 · H0 이 진다.

/** 탐침 결과. floorMs = S_live, endMs = 라이브 목록 끝(로그의 d 몫) */
export type LiveProbeResult =
  { kind: 'ok'; floorMs: number; endMs: number } | { kind: 'unavailable' };

/** 받기 시한 — 다변 목록과 변형 목록을 합쳐 이 안에 끝나야 한다 */
export const PROBE_TIMEOUT_MS = 2_000;

export interface MediaPlaylist {
  fragments: DatedFragment[];
  targetDurationS: number;
  endlist: boolean;
}

/** 다변 목록이면 첫 EXT-X-STREAM-INF 다음 URI(풀지 않은 그대로). 매체 목록이면 null */
export function firstVariantUri(text: string): string | null {
  const lines = text.split(/\r?\n/);
  const at = lines.findIndex((line) => line.startsWith('#EXT-X-STREAM-INF:'));
  if (at < 0) return null;
  const uri = lines.slice(at + 1).find((line) => line.trim() !== '' && !line.startsWith('#'));
  return uri?.trim() ?? null;
}

/**
 * 매체 목록 → 완성 조각(EXTINF 누적 · PDT) · TARGETDURATION · ENDLIST. EXT-X-PART · PRELOAD-HINT 는 세지 않는다(hls.js 의
 * fragments 도 완성 조각만이다). EXT-X-GAP 조각은 센다. 형식 오류는 null
 */
export function parseMediaPlaylist(text: string): MediaPlaylist | null {
  const lines = text.split(/\r?\n/);
  if (lines[0]?.trim() !== '#EXTM3U') return null;
  let targetDurationS: number | null = null;
  let endlist = false;
  let pendingPdt: number | null = null;
  let pendingDuration: number | null = null;
  const fragments: DatedFragment[] = [];
  for (const raw of lines) {
    const line = raw.trim();
    if (line.startsWith('#EXT-X-TARGETDURATION:')) {
      targetDurationS = Number(line.slice('#EXT-X-TARGETDURATION:'.length));
    } else if (line.startsWith('#EXT-X-PROGRAM-DATE-TIME:')) {
      pendingPdt = Date.parse(line.slice('#EXT-X-PROGRAM-DATE-TIME:'.length));
      if (Number.isNaN(pendingPdt)) return null;
    } else if (line.startsWith('#EXTINF:')) {
      pendingDuration = Number(line.slice('#EXTINF:'.length).split(',')[0]);
      if (!Number.isFinite(pendingDuration) || pendingDuration <= 0) return null;
    } else if (line === '#EXT-X-ENDLIST') {
      endlist = true;
    } else if (line !== '' && !line.startsWith('#') && pendingDuration !== null) {
      fragments.push(nextFragment(fragments, pendingDuration, pendingPdt));
      pendingDuration = null;
      pendingPdt = null;
    }
  }
  if (targetDurationS === null || !Number.isFinite(targetDurationS) || targetDurationS <= 0) {
    return null;
  }
  return { fragments: backfillProgramDateTimes(fragments), targetDurationS, endlist };
}

/** PDT 줄이 없는 조각은 앞 조각의 끝 시각을 잇는다(hls.js assignProgramDateTime 과 같다) */
function nextFragment(
  fragments: readonly DatedFragment[],
  duration: number,
  pdt: number | null,
): DatedFragment {
  const prev = fragments[fragments.length - 1];
  const start = prev === undefined ? 0 : prev.start + prev.duration;
  const carried =
    prev?.programDateTime == null ? null : prev.programDateTime + prev.duration * 1000;
  return { start, duration, programDateTime: pdt ?? carried };
}

/**
 * 첫 PDT 앞의 조각은 거꾸로 길이를 빼 채운다(hls.js backfillProgramDateTimes 와 같다). MediaMTX LL-HLS 는 끝 두 조각에만 PDT 를
 * 단다(2026-10-27 로컬 실측) — 채우지 않으면 첫 조각의 시각이 없어 하한을 못 잰다
 */
function backfillProgramDateTimes(fragments: readonly DatedFragment[]): DatedFragment[] {
  const firstDated = fragments.findIndex((f) => f.programDateTime !== null);
  if (firstDated <= 0) return [...fragments];
  const filled = [...fragments];
  for (let i = firstDated - 1; i >= 0; i -= 1) {
    const frag = filled[i]!;
    filled[i] = {
      ...frag,
      programDateTime: filled[i + 1]!.programDateTime! - frag.duration * 1000,
    };
  }
  return filled;
}

/** 목록 하나를 받는다. 200 이 아니면 null. 302 를 따라간 뒤의 주소(response.url)를 함께 낸다 */
async function fetchPlaylist(
  url: string,
  signal: AbortSignal,
): Promise<{ text: string; url: string } | null> {
  const response = await fetch(url, { credentials: mediaCredentials(), signal });
  if (response.status !== 200) return null;
  return { text: await response.text(), url: response.url || url };
}

async function measure(liveUrl: string, signal: AbortSignal): Promise<LiveProbeResult> {
  const unavailable: LiveProbeResult = { kind: 'unavailable' };
  const top = await fetchPlaylist(liveUrl, signal);
  if (top === null) return unavailable;
  const variant = firstVariantUri(top.text);
  // index.m3u8 의 302(세션 파라미터)는 fetch 가 따라간다 — 상대 URI 는 따라간 뒤의 주소로 푼다(useHlsPlayback 과 같은 전제)
  const media =
    variant === null ? top : await fetchPlaylist(new URL(variant, top.url).href, signal);
  if (media === null) return unavailable;
  const parsed = parseMediaPlaylist(media.text);
  if (parsed === null || parsed.endlist) return unavailable;
  const floorMs = liveStartFloorMs(parsed.fragments, parsed.targetDurationS);
  const last = parsed.fragments[parsed.fragments.length - 1];
  if (floorMs === null || last?.programDateTime == null) return unavailable;
  return { kind: 'ok', floorMs, endMs: last.programDateTime + last.duration * 1000 };
}

/**
 * 라이브 목록의 시작 하한을 잰다. 받기는 hls.js 와 같은 자격 규칙(mediaCredentials)을 쓴다. 시한 PROBE_TIMEOUT_MS · 403 ·
 * 404 · 그 밖의 비 200 · 망 오류 · ENDLIST · PDT 없음 · 형식 오류는 unavailable 이다. 부르는 쪽이 signal 로 끊으면 결과 없이
 * AbortError 를 던진다 — 세션이 바뀐 탐침은 부르는 쪽이 버린다
 */
export async function probeLiveWindow(
  liveUrl: string,
  signal: AbortSignal,
): Promise<LiveProbeResult> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), PROBE_TIMEOUT_MS);
  const onAbort = () => timeout.abort();
  signal.addEventListener('abort', onAbort);
  try {
    return await measure(liveUrl, timeout.signal);
  } catch (e) {
    if (signal.aborted) throw e;
    return { kind: 'unavailable' };
  } finally {
    clearTimeout(timer);
    signal.removeEventListener('abort', onAbort);
  }
}
