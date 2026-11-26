import type { ReactNode } from 'react';

/** The `.empty-state` idiom: an invitation, not a shrug. Children carry the call to action. */
export function EmptyState({ title, body, id, children }: { title: string; body?: string; id?: string; children?: ReactNode }) {
  return (
    <div className="empty-state" {...(id === undefined ? {} : { id })}>
      <p className="empty-state-title">{title}</p>
      {body === undefined ? null : <p className="empty-state-body">{body}</p>}
      {children}
    </div>
  );
}
