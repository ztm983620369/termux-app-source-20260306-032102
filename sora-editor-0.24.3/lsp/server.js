const net = require('net');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const fs = require('fs');
const { pathToFileURL } = require('url');

const host = process.env.LSP_HOST ?? '0.0.0.0';
const port = Number.parseInt(process.env.LSP_PORT ?? '4389', 10);

const pylspCommand = process.env.PYLSP_COMMAND ?? 'pylsp';

const pylspExtraArgs = (process.env.PYLSP_ARGS ?? '')
  .split(' ')
  .map((s) => s.trim())
  .filter(Boolean);

const configPath =
  process.env.PYLSP_CONFIG ??
  path.resolve(__dirname, 'pylsp.config.json');

const logFilePath =
  process.env.LSP_LOG_FILE ?? path.resolve(__dirname, 'server.log');

const workDir = process.env.LSP_WORKDIR ?? path.resolve(__dirname, 'workdir');
const filesDir = path.resolve(workDir, 'files');
try {
  fs.mkdirSync(filesDir, { recursive: true });
} catch {}

function log(line) {
  const timestamp = new Date().toISOString();
  const message = `[${timestamp}] ${line}`;
  console.log(message);
  try {
    fs.appendFileSync(logFilePath, `${message}\n`);
  } catch {}
}

function buildPylspArgs() {
  const raw = [...pylspExtraArgs];
  const blocked = new Set(['--tcp', '--ws', '--host', '--port', '--stdio', '--config']);
  const sanitized = [];
  for (let i = 0; i < raw.length; i++) {
    const token = raw[i];
    if (!blocked.has(token)) {
      sanitized.push(token);
      continue;
    }
    if (token === '--host' || token === '--port' || token === '--config') {
      i++;
    }
    log(`ignored pylsp arg: ${token}`);
  }
  return sanitized;
}

function dirToFileUri(dirPath) {
  let p = dirPath;
  if (!p.endsWith(path.sep)) {
    p += path.sep;
  }
  return pathToFileURL(p).href;
}

function normalizeText(text) {
  return String(text ?? '').replace(/\r\n/g, '\n');
}

function parseJsonRpcMessage(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function createMessageParser(onMessage) {
  let buffer = Buffer.alloc(0);
  return (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const headerEnd = buffer.indexOf('\r\n\r\n');
      if (headerEnd === -1) return;
      const headerText = buffer.slice(0, headerEnd).toString('utf8');
      const match = headerText.match(/Content-Length:\s*(\d+)/i);
      if (!match) {
        buffer = buffer.slice(headerEnd + 4);
        continue;
      }
      const contentLength = Number.parseInt(match[1], 10);
      const total = headerEnd + 4 + contentLength;
      if (buffer.length < total) return;
      const body = buffer.slice(headerEnd + 4, total).toString('utf8');
      buffer = buffer.slice(total);
      onMessage(body);
    }
  };
}

function writeJsonRpc(stream, message) {
  const body = JSON.stringify(message);
  const header =
    `Content-Length: ${Buffer.byteLength(body, 'utf8')}\r\n` +
    `Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n` +
    `\r\n`;
  stream.write(header);
  stream.write(body);
}

function getDocFileName(remoteUri) {
  const hash = crypto
    .createHash('sha1')
    .update(String(remoteUri ?? ''))
    .digest('hex')
    .slice(0, 10);
  const fallback = `doc-${hash}.py`;
  try {
    const u = new URL(remoteUri);
    const pathname = u.pathname || '';
    const base = pathname.split('/').filter(Boolean).pop() || '';
    const safe = base.replace(/[^\w.\-]+/g, '_');
    if (!safe) return fallback;
    return `${hash}-${safe}`;
  } catch {
    return fallback;
  }
}

function createDocumentStore() {
  const remoteToLocal = new Map();
  const remoteToLocalPath = new Map();
  const localToRemote = new Map();
  const textByRemote = new Map();

  function ensureMapping(remoteUri) {
    if (remoteToLocal.has(remoteUri)) {
      return {
        remoteUri,
        localUri: remoteToLocal.get(remoteUri),
        localPath: remoteToLocalPath.get(remoteUri),
      };
    }
    const name = getDocFileName(remoteUri);
    const localPath = path.resolve(filesDir, name);
    const localUri = pathToFileURL(localPath).href;
    remoteToLocal.set(remoteUri, localUri);
    remoteToLocalPath.set(remoteUri, localPath);
    localToRemote.set(localUri, remoteUri);
    return { remoteUri, localUri, localPath };
  }

  function getRemoteByLocal(localUri) {
    return localToRemote.get(localUri);
  }

  function getLocalByRemote(remoteUri) {
    return remoteToLocal.get(remoteUri);
  }

  function getText(remoteUri) {
    return textByRemote.get(remoteUri) ?? '';
  }

  function setText(remoteUri, text) {
    textByRemote.set(remoteUri, normalizeText(text));
  }

  function writeToDisk(remoteUri) {
    const mapping = ensureMapping(remoteUri);
    const text = getText(remoteUri);
    try {
      fs.writeFileSync(mapping.localPath, text, 'utf8');
    } catch (e) {
      log(`write file failed: ${mapping.localPath} (${e?.message ?? e})`);
    }
  }

  function offsetAt(text, position) {
    const targetLine = Number(position?.line ?? 0);
    const targetChar = Number(position?.character ?? 0);
    let line = 0;
    let idx = 0;
    while (line < targetLine) {
      const nl = text.indexOf('\n', idx);
      if (nl === -1) {
        idx = text.length;
        break;
      }
      idx = nl + 1;
      line++;
    }
    return Math.min(idx + targetChar, text.length);
  }

  function applyChange(text, change) {
    if (!change || typeof change.text !== 'string') return text;
    if (!change.range) {
      return normalizeText(change.text);
    }
    const start = offsetAt(text, change.range.start);
    const end = offsetAt(text, change.range.end);
    const before = text.slice(0, start);
    const after = text.slice(end);
    return before + normalizeText(change.text) + after;
  }

  function applyChanges(remoteUri, changes) {
    let text = getText(remoteUri);
    const arr = Array.isArray(changes) ? changes : [];
    for (const ch of arr) {
      text = applyChange(text, ch);
    }
    setText(remoteUri, text);
    writeToDisk(remoteUri);
  }

  return {
    ensureMapping,
    getRemoteByLocal,
    getLocalByRemote,
    setText,
    getText,
    writeToDisk,
    applyChanges,
  };
}

