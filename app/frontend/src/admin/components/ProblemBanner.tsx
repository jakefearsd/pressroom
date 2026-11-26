import { useEffect, useState } from 'react';
import { ApiError } from '../api/problem';
import { useT } from '../i18n/messages';

export const SUCCESS_DISMISS_MS = 10_000;

interface Props {
  /** Success lines: the banner dismisses itself after 10 seconds, as messages.jsp's did. */
  messages?: readonly string[];
  /** Error lines: they stay until dismissed. */
  errors?: readonly string[];
  /** A failed API call: its detail and field messages become the error lines. */
  error?: unknown;
}

function lines(error: unknown, errors: readonly string[]): string[] {
  if (error instanceof ApiError) {
    return [...errors, ...error.messages];
  }
  return error instanceof Error ? [...errors, error.message] : [...errors];
}

function Alert({ id, tone, role, items, dismissAfterMs }: {
  id: string; tone: 'success' | 'danger'; role: string; items: readonly string[]; dismissAfterMs?: number;
}) {
  const t = useT();
  const [closed, setClosed] = useState(false);
  useEffect(() => {
    if (dismissAfterMs === undefined) {
      return undefined;
    }
    const timer = setTimeout(() => { setClosed(true); }, dismissAfterMs);
    return () => { clearTimeout(timer); };
  }, [dismissAfterMs]);
  if (closed) {
    return null;
  }
  return (
    <div id={id} className={`alert alert-${tone} alert-dismissible fade show`} role={role}
         {...(tone === 'success' ? { 'aria-live': 'polite' as const } : {})}>
      <button type="button" className="btn-close" aria-label={t('generic.close')}
              onClick={() => { setClosed(true); }} />
      <ul>
        {items.map((item, index) => <li key={`${String(index)}:${item}`}>{item}</li>)}
      </ul>
    </div>
  );
}

/**
 * Errors and messages as tiles/messages.jsp renders them. React escapes the text, so
 * callers pass plain text. Each banner is keyed by its lines: new lines are a new banner.
 */
export function ProblemBanner({ messages = [], errors = [], error }: Props) {
  const errorLines = lines(error, errors);
  return (
    <>
      {messages.length > 0 ? (
        <Alert key={messages.join('\n')} id="messages" tone="success" role="status" items={messages}
               dismissAfterMs={SUCCESS_DISMISS_MS} />
      ) : null}
      {errorLines.length > 0 ? (
        <Alert key={errorLines.join('\n')} id="errors" tone="danger" role="alert" items={errorLines} />
      ) : null}
    </>
  );
}
