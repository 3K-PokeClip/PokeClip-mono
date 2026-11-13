import { readAudioCapture, type AudioCaptureState } from '@/api/cardPositions';
import type { SegmentAudioAccess } from '@/api/clipEditor';
import { runLimited } from '@/lib/runLimited';
import type { EditorPlaylist, PlaylistSegment } from '../source/editorPlaylist';
import type { RemuxedWindow } from '../source/remuxWindow';
import {
  demuxSegmentAudio,
  toAdts,
  type AacConfig,
  type SegmentAudioTrack,
  type SegmentTracks,
} from './fmp4Audio';

// 창 조각의 ② 녹화 소리를 받아 트랙마다 풀어 둔다(POK-389 · 설계 prw2 3.3절 · 7절 c9′). 소리는 영상과 같은 시계에 놓는다 —
// 영상 노드가 트는 창 Blob 시각이다. 장부 시각(PDT)은 조각 경계에서 미디어와 다르다(판 NULL 래칫 · 판 2 겹침 · 건너뜀).
//   트랙 i(조각 k) 시작 = D3_k − Δ + (② 트랙 i 첫 PTS − D2_k)
//   D3_k − Δ = 그 조각 ③ 영상 첫 DTS 의 Blob 시각(windowAnchors) · D2_k = ② 영상 첫 DTS
// AAC는 앞 프레임에 기대 풀리므로 조각마다 끊어 풀지 않고 이어진 조각끼리 프레임을 이어 한 번에 푼다. 트랙마다 따로 본다: 반
// AAC 프레임 넘게 벌어지면 그 트랙만 새 덩어리이고, 겹치면 나중 조각 소리가 이긴다(앞 덩어리 꼬리를 자른다).
//
// 끝 소리 확보(설계 prw2 7절 c10b′): 소리 주소 응답의 audioCapture 가 attached 면 확보 조각(창 바로 뒤 ②)을 받아 그 ③ 조각의
// Blob 닻(D3 − Δ — 창은 다시 싸지 않고 ③ 머리 tfdt 만 읽음)에 놓는다. 판정은 clip 몫이고(웹은 칸을 따른다), ③ 이 GAP 이거나
// 다른 런이면 포기한다(captureRowOf). 결과(TrackAudioLoad)는 ② 기다림 고리가 다시 받을 때 previous 로 넘긴다 — 행이 같으면
// 내려받기 0, 바뀐 덩어리만 다시 푼다.

/** 트랙 하나의 한 덩어리. startSeconds는 창 Blob 시각(영상 노드의 currentTime 과 같은 시계)이다 */
export interface TrackPiece {
  startSeconds: number;
  buffer: AudioBuffer;
}

/** seq → 그 조각 ③ 영상 첫 DTS 의 창 Blob 시각(D3_k − Δ) */
export type SegmentAnchors = ReadonlyMap<number, number>;

/** 다시 싼 창의 닻. Blob 시각 t 의 ③ PTS 는 t + Δ 이므로 ③ 시각 D3_k 는 Blob 시각 D3_k − Δ 다 */
export function windowAnchors(
  remuxed: Pick<RemuxedWindow, 'shiftSeconds' | 'segments'>,
): SegmentAnchors {
  return new Map(remuxed.segments.map((seg) => [seg.seq, seg.videoDts - remuxed.shiftSeconds]));
}

/** 끝 소리 확보 — placed 놓음 · waiting 다시 물으면 바뀔 수 있음(not_ready · ③ 이 목록에 아직 없음) · none 확보 없음 */
export type CaptureOutcome = 'placed' | 'waiting' | 'none';

/** 다시 받기 재료 — 바깥은 읽지 않는다(맡겨 두는 크기 셈만) */
export interface LoadReuse {
  /** 받아 판독한 조각(seq →) — 확보 조각도 든다. 프레임은 조각 버퍼에서 복사한 것이다(fmp4Audio) */
  segments: ReadonlyMap<number, SegmentTracks>;
  /** 놓은 확보 조각과 그 Blob 닻. 없으면 null */
  capture: { seq: number; anchor: number } | null;
  /** 푼 덩어리(트랙 · 첫 seq · 자리 · 프레임 수 →) */
  decoded: ReadonlyMap<string, AudioBuffer>;
}

