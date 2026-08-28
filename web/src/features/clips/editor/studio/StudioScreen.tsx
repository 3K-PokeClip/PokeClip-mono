'use client';

import { useEffect } from 'react';
import { EditorHeader } from '../EditorHeader';
import { PreviewCanvas } from '../PreviewCanvas';
import { TransportBar } from '../TransportBar';
import { MultitrackTimeline } from './MultitrackTimeline';
import { ToolPanel } from './ToolPanel';
import { ToolRail } from './ToolRail';
import styles from './StudioScreen.module.css';
import { editorIntentForKey, type EditorIntent } from '../editorKeys';
import { useClipEditorMockState, type ClipEditorOptions } from '../useClipEditorMockState';

// 시안 1d-a 클립 편집기(스튜디오형). 전폭 자체 헤더를 가지므로 ScreenContainer를 쓰지 않는다
// (라이브 대시보드 선례). 데이터·동작은 전부 useClipEditorMockState 뒤에 있다.

/**
 * 글자를 받는 곳 — 어떤 편집 단축키도 여기서는 비켜선다.
 * (⌘Z는 브라우저·OS의 실행취소가 먼저다)
 *
 * 체크박스·라디오는 뺀다. DS Switch가 `<input type="checkbox">`라, 통째로 잡으면
 * 스위치를 한 번 누른 뒤로 ⌘Z·I·O가 영영 먹지 않는다.
 */
const TEXT_ENTRY =
  'input:not([type="checkbox"]):not([type="radio"]), textarea, select, [contenteditable]';

/**
 * 어떤 위젯이 어떤 키를 제 것으로 쓰는가.
 *
 * 위젯 단위로 뭉뚱그리면 안 쓰는 키까지 양보한다 — 버튼은 화살표를 안 쓰고
 * 슬라이더는 Space를 안 쓴다. 뭉뚱그린 탓에 「버튼 누른 뒤 시킹이 죽고,
 * 슬라이더 위에서 재생이 안 되는」 상태가 났다. 키마다 주인을 적는다.
 *
 * 여기 없는 키(⌘Z·I·O)는 어떤 위젯도 제 것으로 쓰지 않으므로 늘 통과한다.
 */
const KEY_OWNERS: Partial<Record<EditorIntent['kind'], string>> = {
  // Space는 버튼·스위치를 누른다 — 가로채면 키보드로 아무것도 못 누른다.
  // 역할로 가른다: 구간 핸들은 <button role="slider">라 태그로 고르면 같이 걸려,
  // 슬라이더 위에서 Space가 죽는다.
  togglePlay:
    'button:not([role]), [role="button"], [role="switch"], [role="tab"], [role="radio"]',
  // 화살표는 슬라이더가 값을, roving 묶음이 선택을 옮긴다
  seekBy: '[role="slider"], [role="radiogroup"], [role="tablist"]',
};

export function StudioScreen(options: ClipEditorOptions = {}) {
  const state = useClipEditorMockState(options);
  const { togglePlay, seekBy, markIn, markOut, undo, redo } = state;

  useEffect(() => {
    function onKeyDown(event: globalThis.KeyboardEvent) {
      const target = event.target instanceof Element ? event.target : null;
      if (target?.closest(TEXT_ENTRY) != null) return;
      const intent = editorIntentForKey(event);
      if (intent === null) return;
      // 이 키의 주인이 포커스 안에 있으면 그쪽에 넘긴다
      const owner = KEY_OWNERS[intent.kind];
      if (owner !== undefined && target?.closest(owner) != null) return;
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
