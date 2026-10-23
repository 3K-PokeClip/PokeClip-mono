'use client';

// 시안 1d 클립 편집기의 목업 상태.
// 레시피 저장·렌더·업로드(계약6)가 아직 배선 전이라 데이터와 동작이 전부 목업이다 —
// 배선 티켓(POK-107·108·109·111)에서 이 훅 내부만 useQuery/뮤테이션으로 갈아끼우면
// 화면은 그대로 쓴다(useLiveMockState가 라이브 화면에 쓴 방식과 같다).
//
// 화면 컴포넌트에는 목업 값을 두지 않는다 — 언젠가 서버가 내려줄 값(제목·트랙·자막·추천)은
// 전부 여기서 나오고, 화면에는 구조 라벨('레이아웃' 같은 고정 문구)만 남는다.
//
// 실제 모드(POK-251): `source`를 주면 실제 카드·편집본으로 여는 것이다(studio/StudioScreen이 조립한다).
// 그때는 목업 자막·제목 추천·이미지·BGM·효과음과 재생 흉내를 쓰지 않는다 — 줄 백엔드가 없는 칸은 비고,
// 누르면 「준비 중」이라고만 말한다. 저장·주문은 `actions`가 실제 서버로 보낸다.
// `source`가 없으면 지금까지의 목업이다(시험·스토리북).

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useToast } from '@/ui';
import {
  cropRectOf,
  maxCropSize,
  moveCropWindow,
  pointerDeltaToCrop,
  resizeCropWindow,
  type CropCorner,
  type CropRect,
  type CropWindow,
} from './cropMath';
import {
  CAPTION_POSITIONS,
  CENTER_COLOR_PRESETS,
  DEFAULT_CENTER_FILL,
  DEFAULT_PIP,
  DEFAULT_PIP_BORDER,
  DEFAULT_SPLIT_RATIO,
  LAYOUT_OPTIONS,
  PIP_BORDER_PRESETS,
  PIP_BORDER_WIDTHS,
  SOURCE_SIZE,
  SPLIT_RATIOS,
  defaultRegionWindow,
  layoutRegions,
  movePip,
  pipAspectOf,
  pipRectOf,
  resizePip,
  resultAspect,
  type CaptionPosition,
  type CenterFill,
  type EditorLayout,
  type LayoutOption,
  type LayoutRegion,
  type PipBorder,
  type PipBox,
} from './editorLayout';
import {
  applyAudioPreset as presetMix,
  presetAvailability,
  type AudioPresetId,
} from './audioPresets';
import type { EditorPlayback } from './editorPlayback';
import type { FilmstripSource } from './studio/VideoFilmstrip';
import type { TrackLevels } from './trackAudio/trackLevels';
import type { TrackPreview, TrackPreviewStatus } from './trackAudio/useTrackPreview';
import { regionCropsOf, type EditorLook } from './recipeLook';
import { useEditorPlaybackSimulation } from './useEditorPlaybackSimulation';
import {
  canRedo as historyCanRedo,
  canUndo as historyCanUndo,
  createHistory,
  pushHistory,
  redoHistory,
  replacePresent,
  undoHistory,
  type History,
} from './editorHistory';
import {
  formatLengthLabel,
  formatRangeGauge,
  formatTimecodeTenths,
  rangeGaugeFraction,
  rangeLengthSeconds,
  resolveRangeEdge,
  clampTimelineHeight,
  zoomedView,
  zoomStep,
  ZOOM_LEVELS,
  type ClipRange,
  type TimelineView,
} from './timelineMath';

/** 실제 모드에서 줄 백엔드가 없는 동작에 붙는 안내 */
const NOT_AVAILABLE = '준비 중';

function noop() {}

/** 실제 모드인데 재생 어댑터가 없을 때의 자리 — 아무것도 흐르지 않는다(가짜 시계를 쓰지 않는다) */
const NO_PLAYBACK: EditorPlayback = {
  playing: false,
  currentSeconds: 0,
  durationSeconds: 0,
  error: null,
  togglePlay: noop,
  seekTo: noop,
  seekBy: noop,
  setRate: noop,
  setBounds: noop,
};

/**
 * 목업 자막 생성 지연. 화면에 적히는 "약 20초"는 실제 서비스 추정치고,
 * 여기서 그만큼 기다리면 시안 확인도 테스트도 못 한다 — 전이만 보이면 되므로 짧게 둔다.
 */
const MOCK_SUBTITLE_DELAY_MS = 1500;

/**
 * 지금 이 칸이 잡을 수 있는 최대 사각형. 레시피만 보고 계산한다 — 커밋 업데이터 안에서 `current` 로
 * 잰다. 렌더 시점 레시피로 재면 같은 배치에서 setLayout 뒤에 오는 크롭 갱신이 옛 레이아웃 경계로 클램프된다.
 * 자리 모양(분할 지분·작은 화면 비율)이 크기를 정하므로 화면에 그릴 때와 **같은 계산**이어야 한다.
 */
function cropBoundsFor(recipe: EditorRecipe, regionId: string): { w: number; h: number } | null {
  // 작은 화면의 비율은 결과의 자리 모양에서 온다 — 화면에 그릴 때와 같은 비율로 재야 한다
  const region = layoutRegions(
    recipe.layout,
    recipe.splitRatio,
    pipAspectOf(recipe.pip, resultAspect(recipe.layout)),
  ).find((item) => item.id === regionId);
  if (region === undefined) return null;
  return maxCropSize(region.aspect, MOCK_SOURCE.width, MOCK_SOURCE.height);
}

/** 가장자리에 붙어 더 못 가면 히스토리를 늘리지 않는다 — ↺가 아무 일도 안 하는 것처럼 보인다 */
function sameRect(a: CropRect, b: CropRect): boolean {
  return a.x === b.x && a.y === b.y && a.w === b.w && a.h === b.h;
}
const samePip = sameRect;

export type { EditorLayout } from './editorLayout';
export type SubtitleMode = 'burn-cc' | 'burn' | 'cc';
export type EditorTool = 'layout' | 'subtitle' | 'text' | 'audio' | 'image';
/** 방송 소리 트랙의 모양(아이콘): 영상 줄은 타임라인이 따로 그린다 */
export type EditorTrackKind = 'mic' | 'game' | 'bgm';

/** E2 — 존재하는 트랙만 온다(RMS 무음 필터). 화면은 이 배열을 그대로 그린다 */
export interface EditorTrack {
  id: string;
  kind: EditorTrackKind;
  label: string;
  /** 볼륨이 없는 트랙(영상·이미지)은 null */
  volume: number | null;
  muted: boolean;
}

export interface SubtitleItem {
  id: string;
  /** 구간 시작 기준 표기 — 서버가 내려줄 값이라 미리 구워 둔다 */
  timecode: string;
  text: string;
  /** (환호)처럼 발화가 아닌 줄 — 흐리게 그린다 */
  nonSpeech?: boolean;
  /** 타임라인 자막 줄에 놓을 자리(소스 축 초). 모르면 그 줄은 타임라인에 안 그린다 */
  startSeconds?: number;
  endSeconds?: number;
}

export interface TitleSuggestion {
  id: string;
  text: string;
}

/**
 * 소스에서 잡는 영역 하나. 시안 갱신분에서 「소스 1·2」가 사라지고 레이아웃이 정하는
 * 영역(상단/하단, 메인/작은 화면)이 됐다 — 영상 트랙은 원래부터 하나였다.
 */
