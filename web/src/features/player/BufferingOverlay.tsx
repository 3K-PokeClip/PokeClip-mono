import { Spinner } from '@/ui';
import styles from './BufferingOverlay.module.css';

/**
 * 영상이 끊겨 기다리는 동안 가운데에 서는 스피너(POK-439). 마지막 장면과 컨트롤은 그대로 두고 포인터도 받지 않는다.
 * 낭독하지 않는다(소음). 영상 위라 라이트에서도 다크 색이다
 */
export function BufferingOverlay() {
  return (
    <div className={styles.overlay} data-buffering data-theme="dark" aria-hidden>
      <Spinner size="lg" aria-hidden />
    </div>
  );
}
