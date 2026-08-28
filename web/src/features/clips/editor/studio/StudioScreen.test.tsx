import { act } from 'react';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { StudioScreen } from './StudioScreen';
import type { ClipEditorOptions } from '../useClipEditorMockState';

function renderStudio(options?: ClipEditorOptions) {
  return renderWithProviders(<StudioScreen {...options} />);
}

describe('StudioScreen', () => {
  it('헤더에 제목·자동 저장·저장 버튼을 렌더한다', () => {
    renderStudio();

    expect(screen.getByRole('heading', { name: '승급전 마지막 한타 역전' })).toBeInTheDocument();
    expect(screen.getByText('라이브 카드 1:24:03 · 방금 자동 저장됨')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '보관함으로' })).toHaveAttribute('href', '/clips');
    expect(screen.getByRole('button', { name: '템플릿 저장' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '업로드' })).toBeEnabled();
    // 아직 편집한 적이 없으니 되돌릴 곳도 없다
    expect(screen.getByRole('button', { name: '작업 이전으로' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '작업 앞으로' })).toBeDisabled();
  });

  it('타임라인에 트랙 6종을 훅이 준 이름·볼륨으로 그린다', () => {
    renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });

    for (const label of ['영상', '마이크', '게임 사운드', 'BGM', '효과음', '이미지']) {
      expect(within(timeline).getByText(label)).toBeInTheDocument();
    }
    expect(within(timeline).getByText('80%')).toBeInTheDocument();
    expect(
      within(timeline).getByRole('button', { name: /Neon Drive\.mp3/ }),
    ).toBeInTheDocument();
    expect(within(timeline).getByRole('button', { name: '띠용' })).toBeInTheDocument();
    expect(within(timeline).getByRole('button', { name: /로고\.png/ })).toBeInTheDocument();
  });

  it('도구 레일을 누르면 패널만 바뀌고 타임라인은 남는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 시안 기본은 자막 도구
    expect(screen.getByRole('region', { name: '자막' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '이미지' }));

    expect(screen.queryByRole('region', { name: '자막' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: '이미지' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '타임라인' })).toBeInTheDocument();
  });

  it('자막을 만들기 전에는 제목 추천이 잠겨 있고, 만들면 열린다', async () => {
    const user = userEvent.setup();
    renderStudio({ initialSubtitleStatus: 'idle' });

    const locked = screen.getByRole('region', { name: 'AI 제목 추천' });
    expect(within(locked).getByText('잠김')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '1대3 클러치 미쳤다 #발로란트' })).toBeNull();

    await user.click(screen.getByRole('button', { name: /AI 자막 생성/ }));

    // 목업 생성이 끝나면 자막 목록과 제목 후보가 함께 열린다
    expect(
      // 목업 생성 지연(1.5초)이 기본 대기시간보다 길다
      await screen.findByRole(
        'button',
        { name: '1대3 클러치 미쳤다 #발로란트' },
        { timeout: 3000 },
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('아 이게 된다고?? 미쳤다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '재생성' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '번인+CC' })).toBeChecked();
  });

  it('구간을 5초 미만으로 줄이려 하면 값이 그대로고 안내가 뜬다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const startHandle = screen.getByRole('slider', { name: '구간 시작점' });
    expect(startHandle).toHaveAttribute('aria-valuetext', expect.stringContaining('12.4초'));

    startHandle.focus();
    // 1초씩 8번 당기면 12.4초 → 4.4초라 마지막 한 번이 거부된다
    for (let i = 0; i < 8; i += 1) await user.keyboard('{ArrowRight}');

    expect(screen.getByRole('status')).toHaveTextContent('구간은 최소 5초부터예요');
    expect(startHandle).toHaveAttribute('aria-valuetext', expect.stringContaining('5.4초'));
  });

  it('단축키로 재생을 토글하고 되돌린다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '일시정지' })).toBeInTheDocument();
    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();

    // O로 끝점을 플레이헤드(1:22:14)까지 당기면 되돌릴 거리가 생긴다
    await user.keyboard('{o}');
    const undo = screen.getByRole('button', { name: '작업 이전으로' });
    expect(undo).toBeEnabled();

    await user.keyboard('{Meta>}z{/Meta}');
    expect(undo).toBeDisabled();
  });

  it('타임라인을 접으면 선택 구간 요약만 남는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(screen.getByText('Space')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '타임라인 접기' }));

    expect(screen.getByText(/선택 구간/)).toBeInTheDocument();
    expect(screen.queryByText('Space')).not.toBeInTheDocument();
  });

  it('레이아웃과 배속을 고르면 미리보기 안내가 따라온다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(screen.getByText('미리보기 · 자막 번인+CC · 1× 배속')).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: '2×' }));
    expect(screen.getByText('미리보기 · 자막 번인+CC · 2× 배속')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: '9:16' }));
    expect(screen.getByRole('radio', { name: '9:16' })).toBeChecked();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderStudio();
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
