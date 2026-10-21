'use client';

import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { pluginReleaseQueryOptions } from '@/api/pluginRelease';
import type { PluginRelease } from '@/lib/pluginRelease/types';

// 다운로드 배너·창이 그릴 상태 (POK-379). 「설치됨·연결됨」은 없다 — 웹은 모른다(POK-377).
export type PluginReleaseViewState =
  | { kind: 'loading' }
  | { kind: 'ready'; release: PluginRelease }
  | { kind: 'unavailable' }
  | { kind: 'error'; retry: () => void };

export function usePluginReleaseState(): PluginReleaseViewState {
  const query = useQuery(pluginReleaseQueryOptions);
  const { refetch } = query;
  const retry = useCallback(() => void refetch(), [refetch]);

  // 한 번 읽은 값은 재조회 실패에 오류로 갈아끼우지 않는다 — 같은 화면 useStreamKeyState와 같은 규칙.
  // 「아무것도 모르는」 최초 실패만 오류로 그린다.
  if (query.data) {
    return query.data.status === 'ready'
      ? { kind: 'ready', release: query.data.release }
      : { kind: 'unavailable' };
  }
  if (query.isError) return { kind: 'error', retry };
  return { kind: 'loading' };
}
