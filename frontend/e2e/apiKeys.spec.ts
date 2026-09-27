import { expect, test, type Page } from '@playwright/test';
import { ADMIN, SUPER_ADMIN, api, mintToken, openAdminSection, signIn } from './session';

/**
 * 接口密钥, from the super administrator's side and from the side of the system holding a key.
 *
 * The keys these tests make are named e2e-key-…; whatever the tests leave live is revoked afterwards. Revoked keys
 * stay in the list by design — the screen keeps them out of sight unless asked.
 */
const PREFIX = 'e2e-key-';

async function openKeys(page: Page) {
  await signIn(page, SUPER_ADMIN);
  await page.goto('/');
  await openAdminSection(page, '接口密钥');
  await expect(page.getByRole('heading', { level: 3, name: '接口密钥', exact: true })).toBeVisible();
}

async function createKey(page: Page, name: string, scopeLabel: string): Promise<string> {
  await page.getByRole('button', { name: '新建密钥' }).click();
  const form = page.getByRole('dialog', { name: '新建接口密钥' });
  await form.getByPlaceholder('例如：数据中心同步').fill(name);
  await form.locator('.el-checkbox', { hasText: scopeLabel }).click();
  await form.getByRole('button', { name: '创建' }).click();

  const shown = page.getByTestId('api-key-secret');
  await expect(shown).toHaveText(/^zk_[A-Za-z0-9_-]{43}$/);
  return (await shown.textContent())!.trim();
}

test.describe('API keys', () => {
  test.afterAll(async () => {
    const token = mintToken(SUPER_ADMIN);
    const listed = await api(token, 'GET', '/user/admin/api-keys');
    for (const key of listed.data?.data?.items ?? []) {
      if (String(key.name).startsWith(PREFIX) && key.status !== 'REVOKED') {
        await api(token, 'POST', '/user/admin/api-keys/revoke', { keyId: key.id, reason: 'e2e cleanup' });
      }
    }
  });

  test('an ordinary administrator is not offered the screen', async ({ page }) => {
    await signIn(page, ADMIN);
    await page.goto('/');
    await expect(page.getByRole('button', { name: '运营概览', exact: true }).filter({ visible: true }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '接口密钥', exact: true })).toHaveCount(0);
  });

  test('a new key is shown once, and the list keeps only its prefix', async ({ page }) => {
    await openKeys(page);
    const name = PREFIX + Date.now();
    const secret = await createKey(page, name, '读取知识库');

    await page.getByRole('button', { name: '我已保存' }).click();
    const row = page.locator('.admin-desktop-table tr', { hasText: name });
    await expect(row).toContainText(secret.slice(0, 11));
    await expect(row).toContainText('读取知识库');
    // Once the dialog is closed, the full secret is nowhere on the page.
    await expect(page.locator('body')).not.toContainText(secret);
  });

  test('a key made here works against the API, and revoking it here stops it', async ({ page }) => {
    await openKeys(page);
    const name = PREFIX + Date.now();
    const secret = await createKey(page, name, '读取知识库');
    await page.getByRole('button', { name: '我已保存' }).click();

    const before = await api(secret, 'GET', '/knowledge/list');
    expect(before.status).toBe(200);
    expect(before.data.code).toBe(0);
    // Its scope is knowledge:read only; community reads stay shut.
    expect((await api(secret, 'GET', '/square/feed')).status).toBe(403);

    await page.locator('.admin-desktop-table tr', { hasText: name }).getByRole('button', { name: '撤销' }).click();
    const confirm = page.locator('.el-message-box');
    await confirm.getByPlaceholder('例如：合作已结束').fill('e2e: revoke from the screen');
    await confirm.getByRole('button', { name: '撤销' }).click();
    await expect(page.getByText('接口密钥已撤销')).toBeVisible();

    expect((await api(secret, 'GET', '/knowledge/list')).status).toBe(401);
    // Revoked keys leave the default view but are still there when asked for.
    await expect(page.locator('.admin-desktop-table tr', { hasText: name })).toHaveCount(0);
    await page.getByText('已撤销', { exact: true }).first().click();
    await expect(page.locator('.admin-desktop-table tr', { hasText: name })).toContainText('已撤销');
  });

  test('a key that can write has to name the account it writes as', async ({ page }) => {
    await openKeys(page);
    await page.getByRole('button', { name: '新建密钥' }).click();
    const form = page.getByRole('dialog', { name: '新建接口密钥' });
    await form.getByPlaceholder('例如：数据中心同步').fill(PREFIX + 'writer');
    await form.locator('.el-checkbox', { hasText: '发帖与评论' }).click();
    await expect(form.getByText('代为操作的账号（必填）')).toBeVisible();

    await form.getByRole('button', { name: '创建' }).click();
    await expect(form.getByRole('alert')).toHaveText('带写权限的接口密钥必须指定一个代为操作的账号，写入的内容归属该账号');
    await expect(page.getByTestId('api-key-secret')).toHaveCount(0);
  });
});
