import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import {
  EDITOR_MEDIA_BYTES_LIMIT,
  EDITOR_MEDIA_LINGER_MS,
  clearEditorMedia,
  holdEditorMedia,
  segmentTracks,
  trackPiecesBytes,
  windowBlobs,
} from './editorMedia';

// 맡긴 미디어는 창 하나가 수십 MB다: 쓰는 화면이 모두 내려가면 비워야 탭을 닫을 때까지 남지 않는다

const BLOB = new Blob(['mp4']);

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
  const blobOf = (bytes: number) => new Blob([new Uint8Array(bytes)]);
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
    segmentTracks.set('a', [[{ startSeconds: 0, buffer: big }]]);
    segmentTracks.set('b', [[{ startSeconds: 0, buffer: big }]]);
    segmentTracks.set('c', [[{ startSeconds: 0, buffer: big }]]);

    expect(segmentTracks.get('a')).toBeUndefined();
    expect(segmentTracks.get('b')).toBeDefined();
    release();
  });
});