export interface EditorRegion {
  id: string;
  /** 프레임 안 배지 — 상단 / 하단 / 메인 화면 / 작은 화면 */
  label: string;
  tone: 'accent' | 'point';
  resizable: boolean;
  /** 선택 시 바깥을 어둡게 덮는가 */
  scrim: boolean;
  /** 결과 화면에서 놓이는 방식 */
  placement: LayoutRegion['placement'];
  /**
   * 소스에서 잘라 쓰는 영역 — 계약6 `outputs[].crop` 과 같은 모양.
   * 소스를 모르면(목업) 없다 — 잘라낼 그림도, 종횡비를 맞출 해상도도 없기 때문이다.
   */
  crop?: CropRect;
  /** 사각형이 지금 얼마나 당겨져 있나 (1 = 가장 넓게). 그려지는 크기와 같은 값 — 하한으로 클램프한 뒤다 */
  cropZoom?: number;
}

export type SubtitleState =
  | { status: 'idle'; estimateLabel: string }
  | { status: 'generating' }
  | { status: 'ready'; items: SubtitleItem[] };

/**
 * 되돌리기 대상 — ADR-009의 레시피(구간·크롭·트랙·볼륨·자막 스타일·제목)와 같은 범위다.
 * 재생 위치·활성 도구·줌은 "보는 방식"이라 여기 없다.
 */
export interface EditorRecipe {
  range: ClipRange;
  layout: EditorLayout;
  /** 분할 상단 지분(%) — 시안 칩 50 / 60 / 70 */
  splitRatio: number;
  /** 분할 두 영역 사이 2px 라인 */
  splitBorder: boolean;
  /** 자막이 놓이는 자리 — 시안에서 자막 패널로 옮겨졌다 */
  captionPosition: CaptionPosition;
  /** 크롭 모드의 작은 화면이 결과 안에서 놓이는 자리 (메인 화면 기준 정규화) */
  pip: PipBox;
  /** 크롭 모드에서 결과의 작은 화면 둘레 — 라인·그림자·라운드 */
  pipBorder: PipBorder;
  /** 중앙 모드에서 영상 바깥 띠를 채우는 방식 */
  centerFill: CenterFill;
  /**
   * 칸마다 소스에서 잡은 영역 — 중심과 확대율. 계약6이 요구하는 `{x,y,w,h}`는 여기에 비율·소스
   * 해상도를 얹어 파생시킨다. 사각형을 그대로 담으면 비율을 바꿀 때 종횡비 규칙을 어기게 된다.
   */
  crops: Readonly<Record<string, CropWindow>>;
  trackVolumes: Readonly<Record<string, number>>;
  trackMuted: Readonly<Record<string, boolean>>;
  subtitleMode: SubtitleMode;
  selectedTitleId: string | null;
}

const MOCK_SOURCE = {
  clipTitle: '승급전 마지막 한타 역전',
  sourceLabel: '라이브 카드 1:24:03',
  autosaveLabel: '방금 자동 저장됨',
  subtitleFontLabel: 'Pretendard ExtraBold',
  /** 1:24:03 방송 */
  durationSeconds: 5043,
  /** 방송의 해상도 — 크롭 사각형의 경계·종횡비를 재는 데 쓴다 (계약6은 픽셀 기준) */
  width: SOURCE_SIZE.width,
  height: SOURCE_SIZE.height,
  /** 1:22:14 — 시안 트랜스포트 표기 */
  playheadSeconds: 4934,
  /** 1:22:08.4 – 1:22:20.8 = 12.4초 */
  range: { startSeconds: 4928.4, endSeconds: 4940.8 } satisfies ClipRange,
} as const;

/**
 * 목업의 방송 소리 트랙: 실제 모드처럼 스트리머가 이름 붙인 소스 트랙들이다(POK-389).
 * 영상 줄은 타임라인이 늘 그리고, 음악·효과음·이미지는 기능이 생기면 줄이 생긴다.
 */
const MOCK_TRACKS: readonly EditorTrack[] = [
  { id: 'mic', kind: 'mic', label: '마이크', volume: 80, muted: false },
  { id: 'game', kind: 'game', label: '게임', volume: 60, muted: false },
  { id: 'discord', kind: 'mic', label: '디스코드', volume: 100, muted: false },
  { id: 'bgm', kind: 'bgm', label: 'BGM', volume: 40, muted: false },
];

/** 목업 자막: 타임코드(구간 시작 기준)에서 다음 줄 시작까지 화면에 선다. 소스 축 자리는 목업 구간 시작(4928.4)에 더한다 */
const MOCK_SUBTITLES: readonly SubtitleItem[] = (
  [
    { id: 'sub-1', timecode: '02.1', text: '아 이게 된다고?? 미쳤다' },
    { id: 'sub-2', timecode: '04.9', text: '잠깐 잠깐 각 나온다' },
    { id: 'sub-3', timecode: '06.8', text: '1대3인데 이걸 이겨버리네' },
    { id: 'sub-4', timecode: '08.4', text: '(환호)', nonSpeech: true },
    { id: 'sub-5', timecode: '10.2', text: '채팅창 난리났다 ㅋㅋㅋㅋ' },
  ] satisfies SubtitleItem[]
).map((item, i, all) => {
  const start = 4928.4 + Number(item.timecode);
  const next = all[i + 1];
  return {
    ...item,
    startSeconds: start,
    endSeconds: next ? 4928.4 + Number(next.timecode) : start + 1.8,
  };
});

const MOCK_TITLE_SUGGESTIONS: readonly TitleSuggestion[] = [
  { id: 'title-1', text: '1대3 클러치 미쳤다 #발로란트' },
  { id: 'title-2', text: '승급전 마지막 한타 대역전극' },
  { id: 'title-3', text: '이걸 쫓아온다고? 결말 실화' },
];

/** 실제 모드의 빈 콘텐츠 — 줄 백엔드가 없다(파일 머리 주석) */
const NO_SUBTITLES: readonly SubtitleItem[] = [];
const NO_TRACKS: readonly EditorTrack[] = [];
const NO_TITLE_SUGGESTIONS: readonly TitleSuggestion[] = [];

/**
 * 고를 수 있는 값의 목록도 데이터다 — 화면에 배열을 박아두면
 * 자막 모드가 늘어날 때 화면을 고쳐야 한다(레시피 항목이라 서버가 알게 될 값이다).
 */
export const SUBTITLE_MODE_OPTIONS: readonly { value: SubtitleMode; label: string }[] = [
  // 번인·CC 같은 업계 말 대신 결과로 말한다(POK-389). 값(계약6 mode 매핑)은 그대로다
  { value: 'burn', label: '영상에 새기기' },
  { value: 'cc', label: '자막 파일' },
  { value: 'burn-cc', label: '둘 다' },
];

/** 미리보기 아래 설명 줄의 짧은 말: 「자막 둘 다」처럼 읽히지 않게 따로 둔다 */
const SUBTITLE_MODE_NOTE: Record<SubtitleMode, string> = {
  burn: '영상에 새김',
  cc: '자막 파일',
  'burn-cc': '영상+파일',
};

/** 오디오 「빠른 설정」 카드 넷(POK-389): 값 계산은 audioPresets.ts */
const AUDIO_PRESETS: readonly { value: AudioPresetId; label: string; hint: string }[] = [
  { value: 'copyright-safe', label: '저작권 걱정 없이', hint: 'BGM 빼기' },
  { value: 'voice-up', label: '목소리 크게', hint: '마이크 ↑ 게임 ↓' },
  { value: 'game-up', label: '게임 소리 크게', hint: '게임 ↑' },
  { value: 'original', label: '원래 소리', hint: '최종 믹스' },
];

/**
 * 시안 좌측 도구 레일: 순서가 곧 화면 순서다. 쇼츠를 만드는 순서(배치 → 자막 → 글자 → 소리 → 이미지)를 따른다(POK-389).
 * 「구간」은 타임라인 손잡이·재생 막대의 시작·끝과 같은 일이라 뺐고, BGM·효과음은 오디오 안으로 들어갔다.
 */
