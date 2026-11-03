// fMP4(조각 MP4) 상자를 바이트에서 바로 읽는다. 편집기 소리(trackAudio/fmp4Audio.ts)와 플레이어가 같은 상자 해석을 쓴다.
//
// 읽는 함수는 던지지 않는다: 잘린 바이트 · 모르는 모양이면 null 이나 빈 목록을 준다(fmp4Audio.ts 의 demuxAudioTracks 관례).
// 시각 값은 트랙 timescale 의 눈금 그대로 준다. 초로 바꾸는 것은 쓰는 쪽 몫이다.

export interface Box {
  type: string;
  /** 머리 뒤 첫 바이트 */
  start: number;
  end: number;
  /** 상자 머리 첫 바이트 */
  boxStart: number;
}

export function* boxes(view: DataView, start: number, end: number): Generator<Box> {
  let offset = start;
  while (offset + 8 <= end) {
    let size = view.getUint32(offset);
    const type = String.fromCharCode(
      view.getUint8(offset + 4),
      view.getUint8(offset + 5),
      view.getUint8(offset + 6),
      view.getUint8(offset + 7),
    );
    let header = 8;
    if (size === 1) {
      if (offset + 16 > end) return;
      size = Number(view.getBigUint64(offset + 8));
      header = 16;
    } else if (size === 0) {
      size = end - offset;
    }
    if (size < header || offset + size > end) return;
    yield { type, start: offset + header, end: offset + size, boxStart: offset };
    offset += size;
  }
}

export function child(view: DataView, parent: Box, type: string): Box | undefined {
  for (const box of boxes(view, parent.start, parent.end)) {
    if (box.type === type) return box;
  }
  return undefined;
}

export function path(view: DataView, parent: Box, ...types: string[]): Box | undefined {
  let box: Box | undefined = parent;
  for (const type of types) {
    box = box && child(view, box, type);
  }
  return box;
}

/** init(moov)의 영상 트랙 */
export interface VideoTrackInfo {
  trackId: number;
  timescale: number;
}

/** init(moov)에서 영상 트랙(hdlr 'vide') 하나. 없거나 모양을 모르면 null */
export function readVideoTrack(init: Uint8Array): VideoTrackInfo | null {
  const view = viewOf(init);
  try {
    const moov = topBox(view, 'moov');
    if (!moov) return null;
    for (const trak of boxes(view, moov.start, moov.end)) {
      if (trak.type !== 'trak') continue;
      const header = trackHeader(view, trak);
      if (header?.handler !== 'vide') continue;
      return header.timescale > 0 ? { trackId: header.trackId, timescale: header.timescale } : null;
    }
    return null;
  } catch {
    return null; // 상자 안 칸이 바이트 밖이면 RangeError 다
  }
}

/** 조각 안 한 트랙의 시작 */
export interface FragmentTrackStart {
  /** tfdt 의 baseMediaDecodeTime(트랙 timescale 눈금) */
  baseDecodeTime: number;
  /** 첫 표본(표본이 있는 첫 trun)의 composition time offset(눈금). 칸이나 표본이 없으면 0 */
  firstCtoTicks: number;
}

/**
 * 조각(moof)에서 그 트랙의 첫 traf — tfdt(v0/v1) 와 첫 표본 cto(표본이 있는 첫 trun 의 0x800,
 * v0 무부호 · v1 부호)
 */
export function readTrackStart(fragment: Uint8Array, trackId: number): FragmentTrackStart | null {
  const view = viewOf(fragment);
  try {
    const traf = firstTraf(view, trackId);
    if (!traf) return null;
    const tfdt = child(view, traf, 'tfdt');
    const baseDecodeTime = tfdt ? decodeTime(view, tfdt) : null;
    if (baseDecodeTime === null) return null;
    const firstCtoTicks = firstCto(view, traf);
    return firstCtoTicks === null ? null : { baseDecodeTime, firstCtoTicks };
  } catch {
    return null;
  }
}

/** 파일 안 한 트랙이 차지하는 구간 */
export interface TrackSpan {
  trackId: number;
  /** hdlr 의 handler_type('vide' · 'soun' 등) */
  handler: string;
  timescale: number;
  /** 그 트랙 첫 traf 의 tfdt(눈금) */
  startTicks: number;
  /** 그 트랙 모든 표본 길이의 합(눈금) */
  durationTicks: number;
}

/**
 * 트랙별 첫 tfdt · 표본 길이 합(편집기 W8 — trun 표본 길이, 없으면 tfhd · trex 기본값).
 * moov 의 트랙 순서대로 주고, 조각에 한 번도 나오지 않은 트랙은 뺀다. 모양을 모르면 빈 목록이다.
 */
export function readTrackSpans(file: Uint8Array): TrackSpan[] {
  const view = viewOf(file);
  try {
    return trackSpans(view);
  } catch {
    return [];
  }
}

