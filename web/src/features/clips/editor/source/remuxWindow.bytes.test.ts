import { createHash } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { remuxWindow, sliceParts } from './remuxWindow';
import { fixtureWindow, syntheticWindow } from './remuxWindowFixture';

// 창 원천을 바꿔도(조각을 이어 붙인 사본 → 조각 배열을 가로질러 읽기) 다시 싼 바이트는 한 바이트도 바뀌지 않는다.
// 기준 sha 는 바꾸기 전 판(1d5920e8 — concat + BufferSource)의 출력을 이 시험으로 떠 둔 값이다. 출력 moov 의 생성 시각
// (mvhd · tkhd · mdhd)은 변환하는 순간의 시계라서 시계를 FROZEN_AT 에 묶어 둔다.

const FROZEN_AT = new Date('2026-11-13T00:00:00Z');

const BEFORE = {
  m0: { sha256: '3535c0be123cbecdd2cb0040b2545168bd018242d4ecd61c04c6d192e5d246c5', size: 426_936 },
  synthetic50: {
    sha256: '2afb9ed96bb7bbe44ef3d7afd9b9d34ff0143232cc88ddf108d4d9fe784ad628',
    size: 7_094_696,
  },
};

async function digest(blob: Blob): Promise<{ sha256: string; size: number }> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  return { sha256: createHash('sha256').update(bytes).digest('hex'), size: bytes.byteLength };
}

describe('remuxWindow: 다시 싼 바이트는 바꾸기 전 판과 같다(실제 mediabunny)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(FROZEN_AT);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('M0 창(조각 셋)', async () => {
    const { blob } = await remuxWindow(fixtureWindow());

    expect(await digest(blob)).toEqual(BEFORE.m0);
  });

  it('합성 창(조각 50)', async () => {
    const { blob, segments } = await remuxWindow(syntheticWindow(50));

    expect(segments).toHaveLength(50);
    expect(await digest(blob)).toEqual(BEFORE.synthetic50);
  });
});

describe('sliceParts: 이은 파일의 범위를 조각 subarray 로 준다', () => {
  const a = new Uint8Array([0, 1, 2]);
  const b = new Uint8Array([3, 4]);
  const c = new Uint8Array([5, 6, 7, 8]);
  const values = (chunks: Uint8Array[]) => chunks.map((chunk) => [...chunk]);

  it('한 조각 안이면 그 조각의 subarray 하나다 — 복사하지 않는다', () => {
    const [chunk, ...rest] = sliceParts([a, b, c], 5, 8);

    expect(rest).toEqual([]);
    expect([...chunk!]).toEqual([5, 6, 7]);
    expect(chunk!.buffer).toBe(c.buffer);
  });

  it('조각 경계를 가로지르면 조각마다 하나씩 — 시작이 한 조각 끝, 끝이 다음 조각 안', () => {
    const chunks = sliceParts([a, b, c], 2, 6);

    expect(values(chunks)).toEqual([[2], [3, 4], [5]]);
    expect(chunks.map((chunk) => chunk.buffer)).toEqual([a.buffer, b.buffer, c.buffer]);
  });

  it('경계에서 시작 · 끝나면 옆 조각을 끌어들이지 않는다', () => {
    expect(values(sliceParts([a, b, c], 3, 5))).toEqual([[3, 4]]);
    expect(values(sliceParts([a, b, c], 0, 3))).toEqual([[0, 1, 2]]);
    expect(values(sliceParts([a, b, c], 5, 9))).toEqual([[5, 6, 7, 8]]);
  });

  it('파일 전체 · 빈 조각은 건너뛴다 · init 하나뿐인 창', () => {
    const empty = new Uint8Array(0);

    expect(values(sliceParts([a, b, c], 0, 9))).toEqual([
      [0, 1, 2],
      [3, 4],
      [5, 6, 7, 8],
    ]);
    expect(values(sliceParts([a, empty, b], 2, 4))).toEqual([[2], [3]]);
    expect(values(sliceParts([a], 1, 3))).toEqual([[1, 2]]);
  });

  it('subarray 를 조각 안 위치로 자른다 — byteOffset 이 0 이 아닌 조각도', () => {
    const backing = new Uint8Array([9, 9, 10, 11, 12, 9]);
    const inner = backing.subarray(2, 5);

    const [chunk] = sliceParts([a, inner], 4, 6);

    expect([...chunk!]).toEqual([11, 12]);
    expect(chunk!.buffer).toBe(backing.buffer);
  });

  it('범위가 파일 밖이거나 뒤집혔으면 던진다', () => {
    expect(() => sliceParts([a, b], 4, 6)).toThrow(RangeError);
    expect(() => sliceParts([a, b], -1, 2)).toThrow(RangeError);
    expect(() => sliceParts([a, b], 3, 2)).toThrow(RangeError);
  });
});
