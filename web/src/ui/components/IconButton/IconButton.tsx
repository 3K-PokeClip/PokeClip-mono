import { forwardRef } from 'react';
import { Button, type ButtonProps } from '../Button/Button';

export interface IconButtonProps extends Omit<ButtonProps, 'iconStart' | 'iconEnd' | 'fullWidth'> {
  /** Required — icon-only buttons must have an accessible name. */
  'aria-label': string;
}

/** Square, icon-only button. Enforces an accessible name via required `aria-label`. */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { children, ...rest },
  ref,
) {
  return (
    <Button ref={ref} data-icon-only="" {...rest}>
      {children}
    </Button>
  );
});
