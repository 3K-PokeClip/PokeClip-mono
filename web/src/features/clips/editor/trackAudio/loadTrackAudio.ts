import type { SegmentAudioAccess } from '@/api/clipEditor';
import { demuxAudioTracks, toAdts, type AacConfig, type AudioTrackFrames } from './fmp4Audio';

// 구간의 녹화 조각을 받아 소리 트랙마다 풀어 둔다(POK-389). 조각마다 따로 풀지 않고 「이어진 조각끼리」 프레임을 이어 한 번에 푼다.
// AAC는 앞 프레임에 기대 풀리므로 조각마다 끊어 풀면 4초마다 이음매 소리가 난다. 사이가 빈 자리(덜 올라온 조각)에서만 끊는다.

/** 트랙 하나의 한 덩어리. startSeconds는 편집기 시간축(기준점 기준 초)이다 */
export interface TrackPiece {
  startSeconds: number;
  buffer: AudioBuffer;
}

export interface SegmentAudioSource {
  fetchAccess(): Promise<SegmentAudioAccess>;
  fetchBytes(url: string): Promise<ArrayBuffer>;
  decode(adts: Uint8Array): Promise<AudioBuffer>;
}

/** 조각 사이가 이만큼 넘게 벌어지면 다른 덩어리다(조각 길이는 ms 정수라 어긋남이 작다) */
const GAP_TOLERANCE_MS = 50;
/** 동시에 받는 조각 수 */
const PARALLEL = 4;

interface Run {
  startAtMs: number;
  endAtMs: number;
  tracks: AudioTrackFrames[];
}

const sameConfigs = (a: AudioTrackFrames[], b: AudioTrackFrames[]) =>
  a.length === b.length &&
  a.every((track, i) => {
    const other = b[i];
    return other !== undefined && sameConfig(track.config, other.config);
  });

const sameConfig = (a: AacConfig, b: AacConfig) =>
  a.objectType === b.objectType &&
  a.frequencyIndex === b.frequencyIndex &&
  a.channelConfig === b.channelConfig;

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

/**
 * @param baseMs 편집기 시간축의 0초(절대 ms): 조각의 재생 축 시각을 편집기 초로 옮긴다
 * @returns 트랙 번호(파일 순서) → 덩어리들. 소리 트랙을 하나도 못 꺼냈으면 빈 목록. 주소·내려받기·풀기가 실패하면 던진다
 */
export async function loadTrackAudio(
  source: SegmentAudioSource,
  baseMs: number,
): Promise<TrackPiece[][]> {
  const { segments } = await source.fetchAccess();
  const ordered = [...segments].sort((a, b) => a.seq - b.seq);
  const demuxed = await inParallel(ordered, async (segment) =>
    demuxAudioTracks(await source.fetchBytes(segment.url)),
  );

  const runs: Run[] = [];
  ordered.forEach((segment, i) => {
    const tracks = demuxed[i] ?? [];
    if (tracks.length === 0) return;
    const last = runs.at(-1);
    if (
      last &&
      Math.abs(segment.startAtMs - last.endAtMs) <= GAP_TOLERANCE_MS &&
      sameConfigs(last.tracks, tracks)
    ) {
      // 같은 설정이면 트랙 수도 같다(sameConfigs)
      tracks.forEach((track, k) => last.tracks[k]!.frames.push(...track.frames));
      last.endAtMs = segment.startAtMs + segment.durationMs;
    } else {
      runs.push({
        startAtMs: segment.startAtMs,
        endAtMs: segment.startAtMs + segment.durationMs,
        tracks: tracks.map((track) => ({ config: track.config, frames: [...track.frames] })),
      });
    }
  });

  const trackCount = Math.max(0, ...runs.map((run) => run.tracks.length));
  const pieces: TrackPiece[][] = Array.from({ length: trackCount }, () => []);
  for (const run of runs) {
    const buffers = await Promise.all(run.tracks.map((track) => source.decode(toAdts(track))));
    buffers.forEach((buffer, k) =>
      pieces[k]!.push({ startSeconds: (run.startAtMs - baseMs) / 1000, buffer }),
    );
  }
  return pieces;
}
