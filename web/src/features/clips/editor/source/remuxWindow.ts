import type * as Mediabunny from 'mediabunny';
import { readTrackSamples, readVideoTrack, type VideoTrackInfo } from '@/lib/fmp4Boxes';
import type { WindowBytes } from './fetchWindowBytes';

// 창 조각(init + ③ 조각)을 다시 인코딩하지 않고 일반 mp4 하나로 다시 싼다(설계 4절 · 6절 F2). 엔진은 mediabunny 1.65.2 —
// 편집기를 열 때만 쓰므로 동적으로 불러 첫 화면 묶음에 넣지 않는다. 판을 올리면 실측 M1 을 다시 돈다(6절 위험 10).
//
// 🔴 출력 0초는 창 안 **트랙별 첫 PTS 의 최솟값**이다(소리가 영상보다 앞이면 소리 첫 PTS — 2차 평가 3절 2). 그래서 원점을
//    「첫 영상 PTS」로 셈하지 않고 변환 뒤 출력에서 첫 영상 패킷 시각을 읽어 Δ 를 정한다(6절 위험 2).
// 🔴 소리가 영상보다 앞인 창이면 출력 영상 trak 에 빈 편집이 붙는다: elst 첫 항목 = 빈 편집(media_time −1, 길이 = 첫 영상
//    PTS − 출력 0초) · 둘째 = 영상 전체, 그리고 cslg. M0 픽스처는 빈 편집 4551/57600(0.0790104초) · cslg 3859 · −3859 ·
//    5760(testdata/README.md). 엔진마다 빈 편집을 다르게 다룬다 — Firefox 160 은 duration 에서 그 길이를 빼고, Safari 는 그
//    구간 [0, 첫 영상 PTS) 으로 시크하면 앞 그림을 남긴다(실측 2026-11-12-prw2-m1). 화면 규칙은 seekGuard 가 진다.
// 🔴 mediabunny 1.65.1 부터 Conversion 은 GOP 시각 규칙을 어기는 패킷을 조용히 버린다(Vanilagy/mediabunny#614). 입력 영상
//    표본 수와 출력 영상 패킷 수가 다르면 RemuxMismatchError 로 끝낸다 — 프레임 표가 출력과 어긋난 창을 내보내지 않는다.
// trim 은 쓰지 않는다(창 = 조각 경계). 시각은 모두 초이고, 조각 표는 ③ 축(그 조각 tfdt 기준) 값이다.
//
// 메모리(M3 실패 처분 (가) — 실측 2026-11-13-prw2-m3): 200초 창이면 ③ 입력만 약 190MiB 다. 그래서
//   - 입력을 한 덩어리로 이어 붙이지 않는다. 조각 배열을 가로질러 읽는 원천(CustomSource)에 조각의 subarray 를 넘긴다.
//     변환기는 패킷 바이트를 원천이 준 바이트의 subarray 로 쥐므로(isobmff-demuxer fetchPacketInFragment · reader
//     readBytes), 넓게 미리 읽게 해(prefetch 'network') 패킷 대부분이 조각 바이트를 그대로 가리키게 한다.
//   - 변환이 끝나면 입력을 닫고(Input.dispose — 아직 도는 미리 읽기를 멈춘다) 입력 조각을 놓는다(WindowParts.release) —
//     출력을 확인하고 Blob 을 만드는 동안 입력 조각이 살지 않는다.
//   - 부른 쪽이 signal 을 끊으면 변환을 멈춘다(Conversion.cancel) — 끊긴 카드의 변환이 끝까지 돌며 창 크기만큼의 출력을
//     편집기 받기와 겹쳐 쥐지 않는다.
// 다시 싼 바이트는 원천과 무관하게 같다(remuxWindow.bytes.test.ts 가 바꾸기 전 판의 sha 로 단언한다).

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

/**
 * 창 바이트를 일반 mp4 로 다시 싼다. init · 조각을 읽지 못하면 변환 전에 던진다. 변환이 끝나면 창 바이트를 더 쥐지
 * 않는다 — 부른 쪽도 쥐고 있지 않으면 출력을 확인하는 동안 거둘 수 있다. signal 을 끊으면 변환을 멈추고 signal.reason
 * (보통 AbortError)으로 끝난다
 */
export function remuxWindow(bytes: WindowBytes, signal?: AbortSignal): Promise<RemuxedWindow> {
  // async 로 두지 않는다: async 함수는 기다리는 동안 인자를 끝까지 쥔다(V8). 창 바이트는 놓을 수 있는 그릇에만 둔다
  return remuxParts(new WindowParts(bytes), signal);
}

