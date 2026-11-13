import { requestSegmentAudioAccess, type SegmentAudioAccess } from '@/api/clipEditor';
import { mediaControlBaseUrl } from '@/features/player/mediaSource';
import { onSessionAdvance } from '@/stores/auth';
import type { PlaylistSegment } from './editorPlaylist';

// 창 소리 주소 응답(설계 prw2 5절 4) — 창 조각이 덮는 [첫 PDT, 마지막 PDT + EXTINF) 의 ② 서명 주소를 clip 에 묻는다. 판 NULL
// 창의 ② 머리 판독(editorWindow ensureWindowHeads)과 트랙 미리 듣기(useTrackPreview loadSegmentTracks)가 같은 범위를 함께
// 묻는다 — 같은 범위를 묻는 중이면 그 응답을 같이 기다린다(c9′ r1 9번 → c10b′). 응답이 오면 잊는다: 서명 주소는 5분짜리라
// 다시 묻기(서명 만료 · ② 기다림 고리)는 늘 새 응답이어야 한다.
//
// 🔴 계정 경계: 응답은 묻는 계정의 권한으로 서명된 주소다. 계정이 바뀌면(onSessionAdvance) 묻는 중인 표를 비운다 — 안 비우면 새
//    계정이 앞 계정 요청의 응답(앞 계정 권한의 ② 주소)을 같이 받는다(c10b′ r1 1번).
//
// 🔴 주소 거름: 응답의 ② 주소(행 url · 확보 칸 url)는 https 만 둔다. 루프백 http 는 되감기 제어면 주소도 루프백 http 일
//    때(로컬 실험대)만 둔다 — rewindStatus 의 isTrustedPlaylistUrl 과 같은 조건이라, 제어면이 https 인 prod 에서는 루프백
//    http 도 버린다. 버린 행은 응답에 없는 seq 와 같다(② 머리는 missing). 호스트 허용 목록은 버킷 주소 설정이 웹에 없어
//    두지 않는다(보안 검수 prw2 c10b L-4 · E4b-1 L1 · E4b-2 L3 · PR 단계 r1 L-1).

const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);

/** 묻는 중인 요청 — 방송 번호와 범위가 열쇠다 */
const asking = new Map<string, Promise<SegmentAudioAccess>>();

onSessionAdvance(() => asking.clear());

/**
 * 창 조각이 덮는 범위의 소리 주소 응답. 같은 범위를 묻는 중이면 그 요청을 같이 기다린다(실패도 같이 받는다)
 * @param streamId clip 방송 번호
 * @param signal 묻던 쪽이 멈추면(시한 · 창 바뀜 · 화면 떠남) 이 호출은 던지고 그 요청은 더는 같이 쓰지 않는다 — 끝나지 않는
 *   요청이 표에 남으면 같은 창을 다시 열어도 그 요청만 기다려 회복하지 못한다(c10b′ r1 4번)
 */
export function windowAudioAccess(
  streamId: string,
  segs: readonly Pick<PlaylistSegment, 'pdtMs' | 'durationMs'>[],
  signal?: AbortSignal,
): Promise<SegmentAudioAccess> {
  const first = segs[0];
  const last = segs.at(-1);
  if (first === undefined || last === undefined) return Promise.reject(new Error('창 조각이 없다'));
  const toMs = last.pdtMs + last.durationMs;
  if (signal?.aborted) return Promise.reject(new Error('audio access stopped'));
  const key = `${streamId}:${first.pdtMs}-${toMs}`;
  const request = asking.get(key) ?? ask(key, streamId, first.pdtMs, toMs);
  return signal === undefined ? request : untilAborted(request, signal, () => forget(key, request));
}

/** 새로 묻고 표에 둔다. 끝나면 이 요청일 때만 뺀다: 계정이 바뀌어 비운 뒤 같은 열쇠로 새 요청이 섰을 수 있다 */
function ask(
  key: string,
  streamId: string,
  fromMs: number,
  toMs: number,
): Promise<SegmentAudioAccess> {
  const request: Promise<SegmentAudioAccess> = requestSegmentAudioAccess(streamId, fromMs, toMs)
    .then(trustedAccess)
    .finally(() => forget(key, request));
  asking.set(key, request);
  return request;
}

function parseUrlOrNull(value: string | null): URL | null {
  if (value === null) return null;
  try {
    return new URL(value);
  } catch {
    return null;
  }
}

function isLoopbackHttp(url: URL | null): boolean {
  return url?.protocol === 'http:' && LOOPBACK_HOSTS.has(url.hostname);
}

/** ② 주소로 받아도 되는가 — https, 또는 제어면도 루프백 http 일 때(로컬 실험대)의 루프백 http */
function isTrustedAudioUrl(value: string): boolean {
  const url = parseUrlOrNull(value);
  if (url?.protocol === 'https:') return true;
  return isLoopbackHttp(url) && isLoopbackHttp(parseUrlOrNull(mediaControlBaseUrl()));
}

/** 믿을 수 없는 ② 주소의 행 · 확보 칸을 뺀 응답. 버린 것은 수만 경고한다(주소는 서명이 들어 싣지 않는다) */
function trustedAccess(access: SegmentAudioAccess): SegmentAudioAccess {
  const segments = access.segments.filter((row) => isTrustedAudioUrl(row.url));
  const capture = access.audioCaptureSegment;
  const dropCapture = capture !== undefined && !isTrustedAudioUrl(capture.url);
  const dropped = access.segments.length - segments.length + (dropCapture ? 1 : 0);
  if (dropped === 0) return access;
  console.warn(`[editor] 창 소리 주소 응답에서 https 가 아닌 주소 ${dropped}개를 버렸어요`);
  const { audioCaptureSegment: _capture, ...rest } = access;
  return dropCapture ? { ...rest, segments } : { ...access, segments };
}

/** 그 열쇠가 아직 이 요청이면 표에서 뺀다 */
function forget(key: string, request: Promise<SegmentAudioAccess>): void {
  if (asking.get(key) === request) asking.delete(key);
}

/** signal 이 멈추면 onAbort 를 부르고 던진다. 요청이 먼저 끝나면 그 결과다 */
function untilAborted<T>(
  request: Promise<T>,
  signal: AbortSignal,
  onAbort: () => void,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const stop = () => {
      onAbort();
      reject(new Error('audio access stopped'));
    };
    if (signal.aborted) {
      stop();
      return;
    }
    signal.addEventListener('abort', stop, { once: true });
    request.then(resolve, reject).finally(() => signal.removeEventListener('abort', stop));
  });
}
