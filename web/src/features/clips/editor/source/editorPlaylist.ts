import { TIMING_TAG } from '@/features/player/fragmentTiming';

// 편집기가 여는 되감기 · 다시보기 목록(③ 재포장본 m3u8)을 조각과 런으로 읽는다(POK-473 설계 prw2 4절). 목록은 1번 발행기가
// 쓴다(media/internal/rewind/render.go Render) — 조각마다 DISCONTINUITY · MAP · 판 태그 · GAP · PDT · EXTINF · URI 줄 순서다.
// 런은 바이트를 이어 붙여 한 영상으로 다시 쌀 수 있는 조각 묶음이라 GAP · DISCONTINUITY · MAP 바뀜에서 끊는다. GAP 줄은
// 바이트를 받지 않는 자리라 런에 넣지 않고 rows 에만 남긴다. 회차 안 끊김(표지 없는 PDT 건너뜀)은 다음 층(개요 · F 규칙)이
// PDT 로 본다. 목록이 이 꼴을 어기면 고쳐 읽지 않고 통째로 거부한다(null) — 틀린 seq · 주소로 편집하느니 열지 않는다.

/** 목록 조각 한 줄 */
export interface PlaylistSegment {
  /** 조각 번호 — URI 마지막 경로 조각의 숫자를 정수로(0 채움 자릿수와 무관 · pdt-main M-1) */
  seq: number;
  /** 조각 바이트 주소(목록 주소 기준 절대 주소 · 목록과 같은 출처) */
  uri: string;
  /** 이 조각에 걸린 EXT-X-MAP 초기화 조각 주소(절대 · 같은 출처) */
  mapUri: string;
  /** EXT-X-PROGRAM-DATE-TIME(epoch ms) — 편집기 상태 축 T */
  pdtMs: number;
  /** EXTINF(ms) — 장부 duration_ms */
  durationMs: number;
  /** 판 태그로 정한 시각 판 — 'v2' 는 판 2, null 은 판 NULL · 태그 없는 목록 */
  timing: 'v2' | null;
  /** EXT-X-GAP 줄인가 — 바이트를 받지 않는다 */
  gap: boolean;
}

export interface EditorPlaylist {
  /** 이어 쌀 수 있는 조각 묶음들(목록 차례 · GAP 줄 없음) */
  runs: readonly (readonly PlaylistSegment[])[];
  /** 목록의 모든 조각 줄(GAP 줄 포함 · 목록 차례) */
  rows: readonly PlaylistSegment[];
}

/** PDT 꼴 — 제어면 답 검사(api/rewindStatus.ts PDT_RE)와 같다. 발행기 FormatPDT 는 UTC 밀리초 셋째 자리까지 쓴다 */
const PDT_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;
/** EXTINF 의 길이 칸(초) — 발행기는 `4.000,` 처럼 소수 셋째 자리까지 쓴다 */
const EXTINF_RE = /^(\d+(?:\.\d+)?),/;
const MAP_URI_RE = /\bURI="([^"]*)"/;
/** URI 마지막 경로 조각 — 숫자 이름 + 확장자(`000398.m4s` · `1000000.m4s`) */
const SEQ_NAME_RE = /^(\d+)\.[A-Za-z0-9]+$/;
const TIMING_PREFIX = `#${TIMING_TAG}:`;
/** 판 2 로 읽는 태그 값 — 그 밖(`0` · 모르는 값)은 판 NULL 이다(fragmentTiming.ts 와 같은 규칙) */
const VERSION_2_VALUE = '2';

/** 다음 URI 줄까지 모은 그 조각만의 태그 */
interface SegmentTags {
  discontinuity: boolean;
  gap: boolean;
  pdtMs: number | null;
  durationMs: number | null;
}

/** 줄을 차례로 읽는 동안의 상태. MAP · 판은 다음 태그가 올 때까지 뒤 조각에 이어진다 */
interface ReadState {
  mapUri: string | null;
  timing: 'v2' | null;
  tags: SegmentTags;
}

/** 읽은 조각 한 줄 — DISCONTINUITY 는 런을 끊는 데만 쓰고 버린다 */
interface ReadRow {
  segment: PlaylistSegment;
  discontinuity: boolean;
}

const NO_TAGS: SegmentTags = { discontinuity: false, gap: false, pdtMs: null, durationMs: null };

/**
 * raw 를 목록 주소 기준으로 풀어 같은 출처면 절대 주소, 아니면 null. blob: 은 origin 이 안쪽 주소의 출처라 같은 출처로
 * 보이므로 스킴도 목록(http · https)과 같아야 한다
 */
function sameOriginUrl(raw: string, list: URL): string | null {
  try {
    const url = new URL(raw, list);
    return url.protocol === list.protocol && url.origin === list.origin ? url.href : null;
  } catch {
    return null;
  }
}

/** URI 마지막 경로 조각의 숫자. 숫자 이름이 아니면 null */
function seqOf(uri: string): number | null {
  const name = new URL(uri).pathname.split('/').at(-1) ?? '';
  const digits = SEQ_NAME_RE.exec(name)?.[1];
  if (digits === undefined) return null;
  const seq = Number(digits);
  return Number.isSafeInteger(seq) ? seq : null;
}

function pdtMsOf(value: string): number | null {
  if (!PDT_RE.test(value)) return null;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : null;
}

