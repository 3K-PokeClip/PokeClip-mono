import { TriangleAlert } from 'lucide-preact';
import { useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { reasonText } from '../lib/copy';
import type { BridgeState } from '../lib/types';
import styles from './dock.module.css';
import { Button } from './ui';

// A2 — 오디오 트랙 자동 배정은 기본으로 꺼져 있다(사용자 결정 2026-10-01). 페어링 뒤 한 번만 묻는다:
// 왜 나누는지(웹 편집기에서 소리를 따로 켜고 끔)와, 직접 짜 둔 트랙이 있으면 덮어쓴다는 것.
// 답(켜기·나중에)은 플러그인이 기억해 다시 묻지 않는다. 고급 설정 스위치로 언제든 바꿀 수 있다.
export function AudioAssignPrompt({ state, bridge }: { state: BridgeState; bridge: Bridge }) {
  const [busy, setBusy] = useState<'on' | 'later' | null>(null);
  const [error, setError] = useState('');

  const answer = async (enable: boolean) => {
    setBusy(enable ? 'on' : 'later');
    setError('');
    const r = await bridge.putSettings(enable ? { audio_auto_assign: true } : { audio_assign_prompted: true });
    setBusy(null);
    if (!r.ok) setError(reasonText(r.reason));
  };

  return (
    <section class={styles.card} aria-labelledby="audio-prompt-title">
      <div>
        <h2 id="audio-prompt-title" class={styles.cardTitle}>
          오디오 트랙을 소스별로 나눌까요?
        </h2>
        <p class={styles.cardDesc}>
          마이크·게임·BGM처럼 소리 나는 소스를 트랙 2~6에 하나씩 나눠 보내면, PokeClip 웹 편집기에서 소리를 따로 켜고 끌 수
          있어요(예: BGM 뺀 클립). 트랙 1(방송 소리)과 방송 트랙은 그대로예요.
        </p>
      </div>
      {state.audio.customRouting ? (
        <p class={styles.note} data-tone="warn">
          <TriangleAlert size={12} aria-hidden="true" />
          지금 OBS 트랙 2~6에 직접 짜 둔 구성이 있어요. 켜면 PokeClip 배정으로 바꾸고, 나중에 끄면 원래대로 되돌려요.
        </p>
      ) : null}
      {error ? (
        <p class={styles.inlineError} role="alert">
          {error}
        </p>
      ) : null}
      <div class={styles.settingsActions}>
        <Button variant="ghost" size="sm" loading={busy === 'later'} disabled={busy !== null} onClick={() => answer(false)}>
          나중에
        </Button>
        <Button size="sm" loading={busy === 'on'} disabled={busy !== null} onClick={() => answer(true)}>
          {state.audio.customRouting ? '덮어쓰고 켜기' : '켜기'}
        </Button>
      </div>
      <p class={styles.cardDesc}>고급 설정 › 오디오 트랙 자동 배정에서 언제든 바꿀 수 있어요.</p>
    </section>
  );
}
