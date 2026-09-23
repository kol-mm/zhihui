import { expect, test } from '@playwright/test';
import { DEMO, openAdminSection, signIn } from './session';

/**
 * Every screen a member sees, opened in turn.
 *
 * The same net as the admin workspace has, put in place before these views are split out of App.vue rather
 * than after: a name the page spells one way and a view another would not fail the build, it would hand the
 * view undefined and render a blank or broken screen. Running it against the unsplit page first is what makes
 * it a baseline.
 *
 * 关注广场 shares its markup with 社区论坛, and 通知中心 opens a drawer rather than a screen; both are covered
 * elsewhere.
 */
const SCREENS: [string, string][] = [
  ['工作台', '最新知识'],
  ['知识库', '知识库'],
  ['社区论坛', '我的创作'],
  ['消息中心', '消息中心'],
  ['AI 问答', '知识库 AI'],
  ['个人中心', '个人中心'],
];

test.describe('the member workspace', () => {
  test('every screen opens and renders its own page', async ({ page }) => {
    const problems: string[] = [];
    page.on('console', message => {
      if (message.type() === 'error') problems.push(message.text());
    });
    page.on('pageerror', error => problems.push(String(error)));

    await signIn(page, DEMO);
    await page.goto('/');

    for (const [entry, heading] of SCREENS) {
      await openAdminSection(page, entry);
      await expect(page.getByRole('heading', { name: heading, exact: true }).first()).toBeVisible();

    // A fragment of markup left as text renders happily and logs nothing, so look for it directly.
    const stray = await page.evaluate(() => {
      const text = document.body.innerText || '';
      return (text.match(/[^\s<>]*"\s*>/g) || []).slice(0, 3);
    });
    expect(stray, `stray markup on ${entry}`).toEqual([]);
    }

    expect(problems).toEqual([]);
  });

  test('the drafts button on the feed opens the profile on its drafts tab', async ({ page }) => {
    await signIn(page, DEMO);
    await page.goto('/');
    await openAdminSection(page, '社区论坛');

    await page.getByRole('button', { name: /草稿箱/ }).click();

    // One view writes the tab, another reads it, so the page owns it; this is what proves that still works.
    await expect(page.getByRole('heading', { name: '个人中心', exact: true }).first()).toBeVisible();
    await expect(page.getByRole('tab', { name: /我的草稿/ })).toHaveAttribute('aria-selected', 'true');
  });
});
