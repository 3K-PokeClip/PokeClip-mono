'use client';

import { Check, Settings } from 'lucide-react';
import { Popover, Switch } from '@/ui';
import clsx from 'clsx';
import styles from './GlassPlayer.module.css';
import { PLAYER_QUALITIES, type PlayerQuality } from './usePlayerSimulation';

// 설정 팝오버 — 화질 선택 + 저지연 모드 (시안 영상 플레이어 글래스).
// 재생 속도는 VOD 전용이라 라이브 목업엔 없다.
export function PlayerSettingsPopover({
  quality,
  onQualityChange,
  lowLatency,
  onToggleLowLatency,
}: {
  quality: PlayerQuality;
  onQualityChange: (quality: PlayerQuality) => void;
  lowLatency: boolean;
  onToggleLowLatency: () => void;
}) {
  return (
    <Popover side="top" align="end">
      <Popover.Trigger>
        <button type="button" className={styles.glassBtn} aria-label="설정">
          <Settings size={19} aria-hidden />
        </button>
      </Popover.Trigger>
      <Popover.Content className={styles.settingsPanel}>
        <div className={styles.settingsGroupLabel}>화질</div>
        <div className={styles.qualityList}>
          {PLAYER_QUALITIES.map((option) => (
            <button
              key={option}
              type="button"
              className={clsx(styles.qualityBtn, option === quality && styles.qualityBtnActive)}
              aria-pressed={option === quality}
              onClick={() => onQualityChange(option)}
            >
              <Check
                size={14}
                aria-hidden
                className={styles.qualityCheck}
                style={{ opacity: option === quality ? 1 : 0 }}
              />
              {option}
            </button>
          ))}
        </div>
        <div className={styles.settingsDivider} />
        <div className={styles.lowLatencyRow}>
          <Switch
            size="sm"
            label="저지연 모드"
            checked={lowLatency}
            onChange={onToggleLowLatency}
          />
        </div>
      </Popover.Content>
    </Popover>
  );
}