export const TOOL_OPTIONS: readonly { value: EditorTool; label: string }[] = [
  // 시안 갱신분: 레일 맨 위에 레이아웃이 왔다. 이 도구일 때만 가운데가 원본·결과 2판이 된다.
  { value: 'layout', label: '레이아웃' },
  { value: 'subtitle', label: '자막' },
  { value: 'text', label: '텍스트' },
  { value: 'audio', label: '오디오' },
  { value: 'image', label: '이미지' },
];

function initialRecipe(
  range: ClipRange,
  trackMuted: Readonly<Record<string, boolean>> = {},
  trackVolumes: Readonly<Record<string, number>> = {},
): EditorRecipe {
  return {
    range,
    // 시안 갱신분의 기본값은 분할이다 (splitVals: sm ?? 'split')
    layout: 'split',
    splitRatio: DEFAULT_SPLIT_RATIO,
    splitBorder: true,
    captionPosition: 'bottom',
    pip: DEFAULT_PIP,
    pipBorder: DEFAULT_PIP_BORDER,
    centerFill: DEFAULT_CENTER_FILL,
    // 처음엔 비율마다의 기본 자리. 상하분할은 한 소스를 위·아래로 갈라 잡는다
    crops: {},
    // 초기값은 비워 둔다 — 트랙마다의 기본 볼륨은 트랙 정의가 갖고 있고, 여기 복사해 두면
    // 소스가 준 트랙(목업에 없는 id)의 볼륨이 빠진다
    trackVolumes,
    trackMuted,
    subtitleMode: 'burn-cc',
    selectedTitleId: null,
  };
}

/** 실제 카드·편집본에서 온 소스 좌표. 마운트 때 한 번 읽는다 — 바뀌면 컴포넌트를 갈아 끼운다(key). */
export interface EditorSource {
  clipTitle: string;
  sourceLabel: string;
  /** 구간을 잡을 수 있는 오른쪽 끝(초) — 방송 길이이거나, 하이라이트 주변으로 좁힌 상한 */
  durationSeconds: number;
  /**
   * 구간을 잡을 수 있는 왼쪽 끝(초). 없으면 0(방송 처음).
   * 편집기는 하이라이트 하나를 다듬는 자리다 — 핸들이 방송 전체를 돌아다니면 다른 장면을 자르게 된다.
   */
  minSeconds?: number;
  /** 처음 구간(방송 시작 기준 초) */
  range: ClipRange;
}

/** 저장·주문을 실제 서버에 잇는 자리 */
export interface EditorActions {
  saveDraft?: (recipe: EditorRecipe) => void;
  /** thumbnailSeconds: 편집기에서 고른 썸네일 장면(소스 축 초). 안 골랐으면 null(POK-389) */
  requestUpload?: (recipe: EditorRecipe, thumbnailSeconds: number | null) => void;
}

export interface ClipEditorOptions {
  /** 실제 소스 — 주면 실제 모드다(파일 머리 주석) */
  source?: EditorSource;
  /** 미리보기에 그릴 실제 영상 노드(재생 어댑터가 쥔 것). 없으면 자리 표시 문구를 그린다 */
  previewVideo?: HTMLVideoElement | null;
  /** 오디오 트랙(스트리머가 적어 둔 트랙 이름, POK-240). 없으면 목업 트랙(실제 모드면 빈 목록) */
  tracks?: readonly EditorTrack[];
  /**
   * 「최종 믹스」 트랙 id — 주면 스위치가 계약6 규칙을 지킨다: 최종 믹스와 나머지 트랙은 함께 켤 수 없고,
   * 마지막으로 켜진 트랙은 끌 수 없다. 저장할 때 조용히 바꾸면 화면과 다른 소리가 렌더된다(PR #200 codex P1)
   */
  mixTrackId?: string;
  /**
   * 트랙별 미리 듣기(POK-389). 주면 트랙을 끄거나 음량을 바꿀 때마다 그 값(트랙 순서 = 번호)을 넘긴다.
   * 못 불렀으면(status unavailable) 미리보기가 「영상 소리로 들린다」고 알린다
   */
  trackPreview?: TrackPreview;
  /** 타임라인 영상 줄 필름띠의 재료(POK-389). 녹화를 받아 두었을 때만. 없으면 모양만 그린다 */
  filmstripSource?: FilmstripSource | null;
  /** 저장된 편집본의 자막 줄(실제 모드). 마운트 값 — 있으면 자막 도구가 「생성됨」으로 열린다 */
  initialSubtitles?: readonly SubtitleItem[];
  /** 저장된 편집본의 모양(레이아웃·자르는 자리·자막 방식과 자리). 마운트 값 — 없는 칸은 편집기 기본값이다 */
  initialLook?: Partial<EditorLook>;
  /** 처음 꺼 둘 트랙·볼륨 — 저장된 편집본의 값. 마운트 값 */
  initialTrackMuted?: Readonly<Record<string, boolean>>;
  initialTrackVolumes?: Readonly<Record<string, number>>;
  actions?: EditorActions;
  /** 헤더의 저장 상태 문구 — 컨테이너가 서버 응답에 따라 바꾼다 */
  autosaveLabel?: string;
  /** 오른쪽 주 버튼 문구 */
  uploadLabel?: string;
  /**
   * 자막 초기 상태. 시안 1d-a는 "생성 후"를 보여주므로 기본값이 ready다 —
   * 생성 전 → 후 전이는 idle로 마운트해 확인한다(테스트가 쓰는 문).
   */
  initialSubtitleStatus?: 'idle' | 'ready';
  /**
   * 재생을 맡을 어댑터. 안 주면 목업 시뮬레이션이다(미디어 없이 플레이헤드만 흐른다).
   *
   * **한 인스턴스가 사는 동안 있거나 없거나 둘 중 하나여야 한다** — 중간에 뒤집히면 재생 상태의
   * 주인이 바뀌어 위치가 튄다. 실재생을 이을 때 화면 쪽에서 어댑터가 준비된 뒤에만 이 훅을 마운트하는
   * 식으로(예: 컴포넌트를 갈라서) 보장해야 한다 — 지금 StudioScreen 은 옵션을 그대로 넘길 뿐 그 분기가 없다.
   */
  playback?: EditorPlayback;
  /**
   * 시작 비율. 시안 1d-a는 상하분할을 보여주므로 기본값이 그것이다 —
   * 단일 화면의 크롭 동작은 이 문으로 들어가 확인한다.
   */
  initialLayout?: EditorLayout;
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
  saveDraft: () => void;
  requestUpload: () => void;
  uploadLabel: string;
  previewVideo: HTMLVideoElement | null;
  /** 트랙별 미리 듣기 상태. 실제 영상이 없으면 null */
  trackPreviewStatus: TrackPreviewStatus | null;
  /**
   * 소리 줄 파형(트랙 순서 = 번호). 목업이면 undefined(모양만 그린다), 실제인데 아직·못 불렀으면 null(빈 줄)
   */
  trackLevels: TrackLevels | null | undefined;
  filmstripSource: FilmstripSource | null;

