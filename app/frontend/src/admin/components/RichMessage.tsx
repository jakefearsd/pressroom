import type { ReactNode } from 'react';
import { plainText } from '../i18n/messages';

/**
 * A bundle value written for JSP, which carries a little inline markup (`<b>`, `<i>`) and HTML
 * entities, rendered as React elements: the markup the bundle itself wrote, nothing else, so no
 * HTML is ever injected. A wrapping `<p>` is dropped (the caller supplies the block).
 */
export function RichMessage({ text }: { text: string }) {
  const body = text.trim().replace(/^<p>([\s\S]*)<\/p>$/, '$1');
  const out: ReactNode[] = [];
  const pattern = /<(b|i)>([\s\S]*?)<\/\1>/g;
  let last = 0;
  let match = pattern.exec(body);
  while (match !== null) {
    out.push(plainText(body.slice(last, match.index)));
    const inner = plainText(match[2] ?? '');
    out.push(match[1] === 'b' ? <b key={match.index}>{inner}</b> : <i key={match.index}>{inner}</i>);
    last = match.index + match[0].length;
    match = pattern.exec(body);
  }
  out.push(plainText(body.slice(last)));
  return <>{out}</>;
}
