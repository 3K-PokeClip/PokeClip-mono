import { AudioSection } from '../sections/AudioSection';
import { ImageSection } from '../sections/ImageSection';
import { LayoutSection } from '../sections/LayoutSection';
import { SubtitleSection } from '../sections/SubtitleSection';
import { TextSection } from '../sections/TextSection';
import styles from './StudioScreen.module.css';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 1d-a 296px 패널 — 레일에서 고른 도구의 내용만 갈아 끼운다.

export function ToolPanel({ state }: { state: ClipEditorMockState }) {
  return (
    <div
      className={styles.toolPanel}
      role="tabpanel"
      id="editor-tool-panel"
      aria-labelledby={`editor-tool-${state.activeTool}`}
      tabIndex={-1}
    >
      {state.activeTool === 'layout' ? <LayoutSection state={state} /> : null}
      {state.activeTool === 'subtitle' ? <SubtitleSection state={state} /> : null}
      {state.activeTool === 'text' ? <TextSection /> : null}
      {state.activeTool === 'audio' ? (
        <AudioSection state={state} kinds={['mic', 'game', 'bgm', 'sfx']} title="오디오" />
      ) : null}
      {state.activeTool === 'image' ? <ImageSection /> : null}
    </div>
  );
}
