'use client';

// 되감기 제어면(계약3 7-2절, POK-200 M5) — 되감기 진입의 단일 정본이다. 경로가 둘이고 답의 꼴은 같다.
//   GET {base}/api/streams/{key}/rewind                    지금 이 스트림 키의 국면(같은 키의 다른 회차 값일 수 있다)
//   GET {base}/api/streams/{key}/rounds/{roundId}/rewind   세션이 보던 회차의 국면(끝남 대기의 종국 판정은 이것만)
// 공개 · 쿠키 없음 · 사용자 정의 머리 없음(단순 요청이라 preflight 가 없다). 미준비 · 끝남 · 국면 모름은 모두 200 이다.
// 🔴 답의 url 은 재생기 소스가 된다. 그래서 키 다섯을 모두 검사하고, url 은 제어면과 같은 출처의 m3u8 만 받는다(로컬
//    실험대는 루프백 http 끼리만 예외 — 제어면 28086 · 엣지 28480 이 포트로 갈린다). 어긋나면 「잠시 불가」로 접는다.

export type RewindMode = 'live' | 'ending' | 'vod' | 'ended';

export interface RewindStatus {
  /** url 을 지금 로드할 수 있다 */
  ready: boolean;
  /** null = 국면을 아직 모른다(되감기 진입을 열지 않는다) */
  mode: RewindMode | null;
  url: string | null;
  /** url 목록 첫 조각의 시각(epoch ms) */
  firstPdtMs: number | null;
  /** url 목록 마지막 조각의 끝 시각(epoch ms). 시크 상한이다 */
  headPdtMs: number | null;
}

export type RewindStatusResult =
  | { kind: 'ok'; status: RewindStatus }
  /** 404 — 스트림 경로는 「되감기 진입 없음」, 회차별 경로는 「아직 모름」(설계 5.1절) */
  | { kind: 'none' }
  /** 503 · 그 밖의 응답 · 망 오류 · 형식 오류 — 직전 상태를 두고 다음 주기에 다시 묻는다 */
  | { kind: 'unavailable' };

/** 경로 조각 가드 — mediaSource.ts 의 스트림 경로 규칙과 같다 */
const PATH_ID_RE = /^[A-Za-z0-9_-]+$/;
/** 서버가 쓰는 PDT 꼴(UTC · 밀리초 — rewind.FormatPDT). 꼴이 다르면 시각 축을 믿지 않는다 */
const PDT_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;
const MODES: readonly RewindMode[] = ['live', 'ending', 'vod', 'ended'];
const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);

function controlPath(base: string | null, key: string, rest: string): string | null {
  if (!base || !PATH_ID_RE.test(key)) return null;
  return `${base.replace(/\/+$/, '')}/api/streams/${key}${rest}`;
}

/** 스트림 경로. 제어면 주소가 없거나 키가 경로 조각 꼴이 아니면 null — 폴링을 열지 않는다 */
export function streamRewindUrl(base: string | null, key: string): string | null {
  return controlPath(base, key, '/rewind');
}

/** 회차별 경로. roundId 는 세션을 연 때의 방송 번호(= 계약9 streamId = 회차 ID) */
export function roundRewindUrl(base: string | null, key: string, roundId: string): string | null {
  if (!PATH_ID_RE.test(roundId)) return null;
  return controlPath(base, key, `/rounds/${roundId}/rewind`);
}

function parseUrlOrNull(value: string): URL | null {
  try {
    return new URL(value);
  } catch {
    return null;
  }
}

/** 재생기에 넘겨도 되는 목록 주소인가: 제어면과 같은 출처, 또는 둘 다 루프백 http(로컬 실험대) */
function isTrustedPlaylistUrl(value: string, controlBase: string): boolean {
  const url = parseUrlOrNull(value);
  const control = parseUrlOrNull(controlBase);
  if (url === null || control === null || !url.pathname.endsWith('.m3u8')) return false;
  if (url.origin === control.origin) return true;
  return (
    url.protocol === 'http:' &&
    control.protocol === 'http:' &&
    LOOPBACK_HOSTS.has(url.hostname) &&
    LOOPBACK_HOSTS.has(control.hostname)
  );
}

/** PDT 칸: null 이면 null, 꼴이 맞으면 epoch ms, 그 밖은 undefined(형식 오류) */
function parsePdt(value: unknown): number | null | undefined {
  if (value === null) return null;
  if (typeof value !== 'string' || !PDT_RE.test(value)) return undefined;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : undefined;
}

/**
 * 200 본문을 검사해 받는다. 키 다섯이 다 있고 형이 맞아야 한다. ready 면 url · 범위가 다 있고(첫 ≤ 끝) 국면이 live · vod 다.
 * 어긋나면 null(형식 오류 — 부르는 쪽이 「잠시 불가」로 접는다)
 * @param controlBase 제어면 주소(출처 대조의 기준)
 */
export function parseRewindStatus(body: unknown, controlBase: string): RewindStatus | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
  const raw = body as Record<string, unknown>;
  if (!['ready', 'mode', 'url', 'firstPdt', 'headPdt'].every((key) => key in raw)) return null;
  const { ready, mode, url } = raw;
  if (typeof ready !== 'boolean') return null;
  if (mode !== null && !MODES.includes(mode as RewindMode)) return null;
  if (url !== null && (typeof url !== 'string' || !isTrustedPlaylistUrl(url, controlBase)))
    return null;
  const firstPdtMs = parsePdt(raw.firstPdt);
  const headPdtMs = parsePdt(raw.headPdt);
  if (firstPdtMs === undefined || headPdtMs === undefined) return null;
  if (ready) {
    if (mode !== 'live' && mode !== 'vod') return null;
    if (url === null || firstPdtMs === null || headPdtMs === null) return null;
    if (firstPdtMs > headPdtMs) return null;
  }
  return { ready, mode: mode as RewindMode | null, url, firstPdtMs, headPdtMs };
}

/**
 * 제어면을 한 번 묻는다. 끊은 요청(signal)은 결과 없이 AbortError 를 던진다 — 부르는 쪽이 버린다
 * @param controlBase 제어면 주소(답의 url 출처 대조)
 */
export async function fetchRewindStatus(
  url: string,
  controlBase: string,
  signal?: AbortSignal,
): Promise<RewindStatusResult> {
  let response: Response;
  try {
    response = await fetch(url, { credentials: 'omit', signal });
  } catch (e) {
    if (signal?.aborted) throw e;
    return { kind: 'unavailable' };
  }
  if (response.status === 404) return { kind: 'none' };
  if (response.status !== 200) return { kind: 'unavailable' };
  let body: unknown;
  try {
    body = await response.json();
  } catch (e) {
    if (signal?.aborted) throw e;
    return { kind: 'unavailable' };
  }
  const status = parseRewindStatus(body, controlBase);
  return status === null ? { kind: 'unavailable' } : { kind: 'ok', status };
}
