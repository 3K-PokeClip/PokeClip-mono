import { ClipboardPaste } from 'lucide-preact';
import { useRef, useState } from 'preact/hooks';
import { reasonText } from '../lib/copy';
import { extractPairingCode, formatPairingInput, isCompletePairingCode } from '../lib/format';
import type { ActionResult } from '../lib/types';
import styles from './dock.module.css';
import { Button } from './ui';

// POK-495 — 독은 OBS 안의 CEF라 macOS ⌘V가 OBS 「편집 › 붙여넣기(소스)」와 겹쳐 입력칸에 붙지 않는다. ⌘V·Ctrl+V와
// 「붙여넣기」 버튼은 플러그인이 OS 클립보드에서 코드만 골라 준 값을 채운다(onPasteCode). 일반 브라우저의 붙여넣기(onPaste)도 둔다.
export function PairCard({
  onPair,
  onPasteCode,
}: {
  onPair: (code: string) => Promise<ActionResult>;
  onPasteCode: () => Promise<ActionResult & { code?: string }>;
}) {
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const complete = isCompletePairingCode(code);

  const pasteFromClipboard = async () => {
    const r = await onPasteCode();
    if (!r.ok) {
      setError(reasonText(r.reason));
      return;
    }
    setCode(formatPairingInput(r.code ?? ''));
    setError(r.code ? '' : reasonText('no_code_in_clipboard'));
    inputRef.current?.focus();
  };

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
          onKeyDown={(e) => {
            // 플러그인이 클립보드를 읽는다 — preventDefault로 OBS 메뉴(소스 붙여넣기)까지 가지 않게 한다
            const paste = (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.code === 'KeyV';
            if (!paste) return;
            e.preventDefault();
            void pasteFromClipboard();
          }}
          onPaste={(e) => {
            // 브라우저는 붙여넣은 글을 maxLength(9)로 먼저 자른다 — 앞에 공백 하나만 있어도 코드 끝 글자가 잘려
            // 붙여넣기가 안 되는 것처럼 보인다. 클립보드 글을 직접 받아 코드만 고른다.
            const text = e.clipboardData?.getData('text') ?? '';
            if (!text) return;
            e.preventDefault();
            setCode(extractPairingCode(text));
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
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => void pasteFromClipboard()}
        >
          <ClipboardPaste size={13} aria-hidden="true" />
          복사한 코드 붙여넣기
        </Button>
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
