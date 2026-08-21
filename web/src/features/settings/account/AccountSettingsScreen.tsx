'use client';

import { useState } from 'react';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { LoginCard } from './LoginCard';
import { ProfileCard } from './ProfileCard';
import { ProfilePhotoDialog } from './ProfilePhotoDialog';
import { useAccountMockState } from './useAccountMockState';
import { useProfilePhotoState } from './useProfilePhotoState';
import { WithdrawBlockedDialog } from './WithdrawBlockedDialog';
import { WithdrawDialog } from './WithdrawDialog';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 설정 · 계정 (POK-206). 조립만 한다 — 저장·탈퇴 판단은 useAccountMockState,
// 사진 모달의 단계·타이머는 useProfilePhotoState가 갖는다.
//
// ⚠ 프로필 수정·아바타 업로드·탈퇴 API가 아직 하나도 없다(POK-171 「할 일」). 이 화면은
// 시안대로 전부 눌리지만 서버로 나가는 요청이 없고, 새로고침하면 원래대로 돌아온다.
export function AccountSettingsScreen() {
  const account = useAccountMockState();
  const photo = useProfilePhotoState(account.applyPhoto);
  const [withdrawOpen, setWithdrawOpen] = useState(false);

  const name = account.me?.name ?? '';
  const email = account.me?.email ?? '';

  return (
    <div className={styles.screen}>
      {/* 1p의 상단은 제목 한 줄이다 — 보조 설명을 두지 않는다 */}
      <SettingsPageHeader title="계정" />

      <ProfileCard
        name={name}
        email={email}
        photoUrl={account.me?.profileImageUrl}
        draftName={account.draftName}
        dirty={account.dirty}
        onDraftNameChange={account.setDraftName}
        onSave={account.saveName}
        onEditPhoto={photo.open}
      />

      <LoginCard email={email} />

      <div className={styles.withdrawRow}>
        <button type="button" className={styles.withdrawLink} onClick={() => setWithdrawOpen(true)}>
          탈퇴하기
        </button>
      </div>

      {/* 막힌 상태에서는 재확인 대신 이유를 보여 준다 — 두 모달이 동시에 뜨지 않는다 */}
      <WithdrawDialog
        open={withdrawOpen && !account.blocked}
        name={name}
        facts={account.facts}
        onCancel={() => setWithdrawOpen(false)}
        onConfirm={account.completeWithdraw}
      />
      <WithdrawBlockedDialog
        open={withdrawOpen && account.blocked}
        unpaidAmount={account.facts.unpaidAmount}
        onClose={() => setWithdrawOpen(false)}
      />

      {/* 기본 아바타 글리프는 표시 이름의 첫 글자 (Avatar의 이니셜 규칙과 같은 재료) */}
      <ProfilePhotoDialog photo={photo} glyph={name.trim().charAt(0)} />
    </div>
  );
}
