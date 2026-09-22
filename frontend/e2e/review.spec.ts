import { expect, test } from '@playwright/test';
import { createTextKnowledge, removeKnowledge, removeLeftovers, uniqueTitle } from './content';
import { DEMO, SUPER_ADMIN, openAdminSection, signIn } from './session';

/**
 * The review queue. What matters here is that a reviewer can see how a file was indexed, who decided its fate
 * and why, and that approving from the list actually changes the file.
 */
test.describe('the review queue', () => {
  test.afterAll(async () => {
    await removeLeftovers(SUPER_ADMIN);
  });

  test('a waiting file shows how its text was indexed', async ({ page }) => {
    const title = uniqueTitle('review');
    const fileId = await createTextKnowledge(DEMO, title, '一段足够长的正文，用来让服务建立全文索引并进入审核队列。');
    try {
      await signIn(page, SUPER_ADMIN);
      await page.goto('/');
      await openAdminSection(page, '内容审核');

      const row = page.locator('.admin-desktop-table tr', { hasText: title });
      await expect(row).toBeVisible();
      // 全文可搜 / 仅标题检索 / 未提取到正文 / 待解析 — the reviewer should be told which.
      await expect(row).toContainText(/全文可搜|仅标题检索|未提取到正文|待解析/);
    } finally {
      await removeKnowledge(SUPER_ADMIN, fileId);
    }
  });

  test('approving from the list publishes the file', async ({ page }) => {
    const title = uniqueTitle('approve');
    const fileId = await createTextKnowledge(DEMO, title, '这份资料会在审核列表里被通过，用于验证审核动作生效。');
    try {
      await signIn(page, SUPER_ADMIN);
      await page.goto('/');
      await openAdminSection(page, '内容审核');

      const row = page.locator('.admin-desktop-table tr', { hasText: title });
      await row.getByRole('button', { name: '通过' }).click();

      await expect(row).toContainText('已通过');
    } finally {
      await removeKnowledge(SUPER_ADMIN, fileId);
    }
  });

  test('a rejection and its reason survive a reload', async ({ page }) => {
    const title = uniqueTitle('reason');
    const fileId = await createTextKnowledge(DEMO, title, '这份资料会被驳回，用于验证驳回理由被保存下来。');
    try {
      await signIn(page, SUPER_ADMIN);
      await page.goto('/');
      await openAdminSection(page, '内容审核');

      // The list has no reject button: a reviewer opens the document and decides from there.
      const row = page.locator('.admin-desktop-table tr', { hasText: title });
      await row.getByRole('button', { name: '查看内容' }).click();
      await page.locator('.el-dialog').getByRole('button', { name: '驳回' }).click();

      // The reviewer is asked why, and what they write is what gets kept.
      await page.getByRole('textbox').last().fill('与知识库主题无关');
      await page.getByRole('button', { name: '驳回' }).last().click();

      await expect(row).toContainText('已驳回');

      await page.reload();
      await openAdminSection(page, '内容审核');
      await expect(page.locator('.admin-desktop-table tr', { hasText: title }))
        .toContainText('与知识库主题无关');
    } finally {
      await removeKnowledge(SUPER_ADMIN, fileId);
    }
  });
});
