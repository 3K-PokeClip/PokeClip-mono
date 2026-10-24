import { describe, expect, it } from 'vitest';
import type { ClipSnapshot, UploadSnapshot } from '@/api/clipEditor';
import { clipLabel, clipProgressBar, clipSettled } from './clipProgress';

// 편집기 헤더의 진행 문구와 상태 확인(폴링)이 멈추는 때(POK-291): 업로드 정보가 있으면 업로드가 끝날 때까지 묻는다.

function clip(over: Partial<ClipSnapshot> = {}): ClipSnapshot {
  return {
    id: 77,
    streamId: 's1',
    recipeId: 31,
    recipeVersion: 1,
    requestedBy: '9',
    status: 'rendered',
    progress: null,
    outputs: [{ outputId: 'o1', kind: 'video', s3Key: 'k' }],
    error: null,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T11:00:00Z',
    ...over,
  };
}

function upload(status: string, code?: string): UploadSnapshot {
  return {
    id: 5,
    clipId: 77,
    outputId: 'o1',
    title: '보스 막타',
    status,
    videoId: null,
    error: code ? { code, message: null } : null,
    requestedBy: '9',
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T11:00:00Z',
  };
}

const intent = { title: '보스 막타', privacyStatus: 'private', thumbnailSource: 'none' } as const;

describe('clipLabel', () => {
  it('만드는 중 N% → 올리는 중 → 업로드됨 / 업로드 실패', () => {
    expect(
      clipLabel(
        clip({
          status: 'rendering',
          progress: { percent: 42, stage: null, attempt: 1, jobId: 'j' },
        }),
      ),
    ).toBe('영상 #77 만드는 중 42%');
    // 완성과 업로드 줄은 한 트랜잭션이라, 정보가 있는데 줄이 없으면 자동 업로드를 건너뛴 것이다
    expect(clipLabel(clip({ uploadRequest: intent, upload: null }))).toBe(
      '영상 #77 완성 · 업로드는 시작되지 않았어요',
    );
    expect(clipLabel(clip({ uploadRequest: intent, upload: upload('uploading') }))).toBe(
      '영상 #77 유튜브에 올리는 중',
    );
    expect(clipLabel(clip({ upload: upload('uploaded') }))).toBe('영상 #77 업로드됨');
    expect(clipLabel(clip({ upload: upload('failed', 'QUOTA_EXCEEDED') }))).toBe(
      '영상 #77 업로드 실패(QUOTA_EXCEEDED)',
    );
    expect(clipLabel(clip({ upload: upload('checking') }))).toBe('영상 #77 업로드 확인 필요');
  });

  it('만드는 중인데 서버가 퍼센트를 안 줬으면 0%라 하지 않고 %를 뺀다(POK-444)', () => {
    expect(clipLabel(clip({ status: 'rendering', progress: null }))).toBe('영상 #77 만드는 중');
  });

  it('업로드 정보가 없는 완성 영상(옛 서버 포함)은 지금처럼 「완성」이다', () => {
    expect(clipLabel(clip())).toBe('영상 #77 완성');
    expect(clipLabel(clip({ uploadRequest: null, upload: null }))).toBe('영상 #77 완성');
  });
});

describe('clipSettled: 상태 확인을 멈추는 때', () => {
  it('렌더 중이면 계속, 실패면 멈춘다', () => {
    expect(clipSettled(clip({ status: 'queued' }))).toBe(false);
    expect(clipSettled(clip({ status: 'rendering' }))).toBe(false);
    expect(clipSettled(clip({ status: 'failed' }))).toBe(true);
  });

  it('완성됐어도 업로드 정보가 있으면 업로드가 끝날 때까지 묻는다', () => {
    // 줄이 없으면 기다려도 안 생긴다: 끝없이 묻지 않는다
    expect(clipSettled(clip({ uploadRequest: intent, upload: null }))).toBe(true);
    expect(clipSettled(clip({ uploadRequest: intent, upload: upload('queued') }))).toBe(false);
    expect(clipSettled(clip({ uploadRequest: intent, upload: upload('uploading') }))).toBe(false);
    expect(clipSettled(clip({ uploadRequest: intent, upload: upload('uploaded') }))).toBe(true);
    expect(clipSettled(clip({ uploadRequest: intent, upload: upload('failed') }))).toBe(true);
    // 확인 필요는 사람이 채널을 봐야 풀린다
    expect(clipSettled(clip({ uploadRequest: intent, upload: upload('checking') }))).toBe(true);
  });

  it('업로드 정보가 없는 완성 영상은 바로 멈춘다(옛 길)', () => {
    expect(clipSettled(clip())).toBe(true);
  });
});

describe('clipProgressBar: 헤더 진행 막대(POK-444)', () => {
  it('렌더 퍼센트가 있으면 그 값이다', () => {
    expect(
      clipProgressBar(
        clip({
          status: 'rendering',
          progress: { percent: 42, stage: null, attempt: 1, jobId: 'j' },
        }),
      ),
    ).toEqual({ value: 42 });
  });

  it('퍼센트가 없는 기다림(만드는 중 · 대기열 · 유튜브에 올리는 중)은 불확정이다: 지어낸 %가 없다', () => {
    expect(clipProgressBar(clip({ status: 'rendering', progress: null }))).toEqual({ value: null });
    expect(clipProgressBar(clip({ status: 'queued' }))).toEqual({ value: null });
    expect(clipProgressBar(clip({ uploadRequest: intent, upload: upload('uploading') }))).toEqual({
      value: null,
    });
    // 완성됐어도 업로드는 아직 대기열이다
    expect(clipProgressBar(clip({ uploadRequest: intent, upload: upload('queued') }))).toEqual({
      value: null,
    });
  });

  it('기다릴 것이 없으면 막대가 없다: 완성 · 실패 · 업로드됨 · 업로드 실패 · 확인 필요', () => {
    expect(clipProgressBar(clip())).toBeNull();
    expect(clipProgressBar(clip({ status: 'failed' }))).toBeNull();
    expect(clipProgressBar(clip({ uploadRequest: intent, upload: null }))).toBeNull();
    expect(clipProgressBar(clip({ upload: upload('uploaded') }))).toBeNull();
    expect(clipProgressBar(clip({ upload: upload('failed', 'QUOTA_EXCEEDED') }))).toBeNull();
    expect(clipProgressBar(clip({ upload: upload('checking') }))).toBeNull();
  });
});
