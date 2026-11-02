import clsx from 'clsx';
import { Input } from '@/ui';
import type { VodCustomRange, VodPeriodFilter as PeriodFilter } from './useVodListMockState';
import styles from './VodListScreen.module.css';

// 시안 1f 헤더 오른쪽의 기간 칩. HighlightCardPanel의 필터 칩과 같은 구조다 —
// 상호작용이 필요해 Tag(span) 대신 button이고, 눌린 상태는 aria-pressed로 말한다.

const CHIPS: { value: PeriodFilter; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: '7d', label: '7일' },
  { value: '30d', label: '30일' },
  { value: 'custom', label: '기간 지정' },
];

export function VodPeriodFilter({
  filter,
  onFilterChange,
  customRange,
  onCustomRangeChange,
}: {
  filter: PeriodFilter;
  onFilterChange: (filter: PeriodFilter) => void;
  customRange: VodCustomRange;
  onCustomRangeChange: (range: VodCustomRange) => void;
}) {
  return (
    <>
      <div className={styles.filterGroup} role="group" aria-label="기간 필터">
        {CHIPS.map((chip) => (
          <button
            key={chip.value}
            type="button"
            className={clsx(styles.filterChip, filter === chip.value && styles.filterChipActive)}
            aria-pressed={filter === chip.value}
            onClick={() => onFilterChange(chip.value)}
          >
            {chip.label}
          </button>
        ))}
      </div>

      {/*
        날짜 입력은 팝오버 대신 헤더 아래에 인라인으로 편다. 칩 하나가 눌림(aria-pressed)과
        열림(aria-expanded)을 겸하면 두 의미가 섞인다 — 칩은 고르는 것이고, 고른 뒤에
        따라 나오는 것이 입력이다.
      */}
      {filter === 'custom' ? (
        <div className={styles.customRange}>
          <label className={styles.customRangeField}>
            <span className={styles.customRangeLabel}>시작일</span>
            <Input
              type="date"
              size="sm"
              value={customRange.from ?? ''}
              max={customRange.to ?? undefined}
              onChange={(event) =>
                onCustomRangeChange({ ...customRange, from: event.target.value || null })
              }
            />
          </label>
          <label className={styles.customRangeField}>
            <span className={styles.customRangeLabel}>종료일</span>
            <Input
              type="date"
              size="sm"
              value={customRange.to ?? ''}
              min={customRange.from ?? undefined}
              onChange={(event) =>
                onCustomRangeChange({ ...customRange, to: event.target.value || null })
              }
            />
          </label>
        </div>
      ) : null}
    </>
  );
}