function durationMsOf(value: string): number | null {
  const seconds = Number(EXTINF_RE.exec(value)?.[1]);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  return Math.round(seconds * 1000);
}

/** 조각 태그 줄 하나를 더한 태그. 아는 태그인데 값을 못 읽으면 null, 모르는 태그는 그대로 둔다 */
function withSegmentTag(tags: SegmentTags, line: string): SegmentTags | null {
  if (line === '#EXT-X-DISCONTINUITY') return { ...tags, discontinuity: true };
  if (line === '#EXT-X-GAP') return { ...tags, gap: true };
  if (line.startsWith('#EXT-X-PROGRAM-DATE-TIME:')) {
    const pdtMs = pdtMsOf(line.slice('#EXT-X-PROGRAM-DATE-TIME:'.length));
    return pdtMs === null ? null : { ...tags, pdtMs };
  }
  if (line.startsWith('#EXTINF:')) {
    const durationMs = durationMsOf(line.slice('#EXTINF:'.length));
    return durationMs === null ? null : { ...tags, durationMs };
  }
  return tags;
}

/** 태그 줄 하나를 읽은 다음 상태. 목록을 거부할 줄이면 null */
function afterTagLine(state: ReadState, line: string, list: URL): ReadState | null {
  if (line.startsWith('#EXT-X-MAP:')) {
    const raw = MAP_URI_RE.exec(line)?.[1];
    const mapUri = raw === undefined ? null : sameOriginUrl(raw, list);
    return mapUri === null ? null : { ...state, mapUri };
  }
  if (line.startsWith(TIMING_PREFIX)) {
    const value = line.slice(TIMING_PREFIX.length);
    return { ...state, timing: value === VERSION_2_VALUE ? 'v2' : null };
  }
  const tags = withSegmentTag(state.tags, line);
  return tags === null ? null : { ...state, tags };
}

/** URI 줄 하나를 그때까지 모은 태그와 묶은 조각. MAP · PDT · EXTINF · 같은 출처 · 숫자 seq 중 하나라도 없으면 null */
function rowAt(line: string, state: ReadState, list: URL): ReadRow | null {
  const { mapUri, timing, tags } = state;
  if (mapUri === null || tags.pdtMs === null || tags.durationMs === null) return null;
  const uri = sameOriginUrl(line, list);
  const seq = uri === null ? null : seqOf(uri);
  if (uri === null || seq === null) return null;
  return {
    segment: {
      seq,
      uri,
      mapUri,
      pdtMs: tags.pdtMs,
      durationMs: tags.durationMs,
      timing,
      gap: tags.gap,
    },
    discontinuity: tags.discontinuity,
  };
}

/** 머리 줄 뒤의 모든 줄을 조각으로 읽는다. 하나라도 어긋나면(seq 가 앞 줄보다 크지 않음 포함) null */
function readRows(lines: readonly string[], list: URL): ReadRow[] | null {
  const rows: ReadRow[] = [];
  let state: ReadState = { mapUri: null, timing: null, tags: NO_TAGS };
  for (const line of lines) {
    if (line === '') continue;
    if (line.startsWith('#')) {
      const next = afterTagLine(state, line, list);
      if (next === null) return null;
      state = next;
      continue;
    }
    const row = rowAt(line, state, list);
    const previous = rows.at(-1);
    if (row === null || (previous !== undefined && row.segment.seq <= previous.segment.seq)) {
      return null;
    }
    rows.push(row);
    state = { ...state, tags: NO_TAGS };
  }
  return rows;
}

/** 조각을 런으로 묶는다 — GAP 줄 · DISCONTINUITY · MAP 바뀜에서 끊고, GAP 줄은 어느 런에도 넣지 않는다 */
function splitRuns(rows: readonly ReadRow[]): PlaylistSegment[][] {
  const runs: PlaylistSegment[][] = [];
  let run: PlaylistSegment[] = [];
  for (const { segment, discontinuity } of rows) {
    const last = run.at(-1);
    const breaks =
      segment.gap || discontinuity || (last !== undefined && last.mapUri !== segment.mapUri);
    if (breaks && run.length > 0) {
      runs.push(run);
      run = [];
    }
    if (!segment.gap) run.push(segment);
  }
  if (run.length > 0) runs.push(run);
  return runs;
}

function httpUrlOrNull(value: string): URL | null {
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url : null;
  } catch {
    return null;
  }
}

/**
 * 목록 글을 조각 · 런으로 읽는다. 조각 · MAP URI 는 listUrl 기준 절대 주소로 바꾸고, 목록과 다른 출처면 거부한다(제어면
 * 답의 목록 주소 검사 isTrustedPlaylistUrl 은 .m3u8 전용이라 여기서 따로 본다). #EXTM3U 로 시작하지 않거나 · 조각이
 * 없거나 · 조각에 MAP · PDT · EXTINF 가 없거나 · seq 를 못 읽거나 앞 줄보다 크지 않으면 null
 * @param listUrl 목록을 받은 주소(http · https)
 */
export function parseEditorPlaylist(text: string, listUrl: string): EditorPlaylist | null {
  const list = httpUrlOrNull(listUrl);
  const lines = text.split(/\r?\n/).map((line) => line.trim());
  if (list === null || lines[0] !== '#EXTM3U') return null;
  const rows = readRows(lines.slice(1), list);
  if (rows === null || rows.length === 0) return null;
  return { runs: splitRuns(rows), rows: rows.map((row) => row.segment) };
}
