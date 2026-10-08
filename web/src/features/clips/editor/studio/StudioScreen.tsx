'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ChevronLeft, Scissors } from 'lucide-react';
import { EmptyState, useToast } from '@/ui';
import {
  ClipApiError,
  createRecipe,
  fetchBroadcast,
  fetchClip,
  fetchJumpCard,
  fetchLibraryDetail,
  fetchMeLoose,
  requestRender,
  mediaStreamId,
  timelineBaseMs,
  updateRecipe,
  CUT_MAX_MS,
  CUT_MIN_MS,
  type BroadcastRow,
  type ClipSnapshot,
  type JumpCard,
  type RecipeDocument,
  type UploadInfo,
  type UploadRequestSummary,
} from '@/api/clipEditor';
import { EditorHeader } from '../EditorHeader';
import { PreviewCanvas } from '../PreviewCanvas';
import { TransportBar } from '../TransportBar';
import { MultitrackTimeline } from './MultitrackTimeline';
import { ToolPanel } from './ToolPanel';
import { ToolRail } from './ToolRail';
import styles from './StudioScreen.module.css';
import shared from '../editorShared.module.css';
import { editorIntentForKey, type EditorIntent } from '../editorKeys';
import {
  useClipEditorMockState,
  type ClipEditorOptions,
  type EditorRecipe,
  type EditorSource,
  type EditorTrack,
} from '../useClipEditorMockState';
import {
  TRACK_COUNT,
  fetchStreamerTrackLabels,
  trackDisplayName,
  type TrackLabels,
} from '@/api/audioTracks';
import { fetchDelegationsAsEditor } from '@/api/editors';
import { fetchRecordingSpans } from '@/api/mediaPlayback';
import {
  rebaseTimeline,
  recordingTimeline,
  type RecordingTimeline,
} from '@/features/player/recordingTimeline';
import { useEditorVideoPlayback } from '../useEditorVideoPlayback';
import { lookFromDocument, outputsFor, sameRecipe, subtitlesFor } from '../recipeLook';
import {
  emptyUploadDraft,
  UploadInfoDialog,
  type UploadDraft,
  type UploadFieldErrors,
} from '../UploadInfoDialog';
import { defaultSceneOffsetMs, sceneOffsetFromPlayhead } from '../uploadInfo';
import { clipLabel, clipSettled } from './clipProgress';

// 시안 1d-a 클립 편집기(스튜디오형). 전폭 자체 헤더를 가지므로 ScreenContainer를 쓰지 않는다
// (라이브 대시보드 선례). 데이터·동작은 전부 useClipEditorMockState 뒤에 있다.
//
// clip 창구 배선(POK-251): 디자인은 그대로고 목업 값만 실제 값으로 바뀐다 —
//   ?stream=&card=   점프카드 하나를 열어 그 창을 구간으로(처음 만드는 편집본)
//   ?recipe=         보관함의 저장된 편집본을 다시 연다(GET /api/clip/library/{id})
// 「편집본 저장」= POST/PUT recipes(계약6, 구간은 타임라인의 핸들 값) · 「영상 만들기」= 업로드 정보 창 → POST renders
// (업로드 정보를 실어 보내면 렌더가 끝나는 대로 clip이 유튜브에 올린다, POK-291).
// 파형·트랙·AI 자막·이미지·BGM은 백엔드가 없어 빈 채로 둔다(가짜 값을 심지 않는다).

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
 * 어떤 위젯이 어떤 키를 제 것으로 쓰는가. 키마다 주인을 적는다 — 위젯 단위로 뭉뚱그리면
 * 안 쓰는 키까지 양보한다(버튼은 화살표를 안 쓰고 슬라이더는 Space를 안 쓴다).
 */
const KEY_OWNERS: Partial<Record<EditorIntent['kind'], string>> = {
  togglePlay: 'button:not([role]), [role="button"], [role="switch"], [role="tab"], [role="radio"]',
  seekBy: '[role="slider"], [role="radiogroup"], [role="tablist"]',
};

