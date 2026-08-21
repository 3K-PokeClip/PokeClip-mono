// jsdom에는 BroadcastChannel이 없고, vitest jsdom 환경의 전역에는 Node 내장이 새어 들어온다
// (비동기 전달·이벤트 루프 참조 — 핸들이 남으면 워커가 늦게 닫힌다). 같은 프로세스 안의
// "탭"들을 결정적으로 잇는 가짜로 바꾼다. 기본은 동기 전달이고, 순서를 다투는 테스트는
// holdBroadcasts()/flushBroadcasts()로 붙잡았다 푼다. 보낸 인스턴스는 받지 않는다(명세 동일).

type MessageListener = (ev: MessageEvent) => void;

const registry = new Map<string, Set<FakeBroadcastChannel>>();
let held: Array<() => void> | null = null;

export class FakeBroadcastChannel {
  onmessage: MessageListener | null = null;
  private readonly listeners = new Set<MessageListener>();
  private closed = false;

  constructor(public readonly name: string) {
    const peers = registry.get(name) ?? new Set<FakeBroadcastChannel>();
    peers.add(this);
    registry.set(name, peers);
  }

  postMessage(data: unknown) {
    if (this.closed) throw new DOMException('채널이 닫혔다', 'InvalidStateError');
    const payload = structuredClone(data); // 실제처럼 참조가 아니라 복제본을 준다
    for (const peer of registry.get(this.name) ?? []) {
      if (peer === this || peer.closed) continue;
      const deliver = () =>
        peer.dispatch(new MessageEvent('message', { data: structuredClone(payload) }));
      if (held !== null) held.push(deliver);
      else deliver();
    }
  }

  addEventListener(type: string, listener: MessageListener) {
    if (type === 'message') this.listeners.add(listener);
  }

  removeEventListener(type: string, listener: MessageListener) {
    if (type === 'message') this.listeners.delete(listener);
  }

  close() {
    this.closed = true;
    registry.get(this.name)?.delete(this);
  }

  private dispatch(ev: MessageEvent) {
    this.onmessage?.(ev);
    this.listeners.forEach((listener) => listener(ev));
  }
}

/**
 * vi.stubGlobal을 쓰지 않는다 — 여러 테스트 파일의 afterEach(vi.unstubAllGlobals)가 "원본"으로
 * 되돌리는데, 그 원본이 Node 내장 BroadcastChannel이다.
 */
export function installFakeBroadcastChannel() {
  Object.defineProperty(globalThis, 'BroadcastChannel', {
    value: FakeBroadcastChannel,
    configurable: true,
    writable: true,
    enumerable: true,
  });
}

/**
 * 채널 레지스트리는 비우지 않는다 — stores/auth는 한 번 만든 채널을 모듈 싱글턴으로 계속 쓰므로,
 * 비우면 같은 파일의 두 번째 테스트부터 전파가 끊긴다. 지난 테스트의 고아 인스턴스가 메시지를
 * 더 받지만 수신 경로는 localStorage를 쓰지 않아 무해하다. 붙잡아 둔 채 실패한 테스트가 다음
 * 테스트를 막지 않도록 보류 큐만 버린다.
 */
export function resetFakeBroadcastChannels() {
  held = null;
}

/** 이후의 postMessage를 전달하지 않고 쌓아 둔다 — storage 이벤트가 먼저 오는 순서를 만들 때. */
export function holdBroadcasts() {
  held = [];
}

/** 쌓아 둔 메시지를 순서대로 전달하고 동기 전달로 돌아간다. */
export function flushBroadcasts() {
  const queue = held ?? [];
  held = null;
  queue.forEach((deliver) => deliver());
}
