'use client';

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react';
import { EditorHeader } from '../EditorHeader';
import { PreviewCanvas } from '../PreviewCanvas';
import { TransportBar } from '../TransportBar';
import { MultitrackTimeline } from './MultitrackTimeline';
import { ToolPanel } from './ToolPanel';
import { ToolRail } from './ToolRail';
import styles from './StudioScreen.module.css';
import { EditorSourceGate } from './EditorSourceGate';
import { editorIntentForKey, type EditorIntent } from '../editorKeys';
import { editorSourceUrl, initialRangeForSource } from '../editorSource';
import { useEditorHlsPlayback } from '../useEditorHlsPlayback';
import { useEditorSource } from '../useEditorSource';
import { useClipEditorMockState, type ClipEditorOptions } from '../useClipEditorMockState';

// 시안 1d-a 클립 편집기(스튜디오형). 전폭 자체 헤더를 가지므로 ScreenContainer를 쓰지 않는다
// (라이브 대시보드 선례). 데이터·동작은 전부 useClipEditorMockState 뒤에 있다.

/**
 * 글자를 받는 곳 — 어떤 편집 단축키도 여기서는 비켜선다.
 * (⌘Z는 브라우저·OS의 실행취소가 먼저다)
 *
 * 체크박스·라디오는 뺀다. DS Switch가 `<input type="checkbox">`라, 통째로 잡으면
 * 스위치를 한 번 누른 뒤로 ⌘Z·I·O가 영영 먹지 않는다.
 */
const TEXT_ENTRY =
  'input:not([type="checkbox"]):not([type="radio"]), textarea, select, [contenteditable]';

/**
 * 어떤 위젯이 어떤 키를 제 것으로 쓰는가.
 *
 * 위젯 단위로 뭉뚱그리면 안 쓰는 키까지 양보한다 — 버튼은 화살표를 안 쓰고
 * 슬라이더는 Space를 안 쓴다. 뭉뚱그린 탓에 「버튼 누른 뒤 시킹이 죽고,
 * 슬라이더 위에서 재생이 안 되는」 상태가 났다. 키마다 주인을 적는다.
 *
 * 여기 없는 키(⌘Z·I·O)는 어떤 위젯도 제 것으로 쓰지 않으므로 늘 통과한다.
 */
const KEY_OWNERS: Partial<Record<EditorIntent['kind'], string>> = {
  // Space는 버튼·스위치를 누른다 — 가로채면 키보드로 아무것도 못 누른다.
  // 역할로 가른다: 구간 핸들은 <button role="slider">라 태그로 고르면 같이 걸려,
  // 슬라이더 위에서 Space가 죽는다.
  togglePlay: 'button:not([role]), [role="button"], [role="switch"], [role="tab"], [role="radio"]',
  // 화살표는 슬라이더가 값을, roving 묶음이 선택을 옮긴다
  seekBy: '[role="slider"], [role="radiogroup"], [role="tablist"]',
};

/**
 * 타임라인이 **지금** 더 커질 수 있는 양(px). 음수면 이미 그만큼 넘쳤다는 뜻이다.
 *
 * 미리보기 칸에서 신축하는 건 무대(`.stage`) 하나뿐이라, 무대가 최소 높이까지 더 줄 수
 * 있는 여유가 곧 타임라인의 여유다. 타임라인은 `flex: none`, 본문은 `flex: 1`이라 레인이
 * 1px 커지면 무대가 정확히 1px 준다(1:1). 이미 넘친 만큼은 여유에서 뺀다 — 안 빼면
 * 넘친 상태에서 상한이 제자리를 인정해 버린다.
 *
 * 레이아웃이 없는 환경(jsdom)이나 표식을 못 찾으면 `Infinity` — 상한 없음으로 둔다.
 */
function timelineHeadroom(previewColumn: HTMLElement | null): number {
  if (previewColumn === null) return Number.POSITIVE_INFINITY;
  const stage = previewColumn.querySelector<HTMLElement>('[data-preview-stage]');
  if (stage === null) return Number.POSITIVE_INFINITY;
  const stageMin = Number.parseFloat(getComputedStyle(stage).minHeight);
  if (!Number.isFinite(stageMin)) return Number.POSITIVE_INFINITY;
  const spare = stage.clientHeight - stageMin;
  const spilled = previewColumn.scrollHeight - previewColumn.clientHeight;
  return spare - spilled;
}

