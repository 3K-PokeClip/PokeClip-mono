'use client';

import { useId } from 'react';
import { Avatar, Button, Input } from '@/ui';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 「프로필」 카드 — 아바타·표시 이름·이메일이 한 카드에 들어가고,
// 저장 버튼 하나가 이름 입력만 확정한다(이메일은 구글에서 오므로 잠겨 있다).

export function ProfileCard({
  name,
  email,
  photoUrl,
  draftName,
  dirty,
  onDraftNameChange,
  onSave,
  onEditPhoto,
}: {
  name: string;
  email: string;
  photoUrl: string | undefined;
  draftName: string;
  dirty: boolean;
  onDraftNameChange: (next: string) => void;
  onSave: () => void;
  onEditPhoto: () => void;
}) {
  const nameId = useId();
  const emailId = useId();

  return (
    <section className={styles.card} aria-labelledby="account-profile-title">
      <h2 id="account-profile-title" className={styles.cardTitle}>
        프로필
      </h2>

      <div className={styles.avatarRow}>
        {/* me가 늦게 와도 자리가 흔들리지 않는다 — 이름이 채워지면 이니셜만 나중에 뜬다 */}
        <Avatar size="lg" src={photoUrl || undefined} name={name || undefined} />
        <div className={styles.avatarBody}>
          <div className={styles.avatarLabel}>프로필 사진</div>
        </div>
        <Button variant="soft" size="sm" onClick={onEditPhoto}>
          사진 수정
        </Button>
      </div>

      <div className={styles.fieldGrid}>
        <div>
          <label htmlFor={nameId} className={styles.fieldLabel}>
            표시 이름
          </label>
          <Input
            id={nameId}
            className={styles.field}
            value={draftName}
            onChange={(e) => onDraftNameChange(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor={emailId} className={styles.fieldLabel}>
            이메일
          </label>
          {/* 구글 계정에서 오는 값이라 여기서 바꿀 수 없다 (ADR-012 — 구글 로그인 단일) */}
          <Input id={emailId} className={styles.field} value={email} disabled readOnly />
        </div>
      </div>

      <div className={styles.cardActions}>
        {/* 바뀐 것이 없으면 잠근다 — 누를 때마다 「변경했습니다」가 뜨면 거짓말이 된다 */}
        <Button variant="solid" size="sm" disabled={!dirty} onClick={onSave}>
          저장
        </Button>
      </div>
    </section>
  );
}
