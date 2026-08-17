'use client';

import type { KeyboardEvent, MouseEvent } from 'react';
import styles from './GlassPlayer.module.css';
import { LIVE_WINDOW_SECONDS, formatBehind, isAtEdge, progressFraction } from './playerMath';

const KEYBOARD_STEP_SECONDS = 10;
/** 클립 구간 마커 폭 — 진행 비율 기준 % (시안 값: 최근 30초 ≈ 윈도우의 일부를 상징) */
const CLIP_MARK_WIDTH_PCT = 5;

// 커스텀 시크바 — 계약3 4절: DVR 윈도우(1시간) 안에서만 시킹.
// 클릭 → 시차 환산은 playerMath가 담당한다 (jsdom에서 못 하는 좌표 계산의 단위 테스트).
export function PlayerSeekBar({
  behindSeconds,
  clipMarked,
  onSeekToFraction,
  onSeekBy,
  onReturnToLive,
}: {
  behindSeconds: number;
  clipMarked: boolean;
  onSeekToFraction: (fraction: number) => void;
  onSeekBy: (deltaSeconds: number) => void;
  onReturnToLive: () => void;
}) {
  const atEdge = isAtEdge(behindSeconds);
  const pct = progressFraction(behindSeconds) * 100;

  const handleClick = (event: MouseEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    onSeekToFraction((event.clientX - rect.left) / rect.width);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowLeft') onSeekBy(KEYBOARD_STEP_SECONDS);
    else if (event.key === 'ArrowRight') onSeekBy(-KEYBOARD_STEP_SECONDS);
    else if (event.key === 'End') onReturnToLive();
    else if (event.key === 'Home') onSeekBy(LIVE_WINDOW_SECONDS);
    else return;
    event.preventDefault();
  };

  return (
    <div
      role="slider"
      tabIndex={0}
      aria-label="라이브 탐색"
      aria-valuemin={-LIVE_WINDOW_SECONDS}
      aria-valuemax={0}
      aria-valuenow={-behindSeconds}
      aria-valuetext={atEdge ? '실시간' : `실시간에서 ${formatBehind(behindSeconds)}`}
      className={styles.seekArea}
      onClick={handleClick}
      onKeyDown={handleKeyDown}
    >
      <div className={styles.track}>
        <div className={styles.buffer} />
        <div
          className={styles.progress}
          data-at-edge={atEdge || undefined}
          style={{ width: `${pct}%` }}
        />
        {clipMarked ? (
          <div
            className={styles.clipRange}
            style={{
              left: `${Math.max(0, pct - CLIP_MARK_WIDTH_PCT)}%`,
              width: `${CLIP_MARK_WIDTH_PCT}%`,
            }}
          />
        ) : null}
        <div className={styles.thumbDot} style={{ left: `${pct}%` }} />
      </div>
    </div>
  );
}
