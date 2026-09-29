/**
 * The AI's suggested category, in words for a reviewer. Said only when it differs from the file's own category:
 * when the uploader chose none, the suggestion was applied and there is nothing left to point out.
 */
export function suggestedCategoryLabel(
  file: { categoryId?: number | null; suggestedCategoryId?: number | null },
  names: Map<number, string>,
): string {
  const suggested = file.suggestedCategoryId;
  if (!suggested || suggested === file.categoryId) return '';
  return `AI 建议分类：${names.get(suggested) ?? `#${suggested}`}`;
}