function trackSpans(view: DataView): TrackSpan[] {
  const moov = topBox(view, 'moov');
  if (!moov) return [];
  const trexDurations = defaultDurations(view, moov);
  const spans: TrackSpan[] = [];
  for (const trak of boxes(view, moov.start, moov.end)) {
    const header = trak.type === 'trak' ? trackHeader(view, trak) : null;
    const first = header && firstTraf(view, header.trackId);
    if (!header || !first) continue; // 조각에 나오지 않은 트랙
    const tfdt = child(view, first, 'tfdt');
    const startTicks = tfdt ? decodeTime(view, tfdt) : null;
    if (startTicks === null) return []; // 첫 traf 에 쓸 수 있는 tfdt 가 없으면 트랙 자리를 모른다
    const trexDuration = trexDurations.get(header.trackId) ?? 0;
    spans.push({
      ...header,
      startTicks,
      durationTicks: trackDuration(view, header.trackId, trexDuration),
    });
  }
  return spans;
}

/** 그 트랙 traf 전부의 표본 길이 합. 표본 길이 칸이 없으면 tfhd 기본값, 그것도 없으면 trex 기본값 */
function trackDuration(view: DataView, trackId: number, trexDuration: number): number {
  let total = 0;
  for (const moof of boxes(view, 0, view.byteLength)) {
    if (moof.type !== 'moof') continue;
    for (const traf of boxes(view, moof.start, moof.end)) {
      if (traf.type !== 'traf') continue;
      const fragment = fragmentHeader(view, traf);
      if (fragment.trackId !== trackId) continue;
      total += trafDuration(view, traf, fragment.defaultDuration ?? trexDuration);
    }
  }
  return total;
}

interface TrackHeader {
  trackId: number;
  handler: string;
  timescale: number;
}

/**
 * trak → tkhd 의 track_ID · hdlr 의 handler_type · mdhd 의 timescale. 상자가 빠졌으면 null,
 * 상자가 그 칸까지 닿지 않거나 tkhd · mdhd 버전이 0 · 1 이 아니면 RangeError(판독기가 null · 빈 목록으로 바꾼다)
 */
function trackHeader(view: DataView, trak: Box): TrackHeader | null {
  const tkhd = child(view, trak, 'tkhd');
  const mdhd = path(view, trak, 'mdia', 'mdhd');
  const hdlr = path(view, trak, 'mdia', 'hdlr');
  if (!tkhd || !mdhd || !hdlr) return null;
  if (!within(tkhd, tkhd.start + 4) || !within(mdhd, mdhd.start + 4)) {
    throw new RangeError('tkhd · mdhd');
  }
  const tkhdVersion = view.getUint8(tkhd.start);
  const mdhdVersion = view.getUint8(mdhd.start);
  if (tkhdVersion > 1 || mdhdVersion > 1) {
    throw new RangeError('tkhd · mdhd version');
  }
  // tkhd · mdhd 버전 1 은 생성 · 수정 시각이 64비트라 뒤 칸이 8바이트 밀린다
  const trackIdAt = tkhd.start + (tkhdVersion === 1 ? 20 : 12);
  const timescaleAt = mdhd.start + (mdhdVersion === 1 ? 20 : 12);
  if (
    !within(tkhd, trackIdAt + 4) ||
    !within(mdhd, timescaleAt + 4) ||
    !within(hdlr, hdlr.start + 12)
  ) {
    throw new RangeError('tkhd · mdhd · hdlr');
  }
  const trackId = view.getUint32(trackIdAt);
  const timescale = view.getUint32(timescaleAt);
  const handler = String.fromCharCode(
    view.getUint8(hdlr.start + 8),
    view.getUint8(hdlr.start + 9),
    view.getUint8(hdlr.start + 10),
    view.getUint8(hdlr.start + 11),
  );
  return { trackId, handler, timescale };
}

/** mvex 의 trex → 트랙별 default_sample_duration */
function defaultDurations(view: DataView, moov: Box): Map<number, number> {
  const durations = new Map<number, number>();
  const mvex = child(view, moov, 'mvex');
  if (!mvex) return durations;
  for (const trex of boxes(view, mvex.start, mvex.end)) {
    if (trex.type !== 'trex') continue;
    if (!within(trex, trex.start + 16)) {
      throw new RangeError('trex');
    }
    durations.set(view.getUint32(trex.start + 4), view.getUint32(trex.start + 12));
  }
  return durations;
}

/** 맨 위 상자 가운데 처음 나오는 그 종류 */
function topBox(view: DataView, type: string): Box | undefined {
  for (const box of boxes(view, 0, view.byteLength)) {
    if (box.type === type) return box;
  }
  return undefined;
}

/** 맨 위 moof 들을 차례로 훑어 그 트랙의 첫 traf */
function firstTraf(view: DataView, trackId: number): Box | undefined {
  for (const moof of boxes(view, 0, view.byteLength)) {
    if (moof.type !== 'moof') continue;
    for (const traf of boxes(view, moof.start, moof.end)) {
      if (traf.type === 'traf' && fragmentHeader(view, traf).trackId === trackId) return traf;
    }
  }
  return undefined;
}

