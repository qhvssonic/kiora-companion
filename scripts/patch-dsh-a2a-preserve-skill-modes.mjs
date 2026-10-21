import { copyFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const packageDir = resolve(process.argv[2] ?? '');
const manifest = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
if (manifest.name !== '@ryubyte/dsh-a2a' || manifest.version !== '0.3.1') {
  throw new Error('This patch is reviewed for @ryubyte/dsh-a2a 0.3.1 only');
}
const target = join(packageDir, 'lib', 'index.js');
const source = readFileSync(target, 'utf8');
const oldCode = 'if (patch.skills !== void 0) {\n\t\t\tthis.options.skills = patch.skills;\n\t\t\tthis.card.skills = patch.skills;\n\t\t}';
const newCode = 'if (patch.skills !== void 0) {\n\t\t\tconst previous = new Map((this.card.skills ?? []).map((skill) => [skill.id, skill]));\n\t\t\tconst skills = patch.skills.map((skill) => {\n\t\t\t\tconst current = previous.get(skill.id);\n\t\t\t\treturn current ? { ...current, ...skill } : skill;\n\t\t\t});\n\t\t\tthis.options.skills = skills;\n\t\t\tthis.card.skills = skills;\n\t\t}';
const oldSave = 'if (patch.skills !== void 0) s.skills = patch.skills;';
const newSave = 'if (patch.skills !== void 0) s.skills = server.card.skills;';
if (source.includes(newCode) && source.includes(newSave)) {
  process.stdout.write('DSH A2A skill-mode preservation is already installed.\n');
  process.exit(0);
}
const normalized = source.replace(/\r\n/g, '\n');
for (const anchor of [oldCode, oldSave]) {
  const first = normalized.indexOf(anchor);
  if (first < 0 || normalized.indexOf(anchor, first + anchor.length) >= 0) {
    throw new Error(`Expected exactly one 0.3.1 anchor: ${anchor.slice(0, 40)}`);
  }
}
const backup = target + '.kiora-before-skill-modes-0.3.1';
if (!existsSync(backup)) copyFileSync(target, backup);
const updated = normalized.replace(oldCode, newCode).replace(oldSave, newSave);
writeFileSync(target, source.includes('\r\n') ? updated.replace(/\n/g, '\r\n') : updated, 'utf8');
process.stdout.write(`Preserved A2A skill metadata during DSH UI edits in ${target}\n`);
