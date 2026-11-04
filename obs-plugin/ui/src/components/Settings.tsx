import { ChevronDown, ChevronRight, KeyRound, Server, Wrench } from 'lucide-preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { reasonText } from '../lib/copy';
import { resolveApiBase } from '../lib/format';
import type { PluginSettings, ServerEnv } from '../lib/types';
import { ConfirmDialog } from './ConfirmDialog';
import { HotkeySection } from './HotkeySection';
import styles from './dock.module.css';
import { useNotify } from './notify';
import { Button } from './ui';

// 바로 저장하는 스위치(설정 한 개씩 PUT /api/config — 플러그인은 보낸 키만 바꾼다)
type Flag = 'sync_start' | 'audio_auto_assign' | 'force_fallback';
type RowKey = Flag | 'server_env';
// 수신 서버 — 거의 안 바꾸는 값이라 안쪽에 접어 두고 「서버 설정 저장」으로 한 번에 보낸다
type ServerDraft = Pick<PluginSettings, 'ingest_host' | 'ingest_port' | 'latency_ms' | 'send_passphrase'>;
type Fold = 'api' | 'ingest';

const UNPAIR_DESC = '지금 연결이 해제돼요. 바꾼 서버에서 새 8자리 코드를 발급받아 다시 연결해야 해요.';

// waiting — 어느 스위치든 저장 중이라 잠깐 못 누른다. disabled로 잠그면 키보드로 누른 그 스위치의 포커스가 body로
// 빠져(Chromium) 처음부터 다시 Tab해야 한다 — aria-disabled로 흐리게만 하고 누름은 버린다(3차 리뷰).
function Switch({
  id,
  checked,
  disabled,
  waiting,
  onChange,
}: {
  id: string;
  checked: boolean;
  disabled?: boolean;
  waiting?: boolean;
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
      aria-disabled={waiting && !disabled ? 'true' : undefined}
      class={styles.switch}
      disabled={disabled}
      onClick={waiting ? undefined : onChange}
    />
  );
}

export interface SettingsPanelProps {
  bridge: Bridge;
  locked: boolean;
  autoAssign: boolean;
  serverEnv: ServerEnv;
  apiBase: string; // state.apiBase — 다른 곳에서 인증 서버가 바뀌면 다시 불러온다
  paired: boolean; // 연결돼 있을 때만 서버를 바꾸기 전에 「연결이 해제돼요」를 묻는다
  general: boolean; // 일반 설정(스위치 셋) — 연결 전 코드 입력 화면에는 두지 않는다
  devVisible: boolean;
  focusDev: number; // 로고로 개발자 모드를 켤 때마다 오른다 — 그 칸이 보이게 내린다
  hotkey: string | null; // 연결 전엔 null — 단축키 칸을 두지 않는다
}

