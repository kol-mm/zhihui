import { expect, test, type Page } from '@playwright/test';
import { api, DEMO, mintToken } from './session';

/**
 * The login form, which every other suite goes around (see session.ts).
 *
 * The captcha cannot be answered by a test without weakening it, and it is not weakened here. So the tests
 * divide the form at that point:
 *
 * - Everything up to the captcha check runs against the real services: the captcha is issued by user-service
 *   and drawn, the form's own checks stop an incomplete submission before anything is sent, and a wrong answer
 *   is refused by user-service and the form replaces the captcha.
 * - The one step past it — user-service accepting the answer — is played by the test. The login request is
 *   answered in the real response's shape with a real session cookie, signed with the deployment's key, and
 *   the test checks what the form does with a success: the workspace opens as that member, the token never
 *   reaches page storage, and the session holds against the real gateway after a reload.
 *
 * A wrong answer never reaches the password check, so these tests cannot lock the demo account.
 */

const LOGIN = '/api/user/login';
const CAPTCHA = '/api/user/captcha';
/** Typed into the form only in the test whose login request the test itself answers, so it never leaves the page. */
const NEVER_SENT_PASSWORD = 'e2e-password-never-sent';
/** What the page generates for its captcha key, and all user-service accepts. */
const CLIENT_KEY = /^[A-Za-z0-9-]{16,128}$/;

function loginForm(page: Page) {
  const panel = page.locator('.login-panel');
  return {
    panel,
    username: panel.locator('input[autocomplete="username"]'),
    password: panel.locator('input[type="password"]'),
    captcha: panel.getByPlaceholder('请输入图片中的计算结果'),
    captchaImage: panel.getByRole('img', { name: '图片计算验证码' }),
    submit: panel.getByRole('button', { name: '登录', exact: true }),
  };
}

/** Opens the page signed out, with the first-visit guide marked seen so it cannot cover the workspace later. */
async function openSignedOut(page: Page) {
  await page.addInitScript(key => {
    try {
      window.localStorage.setItem(key, 'done');
    } catch {
      /* a browser refusing storage is not this test's subject */
    }
  }, `ai-knowledge-onboarding-v1-${DEMO.userId}`);
  const captcha = page.waitForResponse(response => new URL(response.url()).pathname === CAPTCHA);
  await page.goto('/');
  const response = await captcha;
  const body = (await response.json()) as { code: number; data: { captchaId: string } };
  return { ...body, clientKey: response.request().headers()['x-captcha-client'] };
}

