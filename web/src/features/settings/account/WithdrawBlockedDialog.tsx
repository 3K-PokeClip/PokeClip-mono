'use client';

import { useRouter } from 'next/navigation';
import { useId } from 'react';
import { Button, Dialog } from '@/ui';
import styles from './AccountSettingsScreen.module.css';

// 차단 상태 (디자인 1p ③) — 미결제 잔액이 있으면 탈퇴 재확인 대신 이것이 뜬다.
// 막는 이유와 금액을 먼저 보이고, 풀 수 있는 자리(결제 내역)로 보낸다.
export function WithdrawBlockedDialog({
  open,
  unpaidAmount,
  onClose,
}: {
  open: boolean;
  unpaidAmount: number;
  onClose: () => void;
}) {
  const router = useRouter();
  const bodyId = useId();

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Content className={styles.blockedDialog} aria-describedby={bodyId}>
        <div className={styles.dialogEyebrow}>회원 탈퇴</div>
        <Dialog.Title className={styles.blockedTitle}>지금은 탈퇴할 수 없어요</Dialog.Title>
        <p id={bodyId} className={styles.blockedLead}>
          미결제 청구 금액이 남아 있어요. 결제를 완료한 뒤 다시 시도해 주세요.
        </p>
        <div className={styles.unpaidBox}>
          <span className={styles.factLabel}>미결제 금액</span>
          <span className={styles.unpaidValue}>₩{unpaidAmount.toLocaleString('ko-KR')}</span>
        </div>
        <div className={styles.blockedActions}>
          <Button variant="outline" size="md" fullWidth onClick={onClose}>
            닫기
          </Button>
          {/* 결제 화면은 아직 라우트가 없어 404로 떨어진다. 차단 상태 자체가 ?mock=blocked
              로만 도달하므로 실사용자는 만나지 않는다 — 결제 티켓이 들어오면 살아난다 */}
          <Button
            variant="solid"
            size="md"
            fullWidth
            onClick={() => router.push('/settings/billing')}
          >
            결제 내역으로 이동
          </Button>
        </div>
      </Dialog.Content>
    </Dialog>
  );
}
