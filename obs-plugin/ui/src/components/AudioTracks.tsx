import { TriangleAlert, X } from 'lucide-preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { reasonText } from '../lib/copy';
import type { AudioSource, BridgeState } from '../lib/types';
import styles from './dock.module.css';

// A2 — 트랙 1은 늘 최종 믹스(본방과 같은 소리), 트랙 2~6은 소스별 스템(편집용, ADR-017).
// 실제 OBS 트랙 체크 기준이라 자동 배정을 꺼도 지금 나가는 그대로를 보여준다.
// POK-266 — 플러그인은 새 소스만 자동으로 앉히고, 그 뒤는 여기서 고친 대로 둔다. 칩의 ×는 트랙에서 뺀다(본방 믹스에만
// 남는다), 행 끝 「+」는 소스를 그 트랙으로 옮긴다(찬 트랙이면 묶인다). 어느 트랙에도 없는 소스는 경고로 알린다.
// 자동 배정이 적용 중일 때만 고칠 수 있다 — 꺼져 있으면 OBS 설정 그대로라 플러그인이 트랙을 맡지 않는다.
type Request = { key: string; name: string; track: number; action: 'add' | 'remove' };

export function AudioTracks({ state, bridge }: { state: BridgeState; bridge: Bridge }) {
  const { audio } = state;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [confirm, setConfirm] = useState<Request | null>(null);
  if (!audio.known) return null;

  const names = (list: { name: string }[]) => list.map((s) => s.name).join(', ');
  const mainStreamTracks = audio.tracks.filter((t) => t.mainStream).map((t) => t.track);
  const title = !audio.autoAssign
    ? '오디오 트랙 · OBS 설정 그대로'
    : audio.deferred
      ? '오디오 트랙 · 자동 배정 대기'
      : '오디오 트랙 · 자동 배정';
  const canEdit = audio.applied;

  // 고를 수 있는 소스 — 스템 트랙에 있는 것 + 트랙 2~6에 없는 것. 본방 트랙에만 있는 소스는 트랙 없음 목록으로 온다.
  const pool: AudioSource[] = [];
  const seen = new Set<string>();
  for (const s of [...audio.tracks.filter((t) => !t.mainStream).flatMap((t) => t.sources), ...audio.mixOnly]) {
    if (!seen.has(s.key)) {
      seen.add(s.key);
      pool.push(s);
    }
  }

  const send = async (req: Request) => {
    setBusy(true);
    setError('');
    const r = await bridge.assignAudio(req.key, req.action === 'add' ? req.track : null);
    setBusy(false);
    if (!r.ok) setError(reasonText(r.reason));
  };
  // 방송·녹화 중에는 지금 나가는 트랙이 바뀐다 — 넣기든 빼기든 한 번 묻는다.
  const ask = (req: Request) => {
    if (audio.locked) setConfirm(req);
    else void send(req);
  };

  return (
    <section class={styles.section} aria-labelledby="audio-title">
      <h3 id="audio-title" class={styles.sectionTitle}>
        {title}
      </h3>
      <ul class={styles.checks}>
        <li class={styles.check}>
          <span class={styles.trackNo} data-filled="true">
            <span class={styles.srOnly}>트랙 </span>1
          </span>
          <span class={styles.trackSources}>최종 믹스</span>
          <span class={styles.checkValue}>본방과 같은 소리</span>
        </li>
        {audio.tracks.map((t) => {
          if (t.mainStream) {
            return (
              <li class={styles.check} key={t.track}>
                <span class={styles.trackNo} data-filled="true">
                  <span class={styles.srOnly}>트랙 </span>
                  {t.track}
                </span>
                <span
                  class={styles.trackSources}
                  data-empty={t.sources.length ? undefined : 'true'}
                  title={t.sources.length ? names(t.sources) : undefined}
                >
                  {t.sources.length ? names(t.sources) : '비어 있음'}
                </span>
                <span class={styles.checkValue}>본방 트랙</span>
              </li>
            );
          }
          const empty = t.sources.length === 0;
          const candidates = pool.filter((s) => !t.sources.some((x) => x.key === s.key));
          return (
            <li class={`${styles.check} ${styles.trackRow}`} key={t.track}>
              <span class={styles.trackNo} data-filled={empty ? 'false' : 'true'}>
                <span class={styles.srOnly}>트랙 </span>
                {t.track}
              </span>
              {empty ? (
                <span class={styles.trackSources} data-empty="true">
                  비어 있음
                </span>
              ) : (
                <span class={styles.chips}>
                  {t.sources.map((s) => (
                    <span class={styles.chip} key={s.key}>
                      <span title={s.name}>{s.name}</span>
                      {canEdit ? (
                        <button
                          type="button"
                          class={styles.chipX}
                          title="트랙에서 빼기"
                          aria-label={`${s.name} 트랙 ${t.track}에서 빼기`}
                          disabled={busy}
                          onClick={() => ask({ key: s.key, name: s.name, track: t.track, action: 'remove' })}
                        >
                          <X size={10} aria-hidden="true" />
                        </button>
                      ) : null}
                    </span>
                  ))}
                </span>
              )}
              <select
                class={styles.addSelect}
                aria-label={`트랙 ${t.track}에 소스 넣기`}
                title={
                  canEdit
                    ? `트랙 ${t.track}에 소스 넣기`
                    : audio.deferred
                      ? '방송·녹화가 끝나면 고를 수 있어요'
                      : '자동 배정을 켜면 고를 수 있어요'
                }
                disabled={!canEdit || busy || candidates.length === 0}
                value=""
                onChange={(e) => {
                  const key = e.currentTarget.value;
                  e.currentTarget.value = '';
                  const s = candidates.find((c) => c.key === key);
                  if (s) ask({ key: s.key, name: s.name, track: t.track, action: 'add' });
                }}
              >
                <option value="" disabled hidden>
                  +
                </option>
                {candidates.map((s) => (
                  <option value={s.key} key={s.key}>
                    {s.name}
                  </option>
                ))}
              </select>
            </li>
          );
        })}
      </ul>
      {error ? (
        <p class={styles.inlineError} role="alert">
          {error}
        </p>
      ) : null}
      {!audio.autoAssign ? <p class={styles.note}>자동 배정을 켜면 여기서 트랙을 고를 수 있어요.</p> : null}
      {audio.deferred ? (
        <p class={styles.note}>방송·녹화 중에 켜서 끝나면 자동 배정을 시작해요. 지금 나가는 트랙은 그대로예요.</p>
      ) : null}
      {audio.autoAssign && mainStreamTracks.length > 0 ? (
        <p class={styles.note}>
          본방 오디오 트랙({mainStreamTracks.join('·')})은 자동 배정에서 뺐어요. 이 트랙에 넣을 소스는 OBS에서 직접 고르세요.
        </p>
      ) : null}
      {audio.mixOnly.length > 0 ? (
        <p class={styles.note} data-tone={audio.applied ? 'warn' : undefined} role={audio.applied ? 'status' : undefined}>
          {audio.applied ? <TriangleAlert size={12} aria-hidden="true" /> : null}
          {audio.applied
            ? `트랙에 없는 소스: ${names(audio.mixOnly)}`
            : `트랙 2~6에 없는 소스: ${names(audio.mixOnly)}`}
        </p>
      ) : null}
      {audio.monitorOnly.length > 0 ? (
        <p class={styles.note}>모니터 전용이라 송출되지 않아요: {names(audio.monitorOnly)}</p>
      ) : null}
      {confirm ? (
        <LiveDialog
          req={confirm}
          onCancel={() => setConfirm(null)}
          onConfirm={async () => {
            const req = confirm;
            setConfirm(null);
            await send(req);
          }}
        />
      ) : null}
    </section>
  );
}

