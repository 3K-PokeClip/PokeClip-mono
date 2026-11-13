import { setFlagsFromString } from 'node:v8';
import { runInNewContext } from 'node:vm';
import type * as Mediabunny from 'mediabunny';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchEditorWindow } from './editorWindow';
import type { WindowBytes } from './fetchWindowBytes';
import { remuxWindow } from './remuxWindow';
import { syntheticWindow } from './remuxWindowFixture';

// 창을 다시 싸는 동안 ③ 입력을 한 덩어리로 이어 붙인 사본을 만들지 않고, 변환이 끝나면 입력 조각을 놓는다
// (_workspace/00_clarifier_qa_prw2.md 「M3 실패 처분」 (가) · 실측 2026-11-13-prw2-m3 phase_split).
// mediabunny 는 진짜다. 원천 · 입력 생성자만 감싸 무엇을 넘겼는지 적는다. fetchEditorWindow 갈래는 받기(fetchWindowBytes)를
// 미리 지은 창을 한 번 넘겨주고 잊는 가짜로 바꾼다 — vi.fn 은 돌려준 값을 기록해 창을 쥐므로 쓰지 않는다.
// 끊기 · 입력 닫기 갈래는 만든 Input · Conversion 을 적어 두고 상태(disposed · state)를 본다.

const seen = vi.hoisted(() => ({
  /** Input 에 넘긴 원천 — 차례대로 */
  sources: [] as unknown[],
  /** 만든 Input · Conversion — 차례대로 */
  inputs: [] as Mediabunny.Input[],
  conversions: [] as Mediabunny.Conversion[],
  /** CustomSource read 가 넘긴 바이트(스트림이면 그 조각들) */
  reads: [] as Uint8Array[],
  /** 출력 확인(BufferSource) 직전에 부를 것 */
  onBufferSource: null as (() => void) | null,
  /** CustomSource read 마다 부를 것 */
  onRead: null as (() => void) | null,
  /** 가짜 fetchWindowBytes 가 한 번 넘겨줄 창 */
  nextWindow: null as unknown,
}));

vi.mock('./fetchWindowBytes', () => ({
  fetchWindowBytes: async () => {
    const window = seen.nextWindow;
    seen.nextWindow = null;
    if (window === null) throw new Error('넘겨줄 창이 없다');
    return window;
  },
}));

vi.mock('mediabunny', async (importOriginal) => {
  const mb = await importOriginal<typeof Mediabunny>();

  class Input extends mb.Input {
    constructor(options: Mediabunny.InputOptions) {
      seen.sources.push(options.source);
      super(options);
      seen.inputs.push(this);
    }
  }

  // 생성자가 비공개라 상속하지 못한다 — 진짜 init 을 감싸 만든 것만 적는다
  const Conversion = {
    init: async (options: Mediabunny.ConversionOptions) => {
      const conversion = await mb.Conversion.init(options);
      seen.conversions.push(conversion);
      return conversion;
    },
  };

  class CustomSource extends mb.CustomSource {
    constructor(options: Mediabunny.CustomSourceOptions) {
      super({
        ...options,
        read: (start, end) => {
          seen.onRead?.();
          return record(options.read(start, end));
        },
      });
    }
  }

  class BufferSource extends mb.BufferSource {
    constructor(buffer: AllowSharedBufferSource) {
      seen.onBufferSource?.();
      super(buffer);
    }
  }

  async function record(
    data:
      Uint8Array | ReadableStream<Uint8Array> | Promise<Uint8Array | ReadableStream<Uint8Array>>,
  ) {
    const got = await data;
    if (got instanceof Uint8Array) {
      seen.reads.push(got);
      return got;
    }
    const [forSource, forRecord] = got.tee();
    const reader = forRecord.getReader();
    for (let next = await reader.read(); !next.done; next = await reader.read()) {
      seen.reads.push(next.value);
    }
    return forSource;
  }

  return { ...mb, Input, Conversion, CustomSource, BufferSource };
});

setFlagsFromString('--expose-gc');
const gc = runInNewContext('gc') as () => void;
const nextTask = () => new Promise((resolve) => setTimeout(resolve, 0));
const totalBytes = (window: WindowBytes) =>
  window.segments.reduce((sum, seg) => sum + seg.bytes.byteLength, window.init.byteLength);

