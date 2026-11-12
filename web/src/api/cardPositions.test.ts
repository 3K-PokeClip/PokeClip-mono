import { describe, expect, it } from 'vitest';
import type { LiveCardSnapshot } from '@/features/broadcast/livenow/useLiveMockState';
import { readAudioCapture, readPlayerAtMs, readPlayerWindow } from './cardPositions';
import type { JumpCard, SegmentAudioAccess } from './clipEditor';

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

// PR-B2 S-c6 확정 꼴(pdt-render-67 2026-11-12 20:42 통지 · 995bbc91 JumpCardSnapshotSceneTest P37 예시) — fileHead 칸은 거뒀고
// playerWindow 는 창 두 끝 장면의 목록 PDT 다. 판 NULL 예시: 카드 1500 · 창 [1000, 9000] → playerAtMs 101500 · 창 [101000, 115000].
/** 장면이 다 있는 카드 — 두 칸이 선다 */
const SCENE_WIRE =
  '{"id":1,"streamId":"s-1","source":"hotkey","streamTimestampMs":1500,' +
  '"window":{"startMs":1000,"endMs":9000},"score":null,"evidence":null,"hidden":false,' +
  '"eventSeq":1,"createdAt":"1970-01-01T00:00:00Z","thumbnailUrl":null,' +
  '"playerAtMs":101500,"playerWindow":{"startMs":101000,"endMs":115000}}';
/** 창 끝이 라이브 머리 뒤 — 카드 장면은 있어 playerAtMs 는 서고 창 칸만 null(반쪽을 싣지 않는다) */
const WINDOW_END_BEYOND_WIRE =
  '{"id":1,"streamId":"s-1","source":"hotkey","streamTimestampMs":2000,' +
  '"window":{"startMs":1000,"endMs":30000},"score":null,"evidence":null,"hidden":false,' +
  '"eventSeq":1,"createdAt":"1970-01-01T00:00:00Z","thumbnailUrl":null,' +
  '"playerAtMs":102000,"playerWindow":null}';
/** 카드 장면 없음(라이브 머리 · scene_unavailable) — 두 칸 모두 null */
const NO_SCENE_WIRE =
  '{"id":1,"streamId":"s-1","source":"hotkey","streamTimestampMs":2000,' +
  '"window":{"startMs":1000,"endMs":3000},"score":null,"evidence":null,"hidden":false,' +
  '"eventSeq":1,"createdAt":"1970-01-01T00:00:00Z","thumbnailUrl":null,' +
  '"playerAtMs":null,"playerWindow":null}';
/** 판 NULL 시각 역행으로 끝이 이르면 서버가 끝을 시작에 묶는다 — 길이 0 창도 값이다 */
const CLAMPED_WINDOW_WIRE =
  '{"id":1,"streamId":"s-1","source":"hotkey","streamTimestampMs":3500,' +
  '"window":{"startMs":3000,"endMs":5000},"score":null,"evidence":null,"hidden":false,' +
  '"eventSeq":1,"createdAt":"1970-01-01T00:00:00Z","thumbnailUrl":null,' +
  '"playerAtMs":103500,"playerWindow":{"startMs":103000,"endMs":103000}}';

