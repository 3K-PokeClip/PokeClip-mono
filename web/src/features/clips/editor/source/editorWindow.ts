import { requestSegmentAudioAccess } from '@/api/clipEditor';
import { playableFrom, type RecordingPiece } from '@/features/player/recordingTimeline';
import { editorWindowKey } from '../editorMedia';
import type { PlaylistSegment } from './editorPlaylist';
import { segmentsForWindow, sourceTimelineOf } from './editorSourceTimeline';
import { fetchWindowBytes } from './fetchWindowBytes';
import { ensureHeads } from './fileHeadProbe';
import { remuxWindow, type RemuxedWindow } from './remuxWindow';
import type { SourceList } from './useEditorSource';

// 편집기 미리보기 창 정하기 · 받기(POK-473 설계 prw2 5절 4) — 편집기(useEditorVideoPlayback)와 카드의 미리 받기
// (prefetchCardEditor)가 같은 셈으로 같은 창 · 같은 열쇠를 낸다.
//   decideWindow       구간 ± 여유를 지금 ③ 목록의 개요 구간 안으로 자르고, 그 런 안 조각을 고른다(segmentsForWindow)
//   fetchEditorWindow  조각을 받아(fetchWindowBytes) 일반 mp4 로 다시 싼다(remuxWindow). 하나라도 실패하면 나머지를 끊는다
//   ensureWindowHeads  판 NULL 조각의 ② 머리를 캐시에 채운다 — 행 주소는 clip 창 소리 주소 응답(소리 문맥과 같은 응답)
// 녹화 재생 서버(/playback)는 부르지 않는다.

/** 구간 앞뒤로 더 받는 여유. 카드의 미리 받기(POK-499)는 목록이 구간 끝 + 이 여유까지 올라와야 창이 굳는다고 본다 */
export const PAD_SECONDS = 10;

export interface Window {
  startSeconds: number;
  endSeconds: number;
  /** 구간에 녹화가 하나도 없다(틈 안이거나 녹화 끝 뒤) — 받지 않고 미리보기 실패로 알린다 */
  empty?: true;
}

export function windowFor(
  range: { startSeconds: number; endSeconds: number },
  totalSeconds: number,
  pieces: RecordingPiece[] | undefined,
): Window {
  const wide = {
    startSeconds: Math.max(0, range.startSeconds - PAD_SECONDS),
    endSeconds: Math.min(totalSeconds, range.endSeconds + PAD_SECONDS),
  };
  if (pieces === undefined) return wide;
  // 구간의 시작이 든 녹화 구간(틈이면 다음 구간) 안으로 자른다
  const piece = playableFrom({ pieces }, range.startSeconds);
  const containing = piece && pieces.find((p) => p.toSeconds === piece.toSeconds)!;
  // 구간 자체에 녹화가 없으면(통째로 틈 안이거나 녹화 끝 뒤) 받지 않는다 — 여유 10초만 다음 녹화에 닿아도 그 녹화를 보이면
  // 고른 장면이 아닌 영상이다(PR #205 codex 2·3판)
  if (!containing || containing.fromSeconds >= range.endSeconds) {
    return { startSeconds: range.startSeconds, endSeconds: range.startSeconds, empty: true };
  }
  return {
    startSeconds: Math.max(wide.startSeconds, containing.fromSeconds),
    endSeconds: Math.min(wide.endSeconds, containing.toSeconds),
  };
}

/** 창 하나 — 한 런 안의 조각과 그 조각이 덮는 S 범위. 받은 영상(Blob)은 이 조각들을 다시 싼 것이다 */
export interface WindowTarget {
  /** 받아 둔 창의 열쇠(editorWindowKey) */
  key: string;
  segs: readonly PlaylistSegment[];
  /** 이 창을 지은 목록 주소(SourceList.url — 제어면이 검사한 주소). 조각 실패 사건에 싣는다 */
  listUrl: string;
  /** 조각이 덮는 S 범위 [첫 PDT, 마지막 PDT + EXTINF) */
  startSeconds: number;
  endSeconds: number;
}

export type WindowDecision =
  /** 방송 중 · 목록이 창 끝(구간 끝 + 여유)에 못 미친다 — 목록이 자라기를 기다린다 */
  | { kind: 'wait' }
  /** 구간에 받을 조각이 없다 */
  | { kind: 'empty' }
  | { kind: 'window'; target: WindowTarget };

