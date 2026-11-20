import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChannelRelationContext } from '@/features/channel/ChannelScope';
import { useSwitchCurtain } from '@/features/channel/switchCurtain';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { byTextContent } from '@/test/byTextContent';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { EditingSettingsScreen } from './EditingSettingsScreen';

// 설정 › 편집 환경 (POK-614) — 도구 패널 위치 · 오디오 트랙 이름

const PANEL_SIDE_KEY = 'pc-editor-panel-side';
const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };
/** 서버 모양 그대로: counterpartId가 스트리머 번호다(OwnerOnly 시험과 같다) */
const BYEOLHA = {
  id: 21,
  counterpartId: 3,
  counterpartName: '별하',
  grantedAt: '2026-10-01T00:00:00Z',
};
const TRACKS_URL = '/api/auth/me/audio-tracks';

function stubTracks() {
  return stubFetch((url) => {
    if (url === TRACKS_URL)
      return jsonResponse(200, { labels: [null, null, null, null, null, null] });
    return jsonResponse(404);
  });
}

function radio(name: '왼쪽' | '오른쪽') {
  const group = screen.getByRole('radiogroup', { name: '도구 패널 위치' });
  return within(group).getByRole('radio', { name: new RegExp(`^${name}`) });
}

beforeEach(() => {
  window.localStorage.clear();
  useSwitchCurtain.getState().reset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('EditingSettingsScreen — 도구 패널 위치', () => {
  it('고른 적 없으면 왼쪽(기본)이 골라져 있고, 시안처럼 그림 타일 둘과 각주가 선다', async () => {
    stubTracks();
    const { container } = renderWithProviders(<EditingSettingsScreen />);

    expect(screen.getByRole('heading', { level: 1, name: '편집 환경' })).toBeInTheDocument();
    expect(radio('왼쪽')).toBeChecked();
    expect(radio('왼쪽')).toHaveAccessibleName('왼쪽 기본');
    expect(radio('오른쪽')).not.toBeChecked();
    expect(radio('오른쪽')).toHaveAccessibleName('오른쪽');
    // 실제 도구 이름으로 말한다 — 시안 초안의 「구간 · BGM·효과」는 지금 편집기에 없다
    expect(screen.getByRole('radiogroup', { name: '도구 패널 위치' })).toHaveAccessibleDescription(
      /레이아웃 · 자막 · 텍스트 · 오디오 · 이미지 패널과 도구 레일을 둘 쪽을 고르세요.*패널 위치 버튼으로도 바꿀 수 있어요 · 이 브라우저에만 저장돼요/,
    );
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('오른쪽을 고르면 저장 버튼 없이 바로 편집기가 읽는 값에 남는다', () => {
    stubTracks();
    renderWithProviders(<EditingSettingsScreen />);

    fireEvent.click(radio('오른쪽'));

    expect(radio('오른쪽')).toBeChecked();
    expect(radio('왼쪽')).not.toBeChecked();
    expect(window.localStorage.getItem(PANEL_SIDE_KEY)).toBe('right');

    fireEvent.click(radio('왼쪽'));
    expect(window.localStorage.getItem(PANEL_SIDE_KEY)).toBe('left');
  });

  it('편집기에서 버튼으로 옮겨 둔 쪽이 골라져 있다 — 다른 탭에서 옮겨도 따라간다', () => {
    window.localStorage.setItem(PANEL_SIDE_KEY, 'right');
    stubTracks();
    renderWithProviders(<EditingSettingsScreen />);
    expect(radio('오른쪽')).toBeChecked();

    // 옆 탭의 편집기가 왼쪽으로 옮겼다
    act(() => {
      window.localStorage.setItem(PANEL_SIDE_KEY, 'left');
      window.dispatchEvent(new StorageEvent('storage', { key: PANEL_SIDE_KEY, newValue: 'left' }));
    });
    expect(radio('왼쪽')).toBeChecked();
  });

  it('저장된 값이 깨져 있으면 기본(왼쪽)으로 본다', () => {
    window.localStorage.setItem(PANEL_SIDE_KEY, 'middle');
    stubTracks();
    renderWithProviders(<EditingSettingsScreen />);
    expect(radio('왼쪽')).toBeChecked();
  });
});

describe('EditingSettingsScreen — 오디오 트랙 이름', () => {
  it('내 채널이면 플러그인 화면에서 옮겨 온 카드가 그대로 선다', async () => {
    stubFetch((url) => {
      if (url === TRACKS_URL)
        return jsonResponse(200, { labels: ['전체', null, '디스코드', null, null, null] });
      return jsonResponse(404);
    });
    renderWithProviders(
      <ChannelRelationContext.Provider value="OWNER">
        <EditingSettingsScreen />
      </ChannelRelationContext.Provider>,
    );

    expect(screen.getByRole('heading', { name: '오디오 트랙 이름' })).toBeInTheDocument();
    expect(await screen.findByDisplayValue('디스코드')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이름 저장' })).toBeInTheDocument();
  });

  it('남의 채널에서 편집자로 있으면 내 트랙 이름을 읽지도 않고 안내 칸과 「내 채널로 전환」을 둔다 — 패널 위치는 그대로 고른다', async () => {
    // 시안 A(2026-11-20): 흐린 글 한 줄은 묻혀 안 보였다 — 까닭 · 지금 채널 · 전환 버튼을 한 칸에
    useChannelStore.setState({ userId: 7, selected: 3 });
    const spy = stubFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse(200, ME);
      if (url === '/api/editor-delegations/as-editor') return jsonResponse(200, [BYEOLHA]);
      return jsonResponse(404);
    });
    renderWithProviders(
      <ChannelRelationContext.Provider value="EDITOR">
        <EditingSettingsScreen />
      </ChannelRelationContext.Provider>,
    );

    expect(screen.getByRole('heading', { name: '오디오 트랙 이름' })).toBeInTheDocument();
    expect(screen.getByText('채널 주인만 바꿀 수 있어요')).toBeInTheDocument();
    expect(
      await screen.findByText(byTextContent('지금은 별하 채널에서 편집자로 일하는 중이에요')),
    ).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(spy.mock.calls.filter(([url]) => url === TRACKS_URL)).toHaveLength(0);

    fireEvent.click(radio('오른쪽'));
    expect(window.localStorage.getItem(PANEL_SIDE_KEY)).toBe('right');

    // 막힌 설정 화면(OwnerOnly)과 같은 길로 내 채널로 돌아간다
    fireEvent.click(screen.getByRole('button', { name: '내 채널로 전환' }));
    expect(useSwitchCurtain.getState().curtain).toMatchObject({ name: '나', relation: 'OWNER' });
    await waitFor(() =>
      expect(useChannelStore.getState()).toMatchObject({ userId: 7, selected: null }),
    );
  });
});
