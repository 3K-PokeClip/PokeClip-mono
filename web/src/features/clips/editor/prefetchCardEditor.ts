import {
  fetchBroadcast,
  fetchJumpCard,
  mediaStreamId,
  type BroadcastRow,
  type JumpCard,
} from '@/api/clipEditor';
import { recordingClipUrl } from '@/api/mediaPlayback';
import type { RecordingTimeline } from '@/features/player/recordingTimeline';
import {
  editorTimelineOf,
  loadRecording,
  segmentTrackKey,
  segmentTracks,
  windowBlobs,
} from './editorMedia';
import { fetchRecordingWindow, PAD_SECONDS, windowFor } from './useEditorVideoPlayback';
import type { TrackPiece } from './trackAudio/loadTrackAudio';
import { loadSegmentTracks, PREVIEW_SAMPLE_RATE } from './trackAudio/useTrackPreview';

// 카드에서 편집기를 미리 받아 둔다(POK-499). 편집기가 처음 받는 영상 창과 소리 조각(파형도 여기서 나온다)을 같은 셈 · 같은
// 주소로 받아 editorMedia에 맡긴다. 셋이 다 오면 카드의 「편집」이 열리고, 편집기는 맡긴 것을 그대로 꺼내 쓴다.

/**
 * - ready: 다 받았다(받을 녹화가 없는 끝난 방송도 기다릴 것이 없어 ready다. 그 사정은 편집기가 알린다)
 * - pending: 녹화가 아직 덜 올라왔다(방금 생긴 카드). 조금 뒤 다시 묻는다
 */
export type CardEditorPrefetch = 'ready' | 'pending';

export interface PrefetchDeps {
  fetchJumpCard: (streamId: string, cardId: string) => Promise<JumpCard | null>;
  fetchBroadcast: (streamId: string) => Promise<BroadcastRow | null>;
  loadRecording: (broadcast: BroadcastRow) => Promise<RecordingTimeline | null>;
  fetchRecordingWindow: (streamId: string, url: string, signal?: AbortSignal) => Promise<Blob>;
  loadSegmentTracks: typeof loadSegmentTracks;
  /** 소리를 풀 문맥. 편집기의 미리 듣기와 같은 표본율이라야 같은 소리다 */
  createDecoder: () => Pick<BaseAudioContext, 'decodeAudioData'>;
}

const defaultDeps: PrefetchDeps = {
  fetchJumpCard,
  fetchBroadcast,
  loadRecording,
  fetchRecordingWindow,
  loadSegmentTracks,
  createDecoder: () => new OfflineAudioContext(1, 1, PREVIEW_SAMPLE_RATE),
};

/**
 * 영상 창 받기의 시한. 재생 서버가 요청을 받고 본문을 보내다 멈추면 미리 받기가 끝나지 않아 「편집」이 실패로도 못 넘어가고,
 * 받기 자리(PREFETCH_PARALLEL)도 계속 쥐고 있었다(PR #458 codex). 창 하나가 수십 MB라 느린 망도 넉넉히 기다린다
 */
export const PREFETCH_VIDEO_TIMEOUT_MS = 120_000;

/** 받은 소리가 창을 다 덮었다고 보는 여유(초). 조각은 4초 단위라 처음 · 끝 조각이 창보다 조금 넓다 */
const COVER_TOLERANCE_SECONDS = 1;

/** 첫 트랙이 창을 한 덩어리로 빈틈없이 덮나. 덜 올라온 조각이 있으면 그 자리에서 덩어리가 끊긴다(loadTrackAudio) */
function coversWindow(tracks: TrackPiece[][], fromSeconds: number, toSeconds: number): boolean {
  const pieces = tracks[0] ?? [];
  const only = pieces[0];
  return (
    pieces.length === 1 &&
    only !== undefined &&
    only.startSeconds <= fromSeconds + COVER_TOLERANCE_SECONDS &&
    only.startSeconds + only.buffer.duration >= toSeconds - COVER_TOLERANCE_SECONDS
  );
}