/** 창 소리 받기의 결과(설계 prw2 7절 c10b′) */
export interface TrackAudioLoad {
  /** 트랙 번호(파일 순서) → 덩어리들(Blob 시각 순) */
  tracks: TrackPiece[][];
  /** 창 조각 가운데 소리 주소 응답에 행이 없던 seq(② 미업로드 — 기다림 고리 사유) */
  missingSeqs: readonly number[];
  capture: CaptureOutcome;
  reuse: LoadReuse;
}

/**
 * 확보 seq 의 ③ 을 목록에서 찾는다(설계 prw2 7절 c10b′ 확보 배치). 창 뒤 조각이고 창과 같은 런(DISCONTINUITY · MAP 바뀜
 * 없음)이면 그 줄이다. 목록에 아직 없으면(GAP 줄도 없음 · 목록을 모름) 'wait', GAP 줄 · 다른 런 · 창 안이나 앞 seq 면 null(포기)
 */
export function captureRowOf(
  playlist: EditorPlaylist | null,
  segs: readonly Pick<PlaylistSegment, 'seq'>[],
  seq: number,
): PlaylistSegment | 'wait' | null {
  const last = segs.at(-1);
  if (last === undefined || seq <= last.seq) return null;
  const row = playlist?.rows.find((r) => r.seq === seq);
  if (playlist === null || row === undefined) return 'wait';
  if (row.gap) return null;
  const run = playlist.runs.find((r) => r.some((s) => s.seq === last.seq));
  return run?.some((s) => s.seq === seq) ? row : null;
}

/**
 * 확보 조각의 Blob 닻 — 찾은 줄(captureRowOf)의 ③ 영상 첫 DTS(readDts) − Δ. 기다림 · 포기는 그대로 돌려주고, 읽지 못하면
 * 포기한다(경고에는 주소를 싣지 않는다 — 서명 · 쿠키 주소)
 */
export async function captureAnchorOf(
  row: PlaylistSegment | 'wait' | null,
  shiftSeconds: number,
  readDts: (row: PlaylistSegment) => Promise<number>,
): Promise<number | 'wait' | null> {
  if (row === 'wait' || row === null) return row;
  try {
    return (await readDts(row)) - shiftSeconds;
  } catch (e) {
    console.warn(
      `[editor] 확보 조각 머리를 읽지 못해 끝 소리를 비워 둬요(seq ${row.seq} · ${(e as Error).name})`,
    );
    return null;
  }
}

export interface SegmentAudioSource {
  fetchAccess(): Promise<SegmentAudioAccess>;
  /** 조각 본문. 돌려준 버퍼는 loadTrackAudio 가 판독하고 놓는다(떼어 냄 — releaseBytes) — 따로 들고 쓰지 않는다 */
  fetchBytes(url: string): Promise<ArrayBuffer>;
  /** 창 닻. 받기를 다 마친 뒤에 묻는다 — 영상 창(③ 다시 싸기)이 늦게 와도 소리 받기는 먼저 간다 */
  anchors(): Promise<SegmentAnchors>;
  /**
   * 확보 조각 seq 의 Blob 닻(D3 − Δ). 'wait' 는 아직 놓을 수 없음, null 은 포기다. 닻(anchors) 뒤에 묻는다. 없으면 확보하지
   * 않는다
   */
  captureAnchor?(seq: number): Promise<number | 'wait' | null>;
  decode(adts: Uint8Array): Promise<AudioBuffer>;
}

/** 동시에 받는 조각 수 */
const PARALLEL = 4;
/**
 * 덩어리를 동시에 푸는 수. 트랙이 여섯이면 풀린 소리(수십 MB)와 풀기 작업이 한꺼번에 여섯 몫 선다
 */
const DECODE_PARALLEL = 2;
/** AAC LC 프레임 하나의 표본 수 */
const AAC_FRAME_SAMPLES = 1024;
/** ADTS 빈도 번호 → 표본 빈도(Hz). fmp4Audio 가 0–12 만 넘긴다 */
const SAMPLE_RATES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
];

/** 한 트랙의 한 덩어리 — 풀기 전 프레임 */
interface Run {
  /** 덩어리를 연 조각 — 다시 받기가 같은 덩어리를 알아보는 열쇠의 한 몫 */
  firstSeq: number;
  config: AacConfig;
  startSeconds: number;
  frames: Uint8Array[];
}

