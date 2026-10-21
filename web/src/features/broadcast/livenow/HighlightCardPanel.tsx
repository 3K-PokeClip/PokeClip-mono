'use client';

import { memo, useState } from 'react';
import Link from 'next/link';
import clsx from 'clsx';
import { Zap } from 'lucide-react';
import { EmptyState, Spinner } from '@/ui';
import styles from './LiveScreen.module.css';
import { HighlightCard } from './HighlightCard';
import type { CardVisual } from './useLiveDetailsMockState';
import type { HighlightSource, LiveHighlight, LiveStream } from './useLiveMockState';

// 하이라이트 카드 패널(시안 1b) — 가로로 넘기는 카드 행.
// 개수는 넘어온 목록에서 그때그때 센다. 목업에 박아 두면 마킹으로 카드가 늘었을 때
// 필터 표기만 옛 수에 남는다.

// 'hidden'은 숨긴 카드만 본다(POK-338): 거기서만 숨김을 되돌릴 수 있다
type Filter = 'all' | HighlightSource | 'hidden';

const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'auto', label: '자동' },
  { value: 'manual', label: '수동' },
];

// memo인 이유 — 화면이 경과 표기 때문에 매초 다시 그려진다. 카드 9장의 파형 문자열 생성과
// 필터링이 초당 한 번씩 다시 도는 것을 막는다(ChatPanel·LiveStatsPanel도 같은 이유).
export const HighlightCardPanel = memo(function HighlightCardPanel({
  highlights,
  hiddenHighlights = [],
  stream,
  visuals,
  pendingLabel,
  detectionPaused,
  ended = false,
  loaded,
  onSeek,
}: {
  highlights: LiveHighlight[];
  /** 숨긴 카드(POK-338). 「숨김」 칩을 누르면 이것만 보인다 */
  hiddenHighlights?: LiveHighlight[];
  /** 카드 목록을 한 번이라도 받았나. 받기 전에 비어 있으면 「없다」가 아니라 불러오는 중이다 */
  loaded: boolean;
  stream: LiveStream;
  visuals: Record<string, CardVisual>;
  pendingLabel: string | null;
  /** 수집이 끊겨 자동 탐지가 멈춘 상태 — 감지 중이라고 말하면 거짓이 된다 */
  detectionPaused: boolean;
  /** 끝난 방송: 감지는 방송 중에 끝났다. 「자동 감지 중」이라고 하면 지금 감지하는 것처럼 읽힌다(QA 2026-10-11) */
  ended?: boolean;
  onSeek: (timestamp: string) => void;
}) {
  const [filter, setFilter] = useState<Filter>('all');

  const countFor = (value: Filter) =>
    value === 'all'
      ? highlights.length
      : value === 'hidden'
        ? hiddenHighlights.length
        : highlights.filter((h) => h.source === value).length;
  const shown =
    filter === 'all'
      ? highlights
      : filter === 'hidden'
        ? hiddenHighlights
        : highlights.filter((h) => h.source === filter);
  // 만들어질 카드는 수동이다 — 「자동」을 보고 있을 땐 자리도 두지 않는다.
  // 아니면 자리만 3초 섰다 사라지고 카드는 안 보여 마킹이 실패한 것처럼 읽힌다.
  const showPending = pendingLabel !== null && filter !== 'auto' && filter !== 'hidden';

  return (
    <section className={styles.cardPanel} aria-label="하이라이트 카드">
      <div className={styles.cardPanelHeader}>
        <h2 className={styles.cardPanelHeading}>하이라이트 카드</h2>
        <span className={styles.cardPanelNote}>
          {ended
            ? '카드 클릭 = 시점 이동'
            : // 멈췄을 때 「핫키로 직접 남길 수 있어요」라고 했지만 그 길은 아직 없다(POK-119·257, QA 2026-10-11)
              `${detectionPaused ? '자동 감지 멈춤' : '자동 감지 중'} · 카드 클릭 = 시점 이동`}
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
          <button
            type="button"
            className={clsx(styles.filterChip, filter === 'hidden' && styles.filterChipActive)}
            aria-pressed={filter === 'hidden'}
            onClick={() => setFilter('hidden')}
          >
            숨김 {countFor('hidden')}
          </button>
          <span className={styles.cardPanelDivider} aria-hidden />
          {/* 보관함 화면을 바로 가리킨다 — /clips는 리다이렉트라 한 홉을 더 탄다(POK-235) */}
          <Link className={styles.cardPanelLink} href="/clips/library">
            보관함
          </Link>
        </div>
      </div>
      {shown.length === 0 && !showPending ? (
        <EmptyTrack
          filter={filter}
          loaded={loaded}
          visibleCount={highlights.length}
          hiddenCount={hiddenHighlights.length}
          ended={ended}
          detectionPaused={detectionPaused}
        />
      ) : (
        /* 가로 스크롤 영역은 키보드로도 훑을 수 있어야 한다 (axe scrollable-region-focusable) */
        <ul className={styles.cardTrack} tabIndex={0} aria-label="하이라이트 카드 목록">
          {showPending ? (
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
              <HighlightCard
                highlight={highlight}
                stream={stream}
                visuals={visuals}
                onSeek={onSeek}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
});

// 카드 줄이 비었을 때 하는 말. 카드가 하나도 없으면 빈 상태 카드(아이콘·제목·설명)다. 불러오는 중·필터·숨김으로
// 빈 자리는 같은 점선 틀에 한 줄만 쓴다: 「아직 없다」 카드를 다시 쓰면 거짓이 된다. 줄이 통째로 사라지는 자리라
// 한 줄은 role="status"로 낭독시킨다.
function EmptyTrack({
  filter,
  loaded,
  visibleCount,
  hiddenCount,
  ended,
  detectionPaused,
}: {
  filter: Filter;
  loaded: boolean;
  visibleCount: number;
  hiddenCount: number;
  ended: boolean;
  detectionPaused: boolean;
}) {
  const note = (text: string, busy = false) => (
    <p className={styles.cardEmptyNote} role="status">
      {/* 도는 표시는 눈에만 보인다. Spinner도 status라 그대로 두면 문장과 두 번 낭독된다 */}
      {busy ? <Spinner size="sm" aria-hidden /> : null}
      {text}
    </p>
  );
  // 목록을 받기 전에 「없어요」라고 하면 거짓이다: 모르는 것은 없는 것이 아니다
  if (!loaded) return note('카드를 불러오는 중…', true);
  if (filter === 'hidden') return note('숨긴 카드가 없어요.');
  if (visibleCount > 0)
    return note(filter === 'auto' ? '자동 감지한 카드가 없어요.' : '수동으로 남긴 카드가 없어요.');
  if (hiddenCount > 0) return note('보이는 카드가 없어요. 숨긴 카드는 「숨김」에서 볼 수 있어요.');
  return (
    <EmptyState
      className={styles.cardEmpty}
      icon={<Zap size={21} />}
      // 끝난 방송에는 더 생기지 않는다 — 「아직」이라고 하면 기다리면 생길 것처럼 읽힌다
      title={ended ? '이 방송에서 감지된 하이라이트가 없어요' : '아직 감지된 하이라이트가 없어요'}
      description={
        ended
          ? '자동 감지는 방송 중에만 카드를 만들어요.'
          : detectionPaused
            ? '채팅 수집이 끊겨 자동 감지가 멈춰 있어요.'
            : '채팅 반응이 튀는 순간을 찾으면 여기에 카드가 실시간으로 쌓여요.'
      }
    />
  );
}
