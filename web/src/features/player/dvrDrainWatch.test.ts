import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DRAIN_STALL_SILENT_MS, DRAIN_STALL_WAITING_MS, watchDvrDrain } from './dvrDrainWatch';

// 404 로 받기를 멈춘 되감기 재생기가 남은 버퍼를 다 쓰고 멎었는지(고갈) 본다. 미디어 사건과 영상 노드 값만 보는 순수 DOM
// 코드라, 값을 손으로 바꾸는 영상 노드 하나와 손으로 미는 시계로 잰다(타이머가 없어 가짜 타이머는 쓰지 않는다).

interface MediaState {
  currentTime: number;
  paused: boolean;
  seeking: boolean;
  ended: boolean;
  error: MediaError | null;
  playbackRate: number;
  readyState: number;
  /** [시작, 끝] 초 구간들 — video.buffered */
  buffered: Array<[number, number]>;
}

const MEDIA_KEYS = [
  'currentTime',
  'paused',
  'seeking',
  'ended',
  'error',
  'playbackRate',
  'readyState',
] as const;

/** 기본값 = 10초 자리에서 재생 중 · 받아 둔 영상이 0.3초 남음 · 화면은 섰지만 이어 틀 데이터가 없음(readyState 2) */
function fakeVideo(initial: Partial<MediaState>) {
  const state: MediaState = {
    currentTime: 10,
    paused: false,
    seeking: false,
    ended: false,
    error: null,
    playbackRate: 1,
    readyState: 2,
    buffered: [[0, 10.3]],
    ...initial,
  };
  const video = document.createElement('video');
  for (const key of MEDIA_KEYS) {
    Object.defineProperty(video, key, { get: () => state[key], configurable: true });
  }
  // 브라우저의 TimeRanges 처럼 없는 번호는 던진다
  const range = (i: number) => {
    const found = state.buffered[i];
    if (found === undefined) throw new RangeError(`buffered 구간 ${i} 없음`);
    return found;
  };
  Object.defineProperty(video, 'buffered', {
    get: () => ({
      length: state.buffered.length,
      start: (i: number) => range(i)[0],
      end: (i: number) => range(i)[1],
    }),
    configurable: true,
  });
  return { video, state };
}

const CHECK_EVENTS = ['waiting', 'stalled', 'timeupdate', 'seeked', 'playing', 'canplay'] as const;

function setup(
  initial: Partial<MediaState> = {},
  hooks: { settleEnd?: () => boolean; epoch?: () => number } = {},
) {
  const { video, state } = fakeVideo(initial);
  const clock = { ms: 0 };
  const onChange = vi.fn();
  const settleEnd = vi.fn(hooks.settleEnd ?? (() => false));
  const watch = watchDvrDrain(video, {
    settleEnd,
    epoch: hooks.epoch ?? (() => 0),
    onChange,
    now: () => clock.ms,
  });
  const advance = (ms: number) => {
    clock.ms += ms;
  };
  const fire = (type: string) => video.dispatchEvent(new Event(type));
  /**
   * 시크 한 번 — HTML 표준의 순서대로다: seeking 이 서고 seeking 사건, (도착하면) seeking 이 내려간 뒤 timeupdate ·
   * seeked 사건 차례다(html.spec.whatwg.org media.html 「seeking」 알고리즘의 마지막 단계)
   */
  const seekTo = (to: number, { arrive = true } = {}) => {
    state.currentTime = to;
    state.seeking = true;
    fire('seeking');
    if (!arrive) return;
    state.seeking = false;
    fire('timeupdate');
    fire('seeked');
  };
  return { video, state, onChange, settleEnd, watch, advance, fire, seekTo };
}

