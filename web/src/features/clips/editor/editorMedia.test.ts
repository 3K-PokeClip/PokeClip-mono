import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import {
  EDITOR_MEDIA_BYTES_LIMIT,
  EDITOR_MEDIA_LINGER_MS,
  clearEditorMedia,
  editorWindowFetching,
  editorWindowKey,
  holdEditorMedia,
  holdEditorWindowFetch,
  segmentTracks,
  trackAudioLoadBytes,
  trackPiecesBytes,
  watchEditorWindowFetch,
  windowBlobs,
} from './editorMedia';
import type { SegmentTracks } from './trackAudio/fmp4Audio';
import type { TrackAudioLoad, TrackPiece } from './trackAudio/loadTrackAudio';

// 맡긴 미디어는 창 하나가 수십 MB다: 쓰는 화면이 모두 내려가면 비워야 탭을 닫을 때까지 남지 않는다

/** 다시 싼 창 — 크기는 mp4 Blob 으로 잰다 */
const windowOf = (blob: Blob) => ({ blob, shiftSeconds: 0, segments: [] });
const BLOB = windowOf(new Blob(['mp4']));

/** 맡기는 소리(TrackAudioLoad) — 다시 받기 재료 없이 */
const loadOf = (tracks: TrackPiece[][]): TrackAudioLoad => ({
  tracks,
  missingSeqs: [],
  capture: 'none',
  reuse: { segments: new Map(), capture: null, decoded: new Map() },
});

describe('holdEditorMedia', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    clearEditorMedia();
  });

  afterEach(() => {
    clearEditorMedia();
    vi.useRealTimers();
  });

  it('화면이 서 있는 동안은 비우지 않는다', () => {
    const release = holdEditorMedia();
    windowBlobs.set('a', BLOB);

    vi.advanceTimersByTime(EDITOR_MEDIA_LINGER_MS * 10);

    expect(windowBlobs.get('a')).toBe(BLOB);
    release();
  });

  it('쓰는 화면이 모두 내려가면 잠시 뒤 비운다', () => {
    const release = holdEditorMedia();
    windowBlobs.set('a', BLOB);

    release();
    vi.advanceTimersByTime(EDITOR_MEDIA_LINGER_MS);

    expect(windowBlobs.get('a')).toBeUndefined();
  });

  it('라이브에서 편집기로 넘어가는 사이에는 비우지 않는다: 미리 받은 것을 꺼내 써야 한다', () => {
    const leaveLive = holdEditorMedia();
    windowBlobs.set('a', BLOB);

    leaveLive();
    vi.advanceTimersByTime(EDITOR_MEDIA_LINGER_MS - 1);
    const leaveEditor = holdEditorMedia();
    vi.advanceTimersByTime(EDITOR_MEDIA_LINGER_MS * 10);

    expect(windowBlobs.get('a')).toBe(BLOB);
    leaveEditor();
  });

  it('화면이 내려간 뒤에 끝난 받기도 남기지 않는다', () => {
    windowBlobs.set('late', BLOB);

    vi.advanceTimersByTime(EDITOR_MEDIA_LINGER_MS);

    expect(windowBlobs.get('late')).toBeUndefined();
  });

  it('계정이 바뀌면 바로 비운다: 앞 계정의 녹화가 메모리에 남지 않는다', () => {
    const release = holdEditorMedia();
    windowBlobs.set('a', BLOB);

    useAuthStore.getState().clearTokens();

    expect(windowBlobs.get('a')).toBeUndefined();
    release();
  });
});

