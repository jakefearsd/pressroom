import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { LinkTarget } from '../routes/railTable';

/** A React route navigates client-side; a legacy `.rol` URL is a normal link (full page load). */
export function AppLink({ target, className, current, id, label, children }: {
  target: LinkTarget; className?: string; current?: boolean; id?: string; label?: string; children: ReactNode;
}) {
  const aria = {
    ...(current === true ? { 'aria-current': 'page' as const } : {}),
    ...(id === undefined ? {} : { id }),
    ...(label === undefined ? {} : { 'aria-label': label })
  };
  return target.kind === 'react'
    ? <Link to={target.to} className={className} {...aria}>{children}</Link>
    : <a href={target.to} className={className} {...aria}>{children}</a>;
}
