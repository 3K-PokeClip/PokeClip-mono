import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { MAX_TRACK_SAMPLES } from '@/lib/fmp4Boxes';
import { demuxSegmentAudio, toAdts } from './fmp4Audio';

// 시험 조각은 ffmpeg로 만든 1초짜리 fMP4다(영상 1 + 소리 3, 상자 모양은 녹화 조각과 같다: ftyp·moov·moof/mdat 여러 쌍).
// 소리 트랙마다 표본 빈도·채널을 다르게 해 「파일 순서대로 꺼냈나」가 값으로 드러난다. 프레임 수는 ffprobe -count_packets 값.
//   ffmpeg -f lavfi -i testsrc=size=64x36:rate=10:duration=1 -f lavfi -i sine=440:48000 -f lavfi -i sine=880:44100
//          -f lavfi -i sine=220:24000 -map 0:v -map 1:a -map 2:a -map 3:a -c:v libx264 -g 5 -c:a aac -b:a 32k
//          -ac:a:0 2 -ac:a:1 1 -ac:a:2 2 -movflags frag_keyframe+empty_moov+default_base_moof three-tracks.m4s
const fixture = readFileSync(join(__dirname, '__fixtures__', 'three-tracks.m4s'));

const FREQUENCIES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
];

describe('demuxSegmentAudio: 조각 하나에서 소리 트랙을 파일 순서대로 꺼낸다', () => {
  const tracks = demuxSegmentAudio(fixture).audio;

  it('영상 트랙은 빼고 소리 트랙 셋을 파일 순서대로 준다: 렌더의 [0:a:N]과 같은 번호다', () => {
    expect(tracks).toHaveLength(3);
    expect(tracks.map((t) => FREQUENCIES[t.config.frequencyIndex])).toEqual([48000, 44100, 24000]);
    expect(tracks.map((t) => t.config.channelConfig)).toEqual([2, 1, 2]);
    expect(tracks.every((t) => t.config.objectType === 2)).toBe(true); // AAC LC
  });

  it('조각 안의 moof 전부에서 프레임을 모은다', () => {
    expect(tracks.map((t) => t.frames.length)).toEqual([48, 45, 25]);
    expect(tracks.every((t) => t.frames.every((f) => f.length > 0))).toBe(true);
  });

  it('프레임은 조각 버퍼를 붙잡지 않는 복사본이다: 영상까지 든 조각 전체가 디코드 끝까지 남지 않게(로컬 리뷰 1라운드)', () => {
    const frame = tracks[0]!.frames[0]!;
    expect(frame.buffer).not.toBe(fixture.buffer);
    expect(frame.buffer.byteLength).toBe(frame.byteLength);
  });

  it('소리 트랙이 없거나 상자가 깨졌으면 빈 목록이다(던지지 않는다)', () => {
    expect(demuxSegmentAudio(new Uint8Array(0)).audio).toEqual([]);
    expect(demuxSegmentAudio(fixture.subarray(0, 40)).audio).toEqual([]);
  });
});

describe('toAdts: 브라우저가 풀 수 있게 프레임마다 ADTS 머리(7바이트)를 붙인다', () => {
  it('머리의 동기 표시·길이·설정이 맞고 프레임이 이어 붙는다', () => {
    const first = demuxSegmentAudio(fixture).audio[0]!;
    const adts = toAdts(first);
    let offset = 0;
    let count = 0;
    while (offset < adts.length) {
      expect(adts[offset]).toBe(0xff);
      const at = (i: number) => adts[offset + i]!;
      expect(at(1) & 0xf6).toBe(0xf0);
      const profile = (at(2) >> 6) + 1;
      const frequencyIndex = (at(2) >> 2) & 0x0f;
      const channels = ((at(2) & 0x01) << 2) | (at(3) >> 6);
      const length = ((at(3) & 0x03) << 11) | (at(4) << 3) | (at(5) >> 5);
      expect([profile, frequencyIndex, channels]).toEqual([2, 3, 2]);
      expect(adts.subarray(offset + 7, offset + length)).toEqual(first.frames[count]);
      offset += length;
      count += 1;
    }
    expect(count).toBe(first.frames.length);
    expect(offset).toBe(adts.length);
  });
});

// --- demuxSegmentAudio: ② 녹화 조각의 트랙별 시각(PR-W2 c8 · 설계 prw2 3.2 · 3.3절) ---

// 실물은 운영 형상(영상 1 + 소리 6) 녹화 조각이다. 기대값은 ffprobe -show_packets 에서 옮겼다
// (영상 첫 표본 dts 8136 · pts 11136 · 표본 길이 합 15000 / 소리마다 dts 0 · 길이 합 6144).
const TESTDATA = join(
  __dirname,
  '..',
  '..',
  '..',
  '..',
  '..',
  '..',
  'media',
  'internal',
  'playback',
  'testdata',
);
const segment1v6a = new Uint8Array(readFileSync(join(TESTDATA, 'segment_1v6a.mp4')));

// 합성 바이트: 실물에 없는 모양(트랙마다 다른 timescale · 영상 없음 · 빈 traf · open GOP cto)을 만든다.

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

/** mp4a 표본 항목(AAC LC · 그 빈도 번호 · 1채널). demuxSegmentAudio 가 읽는 esds 모양 그대로 */
function mp4a(frequencyIndex: number): Uint8Array {
  const asc = ((2 << 11) | (frequencyIndex << 7) | (1 << 3)) & 0xffff;
  const decoderConfig = concat(new Uint8Array(13), Uint8Array.of(0x05, 2, asc >> 8, asc & 0xff));
  const es = concat(
    Uint8Array.of(0, 1, 0),
    Uint8Array.of(0x04, decoderConfig.length),
    decoderConfig,
  );
  const esds = fullBox('esds', 0, 0, Uint8Array.of(0x03, es.length), es);
  return box('mp4a', new Uint8Array(28), esds);
}

