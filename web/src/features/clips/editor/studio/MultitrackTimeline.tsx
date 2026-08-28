import { useRef, type KeyboardEvent, type PointerEvent } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { IconButton } from '@/ui';
import { formatUptime } from '@/features/player/playerMath';
import styles from './StudioScreen.module.css';
import { TimelineTrackRow } from './TimelineTrackRow';
import {
  MAX_RANGE_TEXT,
  MIN_RANGE_SECONDS,
  rulerTicks,
  secondsFromPointer,
  secondsToFraction,
} from '../timelineMath';
import {
  EDITOR_SEEK_SHIFT_STEP_SECONDS,
  EDITOR_SEEK_STEP_SECONDS,
  SHORTCUT_LEGEND,
} from '../editorKeys';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 1d-a 하단 — 멀티트랙 타임라인. 도구를 바꿔도 이 줄들은 그대로 남는다.
//
// 구간 핸들은 마우스와 키보드가 같은 문을 쓴다(PlayerSeekBar와 같은 규약) —
// jsdom엔 레이아웃이 없어 포인터 경로를 렌더 테스트로 못 재는데,
// 키보드 경로가 같은 setRangeEdge로 들어가므로 경계 동작을 화면에서도 확인할 수 있다.

/** 높이 조절 키보드 한 걸음 */
const HEIGHT_STEP_PX = 24;

