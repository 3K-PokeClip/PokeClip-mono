'use client';

import { useId } from 'react';
import { ClipStepList, stepsSettled } from '@/features/clips/editor/ClipStepList';
import type { LibraryStepView } from './libraryView';
import styles from './LibraryScreen.module.css';

// 상세 패널 진행 카드(POK-510 시안 A, 2026-10-30 사용자 선택) — 미리보기 바로 아래. 예전에는 배지 · 꺼진 단추 · 회색 상자가
// 「유튜브에 올리는 중」을 세 번 말하고, 어디까지 왔는지 · 언제 시작했는지가 없었다. 편집기 작은 창과 같은 세로 단계(대기 → 영상
// 만들기 → 유튜브 올리기)에 시각을 달고, 진행에 딸린 말(공개 범위 안내)을 이 카드 하나에 모은다. 할 일 단추는 카드
// 밖이다: 확인 필요 풀기는 카드 바로 아래, 그 밖의 주 동작은 패널 단추 줄.

export function ClipProgressCard({
  step,
  privacyNote,
}: {
  step: LibraryStepView;
  /** 공개 범위 안내(privacyNoteText). 다 끝났으면(업로드됨) 이것이 곧 설명이다 */
  privacyNote: string | null;
}) {
  const headId = useId();
  const settled = stepsSettled(step.view);
  return (
    <section className={styles.progressCard} aria-labelledby={headId}>
      <div className={styles.progressHead}>
        <span id={headId}>진행 상황</span>
        <span>{step.versionLabel}</span>
      </div>
      <ClipStepList view={step.view} detail={step.detail} />
      {/* 지금 단계가 없으면(다 끝남 · 안 올림) 설명은 단계 밖에 둔다. 올라갔으면 공개 범위 안내가 「○○로 올라갔어요」를 더 자세히
          말하므로 그것만 둔다 — 둘 다 두면 같은 말을 두 번 한다 */}
      {settled ? (
        <p className={styles.progressDetail}>{privacyNote ?? step.detail}</p>
      ) : privacyNote ? (
        <p className={styles.progressFoot}>{privacyNote}</p>
      ) : null}
    </section>
  );
}
