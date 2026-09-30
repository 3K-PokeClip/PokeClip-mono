'use client';

// 녹화 재생 서버(MediaMTX playback, 로컬 시험용 9996) — 끝난 방송 다시보기와 편집기 미리보기가 쓴다.
//   GET {base}/list?path=<방송>                                   녹화된 구간들(시작 시각·길이)
//   GET {base}/get?path=<방송>&start=<RFC3339>&duration=<초>&format=mp4   그 구간의 영상 한 덩어리
// 방송 중에도 된다(이미 닫힌 4초 조각까지). 서버 주소는 env에만 둔다(하드코딩 금지 규칙).
// 🔴 카드·조각의 위치(ms)는 「방송 시작 편지 시각」이 아니라 **녹화 첫 조각** 기준이다(조각 장부의 pts 축).
//    그래서 절대 시각으로 옮길 때의 기준점은 여기 list의 첫 구간 start다.

const BASE = (process.env.NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL ?? '').replace(/\/+$/, '');
const STREAM_ID_RE = /^[A-Za-z0-9_-]+$/;

export interface RecordingSpan {
  /** 녹화 시작(UTC epoch ms) */
  startMs: number;
  durationSeconds: number;
}

export function playbackConfigured(): boolean {
  return BASE !== '';
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

/** 녹화 구간. 재생 서버가 없거나 녹화가 없으면 빈 배열 */
export async function fetchRecordingSpans(streamId: string): Promise<RecordingSpan[]> {
  if (BASE === '' || !STREAM_ID_RE.test(streamId)) return [];
  try {
    const res = await fetch(`${BASE}/list?path=${encodeURIComponent(streamId)}`);
    if (!res.ok) return [];
    const body = (await res.json()) as { start: string; duration: number }[];
    return body
      .map((span) => ({ startMs: parseSpanStartMs(span.start), durationSeconds: span.duration }))
      .filter((span) => Number.isFinite(span.startMs) && span.durationSeconds > 0);
  } catch {
    return [];
  }
}

/** 그 구간의 mp4 주소. startMs 는 절대 시각(UTC epoch ms) */
export function recordingClipUrl(
  streamId: string,
  startMs: number,
  durationSeconds: number,
): string {
  const qs = new URLSearchParams({
    path: streamId,
    start: new Date(startMs).toISOString(),
    duration: durationSeconds.toFixed(3),
    format: 'mp4',
  });
  return `${BASE}/get?${qs}`;
}
