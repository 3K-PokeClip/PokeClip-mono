import {
  fetchAllJumpCards,
  fetchBroadcast,
  mediaStreamId,
  type BroadcastRow,
  type JumpCard,
} from '@/api/clipEditor';
import { mediaControlBaseUrl } from '@/features/player/mediaSource';
import type { XAnswer } from '@/features/player/rewindSwitch';
import { onSessionAdvance } from '@/stores/auth';
import { segmentTrackKey, segmentTracks, windowBlobs } from './editorMedia';
import { editorXResolver, streamerIdOf } from './source/broadcastStreamer';
import { editorSourceView, type EditorSourceState } from './source/editorSourceState';
import type { PlaylistSegment } from './source/editorPlaylist';
import type { RemuxedWindow } from './source/remuxWindow';
import { readEditorSourceOnce, type EditorSourceSnapshot } from './source/useEditorSource';
import { decideWindow, ensureWindowHeads, fetchEditorWindow } from './source/editorWindow';
import {
  GAP_TOLERANCE_MS,
  SegmentsNotReadyError,
  type TrackPiece,
} from './trackAudio/loadTrackAudio';
import { loadSegmentTracks, PREVIEW_SAMPLE_RATE } from './trackAudio/useTrackPreview';

// 카드에서 편집기를 미리 받아 둔다(POK-499). 편집기가 처음 받는 영상 창과 소리 조각(파형도 여기서 나온다)을 같은 셈 · 같은
// 열쇠로 받아 editorMedia에 맡긴다. 셋이 다 오면 카드의 「편집」이 열리고, 편집기는 맡긴 것을 그대로 꺼내 쓴다.
// 영상 창은 편집기처럼 ③ 되감기 목록에서 정하고(decideWindow) 조각을 받아 다시 싼다(POK-473). 녹화 재생 서버(/playback)는
// 부르지 않는다. 판 NULL 창이면 ② 머리도 함께 읽어 둔다(편집기가 다시 읽지 않는다).

/**
 * - ready: 다 받았다(받을 조각이 없거나 원천이 끝내 막힌 방송도 기다릴 것이 없어 ready다. 그 사정은 편집기가 알린다)
 * - pending: 목록이 아직 덜 올라왔거나 원천이 아직 서지 않았다(방금 생긴 카드 · 회차 정리 중). 조금 뒤 다시 묻는다
 */
export type CardEditorPrefetch = 'ready' | 'pending';

export interface PrefetchDeps {
  fetchJumpCard: (streamId: string, cardId: string) => Promise<JumpCard | null>;
  fetchBroadcast: (streamId: string) => Promise<BroadcastRow | null>;
  /** 편집기 원천 한 번 읽기 — 상태 · ③ 목록 · base(편집기와 같은 판정) */
  readSource: (broadcast: BroadcastRow) => Promise<EditorSourceSnapshot>;
  fetchEditorWindow: (
    mediaKey: string,
    segs: readonly PlaylistSegment[],
    signal?: AbortSignal,
  ) => Promise<RemuxedWindow>;
  ensureWindowHeads: (
    streamId: string,
    mediaKey: string,
    segs: readonly PlaylistSegment[],
    signal?: AbortSignal,
  ) => Promise<void>;
  loadSegmentTracks: typeof loadSegmentTracks;
  /** 소리를 풀 문맥. 편집기의 미리 듣기와 같은 표본율이라야 같은 소리다 */
  createDecoder: () => Pick<BaseAudioContext, 'decodeAudioData'>;
}

/** 카드 목록 · 끝난 방송의 원천을 다시 쓰는 시간 */
const LIST_REUSE_MS = 30_000;
/**
 * 방송 중 원천(③ 목록)을 다시 쓰는 시간 — 편집기가 창 끝을 기다리며 목록을 다시 받는 간격과 같다(설계 prw2 5절 2). 녹화 기다림의
 * 재확인(useCardEditorReady, 10초)이 새 목록 끝을 본다. 같은 방송의 카드 여럿이 한꺼번에 묻는 것만 나눈다
 */
const LIVE_SOURCE_REUSE_MS = 4_000;

/**
 * 키마다 읽기 하나를 잠깐 기억한다. 읽는 중이면 그 읽기를 같이 기다리고(동시 요청 합침), 실패는 기억하지 않는다.
 * 세션이 바뀌면 비운다: 비운 뒤에는 비우기 전에 시작한 읽기가 늦게 끝나도 다시 들어오지 않는다
 */
