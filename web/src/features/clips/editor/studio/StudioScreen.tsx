'use client';

import { useEffect } from 'react';
import { EditorHeader } from '../EditorHeader';
import { PreviewCanvas } from '../PreviewCanvas';
import { TransportBar } from '../TransportBar';
import { MultitrackTimeline } from './MultitrackTimeline';
import { ToolPanel } from './ToolPanel';
import { ToolRail } from './ToolRail';
import styles from './StudioScreen.module.css';
import { editorIntentForKey } from '../editorKeys';
import { useClipEditorMockState, type ClipEditorOptions } from '../useClipEditorMockState';

// 시안 1d-a 클립 편집기(스튜디오형). 전폭 자체 헤더를 가지므로 ScreenContainer를 쓰지 않는다
// (라이브 대시보드 선례). 데이터·동작은 전부 useClipEditorMockState 뒤에 있다.

/**
 * 글자를 받는 곳 — 어떤 편집 단축키도 여기서는 비켜선다.
 * (⌘Z는 브라우저·OS의 실행취소가 먼저다)
 */
const TEXT_ENTRY = 'input, textarea, select, [contenteditable]';

/**
 * 키에 자기 동작이 있는 위젯 — Space·화살표·I/O만 양보한다.
 * 버튼을 통째로 막으면 안 된다: 클릭하면 포커스가 버튼에 남아,
 * 범례가 광고하는 ⌘Z가 그 뒤로 영영 먹지 않는다.
 */
const KEY_OWNING = 'button, [role="slider"]';

export function StudioScreen(options: ClipEditorOptions = {}) {
  const state = useClipEditorMockState(options);
  const { togglePlay, seekBy, markIn, markOut, undo, redo } = state;

  useEffect(() => {
    function onKeyDown(event: globalThis.KeyboardEvent) {
      const target = event.target instanceof Element ? event.target : null;
      if (target?.closest(TEXT_ENTRY) != null) return;
      const intent = editorIntentForKey(event);
      if (intent === null) return;
      // Space가 버튼을 누르는 대신 재생을 토글하면 키보드 사용자가 아무 버튼도 못 누른다.
      // 되돌리기만은 위젯 위에서도 통과시킨다 — 버튼에 포커스가 남는 것이 정상 흐름이라
      // 여기서 막으면 ⌘Z가 사실상 죽는다.
      // 위젯이 제 것으로 쓰는 키만 양보한다: Space는 버튼을 누르고, 화살표는
      // roving·슬라이더가 값을 옮긴다. ⌘Z·I·O는 버튼에 네이티브 동작이 없어
      // 여기서 막으면 범례가 광고한 조작이 흔한 흐름에서 죽는다.
      const widgetOwns = intent.kind === 'togglePlay' || intent.kind === 'seekBy';
      if (widgetOwns && target?.closest(KEY_OWNING) != null) return;
      event.preventDefault();
      switch (intent.kind) {
        case 'togglePlay':
          togglePlay();
          break;
        case 'seekBy':
          seekBy(intent.seconds);
          break;
        case 'markIn':
          markIn();
          break;
        case 'markOut':
          markOut();
          break;
        case 'undo':
          undo();
          break;
        case 'redo':
          redo();
          break;
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [togglePlay, seekBy, markIn, markOut, undo, redo]);

  return (
    <div className={styles.screen}>
      <EditorHeader state={state} showTemplateSave />
      <main className={styles.body} data-panel-side={state.panelSide}>
        <ToolRail
          tools={state.toolOptions}
          activeTool={state.activeTool}
          onSelect={state.setActiveTool}
          panelSide={state.panelSide}
          panelSideTip={state.panelSideTip}
          onTogglePanelSide={state.togglePanelSide}
        />
        <ToolPanel state={state} />
        <div className={styles.previewColumn}>
          <PreviewCanvas state={state} />
          <TransportBar state={state} showRangeLength />
        </div>
      </main>
      <MultitrackTimeline state={state} />
    </div>
  );
}