const sameConfig = (a: AacConfig, b: AacConfig) =>
  a.objectType === b.objectType &&
  a.frequencyIndex === b.frequencyIndex &&
  a.channelConfig === b.channelConfig;

/** 프레임 하나의 길이(초). 트랙 timescale 이 아니라 표본 빈도로 잰다(timescale 은 표본 빈도와 다를 수 있다) */
const frameSeconds = (config: AacConfig) =>
  AAC_FRAME_SAMPLES / SAMPLE_RATES[config.frequencyIndex]!;
/**
 * 이어진 소리의 덩어리 사이에 남을 수 있는 틈의 상한(초). 겹침 자르기(appendTrack)는 프레임 하나 미만의 틈을 남긴다 — 그것을
 * 가장 느린 표본 빈도(7.35kHz)의 프레임 길이로 잰다. 이보다 넓은 틈은 소리가 빈 자리다(덜 올라온 조각 등)
 */
export const MAX_SEAM_SECONDS = AAC_FRAME_SAMPLES / SAMPLE_RATES.at(-1)!;
/** 시각 차(초)를 그 트랙의 표본 수로 */
const samplesOf = (seconds: number, config: AacConfig) =>
  seconds * SAMPLE_RATES[config.frequencyIndex]!;
/**
 * 표본 수 비교의 여유. 초를 그대로 견주면 부동소수 차(0.016000000000000014)로 정확히 반 프레임이 나뉜다 — 그 오차(1e-11표본
 * 꼴)는 흡수하고 한 표본 차는 가른다
 */
const SAMPLE_EPSILON = 1e-6;

/**
 * 다 쓴 바이트를 바로 놓는다: ArrayBuffer 를 떼어 내면(길이 0 으로 옮김) 그 자리에서 메모리를 돌려준다. 놓지 않고 참조만 버리면
 * GC 가 늦게 돌아 창 하나를 받는 동안 ② 조각(영상까지 든 4MB 남짓)이 약 210MiB 쌓였다(M3 · 2026-11-13). 이미 떼어졌거나
 * (decodeAudioData 는 입력을 떼어 간다) 떼어 낼 수 없는 브라우저면 그대로 둔다(GC 가 거둔다)
 */
function releaseBytes(buffer: ArrayBufferLike): void {
  if (!(buffer instanceof ArrayBuffer) || buffer.detached || typeof buffer.transfer !== 'function')
    return;
  buffer.transfer(0);
}

/** 조각 본문을 판독하고 본문은 놓는다. 판독 결과의 프레임은 복사본이라(fmp4Audio) 본문을 놓아도 소리는 같다 */
function demuxAndRelease(bytes: ArrayBuffer): SegmentTracks {
  try {
    return demuxSegmentAudio(bytes);
  } finally {
    releaseBytes(bytes);
  }
}

/**
 * 하나라도 실패하면 거기서 멈춘다: 결과는 버리는데 남은 조각을 끝까지 받으면 수십~백수십 MB를 헛받는다(PR #341 claude). signal 이
 * 끊겨도 다음 항목을 시작하지 않고 던진다
 */
async function inParallel<T, R>(
  items: readonly T[],
  work: (item: T) => Promise<R>,
  signal: AbortSignal | undefined,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  let failed = false;
  const worker = async () => {
    while (!failed && next < items.length) {
      const index = next++;
      try {
        signal?.throwIfAborted();
        results[index] = await work(items[index]!);
      } catch (error) {
        failed = true;
        throw error;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(PARALLEL, items.length) }, worker));
  return results;
}

/** 조각 서명 주소가 만료됐다(403). 받는 쪽(fetchBytes)이 던지면 주소를 새로 받아 그 조각을 한 번 더 받는다 */
export class SegmentUrlExpiredError extends Error {
  constructor() {
    super('segment url expired');
  }
}

/** 조각 목록만 보고 받을 필요가 없다고 판정했다(precheck가 false). 본문은 받지 않았다 */
export class SegmentsNotReadyError extends Error {
  constructor() {
    super('segments not ready');
  }
}

