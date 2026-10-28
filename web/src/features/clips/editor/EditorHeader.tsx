import { Redo2, Undo2 } from 'lucide-react';
import { Button, IconButton, Progress } from '@/ui';
import { ClipStatusPill } from './ClipStatusPill';
import { EditorBackLink } from './EditorBackLink';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d 상단 — 편집기 자체 헤더(전역 헤더 대신). 저장·업로드는 컨테이너가 준 동작으로 간다(없으면 목업 안내).

export function EditorHeader({ state }: { state: ClipEditorMockState }) {
  const { busyAction, reloading } = state;
  // 보내는 동안 컨테이너는 저장·주문을 둘 다 받지 않는다(같은 요청을 두 번 보내지 않게): 누른 버튼만 돌고 다른 쪽은 끈다.
  // 다른 편집 대상을 읽는 중이면 이 화면은 곧 갈아 끼워지므로 고치는 버튼을 모두 끈다 — 이전 화면으로는 나갈 수 있다(POK-444)
  const locked = busyAction !== null || reloading;
  return (
    <header className={styles.header}>
      <EditorBackLink />
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
          disabled={!state.canUndo || reloading}
          onClick={state.undo}
        >
          <Undo2 size={15} aria-hidden />
        </IconButton>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="작업 앞으로"
          disabled={!state.canRedo || reloading}
          onClick={state.redo}
        >
          <Redo2 size={15} aria-hidden />
        </IconButton>
      </div>
      <div className={styles.headerActions}>
        {/* 요청한 영상의 상태는 누른 버튼 바로 옆에 둔다(POK-492). 다른 편집 대상을 읽는 중이면 곧 사라질 화면의 것이라 뺀다 */}
        {state.clipStatus !== null && !reloading ? (
          <ClipStatusPill
            view={state.clipStatus.view}
            versionLabel={state.clipStatus.versionLabel}
            retrying={state.clipStatus.retrying}
            onRetryUpload={state.clipStatus.onRetryUpload}
          />
        ) : null}
        {/* 도는 동안 라벨은 자리만 지켜 폭이 튀지 않는다(.busyButton). 라벨이 시각으로 숨으므로 이름은 aria-label이 댄다 */}
        <Button
          variant="soft"
          size="sm"
          className={styles.busyButton}
          loading={busyAction === 'save'}
          disabled={locked}
          aria-label={busyAction === 'save' ? '편집본 저장 중' : undefined}
          onClick={state.saveDraft}
        >
          편집본 저장
        </Button>
        <Button
          variant="solid"
          size="sm"
          className={styles.busyButton}
          loading={busyAction === 'render'}
          disabled={locked}
          aria-label={busyAction === 'render' ? `${state.uploadLabel} 요청 중` : undefined}
          onClick={state.requestUpload}
        >
          {state.uploadLabel}
        </Button>
      </div>
      {reloading ? (
        <Progress
          className={styles.headerReloadBar}
          size="sm"
          value={null}
          label="편집 대상을 다시 불러오는 중"
        />
      ) : null}
    </header>
  );
}
