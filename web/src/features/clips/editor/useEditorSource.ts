'use client';

// 편집기 소스 사이드카와 파형을 받아 온다 (POK-238).
//
// apiFetch 를 쓰지 않는다 — 정적 스텁(nginx)에는 Bearer 헤더가 붙는 순간 preflight(OPTIONS)가
// 날아가는데 정적 서버는 그것을 처리하지 않는다. 로그인이 필요 없는 자원이라 생 fetch 로 간다
// (api/auth.ts 의 login·logout 이 같은 이유로 생 fetch 를 쓴다).
//
// 서버 API(POK-122)가 열리면 여기 두 fetch 만 api/ 쪽 함수로 갈아끼운다.

import { useQueries, useQuery } from '@tanstack/react-query';
import {
  parseAudioPeaks,
  parseEditorSource,
  type AudioPeaks,
  type EditorMediaSource,
} from './editorSource';

async function fetchJson(url: string): Promise<unknown> {
  const res = await fetch(url, { credentials: 'omit' });
  if (!res.ok) throw new Error(`소스를 받지 못했습니다 (${res.status}) — ${url}`);
  return res.json() as Promise<unknown>;
}

export function editorSourceQueryOptions(url: string) {
  return {
    queryKey: ['editorSource', url] as const,
    queryFn: async () => parseEditorSource(await fetchJson(url), url),
    // 로컬 파일이라 열려 있는 동안 바뀌지 않는다. 다시 만들면 새로고침이 답이다.
    staleTime: Number.POSITIVE_INFINITY,
  };
}

export function audioPeaksQueryOptions(url: string) {
  return {
    queryKey: ['editorPeaks', url] as const,
    queryFn: async () => parseAudioPeaks(await fetchJson(url)),
    staleTime: Number.POSITIVE_INFINITY,
  };
}

export type EditorSourceState =
  /** env 가 비어 있다 — 목업으로 간다 */
  | { status: 'off' }
  | { status: 'loading' }
  | { status: 'error'; message: string; retry: () => void }
  | {
      status: 'ready';
      source: EditorMediaSource;
      /** trackId → 파형. 아직 안 온 트랙은 없다 — 레인이 자리 표시자로 남는다 */
      peaks: ReadonlyMap<number, AudioPeaks>;
    };

/**
 * 사이드카가 오면 준비 완료로 친다. 파형은 늦게 와도 편집을 막지 않는다 —
 * 없으면 레인이 지금처럼 자리 표시자로 남을 뿐이고, 그것 때문에 화면 전체를 붙잡아 두면
 * 구간을 고르는 일이 파형을 기다리는 일이 된다.
 */
export function useEditorSource(url: string | null): EditorSourceState {
  const source = useQuery({
    ...editorSourceQueryOptions(url ?? ''),
    enabled: url !== null,
  });

  const peakQueries = useQueries({
    queries: (source.data?.audioTracks ?? []).map((track) =>
      audioPeaksQueryOptions(track.peaksUrl),
    ),
  });

  if (url === null) return { status: 'off' };
  if (source.isPending) return { status: 'loading' };
  if (source.isError) {
    return {
      status: 'error',
      message: source.error instanceof Error ? source.error.message : '소스를 읽지 못했습니다',
      retry: () => void source.refetch(),
    };
  }

  const peaks = new Map<number, AudioPeaks>();
  source.data.audioTracks.forEach((track, index) => {
    const data = peakQueries[index]?.data;
    if (data !== undefined) peaks.set(track.trackId, data);
  });

  return { status: 'ready', source: source.data, peaks };
}
