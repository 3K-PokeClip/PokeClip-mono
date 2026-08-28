'use client';

// 시안 1d 클립 편집기의 목업 상태.
// 레시피 저장·렌더·업로드(계약6)가 아직 배선 전이라 데이터와 동작이 전부 목업이다 —
// 배선 티켓(POK-107·108·109·111)에서 이 훅 내부만 useQuery/뮤테이션으로 갈아끼우면
// 화면은 그대로 쓴다(useLiveMockState가 라이브 화면에 쓴 방식과 같다).
//
// 화면 컴포넌트에는 목업 값을 두지 않는다 — 언젠가 서버가 내려줄 값(제목·트랙·자막·추천)은
// 전부 여기서 나오고, 화면에는 구조 라벨('레이아웃' 같은 고정 문구)만 남는다.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useToast } from '@/ui';
import {
  canRedo as historyCanRedo,
  canUndo as historyCanUndo,
  createHistory,
  pushHistory,
  redoHistory,
  undoHistory,
  type History,
} from './editorHistory';
import {
  formatRangeGauge,
  formatTimecodeTenths,
  rangeGaugeFraction,
  rangeLengthSeconds,
  rangeRejectionMessage,
  resolveRangeEdge,
  clampTimelineHeight,
  viewWindow,
  zoomStep,
  DEFAULT_TIMELINE_HEIGHT,
  type ClipRange,
  type RangeRejectionReason,
  type TimelineView,
} from './timelineMath';

/**
 * 목업 자막 생성 지연. 화면에 적히는 "약 20초"는 실제 서비스 추정치고,
 * 여기서 그만큼 기다리면 시안 확인도 테스트도 못 한다 — 전이만 보이면 되므로 짧게 둔다.
 */
const MOCK_SUBTITLE_DELAY_MS = 1500;

export type EditorLayout = '9:16' | '1:1' | 'split';
export type SubtitleMode = 'burn-cc' | 'burn' | 'cc';
export type EditorTool = 'range' | 'subtitle' | 'audio' | 'bgm' | 'image';
export type EditorTrackKind = 'video' | 'mic' | 'game' | 'bgm' | 'sfx' | 'image';

export interface EditorTrackClip {
  id: string;
  label: string;
  startSeconds: number;
  endSeconds: number;
}

/** E2 — 존재하는 트랙만 온다(RMS 무음 필터). 화면은 이 배열을 그대로 그린다 */
export interface EditorTrack {
  id: string;
  kind: EditorTrackKind;
  label: string;
  /** 볼륨이 없는 트랙(영상·이미지)은 null */
  volume: number | null;
  muted: boolean;
  clips: EditorTrackClip[];
}

export interface SubtitleItem {
  id: string;
  /** 구간 시작 기준 표기 — 서버가 내려줄 값이라 미리 구워 둔다 */
  timecode: string;
  text: string;
  /** (환호)처럼 발화가 아닌 줄 — 흐리게 그린다 */
  nonSpeech?: boolean;
}

export interface TitleSuggestion {
  id: string;
  text: string;
}

export interface EditorImageItem {
  id: string;
  name: string;
  placement: string;
}

export type SubtitleState =
  | { status: 'idle'; estimateLabel: string }
  | { status: 'generating' }
  | { status: 'ready'; items: SubtitleItem[] };

export interface RangeRejection {
  reason: RangeRejectionReason;
  message: string;
  /** 같은 거부가 이어져도 흔들림이 다시 돌도록 매번 올린다 — 화면이 key로 쓴다 */
  nonce: number;
}

/**
 * 되돌리기 대상 — ADR-009의 레시피(구간·크롭·트랙·볼륨·자막 스타일·제목)와 같은 범위다.
 * 재생 위치·활성 도구·줌은 "보는 방식"이라 여기 없다.
 */
interface EditorRecipe {
  range: ClipRange;
  layout: EditorLayout;
  /** 상하분할 위쪽(게임) 비중 — 시안 1.5 : 1 */
  splitRatio: number;
  trackVolumes: Readonly<Record<string, number>>;
  trackMuted: Readonly<Record<string, boolean>>;
  subtitleMode: SubtitleMode;
  selectedTitleId: string | null;
  /** 소스 1·2 자리바꿈 — 상하분할에서만 의미가 있다 */
  sourcesSwapped: boolean;
}

