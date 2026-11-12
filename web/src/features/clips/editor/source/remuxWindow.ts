import type * as Mediabunny from 'mediabunny';
import { readTrackSamples, readVideoTrack, type VideoTrackInfo } from '@/lib/fmp4Boxes';
import type { WindowBytes } from './fetchWindowBytes';

// 창 조각(init + ③ 조각)을 다시 인코딩하지 않고 일반 mp4 하나로 다시 싼다(설계 4절 · 6절 F2). 엔진은 mediabunny 1.65.2 —
// 편집기를 열 때만 쓰므로 동적으로 불러 첫 화면 묶음에 넣지 않는다. 판을 올리면 실측 M1 을 다시 돈다(6절 위험 10).
//
// 🔴 출력 0초는 창 안 **트랙별 첫 PTS 의 최솟값**이다(소리가 영상보다 앞이면 소리 첫 PTS — 2차 평가 3절 2). 그래서 원점을
//    「첫 영상 PTS」로 셈하지 않고 변환 뒤 출력에서 첫 영상 패킷 시각을 읽어 Δ 를 정한다(6절 위험 2).
// 🔴 mediabunny 1.65.1 부터 Conversion 은 GOP 시각 규칙을 어기는 패킷을 조용히 버린다(Vanilagy/mediabunny#614). 입력 영상
//    표본 수와 출력 영상 패킷 수가 다르면 RemuxMismatchError 로 끝낸다 — 프레임 표가 출력과 어긋난 창을 내보내지 않는다.
// trim 은 쓰지 않는다(창 = 조각 경계). 시각은 모두 초이고, 조각 표는 ③ 축(그 조각 tfdt 기준) 값이다.

export interface RemuxedSegment {
  seq: number;
  /** 그 조각 영상 첫 표본의 DTS(D3_k) */
  videoDts: number;
  /** 영상 프레임 — PTS 순. dur 는 그 표본의 trun 길이 */
  frames: readonly { pts: number; dur: number }[];
  /** 영상 프레임 PTS 최솟값 */
  mediaStart: number;
  /** 영상 프레임 PTS + 길이의 최댓값 */
  mediaEnd: number;
}

export interface RemuxedWindow {
  blob: Blob;
  /** Δ: 출력 시각 t 의 ③ PTS = t + shiftSeconds */
  shiftSeconds: number;
  segments: readonly RemuxedSegment[];
}

/** 입력 영상 표본 수와 출력 영상 패킷 수가 다르다(변환기가 패킷을 버렸다) */
export class RemuxMismatchError extends Error {
  constructor(
    readonly inputFrames: number,
    readonly outputPackets: number,
  ) {
    super(`재다중화 패킷 수가 다르다 — 입력 ${inputFrames} · 출력 ${outputPackets}`);
    this.name = 'RemuxMismatchError';
  }
}

/** 창 바이트를 일반 mp4 로 다시 싼다. init · 조각을 읽지 못하면 변환 전에 던진다 */
export async function remuxWindow(bytes: WindowBytes): Promise<RemuxedWindow> {
  const video = readVideoTrack(bytes.init);
  if (video === null) throw new Error('init 에 영상 트랙이 없다');
  if (bytes.segments.length === 0) throw new Error('창 조각이 없다');
  const segments = bytes.segments.map((seg) => segmentTable(bytes.init, seg, video));
  const inputFrames = segments.reduce((sum, seg) => sum + seg.frames.length, 0);
  const inputFirstPts = Math.min(...segments.map((seg) => seg.mediaStart));

  const mb = await import('mediabunny');
  const output = await convert(mb, concat([bytes.init, ...bytes.segments.map((seg) => seg.bytes)]));
  const packets = await videoPackets(mb, output);
  if (packets.count !== inputFrames) throw new RemuxMismatchError(inputFrames, packets.count);
  return {
    blob: new Blob([output], { type: 'video/mp4' }),
    shiftSeconds: inputFirstPts - packets.firstPts,
    segments,
  };
}

function segmentTable(
  init: Uint8Array,
  seg: { seq: number; bytes: Uint8Array },
  video: VideoTrackInfo,
): RemuxedSegment {
  const samples = readTrackSamples(init, seg.bytes, video.trackId) ?? [];
  const [first] = samples;
  if (first === undefined) throw new Error(`조각 ${seg.seq} 에 읽을 영상 표본이 없다`);
  const seconds = (ticks: number) => ticks / video.timescale;
  const frames = samples
    .map((s) => ({ pts: seconds(s.pts), dur: seconds(s.duration) }))
    .sort((a, b) => a.pts - b.pts);
  return {
    seq: seg.seq,
    videoDts: seconds(first.dts),
    frames,
    mediaStart: Math.min(...frames.map((f) => f.pts)),
    mediaEnd: Math.max(...frames.map((f) => f.pts + f.dur)),
  };
}

/** mediabunny 복사 변환(trim 없음) — moov 를 앞에 둔 일반 mp4 바이트 */
async function convert(mb: typeof Mediabunny, file: Uint8Array): Promise<ArrayBuffer> {
  const input = new mb.Input({ source: new mb.BufferSource(file), formats: mb.ALL_FORMATS });
  const target = new mb.BufferTarget();
  const output = new mb.Output({
    format: new mb.Mp4OutputFormat({ fastStart: 'in-memory' }),
    target,
  });
  const conversion = await mb.Conversion.init({ input, output });
  if (!conversion.isValid) {
    const reasons = conversion.discardedTracks.map((d) => d.reason).join(', ');
    throw new Error(`재다중화를 할 수 없다 — ${reasons}`);
  }
  await conversion.execute();
  if (target.buffer === null) throw new Error('재다중화 출력이 비었다');
  return target.buffer;
}

/** 출력 영상 패킷 수와 가장 이른 패킷 시각(초) */
async function videoPackets(
  mb: typeof Mediabunny,
  file: ArrayBuffer,
): Promise<{ count: number; firstPts: number }> {
  const input = new mb.Input({ source: new mb.BufferSource(file), formats: [mb.MP4] });
  const track = await input.getPrimaryVideoTrack();
  if (track === null) throw new Error('재다중화 출력에 영상 트랙이 없다');
  let count = 0;
  let firstPts = Number.POSITIVE_INFINITY;
  const sink = new mb.EncodedPacketSink(track);
  for await (const packet of sink.packets(undefined, undefined, { metadataOnly: true })) {
    count += 1;
    firstPts = Math.min(firstPts, packet.timestamp);
  }
  return { count, firstPts };
}

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.byteLength, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.byteLength;
  }
  return out;
}