/**
 * 조각 하나의 소리 트랙 하나를 트랙의 덩어리 목록 끝에 붙인다(E9). 앞 덩어리 끝과 반 프레임 안(정확히 반 프레임 포함)이면 이어
 * 붙이고, 그보다 벌어지면 새 덩어리, 겹치면 앞 덩어리 꼬리를 나중 소리 시작 전 프레임 경계에서 잘라 낸 뒤 새 덩어리다. 자르기는
 * 내림이다: 반올림하면 반 프레임까지 남아 두 소리가 함께 난다(32kHz 앞 4초 · 다음 3.7초 → 3.712초까지). 대신 프레임 하나가
 * 안 되는 틈(32kHz 32ms 미만)이 남는다
 */
function appendTrack(
  runs: Run[],
  track: SegmentAudioTrack,
  startSeconds: number,
  seq: number,
): void {
  const last = runs.at(-1);
  if (last !== undefined) {
    const lastFrame = frameSeconds(last.config);
    const gap = startSeconds - (last.startSeconds + last.frames.length * lastFrame);
    const joins = Math.abs(samplesOf(gap, track.config)) <= AAC_FRAME_SAMPLES / 2 + SAMPLE_EPSILON;
    if (joins && sameConfig(last.config, track.config)) {
      last.frames.push(...track.frames);
      return;
    }
    if (gap < 0) {
      const offset = samplesOf(startSeconds - last.startSeconds, last.config);
      const keep = Math.max(0, Math.floor(offset / AAC_FRAME_SAMPLES + SAMPLE_EPSILON));
      if (keep === 0) runs.pop();
      else last.frames = last.frames.slice(0, keep);
    }
  }
  runs.push({ firstSeq: seq, config: track.config, startSeconds, frames: [...track.frames] });
}

/**
 * 조각들(seq 순)의 소리 트랙을 창 Blob 시각에 놓아 트랙마다 덩어리로 묶는다. 트랙 번호는 조각 안 파일 순서다
 * @throws 조각의 닻이 없거나 ② 에 영상 시각이 없으면(놓을 자리를 모른다)
 */
function placeRuns(
  segments: readonly { seq: number; tracks: SegmentTracks }[],
  anchors: SegmentAnchors,
): Run[][] {
  const runs: Run[][] = [];
  for (const { seq, tracks } of segments) {
    const anchor = anchors.get(seq);
    if (anchor === undefined) throw new Error(`segment ${seq} is not in the window`);
    const videoDts = tracks.video?.timing?.firstDts;
    if (videoDts === undefined) throw new Error(`segment ${seq} has no video timing`);
    tracks.audio.forEach((track, k) => {
      // 표본이 없는 트랙은 그 조각에서 낼 소리가 없다
      if (track.timing === null || track.frames.length === 0) return;
      runs[k] ??= [];
      appendTrack(runs[k], track, anchor + track.timing.firstPts - videoDts, seq);
    });
  }
  // 앞 조각에 없던 트랙 번호(가운데가 빈 자리)도 빈 목록으로 둔다
  return Array.from(runs, (trackRuns) => trackRuns ?? []);
}

/** 덩어리의 열쇠 — 같은 조각에서 같은 자리에 같은 프레임 수로 열린 덩어리는 같은 소리다 */
const runKey = (k: number, run: Run) =>
  `${k}:${run.firstSeq}:${run.startSeconds}:${run.frames.length}`;

/**
 * 창 조각을 받아 판독한다. 받기가 서명 주소 수명(기본 5분)보다 길어지면 뒤에 받는 조각의 주소가 만료된다(느린 망의 3분 구간
 * ≈ 150MB): 그 조각에서 주소를 새로 받아 이어 받는다. 동시에 여럿이 만료돼도 새로 받기는 한 번이다(PR #341 3차)
 */
