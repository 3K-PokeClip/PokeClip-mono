import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { CUT_MAX_MS } from '@/api/clipEditor';
import { parseEditorPlaylist, type EditorPlaylist } from './editorPlaylist';
import {
  MAX_WINDOW_SECONDS,
  segmentAtMs,
  segmentsForWindow,
  sourceTimelineOf,
} from './editorSourceTimeline';
import { PAD_SECONDS } from './editorWindow';

// 실물은 미디어 서버 목록 발행기 시험이 쓰는 목록이다(media/internal/rewind/testdata). 기대값은 이 코드가 아니라 목록 글의
// PDT · EXTINF 에서 손으로 옮겼다.
// - vod_round_td_split: 런 넷(5–24 · 26–61 · 63–1626 · 1627–1733 — GAP 4 · 25 · 62 · 회차 바뀜 1627). 런 셋째 안
//   277 → 278 에 818ms 죽은 자리(277 끝 03:36:44.315 · 278 PDT 03:36:45.133)가 표지 없이 있다. 런 셋째 끝과 넷째 시작이
//   같은 PDT(05:11:00.342)로 맞닿는다.
// - g6_f2: 런 둘(398–1030 · 1031–1296)과 끝 GAP 1297. 1030 끝과 1031 PDT 사이 120초가 회차 바뀜 틈이다. 4초 조각이 이어진다.
// 실물에 없는 모양(판 2 겹침)은 합성 목록으로 만든다.
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
  'rewind',
  'testdata',
);

function fixture(name: string): string {
  return readFileSync(join(TESTDATA, name), 'utf8');
}

function parsed(
  text: string,
  listUrl = 'https://media.pokeclip.com/dvr/str/index.m3u8',
): EditorPlaylist {
  const list = parseEditorPlaylist(text, listUrl);
  if (list === null) throw new Error('목록을 거부했다');
  return list;
}

const ROUND_LIST_URL = 'https://cdn.test/dvr/round-golden/rounds/r1/index.m3u8';

/** vod_round_td_split 첫 줄(GAP 4) PDT 2026-11-04T03:17:54.682Z — 끝난 방송의 base(vod 첫 PDT) */
const TD_BASE = 1_793_762_274_682;
/** vod_round_td_split 277 PDT 03:36:41.857 · EXTINF 2.458 */
const TD_277_PDT = 1_793_763_401_857;
const TD_277_END = TD_277_PDT + 2458;
/** vod_round_td_split 278 PDT 03:36:45.133 */
const TD_278_PDT = 1_793_763_405_133;

/** g6_f2 첫 줄(398) PDT 2026-08-31T01:07:59.000Z */
const G6_BASE = 1_788_138_479_000;
/** g6_f2 1030 PDT 01:50:07 · 1031 PDT 01:52:11 */
const G6_1030_PDT = 1_788_141_007_000;
const G6_1031_PDT = 1_788_141_131_000;

function tdSplit(): EditorPlaylist {
  return parsed(fixture('vod_round_td_split.m3u8'), ROUND_LIST_URL);
}

function g6(): EditorPlaylist {
  return parsed(fixture('g6_f2.m3u8'));
}

/**
 * vod_round_td_split 에서 seq 4 부터 headSeq 앞까지 걷어 낸 목록 — 방송 중 1시간 창이 앞을 밀어낸 꼴(머리 줄 · MAP 은
 * 남김). 기본 머리 101 은 PDT 03:24:28.997(+394.315초), 105 는 03:24:45.326(+410.644초)이다
 */
function tdSplitSlid(headSeq = 101): EditorPlaylist {
  const text = fixture('vod_round_td_split.m3u8');
  const lines = text.split('\n');
  const mapAt = lines.findIndex((line) => line.startsWith('#EXT-X-MAP:'));
  const uri = `/seg/${String(headSeq).padStart(6, '0')}.m4s`;
  const uriAt = lines.findIndex((line) => line.endsWith(uri));
  // 머리 조각의 블록은 PDT · EXTINF · URI 세 줄이다
  const slid = [...lines.slice(0, mapAt + 1), ...lines.slice(uriAt - 2)].join('\n');
  return parsed(slid, ROUND_LIST_URL);
}

const MAP_V2 = '#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/A.mp4"';

