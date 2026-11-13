import { CUT_MAX_MS } from '@/api/clipEditor';
import type { RecordingPiece } from '@/features/player/recordingTimeline';
import type { EditorPlaylist, PlaylistSegment } from './editorPlaylist';

// 편집기 되감기 목록(editorPlaylist)을 편집기 표시 초 S = (T − base)/1000 축의 개요 구간 · 컷 값 T 의 조각 · 창 조각으로
// 옮긴다(POK-473 설계 prw2 3.2 · 4절). T 는 ③ 목록 PDT 축 ms 다.
//
// 🔴 F 규칙 — T 를 조각으로 푸는 첫 단계는 clip 과 같다: PDT ≤ T 인 seq 최대(겹치면 나중 조각). 판 2 새 장에서 PDT 가 1초
//    미만 겹치면 겹친 자리는 뒤 조각 몫이다. 앞 조각 끝(PDT + EXTINF)과 다음 PDT 사이의 「죽은 자리」(판 NULL 래칫 · 표지
//    없는 PDT 건너뜀 · 회차 바뀜 틈)에는 미디어가 없어 다음 조각으로 푼다 — 그 자리로 시크하면 다음 조각 시작으로 간다.
//    앞 조각에 붙이면 그 조각 미디어 밖을 가리킨다. 이 죽은 자리 풀이는 편집기 표시 규칙이라 clip 과 다르다 — clip 은
//    이어진 틈 안의 끝을 (k, EXTINF) 로 접는다(cutPosition.ts 머리 주석).
// 조각 값은 seq 와 그 줄의 PDT · EXTINF 로만 정한다. 방송 중 1시간 창이 앞 조각을 밀어내도(목록 미끄러짐) 남은 seq 의
// 결과는 그대로다.

/**
 * 창 앞뒤로 더 받는 여유(초) — source/editorWindow.ts PAD_SECONDS 와 같은 값이다. editorWindow 가 이 모듈을 부르므로
 * 거기서 꺼내 쓰면 순환 import 가 되어 다시 적는다. 같은 값인지는 시험이 지킨다
 */
const WINDOW_PAD_SECONDS = 10;

/** 창 길이 상한(S 축 초) — 컷 최대 180초 + 앞뒤 여유. 조각 경계 · PDT 겹침 때문에 미디어는 이보다 길 수 있다(M3) */
export const MAX_WINDOW_SECONDS = CUT_MAX_MS / 1000 + 2 * WINDOW_PAD_SECONDS;

/** 런 하나와 그 개요 구간 */
interface RunPiece {
  run: readonly PlaylistSegment[];
  piece: RecordingPiece;
}

function secondsOf(ms: number, base: number): number {
  return (ms - base) / 1000;
}

function endMsOf(segment: PlaylistSegment): number {
  return segment.pdtMs + segment.durationMs;
}

/**
 * 런마다 [첫 PDT, 마지막 PDT + EXTINF) 를 S 로 놓은 구간. 런 뒤 다음 줄(GAP 줄이나 뒤 런 첫 조각)이 앞 런 끝보다 일찍
 * 서면(겹침) 앞 구간을 그 줄 시작에서 자른다 — 겹친 자리는 F 규칙으로 그 줄 몫이고, 구간은 겹치지 않아야
 * 한다(RecordingPiece). 길이가 남지 않는 런은 뺀다
 */
function runPieces(list: EditorPlaylist, base: number): RunPiece[] {
  return list.runs.flatMap((run) => {
    const last = run.at(-1)!;
    // rows 는 seq 차례라 런 마지막 seq 보다 큰 첫 줄이 목록에서 그 바로 뒤 줄이다
    const next = list.rows.find((row) => row.seq > last.seq);
    const endMs = Math.min(endMsOf(last), next?.pdtMs ?? Number.POSITIVE_INFINITY);
    const piece = {
      fromSeconds: secondsOf(run[0]!.pdtMs, base),
      toSeconds: secondsOf(endMs, base),
    };
    return piece.toSeconds > piece.fromSeconds ? [{ run, piece }] : [];
  });
}

