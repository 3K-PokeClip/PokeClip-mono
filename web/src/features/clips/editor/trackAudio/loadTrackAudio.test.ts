import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SegmentAudioAccess } from '@/api/clipEditor';
import type { SegmentTracks } from './fmp4Audio';
import {
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
    const tracks = await loadTrackAudio(source(access(BASE + 2000), anchorsOf(0)), [1]);
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
    const tracks = await loadTrackAudio(src, [1, 2, 3]);
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
    expect(await loadTrackAudio(src, [])).toEqual([]);
  });
});

describe('loadTrackAudio — 미리 판정과 풀기 동시 수', () => {
  beforeEach(() => {
    demux.synthetic = null;
  });

  it('precheck가 false면 본문을 하나도 받지 않고 SegmentsNotReadyError로 던진다', async () => {
    const src = source(access(BASE + 2000), anchorsOf(0));

    await expect(loadTrackAudio(src, [1], () => false)).rejects.toBeInstanceOf(
      SegmentsNotReadyError,
    );
    expect(src.fetchBytes).not.toHaveBeenCalled();
    expect(src.decode).not.toHaveBeenCalled();
  });

  it('precheck는 주소 응답의 조각 목록을 받고, true면 그대로 받아 푼다', async () => {
    const src = source(access(BASE + 2000), anchorsOf(0));
    const seen = vi.fn((segments: SegmentAudioAccess['segments']) => segments.length === 1);

    await expect(loadTrackAudio(src, [1], seen)).resolves.toHaveLength(3);
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

    const tracks = await loadTrackAudio(src, [1]);

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
    const tracks = await loadTrackAudio(source(access(BASE + 30_000), anchorsOf(2.0)), [1]);

    // 장부 시각(startAtMs)이 아니라 닻 2.0 − 0.06 이다
    expect(tracks[0]![0]!.startSeconds).toBeCloseTo(1.94, 9);
  });

  it('PDT 가 건너뛴 이웃(PDT 0 → 7초 · Blob 0 → 4초)도 다음 조각 소리가 그 조각 영상 자리(80ms 안)에 붙는다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    const src = source(access(BASE, BASE + 7000), anchorsOf(0, 4));

    const tracks = await loadTrackAudio(src, [1, 2]);

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
    const tracks = await loadTrackAudio(source(access(BASE, BASE + 4000), anchorsOf(0, 4)), [1, 2]);

    expect(tracks[0]!.map((piece) => piece.startSeconds)).toEqual([0]);
    expect(tracks[1]!.map((piece) => piece.startSeconds)).toEqual([0, 4.1]);
  });

  it('반 프레임 안의 어긋남은 이어 붙인다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      // 반 프레임(16ms)보다 작게 8ms 늦다
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0.008 }] },
    });
    const tracks = await loadTrackAudio(source(access(BASE, BASE + 4000), anchorsOf(0, 4)), [1, 2]);

    expect(tracks[0]).toHaveLength(1);
  });

  it('겹치면 나중 조각 소리가 이긴다: 앞 덩어리 꼬리를 그 자리에서 자른다', async () => {
    useSynthetic({
      1: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
      2: { videoDts: 0, tracks: [{ ...FOUR_SECONDS_32K, firstPts: 0 }] },
    });
    // 판 2 이음 겹침: 둘째 조각이 0.32초(프레임 열 개) 일찍 시작한다
    const tracks = await loadTrackAudio(
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
    const tracks = await loadTrackAudio(
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
    const tracks = await loadTrackAudio(
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
    const tracks = await loadTrackAudio(
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
    const tracks = await loadTrackAudio(source(access(BASE, BASE + 8000), anchorsOf(0, 8)), [1, 2]);

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
