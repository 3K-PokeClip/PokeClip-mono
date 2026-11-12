import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SegmentAudioAccess } from '@/api/clipEditor';
import type { EditorPlaylist, PlaylistSegment } from '../source/editorPlaylist';
import type { SegmentTracks } from './fmp4Audio';
import {
  captureAnchorOf,
  captureRowOf,
  loadTrackAudio,
  SegmentsNotReadyError,
  SegmentUrlExpiredError,
  windowAnchors,
  type SegmentAnchors,
} from './loadTrackAudio';

// 창 조각의 ② 소리를 받아 트랙마다 창 Blob 시각에 놓고 푼다(설계 prw2 3.3절 · 7절 c9′). 풀기(decodeAudioData)는 가짜다:
// ADTS 머리에서 표본 빈도 번호 · 프레임 수 · 프레임 본문 첫 바이트(합성 조각은 그 조각 seq)를 읽어 돌려준다 — 어느 트랙의
// 어느 조각 소리를 몇 초 자리에 넘겼는지가 값으로 드러난다.
//
// 판독(demuxSegmentAudio)은 기본이 실물이다. 배치 시험은 조각마다 시각 · 프레임 수를 정해 주려고 합성 결과로 바꾼다.
const demux = vi.hoisted(() => ({
  synthetic: null as null | ((bytes: Uint8Array) => SegmentTracks),
}));
vi.mock('./fmp4Audio', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./fmp4Audio')>();
  return {
    ...actual,
    demuxSegmentAudio: (data: ArrayBuffer | Uint8Array) =>
      demux.synthetic === null
        ? actual.demuxSegmentAudio(data)
        : demux.synthetic(data instanceof Uint8Array ? data : new Uint8Array(data)),
  };
});

const fixture = readFileSync(join(__dirname, '__fixtures__', 'three-tracks.m4s'));
const BASE = 1_000_000;
const FREQUENCIES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
];

interface FakeBuffer {
  frequencyIndex: number;
  frames: number;
  /** 프레임마다 본문 첫 바이트(합성 조각은 그 조각 seq) */
  tags: number[];
  duration: number;
}

function fakeDecode(adts: Uint8Array) {
  const tags: number[] = [];
  let offset = 0;
  while (offset < adts.length) {
    const at = (i: number) => adts[offset + i]!;
    tags.push(adts[offset + 7]!);
    offset += ((at(3) & 0x03) << 11) | (at(4) << 3) | (at(5) >> 5);
  }
  const frequencyIndex = (adts[2]! >> 2) & 0x0f;
  const buffer: FakeBuffer = {
    frequencyIndex,
    frames: tags.length,
    tags,
    duration: (tags.length * 1024) / FREQUENCIES[frequencyIndex]!,
  };
  return Promise.resolve(buffer as unknown as AudioBuffer);
}

const fake = (buffer: AudioBuffer) => buffer as unknown as FakeBuffer;

function access(...starts: number[]): SegmentAudioAccess {
  return {
    expiresAt: '2026-10-23T00:05:00Z',
    segments: starts.map((startAtMs, i) => ({
      seq: i + 1,
      startAtMs,
      durationMs: 1000,
      url: `https://s3/seg_${i + 1}.m4s`,
    })),
  };
}

/** 받기 가짜. 합성 시험이면 조각 바이트 = [seq](판독 가짜가 seq 로 결과를 고른다) */
function source(segments: SegmentAudioAccess, anchors: SegmentAnchors) {
  return {
    fetchAccess: vi.fn(() => Promise.resolve(segments)),
    fetchBytes: vi.fn((url: string) => {
      const seq = Number(/seg_(\d+)/.exec(url)?.[1]);
      return Promise.resolve(
        demux.synthetic === null ? new Uint8Array(fixture).buffer : Uint8Array.of(seq).buffer,
      );
    }),
    anchors: vi.fn(() => Promise.resolve(anchors)),
    decode: vi.fn(fakeDecode),
  };
}

/** seq 마다 같은 닻 간격(초) — 창 조각 seq 1… */
const anchorsOf = (...blobSeconds: number[]): SegmentAnchors =>
  new Map(blobSeconds.map((seconds, i) => [i + 1, seconds]));
const seqsOf = (count: number) => Array.from({ length: count }, (_, i) => i + 1);

