import type { Meta, StoryObj } from '@storybook/react-vite';
import { Canvas, Grid, Section } from './preview-parts';

const meta: Meta = {
  title: 'Foundations/Elevation',
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj;

const shadows = ['sm', 'md', 'lg', 'xl', 'glow-accent'];

export const Shadows: Story = {
  render: () => (
    <Canvas>
      <Section title="그림자 — Elevation (다크 UI 튜닝)">
        <Grid min={170}>
          {shadows.map((s) => (
            <div
              key={s}
              style={{ display: 'flex', flexDirection: 'column', gap: 14, alignItems: 'center' }}
            >
              <div
                style={{
                  width: 130,
                  height: 84,
                  background: 'var(--pc-color-bg-surface-raised)',
                  borderRadius: 'var(--pc-radius-lg)',
                  boxShadow: `var(--pc-shadow-${s})`,
                }}
              />
              <code style={{ fontSize: 12, color: 'var(--pc-color-text-secondary)' }}>
                shadow-{s}
              </code>
            </div>
          ))}
        </Grid>
      </Section>
    </Canvas>
  ),
};
