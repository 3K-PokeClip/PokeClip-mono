import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseEditorPlaylist, type EditorPlaylist } from './editorPlaylist';

// 실물은 미디어 서버 목록 발행기 시험이 쓰는 목록이다(media/internal/rewind/testdata — GAP 줄 · 회차가 바뀌는
// DISCONTINUITY + MAP · 키 꼴 셋). 기대값은 이 코드가 아니라 목록 글에서 손으로 옮겼다. 실물에 없는 모양(판 태그 ·
// 7자리 seq · 다른 출처 · 깨진 줄)은 합성 목록으로 만든다.
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

const MEDIA_LIST_URL = 'https://media.pokeclip.com/dvr/str/index.m3u8';
const ROUND_LIST_URL = 'https://cdn.test/dvr/round-golden/rounds/r1/index.m3u8';

/** 2026-09-26T00:00:00.000Z(gap_key 목록 첫 PDT) */
const GAP_KEY_PDT0 = 1_790_380_800_000;

/** 런마다 [첫 seq, 마지막 seq, 조각 수] — 런이 어디서 끊겼는지 한눈에 본다 */
function runShape(list: EditorPlaylist): number[][] {
  return list.runs.map((run) => [run[0]!.seq, run[run.length - 1]!.seq, run.length]);
}

function parsed(text: string, listUrl = MEDIA_LIST_URL): EditorPlaylist {
  const list = parseEditorPlaylist(text, listUrl);
  if (list === null) throw new Error('목록을 거부했다');
  return list;
}

/** 합성 목록 — 머리 줄 뒤에 조각 줄들을 잇는다 */
function playlist(...body: string[]): string {
  return ['#EXTM3U', '#EXT-X-VERSION:6', '#EXT-X-TARGETDURATION:6', ...body].join('\n') + '\n';
}

const MAP_A = '#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/A.mp4"';

function segment(seq: string, pdt: string, extinf = '4.000'): string[] {
  return [
    `#EXT-X-PROGRAM-DATE-TIME:${pdt}`,
    `#EXTINF:${extinf},`,
    `https://media.pokeclip.com/dvr/str/seg/${seq}.m4s`,
  ];
}

describe('parseEditorPlaylist — 생산자 실물', () => {
  it('vod_gap_key_mixed 는 GAP 줄과 회차 바뀜에서 런을 끊고 GAP 줄은 rows 에만 둔다', () => {
    // Arrange
    const text = fixture('vod_gap_key_mixed.m3u8');

    // Act
    const list = parsed(text);

    // Assert
    expect(runShape(list)).toEqual([
      [0, 1, 2],
      [3, 3, 1],
      [5, 5, 1],
    ]);
    expect(list.rows.map((row) => [row.seq, row.gap])).toEqual([
      [0, false],
      [1, false],
      [2, true],
      [3, false],
      [4, true],
      [5, false],
    ]);
    expect(list.runs.flat().some((row) => row.gap)).toBe(false);
  });

  it('vod_gap_key_mixed 의 조각마다 주소 · MAP · PDT · 길이 · 판을 읽는다', () => {
    // Arrange
    const text = fixture('vod_gap_key_mixed.m3u8');

    // Act
    const list = parsed(text);

    // Assert
    expect(list.rows[0]).toEqual({
      seq: 0,
      uri: 'https://media.pokeclip.com/dvr/str/seg/000000.m4s',
      mapUri: 'https://media.pokeclip.com/dvr/str/init/P.mp4',
      pdtMs: GAP_KEY_PDT0,
      durationMs: 4000,
      timing: null,
      gap: false,
    });
    expect(list.rows[3]).toEqual({
      seq: 3,
      uri: 'https://media.pokeclip.com/dvr/str/P/seg/000003.m4s',
      mapUri: 'https://media.pokeclip.com/dvr/str/init/S.mp4',
      pdtMs: GAP_KEY_PDT0 + 12_000,
      durationMs: 4000,
      timing: null,
      gap: false,
    });
    expect(list.rows.map((row) => row.pdtMs - GAP_KEY_PDT0)).toEqual([
      0, 4000, 8000, 12_000, 16_000, 20_000,
    ]);
  });

  it.each(['vod_gap_key_legacy.m3u8', 'vod_gap_key_round.m3u8'])(
    '%s 도 키 꼴과 상관없이 같은 런으로 읽는다',
    (name) => {
      // Arrange
      const text = fixture(name);

      // Act
      const list = parsed(text);

      // Assert
      expect(runShape(list)).toEqual([
        [0, 1, 2],
        [3, 3, 1],
        [5, 5, 1],
      ]);
      expect(list.rows.filter((row) => row.gap).map((row) => row.seq)).toEqual([2, 4]);
    },
  );

  it('vod_round_td_split 는 MEDIA-SEQUENCE 4 부터 GAP 셋과 회차 바뀜 하나에서 런을 넷으로 끊는다', () => {
    // Arrange
    const text = fixture('vod_round_td_split.m3u8');

    // Act
    const list = parsed(text, ROUND_LIST_URL);

    // Assert
    expect(list.rows).toHaveLength(1730);
    expect(list.rows.filter((row) => row.gap).map((row) => row.seq)).toEqual([4, 25, 62]);
    expect(runShape(list)).toEqual([
      [5, 24, 20],
      [26, 61, 36],
      [63, 1626, 1564],
      [1627, 1733, 107],
    ]);
  });

  it('vod_round_td_split 의 첫 줄과 회차가 바뀐 첫 조각의 값을 그대로 읽는다', () => {
    // Arrange
    const text = fixture('vod_round_td_split.m3u8');

    // Act
    const list = parsed(text, ROUND_LIST_URL);

    // Assert
    expect(list.rows[0]).toEqual({
      seq: 4,
      uri: 'https://cdn.test/dvr/round-golden/seg/000004.m4s',
      mapUri: 'https://cdn.test/dvr/round-golden/init/S-20261104-031754-round-golden-4.mp4',
      pdtMs: 1_793_762_274_682,
      durationMs: 5977,
      timing: null,
      gap: true,
    });
    expect(list.runs[3]![0]).toEqual({
      seq: 1627,
      uri: 'https://cdn.test/dvr/round-golden/seg/001627.m4s',
      mapUri: 'https://cdn.test/dvr/round-golden/init/S-20261104-050857-round-golden-1627.mp4',
      pdtMs: 1_793_769_060_342,
      durationMs: 7660,
      timing: null,
      gap: false,
    });
  });
});

