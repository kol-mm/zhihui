import { describe, expect, it } from 'vitest';
import { fileExtension, uploadLimitMb, uploadSizeProblem } from './uploadLimits';

const limits = { max_upload_mb: 25, pdf_max_upload_mb: 200 };
const mb = (value:number) => value * 1024 * 1024;

describe('upload limits', () => {
  it('reads the extension, whatever the case', () => {
    expect(fileExtension('handbook.PDF')).toBe('pdf');
    expect(fileExtension('notes.md')).toBe('md');
    expect(fileExtension('no-extension')).toBe('');
  });

  it('gives PDFs the larger ceiling', () => {
    expect(uploadLimitMb('handbook.pdf', limits)).toBe(200);
    expect(uploadLimitMb('notes.md', limits)).toBe(25);
    expect(uploadLimitMb('report.docx', limits)).toBe(25);
  });

  it('never lets a PDF end up with less room than other files', () => {
    expect(uploadLimitMb('handbook.pdf', { max_upload_mb: 50, pdf_max_upload_mb: 10 })).toBe(50);
    expect(uploadLimitMb('notes.md', { max_upload_mb: 0, pdf_max_upload_mb: 0 })).toBe(25);
  });

  it('only complains about files that are too large, and says which limit', () => {
    expect(uploadSizeProblem('handbook.pdf', mb(199), limits)).toBeNull();
    expect(uploadSizeProblem('handbook.pdf', mb(201), limits)).toBe('PDF 不能超过 200 MB');
    expect(uploadSizeProblem('notes.md', mb(24), limits)).toBeNull();
    expect(uploadSizeProblem('notes.md', mb(26), limits)).toBe('文件不能超过 25 MB');
    expect(uploadSizeProblem('notes.md', 0, limits)).toBeNull();
  });
});