describe('loadTrackAudio — 실물 조각', () => {
  beforeEach(() => {
    demux.synthetic = null;
  });

  it('트랙 번호는 조각 안 소리 트랙의 파일 순서다: 렌더의 [0:a:N]과 같다', async () => {
    const { tracks } = await loadTrackAudio(source(access(BASE + 2000), anchorsOf(0)), [1]);
    expect(tracks).toHaveLength(3);
    expect(tracks.map((pieces) => fake(pieces[0]!.buffer).frequencyIndex)).toEqual([3, 4, 6]); // 48000 · 44100 · 24000
  });

  it('주소를 못 받으면 던진다: 화면은 영상 소리로 돌아간다', async () => {
    const src = source(access(BASE), anchorsOf(0));
    src.fetchAccess.mockRejectedValueOnce(new Error('503'));
    await expect(loadTrackAudio(src, [1])).rejects.toThrow('503');
  });

  it('조각 하나가 실패하면 남은 조각은 받지 않는다: 결과를 버리는데 끝까지 받으면 헛받기다(PR #341 claude)', async () => {
    const seqs = seqsOf(10);
    const src = source(access(...seqs.map((i) => BASE + i * 1000)), anchorsOf(...seqs));
    // 다른 조각은 한 틱 늦게 온다(망): 실패가 먼저 알려진 뒤에 나머지가 도착한다
    src.fetchBytes.mockImplementation((url: string) =>
      url.endsWith('seg_2.m4s')
        ? Promise.reject(new Error('segment 503'))
        : new Promise<ArrayBuffer>((resolve) =>
            setTimeout(() => resolve(new Uint8Array(fixture).buffer), 0),
          ),
    );
    await expect(loadTrackAudio(src, seqs)).rejects.toThrow('segment 503');
    // 거절은 바로 오지만 남은 작업자는 그 뒤에도 돈다: 받던 조각이 다 도착할 때까지 기다렸다 센다
    await new Promise((resolve) => setTimeout(resolve, 50));
    // 동시에 받던 넷(1~4번)까지만: 실패 뒤에 새 조각을 집지 않는다
    expect(src.fetchBytes).toHaveBeenCalledTimes(4);
  });

  it('받는 사이 서명 주소가 만료되면 주소를 새로 받아 그 조각만 다시 받는다: 동시에 여럿 만료돼도 새로 받기는 한 번(PR #341 3차)', async () => {
    const first = access(BASE + 2000, BASE + 3000, BASE + 4000);
    const second: SegmentAudioAccess = {
      ...first,
      segments: first.segments.map((segment) => ({ ...segment, url: `${segment.url}?v=2` })),
    };
    const src = source(first, anchorsOf(2, 3, 4));
    src.fetchAccess.mockResolvedValueOnce(first).mockResolvedValueOnce(second);
    // 2·3번은 옛 주소가 만료됐다(403)
    src.fetchBytes.mockImplementation((url: string) =>
      /seg_[23]\.m4s$/.test(url)
        ? Promise.reject(new SegmentUrlExpiredError())
        : Promise.resolve(new Uint8Array(fixture).buffer),
    );
    const { tracks } = await loadTrackAudio(src, [1, 2, 3]);
    expect(tracks).toHaveLength(3);
    expect(tracks[0]![0]!.startSeconds).toBe(2);
    expect(src.fetchAccess).toHaveBeenCalledTimes(2);
    expect(src.fetchBytes).toHaveBeenCalledWith('https://s3/seg_2.m4s?v=2');
    expect(src.fetchBytes).toHaveBeenCalledWith('https://s3/seg_3.m4s?v=2');
  });

  it('새 주소로도 만료면 실패다: 끝없이 다시 받지 않는다', async () => {
    const src = source(access(BASE + 2000), anchorsOf(0));
    src.fetchBytes.mockRejectedValue(new SegmentUrlExpiredError());
    await expect(loadTrackAudio(src, [1])).rejects.toBeInstanceOf(SegmentUrlExpiredError);
    expect(src.fetchAccess).toHaveBeenCalledTimes(2);
    expect(src.fetchBytes).toHaveBeenCalledTimes(2);
  });

  it('받은 조각을 하나라도 못 풀면 실패다: 덜 올라온 조각처럼 그 자리를 무음으로 두지 않는다(PR #341 3차)', async () => {
    const src = source(access(BASE + 2000, BASE + 3000, BASE + 4000), anchorsOf(0, 1, 2));
    // 2번만 깨진 조각(상자가 없다)
    src.fetchBytes.mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith('seg_2.m4s') ? new Uint8Array(16).buffer : new Uint8Array(fixture).buffer,
      ),
    );
    await expect(loadTrackAudio(src, [1, 2, 3])).rejects.toThrow('segment 2 has no readable audio');
  });

  it('받을 조각이 없으면 빈 목록이다', async () => {
    const src = source(access(), new Map());
    expect((await loadTrackAudio(src, [])).tracks).toEqual([]);
  });
});

