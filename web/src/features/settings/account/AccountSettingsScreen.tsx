'use client';

import { useEffect, useRef, useState } from 'react';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { LoginCard } from './LoginCard';
import { ProfileCard } from './ProfileCard';
import { ProfilePhotoDialog } from './ProfilePhotoDialog';
import { useAccountMockState } from './useAccountMockState';
import { useAccountState } from './useAccountState';
import { firstGrapheme } from './profilePresets';
import { useProfilePhotoState } from './useProfilePhotoState';
import { WithdrawBlockedDialog } from './WithdrawBlockedDialog';
import { WithdrawDialog } from './WithdrawDialog';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 설정 · 계정 (POK-206 → 실서버 배선 POK-208). 조립만 한다 — 이름·사진 저장은
// useAccountState(실서버), 탈퇴는 useAccountMockState(아직 목업), 사진 모달의 단계·타이머는
// useProfilePhotoState가 갖는다.
//
// ⚠ 탈퇴만 아직 서버로 가지 않는다 — 창구(DELETE /api/auth/me, POK-171)는 생겼지만 웹 배선은
// 별도 티켓이다. 눌리지만 아무것도 지우지 않고 로컬 세션만 접는다.
export function AccountSettingsScreen() {
  const account = useAccountState();
  const mock = useAccountMockState();
  const photo = useProfilePhotoState({
    upload: account.uploadPhoto,
    onCanceled: account.refetchMe,
  });
  const [withdrawOpen, setWithdrawOpen] = useState(false);

  // 🔴 다른 탭이 계정을 바꾸면 열려 있던 사진 모달을 접는다.
  //
  // 계정 교체는 쿼리 캐시만 비우고 이 화면을 언마운트하지 않는다(providers.tsx) — 그대로 두면
  // 모달이 **A가 고른 그림**을 든 채 남고, 「적용」을 누르면 apiFetch가 **지금(B의) 토큰**으로
  // 보내 A의 사진이 B의 아바타가 된다. close()가 진행 중이던 업로드도 함께 끊는다.
  // 탈퇴 모달도 같이 닫는다 — A에게 하던 확인을 B의 이름으로 이어 가면 안 된다.
  const owner = account.me?.id;
  const closePhoto = photo.close;
  const previousOwner = useRef(owner);
  useEffect(() => {
    if (owner === undefined || previousOwner.current === owner) return;
    previousOwner.current = owner;
    closePhoto();
    setWithdrawOpen(false);
  }, [owner, closePhoto]);

  const name = account.me?.name ?? '';
  const email = account.me?.email ?? '';

  return (
    <div className={styles.screen}>
      {/* 1p의 상단은 제목 한 줄이다 — 보조 설명을 두지 않는다 */}
      <SettingsPageHeader title="계정" />

      <ProfileCard
        name={name}
        email={email}
        photoUrl={account.me?.profileImageUrl ?? undefined}
        draftName={account.draftName}
        dirty={account.dirty}
        editable={account.editable}
        saving={account.saving}
        nameError={account.nameError}
        onDraftNameChange={account.setDraftName}
        onSave={account.saveName}
        onEditPhoto={photo.open}
      />

      <LoginCard email={email} />

      <div className={styles.withdrawRow}>
        {/* 저장·사진 수정과 같은 가드다 — me가 없으면 모달 인사말의 이름이 비어
            「님, 정말 탈퇴하시나요?」가 된다 */}
        <button
          type="button"
          className={styles.withdrawLink}
          disabled={!account.editable}
          onClick={() => setWithdrawOpen(true)}
        >
          탈퇴하기
        </button>
      </div>

      {/* 막힌 상태에서는 재확인 대신 이유를 보여 준다 — 두 모달이 동시에 뜨지 않는다 */}
      <WithdrawDialog
        open={withdrawOpen && !mock.blocked}
        name={name}
        facts={mock.facts}
        onCancel={() => setWithdrawOpen(false)}
        onConfirm={mock.completeWithdraw}
      />
      <WithdrawBlockedDialog
        open={withdrawOpen && mock.blocked}
        unpaidAmount={mock.facts.unpaidAmount}
        onClose={() => setWithdrawOpen(false)}
      />

      {/* 기본 아바타 글리프는 표시 이름의 첫 글자 — 이모지·국기를 쪼개지 않게 그래핌 단위 */}
      <ProfilePhotoDialog photo={photo} glyph={firstGrapheme(name)} />
    </div>
  );
}
