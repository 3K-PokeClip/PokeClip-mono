import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  boxes,
  child,
  path,
  readFirstSampledTraf,
  readTrackSamples,
  readTrackSpans,
  readTrackStart,
  readVideoTrack,
  type Box,
} from './fmp4Boxes';

// 실물은 미디어 서버 재생 시험이 쓰는 녹화 조각이다(영상 1 + 소리 1 · 영상 1 + 소리 6, B 프레임 있음).
// 기대값은 이 코드가 아니라 mp4dump --verbosity 3 출력에서 옮겼다(tfdt · 첫 표본 cto · 표본 길이 합).
const TESTDATA = join(__dirname, '..', '..', '..', 'media', 'internal', 'playback', 'testdata');
const segment4s = new Uint8Array(readFileSync(join(TESTDATA, 'segment_4s.mp4')));
const segment1v6a = new Uint8Array(readFileSync(join(TESTDATA, 'segment_1v6a.mp4')));
// ③ 재포장본(M0 — 저장소 생산자가 segment_4s 를 pos 1234.5초에 도장한 것 · 만든 법은 그 폴더 README)
const REMUXED = join(__dirname, '..', 'features', 'clips', 'editor', 'source', 'testdata');
const remuxedInit = new Uint8Array(readFileSync(join(REMUXED, 'init.mp4')));
const remuxedSegment = new Uint8Array(readFileSync(join(REMUXED, 'seg_0000300.m4s')));

// --- 합성 바이트: 실물에 없는 모양(음수 cto · 64비트 tfdt · 기본값 칸)을 만든다 ---

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function u32(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value);
  return out;
}

function i32(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setInt32(0, value);
  return out;
}

function u64(value: bigint): Uint8Array {
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, value);
  return out;
}

function ascii(text: string): Uint8Array {
  return Uint8Array.from(text, (ch) => ch.charCodeAt(0));
}

function box(type: string, ...payload: Uint8Array[]): Uint8Array {
  const body = concat(...payload);
  return concat(u32(body.length + 8), ascii(type), body);
}

function fullBox(
  type: string,
  version: number,
  flags: number,
  ...payload: Uint8Array[]
): Uint8Array {
  return box(type, u32(((version << 24) | flags) >>> 0), ...payload);
}

/** tkhd · mdhd 는 버전 1 이면 시각 칸이 64비트라 id · timescale 자리가 밀린다 */
function trak(trackId: number, handler: string, timescale: number, version = 0): Uint8Array {
  const times = version === 1 ? [u64(0n), u64(0n)] : [u32(0), u32(0)];
  return box(
    'trak',
    fullBox('tkhd', version, 3, ...times, u32(trackId), u32(0)),
    box(
      'mdia',
      fullBox('mdhd', version, 0, ...times, u32(timescale), u32(0)),
      fullBox('hdlr', 0, 0, u32(0), ascii(handler), u32(0), u32(0), u32(0)),
    ),
  );
}

function trex(trackId: number, defaultDuration: number): Uint8Array {
  return fullBox('trex', 0, 0, u32(trackId), u32(1), u32(defaultDuration), u32(0), u32(0));
}

function init(...children: Uint8Array[]): Uint8Array {
  return concat(box('ftyp', ascii('isom'), u32(0)), box('moov', ...children));
}

const DEFAULT_BASE_IS_MOOF = 0x020000;

function tfhd(trackId: number, defaultDuration?: number): Uint8Array {
  if (defaultDuration === undefined) return fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF, u32(trackId));
  return fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF | 0x08, u32(trackId), u32(defaultDuration));
}

function tfdt(version: 0 | 1, baseDecodeTime: bigint): Uint8Array {
  return fullBox(
    'tfdt',
    version,
    0,
    version === 1 ? u64(baseDecodeTime) : u32(Number(baseDecodeTime)),
  );
}

interface Sample {
  duration?: number;
  size?: number;
  flags?: number;
  cto?: number;
}

/** 표본 칸은 flags 가 켠 것만 순서대로 적는다(0x100 길이 · 0x200 크기 · 0x400 표지 · 0x800 cto) */
function trun(version: 0 | 1, flags: number, samples: Sample[]): Uint8Array {
  const head = [u32(samples.length)];
  if (flags & 0x001) head.push(i32(0)); // data_offset
  if (flags & 0x004) head.push(u32(0x02000000)); // first_sample_flags
  const fields = samples.flatMap((sample) => {
    const out: Uint8Array[] = [];
    if (flags & 0x100) out.push(u32(sample.duration ?? 0));
    if (flags & 0x200) out.push(u32(sample.size ?? 0));
    if (flags & 0x400) out.push(u32(sample.flags ?? 0));
    if (flags & 0x800) out.push(version === 1 ? i32(sample.cto ?? 0) : u32(sample.cto ?? 0));
    return out;
  });
  return fullBox('trun', version, flags, ...head, ...fields);
}

function moof(...trafs: Uint8Array[]): Uint8Array {
  return box('moof', fullBox('mfhd', 0, 0, u32(1)), ...trafs);
}