describe('loadTrackAudio — 미리 판정과 풀기 동시 수', () => {
  beforeEach(() => {
    demux.synthetic = null;
  });

  it('precheck가 false면 본문을 하나도 받지 않고 SegmentsNotReadyError로 던진다', async () => {
    const src = source(access(BASE + 2000), anchorsOf(0));

    await expect(loadTrackAudio(src, [1], { precheck: () => false })).rejects.toBeInstanceOf(
      SegmentsNotReadyError,
    );
    expect(src.fetchBytes).not.toHaveBeenCalled();
    expect(src.decode).not.toHaveBeenCalled();
  });

  it('precheck는 주소 응답의 조각 목록을 받고, true면 그대로 받아 푼다', async () => {
    const src = source(access(BASE + 2000), anchorsOf(0));
    const seen = vi.fn((segments: SegmentAudioAccess['segments']) => segments.length === 1);

    expect((await loadTrackAudio(src, [1], { precheck: seen })).tracks).toHaveLength(3);
    expect(seen).toHaveBeenCalledTimes(1);
    expect(src.fetchBytes).toHaveBeenCalledTimes(1);
  });

  it('트랙을 동시에 두 개까지만 풀고, 결과는 끝나는 순서가 아니라 트랙 순서다', async () => {
    let running = 0;
    let peak = 0;
    let started = 0;
    const src = {
      ...source(access(BASE + 2000), anchorsOf(0)),
      // 앞 트랙일수록 늦게 끝나게 한다: 순서가 끝난 차례를 따르면 뒤집힌다
      decode: vi.fn(async (adts: Uint8Array) => {
        const id = started++;
        running += 1;
        peak = Math.max(peak, running);
        const buffer = await fakeDecode(adts);
        await new Promise((resolve) => setTimeout(resolve, 30 - id * 10));
        running -= 1;
        return Object.assign(buffer, { id });
      }),
    };

    const { tracks } = await loadTrackAudio(src, [1]);

    expect(peak).toBe(2);
    expect(tracks.map((track) => (track[0]!.buffer as unknown as { id: number }).id)).toEqual([
      0, 1, 2,
    ]);
  });
});

// --- 창 Blob 시각 배치(설계 prw2 3.3절 「트랙 소리 배치」 · 7절 c9′ E9) ---

interface SyntheticTrack {
  frequencyIndex: number;
  /** 트랙 timescale — 표본 빈도와 다를 수 있다(프레임 길이는 표본 빈도로 잰다) */
  timescale?: number;
  frames: number;
  /** ② 트랙 첫 PTS(초) */
  firstPts: number;
}

interface SyntheticSegment {
  /** ② 영상 첫 DTS(초) D2_k. null 이면 영상 트랙 없음 */
  videoDts: number | null;
  tracks: SyntheticTrack[];
}

/** 조각 바이트 [seq] → 그 seq 의 합성 판독 결과. 프레임 본문은 [seq] 한 바이트다 */
function useSynthetic(segments: Record<number, SyntheticSegment>) {
  demux.synthetic = (bytes) => {
    const seq = bytes[0]!;
    const spec = segments[seq]!;
    return {
      video:
        spec.videoDts === null
          ? null
          : { timescale: 90000, timing: { firstDts: spec.videoDts, firstPts: spec.videoDts } },
      audio: spec.tracks.map((track) => ({
        config: { objectType: 2, frequencyIndex: track.frequencyIndex, channelConfig: 1 },
        frames: Array.from({ length: track.frames }, () => Uint8Array.of(seq)),
        timescale: track.timescale ?? FREQUENCIES[track.frequencyIndex]!,
        timing: { firstDts: track.firstPts, firstPts: track.firstPts },
      })),
    };
  };
}

/** 4초짜리 32kHz 소리(1024표본 프레임 125개 = 정확히 4.000초) */
const FOUR_SECONDS_32K = { frequencyIndex: 5, frames: 125 };
const FRAME_32K = 1024 / 32000;

/** 그 트랙에서 seq 소리가 처음 들리는 Blob 시각 */
function firstHeard(pieces: { startSeconds: number; buffer: AudioBuffer }[], seq: number) {
  for (const piece of pieces) {
    const { tags } = fake(piece.buffer);
    const at = tags.indexOf(seq);
    if (at >= 0) return piece.startSeconds + at * FRAME_32K;
  }
  return null;
}