describe('바이트 예산', () => {
  const blobOf = (bytes: number) => windowOf(new Blob([new Uint8Array(bytes)]));
  const MB = 1024 * 1024;

  beforeEach(() => {
    vi.useFakeTimers();
    clearEditorMedia();
  });

  afterEach(() => {
    clearEditorMedia();
    vi.useRealTimers();
  });

  it('개수가 한도 안이어도 합계가 예산을 넘으면 가장 오래 안 쓴 것부터 버린다', () => {
    const release = holdEditorMedia();
    windowBlobs.set('a', blobOf(90 * MB));
    windowBlobs.set('b', blobOf(90 * MB));
    windowBlobs.set('c', blobOf(90 * MB));

    expect(windowBlobs.get('a')).toBeUndefined();
    expect(windowBlobs.get('b')).toBeDefined();
    expect(windowBlobs.get('c')).toBeDefined();
    release();
  });

  it('가장 최근 하나는 예산을 넘어도 남긴다', () => {
    const release = holdEditorMedia();
    windowBlobs.set('small', blobOf(MB));
    windowBlobs.set('huge', blobOf(EDITOR_MEDIA_BYTES_LIMIT + MB));

    expect(windowBlobs.get('huge')).toBeDefined();
    expect(windowBlobs.get('small')).toBeUndefined();
    release();
  });

  it('쓴 항목은 최근으로 올라가 버려지지 않는다', () => {
    const release = holdEditorMedia();
    windowBlobs.set('a', blobOf(90 * MB));
    windowBlobs.set('b', blobOf(90 * MB));
    windowBlobs.get('a');
    windowBlobs.set('c', blobOf(90 * MB));

    expect(windowBlobs.get('b')).toBeUndefined();
    expect(windowBlobs.get('a')).toBeDefined();
    release();
  });

  it('같은 열쇠를 다시 두면 앞 크기는 합계에서 빠진다', () => {
    const release = holdEditorMedia();
    windowBlobs.set('a', blobOf(150 * MB));
    windowBlobs.set('a', blobOf(MB));
    windowBlobs.set('b', blobOf(150 * MB));

    expect(windowBlobs.get('a')).toBeDefined();
    expect(windowBlobs.get('b')).toBeDefined();
    release();
  });

  it('소리는 표본 수 × 채널 수 × 4바이트로 잰다', () => {
    const buffer = { length: 1_000_000, numberOfChannels: 2 } as unknown as AudioBuffer;
    expect(trackPiecesBytes([[{ startSeconds: 0, buffer }], [{ startSeconds: 0, buffer }]])).toBe(
      16_000_000,
    );

    const release = holdEditorMedia();
    const big = { length: 20 * MB, numberOfChannels: 1 } as unknown as AudioBuffer; // 80MB
    segmentTracks.set('a', loadOf([[{ startSeconds: 0, buffer: big }]]));
    segmentTracks.set('b', loadOf([[{ startSeconds: 0, buffer: big }]]));
    segmentTracks.set('c', loadOf([[{ startSeconds: 0, buffer: big }]]));

    expect(segmentTracks.get('a')).toBeUndefined();
    expect(segmentTracks.get('b')).toBeDefined();
    release();
  });
});

describe('trackAudioLoadBytes', () => {
  it('맡긴 소리는 푼 소리에 다시 받기 재료(판독한 조각의 프레임 바이트)까지 잰다: 기다림 고리가 다시 쓰려고 든다', () => {
    const buffer = { length: 1_000, numberOfChannels: 2 } as unknown as AudioBuffer; // 8,000
    const frames = (count: number, size: number) =>
      Array.from({ length: count }, () => new Uint8Array(size));
    const segment = (size: number) =>
      ({
        video: null,
        audio: [
          { frames: frames(3, size), config: {}, timescale: 48000, timing: null },
          { frames: frames(2, size), config: {}, timescale: 48000, timing: null },
        ],
      }) as unknown as SegmentTracks;
    const load: TrackAudioLoad = {
      ...loadOf([[{ startSeconds: 0, buffer }]]),
      reuse: {
        segments: new Map([
          [1, segment(100)],
          [2, segment(10)],
        ]),
        capture: null,
        decoded: new Map([['0:1:0:5', buffer]]),
      },
    };

    // 푼 소리 8,000 + 프레임 (3 + 2)·100 + (3 + 2)·10 — 푼 덩어리 표(decoded)는 tracks 와 같은 소리라 두 번 세지 않는다
    expect(trackAudioLoadBytes(load)).toBe(8_000 + 500 + 50);
  });
});

describe('editorWindowKey', () => {
  it('영상 키와 창 첫 · 끝 조각 seq 가 모두 같아야 같은 창이다', () => {
    expect(editorWindowKey('key-1', 102, 109)).toBe(editorWindowKey('key-1', 102, 109));
    expect(editorWindowKey('key-1', 102, 109)).not.toBe(editorWindowKey('key-1', 102, 110));
    expect(editorWindowKey('key-1', 102, 109)).not.toBe(editorWindowKey('key-1', 101, 109));
    expect(editorWindowKey('key-1', 102, 109)).not.toBe(editorWindowKey('key-2', 102, 109));
  });
});

// 편집기가 창(영상 · 소리)을 받는 동안 카드 미리 받기를 쉬게 하는 표시(M3 — 둘이 겹칠 때 메모리가 가장 높았다)
describe('holdEditorWindowFetch', () => {
  it('잡은 쪽이 모두 놓을 때까지 받는 중이다', () => {
    const first = holdEditorWindowFetch();
    const second = holdEditorWindowFetch();
    expect(editorWindowFetching()).toBe(true);

    first();
    expect(editorWindowFetching()).toBe(true);
    second();
    expect(editorWindowFetching()).toBe(false);
  });

  it('같은 놓기를 두 번 불러도 한 번만 놓는다: 남이 잡은 것까지 풀지 않는다', () => {
    const mine = holdEditorWindowFetch();
    const other = holdEditorWindowFetch();

    mine();
    mine();
    expect(editorWindowFetching()).toBe(true);
    other();
    expect(editorWindowFetching()).toBe(false);
  });

  it('받는 중 ↔ 아님이 바뀔 때만 알린다', () => {
    const seen: boolean[] = [];
    const stop = watchEditorWindowFetch((fetching) => seen.push(fetching));

    const first = holdEditorWindowFetch();
    const second = holdEditorWindowFetch();
    first();
    second();
    stop();
    holdEditorWindowFetch()();

    expect(seen).toEqual([true, false]);
  });
});
