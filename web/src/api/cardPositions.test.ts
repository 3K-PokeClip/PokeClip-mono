import { describe, expect, it } from 'vitest';
import type { LiveCardSnapshot } from '@/features/broadcast/livenow/useLiveMockState';
import { readPlayerAtMs } from './cardPositions';
import type { JumpCard } from './clipEditor';

// 응답 실물(PR-B1 머지 뒤 로컬 실험 DB · 2026-11-12 record.md 4.1절 · raw/responses/cards_mix_18081.json ·
// mark_web_live_v2.json). 웹이 받는 그대로 글자에서 읽는다 — 형에 없는 fileHead 도 함께 실려 온다.
/** C2 판 NULL row2 — 장면 있음 · fileHead 와 playerAtMs 둘 다 실림 */
const C2_WIRE =
  '{"id":2,"streamId":"sess-mix-1","source":"auto","streamTimestampMs":5000,' +
  '"window":{"startMs":4500,"endMs":9000},"score":1,"evidence":{"label":"C2-null-row2"},' +
  '"hidden":false,"eventSeq":1,"createdAt":"2026-11-11T19:58:53.221403Z",' +
  '"thumbnailUrl":null,"fileHead":{"atMs":1794426523900,"startMs":1794426523400,' +
  '"endMs":1794426527800},"playerAtMs":1794426524000}';
/** C6 창 끝이 라이브 머리 뒤 — fileHead 는 null 이어도 카드 시점 장면은 있어 playerAtMs 가 실림 */
const C6_WIRE =
  '{"id":6,"streamId":"sess-mix-1","source":"auto","streamTimestampMs":31000,' +
  '"window":{"startMs":29000,"endMs":33000},"score":1,' +
  '"evidence":{"label":"C6-end-beyond"},"hidden":false,"eventSeq":5,' +
  '"createdAt":"2026-11-11T19:58:53.221403Z","thumbnailUrl":null,"fileHead":null,' +
  '"playerAtMs":1794426550300}';
/** C5 라이브 머리 뒤 — 장면 없음. 두 칸 모두 생략이 아니라 null 로 실린다 */
const C5_WIRE =
  '{"id":5,"streamId":"sess-mix-1","source":"auto","streamTimestampMs":33000,' +
  '"window":{"startMs":32500,"endMs":34000},"score":1,' +
  '"evidence":{"label":"C5-live-head"},"hidden":false,"eventSeq":4,' +
  '"createdAt":"2026-11-11T19:58:53.221403Z","thumbnailUrl":null,"fileHead":null,' +
  '"playerAtMs":null}';
/** 라이브 웹 마크 답(판 2 구역) — 목록 · 통로 · 마크 답이 같은 꼴(라이브 Snapshot) */
const MARK_WEB_LIVE_WIRE =
  '{"id":8,"streamId":"sess-mix-1","source":"hotkey","streamTimestampMs":25413,' +
  '"window":{"startMs":0,"endMs":45413},"score":null,"evidence":null,"hidden":false,' +
  '"eventSeq":7,"createdAt":"2026-11-11T20:00:02.666160Z","thumbnailUrl":null,' +
  '"fileHead":null,"playerAtMs":1794426544713}';

/** 새 칸을 모르는 옛 clip 의 카드 — playerAtMs 칸 자체가 없다 */
const OLD_SERVER_CARD: JumpCard = {
  id: '2',
  streamId: 'sess-mix-1',
  source: 'auto',
  streamTimestampMs: 5000,
  window: { startMs: 4500, endMs: 9000 },
  score: 1,
  evidence: { label: 'C2-null-row2' },
  eventSeq: 1,
  hidden: false,
  thumbnailUrl: null,
  createdAt: '2026-11-11T19:58:53.221403Z',
};

// 라이브 Snapshot 형에는 아직 playerAtMs 칸이 없다(c13 이 더한다). 칸이 실려 오는 꼴을 형에 그대로 적는다.
type SnapshotWithPlayerAt = LiveCardSnapshot & { playerAtMs?: unknown };

describe('카드 응답의 플레이어용 PDT 읽기(readPlayerAtMs)', () => {
  it('JumpCard 실물에 장면이 있으면 playerAtMs 값을 그대로 돌려준다', () => {
    const card = JSON.parse(C2_WIRE) as JumpCard;

    const atMs = readPlayerAtMs(card);

    expect(atMs).toBe(1794426524000);
  });

  it('fileHead 가 null 이어도 playerAtMs 가 실리면 그 값을 돌려준다', () => {
    const card = JSON.parse(C6_WIRE) as JumpCard;

    const atMs = readPlayerAtMs(card);

    expect(atMs).toBe(1794426550300);
  });

  it('장면이 없어 null 로 실린 칸은 null 을 돌려준다(칸 없음과 구별한다)', () => {
    const card = JSON.parse(C5_WIRE) as JumpCard;

    const atMs = readPlayerAtMs(card);

    expect(atMs).toBeNull();
  });

  it('칸이 없는 옛 서버 카드는 undefined 를 돌려준다(null 과 구별한다)', () => {
    const atMs = readPlayerAtMs(OLD_SERVER_CARD);

    expect(atMs).toBeUndefined();
  });

  it('라이브 Snapshot 꼴(마크 답)도 같은 규칙으로 읽는다', () => {
    const snapshot = JSON.parse(MARK_WEB_LIVE_WIRE) as SnapshotWithPlayerAt;
    const nulled: SnapshotWithPlayerAt = { ...snapshot, playerAtMs: null };
    const { playerAtMs: _dropped, ...withoutField } = snapshot;
    const absent: SnapshotWithPlayerAt = withoutField;

    expect(readPlayerAtMs(snapshot)).toBe(1794426544713);
    expect(readPlayerAtMs(nulled)).toBeNull();
    expect(readPlayerAtMs(absent)).toBeUndefined();
  });

  it.each([
    ['숫자 꼴 문자열', '1794426524000'],
    ['NaN', Number.NaN],
    ['Infinity', Number.POSITIVE_INFINITY],
    ['-Infinity', Number.NEGATIVE_INFINITY],
    ['객체', { atMs: 1794426524000 }],
    ['불리언', true],
  ])('숫자가 아닌 값(%s)은 칸 없음으로 친다', (_label, value) => {
    const card = { ...(JSON.parse(C2_WIRE) as JumpCard), playerAtMs: value } as {
      playerAtMs?: unknown;
    };

    const atMs = readPlayerAtMs(card);

    expect(atMs).toBeUndefined();
  });
});
