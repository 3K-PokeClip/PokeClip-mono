import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePhotoDialog } from '@/features/settings/account/ProfilePhotoDialog';
import { useProfilePhotoState } from '@/features/settings/account/useProfilePhotoState';
import { renderWithProviders } from '@/test/testProviders';

// 모달의 3단계가 시안대로 갈리는지 본다. 시간에 걸린 복귀 규칙은 상태 기계 쪽
// (useProfilePhotoState.test.ts)이 시계를 붙잡고 따로 검사한다.

const DATA_URL = 'data:image/png;base64,AAA';

class SyncFileReader {
  result: string = DATA_URL;
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  readAsDataURL() {
    this.onload?.();
  }
}

function fileOf(name: string, type: string, size: number): File {
  const file = new File(['x'], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

const onApply = vi.fn();

function Harness() {
  const photo = useProfilePhotoState(onApply);
  return (
    <>
      <button type="button" onClick={photo.open}>
        사진 수정
      </button>
      <ProfilePhotoDialog photo={photo} glyph="너" />
    </>
  );
}

const dialog = () => within(screen.getByRole('dialog'));

/** jsdom은 이미지를 디코드하지 않아 onLoad가 뜨지 않는다 — 「적용」을 열려면 직접 발화시킨다. */
function markImageDecoded() {
  fireEvent.load(document.querySelector('[role="dialog"] img') as HTMLImageElement);
}
const dropzone = () => screen.getByRole('button', { name: /사진을 끌어다 놓거나 클릭해 선택/ });

function drop(target: HTMLElement, file: File) {
  fireEvent.drop(target, { dataTransfer: { files: [file], types: ['Files'] } });
}

beforeEach(() => {
  onApply.mockReset();
  vi.stubGlobal('FileReader', SyncFileReader);
  // jsdom에는 캔버스가 없다 — 기본 아바타를 만드는 자리만 통과시킨다
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    fillRect: vi.fn(),
    fillText: vi.fn(),
    translate: vi.fn(),
    rotate: vi.fn(),
    scale: vi.fn(),
    drawImage: vi.fn(),
  } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(
    'data:image/png;base64,PRESET',
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function open() {
  const user = userEvent.setup();
  const view = renderWithProviders(<Harness />);
  await user.click(screen.getByRole('button', { name: '사진 수정' }));
  return { user, ...view };
}

describe('ProfilePhotoDialog', () => {
  it('① 선택 — 드롭존 안내와 기본 아바타 6종이 함께 뜬다', async () => {
    await open();

    expect(dialog().getByText('1 / 3 · 사진 선택')).toBeInTheDocument();
    expect(
      dialog().getByText('JPG · PNG · WebP · 5MB 이하 · 정사각 512px 권장'),
    ).toBeInTheDocument();
    expect(dialog().getByText('사진 대신 기본 아바타')).toBeInTheDocument();
    expect(dialog().getAllByRole('button', { name: /^기본 아바타 \d$/ })).toHaveLength(6);
  });

  it('② 5MB를 넘기면 모달을 닫지 않고 그 자리에서 알린다', async () => {
    await open();

    drop(dropzone(), fileOf('face-cam-0812.png', 'image/png', Math.round(8.2 * 1024 * 1024)));

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(dialog().getByText('8.2MB 파일은 올릴 수 없어요')).toBeInTheDocument();
    expect(
      dialog().getByText('5MB 이하로 줄여 다시 끌어다 놓거나 클릭해 선택해 주세요'),
    ).toBeInTheDocument();
    // 「처음부터 다시」로 읽히지 않게 단계 표시를 유지한다
    expect(dialog().getByText('1 / 3 · 사진 선택')).toBeInTheDocument();
    expect(dialog().getByText('잠시 후 선택 화면으로 돌아갑니다')).toBeInTheDocument();
  });

  it('파일을 끌어오면 받을 수 있다고 표시한다 — 드래그 중에는 :hover가 서지 않는다', async () => {
    await open();
    const zone = dropzone();

    fireEvent.dragEnter(zone, { dataTransfer: { types: ['Files'] } });
    expect(zone).toHaveAttribute('data-dragover');

    fireEvent.dragLeave(zone);
    expect(zone).not.toHaveAttribute('data-dragover');
  });

  it('드롭하면 강조를 걷는다 — 업로드 단계까지 강조가 따라가지 않는다', async () => {
    await open();
    const zone = dropzone();

    fireEvent.dragEnter(zone, { dataTransfer: { types: ['Files'] } });
    drop(zone, fileOf('face-cam-0812.png', 'image/png', Math.round(2.4 * 1024 * 1024)));

    expect(dialog().getByText('2 / 3 · 업로드')).toBeInTheDocument();
    expect(document.querySelector('[data-dragover]')).toBeNull();
  });

  it('③ 정상 파일은 업로드 단계로 넘어가 파일과 진행률을 보여 준다', async () => {
    await open();

    drop(dropzone(), fileOf('face-cam-0812.png', 'image/png', Math.round(2.4 * 1024 * 1024)));

    expect(dialog().getByText('2 / 3 · 업로드')).toBeInTheDocument();
    expect(dialog().getByText('사진을 올리는 중이에요')).toBeInTheDocument();
    expect(dialog().getByText('face-cam-0812.png · 2.4MB')).toBeInTheDocument();
    expect(dialog().getByRole('progressbar', { name: '업로드 진행률' })).toBeInTheDocument();
    expect(dialog().getByRole('button', { name: '취소' })).toBeInTheDocument();
  });

  it('④ 기본 아바타는 업로드를 건너뛰고 곧장 크롭에 든다', async () => {
    const { user } = await open();

    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));

    expect(dialog().getByText('3 / 3 · 크롭')).toBeInTheDocument();
    expect(dialog().getByText('드래그해서 위치 조정')).toBeInTheDocument();
    expect(dialog().getByRole('slider', { name: '확대' })).toBeInTheDocument();
    expect(dialog().getByRole('button', { name: '왼쪽으로 90도 회전' })).toBeInTheDocument();
  });

  it('⑤ 적용은 결과를 넘기고 모달을 닫은 뒤 토스트로 알린다', async () => {
    const { user } = await open();
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));
    markImageDecoded();

    await user.click(dialog().getByRole('button', { name: '적용' }));

    expect(onApply).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByText('프로필 사진을 변경했습니다')).toBeInTheDocument();
    expect(screen.getByText('헤더·사이드바에 바로 반영')).toBeInTheDocument();
    // 되돌릴 수 없는 일이 아니라 다시 손보는 일이다 — 되돌리기가 아니라 편집이 붙는다
    expect(screen.getByRole('button', { name: '편집' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '되돌리기' })).not.toBeInTheDocument();
  });

  it('토스트의 편집은 크롭으로 되돌아간다', async () => {
    const { user } = await open();
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));
    markImageDecoded();
    await user.click(dialog().getByRole('button', { name: '적용' }));

    await user.click(await screen.findByRole('button', { name: '편집' }));

    expect(dialog().getByText('3 / 3 · 크롭')).toBeInTheDocument();
  });

  it('편집으로 돌아오면 자르던 자리를 잇고, 새로 고르면 처음부터 시작한다', async () => {
    const { user } = await open();
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));

    // 확대를 바꿔 「자르던 자리」를 만든다
    markImageDecoded();
    const slider = dialog().getByRole('slider', { name: '확대' });
    slider.focus();
    await user.keyboard('{ArrowRight}{ArrowRight}');
    const moved = slider.getAttribute('aria-valuenow');
    expect(moved).not.toBe('42');

    await user.click(dialog().getByRole('button', { name: '적용' }));
    await user.click(await screen.findByRole('button', { name: '편집' }));
    expect(dialog().getByRole('slider', { name: '확대' })).toHaveAttribute('aria-valuenow', moved);

    // 같은 기본 아바타를 다시 고르면 초기값으로 돌아온다
    await user.click(dialog().getByRole('button', { name: '닫기' }));
    await user.click(screen.getByRole('button', { name: '사진 수정' }));
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));
    expect(dialog().getByRole('slider', { name: '확대' })).toHaveAttribute('aria-valuenow', '42');
  });

  it('에러 면을 클릭하면 파일 선택기가 열린다 — 안내가 「클릭해 선택」이라고 말한다', async () => {
    const { user } = await open();
    // 모달은 Portal로 body에 붙는다 — container에는 없다
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const opened = vi.spyOn(input, 'click');

    drop(dropzone(), fileOf('big.png', 'image/png', Math.round(8.2 * 1024 * 1024)));
    await user.click(screen.getByRole('alert').closest('button') as HTMLElement);

    expect(opened).toHaveBeenCalled();
  });

  it('원본이 디코드되기 전에는 적용을 잠근다 — 잘리지 않은 원본이 저장되지 않게', async () => {
    const { user } = await open();
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));

    expect(dialog().getByRole('button', { name: '적용' })).toBeDisabled();

    markImageDecoded();
    expect(dialog().getByRole('button', { name: '적용' })).toBeEnabled();
  });

  it('읽을 수 없는 파일이면 적용을 막고 이유를 알린다', async () => {
    const { user } = await open();
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));

    fireEvent.error(document.querySelector('[role="dialog"] img') as HTMLImageElement);

    expect(dialog().getByRole('alert')).toHaveTextContent('사진으로 읽을 수 없어요');
    expect(dialog().getByRole('button', { name: '적용' })).toBeDisabled();
    expect(onApply).not.toHaveBeenCalled();
  });

  it('방향키로도 사진 위치를 옮긴다 — 포인터 전용이면 키보드로는 중앙 고정뿐이다', async () => {
    const { user } = await open();
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));
    markImageDecoded();

    const stage = dialog().getByRole('group', { name: /사진 위치 조정/ });
    expect(stage).toHaveAttribute('tabindex', '0');

    const img = document.querySelector('[role="dialog"] img') as HTMLImageElement;
    const before = img.style.transform;
    stage.focus();
    await user.keyboard('{ArrowRight}');

    // jsdom은 naturalWidth가 0이라 클램프가 통과시킨다 — 이동 자체가 붙는지만 본다
    expect(img.style.transform).not.toBe(before);
  });

  it('같은 그림으로 재진입해도 디코드를 다시 기다린다', async () => {
    const { user } = await open();
    await user.click(dialog().getByRole('button', { name: '기본 아바타 1' }));
    markImageDecoded();
    await user.click(dialog().getByRole('button', { name: '적용' }));

    // 토스트 「편집」으로 같은 그림에 재진입 — imageSrc는 그대로다
    await user.click(await screen.findByRole('button', { name: '편집' }));

    // 직전 ready가 남아 있으면 새 <img>가 읽히기도 전에 적용이 열려
    // 잘리지 않은 원본이 저장된다
    expect(dialog().getByRole('button', { name: '적용' })).toBeDisabled();
  });

  it('닫기로 언제든 빠져나온다', async () => {
    const { user } = await open();

    await user.click(dialog().getByRole('button', { name: '닫기' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(onApply).not.toHaveBeenCalled();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = await open();
    expect(await axe(container)).toHaveNoViolations();
  });
});
