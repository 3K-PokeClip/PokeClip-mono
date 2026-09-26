import { TriangleAlert, X } from 'lucide-preact';
import styles from './dock.module.css';

export function Toast({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  return (
    <div class={styles.toast} role="status">
      <TriangleAlert class={styles.toastIcon} size={15} strokeWidth={2.2} aria-hidden="true" />
      <span class={styles.toastText}>{message}</span>
      <button type="button" class={styles.toastClose} onClick={onDismiss} aria-label="알림 닫기">
        <X size={12} strokeWidth={2.4} aria-hidden="true" />
      </button>
    </div>
  );
}
