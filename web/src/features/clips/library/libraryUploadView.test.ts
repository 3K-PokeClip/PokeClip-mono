import { describe, expect, it } from 'vitest';
import {
  ClipApiError,
  type ClipSnapshot,
  type LibraryEntry,
  type LibraryStatus,
} from '@/api/clipEditor';
import {
  detailViewForClip,
  noteText,
  uploadErrorMessage,
  uploadFailureText,
  uploadTitleProblem,
} from './libraryView';
import { toLibraryClip } from './useLibraryMockState';

// 유튜브 업로드를 보관함에 잇는 규칙(POK-111). 서버 규약은 clip 업로드 문(POK-220)이다.

function snapshot(upload: ClipSnapshot['upload']): ClipSnapshot {
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
    upload,
  };
}

function failedUpload(code: string, message: string | null = null): ClipSnapshot['upload'] {
  return {
    id: 7,
    clipId: 5,
    outputId: 'o1',
    title: '보스 막타',
    status: 'failed',
    videoId: null,
    error: { code, message },
    requestedBy: '9',
    createdAt: '2026-09-20T12:00:00Z',
    updatedAt: '2026-09-20T12:01:00Z',
  };
}

function entry(
  status: LibraryStatus,
  latestClip: ClipSnapshot | null = snapshot(null),
): LibraryEntry {
  return {
    recipeId: 12,
    streamId: 's1',
    creatorId: '9',
    recipeVersion: 2,
    cut: { inAtMs: 0, outAtMs: 30_000 },
    status,
    broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
    latestClip,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
  };
}

describe('uploadTitleProblem — 서버 규칙(1~100자, < > 금지)을 보내기 전에 잰다', () => {
  it('보통 제목은 통과한다', () => {
    expect(uploadTitleProblem('보스 막타 · 역전 순간')).toBeNull();
  });

  it('비었거나 공백뿐이면 막는다', () => {
    expect(uploadTitleProblem('')).toBe('유튜브 제목을 적어 주세요.');
    expect(uploadTitleProblem('   ')).toBe('유튜브 제목을 적어 주세요.');
  });

  it('글자 수는 코드포인트로 센다 — 이모지 100개는 통과, 101개는 막는다', () => {
    expect(uploadTitleProblem('😀'.repeat(100))).toBeNull();
    expect(uploadTitleProblem('😀'.repeat(101))).toBe('유튜브 제목은 100자까지예요.');
  });

  it('앞뒤 공백은 빼고 센다', () => {
    expect(uploadTitleProblem(` ${'가'.repeat(100)} `)).toBeNull();
  });

  it('꺾쇠(< >)는 유튜브가 거절해 막는다', () => {
    expect(uploadTitleProblem('a < b')).toBe('유튜브 제목에는 < 와 > 를 쓸 수 없어요.');
    expect(uploadTitleProblem('a > b')).toBe('유튜브 제목에는 < 와 > 를 쓸 수 없어요.');
  });
});

describe('uploadErrorMessage — 주문이 거절된 사유를 사람 말로', () => {
  it.each([
    [new ClipApiError(400, 'invalid_request', 'title'), '유튜브 제목을 확인해 주세요.'],
    [new ClipApiError(409, 'clip_not_rendered', null), '영상이 아직 완성되지 않았어요.'],
    [new ClipApiError(404, 'not_found', null), '편집본을 찾을 수 없어요. 목록을 새로 고쳐 주세요.'],
    [
      new ClipApiError(503, 'upload_unavailable', null),
      '지금은 업로드를 받을 수 없어요. 잠시 뒤 다시 올려 주세요.',
    ],
    [
      new ClipApiError(503, 'authorization_unavailable', null),
      '권한 확인이 잠시 안 돼요. 잠시 뒤 다시 올려 주세요.',
    ],
  ])('%s', (error, message) => {
    expect(uploadErrorMessage(error)).toBe(message);
  });

  it('출력이 여럿이라 고를 영상을 몰라 거절되면 그렇게 말한다', () => {
    expect(uploadErrorMessage(new ClipApiError(400, 'invalid_request', 'outputId'))).toBe(
      '올릴 영상을 고를 수 없어요. 이 편집본은 영상 출력이 하나가 아니에요.',
    );
  });

  it('모르는 사유는 서버 코드를 그대로 보인다', () => {
    expect(uploadErrorMessage(new ClipApiError(500, null, null))).toBe('요청이 실패했다 (500)');
    expect(uploadErrorMessage(new TypeError('Failed to fetch'))).toBe('Failed to fetch');
  });
});