// 방송·녹화 중의 넣기·빼기 확인 — 지금 나가는 트랙이 바뀐다.
function LiveDialog({ req, onCancel, onConfirm }: { req: Request; onCancel: () => void; onConfirm: () => Promise<void> }) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const [busy, setBusy] = useState(false);
  const add = req.action === 'add';

  // 처음 열릴 때만 취소에 포커스를 준다. 이 창은 전송 중에 뜨는데 그때는 통계로 매초 부모가 다시 그려져 onCancel이 새 함수가
  // 되므로, 콜백을 의존성에 두면 포커스가 매초 취소로 돌아간다(「넣기」로 옮긴 포커스가 Enter 직전에 빼앗긴다).
  useEffect(() => {
    cancelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancelRef.current();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div class={styles.scrim} onClick={onCancel}>
      <div
        class={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="track-title"
        aria-describedby="track-desc"
        onClick={(e) => e.stopPropagation()}
      >
        <div>
          <h2 id="track-title" class={styles.cardTitle}>
            {add ? `트랙 ${req.track}에 넣을까요?` : `트랙 ${req.track}에서 뺄까요?`}
          </h2>
          <p id="track-desc" class={styles.cardDesc}>
            {req.name} · 방송 중이라 지금 나가는 트랙이 바뀌어요.
          </p>
        </div>
        <div class={styles.dialogActions}>
          <button ref={cancelRef} type="button" class={styles.dialogButton} data-kind="cancel" onClick={onCancel}>
            취소
          </button>
          <button
            type="button"
            class={styles.dialogButton}
            data-kind="primary"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              await onConfirm();
              setBusy(false);
            }}
          >
            {add ? '넣기' : '빼기'}
          </button>
        </div>
      </div>
    </div>
  );
}
