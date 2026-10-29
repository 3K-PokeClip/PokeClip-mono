import { Keyboard } from 'lucide-preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Bridge } from '../lib/bridge';
import { reasonText } from '../lib/copy';
import { captureStep, isMacPlatform } from '../lib/hotkey';
import styles from './dock.module.css';
import { Button } from './ui';

// POK-494 — 「순간 표시」 단축키를 OBS 설정에 들어가지 않고 여기서 바로 바꾼다. 「바꾸기」를 누르고 새 조합을 누르면
// 저장한다(Esc 취소). 플러그인이 OBS 설정 창과 같은 자리에 적어 설정 창·다음 실행도 같은 키를 쓴다.
// 받는 동안은 지금 키를 눌러도 마크가 나가지 않는다(플러그인이 막는다).
export function HotkeySection({ hotkey, bridge }: { hotkey: string; bridge: Bridge }) {
  const [capturing, setCapturing] = useState(false);
  const [preview, setPreview] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const mac = isMacPlatform();

  useEffect(() => {
    if (capturing) boxRef.current?.focus();
  }, [capturing]);

  const stop = () => {
    setCapturing(false);
    setPreview('');
    void bridge.hotkeyCapture(false);
  };

  const start = () => {
    setMessage(null);
    setPreview('');
    setCapturing(true);
    void bridge.hotkeyCapture(true);
  };

  const onKeyDown = async (e: KeyboardEvent) => {
    if (busy) return;
    e.preventDefault();
    e.stopPropagation();
    if (e.repeat) return;
    const step = captureStep(e, mac);
    if (step.kind === 'modifiers') {
      setPreview(mac ? `${e.ctrlKey ? '⌃' : ''}${e.altKey ? '⌥' : ''}${e.shiftKey ? '⇧' : ''}${e.metaKey ? '⌘' : ''}` : '');
      return;
    }
    if (step.kind === 'cancel') {
      stop();
      return;
    }
    if (step.kind === 'reject') {
      setMessage({ ok: false, text: reasonText(step.reason) });
      return;
    }
    setPreview(step.label);
    setBusy(true);
    const r = await bridge.setHotkey(step.combo);
    setBusy(false);
    setCapturing(false);
    setPreview('');
    if (r.ok) {
      setMessage({
        ok: true,
        text: r.conflict ? `바꿨어요. OBS 「${r.conflict}」 단축키와 같은 키라 둘 다 눌려요.` : '바꿨어요',
      });
    } else {
      void bridge.hotkeyCapture(false);
      setMessage({ ok: false, text: reasonText(r.reason) });
    }
  };

  return (
    <section class={styles.section} aria-labelledby="hotkey-title">
      <h3 id="hotkey-title" class={styles.sectionTitle}>
        순간 표시 단축키
      </h3>
      {capturing ? (
        <div
          ref={boxRef}
          class={styles.hotkeyCapture}
          tabIndex={0}
          role="textbox"
          aria-label="새 단축키를 누르세요. Esc를 누르면 취소해요."
          onKeyDown={onKeyDown}
          onBlur={() => {
            if (!busy) stop();
          }}
        >
          <Keyboard size={14} aria-hidden="true" />
          <span>{preview || '새 단축키를 누르세요'}</span>
          <span class={styles.hotkeyEsc}>Esc 취소</span>
        </div>
      ) : (
        <div class={styles.hotkeyRow}>
          {hotkey ? <kbd class={styles.hotkeyKbd}>{hotkey}</kbd> : <span class={styles.hotkeyNone}>단축키 없음</span>}
          <Button type="button" variant="soft" size="sm" onClick={start}>
            바꾸기
          </Button>
        </div>
      )}
      {message ? (
        <p class={message.ok ? styles.saved : styles.inlineError} role="status">
          {message.text}
        </p>
      ) : (
        <p class={styles.note}>OBS가 뒤에 있어도 방송 중 이 키로 지금 순간을 표시해요.</p>
      )}
    </section>
  );
}