test.describe('the login form', () => {
  test('shows a captcha issued by user-service', async ({ page }) => {
    const issued = await openSignedOut(page);
    const form = loginForm(page);

    expect(issued.code).toBe(0);
    expect(issued.data.captchaId).toBeTruthy();
    expect(issued.clientKey).toMatch(CLIENT_KEY);
    await expect(form.captchaImage).toBeVisible();
    await expect(form.captchaImage).toHaveAttribute('src', /^data:image\/png;base64,/);
    await expect(form.username).toBeVisible();
    await expect(form.password).toBeVisible();
  });

  test('user-service gives no captcha to a request without the page\'s own key', async ({ request }) => {
    // Every visitor reaches user-service from the gateway's address, which is why it may never stand in for the key.
    for (const headers of [{}, { 'X-Captcha-Client': 'short' }, { 'X-Captcha-Client': 'has spaces in it, too' }]) {
      const answer = await request.get(CAPTCHA, { headers });
      const body = await answer.json();
      expect(body.code).not.toBe(0);
      expect(body.message).toBe('验证码请求无效，请刷新页面后重试');
      expect(body.data?.captchaId).toBeUndefined();
    }
  });

  test('stops an incomplete form before anything is sent', async ({ page }) => {
    await openSignedOut(page);
    const form = loginForm(page);
    const attempts: string[] = [];
    page.on('request', request => {
      if (new URL(request.url()).pathname === LOGIN) attempts.push(request.method());
    });

    await form.submit.click();
    await expect(page.getByText('请输入用户名')).toBeVisible();

    await form.username.fill('   ');
    await form.submit.click();
    await expect(page.getByText('请输入用户名').last()).toBeVisible();

    await form.username.fill(DEMO.username);
    await form.submit.click();
    await expect(page.getByText('请输入密码')).toBeVisible();

    await form.password.fill('any password');
    await form.submit.click();
    await expect(page.getByText('请输入验证码')).toBeVisible();

    expect(attempts).toEqual([]);
    await expect(form.submit).toBeVisible();
  });

  test('a wrong captcha is refused by user-service and replaced', async ({ page }) => {
    const issued = await openSignedOut(page);
    const form = loginForm(page);
    await expect(form.captchaImage).toBeVisible();
    const firstImage = await form.captchaImage.getAttribute('src');

    await form.username.fill(DEMO.username);
    await form.password.fill('not the password');
    // Every challenge is a two-digit number plus a single digit, so 0 is never the answer.
    await form.captcha.fill('0');
    const refused = page.waitForResponse(response => new URL(response.url()).pathname === LOGIN);
    const replaced = page.waitForResponse(response => new URL(response.url()).pathname === '/api/user/captcha');
    await form.submit.click();

    const refusal = await refused;
    // Answered under the key the challenge was issued for, so it is the answer that was refused, not the page.
    expect(refusal.request().headers()['x-captcha-client']).toBe(issued.clientKey);
    const body = await refusal.json();
    expect(body.code).not.toBe(0);
    await expect(page.getByText('验证码错误或已失效，请重新获取')).toBeVisible();
    expect((await (await replaced).json()).code).toBe(0);
    await expect(form.captchaImage).not.toHaveAttribute('src', firstImage || '');
    await expect(form.captcha).toHaveValue('');
    // Still signed out, and the name typed is kept so only the answer needs typing again.
    await expect(form.submit).toBeVisible();
    await expect(form.username).toHaveValue(DEMO.username);
  });

  test('a successful login opens the workspace and the session outlives a reload', async ({ page }) => {
    const issued = await openSignedOut(page);
    const form = loginForm(page);

    const token = mintToken(DEMO);
    const profile = await api(token, 'GET', `/user/info?username=${DEMO.username}`);
    expect(profile.data.code, profile.data.message).toBe(0);
    const member = profile.data.data;

    let sent: Record<string, unknown> | undefined;
    let sentKey: string | undefined;
    await page.route(`**${LOGIN}`, async route => {
      sent = route.request().postDataJSON();
      sentKey = route.request().headers()['x-captcha-client'];
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        // What user-service's startSession sends: the token only as an httpOnly cookie, never in the body.
        headers: { 'set-cookie': `zh_session=${token}; Path=/; HttpOnly; SameSite=Lax` },
        body: JSON.stringify({ code: 0, message: 'success', data: { role: DEMO.role, user: member } }),
      });
    });

    await form.username.fill(`  ${DEMO.username}  `);
    await form.password.fill(NEVER_SENT_PASSWORD);
    await form.captcha.fill('42');
    await form.submit.click();

    await expect(page.getByText('登录成功')).toBeVisible();
    // The form sends the challenge user-service issued, what was typed, and the name without its spaces.
    expect(sent).toEqual({ username: DEMO.username, password: NEVER_SENT_PASSWORD, captchaId: issued.data.captchaId, captchaAnswer: '42' });
    // ...under the same key it fetched the challenge with, or user-service would refuse the answer.
    expect(sentKey).toBe(issued.clientKey);

    await expect(form.submit).toBeHidden();
    await expect(page.getByRole('heading', { name: '最新知识', exact: true }).first()).toBeVisible();
    await expect(page.getByText(member.nickname || DEMO.nickname || DEMO.username).first()).toBeVisible();
    expect(await page.evaluate(() => window.localStorage.getItem('ai-knowledge-local-token'))).toBeNull();

    // From here on nothing is played by the test: the real gateway has to accept the cookie.
    await page.unroute(`**${LOGIN}`);
    const restored = page.waitForResponse(response => new URL(response.url()).pathname === '/api/user/session');
    await page.reload();
    expect((await restored).status()).toBe(200);
    await expect(page.getByRole('heading', { name: '最新知识', exact: true }).first()).toBeVisible();
    await expect(form.submit).toBeHidden();
  });
});
