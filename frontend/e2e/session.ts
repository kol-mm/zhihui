import { createHmac } from 'node:crypto';
import { test, type Page } from '@playwright/test';

/**
 * Signing in without going through the login form.
 *
 * Login is captcha-protected, and a test that answers a captcha is a test that erodes the protection it is
 * meant to leave alone. Instead a session is minted with the deployment's own signing key and seeded into the
 * browser, exactly as the app itself would hold it: the stored token is adopted into a cookie session on the
 * first load. Nothing in the product changes for the sake of the tests, and no security control is weakened.
 *
 * The consequence to be aware of: the login form is not covered by these tests. It needs its own check.
 */

const DEVELOPMENT_SECRET = 'local-dev-secret-change-before-production';

export type Member = {
  username: string;
  userId: number;
  role: 'USER' | 'ADMIN';
  superAdmin?: boolean;
  nickname?: string;
};

export const DEMO: Member = { username: 'demo', userId: 1, role: 'USER', nickname: 'Demo User' };
export const ADMIN: Member = { username: 'admin', userId: 2, role: 'ADMIN', nickname: 'Local Admin' };
export const SUPER_ADMIN: Member = { ...ADMIN, superAdmin: true };

function base64url(value: Buffer | string): string {
  return Buffer.from(value).toString('base64url');
}

/** The key the deployment signs with. Set E2E_JWT_SECRET to match a deployment of your own. */
export function signingSecret(): string {
  return process.env.E2E_JWT_SECRET || process.env.AI_KNOWLEDGE_JWT_SECRET || DEVELOPMENT_SECRET;
}

export function mintToken(member: Member, lifetimeSeconds = 1800): string {
  const issuedAt = Math.floor(Date.now() / 1000);
  const claims: Record<string, unknown> = {
    sub: member.username,
    uid: member.userId,
    role: member.role,
    iat: issuedAt,
    exp: issuedAt + lifetimeSeconds,
    jti: `e2e-${issuedAt}-${Math.random().toString(36).slice(2)}`,
  };
  if (member.superAdmin) claims.sa = true;

  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(JSON.stringify(claims));
  const signature = createHmac('sha256', signingSecret()).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

/**
 * The gateway allows each client 600 reads a minute, and every test's browser is the same client. Past about 30
 * tests the suite outran that and the last tests saw 429s instead of pages. The limit is the product protecting
 * itself, so the tests keep within it rather than it being raised for them: requests are counted over a rolling
 * minute, and a test that would take the count past the budget waits for the window to move first. The budget
 * sits well under 600 because api() calls and the gateway's own window boundaries are not counted exactly here.
 */
const REQUEST_BUDGET_PER_MINUTE = 420;
const recentRequests: number[] = [];

async function keepWithinRateLimit(page: Page): Promise<void> {
  const windowStart = () => Date.now() - 60_000;
  while (recentRequests.length && recentRequests[0] < windowStart()) recentRequests.shift();
  if (recentRequests.length >= REQUEST_BUDGET_PER_MINUTE) {
    // Wait until enough of the window has passed that a test's worth of requests (about 40) fits again.
    const release = recentRequests[recentRequests.length - REQUEST_BUDGET_PER_MINUTE + 40] ?? recentRequests[0];
    const wait = Math.max(0, release + 60_000 - Date.now()) + 250;
    // The pause is not the test's own time, so it does not count against the test's timeout.
    test.info().setTimeout(test.info().timeout + wait);
    await new Promise(resolve => setTimeout(resolve, wait));
  }
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/')) recentRequests.push(Date.now());
  });
}

/**
 * Seeds the session before any page script runs, so the app comes up signed in rather than on the login form.
 */
export async function signIn(page: Page, member: Member): Promise<string> {
  await keepWithinRateLimit(page);
  const token = mintToken(member);
  const entries: Record<string, string> = {
    'ai-knowledge-local-token': token,
    'ai-knowledge-user-id': String(member.userId),
    'ai-knowledge-username': member.username,
    'ai-knowledge-name': member.nickname || member.username,
    'ai-knowledge-role': member.role,
    'ai-knowledge-super-admin': member.superAdmin ? '1' : '0',
    // Every test starts with a fresh profile, so the first-visit guide would open over the page and swallow
    // the first click of every test. It has its own coverage; here it is marked as already seen.
    [`ai-knowledge-onboarding-v1-${member.userId}`]: 'done',
  };
  await page.addInitScript(stored => {
    for (const [key, value] of Object.entries(stored)) {
      try {
        window.localStorage.setItem(key, value as string);
      } catch {
        /* a browser refusing storage is not this test's subject */
      }
    }
  }, entries);
  return token;
}

/** Calls the API the way the app does, for creating and removing the content a test needs. */
export async function api(
  token: string,
  method: string,
  path: string,
  body?: unknown,
): Promise<{ status: number; data: any }> {
  const baseUrl = process.env.E2E_BASE_URL || 'http://127.0.0.1:8088';
  const response = await fetch(`${baseUrl}/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Authorization: `Bearer ${token}`,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let data: any = text;
  try {
    data = JSON.parse(text);
  } catch {
    /* not every endpoint answers with JSON */
  }
  return { status: response.status, data };
}


/**
 * The admin sections are buttons, not links, and the markup carries two copies of the navigation — a sidebar
 * and a drawer for narrow screens. This picks the one a person could actually click.
 */
export async function openAdminSection(page: Page, name: string): Promise<void> {
  const entry = page.getByRole('button', { name, exact: true }).filter({ visible: true }).first();
  await entry.click();
}
