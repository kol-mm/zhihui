import { describe, expect, it } from 'vitest';
import { csvValue, localDateStamp } from './csvExport';

describe('writing a cell out as CSV', () => {
  it('quotes every value, so a comma or a line break cannot split the row', () => {
    expect(csvValue('王小明')).toBe('"王小明"');
    expect(csvValue('北京市, 海淀区')).toBe('"北京市, 海淀区"');
    expect(csvValue('第一行\n第二行')).toBe('"第一行\n第二行"');
  });

  it('doubles a quote, the escape a spreadsheet understands', () => {
    expect(csvValue('他说“好”的 "quoted" 部分')).toBe('"他说“好”的 ""quoted"" 部分"');
  });

  it('defuses a value a spreadsheet would run as a formula', () => {
    // A nickname is free text. Without the leading apostrophe these open a program on the reader's machine.
    expect(csvValue('=1+1')).toBe(`"'=1+1"`);
    expect(csvValue('+1 (555) 0100')).toBe(`"'+1 (555) 0100"`);
    expect(csvValue('-5')).toBe(`"'-5"`);
    expect(csvValue('@handle')).toBe(`"'@handle"`);
    expect(csvValue('=HYPERLINK("http://example.test","click")'))
      .toBe(`"'=HYPERLINK(""http://example.test"",""click"")"`);
  });

  it('leaves a formula character alone when it is not the first one', () => {
    expect(csvValue('a=b')).toBe('"a=b"');
    expect(csvValue('1+1')).toBe('"1+1"');
  });

  it('writes nothing for a missing value rather than the word undefined', () => {
    expect(csvValue(undefined)).toBe('""');
    expect(csvValue(null)).toBe('""');
    expect(csvValue(0)).toBe('"0"');
    expect(csvValue(false)).toBe('"false"');
  });
});

describe('naming a downloaded file', () => {
  it('stamps the day where the reader is, zero padded', () => {
    expect(localDateStamp()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('uses the reader’s own day, not the one UTC is on', () => {
    const now = new Date();
    const expected = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    expect(localDateStamp()).toBe(expected);
  });
});