/**
 * 타임라인이 **지금** 더 커질 수 있는 양(px). 음수면 이미 그만큼 넘쳤다는 뜻이다.
 * 미리보기 칸에서 신축하는 건 무대(`.stage`) 하나뿐이라, 무대가 최소 높이까지 더 줄 수
 * 있는 여유가 곧 타임라인의 여유다.
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

/** 시안 그대로의 편집기 본체. 옵션(소스·동작)은 마운트 값이다 — 컨테이너가 key로 갈아 끼운다. */
export function StudioEditor(options: ClipEditorOptions = {}) {
  const state = useClipEditorMockState(options);
  const { togglePlay, seekBy, markIn, markOut, undo, redo } = state;
  const previewColumnRef = useRef<HTMLDivElement>(null);
  const { timelineHeight, timelineCollapsed, setTimelineHeight } = state;

  const headroom = useCallback(() => timelineHeadroom(previewColumnRef.current), []);

  const fitTimeline = useCallback(() => {
    if (timelineHeight === null || timelineCollapsed) return;
    const spare = headroom();
    if (Number.isFinite(spare) && spare < 0) setTimelineHeight(timelineHeight + spare);
  }, [timelineHeight, timelineCollapsed, headroom, setTimelineHeight]);

  useLayoutEffect(fitTimeline, [fitTimeline]);

  const fitRef = useRef(fitTimeline);
  useLayoutEffect(() => {
    fitRef.current = fitTimeline;
  }, [fitTimeline]);

  useEffect(() => {
    const column = previewColumnRef.current;
    if (column === null || typeof ResizeObserver === 'undefined') return;
    let raf = 0;
    const observer = new ResizeObserver(() => {
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
      // 창(업로드 정보 등)이 떠 있는 동안에는 편집기 단축키를 통째로 끈다. 안 끄면 창 안 라디오 위 Space가 재생으로 새며
      // 선택을 막고, 스위치·라디오 위 I·O·⌘Z가 창 뒤 편집기의 구간과 기록을 바꾼다(POK-291)
      if (
        target?.closest('[role="dialog"]') != null ||
        document.querySelector('[aria-modal="true"]') !== null
      )
        return;
      if (target?.closest(TEXT_ENTRY) != null) return;
      const intent = editorIntentForKey(event);
      if (intent === null) return;
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
          <PreviewCanvas state={state} />
          <TransportBar state={state} showRangeLength />
        </div>
      </main>
      <MultitrackTimeline state={state} headroom={headroom} />
    </div>
  );
}

// ── 배선: 주소 → 실제 값 → 편집기 ──

/** ms → h:mm:ss 또는 m:ss */
function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`;
}

function dateLabel(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : `${d.getMonth() + 1}월 ${d.getDate()}일`;
}

interface Loaded {
  streamId: string;
  card: JumpCard | null;
  broadcast: BroadcastRow;
  /** 방송 시작 기준 창(ms) */
  window: { startMs: number; endMs: number };
  saved: { id: number; version: number; document: RecipeDocument } | null;
  latestClip: ClipSnapshot | null;
  /** 스트리머가 적어 둔 트랙 이름(POK-240). 못 읽으면 null — 그때는 「트랙 n」으로 보인다 */
  trackLabels: TrackLabels | null;
  /** 녹화(재생 서버). 있으면 미리보기에 실제 영상이 나오고, 시각의 기준점도 여기다 */
  recording: RecordingTimeline | null;
  /** 저장된 편집본 지금 판에 남겨 둔 업로드 정보(POK-291). 창을 다시 열 때 채운다 */
  uploadRequest: UploadRequestSummary | null;
}

/**
 * 이 방송의 녹화 — 방송 시간과 겹치는 구간들(시계 차이 1분까지)을 한 시간축에 놓는다(첫 구간 시작 ~ 마지막 구간 끝, 틈 포함).
 * 재생 서버의 목록은 경로(스트림키) 전체라 앞 방송 녹화도 섞여 오고, 재접속하면 한 방송의 녹화도 여러 구간이 된다(로컬 리뷰
 * 2라운드). 구간 목록은 미리보기 창이 틈에 걸리지 않게 쓴다(POK-253). 재생 서버가 없거나 녹화가 없으면 null
 */
async function loadRecording(broadcast: BroadcastRow): Promise<RecordingTimeline | null> {
  return recordingTimeline(
    // 녹화 재생 서버의 경로는 영상 키다(POK-233) — 방송 번호가 회차 번호면 그 이름의 녹화가 없다
    await fetchRecordingSpans(mediaStreamId(broadcast)),
    Date.parse(broadcast.startedAt!),
    broadcast.endedAt ? Date.parse(broadcast.endedAt) : null,
  );
}

/**
 * 그 방송 스트리머의 트랙 이름. 방송 줄에 스트리머 번호가 없어 관계로 고른다 — 내 방송이면 나, 편집자면 내가 위임받은
 * 스트리머(위임이 하나일 때만. 방송 줄에 번호가 실리기 전까지의 한계).
 */
async function loadTrackLabels(broadcast: BroadcastRow): Promise<TrackLabels | null> {
  try {
    if (broadcast.relation === 'OWNER') {
      const me = await fetchMeLoose();
      return me === null ? null : await fetchStreamerTrackLabels(me.id);
    }
    // 방송 줄에 스트리머 번호가 없다 — 위임이 하나뿐일 때만 그 스트리머로 안다. 여럿이면 남의 트랙 이름을 붙여
    // 엉뚱한 트랙을 끄게 되므로 「트랙 n」으로 둔다(PR #200 codex)
    const delegations = await fetchDelegationsAsEditor();
    const only = delegations.length === 1 ? delegations[0] : undefined;
    return only === undefined ? null : await fetchStreamerTrackLabels(only.streamerId);
  } catch {
    return null;
  }
}

const trackId = (index: number) => `track-${index}`;

type Status =
  | { kind: 'no-card' }
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'loaded'; data: Loaded };

function messageOf(e: unknown): string {
  if (e instanceof ClipApiError) {
    if (e.status === 409 && e.code === 'source_not_ready')
      return '영상 조각이 아직 다 안 올라왔어요 — 잠시 뒤 다시';
    if (e.status === 503 && e.code === 'render_unavailable')
      return '영상 만들기가 잠시 멈춰 있어요 — 잠시 뒤 다시 해 주세요';
    // 유튜브 업로드 정보를 실은 주문의 거절(POK-291)
    if (e.status === 409 && e.code === 'already_uploaded')
      return '이미 올린 편집본이에요. 고친 뒤 다시 만들어 주세요';
    if (e.status === 409 && e.code === 'youtube_not_linked')
      return '스트리머의 유튜브 채널이 연결돼 있지 않아요';
    if (e.status === 503 && e.code === 'upload_unavailable')
      return '지금은 유튜브 업로드를 받을 수 없어요. 잠시 뒤 다시 해 주세요';
    if (e.status === 503 && e.code === 'thumbnail_store_unavailable')
      return '썸네일 이미지를 저장하지 못했어요. 잠시 뒤 다시 해 주세요';
    if (e.status === 400 && e.field) return `저장할 수 없는 값이 있어요 (${e.field})`;
  }
  return e instanceof Error ? e.message : String(e);
}

const UPLOAD_FIELD_MESSAGE: Required<UploadFieldErrors> = {
  title: '유튜브 제목을 확인해 주세요.',
  description: '설명을 확인해 주세요.',
  tags: '태그를 확인해 주세요.',
  privacyStatus: '공개 범위를 확인해 주세요.',
  thumbnail: '썸네일을 확인해 주세요.',
};

/** 업로드 정보 칸을 짚은 거절이면 창 안 그 칸에 보일 사유. 아니면 null(토스트로 알린다) */
function uploadFieldErrorOf(e: unknown): UploadFieldErrors | null {
  if (!(e instanceof ClipApiError)) return null;
  if (e.status === 413) return { thumbnail: '이미지는 10MB까지 올릴 수 있어요' };
  if (e.status === 415) return { thumbnail: 'JPG나 PNG만 올릴 수 있어요' };
  if (e.status !== 400 || e.field === null) return null;
  // thumbnail.offsetMs 처럼 썸네일 안쪽 칸도 썸네일 칸이 말한다
  const key = e.field.startsWith('thumbnail') ? 'thumbnail' : e.field;
  return key in UPLOAD_FIELD_MESSAGE
    ? { [key]: UPLOAD_FIELD_MESSAGE[key as keyof UploadFieldErrors] }
    : null;
}

/** 창의 값 → 렌더 주문의 업로드 정보. 장면 시각은 완성 영상 안으로 자른다 */
function uploadInfoOf(draft: UploadDraft, cutLengthMs: number): UploadInfo {
  return {
    title: draft.title.trim(),
    description: draft.description,
    tags: draft.tags,
    privacyStatus: draft.privacyStatus,
    madeForKids: draft.madeForKids,
    thumbnail:
      draft.thumbnailSource === 'scene'
        ? {
            source: 'scene',
            offsetMs: Math.min(cutLengthMs - 1, Math.max(0, Math.round(draft.sceneOffsetMs))),
          }
        : { source: draft.thumbnailSource },
  };
}

async function loadFromCard(streamId: string, cardId: string): Promise<Status> {
  const [card, broadcast] = await Promise.all([
    fetchJumpCard(streamId, cardId),
    fetchBroadcast(streamId),
  ]);
  if (card === null)
    return { kind: 'error', message: `카드 ${cardId}를 방송 ${streamId}에서 찾지 못했어요` };
  if (broadcast === null || broadcast.startedAt === null) {
    return { kind: 'error', message: '방송 시작 시각이 없어 구간을 절대 시각으로 못 옮겨요' };
  }
  const [trackLabels, recording] = await Promise.all([
    loadTrackLabels(broadcast),
    loadRecording(broadcast),
  ]);
  return {
    kind: 'loaded',
    data: {
      streamId,
      card,
      broadcast,
      window: card.window,
      saved: null,
      latestClip: null,
      trackLabels,
      recording,
      uploadRequest: null,
    },
  };
}

async function loadFromRecipe(recipeId: number): Promise<Status> {
  const detail = await fetchLibraryDetail(recipeId);
  const broadcast = await fetchBroadcast(detail.streamId);
  const cut = detail.recipe.cut;
  if (cut === null)
    return {
      kind: 'error',
      message: `편집본 #${recipeId}은 구간이 없는 템플릿이라 편집기에서 못 열어요`,
    };
  if (broadcast === null || broadcast.startedAt === null) {
    return {
      kind: 'error',
      message: '방송 시작 시각이 없어 편집본의 구간을 화면 축으로 못 옮겨요',
    };
  }
  const recording = await loadRecording(broadcast);
  // 🔴 컷(절대 시각)을 화면 축으로 되돌리는 기준점은 시각 기준점이다(서버 → 녹화 재생 서버 → 방송 시작, POK-255) —
  //    저장할 때와 같은 기준이어야 제자리로 온다.
  const base = timelineBaseMs(broadcast, recording?.startMs ?? null);
  return {
    kind: 'loaded',
    data: {
      streamId: detail.streamId,
      card: null,
      broadcast,
      window: { startMs: cut.inAtMs - base, endMs: cut.outAtMs - base },
      saved: { id: detail.recipeId, version: detail.recipeVersion, document: detail.recipe },
      latestClip: detail.latestClip,
      trackLabels: await loadTrackLabels(broadcast),
      recording,
      uploadRequest: detail.uploadRequest ?? null,
    },
  };
}

export function StudioScreen() {
  const params = useSearchParams();
  const streamId = params.get('stream');
  const cardId = params.get('card');
  const recipeParam = params.get('recipe');
  const recipeId = recipeParam !== null && /^\d+$/.test(recipeParam) ? Number(recipeParam) : null;
  const [status, setStatus] = useState<Status>(() =>
    (streamId && cardId) || recipeId !== null ? { kind: 'loading' } : { kind: 'no-card' },
  );
  // 이 화면이 첫 저장(POST)으로 만든 편집본. 주소를 ?recipe= 로 바꾸면 주소 값이 바뀌어 아래 읽기가 다시 돈다. 그러면
  // 편집기가 새로 마운트되어 실행취소 기록을 잃고, 구간 한계가 카드 창에서 저장된 컷으로 좁아진다. 스스로 만든 것이면 안 읽는다
  const ownRecipe = useRef<number | null>(null);
  const adoptRecipe = useCallback((id: number) => {
    ownRecipe.current = id;
    // 새로고침·공유해도 같은 편집본으로 열리게 한다. 안 바꾸면 카드 주소로 다시 열려 다음 저장이 편집본을 하나 더 만든다
    window.history.replaceState(null, '', `?recipe=${id}`);
  }, []);

  useEffect(() => {
    if (recipeId !== null && recipeId === ownRecipe.current) return undefined;
    if (!(streamId && cardId) && recipeId === null) {
      setStatus({ kind: 'no-card' });
      return undefined;
    }
    let alive = true;
    setStatus({ kind: 'loading' });
    const load = recipeId !== null ? loadFromRecipe(recipeId) : loadFromCard(streamId!, cardId!);
    load
      .then((next) => {
        if (alive) setStatus(next);
      })
      .catch((e: unknown) => {
        if (alive) setStatus({ kind: 'error', message: messageOf(e) });
      });
    return () => {
      alive = false;
    };
  }, [streamId, cardId, recipeId]);

  if (status.kind === 'loaded') {
    // 소스가 바뀌면(다른 카드·편집본) 훅을 새로 마운트한다 — 구간·히스토리는 마운트 값이다.
    const key = status.data.card
      ? `card:${status.data.streamId}:${status.data.card.id}`
      : `recipe:${status.data.saved?.id}`;
    return <WiredStudio key={key} data={status.data} onRecipeCreated={adoptRecipe} />;
  }

  return (
    <div className={styles.screen}>
      <header className={shared.header}>
        <Link href="/clips/library" className={shared.backLink} aria-label="보관함으로">
          <ChevronLeft size={17} aria-hidden />
        </Link>
        <div className={shared.headerTitleBlock}>
          <h1 className={shared.headerTitle}>클립 편집</h1>
          <div className={shared.headerMeta}>
            {status.kind === 'loading'
              ? '불러오는 중…'
              : status.kind === 'error'
                ? '못 열었어요'
                : '열 편집본이 없어요'}
          </div>
        </div>
      </header>
      <main className={styles.body}>
        <div style={{ padding: '2rem', width: '100%' }}>
          {status.kind === 'no-card' ? (
            <EmptyState
              icon={<Scissors size={21} />}
              title="카드에서 「편집」을 누르거나 보관함에서 편집본을 여세요"
              description="라이브 대시보드의 하이라이트 카드나 보관함의 편집본에서 편집을 시작해요."
            />
          ) : status.kind === 'error' ? (
            <p role="alert">불러오지 못했어요: {status.message}</p>
          ) : (
            <p role="status">카드·방송을 불러오는 중…</p>
          )}
        </div>
      </main>
    </div>
  );
}

/** 저장·주문 좌표. 서버가 준 판 번호·영상 상태를 헤더 문구로 보여 준다. */
interface SaveState {
  recipeId: number | null;
  version: number | null;
  busy: 'save' | 'render' | null;
  clip: ClipSnapshot | null;
  label: string;
  /** 마지막으로 서버에 저장한 본문 — 지금 화면과 다르면 영상 만들기 전에 먼저 저장한다 */
  savedDoc: RecipeDocument | null;
  /** 서버에 남은 업로드 정보와 그 판. 같은 판이면 창을 다시 열 때 채운다 */
  uploadRequest: { version: number; summary: UploadRequestSummary } | null;
}

/** 창이 열려 있는 동안 쥐고 있는 것: 연 순간의 화면(레시피)·완성 영상 길이·처음 값 */
interface PendingUpload {
  recipe: EditorRecipe;
  cutLengthMs: number;
  initial: UploadDraft;
  playheadOffsetMs: number | null;
}

function WiredStudio({
  data,
  onRecipeCreated,
}: {
  data: Loaded;
  /** 첫 저장(POST)으로 편집본이 생겼다. 주소를 ?recipe= 로 바꾼다 */
  onRecipeCreated: (recipeId: number) => void;
}) {
  const { toast } = useToast();
  const router = useRouter();
  const { streamId, card, broadcast, window, saved, latestClip, trackLabels, recording } = data;
  const startedAt = broadcast.startedAt!;
  // 🔴 카드·조각 ms의 0초 = 시각 기준점(서버 → 녹화 재생 서버 → 방송 시작, POK-255). 저장·다시 열기가 같은 값을 써야 제자리다.
  //    방송 시작 편지 시각과 수십 초 갈리고, 조각 장부의 위치 값은 방송을 넘어 이어지므로 녹화 시작과도 갈릴 수 있다
  const base = timelineBaseMs(broadcast, recording?.startMs ?? null);
  // 녹화 끝을 기준점 축의 초로 — 녹화 길이는 녹화 시작부터 잰 값이라 그대로 쓰면 두 시작의 차만큼 끝이 어긋난다
  const recordingEndSeconds = recording
    ? (recording.startMs + recording.durationSeconds * 1000 - base) / 1000
    : 0;

  const initialRange = useMemo(
    () => ({ startSeconds: window.startMs / 1000, endSeconds: window.endMs / 1000 }),
    [window],
  );
  // 녹화 구간들도 기준점 축으로 옮긴다 — 녹화 축과 기준점 축은 두 시작의 차만큼 갈린다
  const recordingPieces = useMemo(
    () => (recording === null ? undefined : rebaseTimeline(recording, base).pieces),
    [recording, base],
  );
  // 실재생 — 녹화가 있을 때만 어댑터를 넘긴다(한 마운트 동안 있거나 없거나 고정: 훅의 규칙).
  const video = useEditorVideoPlayback({
    streamId: mediaStreamId(broadcast),
    // 재생 서버에는 「기준점 + 초」로 절대 시각을 묻는다 — 구간 초가 기준점 축이다
    recordingStartMs: base,
    recordingSeconds: recordingEndSeconds,
    pieces: recordingPieces,
    initialRange,
  });

  const [save, setSave] = useState<SaveState>(() => ({
    recipeId: saved?.id ?? null,
    version: saved?.version ?? null,
    busy: null,
    clip: latestClip,
    label: saved ? `v${saved.version} 저장됨` : '저장 안 됨',
    savedDoc: saved ? saved.document : null,
    uploadRequest:
      saved && data.uploadRequest ? { version: saved.version, summary: data.uploadRequest } : null,
  }));
  const saveRef = useRef(save);
  saveRef.current = save;

  const source = useMemo<EditorSource>(() => {
    const endedMs = broadcast.endedAt ? Date.parse(broadcast.endedAt) - base : null;
    // 방송 길이 — 녹화가 있으면 녹화 길이, 끝났으면 실제 길이, 아니면 창 끝에 1분 여유
    const totalSeconds = Math.max(
      recording ? recordingEndSeconds : (endedMs ?? window.endMs + 60_000) / 1000,
      window.endMs / 1000,
    );
    // 🔴 구간은 **그 하이라이트 안에서만** 잡는다(사용자 확정 2026-09-17) — 카드가 잡아 준 시작~끝이 곧 양쪽 한계이고,
    //    편집자는 그 안에서 줄이기만 한다. 방송의 다른 장면으로는 핸들이 나가지 않는다.
    //    저장된 편집본을 다시 열 때는 카드가 없어 저장된 구간이 한계가 된다(다시 넓히려면 카드에서 새로 연다).
    const minSeconds = window.startMs / 1000;
    const durationSeconds = Math.min(totalSeconds, window.endMs / 1000);
    return {
      clipTitle: card ? `점프카드 #${card.id}` : `편집본 #${saved?.id}`,
      sourceLabel: `${dateLabel(startedAt)} 방송 · ${card ? `카드 ${formatClock(card.streamTimestampMs)}` : `구간 ${formatClock(window.startMs)}`}`,
      durationSeconds,
      minSeconds,
      range: { startSeconds: window.startMs / 1000, endSeconds: window.endMs / 1000 },
    };
  }, [broadcast.endedAt, base, window, card, saved, startedAt, recording, recordingEndSeconds]);

  /** 타임라인의 구간(방송 시작 기준 초) → 계약6 컷(절대 ms). 5초~3분 밖이면 저장 문이 거절하므로 여기서 맞춘다. */
  const documentFor = useCallback(
    (recipe: EditorRecipe): RecipeDocument => {
      const inAtMs = base + Math.round(recipe.range.startSeconds * 1000);
      const rawLength = Math.round((recipe.range.endSeconds - recipe.range.startSeconds) * 1000);
      const length = Math.min(CUT_MAX_MS, Math.max(CUT_MIN_MS, rawLength));
      const cut = { inAtMs, outAtMs: inAtMs + length };
      // 켜 둔 트랙만 믹스된다(계약6). 0(최종 믹스)과 1~5는 같이 못 넣는다 — 소스 트랙을 하나라도 켰으면 0을 뺀다.
      let selected = Array.from({ length: TRACK_COUNT }, (_, i) => i)
        .filter((i) => !(recipe.trackMuted[trackId(i)] ?? i !== 0))
        .map((i) => ({
          trackId: i,
          gain: Math.min(2, Math.max(0, (recipe.trackVolumes[trackId(i)] ?? 100) / 100)),
        }));
      if (selected.some((t) => t.trackId !== 0)) selected = selected.filter((t) => t.trackId !== 0);
      if (selected.length === 0) selected = [{ trackId: 0, gain: 1 }];
      // 모양은 화면 그대로 v2로 싣는다(POK-252) — 저장된 편집본의 옛 출력을 두면 화면과 다른 영상이 나온다.
      // 자막 줄은 편집기가 만들지 않으므로 저장된 것을 그대로 두고, 방식·자리만 화면 값으로 바꾼다.
      const subtitles = subtitlesFor(recipe, saved?.document.subtitles?.segments ?? null);
      return {
        schemaVersion: 2,
        streamId,
        cut,
        outputs: outputsFor(recipe, saved?.document ?? null),
        audio: { tracks: selected },
        ...(subtitles ? { subtitles } : {}),
      };
    },
    [base, saved, streamId],
  );

  /** 새로 만들거나 고쳐 저장하고, 저장한 판을 상태에 적는다 */
  const persist = useCallback(
    async (recipeId: number | null, doc: RecipeDocument) => {
      const snap = await (recipeId === null
        ? createRecipe(streamId, doc)
        : updateRecipe(streamId, recipeId, doc));
      setSave((s) => ({
        ...s,
        recipeId: snap.id,
        version: snap.recipeVersion,
        savedDoc: doc,
      }));
      if (recipeId === null) onRecipeCreated(snap.id);
      return snap;
    },
    [streamId, onRecipeCreated],
  );

  const saveDraft = useCallback(
    (recipe: EditorRecipe) => {
      const current = saveRef.current;
      if (current.busy !== null) return;
      const doc = documentFor(recipe);
      setSave((s) => ({ ...s, busy: 'save', label: '저장하는 중…' }));
      persist(current.recipeId, doc)
        .then((snap) => {
          setSave((s) => ({
            ...s,
            busy: null,
            label: `편집본 #${snap.id} v${snap.recipeVersion} 저장됨`,
          }));
          toast({ tone: 'success', title: `편집본 #${snap.id} 저장됨 (v${snap.recipeVersion})` });
        })
        .catch((e: unknown) => {
          const message = messageOf(e);
          setSave((s) => ({ ...s, busy: null, label: `저장 실패 · ${message}` }));
          toast({ tone: 'error', title: '저장 실패', description: message });
        });
    },
    [documentFor, persist, toast],
  );

  const [pending, setPending] = useState<PendingUpload | null>(null);
  const [uploadErrors, setUploadErrors] = useState<UploadFieldErrors>({});
  // 이 화면에서 마지막으로 보낸 창의 값. 다시 열면 그대로 채운다(고른 이미지 파일까지)
  const lastDraft = useRef<UploadDraft | null>(null);
  // 지금 재생 위치(기준점 축의 초). 녹화가 없으면 재생 위치가 늘 0이라 모르는 것으로 둔다. 재생 중에는 프레임마다
  // 바뀌므로 ref로 읽는다(동작 콜백이 매 프레임 새로 만들어지지 않게)
  const playheadRef = useRef<number | null>(null);
  playheadRef.current = recording ? video.playback.currentSeconds : null;

  /** 「영상 만들기」 = 업로드 정보 창을 연다. 연 순간의 화면이 주문할 모양이다(창이 떠 있는 동안 편집기는 안 바뀐다) */
  const openUploadDialog = useCallback(
    (recipe: EditorRecipe) => {
      const current = saveRef.current;
      if (current.busy !== null) return;
      const playheadSeconds = playheadRef.current;
      const cut = documentFor(recipe).cut!;
      const cutLengthMs = cut.outAtMs - cut.inAtMs;
      const rangeStart = recipe.range.startSeconds;
      const scene = defaultSceneOffsetMs(playheadSeconds, rangeStart, cutLengthMs);
      const remembered = lastDraft.current;
      const stored =
        current.uploadRequest !== null && current.uploadRequest.version === current.version
          ? current.uploadRequest.summary
          : null;
      const initial: UploadDraft = remembered
        ? { ...remembered, sceneOffsetMs: Math.min(cutLengthMs - 1, remembered.sceneOffsetMs) }
        : stored
          ? {
              ...emptyUploadDraft(scene),
              title: stored.title,
              privacyStatus: stored.privacyStatus,
              thumbnailSource: stored.thumbnailSource,
            }
          : emptyUploadDraft(scene);
      setUploadErrors({});
      setPending({
        recipe,
        cutLengthMs,
        initial,
        playheadOffsetMs: sceneOffsetFromPlayhead(playheadSeconds, rangeStart, cutLengthMs),
      });
    },
    [documentFor],
  );

  /**
   * 창에서 확인. 영상은 저장된 판으로 만든다: 화면이 마지막 저장과 다르면(한 번도 안 저장했거나, 저장 뒤 또 고쳤으면) 먼저
   * 저장해 새 판을 만들고 그 판으로 주문한다. 저장 뒤 고친 것을 무시하고 옛 판을 렌더하면 화면과 다른 영상이
   * 나온다(PR #200 codex P1). 같으면 저장하지 않고 바로 주문한다(판 번호를 헛되이 늘리지 않게).
   * 주문에 업로드 정보를 실으면 렌더가 끝나는 대로 clip이 올린다(POK-291). 웹이 렌더 끝을 기다렸다 다시 부르지 않는다.
   */
  const submitUpload = useCallback(
    (draft: UploadDraft) => {
      const current = saveRef.current;
      if (pending === null || current.busy !== null) return;
      lastDraft.current = draft;
      const doc = documentFor(pending.recipe);
      const dirty =
        current.recipeId === null ||
        current.savedDoc === null ||
        !sameRecipe(current.savedDoc, doc);
      const upload = uploadInfoOf(draft, pending.cutLengthMs);
      setUploadErrors({});
      setSave((s) => ({
        ...s,
        busy: 'render',
        label: dirty ? '저장하고 영상 주문하는 중…' : '영상 주문하는 중…',
      }));
      // 어느 단계에서 거절됐나. 저장 문의 400(field)은 업로드 정보 칸이 아니다
      let stage: 'save' | 'render' = 'save';
      (dirty
        ? persist(current.recipeId, doc).then((snap) => snap.id)
        : Promise.resolve(current.recipeId as number)
      )
        .then((recipeId) => {
          stage = 'render';
          return requestRender(
            streamId,
            recipeId,
            upload,
            draft.thumbnailSource === 'file' ? draft.file : null,
          );
        })
        .then(({ created, clip }) => {
          setSave((s) => ({
            ...s,
            busy: null,
            clip,
            uploadRequest: clip.uploadRequest
              ? { version: clip.recipeVersion, summary: clip.uploadRequest }
              : s.uploadRequest,
            label: `편집본 #${s.recipeId} v${s.version} · ${clipLabel(clip)}`,
          }));
          setPending(null);
          // 200은 새 주문이 아니다: 같은 판이 만드는 중이면 끝난 뒤 이 정보로, 이미 만들어져 있으면 지금 올린다
          toast({
            tone: 'success',
            title: created
              ? '영상을 만들고 유튜브에 올릴게요'
              : clip.status === 'rendered'
                ? '만들어 둔 영상을 바로 유튜브에 올릴게요'
                : '이 판은 이미 만드는 중이에요. 다 만들어지면 고른 정보로 올려요',
            description: `${clipLabel(clip)}. 진행은 보관함에서 볼 수 있어요.`,
            action: { label: '보관함 보기', onClick: () => router.push('/clips/library') },
          });
        })
        .catch((e: unknown) => {
          const message = messageOf(e);
          setSave((s) => ({ ...s, busy: null, label: `주문 실패 · ${message}` }));
          const fieldErrors = stage === 'render' ? uploadFieldErrorOf(e) : null;
          // 칸을 짚은 거절은 창 안 그 칸에. 고쳐서 다시 보내는 자리가 창이다
          if (fieldErrors !== null) {
            setUploadErrors(fieldErrors);
            return;
          }
          setPending(null);
          toast({ tone: 'error', title: '영상 만들기 실패', description: message });
        });
    },
    [documentFor, pending, persist, router, streamId, toast],
  );

  // 주문한 영상이 끝날 때까지(업로드 정보가 있으면 업로드가 끝날 때까지) 5초마다 상태를 다시 읽는다. 일꾼의 보고가
  // clips·업로드 표를 바꾼다.
  const clipId = save.clip?.id ?? null;
  const clipDone = save.clip === null || clipSettled(save.clip);
  useEffect(() => {
    if (clipId === null || clipDone) return undefined;
    const t = globalThis.setInterval(() => {
      fetchClip(streamId, clipId)
        .then((clip) =>
          setSave((s) => ({
            ...s,
            clip,
            label: `편집본 #${s.recipeId} v${s.version} · ${clipLabel(clip)}`,
          })),
        )
        .catch(() => {
          /* 다음 틱에 다시 */
        });
    }, 5_000);
    return () => globalThis.clearInterval(t);
  }, [streamId, clipId, clipDone]);

  // 오디오 탭의 트랙 여섯 — 이름은 스트리머 설정에서, 켜짐·볼륨은 저장된 편집본에서(없으면 최종 믹스만 켬).
  const trackSetup = useMemo(() => {
    const savedTracks = new Map(
      (saved?.document.audio.tracks ?? [{ trackId: 0, gain: 1 }]).map((t) => [t.trackId, t.gain]),
    );
    const tracks: EditorTrack[] = [];
    const muted: Record<string, boolean> = {};
    const volumes: Record<string, number> = {};
    for (let i = 0; i < TRACK_COUNT; i += 1) {
      const gain = savedTracks.get(i);
      muted[trackId(i)] = gain === undefined;
      // 계약은 0~200%다 — 100%로 깎으면 손대지 않은 트랙도 다시 저장할 때 1.5가 1.0으로 바뀐다(PR #200 codex)
      volumes[trackId(i)] = Math.round(Math.min(2, gain ?? 1) * 100);
      tracks.push({
        id: trackId(i),
        kind: 'mic',
        label: trackDisplayName(i, trackLabels?.[i]),
        volume: 100,
        muted: false,
        clips: [],
      });
    }
    return { tracks, muted, volumes };
  }, [saved, trackLabels]);

  const actions = useMemo(
    () => ({ saveDraft, requestUpload: openUploadDialog }),
    [saveDraft, openUploadDialog],
  );
  // 저장된 편집본을 열면 그 모양(레이아웃·자르는 자리·자막 방식)으로 연다 — 다시 저장해도 제자리다
  const initialLook = useMemo(
    () => (saved ? lookFromDocument(saved.document) : undefined),
    [saved],
  );
  // 저장된 자막 줄 — 영상에 타는 줄을 화면에도 보여 준다. 표기는 구간 시작 기준 초(시안 「02.1」)
  const initialSubtitles = useMemo(() => {
    const cut = saved?.document.cut;
    const segments = saved?.document.subtitles?.segments ?? [];
    if (!cut) return undefined;
    return segments
      .filter(
        (seg) => seg.endAtMs > cut.inAtMs && seg.startAtMs < cut.outAtMs && seg.text.trim() !== '',
      )
      .map((seg, i) => ({
        id: `saved-${i}`,
        timecode: (Math.max(0, seg.startAtMs - cut.inAtMs) / 1000).toFixed(1).padStart(4, '0'),
        text: seg.text,
      }));
  }, [saved]);
  const label =
    save.clip && save.busy === null && !save.label.includes('영상 #')
      ? `${save.label} · ${clipLabel(save.clip)}`
      : save.label;

  return (
    <>
      {recording ? (
        // 보이지 않는 영상 노드 하나 — 프레임은 미리보기 칸의 캔버스들이 옮겨 그린다(VideoSurface).
        <video
          ref={video.videoRef}
          src={video.src || undefined}
          playsInline
          preload="auto"
          style={{ position: 'fixed', width: 1, height: 1, opacity: 0, pointerEvents: 'none' }}
        />
      ) : null}
      <StudioEditor
        playback={recording ? video.playback : undefined}
        previewVideo={recording ? video.video : null}
        source={source}
        actions={actions}
        autosaveLabel={label}
        uploadLabel="영상 만들기"
        mixTrackId={trackId(0)}
        tracks={trackSetup.tracks}
        initialTrackMuted={trackSetup.muted}
        initialTrackVolumes={trackSetup.volumes}
        initialLook={initialLook}
        initialSubtitles={initialSubtitles}
      />
      {pending !== null ? (
        <UploadInfoDialog
          open
          busy={save.busy === 'render'}
          initial={pending.initial}
          cutLengthMs={pending.cutLengthMs}
          playheadOffsetMs={pending.playheadOffsetMs}
          serverErrors={uploadErrors}
          onCancel={() => setPending(null)}
          onSubmit={submitUpload}
        />
      ) : null}
    </>
  );
}
