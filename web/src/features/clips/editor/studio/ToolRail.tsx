import { Captions, Image as ImageIcon, Music, Scissors, SlidersHorizontal } from 'lucide-react';
import type { ComponentType } from 'react';
import { RovingProvider, useRovingItem } from '@/ui/primitives';
import styles from './StudioScreen.module.css';
import type { EditorTool } from '../useClipEditorMockState';

// 시안 1d-a 왼쪽 58px 레일 — 누르면 패널만 바뀌고 타임라인은 그대로다.
// 탭 시맨틱(tablist/tab)에 roving tabindex를 얹어 화살표로 도구를 옮길 수 있게 한다.

const TOOL_ICONS: Record<EditorTool, ComponentType<{ size?: number }>> = {
  range: Scissors,
  subtitle: Captions,
  audio: SlidersHorizontal,
  bgm: Music,
  image: ImageIcon,
};

function ToolButton({
  tool,
  label,
  selected,
  onSelect,
}: {
  tool: EditorTool;
  label: string;
  selected: boolean;
  onSelect: () => void;
}) {
  const roving = useRovingItem(tool);
  const Icon = TOOL_ICONS[tool];
  return (
    <button
      ref={roving.ref}
      type="button"
      role="tab"
      id={`editor-tool-${tool}`}
      aria-selected={selected}
      aria-controls="editor-tool-panel"
      tabIndex={roving.tabIndex}
      onKeyDown={roving.onKeyDown}
      onFocus={roving.onFocus}
      className={styles.toolButton}
      onClick={onSelect}
    >
      <Icon size={17} />
      {label}
    </button>
  );
}

export function ToolRail({
  tools,
  activeTool,
  onSelect,
}: {
  tools: readonly { value: EditorTool; label: string }[];
  activeTool: EditorTool;
  onSelect: (tool: EditorTool) => void;
}) {
  return (
    <RovingProvider
      activeValue={activeTool}
      onActiveChange={(value) => onSelect(value as EditorTool)}
      orientation="vertical"
    >
      <div className={styles.toolRail} role="tablist" aria-label="편집 도구" aria-orientation="vertical">
        {tools.map((tool) => (
          <ToolButton
            key={tool.value}
            tool={tool.value}
            label={tool.label}
            selected={tool.value === activeTool}
            onSelect={() => onSelect(tool.value)}
          />
        ))}
      </div>
    </RovingProvider>
  );
}
