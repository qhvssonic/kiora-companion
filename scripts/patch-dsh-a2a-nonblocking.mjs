import { copyFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const packageDir = resolve(process.argv[2] ?? '');
const manifest = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
if (manifest.name !== '@ryubyte/dsh-a2a' || manifest.version !== '0.3.1') {
  throw new Error('This patch is reviewed for @ryubyte/dsh-a2a 0.3.1 only');
}
const target = join(packageDir, 'lib', 'index.js');
const source = readFileSync(target, 'utf8');
if (!source.includes('function stageKioraInboundFiles(')) {
  throw new Error('Install the Kiora attachment bridge before this patch');
}
const oldCode = 'this.runTask(task.id, task.contextId, msg);\n\t\t\t\tawait this.waitTerminal(task.id);\n\t\t\t\treturn { task: this.store.get(task.id) };';
const newCode = 'this.runTask(task.id, task.contextId, msg);\n\t\t\t\tif (params.configuration?.blocking !== false) await this.waitTerminal(task.id);\n\t\t\t\treturn { task: this.store.get(task.id) };';
if (source.includes(newCode)) {
  process.stdout.write('DSH A2A nonblocking SendMessage is already installed.\n');
  process.exit(0);
}
const normalized = source.replace(/\r\n/g, '\n');
const first = normalized.indexOf(oldCode);
if (first < 0 || normalized.indexOf(oldCode, first + oldCode.length) >= 0) {
  throw new Error('Expected exactly one 0.3.1 SendMessage anchor');
}
const backup = target + '.kiora-bridge-before-nonblocking-0.3.1';
if (!existsSync(backup)) copyFileSync(target, backup);
const updated = normalized.replace(oldCode, newCode);
writeFileSync(target, source.includes('\r\n') ? updated.replace(/\n/g, '\r\n') : updated, 'utf8');
process.stdout.write(`Enabled nonblocking A2A SendMessage in ${target}\n`);
