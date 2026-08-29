import Link from 'next/link';
import { ChevronLeft, Redo2, Undo2 } from 'lucide-react';
import { Button, IconButton } from '@/ui';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d 상단 — 편집기 자체 헤더(전역 헤더 대신). 저장·업로드는 전부 목업 동작이다.

export function EditorHeader({
  state,
  showTemplateSave = false,
}: {
  state: ClipEditorMockState;
  /** 템플릿 저장은 시안에서 스튜디오형에만 있다 */
  showTemplateSave?: boolean;
}) {
  return (
    <header className={styles.header}>
      <Link href="/clips" className={styles.backLink} aria-label="보관함으로">
        <ChevronLeft size={17} aria-hidden />
      </Link>
      <div className={styles.headerTitleBlock}>
        <h1 className={styles.headerTitle}>{state.clipTitle}</h1>
        <div className={styles.headerMeta}>
          {state.sourceLabel} · {state.autosaveLabel}
        </div>
      </div>
      <div className={styles.headerHistory}>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="작업 이전으로"
          disabled={!state.canUndo}
          onClick={state.undo}
        >
          <Undo2 size={15} aria-hidden />
        </IconButton>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="작업 앞으로"
          disabled={!state.canRedo}
          onClick={state.redo}
        >
          <Redo2 size={15} aria-hidden />
        </IconButton>
      </div>
      <div className={styles.headerActions}>
        {showTemplateSave ? (
          <Button variant="ghost" size="sm" onClick={state.saveTemplate}>
            템플릿 저장
          </Button>
        ) : null}
        <Button variant="soft" size="sm" onClick={state.saveDraft}>
          편집본 저장
        </Button>
        <Button variant="solid" size="sm" onClick={state.requestUpload}>
          업로드
        </Button>
      </div>
    </header>
  );
}
