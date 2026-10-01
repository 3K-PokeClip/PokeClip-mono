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

// API 주소(api_base)는 시안대로 화면에서 뺐다 — 스트리머가 바꿀 값이 아니고 브리지로도 못 바꾼다(설정 파일 전용).
// autoAssign은 지금 상태 — 처음 안내 카드로 켜고 끈 것이 열어 둔 설정 초안에도 반영되게(낡은 초안 저장으로 꺼지지 않게).
export function Settings({ bridge, locked, autoAssign }: { bridge: Bridge; locked: boolean; autoAssign: boolean }) {
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

  useEffect(() => {
    setDraft((d) => (d && d.audio_auto_assign !== autoAssign ? { ...d, audio_auto_assign: autoAssign } : d));
  }, [autoAssign]);

  const set = <K extends keyof PluginSettings>(key: K, value: PluginSettings[K]) => {
    setDraft((d) => (d ? { ...d, [key]: value } : d));
    setMessage(null);
  };

  const save = async (e: Event) => {
    e.preventDefault();
    if (!draft) return;
    setSaving(true);
    // 처음 안내에 답했는지(audio_assign_prompted)는 안내 카드·스위치가 정한다 — 이 저장이 옛 값으로 덮지 않게 뺀다.
    const { api_base: _unused, clip_api_base: _dev, audio_assign_prompted: _prompted, ...editable } = draft;
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
            {/* A2 — 트랙 2~6을 소스별 스템으로 나눈다. 끄면 OBS 고급 오디오 설정의 트랙 체크를 그대로 보낸다. */}
            <Toggle
              id="setting-audio-auto"
              label="오디오 트랙 자동 배정"
              hint="소리 나는 소스를 트랙 2~6에 하나씩 나눠 실어 웹 편집기에서 소리를 따로 켜고 끌 수 있어요. 처음 켤 때의 트랙 체크를 기억해 끄면 되돌려요. 녹화 트랙도 같은 배정을 써요."
              checked={draft.audio_auto_assign}
              onChange={(v) => set('audio_auto_assign', v)}
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
