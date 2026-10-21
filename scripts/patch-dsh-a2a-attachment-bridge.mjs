import { copyFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageDir = resolve(process.argv[2] ?? '');
const manifest = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
if (manifest.name !== '@ryubyte/dsh-a2a' || manifest.version !== '0.3.1') {
  throw new Error('This patch is reviewed for @ryubyte/dsh-a2a 0.3.1 only; port it after an upgrade');
}
const target = join(packageDir, 'lib', 'index.js');
const current = readFileSync(target, 'utf8');
if (current.includes('function stageKioraInboundFiles(')) {
  process.stdout.write('DSH A2A attachment bridge is already installed.\n');
  process.exit(0);
}
const originalBackup = target + '.kiora-original-0.3.1';
const baseline = existsSync(originalBackup) ? readFileSync(originalBackup, 'utf8') : current;
if (baseline.includes('function saveKioraInboundFiles(') || baseline.includes('function stageKioraInboundFiles(')) {
  throw new Error('Original 0.3.1 baseline is unavailable; refusing to layer patches');
}
const snippetPath = join(dirname(fileURLToPath(import.meta.url)), 'dsh-a2a-attachment-bridge.snippet.js');
const snippet = readFileSync(snippetPath, 'utf8').trimEnd();
const newline = baseline.includes('\r\n') ? '\r\n' : '\n';
let output = baseline.replace(/\r\n/g, '\n');
function replaceOne(before, after) {
  const first = output.indexOf(before);
  if (first < 0 || output.indexOf(before, first + before.length) >= 0) {
    throw new Error(`Expected one 0.3.1 anchor: ${before.slice(0, 80)}`);
  }
  output = output.replace(before, after);
}
replaceOne('import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";',
  'import { existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, writeFileSync } from "node:fs";\nimport { createHash } from "node:crypto";');
replaceOne('function partsToPrompt(parts) {', `${snippet}\nfunction partsToPrompt(parts) {`);
replaceOne('const executor = (async ({ message, contextId, signal }) => {\n\t\tconst prompt = partsToPrompt(message.parts);',
  'const executor = (async ({ message, contextId, signal }) => {\n\t\tconst staged = stageKioraInboundFiles(message.parts, opts.cwd, signal);\n\t\tconst prompt = kioraPromptWithAttachments(partsToPrompt(message.parts), staged);');
const previousBackup = target + '.kiora-file-drop-0.3.1';
if (!existsSync(originalBackup)) copyFileSync(target, originalBackup);
if (current.includes('function saveKioraInboundFiles(') && !existsSync(previousBackup)) {
  copyFileSync(target, previousBackup);
}
writeFileSync(target, output.replace(/\n/g, newline), 'utf8');
process.stdout.write(`Installed generic A2A attachment bridge in ${target}\nOriginal 0.3.1 backup: ${originalBackup}\n`);
