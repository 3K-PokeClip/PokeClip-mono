'use client';

import { useState } from 'react';
import clsx from 'clsx';
import { Badge, Card } from '@/ui';
import styles from './LiveScreen.module.css';
import { HighlightRow } from './HighlightRow';
import type { HighlightSource, LiveHighlight } from './useLiveMockState';

type Filter = 'all' | HighlightSource;

const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'auto', label: '자동' },
  { value: 'manual', label: '수동' },
];

// 디자인 1b 우측 — 하이라이트 카드 목록 + 전체/자동/수동 필터.
export function HighlightCardList({
  highlights,
  hiddenCount,
}: {
  highlights: LiveHighlight[];
  hiddenCount: number;
}) {
  const [filter, setFilter] = useState<Filter>('all');
  const visible = highlights.filter((h) => filter === 'all' || h.source === filter);

  return (
    <Card variant="outline" padding={0} className={styles.highlightCard}>
      <div className={styles.highlightHeader}>
        <h2 className={styles.highlightHeading}>하이라이트 카드</h2>
        <Badge tone="point" variant="soft" size="sm">
          {highlights.length}
        </Badge>
        <div className={styles.filterGroup} role="group" aria-label="하이라이트 필터">
          {FILTERS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={clsx(
                styles.filterChip,
                filter === option.value && styles.filterChipActive,
              )}
              aria-pressed={filter === option.value}
              onClick={() => setFilter(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>
      <ul className={styles.highlightRows}>
        {visible.map((highlight) => (
          <HighlightRow key={highlight.id} highlight={highlight} />
        ))}
      </ul>
      <div className={styles.highlightFooter}>
        {/* 숨김 카드 보기는 목록 API 연동에서 — 자리만 (디자인 1b) */}
        <span className={styles.highlightFooterLink} aria-disabled="true">
          숨김 카드 {hiddenCount}개 보기
        </span>
        <span className={styles.highlightFooterNote}>핫키 F8 = 수동 마킹</span>
      </div>
    </Card>
  );
}
