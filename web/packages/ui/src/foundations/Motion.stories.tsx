import type { Meta, StoryObj } from '@storybook/react-vite';
import { Canvas, Section, TokenRow } from './preview-parts';

const meta: Meta = {
  title: 'Foundations/Motion',
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj;

const durations = ['instant', 'fast', 'normal', 'slow', 'slower'];
const easings = ['standard', 'decelerate', 'accelerate', 'emphasized'];

export const Tokens: Story = {
  render: () => (
    <Canvas>
      <style>{`@keyframes pc-demo-slide { 0%,100%{transform:translateX(0)} 50%{transform:translateX(140px)} }`}</style>
      <Section title="지속시간 — Duration (반복 데모)">
        {durations.map((d) => (
          <TokenRow key={d} label={`--pc-duration-${d}`}>
            <div
              style={{
                width: 32,
                height: 32,
                borderRadius: 'var(--pc-radius-md)',
                background: 'var(--pc-color-accent)',
                animationName: 'pc-demo-slide',
                animationDuration: `calc(var(--pc-duration-${d}) * 8)`,
                animationTimingFunction: 'var(--pc-ease-standard)',
                animationIterationCount: 'infinite',
              }}
            />
          </TokenRow>
        ))}
      </Section>
      <Section title="이징 — Easing">
        {easings.map((e) => (
          <TokenRow key={e} label={`--pc-ease-${e}`}>
            <div
              style={{
                width: 32,
                height: 32,
                borderRadius: 'var(--pc-radius-md)',
                background: 'var(--pc-color-accent)',
                animationName: 'pc-demo-slide',
                animationDuration: '2s',
                animationTimingFunction: `var(--pc-ease-${e})`,
                animationIterationCount: 'infinite',
              }}
            />
          </TokenRow>
        ))}
      </Section>
    </Canvas>
  ),
};