describe('loadTrackAudio — 창 Blob 시각 배치', () => {
  beforeEach(() => {
    demux.synthetic = null;
  });

  it('트랙 시작 = 그 조각 ③ 영상 첫 DTS 의 Blob 시각 + (② 트랙 첫 PTS − ② 영상 첫 DTS)', async () => {
    useSynthetic({ 1: { videoDts: 0.1, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0.04 }] } });
    const { tracks } = await loadTrackAudio(source(access(BASE + 30_000), anchorsOf(2.0)), [1]);

    // 장부 시각(startAtMs)이 아니라 닻 2.0 − 0.06 이다
    expect(tracks[0]![0]!.startSeconds).toBeCloseTo(1.94, 9);
  });

  it('PDT 가 건너뛴 이웃(PDT 0 → 7초 · Blob 0 → 4초)도 다음 조각 소리가 그 조각 영상 자리(80ms 안)에 붙는다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    const src = source(access(BASE, BASE + 7000), anchorsOf(0, 4));

    const { tracks } = await loadTrackAudio(src, [1, 2]);

    expect(Math.abs(firstHeard(tracks[0]!, 2)! - 4)).toBeLessThan(0.08);
    // 이어진 소리라 한 덩어리로 푼다(이음매 소리 없음)
    expect(tracks[0]).toHaveLength(1);
  });

  it('반 프레임 넘게 벌어지면 그 트랙만 새 덩어리다: 이어진 다른 트랙은 한 덩어리 그대로', async () => {
    useSynthetic({
      1: {
        videoDts: 0,
        tracks: [
          { ...FOUR_SECONDS_32K, firstPts: 0 },
          { ...FOUR_SECONDS_32K, firstPts: 0 },
        ],
      },
      2: {
        videoDts: 0,
        tracks: [
          { ...FOUR_SECONDS_32K, firstPts: 0 },
          // 둘째 트랙만 0.1초 늦게 시작한다(반 프레임 16ms 넘게 빔)
          { ...FOUR_SECONDS_32K, firstPts: 0.1 },
        ],
      },
    });
    const { tracks } = await loadTrackAudio(
      source(access(BASE, BASE + 4000), anchorsOf(0, 4)),
      [1, 2],
    );

    expect(tracks[0]!.map((piece) => piece.startSeconds)).toEqual([0]);
    expect(tracks[1]!.map((piece) => piece.startSeconds)).toEqual([0, 4.1]);
  });

  it('반 프레임 안의 어긋남은 이어 붙인다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      // 반 프레임(16ms)보다 작게 8ms 늦다
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0.008 }] },
    });
    const { tracks } = await loadTrackAudio(
      source(access(BASE, BASE + 4000), anchorsOf(0, 4)),
      [1, 2],
    );

    expect(tracks[0]).toHaveLength(1);
  });

  it('겹치면 나중 조각 소리가 이긴다: 앞 덩어리 꼬리를 그 자리에서 자른다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    // 판 2 이음 겹침: 둘째 조각이 0.32초(프레임 열 개) 일찍 시작한다
    const { tracks } = await loadTrackAudio(
      source(access(BASE, BASE + 3680), anchorsOf(0, 3.68)),
      [1, 2],
    );

    const [head, tail] = tracks[0]!;
    expect(fake(head!.buffer).tags).toEqual(Array.from({ length: 115 }, () => 1));
    expect(tail!.startSeconds).toBeCloseTo(3.68, 9);
    expect(firstHeard(tracks[0]!, 2)).toBeCloseTo(3.68, 9);
  });

  it('겹침이 프레임 경계에 안 맞아도 앞 덩어리는 나중 소리 시작 전에 끝난다: 두 소리가 함께 나는 자리가 없다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    // 둘째 조각이 3.7초에 시작한다 — 앞 덩어리 115.625 프레임 자리. 반올림하면 116 프레임(3.712초)까지 남아 12ms 겹친다
    const { tracks } = await loadTrackAudio(
      source(access(BASE, BASE + 3700), anchorsOf(0, 3.7)),
      [1, 2],
    );

    const [head, tail] = tracks[0]!;
    expect(head!.startSeconds + fake(head!.buffer).duration).toBeLessThanOrEqual(
      tail!.startSeconds,
    );
    expect(fake(head!.buffer).frames).toBe(115);
  });

  it('정확히 반 프레임 벌어진 것은 이어 붙인다: 부동소수 차(0.016000000000000014)로 가르지 않는다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    // 앞 끝 4초 · 다음 시작 4.016초 = 32kHz 반 프레임(512표본)
    const { tracks } = await loadTrackAudio(
      source(access(BASE, BASE + 4016), anchorsOf(0, 4.016)),
      [1, 2],
    );

    expect(tracks[0]).toHaveLength(1);
  });

  it('반 프레임보다 한 표본이라도 더 벌어지면 새 덩어리다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    const next = 4 + 513 / 32000;
    const { tracks } = await loadTrackAudio(
      source(access(BASE, BASE + 4016), anchorsOf(0, next)),
      [1, 2],
    );

    expect(tracks[0]!.map((piece) => piece.startSeconds)).toEqual([0, next]);
  });

  it('프레임 길이는 트랙 timescale 이 아니라 표본 빈도로 잰다(timescale 90,000 · 48kHz 트랙도 이어 붙인다)', async () => {
    const track = { frequencyIndex: 3, timescale: 90000, frames: 375, firstPts: 0 }; // 375 × 1024 / 48000 = 8초
    useSynthetic({
      1: { videoDts: 0, tracks: [track] },
      2: { videoDts: 0, tracks: [track] },
    });
    const { tracks } = await loadTrackAudio(
      source(access(BASE, BASE + 8000), anchorsOf(0, 8)),
      [1, 2],
    );

    expect(tracks[0]).toHaveLength(1);
    expect(fake(tracks[0]![0]!.buffer).frames).toBe(750);
  });

  it('창 밖 조각(응답에 같이 온 이웃)은 받지 않는다: 놓을 Blob 자리가 없다', async () => {
    useSynthetic({ 2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] } });
    const src = source(access(BASE - 4000, BASE), new Map([[2, 0]]));

    await loadTrackAudio(src, [2]);

    expect(src.fetchBytes).toHaveBeenCalledTimes(1);
    expect(src.fetchBytes).toHaveBeenCalledWith('https://s3/seg_2.m4s');
  });

  it('② 에 영상 시각이 없으면 놓을 자리를 몰라 실패다', async () => {
    useSynthetic({ 1: { videoDts: null, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] } });
    await expect(loadTrackAudio(source(access(BASE), anchorsOf(0)), [1])).rejects.toThrow(
      'segment 1 has no video timing',
    );
  });

  it('닻은 받기를 다 마친 뒤에 묻는다(영상 창이 늦게 와도 받기는 먼저 간다)', async () => {
    useSynthetic({ 1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] } });
    const src = source(access(BASE), anchorsOf(0));
    src.anchors.mockImplementation(() => {
      expect(src.fetchBytes).toHaveBeenCalledTimes(1);
      return Promise.resolve(anchorsOf(0));
    });

    await loadTrackAudio(src, [1]);
    expect(src.anchors).toHaveBeenCalledTimes(1);
  });
});

