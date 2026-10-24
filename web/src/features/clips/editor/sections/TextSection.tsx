import { Plus } from 'lucide-react';
import { PendingNote } from './PendingNote';
import styles from './sections.module.css';

// 쇼츠 위에 얹는 제목 글자(후킹 문구). 쇼츠 서비스들의 첫 메뉴가 「제목」이라 자리를 먼저 잡는다(POK-389).
// 글자를 영상에 태우는 기능이 아직 없어 모든 버튼이 꺼져 있다.

const SAMPLES = ['outline', 'yellow', 'glow', 'box'] as const;

export function TextSection() {
  return (
    <section className={styles.section} aria-label="텍스트">
      <PendingNote title="텍스트" />
      <button type="button" className={styles.dashedAction} disabled>
        <Plus size={12} aria-hidden /> 제목 넣기
      </button>
      <div className={styles.textSampleGrid} aria-hidden>
        {SAMPLES.map((sample) => (
          <span key={sample} className={styles.textSample} data-style={sample}>
            제목
          </span>
        ))}
      </div>
    </section>
  );
}
