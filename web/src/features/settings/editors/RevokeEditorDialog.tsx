'use client';

import { Avatar, ConfirmDialog } from '@/ui';
import type { EditorDelegation } from '@/api/editors';
import { joinedLabel } from './editorDates';
import styles from './EditorSettingsScreen.module.css';

// 편집자 내보내기 재확인 (디자인 1l ⑦, ADR-044) — 파괴적 동작은 모달로 확인받고 토스트는
// 결과만 알린다. 되돌리기는 붙이지 않는다 — 내보내는 즉시 대기 중이던 승인 요청이
// 무효가 되므로 되돌려도 그 요청은 안 돌아온다.
//
// 시안의 대상 카드 메타는 「신뢰 편집자 · 5월 12일 가입 · 제작 클립 42개」지만 등급은 결정
// 대기(권한 등급 검토), 클립 수는 clip 서비스 소유라 계약에 없다 — 지금 주는 합류일만 그린다.
// 결과 둘째 줄도 시안은 대기 요청 유무·건수로 문구를 가르지만 건수 API가 없어 무효 고지
// 한 문장으로 둔다.
export function RevokeEditorDialog({
  target,
  busy,
  onCancel,
  onConfirm,
}: {
  /** 내보낼 위임. null이면 닫힘 — 이름이 제목·결과 문구에 필요해 boolean 대신 대상이 상태다. */
  target: EditorDelegation | null;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const name = target?.name ?? '';

  return (
    <ConfirmDialog
      open={target !== null}
      busy={busy}
      eyebrow="편집자 내보내기"
      title={`${name} 님을 편집자에서 빼고, 내 방송 접근을 막을까요?`}
      subject={
        <div className={styles.kickSubject}>
          <Avatar size="sm" name={name} />
          <div className={styles.kickSubjectBody}>
            <div className={styles.kickSubjectName}>{name}</div>
            <div className={styles.kickSubjectMeta}>
              {target !== null ? joinedLabel(target.grantedAt) : ''}
            </div>
          </div>
        </div>
      }
      consequences={[
        '지금 바로 내 방송의 하이라이트·클립을 볼 수 없게 됩니다.',
        '대기 중이던 승인 요청은 무효가 됩니다.',
        '이미 업로드된 클립과 만들어 둔 편집본은 그대로 남아요.',
        '편집자 자리 1개가 바로 비어요.',
      ]}
      footnote="되돌릴 수 없지만, 필요하면 언제든 다시 초대할 수 있어요."
      confirmLabel="내보내기"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
