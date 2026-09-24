// @ts-check

import { accessToken, getSession } from './auth.js';

export class ApiError extends Error {
  /** @param {number} status @param {string} code @param {string} message @param {Array<{field:string,message:string}>} details */
  constructor(status, code, message, details = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

/**
 * @template T
 * @param {string} path
 * @param {{method?: string, body?: unknown, idempotencyKey?: string, acceptRisk?: boolean}} [options]
 * @returns {Promise<T>}
 */
export async function api(path, options = {}) {
  const headers = /** @type {Record<string,string>} */ ({ Accept: 'application/json' });
  const session = getSession();
  if (session.mode === 'production') {
    const token = await accessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  } else if (session.ownerId) {
    headers['X-LedgerX-Owner-Id'] = session.ownerId;
  }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
  let response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method: options.method || 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      cache: 'no-store',
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Connection lost. The outcome may be unknown.');
  }
  if (!response.ok) {
    let payload = /** @type {Record<string,unknown>} */ ({});
    try { payload = await response.json(); } catch { /* Authentication errors may have no JSON body. */ }
    if (options.acceptRisk && response.status === 422 && (payload.outcome === 'BLOCK' || payload.code === 'RISK_REVIEW_CLOSED')) {
      return /** @type {T} */ (payload);
    }
    const message = typeof payload.message === 'string' ? payload.message
      : response.status === 401 ? 'Your session has ended. Sign in again.'
      : response.status === 403 ? 'You do not have access to this action.'
      : `Request failed (${response.status}).`;
    const details = Array.isArray(payload.details) ? payload.details : [];
    throw new ApiError(response.status, String(payload.code || 'REQUEST_FAILED'), message, details);
  }
  if (response.status === 204) return /** @type {T} */ (null);
  if (response.status === 202) {
    const payload = await response.text();
    return /** @type {T} */ (payload ? JSON.parse(payload) : null);
  }
  return /** @type {T} */ (await response.json());
}
