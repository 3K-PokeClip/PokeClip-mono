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
  readTrackSamples,
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

/** 꺼내는 중인 소리 트랙 하나: 트랙 머리 · 설정 · trex 기본 표본 크기 · 프레임 수 시간 상한과 지금까지 모은 프레임 */
interface TrackInfo {
  header: TrackHeader;
  config: AacConfig;
  defaultSize: number;
  maxFrames: number;
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

/** AAC 빈도 번호(samplingFrequencyIndex 0~12, ISO/IEC 14496-3) → Hz */
const SAMPLE_RATES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
];

/**
 * AAC 프레임 하나의 표본 수. HE-AAC(SBR)도 esds 의 AudioSpecificConfig 가 적은 빈도는 핵심 부호기 빈도이고 프레임 하나는 그
 * 빈도로 1,024 표본이라(출력은 두 배 빈도 · 2,048 표본), 「설정 빈도 / 1,024」 가 초당 프레임 수로 그대로 맞는다
 */
const SAMPLES_PER_FRAME = 1024;

/**
 * 프레임 수 시간 상한의 배율. 정상 조각은 프레임 수가 「시간 × 빈도 / 1,024」 와 같다(4초 · 48kHz = 187.5 — 실측 187~189).
 * 1.5 배면 약 281 이라 정상보다 반을 더 남기고, 범람(4초 조각에 1ms 간격 약 4,200 · 같은 시각 약 1.9만 — 실측
 * runs/2026-11-13-prw2-aac-flood)은 상수 여유를 더한 상한 328 의 12배를 넘어 걸린다
 */
const FRAME_MARGIN = 1.5;

/**
 * 짧은 조각용 상수 여유(초 — 트랙마다 ceil(이 초 × 설정 빈도 / 1,024) 프레임을 더한다). 녹화가 끝나는 마지막 조각처럼 영상
 * 시간이 아주 짧으면 소리 · 영상 조각 경계의 어긋남이 배율로 덮이지 않는다. 프레임 수가 아니라 초로 두는 까닭: 고정 48
 * 프레임은 48kHz 로 1초지만 7,350Hz 로는 6.7초라 4초 조각 상한이 12.7초 분량까지 벌어진다(보안 검수 prw2 hard4 M-2).
 * 4초 조각이면 48kHz 는 281 + 47 = 328 · 7,350Hz 는 43 + 8 = 51 이다
 */
const SHORT_SEGMENT_SECONDS = 1;

/**
 * 기준 초를 낼 때 소리 표본 하나가 셀 수 있는 길이(그 트랙 설정 빈도로 AAC 프레임 몇 개 몫인가). 정상 소리 표본은 프레임
 * 하나 = 1,024 표본 길이다(MediaMTX 녹화 format_fmp4.go 가 PES 안 프레임마다 SamplesPerAccessUnit 1,024 씩 더해 DTS 를
 * 매긴다). PES 경계의 시각 흔들림을 덮도록 두 배까지 센다. 소리가 끊긴 틈은 두 프레임 몫만 세지만 그 틈에는 프레임도 없어 프레임 수와 시간의 비가 그대로다
 */
const MAX_AUDIO_SAMPLE_FRAMES = 2;

/**
 * 기준 초를 낼 때 영상 표본 하나가 셀 수 있는 길이(초). 정상 영상 프레임 간격은 1/120–1/10초이고, 가변 프레임률(장면이
 * 멈추면 프레임을 드물게 보냄)도 1초 안이면 그대로 센다. 그보다 긴 멈춤은 1초로 깎이지만 늦은 시작 여유가 덮는다
 * (4초 조각에 3초 멈춤이면 영상 2초 + 여유 3초 ≥ 소리 4초)
 */
const MAX_VIDEO_SAMPLE_SECONDS = 1;