  // 트랜스포트
  playing: boolean;
  togglePlay: () => void;
  playheadSeconds: number;
  /** 편집기에서 고른 썸네일 장면(소스 축 초): 쇼츠 구간 밖으로 밀려나면 null(POK-389) */
  thumbnailSeconds: number | null;
  /** 지금 재생 위치가 쇼츠 구간 안이라 썸네일로 고를 수 있는가 */
  canPickThumbnail: boolean;
  pickThumbnail: () => void;
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
  /**
   * 드래그 시작·끝 — 그 사이의 변경은 실행취소 한 칸으로 묶이고 타임라인 창이 고정된다.
   * 시작은 토큰을 돌려준다. 끝에 토큰을 주면 **그 제스처가 아직 최신일 때만** 닫는다 — blur 처럼 늦게
   * 오는 신호가 다른 위젯이 방금 연 제스처를 죽이지 않게. 토큰 없이 부르면 무조건 닫는다.
   */
  beginGesture: () => number;
  endGesture: (token?: number) => void;
  /**
   * 볼륨 슬라이더처럼 이어지는 조작에 그대로 펼쳐 붙이는 포인터 핸들러.
   * DS Slider는 pointermove마다 onValueChange를 부르므로 묶지 않으면
   * 드래그 한 번이 히스토리 상한을 넘긴다.
   *
   * 시작을 캡처 단계(onPointerDownCapture)로 받는 것이 핵심이다. Slider는 자기
   * onPointerDown 뒤에 {...rest}를 펼치므로, 같은 이름으로 넘기면 포인터 캡처와
   * 값 설정을 하던 Slider의 핸들러를 덮어써 마우스 조작이 통째로 죽는다.
   * onPointerUp 계열은 Slider가 쓰지 않아 그대로 전달돼도 안전하다.
   */
  gestureHandlers: {
    onPointerDownCapture: () => void;
    onPointerUp: () => void;
    onPointerCancel: () => void;
    onLostPointerCapture: () => void;
    /** DS Slider의 키보드 경로는 자동반복을 거르지 않는다 — 여기서 막는다 */
    onKeyDownCapture: (event: { repeat?: boolean; stopPropagation: () => void }) => void;
  };
  markIn: () => void;
  markOut: () => void;

  // 레이아웃 (E5)
  layout: EditorLayout;
  layoutOptions: readonly LayoutOption[];
  setLayout: (layout: EditorLayout) => void;
  /** 지금 레이아웃이 정하는 영역들 — 시안의 프레임 하나가 여기 한 항목이다 */
  regions: readonly EditorRegion[];
  /** 결과 화면의 비율(가로/세로). 가로 모드만 16:9 다 */
  resultAspect: number;
  /** 지금 고른 레이아웃의 이름 — 「미리보기 · 분할」처럼 배지에 쓴다 */
  layoutLabel: string;
  /** 지금 손대고 있는 영역. 선택된 것만 테두리가 진하고 바깥이 어두워진다 */
  selectedRegionId: string;
  selectRegion: (regionId: string) => void;
  splitRatio: number;
  splitRatioOptions: readonly number[];
  setSplitRatio: (percent: number) => void;
  splitBorder: boolean;
  toggleSplitBorder: () => void;
  captionPosition: CaptionPosition;
  captionPositionOptions: readonly { value: CaptionPosition; label: string }[];
  setCaptionPosition: (position: CaptionPosition) => void;
  /**
   * 크롭 모드의 작은 화면이 결과 안에서 차지하는 자리 — 메인 화면 기준 정규화 사각형.
   * 다른 모드에서는 null. 원본 판의 「메인 화면」 프레임 안에 타깃으로 그려 잡는다(시안 cropPipTarget).
   */
  pipPlacement: { x: number; y: number; w: number; h: number } | null;
  /** 타깃을 끈 만큼 옮긴다. 픽셀 → 정규화 환산에 메인 프레임의 화면 크기가 필요하다 */
  dragPip: (
    pointerDelta: { x: number; y: number },
    framePx: { width: number; height: number },
  ) => void;
  /**
   * 타깃 모서리를 끈다 — 비율을 묶지 않는다. 잡은 모양이 곧 작은 화면의 비율이고, 원본의
   * 「작은 화면」 프레임은 그 비율에 묶인 채 크기만 바뀐다. `pointer` 는 메인 프레임 안의 정규화 좌표
   */
  resizePip: (corner: CropCorner, pointer: { x: number; y: number }) => void;
  resetPip: () => void;
  /** 크롭 모드 — 결과의 작은 화면 둘레(라인 · 그림자 · 라운드). 시안은 늘 그리지만 설정으로 뺐다 */
  pipBorder: PipBorder;
  pipBorderWidths: readonly number[];
  pipBorderPresets: readonly { value: string; label: string }[];
  setPipBorder: (border: PipBorder) => void;
  /** 중앙 모드 — 영상 바깥 띠를 블러로 채울지 색으로 채울지 */
  centerFill: CenterFill;
  centerColorPresets: readonly { value: string; label: string }[];
  setCenterFill: (fill: CenterFill) => void;
  /**
   * 소스 판에서 사각형을 끈 만큼 옮긴다 (E5). 픽셀 → 정규화 환산은 허브가 한다 —
   * 화면은 몇 픽셀 움직였는지와 판이 얼마나 큰지만 알면 된다.
   * 드래그 한 번을 실행취소 한 칸으로 묶으려면 beginGesture/endGesture 로 감싼다.
   */
  dragCrop: (
    sourceId: string,
    pointerDelta: { x: number; y: number },
    panelSize: { width: number; height: number },
  ) => void;
  /** 키보드 한 걸음 — 소스 기준 정규화 값이다 */
  nudgeCrop: (sourceId: string, delta: { x: number; y: number }) => void;
  /**
   * 모서리를 끌어 범위를 바꾼다. `pointer` 는 소스 안의 정규화 좌표(0..1) —
   * 반대편 모서리를 못 박고 비율은 유지한다.
   */
  resizeCrop: (sourceId: string, corner: CropCorner, pointer: { x: number; y: number }) => void;
  /** 사각형을 기본 자리로 되돌린다 */
  resetCrop: (sourceId: string) => void;

  // 트랙 (E2)
  tracks: EditorTrack[];
  setTrackVolume: (trackId: string, volume: number) => void;
  toggleTrackMute: (trackId: string) => void;
  /** 오디오 「빠른 설정」(POK-389): 고른 카드. 트랙을 손으로 바꾸면 풀린다 */
  audioPreset: AudioPresetId | null;
  audioPresetOptions: readonly {
    value: AudioPresetId;
    label: string;
    hint: string;
    /** 맞는 트랙 이름이 없으면 못 고르고 그 이유 */
    unavailableReason: string | null;
  }[];
  applyAudioPreset: (preset: AudioPresetId) => void;

  // 자막 (E4)
  subtitle: SubtitleState;
  subtitleFontLabel: string;
  subtitleMode: SubtitleMode;
  subtitleModeLabel: string;
  subtitleModeOptions: readonly { value: SubtitleMode; label: string }[];
  setSubtitleMode: (mode: SubtitleMode) => void;
  generateSubtitles: () => void;
  /** AI 자막을 만들 서버 기능이 있는가: 실제 모드에는 아직 없다(MVP 밖) */
  subtitleGenerationAvailable: boolean;
  selectedSubtitleId: string | null;
  selectSubtitle: (id: string) => void;

  // 제목 추천 (E7)
  titlesLocked: boolean;
  titleSuggestions: readonly TitleSuggestion[];
  selectedTitleId: string | null;
  selectTitle: (id: string) => void;

  // 타임라인 (보는 방식 — 되돌리기 대상 아님)
  sourceDurationSeconds: number;
  /** 소스의 가로/세로. 크롭 사각형이 영상 위에 정확히 겹치려면 판이 이 비율이어야 한다 */
  sourceAspect: number;
  view: TimelineView;
  activeTool: EditorTool;
  toolOptions: readonly { value: EditorTool; label: string }[];
  setActiveTool: (tool: EditorTool) => void;
  timelineCollapsed: boolean;
  toggleTimeline: () => void;
  /** 사용자가 끌어 정한 높이(px). null이면 트랙 수에 맞춘 기본 높이다 */
  timelineHeight: number | null;
  /**
   * 높이를 정한다. `ceiling`은 화면이 잰 상한 — 훅은 레이아웃을 모르니 받아서 넘길 뿐이다.
   * 안 주면 상수 상한만 쓴다.
   */
  setTimelineHeight: (px: number | null, ceiling?: number) => void;
  zoom: number;
  zoomLabel: string;
  zoomIn: () => void;
  zoomOut: () => void;
  /** 확대 막대가 고르는 단계들(%): 막대 값은 이 목록의 자리다 */
  zoomLevels: readonly number[];
  setZoom: (zoom: number) => void;
}

