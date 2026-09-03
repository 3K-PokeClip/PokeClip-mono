'use client';

import { useRef, type ReactNode, type RefObject } from 'react';
import { CropOverlay } from './CropOverlay';
import { CropResult } from './CropResult';
import { Segmented } from './Segmented';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d 가운데 — 레이아웃 세그먼트 + 「클립 만들기」(소스와 크롭 사각형) + 「클립 미리보기」(결과).
//
// 소스를 통째로 보여주고 그 위의 사각형을 잡게 한다. 미리보기 안에서 영상을 끄는 방식보다
// **잡은 영역이 소스의 어디인지**가 눈에 보이고, 상하분할처럼 한 소스에서 두 영역을 고를 때
// 둘의 관계가 드러난다 (POK-109).

export function PreviewCanvas({
  state,
  videoNode = null,
  videoRef,
}: {
  state: ClipEditorMockState;
  /** 소스 판에 깔리는 실재생 `<video>`. 없으면 자리 표시자만 그린다 */
  videoNode?: ReactNode;
  /** 결과 칸이 이 영상에서 잘라 그린다 */
  videoRef?: RefObject<HTMLVideoElement | null>;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const split = state.layout === 'split';
  // 상하분할이 아니면 첫 칸만 쓴다 — 잡을 영역이 하나다
  const panes = split ? state.sources : state.sources.slice(0, 1);

  const subtitleText =
    state.subtitle.status === 'ready'
      ? (state.subtitle.items.find((item) => item.id === state.selectedSubtitleId)?.text ?? null)
      : null;

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
        <span className={styles.previewHint}>
          사각형을 끌어 위치 · 모서리를 끌어 범위 · 더블클릭으로 되돌리기
        </span>
      </div>

      {/* data-preview-stage: 타임라인 높이 상한을 재는 표식. 미리보기 칸에서 신축하는 건
          이 무대뿐이라, 여기 남은 여유가 곧 타임라인이 더 커질 수 있는 양이다 (POK-237). */}
      <div className={styles.stage} data-preview-stage>
        <section className={styles.sourceSide} aria-label="클립 만들기">
          <span className={styles.sideLabel}>클립 만들기</span>
          <div
            className={styles.sourcePanel}
            ref={panelRef}
            // 사각형은 판 기준 %로 놓인다 — 판이 소스와 다른 비율이면 영상 위에서 어긋난다
            style={{ aspectRatio: state.sourceAspect ?? 16 / 9 }}
          >
            {videoNode !== null ? (
              // aria-hidden: 그림이다. 조작은 위에 얹힌 사각형이 받는다.
              <div className={styles.sourceVideo} aria-hidden>
                {videoNode}
              </div>
            ) : (
              <span className={styles.sourcePlaceholder}>소스 영상</span>
            )}
            {/* 사각형 밖을 어둡게 — 무엇이 잘려 나가는지 한눈에 보인다 */}
            {panes.some((pane) => pane.crop !== undefined) ? (
              <div className={styles.sourceScrim} aria-hidden />
            ) : null}
            {panes.map((pane, index) => (
              <CropOverlay
                key={pane.id}
                state={state}
                source={pane}
                index={index}
                panelRef={panelRef}
              />
            ))}
          </div>
        </section>

        <section className={styles.resultSide} aria-label="클립 미리보기">
          <span className={styles.sideLabel}>클립 미리보기</span>
          <div className={styles.resultFrame} data-layout={state.layout}>
            {panes.map((pane, index) => (
              <CropResult
                key={pane.id}
                videoRef={videoRef}
                crop={pane.crop}
                label={pane.placeholder}
                flex={split && index === 0 ? state.splitRatio : 1}
              />
            ))}
            {subtitleText !== null ? (
              <span className={styles.burnedSubtitle}>“{subtitleText}”</span>
            ) : null}
          </div>
          <span className={styles.stageNote}>
            자막 {state.subtitleModeLabel} · {state.speed}× 배속
          </span>
        </section>
      </div>
    </div>
  );
}