function shortLived<T>() {
  const entries = new Map<string, { settledAt: number | null; value: Promise<T> }>();
  return {
    read(
      key: string,
      reuseMs: number,
      load: () => Promise<T>,
    ): { value: Promise<T>; reused: boolean } {
      const known = entries.get(key);
      if (
        known !== undefined &&
        (known.settledAt === null || Date.now() - known.settledAt < reuseMs)
      )
        return { value: known.value, reused: true };
      const entry = { settledAt: null as number | null, value: load() };
      entries.set(key, entry);
      entry.value.then(
        () => {
          entry.settledAt = Date.now();
        },
        () => {
          if (entries.get(key) === entry) entries.delete(key);
        },
      );
      return { value: entry.value, reused: false };
    },
    drop: (key: string) => entries.delete(key),
    clear: () => entries.clear(),
  };
}

/** 방송 → 가린 카드까지 전체 카드 목록. 카드 하나를 찾으려고 목록 전체를 읽으므로(fetchJumpCard) 카드마다 읽지 않게 나눈다 */
const cardLists = shortLived<JumpCard[]>();
/** 방송(과 끝남 여부) → 편집기 원천(③ 목록 · base) */
const sources = shortLived<EditorSourceSnapshot>();
/** 방송 → 지금 방송 번호 묻기(R58). 채널 번호 판정(관계 · 위임 조회)을 카드마다 하지 않게 나눈다 */
const resolvers = new Map<string, (fresh: boolean) => Promise<XAnswer>>();

/** 기억해 둔 카드 목록 · 원천을 비운다. 계정이 바뀔 때 · 화면 시험 사이에 부른다 */
export function forgetPrefetchLists(): void {
  cardLists.clear();
  sources.clear();
  resolvers.clear();
}
onSessionAdvance(forgetPrefetchLists);

/**
 * 카드 하나. 기억한 목록에 없으면 낡은 목록일 수 있다(방송 중 새로 생긴 카드)고 보고 한 번 다시 읽는다 — 방금 읽은 목록에도 없으면
 * 정말 없는 카드다. 카드에서 보관함 저장(saveCardDraft)도 같은 목록을 쓴다
 */
export async function cardFromList(streamId: string, cardId: string): Promise<JumpCard | null> {
  const load = () => fetchAllJumpCards(streamId, undefined, { includeHidden: true });
  const find = (cards: JumpCard[]) => cards.find((card) => String(card.id) === cardId) ?? null;
  const first = cardLists.read(streamId, LIST_REUSE_MS, load);
  const card = find(await first.value);
  if (card !== null || !first.reused) return card;
  cardLists.drop(streamId);
  return find(await cardLists.read(streamId, LIST_REUSE_MS, load).value);
}

function resolverOf(broadcast: BroadcastRow): (fresh: boolean) => Promise<XAnswer> {
  const known = resolvers.get(broadcast.streamId);
  if (known !== undefined) return known;
  const resolver = editorXResolver(streamerIdOf(broadcast), mediaStreamId(broadcast));
  resolvers.set(broadcast.streamId, resolver);
  return resolver;
}

function sourceOf(broadcast: BroadcastRow): Promise<EditorSourceSnapshot> {
  const live = broadcast.endedAt === null;
  return sources.read(
    `${broadcast.streamId}:${broadcast.endedAt ?? 'live'}`,
    live ? LIVE_SOURCE_REUSE_MS : LIST_REUSE_MS,
    () => readEditorSourceOnce(broadcast, resolverOf(broadcast)),
  ).value;
}

const defaultDeps: PrefetchDeps = {
  fetchJumpCard: cardFromList,
  fetchBroadcast,
  readSource: sourceOf,
  fetchEditorWindow,
  ensureWindowHeads,
  loadSegmentTracks,
  createDecoder: () => new OfflineAudioContext(1, 1, PREVIEW_SAMPLE_RATE),
};

/**
 * 영상 창 받기의 시한. 조각 받기가 본문을 보내다 멈추면 미리 받기가 끝나지 않아 「편집」이 실패로도 못 넘어가고, 받기
 * 자리(PREFETCH_PARALLEL)도 계속 쥐고 있었다(PR #458 codex). 창 하나가 수십 MB라 느린 망도 넉넉히 기다린다
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

/**
 * 조각 목록만 보고 구간을 한 덩어리로 빈틈없이 덮나(coversWindow의 목록판). 덜 올라온 조각이 있으면 거기서 틈이 난다.
 * 목록이 비었으면 true다: 그때는 받을 것이 없어 아래의 「소리 트랙 없음」 판정이 맡는다
 */
