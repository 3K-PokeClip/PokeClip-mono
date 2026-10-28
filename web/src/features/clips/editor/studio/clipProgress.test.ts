import { describe, expect, it } from 'vitest';
import type { ClipSnapshot, UploadSnapshot } from '@/api/clipEditor';
import { clipProgressBar, clipSettled, clipStatusView } from './clipProgress';

// 편집기 헤더의 영상 상태 알약(POK-492)과 상태 확인(폴링)이 멈추는 때(POK-291): 업로드 정보가 있으면 업로드가 끝날 때까지 묻는다.

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

describe('clipStatusView: 영상 상태 알약과 작은 창(POK-492)', () => {
  const rendering = (percent: number | null) =>
    clip({
      status: 'rendering',
      uploadRequest: intent,
      progress: percent === null ? null : { percent, stage: null, attempt: 1, jobId: 'j' },
    });

  it.each([
    ['대기', clip({ status: 'queued', uploadRequest: intent }), 'progress', '차례 기다리는 중', 0],
    ['만드는 중', rendering(42), 'progress', '영상 만드는 중 42%', 1],
    [
      '올리는 중',
      clip({ uploadRequest: intent, upload: upload('uploading') }),
      'progress',
      '유튜브에 올리는 중',
      2,
    ],
    ['업로드됨', clip({ upload: upload('uploaded') }), 'success', '유튜브에 올라갔어요', 3],
    [
      '업로드 실패',
      clip({ upload: upload('failed', 'QUOTA_EXCEEDED') }),
      'danger',
      '업로드 실패',
      2,
    ],
    ['확인 필요', clip({ upload: upload('checking') }), 'warning', '확인 필요', 2],
    ['영상 만들기 실패', clip({ status: 'failed' }), 'danger', '영상 만들기 실패', 1],
  ] as const)('%s: 상태색 하나와 짧은 말, 단계', (_, c, tone, title, stage) => {
    const view = clipStatusView(c);
    expect(view).toMatchObject({ tone, title, stage });
  });

  it('화면 말에 「주문」이 없다(사용자가 못 알아듣는다, 2026-10-28)', () => {
    const all = [
      clip({ status: 'queued', uploadRequest: intent }),
      rendering(null),
      clip({ uploadRequest: intent, upload: null }),
    ].map(clipStatusView);
    for (const view of all) expect(`${view.title} ${view.detail}`).not.toContain('주문');
  });

  it('만드는 중인데 서버가 퍼센트를 안 줬으면 0%라 하지 않고 %를 뺀다(POK-444)', () => {
    expect(clipStatusView(rendering(null)).title).toBe('영상 만드는 중');
    expect(clipStatusView(rendering(null)).progress).toEqual({ value: null });
  });

  it('업로드됨: 유튜브에서 보기 주소와 공개 범위를 말한다', () => {
    const view = clipStatusView(
      clip({ upload: { ...upload('uploaded'), videoId: 'abc123', privacyStatus: 'unlisted' } }),
    );
    expect(view.actions).toEqual(['youtube', 'library']);
    expect(view.youtubeUrl).toBe('https://youtu.be/abc123');
    expect(view.detail).toBe('일부 공개로 올라갔어요.');
    // 영상 번호가 아직 없으면 가짜 주소를 만들지 않는다
    expect(clipStatusView(clip({ upload: upload('uploaded') })).actions).toEqual(['library']);
  });

  it('업로드 실패는 보관함과 같은 사유를 말하고 다시 올리기를 준다', () => {
    const view = clipStatusView(clip({ upload: upload('failed', 'QUOTA_EXCEEDED') }));
    expect(view.detail).toBe(
      '오늘 유튜브 업로드 한도를 다 써서 올리지 못했어요. 내일 다시 올려 주세요.',
    );
    expect(view.actions).toEqual(['retryUpload', 'library']);
  });

  it('완성됐는데 업로드가 시작되지 않았으면(정보는 있는데 줄이 없음) 기다리지 않고 다시 올리기를 준다', () => {
    // 완성과 업로드 줄은 한 트랜잭션이라, 정보가 있는데 줄이 없으면 자동 업로드를 건너뛴 것이다
    const view = clipStatusView(clip({ uploadRequest: intent, upload: null }));
    expect(view).toMatchObject({ tone: 'warning', title: '업로드 안 됨', progress: null });
    expect(view.actions).toEqual(['retryUpload', 'library']);
  });

  it('업로드 정보가 없는 완성 영상(옛 서버 포함)은 「영상 완성」이다', () => {
    expect(clipStatusView(clip())).toMatchObject({ tone: 'success', title: '영상 완성', stage: 3 });
  });

  it('확인 필요는 푸는 곳(보관함)으로만 보낸다', () => {
    expect(clipStatusView(clip({ upload: upload('checking') })).actions).toEqual(['library']);
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