const MOCK_SOURCE = {
  clipTitle: '승급전 마지막 한타 역전',
  sourceLabel: '라이브 카드 1:24:03',
  autosaveLabel: '방금 자동 저장됨',
  subtitleFontLabel: 'Pretendard ExtraBold',
  /** 1:24:03 방송 */
  durationSeconds: 5043,
  /** 1:22:14 — 시안 트랜스포트 표기 */
  playheadSeconds: 4934,
  /** 1:22:08.4 – 1:22:20.8 = 12.4초 */
  range: { startSeconds: 4928.4, endSeconds: 4940.8 } satisfies ClipRange,
} as const;

/** 시안 1d-a 타임라인의 트랙 6종 — 클립 위치는 눈금자(1:21:40~1:22:55) 기준 */
const MOCK_TRACKS: readonly EditorTrack[] = [
  { id: 'video', kind: 'video', label: '영상', volume: null, muted: false, clips: [] },
  { id: 'mic', kind: 'mic', label: '마이크', volume: 80, muted: false, clips: [] },
  { id: 'game', kind: 'game', label: '게임 사운드', volume: 60, muted: false, clips: [] },
  {
    id: 'bgm',
    kind: 'bgm',
    label: 'BGM',
    volume: 40,
    muted: false,
    clips: [
      {
        id: 'bgm-1',
        label: 'Neon Drive.mp3 · 페이드 인/아웃',
        startSeconds: 4913.5,
        endSeconds: 4957,
      },
    ],
  },
  {
    id: 'sfx',
    kind: 'sfx',
    label: '효과음',
    volume: 70,
    muted: false,
    clips: [
      { id: 'sfx-1', label: '띠용', startSeconds: 4927, endSeconds: 4931.5 },
      { id: 'sfx-2', label: '박수', startSeconds: 4939, endSeconds: 4943.5 },
    ],
  },
  {
    id: 'image',
    kind: 'image',
    label: '이미지',
    volume: null,
    muted: false,
    clips: [
      { id: 'img-1', label: '로고.png · 우측 상단', startSeconds: 4922.5, endSeconds: 4949.5 },
    ],
  },
];

const MOCK_SUBTITLES: readonly SubtitleItem[] = [
  { id: 'sub-1', timecode: '02.1', text: '아 이게 된다고?? 미쳤다' },
  { id: 'sub-2', timecode: '04.9', text: '잠깐 잠깐 각 나온다' },
  { id: 'sub-3', timecode: '06.8', text: '1대3인데 이걸 이겨버리네' },
  { id: 'sub-4', timecode: '08.4', text: '(환호)', nonSpeech: true },
  { id: 'sub-5', timecode: '10.2', text: '채팅창 난리났다 ㅋㅋㅋㅋ' },
];

const MOCK_TITLE_SUGGESTIONS: readonly TitleSuggestion[] = [
  { id: 'title-1', text: '1대3 클러치 미쳤다 #발로란트' },
  { id: 'title-2', text: '승급전 마지막 한타 대역전극' },
  { id: 'title-3', text: '이걸 쫓아온다고? 결말 실화' },
];

const MOCK_IMAGES: readonly EditorImageItem[] = [
  { id: 'img-1', name: '채널 로고.png', placement: '우측 상단 · 크기 12%' },
];

/** 시안 BGM·효과음 패널의 프리셋 */
const MOCK_SFX_PRESETS = ['띠용', '박수', '두둥'] as const;

const MOCK_BGM_LABEL = 'Neon Drive.mp3';

function initialRecipe(): EditorRecipe {
  return {
    range: MOCK_SOURCE.range,
    // 시안 1d-a는 상하분할이 켜진 상태를 보여준다
    layout: 'split',
    splitRatio: 1.5,
    trackVolumes: Object.fromEntries(
      MOCK_TRACKS.filter((t) => t.volume !== null).map((t) => [t.id, t.volume as number]),
    ),
    trackMuted: {},
    subtitleMode: 'burn-cc',
    selectedTitleId: null,
    sourcesSwapped: false,
  };
}

export interface ClipEditorOptions {
  /**
   * 자막 초기 상태. 시안 1d-a는 "생성 후"를 보여주므로 기본값이 ready다 —
   * 생성 전 → 후 전이는 idle로 마운트해 확인한다(테스트가 쓰는 문).
   */
  initialSubtitleStatus?: 'idle' | 'ready';
}