function segmentsCover(
  segments: { seq: number; startAtMs: number; durationMs: number }[],
  fromMs: number,
  toMs: number,
): boolean {
  const ordered = [...segments].sort((a, b) => a.seq - b.seq);
  const first = ordered[0];
  if (first === undefined) return true;
  if (first.startAtMs > fromMs + COVER_TOLERANCE_SECONDS * 1000) return false;
  let end = first.startAtMs + first.durationMs;
  for (const segment of ordered.slice(1)) {
    if (Math.abs(segment.startAtMs - end) > GAP_TOLERANCE_MS) return false;
    end = segment.startAtMs + segment.durationMs;
  }
  return end >= toMs - COVER_TOLERANCE_SECONDS * 1000;
}

/** 원천이 끝내 막힌 상태 — 기다려도 창이 서지 않는다(편집기가 그 까닭을 알린다) */
const SETTLED_SOURCE: ReadonlySet<EditorSourceState['kind']> = new Set([
  'expired',
  'goneOrNone',
  'access',
  'outsideDvr',
]);

/** 창을 정할 수 없는 원천 상태의 미리 받기 판정. 제어면이 없는 환경은 받을 것이 없어 ready 다 */
function verdictOfSource(view: EditorSourceState): CardEditorPrefetch {
  if (SETTLED_SOURCE.has(view.kind)) return 'ready';
  if (view.kind === 'unavailable' && mediaControlBaseUrl() === null) return 'ready';
  return 'pending';
}

/** 카드 창의 소리 조각을 받아 맡긴다. 다 덮은 소리를 받았나 — 방송 중에 조각이 덜 올라왔으면 맡기지 않고 false다 */
async function prefetchAudio(
  streamId: string,
  base: number,
  range: { fromMs: number; toMs: number; startSeconds: number; endSeconds: number },
  live: boolean,
  deps: PrefetchDeps,
): Promise<boolean> {
  const { fromMs, toMs } = range;
  const trackKey = segmentTrackKey(streamId, fromMs, toMs);
  if (segmentTracks.get(trackKey) !== undefined) return true;
  const decoder = deps.createDecoder();
  let tracks: TrackPiece[][];
  try {
    tracks = await deps.loadSegmentTracks({
      streamId,
      baseMs: base,
      fromMs,
      toMs,
      decode: (adts) => decoder.decodeAudioData(adts.buffer as ArrayBuffer),
      // 방송 중에 조각 목록이 구간을 덮지 못하면 본문을 받지 않는다: 어차피 덜 덮은 소리는 맡기지 않고, 다시 확인할 때마다
      // 같은 구간을 통째로 받아 풀었다(10초마다 최대 6번)
      precheck: live ? (segments) => segmentsCover(segments, fromMs, toMs) : undefined,
    });
  } catch (error) {
    if (error instanceof SegmentsNotReadyError) return false;
    throw error;
  }
  // 소리 트랙을 하나도 못 꺼냈으면 편집기도 「소리 못 부름」이다: 준비됐다고 하지 않는다
  if (tracks.length === 0) throw new Error('no audio tracks');
  // 영상 조각은 올라왔어도 소리 조각은 아직 올라가는 중일 수 있다(서버는 다 올라간 조각만 준다). 덜 덮은 소리를 맡기면 편집기가
  // 그것을 그대로 꺼내 빈 자리를 무음으로 튼다(PR #458 codex). 끝난 방송이면 더 올라올 것이 없어 그대로 맡긴다
  if (live && !coversWindow(tracks, range.startSeconds, range.endSeconds)) return false;
  segmentTracks.set(trackKey, tracks);
  return true;
}

/**
 * 창 조각을 받아 다시 싸 맡기고, 판 NULL 이면 ② 머리를 함께 읽는다. 머리 판독은 실패해도 미리 받기를 막지 않는다(편집기가 다시
 * 읽는다). 둘 다 시한에 끊는다
 */