/**
 * 편집기 진입점. 로컬 소스 주소가 있으면 실재생, 없으면 목업이다.
 *
 * 컴포넌트를 가르는 이유는 훅 조건부 호출을 피하기 위해서다 — videoRef 와 hls 어댑터는
 * 실재생일 때만 존재한다(GlassPlayer 가 라이브에서 쓰는 방식과 같다). 덤으로 상태 허브의
 * `playback` 주입이 한 인스턴스 수명 동안 뒤집히지 않는 것도 여기서 보장된다.
 */
export function StudioScreen(options: ClipEditorOptions = {}) {
  // 테스트가 소스·재생을 직접 주입하면 그것을 쓴다 — env 나 네트워크를 타지 않는다
  const injected = options.playback !== undefined || options.source !== undefined;
  // 소스를 못 읽어도 편집기를 열 수 있어야 한다 — 화면 작업이 미디어에 발목 잡히면 안 된다
  const [skipped, setSkipped] = useState(false);
  const url = injected || skipped ? null : editorSourceUrl();
  const source = useEditorSource(url);

  if (injected || source.status === 'off') return <StudioScreenBody options={options} />;
  if (source.status === 'loading' || source.status === 'error') {
    return <EditorSourceGate state={source} onSkip={() => setSkipped(true)} />;
  }
  return (
    // key: 허브의 초기 구간이 useState 초기화라 소스가 바뀌면 다시 마운트해야 반영된다
    <HlsStudioScreen
      key={source.source.playlistUrl}
      options={options}
      source={source.source}
      peaks={source.peaks}
    />
  );
}