/**
 * 영상이 소리보다 늦게 시작하는 몫(초) — 기준 초의 영상 쪽 합에 더한다. 녹화기(포크 format_fmp4.go)는 영상 트랙의 첫
 * 키프레임이 오기 전 영상 표본을 버리지만(`if !randomAccess { return nil }`) 소리는 처음부터 적는다. 그래서 녹화가
 * (다시) 시작된 첫 조각은 영상이 키프레임 간격만큼 짧다 — 플러그인은 그 간격을 2초로 강제한다(obs-plugin constants.hpp
 * kForcedKeyintSec · ADR-020). 여유가 없으면 영상 2초 · 소리 4.05초 · 188 프레임인 재시작 첫 조각이 상한 187 에 걸린다.
 * 값 = 키프레임 간격 2초 + 짧은 조각 여유와 같은 1초. 범람 쪽 몫: 소리 자신의 합이 실제 시간이라 범람(길이 0 · 1ms)에는
 * 보탬이 없고, 1,024 간격으로 소리 시각만 넓힌 꼴에는 4초 조각 상한이 328 에서 539(48kHz)로 오른다
 */
const VIDEO_LATE_START_SECONDS = 3;

/** 판독 한 번에 남은 몫: 프레임 수(트랙 합)와 복사할 수 있는 바이트 */
interface Room {
  frames: number;
  bytes: number;
}

