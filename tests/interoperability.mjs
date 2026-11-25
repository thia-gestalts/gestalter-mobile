import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { createInterface } from 'node:readline';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { pathToFileURL } from 'node:url';
import { deflateSync, crc32 } from 'node:zlib';

const desktopRoot = resolve(process.env.GESTALTER_DESKTOP ?? '../gestalter-desktop');
const olymp = resolve(process.env.OLYMP_BINARY ?? '../thia-olymp/target/debug/olymp');
const origin = 'http://127.0.0.1:8787';
const credentials = { name: 'mobile.e2e', password: 'a guest learns café 🙂', device: 'Gestalter desktop host' };
const desktopNote = '87ec3044-2ae0-445a-9b0f-b903eb515024';
const disposalKeyPrefix = 'Disposable Queue note';

function png() {
  const width = 420, height = 256;
  const rows = Buffer.alloc(height * (width * 3 + 1));
  // A deterministic incompressible image covers more than one Olymp chunk.
  let x = 7133;
  for (let i = 0; i < rows.length; i++) {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    if (i % (width * 3 + 1)) rows[i] = x & 255;
  }
  const chunk = (type, bytes) => {
    const data = Buffer.concat([Buffer.from(type), bytes]);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    const checksum = Buffer.alloc(4);
    checksum.writeUInt32BE(crc32(data));
    return Buffer.concat([length, data, checksum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 2;
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}

async function ready(child) {
  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, 'line');
  lines.close();
  return line;
}

async function stop(child, signal) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const ended = once(child, 'exit');
  child.kill(signal);
  return ended;
}

test('Olymp 1.1 mobile restart, desktop projection and whole-base recovery', { timeout: 60000 }, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'gestalter-mobile-proof-'));
  const processes = new Set();
  const spawned = (cmd, args, config) => {
    const child = spawn(cmd, args, config);
    processes.add(child);
    return child;
  };
  const originalFetch = globalThis.fetch;
  let disposeDesktop;
  try {
    const runtime = join(temp, 'sdk');
    const imagePath = join(temp, 'proof.png');
    const pixels = png();
    const unknownOtherBytes = Buffer.from('opaque academic attachment alongside the PNG');
    await mkdir(runtime);
    await writeFile(imagePath, pixels);
    const unpack = spawned('tar', ['-xzf', 'vendor/thia-olymp-1.1.0.tgz', '--strip-components=2',
      '-C', runtime, 'package/web'], { stdio: 'inherit' });
    assert.equal((await once(unpack, 'exit'))[0], 0);
    await writeFile(join(runtime, 'package.json'), '{"type":"module"}');
    const sdk = await import(pathToFileURL(join(runtime, 'client.js')));
    const wasm = await readFile(join(runtime, 'opaque/opaque_bg.wasm'));
    const data = join(temp, 'olymp');
    let server, fetchOlymp;
    async function startService() {
      // The real Olymp router serves HTTP/1.1 over an inherited Unix stream.
      server = spawned('sh', ['-c', 'exec "$1" --connected-stdin <&3', 'olymp', olymp],
        { stdio: ['ignore', 'pipe', 'inherit', 'pipe'], env: { ...process.env, OLYMP_DATA: data } });
      assert.equal(await ready(server), 'Olymp v1 ready');
      const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
      agent.createConnection = () => server.stdio[3];
      fetchOlymp = (url, options = {}) => new Promise((accept, reject) => {
        const req = http.request(new URL(url, origin), { agent, method: options.method, headers: options.headers },
          response => {
            const bytes = [];
            response.on('data', chunk => bytes.push(chunk));
            response.on('error', reject);
            response.on('end', () => accept(new Response(response.statusCode === 204 ? null : Buffer.concat(bytes),
              { status: response.statusCode })));
          });
        req.on('error', reject);
        req.end(options.body);
      });
    }
    await startService();
    globalThis.fetch = (url, options) => fetchOlymp(url, options);

    async function mobile(stage, interceptor = () => false) {
      const child = spawned('java', ['-Djava.library.path=native/target/debug',
        '-cp', 'build/host:vendor/gson-2.11.0.jar', 'thia.gestalter.core.MobilePeer',
        stage, join(temp, 'mobile'), imagePath], { stdio: ['pipe', 'pipe', 'inherit'] });
      const lines = createInterface({ input: child.stdout });
      const exited = once(child, 'exit');
      let result;
      for await (const line of lines) {
        const event = JSON.parse(line);
        if ('result' in event) {
          result = event.result;
          if (stage === 'offline') child.kill('SIGKILL');
          continue;
        }
        const { origin, path, method, headers, body } = event.request;
        if (interceptor(event.request)) {
          child.stdin.write('{"status":0}\n');
          continue;
        }
        const response = await fetchOlymp(origin + '/v1/' + path, {
          method, headers, body: body === null ? undefined : Buffer.from(body, 'base64'),
        });
        child.stdin.write(JSON.stringify({ status: response.status,
          body: Buffer.from(await response.arrayBuffer()).toString('base64') }) + '\n');
      }
      const [code, signal] = await exited;
      if (stage === 'offline') assert.equal(signal, 'SIGKILL');
      else assert.equal(code, 0, 'Mobile peer exited during ' + stage);
      assert.ok(result, 'Mobile peer did not complete ' + stage);
      return result;
    }

    const session = async () => sdk.signIn(origin, { ...credentials, wasm });
    assert.match(await mobile('enroll'), /Fully synced/);
    const desktopSession = await session();

    // Use the pinned SDK for desktop-origin writes, including a specialist record
    // that the desktop replica can carry but the mobile editor never projects.
    function peer(active) {
      let state;
      let tail = Promise.resolve();
      const chunks = new Map();
      const store = {
        scope: JSON.stringify([origin, active.account.id]),
        state: async () => structuredClone(state),
        save: async data => { state = structuredClone(data); },
        chunk: async id => chunks.get(id)?.slice(),
        saveChunk: async (id, data) => chunks.set(id, data.slice()),
        lock: work => {
          const next = tail.then(work);
          tail = next.catch(() => {});
          return next;
        },
      };
      return new sdk.Replica(active, store);
    }
    const authoring = peer(desktopSession);
    await authoring.sync();
    const academic = {
      kind: 'desktop-academic-appendix', id: 'citation/β',
      citation: { authors: ['Ada Chen'], locators: ['§3', null], year: 2026, restricted: false },
      attachments: ['scan', 'transcription'], extension: '\ud800\u2028\u0000',
    };
    const academicKey = sdk.recordKey(academic);
    const academicOp = await authoring.put(academicKey, academic, [pixels, unknownOtherBytes]);
    const related = [{ kind: 'artifact', id: 'name:637572766520736b65746368626f6f6b' }];
    const desktopValue = { kind: 'note', id: desktopNote, text: 'Desktop note', related,
      tags: ['desktop-tag'], extra: { keep: [1, null] } };
    await authoring.put(sdk.recordKey(desktopValue), desktopValue);
    await authoring.sync();
    const originalUnknown = (await authoring.records()).find(h => h.id === academicOp);
    authoring.close();

    // Exercise the exact desktop projection and SQLite service. The host shim
    // replaces Electron IPC, remembered-session storage and browser fetch only.
    const backend = spawned(process.env.GESTALTER_PYTHON ?? 'python3.14',
      ['-B', join(desktopRoot, 'backend/service.py')], {
        stdio: ['pipe', 'pipe', 'inherit'], env: { ...process.env, XDG_DATA_HOME: join(temp, 'desktop') },
      });
    const jsonLines = createInterface({ input: backend.stdout });
    const responses = new Map();
    let nextId = 0;
    jsonLines.on('line', line => {
      const message = JSON.parse(line);
      if (message.ready) return;
      const reply = responses.get(message.id);
      if (!reply) return;
      responses.delete(message.id);
      if (message.error) reply.reject(new Error(message.error));
      else reply.accept(message.result);
    });
    globalThis.window = {
      gestalter: {
        serviceOrigin: async () => origin,
        call: (method, ...args) => new Promise((accept, reject) => {
          const id = ++nextId;
          responses.set(id, { accept, reject });
          backend.stdin.write(JSON.stringify({ id, method, args }) + '\n');
        }),
      },
      dispatchEvent() {},
    };
    globalThis.addEventListener = (event, callback) => { if (event === 'pagehide') disposeDesktop = callback; };
    globalThis.testDesktopSession = desktopSession;
    await writeFile(join(runtime, 'remembered.js'), 'export const remembered = {read: () => globalThis.testDesktopSession};');
    const desktopSource = (await readFile(join(desktopRoot, 'src/olymp.js'), 'utf8'))
      .replace("const runtime = '/olymp/';", "const runtime = './';")
      .replace("runtime + 'browser.js'", "runtime + 'remembered.js'");
    await writeFile(join(runtime, 'desktop.mjs'), desktopSource);
    const desktop = await import(pathToFileURL(join(runtime, 'desktop.mjs')));
    async function desktopEventually(check) {
      for (let i = 0; i < 70; i++) {
        const value = await check();
        if (value) return value;
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      assert.fail('Desktop did not project the expected replica state');
    }
    assert.equal((await desktopEventually(() => desktop.call('getNote', [desktopNote]))).text, 'Desktop note');

    const capturedKey = await mobile('offline');
    const capturedId = JSON.parse(capturedKey.substring('gestalt/'.length))[1];
    await mobile('restart');
    assert.equal(await desktop.call('getNote', [capturedId]), null);
    let uploads = 0;
    await mobile('interrupted', ({ path, method }) => path.startsWith('chunks/') && method === 'PUT' && ++uploads === 2);
    assert.equal(uploads, 2);
    let retried = 0;
    await mobile('sync', ({ path, method }) => {
      if (path.startsWith('chunks/') && method === 'PUT') retried++;
      return false;
    });
    assert.equal(retried, 1, 'Only the remaining staged chunk should upload');
    const captured = await desktopEventually(() => desktop.call('getNote', [capturedId]));
    const shown = await desktop.call('getImage', [captured.attachments[0].id]);
    assert.match(shown, /^data:image\/png;base64,/);
    assert.deepEqual(Buffer.from(shown.split(',')[1], 'base64'), pixels);
    const disposable = (await desktop.call('listNotes', [])).find(n => n.title === disposalKeyPrefix);
    assert.ok(disposable);
    await mobile('queue-crud');
    await desktopEventually(async () =>
      (await desktop.call('getNote', [desktopNote]))?.text === 'Updated from Queue');
    assert.equal(await desktop.call('getNote', [disposable.id]), null);

    disposeDesktop();
    disposeDesktop = null;
    await stop(server);
    const db = new DatabaseSync(join(data, 'olymp.db'));
    db.exec('DELETE FROM chunks; DELETE FROM bases;');
    db.close();
    await startService();
    await mobile('recover');
    const recovered = peer(await session());
    await recovered.sync();
    const heads = await recovered.records();
    const preserved = heads.find(h => h.id === academicOp);
    assert.deepEqual(preserved, originalUnknown);
    assert.deepEqual(Buffer.from(await recovered.attachment(preserved.files[0])), pixels);
    assert.deepEqual(Buffer.from(await recovered.attachment(preserved.files[1])), unknownOtherBytes);
    const edited = heads.find(h => h.key === sdk.recordKey(desktopValue));
    assert.deepEqual(edited.value, { ...desktopValue, text: 'Updated from Queue', tags: ['desktop-tag', 'no-queue'] });
    assert.equal(heads.find(h => h.key === sdk.recordKey({ kind: 'note', id: disposable.id })).deleted, true);
    recovered.close();

    await mobile('switch');
    console.log('Offline SIGKILL/reopen, staged retry, desktop image and Queue CRUD, typed identity, account isolation, and opaque academic attachment recovery passed.');
  } finally {
    disposeDesktop?.();
    globalThis.fetch = originalFetch;
    await Promise.all([...processes].map(child => stop(child)));
    await rm(temp, { recursive: true, force: true });
  }
});