const SPEED_OPTIONS = [0.5, 1, 1.5, 2] as const;

export function useClipEditorMockState(options: ClipEditorOptions = {}): ClipEditorMockState {
  const real = options.source !== undefined;
  // 실제 모드의 자막은 「생성 전」에서 시작한다 — 만들어 줄 백엔드가 아직 없다. 저장된 편집본에 자막 줄이 있으면
  // 그 줄로 연다(영상에는 타는데 화면이 「미생성」이면 화면과 영상이 다르다)
  const savedSubtitles = options.initialSubtitles ?? NO_SUBTITLES;
  const {
    initialSubtitleStatus = real && savedSubtitles.length === 0 ? 'idle' : 'ready',
    initialLayout,
    actions,
  } = options;
  const { toast } = useToast();
  // 소스 좌표는 마운트 값이다(재생 어댑터와 같은 규칙) — 컨테이너가 데이터를 다 읽은 뒤에만 이 훅을 마운트한다
  const source: EditorSource = options.source ?? MOCK_SOURCE;
  // 실제 모드에서는 목업 콘텐츠(자막·추천·이미지·BGM·효과음)를 비운다
  const subtitles = real ? savedSubtitles : MOCK_SUBTITLES;

  const [history, setHistory] = useState<History<EditorRecipe>>(() => {
    const recipe = initialRecipe(
      source.range,
      options.initialTrackMuted,
      options.initialTrackVolumes,
    );
    const withLook = { ...recipe, ...options.initialLook };
    return createHistory(
      initialLayout === undefined ? withLook : { ...withLook, layout: initialLayout },
    );
  });
  const recipe = history.present;

  // 재생은 어댑터가 맡는다. 주입이 없으면 목업 시뮬레이션 — 훅은 늘 호출하고(조건부 호출 금지)
  // 쓸 쪽만 고른다. 안 쓰이는 쪽은 playing=false 라 타이머도 걸지 않는다.
  const simulation = useEditorPlaybackSimulation({
    durationSeconds: MOCK_SOURCE.durationSeconds,
    initialSeconds: MOCK_SOURCE.playheadSeconds,
  });
  const playback = options.playback ?? (real ? NO_PLAYBACK : simulation);
  const { playing, currentSeconds: playheadSeconds } = playback;
  const [speed, setSpeed] = useState<number>(1);
  const [loop, setLoop] = useState(true);

  const [subtitleStatus, setSubtitleStatus] = useState<'idle' | 'generating' | 'ready'>(
    initialSubtitleStatus,
  );
  const [selectedSubtitleId, setSelectedSubtitleId] = useState<string | null>(
    initialSubtitleStatus === 'ready' ? (subtitles[0]?.id ?? null) : null,
  );

  const [selectedRegionId, setSelectedRegionId] = useState('top');
  // 시안 갱신분의 기본 도구는 레이아웃이다 (splitVals: tool ?? 'layout')
  const [activeTool, setActiveTool] = useState<EditorTool>('layout');
  const [timelineCollapsed, setTimelineCollapsed] = useState(false);
  // 기본은 내용에 맞춘다 — 화면 배율(--pc-u)이 rem을 따라 커지면 레인도 함께 커지는데
  // 여기에 고정 픽셀 상한을 걸면 배율이 큰 화면에서 마지막 트랙이 잘린다.
  const [timelineHeight, setTimelineHeightState] = useState<number | null>(null);
  const [zoom, setZoom] = useState(100);

  // 언마운트 뒤 남은 목업 타이머가 상태를 건드리지 않게 잡아 둔다
  const subtitleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (subtitleTimer.current !== null) clearTimeout(subtitleTimer.current);
    },
    [],
  );

  // 드래그 한 번을 실행취소 한 칸으로 묶는다. 첫 변경만 쌓고 나머지는 현재 상태를 갈아끼운다 —
  // 포인터가 움직일 때마다 쌓으면 제스처 하나가 상한을 넘겨 이전 편집을 밀어낸다.
  const gesturing = useRef(false);
  // 제스처가 시작될 때의 레시피. 이것이 past 맨 끝에 있으면 이 제스처의 첫 변경이 이미 쌓인 것이다.
  // 「첫 커밋」 표식을 따로 켜는 방식은 안 된다 — 가장자리에 붙어 무변경인 첫 이벤트가 표식만 켜서
  // 다음 실제 변경이 갈아끼우기로 들어가고, 드래그 직전 상태가 히스토리에서 사라진다.
  const gestureBase = useRef<EditorRecipe | null>(null);
  const gestureToken = useRef(0);
  const presentRef = useRef(recipe);
  presentRef.current = recipe;

  const beginGesture = useCallback(() => {
    gestureToken.current += 1;
    gesturing.current = true;
    gestureBase.current = presentRef.current;
    setFrozenView(liveViewRef.current);
    return gestureToken.current;
  }, []);

  const endGesture = useCallback((token?: number) => {
    // 낡은 토큰은 무시한다 — 그 제스처는 이미 다음 제스처로 대체됐다
    if (token !== undefined && token !== gestureToken.current) return;
    gesturing.current = false;
    gestureBase.current = null;
    setFrozenView(null);
  }, []);

  const commit = useCallback((update: (recipe: EditorRecipe) => EditorRecipe) => {
    setHistory((current) => {
      const next = update(current.present);
      // 같은 값을 다시 고르면 히스토리를 늘리지 않는다 — ↺가 아무 일도 안 하는 것처럼 보인다
      if (next === current.present) return current;
      // 업데이터는 ref를 읽기만 한다 — StrictMode 이중 호출에서도 같은 답이 나온다
      const opened =
        gesturing.current && current.past[current.past.length - 1] === gestureBase.current;
      return opened ? replacePresent(current, next) : pushHistory(current, next);
    });
  }, []);

  /** 창을 바꾸는 모든 길이 여기로 모인다 — 같은 클램프·같은 히스토리 규칙을 쓰게 하려고 */
  const updateCrop = useCallback(
    (
      sourceId: string,
      update: (
        window: CropWindow,
        maxSize: { w: number; h: number },
        /** 제스처가 시작될 때의 창 — 제스처 밖이면 지금 창 */
        reference: CropWindow,
      ) => CropWindow,
    ) => {
      commit((current) => {
        const maxSize = cropBoundsFor(current, sourceId);
        if (maxSize === null) return current;
        const stored = current.crops[sourceId] ?? defaultRegionWindow(current.layout, sourceId);
        // 자리 모양이 바뀐 뒤면 저장된 중심이 새 범위 밖일 수 있다 — 화면은 가둬서 그리는데 계산이
        // 밖에서 시작하면 첫 입력이 범위 안으로 들어오는 데 다 쓰여 화면은 안 움직인다. 먼저 가둔다
        const window = moveCropWindow(stored, { x: 0, y: 0 }, maxSize);
        const base = gestureBase.current;
        const reference =
          base === null
            ? window
            : (base.crops[sourceId] ?? defaultRegionWindow(base.layout, sourceId));
        const next = update(window, maxSize, reference);
        // 가장자리에 붙어 더 못 가면 히스토리를 늘리지 않는다 — ↺가 아무 일도 안 하는 것처럼 보인다.
        // 그려지는 사각형으로 비교한다 — 저장값(하한 아래 zoom, 범위 밖 중심)이 아니라
        if (sameRect(cropRectOf(next, maxSize), cropRectOf(window, maxSize))) return current;
        return { ...current, crops: { ...current.crops, [sourceId]: next } };
      });
    },
    [commit],
  );

  // 드래그 중에는 타임라인 창을 붙잡는다. 창이 구간 중심을 따라 움직이면 포인터→초 환산
  // 기준이 이벤트마다 옮겨가, 눈금이 손 아래에서 미끄러지고 늘리기가 창 폭에서 멎는다.
  const [frozenView, setFrozenView] = useState<TimelineView | null>(null);

  // 구간·배속을 어댑터에 알린다. 경계에서 멈추거나 되감는 판단은 어댑터의 재생 틱이 한다 —
  // 여기(상태 갱신 뒤)서 하면 배속이 높을 때 눈에 띌 만큼 구간 밖을 지나치고,
  // 정지 중에 핸들만 끌어도 재생 위치가 끌려간다.
  //
  // 객체가 아니라 함수에 의존한다 — 주입된 어댑터가 렌더마다 새 객체일 수 있다.
  const { setBounds, setRate } = playback;
  useEffect(() => {
    setBounds({ ...recipe.range, loop });
  }, [setBounds, recipe.range, loop]);
  useEffect(() => {
    setRate(speed);
  }, [setRate, speed]);

  const setRangeEdge = useCallback(
    (edge: 'start' | 'end', seconds: number) => {
      // 왼쪽 끝을 먼저 막는다 — 오른쪽 끝과 길이(5초~3분)는 resolveRangeEdge 가 본다
      const result = resolveRangeEdge(
        edge,
        Math.max(source.minSeconds ?? 0, seconds),
        recipe.range,
        source.durationSeconds,
      );
      const rejection = result.rejection;
      // 경계 밖이면 값을 그대로 둔다 — 핸들이 거기서 멈추는 것이 곧 안내다
      // (범례가 5초~3:00을 미리 적어 둔다)
      if (rejection !== null) return;
      // 원본 끝에 닿아 같은 값으로 잘렸으면 아무 일도 하지 않는다 —
      // resolveRangeEdge는 값이 같아도 새 객체를 주므로 여기서 걸러야 한다
      if (
        result.range.startSeconds === recipe.range.startSeconds &&
        result.range.endSeconds === recipe.range.endSeconds
      ) {
        return;
      }
      commit((current) => ({ ...current, range: result.range }));
    },
    [commit, recipe.range, source.minSeconds, source.durationSeconds],
  );

  // 플레이헤드를 ref로 읽는다 — 의존성에 걸면 재생 중 100ms마다 콜백 신원이 바뀌어
  // 전역 키 리스너가 해제·재등록을 반복한다.
  const playheadRef = useRef(playheadSeconds);
  playheadRef.current = playheadSeconds;

  // 썸네일 장면은 업로드 정보라 편집 기록(실행취소)에 넣지 않는다. 구간이 줄어 밖으로 나가면 고른 것이 없는 셈이다
  const [pickedThumbnail, setPickedThumbnail] = useState<number | null>(null);
  const thumbnailSeconds =
    pickedThumbnail !== null &&
    pickedThumbnail >= recipe.range.startSeconds &&
    pickedThumbnail < recipe.range.endSeconds
      ? pickedThumbnail
      : null;
  const thumbnailRef = useRef(thumbnailSeconds);
  thumbnailRef.current = thumbnailSeconds;
  const canPickThumbnail =
    playheadSeconds >= recipe.range.startSeconds && playheadSeconds < recipe.range.endSeconds;
  const pickThumbnail = useCallback(() => setPickedThumbnail(playheadRef.current), []);

  const markIn = useCallback(() => setRangeEdge('start', playheadRef.current), [setRangeEdge]);
  const markOut = useCallback(() => setRangeEdge('end', playheadRef.current), [setRangeEdge]);

  const generateSubtitles = useCallback(() => {
    // 실제 모드는 만들어 줄 백엔드(AI 자막, 2번 몫)가 아직 없다 — 상태를 바꾸지 않고 안내만 한다
    if (real) {
      toast({ tone: 'info', title: `AI 자막 생성 · ${NOT_AVAILABLE}` });
      return;
    }
    setSubtitleStatus('generating');
    if (subtitleTimer.current !== null) clearTimeout(subtitleTimer.current);
    subtitleTimer.current = setTimeout(() => {
      setSubtitleStatus('ready');
      setSelectedSubtitleId(MOCK_SUBTITLES[0]?.id ?? null);
    }, MOCK_SUBTITLE_DELAY_MS);
  }, [real, toast]);

  const subtitle: SubtitleState = useMemo(() => {
    if (subtitleStatus === 'ready') return { status: 'ready', items: [...subtitles] };
    if (subtitleStatus === 'generating') return { status: 'generating' };
    return { status: 'idle', estimateLabel: real ? NOT_AVAILABLE : '약 20초' };
  }, [subtitleStatus, subtitles, real]);

  const [audioPreset, setAudioPreset] = useState<AudioPresetId | null>(null);

  const tracks = useMemo(
    () =>
      (options.tracks ?? (real ? NO_TRACKS : MOCK_TRACKS)).map((track) => ({
        ...track,
        volume: track.volume === null ? null : (recipe.trackVolumes[track.id] ?? track.volume),
        muted: recipe.trackMuted[track.id] ?? false,
      })),
    [options.tracks, real, recipe.trackVolumes, recipe.trackMuted],
  );

  // 미리 듣기 음량: 저장할 소리와 같은 규칙(끈 트랙은 0, 음량은 %)
  const trackPreview = options.trackPreview;
  useEffect(() => {
    trackPreview?.setGains(
      tracks.map((track) => (track.muted || track.volume === null ? 0 : track.volume / 100)),
    );
  }, [trackPreview, tracks]);

  // 바로 앞에 그린 창: 키운 상태에서 재생 위치가 그 안에 있으면 창을 그대로 둔다(zoomedView)
  const previousViewRef = useRef<TimelineView | null>(null);
  // 100% 이하에서는 재생 위치가 창을 안 바꾼다: 그때는 재생할 때마다 다시 셀 필요가 없다
  const followedPlayhead = zoom > 100 ? playheadSeconds : null;
  const liveView = useMemo(
    () =>
      zoomedView({
        range: recipe.range,
        playheadSeconds: followedPlayhead ?? recipe.range.startSeconds,
        zoom,
        // 잡을 수 없는 왼쪽(하이라이트 범위 밖)은 눈금자에도 안 보인다
        minSeconds: source.minSeconds ?? 0,
        maxSeconds: source.durationSeconds,
        previous: previousViewRef.current,
      }),
    [recipe.range, followedPlayhead, zoom, source.durationSeconds, source.minSeconds],
  );
  previousViewRef.current = liveView;
  // 제스처가 시작될 때의 창을 그대로 붙잡아 둔다
  const liveViewRef = useRef(liveView);
  liveViewRef.current = liveView;
  const view = frozenView ?? liveView;

  const length = rangeLengthSeconds(recipe.range);
  const titlesLocked = subtitleStatus !== 'ready';

  // 레이아웃을 바꾸면 영역 id 가 통째로 달라진다 — 없는 id 를 고른 채로 두면 아무것도 선택되지
  // 않은 것처럼 보이므로 첫 영역으로 돌린다
  const regionIds = useMemo(
    () => layoutRegions(recipe.layout, recipe.splitRatio).map((region) => region.id),
    [recipe.layout, recipe.splitRatio],
  );

  return {
    clipTitle: source.clipTitle,
    sourceLabel: source.sourceLabel,
    autosaveLabel: options.autosaveLabel ?? (real ? '저장 전' : MOCK_SOURCE.autosaveLabel),
    uploadLabel: options.uploadLabel ?? '업로드',
    previewVideo: options.previewVideo ?? null,
    trackPreviewStatus: options.trackPreview?.status ?? null,
    trackLevels: real ? (options.trackPreview?.levels ?? null) : undefined,
    filmstripSource: options.filmstripSource ?? null,
    canUndo: historyCanUndo(history),
    canRedo: historyCanRedo(history),
    // 되돌리면 구간이 다른 값이 되므로 직전 거부 안내는 더 이상 그 구간의 이야기가 아니다
    // 되돌리면 트랙 값이 고른 빠른 설정과 달라질 수 있다: 표시를 푼다
    undo: useCallback(() => {
      setHistory(undoHistory);
      setAudioPreset(null);
    }, []),
    redo: useCallback(() => {
      setHistory(redoHistory);
      setAudioPreset(null);
    }, []),
    // 저장·주문은 컨테이너가 준 실제 동작으로 간다. 없으면 목업 안내다
    saveDraft: useCallback(() => {
      if (actions?.saveDraft) actions.saveDraft(presentRef.current);
      else toast({ tone: 'success', title: '편집본을 저장했어요' });
    }, [actions, toast]),
    requestUpload: useCallback(() => {
      if (actions?.requestUpload) actions.requestUpload(presentRef.current, thumbnailRef.current);
      else
        toast({
          tone: 'info',
          title: '업로드는 아직 준비 중이에요',
          description: '지금은 화면만 있는 목업이에요.',
        });
    }, [actions, toast]),

    playing,
    togglePlay: playback.togglePlay,
    playheadSeconds,
    playheadLabel: formatTimecodeTenths(playheadSeconds),
    seekBy: playback.seekBy,
    seekTo: playback.seekTo,
    speed,
    speedOptions: SPEED_OPTIONS,
    setSpeed,
    loop,
    toggleLoop: useCallback(() => setLoop((v) => !v), []),

    range: recipe.range,
    rangeLengthSeconds: length,
    rangeStartLabel: formatTimecodeTenths(recipe.range.startSeconds),
    rangeEndLabel: formatTimecodeTenths(recipe.range.endSeconds),
    rangeLengthLabel: formatLengthLabel(length),
    rangeGaugeLabel: formatRangeGauge(length),
    rangeGaugeFraction: rangeGaugeFraction(length),
    setRangeEdge,
    beginGesture,
    endGesture,
    gestureHandlers: useMemo(
      () => ({
        onPointerDownCapture: () => {
          beginGesture();
        },
        // 취소·캡처 상실도 끝으로 친다 — 안 그러면 창이 고정된 채 남고
        // 이후 편집이 계속 같은 히스토리 항목을 덮어쓴다. (이벤트 객체가 토큰 자리로 들어가지 않게 감싼다)
        onPointerUp: () => endGesture(),
        onPointerCancel: () => endGesture(),
        onLostPointerCapture: () => endGesture(),
        // 눌러둔 화살표가 초당 수십 번 값을 바꾸면 상한(50)을 넘겨 이전 편집이 밀려난다.
        // editorKeys·구간 핸들이 같은 이유로 막는 그 실패 모드다.
        //
        // preventDefault로는 못 막는다 — DS Slider의 onKeyDown이 defaultPrevented를
        // 보지 않는다. 캡처 단계에서 전파를 끊어 썸까지 가지 못하게 한다.
        onKeyDownCapture: (event) => {
          if (event.repeat === true) event.stopPropagation();
        },
      }),
      [beginGesture, endGesture],
    ),
    markIn,
    markOut,
    thumbnailSeconds,
    canPickThumbnail,
    pickThumbnail,

    layout: recipe.layout,
    layoutOptions: LAYOUT_OPTIONS,
    setLayout: useCallback(
      (layout: EditorLayout) =>
        commit((current) => (current.layout === layout ? current : { ...current, layout })),
      [commit],
    ),
    regions: useMemo(() => {
      // 작은 화면 비율은 결과의 자리 모양에서 온다 — 소스에서 잘라낼 프레임과 결과 배치에 같이 걸린다
      const pipRatio = pipAspectOf(recipe.pip, resultAspect(recipe.layout));
      const regions = layoutRegions(recipe.layout, recipe.splitRatio, pipRatio);
      // 작은 화면의 결과 배치는 사용자가 잡은 값이 정본이다 — 레이아웃 정의의 기본값을 덮는다
      const pipRect = pipRectOf(recipe.pip);
      const base = regions.map((region) => ({
        id: region.id,
        label: region.label,
        tone: region.tone,
        resizable: region.resizable,
        scrim: region.scrim,
        placement:
          region.placement.kind === 'overlay'
            ? {
                kind: 'overlay' as const,
                left: pipRect.x,
                top: pipRect.y,
                width: pipRect.w,
                aspect: pipRatio,
                border: recipe.pipBorder,
              }
            : region.placement.kind === 'contain'
              ? { ...region.placement, fill: recipe.centerFill }
              : region.placement,
      }));
      // 크롭 자리는 저장(recipeLook)과 같은 계산이다 — 화면에서 본 자리가 그대로 레시피에 실린다
      const crops = regionCropsOf({
        layout: recipe.layout,
        splitRatio: recipe.splitRatio,
        pip: recipe.pip,
        crops: recipe.crops,
      });
      return regions.map((_, index) => ({
        ...base[index]!,
        crop: crops[index]!.crop,
        cropZoom: crops[index]!.zoom,
      }));
    }, [
      recipe.layout,
      recipe.splitRatio,
      recipe.crops,
      recipe.pip,
      recipe.pipBorder,
      recipe.centerFill,
    ]),
    resultAspect: resultAspect(recipe.layout),
    layoutLabel: LAYOUT_OPTIONS.find((option) => option.value === recipe.layout)?.label ?? '',
    selectedRegionId: regionIds.includes(selectedRegionId)
      ? selectedRegionId
      : (regionIds[0] ?? 'main'),
    selectRegion: setSelectedRegionId,
    splitRatio: recipe.splitRatio,
    splitRatioOptions: SPLIT_RATIOS,
    setSplitRatio: useCallback(
      (percent: number) =>
        commit((current) =>
          current.splitRatio === percent ? current : { ...current, splitRatio: percent },
        ),
      [commit],
    ),
    splitBorder: recipe.splitBorder,
    toggleSplitBorder: useCallback(
      () => commit((current) => ({ ...current, splitBorder: !current.splitBorder })),
      [commit],
    ),
    pipPlacement: recipe.layout === 'crop' ? pipRectOf(recipe.pip) : null,
    dragPip: useCallback(
      (pointerDelta: { x: number; y: number }, framePx: { width: number; height: number }) =>
        commit((current) => {
          if (framePx.width <= 0 || framePx.height <= 0) return current;
          const next = movePip(current.pip, {
            x: pointerDelta.x / framePx.width,
            y: pointerDelta.y / framePx.height,
          });
          if (samePip(next, current.pip)) return current;
          return { ...current, pip: next };
        }),
      [commit],
    ),
    resizePip: useCallback(
      (corner: CropCorner, pointer: { x: number; y: number }) =>
        commit((current) => {
          const next = resizePip(current.pip, corner, pointer);
          if (samePip(next, current.pip)) return current;
          return { ...current, pip: next };
        }),
      [commit],
    ),
    resetPip: useCallback(
      () =>
        commit((current) =>
          samePip(current.pip, DEFAULT_PIP) ? current : { ...current, pip: DEFAULT_PIP },
        ),
      [commit],
    ),
    pipBorder: recipe.pipBorder,
    pipBorderWidths: PIP_BORDER_WIDTHS,
    pipBorderPresets: PIP_BORDER_PRESETS,
    setPipBorder: useCallback(
      (border: PipBorder) =>
        commit((current) => {
          const same =
            current.pipBorder.on === border.on &&
            current.pipBorder.width === border.width &&
            current.pipBorder.color === border.color;
          return same ? current : { ...current, pipBorder: border };
        }),
      [commit],
    ),
    centerFill: recipe.centerFill,
    centerColorPresets: CENTER_COLOR_PRESETS,
    setCenterFill: useCallback(
      (fill: CenterFill) =>
        commit((current) => {
          const before = current.centerFill;
          const same =
            fill.kind === 'blur'
              ? before.kind === 'blur' && before.strength === fill.strength
              : before.kind === 'color' && before.color === fill.color;
          return same ? current : { ...current, centerFill: fill };
        }),
      [commit],
    ),
    captionPosition: recipe.captionPosition,
    captionPositionOptions: CAPTION_POSITIONS,
    setCaptionPosition: useCallback(
      (position: CaptionPosition) =>
        commit((current) =>
          current.captionPosition === position
            ? current
            : { ...current, captionPosition: position },
        ),
      [commit],
    ),
    dragCrop: useCallback(
      (
        sourceId: string,
        pointerDelta: { x: number; y: number },
        panelSize: { width: number; height: number },
      ) =>
        updateCrop(sourceId, (window, maxSize) =>
          moveCropWindow(window, pointerDeltaToCrop(pointerDelta, panelSize), maxSize),
        ),
      [updateCrop],
    ),
    nudgeCrop: useCallback(
      (sourceId: string, delta: { x: number; y: number }) =>
        updateCrop(sourceId, (window, maxSize) => moveCropWindow(window, delta, maxSize)),
      [updateCrop],
    ),
    // 원본 프레임은 어느 모드든 비율에 묶여 크기만 바뀐다 — 크롭 모드의 작은 화면도 결과의 자리가
    // 정한 비율을 따른다. 비율을 바꾸려면 자리(배치 타깃)의 꼭짓점을 잡는다.
    resizeCrop: useCallback(
      (sourceId: string, corner: CropCorner, pointer: { x: number; y: number }) =>
        updateCrop(sourceId, (window, maxSize, reference) =>
          resizeCropWindow(window, corner, pointer, maxSize, reference),
        ),
      [updateCrop],
    ),
    resetCrop: useCallback(
      (sourceId: string) =>
        commit((current) => {
          const stored = current.crops[sourceId];
          if (stored === undefined) return current;
          // 값이 이미 기본 자리면 키만 지우는 것은 화면 변화 없는 히스토리 한 칸이다
          const maxSize = cropBoundsFor(current, sourceId);
          if (
            maxSize !== null &&
            sameRect(
              cropRectOf(stored, maxSize),
              cropRectOf(defaultRegionWindow(current.layout, sourceId), maxSize),
            )
          ) {
            return current;
          }
          const next = { ...current.crops };
          delete next[sourceId];
          return { ...current, crops: next };
        }),
      [commit],
    ),

    tracks,
    setTrackVolume: useCallback(
      (trackId: string, volume: number) => {
        setAudioPreset(null);
        commit((current) => ({
          ...current,
          trackVolumes: { ...current.trackVolumes, [trackId]: volume },
        }));
      },
      [commit],
    ),
    toggleTrackMute: useCallback(
      (trackId: string) => {
        const mix = options.mixTrackId;
        const current = presentRef.current;
        const turningOn = current.trackMuted[trackId] ?? false;
        const muted = { ...current.trackMuted, [trackId]: !turningOn };
        if (mix !== undefined) {
          const ids = (options.tracks ?? []).map((t) => t.id);
          if (turningOn && trackId === mix) for (const id of ids) muted[id] = id !== mix;
          else if (turningOn) muted[mix] = true;
          else if (ids.every((id) => muted[id] ?? false)) {
            // 소리가 하나도 없는 영상은 렌더가 거절한다 — 끄지 않고 이유를 말한다
            toast({ tone: 'info', title: '트랙을 하나는 켜 둬야 해요' });
            return;
          }
        }
        setAudioPreset(null);
        commit((cur) => ({ ...cur, trackMuted: muted }));
      },
      [commit, options.mixTrackId, options.tracks, toast],
    ),
    audioPreset,
    audioPresetOptions: useMemo(
      () =>
        AUDIO_PRESETS.map((preset) => {
          const availability = presetAvailability(preset.value, tracks, options.mixTrackId);
          return {
            ...preset,
            unavailableReason: availability.available ? null : availability.reason,
          };
        }),
      [tracks, options.mixTrackId],
    ),
    applyAudioPreset: useCallback(
      (preset: AudioPresetId) => {
        if (!presetAvailability(preset, tracks, options.mixTrackId).available) return;
        const current = {
          muted: Object.fromEntries(tracks.map((t) => [t.id, t.muted])),
          volumes: Object.fromEntries(tracks.map((t) => [t.id, t.volume ?? 100])),
        };
        const next = presetMix(preset, tracks, options.mixTrackId, current);
        setAudioPreset(preset);
        commit((cur) => ({
          ...cur,
          trackMuted: { ...cur.trackMuted, ...next.muted },
          trackVolumes: { ...cur.trackVolumes, ...next.volumes },
        }));
      },
      [commit, tracks, options.mixTrackId],
    ),

    subtitle,
    subtitleFontLabel: MOCK_SOURCE.subtitleFontLabel,
    subtitleMode: recipe.subtitleMode,
    subtitleModeLabel: SUBTITLE_MODE_NOTE[recipe.subtitleMode],
    subtitleModeOptions: SUBTITLE_MODE_OPTIONS,
    setSubtitleMode: useCallback(
      (mode: SubtitleMode) =>
        commit((current) =>
          current.subtitleMode === mode ? current : { ...current, subtitleMode: mode },
        ),
      [commit],
    ),
    generateSubtitles,
    subtitleGenerationAvailable: !real,
    selectedSubtitleId,
    selectSubtitle: setSelectedSubtitleId,

    titlesLocked,
    titleSuggestions: real ? NO_TITLE_SUGGESTIONS : MOCK_TITLE_SUGGESTIONS,
    selectedTitleId: recipe.selectedTitleId,
    selectTitle: useCallback(
      (id: string) =>
        commit((current) =>
          current.selectedTitleId === id ? current : { ...current, selectedTitleId: id },
        ),
      [commit],
    ),

    // 소스 길이의 정본은 목업 소스 하나다 — 구간 핸들 경계·타임라인 창도 같은 값을 본다.
    // 어댑터의 durationSeconds 는 어댑터 자신의 클램프용이라, 실제 소스가 오기 전엔 여기로 안 끌어온다
    sourceDurationSeconds: source.durationSeconds,
    sourceAspect: MOCK_SOURCE.width / MOCK_SOURCE.height,
    view,
    activeTool,
    toolOptions: TOOL_OPTIONS,
    setActiveTool,
    timelineCollapsed,
    toggleTimeline: useCallback(() => setTimelineCollapsed((v) => !v), []),
    timelineHeight,
    setTimelineHeight: useCallback(
      (px: number | null, ceiling?: number) =>
        setTimelineHeightState(px === null ? null : clampTimelineHeight(px, ceiling)),
      [],
    ),
    zoom,
    zoomLabel: `${zoom}%`,
    zoomIn: useCallback(() => setZoom((z) => zoomStep(z, 'in')), []),
    zoomOut: useCallback(() => setZoom((z) => zoomStep(z, 'out')), []),
    zoomLevels: ZOOM_LEVELS,
    setZoom,
  };
}
