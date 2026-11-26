export interface ShortcodeCard {
  name: string;
  /** Message key of the menu label. */
  labelKey: string;
  snippet: string;
  /** Opens the media chooser, which inserts the shortcode itself. */
  usesMediaChooser: boolean;
}

/**
 * The Insert menu and the guide's shortcode table: the registry's cards (each handler's
 * getCard()), which no API route exposes yet, so they are listed here. A test reads the Java
 * handlers' card names and fails when this list drifts from the registry.
 * Labels: shortcode.image.label shortcode.gallery.label shortcode.map.label shortcode.cta.label
 * shortcode.faq.label shortcode.video.label shortcode.contact.label shortcode.subscribe.label
 */
export const SHORTCODE_CARDS: readonly ShortcodeCard[] = [
  { name: 'image', labelKey: 'shortcode.image.label', snippet: '', usesMediaChooser: true },
  { name: 'gallery', labelKey: 'shortcode.gallery.label', snippet: '[gallery dir="default" row="320"]', usesMediaChooser: false },
  { name: 'map', labelKey: 'shortcode.map.label',
    snippet: '[map zoom="12"]\n[pin lat="48.8584" lng="2.2945" label="Eiffel Tower"]\n[pin lat="48.8606" lng="2.3376" label="Louvre"]\n[/map]',
    usesMediaChooser: false },
  { name: 'cta', labelKey: 'shortcode.cta.label',
    snippet: '[cta href="https://example.com/book" label="Book this stay" note="Free cancellation for 48 hours"]',
    usesMediaChooser: false },
  { name: 'faq', labelKey: 'shortcode.faq.label',
    snippet: '[faq]\n[q]How do I get there?[/q]\n[a]Answer.[/a]\n[q]When should I visit?[/q]\n[a]Answer.[/a]\n[/faq]',
    usesMediaChooser: false },
  { name: 'video', labelKey: 'shortcode.video.label',
    snippet: '[video url="https://youtu.be/dQw4w9WgXcQ" caption="What this shows"]', usesMediaChooser: false },
  { name: 'contact', labelKey: 'shortcode.contact.label', snippet: '[contact]', usesMediaChooser: false },
  { name: 'subscribe', labelKey: 'shortcode.subscribe.label', snippet: '[subscribe]', usesMediaChooser: false }
];
