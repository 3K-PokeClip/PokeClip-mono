import { ChevronDown, SlidersHorizontal } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { reasonText } from '../lib/copy';
import type { PluginSettings } from '../lib/types';
import styles from './dock.module.css';
import { Button } from './ui';

function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div class={styles.toggleRow}>
      <span>{label}</span>
      <button type="button" role="switch" aria-checked={checked} aria-label={label} class={styles.switch} onClick={() => onChange(!checked)} />
    </div>
  );
}

export function Settings({ bridge, locked }: { bridge: Bridge; locked: boolean }) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<PluginSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (!open || draft) return;
    bridge.getSettings().then(setDraft).catch(() => setMessage({ ok: false, text: '설정을 불러오지 못했어요.' }));
  }, [open]);

  const set = <K extends keyof PluginSettings>(key: K, value: PluginSettings[K]) => {
    setDraft((d) => (d ? { ...d, [key]: value } : d));
    setMessage(null);
  };

  const save = async (e: Event) => {
    e.preventDefault();
    if (!draft) return;
    setSaving(true);
    const result = await bridge.putSettings(draft);
    setSaving(false);
    if (result.ok) {
      if (result.settings) setDraft(result.settings);
      setMessage({ ok: true, text: '저장했어요' });
    } else {
      setMessage({ ok: false, text: reasonText(result.reason) });
    }
  };

  return (
    <details class={styles.details} open={open} onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}>
      <summary class={styles.summary}>
        <SlidersHorizontal size={13} strokeWidth={2} aria-hidden />
        고급 설정
        <ChevronDown class={styles.chevron} size={14} strokeWidth={2} aria-hidden />
      </summary>
      {draft ? (
        <form class={styles.settingsBody} onSubmit={save}>
          <label class={styles.field}>
            <span class={styles.fieldLabel}>PokeClip API 주소</span>
            <input class={styles.textInput} value={draft.api_base} onInput={(e) => set('api_base', (e.currentTarget as HTMLInputElement).value)} spellcheck={false} />
          </label>
          <div class={styles.fieldRow}>
            <label class={styles.field}>
              <span class={styles.fieldLabel}>수신 호스트</span>
              <input class={styles.textInput} value={draft.ingest_host} onInput={(e) => set('ingest_host', (e.currentTarget as HTMLInputElement).value)} spellcheck={false} />
            </label>
            <label class={styles.field}>
              <span class={styles.fieldLabel}>포트</span>
              <input class={styles.textInput} type="number" min={1} max={65535} value={draft.ingest_port} onInput={(e) => set('ingest_port', Number((e.currentTarget as HTMLInputElement).value))} />
            </label>
          </div>
          <label class={styles.field}>
            <span class={styles.fieldLabel}>SRT 지연 (ms)</span>
            <input class={styles.textInput} type="number" min={20} max={8000} step={10} value={draft.latency_ms} onInput={(e) => set('latency_ms', Number((e.currentTarget as HTMLInputElement).value))} />
          </label>
          <Toggle label="본방 시작 시 함께 전송" checked={draft.sync_start} onChange={(v) => set('sync_start', v)} />
          <Toggle label="SRT 암호 사용" checked={draft.send_passphrase} onChange={(v) => set('send_passphrase', v)} />
          <Toggle label="기본 패널로 표시 (다음 실행부터)" checked={draft.force_fallback} onChange={(v) => set('force_fallback', v)} />
          <div class={styles.settingsActions}>
            {message ? (
              <span class={message.ok ? styles.saved : styles.inlineError} role="status">
                {message.text}
              </span>
            ) : null}
            <Button type="submit" variant="soft" size="sm" loading={saving} disabled={locked} title={locked ? '방송 중에는 바꿀 수 없어요' : undefined}>
              저장
            </Button>
          </div>
        </form>
      ) : open ? (
        <div class={styles.settingsBody}>{message ? <span class={styles.inlineError}>{message.text}</span> : '불러오는 중…'}</div>
      ) : null}
    </details>
  );
}
