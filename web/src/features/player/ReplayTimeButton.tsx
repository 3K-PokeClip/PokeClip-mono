'use client';

import { useState } from 'react';
import styles from './GlassPlayer.module.css';
import { formatUptime } from './playerMath';

/**
 * 지난 방송의 시간 표기(POK-518) — 「지금 / 전체」이고, 누르면 앞 칸이 끝까지 남은 시간으로 바뀐다. 다시 누르면 돌아온다.
 * 녹화 처음부터 잰다: windowSeconds는 녹화 처음부터 끝까지의 길이, behindSeconds는 끝까지 남은 시간이다.
 *
 * 설명 말풍선은 공용 Tooltip이 아니다. Tooltip은 document.body에 떠서 플레이어 전체 화면에서 안 보이고, 재생 중 컨트롤이 숨어도
 * 혼자 남는다. 컨트롤 안에 두면 둘 다 저절로 맞는다. 낭독은 버튼 이름이 맡는다(말풍선은 aria-hidden)
 */
export function ReplayTimeButton({
  behindSeconds,
  windowSeconds,
}: {
  behindSeconds: number;
  windowSeconds: number;
}) {
  const [showRemaining, setShowRemaining] = useState(false);
  const now = showRemaining
    ? `-${formatUptime(behindSeconds)}`
    : formatUptime(windowSeconds - behindSeconds);
  return (
    <span className={styles.replayTime}>
      <button
        type="button"
        className={styles.livePillBtn}
        aria-label={showRemaining ? '재생 시간 보기' : '남은 시간 보기'}
        onClick={() => setShowRemaining((on) => !on)}
      >
        <span className={styles.replayTimeDigits}>
          {now} / {formatUptime(windowSeconds)}
        </span>
      </button>
      <span className={styles.replayTimeHint} data-theme="dark" aria-hidden="true">
        {showRemaining
          ? '남은 시간이에요. 누르면 재생 시간을 보여 줘요'
          : '재생 시간이에요. 누르면 남은 시간을 보여 줘요'}
      </span>
    </span>
  );
}
