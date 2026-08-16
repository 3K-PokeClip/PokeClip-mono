import type { Meta, StoryObj } from '@storybook/react-vite';
import { Canvas, Section, TokenRow } from './preview-parts';

const meta: Meta = {
  title: 'Foundations/Spacing',
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj;

const steps: Array<[string, string]> = [
  ['1', '4'],
  ['2', '8'],
  ['3', '12'],
  ['4', '16'],
  ['5', '20'],
  ['6', '24'],
  ['8', '32'],
  ['10', '40'],
  ['12', '48'],
  ['16', '64'],
  ['20', '80'],
  ['24', '96'],
];

export const Scale: Story = {
  render: () => (
    <Canvas>
      <Section title="간격 스케일 — 4px grid">
        {steps.map(([s, px]) => (
          <TokenRow key={s} label={`--pc-space-${s} (${px}px)`}>
            <div
              style={{
                height: 16,
                width: `var(--pc-space-${s})`,
                background: 'var(--pc-color-accent)',
                borderRadius: 'var(--pc-radius-xs)',
              }}
            />
          </TokenRow>
        ))}
      </Section>
    </Canvas>
  ),
};
