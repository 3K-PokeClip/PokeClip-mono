import { readFirstSampledTraf, readTrackHeaders } from '@/lib/fmp4Boxes';

// 판 NULL 조각의 ② 머리 — 창 배치 · 컷 원점이 기대는 값(POK-473 설계 prw2 3.2 결정 3 · 4절). seq 의 ② 서명 주소(창 소리 주소
// 응답의 행 url)를 Range 64KB 씩 받아 moov 와 moof 들에서 트랙마다 「표본 있는 첫 traf」 를 찾는다. 못 찾은 트랙이 남으면 다음
// 64KB 를 받고, 파일 끝까지 못 찾은 트랙은 빈 트랙이라 뺀다(렌더 readTiming 규칙).
//
// 🔴 자격: ② 는 S3 서명 URL 이다. 버킷 CORS 에 Allow-Credentials 가 없어 쿠키를 실으면(include) 막힌다 — 늘 omit 이다. 단일
//    Range 는 CORS 안전 목록이라 프리플라이트가 없고(버킷 AllowedHeaders 없음), 그 밖의 머리는 싣지 않는다. 버킷 ExposeHeaders
//    가 비어 응답 머리(Content-Range)를 읽을 수 없으므로 상태 코드와 본문 길이로만 파일 끝을 안다 — 받은 본문이 64KB 보다
//    짧거나 416 이면 끝이다. Range 가 망 · CORS 오류면 Range 없이 한 번 전체를 받는다. 서명 URL 은 경고 · 오류에 싣지 않는다.
//
// 값은 영상 키 · seq 별로 세션 캐시에 둔다(조각 머리는 바뀌지 않는다 — 장부 키 seq 고정 · pdt-main M-1). seq 는 그 키의 장부
// 번호라 키가 다르면 캐시도 따로다.

/** ② 파일 머리. 모두 그 ② 파일 축의 초다(트랙마다 자기 timescale 로 먼저 초로 바꾼 값) */
export interface FileHead {
  /** Hd2 — 샘플 있는 트랙의 첫 DTS 최솟값(렌더 readTiming 과 같은 규칙) */
  hd: number;
  /** Hp2 — 샘플 있는 트랙의 PTS 최솟값의 최솟값(영상은 첫 traf 표본들의 PTS 최솟값 — open GOP 대비) */
  hp: number;
  /** D2 — ② 영상 첫 DTS */
  videoDts: number;
}

/** seq 하나의 머리 판독 결과 — missing 은 소리 주소 응답에 그 행이 없음(② 미업로드), failed 는 판독 실패 */
export type HeadEntry =
  { state: 'ready'; head: FileHead } | { state: 'missing' } | { state: 'failed' };

/** seq 별 머리 캐시. 조각 머리는 바뀌지 않으므로 ready 는 다시 읽지 않는다. get 이 undefined 면 아직 묻지 않은 seq 다 */
export interface HeadCache {
  get(seq: number): HeadEntry | undefined;
  /** 값이 바뀔 때마다 부른다. 돌려준 함수로 구독을 끊는다 */
  subscribe(listener: () => void): () => void;
}

/** 소리 주소 응답의 행 하나 — seq 와 그 ② 서명 주소 */
export interface HeadRow {
  seq: number;
  url: string;
}

/** 한 번에 받는 바이트(Range bytes=0-65535). 대개 moov 와 첫 moof 가 이 안에 든다 */
export const HEAD_CHUNK_BYTES = 65_536;
/** 동시에 읽는 seq 수(설계 3.2 비용 — 창 조각 약 50 · 동시 6) */
const PROBE_CONCURRENCY = 6;

/** 판독 실패 — 사유만 싣는다(서명 URL 은 싣지 않는다) */
class HeadProbeError extends Error {
  constructor(reason: string) {
    super(`② 머리 판독 실패(${reason})`);
    this.name = 'HeadProbeError';
  }
}

interface ScopeCache extends HeadCache {
  entries: Map<number, HeadEntry>;
  inFlight: Map<number, Promise<void>>;
  set(seq: number, entry: HeadEntry | undefined): void;
}

const scopes = new Map<string, ScopeCache>();

function scopeOf(mediaKey: string): ScopeCache {
  const known = scopes.get(mediaKey);
  if (known !== undefined) return known;
  const entries = new Map<number, HeadEntry>();
  const listeners = new Set<() => void>();
  const scope: ScopeCache = {
    entries,
    inFlight: new Map(),
    get: (seq) => entries.get(seq),
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    set(seq, entry) {
      if (entry === undefined) entries.delete(seq);
      else entries.set(seq, entry);
      for (const listener of [...listeners]) listener();
    },
  };
  scopes.set(mediaKey, scope);
  return scope;
}

/** 그 영상 키의 머리 캐시 */
export function headCacheFor(mediaKey: string): HeadCache {
  return scopeOf(mediaKey);
}

/** 판독 한 번의 결과 — 머리, 아직 더 받아야 함, 읽을 수 없음 */
type HeadRead = FileHead | 'more' | 'bad';

/**
 * 받은 바이트(파일 앞부분)에서 머리를 읽는다. atEnd 면 파일 끝까지 받은 것이라 표본 있는 traf 가 없는 트랙을 뺀다. 아직
 * 끝이 아니면 moov 를 못 찾았거나 표본 있는 traf 를 못 찾은 트랙이 남은 동안 more 다
 */
