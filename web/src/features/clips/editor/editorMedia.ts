import { ensurePlaybackAccess, isPlaybackExpired } from '@/api/playbackAccess';
import { fetchRecordingSpans } from '@/api/mediaPlayback';
import { mediaStreamId, timelineBaseMs, type BroadcastRow } from '@/api/clipEditor';
import { onSessionAdvance } from '@/stores/auth';
import {
  rebaseTimeline,
  recordingTimeline,
  type RecordingPiece,
  type RecordingTimeline,
} from '@/features/player/recordingTimeline';
import type { TrackPiece } from './trackAudio/loadTrackAudio';

// 편집기가 여는 방송의 녹화와 미리 받아 둔 미디어(POK-499). 하이라이트 카드가 편집기에 들어가기 전에 영상 창 · 소리 조각을
// 받아 여기 맡기고, 편집기는 같은 주소면 다시 받지 않고 꺼내 쓴다. 두 쪽이 같은 주소를 만들어야 해서 녹화 읽기와 시간축
// 셈도 여기 하나로 둔다.

/**
 * 이 방송의 녹화 — 방송 시간과 겹치는 구간들(시계 차이 1분까지)을 한 시간축에 놓는다(첫 구간 시작 ~ 마지막 구간 끝, 틈 포함).
 * 재생 서버의 목록은 경로(스트림키) 전체라 앞 방송 녹화도 섞여 오고, 재접속하면 한 방송의 녹화도 여러 구간이 된다(로컬 리뷰
 * 2라운드). 구간 목록은 미리보기 창이 틈에 걸리지 않게 쓴다(POK-253). 재생 서버가 없거나 녹화가 없으면 null
 */
export async function loadRecording(broadcast: BroadcastRow): Promise<RecordingTimeline | null> {
  // CDN 뒤 녹화 목록·미리보기는 출입증 쿠키가 있어야 열린다(POK-317). 미리보기 창은 이 키로 갱신한다(ensureMediaAccess)
  const access = await ensurePlaybackAccess(broadcast.streamId, mediaStreamId(broadcast));
  // 못 받았는데 녹화를 물으면 CDN 403이 「녹화 없음」으로 접혀 미리보기 없이 열리고 다시 묻지 않는다(PR #320 codex)
  if (access?.kind === 'failed')
    throw new Error('영상 출입증을 받지 못했어요. 잠시 뒤 다시 열어 주세요');
  if (access?.kind === 'expired' || isPlaybackExpired(broadcast.streamId))
    throw new Error('보관 기간(60일)이 지나 이 방송의 영상을 볼 수 없어요');
  return recordingTimeline(
    // 녹화 재생 서버의 경로는 영상 키다(POK-233) — 방송 번호가 회차 번호면 그 이름의 녹화가 없다
    await fetchRecordingSpans(mediaStreamId(broadcast)),
    Date.parse(broadcast.startedAt!),
    broadcast.endedAt ? Date.parse(broadcast.endedAt) : null,
  );
}

export interface EditorTimeline {
  /** 편집기 시간축 0초의 절대 ms(시각 기준점) */
  base: number;
  /** 녹화 끝(기준점 축의 초). 녹화가 없으면 0 */
  recordingEndSeconds: number;
  /** 녹화 구간들(기준점 축의 초). 녹화가 없으면 undefined */
  pieces: RecordingPiece[] | undefined;
}

export function editorTimelineOf(
  broadcast: BroadcastRow,
  recording: RecordingTimeline | null,
): EditorTimeline {
  // 🔴 카드·조각 ms의 0초 = 시각 기준점(서버 → 녹화 재생 서버 → 방송 시작, POK-255). 저장·다시 열기가 같은 값을 써야 제자리다.
  //    방송 시작 편지 시각과 수십 초 갈리고, 조각 장부의 위치 값은 방송을 넘어 이어지므로 녹화 시작과도 갈릴 수 있다
  const base = timelineBaseMs(broadcast, recording?.startMs ?? null);
  return {
    base,
    // 녹화 끝을 기준점 축의 초로 — 녹화 길이는 녹화 시작부터 잰 값이라 그대로 쓰면 두 시작의 차만큼 끝이 어긋난다
    recordingEndSeconds: recording
      ? (recording.startMs + recording.durationSeconds * 1000 - base) / 1000
      : 0,
    // 녹화 구간들도 기준점 축으로 옮긴다 — 녹화 축과 기준점 축은 두 시작의 차만큼 갈린다
    pieces: recording === null ? undefined : rebaseTimeline(recording, base).pieces,
  };
}

