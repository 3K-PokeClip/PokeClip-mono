import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import type { SegmentAudioAccess } from '@/api/clipEditor';
import { loadTrackAudio } from './loadTrackAudio';

// 조각 셋을 받아 트랙마다 「이어진 조각끼리 한 덩어리」로 푼다. 풀기(decodeAudioData)는 가짜다: ADTS 머리에서 표본 빈도
// 번호와 프레임 수를 읽어 돌려준다 — 어느 트랙의 무엇을 넘겼는지가 값으로 드러난다.
const fixture = readFileSync(join(__dirname, '__fixtures__', 'three-tracks.m4s'));
const BASE = 1_000_000;

function fakeDecode(adts: Uint8Array) {
  let frames = 0;
  let offset = 0;
  while (offset < adts.length) {
    const at = (i: number) => adts[offset + i]!;
    offset += ((at(3) & 0x03) << 11) | (at(4) << 3) | (at(5) >> 5);
    frames += 1;
  }
  const frequencyIndex = (adts[2]! >> 2) & 0x0f;
  return Promise.resolve({
    frequencyIndex,
    frames,
    duration: frames / 50,
  } as unknown as AudioBuffer);
}

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

const source = (segments: SegmentAudioAccess) => ({
  fetchAccess: vi.fn(() => Promise.resolve(segments)),
  fetchBytes: vi.fn(() => Promise.resolve(new Uint8Array(fixture).buffer)),
  decode: vi.fn(fakeDecode),
});

describe('loadTrackAudio', () => {
  it('트랙 번호는 조각 안 소리 트랙의 파일 순서다 — 렌더의 [0:a:N]과 같다', async () => {
    const tracks = await loadTrackAudio(source(access(BASE + 2000)), BASE);
    expect(tracks).toHaveLength(3);
    expect(
      tracks.map(
        (pieces) => (pieces[0]!.buffer as unknown as { frequencyIndex: number }).frequencyIndex,
      ),
    ).toEqual([3, 4, 6]); // 48000 · 44100 · 24000
  });

  it('이어진 조각은 트랙마다 한 덩어리로 푼다 — 조각마다 따로 풀면 4초마다 이음매 소리가 난다', async () => {
    const src = source(access(BASE + 2000, BASE + 3000, BASE + 4000));
    const tracks = await loadTrackAudio(src, BASE);
    expect(tracks.map((pieces) => pieces.length)).toEqual([1, 1, 1]);
    expect(tracks[0]![0]!.startSeconds).toBe(2);
    expect((tracks[0]![0]!.buffer as unknown as { frames: number }).frames).toBe(48 * 3);
    expect(src.decode).toHaveBeenCalledTimes(3);
  });

  it('사이가 빈 조각(덜 올라온 조각이 빠진 자리)에서 덩어리를 끊는다 — 빈 자리는 소리 없이 둔다', async () => {
    const tracks = await loadTrackAudio(
      source(access(BASE + 2000, BASE + 3000, BASE + 8000)),
      BASE,
    );
    expect(tracks[1]!.map((piece) => piece.startSeconds)).toEqual([2, 8]);
  });

  it('주소를 못 받으면 던진다 — 화면은 영상 소리로 돌아간다', async () => {
    const src = source(access(BASE));
    src.fetchAccess.mockRejectedValueOnce(new Error('503'));
    await expect(loadTrackAudio(src, BASE)).rejects.toThrow('503');
  });

  it('소리 트랙을 못 꺼낸 조각만 있으면 빈 목록이다', async () => {
    const src = source(access(BASE));
    src.fetchBytes.mockResolvedValueOnce(new Uint8Array(16).buffer);
    expect(await loadTrackAudio(src, BASE)).toEqual([]);
  });
});
