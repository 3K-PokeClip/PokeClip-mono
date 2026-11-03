import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { mediaTimeAt, programDateAt, type DatedFragment } from './dvrWindow';
import { TIMING_TAG, datedFragments, type HlsFragmentLike, type StartOf } from './fragmentTiming';

// 공유 시험 표(services/ledger-timeline 의 ledger-timeline-cases.json · 설계 r7 3.5 · 10절 c6)의 F 사례 가운데 원점이
// 판 2 PDT 인 것(axis pdt · offsetOrigin VIDEO_START — Q-R5)으로 플레이어 환산을 견준다. 표의 행을 판 2 목록의 조각으로
// 만들어 datedFragments 에 넣고, 표 시각 t 를 mediaTimeAt · programDateAt 으로 옮긴 값이 표의 자리와 같은지 본다.
// 다른 갈래(length · audioJoin)와 다른 축(wall · startPts)은 편집기 · clip 몫이라 건너뛴다. 표는 세 곳이 읽는 계약이라
// 읽는 쪽은 fail-closed 다 — 모르는 version · kind · axis 는 표 전체를, F · pdt 안의 모르는 op · expect 꼴은 그 사례를
// 실패시킨다. 조용히 건너뛰면 표가 자라도 초록이 된다.

const TABLE_PATH = join(
  __dirname,
  '..',
  '..',
  '..',
  '..',
  'services',
  'ledger-timeline',
  'src',
  'test',
  'resources',
  'ledger-timeline-cases.json',
);

/** 표 뿌리 판(services 쪽 읽개 LedgerTimelineCases.VERSION 과 같다) */
const TABLE_VERSION = 2;
const KINDS: ReadonlySet<string> = new Set(['F', 'length', 'audioJoin']);
const AXES: ReadonlySet<string> = new Set(['wall', 'pdt', 'startPts']);
const MEDIA_KEYS = ['offsetMs', 'offsetOrigin', 'place', 'seq'];
const GAP_KEYS = ['afterSeq', 'beforeSeq', 'gapMs', 'intoGapMs', 'place'];

/** 판 2 PDT 축의 원점 — 영상 첫 표본(설계 r7 3.2 표) */
const PDT_ORIGIN = 'VIDEO_START';

/** 표의 PDT 0 을 놓는 벽시각(epoch ms) */
const PDT0 = Date.parse('2026-11-03T05:00:00.000Z');

/**
 * 해체 뒤 hls.js 가 덮은 frag.start · duration 의 어긋남(초). 기준 트랙(소리)이 영상보다 먼저 시작하고 모든 트랙 끝이
 * 영상 끝보다 늦은 자리를 흉내 낸다 — 환산이 이 값을 쓰면 표와 갈린다
 */
const HLS_START_LEAD_S = 0.05;
const HLS_DURATION_EXTRA_S = 0.1;

/** 그 조각 첫 영상 표본의 구성 시차(초) — 판 2 환산은 쓰지 않는다 */
const FIRST_CTO_S = 1 / 15;

interface LedgerRow {
  seq: number;
  startAtMs: number;
  durationMs: number;
  continuesPrevious: boolean;
}

type ExpectedPlace =
  | { place: 'media'; seq: number; offsetMs: number }
  | { place: 'gap'; beforeSeq: number; afterSeq: number; intoGapMs: number; gapMs: number };

interface PdtCase {
  id: string;
  op: 'locate' | 'locateEnd';
  rows: LedgerRow[];
  t: number;
  expect: ExpectedPlace;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function numberAt(record: Record<string, unknown>, key: string, where: string): number {
  const value = record[key];
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new Error(`${where}: ${key} 는 정수여야 한다: ${JSON.stringify(value)}`);
  }
  return value;
}

function requireKeys(record: Record<string, unknown>, keys: readonly string[], where: string) {
  const actual = Object.keys(record).sort();
  if (actual.join(',') !== keys.join(',')) {
    throw new Error(
      `${where}: 모르는 expect 꼴 ${JSON.stringify(record)} — 칸은 ${keys.join(' · ')} 이다`,
    );
  }
}

/**
 * 표 전체를 읽어 F · pdt 사례의 원본을 돌려준다. 표 전체가 깨진 것(version · id · kind · axis)은 여기서 던진다
 */
