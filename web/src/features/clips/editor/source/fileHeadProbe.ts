import { MAX_TRACK_SAMPLES, readFirstSampledTraf, readTrackHeaders } from '@/lib/fmp4Boxes';

// 판 NULL 조각의 ② 머리 — 창 배치 · 컷 원점이 기대는 값(POK-473 설계 prw2 3.2 결정 3 · 4절). seq 의 ② 서명 주소(창 소리 주소
// 응답의 행 url)를 Range 로 받아 moov 와 moof 들에서 트랙마다 「표본 있는 첫 traf」 를 찾는다. 첫 Range 는 64KB 이고, 못 찾은
// 트랙이 남으면 앞 요청의 두 배를 이어 받는다(64 → 128 → 256KB …). 파일 끝까지 못 찾은 트랙은 빈 트랙이라 뺀다(렌더
// readTiming 규칙).
//
// 두 배씩인 까닭: 받을 때마다 받은 앞부분 전체를 처음부터 다시 읽는다. 설계 3.2 의 「64KB 씩」 은 상한까지 128번 다시 읽어,
// 표본 traf 를 파일 중간에 두고 빈 traf 로 채운 ② 하나가 수십 초 걸렸다(보안 검수 prw2 후속 경화 M-1 실측 node 42–46초).
// 두 배씩이면 상한까지 여덟 번이다 — 설계 3.2 의 계획 편차(kty 결정 (가) 2026-11-12). 정상 ② 는 그대로 첫 64KB 하나로 끝난다.
//
// 🔴 자격: ② 는 S3 서명 URL 이다. 버킷 CORS 에 Allow-Credentials 가 없어 쿠키를 실으면(include) 막힌다 — 늘 omit 이다. 단일
//    Range 는 CORS 안전 목록이라 프리플라이트가 없고(버킷 AllowedHeaders 없음), 그 밖의 머리는 싣지 않는다. 버킷 ExposeHeaders
//    가 비어 응답 머리(Content-Range)를 읽을 수 없으므로 상태 코드와 본문 길이로만 파일 끝을 안다 — 받은 본문이 이번에
//    요청한 크기보다 짧거나 416 이면 끝이다. Range 가 망 · CORS 오류거나 206 본문이 도중에 끊기면 Range 없이 한 번 전체를 받는다. 서명 URL 은
//    경고 · 오류에 싣지 않는다(전송 오류 원문에도 URL 이 들 수 있어 사유만 싣는다).
//
// 🔴 상한: ② 하나의 판독 버퍼는 HEAD_PROBE_MAX_BYTES 까지다. 전체 받기 · Range 를 무시한 200 도 본문 스트림을 세다가 상한에서
//    끊는다. 상한까지 받고도 머리가 끝나지 않으면(빈 트랙이 그 뒤까지 이어짐) 그 seq 는 failed 다(보안 검수 prw2 E4b-1 M2).
//    받는 바이트의 합은 상한의 약 두 배까지다 — Range 로 상한 가까이 받은 뒤 206 본문이 끊기면 전체 받기로 처음부터 다시
//    받는다. 판독 한 번의 비용도 묶는다: 트랙 수는 MAX_TRACKS 까지(fmp4Boxes), 표본은 트랙 합으로 MAX_TRACK_SAMPLES 까지다
//    (보안 검수 prw2 경화 M-1).
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

/** 첫 Range 로 받는 바이트(bytes=0-65535) — 다음부터는 앞 요청의 두 배다. 대개 moov 와 첫 moof 가 이 안에 든다 */
export const HEAD_CHUNK_BYTES = 65_536;
/**
 * ② 하나의 판독 버퍼 상한(Range 는 64KB 부터 두 배씩 여덟 번 — 마지막은 상한까지 남은 만큼만 요청한다). 받는 바이트의 합은
 * 이것의 약 두 배까지다(파일 머리의 상한 절 — 206 끊김 뒤 전체 받기). 정상 ② 는
 * 첫 64KB 하나로 끝나고(설계 3.2 비용 — 창 50 조각 ≈ 3.2MB), 이 상한은 빈 트랙이라 파일 끝까지 읽어야 하는 ② 를 위한
 * 것이다. 값 = ② 조각 하나 크기(계약-세그먼트인덱스 「개당 ~4MB」 · ③ 창 200초 ≈ 150MB ÷ 50 조각 ≈ 3MB)의 두 배 —
 * 정상 ② 는 끝까지 읽고, 그보다 큰 파일은 머리를 끝내지 못하면 판독 실패로 둔다
 */
export const HEAD_PROBE_MAX_BYTES = 8 * 1024 * 1024;
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
  // 표본 상한은 트랙마다가 아니라 판독 한 번의 트랙 합이다. 정상 ② 는 4초 조각이라 영상 240fps 960 + 소리 AAC 6 × 약 188
  // ≈ 2,100 으로 한참 못 미친다
  let room = MAX_TRACK_SAMPLES;
  for (const { trackId, handler, timescale } of headers) {
    if (timescale <= 0) continue;
    const samples = readFirstSampledTraf(bytes, bytes, trackId, room);
    if (samples === null) return 'bad';
    room -= samples.length;
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

/**
 * 받은 조각 하나 — whole 은 Range 없이(또는 서버가 Range 를 무시해) 받은 파일 앞부분이라 앞서 받은 바이트를 갈음한다.
 * atEnd 는 파일 끝까지 받았는가(상한에서 끊은 전체 받기는 끝이 아니다)
 */
interface Chunk {
  bytes: Uint8Array;
  whole: boolean;
  atEnd: boolean;
}

/**
 * 본문 스트림을 limit 바이트까지 읽는다. 더 오면 거기서 끊는다(응답 머리의 길이는 읽을 수 없다 — 위 자격 절). 받은 쪼각은
 * 곧바로 버퍼로 옮기고 들고 있지 않는다 — 1바이트 쪼각도 쪼각마다 객체라, 모아 두면 256KB 에 힙 약 60MB 다(codex prw2 경화
 * r1 지적 1)
 */
async function readBody(res: Response, limit: number): Promise<Uint8Array> {
  const reader = res.body?.getReader();
  if (reader === undefined) return new Uint8Array(0);
  let buffer: Uint8Array = new Uint8Array(0);
  let length = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return buffer.subarray(0, length);
    if (length + value.length > limit) {
      buffer = appended(buffer, length, value.subarray(0, limit - length), limit);
      await reader.cancel();
      return buffer.subarray(0, limit);
    }
    buffer = appended(buffer, length, value, limit);
    length += value.length;
  }
}