async function downloadSegments(
  source: SegmentAudioSource,
  first: SegmentAudioAccess,
  seqs: readonly number[],
  signal: AbortSignal | undefined,
): Promise<Map<number, SegmentTracks>> {
  let current = first;
  let refreshing: Promise<SegmentAudioAccess> | null = null;
  const refresh = (stale: SegmentAudioAccess): Promise<SegmentAudioAccess> => {
    if (current !== stale) return Promise.resolve(current);
    refreshing ??= source.fetchAccess().then(
      (next) => {
        current = next;
        refreshing = null;
        return next;
      },
      (error: unknown) => {
        refreshing = null;
        throw error;
      },
    );
    return refreshing;
  };
  const urlOf = (access: SegmentAudioAccess, seq: number) => {
    const url = access.segments.find((segment) => segment.seq === seq)?.url;
    if (url === undefined) throw new Error(`segment ${seq} missing`);
    return url;
  };
  const download = async (seq: number) => {
    const used = current;
    try {
      return await source.fetchBytes(urlOf(used, seq));
    } catch (error) {
      if (!(error instanceof SegmentUrlExpiredError)) throw error;
      // 새 주소로도 만료면(시계가 크게 어긋남 · 권한 없음) 그대로 실패다
      return source.fetchBytes(urlOf(await refresh(used), seq));
    }
  };
  // 받은 조각을 못 풀면(깨짐 · 모르는 AAC 모양) 실패다: 덜 올라온 조각(목록에 없음)처럼 건너뛰면 그 자리가 영상 소리를 끈 채
  // 무음이고 안내도 없다(PR #341 3차). 던지면 받던 나머지도 멈추고 화면은 영상 소리로 물러나 알린다
  const demuxed = await inParallel(
    seqs,
    async (seq) => {
      const tracks = demuxAndRelease(await download(seq));
      if (tracks.audio.length === 0) throw new Error(`segment ${seq} has no readable audio`);
      return tracks;
    },
    signal,
  );
  return new Map(seqs.map((seq, i) => [seq, demuxed[i]!]));
}

/** 확보를 정한 결과 — 놓을 확보 조각(seq · Blob 닻 · 판독)이 있으면 placed */
interface CaptureResolution {
  outcome: CaptureOutcome;
  placed: { seq: number; anchor: number; tracks: SegmentTracks } | null;
}

const NO_CAPTURE: CaptureResolution = { outcome: 'none', placed: null };

/**
 * 끝 소리 확보를 정한다. attached 면 확보 닻을 묻고(앞서 놓은 같은 seq 면 그것을 쓴다) 확보 조각을 받아 판독한다. 확보 조각을
 * 못 받거나 못 풀어도 창 소리는 그대로 두고 확보만 포기한다(경고에는 주소를 싣지 않는다)
 */
async function resolveCapture(
  source: SegmentAudioSource,
  state: AudioCaptureState,
  previous: TrackAudioLoad | undefined,
): Promise<CaptureResolution> {
  if (state.kind === 'notReady') return { outcome: 'waiting', placed: null };
  if (state.kind === 'none') return NO_CAPTURE;
  const { seq, url } = state.segment;
  const known = previous?.reuse.capture;
  const knownTracks = previous?.reuse.segments.get(seq);
  if (known?.seq === seq && knownTracks !== undefined) {
    return { outcome: 'placed', placed: { seq, anchor: known.anchor, tracks: knownTracks } };
  }
  if (source.captureAnchor === undefined) return NO_CAPTURE;
  try {
    const anchor = await source.captureAnchor(seq);
    if (anchor === 'wait') return { outcome: 'waiting', placed: null };
    if (anchor === null) return NO_CAPTURE;
    const tracks = demuxAndRelease(await source.fetchBytes(url));
    if (tracks.audio.length === 0) throw new Error('no readable audio');
    return { outcome: 'placed', placed: { seq, anchor, tracks } };
  } catch (e) {
    console.warn(
      `[editor] 끝 소리 확보 조각을 받지 못해 비워 둬요(seq ${seq} · ${(e as Error).name})`,
    );
    return NO_CAPTURE;
  }
}

/**
 * 풀린 소리의 첫 표본이 놓일 Blob 시각. 브라우저 decodeAudioData(ADTS)는 앞 표본을 엔진마다 다르게 버린다(실측 M6 · 2026-11-13
 * — 48kHz 기준 Chrome 0 · Firefox 1024 · WebKit · Safari 2112표본). 머리에서 버려 남은 소리가 모두 그만큼 이르다 — 버린 몫(기대
 * 길이 − 풀린 길이)만큼 뒤로 민다. 기대보다 길게 풀리면(꼬리 · SBR 두 배 빈도) 머리를 버린 근거가 없어 그대로 둔다
 */
