import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { boxes, child } from '@/lib/fmp4Boxes';
import type { WindowBytes } from './fetchWindowBytes';

// 창 재다중화 시험 전용 픽스처(M0 — testdata/README.md). 조각 셋(seq 300–302)과, 큰 창을 흉내 내는 합성 창을 준다.
// 합성 창은 seq 300 조각을 n 번 되풀이하되 트랙마다 tfdt 를 「seq 301 − seq 300」 만큼씩 밀어 시각이 이어지게 한다.
// 세 조각은 tfdt 만 다르다(cmp: 다른 바이트 24개 = moof 8개의 tfdt 아래 3바이트) — 그래서 tfdt 만 고쳐도 같은 꼴이다.

const TESTDATA = join(__dirname, 'testdata');
const read = (name: string) => new Uint8Array(readFileSync(join(TESTDATA, name)));
const segmentName = (seq: number) => `seg_${String(seq).padStart(7, '0')}.m4s`;

/** M0 창: init + 조각 300 · 301 · 302. 부를 때마다 새 바이트다 */
export function fixtureWindow(): WindowBytes {
  return {
    init: read('init.mp4'),
    segments: [300, 301, 302].map((seq) => ({ seq, bytes: read(segmentName(seq)) })),
  };
}

/** 조각 count 개짜리 합성 창(seq 300 부터). 조각마다 따로 할당한 바이트다 */
export function syntheticWindow(count: number): WindowBytes {
  const first = read(segmentName(300));
  const step = tfdtStep(first, read(segmentName(301)));
  return {
    init: read('init.mp4'),
    segments: Array.from({ length: count }, (_, k) => ({
      seq: 300 + k,
      bytes: shiftTfdt(first, (trackId) => k * (step.get(trackId) ?? 0)),
    })),
  };
}

/** 트랙마다 두 조각의 첫 tfdt 차 */
function tfdtStep(a: Uint8Array, b: Uint8Array): Map<number, number> {
  const firstOf = (bytes: Uint8Array) => {
    const seen = new Map<number, number>();
    for (const { trackId, value } of tfdts(bytes)) {
      if (!seen.has(trackId)) seen.set(trackId, value);
    }
    return seen;
  };
  const before = firstOf(a);
  const after = firstOf(b);
  return new Map([...before].map(([trackId, value]) => [trackId, after.get(trackId)! - value]));
}

/** 조각 사본 — 모든 traf 의 tfdt 에 shift(trackId) 를 더한다 */
function shiftTfdt(bytes: Uint8Array, shift: (trackId: number) => number): Uint8Array {
  const out = bytes.slice();
  const view = new DataView(out.buffer);
  for (const { trackId, offset, version, value } of tfdts(out)) {
    const next = value + shift(trackId);
    if (version === 1) view.setBigUint64(offset, BigInt(next));
    else view.setUint32(offset, next);
  }
  return out;
}

function* tfdts(
  bytes: Uint8Array,
): Generator<{ trackId: number; offset: number; version: number; value: number }> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (const moof of boxes(view, 0, bytes.byteLength)) {
    if (moof.type !== 'moof') continue;
    for (const traf of boxes(view, moof.start, moof.end)) {
      if (traf.type !== 'traf') continue;
      const tfhd = child(view, traf, 'tfhd');
      const tfdt = child(view, traf, 'tfdt');
      if (tfhd === undefined || tfdt === undefined) continue;
      const version = view.getUint8(tfdt.start);
      const offset = tfdt.start + 4;
      const value = version === 1 ? Number(view.getBigUint64(offset)) : view.getUint32(offset);
      yield { trackId: view.getUint32(tfhd.start + 4), offset, version, value };
    }
  }
}
