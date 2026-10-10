import { act } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { emptyUploadDraft, UploadInfoDialog, type UploadDraft } from './UploadInfoDialog';
import { THUMBNAIL_MAX_BYTES } from './uploadInfo';

// 「영상 만들기」의 업로드 정보 창(POK-291). DS 부품 조립만 한다: 여기서 재는 것은 칸의 값과 규칙이다.

function renderDialog(
  overrides: Partial<Parameters<typeof UploadInfoDialog>[0]> = {},
  initial: Partial<UploadDraft> = {},
) {
  const onSubmit = vi.fn();
  const onCancel = vi.fn();
  const view = renderWithProviders(
    <UploadInfoDialog
      open
      busy={false}
      initial={{ ...emptyUploadDraft(6_200), ...initial }}
      cutLengthMs={12_400}
      playheadOffsetMs={3_250}
      serverErrors={{}}
      onCancel={onCancel}
      onSubmit={onSubmit}
      {...overrides}
    />,
  );
  return { ...view, onSubmit, onCancel, dialog: () => within(screen.getByRole('dialog')) };
}

describe('UploadInfoDialog', () => {
  it('칸 여섯의 처음 값: 비공개 · 아동용 아님 · 썸네일 자동 · 확인 단추는 「만들고 올리기」', () => {
    const { dialog } = renderDialog();

    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveValue('');
    expect(dialog().getByRole('textbox', { name: /설명/ })).toHaveValue('');
    expect(dialog().getByRole('textbox', { name: /태그/ })).toHaveValue('');
    expect(dialog().getByRole('radio', { name: '비공개' })).toBeChecked();
    expect(dialog().getByRole('switch', { name: '아동용 영상이에요' })).not.toBeChecked();
    expect(dialog().getByRole('radio', { name: '자동(유튜브가 고름)' })).toBeChecked();
    expect(dialog().getByRole('button', { name: '만들고 올리기' })).toBeEnabled();
    // 헤더의 「영상 만들기」와 겹치지 않는다
    expect(dialog().queryByRole('button', { name: '영상 만들기' })).not.toBeInTheDocument();
    // 공개 범위 경고는 문장 전체를 잰다: 누가 보는지·구독자 알림·바꾸는 곳 중 하나가 빠져도 빨간불이어야 한다
    expect(
      dialog().getByText(
        '일부 공개는 주소를 아는 사람이, 공개는 누구나 볼 수 있어요. 공개는 구독자에게 새 영상 알림이 갈 수 있어요. 다 만들어지는 대로 바로 올라가고, 올린 뒤에는 스트리머 채널의 유튜브 스튜디오에서만 바꿀 수 있어요.',
      ),
    ).toBeInTheDocument();
  });

  it('모든 칸을 채워 보내면 그 값이 그대로 간다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog();

    await user.type(dialog().getByRole('textbox', { name: /제목/ }), '  보스 막타 ');
    await user.type(dialog().getByRole('textbox', { name: /설명/ }), '역전 순간');
    await user.type(dialog().getByRole('textbox', { name: /태그/ }), '롤{Enter}보스 막타,역전,');
    await user.click(dialog().getByRole('radio', { name: '일부 공개' }));
    await user.click(dialog().getByRole('switch', { name: '아동용 영상이에요' }));
    await user.click(dialog().getByRole('button', { name: '만들고 올리기' }));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '  보스 막타 ',
        description: '역전 순간',
        tags: ['롤', '보스 막타', '역전'],
        privacyStatus: 'unlisted',
        madeForKids: true,
        thumbnailSource: 'none',
      }),
    );
  });

  it('태그는 제거 단추로 뺄 수 있고, 친 채 남은 글자도 보낼 때 태그가 된다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목', tags: ['롤', '보스'] });

    await user.click(dialog().getByRole('button', { name: '태그 롤 빼기' }));
    await user.type(dialog().getByRole('textbox', { name: /태그/ }), '남은 글자');
    await user.click(dialog().getByRole('button', { name: '만들고 올리기' }));

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ tags: ['보스', '남은 글자'] }));
  });

  it('제목이 비었거나 규칙에 어긋나면 보내지 않고 그 칸에 말한다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog();

    await user.click(dialog().getByRole('button', { name: '만들고 올리기' }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveAccessibleDescription(
      /유튜브 제목을 적어 주세요/,
    );

    await user.type(dialog().getByRole('textbox', { name: /제목/ }), 'a <b>');
    await user.click(dialog().getByRole('button', { name: '만들고 올리기' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('태그 합계가 500자를 넘으면 넣지 않고 까닭을 말한다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog({}, { tags: ['a'.repeat(498)] });

    await user.type(dialog().getByRole('textbox', { name: /태그/ }), 'bc{Enter}');
    expect(dialog().getByText('태그는 합쳐서 500자까지예요.')).toBeInTheDocument();
    expect(dialog().queryByText('bc')).not.toBeInTheDocument();
  });

  it('장면 고르기는 슬라이더와 「지금 재생 위치로」로 고른 ms를 보낸다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목' });

    await user.click(dialog().getByRole('radio', { name: '장면 고르기' }));
    const slider = dialog().getByRole('slider', { name: '썸네일 장면' });
    expect(slider).toHaveAttribute('aria-valuenow', '6200');
    expect(slider).toHaveAttribute('aria-valuemax', '12399');
    expect(dialog().getByText(/완성 영상의 이 장면을 썸네일로 써요/)).toBeInTheDocument();

    await user.click(dialog().getByRole('button', { name: '지금 재생 위치로' }));
    expect(slider).toHaveAttribute('aria-valuenow', '3250');
    await user.click(dialog().getByRole('button', { name: '만들고 올리기' }));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ thumbnailSource: 'scene', sceneOffsetMs: 3_250 }),
    );
  });

  it('재생 위치를 모르면(녹화 없음) 「지금 재생 위치로」를 누를 수 없다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog({ playheadOffsetMs: null });
    await user.click(dialog().getByRole('radio', { name: '장면 고르기' }));
    expect(dialog().getByRole('button', { name: '지금 재생 위치로' })).toBeDisabled();
  });

  it('이미지 올리기는 JPG·PNG 10MB까지 받고, 넘으면 창 안에서 거절한다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목' });

    await user.click(dialog().getByRole('radio', { name: '이미지 올리기' }));
    expect(dialog().getByText(/가로 1280×720 이상을 권해요/)).toBeInTheDocument();
    expect(dialog().getByText(/채널 전화 인증이 끝나야 붙어요/)).toBeInTheDocument();
    // 고르지 않고 보내면 그 칸에 말한다
    await user.click(dialog().getByRole('button', { name: '만들고 올리기' }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(dialog().getByText('이미지를 골라 주세요')).toBeInTheDocument();

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const big = new File([new Uint8Array(THUMBNAIL_MAX_BYTES + 1)], 'big.png', {
      type: 'image/png',
    });
    fireEvent.change(input, { target: { files: [big] } });
    expect(dialog().getByText('이미지는 10MB까지 올릴 수 있어요')).toBeInTheDocument();

    const ok = new File([new Uint8Array([0xff, 0xd8, 0xff])], 'cover.jpg', { type: 'image/jpeg' });
    fireEvent.change(input, { target: { files: [ok] } });
    expect(dialog().getByText('cover.jpg')).toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: '만들고 올리기' }));
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ thumbnailSource: 'file', file: ok }),
    );
  });

  it('서버가 칸을 짚어 거절하면 그 칸에 보인다', () => {
    const { dialog } = renderDialog({ serverErrors: { tags: '태그를 확인해 주세요.' } });
    expect(dialog().getByRole('textbox', { name: /태그/ })).toHaveAccessibleDescription(
      /태그를 확인해 주세요/,
    );
  });

  it('서버가 짚은 칸을 고치면 그 칸의 서버 오류가 사라진다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog(
      {
        serverErrors: {
          title: '유튜브 제목을 확인해 주세요.',
          thumbnail: '이미지는 10MB까지 올릴 수 있어요',
        },
      },
      { title: '처음 제목', thumbnailSource: 'file' },
    );
    const title = dialog().getByRole('textbox', { name: /제목/ });
    expect(title).toHaveAccessibleDescription(/유튜브 제목을 확인해 주세요/);

    await user.type(title, '!');
    expect(title).not.toHaveAccessibleDescription(/유튜브 제목을 확인해 주세요/);
    expect(dialog().queryByText('유튜브 제목을 확인해 주세요.')).not.toBeInTheDocument();
    // 고치지 않은 칸의 서버 오류는 남는다
    expect(dialog().getByText('이미지는 10MB까지 올릴 수 있어요')).toBeInTheDocument();

    await user.click(dialog().getByRole('radio', { name: '자동(유튜브가 고름)' }));
    expect(dialog().queryByText('이미지는 10MB까지 올릴 수 있어요')).not.toBeInTheDocument();
  });

  it('다른 칸이 창 안 검사에 걸려도 고치지 않은 칸의 서버 오류는 남는다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({
      serverErrors: { description: '설명을 확인해 주세요.' },
    });
    // 제목이 비어 창 안 검사에 걸린다
    await user.click(dialog().getByRole('button', { name: /만들고 올리기/ }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(dialog().getByRole('textbox', { name: /설명/ })).toHaveAccessibleDescription(
      /설명을 확인해 주세요/,
    );
  });

  it('보내는 중에는 확인 단추가 잠기고 닫히지 않는다', async () => {
    const user = userEvent.setup();
    const { dialog, onCancel } = renderDialog({ busy: true });
    expect(dialog().getByRole('button', { name: /만들고 올리기/ })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('다시 열면 받은 값(같은 판의 업로드 정보)으로 채워진다', () => {
    const { dialog } = renderDialog(
      {},
      { title: '먼저 적은 제목', privacyStatus: 'public', thumbnailSource: 'scene' },
    );
    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveValue('먼저 적은 제목');
    expect(dialog().getByRole('radio', { name: '공개' })).toBeChecked();
    expect(dialog().getByRole('radio', { name: '장면 고르기' })).toBeChecked();
  });

  it('접근성 위반이 없다: 장면·이미지 칸을 연 상태까지', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();
    await user.click(dialog().getByRole('radio', { name: '장면 고르기' }));
    await act(async () => {
      expect(await axe(document.body)).toHaveNoViolations();
    });
    await user.click(dialog().getByRole('radio', { name: '이미지 올리기' }));
    await act(async () => {
      expect(await axe(document.body)).toHaveNoViolations();
    });
  });
});