describe('카드 응답의 플레이어 창 양끝 읽기(readPlayerWindow)', () => {
  it('두 끝이 실리면 그 값을 그대로 돌려준다', () => {
    const card = JSON.parse(SCENE_WIRE) as JumpCard;

    const window = readPlayerWindow(card);

    expect(window).toEqual({ startMs: 101000, endMs: 115000 });
  });

  it('창 끝 장면이 없어 창 칸만 null 이면 null 을 돌려주고 playerAtMs 는 그대로 선다', () => {
    const card = JSON.parse(WINDOW_END_BEYOND_WIRE) as JumpCard;

    expect(readPlayerWindow(card)).toBeNull();
    expect(readPlayerAtMs(card)).toBe(102000);
  });

  it('카드 장면이 없어 두 칸 모두 null 이면 둘 다 null 이다', () => {
    const card = JSON.parse(NO_SCENE_WIRE) as JumpCard;

    expect(readPlayerWindow(card)).toBeNull();
    expect(readPlayerAtMs(card)).toBeNull();
  });

  it('칸이 없는 옛 서버 카드는 undefined 를 돌려준다(null 과 구별한다 — estimate 로 떨어지는 신호)', () => {
    const window = readPlayerWindow(OLD_SERVER_CARD);

    expect(window).toBeUndefined();
  });

  it('끝이 시작과 같은 창(서버가 묶은 창)은 받는다', () => {
    const card = JSON.parse(CLAMPED_WINDOW_WIRE) as JumpCard;

    const window = readPlayerWindow(card);

    expect(window).toEqual({ startMs: 103000, endMs: 103000 });
  });

  it('끝이 시작보다 이른 창은 믿을 수 없어 칸 없음으로 친다', () => {
    const card = { playerWindow: { startMs: 115000, endMs: 101000 } };

    const window = readPlayerWindow(card);

    expect(window).toBeUndefined();
  });

  it.each([
    ['숫자 꼴 문자열 끝', { startMs: 101000, endMs: '115000' }],
    ['NaN 시작', { startMs: Number.NaN, endMs: 115000 }],
    ['Infinity 끝', { startMs: 101000, endMs: Number.POSITIVE_INFINITY }],
    ['끝 없음', { startMs: 101000 }],
    ['배열', [101000, 115000]],
    ['숫자', 101000],
    ['문자열', '101000-115000'],
  ])('숫자 두 끝이 아닌 값(%s)은 칸 없음으로 친다', (_label, value) => {
    const card = { playerWindow: value as unknown };

    const window = readPlayerWindow(card);

    expect(window).toBeUndefined();
  });

  it('거둔 칸(fileHead)이 함께 실려 와도 같은 값을 돌려준다', () => {
    const card = JSON.parse(SCENE_WIRE) as JumpCard;
    const withRetired = {
      ...card,
      fileHead: { atMs: 101500, startMs: 101000, endMs: 109000 },
    } as JumpCard;

    expect(readPlayerWindow(withRetired)).toEqual({ startMs: 101000, endMs: 115000 });
    expect(readPlayerAtMs(withRetired)).toBe(101500);
  });

  it('창 안의 덧붙은 칸은 버리고 두 끝만 새 객체로 돌려준다', () => {
    const wire = { startMs: 101000, endMs: 115000, axis: 'fileHead' };

    const window = readPlayerWindow({ playerWindow: wire });

    expect(window).toStrictEqual({ startMs: 101000, endMs: 115000 });
    expect(window).not.toBe(wire);
  });

  it('라이브 Snapshot 꼴(마크 답)도 같은 규칙으로 읽는다', () => {
    const snapshot = {
      ...(JSON.parse(MARK_WEB_LIVE_WIRE) as SnapshotWithPlayerAt),
      playerWindow: { startMs: 1794426519300, endMs: 1794426564713 },
    } as SnapshotWithPlayerAt & { playerWindow?: unknown };

    expect(readPlayerWindow(snapshot)).toEqual({
      startMs: 1794426519300,
      endMs: 1794426564713,
    });
  });
});

// PR-B2 S-c7 확정 꼴(pdt-render-67 2026-11-12 21:55 통지 · 995bbc91 SegmentAudioCaptureTest · audio-access-develop.json).
// attached 가 아니면 develop 응답 바이트 끝에 audioCapture 만 붙는다. url 은 기대값 파일의 자리표시 그대로다.
/** not_ready — 확보 조각 칸이 없다 */
const AUDIO_NOT_READY_WIRE =
  '{"expiresAt":"<EXPIRES_AT>","segments":[{"seq":2,"startAtMs":1792713610000,"durationMs":4000,' +
  '"url":"<URL>"},{"seq":3,"startAtMs":1792713614000,"durationMs":4000,"url":"<URL>"}],' +
  '"audioCapture":"not_ready"}';
/** attached — 창 [10000, 11000) 과 겹치는 조각은 seq 2 하나 · 확보 조각 seq 3 은 뿌리 칸에 따로 */
const AUDIO_ATTACHED_WIRE =
  '{"expiresAt":"<EXPIRES_AT>","segments":[{"seq":2,"startAtMs":1792713610000,"durationMs":4000,' +
  '"url":"<URL>"}],"audioCapture":"attached","audioCaptureSegment":{"seq":3,' +
  '"startAtMs":1792713614000,"durationMs":4000,"url":"<URL>"}}';
/** skipped — 확보 없이 진행 */
const AUDIO_SKIPPED_WIRE =
  '{"expiresAt":"<EXPIRES_AT>","segments":[{"seq":2,"startAtMs":1792713610000,"durationMs":4000,' +
  '"url":"<URL>"}],"audioCapture":"skipped"}';
