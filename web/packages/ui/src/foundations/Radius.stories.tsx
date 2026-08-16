import type { Meta, StoryObj } from '@storybook/react-vite';
import { Canvas, Grid, Section } from './preview-parts';

const meta: Meta = {
  title: 'Foundations/Radius',
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj;

const radii = ['xs', 'sm', 'md', 'lg', 'xl', '2xl', 'pill'];

export const Scale: Story = {
  render: () => (
    <Canvas>
      <Section title="라운드 — Corner radius">
        <Grid min={140}>
          {radii.map((r) => (
            <div
              key={r}
              style={{ display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'center' }}
            >
              <div
                style={{
                  width: 88,
                  height: 88,
                  background: 'var(--pc-color-accent-subtle)',
                  border: '1px solid var(--pc-color-accent)',
                  borderRadius: `var(--pc-radius-${r})`,
                }}
              />
              <code style={{ fontSize: 12, color: 'var(--pc-color-text-secondary)' }}>
                radius-{r}
              </code>
            </div>
          ))}
        </Grid>
      </Section>
    </Canvas>
  ),
};
