import type { ApiClient } from '../api/client';

export interface UploadOutcome {
  /** The new media file's id on success. */
  id?: string;
  /** Why it was refused, in the server's words (a per-file refusal names its file). */
  detail?: string;
}

/**
 * One image to the weblog's default media directory through `POST /v1/weblogs/{handle}/media`
 * (what `mediaFileAdd!upload.rol` did for a paste or a drop). Never throws: a refusal, a
 * network failure and a malformed answer all come back as an outcome the status line can show.
 */
export async function uploadImage(api: ApiClient, handle: string, file: File): Promise<UploadOutcome> {
  const form = new FormData();
  form.append('file', file, file.name);
  try {
    const { data, error } = await api.POST('/v1/weblogs/{handle}/media', {
      params: { path: { handle } },
      body: { file: [] },
      bodySerializer: () => form
    });
    const result = data?.results?.[0];
    if (result?.file?.id !== undefined && result.file.id !== '') {
      return { id: result.file.id };
    }
    const problem = error as { detail?: unknown } | undefined;
    const detail = result?.detail ?? (typeof problem?.detail === 'string' ? problem.detail : undefined);
    return detail === undefined || detail === '' ? {} : { detail };
  } catch {
    return {};
  }
}
