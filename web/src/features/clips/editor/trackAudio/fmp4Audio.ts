// 녹화 조각(fMP4) 하나에서 소리 트랙을 하나씩 꺼낸다(POK-389, 트랙별 미리 듣기).
//
// 재생 서버 영상은 소리를 하나만 남겨(ADR-057) 트랙별로 들을 수 없다. 조각 파일에는 방송 소리 트랙이 전부 들어 있고, 렌더 일꾼도
// 같은 파일을 [0:a:N]으로 읽는다. 그래서 여기서도 moov의 소리 트랙을 「파일 순서대로」 번호를 매긴다(0번이 최종 믹스).
//
// 라이브러리(mp4box 등)를 안 쓴 이유: 읽을 상자가 몇 개뿐이고(moov·trak·stsd·esds·trex·moof·traf·tfhd·trun), 녹화 조각의
// 모양이 정해져 있다(ftyp → 표본 없는 moov → moof/mdat 여러 쌍). 모르는 모양이면 빈 목록을 주고 화면은 영상 소리로 돌아간다.

import {
  boxes,
  child,
  MAX_TRACK_SAMPLES,
  path,
  readFirstSampledTraf,
  readTrackHeaders,
  type Box,
  type TrackHeader,
} from '@/lib/fmp4Boxes';

/** AudioSpecificConfig에서 ADTS 머리에 필요한 셋 */
export interface AacConfig {
  /** 1~4만 ADTS로 옮길 수 있다(2 = AAC LC) */
  objectType: number;
  frequencyIndex: number;
  channelConfig: number;
}

export interface AudioTrackFrames {
  config: AacConfig;
  /** 조각 안의 AAC 프레임을 나온 순서대로 */
  frames: Uint8Array[];
}

/** ES 서술자의 길이(1~4바이트, 앞 비트가 이어짐 표시) */
function descriptorLength(view: DataView, offset: number): [length: number, next: number] {
  let length = 0;
  let cursor = offset;
  for (let i = 0; i < 4; i++) {
    const byte = view.getUint8(cursor++);
    length = (length << 7) | (byte & 0x7f);
    if ((byte & 0x80) === 0) break;
  }
  return [length, cursor];
}

/** esds → AudioSpecificConfig. 모르는 모양이면 null */
function aacConfig(view: DataView, esds: Box): AacConfig | null {
  let cursor = esds.start + 4; // version·flags
  if (view.getUint8(cursor) !== 0x03) return null; // ES_Descriptor
  [, cursor] = descriptorLength(view, cursor + 1);
  const flags = view.getUint8(cursor + 2);
  cursor += 3;
  if (flags & 0x80) cursor += 2;
  if (flags & 0x40) cursor += 1 + view.getUint8(cursor);
  if (flags & 0x20) cursor += 2;
  if (view.getUint8(cursor) !== 0x04) return null; // DecoderConfigDescriptor
  [, cursor] = descriptorLength(view, cursor + 1);
  cursor += 13;
  if (view.getUint8(cursor) !== 0x05) return null; // DecoderSpecificInfo
  [, cursor] = descriptorLength(view, cursor + 1);
  const bits = (view.getUint8(cursor) << 8) | view.getUint8(cursor + 1);
  const objectType = bits >> 11;
  const frequencyIndex = (bits >> 7) & 0x0f;
  const channelConfig = (bits >> 3) & 0x0f;
  // ADTS 머리는 objectType을 2비트(1~4)로, 빈도를 표 번호로만 적는다. 그 밖(HE-AAC 확장·직접 적은 빈도)은 못 옮긴다.
  if (
    objectType < 1 ||
    objectType > 4 ||
    frequencyIndex > 12 ||
    channelConfig < 1 ||
    channelConfig > 7
  ) {
    return null;
  }
  return { objectType, frequencyIndex, channelConfig };
}

/** stsd 안 mp4a 표본 항목 → esds. 버전 0·1(QuickTime) 둘 다 */
function mp4aConfig(view: DataView, stsd: Box): AacConfig | null {
  for (const entry of boxes(view, stsd.start + 8, stsd.end)) {
    if (entry.type !== 'mp4a') continue;
    const version = view.getUint16(entry.start + 8);
    const childStart = entry.start + 28 + (version === 1 ? 16 : version === 2 ? 36 : 0);
    for (const box of boxes(view, childStart, entry.end)) {
      if (box.type === 'esds') return aacConfig(view, box);
    }
  }
  return null;
}