function pdtCasesOf(table: unknown): Array<Record<string, unknown>> {
  if (!isRecord(table) || table['version'] !== TABLE_VERSION) {
    throw new Error(`표 version 이 ${TABLE_VERSION} 이 아니다`);
  }
  const cases = table['cases'];
  if (!Array.isArray(cases)) throw new Error('표에 cases 배열이 없다');
  const ids = new Set<string>();
  const pdtCases: Array<Record<string, unknown>> = [];
  for (const raw of cases) {
    if (!isRecord(raw)) throw new Error(`사례가 객체가 아니다: ${JSON.stringify(raw)}`);
    const id = raw['id'];
    if (typeof id !== 'string' || id === '' || ids.has(id)) {
      throw new Error(`사례 id 가 비었거나 겹친다: ${JSON.stringify(id)}`);
    }
    ids.add(id);
    const kind = raw['kind'];
    if (typeof kind !== 'string' || !KINDS.has(kind)) {
      throw new Error(`${id}: 모르는 kind: ${JSON.stringify(kind)}`);
    }
    if (kind !== 'F') continue;
    const axis = raw['axis'];
    if (typeof axis !== 'string' || !AXES.has(axis)) {
      throw new Error(`${id}: 모르는 axis: ${JSON.stringify(axis)}`);
    }
    if (axis === 'pdt') pdtCases.push(raw);
  }
  if (pdtCases.length === 0) throw new Error('F · pdt 사례가 하나도 없다');
  return pdtCases;
}

function rowOf(raw: unknown, where: string): LedgerRow {
  if (!isRecord(raw)) throw new Error(`${where}: 행이 객체가 아니다`);
  if (raw['timingVersion'] !== 2) {
    throw new Error(`${where}: pdt 사례의 행은 판 2 다: ${JSON.stringify(raw['timingVersion'])}`);
  }
  const continuesPrevious = raw['continuesPrevious'];
  if (typeof continuesPrevious !== 'boolean') {
    throw new Error(`${where}: continuesPrevious 가 참거짓이 아니다`);
  }
  return {
    seq: numberAt(raw, 'seq', where),
    startAtMs: numberAt(raw, 'startAtMs', where),
    durationMs: numberAt(raw, 'durationMs', where),
    continuesPrevious,
  };
}

function expectedPlaceOf(raw: unknown, id: string): ExpectedPlace {
  if (!isRecord(raw)) throw new Error(`${id}: expect 가 객체가 아니다`);
  if (raw['place'] === 'media') {
    requireKeys(raw, MEDIA_KEYS, id);
    if (raw['offsetOrigin'] !== PDT_ORIGIN) {
      throw new Error(
        `${id}: pdt 사례의 원점은 ${PDT_ORIGIN} 다: ${JSON.stringify(raw['offsetOrigin'])}`,
      );
    }
    return {
      place: 'media',
      seq: numberAt(raw, 'seq', id),
      offsetMs: numberAt(raw, 'offsetMs', id),
    };
  }
  if (raw['place'] === 'gap') {
    requireKeys(raw, GAP_KEYS, id);
    return {
      place: 'gap',
      beforeSeq: numberAt(raw, 'beforeSeq', id),
      afterSeq: numberAt(raw, 'afterSeq', id),
      intoGapMs: numberAt(raw, 'intoGapMs', id),
      gapMs: numberAt(raw, 'gapMs', id),
    };
  }
  throw new Error(`${id}: 이 시험이 견주는 법을 모르는 place: ${JSON.stringify(raw['place'])}`);
}

/** F · pdt 사례 하나를 읽는다 — 모르는 op · expect 꼴 · 판 2 가 아닌 행은 던져 그 사례를 실패시킨다 */
function pdtCaseOf(raw: Record<string, unknown>): PdtCase {
  const id = String(raw['id']);
  const op = raw['op'];
  if (op !== 'locate' && op !== 'locateEnd') {
    throw new Error(`${id}: 모르는 op: ${JSON.stringify(op)}`);
  }
  const rawRows = raw['rows'];
  if (!Array.isArray(rawRows) || rawRows.length === 0) throw new Error(`${id}: rows 가 비었다`);
  const rows = rawRows.map((row, i) => rowOf(row, `${id} 행 ${i}`));
  rows.forEach((row, i) => {
    const prev = rows[i - 1];
    if (prev !== undefined && row.seq <= prev.seq) {
      throw new Error(`${id}: 행이 seq 오름차순이 아니다: ${prev.seq} 뒤에 ${row.seq}`);
    }
  });
  return { id, op, rows, t: numberAt(raw, 't', id), expect: expectedPlaceOf(raw['expect'], id) };
}

