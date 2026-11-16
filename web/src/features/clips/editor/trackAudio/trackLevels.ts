import type { TrackPiece } from './loadTrackAudio';

// 타임라인 소리 줄의 파형(POK-389). 미리 듣기로 풀어 둔 트랙별 소리에서 일정 간격마다 가장 큰 표본을 재 둔다.
// 화면은 이 값을 보이는 창에 맞춰 그리기만 한다(확대·이동에 다시 재지 않는다).

export interface TrackLevels {
  /** 첫 칸의 시각(편집기 초) */
  startSeconds: number;
  stepSeconds: number;
  /** 트랙 번호 순. 칸마다 0~1 */
  tracks: Float32Array[];
}

/** 파형 한 칸의 길이. 3분 20초면 4,000칸이라 그리기에 가볍고, 말소리 끊김이 보일 만큼 촘촘하다 */
export const LEVEL_STEP_SECONDS = 0.05;
/** 한 칸 안에서 이만큼 건너뛰며 잰다(24kHz면 칸당 1,200표본 → 150번). 최댓값이라 조금 놓쳐도 모양은 같다 */
const SAMPLE_STRIDE = 8;
/** 이보다 작은 소리는 평평하게 그린다 */
const FLOOR_DB = -60;

/** 일정 간격(stepSeconds) 칸으로 [fromSeconds, toSeconds)를 잰다 — 칸 경계를 고르게 두고 measureCells 로 잰다 */
export function measureLevels(
  tracks: readonly (readonly TrackPiece[])[],
  fromSeconds: number,
  toSeconds: number,
  stepSeconds = LEVEL_STEP_SECONDS,
): TrackLevels {
  const count = Math.max(0, Math.ceil((toSeconds - fromSeconds) / stepSeconds - 1e-9));
  const edges = Array.from({ length: count + 1 }, (_, i) => fromSeconds + i * stepSeconds);
  return { startSeconds: fromSeconds, stepSeconds, tracks: measureCells(tracks, edges) };
}

/**
 * 칸마다 정해 준 소리 시각 구간 [edges[i], edges[i + 1])에서 가장 큰 표본(트랙 번호 순 · 칸 수 = edges 길이 − 1). 길이가 0
 * 이하인 칸은 빈 칸(0)이다. 덩어리는 시작 순이고 서로 겹치지 않는다(loadTrackAudio) — 칸마다 덩어리를 이분 탐색으로 찾아,
 * 3분 20초(4,000칸) × 조각 50개도 칸마다 모든 덩어리를 훑지 않는다
 */
export function measureCells(
  tracks: readonly (readonly TrackPiece[])[],
  edges: readonly number[],
): Float32Array[] {
  const count = Math.max(0, edges.length - 1);
  return tracks.map((pieces) => {
    const out = new Float32Array(count);
    const channels = pieces.map(({ buffer }) =>
      Array.from({ length: buffer.numberOfChannels }, (_, c) => buffer.getChannelData(c)),
    );
    for (let i = 0; i < count; i++) {
      const from = edges[i]!;
      const to = edges[i + 1]!;
      if (to <= from) continue;
      // 칸 끝 앞에서 시작한 마지막 덩어리부터 거꾸로 — 칸 시작 앞에서 끝난 덩어리를 만나면 그 앞도 모두 그렇다
      for (let k = lastStartBefore(pieces, to); k >= 0; k--) {
        const piece = pieces[k]!;
        const data = channels[k]!;
        const length = piece.buffer.length ?? data[0]?.length ?? 0;
        if (piece.startSeconds + length / piece.buffer.sampleRate <= from) break;
        out[i] = Math.max(out[i]!, peakIn(piece, data, length, from, to));
      }
    }
    return out;
  });
}

/** 시작이 seconds 앞인 마지막 덩어리의 자리. 없으면 −1 */
function lastStartBefore(pieces: readonly TrackPiece[], seconds: number): number {
  let lo = 0;
  let hi = pieces.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (pieces[mid]!.startSeconds < seconds) lo = mid + 1;
    else hi = mid;
  }
  return lo - 1;
}

/** 덩어리 하나에서 소리 시각 [from, to)의 가장 큰 표본 크기 */
function peakIn(
  piece: TrackPiece,
  channels: readonly Float32Array[],
  length: number,
  from: number,
  to: number,
): number {
  const rate = piece.buffer.sampleRate;
  const first = Math.max(0, Math.round((from - piece.startSeconds) * rate));
  const last = Math.min(length, Math.round((to - piece.startSeconds) * rate));
  if (last <= first) return 0;
  // 칸이 짧으면(시험 등) 건너뛰지 않는다
  const stride = last - first > SAMPLE_STRIDE * 4 ? SAMPLE_STRIDE : 1;
  let peak = 0;
  for (const data of channels) {
    for (let s = first; s < last; s += stride) {
      const v = Math.abs(data[s]!);
      if (v > peak) peak = v;
    }
  }
  return peak;
}

/** 소리 크기(0~1)와 음량 비율(1 = 100%) → 띠 높이(0~1). 데시벨로 펴서 작은 소리도 보이게 한다 */
export function levelToHeight(level: number, gain: number): number {
  const amplitude = level * gain;
  if (amplitude <= 0) return 0;
  const db = 20 * Math.log10(amplitude);
  return Math.min(1, Math.max(0, (db - FLOOR_DB) / -FLOOR_DB));
}
