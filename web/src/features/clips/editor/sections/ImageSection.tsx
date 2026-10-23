import { ImagePlus } from 'lucide-react';
import { PendingNote } from './PendingNote';
import styles from './sections.module.css';

// 로고·스티커 얹기. 이미지를 영상에 태우는 기능이 아직 없어 메뉴만 둔다(POK-389).

export function ImageSection() {
  return (
    <section className={styles.section} aria-label="이미지">
      <PendingNote title="이미지" hint="로고 · 스티커" />
      <button type="button" className={styles.dashedAction} disabled>
        <ImagePlus size={12} aria-hidden /> 로고 · 스티커 올리기
      </button>
    </section>
  );
}
