import type { SegmentAudioAccess } from '@/api/clipEditor';
import { runLimited } from '@/lib/runLimited';
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

export interface SegmentAudioSource {
  fetchAccess(): Promise<SegmentAudioAccess>;
  fetchBytes(url: string): Promise<ArrayBuffer>;
  /** 창 닻. 받기를 다 마친 뒤에 묻는다 — 영상 창(③ 다시 싸기)이 늦게 와도 소리 받기는 먼저 간다 */
  anchors(): Promise<SegmentAnchors>;
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

/** 하나라도 실패하면 거기서 멈춘다: 결과는 버리는데 남은 조각을 끝까지 받으면 수십~백수십 MB를 헛받는다(PR #341 claude) */
async function inParallel<T, R>(items: readonly T[], work: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  let failed = false;
  const worker = async () => {
    while (!failed && next < items.length) {
      const index = next++;
      try {
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
function appendTrack(runs: Run[], track: SegmentAudioTrack, startSeconds: number): void {
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
  runs.push({ config: track.config, startSeconds, frames: [...track.frames] });
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
      appendTrack(runs[k], track, anchor + track.timing.firstPts - videoDts);
    });
  }
  // 앞 조각에 없던 트랙 번호(가운데가 빈 자리)도 빈 목록으로 둔다
  return Array.from(runs, (trackRuns) => trackRuns ?? []);
}

/**
 * @param seqs 창 조각 seq. 주소 응답에 같이 온 그 밖의 조각(창과 겹치는 이웃)은 놓을 Blob 자리가 없어 받지 않는다
 * @param precheck 조각 목록(주소 응답)만 보고 본문을 받을지 정한다. false면 하나도 받지 않고 SegmentsNotReadyError로 던진다
 * @returns 트랙 번호(파일 순서) → 덩어리들(Blob 시각 순). 받을 조각이 없으면 빈 목록. 주소·내려받기가 실패하거나 받은 조각
 *   하나라도 못 풀거나 놓을 자리(② 영상 시각 · 닻)를 모르면 던진다
 */
export async function loadTrackAudio(
  source: SegmentAudioSource,
  seqs: readonly number[],
  precheck?: (segments: SegmentAudioAccess['segments']) => boolean,
): Promise<TrackPiece[][]> {
  let current = await source.fetchAccess();
  if (precheck !== undefined && !precheck(current.segments)) throw new SegmentsNotReadyError();
  const inWindow = new Set(seqs);
  const ordered = current.segments
    .filter((segment) => inWindow.has(segment.seq))
    .sort((a, b) => a.seq - b.seq);
  // 놓을 것이 없으면 닻(영상 창)을 기다리지 않는다
  if (ordered.length === 0) return [];

  // 받기가 서명 주소 수명(기본 5분)보다 길어지면 뒤에 받는 조각의 주소가 만료된다(느린 망의 3분 구간 ≈ 150MB):
  // 그 조각에서 주소를 새로 받아 이어 받는다. 동시에 여럿이 만료돼도 새로 받기는 한 번이다(PR #341 3차)
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
  const demuxed = await inParallel(ordered, async (segment) => {
    const tracks = demuxSegmentAudio(await download(segment.seq));
    if (tracks.audio.length === 0) throw new Error(`segment ${segment.seq} has no readable audio`);
    return tracks;
  });
  const anchors = await source.anchors();
  const runs = placeRuns(
    ordered.map((segment, i) => ({ seq: segment.seq, tracks: demuxed[i]! })),
    anchors,
  );

  const pieces: TrackPiece[][] = runs.map(() => []);
  const jobs = runs.flatMap((trackRuns, k) => trackRuns.map((run, j) => ({ run, k, j })));
  // 결과 순서는 트랙 · 덩어리 순서다: 끝나는 순서가 아니라 자리(k · j)에 넣는다
  await runLimited(jobs, DECODE_PARALLEL, async ({ run, k, j }) => {
    pieces[k]![j] = { startSeconds: run.startSeconds, buffer: await source.decode(toAdts(run)) };
  });
  return pieces;
}