// 설정 탭 안쪽(general) · 연결 전 개발자 칸(DevCard). 맨 위에 순간 표시 단축키(연결됐을 때만), 그 아래 스위치는 누르는 즉시
// 저장되고 아래 토스트로 「저장했어요」를 알린다(오류는 그 줄에) — 아래 「저장」을 또 누르지 않는다. 방송 중엔 플러그인이 동기화·독 표시 방식·서버
// 환경·인증 서버·수신 서버를 거절하므로(409 streaming) 그 줄은 잠그고, 오디오 자동 배정만 바꿀 수 있다(배정은 방송·녹화가
// 끝난 뒤 적용).
// 개발자 모드(devVisible) — 로고를 연달아 눌러 켰거나(app.tsx) 이미 개발 환경일 때만 「개발 서버 사용」·인증 서버·수신
// 서버가 보인다. 서버 환경·인증 서버는 바꾸면 플러그인이 연결을 해제하므로(키는 그 서버가 발급했다) 연결돼 있으면 확인 창을
// 거친다. 수신 서버는 키와 무관해 연결을 끊지 않는다.
export function SettingsPanel({
  bridge,
  locked,
  autoAssign,
  serverEnv,
  apiBase,
  paired,
  general,
  devVisible,
  focusDev,
  hotkey,
}: SettingsPanelProps) {
  const [settings, setSettings] = useState<PluginSettings | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState<RowKey | ''>('');
  const [rowError, setRowError] = useState<{ key: RowKey; text: string } | null>(null);
  const [confirmEnv, setConfirmEnv] = useState<ServerEnv | null>(null);
  const [open, setOpen] = useState<Record<Fold, boolean>>({ api: false, ingest: false });
  const [server, setServer] = useState<ServerDraft | null>(null);
  const [serverSaving, setServerSaving] = useState(false);
  const [serverError, setServerError] = useState('');
  const [apiDraft, setApiDraft] = useState<string | null>(null);
  const [apiSaving, setApiSaving] = useState(false);
  const [apiError, setApiError] = useState('');
  const [confirmApi, setConfirmApi] = useState<string | null>(null);
  const notify = useNotify();
  const devSection = useRef<HTMLDivElement>(null);
  const scrolledFor = useRef(0);

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

  // 서버 환경·인증 서버가 다른 곳(다른 브리지 클라이언트)에서 바뀌었으면 수신 주소·기본 주소는 옛 짝이다 — 버리고 다시 불러온다.
  useEffect(() => {
    setSettings((s) => (s && (s.server_env !== serverEnv || s.api_base !== apiBase) ? null : s));
    setServer(null);
    setApiDraft(null);
  }, [serverEnv, apiBase]);

  useEffect(() => {
    if (!devVisible || !settings || focusDev <= scrolledFor.current) return;
    scrolledFor.current = focusDev;
    devSection.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [focusDev, devVisible, settings]);

  if (!settings) {
    return (
      <div class={styles.settingsPanel}>
        {hotkey !== null ? <HotkeySection hotkey={hotkey} bridge={bridge} bare /> : null}
        <p class={loadFailed ? styles.inlineError : styles.note}>{loadFailed ? '설정을 불러오지 못했어요.' : '불러오는 중…'}</p>
      </div>
    );
  }

  const toggle = async (key: Flag, label: string) => {
    if (busy) return;
    const next = !settings[key];
    setRowError(null);
    setBusy(key);
    setSettings({ ...settings, [key]: next }); // 바로 바뀌어 보이게 — 거절되면 되돌린다
    const r = await bridge.putSettings({ [key]: next } as Partial<PluginSettings>);
    setBusy('');
    if (r.ok) {
      if (r.settings) setSettings(r.settings);
      notify('success', `「${label}」 ${next ? '켬' : '끔'} · 저장했어요`);
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
      setApiDraft(null);
      notify('success', target === 'dev' ? '개발 서버로 바꿨어요' : '운영 서버로 바꿨어요');
    } else {
      setRowError({ key: 'server_env', text: reasonText(r.reason) });
    }
  };

  const toggleFold = (fold: Fold) => {
    setOpen((o) => ({ ...o, [fold]: !o[fold] }));
    if (fold === 'api') {
      setApiDraft(null);
      setApiError('');
    } else {
      setServer(null);
      setServerError('');
    }
  };

  // 인증 서버 — 비우면 지금 환경의 기본 주소. 지금과 같은 주소면 저장할 게 없다.
  const defaultApi = settings.default_api_base ?? '';
  const apiValue = apiDraft ?? settings.api_base;
  const apiTarget = resolveApiBase(apiValue, defaultApi);
  // 저장된 값도 같은 정리를 거쳐 비교한다 — 설정 파일에 끝 '/'를 붙여 적은 주소는 손대지 않았는데 바뀐 것으로 보인다
  const apiChanged = apiTarget !== resolveApiBase(settings.api_base, defaultApi);
  const applyApi = async (target: string) => {
    setApiSaving(true);
    setApiError('');
    const r = await bridge.putSettings({ api_base: target });
    setApiSaving(false);
    setConfirmApi(null);
    if (r.ok) {
      const savedBase = r.settings?.api_base ?? target;
      if (r.settings) setSettings(r.settings);
      setApiDraft(null);
      // 다른 환경의 기본 주소를 적으면 플러그인이 지금 환경 짝으로 맞춘다 — 왜 다른 주소가 됐는지 알린다
      if (savedBase === target) notify('success', '인증 서버를 바꿨어요');
      else notify('warning', `운영·개발 주소는 「개발 서버 사용」을 따라가요 (${savedBase})`);
    } else {
      setApiError(reasonText(r.reason));
    }
  };
  const saveApi = (e: Event) => {
    e.preventDefault();
    if (locked || !apiChanged || apiSaving) return;
    if (paired) setConfirmApi(apiTarget);
    else void applyApi(apiTarget);
  };

  const draft: ServerDraft = server ?? {
    ingest_host: settings.ingest_host,
    ingest_port: settings.ingest_port,
    latency_ms: settings.latency_ms,
    send_passphrase: settings.send_passphrase,
  };
  const setField = <K extends keyof ServerDraft>(key: K, value: ServerDraft[K]) => {
    setServer({ ...draft, [key]: value });
    setServerError('');
  };
  const saveServer = async (e: Event) => {
    e.preventDefault();
    if (locked) return;
    setServerSaving(true);
    setServerError(''); // 값을 그대로 두고 다시 눌러 성공하면 옛 오류가 남지 않게
    const r = await bridge.putSettings(draft);
    setServerSaving(false);
    if (r.ok) {
      if (r.settings) setSettings(r.settings);
      setServer(null);
      notify('success', '수신 서버를 저장했어요');
    } else {
      setServerError(reasonText(r.reason));
    }
  };

  const row = (key: RowKey, label: string, hint: string, opts: { checked: boolean; lockedRow: boolean; onChange: () => void }) => {
    const id = `setting-${key}`;
    const error = rowError?.key === key ? rowError.text : '';
    return (
      <div class={styles.toggleRow}>
        <span class={styles.toggleText}>
          <span id={`${id}-label`}>{label}</span>
          <span id={`${id}-hint`} class={styles.toggleHint} data-tone={error ? 'error' : undefined}>
            {error || (opts.lockedRow ? '방송 중에는 바꿀 수 없어요' : hint)}
          </span>
        </span>
        {/* 하나가 저장되는 동안은 모든 스위치를 흐리게 잠근다(포커스는 둔다) — 다른 줄 클릭을 조용히 버리면 눌렀는데 아무 일도 없어 보인다 */}
        <Switch id={id} checked={opts.checked} disabled={opts.lockedRow} waiting={busy !== ''} onChange={opts.onChange} />
      </div>
    );
  };

  const foldButton = (fold: Fold, Icon: typeof Server, label: string, value: string) => (
    <button
      type="button"
      class={styles.subFold}
      aria-expanded={open[fold]}
      aria-controls={open[fold] ? `${fold}-fields` : undefined}
      onClick={() => toggleFold(fold)}
    >
      <Icon size={13} strokeWidth={2} aria-hidden="true" />
      <span>{label}</span>
      <span class={styles.subFoldValue}>{value}</span>
      {open[fold] ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
    </button>
  );

  const lockedTitle = locked ? '방송 중에는 바꿀 수 없어요' : undefined;

  return (
    <div class={styles.settingsPanel}>
      {hotkey !== null ? (
        <>
          <HotkeySection hotkey={hotkey} bridge={bridge} bare />
          <div class={styles.divider} />
        </>
      ) : null}
      {general ? (
        <>
          {/* 동기화 = 본방의 시작·정지만 따라간다. 본방이 잠시 끊겨 재연결 중일 땐 우리 송출을 유지한다(녹화 구멍 방지). */}
          {row('sync_start', '본 방송과 함께 시작·정지', '잠깐 끊겨도 PokeClip 전송은 이어가요', {
            checked: settings.sync_start,
            lockedRow: locked,
            onChange: () => void toggle('sync_start', '본 방송과 함께 시작·정지'),
          })}
          {/* A2 — 트랙 2~6을 소스별 스템으로 나눈다. 끄면 처음 켤 때 기억한 트랙 체크로 되돌린다(README 「오디오 트랙」). */}
          {row('audio_auto_assign', '오디오 트랙 자동 배정', '소리 나는 소스를 트랙 2~6에 나눠 실어요', {
            checked: settings.audio_auto_assign,
            lockedRow: false,
            onChange: () => void toggle('audio_auto_assign', '오디오 트랙 자동 배정'),
          })}
          {row('force_fallback', '기본 패널로 표시', '다음에 OBS를 켤 때부터', {
            checked: settings.force_fallback,
            lockedRow: locked,
            onChange: () => void toggle('force_fallback', '기본 패널로 표시'),
          })}
        </>
      ) : null}
      {devVisible ? (
        <>
          {general ? <div class={styles.divider} /> : null}
          <div ref={devSection} class={styles.devLabel}>
            <Wrench size={12} strokeWidth={2} aria-hidden="true" />
            <span>개발자 모드</span>
          </div>
          {row('server_env', '개발 서버 사용', paired ? '바꾸면 연결이 해제돼 새 코드로 다시 연결해요' : '바꾼 서버에서 받은 코드로 연결해요', {
            checked: serverEnv === 'dev',
            lockedRow: locked,
            onChange: () => {
              setRowError(null);
              const target = serverEnv === 'dev' ? 'prod' : 'dev';
              // 연결 전이면 끊을 연결이 없다 — 묻지 않고 바꾼다
              if (paired) setConfirmEnv(target);
              else void switchEnv(target);
            },
          })}
          {/* 인증 서버 — 8자리 코드를 확인하고 스트림 키를 발급하는 곳(페어링 교환·마크) */}
          {foldButton('api', KeyRound, '인증 서버', settings.api_base)}
          {open.api ? (
            <form id="api-fields" class={styles.settingsPanel} onSubmit={saveApi}>
              <label class={styles.field}>
                <span class={styles.fieldLabel}>연결 코드를 확인하는 주소</span>
                <input
                  id="setting-api-base"
                  class={styles.textInput}
                  value={apiValue}
                  placeholder={defaultApi}
                  onInput={(e) => {
                    setApiDraft((e.currentTarget as HTMLInputElement).value);
                    setApiError('');
                  }}
                  inputMode="url"
                  autoComplete="off"
                  spellcheck={false}
                  aria-describedby="setting-api-base-hint"
                />
              </label>
              <span id="setting-api-base-hint" class={styles.toggleHint}>
                비우면 기본 주소예요. 직접 적는 주소는 이 PC(http://localhost:포트)만 돼요.
              </span>
              {apiError ? (
                <span class={styles.inlineError} role="alert">
                  {apiError}
                </span>
              ) : null}
              <Button
                type="submit"
                variant="soft"
                size="sm"
                block
                loading={apiSaving}
                disabled={locked || !apiChanged}
                title={lockedTitle}
              >
                인증 서버 저장
              </Button>
            </form>
          ) : null}
          {/* 수신 서버 — 우리 SRT 송출이 가는 곳. 키와 무관해 바꿔도 연결은 그대로다 */}
          {foldButton('ingest', Server, '수신 서버', `${settings.ingest_host}:${settings.ingest_port}`)}
          {open.ingest ? (
            <form id="ingest-fields" class={styles.settingsPanel} onSubmit={saveServer}>
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
              {serverError ? (
                <span class={styles.inlineError} role="alert">
                  {serverError}
                </span>
              ) : null}
              <Button type="submit" variant="soft" size="sm" block loading={serverSaving} disabled={locked} title={lockedTitle}>
                서버 설정 저장
              </Button>
            </form>
          ) : null}
        </>
      ) : null}
      {confirmEnv ? (
        <ConfirmDialog
          id="server-env"
          title={confirmEnv === 'dev' ? '개발 서버로 바꿀까요?' : '운영 서버로 바꿀까요?'}
          description={UNPAIR_DESC}
          confirmLabel="바꾸기"
          onCancel={() => setConfirmEnv(null)}
          onConfirm={() => switchEnv(confirmEnv)}
        />
      ) : null}
      {confirmApi !== null ? (
        <ConfirmDialog
          id="api-base"
          title="인증 서버를 바꿀까요?"
          description={UNPAIR_DESC}
          confirmLabel="바꾸기"
          onCancel={() => setConfirmApi(null)}
          onConfirm={() => applyApi(confirmApi)}
        />
      ) : null}
    </div>
  );
}

// 연결 전(코드 입력 화면) — 개발자 모드일 때만 코드 입력 아래에 둔다. 서버(환경·인증·수신)만 고른다 — 나머지 설정은
// 연결 뒤 설정 탭에 있다.
export function DevCard(props: Omit<SettingsPanelProps, 'general' | 'devVisible' | 'hotkey'>) {
  return (
    <section class={styles.devCard} aria-label="개발자 모드">
      <SettingsPanel {...props} general={false} devVisible hotkey={null} />
    </section>
  );
}
