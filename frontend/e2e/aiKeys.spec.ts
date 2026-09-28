import { expect, test, type Page } from '@playwright/test';
import { ADMIN, SUPER_ADMIN, api, mintToken, openAdminSection, signIn } from './session';

/**
 * 模型密钥 — the keys ai-service uses to call its model provider.
 *
 * The keys here are clearly fake (sk-ant-api03-e2e…), so no real account is involved; testing one still goes to
 * Anthropic and comes back refused, which is the path worth seeing on screen. Every key made here is deleted
 * afterwards, and AI 与系统 is looked at but never saved, so the live configuration is left as it was.
 */
const PREFIX = 'e2e-model-';
const fakeKey = (tag: string) => `sk-ant-api03-e2e${tag}${'Q'.repeat(64)}Zx7${tag.slice(-1) || 'k'}`;

async function openKeys(page: Page) {
  await signIn(page, SUPER_ADMIN);
  await page.goto('/');
  await openAdminSection(page, '模型密钥');
  await expect(page.getByRole('heading', { level: 3, name: '模型密钥', exact: true })).toBeVisible();
}

async function addKey(page: Page, name: string, secret: string, activate: boolean) {
  await page.getByRole('button', { name: '添加密钥' }).click();
  const dialog = page.getByRole('dialog', { name: '添加模型密钥' });
  await dialog.getByPlaceholder('例如：生产环境主密钥').fill(name);
  await dialog.getByLabel('密钥', { exact: true }).fill(secret);
  const box = dialog.locator('.el-checkbox');
  if ((await box.locator('input').isChecked()) !== activate) await box.click();
  await dialog.getByRole('button', { name: '保存' }).click();
  await expect(dialog).toBeHidden();
}

