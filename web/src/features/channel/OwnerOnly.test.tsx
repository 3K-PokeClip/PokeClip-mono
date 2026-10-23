import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { ChannelRelationContext } from './ChannelScope';
import { OwnerOnly } from './OwnerOnly';
import { useSwitchCurtain } from './switchCurtain';

const ME = { id: 7, email: 'me@example.com', name: '나', profileImageUrl: null };

let mounts = 0;

/** 막힌 화면 흉내: 마운트되면 센다. 진짜 플러그인 화면은 마운트 즉시 내 송출 키·연동을 서버에서 읽는다 */
function PluginScreen() {
  useEffect(() => {
    mounts += 1;
  }, []);
  return <p>플러그인 화면</p>;
}

function renderAs(relation: 'OWNER' | 'EDITOR' | null) {
  return renderWithProviders(
    <ChannelRelationContext.Provider value={relation}>
      <OwnerOnly title="플러그인">
        <PluginScreen />
      </OwnerOnly>
    </ChannelRelationContext.Provider>,
  );
}

beforeEach(() => {
  mounts = 0;
  window.localStorage.clear();
  useSwitchCurtain.getState().reset();
  useChannelStore.setState({ userId: 7, selected: 3 });
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  stubFetch((url) => (url === '/api/auth/me' ? jsonResponse(200, ME) : jsonResponse(404)));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('OwnerOnly', () => {
  it('남의 채널이면 화면 대신 화면 이름 · 안내 · 「내 채널로 전환」을 보이고, 누르면 내 채널로 돌아간다', async () => {
    const user = userEvent.setup();
    renderAs('EDITOR');

    expect(screen.queryByText('플러그인 화면')).not.toBeInTheDocument();
    // 어느 화면이 막혔는지는 제목으로 말한다
    expect(screen.getByRole('heading', { name: '플러그인' })).toBeInTheDocument();
    expect(screen.getByText('채널 주인만 볼 수 있는 화면이에요')).toBeInTheDocument();
    // 한 번이라도 마운트하면 그 사이 내 키·연동을 읽는다: 그렸다 지우는 구현을 여기서 막는다(감사 3회차 지적 3)
    expect(mounts).toBe(0);

    await user.click(screen.getByRole('button', { name: '내 채널로 전환' }));

    expect(useSwitchCurtain.getState().curtain).toMatchObject({ name: '나', relation: 'OWNER' });
    await waitFor(() =>
      expect(useChannelStore.getState()).toMatchObject({ userId: 7, selected: null }),
    );
  });

  it('내 채널이면 화면 그대로다', () => {
    renderAs('OWNER');
    expect(screen.getByText('플러그인 화면')).toBeInTheDocument();
    expect(mounts).toBe(1);
  });

  it('채널을 모르면(ChannelScope 밖) 막지 않는다', () => {
    renderAs(null);
    expect(screen.getByText('플러그인 화면')).toBeInTheDocument();
  });
});