export interface ClipEditorMockState {
  // 헤더
  clipTitle: string;
  sourceLabel: string;
  autosaveLabel: string;
  canUndo: boolean;
  canRedo: boolean;
  undo: () => void;
  redo: () => void;
  saveTemplate: () => void;
  saveDraft: () => void;
  requestUpload: () => void;

  // 트랜스포트
  playing: boolean;
  togglePlay: () => void;
  playheadSeconds: number;
  playheadLabel: string;
  seekBy: (deltaSeconds: number) => void;
  seekTo: (seconds: number) => void;
  speed: number;
  speedOptions: readonly number[];
  setSpeed: (speed: number) => void;
  loop: boolean;
  toggleLoop: () => void;

  // 구간 (E1)
  range: ClipRange;
  rangeLengthSeconds: number;
  rangeStartLabel: string;
  rangeEndLabel: string;
  rangeLengthLabel: string;
  rangeGaugeLabel: string;
  rangeGaugeFraction: number;
  setRangeEdge: (edge: 'start' | 'end', seconds: number) => void;
  markIn: () => void;
  markOut: () => void;
  rangeRejection: RangeRejection | null;

  // 레이아웃 (E5)
  layout: EditorLayout;
  setLayout: (layout: EditorLayout) => void;
  splitRatio: number;
  setSplitRatio: (ratio: number) => void;
  sourcesSwapped: boolean;
  swapSources: () => void;

  // 트랙 (E2)
  tracks: EditorTrack[];
  setTrackVolume: (trackId: string, volume: number) => void;
  toggleTrackMute: (trackId: string) => void;
  selectedClipId: string | null;
  selectClip: (clipId: string | null) => void;

  // 자막 (E4)
  subtitle: SubtitleState;
  subtitleFontLabel: string;
  subtitleMode: SubtitleMode;
  setSubtitleMode: (mode: SubtitleMode) => void;
  generateSubtitles: () => void;
  selectedSubtitleId: string | null;
  selectSubtitle: (id: string) => void;

  // 제목 추천 (E7)
  titlesLocked: boolean;
  titleSuggestions: readonly TitleSuggestion[];
  selectedTitleId: string | null;
  selectTitle: (id: string) => void;

  // BGM·효과음·이미지
  bgmLabel: string | null;
  sfxPresets: readonly string[];
  images: readonly EditorImageItem[];

  // 타임라인 (보는 방식 — 되돌리기 대상 아님)
  sourceDurationSeconds: number;
  view: TimelineView;
  activeTool: EditorTool;
  setActiveTool: (tool: EditorTool) => void;
  timelineCollapsed: boolean;
  toggleTimeline: () => void;
  timelineHeight: number;
  setTimelineHeight: (px: number) => void;
  zoom: number;
  zoomLabel: string;
  zoomIn: () => void;
  zoomOut: () => void;
}

const SPEED_OPTIONS = [0.5, 1, 1.5, 2] as const;

