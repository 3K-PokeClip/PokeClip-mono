import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThemeProvider } from './ThemeProvider';
import { useTheme } from './useTheme';

function Probe() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  return (
    <div>
      <span data-testid="theme">{theme}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
      <button onClick={() => setTheme('light')}>light</button>
      <button onClick={() => setTheme('system')}>system</button>
    </div>
  );
}

/** jsdom에는 matchMedia가 없다 — OS 테마를 바꿔 가며 change 이벤트를 쏠 수 있는 가짜. */
function mockSystemTheme(initial: 'light' | 'dark') {
  const state = { light: initial === 'light' };
  const listeners = new Set<() => void>();
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      get matches() {
        return state.light;
      },
      addEventListener: (_: string, l: () => void) => listeners.add(l),
      removeEventListener: (_: string, l: () => void) => listeners.delete(l),
    })),
  );
  return (next: 'light' | 'dark') =>
    act(() => {
      state.light = next === 'light';
      listeners.forEach((l) => l());
    });
}

function storageEvent(key: string | null, newValue: string | null) {
  act(() => {
    window.dispatchEvent(new StorageEvent('storage', { key, newValue }));
  });
}

const htmlTheme = () => document.documentElement.dataset.theme;

beforeEach(() => {
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ThemeProvider', () => {
  it('저장값이 없으면 기본값(dark)을 쓰고 <html data-theme>에 반영한다', () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('theme')).toHaveTextContent('dark');
    expect(htmlTheme()).toBe('dark');
  });

  it('저장된 선택을 복원하고, 잘못된 저장값은 기본값으로 본다', () => {
    localStorage.setItem('pc-theme', 'light');
    const { unmount } = render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(htmlTheme()).toBe('light');
    unmount();

    localStorage.setItem('pc-theme', 'sepia');
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('theme')).toHaveTextContent('dark');
  });

  it('고르면 저장하고 바로 반영한다', () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByText('light'));
    expect(localStorage.getItem('pc-theme')).toBe('light');
    expect(htmlTheme()).toBe('light');
  });

  it('저장이 막혀도 지금 탭은 바뀐다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByText('light'));
    expect(htmlTheme()).toBe('light');
  });

  it('system은 OS 테마를 따라가고, 명시한 선택은 OS 변경을 무시한다', () => {
    const setOs = mockSystemTheme('light');
    render(
      <ThemeProvider defaultTheme="system">
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('resolved')).toHaveTextContent('light');
    setOs('dark');
    expect(htmlTheme()).toBe('dark');

    fireEvent.click(screen.getByText('light'));
    setOs('dark');
    expect(htmlTheme()).toBe('light');
  });

  it('다른 탭에서 바꾸거나 지우면 따라가고, 무관한 키는 무시한다', () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    storageEvent('pc-theme', 'light');
    expect(htmlTheme()).toBe('light');

    storageEvent('pokeclip-auth', 'x');
    expect(htmlTheme()).toBe('light');

    storageEvent('pc-theme', null);
    expect(htmlTheme()).toBe('dark');

    storageEvent('pc-theme', 'light');
    storageEvent(null, null); // localStorage.clear()
    expect(htmlTheme()).toBe('dark');
  });

  it('영속을 끄면 저장소를 읽지도 쓰지도 않고 다른 탭도 따라가지 않는다', () => {
    localStorage.setItem('pc-theme', 'light');
    const getItem = vi.spyOn(Storage.prototype, 'getItem');
    render(
      <ThemeProvider enablePersistence={false}>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('theme')).toHaveTextContent('dark');
    expect(getItem).not.toHaveBeenCalledWith('pc-theme');

    fireEvent.click(screen.getByText('system'));
    expect(localStorage.getItem('pc-theme')).toBe('light');

    storageEvent('pc-theme', 'light');
    expect(screen.getByTestId('theme')).toHaveTextContent('system');
  });
});