/**
 * 개요 타임라인 — 런마다 미디어가 있는 구간(S 축 · 시간순 · 겹치지 않음)과 마지막 구간 끝. GAP 줄은 바이트가 없어 어느
 * 구간에도 들지 않는다. 런 안 죽은 자리에서는 구간을 끊지 않는다(그 자리는 F 규칙이 다음 조각으로 푼다). 구간이 없으면 끝은 0
 * @param base 편집기 0초의 절대 ms — 세션 동안 바꾸지 않는다
 */
export function sourceTimelineOf(
  list: EditorPlaylist,
  base: number,
): { pieces: RecordingPiece[]; endSeconds: number } {
  const pieces = runPieces(list, base).map(({ piece }) => piece);
  return { pieces, endSeconds: pieces.at(-1)?.toSeconds ?? 0 };
}

/**
 * 컷 값 T 의 조각(F 규칙) — PDT ≤ T 인 seq 최대. T 가 그 조각 끝(PDT + EXTINF) 이후면 죽은 자리라 다음 줄이다. GAP 줄도
 * 줄이라 그대로 돌려준다(바이트 없음은 부르는 쪽이 gap 으로 본다). 첫 PDT 앞이나 마지막 줄 끝 이후면 null
 * @param ms 목록 PDT 축 ms
 */
export function segmentAtMs(list: EditorPlaylist, ms: number): PlaylistSegment | null {
  const rows = list.rows;
  // rows 는 seq 차례라 뒤에서 처음 맞는 줄이 seq 최대다
  const at = rows.findLastIndex((row) => row.pdtMs <= ms);
  const row = rows[at];
  if (row === undefined) return null;
  return ms < endMsOf(row) ? row : (rows[at + 1] ?? null);
}

/** 창 시작이 든 런 — 구간 안이면 그 런, 구간 사이(GAP · 틈)면 다음 런. 마지막 구간 끝 이후면 null */
function runFrom(runs: readonly RunPiece[], startSeconds: number): RunPiece | null {
  return runs.find(({ piece }) => piece.toSeconds > startSeconds) ?? null;
}

/**
 * 창 [startSeconds, endSeconds)(S 축)를 덮는 조각 — 한 런 안에서만 고른다(다른 런 조각은 한 영상으로 이어 쌀 수 없다).
 * 창은 MAX_WINDOW_SECONDS 로 자르고, 시작 런은 개요 구간과 같은 셈으로 정한다(맞닿은 두 런의 경계에서 앞 런을 고르지
 * 않게). 런 안에서는 창 시작 · 끝 직전의 T 를 F 규칙으로 푼 조각 사이를 고른다. GAP 줄은 넣지 않는다. 창이 그 런 구간
 * 앞에서 끝나면(목록 머리 앞 · 런 사이 틈 · GAP 줄 자리) 덮을 미디어가 없어 빈 목록이다
 */
export function segmentsForWindow(
  list: EditorPlaylist,
  base: number,
  win: { startSeconds: number; endSeconds: number },
): readonly PlaylistSegment[] {
  const endSeconds = Math.min(win.endSeconds, win.startSeconds + MAX_WINDOW_SECONDS);
  if (endSeconds <= win.startSeconds) return [];
  const found = runFrom(runPieces(list, base), win.startSeconds);
  if (found === null) return [];
  const { run, piece } = found;
  // 판정은 조각을 푸는 정수 ms 축에서 한다 — 초 축으로 보면 창 끝이 머리 PDT 와 1ms 안일 때 두 축이 갈린다
  const startMs = Math.floor(base + 1000 * Math.max(win.startSeconds, piece.fromSeconds));
  const endMs = Math.ceil(base + 1000 * endSeconds);
  // 창 끝 직전 T 가 다음 런 첫 조각(죽은 자리 규칙)이나 null(목록 머리 앞)로 풀려 런 조각을 끌어오지 않게 한다
  if (endMs <= run[0]!.pdtMs) return [];
  const firstSeq = segmentAtMs(list, startMs)?.seq ?? run[0]!.seq;
  const lastSeq = segmentAtMs(list, endMs - 1)?.seq ?? run.at(-1)!.seq;
  return run.filter((segment) => segment.seq >= firstSeq && segment.seq <= lastSeq);
}
