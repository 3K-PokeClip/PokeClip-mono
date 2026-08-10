import {
  Children,
  Fragment,
  forwardRef,
  isValidElement,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react';
import clsx from 'clsx';
import styles from './Breadcrumb.module.css';

export interface BreadcrumbProps extends ComponentPropsWithoutRef<'nav'> {
  separator?: ReactNode;
  label?: string;
}

const BreadcrumbRoot = forwardRef<HTMLElement, BreadcrumbProps>(function Breadcrumb(
  { separator = '/', label = '브레드크럼', className, children, ...rest },
  ref,
) {
  const items = Children.toArray(children).filter(isValidElement);
  return (
    <nav ref={ref} aria-label={label} className={className} {...rest}>
      <ol className={styles.list}>
        {items.map((item, i) => (
          <Fragment key={i}>
            {i > 0 ? (
              <li aria-hidden="true" className={styles.separator}>
                {separator}
              </li>
            ) : null}
            {item}
          </Fragment>
        ))}
      </ol>
    </nav>
  );
});

export interface BreadcrumbItemProps extends ComponentPropsWithoutRef<'a'> {
  current?: boolean;
}
const BreadcrumbItem = forwardRef<HTMLAnchorElement, BreadcrumbItemProps>(function BreadcrumbItem(
  { current = false, className, children, ...rest },
  ref,
) {
  return (
    <li className={styles.item}>
      {current ? (
        <span aria-current="page" className={styles.current}>
          {children}
        </span>
      ) : (
        <a ref={ref} className={clsx(styles.link, className)} {...rest}>
          {children}
        </a>
      )}
    </li>
  );
});

/** Navigation breadcrumb trail; marks the final item with `aria-current="page"`. */
export const Breadcrumb = Object.assign(BreadcrumbRoot, { Item: BreadcrumbItem });
