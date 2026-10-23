'use client';

import { useRef, useState } from 'react';
import { Switch } from '@/ui';
import { CropOverlay } from './CropOverlay';
import { CropResult } from './CropResult';
import { VideoSurface } from './VideoSurface';
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

/**
 * 쇼츠 화면에서 플랫폼 버튼·설명에 가려지기 쉬운 자리(결과 폭·높이 대비). 유튜브 쇼츠의 오른쪽 버튼 줄과
 * 아래 제목·채널 줄을 어림한 값이다: 알파컷 「데드존 표시」와 피카클립의 「쇼츠 안전영역」 안내를 참고했다(POK-389).
 * 화면에만 그리는 보조선이라 영상에는 타지 않는다.
 */
export const SAFE_ZONE = { right: 0.12, bottom: 0.2 } as const;

/** 결과 비율 → 막대에 적는 말 */
function aspectLabel(aspect: number): string {
  if (Math.abs(aspect - 9 / 16) < 0.01) return '9:16';
  if (Math.abs(aspect - 1) < 0.01) return '1:1';
  return aspect < 1 ? '세로' : '가로';
}

export function PreviewCanvas({ state }: { state: ClipEditorMockState }) {
  const panelRef = useRef<HTMLDivElement>(null);
  // 보는 방식이라 편집 기록(실행취소)에 넣지 않는다
  const [safeZone, setSafeZone] = useState(false);
  const editingLayout = state.activeTool === 'layout';

  // CC만 고르면 영상에 글자가 타지 않는다 — 번인 미리보기도 그리지 않는다(렌더와 같다)
  const subtitleText =
    state.subtitle.status === 'ready' && state.subtitleMode !== 'cc'
      ? (state.subtitle.items.find((item) => item.id === state.selectedSubtitleId)?.text ?? null)
      : null;
  // 결과 설명은 결과에 실제로 들어갈 것을 말한다: 자막이 없는데 고른 방식(번인+CC)만 적으면 자막이 들어갈 것처럼
  // 읽힌다(QA 2026-10-11)
  const subtitleNote =
    state.subtitle.status === 'ready' && state.subtitle.items.length > 0
      ? state.subtitleModeLabel
      : state.subtitle.status === 'generating'
        ? '만드는 중'
        : '없음';

  const result = (
    <div
      className={styles.resultFrame}
      style={{ ['--pc-ar' as string]: String(state.resultAspect) }}
      data-border={state.layout === 'split' && state.splitBorder ? 'on' : undefined}
    >
      {state.regions.map((region) => (
        <CropResult
          key={region.id}
          label={region.label}
          placement={region.placement}
          video={state.previewVideo}
          crop={region.crop ?? null}
        />
      ))}
      {subtitleText !== null ? (
        <span
          className={styles.burnedSubtitle}
          data-position={state.captionPosition}
          // 「경계」는 분할 지분 자리다 — 50:50 이 아니면 한가운데가 아니다
          style={{ ['--pc-edge' as string]: `${state.splitRatio}%` }}
        >
          “{subtitleText}”
        </span>
      ) : null}
      {safeZone ? (
        <span
          className={styles.safeZone}
          data-safe-zone
          aria-hidden
          style={{
            ['--pc-safe-right' as string]: `${SAFE_ZONE.right * 100}%`,
            ['--pc-safe-bottom' as string]: `${SAFE_ZONE.bottom * 100}%`,
          }}
        />
      ) : null}
    </div>
  );

  return (
    <div className={styles.preview}>
      <div className={styles.previewBar} role="toolbar" aria-label="미리보기 보기">
        <span className={styles.previewChip}>{aspectLabel(state.resultAspect)}</span>
        <span className={styles.previewChip}>{state.layoutLabel}</span>
        <Switch
          size="sm"
          label="안전 영역"
          checked={safeZone}
          onChange={() => setSafeZone((on) => !on)}
        />
      </div>
      {/* data-preview-stage: 타임라인 높이 상한을 재는 표식. 미리보기 칸에서 신축하는 건
          이 무대뿐이라, 여기 남은 여유가 곧 타임라인이 더 커질 수 있는 양이다 (POK-237). */}
      <div className={styles.stage} data-preview-stage>
        {editingLayout ? (
          <section className={styles.sourceSide} aria-label="원본">
            <span className={styles.sideLabel}>원본</span>
            <div
              className={styles.sourcePanel}
              ref={panelRef}
              // 프레임은 판 기준 %로 놓인다 — 판이 소스와 다른 비율이면 영상 위에서 어긋난다
              style={{ ['--pc-ar' as string]: String(state.sourceAspect) }}
            >
              {state.previewVideo ? (
                <VideoSurface video={state.previewVideo} />
              ) : (
                <span className={styles.sourcePlaceholder}>원본 방송 화면 16:9</span>
              )}
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

        <section
          className={editingLayout ? styles.resultSide : styles.resultOnly}
          aria-label="결과"
        >
          {editingLayout ? <span className={styles.sideLabel}>결과</span> : null}
          {result}
          <span className={styles.stageNote}>
            {editingLayout ? '' : '미리보기 · '}
            {state.layoutLabel} · 자막 {subtitleNote}
          </span>
          {state.trackPreviewStatus === 'unavailable' ? (
            <span className={styles.stageNote} role="status">
              트랙별 미리 듣기를 못 불러왔어요 · 영상 소리로 들려요
            </span>
          ) : null}
        </section>
      </div>
    </div>
  );
}
