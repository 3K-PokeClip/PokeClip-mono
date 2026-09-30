import type { RecordingSpan } from '@/api/mediaPlayback';

// 녹화 구간 여럿을 한 시간축에 놓는다(POK-253). 송출이 끊기거나 녹화기가 다시 서면 한 방송의 녹화가 구간 여럿이 된다.
// 🔴 재생 서버는 틈을 건너 주지 않는다(2026-09-30 로컬 실측): 틈을 건너는 요청은 틈 앞에서 끝나고(40초를 달라 했는데
// 15.9초), 틈 안에서 시작하는 요청은 404다. 그래서 한 번에 한 구간만 달라 하고, 끝나면 다음 구간으로 넘어간다.
//
// 시간축은 첫 구간 시작부터 마지막 구간 끝까지의 벽시계 초다 — 틈도 축에 남긴다. 카드 시점이 녹화 첫 조각 기준이라
// 틈을 접으면 틈 뒤 카드가 전부 틈 길이만큼 어긋난다.

export interface RecordingPiece {
  /** 첫 구간 시작 기준 초 */
  fromSeconds: number;
  toSeconds: number;
}

export interface RecordingTimeline {
  /** 첫 구간 시작(UTC epoch ms) — 축의 0초 */
  startMs: number;
  /** 첫 구간 시작부터 마지막 구간 끝까지(틈 포함) */
  durationSeconds: number;
  /** 시간순, 겹치지 않는다 */
  pieces: RecordingPiece[];
}

/** 방송 앞뒤로 녹화를 얼마나 넉넉히 받아 줄까 — 녹화기는 시작 편지보다 먼저 서고 종료 편지보다 늦게 닫힌다 */
const BEFORE_START_MS = 2 * 60_000;
const AFTER_END_MS = 10 * 60_000;

/**
 * 그 방송의 녹화만 골라 시간축으로 만든다. 목록은 경로(스트림키) 전체라 앞 방송 녹화도 섞여 온다 — 방송 시각으로 거른다.
 * 시작 시각을 모르면 거르지 않는다. 남는 구간이 없으면 null
 */
export function recordingTimeline(
  spans: RecordingSpan[],
  startedAtMs: number | null,
  endedAtMs: number | null,
): RecordingTimeline | null {
  const from = startedAtMs === null ? Number.NEGATIVE_INFINITY : startedAtMs - BEFORE_START_MS;
  const until = endedAtMs === null ? Number.POSITIVE_INFINITY : endedAtMs + AFTER_END_MS;
  const mine = spans
    .filter((span) => span.startMs + span.durationSeconds * 1000 >= from && span.startMs <= until)
    .sort((a, b) => a.startMs - b.startMs);
  const first = mine[0];
  if (first === undefined) return null;
  const pieces: RecordingPiece[] = [];
  for (const span of mine) {
    const fromSeconds = (span.startMs - first.startMs) / 1000;
    const toSeconds = fromSeconds + span.durationSeconds;
    const last = pieces[pieces.length - 1];
    // 겹치거나 맞닿은 구간은 하나로 — 재생 서버도 1초 안쪽 틈은 이어 준다
    if (last !== undefined && fromSeconds <= last.toSeconds) {
      last.toSeconds = Math.max(last.toSeconds, toSeconds);
    } else {
      pieces.push({ fromSeconds, toSeconds });
    }
  }
  return {
    startMs: first.startMs,
    durationSeconds: pieces[pieces.length - 1]!.toSeconds,
    pieces,
  };
}

/**
 * 그 자리에서 틀 수 있는 한 덩어리. 틈 안이면 다음 구간 시작부터다. 녹화 끝을 지났으면 null
 */
export function playableFrom(
  timeline: Pick<RecordingTimeline, 'pieces'>,
  seconds: number,
): RecordingPiece | null {
  const piece = timeline.pieces.find((p) => p.toSeconds > seconds);
  if (piece === undefined) return null;
  return { fromSeconds: Math.max(seconds, piece.fromSeconds), toSeconds: piece.toSeconds };
}