function traf(...children: Uint8Array[]): Uint8Array {
  return box('traf', ...children);
}

/** 영상 1 · 첫 표본 cto 하나뿐인 조각(moof + 빈 mdat) */
function videoFragment(trackId: number, decodeTime: Uint8Array, run: Uint8Array): Uint8Array {
  return concat(moof(traf(tfhd(trackId), decodeTime, run)), box('mdat'));
}

function viewOf(bytes: Uint8Array): DataView {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

function topLevel(bytes: Uint8Array): Box[] {
  return [...boxes(viewOf(bytes), 0, bytes.byteLength)];
}

describe('boxes: 상자를 머리 크기대로 차례로 내준다', () => {
  it('보통 상자(32비트 크기)는 머리 8바이트 뒤가 내용이다', () => {
    const bytes = concat(box('ftyp', u32(7)), box('free'));

    expect(topLevel(bytes)).toEqual([
      { type: 'ftyp', boxStart: 0, start: 8, end: 12 },
      { type: 'free', boxStart: 12, start: 20, end: 20 },
    ]);
  });

  it('size 1 이면 뒤 64비트가 크기이고 머리는 16바이트다', () => {
    const bytes = concat(u32(1), ascii('mdat'), u64(20n), u32(0xabcdef), box('free'));

    expect(topLevel(bytes)).toEqual([
      { type: 'mdat', boxStart: 0, start: 16, end: 20 },
      { type: 'free', boxStart: 20, start: 28, end: 28 },
    ]);
  });

  it('size 0 이면 범위 끝까지가 그 상자다', () => {
    const bytes = concat(box('ftyp', u32(7)), u32(0), ascii('mdat'), u32(1), u32(2));

    expect(topLevel(bytes)).toEqual([
      { type: 'ftyp', boxStart: 0, start: 8, end: 12 },
      { type: 'mdat', boxStart: 12, start: 20, end: 28 },
    ]);
  });

  it('범위를 넘는 상자(잘린 바이트)에서 멈추고 던지지 않는다', () => {
    const whole = concat(box('ftyp', u32(7)), box('moov', u32(1), u32(2)));
    const cut = whole.subarray(0, whole.length - 3);

    expect(topLevel(cut)).toEqual([{ type: 'ftyp', boxStart: 0, start: 8, end: 12 }]);
  });

  it('size 가 머리보다 작거나 64비트 크기가 잘렸으면 멈춘다', () => {
    expect(topLevel(concat(u32(4), ascii('free'), u32(0)))).toEqual([]);
    expect(topLevel(concat(u32(1), ascii('mdat'), u32(0)))).toEqual([]);
  });

  it('child · path 는 바로 아래 · 몇 층 아래 상자를 찾고 없으면 undefined 다', () => {
    const bytes = box('moov', box('trak', box('mdia', box('hdlr', u32(9)))));
    const view = viewOf(bytes);
    const moov = topLevel(bytes)[0]!;

    expect(child(view, moov, 'trak')?.boxStart).toBe(8);
    expect(path(view, moov, 'trak', 'mdia', 'hdlr')).toEqual({
      type: 'hdlr',
      boxStart: 24,
      start: 32,
      end: 36,
    });
    expect(child(view, moov, 'mvex')).toBeUndefined();
    expect(path(view, moov, 'trak', 'minf', 'stbl')).toBeUndefined();
  });
});

describe('readVideoTrack: init 에서 영상 트랙 번호와 timescale 을 읽는다', () => {
  it('실물 조각의 영상 트랙(영상 1 + 소리 1 · 영상 1 + 소리 6)', () => {
    expect(readVideoTrack(segment4s)).toEqual({ trackId: 1, timescale: 90000 });
    expect(readVideoTrack(segment1v6a)).toEqual({ trackId: 1, timescale: 90000 });
  });

  it('소리 트랙 뒤에 있어도 hdlr 이 vide 인 트랙을 고른다', () => {
    const bytes = init(trak(1, 'soun', 48000), trak(7, 'vide', 90000));

    expect(readVideoTrack(bytes)).toEqual({ trackId: 7, timescale: 90000 });
  });

  it('tkhd · mdhd 버전 1(64비트 시각)이면 밀린 자리에서 읽는다', () => {
    expect(readVideoTrack(init(trak(3, 'vide', 30000, 1)))).toEqual({
      trackId: 3,
      timescale: 30000,
    });
  });

  it('영상 트랙이 없거나 · timescale 이 0 이거나 · 잘렸거나 · 비었으면 null 이다', () => {
    expect(readVideoTrack(init(trak(1, 'soun', 48000)))).toBeNull();
    expect(readVideoTrack(init(trak(1, 'vide', 0)))).toBeNull();
    expect(readVideoTrack(segment4s.subarray(0, 200))).toBeNull();
    expect(readVideoTrack(new Uint8Array(0))).toBeNull();
  });
});

describe('readTrackStart: 조각에서 그 트랙 첫 traf 의 tfdt 와 첫 표본 cto 를 읽는다', () => {
  it('실물 조각의 첫 moof: 영상 tfdt 1099 · 첫 표본 cto 6012(B 프레임)', () => {
    expect(readTrackStart(segment4s, 1)).toEqual({ baseDecodeTime: 1099, firstCtoTicks: 6012 });
    expect(readTrackStart(segment1v6a, 1)).toEqual({ baseDecodeTime: 8136, firstCtoTicks: 3000 });
  });

  it('실물 조각의 둘째 moof 부터 자른 바이트(보기의 byteOffset 이 0 이 아님)', () => {
    const second = topLevel(segment4s).filter((b) => b.type === 'moof')[1]!;

    expect(readTrackStart(segment4s.subarray(second.boxStart), 1)).toEqual({
      baseDecodeTime: 91126,
      firstCtoTicks: 3015,
    });
  });

  it('trun 에 0x800 이 없으면 cto 는 0 이다(실물 소리 트랙 · 합성)', () => {
    expect(readTrackStart(segment4s, 2)).toEqual({ baseDecodeTime: 0, firstCtoTicks: 0 });
    const bytes = videoFragment(1, tfdt(0, 500n), trun(0, 0x301, [{ duration: 3000, size: 1 }]));

    expect(readTrackStart(bytes, 1)).toEqual({ baseDecodeTime: 500, firstCtoTicks: 0 });
  });

  it('tfdt v0 은 32비트 무부호다', () => {
    const bytes = videoFragment(1, tfdt(0, 0xfffffff0n), trun(0, 0, [{}]));

    expect(readTrackStart(bytes, 1)?.baseDecodeTime).toBe(0xfffffff0);
  });

  it('tfdt v1 은 64비트다: 상위 32비트까지 읽는다', () => {
    const decodeTime = 5n * 2n ** 32n + 7n;
    const bytes = videoFragment(1, tfdt(1, decodeTime), trun(0, 0, [{}]));

    expect(readTrackStart(bytes, 1)?.baseDecodeTime).toBe(Number(decodeTime));
  });

  it('tfdt v1 이 안전 정수(2^53 − 1)를 넘으면 판독 실패로 null 이다', () => {
    const safe = videoFragment(1, tfdt(1, 2n ** 53n - 1n), trun(0, 0, [{}]));
    const exact = videoFragment(1, tfdt(1, 2n ** 53n), trun(0, 0, [{}]));
    const unsafe = videoFragment(1, tfdt(1, 2n ** 53n + 1n), trun(0, 0, [{}]));

    expect(readTrackStart(safe, 1)?.baseDecodeTime).toBe(Number.MAX_SAFE_INTEGER);
    expect(readTrackStart(exact, 1)).toBeNull();
    expect(readTrackStart(unsafe, 1)).toBeNull();
  });

  it('trun v0 의 cto 는 무부호다', () => {
    const bytes = videoFragment(1, tfdt(0, 0n), trun(0, 0x800, [{ cto: 0xfffffa24 }]));

    expect(readTrackStart(bytes, 1)?.firstCtoTicks).toBe(0xfffffa24);
  });

  it('trun v1 의 cto 는 부호 있다(음수 cto)', () => {
    const bytes = videoFragment(1, tfdt(0, 0n), trun(1, 0x800, [{ cto: -1500 }]));

    expect(readTrackStart(bytes, 1)?.firstCtoTicks).toBe(-1500);
  });

  // 0x004 first_sample_flags 와 0x400 sample_flags 는 함께 켜지 않는다(ISOBMFF 8.8.8)
  it.each([
    { name: '0x001 · 0x004 · 0x100 · 0x200', flags: 0xb05 },
    { name: '0x001 · 0x100 · 0x200 · 0x400', flags: 0xf01 },
  ])('cto 앞의 칸($name)을 건너뛰고 첫 표본 것만 읽는다', ({ flags }) => {
    const run = trun(1, flags, [
      { duration: 3000, size: 900, flags: 0x10000, cto: 6000 },
      { duration: 3000, size: 400, flags: 0x10000, cto: -3000 },
    ]);

    expect(readTrackStart(videoFragment(1, tfdt(1, 42n), run), 1)).toEqual({
      baseDecodeTime: 42,
      firstCtoTicks: 6000,
    });
  });

  it('표본이 없는 trun 은 건너뛰고 표본이 있는 첫 trun 의 cto 를 읽는다', () => {
    const empty = trun(1, 0x800, []);
    const bytes = concat(
      moof(traf(tfhd(1), tfdt(0, 0n), empty, trun(1, 0x800, [{ cto: -1500 }]))),
      box('mdat'),
    );
    const allEmpty = concat(moof(traf(tfhd(1), tfdt(0, 0n), empty, empty)), box('mdat'));

    expect(readTrackStart(bytes, 1)?.firstCtoTicks).toBe(-1500);
    expect(readTrackStart(allEmpty, 1)?.firstCtoTicks).toBe(0);
  });

  it('그 트랙의 첫 traf 를 고른다: 다른 트랙 traf 가 앞에 있어도', () => {
    const bytes = concat(
      moof(
        traf(tfhd(2), tfdt(0, 11n), trun(0, 0x800, [{ cto: 1 }])),
        traf(tfhd(1), tfdt(0, 22n), trun(0, 0x800, [{ cto: 2 }])),
      ),
      moof(traf(tfhd(1), tfdt(0, 33n), trun(0, 0x800, [{ cto: 3 }]))),
    );

    expect(readTrackStart(bytes, 1)).toEqual({ baseDecodeTime: 22, firstCtoTicks: 2 });
  });

  it('트랙이 없거나 · tfdt 가 없거나 · 잘렸으면 null 이다(던지지 않는다)', () => {
    const noTfdt = concat(moof(traf(tfhd(1), trun(0, 0, [{}]))), box('mdat'));
    const cutRun = videoFragment(1, tfdt(0, 0n), trun(1, 0x800, [{ cto: 9 }]));
    // trun 상자 크기를 4 줄여 cto 칸이 상자 밖에 놓이게 한다(바이트 전체는 남아 있다)
    const shortRun = cutRun.slice();
    const runAt = topLevel(cutRun)[0]!.end - 20;
    new DataView(shortRun.buffer).setUint32(runAt, 16);

    expect(readTrackStart(segment4s, 9)).toBeNull();
    expect(readTrackStart(noTfdt, 1)).toBeNull();
    expect(readTrackStart(segment4s.subarray(0, 1300), 1)).toBeNull();
    expect(readTrackStart(shortRun, 1)).toBeNull();
    expect(readTrackStart(new Uint8Array(0), 1)).toBeNull();
  });
});

describe('readTrackSamples: 조각 안 그 트랙의 표본마다 DTS · PTS · 길이를 디코드 순서로 읽는다', () => {
  it('실물 ③ 조각: 영상 120 표본 · 첫 셋은 mp4dump 값(B 프레임이라 PTS 가 DTS 순서와 다르다)', () => {
    const video = readTrackSamples(remuxedInit, remuxedSegment, 1);

    expect(video).toHaveLength(120);
    expect(video?.slice(0, 3)).toEqual([
      { dts: 111106099, pts: 111112111, duration: 2997 },
      { dts: 111109096, pts: 111124081, duration: 3015 },
      { dts: 111112111, pts: 111118141, duration: 2970 },
    ]);
  });

  it('실물 ③ 조각: 소리 172 표본은 PTS = DTS 이고 첫 표본이 tfdt 54441450 이다', () => {
    const audio = readTrackSamples(remuxedInit, remuxedSegment, 2);

    expect(audio).toHaveLength(172);
    expect(audio?.[0]).toEqual({ dts: 54441450, pts: 54441450, duration: 1015 });
  });

  it('traf 마다 그 tfdt 에서 다시 센다(moof 둘)', () => {
    const bytes = concat(
      moof(traf(tfhd(1), tfdt(0, 100n), trun(0, 0x100, [{ duration: 10 }, { duration: 20 }]))),
      moof(traf(tfhd(1), tfdt(0, 500n), trun(0, 0x100, [{ duration: 30 }]))),
    );

    expect(readTrackSamples(bytes, bytes, 1)).toEqual([
      { dts: 100, pts: 100, duration: 10 },
      { dts: 110, pts: 110, duration: 20 },
      { dts: 500, pts: 500, duration: 30 },
    ]);
  });

  it('trun v1 의 cto 는 부호 있다: 음수 cto 면 PTS 가 DTS 보다 앞이다', () => {
    const run = trun(1, 0x900, [
      { duration: 3000, cto: 3000 },
      { duration: 3000, cto: -1500 },
    ]);

    expect(readTrackSamples(new Uint8Array(0), videoFragment(1, tfdt(0, 9000n), run), 1)).toEqual([
      { dts: 9000, pts: 12000, duration: 3000 },
      { dts: 12000, pts: 10500, duration: 3000 },
    ]);
  });

  it('trun v0 의 cto 는 무부호다', () => {
    const run = trun(0, 0x900, [{ duration: 1, cto: 0xfffffa24 }]);

    expect(readTrackSamples(new Uint8Array(0), videoFragment(1, tfdt(0, 0n), run), 1)).toEqual([
      { dts: 0, pts: 0xfffffa24, duration: 1 },
    ]);
  });

  it('길이 칸이 없으면 tfhd 기본값, 그것도 없으면 init 의 trex 기본값이다', () => {
    const header = init(trak(1, 'vide', 90000), box('mvex', trex(1, 3000)));
    const byTfhd = concat(moof(traf(tfhd(1, 1500), tfdt(0, 0n), trun(0, 0, [{}, {}]))));
    const byTrex = concat(moof(traf(tfhd(1), tfdt(0, 0n), trun(0, 0, [{}, {}]))));

    expect(readTrackSamples(header, byTfhd, 1)?.map((s) => s.dts)).toEqual([0, 1500]);
    expect(readTrackSamples(header, byTrex, 1)?.map((s) => s.dts)).toEqual([0, 3000]);
  });

  it('표본 없는 traf 는 tfdt 가 없어도 지나간다 · 다른 트랙 traf 는 세지 않는다', () => {
    const bytes = concat(
      moof(traf(tfhd(1), trun(0, 0, [])), traf(tfhd(2), tfdt(0, 7n), trun(0, 0, [{}]))),
      moof(traf(tfhd(1), tfdt(0, 40n), trun(0, 0x100, [{ duration: 5 }]))),
    );

    expect(readTrackSamples(bytes, bytes, 1)).toEqual([{ dts: 40, pts: 40, duration: 5 }]);
    expect(readTrackSamples(bytes, bytes, 9)).toEqual([]);
  });

  it('표본 있는 traf 에 tfdt 가 없거나 · trun 이 잘렸으면 null 이다(던지지 않는다)', () => {
    const noTfdt = concat(moof(traf(tfhd(1), trun(0, 0, [{}]))), box('mdat'));
    const full = videoFragment(1, tfdt(0, 0n), trun(1, 0x900, [{ duration: 1, cto: 9 }]));
    // trun 상자 크기를 4 줄여 cto 칸이 상자 밖에 놓이게 한다
    const shortRun = full.slice();
    new DataView(shortRun.buffer).setUint32(topLevel(full)[0]!.end - 24, 20);

    expect(readTrackSamples(noTfdt, noTfdt, 1)).toBeNull();
    expect(readTrackSamples(shortRun, shortRun, 1)).toBeNull();
  });
});

describe('readFirstSampledTraf: 그 트랙의 표본 있는 첫 traf 의 표본을 읽는다', () => {
  it('표본 없는 traf 가 먼저 와도 건너뛰고 표본 있는 traf 를 고른다', () => {
    const bytes = concat(
      moof(traf(tfhd(1), tfdt(0, 10n), trun(0, 0x100, []))),
      moof(
        traf(tfhd(1), tfdt(0, 70n), trun(1, 0x900, [{ duration: 4, cto: -2 }, { duration: 4 }])),
      ),
      moof(traf(tfhd(1), tfdt(0, 90n), trun(0, 0x100, [{ duration: 4 }]))),
    );

    expect(readFirstSampledTraf(bytes, bytes, 1)).toEqual([
      { dts: 70, pts: 68, duration: 4 },
      { dts: 74, pts: 74, duration: 4 },
    ]);
  });

  it('실물 ② 파일(영상 1 + 소리 6): 영상 첫 traf 는 tfdt 8136 · 소리는 0 에서 시작한다', () => {
    const video = readFirstSampledTraf(segment1v6a, segment1v6a, 1);
    const audio = readFirstSampledTraf(segment1v6a, segment1v6a, 2);

    expect(video?.[0]?.dts).toBe(8136);
    expect(video?.[0]?.pts).toBe(8136 + 3000);
    expect(video).toHaveLength(5);
    expect(audio?.[0]).toEqual({ dts: 0, pts: 0, duration: 1024 });
  });

  it('표본 있는 traf 가 없으면 빈 목록 · 모양을 모르면 null 이다', () => {
    const empty = concat(moof(traf(tfhd(1), tfdt(0, 0n), trun(0, 0, []))));
    const noTfdt = concat(moof(traf(tfhd(1), trun(0, 0, [{}]))));

    expect(readFirstSampledTraf(empty, empty, 1)).toEqual([]);
    expect(readFirstSampledTraf(noTfdt, noTfdt, 1)).toBeNull();
  });
});

describe('readTrackSpans: 트랙마다 첫 tfdt 와 표본 길이 합을 읽는다', () => {
  it('실물 조각(영상 1 + 소리 1): moov 의 트랙 순서대로', () => {
    expect(readTrackSpans(segment4s)).toEqual([
      { trackId: 1, handler: 'vide', timescale: 90000, startTicks: 1099, durationTicks: 360018 },
      { trackId: 2, handler: 'soun', timescale: 44100, startTicks: 0, durationTicks: 176136 },
    ]);
  });

  it('실물 조각(영상 1 + 소리 6)', () => {
    const audio = [2, 3, 4, 5, 6, 7].map((trackId) => ({
      trackId,
      handler: 'soun',
      timescale: 44100,
      startTicks: 0,
      durationTicks: 6144,
    }));

    expect(readTrackSpans(segment1v6a)).toEqual([
      { trackId: 1, handler: 'vide', timescale: 90000, startTicks: 8136, durationTicks: 15000 },
      ...audio,
    ]);
  });

  it('표본 길이가 trun 에 없으면 tfhd 기본값, 그것도 없으면 trex 기본값이다', () => {
    const bytes = concat(
      init(
        trak(1, 'vide', 90000),
        trak(2, 'soun', 48000),
        box('mvex', trex(1, 3000), trex(2, 1024)),
      ),
      moof(
        traf(tfhd(1), tfdt(0, 100n), trun(0, 0x100, [{ duration: 2000 }, { duration: 4000 }])),
        traf(tfhd(2, 960), tfdt(1, 50n), trun(0, 0, [{}, {}, {}])),
      ),
      moof(
        traf(tfhd(1), tfdt(0, 6100n), trun(0, 0, [{}, {}])),
        traf(tfhd(2), tfdt(1, 2930n), trun(0, 0, [{}])),
      ),
    );

    expect(readTrackSpans(bytes)).toEqual([
      { trackId: 1, handler: 'vide', timescale: 90000, startTicks: 100, durationTicks: 12000 },
      { trackId: 2, handler: 'soun', timescale: 48000, startTicks: 50, durationTicks: 3904 },
    ]);
  });

  // 0x01 base_data_offset(8) · 0x02 sample_description_index(4) 칸 값은 기본 길이 960 과 다르게 둔다
  it.each([
    { name: '0x01', flags: 0x09, before: [u64(7n)] },
    { name: '0x02', flags: 0x0a, before: [u32(1)] },
    { name: '0x01 · 0x02', flags: 0x0b, before: [u64(7n), u32(1)] },
  ])('tfhd 기본 길이는 앞 칸($name)만큼 밀린 자리에서 읽는다', ({ flags, before }) => {
    const header = fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF | flags, u32(1), ...before, u32(960));
    const bytes = concat(
      init(trak(1, 'soun', 48000)),
      moof(traf(header, tfdt(0, 0n), trun(0, 0, [{}, {}]))),
    );

    expect(readTrackSpans(bytes)[0]?.durationTicks).toBe(1920);
  });

  it('조각에 없는 트랙은 빠진다', () => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 48000)),
      moof(traf(tfhd(1, 3000), tfdt(0, 0n), trun(0, 0, [{}]))),
    );

    expect(readTrackSpans(bytes).map((span) => span.trackId)).toEqual([1]);
  });

  it('moov 가 없거나 · 첫 traf 에 tfdt 가 없거나 · 잘렸으면 빈 목록이다', () => {
    const noTfdt = concat(
      init(trak(1, 'vide', 90000)),
      moof(traf(tfhd(1, 3000), trun(0, 0, [{}]))),
    );

    expect(readTrackSpans(moof(traf(tfhd(1), tfdt(0, 0n))))).toEqual([]);
    expect(readTrackSpans(noTfdt)).toEqual([]);
    expect(readTrackSpans(segment4s.subarray(0, 600))).toEqual([]);
    expect(readTrackSpans(new Uint8Array(0))).toEqual([]);
  });
});

