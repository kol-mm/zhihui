import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer, request as proxyRequest } from 'node:http';
import { extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const host = process.env.FRONTEND_HOST || '127.0.0.1';
const port = Number(process.env.FRONTEND_PORT || 5173);
const gateway = new URL(process.env.GATEWAY_URL || 'http://127.0.0.1:8080');
const distRoot = resolve(fileURLToPath(new URL('./dist/', import.meta.url)));
const distPrefix = `${distRoot}${sep}`;

const contentTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.jpeg', 'image/jpeg'],
  ['.jpg', 'image/jpeg'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.map', 'application/json; charset=utf-8'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml'],
  ['.webp', 'image/webp'],
  ['.woff', 'font/woff'],
  ['.woff2', 'font/woff2']
]);

function proxyApi(req, res, url) {
  const upstreamPath = `${url.pathname.slice(4) || '/'}${url.search}`;
  const headers = { ...req.headers, host: gateway.host };
  delete headers.connection;

  const upstream = proxyRequest({
    protocol: gateway.protocol,
    hostname: gateway.hostname,
    port: gateway.port,
    method: req.method,
    path: upstreamPath,
    headers
  }, (upstreamResponse) => {
    res.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
    upstreamResponse.pipe(res);
  });

  upstream.on('error', (error) => {
    if (res.headersSent) {
      res.destroy(error);
      return;
    }
    res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ code: 502, message: `Gateway unavailable: ${error.message}` }));
  });
  req.pipe(upstream);
}

function serveStatic(req, res, url) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' });
    res.end();
    return;
  }

  let pathname;
  try {
    pathname = decodeURIComponent(url.pathname);
  } catch {
    res.writeHead(400);
    res.end('Bad Request');
    return;
  }

  const relativePath = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  let filePath = resolve(distRoot, relativePath);
  if (filePath !== distRoot && !filePath.startsWith(distPrefix)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }

  if (!existsSync(filePath) || !statSync(filePath).isFile()) {
    filePath = resolve(distRoot, 'index.html');
  }

  const headers = {
    'Content-Type': contentTypes.get(extname(filePath).toLowerCase()) || 'application/octet-stream',
    'Cache-Control': filePath.includes(`${sep}assets${sep}`) ? 'public, max-age=31536000, immutable' : 'no-cache'
  };
  res.writeHead(200, headers);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  createReadStream(filePath).pipe(res);
}

if (!existsSync(resolve(distRoot, 'index.html'))) {
  throw new Error(`Frontend build is missing: ${resolve(distRoot, 'index.html')}`);
}

const server = createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || `${host}:${port}`}`);
  if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
    proxyApi(req, res, url);
    return;
  }
  serveStatic(req, res, url);
});

server.on('error', (error) => {
  console.error(`Frontend server failed: ${error.message}`);
  process.exitCode = 1;
});

server.listen(port, host, () => {
  console.log(`Frontend: http://${host}:${port}/`);
  console.log(`API proxy: /api -> ${gateway.href}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