/** 판 2 합성 목록 — 1(PDT 100.000 · 4초) 다음 2 가 0.3초 일찍(PDT 103.700) 시작해 겹친다(새 장 PDT 겹침 B8) */
function overlapV2(): EditorPlaylist {
  return parsed(
    [
      '#EXTM3U',
      '#EXT-X-VERSION:6',
      '#EXT-X-TARGETDURATION:6',
      MAP_V2,
      '#EXT-X-POKECLIP-TIMING:2',
      '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:01:40.000Z',
      '#EXTINF:4.000,',
      'https://media.pokeclip.com/dvr/str/seg/000001.m4s',
      '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:01:43.700Z',
      '#EXTINF:4.000,',
      'https://media.pokeclip.com/dvr/str/seg/000002.m4s',
      '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:01:47.700Z',
      '#EXTINF:4.000,',
      'https://media.pokeclip.com/dvr/str/seg/000003.m4s',
    ].join('\n'),
  );
}

/** 회차가 바뀌며(DISCONTINUITY + MAP) 두 런이 0.2초 겹치는 합성 목록 — 1–2(PDT 0 · 4 · 4초) 다음 3 이 7.8초에 선다 */
function overlappingRuns(): EditorPlaylist {
  return parsed(
    [
      '#EXTM3U',
      '#EXT-X-VERSION:6',
      '#EXT-X-TARGETDURATION:6',
      MAP_V2,
      '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:00.000Z',
      '#EXTINF:4.000,',
      'https://media.pokeclip.com/dvr/str/seg/000001.m4s',
      '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:04.000Z',
      '#EXTINF:4.000,',
      'https://media.pokeclip.com/dvr/str/seg/000002.m4s',
      '#EXT-X-DISCONTINUITY',
      '#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/B.mp4"',
      '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:07.800Z',
      '#EXTINF:4.000,',
      'https://media.pokeclip.com/dvr/str/seg/000003.m4s',
    ].join('\n'),
  );
}

function seqs(segments: readonly { seq: number }[]): number[] {
  return segments.map((segment) => segment.seq);
}

describe('segmentAtMs — F 규칙(PDT ≤ T 인 seq 최대 · 나중 우선)', () => {
  it('겹친 두 조각이 함께 덮는 T 는 나중 조각으로 푼다', () => {
    // Arrange — 1 은 100.000–104.000, 2 는 103.700 부터
    const list = overlapV2();

    // Act
    const atOverlap = segmentAtMs(list, 103_800);
    const beforeOverlap = segmentAtMs(list, 103_699);

    // Assert
    expect(atOverlap?.seq).toBe(2);
    expect(beforeOverlap?.seq).toBe(1);
  });

  it('조각 PDT 와 같은 T 는 그 조각이고 조각 안의 T 도 그 조각이다', () => {
    // Arrange
    const list = tdSplit();

    // Act
    const atPdt = segmentAtMs(list, TD_277_PDT);
    const inside = segmentAtMs(list, TD_277_END - 1);

    // Assert
    expect(atPdt?.seq).toBe(277);
    expect(inside?.seq).toBe(277);
  });

  it('vod_round_td_split 런 안 죽은 자리(277 끝 – 278 PDT)의 T 는 다음 조각 278 로 푼다', () => {
    // Arrange
    const list = tdSplit();

    // Act
    const deadStart = segmentAtMs(list, TD_277_END);
    const deadEnd = segmentAtMs(list, TD_278_PDT - 1);

    // Assert
    expect(deadStart?.seq).toBe(278);
    expect(deadEnd?.seq).toBe(278);
  });

  it('g6_f2 회차 바뀜 틈(1030 끝 – 1031 PDT 120초)의 T 는 다음 회차 첫 조각 1031 로 푼다', () => {
    // Arrange
    const list = g6();

    // Act
    const inGap = segmentAtMs(list, G6_1030_PDT + 4000 + 60_000);

    // Assert
    expect(inGap?.seq).toBe(1031);
    expect(inGap?.pdtMs).toBe(G6_1031_PDT);
  });

  it('GAP 줄 자리의 T 는 그 GAP 줄을 돌려준다 — 바이트가 없음은 gap 으로 안다', () => {
    // Arrange — vod_gap_key_mixed 의 2 는 GAP 줄(PDT +8초 · 4초)
    const list = parsed(fixture('vod_gap_key_mixed.m3u8'));
    const pdt0 = list.rows[0]!.pdtMs;

    // Act
    const atGap = segmentAtMs(list, pdt0 + 9000);

    // Assert
    expect(atGap?.seq).toBe(2);
    expect(atGap?.gap).toBe(true);
  });

  it('목록 첫 PDT 앞과 마지막 조각 끝 뒤의 T 는 null 이다', () => {
    // Arrange — g6_f2 마지막 줄 1297(GAP) 끝은 PDT 02:09:55 + 4초
    const list = g6();
    const lastEnd = G6_BASE + 3_720_000;

    // Act
    const before = segmentAtMs(list, G6_BASE - 1);
    const lastMs = segmentAtMs(list, lastEnd - 1);
    const after = segmentAtMs(list, lastEnd);

    // Assert
    expect(before).toBeNull();
    expect(lastMs?.seq).toBe(1297);
    expect(after).toBeNull();
  });
});

