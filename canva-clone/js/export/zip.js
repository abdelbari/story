// Minimal ZIP writer (STORED, no compression): local file headers + central
// directory + CRC32. Enough to bundle page PNGs into one download without
// tripping the browser's multiple-download blocker.

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(data) {
  let crc = 0xffffffff;
  for (let i = 0; i < data.length; i++) {
    crc = CRC_TABLE[(crc ^ data[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

// files: [{ name: string, data: Uint8Array }]
export function makeZip(files) {
  const encoder = new TextEncoder();
  const chunks = [];
  const central = [];
  let offset = 0;

  const u16 = v => new Uint8Array([v & 0xff, (v >> 8) & 0xff]);
  const u32 = v => new Uint8Array([v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >>> 24) & 0xff]);

  for (const file of files) {
    const nameBytes = encoder.encode(file.name);
    const crc = crc32(file.data);
    const header = [
      u32(0x04034b50), u16(20), u16(0), u16(0), u16(0), u16(0),
      u32(crc), u32(file.data.length), u32(file.data.length),
      u16(nameBytes.length), u16(0),
    ];
    const headerLen = 30 + nameBytes.length;
    chunks.push(...header, nameBytes, file.data);
    central.push({ nameBytes, crc, size: file.data.length, offset });
    offset += headerLen + file.data.length;
  }

  const centralStart = offset;
  let centralSize = 0;
  for (const entry of central) {
    const record = [
      u32(0x02014b50), u16(20), u16(20), u16(0), u16(0), u16(0), u16(0),
      u32(entry.crc), u32(entry.size), u32(entry.size),
      u16(entry.nameBytes.length), u16(0), u16(0), u16(0), u16(0),
      u32(0), u32(entry.offset),
    ];
    chunks.push(...record, entry.nameBytes);
    centralSize += 46 + entry.nameBytes.length;
  }
  chunks.push(
    u32(0x06054b50), u16(0), u16(0), u16(central.length), u16(central.length),
    u32(centralSize), u32(centralStart), u16(0),
  );

  return new Blob(chunks, { type: 'application/zip' });
}
