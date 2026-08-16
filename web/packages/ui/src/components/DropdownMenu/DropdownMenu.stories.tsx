import type { Meta, StoryObj } from '@storybook/react-vite';
import { DropdownMenu } from './DropdownMenu';
import { Button } from '../Button';

const meta: Meta<typeof DropdownMenu> = {
  title: 'Components/DropdownMenu',
  component: DropdownMenu,
  parameters: { layout: 'centered' },
};
export default meta;
type Story = StoryObj<typeof DropdownMenu>;

export const Default: Story = {
  render: () => (
    <DropdownMenu>
      <DropdownMenu.Trigger>
        <Button variant="outline">메뉴 ▾</Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Content>
        <DropdownMenu.Label>클립</DropdownMenu.Label>
        <DropdownMenu.Item onSelect={() => {}}>공유하기</DropdownMenu.Item>
        <DropdownMenu.Item onSelect={() => {}}>다운로드</DropdownMenu.Item>
        <DropdownMenu.Separator />
        <DropdownMenu.Item danger onSelect={() => {}}>
          삭제
        </DropdownMenu.Item>
      </DropdownMenu.Content>
    </DropdownMenu>
  ),
};
