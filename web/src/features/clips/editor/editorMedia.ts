import { ensurePlaybackAccess, isPlaybackExpired } from '@/api/playbackAccess';
import { fetchRecordingSpans } from '@/api/mediaPlayback';
import { mediaStreamId, timelineBaseMs, type BroadcastRow } from '@/api/clipEditor';
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

function recentlyUsed<T>() {
  const entries = new Map<string, T>();
  return {
    get(key: string): T | undefined {
      const value = entries.get(key);
      if (value !== undefined) {
        entries.delete(key);
        entries.set(key, value);
      }
      return value;
    },
    set(key: string, value: T) {
      entries.delete(key);
      entries.set(key, value);
      while (entries.size > EDITOR_MEDIA_LIMIT) entries.delete(entries.keys().next().value!);
    },
    clear: () => entries.clear(),
  };
}

/** 받아 둔 영상 창(녹화 재생 서버 주소 → mp4) */
export const windowBlobs = recentlyUsed<Blob>();
/** 받아 풀어 둔 소리 조각(segmentTrackKey → 트랙별 덩어리) */
export const segmentTracks = recentlyUsed<TrackPiece[][]>();

/** 소리 조각의 열쇠: 방송 번호와 받은 창의 절대 ms. 편집기의 트랙 미리 듣기가 묻는 값과 같다 */
export const segmentTrackKey = (streamId: string, fromMs: number, toMs: number) =>
  `${streamId}:${fromMs}:${toMs}`;

/** 시험이 서로 섞이지 않게 비운다 */
export function clearEditorMedia() {
  windowBlobs.clear();
  segmentTracks.clear();
}
