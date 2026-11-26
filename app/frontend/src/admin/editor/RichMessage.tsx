import type { ReactNode } from 'react';

const TAG = /<(a|span)\b([^>]*)>(.*?)<\/\1>/gs;
const HREF = /\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')/;

/** A link target a message may carry: an http(s) URL or a path, never a script. */
function safeHref(href: string): string | undefined {
  return /^(https?:\/\/|\/)/i.test(href) ? href : undefined;
}

/**
 * A message whose bundle value carries a little markup (`<a href="{0}">View it</a>`,
 * `<span>{0}</span>`) rendered as elements, not parsed as HTML: only a, with a safe href,
 * and span are understood and everything else stays text, so a user-typed argument cannot
 * inject markup (the "arguments are not escaped" hazard does not carry over to React).
 */
export function RichMessage({ text }: { text: string }) {
  const nodes: ReactNode[] = [];
  let last = 0;
  for (const match of text.matchAll(TAG)) {
    if (match.index > last) {
      nodes.push(text.slice(last, match.index));
    }
    const [, tag, attributes = '', inner = ''] = match;
    if (tag === 'a') {
      const href = safeHref(HREF.exec(attributes)?.[1] ?? HREF.exec(attributes)?.[2] ?? '');
      nodes.push(href === undefined ? inner : <a key={match.index} href={href}>{inner}</a>);
    } else {
      nodes.push(<span key={match.index}>{inner}</span>);
    }
    last = match.index + match[0].length;
  }
  nodes.push(text.slice(last));
  return <>{nodes}</>;
}