async function remuxParts(
  parts: WindowParts,
  signal: AbortSignal | undefined,
): Promise<RemuxedWindow> {
  const { segments, inputFrames, inputFirstPts } = windowTable(parts.bytes);
  const mb = await import('mediabunny');
  const output = await convert(mb, parts, signal).finally(() => parts.release());
  const packets = await videoPackets(mb, output);
  if (packets.count !== inputFrames) throw new RemuxMismatchError(inputFrames, packets.count);
  return {
    blob: new Blob([output], { type: 'video/mp4' }),
    shiftSeconds: inputFirstPts - packets.firstPts,
    segments,
  };
}

/** 조각 표 · 입력 영상 표본 수 · 입력 첫 영상 PTS. 창 바이트는 붙들지 않는다 */
function windowTable(bytes: WindowBytes): {
  segments: RemuxedSegment[];
  inputFrames: number;
  inputFirstPts: number;
} {
  const video = readVideoTrack(bytes.init);
  if (video === null) throw new Error('init 에 영상 트랙이 없다');
  if (bytes.segments.length === 0) throw new Error('창 조각이 없다');
  const segments = bytes.segments.map((seg) => segmentTable(bytes.init, seg, video));
  return {
    segments,
    inputFrames: segments.reduce((sum, seg) => sum + seg.frames.length, 0),
    inputFirstPts: Math.min(...segments.map((seg) => seg.mediaStart)),
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

/** mediabunny 복사 변환(trim 없음) — moov 를 앞에 둔 일반 mp4 바이트. 끝나면(실패 · 끊김 포함) 입력을 닫는다 */
async function convert(
  mb: typeof Mediabunny,
  parts: WindowParts,
  signal: AbortSignal | undefined,
): Promise<ArrayBuffer> {
  const source = new mb.CustomSource({
    getSize: () => parts.size,
    read: (start, end) => parts.read(start, end),
    prefetchProfile: 'network',
  });
  const input = new mb.Input({ source, formats: mb.ALL_FORMATS });
  // 닫지 않고 창 바이트를 놓으면, 아직 도는 미리 읽기 작업자가 놓은 바이트를 읽다 던진 오류를 받을 곳이 없다
  try {
    return await convertInput(mb, input, signal);
  } finally {
    input.dispose();
  }
}

async function convertInput(
  mb: typeof Mediabunny,
  input: Mediabunny.Input,
  signal: AbortSignal | undefined,
): Promise<ArrayBuffer> {
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
  signal?.throwIfAborted();
  const cancel = () => void conversion.cancel();
  signal?.addEventListener('abort', cancel, { once: true });
  try {
    await conversion.execute();
  } catch (e) {
    // 끊어서 멈춘 변환은 ConversionCanceledError 를 던진다 — 부른 쪽에는 끊은 까닭으로 알린다
    throw signal?.aborted ? signal.reason : e;
  } finally {
    signal?.removeEventListener('abort', cancel);
  }
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

/**
 * 창 바이트(init + 조각)를 이어 붙이지 않고 한 파일처럼 읽게 쥔다. release 뒤에는 바이트를 쥐지 않고, 읽으면 던진다
 */
class WindowParts {
  private window: WindowBytes | null;
  private parts: readonly Uint8Array[] | null;
  readonly size: number;

  constructor(window: WindowBytes) {
    this.window = window;
    this.parts = [window.init, ...window.segments.map((seg) => seg.bytes)];
    this.size = this.parts.reduce((sum, part) => sum + part.byteLength, 0);
  }

  get bytes(): WindowBytes {
    if (this.window === null) throw new Error('창 바이트를 이미 놓았다');
    return this.window;
  }

  /** [start, end) — 한 조각 안이면 그 subarray, 조각을 가로지르면 조각별 subarray 를 차례로 내는 스트림 */
  read(start: number, end: number): Uint8Array | ReadableStream<Uint8Array> {
    if (this.parts === null) throw new Error('창 바이트를 이미 놓았다');
    const chunks = sliceParts(this.parts, start, end);
    if (chunks.length === 1) return chunks[0]!;
    return new ReadableStream({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(chunk));
        controller.close();
      },
    });
  }

  release(): void {
    this.window = null;
    this.parts = null;
  }
}

/**
 * parts 를 차례로 이은 파일의 [start, end) 를 조각마다 subarray 로 준다(복사하지 않는다). 빈 조각은 건너뛴다.
 * 범위가 파일 밖이거나 뒤집혔으면 RangeError
 */
export function sliceParts(parts: readonly Uint8Array[], start: number, end: number): Uint8Array[] {
  const size = parts.reduce((sum, part) => sum + part.byteLength, 0);
  if (start < 0 || end > size || start > end) {
    throw new RangeError(`창 바이트 범위 밖 — [${start}, ${end}) · 크기 ${size}`);
  }
  const chunks: Uint8Array[] = [];
  let partStart = 0;
  for (const part of parts) {
    const partEnd = partStart + part.byteLength;
    const from = Math.max(start, partStart);
    const to = Math.min(end, partEnd);
    if (from < to) chunks.push(part.subarray(from - partStart, to - partStart));
    partStart = partEnd;
  }
  return chunks;
}
