'use client';

import { memo, useState } from 'react';
import Link from 'next/link';
import clsx from 'clsx';
import { Spinner } from '@/ui';
import styles from './LiveScreen.module.css';
import { HighlightCard } from './HighlightCard';
import type { CardVisual } from './useLiveDetailsMockState';
import type { HighlightSource, LiveHighlight, LiveStream } from './useLiveMockState';

// 하이라이트 카드 패널(시안 1b) — 가로로 넘기는 카드 행.
// 개수는 넘어온 목록에서 그때그때 센다. 목업에 박아 두면 마킹으로 카드가 늘었을 때
// 필터 표기만 옛 수에 남는다.

type Filter = 'all' | HighlightSource;

const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'auto', label: '자동' },
  { value: 'manual', label: '수동' },
];

// memo인 이유 — 화면이 경과 표기 때문에 매초 다시 그려진다. 카드 9장의 파형 문자열 생성과
// 필터링이 초당 한 번씩 다시 도는 것을 막는다(ChatPanel·LiveStatsPanel도 같은 이유).
export const HighlightCardPanel = memo(function HighlightCardPanel({
  highlights,
  stream,
  visuals,
  pendingLabel,
  detectionPaused,
  onSeek,
}: {
  highlights: LiveHighlight[];
  stream: LiveStream;
  visuals: Record<string, CardVisual>;
  pendingLabel: string | null;
  /** 수집이 끊겨 자동 탐지가 멈춘 상태 — 감지 중이라고 말하면 거짓이 된다 */
  detectionPaused: boolean;
  onSeek: (timestamp: string) => void;
}) {
  const [filter, setFilter] = useState<Filter>('all');

  const countFor = (value: Filter) =>
    value === 'all' ? highlights.length : highlights.filter((h) => h.source === value).length;
  const shown = filter === 'all' ? highlights : highlights.filter((h) => h.source === filter);

  return (
    <section className={styles.cardPanel} aria-label="하이라이트 카드">
      <div className={styles.cardPanelHeader}>
        <h2 className={styles.cardPanelHeading}>하이라이트 카드</h2>
        <span className={styles.cardPanelNote}>
          {detectionPaused ? '자동 감지 멈춤 · 핫키로 직접 남길 수 있어요' : '자동 감지 중'} · 카드
          클릭 = 시점 이동
        </span>
        <div className={styles.filterGroup} role="group" aria-label="하이라이트 필터">
          {FILTERS.map(({ value, label }) => (
            <button
              key={value}
              type="button"
              className={clsx(styles.filterChip, filter === value && styles.filterChipActive)}
              aria-pressed={filter === value}
              onClick={() => setFilter(value)}
            >
              {label} {countFor(value)}
            </button>
          ))}
          <span className={styles.cardPanelDivider} aria-hidden />
          <Link className={styles.cardPanelLink} href="/clips">
            보관함
          </Link>
        </div>
      </div>
      {/* 가로 스크롤 영역은 키보드로도 훑을 수 있어야 한다 (axe scrollable-region-focusable) */}
      <ul className={styles.cardTrack} tabIndex={0} aria-label="하이라이트 카드 목록">
        {/* 만들어질 카드는 수동이다 — 「자동」을 보고 있을 땐 자리도 두지 않는다.
            아니면 자리만 3초 섰다 사라지고 카드는 안 보여 마킹이 실패한 것처럼 읽힌다. */}
        {pendingLabel && filter !== 'auto' ? (
          <li className={styles.cardSlot}>
            <div className={styles.pendingCard}>
              <Spinner size="sm" label="카드 만드는 중" />
              <span className={styles.pendingLabel}>{pendingLabel} 수동 마킹</span>
              <span className={styles.pendingNote}>카드 만드는 중…</span>
            </div>
          </li>
        ) : null}
        {shown.map((highlight) => (
          <li key={highlight.id} className={styles.cardSlot}>
            <HighlightCard highlight={highlight} stream={stream} visuals={visuals} onSeek={onSeek} />
          </li>
        ))}
      </ul>
    </section>
  );
});
