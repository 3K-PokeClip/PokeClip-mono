import { describe, expect, it } from 'vitest';
import type { ClipSnapshot, LibraryEntry, LibraryStatus, UploadSnapshot } from '@/api/clipEditor';
import { noteText, stepViewForClip, uploadFailureText } from './libraryView';
import { MOCK_CLIPS } from './libraryFixture';
import { toLibraryClip } from './useLibraryMockState';

// 상세 패널의 진행 카드(POK-510) 판정

const NOW = new Date('2026-10-30T14:30:00+09:00');
const STARTED = '2026-10-17T19:52:00+09:00';
const ENDED = '2026-10-17T22:06:00+09:00';
/** 편집 구간 1:23:45 ~ 1:24:30 */
const CUT = { inAtMs: 5_025_000, outAtMs: 5_070_000 };

function video(over: Partial<ClipSnapshot> = {}): ClipSnapshot {
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
    createdAt: '2026-10-30T14:21:00+09:00',
    updatedAt: '2026-10-30T14:23:00+09:00',
    upload: null,
    ...over,
  };
}

function upload(over: Partial<UploadSnapshot>): UploadSnapshot {
  return {
    id: 7,
    clipId: 5,
    outputId: 'o1',
    title: '보스 막타',
    status: 'uploading',
    videoId: null,
    error: null,
    requestedBy: '9',
    createdAt: '2026-10-30T14:23:00+09:00',
    updatedAt: '2026-10-30T14:23:00+09:00',
    ...over,
  };
}

function entry(
  status: LibraryStatus,
  latestClip: ClipSnapshot | null = video(),
  over: Partial<LibraryEntry> = {},
): LibraryEntry {
  return {
    recipeId: 12,
    streamId: 's1',
    creatorId: '9',
    recipeVersion: 2,
    cut: CUT,
    status,
    broadcast: {
      status: 'ended',
      startedAt: STARTED,
      endedAt: ENDED,
      vodExpiresAt: '2026-12-16T22:06:00+09:00',
    },
    latestClip,
    createdAt: '2026-10-30T14:05:00+09:00',
    updatedAt: '2026-10-30T14:20:00+09:00',
    ...over,
  };
}

describe('stepViewForClip — 진행 카드는 서버 영상의 지금 판에만 선다(POK-510)', () => {
  it('목업 줄 · 영상이 없는 줄 · 편집 중(옛 판 영상)은 카드가 없다', () => {
    expect(stepViewForClip(MOCK_CLIPS[0]!, NOW)).toBeNull();
    expect(stepViewForClip(toLibraryClip(entry('rendered', null), '9'), NOW)).toBeNull();
    expect(stepViewForClip(toLibraryClip(entry('editing'), '9'), NOW)).toBeNull();
  });

  it('만드는 중이면 「영상 만들기」가 지금 단계이고 판 이름이 붙는다 — 편집기 작은 창과 같은 판정이다', () => {
    const step = stepViewForClip(
      toLibraryClip(
        entry(
          'rendering',
          video({
            status: 'rendering',
            progress: { percent: 42, stage: null, attempt: 1, jobId: 'j' },
          }),
        ),
        '9',
      ),
      NOW,
    )!;
    expect(step.versionLabel).toBe('편집본 v2');
    expect(step.view.steps.map((s) => s.state)).toEqual(['done', 'now', 'todo']);
    expect(step.view.steps[0]!.note).toBe('14:21');
    expect(step.view.steps[1]!.note).toBe('42%');
    expect(step.view.progress).toEqual({ value: 42 });
    expect(step.detail).toBe('영상을 만들고 있어요.');
  });

  it('확인 필요 · 업로드 실패는 이 화면에서 할 일이라 설명을 보관함 안내로 바꾼다', () => {
    const checking = toLibraryClip(
      entry('checking', video({ upload: upload({ status: 'checking' }) })),
      '9',
    );
    const checkingStep = stepViewForClip(checking, NOW)!;
    expect(checkingStep.view.steps[2]!.state).toBe('warning');
    expect(checkingStep.detail).toBe(noteText('checking'));

    const failed = toLibraryClip(
      entry(
        'rendered',
        video({
          upload: upload({ status: 'failed', error: { code: 'QUOTA_EXCEEDED', message: null } }),
        }),
      ),
      '9',
    );
    const failedStep = stepViewForClip(failed, NOW)!;
    expect(failedStep.view.steps[2]!.state).toBe('danger');
    expect(failedStep.detail).toBe(uploadFailureText(failed));
    // 편집기 말(「영상 만들기」를 다시 눌러)이 아니다
    expect(failedStep.detail).not.toMatch(/영상 만들기」를 다시/);
  });

  it('올라갔으면 세 단계가 다 끝나고 올린 시각이 붙는다', () => {
    const step = stepViewForClip(
      toLibraryClip(
        entry(
          'uploaded',
          video({
            upload: upload({
              status: 'uploaded',
              videoId: 'abc',
              updatedAt: '2026-10-30T14:26:00+09:00',
            }),
          }),
        ),
        '9',
      ),
      NOW,
    )!;
    expect(step.view.steps.map((s) => s.state)).toEqual(['done', 'done', 'done']);
    expect(step.view.steps[2]!.note).toBe('14:26');
    expect(step.view.progress).toBeNull();
  });
});
