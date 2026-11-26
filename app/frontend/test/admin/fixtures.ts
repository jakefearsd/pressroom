import type { Bootstrap } from '../../src/admin/bootstrap';

export const sample: Bootstrap = {
  contextPath: '/pressroom',
  version: '1.2.3',
  csrf: { headerName: 'X-CSRF-TOKEN', token: 'tok' },
  sessionTimeoutSeconds: 1800,
  user: { userName: 'jake', screenName: 'Jake </script><b>', locale: 'en', globalPermission: 'editor' },
  uiLocale: 'en',
  messagesUrl: '/pressroom/pressroom-ui/app-messages.rol?locale=en&v=1.2.3',
  site: { name: 'Pressroom', shortName: 'Pressroom', siteSpaceHandle: 'site-space' },
  colophon: { copyrightYear: '2026' }
};

/** A document holding the bootstrap block the way the shell writes it. */
export function shellDocument(json: string | null): Document {
  const doc = document.implementation.createHTMLDocument('shell');
  if (json !== null) {
    const script = doc.createElement('script');
    script.type = 'application/json';
    script.id = 'pressroom-bootstrap';
    script.textContent = json;
    doc.body.append(script);
  }
  return doc;
}
