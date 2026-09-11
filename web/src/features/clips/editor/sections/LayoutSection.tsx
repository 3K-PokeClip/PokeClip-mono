import { Slider, Switch } from '@/ui';
import { RovingProvider, useRovingItem } from '@/ui/primitives';
import styles from './sections.module.css';
import { DEFAULT_BLUR_STRENGTH, type EditorLayout } from '../editorLayout';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 시안 갱신분(2026-09-08)의 레이아웃 패널 — 레일 맨 위 「레이아웃」 도구가 여는 자리.
// 5종 중 하나를 고르고, 분할이면 지분과 경계선을, 중앙이면 여백 채우기를, 크롭이면 작은 화면
// 테두리를 함께 정한다. 크롭의 작은 화면 자리·비율은 여기서 정하지 않는다 — 원본 판에서 프레임과
// 자리의 꼭짓점을 직접 잡는다. 자막 위치는 여기가 아니라 자막 패널에 있다(시안이 옮겼다).

/** 카드 한 장. 라디오 묶음이라 화살표로 옮겨 다닐 수 있어야 한다 (도구 레일과 같은 규약) */
function LayoutCard({
  value,
  label,
  hint,
  selected,
  onSelect,
}: {
  value: EditorLayout;
  label: string;
  hint: string;
  selected: boolean;
  onSelect: () => void;
}) {
  const roving = useRovingItem(value);
  return (
    <button
      ref={roving.ref}
      type="button"
      role="radio"
      aria-checked={selected}
      tabIndex={roving.tabIndex}
      onKeyDown={roving.onKeyDown}
      onFocus={roving.onFocus}
      className={styles.layoutRow}
      onClick={onSelect}
    >
      {/* 어떤 모양이 되는지 그림으로 — 라벨만으로는 「중앙」과 「크롭」이 안 갈린다 (시안 diag) */}
      <span className={styles.layoutIcon} aria-hidden>
        <span className={styles.layoutDiagram} data-mode={value}>
          <span className={styles.layoutFill} data-slot="1" />
          <span className={styles.layoutFill} data-slot="2" />
        </span>
      </span>
      <span className={styles.layoutText}>
        <span className={styles.layoutLabel}>{label}</span>
        <span className={styles.layoutHint}>{hint}</span>
      </span>
    </button>
  );
}