// --- 상자 끝: 버퍼는 남았지만 개별 상자가 짧으면 다음 상자의 바이트를 제 칸 값으로 읽지 않는다 ---

const TKHD = fullBox('tkhd', 0, 3, u32(0), u32(0), u32(1), u32(0));
const MDHD = fullBox('mdhd', 0, 0, u32(0), u32(0), u32(90000), u32(0));
const HDLR = fullBox('hdlr', 0, 0, u32(0), ascii('vide'), u32(0), u32(0), u32(0));

/** 짧은 상자 뒤에 둔 상자의 바이트가 그 칸 자리에 오도록 짠 영상 trak 들(옛 판독은 괄호 안 값을 읽었다) */
const SHORT_TRAKS = [
  {
    // cx 재현 (나): tkhd 내용이 머리 4바이트뿐 → track_ID 자리에 뒤 free 의 내용 123
    name: 'tkhd 짧음(trackId 123)',
    trackId: 123,
    trak: box('trak', fullBox('tkhd', 0, 3), box('free', u32(123)), box('mdia', MDHD, HDLR)),
  },
  {
    name: 'mdhd 짧음(timescale 90000)',
    trackId: 1,
    trak: box('trak', TKHD, box('mdia', fullBox('mdhd', 0, 0), box('free', u32(90000)), HDLR)),
  },
  {
    name: "hdlr 짧음(handler 'vide')",
    trackId: 1,
    trak: box('trak', TKHD, box('mdia', MDHD, fullBox('hdlr', 0, 0), box('vide'))),
  },
];

