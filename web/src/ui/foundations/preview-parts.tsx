/* Shared presentational helpers for the Foundations documentation stories.
   Not a *.stories file, so its exports are never treated as stories. */
import type { ReactNode } from 'react';

export function Canvas({ children, pad = 32 }: { children: ReactNode; pad?: number }) {
  return (
    <div
      style={{
        padding: pad,
        background: 'var(--pc-color-bg-canvas)',
        color: 'var(--pc-color-text-primary)',
        minHeight: '100vh',
        fontFamily: 'var(--pc-font-sans)',
      }}
    >
      {children}
    </div>
  );
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section style={{ marginBottom: 40 }}>
      <h3
        style={{
          margin: '0 0 16px',
          fontSize: 'var(--pc-font-size-lg)',
          fontWeight: 600,
          color: 'var(--pc-color-text-primary)',
        }}
      >
        {title}
      </h3>
      {children}
    </section>
  );
}

export function Grid({ children, min = 120 }: { children: ReactNode; min?: number }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: `repeat(auto-fill, minmax(${min}px, 1fr))`,
        gap: 12,
      }}
    >
      {children}
    </div>
  );
}

export function Swatch({ name, value, sub }: { name: string; value: string; sub?: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div
        style={{
          height: 56,
          borderRadius: 'var(--pc-radius-md)',
          background: value,
          border: '1px solid var(--pc-color-border-subtle)',
        }}
      />
      <div style={{ fontSize: 12, color: 'var(--pc-color-text-primary)' }}>{name}</div>
      {sub ? (
        <code style={{ fontSize: 11, color: 'var(--pc-color-text-muted)' }}>{sub}</code>
      ) : null}
    </div>
  );
}

export function TokenRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 24,
        padding: '10px 0',
        borderBottom: '1px solid var(--pc-color-border-subtle)',
      }}
    >
      <code
        style={{
          width: 240,
          flexShrink: 0,
          fontSize: 12,
          color: 'var(--pc-color-text-secondary)',
        }}
      >
        {label}
      </code>
      <div style={{ flex: 1 }}>{children}</div>
    </div>
  );
}
