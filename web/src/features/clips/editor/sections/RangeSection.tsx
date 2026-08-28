import styles from './sections.module.css';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 1d 구간 도구 — 선택 구간의 시작·끝과 남은 길이. 핸들 자체는 타임라인에 있다.

export function RangeSection({ state }: { state: ClipEditorMockState }) {
  return (
    <section className={styles.section} aria-label="구간 선택">
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>구간 선택</span>
      </div>
      <p className={styles.hint}>핸들을 드래그해서 조절 · 최대 3:00</p>

      <div className={styles.timecodeRow}>
        <span className={styles.timecodeLabel}>시작</span>
        <span className={styles.timecodeBox}>{state.rangeStartLabel}</span>
      </div>
      <div className={styles.timecodeRow}>
        <span className={styles.timecodeLabel}>끝</span>
        <span className={styles.timecodeBox}>{state.rangeEndLabel}</span>
      </div>

      <div className={styles.gaugeRow}>
        <div className={styles.gaugeTrack}>
          <div
            className={styles.gaugeFill}
            style={{ width: `${state.rangeGaugeFraction * 100}%` }}
          />
        </div>
        <span className={styles.gaugeLabel}>{state.rangeGaugeLabel}</span>
      </div>
    </section>
  );
}
