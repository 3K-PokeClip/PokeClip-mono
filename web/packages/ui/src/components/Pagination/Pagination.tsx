import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import styles from './Pagination.module.css';

export interface PaginationProps extends Omit<ComponentPropsWithoutRef<'nav'>, 'onChange'> {
  page: number;
  count: number;
  onPageChange: (page: number) => void;
  siblingCount?: number;
  label?: string;
}

function getItems(page: number, count: number, sibling: number): Array<number | 'ellipsis'> {
  const items: Array<number | 'ellipsis'> = [];
  const start = Math.max(page - sibling, 1);
  const end = Math.min(page + sibling, count);
  items.push(1);
  if (start > 2) items.push('ellipsis');
  for (let p = Math.max(start, 2); p <= Math.min(end, count - 1); p++) items.push(p);
  if (end < count - 1) items.push('ellipsis');
  if (count > 1) items.push(count);
  return items;
}

/** Page navigation with ellipsis truncation and prev/next controls. */
export const Pagination = forwardRef<HTMLElement, PaginationProps>(function Pagination(
  { page, count, onPageChange, siblingCount = 1, label = '페이지', className, ...rest },
  ref,
) {
  const items = count > 0 ? getItems(page, count, siblingCount) : [];
  return (
    <nav ref={ref} aria-label={label} className={className} {...rest}>
      <ul className={styles.list}>
        <li>
          <button
            type="button"
            className={styles.item}
            aria-label="이전 페이지"
            disabled={page <= 1}
            onClick={() => onPageChange(page - 1)}
          >
            ‹
          </button>
        </li>
        {items.map((it, i) =>
          it === 'ellipsis' ? (
            <li key={`e${i}`} className={styles.ellipsis} aria-hidden="true">
              …
            </li>
          ) : (
            <li key={it}>
              <button
                type="button"
                className={styles.item}
                aria-label={`${it} 페이지`}
                aria-current={it === page ? 'page' : undefined}
                onClick={() => onPageChange(it)}
              >
                {it}
              </button>
            </li>
          ),
        )}
        <li>
          <button
            type="button"
            className={styles.item}
            aria-label="다음 페이지"
            disabled={page >= count}
            onClick={() => onPageChange(page + 1)}
          >
            ›
          </button>
        </li>
      </ul>
    </nav>
  );
});
