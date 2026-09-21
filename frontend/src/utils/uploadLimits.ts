/** How large an uploaded knowledge file may be; PDFs have their own, larger ceiling. */

export type UploadLimits = { max_upload_mb:number; pdf_max_upload_mb:number };

export function fileExtension(name:string):string {
  const match = /\.([^.]+)$/.exec(name || '');
  return match ? match[1].toLowerCase() : '';
}

export function uploadLimitMb(name:string, limits:UploadLimits):number {
  const general = Math.max(1, limits.max_upload_mb || 25);
  if (fileExtension(name) !== 'pdf') return general;
  // A lower general limit never applies to PDFs, and a misconfigured PDF limit never drops below it.
  return Math.max(general, limits.pdf_max_upload_mb || general);
}

/** The Chinese message to show when a file is too large, or null when it fits. */
export function uploadSizeProblem(name:string, size:number, limits:UploadLimits):string|null {
  const limit = uploadLimitMb(name, limits);
  if (!size || size <= limit * 1024 * 1024) return null;
  return fileExtension(name) === 'pdf' ? `PDF 不能超过 ${limit} MB` : `文件不能超过 ${limit} MB`;
}
