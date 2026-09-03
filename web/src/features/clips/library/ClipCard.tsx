'use client';

import clsx from 'clsx';
import { Badge } from '@/ui';
import { STATUS_BADGE, cardName, durationLabel, ownerInitial } from './libraryView';
import type { ClipStatus, LibraryClip } from './useLibraryMockState';
import styles from './LibraryScreen.module.css';

// 시안 1g 9:16 썸네일 카드. 카드 전체가 토글 버튼 하나다 — 안에 링크·버튼을 두지 않는다
// (axe nested-interactive). 상태별 동작은 전부 우측 상세 패널이 맡는다.
//
// 안의 배지·이니셜·제목·길이는 aria-hidden이고 이름은 aria-label 하나(cardName)가 정한다 —
// 그래야 제목 · 상태 · 길이 순서로 읽히고, 눌린 상태는 aria-pressed가 따로 말한다.

/** 패널을 닫을 때 포커스를 돌려줄 카드의 DOM id */
export function clipCardDomId(id: string): string {
  return `clip-card-${id}`;
}

export function ClipCard({
  clip,
  status,
  selected,
  onToggle,
}: {
  clip: LibraryClip;
  status: ClipStatus;
  selected: boolean;
  onToggle: (id: string) => void;
}) {
  const duration = durationLabel(clip, status);
  const badge = STATUS_BADGE[status];

  return (
    <li className={styles.cardItem}>
      <button
        type="button"
        id={clipCardDomId(clip.id)}
        className={clsx(styles.card, status === 'expired' && styles.cardDimmed)}
        aria-pressed={selected}
        aria-label={cardName(clip, status, duration)}
        onClick={() => onToggle(clip.id)}
      >
        {/* 실이미지가 생기면 이 자리만 next/image로 바뀐다 — 오버레이는 그대로(home Thumb 선례) */}
        <span className={styles.cardThumb} aria-hidden="true">
          9:16
        </span>
        <span className={styles.cardBadge} aria-hidden="true">
          <Badge tone={badge.tone} variant="solid" size="sm">
            {badge.label}
          </Badge>
        </span>
        <span className={styles.cardOwner} aria-hidden="true">
          {ownerInitial(clip.owner)}
        </span>
        <span className={styles.cardFoot} aria-hidden="true">
          <span className={styles.cardTitle}>{clip.title.trim() || '제목 없는 편집본'}</span>
          {duration ? <span className={styles.cardDuration}>{duration}</span> : null}
        </span>
      </button>
    </li>
  );
}
