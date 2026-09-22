import { api, mintToken, type Member } from './session';

/**
 * The content a test needs, created through the API and removed afterwards.
 *
 * Tests that depend on whatever happens to be in the database pass or fail for reasons that have nothing to do
 * with the change under test. Everything here is named with a marker so that a leftover is recognisable, and
 * removed in teardown either way.
 */

export const MARKER = 'e2e';

export function uniqueTitle(what: string): string {
  return `${MARKER}-${what}-${Date.now().toString(36)}`;
}

/** A small, valid, multi-page PDF, so the reader has something real to page through. */
export function samplePdf(pages = 6): Buffer {
  const objects: string[] = [];
  const kids = Array.from({ length: pages }, (_, index) => `${3 + index} 0 R`).join(' ');
  objects.push('<< /Type /Catalog /Pages 2 0 R >>');
  objects.push(`<< /Type /Pages /Kids [${kids}] /Count ${pages} >>`);
  for (let index = 0; index < pages; index++) {
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${3 + pages + index} 0 R `
      + `/Resources << /Font << /F1 ${3 + 2 * pages} 0 R >> >> >>`);
  }
  for (let index = 0; index < pages; index++) {
    const stream = `BT /F1 36 Tf 72 700 Td (Page ${index + 1}) Tj ET`;
    objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
  }
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');

  let out = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((body, index) => {
    offsets.push(out.length);
    out += `${index + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) out += `${String(offset).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

/** Uploads a PDF as the given member and returns its id, so a test can read it and then clean up. */
export async function uploadPdf(member: Member, title: string): Promise<number> {
  const token = mintToken(member);
  const baseUrl = process.env.E2E_BASE_URL || 'http://127.0.0.1:8088';
  const form = new FormData();
  form.append('file', new Blob([samplePdf()], { type: 'application/pdf' }), `${title}.pdf`);
  form.append('title', title);

  const response = await fetch(`${baseUrl}/api/knowledge/file/upload`, {
    method: 'POST',
    headers: { 'X-Requested-With': 'XMLHttpRequest', Authorization: `Bearer ${token}` },
    body: form,
  });
  const answer = await response.json();
  if (answer.code !== 0) throw new Error(`upload failed: ${JSON.stringify(answer)}`);
  return answer.data.id as number;
}

/** Text knowledge, for the cases where the reader is not the subject. */
export async function createTextKnowledge(member: Member, title: string, content: string): Promise<number> {
  const { data } = await api(mintToken(member), 'POST', '/knowledge/upload',
    { title, fileType: 'txt', content });
  if (data.code !== 0) throw new Error(`create failed: ${JSON.stringify(data)}`);
  return data.data.id as number;
}

export async function approve(admin: Member, fileId: number): Promise<void> {
  const { data } = await api(mintToken(admin), 'POST', '/knowledge/admin/audit',
    { fileId, auditStatus: 'APPROVED' });
  if (data.code !== 0) throw new Error(`approve failed: ${JSON.stringify(data)}`);
}

export async function removeKnowledge(admin: Member, fileId: number): Promise<void> {
  await api(mintToken(admin), 'DELETE', '/knowledge/file', { fileId });
}

/** A last sweep, so a failed test cannot leave its content behind for the next run to trip over. */
export async function removeLeftovers(admin: Member): Promise<number> {
  const token = mintToken(admin);
  const { data } = await api(token, 'GET', '/knowledge/admin/files/page?limit=100');
  const items: { id: number; title: string }[] = data?.data?.items ?? [];
  const mine = items.filter(item => String(item.title).includes(MARKER));
  for (const item of mine) await api(token, 'DELETE', '/knowledge/file', { fileId: item.id });
  return mine.length;
}