interface FragmentHeader {
  trackId: number;
  /** tfhd 0x08 default_sample_duration. 칸이 없으면 undefined */
  defaultDuration?: number;
}

/**
 * traf 의 tfhd. tfhd 가 없는 traf 는 어느 트랙인지 모르므로 트랙 0(없는 번호)으로 둔다.
 * 상자가 읽을 칸까지 닿지 않으면 RangeError
 */
function fragmentHeader(view: DataView, traf: Box): FragmentHeader {
  const tfhd = child(view, traf, 'tfhd');
  if (!tfhd) return { trackId: 0 };
  if (!within(tfhd, tfhd.start + 8)) {
    throw new RangeError('tfhd');
  }
  const flags = view.getUint32(tfhd.start) & 0xffffff;
  const trackId = view.getUint32(tfhd.start + 4);
  if (!(flags & 0x08)) return { trackId };
  // 0x08 앞의 칸: 0x01 base-data-offset(8) · 0x02 sample-description-index(4)
  const at = tfhd.start + 8 + (flags & 0x01 ? 8 : 0) + (flags & 0x02 ? 4 : 0);
  if (!within(tfhd, at + 4)) {
    throw new RangeError('tfhd');
  }
  return { trackId, defaultDuration: view.getUint32(at) };
}

/**
 * tfdt → baseMediaDecodeTime. v1 은 64비트라 안전 정수(2^53 − 1)를 넘으면 판독 실패로 null.
 * 버전이 0 · 1 이 아니면 칸 자리를 모르므로 null
 */
function decodeTime(view: DataView, tfdt: Box): number | null {
  if (!within(tfdt, tfdt.start + 4)) return null;
  const version = view.getUint8(tfdt.start);
  if (version > 1) return null;
  if (!within(tfdt, tfdt.start + (version === 1 ? 12 : 8))) return null;
  if (version !== 1) return view.getUint32(tfdt.start + 4);
  const value = view.getBigUint64(tfdt.start + 4);
  return value <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(value) : null;
}

interface TrunLayout {
  version: number;
  flags: number;
  count: number;
  /** 첫 표본 칸의 첫 바이트 */
  samplesAt: number;
}

/**
 * trun 머리: 표본 수 뒤의 0x001 data_offset · 0x004 first_sample_flags 를 건너뛴 자리까지.
 * 머리가 상자 끝을 넘으면 RangeError
 */
function trunLayout(view: DataView, trun: Box): TrunLayout {
  if (!within(trun, trun.start + 8)) {
    throw new RangeError('trun');
  }
  const word = view.getUint32(trun.start);
  const flags = word & 0xffffff;
  const samplesAt = trun.start + 8 + (flags & 0x001 ? 4 : 0) + (flags & 0x004 ? 4 : 0);
  if (!within(trun, samplesAt)) {
    throw new RangeError('trun');
  }
  return { version: word >>> 24, flags, count: view.getUint32(trun.start + 4), samplesAt };
}

/**
 * traf 의 첫 표본 cto: 표본이 없는 trun 은 건너뛰고, 표본이 있는 첫 trun 에서
 * 0x100 길이 · 0x200 크기 · 0x400 표지 칸을 건너뛴 0x800. 칸이 없거나 표본이 하나도 없으면 0
 */
function firstCto(view: DataView, traf: Box): number | null {
  for (const trun of boxes(view, traf.start, traf.end)) {
    if (trun.type !== 'trun') continue;
    const { version, flags, count, samplesAt } = trunLayout(view, trun);
    if (count === 0) continue;
    if (!(flags & 0x800)) return 0;
    const at =
      samplesAt + (flags & 0x100 ? 4 : 0) + (flags & 0x200 ? 4 : 0) + (flags & 0x400 ? 4 : 0);
    if (!within(trun, at + 4)) return null;
    return version === 1 ? view.getInt32(at) : view.getUint32(at);
  }
  return 0;
}

/** traf 안 trun 전부의 표본 길이 합. 0x100 칸이 없는 trun 은 표본마다 fallback */
function trafDuration(view: DataView, traf: Box, fallback: number): number {
  let total = 0;
  for (const trun of boxes(view, traf.start, traf.end)) {
    if (trun.type !== 'trun') continue;
    const { flags, count, samplesAt } = trunLayout(view, trun);
    const stride = [0x100, 0x200, 0x400, 0x800].filter((bit) => flags & bit).length * 4;
    if (!within(trun, samplesAt + count * stride)) throw new RangeError('trun');
    if (!(flags & 0x100)) {
      total += count * fallback;
      continue;
    }
    for (let i = 0; i < count; i++) {
      total += view.getUint32(samplesAt + i * stride);
    }
  }
  return total;
}

/** 읽으려는 칸의 끝(end)이 상자 안인가: 상자 밖 바이트(다음 상자)를 제 칸으로 읽지 않게 */
function within(box: Box, end: number): boolean {
  return end <= box.end;
}

function viewOf(bytes: Uint8Array): DataView {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}