describe('uploadFailureText — 지난 업로드가 실패한 편집본의 안내', () => {
  it('실패가 없으면 null', () => {
    expect(uploadFailureText(toLibraryClip(entry('rendered'), '9'))).toBeNull();
  });

  it.each(['YOUTUBE_NOT_LINKED', 'YOUTUBE_UNLINKED', 'YOUTUBE_BROKEN'])(
    '연동이 끊긴 실패(%s)는 채널 연동으로 보낸다',
    (code) => {
      const clip = toLibraryClip(entry('rendered', snapshot(failedUpload(code))), '9');
      expect(uploadFailureText(clip)).toBe(
        '스트리머의 유튜브 채널 연동이 없거나 끊겨 올리지 못했어요. 스트리머가 설정 › 채널 연동에서 다시 연결한 뒤 올려 주세요.',
      );
    },
  );

  it('하루 한도는 내일 다시', () => {
    const clip = toLibraryClip(entry('rendered', snapshot(failedUpload('QUOTA_EXCEEDED'))), '9');
    expect(uploadFailureText(clip)).toBe(
      '오늘 유튜브 업로드 한도를 다 써서 올리지 못했어요. 내일 다시 올려 주세요.',
    );
  });

  it('유튜브 거절은 서버가 준 사유를 붙인다', () => {
    const clip = toLibraryClip(
      entry('rendered', snapshot(failedUpload('YOUTUBE_REJECTED', 'invalidTitle'))),
      '9',
    );
    expect(uploadFailureText(clip)).toBe(
      '유튜브가 영상을 받지 않았어요(invalidTitle). 제목이나 영상을 확인한 뒤 다시 올려 주세요.',
    );
  });

  it('모르는 코드도 코드를 보이고 다시 올리게 한다', () => {
    const clip = toLibraryClip(entry('rendered', snapshot(failedUpload('UNKNOWN'))), '9');
    expect(uploadFailureText(clip)).toBe('유튜브에 올리지 못했어요(UNKNOWN). 다시 올려 주세요.');
  });

  it('다시 올리는 중이면 지난 실패를 말하지 않는다', () => {
    const clip = toLibraryClip(entry('uploading', snapshot(failedUpload('QUOTA_EXCEEDED'))), '9');
    expect(uploadFailureText(clip)).toBeNull();
  });

  it('목업 줄(entry 없음)은 null', () => {
    const clip = { ...toLibraryClip(entry('rendered'), '9'), entry: undefined };
    expect(uploadFailureText(clip)).toBeNull();
  });
});

describe('detailViewForClip — 서버 업로드 상태가 패널 주 동작을 바꾼다', () => {
  it('완성된 서버 편집본은 시점과 무관하게 「업로드」다 — 승인 단계가 없어 바로 올라간다', () => {
    const clip = toLibraryClip(entry('rendered'), '9');
    for (const role of ['streamer', 'editor'] as const) {
      expect(detailViewForClip(clip, 'ready', role).primary).toEqual({
        kind: 'action',
        label: '업로드',
        action: 'upload',
      });
    }
  });

  it('올리는 중에는 누를 수 없는 「유튜브에 올리는 중」이고 제목이 잠긴다', () => {
    const view = detailViewForClip(toLibraryClip(entry('uploading'), '9'), 'ready', 'streamer');
    expect(view.primary).toEqual({ kind: 'busy', label: '유튜브에 올리는 중' });
    expect(view.titleLocked).toBe(true);
    expect(view.note).toBeNull();
  });

  it('올린 편집본은 제목이 잠긴다 — 고쳐도 유튜브 제목은 안 바뀐다', () => {
    const view = detailViewForClip(toLibraryClip(entry('uploaded'), '9'), 'published', 'streamer');
    expect(view.titleLocked).toBe(true);
    expect(view.primary).toEqual({ kind: 'external', label: '유튜브 보기' });
  });

  it('확인 필요는 다시 올리기를 막고 채널을 보라고 안내한다', () => {
    const view = detailViewForClip(toLibraryClip(entry('checking'), '9'), 'ready', 'streamer');
    expect(view.primary).toEqual({ kind: 'busy', label: '업로드 확인 필요' });
    expect(view.titleLocked).toBe(true);
    expect(view.note).toBe('checking');
    expect(noteText('checking', 'streamer')).toBe(
      '유튜브에 올라갔는지 확인하지 못했어요. 두 번 올라가지 않게 다시 올리기를 막아 두었어요. 스트리머 채널의 유튜브 스튜디오에서 비공개 영상을 확인해 주세요.',
    );
  });

  it('목업 줄은 시안 규칙 그대로다 — 편집자는 「업로드 요청」', () => {
    const clip = { ...toLibraryClip(entry('rendered'), '9'), entry: undefined };
    expect(detailViewForClip(clip, 'ready', 'editor').primary).toEqual({
      kind: 'action',
      label: '업로드 요청',
      action: 'upload',
    });
  });
});

describe('toLibraryClip — 올린 편집본은 유튜브 제목을 보인다', () => {
  it('업로드 제목이 있으면 그것이 제목이다', () => {
    const clip = toLibraryClip(entry('rendered', snapshot(failedUpload('UNKNOWN'))), '9');
    expect(clip.title).toBe('보스 막타');
  });

  it('업로드가 없으면 「편집본 #번호」', () => {
    expect(toLibraryClip(entry('rendered'), '9').title).toBe('편집본 #12');
  });
});
