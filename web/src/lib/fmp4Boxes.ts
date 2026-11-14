// fMP4(조각 MP4) 상자를 바이트에서 바로 읽는다. 편집기 소리(trackAudio/fmp4Audio.ts)와 플레이어가 같은 상자 해석을 쓴다.
//
// 읽는 함수는 던지지 않는다: 잘린 바이트 · 모르는 모양이면 null 이나 빈 목록을 준다(fmp4Audio.ts 의 demuxSegmentAudio 관례).
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

/**
 * init(moov)에서 영상 트랙(hdlr 'vide') 하나. 없거나 모양을 모르거나 · timescale 이 0 이거나 · track_ID 가 0 이면 null
 * (0 은 쓰지 않는 번호다 — tfhd 없는 traf 를 트랙 0 으로 보는 fragmentHeader 와 짝지어지지 않게, readTrackHeaders 와 같다)
 */
export function readVideoTrack(init: Uint8Array): VideoTrackInfo | null {
  const view = viewOf(init);
  try {
    const moov = topBox(view, 'moov');
    if (!moov) return null;
    for (const trak of boxes(view, moov.start, moov.end)) {
      if (trak.type !== 'trak') continue;
      const header = trackHeader(view, trak);
      if (header?.handler !== 'vide') continue;
      const { trackId, timescale } = header;
      return trackId > 0 && timescale > 0 ? { trackId, timescale } : null;
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

/** 표본 하나의 시각(트랙 timescale 눈금) */
export interface SampleTiming {
  /** 그 traf 의 tfdt + 앞 표본 길이 합 */
  dts: number;
  /** dts + cto(trun v1 은 부호 있음) */
  pts: number;
  duration: number;
}

/**
 * 조각 바이트 안 그 트랙의 표본 전부를 디코드 순서로(편집기 창의 프레임 표). init 은 trex 기본 길이만 읽는다 —
 * ② 녹화 파일처럼 moov 와 moof 가 한 바이트면 같은 것을 두 번 넘긴다. 그 트랙이 없으면 빈 목록, 모양을 모르거나 표본이
 * MAX_TRACK_SAMPLES 를 넘으면 null
 */
export function readTrackSamples(
  init: Uint8Array,
  fragment: Uint8Array,
  trackId: number,
): SampleTiming[] | null {
  try {
    return [...trafSampleLists(viewOf(init), viewOf(fragment), trackId, MAX_TRACK_SAMPLES)].flat();
  } catch {
    return null;
  }
}

/**
 * 그 트랙의 **표본 있는** 첫 traf 의 표본(빈 traf 는 건너뜀 — 렌더 readTiming 과 같은 규칙). 그 traf 에서 멈춰 뒤
 * traf 는 만들지도 보지도 않는다. 표본 있는 traf 가 없으면 빈 목록, 그때까지 모양을 모르거나 표본이 maxSamples 를
 * 넘으면 null
 * @param maxSamples 표본 수 상한 — 트랙 여럿을 읽는 쪽이 판독 한 번의 남은 몫을 넘긴다(fileHeadProbe)
 */
export function readFirstSampledTraf(
  init: Uint8Array,
  fragment: Uint8Array,
  trackId: number,
  maxSamples: number = MAX_TRACK_SAMPLES,
): SampleTiming[] | null {
  try {
    for (const samples of trafSampleLists(viewOf(init), viewOf(fragment), trackId, maxSamples)) {
      if (samples.length > 0) return samples;
    }
    return [];
  } catch {
    return null;
  }
}

/**
 * 한 판독에서 한 트랙의 표본 수 상한. 표본별 칸이 없는 trun(flags 0x100 · 0x200 · 0x400 · 0x800 모두 꺼짐)은 표본
 * 수 칸만으로 바이트 검사를 통과하므로(stride 0), 이 상한이 없으면 수십 바이트 조각 하나가 표본 객체 40억 개를 만든다
 * (보안 검수 prw2 E3 M1). 값 = 편집기 창 상한 200초(editorSourceTimeline MAX_WINDOW_SECONDS) × 최고 프레임률 240.
 * 판독은 조각 하나(약 4초)씩이라 정상 조각은 이 값에 한참 못 미친다(영상 60fps 4초 = 240 · 소리 AAC 48kHz 4초 ≈ 188)
 */
export const MAX_TRACK_SAMPLES = 48_000;

/**
 * 그 트랙 traf 마다의 표본 목록을 moof · traf 순서로 하나씩 내준다(받는 쪽이 멈추면 뒤 traf 는 읽지 않는다). 내준 표본이
 * 모두 합쳐 maxSamples 를 넘거나, traf 의 크기 칸이 moof 를 넘으면 RangeError
 */
function* trafSampleLists(
  initView: DataView,
  view: DataView,
  trackId: number,
  maxSamples: number,
): Generator<SampleTiming[]> {
  const moov = topBox(initView, 'moov');
  const trexDuration = (moov && defaultDurations(initView, moov).get(trackId)) ?? 0;
  let room = maxSamples;
  for (const moof of boxes(view, 0, view.byteLength)) {
    if (moof.type !== 'moof') continue;
    for (const traf of childBoxes(view, moof)) {
      if (traf.type !== 'traf') continue;
      const header = fragmentHeader(view, traf);
      if (header.trackId !== trackId) continue;
      const tfdt = child(view, traf, 'tfdt');
      const base = tfdt ? decodeTime(view, tfdt) : null;
      const samples = trafSamples(view, traf, base, header.defaultDuration ?? trexDuration, room);
      room -= samples.length;
      yield samples;
    }
  }
}

/**
 * traf 안 trun 전부의 표본. 표본이 있는데 tfdt 를 못 읽었으면(base null) 자리를 모르므로 RangeError, 표본이 room 개를
 * 넘으면 객체를 만들기 전에 RangeError, trun 의 크기 칸이 traf 를 넘으면 RangeError(childBoxes)
 */
function trafSamples(
  view: DataView,
  traf: Box,
  base: number | null,
  fallback: number,
  room: number,
): SampleTiming[] {
  const samples: SampleTiming[] = [];
  let dts = base ?? 0;
  for (const trun of childBoxes(view, traf)) {
    if (trun.type !== 'trun') continue;
    const { version, flags, count, samplesAt } = trunLayout(view, trun);
    const stride = [0x100, 0x200, 0x400, 0x800].filter((bit) => flags & bit).length * 4;
    if (!within(trun, samplesAt + count * stride)) throw new RangeError('trun');
    if (count > 0 && base === null) throw new RangeError('tfdt');
    if (samples.length + count > room) throw new RangeError('trun sample count');
    // cto 는 표본 칸의 마지막(0x100 길이 · 0x200 크기 · 0x400 표지 뒤)
    const ctoAt = stride - 4;
    for (let i = 0; i < count; i++) {
      const at = samplesAt + i * stride;
      const duration = flags & 0x100 ? view.getUint32(at) : fallback;
      let cto = 0;
      if (flags & 0x800)
        cto = version === 1 ? view.getInt32(at + ctoAt) : view.getUint32(at + ctoAt);
      samples.push({ dts, pts: dts + cto, duration });
      dts += duration;
    }
  }
  return samples;
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

/** trak 의 머리 칸 셋 */
export interface TrackHeader {
  /** tkhd 의 track_ID */
  trackId: number;
  /** hdlr 의 handler_type('vide' · 'soun' 등) */
  handler: string;
  /** mdhd 의 timescale. 0 이어도 그대로 준다 — 나누는 쪽이 거른다 */
  timescale: number;
}

/**
 * init 하나의 트랙 수 상한. 트랙 머리를 읽는 쪽(fileHeadProbe · fmp4Audio)은 트랙마다 표본 있는 첫 traf 를 찾아 훑으므로
 * 트랙 수가 곧 판독 비용의 배수다 — 64KB 에 trak 수백 개면 판독 한 번이 초 단위로 멈춘다(보안 검수 prw2 경화 M-1).
 * 값 = ② 녹화 조각의 트랙(영상 1 + 소리 최대 6 — OBS 녹화 소리 트랙 6 · 실물 segment_1v6a)의 두 배를 넘는 여유
 */
export const MAX_TRACKS = 16;

/**
 * init(moov)의 트랙 머리를 moov 의 트랙 순서대로. ② 녹화 파일처럼 moof 가 같은 바이트에 있어도 moov 만 본다.
 * tkhd · mdhd · hdlr 가 빠진 trak 은 뺀다(readTrackSpans 와 같다). moov 가 없으면 빈 목록, 상자가 칸까지
 * 닿지 않거나 모르는 버전이거나 · track_ID 가 0 이거나 겹치거나(ISO/IEC 14496-12 — 0 은 쓰지 않고 겹치지 않는다. 0 을
 * 받으면 tfhd 없는 traf 를 트랙 0 으로 보는 fragmentHeader 와 짝지어진다 — 보안 검수 prw2 hard3 L-3) · 트랙이
 * MAX_TRACKS 를 넘으면 null
 */
export function readTrackHeaders(init: Uint8Array): TrackHeader[] | null {
  const view = viewOf(init);
  try {
    const moov = topBox(view, 'moov');
    if (!moov) return [];
    const headers: TrackHeader[] = [];
    const ids = new Set<number>();
    for (const trak of boxes(view, moov.start, moov.end)) {
      const header = trak.type === 'trak' ? trackHeader(view, trak) : null;
      if (!header) continue;
      if (header.trackId === 0 || ids.has(header.trackId) || headers.length === MAX_TRACKS) {
        return null;
      }
      ids.add(header.trackId);
      headers.push(header);
    }
    return headers;
  } catch {
    return null;
  }
}

/**
 * trak → tkhd 의 track_ID · hdlr 의 handler_type · mdhd 의 timescale. 상자가 빠졌으면 null,
 * 상자가 그 칸까지 닿지 않거나 tkhd · mdhd 버전이 0 · 1, hdlr 버전이 0 이 아니면 RangeError
 * (판독기가 null · 빈 목록으로 바꾼다)
 */
function trackHeader(view: DataView, trak: Box): TrackHeader | null {
  const tkhd = child(view, trak, 'tkhd');
  const mdhd = path(view, trak, 'mdia', 'mdhd');
  const hdlr = path(view, trak, 'mdia', 'hdlr');
  if (!tkhd || !mdhd || !hdlr) return null;
  const tkhdVersion = knownVersion(view, tkhd, 1);
  const mdhdVersion = knownVersion(view, mdhd, 1);
  knownVersion(view, hdlr, 0);
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

/** mvex 의 trex → 트랙별 default_sample_duration. trex 버전이 0 이 아니면 RangeError */
function defaultDurations(view: DataView, moov: Box): Map<number, number> {
  const durations = new Map<number, number>();
  const mvex = child(view, moov, 'mvex');
  if (!mvex) return durations;
  for (const trex of boxes(view, mvex.start, mvex.end)) {
    if (trex.type !== 'trex') continue;
    knownVersion(view, trex, 0);
    if (!within(trex, trex.start + 16)) {
      throw new RangeError('trex');
    }
    durations.set(view.getUint32(trex.start + 4), view.getUint32(trex.start + 12));
  }
  return durations;
}

/**
 * 부모 상자 안의 상자를 boxes 처럼 내주되, 크기 칸이 부모를 넘거나 머리보다 작아 부모 끝 전에 멈추면 RangeError. boxes 는
 * 맨 위에서는 잘림을 정상으로 본다(파일 머리만 받은 바이트). 하지만 부모 안에서 조용히 멈추면 뒤 표본이 빠진 채 판독이
 * 성공한다(codex prw2 E3 — trun 크기 칸 하나가 틀려 표본 120 중 90 만 냄). 끝에 남은 8바이트 미만은 상자 머리가 못 되어 둔다
 */
function* childBoxes(view: DataView, parent: Box): Generator<Box> {
  let end = parent.start;
  for (const box of boxes(view, parent.start, parent.end)) {
    end = box.end;
    yield box;
  }
  if (parent.end - end >= 8) throw new RangeError(`${parent.type} child`);
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
 * 상자가 읽을 칸까지 닿지 않거나 tfhd 버전이 0 이 아니면 RangeError
 */
function fragmentHeader(view: DataView, traf: Box): FragmentHeader {
  const tfhd = child(view, traf, 'tfhd');
  if (!tfhd) return { trackId: 0 };
  knownVersion(view, tfhd, 0);
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
 * 머리가 상자 끝을 넘거나 버전이 0 · 1 이 아니면 RangeError(버전 2 이상의 cto 부호를 모른다)
 */
function trunLayout(view: DataView, trun: Box): TrunLayout {
  const version = knownVersion(view, trun, 1);
  if (!within(trun, trun.start + 8)) {
    throw new RangeError('trun');
  }
  const flags = view.getUint32(trun.start) & 0xffffff;
  const samplesAt = trun.start + 8 + (flags & 0x001 ? 4 : 0) + (flags & 0x004 ? 4 : 0);
  if (!within(trun, samplesAt)) {
    throw new RangeError('trun');
  }
  return { version, flags, count: view.getUint32(trun.start + 4), samplesAt };
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

/**
 * FullBox 머리의 버전. 머리가 상자 끝을 넘거나 규격(ISO/IEC 14496-12)에 없는 버전(maxVersion 초과)이면
 * 뒤 칸 자리를 모르므로 RangeError
 */
function knownVersion(view: DataView, box: Box, maxVersion: number): number {
  if (!within(box, box.start + 4)) {
    throw new RangeError(box.type);
  }
  const version = view.getUint8(box.start);
  if (version > maxVersion) {
    throw new RangeError(`${box.type} version`);
  }
  return version;
}

/** 읽으려는 칸의 끝(end)이 상자 안인가: 상자 밖 바이트(다음 상자)를 제 칸으로 읽지 않게 */
function within(box: Box, end: number): boolean {
  return end <= box.end;
}

function viewOf(bytes: Uint8Array): DataView {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}
