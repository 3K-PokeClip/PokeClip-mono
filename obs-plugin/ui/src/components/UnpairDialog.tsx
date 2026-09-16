import { useEffect, useRef, useState } from 'preact/hooks';
import styles from './dock.module.css';

// 연결 해제 확인. 다시 붙으려면 새 코드가 필요하므로 한 번 묻는다.
export function UnpairDialog({ onCancel, onConfirm }: { onCancel: () => void; onConfirm: () => Promise<void> }) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    cancelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  return (
    <div class={styles.scrim} onClick={onCancel}>
      <div
        class={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="unpair-title"
        aria-describedby="unpair-desc"
        onClick={(e) => e.stopPropagation()}
      >
        <div>
          <h2 id="unpair-title" class={styles.cardTitle}>
            PokeClip 연결을 해제할까요?
          </h2>
          <p id="unpair-desc" class={styles.cardDesc}>
            다시 연결하려면 새 8자리 코드를 발급받아 입력해야 해요.
          </p>
        </div>
        <div class={styles.dialogActions}>
          <button ref={cancelRef} type="button" class={styles.dialogButton} data-kind="cancel" onClick={onCancel}>
            취소
          </button>
          <button
            type="button"
            class={styles.dialogButton}
            data-kind="danger"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              await onConfirm();
              setBusy(false);
            }}
          >
            해제
          </button>
        </div>
      </div>
    </div>
  );
}
