import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import type { SegmentAudioAccess } from '@/api/clipEditor';
import { loadTrackAudio, SegmentsNotReadyError, SegmentUrlExpiredError } from './loadTrackAudio';

// 조각 셋을 받아 트랙마다 「이어진 조각끼리 한 덩어리」로 푼다. 풀기(decodeAudioData)는 가짜다: ADTS 머리에서 표본 빈도
// 번호와 프레임 수를 읽어 돌려준다: 어느 트랙의 무엇을 넘겼는지가 값으로 드러난다.
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
  fetchBytes: vi.fn((_url: string) => Promise.resolve(new Uint8Array(fixture).buffer)),
  decode: vi.fn(fakeDecode),
});

describe('loadTrackAudio', () => {
  it('트랙 번호는 조각 안 소리 트랙의 파일 순서다: 렌더의 [0:a:N]과 같다', async () => {
    const tracks = await loadTrackAudio(source(access(BASE + 2000)), BASE);
    expect(tracks).toHaveLength(3);
    expect(
      tracks.map(
        (pieces) => (pieces[0]!.buffer as unknown as { frequencyIndex: number }).frequencyIndex,
      ),
    ).toEqual([3, 4, 6]); // 48000 · 44100 · 24000
  });

  it('이어진 조각은 트랙마다 한 덩어리로 푼다: 조각마다 따로 풀면 4초마다 이음매 소리가 난다', async () => {
    const src = source(access(BASE + 2000, BASE + 3000, BASE + 4000));
    const tracks = await loadTrackAudio(src, BASE);
    expect(tracks.map((pieces) => pieces.length)).toEqual([1, 1, 1]);
    expect(tracks[0]![0]!.startSeconds).toBe(2);
    expect((tracks[0]![0]!.buffer as unknown as { frames: number }).frames).toBe(48 * 3);
    expect(src.decode).toHaveBeenCalledTimes(3);
  });

  it('사이가 빈 조각(덜 올라온 조각이 빠진 자리)에서 덩어리를 끊는다: 빈 자리는 소리 없이 둔다', async () => {
    const tracks = await loadTrackAudio(
      source(access(BASE + 2000, BASE + 3000, BASE + 8000)),
      BASE,
    );
    expect(tracks[1]!.map((piece) => piece.startSeconds)).toEqual([2, 8]);
  });

  it('주소를 못 받으면 던진다: 화면은 영상 소리로 돌아간다', async () => {
    const src = source(access(BASE));
    src.fetchAccess.mockRejectedValueOnce(new Error('503'));
    await expect(loadTrackAudio(src, BASE)).rejects.toThrow('503');
  });

  it('조각 하나가 실패하면 남은 조각은 받지 않는다: 결과를 버리는데 끝까지 받으면 헛받기다(PR #341 claude)', async () => {
    const src = source(access(...Array.from({ length: 10 }, (_, i) => BASE + i * 1000)));
    // 다른 조각은 한 틱 늦게 온다(망): 실패가 먼저 알려진 뒤에 나머지가 도착한다
    src.fetchBytes.mockImplementation((url: string) =>
      url.endsWith('seg_2.m4s')
        ? Promise.reject(new Error('segment 503'))
        : new Promise<ArrayBuffer>((resolve) =>
            setTimeout(() => resolve(new Uint8Array(fixture).buffer), 0),
          ),
    );
    await expect(loadTrackAudio(src, BASE)).rejects.toThrow('segment 503');
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
    const src = source(first);
    src.fetchAccess.mockResolvedValueOnce(first).mockResolvedValueOnce(second);
    // 2·3번은 옛 주소가 만료됐다(403)
    src.fetchBytes.mockImplementation((url: string) =>
      /seg_[23]\.m4s$/.test(url)
        ? Promise.reject(new SegmentUrlExpiredError())
        : Promise.resolve(new Uint8Array(fixture).buffer),
    );
    const tracks = await loadTrackAudio(src, BASE);
    expect(tracks).toHaveLength(3);
    expect(tracks[0]!.map((piece) => piece.startSeconds)).toEqual([2]);
    expect(src.fetchAccess).toHaveBeenCalledTimes(2);
    expect(src.fetchBytes).toHaveBeenCalledWith('https://s3/seg_2.m4s?v=2');
    expect(src.fetchBytes).toHaveBeenCalledWith('https://s3/seg_3.m4s?v=2');
  });

  it('새 주소로도 만료면 실패다: 끝없이 다시 받지 않는다', async () => {
    const src = source(access(BASE + 2000));
    src.fetchBytes.mockRejectedValue(new SegmentUrlExpiredError());
    await expect(loadTrackAudio(src, BASE)).rejects.toBeInstanceOf(SegmentUrlExpiredError);
    expect(src.fetchAccess).toHaveBeenCalledTimes(2);
    expect(src.fetchBytes).toHaveBeenCalledTimes(2);
  });

  it('받은 조각을 하나라도 못 풀면 실패다: 덜 올라온 조각처럼 그 자리를 무음으로 두지 않는다(PR #341 3차)', async () => {
    const src = source(access(BASE + 2000, BASE + 3000, BASE + 4000));
    // 2번만 깨진 조각(상자가 없다)
    src.fetchBytes.mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith('seg_2.m4s') ? new Uint8Array(16).buffer : new Uint8Array(fixture).buffer,
      ),
    );
    await expect(loadTrackAudio(src, BASE)).rejects.toThrow('segment 2 has no readable audio');
  });

  it('받을 조각이 없으면 빈 목록이다', async () => {
    const src = source(access());
    expect(await loadTrackAudio(src, BASE)).toEqual([]);
  });
});

describe('loadTrackAudio — 미리 판정과 풀기 동시 수', () => {
  it('precheck가 false면 본문을 하나도 받지 않고 SegmentsNotReadyError로 던진다', async () => {
    const src = source(access(BASE + 2000));

    await expect(loadTrackAudio(src, BASE, () => false)).rejects.toBeInstanceOf(
      SegmentsNotReadyError,
    );
    expect(src.fetchBytes).not.toHaveBeenCalled();
    expect(src.decode).not.toHaveBeenCalled();
  });

  it('precheck는 주소 응답의 조각 목록을 받고, true면 그대로 받아 푼다', async () => {
    const src = source(access(BASE + 2000));
    const seen = vi.fn((segments: SegmentAudioAccess['segments']) => segments.length === 1);

    await expect(loadTrackAudio(src, BASE, seen)).resolves.toHaveLength(3);
    expect(seen).toHaveBeenCalledTimes(1);
    expect(src.fetchBytes).toHaveBeenCalledTimes(1);
  });

  it('트랙을 동시에 두 개까지만 풀고, 결과는 끝나는 순서가 아니라 트랙 순서다', async () => {
    let running = 0;
    let peak = 0;
    let started = 0;
    const src = {
      ...source(access(BASE + 2000)),
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

    const tracks = await loadTrackAudio(src, BASE);

    expect(peak).toBe(2);
    expect(tracks.map((track) => (track[0]!.buffer as unknown as { id: number }).id)).toEqual([
      0, 1, 2,
    ]);
  });
});
