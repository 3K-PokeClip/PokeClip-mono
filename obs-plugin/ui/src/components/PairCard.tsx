import { useRef, useState } from 'preact/hooks';
import { reasonText } from '../lib/copy';
import { formatPairingInput, isCompletePairingCode } from '../lib/format';
import type { ActionResult } from '../lib/types';
import styles from './dock.module.css';
import { Button } from './ui';

export function PairCard({ onPair }: { onPair: (code: string) => Promise<ActionResult> }) {
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const complete = isCompletePairingCode(code);

  const submit = async (e: Event) => {
    e.preventDefault();
    if (!complete || busy) return;
    setBusy(true);
    setError('');
    const result = await onPair(code);
    setBusy(false);
    if (result.ok) {
      setCode('');
    } else {
      setError(reasonText(result.reason));
      inputRef.current?.select();
    }
  };

  return (
    <section class={styles.card} aria-labelledby="pair-title">
      <div>
        <h2 id="pair-title" class={styles.cardTitle}>
          PokeClip 연결
        </h2>
        <p class={styles.cardDesc}>PokeClip 설정 › 플러그인에서 받은 8자리 코드를 입력하세요.</p>
      </div>

      <form class={styles.codeForm} onSubmit={submit}>
        <input
          ref={inputRef}
          class={styles.codeInput}
          value={code}
          onInput={(e) => {
            setCode(formatPairingInput((e.currentTarget as HTMLInputElement).value));
            setError('');
          }}
          placeholder="XXXX-XXXX"
          inputMode="text"
          autoComplete="one-time-code"
          autoCapitalize="characters"
          spellcheck={false}
          maxLength={9}
          aria-label="연결 코드"
          aria-invalid={error ? 'true' : 'false'}
          aria-describedby={error ? 'pair-error' : 'pair-hint'}
        />
        {error ? (
          <p id="pair-error" class={styles.inlineError} role="alert">
            {error}
          </p>
        ) : null}
        <Button type="submit" block disabled={!complete} loading={busy}>
          {busy ? '연결 중' : '연결'}
        </Button>
        <p id="pair-hint" class={styles.hint}>
          코드는 발급 후 10분 동안 한 번만 쓸 수 있어요.
        </p>
      </form>
    </section>
  );
}
