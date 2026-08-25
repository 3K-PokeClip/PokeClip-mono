import { useId } from 'react';
import { Avatar, Button } from '@/ui';
import type { EditorDelegation } from '@/api/editors';
import { joinedLabel } from './editorDates';
import styles from './EditorSettingsScreen.module.css';

// 디자인 1l 편집자 행의 표현 껍데기 — 훅이 준 위임 한 건만 그린다. ChannelRow처럼
// 행을 role="group"으로 묶고 편집자 이름으로 접근 이름을 준다 — 「내보내기」 버튼이
// 행마다 있어 이름만으로는 구분되지 않는다.
//
// 시안은 이름 아래 「이메일 · 가입일」을 그리지만 계약(DelegationResponse)이 이메일을
// 의도적으로 주지 않는다 — POK-207이 보강할 때까지 합류일만 그린다. 아바타 이미지도
// 계약에 없어 이니셜 폴백으로 선다.
export function EditorRow({
  editor,
  onRevoke,
}: {
  editor: EditorDelegation;
  onRevoke: (editor: EditorDelegation) => void;
}) {
  const nameId = useId();

  return (
    <div className={styles.row} role="group" aria-labelledby={nameId}>
      <Avatar size="md" name={editor.name} />
      <div className={styles.rowBody}>
        <div className={styles.rowName} id={nameId}>
          {editor.name}
        </div>
        <div className={styles.rowMeta}>{joinedLabel(editor.grantedAt)}</div>
      </div>
      {/* 누르면 바로 내보내지 않는다 — 확인 모달이 받는다 (ADR-044). */}
      <Button variant="ghost" size="sm" onClick={() => onRevoke(editor)}>
        내보내기
      </Button>
    </div>
  );
}
