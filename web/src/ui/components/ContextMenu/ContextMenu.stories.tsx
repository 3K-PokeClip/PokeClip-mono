import type { Meta, StoryObj } from '@storybook/react-vite';
import { ContextMenu } from './ContextMenu';

const meta: Meta<typeof ContextMenu> = {
  title: 'Components/ContextMenu',
  component: ContextMenu,
  parameters: { layout: 'centered' },
};
export default meta;
type Story = StoryObj<typeof ContextMenu>;

export const Default: Story = {
  render: () => (
    <ContextMenu>
      <ContextMenu.Trigger>
        <div
          style={{
            display: 'grid',
            placeItems: 'center',
            width: 260,
            height: 120,
            border: '1px dashed var(--pc-color-border-default)',
            borderRadius: 'var(--pc-radius-md)',
            color: 'var(--pc-color-text-muted)',
            userSelect: 'none',
          }}
        >
          여기를 우클릭하세요
        </div>
      </ContextMenu.Trigger>
      <ContextMenu.Content>
        <ContextMenu.Item onSelect={() => {}}>공유하기</ContextMenu.Item>
        <ContextMenu.Item onSelect={() => {}}>이름 바꾸기</ContextMenu.Item>
        <ContextMenu.Separator />
        <ContextMenu.Item danger onSelect={() => {}}>
          삭제
        </ContextMenu.Item>
      </ContextMenu.Content>
    </ContextMenu>
  ),
};
