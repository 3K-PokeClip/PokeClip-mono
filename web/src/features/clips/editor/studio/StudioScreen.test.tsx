import { act } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
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
    expect(within(timeline).getByRole('button', { name: /Neon Drive\.mp3/ })).toBeInTheDocument();
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

  it('구간을 5초 미만으로 줄이려 하면 핸들이 거기서 멈춘다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const startHandle = screen.getByRole('slider', { name: '구간 시작점' });
    expect(startHandle).toHaveAttribute('aria-valuetext', expect.stringContaining('12.4초'));

    startHandle.focus();
    // 1초씩 8번 당기면 12.4초 → 4.4초라 마지막 한 번이 막힌다
    for (let i = 0; i < 8; i += 1) await user.keyboard('{ArrowRight}');

    expect(startHandle).toHaveAttribute('aria-valuetext', expect.stringContaining('5.4초'));
    // 규칙은 범례가 미리 말해 둔다
    expect(screen.getByText('5초 미만, 3분 초과로는 핸들이 움직이지 않아요')).toBeInTheDocument();
  });

  it('되감기·감기 버튼이 아이콘 안의 초로 갈린다 — 방향만으로는 구분되지 않는다', () => {
    renderStudio();

    // 시안 1d는 원형 화살표 안에 5·1을 적어 네 버튼을 가른다
    expect(screen.getByRole('button', { name: '5초 뒤로' })).toHaveTextContent('5');
    expect(screen.getByRole('button', { name: '1초 뒤로' })).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: '1초 앞으로' })).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: '5초 앞으로' })).toHaveTextContent('5');
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

  // jsdom엔 레이아웃이 없어 겹침 자체(POK-237)는 여기서 못 잰다 — headroom이 Infinity로
  // 떨어지는 경로다. 여기서 지키는 건 손잡이가 높이 상태에 배선돼 있다는 것뿐이고,
  // 실제 상한은 브라우저 실측으로 확인한다.
  it('높이 조절 손잡이의 화살표 키가 레인 높이를 바꾼다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const timeline = screen.getByRole('region', { name: '타임라인' });
    // 기본(null)일 땐 px로 박힌 인라인 높이가 없다 — 트랙 행 높이는 디자인 단위(calc)다
    expect(timeline.querySelector('[style*="px"]')).toBeNull();

    await user.click(screen.getByRole('button', { name: '타임라인 높이 조절' }));
    await user.keyboard('{ArrowUp}');

    // 한 걸음 올리면 레인 높이가 px로 굳는다
    const lane = timeline.querySelector<HTMLElement>('[style*="px"]');
    expect(lane?.style.height).toMatch(/^\d+px$/);
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

  it('패널 위치 버튼이 레일과 패널을 좌우로 옮긴다 — 미리보기·타임라인은 그대로', async () => {
    const user = userEvent.setup();
    renderStudio();

    const body = screen.getByRole('main');
    expect(body).toHaveAttribute('data-panel-side', 'left');

    const toggle = screen.getByRole('button', { name: '패널 위치 · 오른쪽으로 옮기기' });
    await user.click(toggle);

    expect(body).toHaveAttribute('data-panel-side', 'right');
    // 다시 누르면 돌아갈 수 있게 안내가 뒤집힌다
    expect(screen.getByRole('button', { name: '패널 위치 · 왼쪽으로 옮기기' })).toBeInTheDocument();
    // 옮겨도 도구·타임라인은 그대로 선다
    expect(screen.getByRole('region', { name: '자막' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '타임라인' })).toBeInTheDocument();
  });

  it('패널 위치는 도구 탭 묶음에 끼지 않는다', () => {
    renderStudio();

    const tabs = screen.getAllByRole('tab').map((t) => t.textContent);
    expect(tabs).toEqual(['구간', '자막', '오디오', 'BGM·효과', '이미지']);
  });

  it('버튼을 누른 뒤에도 ⌘Z가 먹는다 — 포커스가 버튼에 남는 것이 정상 흐름이다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('radio', { name: '9:16' }));
    expect(screen.getByRole('radio', { name: '9:16' })).toBeChecked();

    // 클릭한 버튼에 포커스가 남은 채로 되돌린다
    await user.keyboard('{Meta>}z{/Meta}');
    expect(screen.getByRole('radio', { name: '상하분할' })).toBeChecked();
  });

  it('버튼 위에서 Space는 여전히 버튼을 누른다 — 재생을 가로채지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const oneToOne = screen.getByRole('radio', { name: '1:1' });
    oneToOne.focus();
    await user.keyboard(' ');

    expect(oneToOne).toBeChecked();
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('레이아웃 묶음을 화살표로 옮길 수 있다', async () => {
    const user = userEvent.setup();
    renderStudio();

    screen.getByRole('radio', { name: '상하분할' }).focus();
    await user.keyboard('{ArrowLeft}');

    expect(screen.getByRole('radio', { name: '1:1' })).toBeChecked();
  });

  it('구간 핸들이 각자 자기 경계 위치를 읽어 준다', () => {
    renderStudio();

    const start = screen.getByRole('slider', { name: '구간 시작점' });
    const end = screen.getByRole('slider', { name: '구간 끝점' });
    // 둘이 같은 값을 말하면 스크린리더가 두 핸들을 구분하지 못한다
    expect(start.getAttribute('aria-valuenow')).not.toBe(end.getAttribute('aria-valuenow'));
    expect(Number(end.getAttribute('aria-valuenow'))).toBeGreaterThan(
      Number(start.getAttribute('aria-valuenow')),
    );
  });

  it('버튼에 포커스가 있어도 I·O는 통과한다 — Space·화살표는 계속 양보한다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const play = screen.getByRole('button', { name: '재생' });
    play.focus();
    await user.keyboard('{o}');

    // O가 끝점을 플레이헤드로 당겼으니 되돌릴 거리가 생긴다
    expect(screen.getByRole('button', { name: '작업 이전으로' })).toBeEnabled();
    // Space는 여전히 버튼 것이라 재생으로 새지 않는다
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('핸들 ARIA 범위가 3분 상한까지 반영한다', () => {
    renderStudio();

    const start = screen.getByRole('slider', { name: '구간 시작점' });
    const end = screen.getByRole('slider', { name: '구간 끝점' });
    // 시작 핸들의 최소는 0이 아니라 「끝 − 3분」이다
    expect(Number(start.getAttribute('aria-valuemin'))).toBeGreaterThan(0);
    expect(
      Number(end.getAttribute('aria-valuenow')) - Number(start.getAttribute('aria-valuemin')),
    ).toBeLessThanOrEqual(180);
  });

  it('볼륨 슬라이더가 포인터로 살아 있다 — 제스처 핸들러가 DS 핸들러를 덮지 않는다', () => {
    renderStudio();

    const [slider] = screen.getAllByRole('slider', { name: /볼륨/ });
    if (slider === undefined) throw new Error('볼륨 슬라이더가 없다');
    // jsdom에는 포인터 캡처가 없어 Slider 내부에서 던진다 — 있는 척만 해 준다
    const root = slider.parentElement?.parentElement as HTMLElement;
    root.setPointerCapture = () => {};
    root.hasPointerCapture = () => true;

    fireEvent.pointerDown(root, { clientX: 10, pointerId: 1 });

    // Slider의 onPointerDown이 살아 있으면 썸에 포커스가 간다.
    // 우리 핸들러가 덮어썼다면 이 줄이 깨진다 (실제로 그렇게 깨진 적이 있다).
    expect(document.activeElement).toBe(slider);
  });

  it('구간이 창보다 길어져도 핸들 둘이 남는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const end = screen.getByRole('slider', { name: '구간 끝점' });
    end.focus();
    // 5초씩 20번이면 구간이 100초를 넘겨 100% 줌 창(75초)보다 길어진다
    for (let i = 0; i < 20; i += 1) await user.keyboard('{Shift>}{ArrowRight}{/Shift}');

    expect(screen.getByRole('slider', { name: '구간 시작점' })).toBeInTheDocument();
    expect(screen.getByRole('slider', { name: '구간 끝점' })).toBeInTheDocument();
  });

  it('스위치를 누른 뒤에도 ⌘Z가 먹는다 — DS Switch는 checkbox라 글자 입력이 아니다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('tab', { name: '오디오' }));
    const mic = screen.getByRole('switch', { name: '마이크 사용' });
    await user.click(mic);
    expect(mic).not.toBeChecked();

    // 포커스가 스위치(input)에 남은 채로 되돌린다
    await user.keyboard('{Meta>}z{/Meta}');
    expect(screen.getByRole('switch', { name: '마이크 사용' })).toBeChecked();
  });

  it('키마다 주인이 다르다 — 버튼 위 화살표는 시킹, 슬라이더 위 Space는 재생', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 버튼은 화살표를 안 쓴다 → 시킹이 통과해야 한다
    const play = screen.getByRole('button', { name: '재생' });
    play.focus();
    // 트랜스포트의 현재 시각만 본다 (타임코드 박스·눈금에도 1:22가 있다)
    const clock = () =>
      screen.getByRole('button', { name: '5초 뒤로' }).parentElement?.textContent ?? '';
    const before = clock();
    await user.keyboard('{ArrowRight}');
    expect(clock()).not.toBe(before);

    // 슬라이더는 Space를 안 쓴다 → 재생이 통과해야 한다
    screen.getByRole('slider', { name: '구간 시작점' }).focus();
    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '일시정지' })).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderStudio();
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
