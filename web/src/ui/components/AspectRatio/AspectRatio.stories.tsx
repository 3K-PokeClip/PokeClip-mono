import type { Meta, StoryObj } from '@storybook/react-vite';
import { AspectRatio } from './AspectRatio';

const meta: Meta<typeof AspectRatio> = {
  title: 'Components/AspectRatio',
  component: AspectRatio,
  parameters: { layout: 'padded' },
  args: { ratio: 16 / 9 },
};
export default meta;
type Story = StoryObj<typeof AspectRatio>;

export const Default: Story = {
  render: (args) => (
    <div style={{ width: 320 }}>
      <AspectRatio {...args}>
        <div
          style={{
            width: '100%',
            height: '100%',
            background: 'var(--pc-color-bg-surface-raised)',
            display: 'grid',
            placeItems: 'center',
            color: 'var(--pc-color-text-muted)',
            borderRadius: 'var(--pc-radius-md)',
          }}
        >
          16 : 9
        </div>
      </AspectRatio>
    </div>
  ),
};
