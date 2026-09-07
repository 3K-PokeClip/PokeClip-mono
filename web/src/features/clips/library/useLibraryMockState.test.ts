import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { withToastProvider } from '@/test/testProviders';
import { useLibraryMockState, type LibraryOptions } from './useLibraryMockState';

function renderLibrary(options?: LibraryOptions) {
  return renderHook(() => useLibraryMockState(options), { wrapper: withToastProvider });
}

function ids(result: ReturnType<typeof renderLibrary>['result']) {
  return result.current.clips.map((clip) => clip.id);
}

describe('useLibraryMockState', () => {
  it('시안 1g 기본값으로 시작한다 — 스트리머 · 8건 · 최근 편집순 · 전체 · 미선택', () => {
    const { result } = renderLibrary();

    expect(result.current.role).toBe('streamer');
    expect(result.current.totalCount).toBe(8);
    expect(result.current.sort).toBe('edited');
    expect(result.current.chip).toBe('all');
    expect(result.current.query).toBe('');
    expect(result.current.selectedId).toBeNull();
    expect(result.current.selectedClip).toBeNull();
    // 최근 편집순 — 시안의 카드 순서(id 순)가 아니라 editedAt이 정한다
    expect(ids(result)).toEqual([
      'lib2-3',
      'lib2-7',
      'lib2-2',
      'lib2-1',
      'lib2-5',
      'lib2-8',
      'lib2-4',
      'lib2-6',
    ]);
  });

  it('칩 수를 시점별로 센다 — 스트리머 작업 중 4 · 업로드 대기 2 · 발행됨 2 / 편집자 작업 중 3 · 반려됨 1', () => {
    const streamer = renderLibrary();
    expect(streamer.result.current.counts).toEqual({
      all: 8,
      working: 4,
      ready: 2,
      rejected: 0,
      published: 2,
    });

    const editor = renderLibrary({ role: 'editor' });
    expect(editor.result.current.counts).toEqual({
      all: 8,
      working: 3,
      ready: 2,
      rejected: 1,
      published: 2,
    });
  });

  it('승인 대기 수를 배너용으로 준다', () => {
    const { result } = renderLibrary();
    expect(result.current.pendingCount).toBe(1);
  });

  it('같은 카드를 두 번 고르면 해제된다', () => {
    const { result } = renderLibrary();

    act(() => result.current.select('lib2-1'));
    expect(result.current.selectedId).toBe('lib2-1');
    expect(result.current.selectedClip?.title).toBe('보스 막타 · 역전 순간');

    act(() => result.current.select('lib2-1'));
    expect(result.current.selectedId).toBeNull();
    expect(result.current.selectedClip).toBeNull();
  });

  it('다른 카드를 고르면 선택이 옮겨 가고 deselect가 푼다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });

    act(() => result.current.select('lib2-2'));
    expect(result.current.selectedId).toBe('lib2-2');

    act(() => result.current.deselect());
    expect(result.current.selectedId).toBeNull();
  });

  it('업로드는 스트리머면 발행됨, 편집자면 승인 대기로 옮긴다', () => {
    const streamer = renderLibrary({ selectedId: 'lib2-2' });
    act(() => streamer.result.current.upload('lib2-2'));
    expect(streamer.result.current.selectedClip?.status).toBe('published');
    // 목업 업로드로 발행된 것은 갈 곳(유튜브 주소)이 없다
    expect(streamer.result.current.selectedClip?.youtubeUrl).toBeUndefined();

    const editor = renderLibrary({ role: 'editor', selectedId: 'lib2-2' });
    act(() => editor.result.current.upload('lib2-2'));
    expect(editor.result.current.selectedClip?.status).toBe('pending');
    expect(editor.result.current.pendingCount).toBe(2);
  });

  it('업로드 대기가 아닌 편집본은 업로드해도 그대로다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });
    act(() => result.current.upload('lib2-1'));
    expect(result.current.selectedClip?.status).toBe('editing');
  });

  it('렌더 재시도는 업로드 대기로 돌린다 — 실패한 것만, 길이는 여전히 모른다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-8' });
    act(() => result.current.retryRender('lib2-8'));
    expect(result.current.selectedClip?.status).toBe('ready');
    expect(result.current.selectedClip?.durationSec).toBeNull();

    act(() => result.current.select('lib2-1'));
    act(() => result.current.retryRender('lib2-1'));
    expect(result.current.selectedClip?.status).toBe('editing');
  });

  it('삭제는 목록에서 빼고 선택도 푼다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-2' });
    act(() => result.current.remove('lib2-2'));

    expect(result.current.totalCount).toBe(7);
    expect(ids(result)).not.toContain('lib2-2');
    expect(result.current.selectedId).toBeNull();
    expect(result.current.counts.ready).toBe(1);
  });

  it('다른 편집본을 지우면 선택은 남는다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });
    act(() => result.current.remove('lib2-2'));
    expect(result.current.selectedId).toBe('lib2-1');
  });

  it('제목은 입력마다 저장된다 — selectedClip에도 바로 비친다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });
    act(() => result.current.renameClip('lib2-1', '보스 막타'));
    expect(result.current.selectedClip?.title).toBe('보스 막타');
    expect(result.current.clips.find((c) => c.id === 'lib2-1')?.title).toBe('보스 막타');
  });

  it('검색·칩·정렬이 함께 걸린다 — 「랭크」 검색 + 업로드 대기 칩 → 1건', () => {
    const { result } = renderLibrary();

    act(() => result.current.setQuery('랭크'));
    expect(ids(result)).toEqual(['lib2-7']);
    // 칩 수는 검색과 무관하다 — 재고를 센다
    expect(result.current.counts.all).toBe(8);

    act(() => result.current.setChip('ready'));
    expect(ids(result)).toEqual(['lib2-7']);

    act(() => result.current.setQuery('보스'));
    expect(ids(result)).toEqual([]);
    expect(result.current.totalCount).toBe(8);
  });

  it('만료 임박순은 원본이 가장 먼저 사라질 것부터, 이미 만료된 것은 마지막이다', () => {
    const { result } = renderLibrary();
    act(() => result.current.setSort('expiry'));

    const order = ids(result);
    expect(order[0]).toBe('lib2-4'); // 7월 22일 라이브 — D-18
    expect(order[order.length - 1]).toBe('lib2-6'); // 5월 — 만료됨
  });

  it('clips를 주입하면 totalCount가 그것을 따른다 — 빈 배열이면 0', () => {
    const { result } = renderLibrary({ clips: [] });
    expect(result.current.totalCount).toBe(0);
    expect(result.current.clips).toEqual([]);
    expect(result.current.pendingCount).toBe(0);
  });
});
