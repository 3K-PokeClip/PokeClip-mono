import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Portal } from '../../primitives/Portal';
import styles from './Toast.module.css';

export type ToastVariant = 'default' | 'success' | 'danger' | 'info';

export interface ToastOptions {
  title?: ReactNode;
  description?: ReactNode;
  variant?: ToastVariant;
  /** Auto-dismiss delay in ms; 0 keeps it until dismissed. */
  duration?: number;
}
interface ToastItem extends ToastOptions {
  id: string;
}

interface ToastContextValue {
  toast: (options: ToastOptions) => string;
  dismiss: (id: string) => void;
}
const ToastContext = createContext<ToastContextValue | null>(null);

function ToastCard({ toast, onClose }: { toast: ToastItem; onClose: () => void }) {
  const assertive = toast.variant === 'danger';
  return (
    <div
      className={styles.toast}
      data-variant={toast.variant ?? 'default'}
      role={assertive ? 'alert' : 'status'}
      aria-live={assertive ? 'assertive' : 'polite'}
      aria-atomic="true"
    >
      <div className={styles.body}>
        {toast.title != null ? <div className={styles.title}>{toast.title}</div> : null}
        {toast.description != null ? (
          <div className={styles.description}>{toast.description}</div>
        ) : null}
      </div>
      <button type="button" className={styles.close} aria-label="닫기" onClick={onClose}>
        <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
          <path
            d="M3.5 3.5l7 7M10.5 3.5l-7 7"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
          />
        </svg>
      </button>
    </div>
  );
}

export interface ToastProviderProps {
  children: ReactNode;
  /** Default auto-dismiss delay in ms. */
  duration?: number;
}

/** Provides an imperative `toast()` API and renders the toast viewport. */
export function ToastProvider({ children, duration = 4000 }: ToastProviderProps) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const counter = useRef(0);

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const toast = useCallback(
    (options: ToastOptions) => {
      counter.current += 1;
      const id = `pc-toast-${counter.current}`;
      setToasts((prev) => [...prev, { id, ...options }]);
      const d = options.duration ?? duration;
      if (d > 0) window.setTimeout(() => dismiss(id), d);
      return id;
    },
    [duration, dismiss],
  );

  return (
    <ToastContext.Provider value={{ toast, dismiss }}>
      {children}
      <Portal>
        <div className={styles.viewport}>
          {toasts.map((t) => (
            <ToastCard key={t.id} toast={t} onClose={() => dismiss(t.id)} />
          ))}
        </div>
      </Portal>
    </ToastContext.Provider>
  );
}

/** Access the imperative toast API. Requires a `ToastProvider` ancestor. */
export function useToast(): ToastContextValue {
  const c = useContext(ToastContext);
  if (!c) throw new Error('useToast must be used within a ToastProvider.');
  return c;
}
