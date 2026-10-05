// Login with Keycloak: authorization code + PKCE, no library. Tokens live in memory only; sessionStorage keeps
// just the PKCE verifier and state for the round trip. A reload logs in again (silently while the Keycloak
// session lasts).

interface Config { issuer: string; clientId: string }
interface Tokens { access_token: string; refresh_token: string; id_token?: string; expires_in: number }
export interface User { name: string; roles: string[] }

let config: Config;
let tokens: Tokens | undefined;
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let ready: Promise<User> | undefined;

const b64url = (bytes: ArrayBuffer | Uint8Array) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const random = () => b64url(crypto.getRandomValues(new Uint8Array(32)));
// Keycloak sends the user back to the page they started on (the client allows any path under the origin).
const pageUri = () => `${location.origin}${location.pathname}`;
const endpoint = (name: string) => `${config.issuer}/protocol/openid-connect/${name}`;

function claims(token: string) {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  const json = new TextDecoder().decode(Uint8Array.from(atob(payload), (c) => c.charCodeAt(0)));
  return JSON.parse(json) as { preferred_username: string; realm_access?: { roles: string[] } };
}

async function requestTokens(params: Record<string, string>) {
  const response = await fetch(endpoint('token'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: config.clientId, ...params }),
  });
  if (!response.ok) throw new Error(`Login failed (${response.status})`);
  tokens = (await response.json()) as Tokens;
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refresh, Math.max(5, tokens.expires_in - 30) * 1000);
}

async function refresh() {
  try {
    await requestTokens({ grant_type: 'refresh_token', refresh_token: tokens!.refresh_token });
  } catch {
    await login();
  }
}

async function login(): Promise<never> {
  const verifier = random();
  const state = random();
  const redirect = pageUri();
  sessionStorage.setItem('pkce', JSON.stringify({ verifier, state, redirect, query: location.search }));
  const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  location.assign(`${endpoint('auth')}?${new URLSearchParams({
    client_id: config.clientId,
    response_type: 'code',
    scope: 'openid',
    redirect_uri: redirect,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  })}`);
  return new Promise<never>(() => {}); // the browser is leaving for Keycloak
}

function currentUser(): User {
  const c = claims(tokens!.access_token);
  return { name: c.preferred_username, roles: c.realm_access?.roles ?? [] };
}

async function start(): Promise<User> {
  config = (await (await fetch('/ui/config.json')).json()) as Config;
  const params = new URLSearchParams(location.search);
  const pending = JSON.parse(sessionStorage.getItem('pkce') ?? 'null') as
    | { verifier: string; state: string; redirect: string; query: string }
    | null;
  if (params.has('code') && pending && params.get('state') === pending.state) {
    sessionStorage.removeItem('pkce');
    await requestTokens({
      grant_type: 'authorization_code',
      code: params.get('code')!,
      redirect_uri: pending.redirect,
      code_verifier: pending.verifier,
    });
    // back to the address the user asked for, without the code; no reload, the tokens live in memory
    history.replaceState(null, '', location.pathname + pending.query);
    return currentUser();
  }
  return login();
}

/** Resolves once logged in; may redirect to Keycloak instead. Safe to call more than once. */
export function signIn(): Promise<User> {
  ready ??= start();
  return ready;
}

export const accessToken = () => tokens?.access_token;

export function logout() {
  const idToken = tokens?.id_token;
  tokens = undefined;
  location.assign(`${endpoint('logout')}?${new URLSearchParams({
    client_id: config.clientId,
    post_logout_redirect_uri: `${location.origin}/ui/`,
    ...(idToken ? { id_token_hint: idToken } : {}),
  })}`);
}
