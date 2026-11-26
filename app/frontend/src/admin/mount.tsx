import { createRoot } from 'react-dom/client';
import { App } from './App';
import { readBootstrap } from './bootstrap';

/** Reads the shell's bootstrap block and renders the app into #pressroom-root. */
export function mount(doc: Document): void {
  const boot = readBootstrap(doc);
  const container = doc.getElementById('pressroom-root');
  if (container === null) {
    throw new Error('Missing #pressroom-root');
  }
  createRoot(container).render(<App boot={boot} />);
}
