import { Fragment, type ReactNode } from 'react';
import { plainText } from '../i18n/messages';

/** Entity-escapes a user-typed message argument, as the JSP's fn:escapeXml did, so it cannot read as a tag. */
export function escapeArg(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

const TAGGED = /<(b|span)>(.*?)<\/\1>/gs;

/**
 * A message value that carries the two inline tags the bundle uses (`<b>`, `<span>`) as React
 * elements, never as HTML. Pass user-typed arguments through escapeArg before formatting.
 */
export function Rich({ text }: { text: string }) {
  const parts: ReactNode[] = [];
  let last = 0;
  for (const match of text.matchAll(TAGGED)) {
    parts.push(plainText(text.slice(last, match.index)));
    const inner = plainText(match[2] ?? '');
    parts.push(match[1] === 'b' ? <b key={match.index}>{inner}</b> : <span key={match.index}>{inner}</span>);
    last = match.index + match[0].length;
  }
  parts.push(plainText(text.slice(last)));
  return <>{parts.map((part, i) => <Fragment key={i}>{part}</Fragment>)}</>;
}