/**
 * 구간을 덮는 창을 지금 목록으로 정한다. 편집기와 카드의 미리 받기(POK-499)가 같은 셈으로 같은 열쇠를 낸다. 창은 지금 목록의
 * 개요 구간 안으로 자르므로, 목록이 미끄러져 구간 앞이 목록 머리 앞이 돼도 머리부터의 창이 된다
 * @param waitForGrowth 방송 중이라 목록이 더 자랄 수 있다 — 창 끝에 못 미치면 wait
 */
export function decideWindow(
  range: { startSeconds: number; endSeconds: number },
  list: SourceList,
  base: number,
  mediaKey: string,
  waitForGrowth: boolean,
): WindowDecision {
  const { pieces, endSeconds } = sourceTimelineOf(list.playlist, base);
  if (waitForGrowth && range.endSeconds + PAD_SECONDS > endSeconds) return { kind: 'wait' };
  const win = windowFor(range, endSeconds, pieces);
  if (win.empty) return { kind: 'empty' };
  const segs = segmentsForWindow(list.playlist, base, win);
  const first = segs[0];
  const last = segs.at(-1);
  if (first === undefined || last === undefined) return { kind: 'empty' };
  return {
    kind: 'window',
    target: {
      key: editorWindowKey(mediaKey, first.seq, last.seq),
      segs,
      listUrl: list.url,
      startSeconds: (first.pdtMs - base) / 1000,
      endSeconds: (last.pdtMs + last.durationMs - base) / 1000,
    },
  };
}

/**
 * 창 조각을 받아 일반 mp4 하나로 다시 싼다. 편집기와 카드의 미리 받기(POK-499)가 함께 쓴다. 조각 하나라도 실패하면 아직 받는
 * 나머지 요청을 끊고 그 오류를 던진다(SegmentForbiddenError · SegmentGoneError · RemuxMismatchError 등)
 */
export async function fetchEditorWindow(
  mediaKey: string,
  segs: readonly PlaylistSegment[],
  signal?: AbortSignal,
): Promise<RemuxedWindow> {
  const own = new AbortController();
  const stop = () => own.abort();
  if (signal?.aborted) own.abort();
  signal?.addEventListener('abort', stop, { once: true });
  try {
    return await remuxWindow(await fetchWindowBytes(mediaKey, segs, own.signal));
  } catch (e) {
    own.abort();
    throw e;
  } finally {
    signal?.removeEventListener('abort', stop);
  }
}

/**
 * 판 NULL 창 조각의 ② 머리를 캐시에 채운다(설계 prw2 3.2 결정 3). 창 범위 [첫 PDT, 마지막 PDT + EXTINF) 의 소리 주소를 clip 에
 * 한 번 묻고, 그 응답 행의 ② 서명 주소로 읽는다. 응답에 행이 없는 seq 는 missing 이다. 판 2 조각은 읽지 않는다. 소리 주소를 못
 * 물었으면 경고만 남긴다(머리는 아직 모름 — 배치는 근사로 먼저 보인다). 던지지 않는다
 * @param streamId clip 방송 번호(소리 주소 요청)
 * @param mediaKey 영상 키(머리 캐시)
 */
export async function ensureWindowHeads(
  streamId: string,
  mediaKey: string,
  segs: readonly PlaylistSegment[],
  signal?: AbortSignal,
): Promise<void> {
  const unversioned = new Set(segs.filter((seg) => seg.timing === null).map((seg) => seg.seq));
  const first = segs[0];
  const last = segs.at(-1);
  if (unversioned.size === 0 || first === undefined || last === undefined) return;
  let rows: { seq: number; url: string }[];
  try {
    const access = await requestSegmentAudioAccess(
      streamId,
      first.pdtMs,
      last.pdtMs + last.durationMs,
    );
    rows = access.segments.filter((row) => unversioned.has(row.seq));
  } catch (e) {
    if (!signal?.aborted) {
      console.warn('[editor] 창 소리 주소를 받지 못해 ② 머리를 못 읽었어요:', (e as Error).message);
    }
    return;
  }
  if (signal?.aborted) return;
  const found = new Set(rows.map((row) => row.seq));
  const missing = [...unversioned].filter((seq) => !found.has(seq));
  await ensureHeads(
    mediaKey,
    rows.map(({ seq, url }) => ({ seq, url })),
    missing,
    signal,
  );
}
