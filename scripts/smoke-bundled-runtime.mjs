import { spawn } from 'node:child_process';
import { mkdir, writeFile, symlink, readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import assert from 'node:assert/strict';
// Optional browser check: npm install --prefix .build-input/browser-tests playwright
// node scripts/smoke-bundled-runtime.mjs --browser
const useBrowser = process.argv.includes('--browser');
const useIdeSettings = !process.argv.includes('--without-ide-settings');

const runtime = resolve('build/bundled-dsh-runtime/dsh-runtime');
const expectedVersion = (await readFile('build.gradle.kts', 'utf8')).match(/val bundledDshVersion = "([^"]+)"/)[1];
assert.equal((await readFile(join(runtime, 'version.txt'), 'utf8')).trim(), expectedVersion);
const home = resolve('.build-input/smoke-' + expectedVersion + '-' + Date.now());
await mkdir(join(home, 'profiles/node_modules'), { recursive: true });
await symlink(join(runtime, 'node_modules/dsh-ide-settings'), join(home, 'profiles/node_modules/dsh-ide-settings'), 'junction');
const patch = join(home, 'patch.yml');
await writeFile(patch, useIdeSettings ? "- insert:\n    - id: dsh-ide-settings\n      name: 'dsh-ide-settings'\n" : "[]\n");
const requests = [];
const bridge = createServer(async (req, res) => {
  let body = '';
  for await (const data of req) body += data;
  requests.push({ url: req.url, auth: req.headers.authorization, body: JSON.parse(body) });
  res.writeHead(200).end('{}');
});
await new Promise(r => bridge.listen(0, '127.0.0.1', r));
process.env.DSH_IDE_BRIDGE_URL = `http://127.0.0.1:${bridge.address().port}`;
process.env.DSH_IDE_BRIDGE_TOKEN = 'smoke-token';
let child;
let browser;
let output = '';
try {
  const native = await import(pathToFileURL(join(runtime, 'node_modules/@deepseek-ai/dsh-native-command/lib/index.js')));
  await native.openNativePath(resolve('README.md'), new AbortController().signal);
  await native.openNativeTextFile(resolve('build.gradle.kts'), new AbortController().signal);
  assert.equal(requests.length, 2);
  assert.ok(requests.every(r => r.url === '/open' && r.auth === 'Bearer smoke-token'));
  console.log('Native file and text-file IDE bridge: PASS');
  child = spawn(process.execPath, [join(runtime, 'node_modules/@deepseek-ai/dsh/lib/bin.js'), 'web', '--patch', patch, '--host', '127.0.0.1', '--port', '0', '--no-open'], {
    env: { ...process.env, DSH_HOME: home }, windowsHide: true,
  });
  child.stdout.on('data', d => output += d);
  child.stderr.on('data', d => output += d);
  const deadline = Date.now() + 90000;
  let url;
  while (Date.now() < deadline) {
    url = output.match(/http:\/\/127\.0\.0\.1:\d+[^\s\x1b]*/)?.[0];
    if (url) break;
    if (child.exitCode !== null) throw new Error(`DSH exited ${child.exitCode}: ${output}`);
    await new Promise(r => setTimeout(r, 250));
  }
  assert.ok(url, `No server URL: ${output}`);
  const login = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(15000) });
  assert.equal(login.status, 303);
  const cookie = login.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
  assert.ok(cookie);
  const response = await fetch(new URL(login.headers.get('location'), url), { headers: { cookie }, signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.ok(html.includes('<html') || html.includes('<!DOCTYPE'));
  assert.ok(!/ERR_MODULE_NOT_FOUND|failed to load|Cannot find package/i.test(output), output);
  console.log(`Bundled Web startup: PASS (HTTP ${response.status}, ${html.length} chars)`);
  if (useBrowser) {
    const { chromium } = await import('../.build-input/browser-tests/node_modules/playwright/index.mjs');
    browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await page.addInitScript(() => {
      window.__dshIdeDroppedFiles = async () => JSON.stringify(window.__testDraggedFiles ?? []);
      window.__dshIdeOpenFiles = async () => '[]';
      window.__testOpenedFiles = [];
      window.__dshIdeOpenPath = async (path, line) => { window.__testOpenedFiles.push({path, line}); };
    });
    await page.goto(url);
    await page.getByRole('button', { name: '继续', exact: true }).click();
    await page.waitForFunction(() => typeof window.__dshIdePrepareFileDrop === 'function');
    const composer = page.locator('[data-composer-input]');
    const imageBase64 = (await readFile('src/main/resources/icons/dshPluginIcon.png')).toString('base64');
    await page.getByRole('dialog', { name: '开始使用', exact: true }).getByRole('button').first().click();
    await page.locator('[data-composer-input][contenteditable="true"]').waitFor();
    await composer.fill('请检查这些文件 ');
    await composer.evaluate((input, { paths, imageBase64 }) => {
      window.__testDraggedFiles = paths;
      const transfer = new DataTransfer();
      transfer.items.add(new File(['class Example {}'], '示例 文件.java', { type: 'text/plain' }));
      transfer.items.add(new File(['export const test = true;'], 'second.ts', { type: 'text/plain' }));
      const bytes = Uint8Array.from(atob(imageBase64), c => c.charCodeAt(0));
      transfer.items.add(new File([bytes], '参考图.png', { type: 'image/png' }));
      for (const name of ['dragenter', 'dragover', 'drop']) {
        input.dispatchEvent(new DragEvent(name, { bubbles: true, cancelable: true, dataTransfer: transfer }));
      }
    }, { imageBase64, paths: [
      { path: resolve('示例 文件.java'), directory: false },
      { path: resolve('src/second.ts'), directory: false },
      { path: resolve('参考图.png'), directory: false },
    ] });
    await page.waitForFunction(() => document.querySelectorAll('[data-composer-chip="reference"]').length === 2);
    assert.ok((await composer.innerText()).startsWith('请检查这些文件 '));
    assert.deepEqual(await composer.locator('[data-composer-chip="reference"]').allTextContents(), ['示例 文件.java', 'second.ts']);
    await page.waitForFunction(() => [...document.querySelectorAll('img')].some(img => img.src.startsWith('blob:') && img.naturalWidth > 0));
    await composer.press('Control+A');
    const copied = await composer.evaluate(input => {
      const transfer = new DataTransfer();
      input.dispatchEvent(new ClipboardEvent('copy', { bubbles: true, cancelable: true, clipboardData: transfer }));
      return transfer.getData('text/plain');
    });
    assert.ok(copied.includes(resolve('示例 文件.java')), copied);
    assert.ok(copied.includes(resolve('src/second.ts')), copied);
    await composer.press('ArrowRight');
    assert.deepEqual(errors, []);
    await page.screenshot({ path: 'tmp/dsh-file-drop-smoke.png', fullPage: true });
    console.log('Browser mixed drop: PASS (two file references, image preview, existing draft retained, no page errors)');
  }
} finally {
  await browser?.close();
  if (child && child.exitCode === null) {
    const closed = new Promise(r => child.once('close', r));
    child.kill();
    await closed;
  }
  await new Promise(r => bridge.close(r));
}
