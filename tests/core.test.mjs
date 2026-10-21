import test from 'node:test';
import assert from 'node:assert/strict';
import '../shared.js';
const M = globalThis.MediaLens;
test('Missing attributes do not become bogus image URLs', () => {
  for (const value of [null, undefined, '', ' ', '#', 'javascript:alert(1)', 'data:image/png,a', 'file:///tmp/a', 'https://user:pass@site.test/a']) {
    assert.equal(M.httpUrl(value, 'https://site.test/channel'), null);
  }
  assert.equal(M.httpUrl('/photo.jpg', 'https://site.test/channel'), 'https://site.test/photo.jpg');
});
test('Largest srcset accepts density and width descriptors, with or without comma spacing', () => {
  assert.equal(M.biggestFromSrcset(['small.jpg 320w,large.jpg 1280w'], 'https://site.test/').url, 'https://site.test/large.jpg');
  assert.equal(M.biggestFromSrcset(['one.jpg 1x, two.jpg 2x'], 'https://site.test/').url, 'https://site.test/two.jpg');
});
test('Stream discovery ignores individual fragments', () => {
  assert.equal(M.streamKind('https://cdn.test/master.m3u8?token=a'), 'hls');
  assert.equal(M.streamKind('https://cdn.test/manifest.mpd'), 'dash');
  assert.equal(M.streamKind('https://cdn.test/film.mp4'), 'video');
  for (const url of ['https://cdn.test/file.m4s','https://cdn.test/segment-001.mp4','https://cdn.test/file.ts']) assert.equal(M.streamKind(url), null);
});
test('Filenames cannot escape the download directory', () => {
  assert.doesNotMatch(M.filename('../../a\0.png'), /[\x00-\x1f\\/]/);
  assert.ok(M.filename('...'));
});
