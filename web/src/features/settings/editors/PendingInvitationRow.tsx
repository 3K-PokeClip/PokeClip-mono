import { useId } from 'react';
import { Plus } from 'lucide-react';
import { Button } from '@/ui';
import type { SentInvitation } from '@/api/editors';
import { expiresLabel } from './editorDates';
import styles from './EditorSettingsScreen.module.css';

// 디자인 1l 대기 중인 초대 행 — 점선 카드가 「아직 편집자가 아니다」를 말한다. 시안은
// 한 행에 「대기 중인 초대 1건 · 이메일」로 묶지만, 취소가 행 단위 동작이라 초대마다
// 행 하나로 그린다 (여러 건이면 시안의 묶음 표기는 취소 대상을 특정하지 못한다).
//
// 시안의 「기본 편집자로 초대」 보조설명은 넣지 않는다 — 권한 등급이 결정 대기다 (POK-208).
export function PendingInvitationRow({
  invitation,
  canceling,
  onCancel,
}: {
  invitation: SentInvitation;
  /** 이 행의 취소 요청이 도는 중 — 버튼만 잠근다. */
  canceling: boolean;
  onCancel: (invitation: SentInvitation) => void;
}) {
  const labelId = useId();

  return (
    <div className={styles.pendingRow} role="group" aria-labelledby={labelId}>
      <span className={styles.pendingIcon} aria-hidden="true">
        <Plus size={18} />
      </span>
      <div className={styles.rowBody}>
        <div className={styles.pendingTitle} id={labelId}>
          대기 중인 초대 · {invitation.inviteeEmail}
        </div>
        <div className={styles.rowMeta}>{expiresLabel(invitation.expiresAt)}</div>
      </div>
      {/* 확인 모달이 없다 — 같은 이메일로 다시 초대하면 되돌아가는 동작이라 파괴적이지 않다. */}
      <Button variant="ghost" size="sm" loading={canceling} onClick={() => onCancel(invitation)}>
        취소
      </Button>
    </div>
  );
}