// --- 브라우저 풀기가 버린 머리(실측 M6 · 2026-11-13) ---

/**
 * 풀린 소리를 seconds 만큼 줄여 돌려주는 풀기 — 브라우저 decodeAudioData(ADTS)가 머리 표본을 버리는 것을 흉내 낸다. 음수면
 * 기대보다 길다(꼬리 · SBR 두 배 빈도 등)
 */
function trimmingDecode(seconds: number) {
  return vi.fn(async (adts: Uint8Array) => {
    const buffer = fake(await fakeDecode(adts));
    return { ...buffer, duration: buffer.duration - seconds } as unknown as AudioBuffer;
  });
}

/** 8초짜리 48kHz 소리(1024표본 프레임 375개) */
const EIGHT_SECONDS_48K = { frequencyIndex: 3, frames: 375 };

describe('loadTrackAudio — 풀기가 버린 머리만큼 시작을 뒤로 민다', () => {
  beforeEach(() => {
    demux.synthetic = null;
  });

  it('WebKit · Safari 꼴(48kHz 2112표본 = 44ms 버림): 덩어리 시작이 44ms 뒤로 간다 — 남은 소리가 제 시각에 난다', async () => {
    useSynthetic({ 1: { videoDts: 0, tracks: [{ ...EIGHT_SECONDS_48K, firstPts: 0 }] } });
    const src = { ...source(access(BASE), anchorsOf(2)), decode: trimmingDecode(2112 / 48000) };

    const { tracks } = await loadTrackAudio(src, [1]);

    expect(tracks[0]![0]!.startSeconds).toBeCloseTo(2 + 0.044, 9);
  });

  it('Firefox 꼴(프레임 하나 버림): 그 트랙 프레임 길이만큼 뒤로 간다', async () => {
    useSynthetic({ 1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] } });
    const src = { ...source(access(BASE), anchorsOf(2)), decode: trimmingDecode(FRAME_32K) };

    const { tracks } = await loadTrackAudio(src, [1]);

    expect(tracks[0]![0]!.startSeconds).toBeCloseTo(2 + FRAME_32K, 9);
  });

  it('기대보다 길게 풀리면 시작을 그대로 둔다: 머리를 버린 근거가 없다(꼬리 · SBR)', async () => {
    useSynthetic({ 1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] } });
    const src = { ...source(access(BASE), anchorsOf(2)), decode: trimmingDecode(-4) };

    const { tracks } = await loadTrackAudio(src, [1]);

    expect(tracks[0]![0]!.startSeconds).toBe(2);
  });

  it('앞서 푼 덩어리를 다시 써도(previous) 같은 만큼 민다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      // 1초 떨어진 둘째 조각 — 새 덩어리라 첫 덩어리는 앞서 푼 것을 쓴다
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    const decode = trimmingDecode(FRAME_32K);
    const first = await loadTrackAudio({ ...source(access(BASE), anchorsOf(0, 5)), decode }, [1]);
    const second = await loadTrackAudio(
      { ...source(access(BASE, BASE + 5000), anchorsOf(0, 5)), decode },
      [1, 2],
      { previous: first },
    );

    expect(decode).toHaveBeenCalledTimes(2);
    const [head, tail] = second.tracks[0]!;
    expect(head!.startSeconds).toBeCloseTo(FRAME_32K, 9);
    expect(tail!.startSeconds).toBeCloseTo(5 + FRAME_32K, 9);
  });
});

describe('windowAnchors', () => {
  it('seq → ③ 영상 첫 DTS − Δ(창 Blob 시각)', () => {
    const segment = (seq: number, videoDts: number) => ({
      seq,
      videoDts,
      frames: [],
      mediaStart: videoDts + 0.1,
      mediaEnd: videoDts + 4,
    });
    expect(
      windowAnchors({ shiftSeconds: 10.2, segments: [segment(7, 10.3), segment(8, 14.3)] }),
    ).toEqual(
      new Map([
        [7, 10.3 - 10.2],
        [8, 14.3 - 10.2],
      ]),
    );
  });
});

// --- ② 기다림 고리 재료 · 끝 소리 확보(설계 prw2 7절 c10b′) ---

/** 확보 칸을 더한 응답 — 확보 조각은 segments 에 섞지 않는다 */
function withCapture(
  base: SegmentAudioAccess,
  audioCapture: string | undefined,
  captureSeq?: number,
): SegmentAudioAccess {
  return {
    ...base,
    ...(audioCapture === undefined ? {} : { audioCapture }),
    ...(captureSeq === undefined
      ? {}
      : {
          audioCaptureSegment: {
            seq: captureSeq,
            startAtMs: BASE + captureSeq * 4000,
            durationMs: 4000,
            url: `https://s3/seg_${captureSeq}.m4s`,
          },
        }),
  } as SegmentAudioAccess;
}