/**
 * tkhd · mdhd 는 그 버전(칸 배치는 언제나 v0 — 버전 2 는 규격에 없어 판독이 거부해야 한다), frequencyIndex 는 소리 트랙
 * AAC 설정의 빈도 번호(기본 4 = 44,100Hz)
 */
function trak(
  trackId: number,
  handler: 'vide' | 'soun',
  timescale: number,
  { tkhd = 0, mdhd = 0, frequencyIndex = 4 } = {},
): Uint8Array {
  const entries = handler === 'soun' ? [mp4a(frequencyIndex)] : [];
  return box(
    'trak',
    fullBox('tkhd', tkhd, 3, u32(0), u32(0), u32(trackId), u32(0)),
    box(
      'mdia',
      fullBox('mdhd', mdhd, 0, u32(0), u32(0), u32(timescale), u32(0)),
      fullBox('hdlr', 0, 0, u32(0), ascii(handler), u32(0), u32(0), u32(0)),
      box('minf', box('stbl', fullBox('stsd', 0, 0, u32(entries.length), ...entries))),
    ),
  );
}

function init(...traks: Uint8Array[]): Uint8Array {
  return concat(box('ftyp', ascii('isom'), u32(0)), box('moov', ...traks));
}

interface Sample {
  duration: number;
  cto?: number;
}

/**
 * trun 하나(기본 v1 — 표본마다 길이 · 크기 1 · cto). 표본이 없으면 빈 trun. 표본 칸은 한 버퍼에 바로 쓴다 — 범람 꼴(표본
 * 2만 개 가까이)을 인자 펼치기로 이으면 호출 스택이 넘친다
 */
function trun(samples: Sample[], version = 1): Uint8Array {
  const fields = new Uint8Array(samples.length * 12);
  const view = new DataView(fields.buffer);
  samples.forEach((s, i) => {
    view.setUint32(i * 12, s.duration);
    view.setUint32(i * 12 + 4, 1);
    view.setInt32(i * 12 + 8, s.cto ?? 0);
  });
  return fullBox('trun', version, 0x100 | 0x200 | 0x800, u32(samples.length), fields);
}

/** traf 하나: tfdt v0 · 표본 목록마다 trun 하나(runs 가 여럿이면 trun 도 여럿) */
function trafOfRuns(trackId: number, decodeTime: number, ...runs: Sample[][]): Uint8Array {
  return box(
    'traf',
    fullBox('tfhd', 0, 0x020000, u32(trackId)),
    fullBox('tfdt', 0, 0, u32(decodeTime)),
    ...runs.map((samples) => trun(samples)),
  );
}

/** traf 하나: tfdt v0 · trun 하나(trunVersion — 기본 v1) */
function traf(trackId: number, decodeTime: number, samples: Sample[], trunVersion = 1): Uint8Array {
  return box(
    'traf',
    fullBox('tfhd', 0, 0x020000, u32(trackId)),
    fullBox('tfdt', 0, 0, u32(decodeTime)),
    trun(samples, trunVersion),
  );
}

function fragment(...trafs: Uint8Array[]): Uint8Array {
  return concat(
    box('moof', fullBox('mfhd', 0, 0, u32(1)), ...trafs),
    box('mdat', new Uint8Array(16)),
  );
}

const frame = (duration: number, cto = 0): Sample => ({ duration, cto });

const EMPTY = { audio: [], video: null };