export function MultitrackTimeline({ state }: { state: ClipEditorMockState }) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const draggingEdge = useRef<'start' | 'end' | null>(null);
  const laneAreaRef = useRef<HTMLDivElement>(null);
  const heightDragOrigin = useRef<{ y: number; height: number } | null>(null);

  const startFraction = secondsToFraction(state.range.startSeconds, state.view);
  const endFraction = secondsToFraction(state.range.endSeconds, state.view);
  const playheadFraction = secondsToFraction(state.playheadSeconds, state.view);

  const moveEdgeFromPointer = (edge: 'start' | 'end', clientX: number) => {
    const overlay = overlayRef.current;
    if (overlay === null) return;
    const seconds = secondsFromPointer(overlay.getBoundingClientRect(), clientX, state.view);
    if (seconds === null) return;
    state.setRangeEdge(edge, seconds);
  };

  const onHandlePointerDown = (edge: 'start' | 'end') => (event: PointerEvent<HTMLElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    draggingEdge.current = edge;
    // 드래그 한 번을 실행취소 한 칸으로 묶고, 그 동안 타임라인 창을 붙잡는다
    state.beginGesture();
    moveEdgeFromPointer(edge, event.clientX);
  };

  const onHandlePointerMove = (event: PointerEvent<HTMLElement>) => {
    const edge = draggingEdge.current;
    if (edge === null || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    moveEdgeFromPointer(edge, event.clientX);
  };

  const onHandlePointerUp = () => {
    if (draggingEdge.current !== null) state.endGesture();
    draggingEdge.current = null;
  };

  const onHandleKeyDown = (edge: 'start' | 'end') => (event: KeyboardEvent<HTMLElement>) => {
    const step = event.shiftKey ? EDITOR_SEEK_SHIFT_STEP_SECONDS : EDITOR_SEEK_STEP_SECONDS;
    const current = edge === 'start' ? state.range.startSeconds : state.range.endSeconds;
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      state.setRangeEdge(edge, current - step);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      state.setRangeEdge(edge, current + step);
    }
  };

  const onResizePointerDown = (event: PointerEvent<HTMLButtonElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    // 아직 끈 적이 없으면(기본 높이) 지금 그려진 높이에서 이어서 끈다
    const current =
      state.timelineHeight ?? (laneAreaRef.current?.getBoundingClientRect().height ?? 0);
    heightDragOrigin.current = { y: event.clientY, height: current };
  };

  const onResizePointerMove = (event: PointerEvent<HTMLButtonElement>) => {
    const origin = heightDragOrigin.current;
    if (origin === null || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    // 위로 끌면 타임라인이 커진다 — 손잡이가 위 모서리에 있다
    state.setTimelineHeight(origin.height + (origin.y - event.clientY));
  };

  const onResizeKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const current =
      state.timelineHeight ?? (laneAreaRef.current?.getBoundingClientRect().height ?? 0);
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      state.setTimelineHeight(current + HEIGHT_STEP_PX);
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      state.setTimelineHeight(current - HEIGHT_STEP_PX);
    }
  };

  return (
    <section className={styles.timeline} aria-label="타임라인">
      {!state.timelineCollapsed ? (
        <button
          type="button"
          className={styles.resizeHandle}
          aria-label="타임라인 높이 조절"
          onPointerDown={onResizePointerDown}
          onPointerMove={onResizePointerMove}
          onPointerUp={() => {
            heightDragOrigin.current = null;
          }}
          onKeyDown={onResizeKeyDown}
          onDoubleClick={() => state.setTimelineHeight(null)}
        >
          <span className={styles.resizeGrip} aria-hidden />
        </button>
      ) : null}

      <div className={styles.timelineHead}>
        <span className={styles.timelineTitle}>타임라인</span>
        {state.timelineCollapsed ? (
          <span className={styles.timelineSummary}>
            선택 구간{' '}
            <b>
              {formatUptime(state.range.startSeconds)} – {formatUptime(state.range.endSeconds)}
            </b>{' '}
            · {state.rangeLengthLabel}
          </span>
        ) : null}
        <IconButton
          variant="ghost"
          size="sm"
          aria-label={state.timelineCollapsed ? '타임라인 열기' : '타임라인 접기'}
          onClick={state.toggleTimeline}
        >
          {state.timelineCollapsed ? (
            <ChevronUp size={14} aria-hidden />
          ) : (
            <ChevronDown size={14} aria-hidden />
          )}
        </IconButton>

        <div className={styles.zoomGroup}>
          <IconButton variant="ghost" size="sm" aria-label="타임라인 축소" onClick={state.zoomOut}>
            −
          </IconButton>
          <span className={styles.zoomValue}>{state.zoomLabel}</span>
          <IconButton variant="ghost" size="sm" aria-label="타임라인 확대" onClick={state.zoomIn}>
            ＋
          </IconButton>
        </div>
      </div>

      {!state.timelineCollapsed ? (
        <>
          <div className={styles.ruler}>
            {rulerTicks(state.view).map((tick) => (
              <span key={tick}>{formatUptime(tick)}</span>
            ))}
          </div>

          <div
            className={styles.laneArea}
            ref={laneAreaRef}
            // 기본(null)은 트랙 수에 맞춘다. 사용자가 정한 값은 height로 걸어야
            // 내용보다 크게도 늘릴 수 있다 — maxHeight만으로는 줄이기만 된다.
            style={
              state.timelineHeight === null ? undefined : { height: `${state.timelineHeight}px` }
            }
          >
            <div className={styles.lanes}>
              {state.tracks.map((track) => (
                <TimelineTrackRow
                  key={track.id}
                  track={track}
                  view={state.view}
                  selectedClipId={state.selectedClipId}
                  onSelectClip={state.selectClip}
                  onVolumeChange={state.setTrackVolume}
                />
              ))}
            </div>

            <div className={styles.overlay} ref={overlayRef}>
              <div
                className={styles.rangeBox}
                style={{
                  left: `${startFraction * 100}%`,
                  width: `${(endFraction - startFraction) * 100}%`,
                }}
              >
                {(['start', 'end'] as const).map((edge) => (
                  <button
                    key={edge}
                    type="button"
                    role="slider"
                    aria-label={edge === 'start' ? '구간 시작점' : '구간 끝점'}
                    // 이름·키 조작이 「경계 위치」를 뜻하므로 값도 위치로 읽어 준다.
                    // 길이를 실으면 시작점에서 ArrowRight(증가)에 값이 줄어드는 모순이 생기고
                    // 두 핸들이 늘 같은 숫자를 말한다.
                    aria-valuemin={edge === 'start' ? 0 : state.range.startSeconds + MIN_RANGE_SECONDS}
                    aria-valuemax={
                      edge === 'start'
                        ? state.range.endSeconds - MIN_RANGE_SECONDS
                        : state.sourceDurationSeconds
                    }
                    aria-valuenow={
                      edge === 'start' ? state.range.startSeconds : state.range.endSeconds
                    }
                    aria-valuetext={`${edge === 'start' ? state.rangeStartLabel : state.rangeEndLabel} · 구간 ${state.rangeLengthLabel}`}
                    className={`${styles.rangeHandle} ${
                      edge === 'start' ? styles.rangeHandleStart : styles.rangeHandleEnd
                    }`}
                    onPointerDown={onHandlePointerDown(edge)}
                    onPointerMove={onHandlePointerMove}
                    onPointerUp={onHandlePointerUp}
                    onKeyDown={onHandleKeyDown(edge)}
                  />
                ))}
              </div>
              <div className={styles.playhead} style={{ left: `${playheadFraction * 100}%` }}>
                <span className={styles.playheadTip} aria-hidden />
              </div>
            </div>
          </div>

          <div className={styles.legend}>
            {SHORTCUT_LEGEND.map((item) => (
              <span key={item.keys}>
                <b>{item.keys}</b> {item.label}
              </span>
            ))}
            <span className={styles.legendNote}>
              {MIN_RANGE_SECONDS}초 미만·{MAX_RANGE_TEXT} 초과로는 핸들이 움직이지 않아요
            </span>
          </div>

        </>
      ) : null}
    </section>
  );
}