describe('parseEditorPlaylist — 런 끊김', () => {
  it('DISCONTINUITY 만 있어도(MAP 이 같아도) 런을 끊는다', () => {
    // Arrange
    const text = playlist(
      MAP_A,
      ...segment('000010', '2026-11-04T00:00:00.000Z'),
      '#EXT-X-DISCONTINUITY',
      ...segment('000011', '2026-11-04T00:00:04.000Z'),
    );

    // Act
    const list = parsed(text);

    // Assert
    expect(runShape(list)).toEqual([
      [10, 10, 1],
      [11, 11, 1],
    ]);
  });

  it('DISCONTINUITY 가 없어도 MAP 주소가 바뀌면 런을 끊고 같은 MAP 을 다시 적으면 잇는다', () => {
    // Arrange
    const text = playlist(
      MAP_A,
      ...segment('000010', '2026-11-04T00:00:00.000Z'),
      MAP_A,
      ...segment('000011', '2026-11-04T00:00:04.000Z'),
      '#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/B.mp4"',
      ...segment('000012', '2026-11-04T00:00:08.000Z'),
    );

    // Act
    const list = parsed(text);

    // Assert
    expect(runShape(list)).toEqual([
      [10, 11, 2],
      [12, 12, 1],
    ]);
    expect(list.runs[1]![0]!.mapUri).toBe('https://media.pokeclip.com/dvr/str/init/B.mp4');
  });

  it('PDT 가 건너뛰어도 표지가 없으면 런을 잇는다(회차 안 끊김은 다음 층이 PDT 로 본다)', () => {
    // Arrange
    const text = playlist(
      MAP_A,
      ...segment('000010', '2026-11-04T00:00:00.000Z'),
      ...segment('000011', '2026-11-04T00:00:12.300Z'),
    );

    // Act
    const list = parsed(text);

    // Assert
    expect(runShape(list)).toEqual([[10, 11, 2]]);
    expect(list.rows[1]!.pdtMs - list.rows[0]!.pdtMs).toBe(12_300);
  });
});

