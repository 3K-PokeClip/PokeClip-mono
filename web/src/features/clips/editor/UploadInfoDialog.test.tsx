import { act } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { emptyUploadDraft, UploadInfoDialog, type UploadDraft } from './UploadInfoDialog';
import { THUMBNAIL_MAX_BYTES } from './uploadInfo';

// 「영상 만들기」의 업로드 창(POK-291 → POK-381 개편). 탭 「정보 · 썸네일 · 공개」와 썸네일 펼치는 목록.
// 여기서 재는 것은 칸의 값·규칙과 「업로드는 공개 탭에서만」이다. 장면 그림은 녹화 조각에서 뽑으므로 시험에서는 재료가 없다
// (frameSource 없음 → 시각만 보인다).

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
      highlightOffsetMs={8_000}
      serverErrors={{}}
      onCancel={onCancel}
      onSubmit={onSubmit}
      {...overrides}
    />,
  );
  const dialog = () => within(screen.getByRole('dialog', { name: '유튜브에 올리기' }));
  return { ...view, onSubmit, onCancel, dialog };
}

type Dialog = ReturnType<typeof renderDialog>['dialog'];

async function openTab(user: ReturnType<typeof userEvent.setup>, dialog: Dialog, name: string) {
  await user.click(dialog().getByRole('tab', { name: new RegExp(name) }));
}

async function upload(user: ReturnType<typeof userEvent.setup>, dialog: Dialog) {
  await openTab(user, dialog, '공개');
  await user.click(dialog().getByRole('button', { name: /업로드/ }));
}

