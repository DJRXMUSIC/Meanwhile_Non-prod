// Renders the app icon (rounded square, accent "H" glyph) to PNGs via sharp.
import sharp from 'sharp';
import { mkdir } from 'node:fs/promises';

const svg = `
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <rect x="16" y="16" width="480" height="480" rx="96" fill="#0B0F14"/>
  <rect x="16" y="16" width="480" height="480" rx="96" fill="none" stroke="#3FB68B" stroke-width="12"/>
  <text x="256" y="346" font-family="Georgia, serif" font-size="280" font-weight="bold"
        text-anchor="middle" fill="#3FB68B">H</text>
</svg>`;

const maskableSvg = `
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <rect width="512" height="512" fill="#0B0F14"/>
  <text x="256" y="336" font-family="Georgia, serif" font-size="220" font-weight="bold"
        text-anchor="middle" fill="#3FB68B">H</text>
</svg>`;

await mkdir('public', { recursive: true });
await sharp(Buffer.from(svg)).resize(192, 192).png().toFile('public/icon-192.png');
await sharp(Buffer.from(svg)).resize(512, 512).png().toFile('public/icon-512.png');
await sharp(Buffer.from(maskableSvg)).resize(512, 512).png().toFile('public/icon-maskable.png');
console.log('icons written to public/');