/** 꺼내는 중인 소리 트랙 하나: 트랙 머리 · 설정 · trex 기본 표본 크기와 지금까지 모은 프레임 */
interface TrackInfo {
  header: TrackHeader;
  config: AacConfig;
  defaultSize: number;
  frames: Uint8Array[];
}

/** 조각 안 한 트랙의 시작 시각(초). 트랙마다 자기 timescale 로 먼저 초로 바꾼 파일 안 값이다 */
export interface TrackTiming {
  /**
   * 표본 있는 첫 traf 의 tfdt = 트랙 첫 DTS. 표본 없는 traf 는 건너뛴다
   * (media `internal/playback/timing.go` 의 readTiming 과 같은 규칙)
   */
  firstDts: number;
  /** 표본 있는 첫 traf 표본들의 PTS 최솟값(open GOP 면 첫 표본의 PTS 보다 이르다) */
  firstPts: number;
}

/** 조각 안 한 트랙의 timescale 과 시각. 표본이 하나도 없으면 timing 이 null 이다 */
export interface SegmentTrack {
  timescale: number;
  timing: TrackTiming | null;
}

export interface SegmentAudioTrack extends AudioTrackFrames, SegmentTrack {}

/**
 * ② 녹화 조각 하나의 소리 트랙(프레임 · 시각)과 영상 트랙 시각. 설계 prw2 3.3절 소리 배치의 입력이다:
 * D2 = video.timing.firstDts · 「트랙 i 첫 PTS」 = audio[i].timing.firstPts
 */
export interface SegmentTracks {
  /** 소리 트랙(파일 순서 — 렌더의 [0:a:N] 과 같은 번호) */
  audio: SegmentAudioTrack[];
  /** 파일 순서로 첫 영상 트랙. 영상 트랙이 없으면 null */
  video: SegmentTrack | null;
}

/**
 * 녹화 조각 하나 → 소리 트랙들과 트랙별 시각. 소리 트랙이 없거나 모양을 모르면 빈 결과다(던지지 않는다 — 잘린 파일 등에서 범위를
 * 넘어 읽은 RangeError 도 빈 결과로 접는다). 화면은 빈 결과를 「못 꺼냄」으로 다룬다
 */
export function demuxSegmentAudio(data: ArrayBuffer | Uint8Array): SegmentTracks {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  try {
    return segmentTracks(view, bytes);
  } catch {
    return { audio: [], video: null };
  }
}

/** 상자 판독은 fmp4Boxes 가 한다(상자 끝 · 버전 검사). 판독이 실패하면 RangeError 로 빈 결과가 된다 */
function segmentTracks(view: DataView, bytes: Uint8Array): SegmentTracks {
  const headers = readTrackHeaders(bytes);
  if (!headers) throw new RangeError('trak');
  const audio = demux(view, bytes, headers).map(({ header, audio: track }) => ({
    ...track,
    ...timedTrack(bytes, header),
  }));
  const video = headers.find((h) => h.handler === 'vide');
  return { audio, video: video ? timedTrack(bytes, video) : null };
}

/** 트랙 하나의 timescale 과 초 시각. ② 파일은 moov 와 moof 가 한 바이트라 init 과 조각으로 같은 것을 넘긴다 */
function timedTrack(bytes: Uint8Array, header: TrackHeader): SegmentTrack {
  const { trackId, timescale } = header;
  const samples = readFirstSampledTraf(bytes, bytes, trackId);
  if (!samples || timescale === 0) throw new RangeError('traf');
  const first = samples[0];
  if (!first) return { timescale, timing: null };
  // 트랙마다 자기 timescale 로 먼저 초로 바꾼다: 트랙끼리 비교는 초에서만 한다
  const minPts = samples.reduce((min, sample) => Math.min(min, sample.pts), first.pts);
  return { timescale, timing: { firstDts: first.dts / timescale, firstPts: minPts / timescale } };
}

/** 소리 트랙(파일 순서)과 그 트랙 머리 */
interface DemuxedTrack {
  header: TrackHeader;
  audio: AudioTrackFrames;
}

