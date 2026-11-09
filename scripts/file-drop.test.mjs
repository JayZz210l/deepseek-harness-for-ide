import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../src/main/resources/dsh/ide-settings/file-drop.js', import.meta.url), 'utf8');
function bridge(query, previous) {
  const context = vm.createContext({
    __dshIdeDroppedFiles: query,
    __DSH_HOST_PATHS__: previous,
    console: { warn() {} },
  });
  vm.runInContext(source, context);
  return context;
}

test('mixed multi-file drop retains Unicode, spaces, absolute paths and original image bytes', async () => {
  const code = new File(['class Example {}'], '示例 文件.java');
  const image = new File([new Uint8Array([137, 80, 78, 71])], '参考图.png', { type: 'image/png' });
  const entries = [
    { path: 'F:\\项目\\src\\示例 文件.java', directory: false },
    { path: 'F:\\项目\\参考图.png', directory: false },
  ];
  const ctx = bridge(async () => JSON.stringify(entries));
  const files = [code, image];
  const result = await ctx.__dshIdePrepareFileDrop(files, new Set());
  assert.equal(result.files, files);
  assert.equal(result.files[1], image);
  assert.equal(ctx.__DSH_HOST_PATHS__.pathFor(code), entries[0].path);
  assert.equal(ctx.__DSH_HOST_PATHS__.pathFor(image), entries[1].path);
  assert.equal(image.type, 'image/png');
  assert.deepEqual([...new Uint8Array(await image.arrayBuffer())], [137, 80, 78, 71]);
});

test('directory metadata joins browser entry metadata without changing the caller set', async () => {
  const folder = new File([], 'src');
  const known = new File([], 'assets');
  const directories = new Set([known]);
  const ctx = bridge(async () => [
    { path: 'F:/project/src', directory: true },
    { path: 'F:/project/assets', directory: true },
  ]);
  const result = await ctx.__dshIdePrepareFileDrop([folder, known], directories);
  assert.equal(result.directories.has(folder), true);
  assert.equal(result.directories.has(known), true);
  assert.equal(directories.has(folder), false);
});

test('canceled or stale drag cannot assign even a partially matching batch', async () => {
  const first = new File([], 'same.txt');
  const second = new File([], 'new.txt');
  const ctx = bridge(async () => [
    { path: 'F:/old/same.txt' }, { path: 'F:/old/previous.txt' },
  ]);
  const result = await ctx.__dshIdePrepareFileDrop([first, second], new Set());
  assert.equal(result.files[0], first);
  assert.equal(ctx.__DSH_HOST_PATHS__.pathFor(first), '');
  assert.equal(ctx.__DSH_HOST_PATHS__.pathFor(second), '');
});

test('incomplete native batch preserves every original file for stock upload', async () => {
  const files = [new File([], 'one.txt'), new File([], 'two.txt')];
  const ctx = bridge(async () => [{ path: 'F:/one.txt' }]);
  assert.equal((await ctx.__dshIdePrepareFileDrop(files, new Set())).files, files);
  assert.ok(files.every(file => ctx.__DSH_HOST_PATHS__.pathFor(file) === ''));
});

test('unavailable or failed native query keeps stock browser attachment behavior', async () => {
  const file = new File(['test'], 'test.txt');
  for (const query of [undefined, async () => { throw new Error('disconnected'); }, async () => 'invalid-json']) {
    const ctx = bridge(query);
    assert.equal((await ctx.__dshIdePrepareFileDrop([file], new Set())).files[0], file);
    assert.equal(ctx.__DSH_HOST_PATHS__.pathFor(file), '');
  }
});

test('preexisting host path bridge remains available for unrelated files', async () => {
  const old = new File([], 'old.txt');
  const added = new File([], 'new.txt');
  const ctx = bridge(async () => [{ path: 'F:/project/new.txt' }], {
    pathFor: file => file === old ? 'F:/existing/old.txt' : '',
  });
  await ctx.__dshIdePrepareFileDrop([added], new Set());
  assert.equal(ctx.__DSH_HOST_PATHS__.pathFor(old), 'F:/existing/old.txt');
  assert.equal(ctx.__DSH_HOST_PATHS__.pathFor(added), 'F:/project/new.txt');
});
