'use client';

import { Button, Dialog } from '@/ui';
import styles from './PluginSettingsScreen.module.css';

// 재발급 확인 모달 (POK-102 완료조건) — 기존 키가 유예 없이 즉시 죽는다는 사실을
// 실행 전에 알린다. ADR-019가 방송 중 재발급을 서버에서 막더라도, 화면이 먼저
// 경고해야 "방송 끊김" 사고를 클릭 전에 막을 수 있다.
export function RotateConfirmDialog({
  open,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <Dialog.Content>
        <Dialog.Title>연동 코드를 재발급할까요?</Dialog.Title>
        {/* "기존 코드"는 약속하지 않는다 — rotate는 미사용 페어링 코드를 무효화하지 못한다 (리뷰 #73) */}
        <Dialog.Description>
          기존 스트림 키가 즉시 만료됩니다 — 방송 중이라면 송출이 끊겨요. 새 코드는 발급 직후 한
          번만 표시됩니다.
        </Dialog.Description>
        <div className={styles.confirmActions}>
          <Dialog.Close>
            <Button variant="ghost" size="sm">
              취소
            </Button>
          </Dialog.Close>
          <Button variant="danger" size="sm" onClick={onConfirm}>
            지금 재발급
          </Button>
        </div>
      </Dialog.Content>
    </Dialog>
  );
}