/** 4초 조각 seq 1… 를 Blob 0, 4, 8 … 에 둔 응답(seq 를 골라 뺄 수 있다) */
function accessOf(seqs: readonly number[]): SegmentAudioAccess {
  return {
    expiresAt: '2026-10-23T00:05:00Z',
    segments: seqs.map((seq) => ({
      seq,
      startAtMs: BASE + seq * 4000,
      durationMs: 4000,
      url: `https://s3/seg_${seq}.m4s`,
    })),
  };
}

const FOUR = (): SyntheticSegment => ({
  videoDts: 0,
  tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }],
});
const BLOB_ANCHORS = new Map([1, 2, 3, 4].map((seq) => [seq, (seq - 1) * 4]));

/** 확보 조각(seq 9) 프레임이 든 덩어리만 못 푸는 풀기 — decodeAudioData 거절을 흉내 낸다 */
function rejectCaptureFrames(adts: Uint8Array) {
  for (let offset = 0; offset < adts.length;) {
    if (adts[offset + 7] === 9) {
      return Promise.reject(new DOMException('Unable to decode audio data', 'EncodingError'));
    }
    offset +=
      ((adts[offset + 3]! & 0x03) << 11) | (adts[offset + 4]! << 3) | (adts[offset + 5]! >> 5);
  }
  return fakeDecode(adts);
}

describe('loadTrackAudio — 빠진 행 · 확보 상태', () => {
  beforeEach(() => {
    useSynthetic({ 1: FOUR(), 2: FOUR(), 3: FOUR(), 4: FOUR(), 9: FOUR() });
  });

  it('창 조각 가운데 응답에 행이 없는 seq 를 missingSeqs 로 알리고, 있는 조각만 놓는다', async () => {
    const src = source(accessOf([1, 3]), BLOB_ANCHORS);

    const load = await loadTrackAudio(src, [1, 2, 3]);

    expect(load.missingSeqs).toEqual([2]);
    expect(firstHeard(load.tracks[0]!, 1)).toBeCloseTo(0, 9);
    expect(firstHeard(load.tracks[0]!, 3)).toBeCloseTo(8, 9);
    expect(firstHeard(load.tracks[0]!, 2)).toBeNull();
  });

  it('응답에 행이 하나도 없으면 빈 트랙에 모든 seq 가 빠졌다고 알린다(닻을 기다리지 않는다)', async () => {
    const src = source(accessOf([]), BLOB_ANCHORS);

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.tracks).toEqual([]);
    expect(load.missingSeqs).toEqual([1, 2]);
    expect(src.anchors).not.toHaveBeenCalled();
  });

  it.each([
    ['칸 없음(옛 clip) → none', undefined, 'none'],
    ['skipped → none(확보 안 함 · 다시 묻지 않음 — R28)', 'skipped', 'none'],
    ['모르는 값 → none', 'later', 'none'],
    ['not_ready → waiting', 'not_ready', 'waiting'],
  ] as const)('%s · 확보 닻을 묻지 않는다', async (_name, value, expected) => {
    const captureAnchor = vi.fn(() => Promise.resolve(8));
    const src = { ...source(withCapture(accessOf([1, 2]), value), BLOB_ANCHORS), captureAnchor };

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe(expected);
    expect(captureAnchor).not.toHaveBeenCalled();
    expect(src.fetchBytes).toHaveBeenCalledTimes(2);
  });

  it('attached 면 확보 조각(응답 audioCaptureSegment 주소)을 받아 확보 닻(D3 − Δ)에 놓는다: 창 끝 소리가 이어진다', async () => {
    const captureAnchor = vi.fn(() => Promise.resolve(8));
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor,
    };

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe('placed');
    expect(captureAnchor).toHaveBeenCalledWith(9);
    expect(src.fetchBytes).toHaveBeenCalledWith('https://s3/seg_9.m4s');
    expect(firstHeard(load.tracks[0]!, 9)).toBeCloseTo(8, 9);
    // 창 끝(Blob 8초)에 바로 이어 한 덩어리다
    expect(load.tracks[0]).toHaveLength(1);
  });

  it('확보 닻이 아직이면(③ 이 목록에 아직 없음) 확보 조각을 받지 않고 waiting 이다', async () => {
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve('wait' as const)),
    };

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe('waiting');
    expect(src.fetchBytes).not.toHaveBeenCalledWith('https://s3/seg_9.m4s');
  });

  it('확보를 포기하면(GAP · 다른 런 · 판독 실패) 받지 않고 none 이다', async () => {
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(null)),
    };

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe('none');
    expect(src.fetchBytes).toHaveBeenCalledTimes(2);
  });

  it('확보 닻을 물을 길이 없으면(부르는 쪽이 목록을 모름) 확보하지 않는다', async () => {
    const src = source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS);

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe('none');
    expect(src.fetchBytes).toHaveBeenCalledTimes(2);
  });

  it('확보 조각 받기가 실패해도 창 소리는 그대로 놓고 확보만 포기한다', async () => {
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(8)),
    };
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fetchBytes = src.fetchBytes.getMockImplementation()!;
    src.fetchBytes.mockImplementation((url: string) =>
      url.endsWith('seg_9.m4s') ? Promise.reject(new Error('503')) : fetchBytes(url),
    );

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe('none');
    expect(firstHeard(load.tracks[0]!, 2)).toBeCloseTo(4, 9);
  });

  it('창 끝에 이어 붙은 확보 소리를 못 풀면 확보를 빼고 창 소리만 다시 놓아 푼다: 창 소리는 그대로 · 확보 none(c10b′ r1 2번)', async () => {
    useSynthetic({ 1: FOUR(), 2: FOUR(), 9: FOUR() });
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(8)),
    };
    src.decode.mockImplementation(rejectCaptureFrames);

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe('none');
    expect(load.tracks[0]).toHaveLength(1);
    expect(firstHeard(load.tracks[0]!, 1)).toBeCloseTo(0, 9);
    expect(firstHeard(load.tracks[0]!, 2)).toBeCloseTo(4, 9);
    expect(firstHeard(load.tracks[0]!, 9)).toBeNull();
    expect(load.reuse.capture).toBeNull();
    expect([...load.reuse.segments.keys()]).toEqual([1, 2]);
  });

  it('확보 조각에 ② 영상 시각이 없어 놓을 수 없으면 확보를 빼고 창 소리만 놓는다: 확보 none', async () => {
    useSynthetic({
      1: FOUR(),
      2: FOUR(),
      9: { videoDts: null, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(8)),
    };

    const load = await loadTrackAudio(src, [1, 2]);

    expect(load.capture).toBe('none');
    expect(firstHeard(load.tracks[0]!, 2)).toBeCloseTo(4, 9);
    expect(firstHeard(load.tracks[0]!, 9)).toBeNull();
  });

  it('창 조각 소리를 못 풀면 확보와 무관하게 실패다: 확보를 빼고 다시 해도 못 풀면 던진다', async () => {
    useSynthetic({ 1: FOUR(), 2: FOUR(), 9: FOUR() });
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(8)),
    };
    src.decode.mockRejectedValue(new DOMException('Unable to decode audio data', 'EncodingError'));

    await expect(loadTrackAudio(src, [1, 2])).rejects.toThrow('Unable to decode audio data');
  });
});

