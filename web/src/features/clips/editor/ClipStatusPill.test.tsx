import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ClipStatusPill } from './ClipStatusPill';
import type { ClipStatusView } from './studio/clipProgress';

// 「영상 만들기」 옆 영상 상태 알약과 작은 창(POK-492)의 접근성. 판정·문구는 clipProgress.test, 화면 흐름은 StudioScreen 테스트가 맡는다.

const failed: ClipStatusView = {
  tone: 'danger',
  title: '업로드 실패',
  detail: '오늘 유튜브 업로드 한도를 다 써서 올리지 못했어요.',
  steps: [
    { label: '대기', state: 'done', note: '20:00' },
    { label: '영상 만들기', state: 'done', note: '20:02' },
    { label: '유튜브 올리기', state: 'danger', note: '20:03 실패' },
  ],
  progress: null,
  actions: ['retryUpload', 'library'],
  youtubeUrl: null,
  retryNote: null,
};

const uploading: ClipStatusView = {
  ...failed,
  tone: 'progress',
  title: '유튜브에 올리는 중',
  detail: '스트리머 채널에 올리고 있어요.',
  steps: [
    { label: '대기', state: 'done', note: '20:00' },
    { label: '영상 만들기', state: 'done', note: '20:02' },
    { label: '유튜브 올리기', state: 'now', note: '20:10부터' },
  ],
  progress: { value: null },
  actions: ['library'],
};

const props = {
  versionLabel: '편집본 v1',
  retrying: false,
  onRetryUpload: vi.fn(),
  onRemake: vi.fn(),
  remakeLocked: false,
};

describe('ClipStatusPill', () => {
  it('「다시 올리기」가 성공해 누른 단추가 사라져도 포커스는 작은 창에 남는다(PR #454 1차 리뷰)', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<ClipStatusPill view={failed} {...props} />);
    await user.click(screen.getByRole('button', { name: '영상 상태: 업로드 실패. 자세히 보기' }));
    screen.getByRole('button', { name: '다시 올리기' }).focus();

    // 새 업로드가 대기열에 섰다: 할 일이 「보관함에서 보기」 하나로 바뀌며 「다시 올리기」가 사라진다
    rerender(<ClipStatusPill view={uploading} {...props} />);

    expect(screen.queryByRole('button', { name: '다시 올리기' })).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: '영상 상태' })).toHaveFocus();
  });

  it('알약 안 진행 막대는 보조기기에 숨긴다: 창을 열어도 「영상 진행」 막대는 하나다(PR #454 1차 리뷰)', async () => {
    const user = userEvent.setup();
    render(<ClipStatusPill view={uploading} {...props} />);
    // 퍼센트·상태는 알약 이름이 말한다
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: '영상 상태: 유튜브에 올리는 중. 자세히 보기' }),
    );
    expect(screen.getAllByRole('progressbar', { name: '영상 진행' })).toHaveLength(1);
  });

  it('「다시 올리기」 위에 공개 범위 경고가 붙고, 버튼의 설명으로도 읽힌다(PR #454 2차 리뷰)', async () => {
    const user = userEvent.setup();
    const note =
      '다시 올리면 「공개」로 올라가 누구나 볼 수 있어요. 구독자에게 새 영상 알림이 갈 수 있어요.';
    render(<ClipStatusPill view={{ ...failed, retryNote: note }} {...props} />);
    await user.click(screen.getByRole('button', { name: '영상 상태: 업로드 실패. 자세히 보기' }));
    expect(screen.getByText(note)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 올리기' })).toHaveAccessibleDescription(note);
  });

  it('편집본 저장 중(헤더 「영상 만들기」가 꺼진 동안)에는 「다시 만들기」도 꺼진다: 눌러도 아무 일 없던 버튼(PR #454 2차 리뷰)', async () => {
    const user = userEvent.setup();
    const onRemake = vi.fn();
    const renderFailed: ClipStatusView = {
      ...failed,
      title: '영상 만들기 실패',
      actions: ['remake', 'library'],
    };
    const { rerender } = render(
      <ClipStatusPill view={renderFailed} {...props} onRemake={onRemake} remakeLocked />,
    );
    await user.click(
      screen.getByRole('button', { name: '영상 상태: 영상 만들기 실패. 자세히 보기' }),
    );
    expect(screen.getByRole('button', { name: '다시 만들기' })).toBeDisabled();

    // 저장이 끝나면 다시 눌린다
    rerender(<ClipStatusPill view={renderFailed} {...props} onRemake={onRemake} />);
    await user.click(screen.getByRole('button', { name: '다시 만들기' }));
    expect(onRemake).toHaveBeenCalledTimes(1);
  });
});
