import { AudioSection } from '../sections/AudioSection';
import { BgmSfxSection } from '../sections/BgmSfxSection';
import { ImageSection } from '../sections/ImageSection';
import { RangeSection } from '../sections/RangeSection';
import { SubtitleSection } from '../sections/SubtitleSection';
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
      {state.activeTool === 'range' ? <RangeSection state={state} /> : null}
      {state.activeTool === 'subtitle' ? <SubtitleSection state={state} /> : null}
      {state.activeTool === 'audio' ? (
        <AudioSection state={state} kinds={['mic', 'game']} title="오디오" />
      ) : null}
      {state.activeTool === 'bgm' ? <BgmSfxSection state={state} /> : null}
      {state.activeTool === 'image' ? <ImageSection state={state} /> : null}
    </div>
  );
}
