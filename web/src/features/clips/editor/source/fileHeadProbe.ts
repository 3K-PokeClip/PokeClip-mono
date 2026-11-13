import { READ_TIMEOUT_MS } from '@/api/client';
import { mediaCredentials } from '@/api/mediaPlayback';
import {
  MAX_TRACK_SAMPLES,
  readFirstSampledTraf,
  readTrackHeaders,
  readTrackStart,
  readVideoTrack,
} from '@/lib/fmp4Boxes';
import { captureSession, onSessionAdvance } from '@/stores/auth';
import type { PlaylistSegment } from './editorPlaylist';

// 판 NULL 조각의 ② 머리 — 창 배치 · 컷 원점이 기대는 값(POK-473 설계 prw2 3.2 결정 3 · 4절). seq 의 ② 서명 주소(창 소리 주소
// 응답의 행 url)를 Range 로 받아 moov 와 moof 들에서 트랙마다 「표본 있는 첫 traf」 를 찾는다. 첫 Range 는 64KB 이고, 못 찾은
// 트랙이 남으면 앞 요청의 두 배를 이어 받는다(64 → 128 → 256KB …). 파일 끝까지 못 찾은 트랙은 빈 트랙이라 뺀다(렌더
// readTiming 규칙).
//
// 두 배씩인 까닭: 받을 때마다 받은 앞부분 전체를 처음부터 다시 읽는다. 설계 3.2 의 「64KB 씩」 은 상한까지 128번 다시 읽어,
// 표본 traf 를 파일 중간에 두고 빈 traf 로 채운 ② 하나가 수십 초 걸렸다(보안 검수 prw2 후속 경화 M-1 실측 node 42–46초).
// 두 배씩이면 상한까지 여덟 번이다 — 설계 3.2 의 계획 편차(kty 결정 (가) 2026-11-12). 정상 ② 는 그대로 첫 64KB 하나로 끝난다.
//
// 🔴 자격: ② 는 S3 서명 URL 이다. 서명이 자격이라 실을 쿠키가 없어 늘 omit 이다 — CORS 가 막아서가 아니다(M4 실측
//    2026-11-13: 버킷은 Origin 이 맞으면 Allow-Credentials true 를 붙이고(curl), include 도 세 브라우저에서 206 이었다 —
//    Chrome · Safari 는 캐시였을 수 있어 확인은 Firefox 다). 받기는 모두 3xx 를 따라가지 않는다(redirect 'error' — ③ 은
//    쿠키를 싣는다 · PR 단계 r1 L-2). 단일
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
// 🔴 시한: 응답 머리와 본문 쪼각을 HEAD_FETCH_STALL_MS 씩 기다린다. 넘으면 멈춘 받기라 끊고 그 seq 는 failed 다 — 전체 받기로
//    물러나지 않는다(멈춘 서버를 한 번 더 기다리게 될 뿐이다). 쪼각마다 시계를 새로 재므로 느리게라도 이어 오는 본문은 끊지
//    않는다(보안 검수 prw2 경화 L-3).
//
// 🔴 동시성: 동시에 받는 seq 는 모듈 전체로 PROBE_CONCURRENCY 까지다 — 창 판독과 카드 미리 받기 · 영상 키가 달라도 합쳐 센다.
//    줄에 세운 seq 는 받기 전부터 읽는 중으로 두어, 겹쳐 부른 쪽은 그 판독을 기다리고 두 번 받지 않는다. 기다리던 판독이 그
//    판독을 세운 쪽의 멈춤으로 끝나면 자기 signal 로 다시 세운다(보안 검수 prw2 E4b-1 · E4b-2 L4).
//
// 🔴 계정 경계: 계정이 바뀌면(onSessionAdvance) 모든 영상 키의 캐시를 비우고, 바뀌기 전에 세운 판독은 결과를 넣지 않는다.
//    캐시 객체는 그대로 둔다 — 화면이 들고 있는 캐시(headCacheFor)가 비움을 듣고 새 판독을 받는다(보안 검수 prw2 E4b-1 L2).
//
// 확보 조각(설계 prw2 7절 c10b′)은 ③ 조각 머리 tfdt 만 같은 Range 판독으로 읽는다(readFragmentVideoDts). ③ 은 CloudFront
// 서명 쿠키라 자격이 mediaCredentials() 다 — 판독 함수는 자격을 인자로만 받고, 고르는 것은 부르는 쪽이다.
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
/** 동시에 읽는 seq 수 — 모듈 전체(설계 3.2 비용 — 창 조각 약 50 · 동시 6) */
const PROBE_CONCURRENCY = 6;
/** 응답 머리 · 본문 쪼각 하나를 기다리는 한도(파일 머리의 시한 절 — api 읽기 한도 READ_TIMEOUT_MS 와 같은 규칙 · 값) */
export const HEAD_FETCH_STALL_MS = READ_TIMEOUT_MS;

