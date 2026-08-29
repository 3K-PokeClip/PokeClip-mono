import { Image as ImageIcon, X } from 'lucide-react';
import { IconButton } from '@/ui';
import styles from './sections.module.css';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 1d 이미지 도구 — 로고·스티커 얹기. 추가·제거는 목업이라 목록을 바꾸지 않는다.

export function ImageSection({ state }: { state: ClipEditorMockState }) {
  return (
    <section className={styles.section} aria-label="이미지">
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>이미지</span>
        <span className={styles.hint}>로고 · 스티커</span>
      </div>

      {state.images.map((image) => (
        <div key={image.id} className={styles.imageRow}>
          <span className={styles.imageThumb} aria-hidden>
            <ImageIcon size={12} />
          </span>
          <span className={styles.imageBody}>
            <span className={styles.imageName}>{image.name}</span>
            <span className={styles.imagePlacement}>{image.placement}</span>
          </span>
          <IconButton variant="ghost" size="sm" aria-label={`${image.name} 제거`}>
            <X size={12} aria-hidden />
          </IconButton>
        </div>
      ))}

      <button type="button" className={styles.dashedAction}>
        ＋ 이미지 추가 · PNG/JPG
      </button>
    </section>
  );
}
