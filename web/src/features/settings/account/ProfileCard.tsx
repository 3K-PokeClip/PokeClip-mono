'use client';

import { Avatar, Button, Field, Input } from '@/ui';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 「프로필」 카드 — 아바타·표시 이름·이메일이 한 카드에 들어가고,
// 저장 버튼 하나가 이름 입력만 확정한다(이메일은 구글에서 오므로 잠겨 있다).
// 이름 오류는 입력 아래 한 줄이다 — Field가 role=alert와 aria-describedby를 배선한다.

export function ProfileCard({
  name,
  email,
  photoUrl,
  draftName,
  dirty,
  editable,
  saving,
  nameError,
  onDraftNameChange,
  onSave,
  onEditPhoto,
}: {
  name: string;
  email: string;
  photoUrl: string | undefined;
  draftName: string;
  dirty: boolean;
  editable: boolean;
  /** 이름 저장 왕복 중 — 버튼이 스피너를 달고 잠긴다. */
  saving: boolean;
  /** 입력 아래에 그릴 사유 — 화면 선판정(30자)과 서버 거절(400)이 같은 자리다. */
  nameError: string | null;
  onDraftNameChange: (next: string) => void;
  onSave: () => void;
  onEditPhoto: () => void;
}) {
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
        {/* me가 오기 전엔 잠근다 — 열어 두면 잘라낸 사진을 올릴 주인이 없는데 모달만 열린다
            (저장 버튼과 같은 이유) */}
        <Button variant="soft" size="sm" disabled={!editable} onClick={onEditPhoto}>
          사진 수정
        </Button>
      </div>

      <div className={styles.fieldGrid}>
        <Field invalid={nameError !== null}>
          <Field.Label className={styles.fieldLabel}>표시 이름</Field.Label>
          <Input
            className={styles.field}
            value={draftName}
            onChange={(e) => onDraftNameChange(e.target.value)}
          />
          {nameError !== null ? <Field.Error>{nameError}</Field.Error> : null}
        </Field>
        {/* 구글 계정에서 오는 값이라 여기서 바꿀 수 없다 (ADR-012 — 구글 로그인 단일).
            편집자 초대의 열쇠이기도 해서 서버도 창구를 열지 않았다 (ADR-039) */}
        <Field disabled>
          <Field.Label className={styles.fieldLabel}>이메일</Field.Label>
          <Input className={styles.field} value={email} readOnly />
        </Field>
      </div>

      <div className={styles.cardActions}>
        {/* 바뀐 것이 없으면 잠근다 — 누를 때마다 「변경했습니다」가 뜨면 거짓말이 된다 */}
        <Button variant="solid" size="sm" disabled={!dirty} loading={saving} onClick={onSave}>
          저장
        </Button>
      </div>
    </section>
  );
}