/**
 * 조각 하나에서 꺼내는 소리 프레임 수의 상한 — 트랙 합. 프레임마다 복사본을 하나씩 만들므로 1바이트 프레임 수백만 개면 1MB
 * 조각이 224MiB 가 된다(보안 검수 prw2 c9′ M-1) — 복사 바이트 상한만으로는 못 막는다. 값은 판독 한 번의 표본 상한
 * MAX_TRACK_SAMPLES(fmp4Boxes)를 그대로 쓴다: 정상 ② 조각(4초 · 소리 최대 6트랙)은 48kHz 면 트랙마다 4 × 48,000 / 1,024
 * ≈ 188 프레임 · 합 약 1,130 이고, 96kHz 여도 합 약 2,250 이라 20배 넘게 남는다
 */
const MAX_SEGMENT_FRAMES = MAX_TRACK_SAMPLES;

/** 판독 한 번에 남은 몫: 프레임 수(트랙 합)와 복사할 수 있는 바이트 */
interface Room {
  frames: number;
  bytes: number;
}

function demux(view: DataView, bytes: Uint8Array, headers: readonly TrackHeader[]): DemuxedTrack[] {
  const top = [...boxes(view, 0, bytes.byteLength)];
  const moov = top.find((box) => box.type === 'moov');
  if (!moov) return [];
  const tracks = audioTracks(view, bytes, moov, headers);
  if (!tracks) return [];

  // 정상 조각은 프레임마다 mdat 의 다른 바이트라 복사 합이 입력 크기를 넘지 않는다
  let room: Room = { frames: MAX_SEGMENT_FRAMES, bytes: bytes.byteLength };
  for (const moof of top) {
    if (moof.type !== 'moof') continue;
    for (const traf of boxes(view, moof.start, moof.end)) {
      if (traf.type === 'traf') room = readTraf(view, bytes, moof, traf, tracks, room);
    }
  }
  return [...tracks.values()].map(({ header, config, frames }) => ({
    header,
    audio: { config, frames },
  }));
}

/**
 * moov 의 소리 trak 을 파일 순서대로(Map 은 넣은 순서를 지킨다), track_ID 로 찾게. 못 읽는 소리 트랙이 있으면 null.
 * 소리 trak 마다 트랙 머리(readTrackHeaders — 트랙 수 상한 · 번호 겹침을 거른 목록)의 짝을 traf 를 읽기 전에 확인한다:
 * mdhd 가 빠진 trak 은 그 목록에 없어 상한에 세지 않으므로, 짝 없이 받으면 trak 수만큼 판독이 부푼다(보안 검수 prw2 c9′
 * M-2). 짝이 없거나 번호가 겹치면 RangeError
 */
function audioTracks(
  view: DataView,
  bytes: Uint8Array,
  moov: Box,
  headers: readonly TrackHeader[],
): Map<number, TrackInfo> | null {
  const defaults = new Map<number, number>();
  const mvex = child(view, moov, 'mvex');
  if (mvex) {
    for (const trex of boxes(view, mvex.start, mvex.end)) {
      if (trex.type === 'trex')
        defaults.set(view.getUint32(trex.start + 4), view.getUint32(trex.start + 16));
    }
  }

  const headerOf = new Map(headers.map((header) => [header.trackId, header]));
  const tracks = new Map<number, TrackInfo>();
  for (const trak of boxes(view, moov.start, moov.end)) {
    if (trak.type !== 'trak') continue;
    const tkhd = child(view, trak, 'tkhd');
    const hdlr = path(view, trak, 'mdia', 'hdlr');
    const stsd = path(view, trak, 'mdia', 'minf', 'stbl', 'stsd');
    if (!tkhd || !hdlr || !stsd) continue;
    const handler = String.fromCharCode(...bytes.subarray(hdlr.start + 8, hdlr.start + 12));
    if (handler !== 'soun') continue;
    const config = mp4aConfig(view, stsd);
    // 소리인데 못 읽는 트랙이 하나라도 있으면 번호가 밀린다: 트랙 번호가 렌더와 어긋나느니 통째로 포기한다.
    if (!config) return null;
    const id = view.getUint32(tkhd.start + (view.getUint8(tkhd.start) === 1 ? 20 : 12));
    const header = headerOf.get(id);
    if (!header || tracks.has(id)) throw new RangeError('trak');
    tracks.set(id, { header, config, defaultSize: defaults.get(id) ?? 0, frames: [] });
  }
  return tracks;
}

