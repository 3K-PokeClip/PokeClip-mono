import { Music, Plus } from 'lucide-react';
import { Button, Tag } from '@/ui';
import { AudioSection } from './AudioSection';
import styles from './sections.module.css';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 1d BGM·효과 도구 — 곡 하나와 효과음 프리셋. 추가는 목업이라 자리만 잡는다.

export function BgmSfxSection({ state }: { state: ClipEditorMockState }) {
  return (
    <div className={styles.section}>
      <AudioSection state={state} kinds={['bgm', 'sfx']} title="BGM · 효과음" />
      <div className={styles.divider} />

      <section className={styles.section} aria-label="BGM">
        <div className={styles.trackRow}>
          <Music size={13} aria-hidden />
          <span className={styles.trackName}>{state.bgmLabel ?? 'BGM 없음'}</span>
          <Button variant="ghost" size="sm">
            ＋ 추가
          </Button>
        </div>
      </section>

      <section className={styles.section} aria-label="효과음">
        <div className={styles.fieldLabel}>효과음</div>
        <div className={styles.tagRow}>
          {state.sfxPresets.map((preset) => (
            <button key={preset} type="button" className={styles.tagButton}>
              <Tag variant="soft" size="sm">
                {preset}
              </Tag>
            </button>
          ))}
          <button type="button" className={styles.tagButton} aria-label="효과음 더 찾기">
            <Tag variant="soft" size="sm">
              <Plus size={11} aria-hidden />
            </Tag>
          </button>
        </div>
        <p className={styles.hint}>효과음은 플레이헤드 위치에 삽입돼요</p>
      </section>
    </div>
  );
}