describe('sourceTimelineOf — 개요 타임라인', () => {
  it('g6_f2 는 런마다 [첫 PDT, 마지막 PDT + EXTINF) 를 base 축 초로 놓고 끝 GAP 줄은 구간에 넣지 않는다', () => {
    // Arrange
    const list = g6();

    // Act
    const timeline = sourceTimelineOf(list, G6_BASE);

    // Assert
    expect(timeline.pieces).toEqual([
      { fromSeconds: 0, toSeconds: 2532 },
      { fromSeconds: 2652, toSeconds: 3716 },
    ]);
    expect(timeline.endSeconds).toBe(3716);
  });

  it('vod_round_td_split 는 GAP · 회차 바뀜에서 구간을 넷으로 나누고 런 안 죽은 자리에서는 끊지 않는다', () => {
    // Arrange
    const list = tdSplit();

    // Act
    const { pieces, endSeconds } = sourceTimelineOf(list, TD_BASE);

    // Assert — 5 PDT +5.977 · 24 끝 +86.077 · 26 +90.087 · 61 끝 +234.267 · 63 +238.267 · 1626 끝 = 1627 PDT +6785.660
    //   · 1733 끝 +7235.785
    const expected = [
      [5.977, 86.077],
      [90.087, 234.267],
      [238.267, 6785.66],
      [6785.66, 7235.785],
    ];
    expect(pieces).toHaveLength(expected.length);
    pieces.forEach((piece, i) => {
      expect(piece.fromSeconds).toBeCloseTo(expected[i]![0]!, 6);
      expect(piece.toSeconds).toBeCloseTo(expected[i]![1]!, 6);
    });
    expect(endSeconds).toBeCloseTo(7235.785, 6);
  });

  it('두 런이 겹치면 앞 구간을 뒤 런 시작에서 자른다 — 겹친 자리는 나중 런 몫이고 구간은 겹치지 않는다', () => {
    // Arrange — 앞 런 0–8초, 뒤 런 7.8–11.8초
    const list = overlappingRuns();

    // Act
    const timeline = sourceTimelineOf(list, 0);

    // Assert
    expect(timeline.pieces).toEqual([
      { fromSeconds: 0, toSeconds: 7.8 },
      { fromSeconds: 7.8, toSeconds: 11.8 },
    ]);
    expect(timeline.endSeconds).toBe(11.8);
  });

  it('GAP 줄이 앞 조각 끝보다 일찍 서면 앞 구간을 GAP 시작에서 자른다 — 겹친 자리는 F 규칙으로 GAP 줄 몫이다', () => {
    // Arrange — 판 2 · 1 [0, 4) · GAP 2 [3.8, 7.8) · 3 [7.8, 11.8)
    const list = parsed(
      [
        '#EXTM3U',
        MAP_V2,
        '#EXT-X-POKECLIP-TIMING:2',
        '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:00.000Z',
        '#EXTINF:4.000,',
        'https://media.pokeclip.com/dvr/str/seg/000001.m4s',
        '#EXT-X-GAP',
        '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:03.800Z',
        '#EXTINF:4.000,',
        'https://media.pokeclip.com/dvr/str/seg/000002.m4s',
        '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:07.800Z',
        '#EXTINF:4.000,',
        'https://media.pokeclip.com/dvr/str/seg/000003.m4s',
      ].join('\n'),
    );

    // Act
    const timeline = sourceTimelineOf(list, 0);

    // Assert
    expect(segmentAtMs(list, 3900)?.gap).toBe(true);
    expect(timeline.pieces).toEqual([
      { fromSeconds: 0, toSeconds: 3.8 },
      { fromSeconds: 7.8, toSeconds: 11.8 },
    ]);
    expect(seqs(segmentsForWindow(list, 0, { startSeconds: 3.9, endSeconds: 9 }))).toEqual([3]);
  });

  it('조각이 모두 GAP 줄이면 구간이 없고 끝은 0 이다', () => {
    // Arrange
    const list = parsed(
      [
        '#EXTM3U',
        MAP_V2,
        '#EXT-X-GAP',
        '#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:00.000Z',
        '#EXTINF:4.000,',
        'https://media.pokeclip.com/dvr/str/seg/000001.m4s',
      ].join('\n'),
    );

    // Act
    const timeline = sourceTimelineOf(list, 0);

    // Assert
    expect(timeline).toEqual({ pieces: [], endSeconds: 0 });
  });
});

