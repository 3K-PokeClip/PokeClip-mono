import { describe, expect, it } from 'vitest';
import {
  canRedo,
  canUndo,
  createHistory,
  pushHistory,
  redoHistory,
  undoHistory,
} from './editorHistory';

describe('editorHistory', () => {
  it('쌓고 되돌리고 다시 실행한다', () => {
    let history = createHistory('a');
    history = pushHistory(history, 'b');
    history = pushHistory(history, 'c');
    expect(history.present).toBe('c');

    history = undoHistory(history);
    expect(history.present).toBe('b');
    expect(canRedo(history)).toBe(true);

    history = redoHistory(history);
    expect(history.present).toBe('c');
    expect(canRedo(history)).toBe(false);
  });

  it('되돌린 뒤 새로 쌓으면 다시실행 더미가 사라진다', () => {
    let history = pushHistory(createHistory('a'), 'b');
    history = undoHistory(history);
    history = pushHistory(history, 'c');

    expect(canRedo(history)).toBe(false);
    expect(redoHistory(history).present).toBe('c');
  });

  it('상한을 넘으면 가장 오래된 것부터 버린다', () => {
    let history = createHistory(0);
    for (let i = 1; i <= 5; i += 1) history = pushHistory(history, i, 3);

    expect(history.past).toHaveLength(3);
    expect(history.past[0]).toBe(2);
  });

  it('되돌릴 것이 없으면 같은 객체를 그대로 준다', () => {
    const history = createHistory('a');
    expect(canUndo(history)).toBe(false);
    expect(undoHistory(history)).toBe(history);
    expect(redoHistory(history)).toBe(history);
  });
});
