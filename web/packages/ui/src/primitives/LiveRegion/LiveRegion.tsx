import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { VisuallyHidden } from '../VisuallyHidden';

export type Politeness = 'polite' | 'assertive';

interface AnnouncerContextValue {
  announce: (message: string, politeness?: Politeness) => void;
}
const AnnouncerContext = createContext<AnnouncerContextValue | null>(null);

/** Provides ARIA live regions so dynamic status (toasts, async results) reaches screen readers. */
export function LiveRegionProvider({ children }: { children: ReactNode }) {
  const [polite, setPolite] = useState('');
  const [assertive, setAssertive] = useState('');

  const announce = useCallback((message: string, politeness: Politeness = 'polite') => {
    const set = politeness === 'assertive' ? setAssertive : setPolite;
    set('');
    requestAnimationFrame(() => set(message));
  }, []);

  return (
    <AnnouncerContext.Provider value={{ announce }}>
      {children}
      <VisuallyHidden role="status" aria-live="polite" aria-atomic="true">
        {polite}
      </VisuallyHidden>
      <VisuallyHidden role="alert" aria-live="assertive" aria-atomic="true">
        {assertive}
      </VisuallyHidden>
    </AnnouncerContext.Provider>
  );
}

/** Announce a message via the surrounding {@link LiveRegionProvider}. */
export function useAnnouncer(): AnnouncerContextValue {
  const ctx = useContext(AnnouncerContext);
  if (!ctx) throw new Error('useAnnouncer must be used within a LiveRegionProvider.');
  return ctx;
}
