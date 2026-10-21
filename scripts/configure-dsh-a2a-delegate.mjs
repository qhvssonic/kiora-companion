import { copyFileSync, existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { randomBytes, randomUUID } from 'node:crypto';
import { resolve } from 'node:path';

const configPath = resolve(process.argv[2] ?? '');
const current = readFileSync(configPath, 'utf8');
const config = JSON.parse(current);
const server = config.server ?? {};
const skills = Array.isArray(server.skills) ? server.skills.filter((skill) => skill?.id !== 'file_drop') : [];
const delegate = {
  id: 'delegate_task',
  name: 'DSH 通用任务',
  description: '由 DeepSeek Harness 使用已配置的工具处理用户任务；可接收附件并根据指令读取、分析或保存。附件字节不进入模型提示词。',
  tags: ['agent', 'files', 'analysis', 'automation'],
  examples: ['分析附件并给出摘要', '将附件复制到指定目录并返回路径'],
  inputModes: ['text/plain', '*/*'],
};
const existing = skills.findIndex((skill) => skill?.id === delegate.id);
if (existing >= 0) skills[existing] = { ...skills[existing], ...delegate };
else skills.push(delegate);
config.server = {
  ...server,
  enabled: true,
  agentName: 'DSH Agent',
  agentDescription: '使用 DeepSeek Harness 的已配置工具处理 A2A 任务和附件',
  agentVersion: '0.2.0',
  endpointPath: server.endpointPath || '/a2a',
  authToken: server.authToken || randomBytes(32).toString('hex'),
  skills,
};
const backup = configPath + '.kiora-file-drop-0.3.1';
if (!existsSync(backup)) copyFileSync(configPath, backup);
const temporary = configPath + '.kiora-writing-' + randomUUID();
writeFileSync(temporary, JSON.stringify(config, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' });
renameSync(temporary, configPath);
process.stdout.write(`Configured DSH delegate_task with file input and Bearer authentication in ${configPath}\nPrevious file-drop config backup: ${backup}\n`);
