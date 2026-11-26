/**
 * The rendered page, as the server produced it. This is the one place the page screens use
 * dangerouslySetInnerHTML (listed in the lint allowlist): `html` comes from
 * POST .../pages/preview, which renders through the same pipeline as a published page and
 * ends in HTMLSanitizer, the only content boundary. Never pass anything else here.
 */
export function PagePreview({ html }: { html: string }) {
  return <div className="editor-preview-content" dangerouslySetInnerHTML={{ __html: html }} />;
}