describe('segmentsForWindow — 창 조각', () => {
  it('MAX_WINDOW_SECONDS 는 컷 최대 180초에 앞뒤 여유를 더한 200초다', () => {
    // Assert
    expect(MAX_WINDOW_SECONDS).toBe(200);
    expect(MAX_WINDOW_SECONDS).toBe(CUT_MAX_MS / 1000 + 2 * PAD_SECONDS);
  });

  it('창이 200초를 넘으면 창 시작부터 200초까지 덮는 조각만 고른다', () => {
    // Arrange — g6_f2 첫 런은 4초 조각이 398 부터 이어진다(0–4 · … · 196–200 = 447)
    const list = g6();

    // Act
    const segments = segmentsForWindow(list, G6_BASE, { startSeconds: 0, endSeconds: 300 });

    // Assert
    expect(segments).toHaveLength(50);
    expect(segments[0]!.seq).toBe(398);
    expect(segments.at(-1)!.seq).toBe(447);
  });

  it('창 끝은 열린 끝이라 끝이 조각 PDT 와 같으면 그 조각을 넣지 않는다', () => {
    // Arrange
    const list = g6();

    // Act
    const segments = segmentsForWindow(list, G6_BASE, { startSeconds: 2, endSeconds: 8 });

    // Assert
    expect(seqs(segments)).toEqual([398, 399]);
  });

  it('창이 죽은 자리에서 시작하면 다음 조각부터 고른다', () => {
    // Arrange — 277 끝 +1129.633초 · 278 PDT +1130.451초 사이에서 시작
    const list = tdSplit();
    const startSeconds = (TD_277_END + 100 - TD_BASE) / 1000;

    // Act
    const segments = segmentsForWindow(list, TD_BASE, {
      startSeconds,
      endSeconds: startSeconds + 7,
    });

    // Assert — 창 1129.733–1136.733 · 278(1130.451–1136.020) · 279(1136.040–)
    expect(seqs(segments)).toEqual([278, 279]);
  });

  it('창 시작이 맞닿은 뒤 런의 첫 자리면 앞 런 끝 조각을 섞지 않는다', () => {
    // Arrange — vod_round_td_split 런 셋째 끝(1626)과 넷째 시작(1627)이 같은 PDT 다
    const list = tdSplit();
    const fourth = sourceTimelineOf(list, TD_BASE).pieces[3]!;

    // Act
    const segments = segmentsForWindow(list, TD_BASE, {
      startSeconds: fourth.fromSeconds,
      endSeconds: fourth.fromSeconds + 10,
    });

    // Assert — 1627(7.66초) · 1628
    expect(seqs(segments)).toEqual([1627, 1628]);
  });

  it('런을 건너는 창은 시작 런 끝에서 멈춘다 — 다른 런 조각은 한 영상으로 이어 쌀 수 없다', () => {
    // Arrange — g6_f2 1028(2520–2524)부터 1031(2652–)을 넘는 창
    const list = g6();

    // Act
    const segments = segmentsForWindow(list, G6_BASE, { startSeconds: 2520, endSeconds: 2700 });

    // Assert
    expect(seqs(segments)).toEqual([1028, 1029, 1030]);
  });

  it('창이 GAP 줄 자리에서 시작하면 다음 런부터 고르고 GAP 줄은 넣지 않는다', () => {
    // Arrange — vod_gap_key_mixed: 0 · 1 | GAP 2(8–12초) | 3(12–16초) | GAP 4 | 5
    const list = parsed(fixture('vod_gap_key_mixed.m3u8'));
    const base = list.rows[0]!.pdtMs;

    // Act
    const fromGap = segmentsForWindow(list, base, { startSeconds: 9, endSeconds: 15 });
    const beforeGap = segmentsForWindow(list, base, { startSeconds: 0, endSeconds: 12 });

    // Assert
    expect(seqs(fromGap)).toEqual([3]);
    expect(seqs(beforeGap)).toEqual([0, 1]);
  });

  it('창 끝이 시작보다 늦지 않거나 목록 끝 뒤에서 시작하면 빈 목록이다', () => {
    // Arrange
    const list = g6();

    // Act
    const empty = segmentsForWindow(list, G6_BASE, { startSeconds: 100, endSeconds: 100 });
    const pastEnd = segmentsForWindow(list, G6_BASE, { startSeconds: 3716, endSeconds: 3800 });

    // Assert
    expect(empty).toEqual([]);
    expect(pastEnd).toEqual([]);
  });
});