describe('demuxSegmentAudio: 녹화 조각의 트랙별 첫 DTS · PTS 와 timescale 을 읽는다', () => {
  it('실물 영상 1 + 소리 6 조각에서 트랙마다 자기 timescale 로 바꾼 초를 준다', () => {
    const seg = demuxSegmentAudio(segment1v6a);

    expect(seg.video).toEqual({
      timescale: 90000,
      timing: { firstDts: 8136 / 90000, firstPts: 11136 / 90000 },
    });
    expect(seg.audio).toHaveLength(6);
    for (const track of seg.audio) {
      expect(track.timescale).toBe(44100);
      expect(track.timing).toEqual({ firstDts: 0, firstPts: 0 });
      expect(track.frames).toHaveLength(6);
    }
  });

  it('timescale 이 다른 트랙은 각자 자기 timescale 로 초가 된다(영상 90,000 · 소리 44,100 · 48,000)', () => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 44100), trak(3, 'soun', 48000)),
      fragment(
        traf(1, 9000, [frame(3000, 3000)]),
        traf(2, 2205, [frame(1024)]),
        traf(3, 4800, [frame(1024, 480)]),
      ),
    );

    const seg = demuxSegmentAudio(bytes);

    expect(seg.video).toEqual({
      timescale: 90000,
      timing: { firstDts: 0.1, firstPts: 12000 / 90000 },
    });
    expect(seg.audio.map((t) => [t.timescale, t.timing?.firstDts, t.timing?.firstPts])).toEqual([
      [44100, 0.05, 0.05],
      [48000, 0.1, 0.11],
    ]);
  });

  it('첫 PTS 는 첫 표본 PTS 가 아니라 첫 traf 표본들의 PTS 최솟값이다(open GOP)', () => {
    // 영상 표본 둘: dts 9000 · cto 6000 → pts 15000 / dts 12000 · cto 0 → pts 12000(첫 표본보다 이르다)
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 44100)),
      fragment(traf(1, 9000, [frame(3000, 6000), frame(3000, 0)]), traf(2, 5292, [frame(1024)])),
    );

    const seg = demuxSegmentAudio(bytes);

    expect(seg.video?.timing).toEqual({ firstDts: 0.1, firstPts: 12000 / 90000 });
  });

  it('영상 트랙이 없는 파일은 소리 프레임 수를 잴 시간 기준이 없어 빈 결과다', () => {
    const bytes = concat(
      init(trak(2, 'soun', 44100), trak(3, 'soun', 48000)),
      fragment(traf(2, 4410, [frame(1024)]), traf(3, 2400, [frame(1024)])),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });

  it('표본 없는 traf 는 건너뛰고 표본 있는 첫 traf 의 tfdt 를 첫 DTS 로 쓴다', () => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 44100)),
      fragment(traf(1, 9000, [frame(3000)]), traf(2, 0, [])),
      fragment(traf(1, 12000, [frame(3000)]), traf(2, 8820, [frame(1024)])),
    );

    const seg = demuxSegmentAudio(bytes);

    expect(seg.audio[0]?.timing).toEqual({ firstDts: 0.2, firstPts: 0.2 });
    expect(seg.video?.timing).toEqual({ firstDts: 0.1, firstPts: 0.1 });
  });

  it('표본이 하나도 없는 트랙은 timescale 만 있고 timing 이 null 이다', () => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 44100), trak(3, 'soun', 48000)),
      fragment(traf(1, 9000, [frame(3000)]), traf(2, 8820, [frame(1024)]), traf(3, 0, [])),
    );

    const seg = demuxSegmentAudio(bytes);

    expect(seg.audio.map((t) => [t.timescale, t.timing === null])).toEqual([
      [44100, false],
      [48000, true],
    ]);
  });

  it('시각 값이 빠진 12바이트 tfdt 뒤에 정상 trun 이 오면 빈 결과다(다음 상자 크기를 DTS 로 읽지 않는다)', () => {
    const shortTfdt = box(
      'traf',
      fullBox('tfhd', 0, 0x020000, u32(1)),
      fullBox('tfdt', 0, 0),
      fullBox('trun', 1, 0x100 | 0x200 | 0x800, u32(1), u32(3000), u32(1), i32(0)),
    );
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 44100)),
      fragment(shortTfdt, traf(2, 0, [frame(1024)])),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });

  it.each([
    { name: 'tkhd', versions: { tkhd: 2, mdhd: 0 } },
    { name: 'mdhd', versions: { tkhd: 0, mdhd: 2 } },
  ])('$name 버전 2 는 v0 배치로 읽지 않고 빈 결과다', ({ versions }) => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 44100, versions)),
      fragment(traf(1, 9000, [frame(3000)]), traf(2, 0, [frame(1024)])),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });

  it('trun 버전이 0 · 1 이 아니면 cto 부호를 몰라 빈 결과를 준다', () => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 44100)),
      fragment(traf(1, 9000, [frame(3000, 3000)], 2), traf(2, 0, [frame(1024)])),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });

  it('상자가 깨졌거나 비었으면 빈 결과를 준다(던지지 않는다)', () => {
    expect(demuxSegmentAudio(new Uint8Array(0))).toEqual(EMPTY);
    expect(demuxSegmentAudio(segment1v6a.subarray(0, 40))).toEqual(EMPTY);
  });
});

// --- 꾸민 조각이 판독을 부풀리지 못한다(보안 검수 prw2 c9′ M-1 · M-2 · codex prw2 후속 경화 M) ---
// 모두 S3 쓰기 권한이나 생산자 침해를 전제한 꾸민 ② 조각이다. 정상 녹화 조각에서는 생기지 않는 모양이라 판독 실패(빈 결과)로 접는다.

/**
 * 입력 바이트의 slice(프레임 복사) 횟수와 복사한 바이트를 센다. 판독이 실패해 빈 결과를 줘도 그 전에 한 일이 드러난다
 * (앞 커밋 fileHeadProbe 의 할당 계수 시험과 같은 결)
 */
function countingCopies(bytes: Uint8Array) {
  let copies = 0;
  let copied = 0;
  const proto: object = Object.create(Uint8Array.prototype, {
    slice: {
      value(this: Uint8Array, start?: number, end?: number) {
        const out = Uint8Array.prototype.slice.call(this, start, end);
        copies += 1;
        copied += out.length;
        return out;
      },
    },
  });
  Object.setPrototypeOf(bytes, proto);
  return { bytes, copies: () => copies, copied: () => copied };
}

/** 표본별 칸 없는 trun(count 개 · 기본 크기는 tfhd) — 프레임 자리는 data_offset(moof 머리 기준) */
function bareTrun(count: number, dataOffset: number): Uint8Array {
  return fullBox('trun', 0, 0x01, u32(count), i32(dataOffset));
}

/** 소리 traf: tfhd 기본 표본 길이 1,024 · 기본 표본 크기 size · tfdt 0 · 주어진 trun 들 */
function sizedTraf(trackId: number, size: number, ...truns: Uint8Array[]): Uint8Array {
  return box(
    'traf',
    fullBox('tfhd', 0, 0x020000 | 0x08 | 0x10, u32(trackId), u32(1024), u32(size)),
    fullBox('tfdt', 0, 0, u32(0)),
    ...truns,
  );
}

/**
 * 아래 꾸민 조각들의 영상 트랙(1번 · 1초 표본 2,000개 — 표본 하나는 1초까지만 센다). 소리 프레임 수의 시간 상한이 먼저 걸리지
 * 않게 길게 둬서, 각 시험이 겨누는 다른 상한(복사 바이트 · 프레임 합 · 트랙 머리 짝)이 거르는지를 본다
 */
const LONG_VIDEO_TRAK = trak(1, 'vide', 90000);
const LONG_VIDEO_TRAF = traf(
  1,
  0,
  Array.from({ length: 2000 }, () => frame(90000)),
);

/**
 * moof + mdat(payload 바이트). trafsAt 은 mdat 본문 첫 바이트의 자리(moof 머리 기준 data_offset)를 받아 traf 들을 만든다 —
 * 자리를 알려면 moof 크기가 있어야 하므로 한 번 재고 다시 만든다(크기는 자리 값과 무관하다)
 */