/**
 * 행마다 재생 위치(초) — 판 2 조각의 v_k 를 앞 행 길이의 누적으로 둔다. 이어진 이웃이든 끊긴 이웃이든 매체는 맞닿는다:
 * hls.js 파서는 DISCONTINUITY 와 무관하게 조각 시작을 목록 순서의 EXTINF 누적으로 놓는다(m3u8-parser setStart(totalduration))
 */
function videoStartsOf(rows: readonly LedgerRow[]): number[] {
  let at = 0;
  return rows.map((row) => {
    const start = at;
    at += row.durationMs / 1000;
    return start;
  });
}

/**
 * 표의 행 → 판 2 /dvr · /vod 목록의 hls.js 조각. 판 태그는 첫 조각에 · 원래 EXTINF 는 조각마다 싣고, 끊긴 이웃 앞에서
 * cc 를 올린다(EXT-X-DISCONTINUITY). hls.js 의 start · duration 은 해체 뒤 덮인 값처럼 일부러 어긋나게 둔다
 */
function hlsFragmentsOf(
  rows: readonly LedgerRow[],
  videoStarts: readonly number[],
): HlsFragmentLike[] {
  let cc = 0;
  return rows.map((row, i) => {
    if (i > 0 && !row.continuesPrevious) cc += 1;
    const infS = row.durationMs / 1000;
    const tagList = [['INF', infS.toFixed(3)]];
    if (i === 0) tagList.unshift([TIMING_TAG, '2']);
    return {
      sn: row.seq,
      cc,
      start: (videoStarts[i] ?? 0) - HLS_START_LEAD_S,
      duration: infS + HLS_DURATION_EXTRA_S,
      programDateTime: PDT0 + row.startAtMs,
      gap: false,
      tagList,
    };
  });
}

/** sn(= seq) → v_k. 모든 조각을 해체했다고 본다 */
function startsBySeq(rows: readonly LedgerRow[], videoStarts: readonly number[]): StartOf {
  const bySeq = new Map(rows.map((row, i) => [row.seq, videoStarts[i] ?? 0]));
  return (frag) => {
    const videoStartS = typeof frag.sn === 'number' ? bySeq.get(frag.sn) : undefined;
    return videoStartS === undefined ? null : { videoStartS, firstCtoS: FIRST_CTO_S };
  };
}

/** 초 → ms 환산의 부동소수 오차를 흡수하는 여유(초) */
const SECONDS_EPSILON = 1e-9;

/** 재생 위치 m(초)이 든 조각 — 시작 ≤ m 인 마지막 조각(반개 [start, start + duration)) */
function fragmentIndexAt(fragments: readonly DatedFragment[], m: number): number {
  return fragments.findLastIndex((frag) => frag.start <= m + SECONDS_EPSILON);
}

function toMs(seconds: number): number {
  return Math.round(seconds * 1000);
}

/**
 * 반개 끝(locateEnd) — 플레이어에는 대응 함수가 없다(재생기는 점으로 옮겨 간다). 누적 배치라 표의 (k, dur(k)) 와
 * (k + 1, 0) 은 이웃이 이어졌든 끊겼든 같은 재생 위치라, 표 자리를 재생 위치로 옮겨 mediaTimeAt 값과 견준다
 */
function expectEndAt(c: PdtCase, videoStarts: readonly number[], at: number) {
  const expected = c.expect;
  if (expected.place !== 'media') {
    throw new Error(`${c.id}: locateEnd 의 ${expected.place} 를 견주는 법을 이 시험은 모른다`);
  }
  const i = c.rows.findIndex((row) => row.seq === expected.seq);
  expect(i).toBeGreaterThanOrEqual(0);
  expect(toMs(at)).toBe(toMs(videoStarts[i] ?? Number.NaN) + expected.offsetMs);
}

/**
 * 시작 쪽(locate) — 재생 위치가 든 조각과 조각 안 거리가 표 자리와 같고, 거꾸로 그 자리의 PDT 는 그 행 PDT + 거리다.
 * 거리는 시험이 지은 참값 v_k(videoStarts)에서 센다 — 플레이어가 낸 조각 시작에서 세면 그 시작이 틀려도 상쇄된다
 */
