import { CircleCheck, TriangleAlert, X } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import type { ToastTone } from '../lib/copy';
import styles from './dock.module.css';

// 늘 붙어 있는 낭독 영역(App이 토스트와 같은 문구를 넘긴다). 토스트는 알릴 때마다 새로 붙어 라이브 영역이 미리 없으니
// 스크린리더가 첫 알림을 놓칠 수 있다 — 그래서 토스트 자체에는 role을 두지 않고 여기서 읽힌다. 비웠다가 잠깐 뒤 채워,
// 같은 문구가 거듭 와도(nonce가 바뀌면) 다시 읽는다. rAF 대신 타이머 — 독이 가려져 있으면 rAF가 멈춘다.
export function LiveAnnouncer({ message, nonce }: { message: string; nonce: unknown }) {
  const [text, setText] = useState('');
  useEffect(() => {
    setText('');
    if (!message) return;
    const t = window.setTimeout(() => setText(message), 50);
    return () => window.clearTimeout(t);
  }, [message, nonce]);
  return (
    <div class={styles.srOnly} role="status" aria-live="polite" aria-atomic="true">
      {text}
    </div>
  );
}

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
    <div class={styles.toast} data-tone={tone}>
      <Icon class={styles.toastIcon} size={15} strokeWidth={2.2} aria-hidden="true" />
      <span class={styles.toastText}>{message}</span>
      <button type="button" class={styles.toastClose} onClick={onDismiss} aria-label="알림 닫기">
        <X size={12} strokeWidth={2.4} aria-hidden="true" />
      </button>
    </div>
  );
}
