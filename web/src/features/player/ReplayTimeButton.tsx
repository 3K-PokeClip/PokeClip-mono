'use client';

import { useState } from 'react';
import styles from './GlassPlayer.module.css';
import { PlayerControlHint } from './PlayerControlHint';
import { formatUptime } from './playerMath';

/**
 * 지난 방송의 시간 표기(POK-518) — 「지금 / 전체」이고, 누르면 앞 칸이 끝까지 남은 시간으로 바뀐다. 다시 누르면 돌아온다.
 * 녹화 처음부터 잰다: windowSeconds는 녹화 처음부터 끝까지의 길이, behindSeconds는 끝까지 남은 시간이다.
 */
export function ReplayTimeButton({
  behindSeconds,
  windowSeconds,
}: {
  behindSeconds: number;
  windowSeconds: number;
}) {
  const [showRemaining, setShowRemaining] = useState(false);
  const name = showRemaining ? '재생 시간 보기' : '남은 시간 보기';
  const now = showRemaining
    ? `-${formatUptime(behindSeconds)}`
    : formatUptime(windowSeconds - behindSeconds);
  return (
    <PlayerControlHint label={name}>
      <button
        type="button"
        className={styles.livePillBtn}
        aria-label={name}
        onClick={() => setShowRemaining((on) => !on)}
      >
        <span className={styles.replayTimeDigits}>
          {now} / {formatUptime(windowSeconds)}
        </span>
      </button>
    </PlayerControlHint>
  );
}
