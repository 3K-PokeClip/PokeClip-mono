import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Pagination } from './Pagination';

const meta: Meta<typeof Pagination> = {
  title: 'Components/Pagination',
  component: Pagination,
  parameters: { layout: 'centered' },
};
export default meta;
type Story = StoryObj<typeof Pagination>;

export const Default: Story = {
  render: function Default() {
    const [page, setPage] = useState(4);
    return <Pagination page={page} count={12} onPageChange={setPage} />;
  },
};
