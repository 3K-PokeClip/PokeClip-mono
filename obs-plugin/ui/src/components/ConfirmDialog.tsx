import { useEffect, useRef, useState } from 'preact/hooks';
import styles from './dock.module.css';

// 되돌리기 어려운 동작을 한 번 묻는 창 — 연결 해제(UnpairDialog)·서버 환경 전환(Settings)이 쓴다.
// CEF 독에서 window.confirm은 막히거나 OBS를 멈추는 네이티브 창이 되므로 페이지 안에 그린다.
export function ConfirmDialog({
  id,
  title,
  description,
  confirmLabel,
  onCancel,
  onConfirm,
}: {
  id: string;
  title: string;
  description: string;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: () => Promise<void>;
}) {
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
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-desc`}
        onClick={(e) => e.stopPropagation()}
      >
        <div>
          <h2 id={`${id}-title`} class={styles.cardTitle}>
            {title}
          </h2>
          <p id={`${id}-desc`} class={styles.cardDesc}>
            {description}
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
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