async function prefetchOnce(
  streamId: string,
  cardId: string,
  deps: PrefetchDeps,
): Promise<CardEditorPrefetch> {
  const [card, broadcast] = await Promise.all([
    deps.fetchJumpCard(streamId, cardId),
    deps.fetchBroadcast(streamId),
  ]);
  if (card === null) throw new Error(`card ${cardId} not found`);
  if (broadcast === null || broadcast.startedAt === null) throw new Error('broadcast not started');
  const live = broadcast.endedAt === null;
  const recording = await deps.loadRecording(broadcast);
  const { base, recordingEndSeconds, pieces } = editorTimelineOf(broadcast, recording);
  const range = { startSeconds: card.window.startMs / 1000, endSeconds: card.window.endMs / 1000 };
  // 방송 중에는 녹화가 구간 끝 + 여유까지 올라와야 창이 굳는다. 그 전에 받으면 편집기가 들어갈 때 창이 더 길어져 주소가
  // 달라지고(다시 받는다), 받은 영상도 구간 끝에서 잘린다
  if (live && range.endSeconds + PAD_SECONDS > recordingEndSeconds) return 'pending';
  // 끝난 방송인데 녹화가 없으면 편집기도 미리보기 없이 연다: 기다려도 오지 않는다
  if (recording === null || recordingEndSeconds <= 0) return 'ready';
  const win = windowFor(range, recordingEndSeconds, pieces);
  // 구간에 녹화가 없다(틈 안). 편집기도 받지 않고 「녹화 없음」으로 알린다
  if (win.empty) return 'ready';

  // 편집기(useEditorVideoPlayback · useTrackPreview)가 묻는 주소 · 열쇠와 같아야 꺼내 쓴다
  const videoUrl = recordingClipUrl(
    mediaStreamId(broadcast),
    base + Math.round(win.startSeconds * 1000),
    Math.max(1, win.endSeconds - win.startSeconds),
  );
  const fromMs = base + Math.round(win.startSeconds * 1000);
  const toMs = base + Math.round(win.endSeconds * 1000);
  const trackKey = segmentTrackKey(streamId, fromMs, toMs);

  const loadVideo = async () => {
    if (windowBlobs.get(videoUrl) !== undefined) return;
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), PREFETCH_VIDEO_TIMEOUT_MS);
    try {
      windowBlobs.set(
        videoUrl,
        await deps.fetchRecordingWindow(mediaStreamId(broadcast), videoUrl, abort.signal),
      );
    } finally {
      clearTimeout(timer);
    }
  };
  /** 다 덮은 소리를 받았나. 방송 중에 조각이 덜 올라왔으면 맡기지 않고 false다 */
  const loadAudio = async (): Promise<boolean> => {
    if (segmentTracks.get(trackKey) !== undefined) return true;
    const decoder = deps.createDecoder();
    const tracks = await deps.loadSegmentTracks({
      streamId,
      baseMs: base,
      fromMs,
      toMs,
      decode: (adts) => decoder.decodeAudioData(adts.buffer as ArrayBuffer),
    });
    // 소리 트랙을 하나도 못 꺼냈으면 편집기도 「소리 못 부름」이다: 준비됐다고 하지 않는다
    if (tracks.length === 0) throw new Error('no audio tracks');
    // 녹화는 올라왔어도 소리 조각은 아직 올라가는 중일 수 있다(서버는 다 올라간 조각만 준다). 덜 덮은 소리를 맡기면 편집기가
    // 그것을 그대로 꺼내 빈 자리를 무음으로 튼다(PR #458 codex). 끝난 방송이면 더 올라올 것이 없어 그대로 맡긴다
    if (live && !coversWindow(tracks, win.startSeconds, win.endSeconds)) return false;
    segmentTracks.set(trackKey, tracks);
    return true;
  };
  const [, audioComplete] = await Promise.all([loadVideo(), loadAudio()]);
  return audioComplete ? 'ready' : 'pending';
}

/** 동시에 받는 카드 수. 한 화면에 카드가 많아도 받기가 한꺼번에 몰리지 않게 줄 세운다 */
export const PREFETCH_PARALLEL = 2;

let running = 0;
const waiting: (() => void)[] = [];
const inFlight = new Map<string, Promise<CardEditorPrefetch>>();

async function inTurn<T>(work: () => Promise<T>): Promise<T> {
  if (running >= PREFETCH_PARALLEL) await new Promise<void>((resolve) => waiting.push(resolve));
  running += 1;
  try {
    return await work();
  } finally {
    running -= 1;
    waiting.shift()?.();
  }
}

/**
 * 카드 하나의 편집기 미디어를 미리 받는다. 같은 카드를 받는 중이면 그 받기를 같이 기다린다(카드가 다시 그려져도 두 번 받지
 * 않는다). 실패하면 던지고, 다시 부르면 처음부터 다시 받는다
 */
export function prefetchCardEditor(
  streamId: string,
  cardId: string,
  deps: PrefetchDeps = defaultDeps,
): Promise<CardEditorPrefetch> {
  const key = `${streamId}:${cardId}`;
  const existing = inFlight.get(key);
  if (existing) return existing;
  const started = inTurn(() => prefetchOnce(streamId, cardId, deps)).finally(() =>
    inFlight.delete(key),
  );
  inFlight.set(key, started);
  return started;
}
