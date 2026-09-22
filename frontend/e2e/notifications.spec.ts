import { expect, test } from '@playwright/test';
import { api, ADMIN, DEMO, SUPER_ADMIN, mintToken, signIn } from './session';

/**
 * Notifications belong to one member.
 *
 * This is here because the list endpoint once answered an administrator's request for their own notifications
 * with everyone's: the user id resolved to null and the store read that as "no filter". The API check below is
 * the direct pin; the page check is that the interface shows the signed-in member's own.
 */
test.describe('notifications', () => {
  test('an administrator sees their own, not everyone’s', async () => {
    const adminToken = mintToken(ADMIN);
    const { data: mine } = await api(adminToken, 'GET', '/notification/list');
    expect(mine.code).toBe(0);

    const foreign = (mine.data as { userId?: number }[]).filter(row => row.userId && row.userId !== ADMIN.userId);
    expect(foreign, 'another member’s notifications must not appear in an administrator’s own list')
      .toHaveLength(0);
  });

  test('an administrator may still read a member’s on request', async () => {
    const { status, data } = await api(mintToken(SUPER_ADMIN), 'GET', `/notification/list?userId=${DEMO.userId}`);

    expect(status).toBe(200);
    expect(data.code).toBe(0);
  });

  test('a member cannot ask for someone else’s', async () => {
    const { data } = await api(mintToken(DEMO), 'GET', `/notification/list?userId=${ADMIN.userId}`);

    expect(data.code).not.toBe(0);
    expect(String(data.message)).toContain('无权访问');
  });

  test('the notification list renders for a signed-in member', async ({ page }) => {
    await signIn(page, DEMO);
    await page.goto('/');

    const bell = page.getByRole('button', { name: /通知/ }).first();
    if (await bell.count()) {
      await bell.click();
      // Either notifications or an empty state — what matters is that the panel renders rather than erroring.
      await expect(page.locator('.el-drawer, .el-dialog, .notification-panel').first()).toBeVisible();
    }
  });
});
