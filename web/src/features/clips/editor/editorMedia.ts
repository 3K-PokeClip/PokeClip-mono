import { onSessionAdvance } from '@/stores/auth';
import type { RemuxedWindow } from './source/remuxWindow';
import type { TrackAudioLoad, TrackPiece } from './trackAudio/loadTrackAudio';

// 편집기가 미리 받아 둔 미디어(POK-499). 하이라이트 카드가 편집기에 들어가기 전에 영상 창 · 소리 조각을 받아 여기 맡기고,
// 편집기는 같은 열쇠면 다시 받지 않고 꺼내 쓴다. 두 쪽이 같은 열쇠를 만들어야 해서 열쇠 셈도 여기 하나로 둔다.

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

/**
 * 맡긴 소리(TrackAudioLoad)의 크기: 푼 소리에 다시 받기 재료 — 판독한 조각의 프레임 바이트 — 를 더한다(② 기다림 고리가
 * 빠진 조각만 받아 다시 묶으려고 든다 · 설계 prw2 7절 c10b′). 푼 덩어리 표(reuse.decoded)는 tracks 와 같은 소리라 더하지 않는다
 */
export function trackAudioLoadBytes(load: TrackAudioLoad): number {
  let frames = 0;
  for (const segment of load.reuse.segments.values())
    for (const track of segment.audio) for (const frame of track.frames) frames += frame.byteLength;
  return trackPiecesBytes(load.tracks) + frames;
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

/** 받아 둔 영상 창(editorWindowKey → 다시 싼 창 — mp4 와 조각 표) */
export const windowBlobs = recentlyUsed<RemuxedWindow>((remuxed) => remuxed.blob.size);

/**
 * 영상 창의 열쇠: 영상 키와 창 첫 · 끝 조각 seq(설계 prw2 5절 4). 같은 seq 의 조각 바이트는 바뀌지 않으므로 같은 범위면 같은
 * 창이다. 편집기와 카드의 미리 받기가 같은 셈으로 낸다
 */
export const editorWindowKey = (mediaKey: string, firstSeq: number, lastSeq: number) =>
  `${mediaKey}:${firstSeq}-${lastSeq}`;
/**
 * 받아 풀어 둔 소리 조각(segmentTrackKey → 받기 결과 — 트랙별 덩어리는 그 창 Blob 시각에 놓았다). 빠진 행 · 확보 기다림도 함께
 * 들어 편집기가 꺼내 쓰면 ② 기다림 고리를 이어 돈다
 */
export const segmentTracks = recentlyUsed<TrackAudioLoad>(trackAudioLoadBytes);

/**
 * 소리 조각의 열쇠: 방송 번호와 창 첫 · 끝 조각 seq(설계 prw2 5절 4). 소리는 그 창을 다시 싼 Blob 시각에 놓으므로 영상 창과
 * 같은 범위로 가른다 — 같은 seq 범위면 같은 다시 싸기 · 같은 자리다. 편집기의 트랙 미리 듣기가 묻는 값과 같다
 */
export const segmentTrackKey = (streamId: string, firstSeq: number, lastSeq: number) =>
  `${streamId}:${firstSeq}-${lastSeq}`;

/** 맡긴 것을 다 비운다. 계정이 바뀌어도 비운다: 앞 계정의 녹화가 메모리에 남지 않게(시험 사이에도 부른다) */
export function clearEditorMedia() {
  clearTimeout(releaseTimer);
  windowBlobs.clear();
  segmentTracks.clear();
}
onSessionAdvance(clearEditorMedia);
