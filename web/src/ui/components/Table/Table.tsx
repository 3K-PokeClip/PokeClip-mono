import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './Table.module.css';

export type SortDirection = 'ascending' | 'descending' | 'none';

const TableRoot = forwardRef<HTMLTableElement, ComponentPropsWithoutRef<'table'>>(function Table(
  { className, children, ...rest },
  ref,
) {
  return (
    <div className={styles.scroll}>
      <table ref={ref} className={clsx(styles.table, className)} {...rest}>
        {children}
      </table>
    </div>
  );
});

const TableHead = forwardRef<HTMLTableSectionElement, ComponentPropsWithoutRef<'thead'>>(
  function TableHead(props, ref) {
    return <thead ref={ref} {...props} />;
  },
);

const TableBody = forwardRef<HTMLTableSectionElement, ComponentPropsWithoutRef<'tbody'>>(
  function TableBody(props, ref) {
    return <tbody ref={ref} {...props} />;
  },
);

const TableRow = forwardRef<HTMLTableRowElement, ComponentPropsWithoutRef<'tr'>>(function TableRow(
  { className, ...rest },
  ref,
) {
  return <tr ref={ref} className={clsx(styles.row, className)} {...rest} />;
});

const TableCell = forwardRef<HTMLTableCellElement, ComponentPropsWithoutRef<'td'>>(
  function TableCell({ className, ...rest }, ref) {
    return <td ref={ref} className={clsx(styles.td, className)} {...rest} />;
  },
);

interface TableHeaderCellProps extends Omit<ComponentPropsWithoutRef<'th'>, 'onClick'> {
  sortable?: boolean;
  sortDirection?: SortDirection;
  onSort?: () => void;
}
const TableHeaderCell = forwardRef<HTMLTableCellElement, TableHeaderCellProps>(
  function TableHeaderCell(
    { sortable, sortDirection = 'none', onSort, className, children, ...rest },
    ref,
  ) {
    return (
      <th
        ref={ref}
        scope="col"
        aria-sort={sortable ? sortDirection : undefined}
        className={clsx(styles.th, className)}
        {...rest}
      >
        {sortable ? (
          <button type="button" className={styles.sortButton} onClick={onSort}>
            {children}
            <svg
              className={styles.sortIcon}
              width="12"
              height="12"
              viewBox="0 0 12 12"
              aria-hidden="true"
            >
              <path
                d="M3 5l3 3 3-3"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
        ) : (
          children
        )}
      </th>
    );
  },
);

/** Styled, scroll-safe data table with sortable header cells (`aria-sort`). */
export const Table = Object.assign(TableRoot, {
  Head: TableHead,
  Body: TableBody,
  Row: TableRow,
  Cell: TableCell,
  HeaderCell: TableHeaderCell,
});
