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
// bare — 독 설정 탭 맨 위(카드 테두리 없이)
// 받기는 CAPTURE_MAX_MS 뒤 저절로 끝난다 — 플러그인 막기(kHotkeyCaptureMaxMs 30초)보다 먼저 닫아, 막기가 풀린 채
// 상자만 열려 지금 키가 마크로 나가는 일이 없게. 저장·Esc·시간 초과로 끝나면 「바꾸기」로 포커스를 돌려준다
// (다른 곳을 눌러 끝났으면 그 자리를 존중해 돌려주지 않는다).
const CAPTURE_MAX_MS = 25_000; // 플러그인 mark-policy.hpp kHotkeyCaptureUiMs와 같은 값 — 막기 30초보다 먼저
const CHANGE_BUTTON_ID = 'hotkey-change';
type Message = { tone: 'ok' | 'error' | 'info'; text: string };

export function HotkeySection({ hotkey, bridge, bare }: { hotkey: string; bridge: Bridge; bare?: boolean }) {
  const [capturing, setCapturing] = useState(false);
  const [preview, setPreview] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const busyRef = useRef(false);
  const capturingRef = useRef(false); // 상자가 사라질 때도 blur가 온다 — 이미 끝난 받기를 「다른 곳을 눌러 끝남」으로 덮지 않게
  const restoreFocus = useRef(false);
  const mac = isMacPlatform();

  useEffect(() => {
    if (capturing) {
      boxRef.current?.focus();
      return;
    }
    if (restoreFocus.current) {
      restoreFocus.current = false;
      document.getElementById(CHANGE_BUTTON_ID)?.focus();
    }
  }, [capturing]);

  const stop = (focusBack: boolean) => {
    capturingRef.current = false;
    restoreFocus.current = focusBack;
    setCapturing(false);
    setPreview('');
    void bridge.hotkeyCapture(false);
  };

  useEffect(() => {
    if (!capturing) return;
    const t = window.setTimeout(() => {
      if (busyRef.current) return; // 저장 중 — 결과가 받기를 끝낸다
      setMessage({ tone: 'info', text: '시간이 지나 단축키를 그대로 뒀어요' });
      stop(true);
    }, CAPTURE_MAX_MS);
    return () => window.clearTimeout(t);
  }, [capturing]);

  const start = () => {
    setMessage(null);
    setPreview('');
    capturingRef.current = true;
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
      stop(true);
      return;
    }
    if (step.kind === 'reject') {
      setMessage({ tone: 'error', text: reasonText(step.reason) });
      return;
    }
    setPreview(step.label);
    setBusy(true);
    busyRef.current = true;
    const r = await bridge.setHotkey(step.combo);
    setBusy(false);
    busyRef.current = false;
    capturingRef.current = false;
    restoreFocus.current = true;
    setCapturing(false);
    setPreview('');
    if (r.ok) {
      setMessage({
        tone: 'ok',
        text: r.conflict ? `바꿨어요. OBS 「${r.conflict}」 단축키와 같은 키라 둘 다 눌려요.` : '바꿨어요',
      });
    } else {
      void bridge.hotkeyCapture(false);
      setMessage({ tone: 'error', text: reasonText(r.reason) });
    }
  };

  return (
    <section class={bare ? styles.bareSection : styles.section} aria-labelledby="hotkey-title">
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
            if (capturingRef.current && !busyRef.current) stop(false);
          }}
        >
          <Keyboard size={14} aria-hidden="true" />
          <span>{preview || '새 단축키를 누르세요'}</span>
          <span class={styles.hotkeyEsc}>Esc 취소</span>
        </div>
      ) : (
        <div class={styles.hotkeyRow}>
          {hotkey ? <kbd class={styles.hotkeyKbd}>{hotkey}</kbd> : <span class={styles.hotkeyNone}>단축키 없음</span>}
          <Button id={CHANGE_BUTTON_ID} type="button" variant="soft" size="sm" onClick={start}>
            바꾸기
          </Button>
        </div>
      )}
      {message ? (
        <p
          class={message.tone === 'ok' ? styles.saved : message.tone === 'error' ? styles.inlineError : styles.note}
          role="status"
        >
          {message.text}
        </p>
      ) : (
        <p class={styles.note}>OBS가 뒤에 있어도 방송 중 이 키로 지금 순간을 표시해요.</p>
      )}
    </section>
  );
}
