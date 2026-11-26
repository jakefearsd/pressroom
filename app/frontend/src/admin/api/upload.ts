import { useCallback } from 'react';
import type { Bootstrap } from '../bootstrap';
import { useBoot, useExpire } from '../context';
import type { UploadResponse } from './media';
import { toApiError } from './problem';

export type Progress = (loaded: number, total: number) => void;

/**
 * Uploads one file as multipart (XMLHttpRequest, because fetch cannot report upload progress),
 * with the session cookie and the CSRF header every session write carries. Resolves to the
 * 201/207 UploadResponse (a refused file is in `results`, not a rejection); any other status
 * rejects with the problem+json as an ApiError, a network failure with a plain Error.
 */
export function uploadFile(
  boot: Bootstrap, handle: string, directoryId: string | undefined, file: File,
  onProgress?: Progress, onUnauthorized?: () => void, makeRequest: () => XMLHttpRequest = () => new XMLHttpRequest()
): Promise<UploadResponse> {
  return new Promise((resolve, reject) => {
    const xhr = makeRequest();
    const body = new FormData();
    body.append('file', file);
    if (directoryId !== undefined && directoryId !== '') {
      body.append('directoryId', directoryId);
    }
    xhr.open('POST', `${boot.contextPath}/api/v1/weblogs/${encodeURIComponent(handle)}/media`);
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
      if (xhr.status === 201 || xhr.status === 207) {
        resolve(parsed as UploadResponse);
      } else {
        reject(toApiError(xhr.status, parsed));
      }
    };
    xhr.send(body);
  });
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
