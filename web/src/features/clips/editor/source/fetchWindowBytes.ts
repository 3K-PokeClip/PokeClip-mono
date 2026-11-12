'use client';

import { ensureMediaAccess, reportPlaybackForbidden } from '@/api/playbackAccess';
import { mediaCredentials } from '@/api/mediaPlayback';
import type { PlaylistSegment } from './editorPlaylist';

// 편집기 창의 ③ 재포장본(init + 조각) 바이트를 받는다(설계 4절 · 6절 위험 9).
//
// CloudFront 서명 쿠키로 받는다: 출입증을 받은 뒤에만 쿠키를 싣는다(mediaCredentials). 요청은 단순 GET 이라 프리플라이트가
// 없다 — Range · 사용자 정의 머리를 싣지 않는다. CloudFront ExposeHeaders 가 비어 브라우저는 응답 머리를 보여 주지 않으므로
// 상태 코드와 본문만 읽는다. 403 · 갱신 · 한 번 더는 녹화 창 받기(useEditorVideoPlayback 의 fetchRecordingWindow)와 같은 관례다.
// GAP 줄의 주소는 받지 않는다: 그 자리는 객체가 없다(편집기 목록 파서가 런을 GAP 에서 끊는다).

/** 출입증을 새로 받고도 403 이다 — 다시 받을 길이 없다. seq 가 null 이면 init */
export class SegmentForbiddenError extends Error {
  constructor(readonly seq: number | null) {
    super(`조각 받기 거절(403) — ${seq ?? 'init'}`);
    this.name = 'SegmentForbiddenError';
  }
}

/** 404 — 보관 기간 · 방송 중 편집 범위(dvr 하한) 밖으로 사라졌다. seq 가 null 이면 init */
export class SegmentGoneError extends Error {
  constructor(readonly seq: number | null) {
    super(`조각이 없다(404) — ${seq ?? 'init'}`);
    this.name = 'SegmentGoneError';
  }
}

export interface WindowBytes {
  init: Uint8Array;
  segments: { seq: number; bytes: Uint8Array }[];
}

/**
 * 창 조각(한 런 · 한 MAP)의 init 과 조각 바이트. 조각은 넘긴 순서 그대로다. GAP 조각 · MAP 이 둘 이상 · 빈 목록이면 묻지
 * 않고 던진다. 한 요청이라도 실패하면 그 오류로 끝난다(SegmentForbiddenError · SegmentGoneError · 그 밖의 상태는 Error)
 */
export async function fetchWindowBytes(
  mediaKey: string,
  segs: readonly PlaylistSegment[],
  signal?: AbortSignal,
): Promise<WindowBytes> {
  const mapUri = singleMap(segs);
  // 출입증이 곧 만료되면 새로 받은 뒤 부른다(POK-317). 받아 둔 것이 쓸 만하면 기다리지 않는다
  await ensureMediaAccess(mediaKey);
  const [init, segments] = await Promise.all([
    fetchBytes(mediaKey, mapUri, null, signal),
    Promise.all(
      segs.map(async (seg) => ({
        seq: seg.seq,
        bytes: await fetchBytes(mediaKey, seg.uri, seg.seq, signal),
      })),
    ),
  ]);
  return { init, segments };
}

/** 창의 MAP 주소 하나. 창은 init 하나 안이어야 이은 바이트가 한 파일이 된다(Vanilagy/mediabunny#528) */
function singleMap(segs: readonly PlaylistSegment[]): string {
  const [first] = segs;
  if (first === undefined) throw new Error('창 조각이 없다');
  if (segs.some((seg) => seg.gap)) throw new Error('GAP 조각은 받지 않는다');
  if (segs.some((seg) => seg.mapUri !== first.mapUri)) throw new Error('창이 MAP 둘에 걸쳤다');
  return first.mapUri;
}

async function fetchBytes(
  mediaKey: string,
  url: string,
  seq: number | null,
  signal: AbortSignal | undefined,
): Promise<Uint8Array> {
  const load = () => fetch(url, { signal, credentials: mediaCredentials() });
  let res = await load();
  // 403 이면 쿠키가 지워졌다(다른 탭 · 쿠키 한도 · 만료): 출입증을 버리고 다시 받아 한 번 더 부른다
  if (res.status === 403) {
    reportPlaybackForbidden(mediaKey);
    await ensureMediaAccess(mediaKey);
    res = await load();
  }
  if (res.status === 403) throw new SegmentForbiddenError(seq);
  if (res.status === 404) throw new SegmentGoneError(seq);
  if (!res.ok) throw new Error(String(res.status));
  return new Uint8Array(await res.arrayBuffer());
}
