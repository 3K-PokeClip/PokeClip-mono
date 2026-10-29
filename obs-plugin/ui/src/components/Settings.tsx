import { ChevronDown, ChevronRight, Server, SlidersHorizontal } from 'lucide-preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { reasonText } from '../lib/copy';
import type { PluginSettings, ServerEnv } from '../lib/types';
import { ConfirmDialog } from './ConfirmDialog';
import { HotkeySection } from './HotkeySection';
import styles from './dock.module.css';
import { Button } from './ui';

// 바로 저장하는 스위치(설정 한 개씩 PUT /api/config — 플러그인은 보낸 키만 바꾼다)
type Flag = 'sync_start' | 'audio_auto_assign' | 'force_fallback';
type RowKey = Flag | 'server_env';
// 수신 서버 — 거의 안 바꾸는 값이라 안쪽에 접어 두고 「서버 설정 저장」으로 한 번에 보낸다
type ServerDraft = Pick<PluginSettings, 'ingest_host' | 'ingest_port' | 'latency_ms' | 'send_passphrase'>;

const SAVED_MS = 1500;

function Switch({
  id,
  checked,
  disabled,
  onChange,
}: {
  id: string;
  checked: boolean;
  disabled?: boolean;
  onChange: () => void;
}) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-labelledby={`${id}-label`}
      aria-describedby={`${id}-hint`}
      class={styles.switch}
      disabled={disabled}
      onClick={onChange}
    />
  );
}

