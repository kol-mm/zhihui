import { expect, test } from '@playwright/test';
import { approve, removeKnowledge, removeLeftovers, uniqueTitle, uploadPdf } from './content';
import { ADMIN, DEMO, SUPER_ADMIN, signIn } from './session';

/**
 * Reading a document. The reader scrolls continuously and fetches the pages it is about to draw, so the thing
 * worth pinning is that a document opens without waiting for all of it, and that paging follows the scroll.
 */
test.describe('reading a PDF', () => {
  let fileId: number;
  let title: string;

  test.beforeAll(async () => {
    title = uniqueTitle('reader');
    fileId = await uploadPdf(DEMO, title);
    await approve(SUPER_ADMIN, fileId);
  });

  test.afterAll(async () => {
    if (fileId) await removeKnowledge(SUPER_ADMIN, fileId);
    await removeLeftovers(SUPER_ADMIN);
  });

  test('a document opens and draws its first page', async ({ page }) => {
    await signIn(page, DEMO);
    await page.goto('/');

    await page.getByText(title).first().click();

    const pages = page.locator('.pdf-page');
    await expect(pages.first()).toBeVisible();
    // The first page is drawn; the rest arrive as they come into view rather than up front.
    await expect(page.locator('.pdf-page canvas').first()).toBeVisible();
    await expect(page.locator('.pdf-page-controls input')).toHaveValue('1');
  });

  test('scrolling moves through the document', async ({ page }) => {
    await signIn(page, DEMO);
    await page.goto('/');
    await page.getByText(title).first().click();
    await expect(page.locator('.pdf-page canvas').first()).toBeVisible();

    await page.locator('.pdf-stage').evaluate(stage => {
      stage.scrollTo({ top: stage.scrollHeight, behavior: 'auto' });
    });

    // The page number follows the scroll rather than only the paging buttons.
    await expect(page.locator('.pdf-page-controls input')).not.toHaveValue('1');
  });

  test('a reader without permission cannot read an unapproved document', async ({ page }) => {
    const pendingTitle = uniqueTitle('pending');
    const pendingId = await uploadPdf(DEMO, pendingTitle);
    try {
      await signIn(page, ADMIN);
      await page.goto('/');
      // It is not published, so it must not appear in the knowledge list a member browses.
      await expect(page.getByText(pendingTitle)).toHaveCount(0);
    } finally {
      await removeKnowledge(SUPER_ADMIN, pendingId);
    }
  });
});