describe('loadTrackAudio — 다시 받기(previous): 행이 같으면 내려받기 0 · 바뀐 덩어리만 다시 푼다', () => {
  beforeEach(() => {
    useSynthetic({ 1: FOUR(), 2: FOUR(), 3: FOUR(), 4: FOUR(), 9: FOUR() });
  });

  it('행이 같으면 하나도 받거나 풀지 않고 같은 트랙을 돌려준다', async () => {
    const first = await loadTrackAudio(source(accessOf([1, 2]), BLOB_ANCHORS), [1, 2, 3]);
    const src = source(accessOf([1, 2]), BLOB_ANCHORS);

    const again = await loadTrackAudio(src, [1, 2, 3], { previous: first });

    expect(src.fetchBytes).not.toHaveBeenCalled();
    expect(src.decode).not.toHaveBeenCalled();
    expect(again.tracks).toBe(first.tracks);
    expect(again.missingSeqs).toEqual([3]);
  });

  it('빠졌던 행이 오면 그 조각만 받고, 그 조각이 든 덩어리를 다시 푼다', async () => {
    const first = await loadTrackAudio(source(accessOf([1, 2]), BLOB_ANCHORS), [1, 2, 3]);
    const src = source(accessOf([1, 2, 3]), BLOB_ANCHORS);

    const again = await loadTrackAudio(src, [1, 2, 3], { previous: first });

    expect(src.fetchBytes.mock.calls.map(([url]) => url)).toEqual(['https://s3/seg_3.m4s']);
    expect(again.missingSeqs).toEqual([]);
    expect(again.tracks[0]).toHaveLength(1);
    expect(firstHeard(again.tracks[0]!, 3)).toBeCloseTo(8, 9);
    expect(src.decode).toHaveBeenCalledTimes(1);
  });

  it('바뀌지 않은 덩어리는 다시 풀지 않는다: 창 뒤 떨어진 자리에 확보 소리가 붙으면 그 덩어리만 푼다', async () => {
    const first = await loadTrackAudio(
      source(withCapture(accessOf([1, 2]), 'not_ready'), BLOB_ANCHORS),
      [1, 2],
    );
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(20)),
    };

    const again = await loadTrackAudio(src, [1, 2], { previous: first });

    expect(again.capture).toBe('placed');
    expect(src.fetchBytes.mock.calls.map(([url]) => url)).toEqual(['https://s3/seg_9.m4s']);
    expect(src.decode).toHaveBeenCalledTimes(1);
    expect(again.tracks[0]![0]!.buffer).toBe(first.tracks[0]![0]!.buffer);
    expect(firstHeard(again.tracks[0]!, 9)).toBeCloseTo(20, 9);
  });

  it('놓아 둔 확보 조각이 같으면 확보 닻을 다시 묻지도 받지도 않는다', async () => {
    const captured = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(8)),
    };
    const first = await loadTrackAudio(captured, [1, 2, 3]);
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(8)),
    };

    const again = await loadTrackAudio(src, [1, 2, 3], { previous: first });

    expect(src.captureAnchor).not.toHaveBeenCalled();
    expect(src.fetchBytes).not.toHaveBeenCalled();
    expect(again.tracks).toBe(first.tracks);
    expect(again.capture).toBe('placed');
  });

  it('다시 받기에서 새로 붙인 확보 소리를 못 풀어도 앞 창 소리를 그대로 돌려주고 확보는 none 이다', async () => {
    useSynthetic({ 1: FOUR(), 2: FOUR(), 9: FOUR() });
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const first = await loadTrackAudio(
      source(withCapture(accessOf([1, 2]), 'not_ready'), BLOB_ANCHORS),
      [1, 2],
    );
    const src = {
      ...source(withCapture(accessOf([1, 2]), 'attached', 9), BLOB_ANCHORS),
      captureAnchor: vi.fn(() => Promise.resolve(8)),
    };
    src.decode.mockImplementation(rejectCaptureFrames);

    const again = await loadTrackAudio(src, [1, 2], { previous: first });

    expect(first.capture).toBe('waiting');
    expect(again.capture).toBe('none');
    expect(again.tracks).toBe(first.tracks);
    expect(again.reuse.capture).toBeNull();
  });
});

