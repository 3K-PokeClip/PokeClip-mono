import { ConfirmDialog } from './ConfirmDialog';

// 연결 해제 확인. 다시 붙으려면 새 코드가 필요하므로 한 번 묻는다.
export function UnpairDialog({ onCancel, onConfirm }: { onCancel: () => void; onConfirm: () => Promise<void> }) {
  return (
    <ConfirmDialog
      id="unpair"
      title="PokeClip 연결을 해제할까요?"
      description="다시 연결하려면 새 8자리 코드를 발급받아 입력해야 해요."
      confirmLabel="해제"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