/** 이 칸을 모르는 옛 clip(develop) 응답 — audio-access-develop.json 그대로 */
const AUDIO_DEVELOP_WIRE =
  '{"expiresAt":"<EXPIRES_AT>","segments":[{"seq":2,"startAtMs":1792713610000,"durationMs":4000,' +
  '"url":"<URL>"},{"seq":3,"startAtMs":1792713614000,"durationMs":4000,"url":"<URL>"}]}';

const CAPTURE_SEGMENT = { seq: 3, startAtMs: 1792713614000, durationMs: 4000, url: '<URL>' };

describe('소리 주소 응답의 끝 소리 확보 상태 읽기(readAudioCapture)', () => {
  it('attached 면 확보 조각을 함께 돌려준다', () => {
    const access = JSON.parse(AUDIO_ATTACHED_WIRE) as SegmentAudioAccess;

    const capture = readAudioCapture(access);

    expect(capture).toEqual({ kind: 'attached', segment: CAPTURE_SEGMENT });
  });

  it('not_ready 면 기다림이다', () => {
    const access = JSON.parse(AUDIO_NOT_READY_WIRE) as SegmentAudioAccess;

    const capture = readAudioCapture(access);

    expect(capture).toEqual({ kind: 'notReady' });
  });

  it('skipped 면 확보 없음이다', () => {
    const access = JSON.parse(AUDIO_SKIPPED_WIRE) as SegmentAudioAccess;

    const capture = readAudioCapture(access);

    expect(capture).toEqual({ kind: 'none' });
  });

  it('칸이 없는 옛 clip 응답은 확보 없음이다', () => {
    const access = JSON.parse(AUDIO_DEVELOP_WIRE) as SegmentAudioAccess;

    const capture = readAudioCapture(access);

    expect(capture).toEqual({ kind: 'none' });
  });

  it.each([
    ['모르는 문자열', 'pending'],
    ['대문자', 'ATTACHED'],
    ['null', null],
    ['숫자', 1],
  ])('모르는 값(%s)은 확보 없음으로 친다', (_label, value) => {
    const access = { ...(JSON.parse(AUDIO_ATTACHED_WIRE) as object), audioCapture: value };

    const capture = readAudioCapture(access);

    expect(capture).toEqual({ kind: 'none' });
  });

  it.each([
    ['not_ready', 'not_ready', { kind: 'notReady' }],
    ['skipped', 'skipped', { kind: 'none' }],
  ])('%s 인데 확보 조각이 실려 와도 무시한다', (_label, value, expected) => {
    const access = { ...(JSON.parse(AUDIO_ATTACHED_WIRE) as object), audioCapture: value };

    const capture = readAudioCapture(access);

    expect(capture).toEqual(expected);
  });

  it.each([
    ['칸 없음', undefined],
    ['null', null],
    ['url 없음', { seq: 3, startAtMs: 1792713614000, durationMs: 4000 }],
    ['seq 문자열', { seq: '3', startAtMs: 1792713614000, durationMs: 4000, url: '<URL>' }],
    ['시작 NaN', { seq: 3, startAtMs: Number.NaN, durationMs: 4000, url: '<URL>' }],
    ['길이 Infinity', { seq: 3, startAtMs: 1792713614000, durationMs: Infinity, url: '<URL>' }],
  ])('attached 인데 확보 조각(%s)을 못 읽으면 확보 없음으로 친다', (_label, segment) => {
    const access = { audioCapture: 'attached', audioCaptureSegment: segment as unknown };

    const capture = readAudioCapture(access);

    expect(capture).toEqual({ kind: 'none' });
  });

  it('확보 조각의 덧붙은 칸(거둔 role 등)은 버리고 네 칸만 새 객체로 돌려준다', () => {
    const wire = { ...CAPTURE_SEGMENT, role: 'capture', startWallUtcMs: 1792713613000 };

    const capture = readAudioCapture({ audioCapture: 'attached', audioCaptureSegment: wire });

    expect(capture).toStrictEqual({ kind: 'attached', segment: CAPTURE_SEGMENT });
    expect(capture.kind === 'attached' && capture.segment).not.toBe(wire);
  });

  it('거둔 뿌리 칸(axis · gapEvidence)이 함께 실려 와도 같은 결과다', () => {
    const access = {
      ...(JSON.parse(AUDIO_ATTACHED_WIRE) as SegmentAudioAccess),
      axis: 'fileHead',
      gapEvidence: [],
    };

    const capture = readAudioCapture(access);

    expect(capture).toEqual({ kind: 'attached', segment: CAPTURE_SEGMENT });
  });
});
