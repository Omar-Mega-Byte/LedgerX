import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');
const composeFile = resolve(root, 'compose.e2e.yaml');
const project = `ledgerx-e2e-${process.pid}-${randomBytes(3).toString('hex')}`;
const secretDirectory = resolve(root, 'target', 'e2e-secrets', project);
const docker = process.platform === 'win32' ? 'docker.exe' : 'docker';

async function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') return reject(new Error('Could not reserve a TCP port'));
      server.close(() => resolvePort(address.port));
    });
  });
}

function run(command, args, env, { input, quiet = false } = {}) {
  return new Promise((done, reject) => {
    const child = spawn(command, args, {
      cwd: root,
      env,
      stdio: input === undefined ? (quiet ? 'ignore' : 'inherit') : ['pipe', 'inherit', 'inherit'],
    });
    child.once('error', reject);
    if (input !== undefined) child.stdin.end(input);
    child.once('exit', code => code === 0 ? done() : reject(new Error(`${command} exited with ${code}`)));
  });
}

const webPort = await freePort();
let idpPort = await freePort();
while (idpPort === webPort) idpPort = await freePort();
const env = {
  ...process.env,
  E2E_DB_USERNAME: `e2e_ledgerx_${randomBytes(4).toString('hex')}`,
  E2E_DB_PASSWORD: randomBytes(24).toString('hex'),
  E2E_KEYCLOAK_DB_USERNAME: `e2e_keycloak_${randomBytes(4).toString('hex')}`,
  E2E_KEYCLOAK_DB_PASSWORD: randomBytes(24).toString('hex'),
  E2E_KEYCLOAK_ADMIN_USERNAME: `e2e-admin-${randomBytes(4).toString('hex')}`,
  E2E_KEYCLOAK_ADMIN_PASSWORD: randomBytes(24).toString('hex'),
  E2E_WEB_PORT: String(webPort),
  E2E_IDP_PORT: String(idpPort),
  E2E_WEB_ORIGIN: `https://localhost:${webPort}`,
  E2E_IDP_ORIGIN: `https://localhost:${idpPort}`,
  E2E_COMPOSE_PROJECT: project,
  E2E_POSTGRES_ENV_FILE: resolve(secretDirectory, 'postgres.env'),
  E2E_KEYCLOAK_POSTGRES_ENV_FILE: resolve(secretDirectory, 'keycloak-postgres.env'),
  E2E_KEYCLOAK_ENV_FILE: resolve(secretDirectory, 'keycloak.env'),
  E2E_LEDGERX_ENV_FILE: resolve(secretDirectory, 'ledgerx.env'),
};
const compose = ['compose', '-p', project, '-f', composeFile];
let started = false;
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => { interrupted = true; });
}

try {
  await mkdir(secretDirectory, { recursive: true });
  await Promise.all([
    writeFile(env.E2E_POSTGRES_ENV_FILE, `POSTGRES_PASSWORD=${env.E2E_DB_PASSWORD}\n`, { mode: 0o600 }),
    writeFile(env.E2E_KEYCLOAK_POSTGRES_ENV_FILE, `POSTGRES_PASSWORD=${env.E2E_KEYCLOAK_DB_PASSWORD}\n`, { mode: 0o600 }),
    writeFile(env.E2E_KEYCLOAK_ENV_FILE, `KC_BOOTSTRAP_ADMIN_PASSWORD=${env.E2E_KEYCLOAK_ADMIN_PASSWORD}\nKC_DB_PASSWORD=${env.E2E_KEYCLOAK_DB_PASSWORD}\n`, { mode: 0o600 }),
    writeFile(env.E2E_LEDGERX_ENV_FILE, `LEDGERX_DB_PASSWORD=${env.E2E_DB_PASSWORD}\n`, { mode: 0o600 }),
  ]);
  console.log('Packaging the current LedgerX application');
  if (process.platform === 'win32') {
    await run('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd', '-DskipTests', 'package'], env);
  } else {
    await run('bash', ['./mvnw', '--batch-mode', '-DskipTests', 'package'], env);
  }
  console.log(`Starting isolated Phase 10 stack (${project})`);
  started = true;
  await run(docker, [...compose, 'up', '-d', '--wait', '--wait-timeout', '300'], env);
  if (interrupted) throw new Error('Interrupted');
  await run(process.execPath, [resolve(root, 'node_modules/@playwright/test/cli.js'), 'test', '--config', 'playwright.config.mjs'], env);
} catch (error) {
  console.error(error);
  if (started) {
    try { await run(docker, [...compose, 'logs', '--tail', '20', 'ledgerx', 'keycloak', 'caddy'], env); }
    catch (logsError) { console.error('Could not read stack logs:', logsError); }
  }
  process.exitCode = 1;
} finally {
  if (started && process.env.E2E_KEEP_STACK !== '1') {
    try { await run(docker, [...compose, 'down', '--volumes', '--remove-orphans'], env); }
    catch (error) { console.error('Could not clean up isolated stack:', error); process.exitCode = 1; }
  } else if (started) {
    console.log(`Kept ${project} at ${env.E2E_WEB_ORIGIN} (Keycloak ${env.E2E_IDP_ORIGIN}).`);
  }
  if (process.env.E2E_KEEP_STACK !== '1') await rm(secretDirectory, { recursive: true, force: true });
}
