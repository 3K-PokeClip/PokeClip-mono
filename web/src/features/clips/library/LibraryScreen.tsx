'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { FolderOpen } from 'lucide-react';
import { EmptyState, Input, Select } from '@/ui';
import { ClipCard, clipCardDomId } from './ClipCard';
import { ClipDetailPanel } from './ClipDetailPanel';
import { DeleteClipDialog } from './DeleteClipDialog';
import { LibraryFilterChips } from './LibraryFilterChips';
import { SORT_OPTIONS, isLibrarySort, statusFor } from './libraryView';
import { useLibraryMockState, type LibraryClip, type LibraryOptions } from './useLibraryMockState';
import styles from './LibraryScreen.module.css';

// 시안 1g — 보관함. 편집기(1d)가 만든 9:16 편집본을 다시 찾는 유일한 자리다(F6, P0).
// 썸네일을 고르면 우측 상세 패널(344u)이 열리고 그리드가 촘촘해진다 · 같은 썸네일을 다시
// 누르면 닫힌다. 그리드는 문서 스크롤로 내려가고 패널은 붙어 있는다(Side와 같다).
//
// 본문 랜드마크는 각 화면이 세운다 — clips/layout은 Side와 감싸는 div까지만 준다.
// 본문 폭은 가두지 않는다: 그리드가 재배치되므로 접힌 사이드바가 남긴 폭을 그대로 쓴다
// (docs/DESIGN_SYSTEM.md 「셸 · 본문 폭」).
//
// 시안에 있으나 여기 없는 것: 스트리머/편집자 시점 토글(핸드오프용 — 시점은 훅 값이다).
export function LibraryScreen(options: LibraryOptions = {}) {
  const {
    now,
    role,
    clips,
    totalCount,
    counts,
    pendingCount,
    chip,
    setChip,
    query,
    setQuery,
    sort,
    setSort,
    selectedId,
    selectedClip,
    select,
    deselect,
    renameClip,
    upload,
    retryRender,
    download,
    remove,
  } = useLibraryMockState(options);
  const open = selectedClip !== null;

  // 닫히는 동안 마지막 편집본을 그대로 그린다 — 내용이 먼저 비면 빈 패널이 미끄러진다.
  // 렌더 중 setState(파생 상태 패턴). 객체 동일성으로 비교해 제목을 고친 뒤에도 최신을 따른다.
  const [lastClip, setLastClip] = useState<LibraryClip | null>(null);
  if (selectedClip && selectedClip !== lastClip) setLastClip(selectedClip);
  const panelClip = selectedClip ?? lastClip;

  const [pendingDelete, setPendingDelete] = useState<LibraryClip | null>(null);
  const gridRef = useRef<HTMLUListElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  /** 카드가 사라진 자리의 대체 포커스 — 목록의 첫 카드, 목록도 비면 늘 서 있는 검색창 */
  const focusFallback = () => {
    const firstCard = gridRef.current?.querySelector<HTMLButtonElement>('button');
    (firstCard ?? searchRef.current)?.focus();
  };

  /**
   * 닫은 카드로 포커스를 돌려준다. 칩·검색으로 그 카드가 목록에서 빠졌으면 자리가 없으므로
   * (선택은 남는 것이 의도다) 대체 자리로 물러선다 — 안 그러면 포커스가 곧 inert가 되는
   * 패널 안에 남아 브라우저가 body로 떨어뜨린다.
   */
  const focusCardOrFallback = (id: string | null) => {
    const card = id ? document.getElementById(clipCardDomId(id)) : null;
    if (card) card.focus();
    else focusFallback();
  };

  const closePanel = () => {
    const id = selectedId;
    deselect();
    focusCardOrFallback(id);
  };

  const confirmDelete = () => {
    if (pendingDelete) {
      remove(pendingDelete.id);
      refocusAfterDelete.current = true;
    }
    setPendingDelete(null);
  };

  // 삭제를 확정하면 선택이 풀려 패널이 inert가 되는데, 대화상자의 FocusScope는 열기 전
  // 포커스였던 패널 안 삭제 버튼으로 되돌린다 — 받을 수 없는 자리라 포커스가 body로 떨어진다.
  // FocusScope의 복원은 언마운트 정리라 이 effect보다 먼저 돈다. 그 뒤에 다시 잡는다.
  const refocusAfterDelete = useRef(false);
  useEffect(() => {
    if (!refocusAfterDelete.current) return;
    refocusAfterDelete.current = false;
    focusFallback();
  });

  const pendingDeleteStatus = pendingDelete ? statusFor(pendingDelete, now) : null;

  return (
    <div className={styles.screen}>
      <main className={styles.main}>
        <div className={styles.header}>
          <h1 className={styles.title}>보관함</h1>
          <div className={styles.tools}>
            <Input
              type="search"
              size="sm"
              ref={searchRef}
              className={styles.search}
              aria-label="편집본 검색"
              placeholder="검색"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
            <Select
              size="sm"
              className={styles.sort}
              aria-label="정렬"
              options={SORT_OPTIONS}
              value={sort}
              onValueChange={(value) => {
                if (isLibrarySort(value)) setSort(value);
              }}
            />
          </div>
        </div>

        {totalCount > 0 ? (
          <div className={styles.chipRow}>
            <LibraryFilterChips role={role} chip={chip} counts={counts} onChange={setChip} />
            {/* 스트리머에겐 승인 대기 칩이 없다 — 검토는 승인 대기함의 일이라 배너로 보낸다(시안 1g ⑤) */}
            {role === 'streamer' && pendingCount > 0 ? (
              <Link href="/clips/approvals" className={styles.pendingBanner}>
                <span className={styles.pendingDot} aria-hidden="true" />
                승인 대기 {pendingCount}건{' '}
                <span className={styles.pendingHint}>편집자 요청은 승인 대기함에서 검토해요</span>{' '}
                <span className={styles.pendingHint}>열기 →</span>
              </Link>
            ) : null}
          </div>
        ) : null}

        {totalCount === 0 ? (
          <EmptyState
            icon={<FolderOpen size={21} />}
            title="아직 보관한 편집본이 없어요"
            description="라이브 대시보드의 하이라이트 카드나 지난 방송에서 클립을 만들면 여기에 쌓여요. 편집본은 계속 보관되지만 원본 VOD는 60일 뒤 만료돼요."
          />
        ) : clips.length === 0 ? (
          // 「아직 없다」와 「조건에 없다」는 다른 말이다 — 빈 상태 카드를 재사용하면 거짓이 된다.
          // 목록이 통째로 사라지는 자리라 role="status"로 낭독시킨다(VodListScreen 선례).
          <p className={styles.filterEmpty} role="status">
            조건에 맞는 편집본이 없어요.
          </p>
        ) : (
          <ul ref={gridRef} className={styles.grid} aria-label="편집본 목록" data-panel-open={open}>
            {clips.map((clip) => (
              <ClipCard
                key={clip.id}
                clip={clip}
                status={statusFor(clip, now)}
                selected={clip.id === selectedId}
                onToggle={select}
              />
            ))}
          </ul>
        )}
      </main>

      {/* 닫힌 패널은 폭 0으로 접히고 inert다 — 보이지 않는 버튼에 Tab이 들어가지 않는다 */}
      <aside className={styles.panel} aria-label="편집본 상세" data-open={open} inert={!open}>
        <div className={styles.panelInner}>
          {panelClip ? (
            <ClipDetailPanel
              clip={panelClip}
              status={statusFor(panelClip, now)}
              role={role}
              now={now}
              onDeselect={closePanel}
              onTitleChange={(title) => renameClip(panelClip.id, title)}
              onUpload={() => upload(panelClip.id)}
              onRetryRender={() => retryRender(panelClip.id)}
              onDownload={() => download(panelClip.id)}
              onDelete={() => setPendingDelete(panelClip)}
            />
          ) : null}
        </div>
      </aside>

      <DeleteClipDialog
        open={pendingDelete !== null}
        published={pendingDeleteStatus === 'published' || pendingDeleteStatus === 'expired'}
        onCancel={() => setPendingDelete(null)}
        onConfirm={confirmDelete}
      />
    </div>
  );
}
