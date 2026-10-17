import { CircleCheck, TriangleAlert, X } from 'lucide-preact';
import type { ToastTone } from '../lib/copy';
import styles from './dock.module.css';

export function Toast({
  message,
  onDismiss,
  tone = 'warning',
}: {
  message: string;
  onDismiss: () => void;
  tone?: ToastTone;
}) {
  const Icon = tone === 'success' ? CircleCheck : TriangleAlert;
  return (
    <div class={styles.toast} data-tone={tone} role="status">
      <Icon class={styles.toastIcon} size={15} strokeWidth={2.2} aria-hidden="true" />
      <span class={styles.toastText}>{message}</span>
      <button type="button" class={styles.toastClose} onClick={onDismiss} aria-label="알림 닫기">
        <X size={12} strokeWidth={2.4} aria-hidden="true" />
      </button>
    </div>
  );
}