function demux(view: DataView, bytes: Uint8Array, headers: readonly TrackHeader[]): DemuxedTrack[] {
  const top = [...boxes(view, 0, bytes.byteLength)];
  const moov = top.find((box) => box.type === 'moov');
  if (!moov) return [];
  const tracks = audioTracks(view, bytes, moov, headers, frameLimit(bytes, headers));
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
 * 소리 트랙 하나가 이 조각에서 낼 수 있는 프레임 수(시간 상한 — 보안 검수 prw2 hard3 M-1 추기). 방송자는 송출만으로 4초 조각
 * 소리 trun 을 16만 표본 넘게 부풀리고, 그 프레임을 decodeAudioData 로 풀면 탭 메모리가 다 찬다. 그래서 트랙마다
 * floor(기준 초 × 설정 빈도 / 1,024 × FRAME_MARGIN) + ceil(SHORT_SEGMENT_SECONDS × 설정 빈도 / 1,024) 를 넘으면 그 조각
 * 소리 전체를 판독 실패로 접는다.
 *
 * 기준 초 = min(영상 트랙 표본 길이 합 + VIDEO_LATE_START_SECONDS, 그 소리 트랙 표본 길이 합) — 표본 길이는 하나씩
 * 잘라(소리 MAX_AUDIO_SAMPLE_FRAMES 프레임 몫 · 영상 MAX_VIDEO_SAMPLE_SECONDS) 더한다. 작은 쪽을 쓰는 까닭: 둘 다 송출 시각 도장에서 오므로, 범람을
 * 통과시키려면 두 트랙을 함께 벌려야 하게 만든다. 큰 쪽이면 한 트랙만 벌려도 되고, 소리 자신만 쓰면 소리 시각을 넓힌 꼴이,
 * 영상만 쓰면 영상 시각을 넓힌 꼴이 그대로 통과한다. 범람 표본은 길이가 0(같은 시각) · 1ms 라 소리 자신의 합도 실제
 * 시간만큼만 잡힌다.
 *
 * 표본마다 자르는 까닭(보안 검수 prw2 hard4 H-1 · cc · codex 재현): 녹화 서버 포크(v1.21.1-pokeclip.2
 * format_fmp4_track.go)는 DTS 뜀을 tfdt 가 아니라 앞 표본 길이(uint32(다음 DTS − 이 DTS) · 상한 없음)로 적고, DTS 와
 * NTP 가 5초 넘게 벌어졌는지(recorder.go ntpDriftTolerance)는 다음 차례 표본에서야 본다. 그래서 방송자가 송출만으로 두
 * 트랙 DTS 를 함께 크게 뛰우면 녹화가 다시 시작되기 직전 조각의 마지막 표본 길이가 수백 초가 되고, 드리프트 검사는 이를
 * 막지 못한다(묶음 AU 는 format_fmp4.go 가 NTP 도 같은 몫만큼 늘려 드리프트에도 걸리지 않는다). 상류 main 은 이 길이를
 * 0 으로 바꿨으나 포크에는 없다. 표본 하나가 셀 수 있는 길이를 묶으면 뜀 하나가 보탤 수 있는 시간은 소리 프레임 두 개
 * 몫 · 영상 1초뿐이다.
 *
 * 영상 트랙이 없거나 그 시간이 0 이면 기준이 없어 RangeError. 포크는 영상이 늦으면 소리만으로 조각을 열기도 하지만, 영상
 * 시각이 없는 조각은 배치(loadTrackAudio placeRuns)도 못 하므로 여기서 실패로 접어도 결과가 같다
 */
function frameLimit(
  bytes: Uint8Array,
  headers: readonly TrackHeader[],
): (header: TrackHeader, config: AacConfig) => number {
  const video = headers.find((h) => h.handler === 'vide');
  const videoSeconds = video
    ? cappedSeconds(bytes, video, video.timescale * MAX_VIDEO_SAMPLE_SECONDS)
    : 0;
  if (videoSeconds <= 0) throw new RangeError('video span');
  const videoBase = videoSeconds + VIDEO_LATE_START_SECONDS;
  return (header, config) => {
    const rate = SAMPLE_RATES[config.frequencyIndex]!;
    // 프레임 몫을 그 트랙 timescale 눈금으로: 2 × 1,024 × timescale / 설정 빈도(timescale 이 빈도와 같으면 2,048)
    const maxTicks = (MAX_AUDIO_SAMPLE_FRAMES * SAMPLES_PER_FRAME * header.timescale) / rate;
    const base = Math.min(videoBase, cappedSeconds(bytes, header, maxTicks));
    const slack = Math.ceil((SHORT_SEGMENT_SECONDS * rate) / SAMPLES_PER_FRAME);
    return Math.floor(((base * rate) / SAMPLES_PER_FRAME) * FRAME_MARGIN) + slack;
  };
}

/**
 * 트랙 표본 길이 합(초) — 표본마다 maxTicks 눈금까지만 센다. 조각에 나오지 않은 트랙은 0. timescale 이 0 이면 초를 몰라,
 * 표본이 판독 한 번의 상한 MAX_TRACK_SAMPLES 를 넘거나 모양을 모르면(readTrackSamples 가 null) RangeError
 */
function cappedSeconds(bytes: Uint8Array, header: TrackHeader, maxTicks: number): number {
  if (header.timescale === 0) throw new RangeError('timescale');
  const samples = readTrackSamples(bytes, bytes, header.trackId);
  if (!samples) throw new RangeError('samples');
  const ticks = samples.reduce((sum, sample) => sum + Math.min(sample.duration, maxTicks), 0);
  return ticks / header.timescale;
}

/**
 * moov 의 소리 trak 을 파일 순서대로(Map 은 넣은 순서를 지킨다), track_ID 로 찾게. 못 읽는 소리 트랙이 있으면 null.
 * 소리 trak 마다 트랙 머리(readTrackHeaders — 트랙 수 상한 · 번호 겹침을 거른 목록)의 짝을 traf 를 읽기 전에 확인한다:
 * mdhd 가 빠진 trak 은 그 목록에 없어 상한에 세지 않으므로, 짝 없이 받으면 trak 수만큼 판독이 부푼다(보안 검수 prw2 c9′
 * M-2). 짝이 없거나 번호가 겹치면 RangeError. 트랙마다 프레임 수 시간 상한은 maxFramesOf(frameLimit)가 준다
 */
function audioTracks(
  view: DataView,
  bytes: Uint8Array,
  moov: Box,
  headers: readonly TrackHeader[],
  maxFramesOf: (header: TrackHeader, config: AacConfig) => number,
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
    const defaultSize = defaults.get(id) ?? 0;
    const maxFrames = maxFramesOf(header, config);
    tracks.set(id, { header, config, defaultSize, maxFrames, frames: [] });
  }
  return tracks;
}

/**
 * traf 하나의 프레임을 그 트랙에 덧붙이고 남은 몫을 준다(영상 트랙이면 그대로). 프레임 수가 몫(트랙 합)이나 그 트랙의 시간
 * 상한(maxFrames)을 넘으면 프레임을 만들기 전에, 복사 바이트가 몫을 넘으면(trun 이 data_offset 으로 같은 바이트를 거듭
 * 가리킴 — codex prw2 후속 경화 M) RangeError
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
    if (track.frames.length + count > track.maxFrames) throw new RangeError('trun frame time');
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
