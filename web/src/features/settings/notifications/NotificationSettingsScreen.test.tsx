import type { QueryClient } from '@tanstack/react-query';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { delegationsAsEditorQueryOptions } from '@/api/editors';
import { NotificationSettingsScreen } from '@/features/settings/notifications/NotificationSettingsScreen';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const AS_EDITOR_URL = '/api/editor-delegations/as-editor';
const DELEGATION = {
  id: 11,
  counterpartId: 3,
  counterpartName: '큰손',
  grantedAt: '2026-09-20T00:00:00Z',
};

// 편집을 맡은 채널 목록만 답한다 — 이 화면이 부르는 API는 그것 하나다
function stubAsEditor(body: unknown[] | 'error') {
  return stubFetch((url) => {
    if (url === AS_EDITOR_URL) {
      return body === 'error' ? jsonResponse(500, {}) : jsonResponse(200, body);
    }
    throw new Error(`unexpected fetch: ${url}`);
  });
}

// 맡은 채널 조회가 끝날 때까지 기다린다 — fetch가 불린 것만 보면 응답이 반영되기 전(pending)에 단언이
// 끝나, 편집자 줄이 pending이라 숨은 것인지 응답 때문에 숨은 것인지 가리지 못한다
async function settled(queryClient: QueryClient, status: 'success' | 'error') {
  await waitFor(() =>
    expect(queryClient.getQueryState(delegationsAsEditorQueryOptions.queryKey)?.status).toBe(
      status,
    ),
  );
}

const segment = (title: string) => screen.getByRole('radiogroup', { name: title });
const picked = (title: string) =>
  within(segment(title)).getByRole('radio', { checked: true }).closest('label')!.textContent;
const pick = (title: string, label: '끔' | '받기' | '중요') =>
  fireEvent.click(within(segment(title)).getByRole('radio', { name: label }));
const dndText = () =>
  within(screen.getByRole('region', { name: '방송 중 방해 금지' })).getByText(/라이브 중/)
    .textContent;

const STREAMER_ROWS = [
  '방송 채널 연동 만료 · 채팅 수집 끊김',
  '유튜브 연동 만료',
  '클립 렌더 · 업로드 결과',
  '편집자가 내 채널에 업로드',
  'VOD 삭제 임박 (D-7 · D-3 · D-1)',
  '방송 종료 · 다시보기 가능',
];
const EDITOR_ROW = '편집 중인 채널 방송 시작';

beforeEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('NotificationSettingsScreen', () => {
  it('이메일 열 없이 방해 금지 카드와 알림 카드가 뜬다', () => {
    stubAsEditor([]);
    renderWithProviders(<NotificationSettingsScreen />);

    expect(screen.getByRole('heading', { name: '알림 설정' })).toBeInTheDocument();
    expect(screen.getByText('알림은 오른쪽 위 종 아이콘에서 볼 수 있어요')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '방송 중 방해 금지' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '알림' })).toBeInTheDocument();

    // 채널 열(인앱·이메일)이 사라졌다
    expect(screen.queryByText('이메일')).toBeNull();
    expect(screen.queryByText('인앱')).toBeNull();
  });

  it('맡은 채널이 없는 계정엔 여섯 줄 — 기능이 없는 알림과 편집자 초대는 목록에 없다', async () => {
    stubAsEditor([]);
    const { queryClient } = renderWithProviders(<NotificationSettingsScreen />);
    await settled(queryClient, 'success');

    expect(screen.getAllByRole('radiogroup')).toHaveLength(6);
    for (const title of STREAMER_ROWS) {
      expect(segment(title)).toBeInTheDocument();
    }
    expect(screen.queryByRole('radiogroup', { name: EDITOR_ROW })).toBeNull();
    // 기능이 없어 뺀 셋, 끌 수 없어 목록 밖에 둔 하나
    for (const gone of ['결제 실패', '주간 성과 요약', '편집자 초대']) {
      expect(screen.queryByText(new RegExp(gone))).toBeNull();
    }
    expect(screen.queryByText(/자동 처리 시간/)).toBeNull();
  });

  it('편집을 맡은 채널이 있으면 편집자용 줄이 하나 더 보인다', async () => {
    stubAsEditor([DELEGATION]);
    renderWithProviders(<NotificationSettingsScreen />);

    expect(await screen.findByRole('radiogroup', { name: EDITOR_ROW })).toBeInTheDocument();
    expect(screen.getAllByRole('radiogroup')).toHaveLength(7);
  });

  it('맡은 채널을 못 불러오면 편집자용 줄을 숨긴다', async () => {
    stubAsEditor('error');
    const { queryClient } = renderWithProviders(<NotificationSettingsScreen />);
    await settled(queryClient, 'error');

    expect(screen.queryByRole('radiogroup', { name: EDITOR_ROW })).toBeNull();
    expect(screen.getAllByRole('radiogroup')).toHaveLength(6);
  });

  it('처음 값 — 방송·업로드가 막히는 둘만 「중요」, 나머지는 「받기」', async () => {
    stubAsEditor([DELEGATION]);
    renderWithProviders(<NotificationSettingsScreen />);
    await screen.findByRole('radiogroup', { name: EDITOR_ROW });

    expect(picked('방송 채널 연동 만료 · 채팅 수집 끊김')).toBe('중요');
    expect(picked('유튜브 연동 만료')).toBe('중요');
    for (const title of [...STREAMER_ROWS.slice(2), EDITOR_ROW]) {
      expect(picked(title)).toBe('받기');
    }
    expect(screen.getByRole('switch', { name: '방송 중 방해 금지' })).toBeChecked();
  });

  it('고른 값이 바뀌고, 방해 금지 설명의 개수가 따라간다', () => {
    stubAsEditor([]);
    renderWithProviders(<NotificationSettingsScreen />);

    expect(dndText()).toBe('라이브 중엔 중요 알림 2개만 와요');

    pick('유튜브 연동 만료', '끔');
    expect(picked('유튜브 연동 만료')).toBe('끔');
    expect(dndText()).toBe('라이브 중엔 중요 알림 1개만 와요');

    pick('클립 렌더 · 업로드 결과', '중요');
    expect(dndText()).toBe('라이브 중엔 중요 알림 2개만 와요');

    pick('방송 채널 연동 만료 · 채팅 수집 끊김', '받기');
    pick('클립 렌더 · 업로드 결과', '받기');
    expect(dndText()).toBe('라이브 중엔 알림이 오지 않아요');

    // 한 줄을 바꿔도 옆 줄은 그대로다
    expect(picked('VOD 삭제 임박 (D-7 · D-3 · D-1)')).toBe('받기');
  });

  it('방해 금지를 끄면 켜 둔 알림이 모두 온다고 말한다', () => {
    stubAsEditor([]);
    renderWithProviders(<NotificationSettingsScreen />);

    fireEvent.click(screen.getByRole('switch', { name: '방송 중 방해 금지' }));
    expect(screen.getByRole('switch', { name: '방송 중 방해 금지' })).not.toBeChecked();
    expect(dndText()).toBe('라이브 중에도 켜 둔 알림이 모두 와요');
  });

  it('어디에도 저장하지 않는다 — 언마운트 후 다시 마운트하면 처음 값이다', () => {
    // 의도를 잠그는 테스트. 저장 계층(localStorage·전역 스토어·모듈 캐시)이 무심코 들어오면
    // 여기서 깨진다. 알림 서버를 붙이는 티켓이 이 테스트를 뒤집는 것이 정상 신호다.
    stubAsEditor([]);
    const first = renderWithProviders(<NotificationSettingsScreen />);

    fireEvent.click(screen.getByRole('switch', { name: '방송 중 방해 금지' }));
    pick('유튜브 연동 만료', '끔');
    expect(window.localStorage.length).toBe(0);

    first.unmount();
    renderWithProviders(<NotificationSettingsScreen />);

    expect(screen.getByRole('switch', { name: '방송 중 방해 금지' })).toBeChecked();
    expect(picked('유튜브 연동 만료')).toBe('중요');
  });

  it('접근성 위반이 없다', async () => {
    stubAsEditor([DELEGATION]);
    const { container } = renderWithProviders(<NotificationSettingsScreen />);
    await screen.findByRole('radiogroup', { name: EDITOR_ROW });

    expect(await axe(container)).toHaveNoViolations();
  });
});