/** 판독 실패 — 사유만 싣는다(서명 URL 은 싣지 않는다). ② 머리 · ③ 조각 판독이 함께 쓴다 */
class HeadProbeError extends Error {
  constructor(reason: string) {
    super(`조각 머리 판독 실패(${reason})`);
    this.name = 'HeadProbeError';
  }
}

interface ScopeCache extends HeadCache {
  entries: Map<number, HeadEntry>;
  inFlight: Map<number, Promise<void>>;
  set(seq: number, entry: HeadEntry | undefined): void;
  /** 머리 · 읽는 중 표를 비우고 구독자를 부른다(계정 바뀜) */
  reset(): void;
}

const scopes = new Map<string, ScopeCache>();

onSessionAdvance(() => scopes.forEach((scope) => scope.reset()));

function scopeOf(mediaKey: string): ScopeCache {
  const known = scopes.get(mediaKey);
  if (known !== undefined) return known;
  const entries = new Map<number, HeadEntry>();
  const inFlight = new Map<number, Promise<void>>();
  const listeners = new Set<() => void>();
  const notify = () => {
    for (const listener of [...listeners]) listener();
  };
  const scope: ScopeCache = {
    entries,
    inFlight,
    get: (seq) => entries.get(seq),
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    set(seq, entry) {
      if (entry === undefined) entries.delete(seq);
      else entries.set(seq, entry);
      notify();
    },
    reset() {
      entries.clear();
      inFlight.clear();
      notify();
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
 * 받기 하나의 끊기 — 부른 쪽 signal 과 멈춤 시한(HEAD_FETCH_STALL_MS)을 함께 듣는 signal 을 준다. arm 은 시계를 새로 재고,
 * release 는 시계와 듣기를 거둔다
 */
class StallGuard {
  private readonly controller = new AbortController();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private timedOut = false;
  private readonly onOuterAbort = () => this.controller.abort(this.outer?.reason);

  constructor(private readonly outer: AbortSignal | undefined) {
    if (outer?.aborted) this.controller.abort(outer.reason);
    else outer?.addEventListener('abort', this.onOuterAbort, { once: true });
    this.arm();
  }

  get signal(): AbortSignal {
    return this.controller.signal;
  }

  /** 시한으로 끊었는가(부른 쪽이 멈춘 것은 아니다) */
  get stalled(): boolean {
    return this.timedOut;
  }

  arm(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timedOut = true;
      this.controller.abort(new HeadProbeError('시한'));
    }, HEAD_FETCH_STALL_MS);
  }

  release(): void {
    clearTimeout(this.timer);
    this.outer?.removeEventListener('abort', this.onOuterAbort);
  }
}

/**
 * work 를 시한과 함께 돌린다. 시한으로 끊겼으면 HeadProbeError('시한')을 던진다 — 부른 쪽이 멈춘 것과 갈라 둔다(그쪽은
 * 받은 오류를 그대로 던진다)
 */
async function withStallGuard<T>(
  signal: AbortSignal | undefined,
  work: (guard: StallGuard) => Promise<T>,
): Promise<T> {
  const guard = new StallGuard(signal);
  try {
    return await work(guard);
  } catch (e) {
    throw guard.stalled && !signal?.aborted ? new HeadProbeError('시한') : e;
  } finally {
    guard.release();
  }
}

/**
 * 본문 쪼각 하나를 읽는다. 시계를 새로 재고, guard 가 끊으면(시한 · 부른 쪽 멈춤) 본문을 닫고 그 사유로 던진다 — 끊어도
 * 풀리지 않는 본문이 있어 read 만 기다리지 않는다
 */
function readWithin(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  guard: StallGuard,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  guard.arm();
  const { signal } = guard;
  return new Promise((resolve, reject) => {
    const stop = () => {
      // 닫기의 성패는 판독 결과와 무관하다 — 끊긴 사유로 이미 던진다
      reader.cancel().catch(() => undefined);
      reject(signal.reason);
    };
    if (signal.aborted) {
      stop();
      return;
    }
    signal.addEventListener('abort', stop, { once: true });
    reader
      .read()
      .then(resolve, reject)
      .finally(() => signal.removeEventListener('abort', stop));
  });
}

/**
 * 본문 스트림을 limit 바이트까지 읽는다. 더 오면 거기서 끊는다(응답 머리의 길이는 읽을 수 없다 — 위 자격 절). 받은 쪼각은
 * 곧바로 버퍼로 옮기고 들고 있지 않는다 — 1바이트 쪼각도 쪼각마다 객체라, 모아 두면 256KB 에 힙 약 60MB 다(codex prw2 경화
 * r1 지적 1). 쪼각마다 guard 의 시계를 새로 잰다
 */
async function readBody(res: Response, limit: number, guard: StallGuard): Promise<Uint8Array> {
  const reader = res.body?.getReader();
  if (reader === undefined) return new Uint8Array(0);
  let buffer: Uint8Array = new Uint8Array(0);
  let length = 0;
  for (;;) {
    const { done, value } = await readWithin(reader, guard);
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
async function wholeChunk(res: Response, guard: StallGuard): Promise<Chunk> {
  const bytes = await readBody(res, HEAD_PROBE_MAX_BYTES, guard);
  return { bytes, whole: true, atEnd: bytes.length < HEAD_PROBE_MAX_BYTES };
}

function fetchWhole(
  url: string,
  credentials: RequestCredentials,
  signal: AbortSignal | undefined,
): Promise<Chunk> {
  return withStallGuard(signal, async (guard) => {
    const res = await fetch(url, { credentials, redirect: 'error', signal: guard.signal });
    if (!res.ok) throw new HeadProbeError(`전체 ${res.status}`);
    return wholeChunk(res, guard);
  });
}

/**
 * Range 요청 하나. 망 · CORS 오류거나 206 본문이 도중에 끊기면 'whole'(전체로 물러남)이다 — guard 가 끊은 것(시한 · 부른
 * 쪽 멈춤)은 물러나지 않고 던진다
 */
async function rangeChunk(
  url: string,
  offset: number,
  size: number,
  credentials: RequestCredentials,
  guard: StallGuard,
): Promise<Chunk | 'whole'> {
  let res: Response;
  try {
    res = await fetch(url, {
      credentials,
      headers: { Range: `bytes=${offset}-${offset + size - 1}` },
      redirect: 'error',
      signal: guard.signal,
    });
  } catch (e) {
    if (guard.signal.aborted) throw e;
    return 'whole';
  }
  if (res.status === 416) return { bytes: new Uint8Array(0), whole: false, atEnd: true };
  if (res.status === 200) return wholeChunk(res, guard);
  if (res.status !== 206) throw new HeadProbeError(String(res.status));
  let bytes: Uint8Array;
  try {
    bytes = await readBody(res, size, guard);
  } catch (e) {
    if (guard.signal.aborted) throw e;
    return 'whole';
  }
  return { bytes, whole: false, atEnd: bytes.length < size };
}

/**
 * offset 부터 size 바이트. 받은 본문이 size 보다 짧으면 파일 끝이다. Range 요청이 망 · CORS 오류거나 206 본문이 도중에
 * 끊기면 전체로, 416(파일 밖 시작)이면 빈 조각으로. 응답 · 쪼각이 시한까지 오지 않으면 HeadProbeError('시한')
 * @param credentials ② 서명 URL 은 omit, ③ CloudFront 는 mediaCredentials() — 부르는 쪽이 고른다
 */
async function fetchChunk(
  url: string,
  offset: number,
  size: number,
  credentials: RequestCredentials,
  signal: AbortSignal | undefined,
): Promise<Chunk> {
  const chunk = await withStallGuard(signal, (guard) =>
    rangeChunk(url, offset, size, credentials, guard),
  );
  return chunk === 'whole' ? fetchWhole(url, credentials, signal) : chunk;
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
    const chunk = await fetchChunk(url, length, size, 'omit', signal);
    buffer = chunk.whole ? chunk.bytes : appended(buffer, length, chunk.bytes);
    length = chunk.whole ? chunk.bytes.length : length + chunk.bytes.length;
    const read = readHead(buffer.subarray(0, length), chunk.atEnd);
    if (read === 'bad') throw new HeadProbeError('상자');
    if (read !== 'more') return read;
    if (length >= HEAD_PROBE_MAX_BYTES) throw new HeadProbeError('상한');
    size = Math.min(2 * size, HEAD_PROBE_MAX_BYTES - length);
  }
}

/**
 * seq 하나를 읽어 캐시에 넣는다. 끊긴 판독은 남기지 않는다(다음에 다시 읽는다). 계정이 바뀐 뒤 끝난 판독도 넣지 않는다
 * @param sameSession 판독을 세운 때와 같은 계정인가(captureSession)
 */
async function probeInto(
  scope: ScopeCache,
  row: HeadRow,
  signal: AbortSignal | undefined,
  sameSession: () => boolean,
): Promise<void> {
  try {
    const head = await probeHead(row.url, signal);
    if (sameSession()) scope.set(row.seq, { state: 'ready', head });
  } catch (e) {
    if (signal?.aborted || !sameSession()) return;
    // 전송 오류 원문(예: Failed to parse URL from …?X-Amz-Signature=…)에는 서명 URL 이 들 수 있어 사유만 싣는다
    const reason = e instanceof HeadProbeError ? e.message : '조각 머리 판독 실패(전송)';
    console.warn(`[editor] ② 머리를 읽지 못했어요(seq ${row.seq}):`, reason);
    scope.set(row.seq, { state: 'failed' });
  }
}

/** 지금 받는 판독 수(모듈 전체)와 자리를 기다리는 판독의 출발 신호 */
let probing = 0;
const turns: (() => void)[] = [];

/** 자리가 나면 work 를 돌린다 — 동시에 PROBE_CONCURRENCY 까지. 끝난 자리는 줄의 맨 앞 판독에 바로 넘긴다 */
async function inTurn(work: () => Promise<void>): Promise<void> {
  if (probing < PROBE_CONCURRENCY) probing += 1;
  else await new Promise<void>((resolve) => turns.push(resolve));
  try {
    await work();
  } finally {
    const next = turns.shift();
    if (next === undefined) probing -= 1;
    else next();
  }
}

/**
 * 그 seq 의 판독 — ready 면 null, 읽는 중(줄에 선 것 포함)이면 그 판독, 아니면 새로 줄에 세워 읽는 중으로 둔다. failed 는
 * 캐시를 지우고 한 번 더 읽는다. 차례가 왔을 때 세운 쪽이 멈췄거나 계정이 바뀌었으면 받지 않는다
 */
function probeOf(
  scope: ScopeCache,
  row: HeadRow,
  signal: AbortSignal | undefined,
  sameSession: () => boolean,
): Promise<void> | null {
  if (scope.entries.get(row.seq)?.state === 'ready') return null;
  const reading = scope.inFlight.get(row.seq);
  if (reading !== undefined) return reading;
  if (scope.entries.has(row.seq)) scope.set(row.seq, undefined);
  const job: Promise<void> = inTurn(async () => {
    if (signal?.aborted || !sameSession()) return;
    await probeInto(scope, row, signal, sameSession);
  }).finally(() => {
    if (scope.inFlight.get(row.seq) === job) scope.inFlight.delete(row.seq);
  });
  scope.inFlight.set(row.seq, job);
  return job;
}

/**
 * seq 하나가 캐시에 들 때까지 기다린다. 기다린 판독이 머리를 남기지 않고 끝났으면(그 판독을 세운 쪽이 멈춤) 자기 signal 로
 * 다시 세운다 — 자기가 멈췄거나 계정이 바뀌었으면 그만둔다
 */
async function settleHead(
  scope: ScopeCache,
  row: HeadRow,
  signal: AbortSignal | undefined,
  sameSession: () => boolean,
): Promise<void> {
  let job = probeOf(scope, row, signal, sameSession);
  while (job !== null) {
    await job;
    if (scope.entries.has(row.seq) || signal?.aborted || !sameSession()) return;
    job = probeOf(scope, row, signal, sameSession);
  }
}

/**
 * 창 조각들의 머리를 캐시에 채운다. rows 는 소리 주소 응답의 행(seq · ② 서명 주소), missingSeqs 는 창에 있는데 응답에 행이
 * 없는 seq(② 미업로드 — missing). 이미 읽은 seq 는 다시 받지 않고, 읽는 중인 seq 는 그 판독을 기다린다. 실패는 캐시의 failed
 * 로 남기고 던지지 않는다. 동시에 받는 수는 모듈 전체로 여섯까지다(파일 머리의 동시성 절)
 * @param mediaKey 영상 키 — seq 는 그 키의 장부 번호다
 * @param sameSession rows 를 받은 계정인가(captureSession). 소리 주소를 묻기 전에 잡아 넘긴다 — 묻는 사이 계정이 바뀌면 옛
 *   계정 응답의 서명 주소로 받지도, 비운 캐시에 적지도 않는다(PR 단계 r1). 없으면 지금 계정이다
 */
export async function ensureHeads(
  mediaKey: string,
  rows: readonly HeadRow[],
  missingSeqs: readonly number[],
  signal?: AbortSignal,
  sameSession: () => boolean = captureSession(),
): Promise<void> {
  if (!sameSession()) return;
  const scope = scopeOf(mediaKey);
  for (const seq of missingSeqs) {
    const entry = scope.entries.get(seq);
    if (entry?.state !== 'ready' && !scope.inFlight.has(seq)) scope.set(seq, { state: 'missing' });
  }
  await Promise.all(rows.map((row) => settleHead(scope, row, signal, sameSession)));
}

/**
 * 기다림 고리 상한(설계 prw2 7절 c10b′)에서 끝내 missing 인 머리를 failed 로 바꾼다 — 그 조각 고름은 저장할 수 없고 「다시
 * 시도」 를 띄운다. ready · 아직 묻지 않은 seq 는 그대로 둔다
 */
export function failMissingHeads(mediaKey: string, seqs: readonly number[]): void {
  const scope = scopeOf(mediaKey);
  for (const seq of seqs) {
    if (scope.entries.get(seq)?.state === 'missing') scope.set(seq, { state: 'failed' });
  }
}

/**
 * ③ 조각의 영상 첫 DTS(D3 — ③ 축 초). 끝 소리 확보 조각을 창 Blob 시각에 놓는 데 쓴다(설계 prw2 7절 c10b′ — 창은 다시 싸지
 * 않는다). init(MAP)의 영상 트랙 · timescale 과 조각 앞부분 moof 의 tfdt 를 각각 Range 64KB 로 읽는다. 자격은
 * mediaCredentials() 다(③ CloudFront 서명 쿠키). 읽을 수 없으면 HeadProbeError(사유만 — 주소는 싣지 않는다)
 */
export async function readFragmentVideoDts(
  seg: Pick<PlaylistSegment, 'uri' | 'mapUri'>,
  signal?: AbortSignal,
): Promise<number> {
  const credentials = mediaCredentials();
  const [init, head] = await Promise.all([
    fetchChunk(seg.mapUri, 0, HEAD_CHUNK_BYTES, credentials, signal),
    fetchChunk(seg.uri, 0, HEAD_CHUNK_BYTES, credentials, signal),
  ]);
  const video = readVideoTrack(init.bytes);
  if (video === null) throw new HeadProbeError('③ init');
  const start = readTrackStart(head.bytes, video.trackId);
  if (start === null) throw new HeadProbeError('③ tfdt');
  return start.baseDecodeTime / video.timescale;
}
