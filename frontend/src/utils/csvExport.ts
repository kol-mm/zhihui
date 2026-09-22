/** Writing a table out as CSV: a spreadsheet has to read every cell back as text, never as a formula. */

export function csvValue(value:unknown){let text=String(value??'');if(/^[=+\-@]/.test(text))text=`'${text}`;return `"${text.replace(/"/g,'""')}"`;}

/** Today where the reader is, for naming a downloaded file. */
export function localDateStamp(){const now=new Date();return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;}
