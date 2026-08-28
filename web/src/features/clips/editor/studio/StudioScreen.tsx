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

/** 전역 단축키를 흘려보낼 대상 — 여기 포커스가 있으면 그 요소의 키 조작이 우선이다 */
const INTERACTIVE = 'input, textarea, select, button, [role="slider"], [contenteditable]';

export function StudioScreen(options: ClipEditorOptions = {}) {
  const state = useClipEditorMockState(options);
  const { togglePlay, seekBy, markIn, markOut, undo, redo } = state;

  useEffect(() => {
    function onKeyDown(event: globalThis.KeyboardEvent) {
      // 버튼·슬라이더 위에서는 그 요소의 키가 먼저다 — Space가 버튼을 누르는 대신
      // 재생을 토글해버리면 키보드 사용자가 아무 버튼도 못 누른다 (GlassPlayer 선례).
      if (event.target instanceof Element && event.target.closest(INTERACTIVE) !== null) return;
      const intent = editorIntentForKey(event);
      if (intent === null) return;
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