describe('UploadInfoDialog', () => {
  it('장면 그림 재료가 없으면 미리보기가 「불러오는 중」에 머물지 않고 못 불러온다고 말한다(PR #301 리뷰)', () => {
    const { dialog } = renderDialog();

    expect(dialog().getByText('장면 그림을 못 불러와요 · 0:06.2')).toBeInTheDocument();
    expect(dialog().queryByText('장면을 불러오는 중이에요')).not.toBeInTheDocument();
  });

  it('편집기에서 고른 장면은 추천 맨 앞 「편집기에서 고름」으로 골라져 있다(POK-389)', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog(
      { pickedInEditor: true },
      { thumbnailSource: 'scene', sceneOffsetMs: 3_500 },
    );
    await openTab(user, dialog, '썸네일');
    expect(dialog().getByRole('button', { name: '편집기에서 고름 0:03.5' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await user.click(dialog().getByRole('button', { name: '중간 0:06.2' }));
    expect(dialog().getByRole('button', { name: '편집기에서 고름 0:03.5' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('처음 값: 정보 탭 · 빈 칸 · 카테고리 게임 · 썸네일은 장면 · 비공개 · 아동용 아님', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();

    expect(dialog().getByRole('tab', { name: '정보' })).toHaveAttribute('aria-selected', 'true');
    // 창을 열면 제목 칸부터 친다(DS 창의 첫 초점은 탭이다)
    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveFocus();
    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveValue('');
    expect(dialog().getByRole('textbox', { name: /설명/ })).toHaveValue('');
    expect(dialog().getByRole('textbox', { name: /태그/ })).toHaveValue('');
    expect(dialog().getByRole('combobox', { name: '카테고리' })).toHaveTextContent('게임');

    await openTab(user, dialog, '썸네일');
    expect(dialog().getByRole('radio', { name: /영상 장면/ })).toBeChecked();

    await openTab(user, dialog, '공개');
    expect(dialog().getByRole('radio', { name: '비공개' })).toBeChecked();
    expect(dialog().getByRole('switch', { name: /아동용 영상으로 올리기/ })).not.toBeChecked();
    // 비공개(처음 값)면 바꾸는 곳만 말한다
    expect(
      dialog().getByText('올린 뒤에는 스트리머 채널의 유튜브 스튜디오에서만 바꿀 수 있어요.'),
    ).toBeInTheDocument();
    // 헤더의 「영상 만들기」와 겹치지 않는다
    expect(dialog().queryByRole('button', { name: '영상 만들기' })).not.toBeInTheDocument();
  });

  it('업로드는 공개 탭에서만: 정보·썸네일 탭은 「다음」으로 넘어가고 「이전」으로 돌아온다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit, onCancel } = renderDialog({}, { title: '제목' });

    expect(dialog().queryByRole('button', { name: '업로드' })).not.toBeInTheDocument();
    expect(dialog().getByText('공개 탭에서 올려요')).toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: '다음' }));
    expect(dialog().getByRole('tab', { name: '썸네일' })).toHaveAttribute('aria-selected', 'true');
    expect(dialog().queryByRole('button', { name: '업로드' })).not.toBeInTheDocument();

    // 「다음」을 누른 단추가 그 자리의 「업로드」로 바뀌어 보내지면 안 된다
    await user.click(dialog().getByRole('button', { name: '다음' }));
    expect(dialog().getByRole('tab', { name: '공개' })).toHaveAttribute('aria-selected', 'true');
    expect(onSubmit).not.toHaveBeenCalled();

    await user.click(dialog().getByRole('button', { name: '이전' }));
    expect(dialog().getByRole('tab', { name: '썸네일' })).toHaveAttribute('aria-selected', 'true');
    await user.click(dialog().getByRole('button', { name: '이전' }));
    await user.click(dialog().getByRole('button', { name: '취소' }));
    expect(onCancel).toHaveBeenCalled();
  });

  it('제목 칸에서 Enter를 쳐도 보내지 않는다(업로드 단추가 없는 탭)', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog();
    await user.type(dialog().getByRole('textbox', { name: /제목/ }), '보스 막타{Enter}');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('공개 범위를 고르면 안내가 그 범위로 바뀐다: 누가 보는지 · 공개면 구독자 알림 · 바꾸는 곳(POK-291)', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();
    await openTab(user, dialog, '공개');

    // 문장 전체를 잰다: 셋 중 하나가 빠지거나 범위가 섞여도 빨간불이어야 한다
    await user.click(dialog().getByRole('radio', { name: '공개' }));
    expect(
      dialog().getByText(
        '누구나 볼 수 있어요. 구독자에게 새 영상 알림이 갈 수 있어요. 올린 뒤에는 스트리머 채널의 유튜브 스튜디오에서만 바꿀 수 있어요.',
      ),
    ).toBeInTheDocument();

    await user.click(dialog().getByRole('radio', { name: '일부 공개' }));
    // 라디오 묶음이 이 안내를 설명으로 가리킨다
    expect(dialog().getByRole('radiogroup', { name: '공개 범위' })).toHaveAccessibleDescription(
      '주소를 아는 사람만 볼 수 있어요. 올린 뒤에는 스트리머 채널의 유튜브 스튜디오에서만 바꿀 수 있어요.',
    );
  });

  it('모든 칸을 채워 보내면 그 값이 그대로 간다(카테고리 포함)', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog();

    await user.type(dialog().getByRole('textbox', { name: /제목/ }), '  보스 막타 ');
    await user.type(dialog().getByRole('textbox', { name: /설명/ }), '역전 순간');
    await user.type(dialog().getByRole('textbox', { name: /태그/ }), '롤{Enter}보스 막타,역전,');
    await user.click(dialog().getByRole('combobox', { name: '카테고리' }));
    await user.click(screen.getByRole('option', { name: '엔터테인먼트' }));
    await openTab(user, dialog, '공개');
    await user.click(dialog().getByRole('radio', { name: '일부 공개' }));
    await user.click(dialog().getByRole('switch', { name: /아동용 영상으로 올리기/ }));
    await user.click(dialog().getByRole('button', { name: '업로드' }));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '  보스 막타 ',
        description: '역전 순간',
        tags: ['롤', '보스 막타', '역전'],
        categoryId: '24',
        privacyStatus: 'unlisted',
        scheduled: false,
        madeForKids: true,
        thumbnailSource: 'scene',
      }),
    );
  });

  it('아동용 스위치를 켜면 카드에 「아동용」 표와 꺼지는 기능이 보인다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();
    await openTab(user, dialog, '공개');
    expect(
      dialog().getByText('어린이(만 13세 미만)를 보라고 만든 영상일 때만 켜요'),
    ).toBeInTheDocument();

    await user.click(dialog().getByRole('switch', { name: /아동용 영상으로 올리기/ }));
    expect(dialog().getByText('아동용')).toBeInTheDocument();
    expect(
      dialog().getByText('아동용으로 올려요 · 댓글 · 알림 · 맞춤 광고가 꺼져요'),
    ).toBeInTheDocument();
  });

  it('예약을 고르면 비공개 + 시각으로 보내고 단추는 「예약 업로드」다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목' });
    await openTab(user, dialog, '공개');
    await user.click(dialog().getByRole('radio', { name: '예약' }));

    await user.click(dialog().getByRole('button', { name: '내일 21:00' }));
    expect(dialog().getByRole('button', { name: '내일 21:00' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(
      dialog().getByText(/^지금 비공개로 올리고 .+ 21:00에 공개로 바꿔요$/),
    ).toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: '예약 업로드' }));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ privacyStatus: 'private', scheduled: true, scheduleTime: '21:00' }),
    );
  });

  it('예약 시각이 1시간 안이거나 지났으면 보내지 않고 그 칸에 말한다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목' });
    await openTab(user, dialog, '공개');
    await user.click(dialog().getByRole('radio', { name: '예약' }));

    fireEvent.change(dialog().getByLabelText('공개 날짜'), { target: { value: '2020-01-01' } });
    await user.click(dialog().getByRole('button', { name: '예약 업로드' }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(dialog().getByRole('alert')).toHaveTextContent('지금부터 1시간 뒤부터 고를 수 있어요');
    expect(dialog().getByRole('tab', { name: /공개/ })).toHaveAccessibleName(/확인할 칸 있음/);
  });

  it('태그는 제거 단추로 뺄 수 있고, 친 채 남은 글자도 보낼 때 태그가 된다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목', tags: ['롤', '보스'] });

    await user.click(dialog().getByRole('button', { name: '태그 롤 빼기' }));
    await user.type(dialog().getByRole('textbox', { name: /태그/ }), '남은 글자');
    await upload(user, dialog);

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ tags: ['보스', '남은 글자'] }));
  });

  it('넣은 태그는 입력 칸 안, 치는 글자 앞에 쌓이고 칸의 빈 자리를 누르거나 태그를 빼면 글자 칸에 초점이 간다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();
    const input = dialog().getByRole('textbox', { name: /태그/ });
    const box = input.parentElement as HTMLElement;

    await user.type(input, '롤{Enter}보스{Enter}');
    expect(within(box).getByText('롤')).toBeInTheDocument();
    expect(
      within(box).getByText('보스').compareDocumentPosition(input) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    await user.click(dialog().getByRole('textbox', { name: /제목/ }));
    await user.click(box);
    expect(input).toHaveFocus();

    await user.click(within(box).getByRole('button', { name: '태그 롤 빼기' }));
    expect(within(box).queryByText('롤')).not.toBeInTheDocument();
    expect(input).toHaveFocus();
  });

  it('빈 태그 칸에서 Backspace를 누르면 마지막 태그부터 빠지고, 친 글자가 있으면 글자만 지운다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog({}, { tags: ['롤', '보스'] });
    const input = dialog().getByRole('textbox', { name: /태그/ });

    await user.type(input, '역{Backspace}');
    expect(input).toHaveValue('');
    expect(dialog().getByText('보스')).toBeInTheDocument();

    await user.type(input, '{Backspace}');
    expect(dialog().queryByText('보스')).not.toBeInTheDocument();
    expect(dialog().getByText('롤')).toBeInTheDocument();

    await user.type(input, '{Backspace}{Backspace}');
    expect(dialog().queryByText('롤')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
  });

  it('제목이 비었으면 보내지 않고 정보 탭을 열어 그 칸에 말한다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog();

    await upload(user, dialog);
    expect(onSubmit).not.toHaveBeenCalled();
    expect(dialog().getByRole('tab', { name: /정보/ })).toHaveAttribute('aria-selected', 'true');
    expect(dialog().getByRole('tab', { name: /정보/ })).toHaveAccessibleName(/확인할 칸 있음/);
    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveAccessibleDescription(
      /유튜브 제목을 적어 주세요/,
    );

    await user.type(dialog().getByRole('textbox', { name: /제목/ }), 'a <b>');
    await upload(user, dialog);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('태그 합계가 500자를 넘으면 넣지 않고 까닭을 말한다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog({}, { tags: ['a'.repeat(498)] });

    await user.type(dialog().getByRole('textbox', { name: /태그/ }), 'bc{Enter}');
    expect(dialog().getByText('태그는 합쳐서 500자까지예요.')).toBeInTheDocument();
    expect(dialog().queryByText('bc')).not.toBeInTheDocument();
  });

  it('추천 장면 다섯(하이라이트 · 시작 · 재생 위치 · 중간 · 마지막)을 누르거나 띠를 옮겨 고른 ms를 보낸다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목', sceneOffsetMs: 8_000 });
    await openTab(user, dialog, '썸네일');

    const picks = [
      '하이라이트 0:08.0',
      '시작 0:00.0',
      '재생 위치 0:03.2',
      '중간 0:06.2',
      '마지막 0:12.3',
    ];
    for (const name of picks) expect(dialog().getByRole('button', { name })).toBeInTheDocument();
    expect(dialog().getByRole('button', { name: '하이라이트 0:08.0' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await user.click(dialog().getByRole('button', { name: '재생 위치 0:03.2' }));
    const strip = dialog().getByRole('slider', { name: '썸네일 장면' });
    expect(strip).toHaveAttribute('aria-valuenow', '3250');
    expect(strip).toHaveAttribute('aria-valuemax', '12399');
    // 0.25초 눈금으로 옮긴다
    strip.focus();
    await user.keyboard('{ArrowRight}');
    expect(strip).toHaveAttribute('aria-valuenow', '3500');
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}');
    expect(strip).toHaveAttribute('aria-valuenow', '4500');
    expect(dialog().getByRole('radio', { name: /영상 장면/ })).toHaveAccessibleName(
      /직접 고름 · 0:04\.5/,
    );

    await upload(user, dialog);
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ thumbnailSource: 'scene', sceneOffsetMs: 4_500 }),
    );
  });

  it('하이라이트·재생 위치를 모르면(카드 없음 · 녹화 없음) 그 추천은 빠진다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog({ highlightOffsetMs: null, playheadOffsetMs: null });
    await openTab(user, dialog, '썸네일');
    expect(dialog().queryByRole('button', { name: /하이라이트/ })).not.toBeInTheDocument();
    expect(dialog().queryByRole('button', { name: /재생 위치/ })).not.toBeInTheDocument();
    expect(dialog().getByRole('button', { name: '중간 0:06.2' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('내 이미지는 JPG·PNG 10MB까지 받고, 넘으면 창 안에서 거절한다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({}, { title: '제목' });
    await openTab(user, dialog, '썸네일');

    await user.click(dialog().getByRole('radio', { name: /내 이미지/ }));
    expect(dialog().getAllByText(/세로 1080×1920 권장/).length).toBeGreaterThan(0);
    // 고르지 않고 보내면 썸네일 탭을 열어 말한다
    await upload(user, dialog);
    expect(onSubmit).not.toHaveBeenCalled();
    expect(dialog().getByRole('tab', { name: /썸네일/ })).toHaveAttribute('aria-selected', 'true');
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
    await upload(user, dialog);
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ thumbnailSource: 'file', file: ok }),
    );
  });

  it('서버가 칸을 짚어 거절하면 그 칸이 있는 탭을 열어 그 칸에 보인다', () => {
    const { dialog } = renderDialog({ serverErrors: { thumbnail: '썸네일을 확인해 주세요.' } });
    expect(dialog().getByRole('tab', { name: /썸네일/ })).toHaveAttribute('aria-selected', 'true');
    expect(dialog().getByText('썸네일을 확인해 주세요.')).toBeInTheDocument();
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
    // 걸린 칸이 있는 첫 탭(정보)이 열린다
    const title = dialog().getByRole('textbox', { name: /제목/ });
    expect(title).toHaveAccessibleDescription(/유튜브 제목을 확인해 주세요/);

    await user.type(title, '!');
    expect(title).not.toHaveAccessibleDescription(/유튜브 제목을 확인해 주세요/);
    expect(dialog().queryByText('유튜브 제목을 확인해 주세요.')).not.toBeInTheDocument();
    // 고치지 않은 칸의 서버 오류는 남는다
    await openTab(user, dialog, '썸네일');
    expect(dialog().getByText('이미지는 10MB까지 올릴 수 있어요')).toBeInTheDocument();

    await user.click(dialog().getByRole('radio', { name: /유튜브에 맡기기/ }));
    expect(dialog().queryByText('이미지는 10MB까지 올릴 수 있어요')).not.toBeInTheDocument();
  });

  it('다른 칸이 창 안 검사에 걸려도 고치지 않은 칸의 서버 오류는 남는다', async () => {
    const user = userEvent.setup();
    const { dialog, onSubmit } = renderDialog({
      serverErrors: { description: '설명을 확인해 주세요.' },
    });
    // 제목이 비어 창 안 검사에 걸린다
    await upload(user, dialog);
    expect(onSubmit).not.toHaveBeenCalled();
    expect(dialog().getByRole('textbox', { name: /설명/ })).toHaveAccessibleDescription(
      /설명을 확인해 주세요/,
    );
  });

  it('보내는 중에는 업로드 단추가 잠기고 닫히지 않는다', async () => {
    const user = userEvent.setup();
    const { dialog, onCancel } = renderDialog({ busy: true });
    await openTab(user, dialog, '공개');
    expect(dialog().getByRole('button', { name: /업로드/ })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('다시 열면 받은 값(같은 판의 업로드 정보)으로 채워진다', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog(
      {},
      { title: '먼저 적은 제목', privacyStatus: 'public', thumbnailSource: 'none' },
    );
    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveValue('먼저 적은 제목');
    await openTab(user, dialog, '썸네일');
    expect(dialog().getByRole('radio', { name: /유튜브에 맡기기/ })).toBeChecked();
    await openTab(user, dialog, '공개');
    expect(dialog().getByRole('radio', { name: '공개' })).toBeChecked();
  });

  it('접근성 위반이 없다: 탭마다, 이미지 칸과 예약 칸을 연 상태까지', async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();
    const check = async () => {
      await act(async () => {
        expect(await axe(document.body)).toHaveNoViolations();
      });
    };
    await check();
    await openTab(user, dialog, '썸네일');
    await check();
    await user.click(dialog().getByRole('radio', { name: /내 이미지/ }));
    await check();
    await openTab(user, dialog, '공개');
    await user.click(dialog().getByRole('radio', { name: '예약' }));
    await check();
  });
});
