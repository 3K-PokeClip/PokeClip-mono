import type { Meta, StoryObj } from '@storybook/react-vite';
import { Grid } from './Grid';

const meta: Meta<typeof Grid> = {
  title: 'Components/Grid',
  component: Grid,
  parameters: { layout: 'padded' },
  args: { columns: 4, gap: 3 },
  argTypes: { columns: { control: { type: 'number' } }, gap: { control: { type: 'number' } } },
};
export default meta;
type Story = StoryObj<typeof Grid>;

const cell = {
  display: 'grid',
  placeItems: 'center',
  minHeight: 48,
  background: 'var(--pc-color-accent-subtle)',
  color: 'var(--pc-color-accent-text)',
  borderRadius: 'var(--pc-radius-md)',
  fontSize: 13,
} as const;

export const Playground: Story = {
  render: (args) => (
    <Grid {...args}>
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} style={cell}>
          {i + 1}
        </div>
      ))}
    </Grid>
  ),
};
