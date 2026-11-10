import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { readNotificationsMockMode, useNotificationsMockMode } from './mockNotifications';

const KEY = 'pc-mock-notifications';

afterEach(() => {
  window.sessionStorage.clear();
  window.history.pushState({}, '', '/');
});

describe('가짜 알림 모드(개발 서버 전용)', () => {
  it('읽기는 저장소에 쓰지 않는다 — 렌더 중에 여러 번 불려도 부수효과가 없다', () => {
    window.history.pushState({}, '', '/home?mock=notifications');
    expect(readNotificationsMockMode()).toBe('normal');
    expect(readNotificationsMockMode()).toBe('normal');
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });

  it('구독하면(화면에 쓰이면) 주소 값을 이 탭에 기억해, 주소에서 빠져도 이어진다', () => {
    window.history.pushState({}, '', '/home?mock=notifications-live');
    const { result } = renderHook(() => useNotificationsMockMode());
    expect(result.current).toBe('live');
    expect(window.sessionStorage.getItem(KEY)).toBe('live');

    window.history.pushState({}, '', '/clips/library');
    expect(readNotificationsMockMode()).toBe('live');
  });

  it('?mock=off는 기억한 값을 지운다', () => {
    window.sessionStorage.setItem(KEY, 'normal');
    window.history.pushState({}, '', '/home?mock=off');
    const { result } = renderHook(() => useNotificationsMockMode());
    expect(result.current).toBeNull();
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });
});
