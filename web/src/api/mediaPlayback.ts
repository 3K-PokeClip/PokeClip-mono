'use client';

import { trackApiActivity } from './client';

// 녹화 재생 서버(MediaMTX playback, 로컬 시험용 9996) — 끝난 방송 다시보기와 편집기 미리보기가 쓴다.
//   GET {base}/playback/<키>/list?path=<키>                                       녹화된 구간들(시작 시각·길이)
//   GET {base}/playback/<키>/get?start=<RFC3339>&duration=<초>&format=mp4&path=<키>   그 구간의 영상 한 덩어리
// 🔴 주소는 출입증 쿠키 경로(/playback/<키>) 아래다(POK-317). 재생 서버가 읽는 path 쿼리는 웹이 경로의 키와 같은 값으로 하나 싣는다
//    (POK-200): CloudFront · ALB 는 쿼리를 그대로 넘긴다. 쿠키 정책은 쿼리를 못 막으므로, 남의 키를 실은 요청을 막는 것은 Media 상자
//    nginx 대조다(path 하나 · 경로 키와 같음일 때만 통과 — infra/media-host/nginx/nginx.conf.tmpl). 로컬은
//    app/media/playback 라우트가 들어온 path 를 버리고 경로의 키로 다시 채운다(이중 방어).
// 방송 중에도 된다(이미 닫힌 4초 조각까지). 서버 주소는 env에만 둔다(하드코딩 금지 규칙).
// 🔴 카드·조각의 위치(ms)는 「방송 시작 편지 시각」이 아니라 **녹화 첫 조각** 기준이다(조각 장부의 pts 축).
//    그래서 절대 시각으로 옮길 때의 기준점은 여기 list의 첫 구간 start다.

const BASE = (process.env.NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL ?? '').replace(/\/+$/, '');
const STREAM_ID_RE = /^[A-Za-z0-9_-]+$/;
/**
 * 녹화 목록을 이만큼 기다린다(답의 본문까지). 넘으면 묻지 못한 것(null)으로 본다: 재생 서버가 연결만 받고 답하지 않으면 영상 자리는
 * 끝없이 「확인하고 있어요」였다(POK-324 리뷰 2라운드). 10초였을 때는 느리게라도 답하는 서버를 늘 묻지 못한 것으로 봐 녹화를 못
 * 틀었다(PR #230): 지난 방송 채팅은 이 답을 다 기다리지 않는다(LiveScreen의 CHAT_WAIT_MS)
 */
const LIST_TIMEOUT_MS = 30_000;

export interface RecordingSpan {
  /** 녹화 시작(UTC epoch ms) */
  startMs: number;
  durationSeconds: number;
}

export function playbackConfigured(): boolean {
  return BASE !== '';
}

/**
 * 영상 요청에 출입증 쿠키를 실을지(POK-317). clip이 출입증을 실제로 내준 뒤에만 싣는다: CDN 뒤(prod)는 쿠키가 없으면 403이고,
 * 로컬·dev 영상 서버(MediaMTX)는 Allow-Origin * 로만 답해 쿠키를 실은 요청의 응답을 브라우저가 버린다(2026-10-23 로컬 실측).
 * 서명 재료가 없는 clip(로컬·dev)은 출입증을 안 내주므로 따로 맞출 설정이 없다
 */
let playbackCookiesIssued = false;

export function markPlaybackCookiesIssued(): void {
  playbackCookiesIssued = true;
}

/**
 * 녹화 목록이 403이면 부를 곳(PR #332 codex). 그 키의 쿠키가 브라우저에 없다는 뜻이다(만료 · 다른 탭이 쿠키 한도를 넘겨 지워짐 ·
 * 사용자가 지움). 출입증을 들고 있는 쪽(clipEditor)이 그 키를 버려 다음에 다시 받는다. 출입증 쪽이 이 파일을 불러오므로 거꾸로
 * 부르지 않고 알림을 건다
 */
let forbiddenListener: ((mediaKey: string) => void) | null = null;

