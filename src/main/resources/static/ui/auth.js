// @ts-check

/** @typedef {{mode: 'production'|'development', issuerUri: string, clientId: string}} UiConfig */
/** @typedef {{authorization_endpoint: string, token_endpoint: string, end_session_endpoint?: string}} Discovery */
/** @typedef {{access_token: string, refresh_token?: string, id_token?: string, expires_in: number}} TokenResponse */

/** @type {UiConfig|null} */
let config = null;
/** @type {Discovery|null} */
let discovery = null;
/** @type {TokenResponse|null} */
let tokens = null;
let expiresAt = 0;

const DEV_OWNER_KEY = 'ledgerx-dev-owner';
const PKCE_KEY = 'ledgerx-pkce';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function initializeAuth() {
  const response = await fetch('/ui-config', { cache: 'no-store' });
  if (!response.ok) throw new Error('Workbench configuration is unavailable.');
  config = /** @type {UiConfig} */ (await response.json());
  if (config.mode !== 'production') return;
  if (!config.issuerUri || !config.clientId) throw new Error('Production sign-in is not configured.');
  const issuer = new URL(config.issuerUri);
  if (issuer.protocol !== 'https:') throw new Error('Production identity issuer must use HTTPS.');
  const discoveryResponse = await fetch(`${config.issuerUri.replace(/\/$/, '')}/.well-known/openid-configuration`);
  if (!discoveryResponse.ok) throw new Error('Identity provider is unavailable.');
  discovery = /** @type {Discovery} */ (await discoveryResponse.json());
  const query = new URLSearchParams(location.search);
  if (query.has('error')) {
    history.replaceState({}, '', '/?signed-out=1');
    throw new Error('Sign-in was not completed. Please try again.');
  }
  if (query.has('code')) await finishSignIn(query);
}

/** @param {URLSearchParams} query */
async function finishSignIn(query) {
  const saved = sessionStorage.getItem(PKCE_KEY);
  sessionStorage.removeItem(PKCE_KEY);
  if (!saved || !discovery || !config) throw new Error('Sign-in session expired. Please try again.');
  const { state, verifier, returnHash } = JSON.parse(saved);
  if (state !== query.get('state') || !query.get('code')) {
    throw new Error('Sign-in could not be verified. Please try again.');
  }
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: config.clientId,
    code: query.get('code') || '',
    redirect_uri: `${location.origin}/`,
    code_verifier: verifier,
  });
  const response = await fetch(discovery.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!response.ok) throw new Error('Sign-in could not be completed. Please try again.');
  saveTokens(/** @type {TokenResponse} */ (await response.json()));
  history.replaceState({}, '', `/${returnHash || '#/wallets'}`);
}

/** @param {TokenResponse} result */
function saveTokens(result) {
  tokens = result;
  expiresAt = Date.now() + Math.max(0, result.expires_in - 15) * 1000;
}

export function getSession() {
  if (!config) return { mode: 'loading', signedIn: false, ownerId: '', operator: false };
  if (config.mode === 'development') {
    const ownerId = sessionStorage.getItem(DEV_OWNER_KEY) || '';
    return { mode: 'development', signedIn: UUID.test(ownerId), ownerId, operator: false };
  }
  const claims = tokens ? tokenClaims(tokens.access_token) : {};
  const realmAccess = typeof claims.realm_access === 'object' && claims.realm_access !== null
    ? /** @type {{roles?: unknown}} */ (claims.realm_access) : {};
  const roles = Array.isArray(realmAccess.roles) ? realmAccess.roles : [];
  return {
    mode: 'production',
    signedIn: Boolean(tokens),
    ownerId: typeof claims.ledgerx_owner_id === 'string' ? claims.ledgerx_owner_id : '',
    operator: roles.includes('ledgerx-operator'),
  };
}

/** @param {string} token */
function tokenClaims(token) {
  try {
    const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return /** @type {Record<string, unknown>} */ (JSON.parse(atob(encoded)));
  } catch { return {}; }
}

/** @param {string} ownerId */
export function setDevelopmentOwner(ownerId) {
  if (!UUID.test(ownerId.trim())) throw new Error('Enter a valid owner UUID.');
  sessionStorage.setItem(DEV_OWNER_KEY, ownerId.trim());
}

export async function signIn() {
  if (!config || !discovery) throw new Error('Identity provider is not ready.');
  const verifier = randomUrlSafe(48);
  const state = randomUrlSafe(24);
  const challenge = base64Url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
  sessionStorage.setItem(PKCE_KEY, JSON.stringify({ state, verifier, returnHash: location.hash }));
  const url = new URL(discovery.authorization_endpoint);
  url.search = new URLSearchParams({
    client_id: config.clientId,
    response_type: 'code',
    scope: 'openid profile',
    redirect_uri: `${location.origin}/`,
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  }).toString();
  location.assign(url.toString());
}

export async function accessToken() {
  if (!config || config.mode !== 'production') return null;
  if (!tokens) throw new Error('Your session has ended. Sign in again.');
  if (Date.now() < expiresAt) return tokens.access_token;
  if (!tokens.refresh_token || !discovery) throw new Error('Your session has ended. Sign in again.');
  const response = await fetch(discovery.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: config.clientId,
      refresh_token: tokens.refresh_token,
    }),
  });
  if (!response.ok) {
    tokens = null;
    throw new Error('Your session has ended. Sign in again.');
  }
  saveTokens(/** @type {TokenResponse} */ (await response.json()));
  return tokens.access_token;
}

export function signOut() {
  const idToken = tokens?.id_token;
  tokens = null;
  if (config?.mode === 'development') {
    sessionStorage.removeItem(DEV_OWNER_KEY);
    location.assign('/?signed-out=1');
    return;
  }
  if (discovery?.end_session_endpoint && config) {
    const url = new URL(discovery.end_session_endpoint);
    url.searchParams.set('client_id', config.clientId);
    url.searchParams.set('post_logout_redirect_uri', `${location.origin}/?signed-out=1`);
    if (idToken) url.searchParams.set('id_token_hint', idToken);
    location.assign(url.toString());
  } else {
    location.assign('/?signed-out=1');
  }
}

/** @param {number} byteCount */
function randomUrlSafe(byteCount) {
  return base64Url(crypto.getRandomValues(new Uint8Array(byteCount)));
}

/** @param {Uint8Array} bytes */
function base64Url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
