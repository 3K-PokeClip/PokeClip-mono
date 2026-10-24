import { PendingNote } from './PendingNote';
import styles from './sections.module.css';

// 오디오 도구(POK-389): 방송 소리 트랙의 넣기 · 음량은 타임라인 트랙 줄에서 한다(같은 값을 두 곳에서 바꾸지 않게).
// 여기에는 그 안내와, 아직 준비 중인 음악 · 효과음만 둔다.

export function AudioSection() {
  return (
    <section className={styles.section} aria-label="오디오">
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>방송 소리</span>
      </div>
      <p className={styles.hint}>
        타임라인 트랙 줄에서 넣고 빼요 · 음량은 숫자를 누르거나 좌우로 끌어요
      </p>

      <div className={styles.divider} />
      <PendingNote title="음악 · 효과음" />
    </section>
  );
}
