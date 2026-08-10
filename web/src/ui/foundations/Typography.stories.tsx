import type { Meta, StoryObj } from '@storybook/react-vite';
import { Canvas, Section, TokenRow } from './preview-parts';

const meta: Meta = {
  title: 'Foundations/Typography',
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj;

const sizes: Array<[string, string]> = [
  ['4xl', '48'],
  ['3xl', '36'],
  ['2xl', '28'],
  ['xl', '22'],
  ['lg', '18'],
  ['md', '15 · base'],
  ['sm', '13'],
  ['xs', '12'],
  ['2xs', '11'],
];

const weights: Array<[string, number]> = [
  ['regular', 400],
  ['medium', 500],
  ['semibold', 600],
  ['bold', 700],
];

export const Scale: Story = {
  render: () => (
    <Canvas>
      <Section title="크기 — Type scale (Pretendard)">
        {sizes.map(([k, px]) => (
          <TokenRow key={k} label={`--pc-font-size-${k} (${px})`}>
            <span
              style={{
                fontSize: `var(--pc-font-size-${k})`,
                color: 'var(--pc-color-text-primary)',
              }}
            >
              다람쥐 헌 쳇바퀴에 타고파 · The quick brown fox
            </span>
          </TokenRow>
        ))}
      </Section>
      <Section title="굵기 — Weight">
        {weights.map(([k, n]) => (
          <TokenRow key={k} label={`--pc-weight-${k} (${n})`}>
            <span
              style={{
                fontWeight: n,
                fontSize: 'var(--pc-font-size-lg)',
                color: 'var(--pc-color-text-primary)',
              }}
            >
              PokeClip 디자인 시스템 · 1234567890
            </span>
          </TokenRow>
        ))}
      </Section>
    </Canvas>
  ),
};
