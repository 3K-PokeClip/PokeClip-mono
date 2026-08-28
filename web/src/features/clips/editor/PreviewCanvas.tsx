import { Image as ImageIcon } from 'lucide-react';
import { Segmented } from './Segmented';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d 가운데 — 레이아웃 세그먼트 + 9:16/1:1/상하분할 미리보기.
// 크롭 위치 드래그·소스 교체는 목업이라 실제 픽셀을 옮기지 않는다.

/** 상하분할 위/아래 소스 — 자리바꿈은 이 배열 순서만 뒤집는다 */
const SPLIT_SOURCES = [
  { key: 'game', badge: '소스 1 · 게임', placeholder: '게임 화면', tone: 'accent' as const },
  { key: 'cam', badge: '소스 2 · 캠', placeholder: '페이스캠', tone: 'point' as const },
];

export function PreviewCanvas({ state }: { state: ClipEditorMockState }) {
  const subtitleText =
    state.subtitle.status === 'ready'
      ? (state.subtitle.items.find((item) => item.id === state.selectedSubtitleId)?.text ?? null)
      : null;

  const split = state.layout === 'split';
  const sources = state.sourcesSwapped ? [...SPLIT_SOURCES].reverse() : SPLIT_SOURCES;
  const panes = split ? sources : sources.slice(0, 1);
  // 상하분할 경계 위치(%) — 시안 1.5 : 1
  const topShare = (state.splitRatio / (state.splitRatio + 1)) * 100;

  return (
    <div className={styles.preview}>
      <div className={styles.previewToolbar}>
        <span className={styles.previewToolbarLabel}>레이아웃</span>
        <Segmented
          label="레이아웃"
          options={state.layoutOptions}
          value={state.layout}
          onChange={state.setLayout}
        />
        <span className={styles.previewHint}>영역 드래그 = 크롭 위치 · 더블클릭 = 소스 교체</span>
      </div>

      <div className={styles.stage}>
        <div className={styles.frame} data-layout={state.layout}>
          {panes.map((source, index) => {
            const isTop = split && index === 0;
            const isBottom = split && index === 1;
            return (
              <div
                key={source.key}
                className={styles.sourcePane}
                data-position={isTop ? 'top' : undefined}
                style={{ flex: split ? (isTop ? state.splitRatio : 1) : 1 }}
                onDoubleClick={state.swapSources}
              >
                <span className={styles.sourcePlaceholder}>{source.placeholder}</span>
                <span className={styles.sourceBadge} data-tone={source.tone}>
                  {source.badge}
                </span>
                {index === 0 ? (
                  <span className={styles.overlayChip}>
                    <ImageIcon size={10} aria-hidden />
                    로고.png
                  </span>
                ) : null}
                {/* 자막은 시안에서 아래 소스(캠) 위에 얹힌다 — 분할이 아니면 유일한 소스 위 */}
                {subtitleText !== null && (isBottom || !split) ? (
                  <span className={styles.burnedSubtitle}>“{subtitleText}”</span>
                ) : null}
              </div>
            );
          })}
          {split ? (
            <button
              type="button"
              className={styles.splitHandle}
              style={{ top: `${topShare}%` }}
              // 드래그는 목업이라 두 소스를 맞바꾸는 것으로 대신한다 —
              // 키보드만 쓰는 사용자도 같은 결과에 닿아야 해서 버튼이다.
              onClick={state.swapSources}
              aria-label="상하 소스 자리 바꾸기"
            >
              ⋯
            </button>
          ) : null}
        </div>
        <span className={styles.stageNote}>
          미리보기 · 자막 {state.subtitleModeLabel} · {state.speed}× 배속
        </span>
      </div>
    </div>
  );
}