// 설정 탭(연결 전엔 「고급 설정」 칸) 안쪽. 맨 위에 순간 표시 단축키(연결됐을 때만), 그 아래 스위치는 누르는 즉시 저장되고
// 그 줄에 「저장됨」이 잠깐 뜬다 — 아래 「저장」을 또 누르지 않는다. 방송 중엔 플러그인이 동기화·독 표시 방식·서버 환경·수신 서버를
// 거절하므로(409 streaming) 그 줄은 잠그고, 오디오 자동 배정만 바꿀 수 있다(배정은 방송·녹화가 끝난 뒤 적용).
// devVisible — POK-267 「개발 서버」 숨은 스위치. 로고를 연달아 눌러 풀었거나(app.tsx) 이미 개발 환경일 때만 보인다.
// 바꾸면 플러그인이 연결을 해제하므로(키가 환경별이다) 확인 창을 거친다. focusDev — 방금 풀었으면 그 줄이 보이게 내린다.
export function SettingsPanel({
  bridge,
  locked,
  autoAssign,
  serverEnv,
  devVisible,
  focusDev,
  hotkey,
}: {
  bridge: Bridge;
  locked: boolean;
  autoAssign: boolean;
  serverEnv: ServerEnv;
  devVisible: boolean;
  focusDev: boolean;
  hotkey: string | null; // 연결 전엔 null — 단축키 칸을 두지 않는다
}) {
  const [settings, setSettings] = useState<PluginSettings | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState<RowKey | ''>('');
  const [saved, setSaved] = useState<RowKey | ''>('');
  const [rowError, setRowError] = useState<{ key: RowKey; text: string } | null>(null);
  const [confirmEnv, setConfirmEnv] = useState<ServerEnv | null>(null);
  const [serverOpen, setServerOpen] = useState(false);
  const [server, setServer] = useState<ServerDraft | null>(null);
  const [serverSaving, setServerSaving] = useState(false);
  const [serverMessage, setServerMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const savedTimer = useRef<number>();
  const devRow = useRef<HTMLDivElement>(null);
  const scrolledToDev = useRef(false);

  useEffect(() => {
    if (settings) return;
    bridge
      .getSettings()
      .then((s) => {
        setSettings(s);
        setLoadFailed(false);
      })
      .catch(() => setLoadFailed(true));
  }, [settings]);

  // 처음 안내 카드로 켜고 끈 자동 배정이 여기에도 바로 보이게
  useEffect(() => {
    setSettings((s) => (s && s.audio_auto_assign !== autoAssign ? { ...s, audio_auto_assign: autoAssign } : s));
  }, [autoAssign]);

  // 서버 환경이 다른 곳(다른 브리지 클라이언트)에서 바뀌었으면 수신 주소는 옛 짝이다 — 버리고 다시 불러온다.
  useEffect(() => {
    setSettings((s) => (s && s.server_env !== serverEnv ? null : s));
    setServer(null);
  }, [serverEnv]);

  useEffect(() => {
    if (!focusDev || !settings || scrolledToDev.current) return;
    scrolledToDev.current = true;
    devRow.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [focusDev, settings]);

  useEffect(() => () => window.clearTimeout(savedTimer.current), []);

  const flashSaved = (key: RowKey) => {
    setSaved(key);
    window.clearTimeout(savedTimer.current);
    savedTimer.current = window.setTimeout(() => setSaved(''), SAVED_MS);
  };

  if (!settings) {
    return (
      <div class={styles.settingsPanel}>
        {hotkey !== null ? <HotkeySection hotkey={hotkey} bridge={bridge} bare /> : null}
        <p class={loadFailed ? styles.inlineError : styles.note}>{loadFailed ? '설정을 불러오지 못했어요.' : '불러오는 중…'}</p>
      </div>
    );
  }

  const toggle = async (key: Flag) => {
    if (busy) return;
    const next = !settings[key];
    setRowError(null);
    setBusy(key);
    setSettings({ ...settings, [key]: next }); // 바로 바뀌어 보이게 — 거절되면 되돌린다
    const r = await bridge.putSettings({ [key]: next } as Partial<PluginSettings>);
    setBusy('');
    if (r.ok) {
      if (r.settings) setSettings(r.settings);
      flashSaved(key);
    } else {
      setSettings((s) => (s ? { ...s, [key]: !next } : s));
      setRowError({ key, text: reasonText(r.reason) });
    }
  };

  const switchEnv = async (target: ServerEnv) => {
    setBusy('server_env');
    const r = await bridge.putSettings({ server_env: target });
    setBusy('');
    setConfirmEnv(null);
    if (r.ok) {
      if (r.settings) setSettings(r.settings);
      setServer(null);
      flashSaved('server_env');
    } else {
      setRowError({ key: 'server_env', text: reasonText(r.reason) });
    }
  };

  const draft: ServerDraft = server ?? {
    ingest_host: settings.ingest_host,
    ingest_port: settings.ingest_port,
    latency_ms: settings.latency_ms,
    send_passphrase: settings.send_passphrase,
  };
  const setField = <K extends keyof ServerDraft>(key: K, value: ServerDraft[K]) => {
    setServer({ ...draft, [key]: value });
    setServerMessage(null);
  };
  const saveServer = async (e: Event) => {
    e.preventDefault();
    if (locked) return;
    setServerSaving(true);
    const r = await bridge.putSettings(draft);
    setServerSaving(false);
    if (r.ok) {
      if (r.settings) setSettings(r.settings);
      setServer(null);
      setServerMessage({ ok: true, text: '저장했어요' });
    } else {
      setServerMessage({ ok: false, text: reasonText(r.reason) });
    }
  };

  const row = (key: RowKey, label: string, hint: string, opts: { checked: boolean; lockedRow: boolean; savedText?: string; onChange: () => void }) => {
    const id = `setting-${key}`;
    const error = rowError?.key === key ? rowError.text : '';
    return (
      <div class={styles.toggleRow} ref={key === 'server_env' ? devRow : undefined}>
        <span class={styles.toggleText}>
          <span id={`${id}-label`}>{label}</span>
          <span id={`${id}-hint`} class={styles.toggleHint} data-tone={error ? 'error' : undefined}>
            {error || (opts.lockedRow ? '방송 중에는 바꿀 수 없어요' : hint)}
          </span>
        </span>
        {saved === key ? (
          <span class={styles.savedTag} role="status">
            {opts.savedText ?? '저장됨'}
          </span>
        ) : null}
        <Switch id={id} checked={opts.checked} disabled={opts.lockedRow || busy === key} onChange={opts.onChange} />
      </div>
    );
  };

  return (
    <div class={styles.settingsPanel}>
      {hotkey !== null ? (
        <>
          <HotkeySection hotkey={hotkey} bridge={bridge} bare />
          <div class={styles.divider} />
        </>
      ) : null}
      {/* 동기화 = 본방의 시작·정지만 따라간다. 본방이 잠시 끊겨 재연결 중일 땐 우리 송출을 유지한다(녹화 구멍 방지). */}
      {row('sync_start', '본 방송과 함께 시작·정지', '잠깐 끊겨도 PokeClip 전송은 이어가요', {
        checked: settings.sync_start,
        lockedRow: locked,
        onChange: () => void toggle('sync_start'),
      })}
      {/* A2 — 트랙 2~6을 소스별 스템으로 나눈다. 끄면 처음 켤 때 기억한 트랙 체크로 되돌린다(README 「오디오 트랙」). */}
      {row('audio_auto_assign', '오디오 트랙 자동 배정', '소리 나는 소스를 트랙 2~6에 나눠 실어요', {
        checked: settings.audio_auto_assign,
        lockedRow: false,
        onChange: () => void toggle('audio_auto_assign'),
      })}
      {row('force_fallback', '기본 패널로 표시', '다음에 OBS를 켤 때부터', {
        checked: settings.force_fallback,
        lockedRow: locked,
        onChange: () => void toggle('force_fallback'),
      })}
      {devVisible
        ? row('server_env', '개발 서버 사용 (관리자)', '바꾸면 연결이 해제돼 새 코드로 다시 연결해요', {
            checked: serverEnv === 'dev',
            lockedRow: locked,
            savedText: serverEnv === 'dev' ? '개발 서버' : '운영 서버',
            onChange: () => {
              setRowError(null);
              setConfirmEnv(serverEnv === 'dev' ? 'prod' : 'dev');
            },
          })
        : null}
      <div class={styles.divider} />
      <button
        type="button"
        class={styles.subFold}
        aria-expanded={serverOpen}
        aria-controls="server-fields"
        onClick={() => {
          setServerOpen((o) => !o);
          setServer(null);
          setServerMessage(null);
        }}
      >
        <Server size={13} strokeWidth={2} aria-hidden="true" />
        <span>수신 서버</span>
        <span class={styles.subFoldValue}>
          {settings.ingest_host}:{settings.ingest_port}
        </span>
        {serverOpen ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
      </button>
      {serverOpen ? (
        <form id="server-fields" class={styles.settingsPanel} onSubmit={saveServer}>
          <div class={styles.fieldRow}>
            <label class={styles.field}>
              <span class={styles.fieldLabel}>수신 호스트</span>
              <input
                id="setting-ingest-host"
                class={styles.textInput}
                value={draft.ingest_host}
                onInput={(e) => setField('ingest_host', (e.currentTarget as HTMLInputElement).value)}
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
                onInput={(e) => setField('ingest_port', Number((e.currentTarget as HTMLInputElement).value))}
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
              onInput={(e) => setField('latency_ms', Number((e.currentTarget as HTMLInputElement).value))}
            />
          </label>
          <div class={styles.toggleRow}>
            <span id="setting-passphrase-label">SRT 암호 사용</span>
            <button
              type="button"
              role="switch"
              aria-checked={draft.send_passphrase}
              aria-labelledby="setting-passphrase-label"
              class={styles.switch}
              onClick={() => setField('send_passphrase', !draft.send_passphrase)}
            />
          </div>
          {serverMessage ? (
            <span class={serverMessage.ok ? styles.saved : styles.inlineError} role="status">
              {serverMessage.text}
            </span>
          ) : null}
          <Button
            type="submit"
            variant="soft"
            size="sm"
            block
            loading={serverSaving}
            disabled={locked}
            title={locked ? '방송 중에는 바꿀 수 없어요' : undefined}
          >
            서버 설정 저장
          </Button>
        </form>
      ) : null}
      {confirmEnv ? (
        <ConfirmDialog
          id="server-env"
          title={confirmEnv === 'dev' ? '개발 서버로 바꿀까요?' : '운영 서버로 바꿀까요?'}
          description="지금 연결이 해제돼요. 바꾼 서버에서 새 8자리 코드를 발급받아 다시 연결해야 해요."
          confirmLabel="바꾸기"
          onCancel={() => setConfirmEnv(null)}
          onConfirm={() => switchEnv(confirmEnv)}
        />
      ) : null}
    </div>
  );
}

// 연결 전 — 탭이 없으니 지금처럼 접는 「고급 설정」 칸 하나. 로고로 개발자 모드를 풀면 저절로 펼친다.
export function Settings({
  bridge,
  locked,
  autoAssign,
  serverEnv,
  devVisible,
  focusDev,
}: {
  bridge: Bridge;
  locked: boolean;
  autoAssign: boolean;
  serverEnv: ServerEnv;
  devVisible: boolean;
  focusDev: boolean;
}) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (focusDev) setOpen(true);
  }, [focusDev]);
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
        <div id="settings-body" class={styles.settingsBody}>
          <SettingsPanel
            bridge={bridge}
            locked={locked}
            autoAssign={autoAssign}
            serverEnv={serverEnv}
            devVisible={devVisible}
            focusDev={focusDev}
            hotkey={null}
          />
        </div>
      ) : null}
    </div>
  );
}
