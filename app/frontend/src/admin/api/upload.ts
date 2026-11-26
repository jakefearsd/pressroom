import { useCallback } from 'react';
import type { Bootstrap } from '../bootstrap';
import { useBoot, useExpire } from '../context';
import type { MediaView, UploadResponse } from './media';
import { toApiError } from './problem';

export type Progress = (loaded: number, total: number) => void;

/**
 * Sends one file as multipart (XMLHttpRequest, because fetch cannot report upload progress),
 * with the session cookie and the CSRF header every session write carries. Resolves to the
 * parsed body of an `ok` status; any other status rejects with the problem+json as an ApiError,
 * a network failure with a plain Error.
 */
function sendFile<T>(
  boot: Bootstrap, method: 'POST' | 'PUT', path: string, fields: Record<string, string | undefined>, file: File,
  ok: readonly number[], onProgress?: Progress, onUnauthorized?: () => void, makeRequest: () => XMLHttpRequest = () => new XMLHttpRequest()
): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = makeRequest();
    const body = new FormData();
    body.append('file', file);
    for (const [name, value] of Object.entries(fields)) {
      if (value !== undefined && value !== '') {
        body.append(name, value);
      }
    }
    xhr.open(method, `${boot.contextPath}/api/v1/weblogs/${path}`);
    xhr.setRequestHeader(boot.csrf.headerName, boot.csrf.token);
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress?.(event.loaded, event.total);
      }
    };
    xhr.onerror = () => { reject(new Error(file.name)); };
    xhr.onload = () => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(xhr.responseText);
      } catch {
        parsed = undefined;
      }
      if (xhr.status === 401) {
        onUnauthorized?.();
      }
      if (ok.includes(xhr.status)) {
        resolve(parsed as T);
      } else {
        reject(toApiError(xhr.status, parsed));
      }
    };
    xhr.send(body);
  });
}

/**
 * Uploads one file to the weblog's media. Resolves to the 201/207 UploadResponse (a refused
 * file is in `results`, not a rejection).
 */
export function uploadFile(
  boot: Bootstrap, handle: string, directoryId: string | undefined, file: File,
  onProgress?: Progress, onUnauthorized?: () => void, makeRequest?: () => XMLHttpRequest
): Promise<UploadResponse> {
  return sendFile(boot, 'POST', `${encodeURIComponent(handle)}/media`, { directoryId }, file, [201, 207],
    onProgress, onUnauthorized, makeRequest);
}

/** Replaces the bytes of one media file (PUT .../media/{id}/content); resolves to the file as it now is. */
export function replaceFile(
  boot: Bootstrap, handle: string, id: string, file: File, onUnauthorized?: () => void, makeRequest?: () => XMLHttpRequest
): Promise<MediaView> {
  return sendFile(boot, 'PUT', `${encodeURIComponent(handle)}/media/${encodeURIComponent(id)}/content`, {}, file, [200],
    undefined, onUnauthorized, makeRequest);
}

/** uploadFile bound to the bootstrap and the session-expiry store. */
export function useUploader() {
  const boot = useBoot();
  const expire = useExpire();
  return useCallback(
    (handle: string, directoryId: string | undefined, file: File, onProgress?: Progress) =>
      uploadFile(boot, handle, directoryId, file, onProgress, expire),
    [boot, expire]
  );
}

/** replaceFile bound to the bootstrap and the session-expiry store. */
export function useReplacer() {
  const boot = useBoot();
  const expire = useExpire();
  return useCallback(
    (handle: string, id: string, file: File) => replaceFile(boot, handle, id, file, expire),
    [boot, expire]
  );
}