/**
 * 200 본문 — 상한까지만 읽는다. 상한을 꽉 채운 본문은 파일 끝으로 치지 않는다: Range 길도 상한을 꽉 채우면 끝을 확인하지
 * 못해 failed 라, 정확히 상한 크기인 ② 가 받는 길에 따라 결과가 갈리지 않게 한다(codex prw2 경화 r1 지적 2)
 */
async function wholeChunk(res: Response): Promise<Chunk> {
  const bytes = await readBody(res, HEAD_PROBE_MAX_BYTES);
  return { bytes, whole: true, atEnd: bytes.length < HEAD_PROBE_MAX_BYTES };
}

async function fetchWhole(url: string, signal: AbortSignal | undefined): Promise<Chunk> {
  const res = await fetch(url, { credentials: 'omit', signal });
  if (!res.ok) throw new HeadProbeError(`전체 ${res.status}`);
  return wholeChunk(res);
}

/**
 * offset 부터 size 바이트. 받은 본문이 size 보다 짧으면 파일 끝이다. Range 요청이 망 · CORS 오류거나 206 본문이 도중에
 * 끊기면 전체로, 416(파일 밖 시작)이면 빈 조각으로
 */
async function fetchChunk(
  url: string,
  offset: number,
  size: number,
  signal: AbortSignal | undefined,
): Promise<Chunk> {
  let res: Response;
  try {
    res = await fetch(url, {
      credentials: 'omit',
      headers: { Range: `bytes=${offset}-${offset + size - 1}` },
      signal,
    });
  } catch (e) {
    if (signal?.aborted) throw e;
    return fetchWhole(url, signal);
  }
  if (res.status === 416) return { bytes: new Uint8Array(0), whole: false, atEnd: true };
  if (res.status === 200) return wholeChunk(res);
  if (res.status !== 206) throw new HeadProbeError(String(res.status));
  let bytes: Uint8Array;
  try {
    bytes = await readBody(res, size);
  } catch (e) {
    if (signal?.aborted) throw e;
    return fetchWhole(url, signal);
  }
  return { bytes, whole: false, atEnd: bytes.length < size };
}

/**
 * buffer 의 앞 length 바이트 뒤에 tail 을 잇는다. 자리가 모자라면 두 배(cap 까지)로 늘린 새 버퍼로 옮긴다 — 쪼각마다 전체를
 * 새로 복사하면 복사량이 받은 바이트의 제곱으로 늘어난다(64KB 쪼각으로 상한 8MB 면 약 512MB → 두 배씩이면 약 16MB)
 */
function appended(
  buffer: Uint8Array,
  length: number,
  tail: Uint8Array,
  cap: number = HEAD_PROBE_MAX_BYTES,
): Uint8Array {
  const needed = length + tail.length;
  if (needed > buffer.length) {
    const grown = new Uint8Array(Math.max(needed, Math.min(2 * buffer.length, cap)));
    grown.set(buffer.subarray(0, length));
    buffer = grown;
  }
  buffer.set(tail, length);
  return buffer;
}

/**
 * ② 파일 하나의 머리. 읽을 수 없거나 상한까지 받고도 끝나지 않으면 HeadProbeError, 끊으면 AbortError 를 던진다. 다음 요청은
 * 앞 요청의 두 배이되 상한까지 남은 만큼을 넘지 않는다 — 정확히 상한 크기인 ② 는 마지막 요청을 꽉 채워 끝을 확인하지 못하고
 * failed 라, 200 으로 상한까지 받은 길(wholeChunk)과 결과가 같다
 */
async function probeHead(url: string, signal: AbortSignal | undefined): Promise<FileHead> {
  let buffer: Uint8Array = new Uint8Array(0);
  let length = 0;
  let size = HEAD_CHUNK_BYTES;
  for (;;) {
    const chunk = await fetchChunk(url, length, size, signal);
    buffer = chunk.whole ? chunk.bytes : appended(buffer, length, chunk.bytes);
    length = chunk.whole ? chunk.bytes.length : length + chunk.bytes.length;
    const read = readHead(buffer.subarray(0, length), chunk.atEnd);
    if (read === 'bad') throw new HeadProbeError('상자');
    if (read !== 'more') return read;
    if (length >= HEAD_PROBE_MAX_BYTES) throw new HeadProbeError('상한');
    size = Math.min(2 * size, HEAD_PROBE_MAX_BYTES - length);
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
    // 전송 오류 원문(예: Failed to parse URL from …?X-Amz-Signature=…)에는 서명 URL 이 들 수 있어 사유만 싣는다
    const reason = e instanceof HeadProbeError ? e.message : '② 머리 판독 실패(전송)';
    console.warn(`[editor] ② 머리를 읽지 못했어요(seq ${row.seq}):`, reason);
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