function fragmentWithData(
  trafsAt: (dataOffset: number) => Uint8Array[],
  payload: number,
): Uint8Array {
  const moofAt = (offset: number) => box('moof', fullBox('mfhd', 0, 0, u32(1)), ...trafsAt(offset));
  return concat(moofAt(moofAt(0).length + 8), box('mdat', new Uint8Array(payload)));
}

/** mdhd 가 빠진 소리 trak(tkhd · hdlr · stsd 는 있다) — readTrackHeaders 는 세지 않고 건너뛰는 모양 */
function trakWithoutMdhd(trackId: number): Uint8Array {
  return box(
    'trak',
    fullBox('tkhd', 0, 3, u32(0), u32(0), u32(trackId), u32(0)),
    box(
      'mdia',
      fullBox('hdlr', 0, 0, u32(0), ascii('soun'), u32(0), u32(0), u32(0)),
      box('minf', box('stbl', fullBox('stsd', 0, 0, u32(1), mp4a(4)))),
    ),
  );
}

describe('demuxSegmentAudio: 같은 바이트를 거듭 가리키는 trun 으로 복사를 부풀리지 못한다(codex prw2 후속 경화 M)', () => {
  const FRAME_BYTES = 4096;
  const file = (truns: number) =>
    concat(
      init(LONG_VIDEO_TRAK, trak(2, 'soun', 48000)),
      fragmentWithData(
        (at) => [
          LONG_VIDEO_TRAF,
          sizedTraf(2, FRAME_BYTES, ...Array.from({ length: truns }, () => bareTrun(1, at))),
        ],
        FRAME_BYTES,
      ),
    );

  it('data_offset 을 한 번 가리킨 꼴은 그대로 푼다(아래 시험의 바탕이 정상 모양임을 보인다)', () => {
    const seg = demuxSegmentAudio(file(1));

    expect(seg.audio.map((t) => t.frames.map((f) => f.length))).toEqual([[FRAME_BYTES]]);
  });

  it('trun 64개가 같은 data_offset 을 다시 가리키면 입력보다 많이 복사하지 않고 빈 결과다', () => {
    const counting = countingCopies(file(64));

    const seg = demuxSegmentAudio(counting.bytes);

    expect(seg).toEqual(EMPTY);
    expect(counting.copied()).toBeLessThanOrEqual(counting.bytes.length);
  });
});

