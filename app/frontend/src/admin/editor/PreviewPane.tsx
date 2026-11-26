import { useEffect, useImperativeHandle, useRef, type Ref } from 'react';
import { useLatest } from './useLatest';
import { call } from '../api/client';
import { useApi, useBoot } from '../context';
import { useT } from '../i18n/messages';

export const PREVIEW_DEBOUNCE_MS = 400;

export interface PreviewHandle {
  /** Scrolls the preview to a fraction of its height (the editor's scroll position). */
  scroll(fraction: number): void;
}

interface Props {
  handle: string;
  /** The saved entry (or page) being edited; absent for a new one (previews a scratch row). */
  entryId: string | undefined;
  /** What is being written: an entry (the default) or a static page, which has its own preview routes. */
  kind?: 'entry' | 'page';
  /** The editor's current text. */
  text: string;
  /** Whether the pane is on screen: nothing is rendered while the author is only writing. */
  visible: boolean;
  scrollFraction: () => number;
  ref?: Ref<PreviewHandle>;
}

/** The weblog's own theme around one empty article: the shell the preview posts server HTML into (PreviewServlet ?shell=true). */
export function previewShellUrl(contextPath: string, handle: string): string {
  return `${contextPath}/pressroom-ui/authoring/preview/${encodeURIComponent(handle)}/?shell=true`;
}

/**
 * The live preview, as EditorScript.jsp built it: the server renders the Markdown (only it can
 * expand [gallery] and [map]), and the HTML is posted into a sandboxed iframe of the weblog's
 * theme, so prose is shown set the way it will publish. At most one render is in flight; edits
 * made meanwhile collapse into one follow-up, so a slow render never paints over a newer one.
 * The HTML never enters this document, so nothing here needs dangerouslySetInnerHTML.
 */
export function PreviewPane({ handle, entryId, kind = 'entry', text, visible, scrollFraction, ref }: Props) {
  const t = useT();
  const api = useApi();
  const { contextPath } = useBoot();
  const frame = useRef<HTMLIFrameElement>(null);
  const ready = useRef(false);
  const inflight = useRef(false);
  const pending = useRef(false);
  const latest = useLatest({ text, scrollFraction, entryId });

  const post = (message: Record<string, unknown>) => {
    frame.current?.contentWindow?.postMessage(message, window.location.origin);
  };

  const push = useLatest(async (): Promise<void> => {
    if (!ready.current) {
      return;
    }
    if (inflight.current) {
      pending.current = true;
      return;
    }
    inflight.current = true;
    try {
      const { text: current, entryId: id, scrollFraction: fraction } = latest.current;
      const view = kind === 'page'
        ? (id === undefined
          ? await call(api.POST('/v1/weblogs/{handle}/pages/preview', { params: { path: { handle } }, body: { text: current } }))
          : await call(api.POST('/v1/weblogs/{handle}/pages/{id}/preview', { params: { path: { handle, id } }, body: { text: current } })))
        : id === undefined
        ? await call(api.POST('/v1/weblogs/{handle}/entries/preview', { params: { path: { handle } }, body: { text: current } }))
        : await call(api.POST('/v1/weblogs/{handle}/entries/{id}/preview', { params: { path: { handle, id } }, body: { text: current } }));
      post({ type: 'pressroom-preview', html: view.html ?? '', scroll: fraction() });
    } catch {
      post({ type: 'pressroom-preview', html: `<p class="pressroom-preview-error">${t('weblogEdit.previewFailed')}</p>` });
    } finally {
      inflight.current = false;
      if (pending.current) {
        pending.current = false;
        void push.current();
      }
    }
  });

  useImperativeHandle(ref, () => ({
    scroll(fraction) {
      if (ready.current) {
        post({ type: 'pressroom-preview-scroll', scroll: fraction });
      }
    }
  }), []);

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      // The origin alone cannot tell this tab's shell from another's: only our own frame may speak.
      if (event.origin !== window.location.origin || event.source !== frame.current?.contentWindow) {
        return;
      }
      const data = event.data as { type?: unknown } | null;
      if (data !== null && data.type === 'pressroom-preview-ready') {
        ready.current = true;
        void push.current();
      }
    };
    window.addEventListener('message', onMessage);
    return () => { window.removeEventListener('message', onMessage); };
  }, [push]);

  useEffect(() => {
    if (!visible) {
      return undefined;
    }
    const timer = setTimeout(() => { void push.current(); }, PREVIEW_DEBOUNCE_MS);
    return () => { clearTimeout(timer); };
  }, [text, visible, push]);

  if (!visible) {
    return null;
  }
  return (
    <div className="editor-preview-pane" id="editorPreviewPane" aria-live="polite">
      {/* allow-same-origin is required: without it the frame's origin is opaque and both sides refuse its messages. */}
      <iframe ref={frame} title={t('editor.mode.preview')} sandbox="allow-scripts allow-same-origin"
              src={previewShellUrl(contextPath, handle)} />
    </div>
  );
}
