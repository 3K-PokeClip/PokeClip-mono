import { describe, expect, it } from 'vitest';
import type { ClipSnapshot, LibraryEntry, LibraryStatus } from '@/api/clipEditor';
import { toLibraryClip } from './useLibraryMockState';

// 보관함 문(clip GET /api/clip/library)의 줄을 화면 칸으로 옮기는 규칙(POK-251).

function entry(status: LibraryStatus, over: Partial<LibraryEntry> = {}): LibraryEntry {
  return {
    recipeId: 12,
    streamId: 's1',
    creatorId: '9',
    recipeVersion: 2,
    cut: { inAtMs: 1_000_000, outAtMs: 1_042_000 },
    status,
    broadcast: {
      status: 'ended',
      startedAt: '2026-09-20T10:00:00Z',
      endedAt: null,
      vodExpiresAt: null,
    },
    latestClip: null,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
    ...over,
  };
}

function clipWithUpload(videoId: string | null): ClipSnapshot {
  return {
    id: 5,
    streamId: 's1',
    recipeId: 12,
    recipeVersion: 2,
    requestedBy: '9',
    status: 'rendered',
    progress: null,
    outputs: [{ outputId: 'o1', kind: 'video', s3Key: 'k' }],
    error: null,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
    upload: {
      id: 1,
      clipId: 5,
      outputId: 'o1',
      title: '제목',
      status: videoId ? 'uploaded' : 'uploading',
      videoId,
      error: null,
      requestedBy: '9',
      createdAt: '2026-09-20T12:00:00Z',
      updatedAt: '2026-09-20T12:00:00Z',
    },
  };
}

describe('toLibraryClip', () => {
  it.each([
    ['editing', 'editing'],
    ['rendering', 'editing'],
    ['rendered', 'ready'],
    ['failed', 'failed'],
    ['uploading', 'ready'],
    ['checking', 'ready'],
    ['uploaded', 'published'],
  ] as const)('서버 %s는 화면 %s다', (server, screen) => {
    expect(toLibraryClip(entry(server), '9').status).toBe(screen);
  });

  it('올린 편집본은 유튜브 주소를 싣는다', () => {
    const clip = toLibraryClip(entry('uploaded', { latestClip: clipWithUpload('abc123') }), '9');
    expect(clip.youtubeUrl).toBe('https://youtu.be/abc123');
  });

  it('올리는 중에는 주소가 없고 보조 줄이 그렇다고 말한다', () => {
    const clip = toLibraryClip(entry('uploading', { latestClip: clipWithUpload(null) }), '9');
    expect(clip.youtubeUrl).toBeUndefined();
    expect(clip.subtitleLabel).toBe('유튜브에 올리는 중');
  });

  it('확인 중은 사람이 채널을 봐야 한다고 말한다', () => {
    expect(toLibraryClip(entry('checking'), '9').subtitleLabel).toBe(
      '유튜브에 올라갔는지 확인이 필요해요',
    );
  });

  it('만든 사람이 나면 「나」, 아니면 회원 번호다', () => {
    expect(toLibraryClip(entry('editing'), '9').owner).toEqual({ name: '나', me: true });
    expect(toLibraryClip(entry('editing'), '1').owner).toEqual({ name: '편집자 9', me: false });
  });

  it('길이는 컷에서, 편집기 주소는 편집본 번호에서 온다', () => {
    const clip = toLibraryClip(entry('editing'), '9');
    expect(clip.durationSec).toBe(42);
    expect(clip.editHref).toBe('/clips/editor/studio?recipe=12');
  });
});