function replaceUrisDeep(value, replaceFn) {
  if (!value) return value;
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      value[i] = replaceUrisDeep(value[i], replaceFn);
    }
    return value;
  }
  if (typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) {
      if (k === 'uri' && typeof v === 'string') {
        value[k] = replaceFn(v);
      } else {
        value[k] = replaceUrisDeep(v, replaceFn);
      }
    }
    return value;
  }
  return value;
}

const server = net.createServer((socket) => {
  socket.setNoDelay(true);
  const remote = `${socket.remoteAddress ?? ''}:${socket.remotePort ?? ''}`;
  log(`client connected: ${remote}`);

  const store = createDocumentStore();
  const localRootUri = dirToFileUri(workDir);

  const pylsp = spawn(pylspCommand, buildPylspArgs(), {
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  socket.on('error', (e) => {
    log(`socket error (${remote}): ${e?.message ?? e}`);
    pylsp.kill();
  });

  pylsp.on('error', (e) => {
    log(`pylsp spawn error (${remote}): ${e?.message ?? e}`);
    socket.destroy();
  });

  socket.on('close', () => {
    log(`client disconnected: ${remote}`);
    pylsp.kill();
  });

  pylsp.on('exit', (code, signal) => {
    log(`pylsp exit (${remote}): code=${code ?? 'null'} signal=${signal ?? 'null'}`);
    socket.destroy();
  });

  const fromClient = createMessageParser((body) => {
    const msg = parseJsonRpcMessage(body);
    if (!msg) return;

    if (msg.method === 'initialize' && msg.params && typeof msg.params === 'object') {
      msg.params.rootUri = localRootUri;
      msg.params.workspaceFolders = [
        {
          uri: localRootUri,
          name: 'sora-python',
        },
      ];
    }

    const method = msg.method;
    const params = msg.params;
    if (method === 'textDocument/didOpen') {
      const uri = params?.textDocument?.uri;
      if (typeof uri === 'string') {
        store.ensureMapping(uri);
        store.setText(uri, params?.textDocument?.text ?? '');
        store.writeToDisk(uri);
      }
    }
    if (method === 'textDocument/didChange') {
      const uri = params?.textDocument?.uri;
      if (typeof uri === 'string') {
        store.ensureMapping(uri);
        store.applyChanges(uri, params?.contentChanges);
      }
    }
    if (method === 'textDocument/didSave') {
      const uri = params?.textDocument?.uri;
      if (typeof uri === 'string' && typeof params?.text === 'string') {
        store.ensureMapping(uri);
        store.setText(uri, params.text);
        store.writeToDisk(uri);
      }
    }

    replaceUrisDeep(msg, (uri) => {
      const mapped = store.getLocalByRemote(uri);
      if (mapped) return mapped;
      if (uri.startsWith('file:///storage/') || uri.startsWith('file:///sdcard/')) {
        return store.ensureMapping(uri).localUri;
      }
      return uri;
    });

    writeJsonRpc(pylsp.stdin, msg);
  });

  const fromServer = createMessageParser((body) => {
    const msg = parseJsonRpcMessage(body);
    if (!msg) return;

    replaceUrisDeep(msg, (uri) => {
      const mapped = store.getRemoteByLocal(uri);
      return mapped ?? uri;
    });

    writeJsonRpc(socket, msg);
  });

  socket.on('data', fromClient);
  pylsp.stdout.on('data', fromServer);

  pylsp.stderr.on('data', (buf) => {
    const text = buf.toString('utf8').trimEnd();
    if (text) {
      log(`pylsp stderr (${remote}): ${text}`);
    }
  });
});

server.on('error', (e) => {
  log(`server error: ${e?.message ?? e}`);
});

server.listen(port, host, () => {
  log(`listening: ${host}:${port}`);
  log(`pylsp command: ${pylspCommand}`);
  log(`pylsp args: ${JSON.stringify(buildPylspArgs())}`);
  log(`pylsp config (ignored): ${configPath}`);
  log(`log file: ${logFilePath}`);
  log(`workdir: ${workDir}`);
});
