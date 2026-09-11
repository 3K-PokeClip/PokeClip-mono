'use client';

import { useRef } from 'react';
import { CropOverlay } from './CropOverlay';
import { CropResult } from './CropResult';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d-a 가운데 (2026-09-08 갱신분).
//
// **레이아웃 도구일 때만 「원본 · 결과」 2판**이고, 다른 도구로 바꾸면 결과 미리보기 하나로
// 돌아간다 — 시안 캡션: "다른 도구(자막 · 오디오 등)로 바꾸면 중앙은 기존처럼 결과 미리보기
// 하나로 돌아가고 타임라인은 항상 유지돼요."
//
// 영역을 잡는 일은 레이아웃을 정할 때만 하는 일이라, 자막을 고치는 동안까지 원본 판이 자리를
// 차지하면 결과가 그만큼 작아진다.
//
// 원본 판은 아직 자리 표시자다 — 실제 영상은 미디어 경로가 이어지면 이 자리에 깔린다.
// 사각형은 판 기준 %로 놓이므로 그때도 좌표는 그대로다.

export function PreviewCanvas({ state }: { state: ClipEditorMockState }) {
  const panelRef = useRef<HTMLDivElement>(null);
  const editingLayout = state.activeTool === 'layout';

  const subtitleText =
    state.subtitle.status === 'ready'
      ? (state.subtitle.items.find((item) => item.id === state.selectedSubtitleId)?.text ?? null)
      : null;

  const result = (
    <div
      className={styles.resultFrame}
      style={{ ['--pc-ar' as string]: String(state.resultAspect) }}
      data-border={state.layout === 'split' && state.splitBorder ? 'on' : undefined}
    >
      {state.regions.map((region) => (
        <CropResult key={region.id} label={region.label} placement={region.placement} />
      ))}
      {subtitleText !== null ? (
        <span className={styles.burnedSubtitle} data-position={state.captionPosition}>
          “{subtitleText}”
        </span>
      ) : null}
    </div>
  );

  return (
    <div className={styles.preview}>
      {/* data-preview-stage: 타임라인 높이 상한을 재는 표식. 미리보기 칸에서 신축하는 건
          이 무대뿐이라, 여기 남은 여유가 곧 타임라인이 더 커질 수 있는 양이다 (POK-237). */}
      <div className={styles.stage} data-preview-stage data-mode={editingLayout ? 'both' : 'result'}>
        {editingLayout ? (
          <section className={styles.sourceSide} aria-label="원본">
            <span className={styles.sideLabel}>원본</span>
            <div
              className={styles.sourcePanel}
              ref={panelRef}
              // 프레임은 판 기준 %로 놓인다 — 판이 소스와 다른 비율이면 영상 위에서 어긋난다
              style={{ ['--pc-ar' as string]: String(state.sourceAspect) }}
            >
              <span className={styles.sourcePlaceholder}>원본 방송 화면 16:9</span>
              {state.regions.map((region, index) => (
                <CropOverlay
                  key={region.id}
                  state={state}
                  region={region}
                  index={index}
                  panelRef={panelRef}
                />
              ))}
            </div>
          </section>
        ) : null}

        <section className={editingLayout ? styles.resultSide : styles.resultOnly} aria-label="결과">
          {editingLayout ? <span className={styles.sideLabel}>결과</span> : null}
          {result}
          <span className={styles.stageNote}>
            {editingLayout ? '' : '미리보기 · '}
            {state.layoutLabel} · 자막 {state.subtitleModeLabel} · {state.speed}× 배속
          </span>
        </section>
      </div>
    </div>
  );
}