/**
 * 맡겨 두는 개수. 창 하나가 영상 수십 MB · 풀어 둔 소리 수십 MB라 화면에 보이는 카드 몇 장 몫만 둔다. 넘치면 가장 오래 안 쓴
 * 것부터 버리고, 버린 카드는 편집기가 예전처럼 직접 받는다
 */
export const EDITOR_MEDIA_LIMIT = 4;

/**
 * 맡긴 미디어를 쓰는 화면(라이브 · 편집기)이 모두 내려간 뒤 더 두는 시간. 둘 사이를 넘어가는 동안은 비우지 않아야 미리 받은 것을
 * 꺼내 쓴다. 안 비우면 창 넷 몫(수백 MB)이 탭을 닫을 때까지 남는다
 */
export const EDITOR_MEDIA_LINGER_MS = 30_000;

let holders = 0;
let releaseTimer: ReturnType<typeof setTimeout> | undefined;

function releaseLater() {
  clearTimeout(releaseTimer);
  releaseTimer = setTimeout(clearEditorMedia, EDITOR_MEDIA_LINGER_MS);
}

/** 맡긴 미디어를 쓰는 화면이 서 있는 동안 잡고 있는다. 돌려준 함수가 놓는다. 효과에서 그대로 돌려주면 된다 */
export function holdEditorMedia(): () => void {
  holders += 1;
  clearTimeout(releaseTimer);
  return () => {
    holders -= 1;
    if (holders === 0) releaseLater();
  };
}

/**
 * 맡겨 두는 바이트 예산(저장소마다). 개수만 걸면 창 넷이 모두 큰 카드일 때 수백 MB가 남는다. 넘치면 가장 오래 안 쓴 것부터 버린다.
 * 단 가장 최근 항목 하나는 예산을 넘어도 남긴다: 큰 카드 하나가 맡기자마자 버려지면 편집기로 넘어가기 전에 사라진다
 */
export const EDITOR_MEDIA_BYTES_LIMIT = 200 * 1024 * 1024;

/** 풀어 둔 소리의 크기: 표본 수 × 채널 수 × 4바이트(Float32) */
export function trackPiecesBytes(tracks: TrackPiece[][]): number {
  let total = 0;
  for (const track of tracks)
    for (const piece of track)
      total += (piece.buffer.length ?? 0) * (piece.buffer.numberOfChannels ?? 1) * 4;
  return total;
}

function recentlyUsed<T>(sizeOf: (value: T) => number) {
  const entries = new Map<string, { value: T; bytes: number }>();
  let totalBytes = 0;
  const drop = (key: string) => {
    const known = entries.get(key);
    if (known === undefined) return;
    totalBytes -= known.bytes;
    entries.delete(key);
  };
  return {
    get(key: string): T | undefined {
      const known = entries.get(key);
      if (known === undefined) return undefined;
      entries.delete(key);
      entries.set(key, known);
      return known.value;
    },
    set(key: string, value: T) {
      drop(key);
      const bytes = sizeOf(value);
      entries.set(key, { value, bytes });
      totalBytes += bytes;
      // 가장 최근 하나(방금 둔 것)는 남긴다
      while (
        entries.size > 1 &&
        (entries.size > EDITOR_MEDIA_LIMIT || totalBytes > EDITOR_MEDIA_BYTES_LIMIT)
      )
        drop(entries.keys().next().value!);
      // 화면이 내려간 뒤에 끝난 받기(받기는 줄을 떠나면 끝까지 간다)도 남기지 않는다
      if (holders === 0) releaseLater();
    },
    clear: () => {
      entries.clear();
      totalBytes = 0;
    },
  };
}

/** 받아 둔 영상 창(녹화 재생 서버 주소 → mp4) */
export const windowBlobs = recentlyUsed<Blob>((blob) => blob.size);
/** 받아 풀어 둔 소리 조각(segmentTrackKey → 트랙별 덩어리) */
export const segmentTracks = recentlyUsed<TrackPiece[][]>(trackPiecesBytes);

/** 소리 조각의 열쇠: 방송 번호와 받은 창의 절대 ms. 편집기의 트랙 미리 듣기가 묻는 값과 같다 */
export const segmentTrackKey = (streamId: string, fromMs: number, toMs: number) =>
  `${streamId}:${fromMs}:${toMs}`;

/** 맡긴 것을 다 비운다. 계정이 바뀌어도 비운다: 앞 계정의 녹화가 메모리에 남지 않게(시험 사이에도 부른다) */
export function clearEditorMedia() {
  clearTimeout(releaseTimer);
  windowBlobs.clear();
  segmentTracks.clear();
}
onSessionAdvance(clearEditorMedia);