test.describe('model provider keys', () => {
  test.afterAll(async () => {
    const token = mintToken(SUPER_ADMIN);
    const listed = await api(token, 'GET', '/ai/admin/provider-keys');
    for (const key of listed.data?.data?.keys ?? []) {
      if (String(key.name).startsWith(PREFIX)) await api(token, 'POST', '/ai/admin/provider-keys/delete', { keyId: key.id });
    }
  });

  test('an ordinary administrator is not offered the screen, and cannot reach it directly', async ({ page }) => {
    await signIn(page, ADMIN);
    await page.goto('/');
    await expect(page.getByRole('button', { name: '运营概览', exact: true }).filter({ visible: true }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '模型密钥', exact: true })).toHaveCount(0);
    const direct = await api(mintToken(ADMIN), 'GET', '/ai/admin/provider-keys');
    expect(direct.status).toBe(403);
  });

  test('a saved key shows only its ends, never the whole of it', async ({ page }) => {
    await openKeys(page);
    const name = PREFIX + Date.now();
    const secret = fakeKey('a');
    await addKey(page, name, secret, false);

    const row = page.locator('.admin-desktop-table tr', { hasText: name });
    await expect(row).toContainText('sk-ant-api03-…');
    await expect(row).toContainText(secret.slice(-4));
    await expect(row).toContainText('备用');
    await expect(page.locator('body')).not.toContainText(secret);
  });

  test('switching a key on and off changes what Claude is called with', async ({ page }) => {
    await openKeys(page);
    const name = PREFIX + 'switch-' + Date.now();
    await addKey(page, name, fakeKey('b'), false);
    const claude = page.locator('.ai-key-providers article', { hasText: 'Anthropic Claude' });

    await page.locator('.admin-desktop-table tr', { hasText: name }).getByRole('button', { name: '启用' }).click();
    await expect(claude).toContainText(`平台中启用的「${name}」`);
    await expect(page.locator('.admin-desktop-table tr', { hasText: name })).toContainText('使用中');

    await page.locator('.admin-desktop-table tr', { hasText: name }).getByRole('button', { name: '停用' }).click();
    await page.locator('.el-message-box').getByRole('button', { name: '停用' }).click();
    await expect(claude).not.toContainText(name);
    await expect(page.locator('.admin-desktop-table tr', { hasText: name })).toContainText('备用');
  });

  test('testing a key asks Anthropic and shows what it said', async ({ page }) => {
    await openKeys(page);
    const name = PREFIX + 'test-' + Date.now();
    await addKey(page, name, fakeKey('c'), false);
    const row = page.locator('.admin-desktop-table tr', { hasText: name });
    await expect(row).toContainText('未测试');

    await row.getByRole('button', { name: '测试' }).click();
    // A fake key is refused by Anthropic; without internet access it cannot connect. Either way it did not pass.
    await expect(row).toContainText('测试未通过', { timeout: 30_000 });
    await expect(row).toContainText(/密钥无效或已被撤销|无法连接到 Anthropic|连接 Anthropic 超时/);
  });

  test('an Anthropic admin key is turned away before it is sent', async ({ page }) => {
    await openKeys(page);
    await page.getByRole('button', { name: '添加密钥' }).click();
    const dialog = page.getByRole('dialog', { name: '添加模型密钥' });
    await dialog.getByPlaceholder('例如：生产环境主密钥').fill(PREFIX + 'admin');
    await dialog.getByLabel('密钥', { exact: true }).fill('sk-ant-admin01-' + 'a'.repeat(60));
    await dialog.getByRole('button', { name: '保存' }).click();
    await expect(dialog.getByRole('alert')).toContainText('管理密钥');
  });

  test('AI 与系统 offers Claude and leaves the address fields to OpenAI-compatible services', async ({ page }) => {
    await signIn(page, SUPER_ADMIN);
    await page.goto('/');
    await openAdminSection(page, 'AI 与系统');
    const provider = page.locator('.el-form-item', { hasText: 'AI 提供商' }).locator('.el-select');
    await provider.click();
    await page.locator('.el-select-dropdown__item', { hasText: 'Anthropic Claude' }).filter({ visible: true }).click();

    await expect(page.getByText('密钥在「模型密钥」中管理')).toBeVisible();
    await expect(page.locator('.el-form-item', { hasText: '完整请求地址' }).locator('input')).toBeDisabled();
    await expect(page.getByText('这一项对 Claude 不生效')).toBeVisible();
    // Not saved: the live configuration stays as it was.
  });

  test('AI review can be given its own model, and the address follows whichever purpose needs it', async ({ page }) => {
    await signIn(page, SUPER_ADMIN);
    await page.goto('/');
    await openAdminSection(page, 'AI 与系统');
    const choose = async (label: string, option: string) => {
      const select = page.locator('.el-form-item', { hasText: label }).locator('.el-select').first();
      await select.click();
      // This select's own list: the one just closed is still fading out and holds options of the same names.
      const list = await select.locator('[aria-controls]').first().getAttribute('aria-controls');
      await page.locator(`[id="${list}"] .el-select-dropdown__item`, { hasText: option }).click();
    };
    const address = page.locator('.el-form-item', { hasText: '完整请求地址' }).locator('input');

    // By default review follows chat.
    await expect(page.locator('.el-form-item', { hasText: '审核模型服务' }).locator('.el-select')).toContainText('与 AI 检索相同');

    // Chat on Claude, review on an OpenAI-compatible service: the address is needed again, and the model is asked for.
    await choose('AI 提供商', 'Anthropic Claude');
    await expect(address).toBeDisabled();
    await choose('审核模型服务', 'OpenAI 兼容接口');
    await expect(address).toBeEnabled();
    await expect(page.locator('.el-form-item', { hasText: '审核模型名称' }).locator('input'))
      .toHaveAttribute('placeholder', /必填/);

    // Local rules only: there is no model to name.
    await choose('审核模型服务', '仅本地规则');
    await expect(page.locator('.el-form-item', { hasText: '审核模型名称' })).toHaveCount(0);

    await page.getByRole('button', { name: '撤销更改' }).click();
    // Not saved: the live configuration stays as it was.
  });
});
