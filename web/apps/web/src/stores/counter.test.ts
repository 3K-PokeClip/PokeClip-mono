import { useCounterStore } from './counter';

describe('useCounterStore', () => {
  beforeEach(() => {
    useCounterStore.setState({ count: 0 });
  });

  it('increment/decrement로 카운트를 조작한다', () => {
    useCounterStore.getState().increment();
    useCounterStore.getState().increment();
    expect(useCounterStore.getState().count).toBe(2);

    useCounterStore.getState().decrement();
    expect(useCounterStore.getState().count).toBe(1);
  });

  it('reset은 0으로 되돌린다', () => {
    useCounterStore.getState().increment();
    useCounterStore.getState().reset();
    expect(useCounterStore.getState().count).toBe(0);
  });
});