/** 칩 한 줄 — 시안의 chip(). 분할 비율 · 여백 채우기 · 테두리 굵기가 같은 모양이다 */
function ChipGroup<T extends string | number>({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: readonly { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div className={styles.ratioRow} role="radiogroup" aria-label={label}>
      {options.map((option) => (
        <button
          key={String(option.value)}
          type="button"
          role="radio"
          aria-checked={option.value === value}
          className={styles.ratioChip}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/** 색 고르기 — 시안: 22px 원형 스와치 넷 + 「직접」 네이티브 색 입력. DS 에 색 선택기가 없다 */
function SwatchPicker({
  label,
  presets,
  value,
  onChange,
}: {
  label: string;
  presets: readonly { value: string; label: string }[];
  value: string;
  onChange: (color: string) => void;
}) {
  const current = value.toLowerCase();
  return (
    <div className={styles.swatchRow}>
      <div className={styles.swatchGroup} role="radiogroup" aria-label={label}>
        {presets.map((preset) => (
          <button
            key={preset.value}
            type="button"
            role="radio"
            aria-checked={current === preset.value}
            aria-label={preset.label}
            title={preset.label}
            className={styles.swatch}
            style={{ background: preset.value }}
            onClick={() => onChange(preset.value)}
          />
        ))}
      </div>
      <label className={styles.swatchCustom}>
        <span className={styles.hint}>직접</span>
        <input
          type="color"
          aria-label={`${label} 직접 고르기`}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      </label>
    </div>
  );
}

export function LayoutSection({ state }: { state: ClipEditorMockState }) {
  const { centerFill, pipBorder } = state;
  return (
    <section className={styles.section} aria-label="레이아웃">
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>레이아웃</span>
        <span className={styles.hint}>원본에서 영역을 잡아요</span>
      </div>

      <RovingProvider
        activeValue={state.layout}
        onActiveChange={(value) => state.setLayout(value as EditorLayout)}
        orientation="vertical"
      >
        <div className={styles.layoutList} role="radiogroup" aria-label="레이아웃 종류">
          {state.layoutOptions.map((option) => (
            <LayoutCard
              key={option.value}
              value={option.value}
              label={option.label}
              hint={option.hint}
              selected={option.value === state.layout}
              onSelect={() => state.setLayout(option.value)}
            />
          ))}
        </div>
      </RovingProvider>

      {state.layout === 'center' ? (
        <>
          <div className={styles.sectionHead}>
            <span className={styles.sectionTitle}>여백 채우기</span>
            <span className={styles.hint}>16:9 위·아래 빈 영역</span>
          </div>
          <ChipGroup
            label="여백 채우기"
            options={[
              { value: 'blur', label: '블러' },
              { value: 'color', label: '단색' },
            ]}
            value={centerFill.kind}
            onChange={(kind) =>
              state.setCenterFill(
                kind === 'blur'
                  ? { kind: 'blur', strength: DEFAULT_BLUR_STRENGTH }
                  : {
                      kind: 'color',
                      color: state.centerColorPresets[0]?.value ?? '#0b0b10',
                    },
              )
            }
          />
          {centerFill.kind === 'blur' ? (
            <div className={styles.optionRow}>
              <span className={styles.optionText}>
                <span className={styles.optionLabel}>블러 강도</span>
                <span className={styles.hint}>원본을 확대해 흐리게 채움</span>
              </span>
              <Slider
                className={styles.optionSlider}
                label="블러 강도"
                min={0}
                max={100}
                value={centerFill.strength}
                {...state.gestureHandlers}
                onValueChange={(strength) => state.setCenterFill({ kind: 'blur', strength })}
              />
            </div>
          ) : (
            <SwatchPicker
              label="배경 색"
              presets={state.centerColorPresets}
              value={centerFill.color}
              onChange={(color) => state.setCenterFill({ kind: 'color', color })}
            />
          )}
        </>
      ) : null}

      {state.layout === 'crop' ? (
        <>
          <div className={styles.sectionHead}>
            <span className={styles.sectionTitle}>작은 화면 테두리</span>
            <span className={styles.hint}>결과의 작은 화면 둘레</span>
          </div>
          {/* 시안은 라인·그림자·라운드를 늘 그린다 — 끄거나 굵기·색을 바꿀 수 있게 설정으로 뺐다 */}
          <div className={styles.optionRow}>
            <span className={styles.optionText}>
              <span className={styles.optionLabel}>테두리 표시</span>
              <span className={styles.hint}>라인 · 그림자 · 둥근 모서리</span>
            </span>
            <Switch
              size="sm"
              aria-label="테두리 표시"
              checked={pipBorder.on}
              onChange={() => state.setPipBorder({ ...pipBorder, on: !pipBorder.on })}
            />
          </div>
          {pipBorder.on ? (
            <>
              <ChipGroup
                label="테두리 굵기"
                options={state.pipBorderWidths.map((width) => ({
                  value: width,
                  label: `${width}px`,
                }))}
                value={pipBorder.width}
                onChange={(width) => state.setPipBorder({ ...pipBorder, width })}
              />
              <SwatchPicker
                label="테두리 색"
                presets={state.pipBorderPresets}
                value={pipBorder.color}
                onChange={(color) => state.setPipBorder({ ...pipBorder, color })}
              />
            </>
          ) : null}
        </>
      ) : null}

      {state.layout === 'split' ? (
        <>
          <div className={styles.sectionHead}>
            <span className={styles.sectionTitle}>분할 비율</span>
            <span className={styles.hint}>상단 : 하단</span>
          </div>
          <ChipGroup
            label="분할 비율"
            options={state.splitRatioOptions.map((percent) => ({
              value: percent,
              label: `${percent} : ${100 - percent}`,
            }))}
            value={state.splitRatio}
            onChange={(percent) => state.setSplitRatio(percent)}
          />

          <div className={styles.optionRow}>
            <span className={styles.optionText}>
              <span className={styles.optionLabel}>경계선 표시</span>
              <span className={styles.hint}>두 영역 사이 2px 라인</span>
            </span>
            <Switch
              size="sm"
              aria-label="경계선 표시"
              checked={state.splitBorder}
              onChange={state.toggleSplitBorder}
            />
          </div>
        </>
      ) : null}
    </section>
  );
}
