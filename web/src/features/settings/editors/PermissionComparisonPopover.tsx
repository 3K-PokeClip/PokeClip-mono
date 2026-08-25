'use client';

import { useState } from 'react';
import { Check, Clock, Minus, Shield, X, Zap } from 'lucide-react';
import { Popover, VisuallyHidden } from '@/ui';
import styles from './EditorSettingsScreen.module.css';

// 권한 2단계 비교 팝오버 (디자인 1l ②) — 하단 안내 문구의 인라인 트리거에서 열린다.
//
// ⚠ 권한 등급은 아직 결정 대기다(편집자-권한등급-검토 2026-08-19: 정본 3등급·구현 0등급·
// 시안 2등급). 이 팝오버는 시안의 2단계(기본·신뢰)를 그대로 옮긴 **안내 전용** 표이고,
// 행 드롭다운·초대 권한 선택 같은 실제 등급 동작은 여전히 없다. 등급이 확정되면 이 표의
// 단계·행이 그 결정을 따라간다.

/** 비교표 한 행 — 기본/신뢰 칸은 표식 종류로 그린다. */
const ROWS = [
  { label: '검토 · 클립 편집', emphasize: false, basic: 'yes', trusted: 'yes' },
  { label: '보관함 저장 · 템플릿 사용', emphasize: false, basic: 'yes', trusted: 'yes' },
  { label: '유튜브 업로드', emphasize: true, basic: 'after-approval', trusted: 'instant' },
  { label: '템플릿 · 채널 설정 변경', emphasize: false, basic: 'no', trusted: 'no' },
] as const;

type Mark = (typeof ROWS)[number]['basic'] | (typeof ROWS)[number]['trusted'];

export function PermissionComparisonPopover() {
  const [open, setOpen] = useState(false);

  return (
    <Popover open={open} onOpenChange={setOpen} side="bottom" align="start">
      <Popover.Trigger>
        <button type="button" className={styles.compareTrigger}>
          권한 2단계 비교
        </button>
      </Popover.Trigger>
      <Popover.Content className={styles.comparePanel} aria-label="권한 2단계 비교">
        <div className={styles.compareHead}>
          <Shield size={13} aria-hidden="true" />
          <span className={styles.compareTitle}>권한 2단계 비교</span>
          <button
            type="button"
            className={styles.compareClose}
            aria-label="닫기"
            onClick={() => setOpen(false)}
          >
            <X size={13} />
          </button>
        </div>
        <table className={styles.compareTable}>
          <colgroup>
            <col />
            <col className={styles.compareCol} />
            <col className={styles.compareCol} />
          </colgroup>
          <thead>
            <tr>
              <td aria-hidden="true" />
              <th scope="col" className={styles.compareColHead}>
                기본
                <span className={styles.compareColSub}>승인 필요</span>
              </th>
              <th scope="col" className={`${styles.compareColHead} ${styles.compareTrusted}`}>
                신뢰
                <span className={styles.compareColSub}>직접 업로드</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {ROWS.map((row) => (
              <tr key={row.label}>
                <th scope="row" className={row.emphasize ? styles.compareRowHeadStrong : styles.compareRowHead}>
                  {row.label}
                </th>
                <td className={styles.compareCell}>{markOf(row.basic)}</td>
                <td className={`${styles.compareCell} ${styles.compareTrusted}`}>
                  {markOf(row.trusted)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className={styles.compareFoot}>
          새 편집자는 기본으로 시작하고, 권한은 언제든 바꿀 수 있어요. 신뢰는 오래 함께한
          편집자에게만 권장해요.
        </div>
      </Popover.Content>
    </Popover>
  );
}

/** 칸 표식 — 시안 ②의 네 종류: ✓(가능) · 시계+승인 후 · 번개+즉시 · −(불가). */
function markOf(mark: Mark) {
  switch (mark) {
    case 'yes':
      return (
        <>
          <Check size={13} className={styles.compareYes} aria-hidden="true" />
          <VisuallyHidden>가능</VisuallyHidden>
        </>
      );
    case 'after-approval':
      return (
        <span className={styles.compareTimed}>
          <Clock size={12} aria-hidden="true" />
          승인 후
        </span>
      );
    case 'instant':
      return (
        <span className={styles.compareInstant}>
          <Zap size={12} aria-hidden="true" />
          즉시
        </span>
      );
    default:
      return (
        <>
          <Minus size={13} className={styles.compareNo} aria-hidden="true" />
          <VisuallyHidden>불가</VisuallyHidden>
        </>
      );
  }
}