function HlsStudioScreen({
  options,
  source,
  peaks,
}: {
  options: ClipEditorOptions;
  source: NonNullable<ClipEditorOptions['source']>;
  peaks: NonNullable<ClipEditorOptions['peaks']>;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  // 허브가 잡을 구간과 같은 자리에서 시작한다 — 순수 함수라 양쪽이 같은 값을 얻는다.
  // 0에서 시작하면 매니페스트가 붙는 순간 플레이헤드가 한 번 튄다.
  const playback = useEditorHlsPlayback(videoRef, source.playlistUrl, {
    durationSeconds: source.durationSeconds,
    initialSeconds: initialRangeForSource(source.durationSeconds).startSeconds,
  });
  return (
    <StudioScreenBody
      options={{ ...options, playback, source, peaks }}
      videoNode={<video ref={videoRef} playsInline preload="metadata" />}
      videoRef={videoRef}
    />
  );
}

function StudioScreenBody({
  options,
  videoNode = null,
  videoRef,
}: {
  options: ClipEditorOptions;
  videoNode?: ReactNode;
  /** 결과 미리보기가 이 영상에서 잘라 그린다 */
  videoRef?: RefObject<HTMLVideoElement | null>;
}) {
  const state = useClipEditorMockState(options);
  const { togglePlay, seekBy, markIn, markOut, undo, redo } = state;
  const previewColumnRef = useRef<HTMLDivElement>(null);
  const { timelineHeight, timelineCollapsed, setTimelineHeight } = state;

  const headroom = useCallback(() => timelineHeadroom(previewColumnRef.current), []);

  // 창이 낮아지면 끌어둔 높이가 여유를 넘긴다 — 페인트 전에 되돌린다.
  // 자동 높이(null)는 손대지 않는다: 그건 트랙 수가 정하는 값이라 여기서 px로 굳히면
  // 화면 배율이 바뀌어도 그대로 남는다. 그 극단은 .timeline의 z-index가 받는다.
  const fitTimeline = useCallback(() => {
    // 접혀 있으면 맞출 레인이 없다. 이 조건을 여기서 읽어야 의존성에 들어가고, 그래야
    // 펼치는 순간 아래 레이아웃 효과가 다시 돌아 페인트 전에 높이를 잡는다.
    // 관찰만 믿으면 늦다 — RO 콜백은 페인트 직전에 도는데 거기서 잡은 rAF는 다음
    // 프레임이라, 낡은 높이로 한 프레임이 그려진다 (리뷰 #166 2회차).
    if (timelineHeight === null || timelineCollapsed) return;
    const spare = headroom();
    if (Number.isFinite(spare) && spare < 0) setTimelineHeight(timelineHeight + spare);
  }, [timelineHeight, timelineCollapsed, headroom, setTimelineHeight]);

  useLayoutEffect(fitTimeline, [fitTimeline]);

  // 관찰 콜백이 늘 최신 fitTimeline을 보게 한다 — 아래 옵저버를 높이가 바뀔 때마다
  // 다시 붙이지 않기 위해서다(붙였다 떼는 동안의 변화를 놓친다).
  const fitRef = useRef(fitTimeline);
  useLayoutEffect(() => {
    fitRef.current = fitTimeline;
  }, [fitTimeline]);

  /*
   * 미리보기 칸의 크기를 바꾸는 모든 경로를 한 문으로 받는다.
   *
   * window resize만 듣던 때는 내용 리플로우를 놓쳤다 (리뷰 #166) — 트랜스포트는
   * flex-wrap이고 "/ 구간 …" 라벨이 구간 편집마다 길어지는데, 줄바꿈이 나도 창 크기는
   * 그대로라 resize 이벤트가 없었다.
   *
   * 접기 토글은 여기 말고 위 레이아웃 효과가 잡는다 — 관찰로 받으면 rAF 한 프레임이
   * 늦어 낡은 높이가 한 번 그려진다. 이쪽은 그 뒤를 받치는 그물이다.
   *
   * 칸 자신과 자식을 함께 본다. 줄바꿈은 칸의 바깥 높이를 안 바꾸고 자식 높이만 바꾼다.
   */
  useEffect(() => {
    const column = previewColumnRef.current;
    if (column === null || typeof ResizeObserver === 'undefined') return;
    let raf = 0;
    const observer = new ResizeObserver(() => {
      // 콜백 안에서 곧바로 상태를 바꾸면 같은 프레임에 다시 관찰돼 루프 경고가 난다.
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => fitRef.current());
    });
    observer.observe(column);
    for (const child of Array.from(column.children)) observer.observe(child);
    return () => {
      cancelAnimationFrame(raf);
      observer.disconnect();
    };
  }, []);

  useEffect(() => {
    function onKeyDown(event: globalThis.KeyboardEvent) {
      const target = event.target instanceof Element ? event.target : null;
      if (target?.closest(TEXT_ENTRY) != null) return;
      const intent = editorIntentForKey(event);
      if (intent === null) return;
      // 이 키의 주인이 포커스 안에 있으면 그쪽에 넘긴다
      const owner = KEY_OWNERS[intent.kind];
      if (owner !== undefined && target?.closest(owner) != null) return;
      event.preventDefault();
      switch (intent.kind) {
        case 'togglePlay':
          togglePlay();
          break;
        case 'seekBy':
          seekBy(intent.seconds);
          break;
        case 'markIn':
          markIn();
          break;
        case 'markOut':
          markOut();
          break;
        case 'undo':
          undo();
          break;
        case 'redo':
          redo();
          break;
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [togglePlay, seekBy, markIn, markOut, undo, redo]);

  return (
    <div className={styles.screen}>
      <EditorHeader state={state} showTemplateSave />
      <main className={styles.body} data-panel-side={state.panelSide}>
        <ToolRail
          tools={state.toolOptions}
          activeTool={state.activeTool}
          onSelect={state.setActiveTool}
          panelSide={state.panelSide}
          panelSideTip={state.panelSideTip}
          onTogglePanelSide={state.togglePanelSide}
        />
        <ToolPanel state={state} />
        <div className={styles.previewColumn} ref={previewColumnRef}>
          <PreviewCanvas state={state} videoNode={videoNode} videoRef={videoRef} />
          <TransportBar state={state} showRangeLength />
        </div>
      </main>
      <MultitrackTimeline state={state} headroom={headroom} />
    </div>
  );
}