export function useClipEditorMockState(options: ClipEditorOptions = {}): ClipEditorMockState {
  const { initialSubtitleStatus = 'ready' } = options;
  const { toast } = useToast();

  const [history, setHistory] = useState<History<EditorRecipe>>(() =>
    createHistory(initialRecipe()),
  );
  const recipe = history.present;

  const [playing, setPlaying] = useState(false);
  // as const가 붙은 목업이라 명시하지 않으면 리터럴 타입(4934)으로 굳는다
  const [playheadSeconds, setPlayheadSeconds] = useState<number>(MOCK_SOURCE.playheadSeconds);
  const [speed, setSpeed] = useState<number>(1);
  const [loop, setLoop] = useState(true);

  const [subtitleStatus, setSubtitleStatus] = useState<'idle' | 'generating' | 'ready'>(
    initialSubtitleStatus,
  );
  const [selectedSubtitleId, setSelectedSubtitleId] = useState<string | null>(
    initialSubtitleStatus === 'ready' ? (MOCK_SUBTITLES[0]?.id ?? null) : null,
  );

  const [rangeRejection, setRangeRejection] = useState<RangeRejection | null>(null);
  const [selectedClipId, setSelectedClipId] = useState<string | null>(null);
  const [activeTool, setActiveTool] = useState<EditorTool>('subtitle');
  const [timelineCollapsed, setTimelineCollapsed] = useState(false);
  const [timelineHeight, setTimelineHeightState] = useState(DEFAULT_TIMELINE_HEIGHT);
  const [zoom, setZoom] = useState(100);

  // 언마운트 뒤 남은 목업 타이머가 상태를 건드리지 않게 잡아 둔다
  const subtitleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (subtitleTimer.current !== null) clearTimeout(subtitleTimer.current);
    },
    [],
  );

  const commit = useCallback((update: (recipe: EditorRecipe) => EditorRecipe) => {
    setHistory((current) => {
      const next = update(current.present);
      // 같은 값을 다시 고르면 히스토리를 늘리지 않는다 — ↺가 아무 일도 안 하는 것처럼 보인다
      return next === current.present ? current : pushHistory(current, next);
    });
  }, []);

  const clampPlayhead = useCallback(
    (seconds: number) => Math.min(MOCK_SOURCE.durationSeconds, Math.max(0, seconds)),
    [],
  );

  // 재생 시뮬레이션 — 구간 끝에서 반복이거나 멈춘다 (usePlayerSimulation의 tick 구조)
  useEffect(() => {
    if (!playing) return undefined;
    const timer = setInterval(() => {
      setPlayheadSeconds((current) => {
        const next = current + 0.1 * speed;
        if (next >= recipe.range.endSeconds) {
          if (loop) return recipe.range.startSeconds;
          setPlaying(false);
          return recipe.range.endSeconds;
        }
        return next;
      });
    }, 100);
    return () => clearInterval(timer);
  }, [playing, speed, loop, recipe.range.startSeconds, recipe.range.endSeconds]);

  const setRangeEdge = useCallback(
    (edge: 'start' | 'end', seconds: number) => {
      const result = resolveRangeEdge(edge, seconds, recipe.range, MOCK_SOURCE.durationSeconds);
      const rejection = result.rejection;
      if (rejection !== null) {
        setRangeRejection((previous) => ({
          reason: rejection,
          message: rangeRejectionMessage(rejection),
          nonce: (previous?.nonce ?? 0) + 1,
        }));
        return;
      }
      setRangeRejection(null);
      commit((current) => ({ ...current, range: result.range }));
    },
    [commit, recipe.range],
  );

  const markIn = useCallback(
    () => setRangeEdge('start', playheadSeconds),
    [setRangeEdge, playheadSeconds],
  );
  const markOut = useCallback(
    () => setRangeEdge('end', playheadSeconds),
    [setRangeEdge, playheadSeconds],
  );

  const generateSubtitles = useCallback(() => {
    setSubtitleStatus('generating');
    if (subtitleTimer.current !== null) clearTimeout(subtitleTimer.current);
    subtitleTimer.current = setTimeout(() => {
      setSubtitleStatus('ready');
      setSelectedSubtitleId(MOCK_SUBTITLES[0]?.id ?? null);
    }, MOCK_SUBTITLE_DELAY_MS);
  }, []);

  const subtitle: SubtitleState = useMemo(() => {
    if (subtitleStatus === 'ready') return { status: 'ready', items: [...MOCK_SUBTITLES] };
    if (subtitleStatus === 'generating') return { status: 'generating' };
    return { status: 'idle', estimateLabel: '약 20초' };
  }, [subtitleStatus]);

  const tracks = useMemo(
    () =>
      MOCK_TRACKS.map((track) => ({
        ...track,
        volume: track.volume === null ? null : (recipe.trackVolumes[track.id] ?? track.volume),
        muted: recipe.trackMuted[track.id] ?? false,
        clips: [...track.clips],
      })),
    [recipe.trackVolumes, recipe.trackMuted],
  );

  const view = useMemo(
    () =>
      viewWindow(
        (recipe.range.startSeconds + recipe.range.endSeconds) / 2,
        zoom,
        MOCK_SOURCE.durationSeconds,
      ),
    [recipe.range.startSeconds, recipe.range.endSeconds, zoom],
  );

  const length = rangeLengthSeconds(recipe.range);
  const titlesLocked = subtitleStatus !== 'ready';

  return {
    clipTitle: MOCK_SOURCE.clipTitle,
    sourceLabel: MOCK_SOURCE.sourceLabel,
    autosaveLabel: MOCK_SOURCE.autosaveLabel,
    canUndo: historyCanUndo(history),
    canRedo: historyCanRedo(history),
    undo: useCallback(() => setHistory(undoHistory), []),
    redo: useCallback(() => setHistory(redoHistory), []),
    saveTemplate: useCallback(
      () => toast({ tone: 'success', title: '템플릿으로 저장했어요' }),
      [toast],
    ),
    saveDraft: useCallback(() => toast({ tone: 'success', title: '편집본을 저장했어요' }), [toast]),
    requestUpload: useCallback(
      () =>
        toast({
          tone: 'info',
          title: '업로드는 아직 준비 중이에요',
          description: '지금은 화면만 있는 목업이에요.',
        }),
      [toast],
    ),

    playing,
    togglePlay: useCallback(() => setPlaying((v) => !v), []),
    playheadSeconds,
    playheadLabel: formatTimecodeTenths(playheadSeconds),
    seekBy: useCallback(
      (delta: number) => setPlayheadSeconds((current) => clampPlayhead(current + delta)),
      [clampPlayhead],
    ),
    seekTo: useCallback(
      (seconds: number) => setPlayheadSeconds(clampPlayhead(seconds)),
      [clampPlayhead],
    ),
    speed,
    speedOptions: SPEED_OPTIONS,
    setSpeed,
    loop,
    toggleLoop: useCallback(() => setLoop((v) => !v), []),

    range: recipe.range,
    rangeLengthSeconds: length,
    rangeStartLabel: formatTimecodeTenths(recipe.range.startSeconds),
    rangeEndLabel: formatTimecodeTenths(recipe.range.endSeconds),
    rangeLengthLabel: `${length.toFixed(1)}초`,
    rangeGaugeLabel: formatRangeGauge(length),
    rangeGaugeFraction: rangeGaugeFraction(length),
    setRangeEdge,
    markIn,
    markOut,
    rangeRejection,

    layout: recipe.layout,
    setLayout: useCallback(
      (layout: EditorLayout) =>
        commit((current) => (current.layout === layout ? current : { ...current, layout })),
      [commit],
    ),
    splitRatio: recipe.splitRatio,
    setSplitRatio: useCallback(
      (ratio: number) =>
        commit((current) => ({ ...current, splitRatio: Math.min(3, Math.max(0.4, ratio)) })),
      [commit],
    ),
    sourcesSwapped: recipe.sourcesSwapped,
    swapSources: useCallback(
      () => commit((current) => ({ ...current, sourcesSwapped: !current.sourcesSwapped })),
      [commit],
    ),

    tracks,
    setTrackVolume: useCallback(
      (trackId: string, volume: number) =>
        commit((current) => ({
          ...current,
          trackVolumes: { ...current.trackVolumes, [trackId]: volume },
        })),
      [commit],
    ),
    toggleTrackMute: useCallback(
      (trackId: string) =>
        commit((current) => ({
          ...current,
          trackMuted: { ...current.trackMuted, [trackId]: !current.trackMuted[trackId] },
        })),
      [commit],
    ),
    selectedClipId,
    selectClip: setSelectedClipId,

    subtitle,
    subtitleFontLabel: MOCK_SOURCE.subtitleFontLabel,
    subtitleMode: recipe.subtitleMode,
    setSubtitleMode: useCallback(
      (mode: SubtitleMode) =>
        commit((current) =>
          current.subtitleMode === mode ? current : { ...current, subtitleMode: mode },
        ),
      [commit],
    ),
    generateSubtitles,
    selectedSubtitleId,
    selectSubtitle: setSelectedSubtitleId,

    titlesLocked,
    titleSuggestions: MOCK_TITLE_SUGGESTIONS,
    selectedTitleId: recipe.selectedTitleId,
    selectTitle: useCallback(
      (id: string) =>
        commit((current) =>
          current.selectedTitleId === id ? current : { ...current, selectedTitleId: id },
        ),
      [commit],
    ),

    bgmLabel: MOCK_BGM_LABEL,
    sfxPresets: MOCK_SFX_PRESETS,
    images: MOCK_IMAGES,

    sourceDurationSeconds: MOCK_SOURCE.durationSeconds,
    view,
    activeTool,
    setActiveTool,
    timelineCollapsed,
    toggleTimeline: useCallback(() => setTimelineCollapsed((v) => !v), []),
    timelineHeight,
    setTimelineHeight: useCallback(
      (px: number) => setTimelineHeightState(clampTimelineHeight(px)),
      [],
    ),
    zoom,
    zoomLabel: `${zoom}%`,
    zoomIn: useCallback(() => setZoom((z) => zoomStep(z, 'in')), []),
    zoomOut: useCallback(() => setZoom((z) => zoomStep(z, 'out')), []),
  };
}