function decodedStart(run: Run, buffer: AudioBuffer): number {
  const dropped = run.frames.length * frameSeconds(run.config) - buffer.duration;
  return run.startSeconds + Math.max(0, dropped);
}

/** 풀기 입력(ADTS — 덩어리 프레임을 이은 사본)을 풀고 놓는다 */
async function decodeAndRelease(
  decode: SegmentAudioSource['decode'],
  adts: Uint8Array,
): Promise<AudioBuffer> {
  try {
    return await decode(adts);
  } finally {
    releaseBytes(adts.buffer);
  }
}

/**
 * 덩어리를 푼다. 앞서 푼 같은 덩어리(runKey)는 다시 풀지 않는다. 결과 순서는 트랙 · 덩어리 순서다. signal 이 끊기면 다음
 * 덩어리를 풀기 시작하지 않고 던진다(풀던 것은 멈출 수 없다 — decodeAudioData)
 */
async function decodeRuns(
  runs: Run[][],
  decode: SegmentAudioSource['decode'],
  known: ReadonlyMap<string, AudioBuffer>,
  signal: AbortSignal | undefined,
): Promise<{ tracks: TrackPiece[][]; decoded: Map<string, AudioBuffer> }> {
  const tracks: TrackPiece[][] = runs.map(() => []);
  const decoded = new Map<string, AudioBuffer>();
  const jobs = runs.flatMap((trackRuns, k) =>
    trackRuns.map((run, j) => ({ run, k, j, key: runKey(k, run) })),
  );
  // 끝나는 순서가 아니라 자리(k · j)에 넣는다
  await runLimited(jobs, DECODE_PARALLEL, async ({ run, k, j, key }) => {
    signal?.throwIfAborted();
    const buffer = known.get(key) ?? (await decodeAndRelease(decode, toAdts(run)));
    decoded.set(key, buffer);
    tracks[k]![j] = { startSeconds: decodedStart(run, buffer), buffer };
  });
  return { tracks, decoded };
}

/** 앞 결과와 놓을 것(조각 seq · 확보 닻)이 같은가 — 같으면 덩어리도 같다 */
function samePlacement(
  previous: TrackAudioLoad,
  segments: ReadonlyMap<number, SegmentTracks>,
  capture: LoadReuse['capture'],
): boolean {
  const before = [...previous.reuse.segments.keys()];
  const now = [...segments.keys()];
  const known = previous.reuse.capture;
  return (
    before.length === now.length &&
    before.every((seq, i) => seq === now[i]) &&
    known?.seq === capture?.seq &&
    known?.anchor === capture?.anchor
  );
}

export interface LoadTrackAudioOptions {
  /** 조각 목록(주소 응답)만 보고 본문을 받을지 정한다. false면 하나도 받지 않고 SegmentsNotReadyError로 던진다 */
  precheck?: (segments: SegmentAudioAccess['segments']) => boolean;
  /** 앞서 받은 결과(기다림 고리) — 판독한 조각 · 확보 닻 · 푼 덩어리를 다시 쓴다 */
  previous?: TrackAudioLoad;
  /** 끊으면 다음 조각 받기 · 다음 덩어리 풀기를 시작하지 않고 던진다(받던 · 풀던 것은 끝까지 간다) */
  signal?: AbortSignal;
}

/**
 * @param seqs 창 조각 seq. 주소 응답에 같이 온 그 밖의 조각(창과 겹치는 이웃)은 놓을 Blob 자리가 없어 받지 않는다
 * @returns 트랙 번호(파일 순서) → 덩어리들(Blob 시각 순)과 빠진 seq · 확보 상태. 받을 조각이 없으면 빈 트랙. 주소 · 내려받기가
 *   실패하거나 받은 조각 하나라도 못 풀거나 놓을 자리(② 영상 시각 · 닻)를 모르면 던진다(확보 조각만의 실패는 확보 포기)
 */