describe('watchDvrDrain', () => {
  beforeEach(() => {
    vi.spyOn(console, 'info').mockImplementation(() => {});
  });
  afterEach(() => vi.restoreAllMocks());

  describe('고갈 판정', () => {
    it('앞 버퍼가 0.5초 아래로 멎고 waiting 을 봤으면 1,000ms 뒤 첫 점검에서 한 번만 알린다', () => {
      const { onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');

      advance(DRAIN_STALL_WAITING_MS - 1);
      watch.check();
      expect(onChange).not.toHaveBeenCalled();

      advance(1);
      watch.check();
      expect(onChange.mock.calls).toEqual([[true, 10]]);

      advance(5_000);
      watch.check();
      fire('stalled');
      fire('waiting');
      expect(onChange).toHaveBeenCalledTimes(1);
    });

    it('waiting 을 못 봤으면 2,000ms 를 기다린다 — stalled 는 waiting 으로 치지 않는다', () => {
      const { onChange, watch, advance, fire } = setup();
      watch.arm();

      advance(DRAIN_STALL_SILENT_MS - 1);
      fire('stalled');
      expect(onChange).not.toHaveBeenCalled();

      advance(1);
      fire('stalled');
      expect(onChange.mock.calls).toEqual([[true, 10]]);
    });

    it('기다리는 시간은 waiting 을 봤으면 1,000ms · 못 봤으면 2,000ms 다', () => {
      expect([DRAIN_STALL_WAITING_MS, DRAIN_STALL_SILENT_MS]).toEqual([1_000, 2_000]);
    });

    it('후보 기간 중간에 온 waiting 도 1,000ms 갈래로 센다', () => {
      const { onChange, watch, advance, fire } = setup();
      watch.arm();
      advance(500);
      fire('waiting');

      advance(DRAIN_STALL_WAITING_MS - 500 - 1);
      watch.check();
      expect(onChange).not.toHaveBeenCalled();

      advance(1);
      watch.check();
      expect(onChange.mock.calls).toEqual([[true, 10]]);
    });

    it('지금 위치를 담은 구간의 잔량만 센다 — 뒤쪽 구간은 앞 버퍼에 넣지 않는다', () => {
      const { onChange, watch, advance } = setup({
        buffered: [
          [0, 10.3],
          [10.6, 20],
        ],
      });
      watch.arm();

      advance(DRAIN_STALL_SILENT_MS);
      watch.check();

      expect(onChange.mock.calls).toEqual([[true, 10]]);
    });

    it('지금 위치가 어느 구간에도 없으면 앞 버퍼는 0 이다', () => {
      const { onChange, watch, advance } = setup({ buffered: [[12, 20]] });
      watch.arm();

      advance(DRAIN_STALL_SILENT_MS);
      watch.check();

      expect(onChange.mock.calls).toEqual([[true, 10]]);
    });

    it('앞 버퍼가 넉넉하면 stalled 가 이어져도 알리지 않는다', () => {
      const { onChange, watch, advance, fire } = setup({ buffered: [[0, 12]] });
      watch.arm();

      for (let i = 0; i < 6; i++) {
        advance(1_000);
        fire('stalled');
      }

      expect(onChange).not.toHaveBeenCalled();
    });

    it.each([
      ['멈춤', { paused: true }],
      ['시크 중', { seeking: true }],
      ['끝남', { ended: true }],
      ['미디어 오류', { error: {} as MediaError }],
      ['재생 속도 0', { playbackRate: 0 }],
      ['이어 틀 데이터 있음(readyState 3)', { readyState: 3 }],
    ] as const)('%s 이면 멎은 것으로 보지 않는다', (_name, media) => {
      const { onChange, watch, advance, fire } = setup(media);
      watch.arm();
      fire('waiting');

      advance(DRAIN_STALL_SILENT_MS * 2);
      watch.check();

      expect(onChange).not.toHaveBeenCalled();
    });

    it('고갈을 알릴 때 멎은 자리를 콘솔에 한 줄 남긴다', () => {
      const { watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();

      expect(console.info).toHaveBeenCalledWith('[player] 받기를 멈춘 뒤 재생이 멎었어요:', 10);
    });
  });

  describe('진행 셈(후보 기간 누적)', () => {
    it('낮은 버퍼라도 제 속도로 나아가면 알리지 않는다', () => {
      const { state, onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');

      for (let i = 0; i < 12; i++) {
        advance(250);
        state.currentTime += 0.25;
        state.buffered = [[0, state.currentTime + 0.3]];
        fire('timeupdate');
      }

      expect(onChange).not.toHaveBeenCalled();
    });

    it('조금씩 나아간 것이 쌓여 0.1초를 넘으면 후보를 다시 잡는다', () => {
      const { state, onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');

      // 300ms 마다 0.04초씩 — 직전 점검과의 차이는 늘 0.1초 아래지만, 후보 시작부터 쌓으면 0.12초에서 넘는다
      for (let i = 0; i < 8; i++) {
        advance(300);
        state.currentTime += 0.04;
        state.buffered = [[0, state.currentTime + 0.3]];
        fire('timeupdate');
      }

      expect(onChange).not.toHaveBeenCalled();
    });

    it('쌓인 진행이 0.1초 아래로 1,000ms 이어지면 알린다', () => {
      const { state, onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');

      for (let i = 0; i < 4; i++) {
        advance(250);
        state.currentTime += 0.02;
        state.buffered = [[0, state.currentTime + 0.3]];
        fire('timeupdate');
      }

      expect(onChange.mock.calls).toEqual([[true, state.currentTime]]);
    });
  });

  describe('멈춤', () => {
    it('후보 중 멈추면 후보를 버리고, 다시 틀면 처음부터 잰다', () => {
      const { state, onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');
      advance(600);

      state.paused = true;
      fire('pause');
      advance(600);
      state.paused = false;
      fire('playing');
      fire('waiting');
      expect(onChange).not.toHaveBeenCalled();

      advance(DRAIN_STALL_WAITING_MS - 1);
      watch.check();
      expect(onChange).not.toHaveBeenCalled();

      advance(1);
      watch.check();
      expect(onChange.mock.calls).toEqual([[true, 10]]);
    });
  });

  describe('끝 증거 먼저', () => {
    it('끝 증거가 서는 점검에서는 고갈을 보지 않는다', () => {
      let endReached = false;
      const { onChange, settleEnd, watch, advance, fire } = setup(
        { currentTime: 59.4, buffered: [[0, 59.6]] },
        { settleEnd: () => endReached },
      );
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);

      endReached = true;
      watch.check();
      advance(DRAIN_STALL_SILENT_MS);
      watch.check();

      expect(settleEnd).toHaveBeenCalled();
      expect(onChange).not.toHaveBeenCalled();
    });

    it('끝 증거가 없으면 목록 끝 1초 안의 정체도 고갈로 알린다', () => {
      const { onChange, watch, advance, fire } = setup({
        currentTime: 59.4,
        buffered: [[0, 59.6]],
      });
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();

      expect(onChange.mock.calls).toEqual([[true, 59.4]]);
    });

    it.each(CHECK_EVENTS)('%s 계기에서도 판정 전에 끝 증거를 먼저 셈한다', (event) => {
      let endReached = false;
      const { onChange, settleEnd, watch, advance, fire } = setup(
        {},
        { settleEnd: () => endReached },
      );
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      endReached = true;
      settleEnd.mockClear();

      fire(event);

      expect(settleEnd).toHaveBeenCalledTimes(1);
      expect(onChange).not.toHaveBeenCalled();
    });
  });

  describe('무장', () => {
    it('무장 전에는 정체가 이어져도 알리지 않는다', () => {
      const { onChange, watch, advance, fire } = setup();
      fire('waiting');

      advance(DRAIN_STALL_SILENT_MS * 2);
      watch.check();
      fire('stalled');

      expect(onChange).not.toHaveBeenCalled();
    });

    it('무장 전에는 끝 증거 셈도 부르지 않는다 — 비무장 재생기의 끝 판정 시각을 바꾸지 않는다', () => {
      const { settleEnd, watch, advance, fire } = setup();

      for (const event of CHECK_EVENTS) fire(event);
      advance(DRAIN_STALL_SILENT_MS);
      watch.check();

      expect(settleEnd).not.toHaveBeenCalled();
    });

    it('무장하면 그 자리에서 한 번 점검한다', () => {
      const { settleEnd, watch } = setup();

      watch.arm();

      expect(settleEnd).toHaveBeenCalledTimes(1);
    });

    it('다시 무장해도 재던 후보를 처음부터 다시 잡지 않는다', () => {
      const { onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');
      advance(600);

      watch.arm();
      advance(DRAIN_STALL_WAITING_MS - 600);
      watch.check();

      expect(onChange.mock.calls).toEqual([[true, 10]]);
    });

    it('무장하는 순간 버퍼 밖으로 시크하는 중이면 곧바로 그 목표로 알린다', () => {
      const { onChange, watch } = setup({ seeking: true, currentTime: 30 });

      watch.arm();

      expect(onChange.mock.calls).toEqual([[true, 30]]);
    });

    it('무장하는 순간 버퍼 안으로 시크하는 중이면 곧바로 알리지 않는다', () => {
      const { onChange, watch } = setup({ seeking: true, currentTime: 5 });

      watch.arm();

      expect(onChange).not.toHaveBeenCalled();
    });
  });

  describe('시크', () => {
    it('무장 뒤 버퍼 밖으로 시크하면 seeked 를 기다리지 않고 곧바로 그 목표로 알린다', () => {
      const { onChange, watch, seekTo } = setup();
      watch.arm();

      seekTo(30, { arrive: false });

      expect(onChange.mock.calls).toEqual([[true, 30]]);
    });

    it('무장 뒤 버퍼 안 시크는 곧바로 알리지 않는다', () => {
      const { onChange, watch, seekTo } = setup();
      watch.arm();

      seekTo(5);

      expect(onChange).not.toHaveBeenCalled();
    });

    it('고갈 뒤 시크 요청만으로는 풀지 않고, 시크 목표에서 실제로 0.1초 넘게 나아가야 푼다', () => {
      const { state, onChange, watch, advance, fire, seekTo } = setup();
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();
      expect(onChange.mock.calls).toEqual([[true, 10]]);

      seekTo(5);
      expect(onChange).toHaveBeenCalledTimes(1);

      state.currentTime = 5.05;
      fire('timeupdate');
      expect(onChange).toHaveBeenCalledTimes(1);

      state.currentTime = 5.15;
      fire('timeupdate');
      expect(onChange.mock.calls).toEqual([
        [true, 10],
        [false, 5.15],
      ]);
    });

    it('고갈 뒤 앞으로 버퍼 안 시크도 시크 거리를 나아간 것으로 세지 않는다 — 시크가 닿은 자리에서 0.1초 넘게 나아가야 푼다', () => {
      const { state, onChange, watch, advance, fire, seekTo } = setup();
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();
      expect(onChange.mock.calls).toEqual([[true, 10]]);

      seekTo(10.2);
      watch.check();
      expect(onChange).toHaveBeenCalledTimes(1);

      state.currentTime = 10.35;
      fire('timeupdate');
      expect(onChange.mock.calls).toEqual([
        [true, 10],
        [false, 10.35],
      ]);
    });

    it('고갈 중 seeking 이 선 동안에는 자리가 나아가 보여도 풀지 않는다', () => {
      const { state, onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();

      state.seeking = true;
      state.currentTime = 10.25;
      fire('timeupdate');
      watch.check();

      expect(onChange).toHaveBeenCalledTimes(1);
    });

    it('고갈 중 뒤로 물러난 자리는 나아간 것이 아니다', () => {
      const { state, onChange, watch, advance, fire } = setup();
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();

      state.currentTime = 9.8;
      fire('timeupdate');

      expect(onChange).toHaveBeenCalledTimes(1);
    });

    it('풀린 뒤 다시 멎으면 한 번 더 알린다 — 무장은 그대로다', () => {
      const { state, onChange, watch, advance, fire, seekTo } = setup();
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();
      seekTo(5);
      state.currentTime = 5.15;
      fire('timeupdate');

      state.currentTime = 10.1;
      fire('timeupdate');
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();

      expect(onChange.mock.calls).toEqual([
        [true, 10],
        [false, 5.15],
        [true, 10.1],
      ]);
    });

    it('고갈 중 멈춘 채로는 나아간 것으로 보지 않는다', () => {
      const { state, onChange, watch, advance, fire, seekTo } = setup();
      watch.arm();
      fire('waiting');
      advance(DRAIN_STALL_WAITING_MS);
      watch.check();
      seekTo(5);

      state.paused = true;
      state.currentTime = 6;
      fire('timeupdate');

      expect(onChange).toHaveBeenCalledTimes(1);
    });

    it('고갈 중 같은 위치 시대의 버퍼 밖 시크는 다시 알리지 않고, 시대가 바뀐 버퍼 밖 시크는 다시 알린다', () => {
      let epoch = 0;
      const { onChange, watch, seekTo } = setup({}, { epoch: () => epoch });
      watch.arm();
      seekTo(30, { arrive: false });

      seekTo(40, { arrive: false });
      expect(onChange.mock.calls).toEqual([[true, 30]]);

      epoch = 1;
      seekTo(50, { arrive: false });
      expect(onChange.mock.calls).toEqual([
        [true, 30],
        [true, 50],
      ]);
    });
  });

  describe('정리', () => {
    it('정리한 뒤에는 늦게 온 사건 · 점검 · 무장으로 알리지 않고 끝 증거 셈도 부르지 않는다', () => {
      const { onChange, settleEnd, watch, advance, fire, seekTo } = setup();
      watch.arm();
      fire('waiting');
      settleEnd.mockClear();

      watch.dispose();
      advance(DRAIN_STALL_SILENT_MS * 2);
      for (const event of CHECK_EVENTS) fire(event);
      watch.check();
      watch.arm();
      seekTo(30, { arrive: false });

      expect(onChange).not.toHaveBeenCalled();
      expect(settleEnd).not.toHaveBeenCalled();
    });
  });
});