describe('parseEditorPlaylist — seq', () => {
  it('seq 는 URI 끝 숫자를 정수로 읽어 6자리에서 7자리로 넘어가도 이어진다', () => {
    // Arrange
    const text = playlist(
      '#EXT-X-MEDIA-SEQUENCE:999999',
      MAP_A,
      ...segment('999999', '2026-11-04T00:00:00.000Z'),
      ...segment('1000000', '2026-11-04T00:00:04.000Z'),
      ...segment('1000001', '2026-11-04T00:00:08.000Z'),
    );

    // Act
    const list = parsed(text);

    // Assert
    expect(list.rows.map((row) => row.seq)).toEqual([999_999, 1_000_000, 1_000_001]);
  });

  it('seq 는 MEDIA-SEQUENCE · 줄 차례가 아니라 URI 의 번호다', () => {
    // Arrange
    const text = playlist(
      '#EXT-X-MEDIA-SEQUENCE:0',
      MAP_A,
      ...segment('0001200', '2026-11-04T00:00:00.000Z'),
      ...segment('0001201', '2026-11-04T00:00:04.000Z'),
    );

    // Act
    const list = parsed(text);

    // Assert
    expect(list.rows.map((row) => row.seq)).toEqual([1200, 1201]);
  });

  it.each([
    ['숫자가 아닌 이름', 'seg-a'],
    ['숫자 뒤 찌꺼기', '000012x'],
    ['빈 이름', ''],
  ])('URI 끝을 숫자로 못 읽으면(%s) 목록을 거부한다', (_label, name) => {
    // Arrange
    const text = playlist(MAP_A, ...segment(name, '2026-11-04T00:00:00.000Z'));

    // Act
    const list = parseEditorPlaylist(text, MEDIA_LIST_URL);

    // Assert
    expect(list).toBeNull();
  });

  it('seq 가 앞 줄보다 크지 않으면 목록을 거부한다', () => {
    // Arrange
    const text = playlist(
      MAP_A,
      ...segment('000011', '2026-11-04T00:00:00.000Z'),
      ...segment('000011', '2026-11-04T00:00:04.000Z'),
    );

    // Act
    const list = parseEditorPlaylist(text, MEDIA_LIST_URL);

    // Assert
    expect(list).toBeNull();
  });
});

describe('parseEditorPlaylist — 판 태그', () => {
  it('판 태그는 다음 태그까지 잇고 2 만 v2 · 그 밖의 값은 null 이다', () => {
    // Arrange
    const text = playlist(
      MAP_A,
      '#EXT-X-POKECLIP-TIMING:2',
      ...segment('000001', '2026-11-04T00:00:00.000Z'),
      ...segment('000002', '2026-11-04T00:00:04.000Z'),
      '#EXT-X-POKECLIP-TIMING:0',
      ...segment('000003', '2026-11-04T00:00:08.000Z'),
      ...segment('000004', '2026-11-04T00:00:12.000Z'),
      '#EXT-X-POKECLIP-TIMING:2',
      '#EXT-X-GAP',
      ...segment('000005', '2026-11-04T00:00:16.000Z'),
      ...segment('000006', '2026-11-04T00:00:20.000Z'),
      '#EXT-X-POKECLIP-TIMING:3',
      ...segment('000007', '2026-11-04T00:00:24.000Z'),
    );

    // Act
    const list = parsed(text);

    // Assert — 모르는 판(3)은 목록을 거부하지 않고 판 NULL 로 읽는다(fragmentTiming.ts 와 같은 규칙)
    expect(list.rows.map((row) => row.timing)).toEqual(['v2', 'v2', null, null, 'v2', 'v2', null]);
  });
});

