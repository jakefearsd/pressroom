import type { Bootstrap } from './bootstrap';

/** Placeholder: the app frame (rail, banner, routes) is the next task. */
export function App({ boot }: { boot: Bootstrap }) {
  return <main id="pressroom-app" data-version={boot.version}>Pressroom {boot.version}</main>;
}