// --- 확보 조각 자리 판정(설계 prw2 7절 c10b′ 확보 배치) ---

const DIR = 'https://cdn/r-1';
function row(seq: number, extra: Partial<PlaylistSegment> = {}): PlaylistSegment {
  return {
    seq,
    uri: `${DIR}/seg/${seq}.m4s`,
    mapUri: `${DIR}/init/i-1.mp4`,
    pdtMs: BASE + seq * 4000,
    durationMs: 4000,
    timing: 'v2',
    gap: false,
    ...extra,
  };
}

/** 목록 — runs 는 나눈 그대로, rows 는 GAP 줄까지 차례로 */
function playlistOf(runs: PlaylistSegment[][], gaps: PlaylistSegment[] = []): EditorPlaylist {
  return { runs, rows: [...runs.flat(), ...gaps].sort((a, b) => a.seq - b.seq) };
}

describe('captureRowOf: 확보 seq 의 ③ 을 목록에서 찾는다', () => {
  const window = [row(10), row(11)];

  it('창과 같은 런의 조각이면 그 줄이다', () => {
    const list = playlistOf([[row(10), row(11), row(12)]]);
    expect(captureRowOf(list, window, 12)).toEqual(row(12));
  });

  it('목록에 아직 없으면(GAP 줄도 없음) 기다린다 — 목록이 없을 때도', () => {
    expect(captureRowOf(playlistOf([[row(10), row(11)]]), window, 12)).toBe('wait');
    expect(captureRowOf(null, window, 12)).toBe('wait');
  });

  it('GAP 줄이면 포기한다', () => {
    const list = playlistOf([[row(10), row(11)]], [row(12, { gap: true })]);
    expect(captureRowOf(list, window, 12)).toBeNull();
  });

  it('다른 런이면(DISCONTINUITY · MAP 바뀜) 포기한다', () => {
    const list = playlistOf([[row(10), row(11)], [row(12, { mapUri: `${DIR}/init/i-2.mp4` })]]);
    expect(captureRowOf(list, window, 12)).toBeNull();
  });

  it('창 안이나 창 앞 seq 는 확보 조각이 아니다: 포기한다', () => {
    const list = playlistOf([[row(9), row(10), row(11), row(12)]]);
    expect(captureRowOf(list, window, 11)).toBeNull();
    expect(captureRowOf(list, window, 9)).toBeNull();
  });
});

describe('captureAnchorOf: 확보 조각의 Blob 닻 = ③ 영상 첫 DTS − Δ', () => {
  it('찾은 줄의 ③ 머리 tfdt 를 읽어 Δ 를 뺀다', async () => {
    const readDts = vi.fn(() => Promise.resolve(512.5));
    await expect(captureAnchorOf(row(12), 500, readDts)).resolves.toBe(12.5);
    expect(readDts).toHaveBeenCalledWith(row(12));
  });

  it('기다림 · 포기는 읽지 않고 그대로다', async () => {
    const readDts = vi.fn(() => Promise.resolve(0));
    await expect(captureAnchorOf('wait', 500, readDts)).resolves.toBe('wait');
    await expect(captureAnchorOf(null, 500, readDts)).resolves.toBeNull();
    expect(readDts).not.toHaveBeenCalled();
  });

  it('읽지 못하면 포기하고 경고에는 주소를 싣지 않는다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const readDts = vi.fn(() =>
      Promise.reject(new Error(`fetch failed ${DIR}/seg/12.m4s?Signature=secret`)),
    );

    await expect(captureAnchorOf(row(12), 500, readDts)).resolves.toBeNull();
    expect(JSON.stringify(warn.mock.calls)).not.toContain('secret');
    expect(JSON.stringify(warn.mock.calls)).not.toContain(DIR);
  });
});