describe('parseEditorPlaylist — 주소와 출처', () => {
  it('상대 URI 는 목록 주소를 기준으로 절대 주소로 바꾼다', () => {
    // Arrange
    const text = playlist(
      '#EXT-X-MAP:URI="init/A.mp4"',
      '#EXT-X-PROGRAM-DATE-TIME:2026-11-04T00:00:00.000Z',
      '#EXTINF:4.000,',
      'seg/000007.m4s',
    );

    // Act
    const list = parsed(text, 'http://127.0.0.1:8080/dvr/k/index.m3u8');

    // Assert
    expect(list.rows[0]!.uri).toBe('http://127.0.0.1:8080/dvr/k/seg/000007.m4s');
    expect(list.rows[0]!.mapUri).toBe('http://127.0.0.1:8080/dvr/k/init/A.mp4');
  });

  it.each([
    ['다른 호스트', 'https://evil.example/dvr/str/seg/000001.m4s'],
    ['다른 스킴', 'http://media.pokeclip.com/dvr/str/seg/000001.m4s'],
    ['다른 포트', 'https://media.pokeclip.com:8443/dvr/str/seg/000001.m4s'],
    ['data 주소', 'data:video/mp4;base64,AAAA'],
    ['file 주소', 'file:///dvr/str/seg/000001.m4s'],
    // blob: 은 origin 이 안쪽 주소의 출처라 출처 대조만으로는 통과한다 — 이름을 숫자로 두어 seq 검사로 걸리지 않게 한다
    ['같은 출처의 blob 주소', 'blob:https://media.pokeclip.com/000001.m4s'],
  ])('조각 URI 가 목록과 다른 출처(%s)면 목록을 거부한다', (_label, uri) => {
    // Arrange
    const text = playlist(
      MAP_A,
      '#EXT-X-PROGRAM-DATE-TIME:2026-11-04T00:00:00.000Z',
      '#EXTINF:4.000,',
      uri,
    );

    // Act
    const list = parseEditorPlaylist(text, MEDIA_LIST_URL);

    // Assert
    expect(list).toBeNull();
  });

  it('GAP 줄의 URI 도 다른 출처면 목록을 거부한다', () => {
    // Arrange
    const text = playlist(
      MAP_A,
      '#EXT-X-GAP',
      '#EXT-X-PROGRAM-DATE-TIME:2026-11-04T00:00:00.000Z',
      '#EXTINF:4.000,',
      'https://evil.example/dvr/str/seg/000001.m4s',
    );

    // Act
    const list = parseEditorPlaylist(text, MEDIA_LIST_URL);

    // Assert
    expect(list).toBeNull();
  });

  it('MAP URI 가 목록과 다른 출처면 목록을 거부한다', () => {
    // Arrange
    const text = playlist(
      '#EXT-X-MAP:URI="https://evil.example/dvr/str/init/A.mp4"',
      ...segment('000001', '2026-11-04T00:00:00.000Z'),
    );

    // Act
    const list = parseEditorPlaylist(text, MEDIA_LIST_URL);

    // Assert
    expect(list).toBeNull();
  });
});

describe('parseEditorPlaylist — 깨진 목록', () => {
  it.each([
    ['#EXTM3U 로 시작하지 않음', segment('000001', '2026-11-04T00:00:00.000Z').join('\n')],
    ['조각이 하나도 없음', playlist(MAP_A)],
    ['MAP 없는 조각', playlist(...segment('000001', '2026-11-04T00:00:00.000Z'))],
    [
      'URI 없는 MAP(앞 MAP 을 잇지 않는다)',
      playlist(
        MAP_A,
        ...segment('000001', '2026-11-04T00:00:00.000Z'),
        '#EXT-X-MAP:BYTERANGE="720@0"',
        ...segment('000002', '2026-11-04T00:00:04.000Z'),
      ),
    ],
    [
      'PDT 없는 조각',
      playlist(MAP_A, '#EXTINF:4.000,', 'https://media.pokeclip.com/dvr/str/seg/000001.m4s'),
    ],
    ['PDT 꼴이 아님', playlist(MAP_A, ...segment('000001', '2026-11-04 00:00:00'))],
    [
      'EXTINF 없는 조각',
      playlist(
        MAP_A,
        ...segment('000001', '2026-11-04T00:00:00.000Z').slice(0, 1),
        'https://media.pokeclip.com/dvr/str/seg/000001.m4s',
      ),
    ],
    [
      'EXTINF 가 숫자가 아님',
      playlist(MAP_A, ...segment('000001', '2026-11-04T00:00:00.000Z', 'abc')),
    ],
    ['EXTINF 가 0', playlist(MAP_A, ...segment('000001', '2026-11-04T00:00:00.000Z', '0.000'))],
  ])('%s 이면 목록을 거부한다', (_label, text) => {
    // Act
    const list = parseEditorPlaylist(text, MEDIA_LIST_URL);

    // Assert
    expect(list).toBeNull();
  });

  it('목록 주소가 주소 꼴이 아니면 목록을 거부한다', () => {
    // Arrange
    const text = fixture('vod_gap_key_mixed.m3u8');

    // Act
    const list = parseEditorPlaylist(text, 'not a url');

    // Assert
    expect(list).toBeNull();
  });
});
