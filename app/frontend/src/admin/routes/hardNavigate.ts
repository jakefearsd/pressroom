/**
 * A full page load, for a screen that has not migrated yet (its `.rol` page). A module of its
 * own so a test can replace it: jsdom cannot navigate.
 */
export function hardNavigate(url: string): void {
  window.location.assign(url);
}
