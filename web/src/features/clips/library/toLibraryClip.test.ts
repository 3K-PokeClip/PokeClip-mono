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
  // 진행 단계(POK-291): 만드는 중 → 올리는 중 → 업로드됨. 업로드 실패는 서버 상태가 아니라 웹이 만든다(아래)
  it.each([
    ['editing', 'editing'],
    ['rendering', 'rendering'],
    ['rendered', 'ready'],
    ['failed', 'failed'],
    ['uploading', 'uploading'],
    ['checking', 'checking'],
    ['uploaded', 'published'],
  ] as const)('서버 %s는 화면 %s다', (server, screen) => {
    expect(toLibraryClip(entry(server), '9').status).toBe(screen);
  });

  it('완성인데 가장 최근 업로드가 실패면 「업로드 실패」다: 서버는 완성으로 돌려준다', () => {
    const latest = clipWithUpload(null);
    latest.upload = { ...latest.upload!, status: 'failed', error: { code: 'X', message: null } };
    expect(toLibraryClip(entry('rendered', { latestClip: latest }), '9').status).toBe(
      'uploadFailed',
    );
  });

  it('올린 편집본은 유튜브 주소를 싣는다', () => {
    const clip = toLibraryClip(entry('uploaded', { latestClip: clipWithUpload('abc123') }), '9');
    expect(clip.youtubeUrl).toBe('https://youtu.be/abc123');
  });

  it('올리는 중에는 주소가 없고 진행 줄이 그렇다고 말한다: 「자막」 칸은 진행을 말하지 않는다', () => {
    const clip = toLibraryClip(entry('uploading', { latestClip: clipWithUpload(null) }), '9');
    expect(clip.youtubeUrl).toBeUndefined();
    expect(clip.progressLabel).toBe('유튜브에 올리는 중');
    expect(clip.subtitleLabel).toBe('—');
  });

  it('만드는 중은 퍼센트를, 업로드 정보가 있으면 끝나고 올린다는 것까지 말한다', () => {
    const rendering = {
      ...clipWithUpload(null),
      status: 'rendering' as const,
      upload: null,
      progress: { percent: 42, stage: null, attempt: 1, jobId: 'j' },
    };
    expect(toLibraryClip(entry('rendering', { latestClip: rendering }), '9').progressLabel).toBe(
      '영상 만드는 중 42%',
    );
    const withIntent = entry('rendering', {
      latestClip: rendering,
      uploadRequest: { title: '창 제목', privacyStatus: 'private', thumbnailSource: 'none' },
    });
    expect(toLibraryClip(withIntent, '9').progressLabel).toBe(
      '영상 만드는 중 42% · 끝나면 유튜브에 올려요',
    );
  });

  it('업로드 줄이 없으면 제목은 창에서 적은 업로드 정보의 제목, 그것도 없으면 「편집본 #번호」', () => {
    const withIntent = entry('rendering', {
      uploadRequest: { title: '창 제목', privacyStatus: 'private', thumbnailSource: 'none' },
    });
    expect(toLibraryClip(withIntent, '9').title).toBe('창 제목');
    expect(toLibraryClip(entry('rendering'), '9').title).toBe('편집본 #12');
    // 올린 적이 있으면 업로드 제목이 정본이다
    expect(
      toLibraryClip(
        entry('uploaded', {
          latestClip: clipWithUpload('v1'),
          uploadRequest: { title: '창 제목', privacyStatus: 'private', thumbnailSource: 'none' },
        }),
        '9',
      ).title,
    ).toBe('제목');
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