describe('목록 미끄러짐 — 앞 조각이 빠진 둘째 목록', () => {
  it('남은 seq 의 F 규칙 · 개요 구간 끝 · 창 조각이 첫 목록과 같다', () => {
    // Arrange — 첫 목록과 seq 4–100 이 빠진 둘째 목록 · base 는 세션 동안 그대로
    const first = tdSplit();
    const second = tdSplitSlid();
    // +7000초 = 05:14:34.682 → 1678(PDT 05:14:34.112)
    const probes = [TD_277_PDT + 1000, TD_277_END + 10, TD_278_PDT + 4000, TD_BASE + 7_000_000];
    const win = { startSeconds: 1120, endSeconds: 1150 };

    // Act
    const firstSeqs = probes.map((ms) => segmentAtMs(first, ms)?.seq);
    const secondSeqs = probes.map((ms) => segmentAtMs(second, ms)?.seq);
    const firstPieces = sourceTimelineOf(first, TD_BASE).pieces;
    const secondPieces = sourceTimelineOf(second, TD_BASE).pieces;

    // Assert
    expect(secondSeqs).toEqual(firstSeqs);
    expect(secondSeqs).toEqual([277, 278, 278, 1678]);
    expect(secondPieces).toHaveLength(2);
    expect(secondPieces[0]!.fromSeconds).toBeCloseTo(394.315, 6);
    expect(secondPieces.map((p) => p.toSeconds)).toEqual(
      firstPieces.slice(2).map((p) => p.toSeconds),
    );
    expect(secondPieces[1]).toEqual(firstPieces[3]);
    expect(seqs(segmentsForWindow(second, TD_BASE, win))).toEqual(
      seqs(segmentsForWindow(first, TD_BASE, win)),
    );
    expect(segmentAtMs(second, TD_BASE + 100_000)).toBeNull();
  });
});

