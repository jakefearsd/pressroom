import { useEffect, type ReactNode } from 'react';
import { useParams } from 'react-router';

/**
 * The page's heading (`h1.pressroom-page-title`, as the tiles layouts render it) and the
 * browser tab title: "Pressroom: <title> - <handle>" so open tabs tell weblogs apart.
 * `tabTitle` names the tab more specifically than the heading when a screen needs it.
 */
export function PageTitle({ children, tabTitle, siteName = 'Pressroom' }: {
  children: string | ReactNode; tabTitle?: string; siteName?: string;
}) {
  const handle = useParams()['handle'];
  const heading = typeof children === 'string' ? children : '';
  useEffect(() => {
    const title = tabTitle ?? heading;
    document.title = `${siteName}: ${title}${handle === undefined ? '' : ` — ${handle}`}`;
  }, [tabTitle, heading, siteName, handle]);
  return <h1 className="pressroom-page-title">{children}</h1>;
}
