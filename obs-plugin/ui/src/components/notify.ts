import { createContext } from 'preact';
import { useContext } from 'preact/hooks';
import type { ToastTone } from '../lib/copy';

// 독 아래 토스트로 알린다(app.tsx가 띄운다) — 단축키·설정을 바꿨을 때의 「바꿨어요」·「저장했어요」처럼 일이 끝났다는 알림만.
// 고칠 게 있는 오류는 그 칸 옆에 둔다(어디를 고칠지 보이게).
export type Notify = (tone: ToastTone, message: string) => void;

export const NotifyContext = createContext<Notify>(() => {});

export function useNotify(): Notify {
  return useContext(NotifyContext);
}
