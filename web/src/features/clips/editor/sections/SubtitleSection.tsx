import { Captions, ChevronDown, Lock, Pencil } from 'lucide-react';
import { Badge, Button, Tag } from '@/ui';
import styles from './sections.module.css';
import { TitleSection } from './TitleSection';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 1d 자막 도구 — 생성 전/생성 중/생성 후 세 상태를 모두 가진다.
// 시안은 스튜디오형에서 "생성 후"를, 플로우형에서 "생성 전"을 보여주는데
// 화면이 둘 다 그릴 수 있어야 전이가 실제로 동작한다.

export function SubtitleSection({ state }: { state: ClipEditorMockState }) {
  const { subtitle } = state;

  if (subtitle.status !== 'ready') {
    const generating = subtitle.status === 'generating';
    return (
      <div className={styles.section}>
        <section className={styles.section} aria-label="AI 자막">
          <div className={styles.sectionHead}>
            <Captions size={14} aria-hidden />
            <span className={styles.sectionTitle}>AI 자막</span>
            <Badge tone="neutral" variant="soft" size="sm">
              {generating ? '생성 중' : '미생성'}
            </Badge>
          </div>
          <p className={styles.hint}>
            구간의 음성을 받아써서 자막을 만들어요. 만든 뒤 문장별로 고칠 수 있어요.
          </p>
          <Button
            variant="solid"
            size="sm"
            fullWidth
            loading={generating}
            onClick={state.generateSubtitles}
          >
            {generating ? '자막을 만드는 중' : `AI 자막 생성 · ${subtitle.estimateLabel}`}
          </Button>
          <p className={styles.pending}>
            <Lock size={11} aria-hidden />
            기본 폰트 제공 · 내 폰트 업로드는 준비 중
          </p>
        </section>
        <div className={styles.divider} />
        <TitleSection state={state} />
      </div>
    );
  }

  return (
    <div className={styles.section}>
      <section className={styles.section} aria-label="자막">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>자막</span>
          <Badge tone="accent" variant="soft" size="sm">
            AI 생성됨
          </Badge>
          <span className={styles.sectionAction}>
            <Button variant="ghost" size="sm" onClick={state.generateSubtitles}>
              재생성
            </Button>
          </span>
        </div>

        <div className={styles.tagRow} role="radiogroup" aria-label="자막 표시 방식">
          {state.subtitleModeOptions.map((option) => {
            const selected = option.value === state.subtitleMode;
            return (
              <button
                key={option.value}
                type="button"
                role="radio"
                aria-checked={selected}
                className={styles.tagButton}
                onClick={() => state.setSubtitleMode(option.value)}
              >
                <Tag variant={selected ? 'solid' : 'soft'} size="sm">
                  {option.label}
                </Tag>
              </button>
            );
          })}
        </div>

        <div>
          <div className={styles.fieldLabel} id="subtitle-font-label">
            폰트
          </div>
          <button type="button" className={styles.fontSelect} aria-labelledby="subtitle-font-label">
            {state.subtitleFontLabel}
            <ChevronDown size={13} aria-hidden className={styles.fontSelectChevron} />
          </button>
          {/* 폰트 업로드는 이 티켓 범위 밖 — 시안도 「준비 중」으로 적는다 */}
          <p className={styles.pending}>
            <Lock size={12} aria-hidden />내 폰트 업로드 · 준비 중
          </p>
        </div>

        <ul className={styles.subtitleList}>
          {subtitle.items.map((item) => {
            const current = item.id === state.selectedSubtitleId;
            return (
              <li key={item.id}>
                <button
                  type="button"
                  className={styles.subtitleRow}
                  aria-current={current}
                  data-non-speech={item.nonSpeech ? 'true' : undefined}
                  onClick={() => state.selectSubtitle(item.id)}
                >
                  <span className={styles.subtitleTime}>{item.timecode}</span>
                  <span className={styles.subtitleText}>{item.text}</span>
                  {current ? (
                    <Pencil size={12} aria-hidden className={styles.subtitleEditIcon} />
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      </section>

      <div className={styles.divider} />
      <TitleSection state={state} />
    </div>
  );
}