describe('demuxSegmentAudio: 아주 작은 프레임으로 프레임 수를 부풀리지 못한다(보안 검수 prw2 c9′ M-1)', () => {
  /** 트랙마다 counts[i] 개의 1바이트 프레임을 traf 하나씩으로. 첫 트랙 첫 traf 는 프레임 하나(timedTrack 이 보는 자리) */
  const file = (...counts: number[]) => {
    const ids = counts.map((_, i) => i + 2);
    const total = counts.reduce((sum, n) => sum + n, 0);
    return concat(
      init(LONG_VIDEO_TRAK, ...ids.map((id) => trak(id, 'soun', 48000))),
      fragmentWithData((at) => {
        let offset = at;
        const audio = counts.map((count, i) => {
          const traf = sizedTraf(ids[i]!, 1, bareTrun(count, offset));
          offset += count;
          return traf;
        });
        return [LONG_VIDEO_TRAF, ...audio];
      }, total),
    );
  };

  it('첫 트랙은 프레임 하나 · 뒤 트랙은 1바이트 프레임 MAX_TRACK_SAMPLES 개면 뒤 프레임을 만들기 전에 빈 결과다', () => {
    const counting = countingCopies(file(1, MAX_TRACK_SAMPLES));

    const seg = demuxSegmentAudio(counting.bytes);

    expect(seg).toEqual(EMPTY);
    expect(counting.copies()).toBe(1);
  });

  it('한 트랙 표본이 traf 여럿에 걸쳐 MAX_TRACK_SAMPLES 를 넘으면 기준 초를 읽을 때 프레임 하나 없이 빈 결과다', () => {
    const bytes = concat(
      init(LONG_VIDEO_TRAK, trak(2, 'soun', 48000)),
      fragmentWithData(
        (at) => [
          LONG_VIDEO_TRAF,
          sizedTraf(2, 1, bareTrun(1, at)),
          sizedTraf(2, 1, bareTrun(MAX_TRACK_SAMPLES, at + 1)),
        ],
        MAX_TRACK_SAMPLES + 1,
      ),
    );
    const counting = countingCopies(bytes);

    expect(demuxSegmentAudio(counting.bytes)).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('조각 하나의 소리 프레임이 트랙 합으로 꼭 MAX_TRACK_SAMPLES 면 그대로 푼다', () => {
    const half = MAX_TRACK_SAMPLES / 2;

    const seg = demuxSegmentAudio(file(half, half));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([half, half]);
  });

  it('트랙마다는 상한 아래여도 트랙 합이 MAX_TRACK_SAMPLES 를 넘으면 빈 결과다', () => {
    const half = MAX_TRACK_SAMPLES / 2;

    expect(demuxSegmentAudio(file(half, half + 1))).toEqual(EMPTY);
  });
});

describe('demuxSegmentAudio: mdhd 없는 소리 trak 으로 트랙 수 상한을 비켜 가지 못한다(보안 검수 prw2 c9′ M-2)', () => {
  it('트랙 머리(mdhd)가 없는 소리 trak 이 있으면 traf 를 읽기 전에 빈 결과다', () => {
    const bytes = concat(
      init(LONG_VIDEO_TRAK, trak(2, 'soun', 48000), trakWithoutMdhd(3)),
      fragmentWithData((at) => [LONG_VIDEO_TRAF, sizedTraf(2, 1, bareTrun(1, at))], 1),
    );
    const counting = countingCopies(bytes);

    const seg = demuxSegmentAudio(counting.bytes);

    expect(seg).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('mdhd 없는 trak 이 다른 소리 트랙의 번호를 겹쳐 써도 빈 결과다', () => {
    const bytes = concat(
      init(LONG_VIDEO_TRAK, trak(2, 'soun', 48000), trakWithoutMdhd(2)),
      fragmentWithData((at) => [LONG_VIDEO_TRAF, sizedTraf(2, 1, bareTrun(1, at))], 1),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });
});

// --- 소리 프레임 수를 조각 시간에 묶는다(보안 검수 prw2 hard3 M-1 추기 · kty 결정 (가) 2026-11-13) ---
// 방송자는 송출만으로(SRT) 4초 ② 조각 소리 trun 을 16만 표본 넘게 부풀린다 — 대부분 길이 0(MediaMTX 가 같거나 작은 DTS 를 길이
// 0 으로 기록) · 1ms 간격이면 약 4,200개(실측 runs/2026-11-13-prw2-aac-flood). 그 조각은 영상이 정상이라 재생 경로를 지난다.

/** 48kHz · 기준 4초 한 트랙의 상한: floor(4 × 48,000 / 1,024 × 1.5) + ceil(48,000 / 1,024) = 281 + 47 */
const CAP_4S_48K = 328;

/** 길이 duration 표본 count 개 */
const samples = (count: number, duration: number): Sample[] =>
  Array.from({ length: count }, () => frame(duration));

/** 30fps 영상(90kHz · 표본마다 3,000 눈금) seconds 초어치 */
const fps30 = (seconds: number): Sample[] => samples(seconds * 30, 3000);

/** 트랙 첫 표본의 시각(초) — 영상 첫 DTS 와 소리 첫 PTS(cto 0 이라 tfdt 그대로) */
interface Starts {
  video?: number;
  audio?: number;
}

/**
 * 영상 1(90kHz · 주어진 표본) + 소리 트랙들(트랙마다 주어진 표본 · 빈도 번호 frequencyIndex · timescale 은 그 빈도).
 * 트랙 첫 tfdt 는 starts(초 · 기본 0). 소리 표본은 크기 1 이고 moof 머리부터 읽는다
 */
function startedSegment(
  starts: Starts,
  video: Sample[],
  frequencyIndex: number,
  ...audio: Sample[][]
): Uint8Array {
  const rate = FREQUENCIES[frequencyIndex]!;
  const ids = audio.map((_, i) => i + 2);
  const audioStart = (starts.audio ?? 0) * rate;
  return concat(
    init(trak(1, 'vide', 90000), ...ids.map((id) => trak(id, 'soun', rate, { frequencyIndex }))),
    fragment(
      traf(1, (starts.video ?? 0) * 90000, video),
      ...audio.map((list, i) => traf(ids[i]!, audioStart, list)),
    ),
  );
}

/** 모든 트랙이 tfdt 0 에서 시작하는 startedSegment */
function timedSegment(video: Sample[], frequencyIndex: number, ...audio: Sample[][]): Uint8Array {
  return startedSegment({}, video, frequencyIndex, ...audio);
}

const HZ_48K = 3;
const HZ_96K = 0;
const HZ_7350 = 12;
/** 48kHz 4초 정상 소리(1,024 눈금 프레임 188개) */
const NORMAL_4S = samples(188, 1024);
/**
 * 영상 표본 하나 1초 — 소리보다 2초 늦게 시작하면(LATE_2S · 재시작 첫 조각 꼴) 여유 1 + 2초를 더해 영상 쪽 기준이 꼭
 * 4초다. 상한 경계 시험은 소리를 그보다 길게 둬 영상 쪽이 기준이 되게 한다(1초 표본을 그대로 다 세는지도 함께 본다)
 */
const VIDEO_1S = [frame(90000)];
const LATE_2S: Starts = { video: 2 };

describe('demuxSegmentAudio: 소리 프레임 수를 그 조각의 시간에 비례해 묶는다(보안 검수 prw2 hard3 M-1)', () => {
  it('정상 4초 조각(48kHz 188 프레임)은 그대로 푼다', () => {
    const seg = demuxSegmentAudio(timedSegment(fps30(4), HZ_48K, NORMAL_4S));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([188]);
  });

  it('정상 4초 조각(96kHz 375 프레임)은 그대로 푼다', () => {
    const seg = demuxSegmentAudio(timedSegment(fps30(4), HZ_96K, samples(375, 1024)));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([375]);
  });

  it('같은 시각 범람(길이 0 표본 4,000 덧붙임)은 프레임을 만들기 전에 빈 결과다', () => {
    const flood = [...NORMAL_4S, ...samples(4000, 0)];
    const counting = countingCopies(timedSegment(fps30(4), HZ_48K, flood));

    expect(demuxSegmentAudio(counting.bytes)).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('1ms 간격 범람(4,200 표본 · 길이 48)은 빈 결과다', () => {
    expect(demuxSegmentAudio(timedSegment(fps30(4), HZ_48K, samples(4200, 48)))).toEqual(EMPTY);
  });

  it('소리 시각을 넓게 벌려도(4,200 표본 × 1,024 = 89.6초) 영상이 4초면 빈 결과다', () => {
    expect(demuxSegmentAudio(timedSegment(fps30(4), HZ_48K, samples(4200, 1024)))).toEqual(EMPTY);
  });

  it('영상 시각을 넓게 벌려도(90초) 소리 자신의 시간이 4초면 범람은 빈 결과다', () => {
    const flood = [...NORMAL_4S, ...samples(4000, 0)];

    expect(demuxSegmentAudio(timedSegment(fps30(90), HZ_48K, flood))).toEqual(EMPTY);
  });

  it('상한에 꼭 맞으면 푼다 · 하나 넘으면 빈 결과다', () => {
    const atCap = demuxSegmentAudio(
      startedSegment(LATE_2S, VIDEO_1S, HZ_48K, samples(CAP_4S_48K, 1024)),
    );

    expect(atCap.audio.map((t) => t.frames.length)).toEqual([CAP_4S_48K]);
    const over = startedSegment(LATE_2S, VIDEO_1S, HZ_48K, samples(CAP_4S_48K + 1, 1024));
    expect(demuxSegmentAudio(over)).toEqual(EMPTY);
  });

  it('상한은 트랙마다다: 다른 트랙이 정상이어도 한 트랙이 넘으면 빈 결과다', () => {
    const over = samples(CAP_4S_48K + 71, 1024);

    const seg = demuxSegmentAudio(startedSegment(LATE_2S, VIDEO_1S, HZ_48K, NORMAL_4S, over));

    expect(seg).toEqual(EMPTY);
  });

  it('영상 시간이 0 이면 기준이 없어 빈 결과다', () => {
    expect(demuxSegmentAudio(timedSegment([frame(0)], HZ_48K, samples(1, 1024)))).toEqual(EMPTY);
  });

  it('긴 정상 조각(6트랙 · 96kHz · 84초)도 그대로 푼다 — 시간 상한은 조각 길이를 따라 커진다', () => {
    const perTrack = (84 * 96000) / 1024;
    const tracks = Array.from({ length: 6 }, () => samples(perTrack, 1024));

    const seg = demuxSegmentAudio(timedSegment(fps30(84), HZ_96K, ...tracks));

    expect(seg.audio.map((t) => t.frames.length)).toEqual(Array(6).fill(perTrack));
  });

  it('영상 표본이 하나에 1초(가변 프레임률이 가장 성긴 꼴)여도 1초씩 다 세어 정상 4초 소리를 푼다', () => {
    const seg = demuxSegmentAudio(timedSegment(samples(4, 90000), HZ_48K, NORMAL_4S));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([188]);
  });

  it('가변 프레임률 영상(0.1초 간격 · 중간 0.9초 멈춤)도 정상 4초 소리를 그대로 푼다', () => {
    const video = [...samples(31, 9000), frame(81000)];

    const seg = demuxSegmentAudio(timedSegment(video, HZ_48K, NORMAL_4S));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([188]);
  });
});

// --- 표본 하나의 길이를 잘라 기준 초를 낸다(보안 검수 prw2 hard4 H-1 · codex 재현) ---
// 녹화 서버 포크(v1.21.1-pokeclip.2)는 앞 표본 길이를 「다음 DTS − 이 DTS」 로 상한 없이 적는다. 리셋 직전에 영상 · 소리 DTS 를
// 함께 크게 뛰우면 그 조각의 마지막 표본 길이가 수백 초가 된다(codex: 같은 PTS 실물 범람의 두 마지막 표본을 600초로 바꾸면
// 18,887 프레임이 통과). 상류 main 은 이 길이를 0 으로 바꿨지만 포크에는 없다.

/** 600초(리셋 직전 DTS 점프로 부풀린 마지막 표본) */
const JUMP_SECONDS = 600;

describe('demuxSegmentAudio: 마지막 표본 길이를 부풀려 시간 상한을 끄지 못한다(보안 검수 prw2 hard4 H-1)', () => {
  /**
   * 정상 188 + 길이 0 표본 + 마지막 표본(lastTicks). 기본 프레임 수는 같은 PTS 실물 범람(same100r1)의 18,887 이고, 영상이
   * 90초면 그 시간 상한(약 6,376)이 18,887 을 막아 버리므로 그 시험은 1ms 간격 실물(ms1r1)의 4,187 로 줄인다
   */
  const sameTimeFlood = (lastTicks: number, total = 18887) => [
    ...NORMAL_4S,
    ...samples(total - NORMAL_4S.length - 1, 0),
    frame(lastTicks),
  ];

  it('영상 · 소리 마지막 표본을 함께 600초로 벌린 범람은 프레임을 만들기 전에 빈 결과다', () => {
    const video = [...fps30(4), frame(JUMP_SECONDS * 90000)];
    const counting = countingCopies(
      timedSegment(video, HZ_48K, sameTimeFlood(JUMP_SECONDS * 48000)),
    );

    expect(demuxSegmentAudio(counting.bytes)).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('소리 마지막 표본만 600초로 벌리면 영상 4초가 기준이라 빈 결과다', () => {
    const bytes = timedSegment(fps30(4), HZ_48K, sameTimeFlood(JUMP_SECONDS * 48000));

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });

  it('영상 마지막 표본만 600초로 벌리면 소리 4초가 기준이라 빈 결과다', () => {
    const video = [...fps30(4), frame(JUMP_SECONDS * 90000)];

    expect(demuxSegmentAudio(timedSegment(video, HZ_48K, sameTimeFlood(1024)))).toEqual(EMPTY);
  });

  it('영상이 길어도(90초) 소리 마지막 표본 600초로 소리 시간을 부풀리지 못한다(표본마다 2 × 1,024 눈금까지)', () => {
    const bytes = timedSegment(fps30(90), HZ_48K, sameTimeFlood(JUMP_SECONDS * 48000, 4187));

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });

  it('소리가 길어도(90초 분량) 영상 마지막 표본 600초로 영상 시간을 부풀리지 못한다(표본마다 1초까지)', () => {
    const video = [...fps30(4), frame(JUMP_SECONDS * 90000)];

    expect(demuxSegmentAudio(timedSegment(video, HZ_48K, samples(4219, 1024)))).toEqual(EMPTY);
  });

  it('같은 시각 범람(4만) + 영상 · 소리 마지막 표본 700초는 빈 결과다(cc 재현 — 1차 상한은 40,189 프레임을 통과시켰다)', () => {
    const video = [...fps30(4), frame(700 * 90000)];
    const flood = sameTimeFlood(700 * 48000, NORMAL_4S.length + 40000 + 1);

    expect(demuxSegmentAudio(timedSegment(video, HZ_48K, flood))).toEqual(EMPTY);
  });

  it('1,024 간격 AU 4만 개(소리 약 853초 — 묶음 AU 는 NTP 도 같이 늘어 드리프트에 안 걸림) + 영상 마지막 표본 700초는 빈 결과다', () => {
    const video = [...fps30(4), frame(700 * 90000)];

    expect(demuxSegmentAudio(timedSegment(video, HZ_48K, samples(40000, 1024)))).toEqual(EMPTY);
  });

  /** 48kHz 설정 소리 트랙을 timescale 192,000(빈도의 4배 — 프레임 하나 4,096 눈금)으로 적은 조각 */
  const scaledSegment = (video: Sample[], audio: Sample[]) =>
    concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 192000, { frequencyIndex: HZ_48K })),
      fragment(traf(1, 0, video), traf(2, 0, audio)),
    );

  // 8초를 쓰는 까닭: 표본 상한을 timescale 없이 2,048 눈금으로 잡으면 소리가 절반(4초)으로 세여 상한 328 < 375 로 걸린다.
  // 4초면 그 절반(2초)으로도 상한이 꼭 188 이라 구별되지 않는다
  it('소리 timescale 이 빈도와 달라도(192,000) 정상 8초는 그대로 푼다 — 표본 상한은 그 timescale 의 2 × 1,024 표본 몫이다', () => {
    const seg = demuxSegmentAudio(scaledSegment(fps30(8), samples(375, 4096)));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([375]);
  });

  it('소리 timescale 이 빈도와 달라도(192,000) 마지막 표본 600초 범람은 빈 결과다', () => {
    const video = [...fps30(4), frame(JUMP_SECONDS * 90000)];
    const flood = [...samples(188, 4096), ...samples(18698, 0), frame(JUMP_SECONDS * 192000)];

    expect(demuxSegmentAudio(scaledSegment(video, flood))).toEqual(EMPTY);
  });
});

describe('demuxSegmentAudio: 짧은 조각 여유는 프레임 수가 아니라 1초 분량이다(보안 검수 prw2 hard4 M-2)', () => {
  /** 7,350Hz · 기준 4초 한 트랙의 상한: floor(4 × 7,350 / 1,024 × 1.5) + ceil(7,350 / 1,024) = 43 + 8 */
  const CAP_4S_7350 = 51;

  it('7,350Hz 4초 조각은 상한 51 에 꼭 맞으면 푼다 · 하나 넘으면 빈 결과다', () => {
    const atCap = demuxSegmentAudio(
      startedSegment(LATE_2S, VIDEO_1S, HZ_7350, samples(CAP_4S_7350, 1024)),
    );

    expect(atCap.audio.map((t) => t.frames.length)).toEqual([CAP_4S_7350]);
    const over = startedSegment(LATE_2S, VIDEO_1S, HZ_7350, samples(CAP_4S_7350 + 1, 1024));
    expect(demuxSegmentAudio(over)).toEqual(EMPTY);
  });
});

// --- 녹화가 (다시) 시작된 첫 조각은 영상이 늦게 시작한다(오케스트레이터 3차 · kty 결정 (가) 「정상 조각은 통과」) ---
// 녹화기는 첫 키프레임 전 영상 표본을 버리고 소리는 처음부터 적는다 — 그 조각은 영상이 키프레임 간격(플러그인 2초)만큼 짧다.

describe('demuxSegmentAudio: 영상이 늦게 시작하거나 멈춘 정상 조각도 소리를 푼다(늦은 시작 여유)', () => {
  it('녹화 재시작 첫 조각(영상 2초 늦게 시작해 2초 · 소리 약 4.05초 · 188 프레임)은 그대로 푼다', () => {
    const audio = [...samples(187, 1024), frame(2920)];

    const seg = demuxSegmentAudio(startedSegment(LATE_2S, fps30(2), HZ_48K, audio));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([188]);
  });

  it('영상이 3초 멈춘 4초 조각(영상 1초 + 멈춤 표본 하나 — 1초로 깎임)도 정상 소리를 푼다', () => {
    const video = [...fps30(1), frame(3 * 90000)];

    const seg = demuxSegmentAudio(timedSegment(video, HZ_48K, NORMAL_4S));

    expect(seg.audio.map((t) => t.frames.length)).toEqual([188]);
  });
});

// --- 판독 시험 그물 · 판독기 입력 거름(PR 단계) ---

describe('demuxSegmentAudio: timescale 0 · 모르는 표본 모양은 프레임을 만들기 전에 빈 결과다', () => {
  it('소리 트랙 timescale 이 0 이면 프레임 하나 없이 빈 결과다(초를 몰라 시간 상한을 못 낸다)', () => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 0, { frequencyIndex: HZ_48K })),
      fragment(traf(1, 0, fps30(4)), traf(2, 0, NORMAL_4S)),
    );
    const counting = countingCopies(bytes);

    expect(demuxSegmentAudio(counting.bytes)).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('영상 트랙 timescale 이 0 이면 프레임 하나 없이 빈 결과다(기준 초가 없다)', () => {
    const bytes = concat(
      init(trak(1, 'vide', 0), trak(2, 'soun', 48000, { frequencyIndex: HZ_48K })),
      fragment(traf(1, 0, fps30(4)), traf(2, 0, NORMAL_4S)),
    );
    const counting = countingCopies(bytes);

    expect(demuxSegmentAudio(counting.bytes)).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('소리 표본 모양을 모르면(trun 버전 2) 시간 상한을 0 초로 치지 않고 프레임 하나 없이 빈 결과다', () => {
    const bytes = concat(
      init(trak(1, 'vide', 90000), trak(2, 'soun', 48000, { frequencyIndex: HZ_48K })),
      fragment(traf(1, 0, fps30(4)), traf(2, 0, samples(10, 1024), 2)),
    );
    const counting = countingCopies(bytes);

    expect(demuxSegmentAudio(counting.bytes)).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });
});

describe('demuxSegmentAudio: 시간 상한은 trun · moof 하나가 아니라 그 트랙 프레임 누적에 건다', () => {
  /** 영상 4초 · 48kHz 소리 — 상한은 trun 하나(300)보다 크고 둘의 합(600)보다 작다 */
  const header = () =>
    init(trak(1, 'vide', 90000), trak(2, 'soun', 48000, { frequencyIndex: HZ_48K }));

  it('한 traf 의 trun 둘(150 + 150)이 상한 안이면 그대로 푼다', () => {
    const bytes = concat(
      header(),
      fragment(traf(1, 0, fps30(4)), trafOfRuns(2, 0, samples(150, 1024), samples(150, 1024))),
    );

    expect(demuxSegmentAudio(bytes).audio.map((t) => t.frames.length)).toEqual([300]);
  });

  it('한 traf 의 trun 둘(300 + 300)은 하나씩은 상한 안이어도 합이 넘어 빈 결과다', () => {
    const bytes = concat(
      header(),
      fragment(traf(1, 0, fps30(4)), trafOfRuns(2, 0, samples(300, 1024), samples(300, 1024))),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });

  it('moof 둘에 나눈 trun(300 + 300)도 합이 넘어 빈 결과다', () => {
    const bytes = concat(
      header(),
      fragment(traf(1, 0, fps30(4)), traf(2, 0, samples(300, 1024))),
      fragment(traf(2, 300 * 1024, samples(300, 1024))),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });
});

describe('demuxSegmentAudio: 기준 초에서 소리 표본 하나는 프레임 두 개 몫까지만 센다', () => {
  /**
   * 영상 90초(소리 쪽이 기준) · 정상 188 + 길이 0 표본 zeros 개 + 600초 마지막 표본. 소리 기준 = (188 + 2) × 1,024 눈금
   * = 4.053초라 상한은 floor(190 × 1.5) + 47 = 332 다. 표본 하나를 세 프레임 몫까지 세면 상한이 333 이 된다
   */
  const bytes = (zeros: number) =>
    timedSegment(fps30(90), HZ_48K, [
      ...NORMAL_4S,
      ...samples(zeros, 0),
      frame(JUMP_SECONDS * 48000),
    ]);

  it('프레임 332 개(상한에 꼭 맞음)는 그대로 푼다', () => {
    expect(demuxSegmentAudio(bytes(143)).audio.map((t) => t.frames.length)).toEqual([332]);
  });

  it('프레임 333 개는 빈 결과다', () => {
    expect(demuxSegmentAudio(bytes(144))).toEqual(EMPTY);
  });
});

describe('demuxSegmentAudio: 소리 trak 의 머리 짝 · 프레임 자리를 거른다(보안 검수 prw2 hard3 L-1 · L-2)', () => {
  it('mdhd 없는 소리 trak 이 영상 트랙의 번호를 쓰면(짝 머리가 vide) traf 를 읽기 전에 빈 결과다', () => {
    const bytes = concat(
      init(LONG_VIDEO_TRAK, trak(2, 'soun', 48000), trakWithoutMdhd(1)),
      fragmentWithData((at) => [LONG_VIDEO_TRAF, sizedTraf(2, 1, bareTrun(1, at))], 1),
    );
    const counting = countingCopies(bytes);

    expect(demuxSegmentAudio(counting.bytes)).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('data_offset 이 파일 앞을 가리키면(음수 자리) 파일 끝 바이트를 프레임으로 읽지 않고 빈 결과다', () => {
    const head = init(LONG_VIDEO_TRAK, trak(2, 'soun', 48000));
    // 기준(moof 머리) = head.length 이므로 프레임 자리는 −10 — slice 는 이 자리를 파일 끝에서 센다
    const bytes = concat(
      head,
      fragmentWithData(
        () => [LONG_VIDEO_TRAF, sizedTraf(2, 1, bareTrun(1, -(head.length + 10)))],
        1,
      ),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });
});

describe('demuxSegmentAudio: 영상 쪽 여유는 1초 + 영상이 소리보다 늦게 시작한 몫(0–2초)이다(보안 검수 prw2 hard4 M-A)', () => {
  // 영상 1초 · 48kHz · 소리는 영상 쪽보다 길다. 상한 = floor((1 + 1 + 늦은 몫) × 48,000 / 1,024 × 1.5) + 47
  it.each([
    { name: '소리가 2초 늦게 시작함(0 으로 깎임)', starts: { audio: 2 }, cap: 187 },
    { name: '같이 시작함', starts: {}, cap: 187 },
    { name: '영상이 1초 늦음', starts: { video: 1 }, cap: 257 },
    { name: '영상이 2초 늦음(재시작 첫 조각)', starts: { video: 2 }, cap: 328 },
    { name: '영상이 4초 늦음(2초로 깎임)', starts: { video: 4 }, cap: 328 },
  ])('$name: 상한 $cap 에 꼭 맞으면 푼다 · 하나 넘으면 빈 결과다', ({ starts, cap }) => {
    const atCap = demuxSegmentAudio(startedSegment(starts, VIDEO_1S, HZ_48K, samples(cap, 1024)));

    expect(atCap.audio.map((t) => t.frames.length)).toEqual([cap]);
    expect(
      demuxSegmentAudio(startedSegment(starts, VIDEO_1S, HZ_48K, samples(cap + 1, 1024))),
    ).toEqual(EMPTY);
  });
});
