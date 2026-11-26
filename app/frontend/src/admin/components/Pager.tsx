import { Link } from 'react-router';
import { useT } from '../i18n/messages';

/**
 * The Newer/Older pager of Entries.jsp, Comments.jsp and Submissions.jsp: rendered only when
 * there is somewhere to go (an empty nav is still a landmark a screen reader announces).
 * `offset` is the list's own offset; `hrefFor` builds the link for another offset.
 */
export function Pager({ offset, limit, hasMore, hrefFor }: {
  offset: number; limit: number; hasMore: boolean; hrefFor: (offset: number) => string;
}) {
  const t = useT();
  if (offset <= 0 && !hasMore) {
    return null;
  }
  return (
    <nav aria-label={t('pager.label')}>
      <div className="d-flex justify-content-between">
        {offset > 0 ? (
          <Link to={hrefFor(Math.max(0, offset - limit))} className="btn btn-outline-secondary previous">
            <span aria-hidden="true">&larr;</span> {t('pager.newer')}
          </Link>
        ) : null}
        {hasMore ? (
          <Link to={hrefFor(offset + limit)} className="btn btn-outline-secondary next ms-auto">
            {t('pager.older')} <span aria-hidden="true">&rarr;</span>
          </Link>
        ) : null}
      </div>
    </nav>
  );
}
