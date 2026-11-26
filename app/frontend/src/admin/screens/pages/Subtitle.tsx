import type { ReactNode } from 'react';

/**
 * A message whose value carries `<b>` around an argument ("Static pages for <b>{0}</b>"),
 * as React nodes: the bold span is real, everything else is text (so an argument can never
 * inject markup).
 */
export function BoldMarkup({ text }: { text: string }) {
  const nodes: ReactNode[] = [];
  const pattern = /<b>(.*?)<\/b>/gs;
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    nodes.push(text.slice(last, match.index));
    nodes.push(<b key={match.index}>{match[1]}</b>);
    last = match.index + match[0].length;
  }
  nodes.push(text.slice(last));
  return <>{nodes}</>;
}

/** `p.subtitle` under a page title. */
export function Subtitle({ text }: { text: string }) {
  return <p className="subtitle"><BoldMarkup text={text} /></p>;
}
