import type { components } from '../api/schema';

type MyWeblog = components['schemas']['MyWeblog'];

/**
 * The home's order: the blogs with the most waiting for the author (comments awaiting approval plus
 * new inquiries) first, then the most recently published, a blog that never published last, then by name.
 */
export function attentionOrder(a: MyWeblog, b: MyWeblog): number {
  const waiting = (w: MyWeblog) => w.pendingComments + w.newInquiries;
  if (waiting(a) !== waiting(b)) {
    return waiting(b) - waiting(a);
  }
  const published = (w: MyWeblog) => (w.lastPublished === undefined ? Number.NEGATIVE_INFINITY : Date.parse(w.lastPublished));
  if (published(a) !== published(b)) {
    return published(a) < published(b) ? 1 : -1;
  }
  return a.name.localeCompare(b.name);
}
