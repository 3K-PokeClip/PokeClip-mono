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
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const [busy, setBusy] = useState(false);

  // 처음 열릴 때만 취소에 포커스를 준다(AudioTracks LiveDialog와 같다). 상태가 올 때마다 부모가 다시 그려져 onCancel이
  // 새 함수가 되므로, 콜백을 의존성에 두면 포커스가 그때마다 취소로 돌아간다(「바꾸기」로 옮긴 포커스가 Enter 직전에 빼앗긴다).
  useEffect(() => {
    cancelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancelRef.current();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

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
