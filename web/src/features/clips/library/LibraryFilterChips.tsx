'use client';

import { chipsFor, type LibraryChip } from './libraryView';
import type { LibraryRole } from './useLibraryMockState';
import styles from './LibraryScreen.module.css';

// 시안 1g 상태 칩. HighlightCardPanel·VodPeriodFilter와 같은 구조다 — 상호작용이 필요해
// Tag(span) 대신 button이고, 눌린 상태는 aria-pressed로 말한다.
//
// 세 번째 손 칩이지만 「세 번째 화면이 같은 칩을 원하면 올린다」(VodListScreen.module.css)는
// 아직 발동하지 않는다 — 이 칩은 시안의 Tag solid/soft 모양이라 앞의 둘(outline 알약)과
// 같은 칩이 아니다. 세 모양을 한 컴포넌트가 받을 수 있을 때 승격한다(후속 티켓).

export function LibraryFilterChips({
  role,
  chip,
  counts,
  onChange,
}: {
  role: LibraryRole;
  chip: LibraryChip;
  counts: Record<LibraryChip, number>;
  onChange: (chip: LibraryChip) => void;
}) {
  return (
    <div className={styles.chipGroup} role="group" aria-label="상태 필터">
      {chipsFor(role).map((item) => (
        <button
          key={item.value}
          type="button"
          className={styles.chip}
          aria-pressed={chip === item.value}
          onClick={() => onChange(item.value)}
        >
          {item.label} <span className={styles.chipCount}>{counts[item.value]}</span>
        </button>
      ))}
    </div>
  );
}