export function onPlaybackForbidden(listener: (mediaKey: string) => void): void {
  forbiddenListener = listener;
}

/** fetch의 credentials 값. 출입증을 받기 전에는 fetch 기본값(same-origin) 그대로다 */
export function mediaCredentials(): RequestCredentials {
  return playbackCookiesIssued ? 'include' : 'same-origin';
}

/**
 * 녹화 시작 시각을 ms로 **올림**해 읽는다. 재생 서버는 마이크로초까지 주는데(`…16.372191Z`) Date.parse는 ms로 내린다 —
 * 내린 값으로 두 번째 구간부터 달라 하면 요청이 틈 안으로 0.2ms 들어가 404다(2026-09-30 로컬 실측, POK-253).
 */
export function parseSpanStartMs(iso: string): number {
  const ms = Date.parse(iso);
  const fraction = /\.(\d+)/.exec(iso)?.[1] ?? '';
  return /[1-9]/.test(fraction.slice(3)) ? ms + 1 : ms;
}

/**
 * 녹화 구간을 묻는다. 재생 서버가 없거나(주소 설정 없음) 묻지 못했으면(네트워크 오류·서버 오류) null이다: 녹화가 없다는 뜻이
 * 아니다. 녹화가 없으면 빈 배열이다. 재생 서버는 그 경로의 녹화 폴더가 없으면 400으로 답하고(2026-10-11 로컬 실측), 404도 같은
 * 뜻으로 받는다. LIST_TIMEOUT_MS 안에 답(본문까지)이 오지 않아도 묻지 못한 것이다
 */
export async function lookupRecordingSpans(streamId: string): Promise<RecordingSpan[] | null> {
  if (BASE === '') return null;
  if (!STREAM_ID_RE.test(streamId)) return [];
  // apiFetch 밖 요청이라 직접 알린다(본문까지): 지난 방송 목록이 이것을 기다리니 채널 전환 화면도 기다린다(PR #342 2차 리뷰)
  return trackApiActivity(async () => {
    try {
      const res = await fetch(playbackUrl(streamId, 'list'), {
        signal: AbortSignal.timeout(LIST_TIMEOUT_MS),
        credentials: mediaCredentials(),
      });
      if (res.status === 400 || res.status === 404) return [];
      if (res.status === 403) forbiddenListener?.(streamId);
      if (!res.ok) return null;
      const body = (await res.json()) as { start: string; duration: number }[];
      return body
        .map((span) => ({ startMs: parseSpanStartMs(span.start), durationSeconds: span.duration }))
        .filter((span) => Number.isFinite(span.startMs) && span.durationSeconds > 0);
    } catch {
      return null;
    }
  });
}

/** 녹화 구간. 재생 서버가 없거나 녹화가 없거나 묻지 못했으면 빈 배열이다(「있음」만 쓰는 목록·편집기) */
export async function fetchRecordingSpans(streamId: string): Promise<RecordingSpan[]> {
  return (await lookupRecordingSpans(streamId)) ?? [];
}

/** 그 구간의 mp4 주소. startMs 는 절대 시각(UTC epoch ms) */
export function recordingClipUrl(
  streamId: string,
  startMs: number,
  durationSeconds: number,
): string {
  return playbackUrl(streamId, 'get', {
    start: new Date(startMs).toISOString(),
    duration: durationSeconds.toFixed(3),
    format: 'mp4',
  });
}

/**
 * 출입증 쿠키 경로(POK-317) 아래 녹화 재생 주소. 경로의 키와 path 값을 같은 streamId 로 여기서만 만든다: 둘이 다르거나 path 가 둘이면
 * Media 상자 nginx 가 403 이다(POK-200)
 */
function playbackUrl(
  streamId: string,
  op: 'list' | 'get',
  query: { [name: string]: string } = {},
): string {
  const qs = new URLSearchParams({ ...query, path: streamId });
  return `${BASE}/playback/${encodeURIComponent(streamId)}/${op}?${qs}`;
}
