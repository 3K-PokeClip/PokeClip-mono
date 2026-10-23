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

export function measureLevels(
  tracks: readonly (readonly TrackPiece[])[],
  fromSeconds: number,
  toSeconds: number,
  stepSeconds = LEVEL_STEP_SECONDS,
): TrackLevels {
  const count = Math.max(0, Math.ceil((toSeconds - fromSeconds) / stepSeconds - 1e-9));
  const measured = tracks.map((pieces) => {
    const out = new Float32Array(count);
    for (const { startSeconds, buffer } of pieces) {
      const channels = Array.from({ length: buffer.numberOfChannels }, (_, c) =>
        buffer.getChannelData(c),
      );
      const rate = buffer.sampleRate;
      for (let i = 0; i < count; i++) {
        const from = Math.max(0, Math.round((fromSeconds + i * stepSeconds - startSeconds) * rate));
        const to = Math.min(
          buffer.length ?? channels[0]!.length,
          Math.round((fromSeconds + (i + 1) * stepSeconds - startSeconds) * rate),
        );
        if (to <= from) continue;
        // 칸이 짧으면(시험 등) 건너뛰지 않는다
        const stride = to - from > SAMPLE_STRIDE * 4 ? SAMPLE_STRIDE : 1;
        let peak = out[i]!;
        for (const data of channels) {
          for (let s = from; s < to; s += stride) {
            const v = Math.abs(data[s]!);
            if (v > peak) peak = v;
          }
        }
        out[i] = peak;
      }
    }
    return out;
  });
  return { startSeconds: fromSeconds, stepSeconds, tracks: measured };
}

/** 소리 크기(0~1)와 음량 비율(1 = 100%) → 띠 높이(0~1). 데시벨로 펴서 작은 소리도 보이게 한다 */
export function levelToHeight(level: number, gain: number): number {
  const amplitude = level * gain;
  if (amplitude <= 0) return 0;
  const db = 20 * Math.log10(amplitude);
  return Math.min(1, Math.max(0, (db - FLOOR_DB) / -FLOOR_DB));
}