export async function loadTrackAudio(
  source: SegmentAudioSource,
  seqs: readonly number[],
  options: LoadTrackAudioOptions = {},
): Promise<TrackAudioLoad> {
  const { precheck, previous, signal } = options;
  const access = await source.fetchAccess();
  if (precheck !== undefined && !precheck(access.segments)) throw new SegmentsNotReadyError();
  const inWindow = new Set(seqs);
  const rows = access.segments
    .filter((segment) => inWindow.has(segment.seq))
    .map((segment) => segment.seq)
    .sort((a, b) => a - b);
  const found = new Set(rows);
  const missingSeqs = seqs.filter((seq) => !found.has(seq));
  const state = readAudioCapture(access);
  // 놓을 것이 없으면 닻(영상 창)을 기다리지 않는다
  if (rows.length === 0) return emptyLoad(missingSeqs, state);

  const known = previous?.reuse.segments ?? new Map<number, SegmentTracks>();
  const fresh = await downloadSegments(
    source,
    access,
    rows.filter((seq) => !known.has(seq)),
    signal,
  );
  const windowLoad: WindowLoad = {
    segments: new Map(rows.map((seq) => [seq, known.get(seq) ?? fresh.get(seq)!])),
    anchors: await source.anchors(),
    missingSeqs,
    previous,
    signal,
  };
  const { outcome, placed } = await resolveCapture(source, state, previous);
  if (placed === null) return arrange(source, windowLoad, null, outcome);
  // 확보 소리는 창 끝 덩어리에 이어 붙어 같이 풀리므로 덩어리 하나만 감싸서는 창 소리를 건지지 못한다: 확보를 넣어 놓기 ·
  // 풀기가 실패하면 확보를 빼고 창 조각만으로 다시 놓아 푼다(창 조각 자체가 못 풀리면 그때 던진다 — c10b′ r1 2번)
  try {
    return await arrange(source, windowLoad, placed, outcome);
  } catch (e) {
    // 끊김은 확보 실패가 아니다: 확보를 빼고 다시 풀지 않는다
    if (signal?.aborted) throw e;
    console.warn(
      `[editor] 끝 소리 확보 조각을 놓거나 풀지 못해 비워 둬요(seq ${placed.seq} · ${(e as Error).name})`,
    );
    return arrange(source, windowLoad, null, 'none');
  }
}

/** 놓을 창 조각 — 확보 조각은 섞지 않는다 */
interface WindowLoad {
  segments: ReadonlyMap<number, SegmentTracks>;
  anchors: SegmentAnchors;
  missingSeqs: readonly number[];
  previous: TrackAudioLoad | undefined;
  signal: AbortSignal | undefined;
}

/**
 * 창 조각(과 확보 조각)을 Blob 시각에 놓아 푼다. 앞 결과와 놓을 것이 같으면 앞 결과를 그대로 쓴다
 * @throws 놓을 자리(② 영상 시각 · 닻)를 모르거나 덩어리를 못 풀면
 */
async function arrange(
  source: SegmentAudioSource,
  { segments: windowSegments, anchors, missingSeqs, previous, signal }: WindowLoad,
  placed: CaptureResolution['placed'],
  outcome: CaptureOutcome,
): Promise<TrackAudioLoad> {
  const segments = new Map(windowSegments);
  if (placed !== null) segments.set(placed.seq, placed.tracks);
  const capture = placed === null ? null : { seq: placed.seq, anchor: placed.anchor };
  if (previous !== undefined && samePlacement(previous, segments, capture)) {
    return { ...previous, missingSeqs, capture: outcome };
  }
  const runs = placeRuns(
    [...segments].map(([seq, tracks]) => ({ seq, tracks })),
    placed === null ? anchors : new Map([...anchors, [placed.seq, placed.anchor]]),
  );
  const { tracks, decoded } = await decodeRuns(
    runs,
    (adts) => source.decode(adts),
    previous?.reuse.decoded ?? new Map(),
    signal,
  );
  return { tracks, missingSeqs, capture: outcome, reuse: { segments, capture, decoded } };
}

/** 응답에 창 조각 행이 하나도 없다 — 놓을 소리가 없다. 확보는 행이 오면 다시 정한다 */
function emptyLoad(missingSeqs: readonly number[], state: AudioCaptureState): TrackAudioLoad {
  return {
    tracks: [],
    missingSeqs,
    capture: state.kind === 'none' ? 'none' : 'waiting',
    reuse: { segments: new Map(), capture: null, decoded: new Map() },
  };
}