describe('segmentsForWindow — 미디어가 없는 창', () => {
  it('미끄러진 목록의 머리(+394.315초) 앞에서 끝나는 창은 빈 목록이다', () => {
    // Arrange — 창 끝은 열린 끝이라 머리 PDT 에서 끝나는 창도 머리 앞이다
    const list = tdSplitSlid();

    // Act
    const windows = [
      { startSeconds: 0, endSeconds: 200 },
      { startSeconds: 190, endSeconds: 390 },
      { startSeconds: 200, endSeconds: 394 },
      { startSeconds: 300, endSeconds: 394.315 },
    ].map((win) => seqs(segmentsForWindow(list, TD_BASE, win)));

    // Assert
    expect(windows).toEqual([[], [], [], []]);
  });

  it('창이 미끄러진 목록의 머리를 걸치면 머리 조각부터 창 끝 조각까지만 고른다', () => {
    // Arrange — 101(394.315–398.325) · 102(398.325–402.635)
    const list = tdSplitSlid();

    // Act
    const segments = segmentsForWindow(list, TD_BASE, { startSeconds: 200, endSeconds: 400 });

    // Assert
    expect(seqs(segments)).toEqual([101, 102]);
  });

  it('앞 목록으로 정한 창을 네 조각 더 미끄러진 목록에 넣어 창이 머리 앞이 되면 빈 목록이다', () => {
    // Arrange — 첫 목록 머리 101 에 맞춘 창을, 101–104 까지 빠져 머리가 105(+410.644초)인 목록에 넣는다
    const stale = { startSeconds: 394.315, endSeconds: 405 };
    const list = tdSplitSlid(105);

    // Act
    const segments = segmentsForWindow(list, TD_BASE, stale);

    // Assert
    expect(sourceTimelineOf(list, TD_BASE).pieces[0]!.fromSeconds).toBeCloseTo(410.644, 6);
    expect(segments).toEqual([]);
  });

  it('창 끝이 목록 머리 PDT 와 1ms 안이면 ms 축에서 판정해 런 전체를 끌어오지 않는다', () => {
    // Arrange — 끝 1e-7초는 base + 1e-4ms 라 부동소수로 머리 PDT(base)와 같은 ms 가 된다
    const list = g6();

    // Act
    const segments = segmentsForWindow(list, G6_BASE, { startSeconds: -5, endSeconds: 1e-7 });

    // Assert
    expect(G6_BASE + 1000 * 1e-7).toBe(G6_BASE);
    expect(segments).toEqual([]);
  });

  it('g6_f2 회차 바뀜 틈(1030 끝 +2532초 – 1031 +2652초) 안의 창은 빈 목록이다', () => {
    // Arrange
    const list = g6();

    // Act
    const segments = segmentsForWindow(list, G6_BASE, { startSeconds: 2560, endSeconds: 2600 });

    // Assert
    expect(segments).toEqual([]);
  });

  it('GAP 줄 자리(vod_gap_key_mixed 2 · 8–12초) 안의 창은 빈 목록이다', () => {
    // Arrange
    const list = parsed(fixture('vod_gap_key_mixed.m3u8'));
    const base = list.rows[0]!.pdtMs;

    // Act
    const segments = segmentsForWindow(list, base, { startSeconds: 9, endSeconds: 11 });

    // Assert
    expect(segments).toEqual([]);
  });

  it('원본 목록의 [0, 200] 창은 머리를 걸쳐 첫 런 5–24 만 고른다', () => {
    // Arrange — 첫 런 5(+5.977초)–24(끝 +86.077초) · 창 끝 200초는 둘째 런 안이다
    const list = tdSplit();

    // Act
    const segments = segmentsForWindow(list, TD_BASE, { startSeconds: 0, endSeconds: 200 });

    // Assert
    expect(segments[0]!.seq).toBe(5);
    expect(segments.at(-1)!.seq).toBe(24);
    expect(segments).toHaveLength(20);
  });

  it('어떤 창이든 고른 조각 길이 합은 200초로 자른 창에 앞뒤 한 조각씩을 더한 것을 넘지 않는다', () => {
    // Arrange — 목록마다 머리 앞 300초부터 끝 뒤 300초까지 창을 훑는다. 앞뒤 한 조각은 창 시작 · 끝을 걸친 조각 몫이다
    const lists = [
      { list: tdSplit(), base: TD_BASE },
      { list: tdSplitSlid(), base: TD_BASE },
      { list: tdSplitSlid(105), base: TD_BASE },
      { list: g6(), base: G6_BASE },
      { list: overlappingRuns(), base: 0 },
    ];
    const lengths = [1, 9, 200, 450];
    // 머리 앞 · 끝 뒤 여유와 창 시작 간격(초) — 간격은 4초 조각 경계와 어긋나게 홀수로 둔다
    const marginSeconds = 300;
    const stepSeconds = 23;

    // Act
    const overs = lists.flatMap(({ list, base }) => {
      const maxMs = Math.max(...list.rows.map((row) => row.durationMs));
      const { endSeconds } = sourceTimelineOf(list, base);
      const starts = Array.from(
        { length: Math.ceil((endSeconds + 2 * marginSeconds) / stepSeconds) },
        (_, i) => i * stepSeconds - marginSeconds,
      );
      return starts.flatMap((startSeconds) =>
        lengths.flatMap((length) => {
          const win = { startSeconds, endSeconds: startSeconds + length };
          const totalMs = segmentsForWindow(list, base, win).reduce((n, s) => n + s.durationMs, 0);
          const boundMs = 1000 * Math.min(length, MAX_WINDOW_SECONDS) + 2 * maxMs;
          return totalMs > boundMs ? [{ win, totalMs, boundMs }] : [];
        }),
      );
    });

    // Assert
    expect(overs).toEqual([]);
  });
});