describe('remuxWindow: 큰 창을 이어 붙이지 않고 다시 싼다', () => {
  beforeEach(() => {
    seen.sources.length = 0;
    seen.inputs.length = 0;
    seen.conversions.length = 0;
    seen.reads.length = 0;
    seen.onBufferSource = null;
    seen.onRead = null;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('변환 입력은 조각 배열 원천이고, read 는 조각 바이트의 subarray 만 넘긴다(합성 50 조각)', async () => {
    const mb = await import('mediabunny');
    const window = syntheticWindow(50);
    const owned = new Set<ArrayBufferLike>([
      window.init.buffer,
      ...window.segments.map((seg) => seg.bytes.buffer),
    ]);

    await remuxWindow(window);

    // 원천 · 바이트를 그대로 비교하면 실패 메시지가 수 MiB 를 찍는다 — 이름 · 길이만 본다
    expect(seen.sources.map((source) => (source as object).constructor.name)[0]).toBe(
      mb.CustomSource.name,
    );
    expect(seen.reads.length).toBeGreaterThan(0);
    expect(
      seen.reads.filter((bytes) => !owned.has(bytes.buffer)).map((bytes) => bytes.byteLength),
    ).toEqual([]);
  });

  it('변환하는 동안 새로 할당하는 Uint8Array 는 입력 크기의 1/4 미만이다 — 이은 사본(입력 크기 하나)이 없다', async () => {
    const window = syntheticWindow(50);
    let allocated = 0;
    const counted = new Proxy(Uint8Array, {
      construct(target, args: unknown[]) {
        if (typeof args[0] === 'number') allocated += args[0];
        return Reflect.construct(target, args) as object;
      },
    });
    // 변환이 끝나 출력을 확인하기 직전까지만 센다 — 그 뒤 Blob 사본(출력 크기)은 이 단언의 대상이 아니다
    let duringConversion: number | null = null;
    seen.onBufferSource = () => {
      duringConversion ??= allocated;
    };
    vi.stubGlobal('Uint8Array', counted);

    await remuxWindow(window);

    vi.unstubAllGlobals();
    expect(duringConversion).not.toBeNull();
    expect(duringConversion).toBeLessThan(totalBytes(window) / 4);
  });

  it('출력을 확인할 때는 입력 조각을 이미 놓았다 — 창 바이트를 부른 쪽만 쥐고 있지 않으면 거둔다', async () => {
    const aliveAtCheck = await aliveWhenCheckingOutput((window) => remuxWindow(window));

    expect(aliveAtCheck).toBe(0);
  });

  it('fetchEditorWindow 도 받은 창 바이트를 쥐고 기다리지 않는다', async () => {
    const aliveAtCheck = await aliveWhenCheckingOutput((window) => {
      seen.nextWindow = window;
      return fetchEditorWindow('key-1', []);
    });

    expect(aliveAtCheck).toBe(0);
  });

  it('변환이 끝나면 입력(Input)을 닫는다 — 아직 도는 미리 읽기가 놓은 창 바이트를 읽지 않는다', async () => {
    await remuxWindow(syntheticWindow(8));

    expect(seen.inputs[0]?.disposed).toBe(true);
  });

  it('변환 중에 signal 을 끊으면 변환을 멈추고 AbortError 로 끝난다 · 입력을 닫는다', async () => {
    const controller = new AbortController();
    // 변환이 시작된 뒤(Conversion 이 생긴 뒤) 읽기가 몇 번 지나면 끊는다
    let readsAfterStart = 0;
    seen.onRead = () => {
      if (seen.conversions.length === 0) return;
      readsAfterStart += 1;
      if (readsAfterStart === 3) controller.abort();
    };

    const failure = remuxWindow(syntheticWindow(50), controller.signal);

    await expect(failure).rejects.toMatchObject({ name: 'AbortError' });
    expect(controller.signal.aborted).toBe(true);
    expect(seen.conversions[0]?.state).toBe('canceled');
    expect(seen.inputs[0]?.disposed).toBe(true);
  });

  it('변환 전에 이미 끊긴 signal 이면 변환을 돌리지 않는다', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(remuxWindow(syntheticWindow(8), controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
    expect(seen.conversions.every((conversion) => conversion.state !== 'done')).toBe(true);
  });
});

/**
 * 합성 창을 run 에 넘기고 잊은 뒤, 출력을 확인하는 순간(BufferSource) 거두고 남은 입력 조각 수를 센다. 이 함수도 창을
 * 쥐지 않는다 — 창은 run 이 받는 인자로만 산다
 */
async function aliveWhenCheckingOutput(run: (window: WindowBytes) => Promise<unknown>) {
  let pending: WindowBytes | null = syntheticWindow(8);
  const refs = [pending.init, ...pending.segments.map((seg) => seg.bytes)].map(
    (bytes) => new WeakRef(bytes.buffer),
  );
  let aliveAtCheck: number | null = null;
  seen.onBufferSource = () => {
    // 이 시험의 감시도 원천 · 입력 · 읽은 바이트를 쥔다 — 그것부터 놓는다
    seen.sources.length = 0;
    seen.inputs.length = 0;
    seen.conversions.length = 0;
    seen.reads.length = 0;
    gc();
    aliveAtCheck = refs.filter((ref) => ref.deref() !== undefined).length;
  };
  await nextTask(); // WeakRef 를 만든 작업이 끝나야 대상을 거둘 수 있다

  const done = run(pending);
  pending = null;
  await done;
  return aliveAtCheck;
}
