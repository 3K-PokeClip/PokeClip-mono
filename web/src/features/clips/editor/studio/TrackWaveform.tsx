import { useMemo } from 'react';
import { levelToHeight } from '../trackAudio/trackLevels';
import styles from './StudioScreen.module.css';

// 소리 줄의 실제 파형(POK-389). 미리 듣기로 풀어 둔 트랙 소리의 크기를 칸마다 위아래 같은 높이로 그린다(캡컷 아래쪽 파형 꼴).
// 좌표는 편집기 초 그대로 두고 viewBox로 보이는 창만 잘라 본다: 확대·이동마다 다시 계산하지 않는다.

/** 무음도 줄이 보이게 남기는 가장 낮은 반높이 */
const MIN_HALF = 0.01;

const round = (value: number, digits: number) => +value.toFixed(digits);

/** 칸마다의 소리 크기 → 가운데(0.5) 기준 위아래 띠 한 덩어리 */
export function wavePath(
  levels: Float32Array,
  startSeconds: number,
  stepSeconds: number,
  gain: number,
): string {
  const n = levels.length;
  if (n === 0) return '';
  const half = Array.from(levels, (level) => Math.max(MIN_HALF, levelToHeight(level, gain) / 2));
  const x = (i: number) => round(startSeconds + i * stepSeconds, 3);
  const top: string[] = [];
  const bottom: string[] = [];
  for (let i = 0; i <= n; i++) {
    const h = half[Math.min(i, n - 1)]!;
    top.push(`${x(i)},${round(0.5 - h, 2)}`);
    bottom.push(`${x(i)},${round(0.5 + h, 2)}`);
  }
  return `M${top.join(' L')} L${bottom.reverse().join(' L')} Z`;
}

export function TrackWaveform({
  levels,
  startSeconds,
  stepSeconds,
  gain,
  view,
}: {
  levels: Float32Array;
  startSeconds: number;
  stepSeconds: number;
  /** 음량 비율(1 = 100%). 끈 트랙은 0 */
  gain: number;
  view: { startSeconds: number; endSeconds: number };
}) {
  const d = useMemo(
    () => wavePath(levels, startSeconds, stepSeconds, gain),
    [levels, startSeconds, stepSeconds, gain],
  );
  const span = Math.max(0.001, view.endSeconds - view.startSeconds);
  return (
    <svg
      className={styles.laneWaveform}
      viewBox={`${view.startSeconds} 0 ${span} 1`}
      preserveAspectRatio="none"
      aria-hidden
    >
      <path d={d} />
    </svg>
  );
}