/**
 * traf 하나의 프레임을 그 트랙에 덧붙이고 남은 몫을 준다(영상 트랙이면 그대로). 프레임 수가 몫을 넘으면 프레임을 만들기 전에,
 * 복사 바이트가 몫을 넘으면(trun 이 data_offset 으로 같은 바이트를 거듭 가리킴 — codex prw2 후속 경화 M) RangeError
 */
function readTraf(
  view: DataView,
  bytes: Uint8Array,
  moof: Box,
  traf: Box,
  tracks: ReadonlyMap<number, TrackInfo>,
  room: Room,
): Room {
  const tfhd = child(view, traf, 'tfhd');
  const track = tfhd && tracks.get(view.getUint32(tfhd.start + 4));
  if (!tfhd || !track) return room; // 영상 트랙
  const { base, defaultSize } = trafDefaults(view, moof, tfhd, track.defaultSize);

  let { frames: framesLeft, bytes: bytesLeft } = room;
  let dataOffset = base;
  for (const trun of boxes(view, traf.start, traf.end)) {
    if (trun.type !== 'trun') continue;
    const flags = view.getUint32(trun.start) & 0xffffff;
    const count = view.getUint32(trun.start + 4);
    if (count > framesLeft) throw new RangeError('trun frame count');
    framesLeft -= count;
    let at = trun.start + 8;
    if (flags & 0x01) {
      dataOffset = base + view.getInt32(at);
      at += 4;
    }
    if (flags & 0x04) at += 4;
    for (let i = 0; i < count; i++) {
      if (flags & 0x100) at += 4;
      let size = defaultSize;
      if (flags & 0x200) {
        size = view.getUint32(at);
        at += 4;
      }
      if (flags & 0x400) at += 4;
      if (flags & 0x800) at += 4;
      if (size <= 0 || dataOffset + size > bytes.byteLength) throw new RangeError('trun');
      if (size > bytesLeft) throw new RangeError('trun data_offset');
      bytesLeft -= size;
      // 복사한다: 보기(subarray)면 영상까지 든 조각 버퍼 전체가 디코드가 끝날 때까지 남는다(3분 구간이면 약 170MB)
      track.frames.push(bytes.slice(dataOffset, dataOffset + size));
      dataOffset += size;
    }
  }
  return { frames: framesLeft, bytes: bytesLeft };
}

/** tfhd → 프레임 자리의 기준(base-data-offset, 없으면 moof 머리 — 녹화 조각은 default-base-is-moof)과 기본 표본 크기 */
function trafDefaults(
  view: DataView,
  moof: Box,
  tfhd: Box,
  trexSize: number,
): { base: number; defaultSize: number } {
  const flags = view.getUint32(tfhd.start) & 0xffffff;
  let cursor = tfhd.start + 8;
  let base = moof.boxStart;
  if (flags & 0x01) {
    base = Number(view.getBigUint64(cursor));
    cursor += 8;
  }
  if (flags & 0x02) cursor += 4;
  if (flags & 0x08) cursor += 4;
  return { base, defaultSize: flags & 0x10 ? view.getUint32(cursor) : trexSize };
}

/** 프레임마다 ADTS 머리(보호 없음, 7바이트)를 붙여 이은다: decodeAudioData가 그대로 받는다 */
export function toAdts(track: AudioTrackFrames): Uint8Array {
  const { objectType, frequencyIndex, channelConfig } = track.config;
  const total = track.frames.reduce((sum, frame) => sum + frame.length + 7, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const frame of track.frames) {
    const length = frame.length + 7;
    out[offset] = 0xff;
    out[offset + 1] = 0xf1; // MPEG-4, 층 0, 보호 없음
    out[offset + 2] = ((objectType - 1) << 6) | (frequencyIndex << 2) | (channelConfig >> 2);
    out[offset + 3] = ((channelConfig & 0x03) << 6) | (length >> 11);
    out[offset + 4] = (length >> 3) & 0xff;
    out[offset + 5] = ((length & 0x07) << 5) | 0x1f;
    out[offset + 6] = 0xfc;
    out.set(frame, offset + 7);
    offset += length;
  }
  return out;
}
