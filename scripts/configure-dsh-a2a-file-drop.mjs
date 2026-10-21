import { copyFileSync, existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomBytes } from 'node:crypto';

const configPath = resolve(process.argv[2] ?? '');
const original = readFileSync(configPath, 'utf8');
const config = JSON.parse(original);
const server = config.server ?? {};
const skills = Array.isArray(server.skills) ? [...server.skills] : [];
const fileDrop = {
  id: 'file_drop',
  name: '接收文件',
  description: '接收 A2A 文件附件并直接保存到 DSH 当前工作目录，返回实际保存路径；不整理文字或入库。',
  tags: ['file', 'transfer'],
};
const existingIndex = skills.findIndex((skill) => skill?.id === fileDrop.id);
if (existingIndex >= 0) skills[existingIndex] = { ...skills[existingIndex], ...fileDrop };
else skills.push(fileDrop);
config.server = {
  ...server,
  enabled: true,
  agentName: server.agentName || 'DSH 文件接收 Agent',
  agentDescription: server.agentDescription || '通过 A2A 接收文件并保存到当前工作目录',
  agentVersion: server.agentVersion || '0.1.0',
  endpointPath: server.endpointPath || '/a2a',
  authToken: server.authToken || randomBytes(32).toString('hex'),
  skills,
};
const backup = configPath + '.kiora-original';
if (!existsSync(backup)) copyFileSync(configPath, backup);
const temp = configPath + '.kiora-writing';
writeFileSync(temp, JSON.stringify(config, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' });
renameSync(temp, configPath);
process.stdout.write(`Configured authenticated file_drop in ${configPath}; original saved at ${backup}\n`);
