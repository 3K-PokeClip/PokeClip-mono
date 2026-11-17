import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ALL_FORMATS, BufferSource, EncodedPacketSink, Input } from 'mediabunny';
import { describe, expect, it } from 'vitest';
import { boxes, readTrackSamples } from '@/lib/fmp4Boxes';
import { remuxWindow } from './remuxWindow';

// 창 재다중화(설계 4절 remuxWindow · 6절 위험 2 · 7). 실제 mediabunny 1.65.2 로 M0 픽스처를 변환한다.
// 픽스처: 저장소 ③ 생산자가 segment_4s(영상 1 + 소리 1 · B 프레임)를 pos 1234.5 · 1238.5 · 1242.5 초에 도장한 조각 셋
// (만든 법 · 판독은 testdata/README.md). 기대값은 이 코드가 아니라 ffprobe · mp4dump 출력에서 옮겼다.

const TESTDATA = join(__dirname, 'testdata');
const read = (name: string) => new Uint8Array(readFileSync(join(TESTDATA, name)));
const INIT = read('init.mp4');
const SEGMENTS = [300, 301, 302].map((seq) => ({
  seq,
  bytes: read(`seg_${String(seq).padStart(7, '0')}.m4s`),
}));
const VIDEO_TIMESCALE = 90000;
const AUDIO_TIMESCALE = 44100;

/** 출력 영상 패킷의 시각(초) — 디코드 순서 */
async function outputVideoTimes(blob: Blob): Promise<number[]> {
  const input = new Input({
    source: new BufferSource(await blob.arrayBuffer()),
    formats: ALL_FORMATS,
  });
  const track = await input.getPrimaryVideoTrack();
  const times: number[] = [];
  for await (const packet of new EncodedPacketSink(track!).packets(undefined, undefined, {
    metadataOnly: true,
  })) {
    times.push(packet.timestamp);
  }
  return times;
}

describe('remuxWindow: 창 조각을 trim 없이 일반 mp4 하나로 다시 싼다(실제 mediabunny)', () => {
  it('출력은 moov 가 mdat 앞에 있는 일반 mp4 Blob 이다', async () => {
    const { blob } = await remuxWindow({ init: INIT, segments: SEGMENTS });
    const bytes = new Uint8Array(await blob.arrayBuffer());

    expect(blob.type).toBe('video/mp4');
    const top = [...boxes(new DataView(bytes.buffer), 0, bytes.byteLength)].map((b) => b.type);
    expect(top).toEqual(['ftyp', 'moov', 'mdat']);
  });

  it('shiftSeconds 는 창 안 트랙별 첫 PTS 최솟값이다 — 이 창은 소리(1234.5)가 영상(1234.579011)보다 앞', async () => {
    // 따로 셈: 트랙마다 첫 조각 표본의 PTS 최솟값(눈금 → 초)
    const firstPts = (trackId: number, timescale: number) =>
      Math.min(...readTrackSamples(INIT, SEGMENTS[0]!.bytes, trackId)!.map((s) => s.pts)) /
      timescale;
    const expected = Math.min(firstPts(1, VIDEO_TIMESCALE), firstPts(2, AUDIO_TIMESCALE));

    const { shiftSeconds } = await remuxWindow({ init: INIT, segments: SEGMENTS });

    expect(expected).toBe(1234.5); // ffprobe 소리 start_time
    expect(shiftSeconds).toBeCloseTo(expected, 4);
    expect(Math.abs(shiftSeconds - 111112111 / VIDEO_TIMESCALE)).toBeGreaterThan(0.07);
  });

  it('출력 영상 패킷 시각 + shiftSeconds 는 입력 영상 표본의 ③ PTS 와 하나씩 맞는다(패킷 360)', async () => {
    const { blob, shiftSeconds } = await remuxWindow({ init: INIT, segments: SEGMENTS });
    const inputPts = SEGMENTS.flatMap((seg) =>
      readTrackSamples(INIT, seg.bytes, 1)!.map((s) => s.pts / VIDEO_TIMESCALE),
    ).sort((a, b) => a - b);

    const outputPts = (await outputVideoTimes(blob))
      .map((t) => t + shiftSeconds)
      .sort((a, b) => a - b);

    expect(outputPts).toHaveLength(360);
    outputPts.forEach((pts, i) => expect(pts).toBeCloseTo(inputPts[i]!, 4));
  });

  it('조각별 표: seq · 영상 첫 DTS · PTS 범위 · PTS 순 프레임(③ 축 초)', async () => {
    const { segments } = await remuxWindow({ init: INIT, segments: SEGMENTS });

    expect(segments.map((s) => s.seq)).toEqual([300, 301, 302]);
    const [first] = segments;
    expect(first!.videoDts).toBeCloseTo(111106099 / VIDEO_TIMESCALE, 9);
    expect(first!.mediaStart).toBeCloseTo(111112111 / VIDEO_TIMESCALE, 9);
    expect(first!.mediaEnd).toBeCloseTo(111472137 / VIDEO_TIMESCALE, 9);
    expect(first!.frames).toHaveLength(120);
    expect(first!.frames[0]!.pts).toBeCloseTo(111112111 / VIDEO_TIMESCALE, 9);
    expect(first!.frames[0]!.dur).toBeCloseTo(2997 / VIDEO_TIMESCALE, 9);
    const pts = first!.frames.map((f) => f.pts);
    expect(pts).toEqual([...pts].sort((a, b) => a - b));
    expect(segments[2]!.videoDts).toBeCloseTo(111826099 / VIDEO_TIMESCALE, 9);
  });

  it('init 에 영상 트랙이 없거나 · 조각이 없거나 · 조각에 영상 표본이 없으면 변환 전에 던진다', async () => {
    await expect(remuxWindow({ init: new Uint8Array(0), segments: SEGMENTS })).rejects.toThrow();
    await expect(remuxWindow({ init: INIT, segments: [] })).rejects.toThrow();
    await expect(
      remuxWindow({ init: INIT, segments: [{ seq: 300, bytes: new Uint8Array(0) }] }),
    ).rejects.toThrow();
  });
});
