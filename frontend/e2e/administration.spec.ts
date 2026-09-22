import { expect, test } from '@playwright/test';
import { ADMIN, SUPER_ADMIN, openAdminSection, signIn } from './session';

/**
 * What an ordinary administrator may not do, and what the settings screen says about AI review.
 *
 * The rules are enforced by the services — these check that the interface tells the truth about them, so an
 * administrator is not offered a control that will be refused, and a super administrator is not blocked from
 * one that will not.
 */
test.describe('administration', () => {
  test('an ordinary administrator cannot appoint another administrator', async ({ page }) => {
    await signIn(page, ADMIN);
    await page.goto('/');
    await openAdminSection(page, '用户管理');

    await page.locator('.admin-desktop-table tr', { hasText: '@demo' })
      .getByRole('button', { name: '资料与治理' }).click();

    await expect(page.getByText('只有超级管理员可以任命或撤销管理员')).toBeVisible();
    // The control is disabled rather than offered and then refused. Ask the accessibility tree rather than a
    // class name: Element Plus marks a different element than the one carrying .el-select.
    await expect(page.getByRole('combobox', { name: '角色' })).toBeDisabled();
  });

  test('a super administrator may change a role', async ({ page }) => {
    await signIn(page, SUPER_ADMIN);
    await page.goto('/');
    await openAdminSection(page, '用户管理');

    await page.locator('.admin-desktop-table tr', { hasText: '@demo' })
      .getByRole('button', { name: '资料与治理' }).click();

    await expect(page.getByText('只有超级管理员可以任命或撤销管理员')).toHaveCount(0);
  });

  test('the super administrator is marked in the user list', async ({ page }) => {
    await signIn(page, SUPER_ADMIN);
    await page.goto('/');
    await openAdminSection(page, '用户管理');

    await expect(page.locator('.admin-desktop-table tr', { hasText: '@admin' }))
      .toContainText('超级管理员');
  });

  test('the AI upstream is out of an ordinary administrator’s reach', async ({ page }) => {
    await signIn(page, ADMIN);
    await page.goto('/');
    await openAdminSection(page, 'AI 与系统');

    await expect(page.getByText('只有超级管理员可以修改')).toBeVisible();
    await expect(page.getByLabel('模型名称')).toBeDisabled();
  });

  test('the settings screen reports what AI review is doing', async ({ page }) => {
    await signIn(page, SUPER_ADMIN);
    await page.goto('/');
    await openAdminSection(page, 'AI 与系统');

    const health = page.locator('.review-health');
    await expect(health).toBeVisible();
    // Whichever state it is in, it must say something rather than leave silence to be interpreted.
    await expect(health).toContainText(/未开启|没有收到请求|模型始终失败|部分调用失败|仅本地规则|运行正常/);
    await expect(page.locator('.review-health-counters')).toContainText('收到请求');
  });
});
