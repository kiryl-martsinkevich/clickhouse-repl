// OIDC authorization code flow with PKCE (S256) for a public client. The access token lives in
// memory only; the PKCE verifier and state survive the redirect in sessionStorage.

export interface PortalConfig {
  issuer: string;
  client_id: string;
  scope: string;
}

interface Discovery {
  authorization_endpoint: string;
  token_endpoint: string;
}

interface PendingLogin {
  verifier: string;
  state: string;
  returnTo: string;
}

const PENDING = "chrepl.pkce";
let accessToken: string | null = null;
let expiresAt = 0;

export async function loadConfig(): Promise<PortalConfig> {
  const response = await fetch("/portal/config.json");
  if (!response.ok) throw new Error(`portal configuration unavailable (HTTP ${response.status})`);
  return response.json();
}

async function discover(issuer: string): Promise<Discovery> {
  const response = await fetch(`${issuer.replace(/\/$/, "")}/.well-known/openid-configuration`);
  if (!response.ok) throw new Error(`cannot reach the sign-in service at ${issuer}`);
  return response.json();
}

function base64url(bytes: Uint8Array): string {
  let text = "";
  bytes.forEach((b) => (text += String.fromCharCode(b)));
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomString(bytes = 32): string {
  return base64url(crypto.getRandomValues(new Uint8Array(bytes)));
}

function redirectUri(): string {
  return `${location.origin}/`;
}

/** Sends the browser to the identity provider. */
export async function login(config: PortalConfig): Promise<never> {
  const discovery = await discover(config.issuer);
  const verifier = randomString(48);
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  const state = randomString(16);
  const pending: PendingLogin = { verifier, state, returnTo: location.hash || "#/new" };
  sessionStorage.setItem(PENDING, JSON.stringify(pending));
  const params = new URLSearchParams({
    response_type: "code",
    client_id: config.client_id,
    redirect_uri: redirectUri(),
    scope: config.scope,
    state,
    code_challenge: challenge,
    code_challenge_method: "S256",
  });
  location.assign(`${discovery.authorization_endpoint}?${params}`);
  return new Promise<never>(() => {});
}

/** Finishes a login when the IdP redirected back with a code; true when a token was obtained. */
export async function completeLogin(config: PortalConfig): Promise<boolean> {
  const params = new URLSearchParams(location.search);
  const code = params.get("code");
  if (!code) return false;
  const pending: PendingLogin | null = JSON.parse(sessionStorage.getItem(PENDING) ?? "null");
  sessionStorage.removeItem(PENDING);
  if (!pending || pending.state !== params.get("state")) throw new Error("sign-in failed: unexpected state; please try again");
  const discovery = await discover(config.issuer);
  const response = await fetch(discovery.token_endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      code_verifier: pending.verifier,
      client_id: config.client_id,
      redirect_uri: redirectUri(),
    }),
  });
  if (!response.ok) throw new Error("sign-in failed: the identity provider refused the code");
  const tokens = await response.json();
  accessToken = tokens.access_token;
  expiresAt = Date.now() + (tokens.expires_in ?? 300) * 1000;
  history.replaceState(null, "", `${location.pathname}${pending.returnTo}`);
  return true;
}

export function token(): string | null {
  return accessToken && Date.now() < expiresAt - 10_000 ? accessToken : null;
}

export function logout(): void {
  accessToken = null;
  expiresAt = 0;
}
