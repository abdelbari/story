// Minimal PDF writer: builds a multi-page PDF by embedding each page as a
// JPEG XObject (/DCTDecode), no dependencies. Good print fidelity because
// pages are rasterized at 2x by the canvas exporter.

import { renderPageToCanvas } from './exporter.js';

const PX_TO_PT = 72 / 96;

export async function exportPDF(doc, pageIndexes, scale = 2, quality = 0.92) {
  const jpegs = [];
  for (const i of pageIndexes) {
    const canvas = await renderPageToCanvas(doc, doc.pages[i], scale);
    const blob = await new Promise(res => canvas.toBlob(res, 'image/jpeg', quality));
    const bytes = new Uint8Array(await blob.arrayBuffer());
    jpegs.push({ bytes, w: canvas.width, h: canvas.height });
  }
  return buildPdf(jpegs, doc.width * PX_TO_PT, doc.height * PX_TO_PT);
}

function buildPdf(jpegs, pageWpt, pageHpt) {
  const encoder = new TextEncoder();
  const chunks = [];
  let offset = 0;
  const offsets = []; // 1-based object byte offsets

  const push = data => {
    const bytes = typeof data === 'string' ? encoder.encode(data) : data;
    chunks.push(bytes);
    offset += bytes.length;
  };
  const beginObj = num => {
    offsets[num] = offset;
    push(`${num} 0 obj\n`);
  };

  const n = jpegs.length;
  // Object layout: 1 catalog, 2 pages, then per page i (0-based):
  // page obj = 3 + i*3, content = 4 + i*3, image = 5 + i*3.
  const pageObj = i => 3 + i * 3;
  const contentObj = i => 4 + i * 3;
  const imageObj = i => 5 + i * 3;
  const totalObjs = 2 + n * 3;

  push('%PDF-1.4\n%âãÏÓ\n');

  beginObj(1);
  push('<< /Type /Catalog /Pages 2 0 R >>\nendobj\n');

  beginObj(2);
  const kids = jpegs.map((_, i) => `${pageObj(i)} 0 R`).join(' ');
  push(`<< /Type /Pages /Kids [${kids}] /Count ${n} >>\nendobj\n`);

  jpegs.forEach((jpeg, i) => {
    beginObj(pageObj(i));
    push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(pageWpt)} ${num(pageHpt)}] ` +
      `/Contents ${contentObj(i)} 0 R /Resources << /XObject << /Im${i} ${imageObj(i)} 0 R >> >> >>\nendobj\n`);

    const content = `q\n${num(pageWpt)} 0 0 ${num(pageHpt)} 0 0 cm\n/Im${i} Do\nQ\n`;
    beginObj(contentObj(i));
    push(`<< /Length ${content.length} >>\nstream\n${content}endstream\nendobj\n`);

    beginObj(imageObj(i));
    push(`<< /Type /XObject /Subtype /Image /Width ${jpeg.w} /Height ${jpeg.h} ` +
      `/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.bytes.length} >>\nstream\n`);
    push(jpeg.bytes);
    push('\nendstream\nendobj\n');
  });

  const xrefStart = offset;
  let xref = `xref\n0 ${totalObjs + 1}\n0000000000 65535 f \n`;
  for (let i = 1; i <= totalObjs; i++) {
    xref += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  }
  push(xref);
  push(`trailer\n<< /Size ${totalObjs + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`);

  const total = chunks.reduce((sum, c) => sum + c.length, 0);
  const out = new Uint8Array(total);
  let pos = 0;
  for (const c of chunks) { out.set(c, pos); pos += c.length; }
  return new Blob([out], { type: 'application/pdf' });
}

function num(v) {
  return Number(v.toFixed(2));
}
