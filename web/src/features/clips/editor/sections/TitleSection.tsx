import { Lock, Sparkles } from 'lucide-react';
import { Badge } from '@/ui';
import styles from './sections.module.css';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 1d 자막 패널 하단 — "자막 → 제목" 의존을 잠금 상태로 보여준다.
// 자막을 만들기 전에는 추천을 계산할 재료가 없으므로 목록 자체를 내주지 않는다.

export function TitleSection({ state }: { state: ClipEditorMockState }) {
  if (state.titlesLocked) {
    return (
      <section className={`${styles.section} ${styles.locked}`} aria-label="AI 제목 추천">
        <div className={styles.sectionHead}>
          <Lock size={14} aria-hidden />
          <span className={styles.sectionTitle}>AI 제목 추천</span>
          <Badge tone="neutral" variant="soft" size="sm">
            잠김
          </Badge>
        </div>
        <p className={styles.hint}>AI 자막을 생성하면 자막 내용을 바탕으로 제목을 추천해 드려요.</p>
      </section>
    );
  }

  return (
    <section className={styles.section} aria-label="AI 제목 추천">
      <div className={styles.sectionHead}>
        <Sparkles size={13} aria-hidden />
        <span className={styles.sectionTitle}>AI 제목 추천</span>
        <Badge tone="accent" variant="soft" size="sm">
          자막 기반
        </Badge>
      </div>
      <div className={styles.titleList}>
        {state.titleSuggestions.map((suggestion) => (
          <button
            key={suggestion.id}
            type="button"
            className={styles.titleOption}
            aria-pressed={state.selectedTitleId === suggestion.id}
            onClick={() => state.selectTitle(suggestion.id)}
          >
            {suggestion.text}
          </button>
        ))}
      </div>
      <p className={styles.hint}>자막을 고치면 추천도 새로 계산돼요</p>
    </section>
  );
}
