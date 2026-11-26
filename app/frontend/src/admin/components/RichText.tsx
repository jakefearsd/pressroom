import { Fragment, type ReactNode } from 'react';
import { plainText } from '../i18n/messages';

const MARKUP = /<(b|span)>(.*?)<\/\1>/gs;

/**
 * A message value that carries the two inline tags the bundles use (`<b>`, `<span>`) and
 * HTML entities, as React nodes: no innerHTML, no other tag survives (it prints as text).
 */
export function RichText({ text }: { text: string }) {
  const nodes: ReactNode[] = [];
  let from = 0;
  for (const match of text.matchAll(MARKUP)) {
    nodes.push(plainText(text.slice(from, match.index)));
    nodes.push(match[1] === 'b' ? <b key={match.index}>{plainText(match[2] ?? '')}</b>
      : <span key={match.index}>{plainText(match[2] ?? '')}</span>);
    from = match.index + match[0].length;
  }
  nodes.push(plainText(text.slice(from)));
  return <>{nodes.map((node, index) => <Fragment key={index}>{node}</Fragment>)}</>;
}