describe('상자 끝: 짧은 trak 머리 상자는 판독 실패다', () => {
  it.each(SHORT_TRAKS)('readVideoTrack — $name 이면 null', ({ trak }) => {
    expect(readVideoTrack(init(trak))).toBeNull();
  });

  it.each(SHORT_TRAKS)('readTrackSpans — $name 이면 빈 목록', ({ trak, trackId }) => {
    const bytes = concat(
      init(trak),
      moof(traf(tfhd(trackId), tfdt(0, 0n), trun(0, 0x100, [{ duration: 10 }]))),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });

  it('readTrackSpans — trex 짧음(기본 길이 자리에 뒤 free 의 크기 8)이면 빈 목록', () => {
    const shortTrex = fullBox('trex', 0, 0, u32(1), u32(1));
    const bytes = concat(
      init(trak(1, 'vide', 90000), box('mvex', shortTrex, box('free'))),
      moof(traf(tfhd(1), tfdt(0, 0n), trun(0, 0, [{}, {}]))),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });
});

/** tfhd 플래그가 default_sample_duration(0x08)을 켰는데 그 칸이 없다(앞 선택 칸은 있다) */
const SHORT_TFHDS = [
  // cx 재현 (가): 내용이 track_ID 까지뿐 → 기본 길이 자리에 다음 tfdt 의 크기 16
  { name: '0x08', tfhd: fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF | 0x08, u32(1)) },
  { name: '0x01 · 0x08', tfhd: fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF | 0x09, u32(1), u64(0n)) },
  { name: '0x02 · 0x08', tfhd: fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF | 0x0a, u32(1), u32(1)) },
  {
    name: '0x01 · 0x02 · 0x08',
    tfhd: fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF | 0x0b, u32(1), u64(0n), u32(1)),
  },
];

