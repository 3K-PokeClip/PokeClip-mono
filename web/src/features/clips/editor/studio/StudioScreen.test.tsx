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
    expect(screen.getByRole('link', { name: '보관함으로' })).toHaveAttribute(
      'href',
      '/clips/library',
    );
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

    // 시안 갱신분의 기본은 레이아웃 도구다
    expect(screen.getByRole('region', { name: '레이아웃' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: '자막' }));
    expect(screen.getByRole('region', { name: '자막' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '이미지' }));

    expect(screen.queryByRole('region', { name: '자막' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: '이미지' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '타임라인' })).toBeInTheDocument();
  });

  it('자막을 만들기 전에는 제목 추천이 잠겨 있고, 만들면 열린다', async () => {
    const user = userEvent.setup();
    renderStudio({ initialSubtitleStatus: 'idle' });
    await user.click(screen.getByRole('tab', { name: '자막' }));

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

    const lane = document.querySelector<HTMLElement>('[data-timeline-lanes]');
    // 기본(null)일 땐 인라인 높이가 없다 — 레인 높이는 트랙 수가 정한다
    expect(lane).not.toBeNull();
    expect(lane?.style.height).toBe('');

    await user.click(screen.getByRole('button', { name: '타임라인 높이 조절' }));
    await user.keyboard('{ArrowUp}');

    // 한 걸음 올리면 레인 높이가 px로 굳는다
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

    expect(screen.getByText(/자막 번인\+CC · 1× 배속/)).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: '2×' }));
    expect(screen.getByText(/자막 번인\+CC · 2× 배속/)).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /세로/ }));
    expect(screen.getByRole('radio', { name: /세로/ })).toBeChecked();
  });

  it('패널 위치 버튼이 레일과 패널을 좌우로 옮긴다 — 미리보기·타임라인은 그대로', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('tab', { name: '자막' }));
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
    expect(tabs).toEqual(['레이아웃', '구간', '자막', '오디오', 'BGM·효과', '이미지']);
  });

  it('버튼을 누른 뒤에도 ⌘Z가 먹는다 — 포커스가 버튼에 남는 것이 정상 흐름이다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('radio', { name: /세로/ }));
    expect(screen.getByRole('radio', { name: /세로/ })).toBeChecked();

    // 클릭한 버튼에 포커스가 남은 채로 되돌린다
    await user.keyboard('{Meta>}z{/Meta}');
    expect(screen.getByRole('radio', { name: /분할/ })).toBeChecked();
  });

  it('버튼 위에서 Space는 여전히 버튼을 누른다 — 재생을 가로채지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const oneToOne = screen.getByRole('radio', { name: /중앙/ });
    oneToOne.focus();
    await user.keyboard(' ');

    expect(oneToOne).toBeChecked();
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('레이아웃 묶음을 화살표로 옮길 수 있다', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 시안 순서: 세로 · 분할 · 중앙 · 크롭 · 가로 — 세로 목록이라 위아래 화살표다
    screen.getByRole('radio', { name: /분할/ }).focus();
    await user.keyboard('{ArrowUp}');
    expect(screen.getByRole('radio', { name: /세로/ })).toBeChecked();

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(screen.getByRole('radio', { name: /중앙/ })).toBeChecked();
  });

  it('가로는 목록에 보이되 고를 수 없다 — 누르거나 화살표로 닿아도 선택이 안 바뀐다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const horiz = screen.getByRole('radio', { name: /가로/ });
    expect(horiz).toBeDisabled();

    await user.click(horiz);
    expect(screen.getByRole('radio', { name: /분할/ })).toBeChecked();

    // 크롭에서 아래로 내려가도 가로에 서지 않는다
    await user.click(screen.getByRole('radio', { name: /크롭/ }));
    await user.keyboard('{ArrowDown}');
    expect(horiz).not.toBeChecked();
    expect(horiz).not.toHaveFocus();
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

// --- 크롭·레이아웃 -------------------------------------------------------------
// 원본 판은 아직 자리 표시자다. 사각형은 목업 해상도(1920×1080) 기준으로 잡히므로
// 영상 없이도 좌표·경계·실행취소를 그대로 확인할 수 있다.

describe('StudioScreen — 크롭 영역 (E5)', () => {
  it('소스 위에 잡을 영역과 모서리 네 개가 선다', () => {
    renderStudio();

    expect(screen.getByRole('region', { name: '원본' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '결과' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /상단 영역/ })).toBeInTheDocument();
    for (const corner of ['왼쪽 위', '오른쪽 위', '왼쪽 아래', '오른쪽 아래']) {
      expect(screen.getByRole('button', { name: `상단 ${corner} 모서리` })).toBeInTheDocument();
    }
  });

  it('상하분할은 한 소스에 사각형 두 개를 얹는다', () => {
    renderStudio();

    expect(screen.getByRole('button', { name: /상단 영역/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /하단 영역/ })).toBeInTheDocument();
  });

  it('사각형을 방향키로 옮긴다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const body = screen.getByRole('button', { name: /상단 영역/ });

    const before = body.parentElement?.getAttribute('style');
    body.focus();
    await user.keyboard('{ArrowRight}');

    expect(body.parentElement?.getAttribute('style')).not.toBe(before);
  });

  it('모서리 방향키가 범위를 넓히고 좁힌다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const handle = screen.getByRole('button', { name: '상단 오른쪽 아래 모서리' });
    const rect = () => handle.parentElement?.style.width ?? '';

    handle.focus();
    const before = rect();
    await user.keyboard('{ArrowRight}');
    const grown = rect();
    expect(Number.parseFloat(grown)).toBeGreaterThan(Number.parseFloat(before));

    await user.keyboard('{ArrowLeft}');
    expect(Number.parseFloat(rect())).toBeLessThan(Number.parseFloat(grown));
  });

  it('옮긴 뒤 실행취소로 되돌린다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const body = screen.getByRole('button', { name: /상단 영역/ });
    const rect = () => body.parentElement?.getAttribute('style');

    const before = rect();
    body.focus();
    await user.keyboard('{ArrowRight}');
    expect(rect()).not.toBe(before);

    await user.click(screen.getByRole('button', { name: '작업 이전으로' }));
    expect(rect()).toBe(before);
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderStudio();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('StudioScreen — 중앙 모드 여백 채우기', () => {
  it('중앙을 고를 때만 보이고, 블러면 강도 슬라이더가, 단색이면 스와치가 열린다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(screen.queryByRole('radiogroup', { name: '여백 채우기' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    expect(screen.getByRole('radio', { name: '블러' })).toBeChecked();
    // 시안 기본 60
    expect(screen.getByRole('slider', { name: '블러 강도' })).toHaveAttribute(
      'aria-valuenow',
      '60',
    );
    expect(screen.queryByRole('radiogroup', { name: '배경 색' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: '단색' }));
    expect(screen.queryByRole('slider', { name: '블러 강도' })).not.toBeInTheDocument();
    const swatches = screen.getByRole('radiogroup', { name: '배경 색' });
    expect(within(swatches).getByRole('radio', { name: '검정' })).toBeChecked();

    await user.click(within(swatches).getByRole('radio', { name: '흰색' }));
    expect(within(swatches).getByRole('radio', { name: '흰색' })).toBeChecked();
    expect(screen.getByLabelText('배경 색 직접 고르기')).toHaveValue('#ffffff');
  });

  it('블러 강도 슬라이더가 결과의 블러 변수를 움직인다', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    const pane = container.querySelector('[data-placement="contain"]') as HTMLElement;
    expect(pane.style.getPropertyValue('--pc-blur')).toBe('0.6');

    const slider = screen.getByRole('slider', { name: '블러 강도' });
    slider.focus();
    await user.keyboard('{ArrowLeft}');

    expect(slider).toHaveAttribute('aria-valuenow', '59');
    expect(pane.style.getPropertyValue('--pc-blur')).toBe('0.59');
  });

  it('접근성 위반이 없다 — 단색 스와치까지', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    await user.click(screen.getByRole('radio', { name: '단색' }));
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('StudioScreen — 크롭 모드 작은 화면', () => {
  it('작은 화면 자리·비율은 패널 설정 없이 원본의 프레임과 자리, 두 사각형의 꼭짓점으로 잡는다', async () => {
    const user = userEvent.setup();
    renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));

    // 따로 고르는 비율·크기 설정은 없다 — 사각형이 곧 설정이다
    expect(screen.queryByRole('radiogroup', { name: '작은 화면 비율' })).not.toBeInTheDocument();
    expect(screen.queryByRole('slider', { name: '작은 화면 크기' })).not.toBeInTheDocument();

    for (const corner of ['왼쪽 위', '오른쪽 위', '왼쪽 아래', '오른쪽 아래']) {
      expect(
        screen.getByRole('button', { name: `작은 화면 ${corner} 모서리` }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: `작은 화면 자리 ${corner} 모서리` }),
      ).toBeInTheDocument();
    }
  });

  it('테두리는 켜진 채 시작하고, 끄면 굵기·색 고르기가 접히며 결과의 라인이 사라진다', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));

    const toggle = screen.getByRole('switch', { name: '테두리 표시' });
    expect(toggle).toBeChecked();
    const widths = screen.getByRole('radiogroup', { name: '테두리 굵기' });
    expect(within(widths).getByRole('radio', { name: '1px' })).toBeChecked();
    const colors = screen.getByRole('radiogroup', { name: '테두리 색' });
    expect(within(colors).getByRole('radio', { name: '흰색' })).toBeChecked();

    const overlay = () => container.querySelector('[data-placement="overlay"]') as HTMLElement;
    expect(overlay().style.borderRadius).not.toBe('');

    await user.click(within(widths).getByRole('radio', { name: '3px' }));
    expect(overlay().style.border).toContain('3');

    await user.click(toggle);
    expect(toggle).not.toBeChecked();
    expect(screen.queryByRole('radiogroup', { name: '테두리 굵기' })).not.toBeInTheDocument();
    expect(overlay().style.borderRadius).toBe('');
  });

  it('접근성 위반이 없다 — 테두리 설정까지', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));
    expect(await axe(container)).toHaveNoViolations();
  });
});
