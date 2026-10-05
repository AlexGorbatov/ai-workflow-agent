// Login with Keycloak: authorization code + PKCE, no library. The tokens live in this module's memory only;
// sessionStorage keeps just the PKCE verifier and state for the round trip to Keycloak. A reload logs in again
// (silently while the Keycloak session lasts).

let config;
let tokens;
let refreshTimer;

const b64url = (bytes) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const random = () => b64url(crypto.getRandomValues(new Uint8Array(32)));
const redirectUri = () => `${location.origin}/ui/`;
const endpoint = (name) => `${config.issuer}/protocol/openid-connect/${name}`;

function claims(token) {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(decodeURIComponent(escape(atob(payload))));
}

async function requestTokens(params) {
  const response = await fetch(endpoint('token'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: config.clientId, ...params }),
  });
  if (!response.ok) throw new Error(`Login failed (${response.status})`);
  tokens = await response.json();
  clearTimeout(refreshTimer);
  // refresh a little before the access token expires
  refreshTimer = setTimeout(refresh, Math.max(5, tokens.expires_in - 30) * 1000);
}

async function refresh() {
  try {
    await requestTokens({ grant_type: 'refresh_token', refresh_token: tokens.refresh_token });
  } catch {
    login();
  }
}

async function login() {
  const verifier = random();
  const state = random();
  sessionStorage.setItem('pkce', JSON.stringify({ verifier, state, hash: location.hash }));
  const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  location.assign(`${endpoint('auth')}?${new URLSearchParams({
    client_id: config.clientId,
    response_type: 'code',
    scope: 'openid',
    redirect_uri: redirectUri(),
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  })}`);
}

/** Resolves once logged in; may redirect to Keycloak instead. */
export async function init() {
  config = await (await fetch('/ui/config.json')).json();
  const params = new URLSearchParams(location.search);
  const pending = JSON.parse(sessionStorage.getItem('pkce') || 'null');
  if (params.has('code') && pending && params.get('state') === pending.state) {
    sessionStorage.removeItem('pkce');
    await requestTokens({
      grant_type: 'authorization_code',
      code: params.get('code'),
      redirect_uri: redirectUri(),
      code_verifier: pending.verifier,
    });
    history.replaceState(null, '', `/ui/${pending.hash || ''}`);
    return;
  }
  await login();
  await new Promise(() => {}); // the browser is leaving for Keycloak
}

export function accessToken() {
  return tokens?.access_token;
}

export function user() {
  const c = claims(tokens.access_token);
  return { name: c.preferred_username, roles: c.realm_access?.roles ?? [] };
}

export function logout() {
  const idToken = tokens?.id_token;
  tokens = undefined;
  location.assign(`${endpoint('logout')}?${new URLSearchParams({
    client_id: config.clientId,
    post_logout_redirect_uri: redirectUri(),
    ...(idToken ? { id_token_hint: idToken } : {}),
  })}`);
}