function readHead(bytes: Uint8Array, atEnd: boolean): HeadRead {
  const headers = readTrackHeaders(bytes);
  if (headers === null) return 'bad';
  if (headers.length === 0) return atEnd ? 'bad' : 'more';
  let hd = Infinity;
  let hp = Infinity;
  let videoDts: number | null = null;
  for (const { trackId, handler, timescale } of headers) {
    if (timescale <= 0) continue;
    const samples = readFirstSampledTraf(bytes, bytes, trackId);
    if (samples === null) return 'bad';
    const [first] = samples;
    if (first === undefined) {
      if (!atEnd) return 'more';
      continue; // 파일 끝까지 표본이 없는 트랙 — Hd · Hp 에서 뺀다
    }
    hd = Math.min(hd, first.dts / timescale);
    for (const sample of samples) hp = Math.min(hp, sample.pts / timescale);
    if (handler === 'vide' && videoDts === null) videoDts = first.dts / timescale;
  }
  return videoDts === null ? 'bad' : { hd, hp, videoDts };
}

/** 받은 조각 하나 — whole 은 Range 없이(또는 서버가 Range 를 무시해) 받은 파일 전체다 */
interface Chunk {
  bytes: Uint8Array;
  whole: boolean;
}

async function fetchWhole(url: string, signal: AbortSignal | undefined): Promise<Chunk> {
  const res = await fetch(url, { credentials: 'omit', signal });
  if (!res.ok) throw new HeadProbeError(`전체 ${res.status}`);
  return { bytes: new Uint8Array(await res.arrayBuffer()), whole: true };
}

/** offset 부터 64KB. Range 가 망 · CORS 오류면 전체로, 416(파일 밖 시작)이면 빈 조각으로 */
async function fetchChunk(
  url: string,
  offset: number,
  signal: AbortSignal | undefined,
): Promise<Chunk> {
  let res: Response;
  try {
    res = await fetch(url, {
      credentials: 'omit',
      headers: { Range: `bytes=${offset}-${offset + HEAD_CHUNK_BYTES - 1}` },
      signal,
    });
  } catch (e) {
    if (signal?.aborted) throw e;
    return fetchWhole(url, signal);
  }
  if (res.status === 416) return { bytes: new Uint8Array(0), whole: false };
  if (res.status !== 200 && res.status !== 206) throw new HeadProbeError(String(res.status));
  return { bytes: new Uint8Array(await res.arrayBuffer()), whole: res.status === 200 };
}

function append(head: Uint8Array, tail: Uint8Array): Uint8Array {
  const out = new Uint8Array(head.length + tail.length);
  out.set(head);
  out.set(tail, head.length);
  return out;
}

/** ② 파일 하나의 머리. 읽을 수 없으면 HeadProbeError, 끊으면 AbortError 를 던진다 */
async function probeHead(url: string, signal: AbortSignal | undefined): Promise<FileHead> {
  let bytes: Uint8Array = new Uint8Array(0);
  for (;;) {
    const chunk = await fetchChunk(url, bytes.length, signal);
    bytes = chunk.whole ? chunk.bytes : append(bytes, chunk.bytes);
    const atEnd = chunk.whole || chunk.bytes.length < HEAD_CHUNK_BYTES;
    const read = readHead(bytes, atEnd);
    if (read === 'bad') throw new HeadProbeError('상자');
    if (read !== 'more') return read;
  }
}

/** seq 하나를 읽어 캐시에 넣는다. 끊긴 판독은 남기지 않는다(다음에 다시 읽는다) */
async function probeInto(
  scope: ScopeCache,
  row: HeadRow,
  signal: AbortSignal | undefined,
): Promise<void> {
  try {
    scope.set(row.seq, { state: 'ready', head: await probeHead(row.url, signal) });
  } catch (e) {
    if (signal?.aborted) return;
    console.warn(`[editor] ② 머리를 읽지 못했어요(seq ${row.seq}):`, (e as Error).message);
    scope.set(row.seq, { state: 'failed' });
  }
}

/** 읽어야 할 행 — ready 는 다시 읽지 않는다. failed 는 캐시를 지우고 한 번 더 읽는다 */
function rowsToProbe(scope: ScopeCache, rows: readonly HeadRow[]): HeadRow[] {
  const wanted: HeadRow[] = [];
  for (const row of rows) {
    const entry = scope.entries.get(row.seq);
    if (entry?.state === 'ready' || scope.inFlight.has(row.seq)) continue;
    if (entry !== undefined) scope.set(row.seq, undefined);
    wanted.push(row);
  }
  return wanted;
}

/**
 * 창 조각들의 머리를 캐시에 채운다. rows 는 소리 주소 응답의 행(seq · ② 서명 주소), missingSeqs 는 창에 있는데 응답에 행이
 * 없는 seq(② 미업로드 — missing). 이미 읽은 seq 는 다시 받지 않고, 읽는 중인 seq 는 그 판독을 기다린다. 실패는 캐시의 failed
 * 로 남기고 던지지 않는다. 동시에 여섯까지 받는다
 * @param mediaKey 영상 키 — seq 는 그 키의 장부 번호다
 */
export async function ensureHeads(
  mediaKey: string,
  rows: readonly HeadRow[],
  missingSeqs: readonly number[],
  signal?: AbortSignal,
): Promise<void> {
  const scope = scopeOf(mediaKey);
  for (const seq of missingSeqs) {
    const entry = scope.entries.get(seq);
    if (entry?.state !== 'ready' && !scope.inFlight.has(seq)) scope.set(seq, { state: 'missing' });
  }
  const waiting = rows.flatMap((row) => scope.inFlight.get(row.seq) ?? []);
  const queue = rowsToProbe(scope, rows);
  const worker = async () => {
    for (let row = queue.shift(); row !== undefined; row = queue.shift()) {
      const job = probeInto(scope, row, signal);
      scope.inFlight.set(row.seq, job);
      await job;
      scope.inFlight.delete(row.seq);
    }
  };
  const workers = Array.from({ length: Math.min(PROBE_CONCURRENCY, queue.length) }, worker);
  await Promise.all([...waiting, ...workers]);
}