describe('상자 끝: 짧은 tfhd 는 판독 실패다', () => {
  it.each(SHORT_TFHDS)('readTrackSpans — $name 칸 없음이면 빈 목록', ({ tfhd: header }) => {
    const bytes = concat(
      init(trak(1, 'vide', 90000)),
      moof(traf(header, tfdt(0, 0n), trun(0, 0, [{}, {}]))),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });

  it.each(SHORT_TFHDS)('readTrackStart — $name 칸 없음이면 null', ({ tfhd: header }) => {
    const bytes = concat(moof(traf(header, tfdt(0, 500n), trun(0, 0, [{}]))), box('mdat'));

    expect(readTrackStart(bytes, 1)).toBeNull();
  });

  it('track_ID 칸이 없으면(그 자리에 뒤 tfdt 의 크기 16) 그 번호로도 읽지 않는다', () => {
    const headOnly = fullBox('tfhd', 0, DEFAULT_BASE_IS_MOOF);
    const fragment = moof(traf(headOnly, tfdt(0, 500n), trun(0, 0x100, [{ duration: 10 }])));

    expect(readTrackStart(concat(fragment, box('mdat')), 16)).toBeNull();
    expect(readTrackSpans(concat(init(trak(16, 'vide', 90000)), fragment))).toEqual([]);
  });
});

/** trun 머리(표본 수 · 0x001 data_offset · 0x004 first_sample_flags)가 상자 끝을 넘는다 */
const SHORT_TRUNS = [
  { name: '표본 수 칸 없음', trun: fullBox('trun', 0, 0) },
  { name: '0x001 칸 없음', trun: fullBox('trun', 0, 0x801, u32(0)) },
  { name: '0x004 칸 없음', trun: fullBox('trun', 0, 0x804, u32(0)) },
];

describe('상자 끝: 짧은 trun 머리는 판독 실패다', () => {
  it.each(SHORT_TRUNS)('readTrackStart — $name 이면 null', ({ trun: run }) => {
    expect(readTrackStart(videoFragment(1, tfdt(0, 500n), run), 1)).toBeNull();
  });

  it.each(SHORT_TRUNS)('readTrackSpans — $name 이면 빈 목록', ({ trun: run }) => {
    const bytes = concat(
      init(trak(1, 'vide', 90000)),
      moof(traf(tfhd(1, 3000), tfdt(0, 0n), run)),
      box('mdat'),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });
});

/** trun 표본 수가 상자 안 표본 표보다 많다(뒤 mdat 바이트가 남은 표본 자리에 온다) */
const SHORT_SAMPLE_TABLES = [
  { name: '0x100 길이 표', trun: fullBox('trun', 0, 0x100, u32(3), u32(10)) },
  { name: '0x200 크기 표(길이는 기본값)', trun: fullBox('trun', 0, 0x200, u32(3), u32(900)) },
];

describe('상자 끝: 표본 표가 상자를 넘는 trun 은 판독 실패다', () => {
  it.each(SHORT_SAMPLE_TABLES)('readTrackSpans — $name 이 짧으면 빈 목록', ({ trun: run }) => {
    const bytes = concat(
      init(trak(1, 'vide', 90000)),
      moof(traf(tfhd(1, 3000), tfdt(0, 0n), run)),
      box('mdat', u32(5), u32(6)),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });
});

/** tfdt 값 칸이 상자 끝을 넘는다(그 자리에 뒤 trun 의 머리가 온다) */
const SHORT_TFDTS = [
  { name: 'v0 값 칸 없음', tfdt: fullBox('tfdt', 0, 0) },
  { name: 'v1 값 칸 4바이트뿐', tfdt: fullBox('tfdt', 1, 0, u32(0)) },
];

describe('상자 끝: 값 칸이 짧은 tfdt 는 판독 실패다', () => {
  it.each(SHORT_TFDTS)('readTrackStart — $name 이면 null', ({ tfdt: decodeTime }) => {
    expect(readTrackStart(videoFragment(1, decodeTime, trun(0, 0, [{}])), 1)).toBeNull();
  });

  it.each(SHORT_TFDTS)('readTrackSpans — $name 이면 빈 목록', ({ tfdt: decodeTime }) => {
    const bytes = concat(
      init(trak(1, 'vide', 90000)),
      moof(traf(tfhd(1, 3000), decodeTime, trun(0, 0, [{}]))),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });
});

// --- 모르는 버전: 0 · 1 밖의 버전은 칸 자리를 모르므로 v0 처럼 읽지 않는다 ---

/** tkhd 또는 mdhd 만 버전 2 인 영상 trak(칸 배치는 v0 와 같다) */
const VERSION_2_TRAKS = [
  {
    name: 'tkhd',
    trak: box(
      'trak',
      fullBox('tkhd', 2, 3, u32(0), u32(0), u32(1), u32(0)),
      box('mdia', MDHD, HDLR),
    ),
  },
  {
    name: 'mdhd',
    trak: box(
      'trak',
      TKHD,
      box('mdia', fullBox('mdhd', 2, 0, u32(0), u32(0), u32(90000), u32(0)), HDLR),
    ),
  },
];

describe('모르는 버전: tkhd · mdhd · tfdt 버전 2 는 판독 실패다', () => {
  it.each(VERSION_2_TRAKS)('readVideoTrack — $name 버전 2 면 null', ({ trak }) => {
    expect(readVideoTrack(init(trak))).toBeNull();
  });

  it.each(VERSION_2_TRAKS)('readTrackSpans — $name 버전 2 면 빈 목록', ({ trak }) => {
    const bytes = concat(
      init(trak),
      moof(traf(tfhd(1), tfdt(0, 0n), trun(0, 0x100, [{ duration: 10 }]))),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });

  it('tfdt 버전 2 면 readTrackStart 는 null · readTrackSpans 는 빈 목록', () => {
    const version2 = fullBox('tfdt', 2, 0, u64(42n));
    const fragment = moof(traf(tfhd(1), version2, trun(0, 0x100, [{ duration: 10 }])));

    expect(readTrackStart(concat(fragment, box('mdat')), 1)).toBeNull();
    expect(readTrackSpans(concat(init(trak(1, 'vide', 90000)), fragment))).toEqual([]);
  });
});

// 규격(ISO/IEC 14496-12)은 trun 에 버전 0 · 1, tfhd · trex · hdlr 에 버전 0 만 둔다

const UNKNOWN_VERSION_TFHD = fullBox('tfhd', 1, DEFAULT_BASE_IS_MOOF, u32(1));

/** hdlr 만 버전 1 인 영상 trak(칸 배치는 v0 와 같다) */
const UNKNOWN_VERSION_TRAK = box(
  'trak',
  TKHD,
  box('mdia', MDHD, fullBox('hdlr', 1, 0, u32(0), ascii('vide'), u32(0), u32(0), u32(0))),
);

describe('모르는 버전: trun 버전 2 · tfhd · trex · hdlr 버전 1 은 판독 실패다', () => {
  it('trun 버전 2 면 readTrackStart 는 null 이다(v0 처럼 무부호 cto 로 읽지 않는다)', () => {
    const run = fullBox('trun', 2, 0x800, u32(1), u32(0xfffffa24));

    expect(readTrackStart(videoFragment(1, tfdt(0, 0n), run), 1)).toBeNull();
  });

  it('trun 버전 2 면 readTrackSpans 는 빈 목록이다', () => {
    const run = fullBox('trun', 2, 0x100, u32(1), u32(10));
    const bytes = concat(init(trak(1, 'vide', 90000)), moof(traf(tfhd(1), tfdt(0, 0n), run)));

    expect(readTrackSpans(bytes)).toEqual([]);
  });

  it('tfhd 버전 1 이면 readTrackStart 는 null · readTrackSpans 는 빈 목록', () => {
    const fragment = moof(
      traf(UNKNOWN_VERSION_TFHD, tfdt(0, 500n), trun(0, 0x100, [{ duration: 10 }])),
    );

    expect(readTrackStart(concat(fragment, box('mdat')), 1)).toBeNull();
    expect(readTrackSpans(concat(init(trak(1, 'vide', 90000)), fragment))).toEqual([]);
  });

  it('trex 버전 1 이면 readTrackSpans 는 빈 목록이다', () => {
    const version1 = fullBox('trex', 1, 0, u32(1), u32(1), u32(3000), u32(0), u32(0));
    const bytes = concat(
      init(trak(1, 'vide', 90000), box('mvex', version1)),
      moof(traf(tfhd(1), tfdt(0, 0n), trun(0, 0, [{}, {}]))),
    );

    expect(readTrackSpans(bytes)).toEqual([]);
  });

  it('hdlr 버전 1 이면 readVideoTrack 은 null · readTrackSpans 는 빈 목록', () => {
    const fragment = moof(traf(tfhd(1), tfdt(0, 0n), trun(0, 0x100, [{ duration: 10 }])));

    expect(readVideoTrack(init(UNKNOWN_VERSION_TRAK))).toBeNull();
    expect(readTrackSpans(concat(init(UNKNOWN_VERSION_TRAK), fragment))).toEqual([]);
  });
});
