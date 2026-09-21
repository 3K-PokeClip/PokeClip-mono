import { ChevronDown, SlidersHorizontal } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { reasonText } from '../lib/copy';
import type { PluginSettings } from '../lib/types';
import styles from './dock.module.css';
import { Button } from './ui';

function Toggle({
  id,
  label,
  hint,
  checked,
  onChange,
}: {
  id: string;
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div class={styles.toggleRow}>
      <span class={styles.toggleText}>
        <span id={`${id}-label`}>{label}</span>
        {hint ? (
          <span id={`${id}-hint`} class={styles.toggleHint}>
            {hint}
          </span>
        ) : null}
      </span>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        aria-labelledby={`${id}-label`}
        aria-describedby={hint ? `${id}-hint` : undefined}
        class={styles.switch}
        onClick={() => onChange(!checked)}
      />
    </div>
  );
}

// API 주소(api_base)는 시안대로 화면에서 뺐다 — 스트리머가 바꿀 값이 아니다.
// 개발용으로 바꿀 때는 pokeclip.json 이나 PUT /api/config 로 넣는다.
export function Settings({ bridge, locked }: { bridge: Bridge; locked: boolean }) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<PluginSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (!open || draft) return;
    bridge
      .getSettings()
      .then(setDraft)
      .catch(() => setMessage({ ok: false, text: '설정을 불러오지 못했어요.' }));
  }, [open]);

  const set = <K extends keyof PluginSettings>(key: K, value: PluginSettings[K]) => {
    setDraft((d) => (d ? { ...d, [key]: value } : d));
    setMessage(null);
  };

  const save = async (e: Event) => {
    e.preventDefault();
    if (!draft) return;
    setSaving(true);
    const { api_base: _unused, ...editable } = draft;
    const result = await bridge.putSettings(editable);
    setSaving(false);
    if (result.ok) {
      if (result.settings) setDraft(result.settings);
      setMessage({ ok: true, text: '저장했어요' });
    } else {
      setMessage({ ok: false, text: reasonText(result.reason) });
    }
  };

  return (
    <div class={styles.settings}>
      <button
        type="button"
        class={styles.settingsToggle}
        aria-expanded={open}
        aria-controls="settings-body"
        onClick={() => setOpen((v) => !v)}
      >
        <SlidersHorizontal size={13} strokeWidth={2} aria-hidden="true" />
        <span>고급 설정</span>
        <ChevronDown class={styles.chevron} size={14} strokeWidth={2} aria-hidden="true" />
      </button>
      {open ? (
        draft ? (
          <form id="settings-body" class={styles.settingsBody} onSubmit={save}>
            <div class={styles.fieldRow}>
              <label class={styles.field}>
                <span class={styles.fieldLabel}>수신 호스트</span>
                <input
                  id="setting-ingest-host"
                  class={styles.textInput}
                  value={draft.ingest_host}
                  onInput={(e) => set('ingest_host', (e.currentTarget as HTMLInputElement).value)}
                  spellcheck={false}
                />
              </label>
              <label class={styles.field}>
                <span class={styles.fieldLabel}>포트</span>
                <input
                  id="setting-ingest-port"
                  class={styles.textInput}
                  type="number"
                  min={1}
                  max={65535}
                  value={draft.ingest_port}
                  onInput={(e) => set('ingest_port', Number((e.currentTarget as HTMLInputElement).value))}
                />
              </label>
            </div>
            <label class={styles.field}>
              <span class={styles.fieldLabel}>SRT 지연 (ms)</span>
              <input
                id="setting-latency"
                class={styles.textInput}
                type="number"
                min={20}
                max={8000}
                step={10}
                value={draft.latency_ms}
                onInput={(e) => set('latency_ms', Number((e.currentTarget as HTMLInputElement).value))}
              />
            </label>
            {/* 동기화 = 본방의 시작·정지만 따라간다. 본방이 잠시 끊겨 재연결 중일 땐 우리 송출을 유지한다(녹화 구멍 방지).
                본방 송출 중에 켜면 그 자리에서 시작한다. */}
            <Toggle
              id="setting-sync-start"
              label="본 방송과 송출 동기화"
              hint="본 방송이 시작·정지할 때 함께 전송하고 멈춰요. 잠시 끊겨 재연결 중일 땐 유지돼요."
              checked={draft.sync_start}
              onChange={(v) => set('sync_start', v)}
            />
            <Toggle id="setting-passphrase" label="SRT 암호 사용" checked={draft.send_passphrase} onChange={(v) => set('send_passphrase', v)} />
            <Toggle
              id="setting-fallback"
              label="기본 패널로 표시 (다음 실행부터)"
              checked={draft.force_fallback}
              onChange={(v) => set('force_fallback', v)}
            />
            <div class={styles.settingsActions}>
              {message ? (
                <span class={message.ok ? styles.saved : styles.inlineError} role="status">
                  {message.text}
                </span>
              ) : null}
              <Button
                type="submit"
                variant="soft"
                size="sm"
                loading={saving}
                disabled={locked}
                title={locked ? '방송 중에는 바꿀 수 없어요' : undefined}
              >
                저장
              </Button>
            </div>
          </form>
        ) : (
          <div id="settings-body" class={styles.settingsBody}>
            {message ? <span class={styles.inlineError}>{message.text}</span> : '불러오는 중…'}
          </div>
        )
      ) : null}
    </div>
  );
}
