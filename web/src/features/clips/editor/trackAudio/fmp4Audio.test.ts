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
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000,
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

/** versions 는 tkhd · mdhd 버전(칸 배치는 언제나 v0 — 버전 2 는 규격에 없어 판독이 거부해야 한다) */
function trak(
  trackId: number,
  handler: 'vide' | 'soun',
  timescale: number,
  versions = { tkhd: 0, mdhd: 0 },
): Uint8Array {
  const entries = handler === 'soun' ? [mp4a(4)] : [];
  return box(
    'trak',
    fullBox('tkhd', versions.tkhd, 3, u32(0), u32(0), u32(trackId), u32(0)),
    box(
      'mdia',
      fullBox('mdhd', versions.mdhd, 0, u32(0), u32(0), u32(timescale), u32(0)),
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

/** traf 하나: tfdt v0 · trun(기본 v1 — 표본마다 길이 · 크기 1 · cto). 표본이 없으면 빈 trun */
function traf(trackId: number, decodeTime: number, samples: Sample[], trunVersion = 1): Uint8Array {
  const fields = samples.flatMap((s) => [u32(s.duration), u32(1), i32(s.cto ?? 0)]);
  return box(
    'traf',
    fullBox('tfhd', 0, 0x020000, u32(trackId)),
    fullBox('tfdt', 0, 0, u32(decodeTime)),
    fullBox('trun', trunVersion, 0x100 | 0x200 | 0x800, u32(samples.length), ...fields),
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

  it('영상 트랙이 없는 파일은 video 가 null 이고 소리 트랙만 준다', () => {
    const bytes = concat(
      init(trak(2, 'soun', 44100), trak(3, 'soun', 48000)),
      fragment(traf(2, 4410, [frame(1024)]), traf(3, 2400, [frame(1024)])),
    );

    const seg = demuxSegmentAudio(bytes);

    expect(seg.video).toBeNull();
    expect(seg.audio.map((t) => t.timing?.firstDts)).toEqual([0.1, 0.05]);
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

/** 소리 traf: tfhd 기본 표본 크기 size · tfdt 0 · 주어진 trun 들 */
function sizedTraf(trackId: number, size: number, ...truns: Uint8Array[]): Uint8Array {
  return box(
    'traf',
    fullBox('tfhd', 0, 0x020000 | 0x10, u32(trackId), u32(size)),
    fullBox('tfdt', 0, 0, u32(0)),
    ...truns,
  );
}

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
      init(trak(2, 'soun', 48000)),
      fragmentWithData(
        (at) => [
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
      init(...ids.map((id) => trak(id, 'soun', 48000))),
      fragmentWithData((at) => {
        let offset = at;
        return counts.map((count, i) => {
          const traf = sizedTraf(ids[i]!, 1, bareTrun(count, offset));
          offset += count;
          return traf;
        });
      }, total),
    );
  };

  it('첫 traf 는 프레임 하나 · 뒤 traf 는 1바이트 프레임 MAX_TRACK_SAMPLES 개면 뒤 프레임을 만들기 전에 빈 결과다', () => {
    const bytes = concat(
      init(trak(2, 'soun', 48000)),
      fragmentWithData(
        (at) => [
          sizedTraf(2, 1, bareTrun(1, at)),
          sizedTraf(2, 1, bareTrun(MAX_TRACK_SAMPLES, at + 1)),
        ],
        MAX_TRACK_SAMPLES + 1,
      ),
    );
    const counting = countingCopies(bytes);

    const seg = demuxSegmentAudio(counting.bytes);

    expect(seg).toEqual(EMPTY);
    expect(counting.copies()).toBe(1);
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
      init(trak(1, 'vide', 90000), trak(2, 'soun', 48000), trakWithoutMdhd(3)),
      fragmentWithData((at) => [sizedTraf(2, 1, bareTrun(1, at))], 1),
    );
    const counting = countingCopies(bytes);

    const seg = demuxSegmentAudio(counting.bytes);

    expect(seg).toEqual(EMPTY);
    expect(counting.copies()).toBe(0);
  });

  it('mdhd 없는 trak 이 다른 소리 트랙의 번호를 겹쳐 써도 빈 결과다', () => {
    const bytes = concat(
      init(trak(2, 'soun', 48000), trakWithoutMdhd(2)),
      fragmentWithData((at) => [sizedTraf(2, 1, bareTrun(1, at))], 1),
    );

    expect(demuxSegmentAudio(bytes)).toEqual(EMPTY);
  });
});
