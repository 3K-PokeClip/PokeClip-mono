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

/**
 * 그 방송의 녹화만 골라 시간축으로 만든다. 목록은 경로(스트림키) 전체라 앞뒤 방송 녹화도 섞여 온다 — **방송 시간
 * [시작, 종료]와 실제로 겹치는 구간만** 남긴다. 시작·종료 시각을 모르는 쪽은 열어 둔다. 남는 구간이 없으면 null
 *
 * 여유를 두지 않는 이유: 녹화기가 시작 편지보다 먼저 서고(수십 초) 종료 뒤에 닫히는 것은 그 구간이 방송 시간과 **겹쳐서**
 * 이미 들어온다. 여유를 두면 같은 스트림키로 그 여유 안에 이어 켠 앞뒤 방송 녹화가 섞여 다시보기가 다음 방송으로 이어지고
 * 0초가 앞 방송으로 밀린다(PR #205 codex, 앞 2분·뒤 10분 → 1분 → 0). 시각은 녹화와 같은 media 시계에서 온다.
 * 대가: 방송 시작 편지보다 **먼저 끝난** 아주 짧은 첫 구간(켜자마자 끊긴 송출)은 빠진다.
 */
export function recordingTimeline(
  spans: RecordingSpan[],
  startedAtMs: number | null,
  endedAtMs: number | null,
): RecordingTimeline | null {
  const from = startedAtMs ?? Number.NEGATIVE_INFINITY;
  const until = endedAtMs ?? Number.POSITIVE_INFINITY;
  const mine = spans
    .filter((span) => span.startMs + span.durationSeconds * 1000 > from && span.startMs < until)
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
 * 축의 0초를 다른 절대 시각으로 옮긴다 — 카드·조각 위치는 서버 기준점(`timelineOriginAt`, POK-255) 축이라 재생기도 그 축에
 * 서야 카드를 누른 자리와 영상이 맞는다. 녹화가 기준점보다 앞서 서면 첫 구간이 음수 초에서 시작한다(0초 앞은 못 간다)
 */
export function rebaseTimeline(timeline: RecordingTimeline, originMs: number): RecordingTimeline {
  const shift = (timeline.startMs - originMs) / 1000;
  const pieces = timeline.pieces.map((p) => ({
    fromSeconds: p.fromSeconds + shift,
    toSeconds: p.toSeconds + shift,
  }));
  return { startMs: originMs, durationSeconds: pieces[pieces.length - 1]!.toSeconds, pieces };
}

/**
 * 뒤로 갈 때의 자리. 틈 안이면 **앞 구간의 끝 1초 전**이다 — 다음 구간 시작으로 올리면 그 구간 시작에서 뒤로 가기를 아무리
 * 눌러도 제자리라 앞 구간에 영영 못 간다(PR #205 codex). 첫 구간 앞이면 첫 구간 시작
 */
export function playableBackFrom(
  timeline: Pick<RecordingTimeline, 'pieces'>,
  seconds: number,
): number {
  const before = timeline.pieces.filter((p) => p.fromSeconds <= seconds);
  const piece = before[before.length - 1];
  if (piece === undefined) return timeline.pieces[0]?.fromSeconds ?? 0;
  if (seconds < piece.toSeconds) return seconds;
  return Math.max(piece.fromSeconds, piece.toSeconds - 1);
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
