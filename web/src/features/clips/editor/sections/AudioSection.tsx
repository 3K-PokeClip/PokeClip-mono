import { Gamepad2, Mic, ShieldCheck, SlidersHorizontal } from 'lucide-react';
import type { ComponentType } from 'react';
import { Checkbox, Slider } from '@/ui';
import { RovingProvider, useRovingItem } from '@/ui/primitives';
import { PendingNote } from './PendingNote';
import styles from './sections.module.css';
import type { AudioPresetId } from '../audioPresets';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 오디오 도구(POK-389): 우리만 가진 것: 방송 소리를 트랙별로 따로 받는다.
// 위: 빠른 설정 넷(트랙 넣기·음량을 한 번에) · 가운데: 방송 소리 트랙마다 넣기·음량 · 아래: 음악·효과음(준비 중).
// 어떤 트랙이 있는지는 데이터가 정한다(스트리머가 적은 트랙 이름, POK-240).

const PRESET_ICONS: Record<AudioPresetId, ComponentType<{ size?: number }>> = {
  'copyright-safe': ShieldCheck,
  'voice-up': Mic,
  'game-up': Gamepad2,
  original: SlidersHorizontal,
};

/** 빠른 설정 카드 하나. 라디오 묶음이라 화살표로 옮겨 다닌다(레이아웃 타일과 같은 규약) */
function PresetCard({
  value,
  label,
  hint,
  reason,
  selected,
  onSelect,
}: {
  value: AudioPresetId;
  label: string;
  hint: string;
  reason: string | null;
  selected: boolean;
  onSelect: () => void;
}) {
  const roving = useRovingItem(value);
  const Icon = PRESET_ICONS[value];
  return (
    <button
      ref={roving.ref}
      type="button"
      role="radio"
      aria-checked={selected}
      disabled={reason !== null}
      tabIndex={roving.tabIndex}
      onKeyDown={roving.onKeyDown}
      className={styles.presetCard}
      title={reason ?? undefined}
      onClick={onSelect}
    >
      <span className={styles.presetIcon} aria-hidden>
        <Icon size={15} />
      </span>
      <span className={styles.presetText}>
        <span className={styles.presetLabel}>{label}</span>
        <span className={styles.presetHint}>{reason ?? hint}</span>
      </span>
    </button>
  );
}

export function AudioSection({ state }: { state: ClipEditorMockState }) {
  const tracks = state.tracks.filter((track) => track.volume !== null);
  const firstAvailable =
    state.audioPresetOptions.find((preset) => preset.unavailableReason === null)?.value ?? null;

  return (
    <section className={styles.section} aria-label="오디오">
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>빠른 설정</span>
      </div>
      <RovingProvider
        activeValue={state.audioPreset ?? firstAvailable}
        onActiveChange={(value) => state.applyAudioPreset(value as AudioPresetId)}
      >
        <div className={styles.presetGrid} role="radiogroup" aria-label="빠른 설정">
          {state.audioPresetOptions.map((preset) => (
            <PresetCard
              key={preset.value}
              value={preset.value}
              label={preset.label}
              hint={preset.hint}
              reason={preset.unavailableReason}
              selected={state.audioPreset === preset.value}
              onSelect={() => state.applyAudioPreset(preset.value)}
            />
          ))}
        </div>
      </RovingProvider>

      <div className={styles.divider} />
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>방송 소리</span>
        <span className={styles.hint}>트랙마다 넣기 · 음량</span>
      </div>
      {tracks.map((track) => (
        <div key={track.id} className={styles.trackRow} data-muted={track.muted || undefined}>
          <Checkbox
            size="sm"
            aria-label={`${track.label} 넣기`}
            checked={!track.muted}
            onChange={() => state.toggleTrackMute(track.id)}
          />
          <span className={styles.trackName}>{track.label}</span>
          <Slider
            className={styles.trackSlider}
            label={`${track.label} 볼륨`}
            value={track.volume ?? 0}
            {...state.gestureHandlers}
            onValueChange={(volume) => state.setTrackVolume(track.id, volume)}
          />
          <span className={styles.trackValue}>{track.muted ? '안 넣음' : `${track.volume}%`}</span>
        </div>
      ))}
      <p className={styles.hint}>켠 소리만 영상에 들어가요</p>

      <div className={styles.divider} />
      <PendingNote title="음악 · 효과음" />
    </section>
  );
}
