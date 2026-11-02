import { useEffect, useRef, useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { audioNeedsAttention, checksNeedAttention } from '../lib/tabs';
import type { BridgeState } from '../lib/types';
import { AudioTracks } from './AudioTracks';
import { Checks } from './Checks';
import styles from './dock.module.css';
import { SettingsPanel } from './Settings';

type Tab = 'checks' | 'audio' | 'settings';
// 패널은 고른 탭 하나만 그린다 — aria-controls는 그 탭에만 달아 없는 id를 가리키지 않게 한다
const PANEL_ID = 'dock-tab-panel';
const TABS: { id: Tab; label: string }[] = [
  { id: 'checks', label: '점검' },
  { id: 'audio', label: '오디오' },
  { id: 'settings', label: '설정' },
];

// 상태 카드 아래 — 송출 점검·오디오 트랙·설정(맨 위에 순간 표시 단축키)을 탭 하나씩 보여 준다. 네 칸을 세로로 쌓으니
// 설정까지 한참 내려가야 했다(2026-10-29 B안). 처음엔 방송 중 자주 보는 오디오. 다른 탭에 확인할 게 있으면 그 탭에 점을 찍는다.
// 로고로 개발자 모드를 풀면(focusDev) 설정 탭으로 넘어가 그 줄을 보인다.
export function DockTabs({
  state,
  bridge,
  locked,
  devVisible,
  focusDev,
}: {
  state: BridgeState;
  bridge: Bridge;
  locked: boolean;
  devVisible: boolean;
  focusDev: boolean;
}) {
  const [tab, setTab] = useState<Tab>('audio');
  const buttons = useRef<Partial<Record<Tab, HTMLButtonElement | null>>>({});

  useEffect(() => {
    if (focusDev) setTab('settings');
  }, [focusDev]);

  const attention: Record<Tab, boolean> = {
    checks: checksNeedAttention(state),
    audio: audioNeedsAttention(state),
    settings: false,
  };

  // 탭 목록 안에서는 ←·→·Home·End로 옮긴다(WAI-ARIA 탭 패턴) — 고르면 바로 보인다
  const onKeyDown = (e: KeyboardEvent, index: number) => {
    const last = TABS.length - 1;
    const next =
      e.key === 'ArrowRight' ? (index === last ? 0 : index + 1)
      : e.key === 'ArrowLeft' ? (index === 0 ? last : index - 1)
      : e.key === 'Home' ? 0
      : e.key === 'End' ? last
      : -1;
    if (next < 0) return;
    e.preventDefault();
    setTab(TABS[next].id);
    buttons.current[TABS[next].id]?.focus();
  };

  return (
    <section class={styles.tabCard}>
      <div class={styles.tabList} role="tablist" aria-label="독 보기">
        {TABS.map((t, i) => (
          <button
            key={t.id}
            id={`dock-tab-${t.id}`}
            ref={(el) => {
              buttons.current[t.id] = el;
            }}
            type="button"
            role="tab"
            class={styles.tab}
            aria-selected={tab === t.id}
            aria-controls={tab === t.id ? PANEL_ID : undefined}
            tabIndex={tab === t.id ? 0 : -1}
            onClick={() => setTab(t.id)}
            onKeyDown={(e) => onKeyDown(e, i)}
          >
            {t.label}
            {attention[t.id] ? (
              <>
                <span class={styles.tabDot} aria-hidden="true" />
                <span class={styles.srOnly}> · 확인 필요</span>
              </>
            ) : null}
          </button>
        ))}
      </div>
      <div id={PANEL_ID} class={styles.tabPanel} role="tabpanel" aria-labelledby={`dock-tab-${tab}`}>
        {tab === 'checks' ? <Checks state={state} bare /> : null}
        {tab === 'audio' ? (
          state.audio.known ? (
            <AudioTracks state={state} bridge={bridge} bare />
          ) : (
            <p class={styles.note}>오디오 트랙 정보를 기다리는 중이에요.</p>
          )
        ) : null}
        {tab === 'settings' ? (
          <SettingsPanel
            bridge={bridge}
            locked={locked}
            autoAssign={state.audio.autoAssign}
            serverEnv={state.serverEnv}
            devVisible={devVisible}
            focusDev={focusDev}
            hotkey={state.marks.hotkey}
          />
        ) : null}
      </div>
    </section>
  );
}
