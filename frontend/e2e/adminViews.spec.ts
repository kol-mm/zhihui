import { expect, test } from '@playwright/test';
import { SUPER_ADMIN, openAdminSection, signIn } from './session';

/**
 * Every admin screen, opened in turn.
 *
 * The admin workspace is now seven components rather than one file, each handed the page state its markup
 * names. A name spelled one way in the page and another in the view would not fail the build — it would hand
 * the view undefined and render a blank or broken screen. So this walks the lot, insists each one puts its own
 * heading up, and fails on any error the page logs while doing it.
 */
const SCREENS: [string, string][] = [
  ['运营概览', '平台运营概览'],
  ['内容审核', '内容审核'],
  ['用户管理', '用户管理'],
  ['平台数据统计', '平台数据统计'],
  ['操作记录', '操作记录'],
  ['工单与常见问题', '工单与常见问题'],
  ['AI 与系统', '平台与 AI 配置'],
];

test.describe('the admin workspace', () => {
  test('every screen opens and renders its own page', async ({ page }) => {
    const problems: string[] = [];
    page.on('console', message => {
      if (message.type() === 'error') problems.push(message.text());
    });
    page.on('pageerror', error => problems.push(String(error)));

    await signIn(page, SUPER_ADMIN);
    await page.goto('/');

    for (const [entry, heading] of SCREENS) {
      await openAdminSection(page, entry);
      await expect(page.getByRole('heading', { name: heading, exact: true }).first()).toBeVisible();
    }

    expect(problems).toEqual([]);
  });
});
