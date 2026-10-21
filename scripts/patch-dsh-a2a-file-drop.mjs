import { copyFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const packageDir = resolve(process.argv[2] ?? '');
const manifest = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
if (manifest.name !== '@ryubyte/dsh-a2a' || manifest.version !== '0.3.1') {
  throw new Error('This patch requires @ryubyte/dsh-a2a 0.3.1');
}
const target = join(packageDir, 'lib', 'index.js');
const original = readFileSync(target, 'utf8');
if (original.includes('function saveKioraInboundFiles(')) {
  process.stdout.write('DSH A2A file receiver is already patched.\n');
  process.exit(0);
}
const newline = original.includes('\r\n') ? '\r\n' : '\n';
let output = original.replace(/\r\n/g, '\n');
function replaceOne(before, after) {
  const first = output.indexOf(before);
  if (first < 0 || output.indexOf(before, first + before.length) >= 0) {
    throw new Error(`Expected one patch anchor: ${before.slice(0, 80)}`);
  }
  output = output.replace(before, after);
}

const receiver = `// Kiora local integration: file_drop stores raw A2A parts in the inbound session cwd.
function kioraFileDropModes(skills) {
  return skills.map((skill) => skill.id === "file_drop" ? {
    ...skill,
    inputModes: ["text/plain", "*/*"]
  } : skill);
}
function saveKioraInboundFiles(parts, cwd, signal) {
  const rawParts = parts.filter((part) => typeof part.raw === "string");
  if (rawParts.length === 0) return [];
  if (rawParts.length > 10) throw new Error("At most 10 files per A2A task");
  const maximum = 10 * 1024 * 1024;
  let total = 0;
  const files = rawParts.map((part) => {
    const name = part.filename;
    if (typeof name !== "string" || name.length === 0 || name.length > 180 ||
        name === "." || name === ".." || /[<>:"/\\\\|?*\\x00-\\x1f]/.test(name) || /[. ]$/.test(name) ||
        /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\.|$)/i.test(name)) {
      throw new Error("Invalid A2A filename");
    }
    if (part.raw.length > Math.ceil(maximum / 3) * 4 + 4) throw new Error("A2A file exceeds 10 MiB");
    const bytes = Buffer.from(part.raw, "base64");
    if (bytes.length === 0 || bytes.toString("base64") !== part.raw) throw new Error("Invalid A2A base64 file");
    total += bytes.length;
    if (total > maximum) throw new Error("A2A files exceed 10 MiB in total");
    return { name, bytes };
  });
  mkdirSync(cwd, { recursive: true });
  const saved = [];
  for (const file of files) {
    if (signal?.aborted) throw new Error("A2A task canceled");
    const dot = file.name.lastIndexOf(".");
    const stem = dot > 0 ? file.name.slice(0, dot) : file.name;
    const extension = dot > 0 ? file.name.slice(dot) : "";
    let destination;
    for (let index = 0; index < 1000; index++) {
      const candidate = join(cwd, index === 0 ? file.name : \`\${stem}-\${index}\${extension}\`);
      try {
        writeFileSync(candidate, file.bytes, { flag: "wx" });
        destination = candidate;
        break;
      } catch (error) {
        if (error?.code !== "EEXIST") throw error;
      }
    }
    if (!destination) throw new Error("Too many files with the same name");
    saved.push(destination);
  }
  return saved;
}
`;

replaceOne('function partsToPrompt(parts) {', receiver + 'function partsToPrompt(parts) {');
replaceOne('this.card.skills = patch.skills;', 'this.card.skills = kioraFileDropModes(patch.skills);');
replaceOne('defaultOutputModes: this.options.defaultOutputModes ?? ["text/plain"],\n\t\t\tskills,',
  'defaultOutputModes: this.options.defaultOutputModes ?? ["text/plain"],\n\t\t\tskills: kioraFileDropModes(skills),');
replaceOne('const executor = (async ({ message, contextId, signal }) => {\n\t\tconst prompt = partsToPrompt(message.parts);',
  'const executor = (async ({ message, contextId, signal }) => {\n\t\tconst saved = saveKioraInboundFiles(message.parts, opts.cwd, signal);\n\t\tif (saved.length) return { messageId: crypto.randomUUID(), role: Role.AGENT, parts: [{ text: `Saved ${saved.length} file(s) to DSH current working directory:\\n${saved.join("\\n")}` }] };\n\t\tconst prompt = partsToPrompt(message.parts);');

const backup = target + '.kiora-original-0.3.1';
if (!existsSync(backup)) copyFileSync(target, backup);
writeFileSync(target, output.replace(/\n/g, newline), 'utf8');
process.stdout.write(`Patched ${target}\nBackup: ${backup}\n`);
