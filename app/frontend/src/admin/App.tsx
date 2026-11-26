import { useEffect, useState } from 'react';
import { RouterProvider } from 'react-router';
import type { Bootstrap } from './bootstrap';
import { AppProviders } from './context';
import { loadMessages, MessagesProvider, type Messages } from './i18n/messages';
import { createAppRouter } from './router';

/**
 * The app: bootstrap and API providers, the message bundle from the bootstrap's
 * messagesUrl, then the router. A bundle that cannot load degrades to the keys (logged),
 * so a message outage does not take the admin down with it.
 */
export function App({ boot, router, fetchImpl }: {
  boot: Bootstrap;
  router?: ReturnType<typeof createAppRouter>;
  fetchImpl?: typeof fetch;
}) {
  const [messages, setMessages] = useState<Messages | null>(null);
  const [created] = useState(() => router ?? createAppRouter(boot.contextPath));

  useEffect(() => {
    let live = true;
    loadMessages(boot.messagesUrl, fetchImpl).then(
      (loaded) => { if (live) { setMessages(loaded); } },
      (error: unknown) => {
        console.error('Could not load the message bundle', error);
        if (live) { setMessages({}); }
      }
    );
    return () => { live = false; };
  }, [boot.messagesUrl, fetchImpl]);

  if (messages === null) {
    return null;
  }
  return (
    <AppProviders boot={boot} {...(fetchImpl ? { fetchImpl } : {})}>
      <MessagesProvider messages={messages} locale={boot.uiLocale}>
        <RouterProvider router={created} />
      </MessagesProvider>
    </AppProviders>
  );
}