function expectPlaceAt(
  c: PdtCase,
  fragments: readonly DatedFragment[],
  videoStarts: readonly number[],
  at: number,
) {
  const j = fragmentIndexAt(fragments, at);
  const seq = c.rows[j]?.seq;
  const offsetMs = toMs(at) - toMs(videoStarts[j] ?? Number.NaN);
  if (c.expect.place === 'media') {
    expect({ seq, offsetMs }).toEqual({ seq: c.expect.seq, offsetMs: c.expect.offsetMs });
    const rowStartMs = c.rows[j]?.startAtMs ?? Number.NaN;
    expect(programDateAt(fragments, at)).toBe(PDT0 + rowStartMs + offsetMs);
    return;
  }
  // 공백 — 플레이어는 공백 안 시각을 뒤 조각 시작으로 옮긴다(mediaTimeAt). 앞 조각 끝의 PDT 는 원래 EXTINF 로 센다
  const before = fragments[j - 1];
  const beforeEndMs =
    (before?.programDateTime ?? Number.NaN) + toMs(before?.duration ?? Number.NaN);
  const afterStartMs = programDateAt(fragments, at) ?? Number.NaN;
  expect({
    beforeSeq: c.rows[j - 1]?.seq,
    afterSeq: seq,
    offsetMs,
    intoGapMs: PDT0 + c.t - beforeEndMs,
    gapMs: afterStartMs - beforeEndMs,
  }).toEqual({
    beforeSeq: c.expect.beforeSeq,
    afterSeq: c.expect.afterSeq,
    offsetMs: 0,
    intoGapMs: c.expect.intoGapMs,
    gapMs: c.expect.gapMs,
  });
}

describe('공유 시험 표 읽기 — fail-closed', () => {
  const media = { place: 'media', seq: 1, offsetMs: 0, offsetOrigin: PDT_ORIGIN };
  const row = {
    seq: 1,
    startAtMs: 0,
    durationMs: 4000,
    continuesPrevious: false,
    timingVersion: 2,
  };
  const pdtCase = {
    id: 'x',
    kind: 'F',
    op: 'locate',
    axis: 'pdt',
    rows: [row],
    t: 0,
    expect: media,
  };
  const table = (cases: unknown[]) => ({ version: TABLE_VERSION, cases });

  it('F · pdt 만 고르고 다른 갈래 · 축은 건너뛴다', () => {
    const wall = { ...pdtCase, id: 'w', axis: 'wall' };
    const length = { id: 'l', kind: 'length' };
    expect(pdtCasesOf(table([wall, length, pdtCase])).map((c) => c['id'])).toEqual(['x']);
  });

  it.each([
    ['version 이 다르다', { version: 3, cases: [pdtCase] }],
    ['모르는 kind', table([pdtCase, { id: 'k', kind: 'G' }])],
    ['모르는 axis', table([pdtCase, { ...pdtCase, id: 'a', axis: 'pts' }])],
    ['id 가 겹친다', table([pdtCase, pdtCase])],
    ['F · pdt 사례가 없다', table([{ id: 'l', kind: 'length' }])],
  ])('표 전체를 거절한다 — %s', (_name, bad) => {
    expect(() => pdtCasesOf(bad)).toThrow();
  });

  it.each([
    ['모르는 op', { ...pdtCase, op: 'locateStart' }],
    ['모르는 place', { ...pdtCase, expect: { place: 'afterLast' } }],
    ['media 에 모르는 칸', { ...pdtCase, expect: { ...media, extra: 1 } }],
    ['원점이 파일 머리', { ...pdtCase, expect: { ...media, offsetOrigin: 'FILE_HEAD' } }],
    ['판 NULL 행', { ...pdtCase, rows: [{ ...row, timingVersion: null }] }],
    ['seq 가 거꾸로', { ...pdtCase, rows: [{ ...row, seq: 2 }, row] }],
  ])('그 사례를 거절한다 — %s', (_name, bad) => {
    expect(() => pdtCaseOf(bad)).toThrow();
  });
});

describe('공유 시험 표의 F · pdt 사례 — 플레이어 환산(datedFragments · mediaTimeAt · programDateAt)', () => {
  const pdtCases = pdtCasesOf(JSON.parse(readFileSync(TABLE_PATH, 'utf8')));

  it.each(pdtCases.map((raw) => [String(raw['id']), raw] as const))('%s', (_id, raw) => {
    const c = pdtCaseOf(raw);
    const videoStarts = videoStartsOf(c.rows);
    const fragments = datedFragments(
      hlsFragmentsOf(c.rows, videoStarts),
      'rewind',
      startsBySeq(c.rows, videoStarts),
    );
    const m = mediaTimeAt(fragments, PDT0 + c.t);
    expect(m).not.toBeNull();
    const at = m ?? Number.NaN;
    if (c.op === 'locateEnd') {
      expectEndAt(c, videoStarts, at);
    } else {
      expectPlaceAt(c, fragments, videoStarts, at);
    }
  });
});
