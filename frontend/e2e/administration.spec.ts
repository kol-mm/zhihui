import { expect, test } from '@playwright/test';
import { ADMIN, DEMO, SUPER_ADMIN, api, openAdminSection, signIn } from './session';

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

  /**
   * 用户管理 is a component of its own, reached through props and an emit. These cover the seams: that the page
   * can still refresh it, that its filters reach the server, that the counts handed to it are shown, and that
   * the password-reset queue below the table came along with it.
   */
  test('refreshing the page reloads the user list without losing the filter', async ({ page }) => {
    const token = await signIn(page, ADMIN);
    await page.goto('/');
    await openAdminSection(page, '用户管理');
    const users = page.locator('.admin-list-surface:not(.reset-request-surface) .admin-desktop-table');
    await expect(users.locator('tr', { hasText: '@admin' })).toBeVisible();

    await page.getByPlaceholder('搜索 ID、用户名、昵称或邮箱').fill('demo');
    await expect(users.locator('tr', { hasText: '@admin' })).toHaveCount(0);
    await expect(users.locator('tr', { hasText: '@demo' })).toContainText('正常');

    try {
      // Changed behind the page's back, so the refresh has to actually fetch again to show it — a refresh that
      // quietly did nothing would leave the row reading 正常 and fail here.
      await api(token, 'POST', '/user/admin/status', { userId: DEMO.userId, status: 'DISABLED' });
      await page.locator('.topbar-refresh').filter({ visible: true }).first().click();

      await expect(users.locator('tr', { hasText: '@demo' })).toContainText('已停用');
      // And the keyword the administrator typed is still in force.
      await expect(users.locator('tr', { hasText: '@admin' })).toHaveCount(0);
    } finally {
      await api(token, 'POST', '/user/admin/status', { userId: DEMO.userId, status: 'ACTIVE' });
    }

    await page.locator('.topbar-refresh').filter({ visible: true }).first().click();
    await expect(users.locator('tr', { hasText: '@demo' })).toContainText('正常');
  });

  test('the summary strip keeps counting the whole site while the table is filtered', async ({ page }) => {
    await signIn(page, ADMIN);
    await page.goto('/');
    await openAdminSection(page, '用户管理');
    const strip = page.locator('.admin-summary-strip');
    await expect(strip).toContainText(/全部\s*[1-9]/);

    await page.getByPlaceholder('搜索 ID、用户名、昵称或邮箱').fill('demo');

    await expect(page.locator('.admin-user-filters')).toContainText('找到');
    await expect(strip).toContainText(/全部\s*[1-9]/);
  });

  test('the password reset queue sits under the user table', async ({ page }) => {
    await signIn(page, ADMIN);
    await page.goto('/');
    await openAdminSection(page, '用户管理');

    const resets = page.locator('.reset-request-surface');
    await expect(resets).toContainText('密码重置申请');
    await expect(resets.getByRole('combobox', { name: '按状态筛选重置申请' })).toBeVisible();
  });
});
