import { strict as assert } from 'node:assert';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const packageDir = resolve(process.argv[2] ?? '');
const { A2AServer } = await import(pathToFileURL(join(packageDir, 'lib', 'index.js')).href);
const server = new A2AServer({
  baseUrl: 'http://127.0.0.1:3000', agentName: 'Test', agentDescription: '',
  agentVersion: '0.1.0', skills: [{ id: 'delegate_task', name: 'Original',
    inputModes: ['text/plain', '*/*'], outputModes: ['text/plain'], tags: ['agent'] }],
});
server.updateCard({ skills: [{ id: 'delegate_task', name: 'Edited', tags: ['agent', 'files'] }] });
const skill = server.card.skills[0];
assert.equal(skill.name, 'Edited');
assert.deepEqual(skill.inputModes, ['text/plain', '*/*']);
assert.deepEqual(skill.outputModes, ['text/plain']);
assert.deepEqual(skill.tags, ['agent', 'files']);
assert.deepEqual(server.options.skills, server.card.skills);
process.stdout.write('DSH UI skill edits preserve uneditable input/output modes\n');
