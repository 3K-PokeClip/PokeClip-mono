import { createContext, forwardRef, useContext, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import { useControllableState } from '../../primitives/hooks/useControllableState';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import { useId } from '../../primitives/hooks/useId';
import { RovingProvider, useRovingItem } from '../../primitives/roving';
import styles from './Tabs.module.css';

interface TabsContextValue {
  value: string | undefined;
  setValue: (v: string) => void;
  baseId: string;
  orientation: 'horizontal' | 'vertical';
}
const TabsContext = createContext<TabsContextValue | null>(null);
function useTabs(): TabsContextValue {
  const c = useContext(TabsContext);
  if (!c) throw new Error('Tabs subcomponents must be used within <Tabs>.');
  return c;
}

export interface TabsProps extends Omit<
  ComponentPropsWithoutRef<'div'>,
  'onChange' | 'defaultValue'
> {
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  orientation?: 'horizontal' | 'vertical';
}

const TabsRoot = forwardRef<HTMLDivElement, TabsProps>(function Tabs(
  { value, defaultValue, onValueChange, orientation = 'horizontal', className, children, ...rest },
  ref,
) {
  const [val, setVal] = useControllableState<string | undefined>({
    value,
    defaultValue,
    onChange: onValueChange as ((v: string | undefined) => void) | undefined,
  });
  const baseId = useId();
  return (
    <TabsContext.Provider value={{ value: val, setValue: (v) => setVal(v), baseId, orientation }}>
      <div
        ref={ref}
        data-orientation={orientation}
        className={clsx(styles.root, className)}
        {...rest}
      >
        {children}
      </div>
    </TabsContext.Provider>
  );
});

const TabsList = forwardRef<HTMLDivElement, ComponentPropsWithoutRef<'div'>>(function TabsList(
  { className, children, ...rest },
  ref,
) {
  const t = useTabs();
  return (
    <RovingProvider
      activeValue={t.value ?? null}
      onActiveChange={t.setValue}
      orientation={t.orientation}
    >
      <div
        ref={ref}
        role="tablist"
        aria-orientation={t.orientation}
        data-orientation={t.orientation}
        className={clsx(styles.list, className)}
        {...rest}
      >
        {children}
      </div>
    </RovingProvider>
  );
});

interface TabsTriggerProps extends ComponentPropsWithoutRef<'button'> {
  value: string;
}
const TabsTrigger = forwardRef<HTMLButtonElement, TabsTriggerProps>(function TabsTrigger(
  { value, className, disabled, ...rest },
  ref,
) {
  const t = useTabs();
  const roving = useRovingItem(value);
  const composedRef = useComposedRefs<HTMLButtonElement>(ref, roving.ref);
  const selected = t.value === value;
  return (
    <button
      {...rest}
      ref={composedRef}
      type="button"
      role="tab"
      id={`${t.baseId}-tab-${value}`}
      aria-selected={selected}
      aria-controls={`${t.baseId}-panel-${value}`}
      data-selected={selected || undefined}
      data-disabled={disabled || undefined}
      disabled={disabled}
      tabIndex={roving.tabIndex}
      onClick={() => t.setValue(value)}
      onKeyDown={roving.onKeyDown}
      onFocus={roving.onFocus}
      className={clsx(styles.trigger, className)}
    />
  );
});

interface TabsPanelProps extends ComponentPropsWithoutRef<'div'> {
  value: string;
}
const TabsPanel = forwardRef<HTMLDivElement, TabsPanelProps>(function TabsPanel(
  { value, className, children, ...rest },
  ref,
) {
  const t = useTabs();
  if (t.value !== value) return null;
  return (
    <div
      ref={ref}
      role="tabpanel"
      id={`${t.baseId}-panel-${value}`}
      aria-labelledby={`${t.baseId}-tab-${value}`}
      tabIndex={0}
      className={clsx(styles.panel, className)}
      {...rest}
    >
      {children}
    </div>
  );
});

/** Tabbed interface. Roving-tabindex keyboard nav with automatic activation. */
export const Tabs = Object.assign(TabsRoot, {
  List: TabsList,
  Trigger: TabsTrigger,
  Panel: TabsPanel,
});
