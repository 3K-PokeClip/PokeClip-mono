import { useRef, type KeyboardEvent, type PointerEvent } from 'react';
import {
  Camera,
  Captions,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  Film,
} from 'lucide-react';
import { IconButton } from '@/ui';
import { formatUptime } from '@/features/player/playerMath';
import styles from './StudioScreen.module.css';
import { ThumbnailTile } from './ThumbnailTile';
import { TimelineTrackRow } from './TimelineTrackRow';
import { VideoFilmstrip } from './VideoFilmstrip';
import {
  MAX_RANGE_SECONDS,
  MAX_RANGE_TEXT,
  MIN_RANGE_SECONDS,
  rulerLabel,
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

/** 썸네일 칸에 적는 쇼츠 안 시각. 반올림하면 끝에서 길이보다 크게 보이니 내림한다 */
function shortClock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export function MultitrackTimeline({
  state,
  headroom,
}: {
  state: ClipEditorMockState;
  /**
   * 지금 더 커질 수 있는 양(px)을 재 준다 — 화면만 레이아웃을 안다.
   * 상수가 아닌 이유: 창 높이와 트랜스포트 줄바꿈에 따라 매번 달라진다 (POK-237).
   */
  headroom: () => number;
}) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const draggingEdge = useRef<'start' | 'end' | null>(null);
  const laneAreaRef = useRef<HTMLDivElement>(null);
  const heightDragOrigin = useRef<{ y: number; height: number; ceiling: number } | null>(null);

  const startFraction = secondsToFraction(state.range.startSeconds, state.view);
  // 자막 줄 조각: 자리를 아는 줄만, 창 밖으로 완전히 벗어난 줄은 빼고
  const subtitleBlocks =
    state.subtitle.status === 'ready'
      ? state.subtitle.items.flatMap((item) => {
          if (item.startSeconds === undefined || item.endSeconds === undefined) return [];
          const left = secondsToFraction(item.startSeconds, state.view);
          const right = secondsToFraction(item.endSeconds, state.view);
          if (right <= 0 || left >= 1) return [];
          const from = Math.max(0, left);
          return [{ id: item.id, text: item.text, left: from, width: Math.min(1, right) - from }];
        })
      : [];
  const endFraction = secondsToFraction(state.range.endSeconds, state.view);
  const playheadFraction = secondsToFraction(state.playheadSeconds, state.view);
  const playheadVisible =
    state.playheadSeconds >= state.view.startSeconds &&
    state.playheadSeconds <= state.view.endSeconds;

  const moveEdgeFromPointer = (edge: 'start' | 'end', clientX: number) => {
    const overlay = overlayRef.current;
    if (overlay === null) return;
    const seconds = secondsFromPointer(overlay.getBoundingClientRect(), clientX, state.view);
    if (seconds === null) return;
    state.setRangeEdge(edge, seconds);
  };

  // 재생 위치 옮기기(POK-389): 타임라인·윗줄 눈금을 누르면 그 시점으로, 누른 채 끌면 따라간다. 눈금과 덮개는 가로 범위가
  // 같다(둘 다 이름 칸 뒤부터 끝까지). 덮개 좌표 하나로 잰다
  const seekFromPointer = (clientX: number) => {
    const overlay = overlayRef.current;
    if (overlay === null) return;
    const seconds = secondsFromPointer(overlay.getBoundingClientRect(), clientX, state.view);
    if (seconds !== null) state.seekTo(seconds);
  };

  // 끄는 동안은 창을 붙잡는다: 키운 창은 재생 위치를 따라 넘어가므로, 안 붙잡으면 끌던 자리 밑에서 창이 움직인다
  const scrubGesture = useRef<number | null>(null);
  const onScrubPointerDown = (event: PointerEvent<HTMLElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    seekFromPointer(event.clientX);
    scrubGesture.current = state.beginGesture();
  };

  const onScrubPointerMove = (event: PointerEvent<HTMLElement>) => {
    if (!event.currentTarget.hasPointerCapture(event.pointerId)) return;
    seekFromPointer(event.clientX);
  };

  const onScrubPointerUp = () => {
    if (scrubGesture.current !== null) state.endGesture(scrubGesture.current);
    scrubGesture.current = null;
  };

  // 키운 창 밖으로 나간 구간 끝(POK-389): 가장자리에 표시하고 누르면 그리로 간다(손잡이를 잃지 않게)
  // 왼쪽 표시: 시작점이 왼쪽 밖이면 「시작점」, 구간이 통째로 왼쪽 밖이면 「구간」. 오른쪽도 같다
  const leftMarker =
    state.range.endSeconds < state.view.startSeconds
      ? { label: '구간', seconds: state.range.startSeconds }
      : state.range.startSeconds < state.view.startSeconds
        ? { label: '시작점', seconds: state.range.startSeconds }
        : null;
  const rightMarker =
    state.range.startSeconds > state.view.endSeconds
      ? { label: '구간', seconds: state.range.startSeconds }
      : state.range.endSeconds > state.view.endSeconds
        ? // 끝 바로 앞으로: 끝에 서면 구간 반복이 처음으로 되돌린다
          {
            label: '끝점',
            seconds: Math.max(state.range.startSeconds, state.range.endSeconds - 0.1),
          }
        : null;

  const onHandlePointerDown = (edge: 'start' | 'end') => (event: PointerEvent<HTMLElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    draggingEdge.current = edge;
    // 드래그 한 번을 실행취소 한 칸으로 묶고, 그 동안 타임라인 창을 붙잡는다
    state.beginGesture();
    // 여기서 바로 옮기지 않는다 — 구간이 창보다 넓어 핸들이 가장자리에 클램프돼 있으면
    // 끌지도 않았는데 클릭만으로 경계가 창 끝 시각으로 점프한다. 움직임은 pointermove가 낸다.
  };

  const onHandlePointerMove = (event: PointerEvent<HTMLElement>) => {
    const edge = draggingEdge.current;
    if (edge === null || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    moveEdgeFromPointer(edge, event.clientX);
  };

  // pointercancel·캡처 상실도 끝이다 — 놓친 채로 두면 창이 고정된 채 남고
  // 이후 편집이 계속 같은 히스토리 항목을 덮어쓴다
  const onHandlePointerUp = () => {
    if (draggingEdge.current !== null) state.endGesture();
    draggingEdge.current = null;
  };

  const onHandleKeyDown = (edge: 'start' | 'end') => (event: KeyboardEvent<HTMLElement>) => {
    // 자동반복은 버린다 — 누르고 있으면 키 반복마다 히스토리가 한 칸씩 쌓여
    // 상한을 밀어낸다 (editorKeys.ts가 같은 이유로 버리는 것과 같다)
    if (event.repeat) return;
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
      state.timelineHeight ?? laneAreaRef.current?.getBoundingClientRect().height ?? 0;
    // 상한은 드래그 시작에 한 번만 잰다 — 끄는 도중에 재면 이미 줄어든 무대를 보고
    // 여유가 0으로 수렴해, 손잡이가 커서를 못 따라오고 눌어붙는다.
    heightDragOrigin.current = { y: event.clientY, height: current, ceiling: current + headroom() };
  };

  const onResizePointerMove = (event: PointerEvent<HTMLButtonElement>) => {
    const origin = heightDragOrigin.current;
    if (origin === null || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    // 위로 끌면 타임라인이 커진다 — 손잡이가 위 모서리에 있다
    state.setTimelineHeight(origin.height + (origin.y - event.clientY), origin.ceiling);
  };

  const onResizeKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const current =
      state.timelineHeight ?? laneAreaRef.current?.getBoundingClientRect().height ?? 0;
    // 키보드는 한 걸음마다 새로 잰다 — 드래그와 달리 걸음 사이에 레이아웃이 이미 정착했다
    const ceiling = current + headroom();
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      state.setTimelineHeight(current + HEIGHT_STEP_PX, ceiling);
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      state.setTimelineHeight(current - HEIGHT_STEP_PX, ceiling);
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
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="타임라인 축소"
            // 범위보다 넓힐 수 없으면(카드 창 등) 꺼 둔다: 눌러도 화면이 그대로다
            disabled={!state.canZoomOut}
            onClick={state.zoomOut}
          >
            −
          </IconButton>
          <span className={styles.zoomValue}>{state.zoomLabel}</span>
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="타임라인 확대"
            disabled={state.zoom >= Math.max(...state.zoomLevels)}
            onClick={state.zoomIn}
          >
            ＋
          </IconButton>
        </div>
      </div>

      {!state.timelineCollapsed ? (
        <>
          <div
            className={styles.ruler}
            data-ruler
            onPointerDown={onScrubPointerDown}
            onPointerMove={onScrubPointerMove}
            onPointerUp={onScrubPointerUp}
            onPointerCancel={onScrubPointerUp}
            onLostPointerCapture={onScrubPointerUp}
          >
            {rulerTicks(state.view).map((tick) => (
              <span key={tick}>
                {rulerLabel(tick, state.view.endSeconds - state.view.startSeconds)}
              </span>
            ))}
          </div>

          <div
            className={styles.laneArea}
            ref={laneAreaRef}
            // 높이가 걸리는 자리를 이름으로 집게 한다 — 인라인 스타일 모양으로 찾으면
            // 다른 요소가 px를 쓰기 시작하는 순간 엉뚱한 것을 집는다 (data-preview-stage와 같은 결)
            data-timeline-lanes
            // 기본(null)은 트랙 수에 맞춘다. 사용자가 정한 값은 height로 걸어야
            // 내용보다 크게도 늘릴 수 있다 — maxHeight만으로는 줄이기만 된다.
            style={
              state.timelineHeight === null ? undefined : { height: `${state.timelineHeight}px` }
            }
          >
            {/* 덮개(구간 상자·재생선)의 기준은 스크롤 상자가 아니라 이 안쪽 상자다: 스크롤 상자에 바로 붙이면 덮개 높이가
                보이는 창 높이에 묶여, 타임라인을 줄이고 아래로 내리면 아래 트랙이 구간 상자 밖으로 빠진다 */}
            <div className={styles.laneContent}>
              <div className={styles.lanes}>
                {subtitleBlocks.length > 0 ? (
                  <div className={styles.trackRow}>
                    <span className={styles.trackLabel}>
                      {/* 아이콘은 소리 줄의 스피커와 같은 칸에 둬서 이름이 한 줄로 맞는다 */}
                      <span className={styles.trackLead}>
                        <Captions size={13} aria-hidden />
                      </span>
                      <span data-row-label>자막</span>
                    </span>
                    <div className={styles.lane} data-kind="subtitle">
                      {subtitleBlocks.map((block) => (
                        <span
                          key={block.id}
                          className={styles.subtitleBlock}
                          data-subtitle-block
                          style={{ left: `${block.left * 100}%`, width: `${block.width * 100}%` }}
                        >
                          {block.text}
                        </span>
                      ))}
                    </div>
                  </div>
                ) : null}
                <div className={styles.trackRow}>
                  <span className={styles.trackLabel}>
                    <span className={styles.trackLead}>
                      <Film size={13} aria-hidden />
                    </span>
                    <span data-row-label>영상</span>
                    <ThumbnailTile
                      seconds={state.thumbnailSeconds}
                      label={shortClock(
                        (state.thumbnailSeconds ?? state.range.startSeconds) -
                          state.range.startSeconds,
                      )}
                      source={state.filmstripSource}
                      onSeek={state.seekTo}
                    />
                  </span>
                  <div className={styles.lane} data-kind="video">
                    {state.filmstripSource !== null ? (
                      <VideoFilmstrip source={state.filmstripSource} view={state.view} />
                    ) : (
                      <div className={styles.laneFilmstrip} aria-hidden />
                    )}
                  </div>
                </div>
                {state.tracks.map((track, index) => {
                  const levels = state.trackLevels;
                  const values = levels?.tracks[index];
                  return (
                    <TimelineTrackRow
                      key={track.id}
                      track={track}
                      onToggleMute={state.toggleTrackMute}
                      volumeControls={state}
                      levels={
                        levels === undefined
                          ? undefined
                          : levels !== null && values !== undefined
                            ? {
                                values,
                                startSeconds: levels.startSeconds,
                                stepSeconds: levels.stepSeconds,
                              }
                            : null
                      }
                      view={state.view}
                    />
                  );
                })}
              </div>

              <div className={styles.overlay} ref={overlayRef}>
                {/* 누르거나 끌어 재생 위치를 옮기는 바닥. 구간 손잡이는 이 뒤에 그려 위에 놓인다. 키로는 ← → 가 같은 일을 한다 */}
                <div
                  className={styles.scrub}
                  data-scrub
                  aria-hidden
                  onPointerDown={onScrubPointerDown}
                  onPointerMove={onScrubPointerMove}
                  onPointerUp={onScrubPointerUp}
                  onPointerCancel={onScrubPointerUp}
                  onLostPointerCapture={onScrubPointerUp}
                />
                {/* 쇼츠 밖은 양쪽을 흐리게 덮는다: 쓰는 구간만 밝게(알파컷 방식, POK-389) */}
                <div
                  className={styles.outsideMask}
                  data-outside
                  style={{ left: 0, width: `${Math.max(0, startFraction) * 100}%` }}
                />
                <div
                  className={styles.outsideMask}
                  data-outside
                  style={{
                    left: `${Math.min(1, endFraction) * 100}%`,
                    right: 0,
                  }}
                />
                <div
                  className={styles.rangeBox}
                  style={{
                    left: `${startFraction * 100}%`,
                    width: `${(endFraction - startFraction) * 100}%`,
                  }}
                >
                  {/* 핸들은 늘 그린다. 키워서 창 밖으로 나간 핸들은 덮개가 가로로 잘라 안 보이고 가장자리 표시가 대신한다.
                    빼 버리면 그 핸들에 있던 키보드 초점이 사라진다(로컬 리뷰 1라운드) */}
                  {(['start', 'end'] as const).map((edge) => (
                    <button
                      key={edge}
                      type="button"
                      role="slider"
                      aria-label={edge === 'start' ? '구간 시작점' : '구간 끝점'}
                      // 이름·키 조작이 「경계 위치」를 뜻하므로 값도 위치로 읽어 준다.
                      // 길이를 실으면 시작점에서 ArrowRight(증가)에 값이 줄어드는 모순이 생기고
                      // 두 핸들이 늘 같은 숫자를 말한다.
                      // 길이 상한(3분)까지 반영한다. 안 하면 「0부터」라 안내해 놓고
                      // 그쪽으로는 값이 움직이지 않는다
                      aria-valuemin={
                        edge === 'start'
                          ? Math.max(0, state.range.endSeconds - MAX_RANGE_SECONDS)
                          : state.range.startSeconds + MIN_RANGE_SECONDS
                      }
                      aria-valuemax={
                        edge === 'start'
                          ? state.range.endSeconds - MIN_RANGE_SECONDS
                          : Math.min(
                              state.sourceDurationSeconds,
                              state.range.startSeconds + MAX_RANGE_SECONDS,
                            )
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
                      onPointerCancel={onHandlePointerUp}
                      onLostPointerCapture={onHandlePointerUp}
                      onKeyDown={onHandleKeyDown(edge)}
                    />
                  ))}
                </div>
                {leftMarker !== null ? (
                  <button
                    type="button"
                    className={`${styles.edgeMarker} ${styles.edgeMarkerStart}`}
                    aria-label={`${leftMarker.label}으로 가기`}
                    onClick={() => state.seekTo(leftMarker.seconds)}
                  >
                    <ChevronLeft size={11} aria-hidden />
                    {leftMarker.label}
                  </button>
                ) : null}
                {rightMarker !== null ? (
                  <button
                    type="button"
                    className={`${styles.edgeMarker} ${styles.edgeMarkerEnd}`}
                    aria-label={`${rightMarker.label}으로 가기`}
                    onClick={() => state.seekTo(rightMarker.seconds)}
                  >
                    {rightMarker.label}
                    <ChevronRight size={11} aria-hidden />
                  </button>
                ) : null}
                {/* 창 밖이면 그리지 않는다. 클램프된 채 두면 눈금 가장자리에 딱 붙어
                  실제와 다른 시각을 현재 위치처럼 읽게 한다. */}
                {state.thumbnailSeconds !== null &&
                state.thumbnailSeconds >= state.view.startSeconds &&
                state.thumbnailSeconds <= state.view.endSeconds ? (
                  <span
                    data-thumbnail-marker
                    className={styles.thumbnailMarker}
                    style={{
                      left: `${secondsToFraction(state.thumbnailSeconds, state.view) * 100}%`,
                    }}
                    aria-hidden
                  >
                    <Camera size={9} />
                  </span>
                ) : null}
                {playheadVisible ? (
                  <div className={styles.playhead} style={{ left: `${playheadFraction * 100}%` }}>
                    <span className={styles.playheadTip} aria-hidden />
                  </div>
                ) : null}
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
              {MIN_RANGE_SECONDS}초 미만, {MAX_RANGE_TEXT} 초과로는 핸들이 움직이지 않아요
            </span>
          </div>
        </>
      ) : null}
    </section>
  );
}
