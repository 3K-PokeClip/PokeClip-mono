import { TriangleAlert } from 'lucide-react';
import styles from './LiveScreen.module.css';

// 디자인 1b — 채팅 수집 끊김 경고. 재연결 액션은 수집 파이프라인 연동 전까지 자리만.
export function ChatWarningBanner() {
  return (
    <div className={styles.warningBanner} role="status">
      <TriangleAlert size={15} aria-hidden className={styles.warningIcon} />
      <span>
        채팅 수집이 잠시 끊겼어요 — 자동 감지가 일시 중단됩니다. 핫키 수동 마킹은 계속 동작해요.
      </span>
      <span className={styles.warningAction} aria-disabled="true">
        다시 연결
      </span>
    </div>
  );
}