async function prefetchVideo(
  streamId: string,
  mediaKey: string,
  target: { key: string; segs: readonly PlaylistSegment[] },
  deps: PrefetchDeps,
): Promise<void> {
  if (windowBlobs.get(target.key) !== undefined) return;
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), PREFETCH_VIDEO_TIMEOUT_MS);
  const stopped = new Promise<void>((resolve) =>
    abort.signal.addEventListener('abort', () => resolve(), { once: true }),
  );
  try {
    const [remuxed] = await Promise.all([
      deps.fetchEditorWindow(mediaKey, target.segs, abort.signal),
      Promise.race([
        deps.ensureWindowHeads(streamId, mediaKey, target.segs, abort.signal),
        stopped,
      ]),
    ]);
    windowBlobs.set(target.key, remuxed);
  } finally {
    clearTimeout(timer);
  }
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
  const { state, list, base } = await deps.readSource(broadcast);
  const range = { startSeconds: card.window.startMs / 1000, endSeconds: card.window.endMs / 1000 };
  if (list === null || base === null) return verdictOfSource(state);
  const rows = list.playlist.rows;
  const bounds =
    rows.length === 0
      ? null
      : { firstPdtMs: rows[0]!.pdtMs, endMs: rows.at(-1)!.pdtMs + rows.at(-1)!.durationMs };
  const view = editorSourceView(state, bounds, base + range.startSeconds * 1000);
  if (view.kind !== 'ready') return verdictOfSource(view);
  const live = list.mode === 'live';
  const mediaKey = mediaStreamId(broadcast);
  // 편집기(useEditorVideoPlayback)와 같은 셈이라야 같은 열쇠다. 방송 중에는 목록이 구간 끝 + 여유까지 올라와야 창이 굳는다 —
  // 그 전에 받으면 편집기가 들어갈 때 창이 더 길어져 열쇠가 달라지고(다시 받는다), 받은 영상도 구간 끝에서 잘린다
  const decision = decideWindow(range, list, base, mediaKey, live);
  if (decision.kind === 'wait') return 'pending';
  // 구간에 받을 조각이 없다(틈 안). 편집기도 받지 않고 「녹화 없음」으로 알린다
  if (decision.kind === 'empty') return 'ready';
  const { target } = decision;
  const audioRange = {
    fromMs: base + Math.round(target.startSeconds * 1000),
    toMs: base + Math.round(target.endSeconds * 1000),
    startSeconds: target.startSeconds,
    endSeconds: target.endSeconds,
  };
  const [, audioComplete] = await Promise.all([
    prefetchVideo(streamId, mediaKey, target, deps),
    prefetchAudio(streamId, base, audioRange, live, deps),
  ]);
  return audioComplete ? 'ready' : 'pending';
}

/** 동시에 받는 카드 수. 한 화면에 카드가 많아도 받기가 한꺼번에 몰리지 않게 줄 세운다 */
export const PREFETCH_PARALLEL = 2;

let running = 0;
const waiting: (() => void)[] = [];

interface Prefetching {
  result: Promise<CardEditorPrefetch>;
  /** 이 받기를 기다리는 쪽 수. 0이 되면 줄에서 빠진다 */
  interest: number;
  abandon: AbortController;
}
const inFlight = new Map<string, Prefetching>();

/** 차례를 기다린다. 기다리는 동안 끊기면 줄에서 빠지고 던진다 */
function awaitTurn(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const turn = () => {
      signal.removeEventListener('abort', leave);
      resolve();
    };
    const leave = () => {
      waiting.splice(waiting.indexOf(turn), 1);
      reject(new DOMException('prefetch abandoned', 'AbortError'));
    };
    waiting.push(turn);
    signal.addEventListener('abort', leave, { once: true });
  });
}

async function inTurn<T>(work: () => Promise<T>, signal: AbortSignal): Promise<T> {
  if (running >= PREFETCH_PARALLEL) await awaitTurn(signal);
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
 * 않는다). 실패하면 던지고, 다시 부르면 처음부터 다시 받는다.
 *
 * @param signal 끊으면 이 카드를 더 기다리지 않는다는 뜻이다. 기다리는 쪽이 모두 끊었고 아직 줄에 있으면 받지 않는다(카드가 화면을
 *   벗어났거나 화면을 떠났다: 맡겨 둘 자리는 넷뿐이라 받아도 버린다). 이미 받기 시작했으면 끝까지 간다: 지금까지 받은 것을 버리지 않게
 */
export function prefetchCardEditor(
  streamId: string,
  cardId: string,
  deps: PrefetchDeps = defaultDeps,
  signal?: AbortSignal,
): Promise<CardEditorPrefetch> {
  const key = `${streamId}:${cardId}`;
  let job = inFlight.get(key);
  if (job === undefined) {
    const abandon = new AbortController();
    const result = inTurn(() => prefetchOnce(streamId, cardId, deps), abandon.signal).finally(() =>
      inFlight.delete(key),
    );
    job = { result, interest: 0, abandon };
    inFlight.set(key, job);
  }
  const shared = job;
  shared.interest += 1;
  signal?.addEventListener(
    'abort',
    () => {
      shared.interest -= 1;
      if (shared.interest === 0) shared.abandon.abort();
    },
    { once: true },
  );
  return shared.result;
}
