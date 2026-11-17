// Automated accessibility checks for the phase 0 trial.
//
// Usage:  node run.mjs [path-to-ui/dist]
//
// The screens are served from a local folder and opened in Chromium. There is no desktop shell
// here: the screens notice that and use the in-page backend, with the same data.
//
// What this covers: axe-core rules (WCAG 2.0-2.2 A and AA) on every screen, in every theme and
// at large text; keyboard operation of tabs, form errors and the grid; reflow at 320 px; the
// accessibility tree Chromium builds for the grid. What it cannot cover: how NVDA or Orca
// actually speak it. That is the manual script in docs/gui/PHASE0-TESTING.md.
//
// Set CHROMIUM_PATH to use a browser that is already installed.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { chromium } from 'playwright';

const require = createRequire(import.meta.url);
const axeSource = await readFile(require.resolve('axe-core/axe.min.js'), 'utf8');
const dist = resolve(process.argv[2] ?? '../../ui/dist');

const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.css': 'text/css' };
const server = createServer(async (request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url, 'http://x').pathname)).replace(/^([/\\])+/, '');
  try {
    const body = await readFile(join(dist, path || 'index.html'));
    response.writeHead(200, { 'content-type': types[extname(path || 'index.html')] ?? 'application/octet-stream' });
    response.end(body);
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise((ready) => server.listen(0, '127.0.0.1', ready));
const url = `http://127.0.0.1:${server.address().port}/`;

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const failures = [];
let checks = 0;
function check(condition, message) {
  checks++;
  if (!condition) failures.push(message);
}

async function section(name, body) {
  try {
    await body();
  } catch (error) {
    failures.push(`section "${name}" stopped: ${error.message.split('\n')[0]}`);
  }
}

async function open(options = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...options });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(url);
  await page.waitForSelector('[role="tab"]');
  return { page, context, errors };
}

async function axe(page, label) {
  await page.addScriptTag({ content: axeSource });
  const result = await page.evaluate(async () =>
    // eslint-disable-next-line no-undef
    axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'] } }),
  );
  for (const v of result.violations) {
    failures.push(`axe [${label}] ${v.id} (${v.impact}): ${v.help} — ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`);
  }
  checks++;
  return result.violations.length;
}

/** Focus moves after the next frame is drawn, so wait for it rather than checking at once. */
async function focusIs(page, id, timeout = 2000) {
  await page.waitForFunction((wanted) => document.activeElement?.id === wanted, id, { timeout }).catch(() => {});
  return (await page.evaluate(() => document.activeElement?.id)) === id;
}

async function showTab(page, name) {
  await page.getByRole('tab', { name }).click();
  await page.waitForTimeout(150);
}

const TABS = ['Run settings', 'Events', 'Running', 'Display'];

// 1. axe on every screen, in each theme and at 200% text.
await section('1. axe on every screen, in each theme and at 200% text.', async () => {
for (const [theme, textSize] of [['light', 100], ['dark', 100], ['contrast', 100], ['light', 200]]) {
  const { page, context, errors } = await open({ colorScheme: theme === 'dark' ? 'dark' : 'light' });
  await page.evaluate(([t, s]) => localStorage.setItem('javabyte.preferences', JSON.stringify({ theme: t, text_size: s })), [theme, textSize]);
  await page.reload();
  await page.waitForSelector('[role="tab"]');
  for (const tab of TABS) {
    await showTab(page, tab);
    if (tab === 'Events') await page.waitForSelector('[role="gridcell"]');
    await axe(page, `${tab}, ${theme}, ${textSize}%`);
  }
  check(errors.length === 0, `page errors (${theme}): ${errors.join('; ')}`);
  await context.close();
}
});

// 2. Keyboard: skip link, tabs, form errors.
await section('2. Keyboard: skip link, tabs, form errors.', async () => {
{
  const { page, context } = await open();
  await page.keyboard.press('Tab');
  check((await page.evaluate(() => document.activeElement.textContent)) === 'Skip to main content', 'first Tab should reach the skip link');
  await page.keyboard.press('Tab');
  check((await page.evaluate(() => document.activeElement.getAttribute('role'))) === 'tab', 'second Tab should reach the tab list');
  await page.keyboard.press('ArrowRight');
  check(await focusIs(page, 'tab-events'), 'ArrowRight should move to the Events tab');
  check(await page.locator('#panel-events').isVisible(), 'Events panel should show when its tab is focused');
  await page.keyboard.press('End');
  check(await focusIs(page, 'tab-display'), 'End should move to the last tab');
  await page.keyboard.press('Home');
  check(await focusIs(page, 'tab-settings'), 'Home should move to the first tab');
  await page.keyboard.press('Tab');
  // Inside the settings panel: submit the empty form and expect the error summary to take focus.
  await page.getByRole('button', { name: 'Check settings' }).click();
  await page.waitForTimeout(100);
  check(await focusIs(page, 'error-summary-title'), 'error summary should take focus after a failed check');
  check((await page.locator('#sample').getAttribute('aria-invalid')) === 'true', 'invalid field should have aria-invalid');
  check((await page.locator('#sample').getAttribute('aria-describedby')).includes('sample-error'), 'invalid field should be described by its error');
  await page.getByRole('link', { name: /Enter the path/ }).click();
  check(await focusIs(page, 'sample'), 'error link should move focus to its field');
  await page.locator('#sample').fill("C:\\Users\\me\\it's.jar");
  await page.getByRole('button', { name: 'Check settings' }).click();
  await page.waitForTimeout(200);
  check((await page.locator('#announcer').textContent()).includes('valid'), 'a valid form should be announced');
  check((await page.locator('pre.command').textContent()).includes("'C:\\Users\\me\\it'\\''s.jar'"), 'command line should quote the path safely');
  await axe(page, 'form with errors fixed');
  await context.close();
}
});

// 3. The grid: one tab stop, arrow keys, jumps to the end, focus kept across re-renders.
await section('3. The grid: one tab stop, arrow keys, jumps to the end, focus kept across re-renders.', async () => {
{
  const { page, context } = await open();
  await showTab(page, 'Events');
  await page.waitForSelector('[role="gridcell"]');
  const grid = page.locator('[role="grid"]');
  check((await grid.getAttribute('aria-rowcount')) === '300001', 'grid should report 300,000 rows plus the header');
  check((await page.locator('[role="gridcell"][tabindex="0"]').count()) === 1, 'exactly one cell should be in the tab order');
  check((await page.locator('[role="row"]').count()) < 120, 'only rows near the view should exist in the page');
  await page.locator('#cell-0-0').focus();
  await page.keyboard.press('ArrowDown');
  check(await focusIs(page, 'cell-1-0'), 'Down arrow should move to the next row');
  await page.keyboard.press('ArrowRight');
  check(await focusIs(page, 'cell-1-1'), 'Right arrow should move to the next column');
  const started = Date.now();
  await page.keyboard.press('Control+End');
  await page.waitForFunction(() => document.activeElement?.id === 'cell-299999-4', null, { timeout: 5000 }).catch(() => {});
  const jump = Date.now() - started;
  check((await page.evaluate(() => document.activeElement.id)) === 'cell-299999-4', 'Control+End should focus the last cell');
  check(jump < 1500, `jumping to the last row took ${jump} ms`);
  check((await page.evaluate(() => document.activeElement.closest('[role="row"]').getAttribute('aria-rowindex'))) === '300001', 'last row should have aria-rowindex 300001');
  await page.keyboard.press('Control+Home');
  await page.waitForFunction(() => document.activeElement?.id === 'cell-0-0', null, { timeout: 5000 }).catch(() => {});
  check((await page.evaluate(() => document.activeElement.id)) === 'cell-0-0', 'Control+Home should return to the first cell');
  for (let i = 0; i < 30; i++) await page.keyboard.press('PageDown');
  await page.waitForTimeout(300);
  const after = await page.evaluate(() => document.activeElement.id);
  check(/^cell-\d+-0$/.test(after) && after !== 'cell-0-0', `PageDown should move far down and keep focus (focus is on ${after})`);
  // Hostile text must arrive as text: no element created from it, controls made visible.
  check((await page.locator('[role="grid"] img, [role="grid"] script').count()) === 0, 'markup from the sample must not become elements');
  // Filtering: the count is a status message.
  await page.locator('#filter-category').selectOption('net');
  await page.waitForFunction(() => document.querySelector('#events-count').textContent.includes('match'));
  check(/of 300,000 events match/.test(await page.locator('#events-count').textContent()), 'filter count should be stated');
  check((await page.locator('[role="gridcell"].col-1').allTextContents()).every((t) => t.trim() === 'net'), 'filtered grid should only show net events');
  await page.locator('#filter-text').fill('connect');
  await page.getByRole('button', { name: 'Apply filter' }).click();
  await page.waitForTimeout(300);
  await axe(page, 'filtered grid');
  // The simple table alternative.
  await page.getByLabel('A simple table, 50 events per page').check();
  await page.waitForSelector('table.simple-table caption');
  check((await page.locator('table.simple-table tbody tr').count()) === 50, 'table view should show 50 rows');
  await page.getByRole('button', { name: 'Next page' }).click();
  await page.waitForTimeout(200);
  check((await page.locator('#announcer').textContent()).startsWith('Page 2 of'), 'page change should be announced');
  await axe(page, 'table view');
  await context.close();
}
});

// 4. Hostile text in the details panel is shown, escaped, not executed.
await section('4. Hostile text in the details panel is shown, escaped, not executed.', async () => {
{
  const { page, context } = await open();
  await showTab(page, 'Events');
  await page.locator('#filter-text').fill('onerror');
  await page.getByRole('button', { name: 'Apply filter' }).click();
  await page.waitForTimeout(300);
  await page.locator('[role="gridcell"]').first().click();
  const details = await page.locator('.details').textContent();
  check(details.includes('<img src=x onerror=alert(1)>'), 'markup should be shown literally');
  check((await page.locator('.details img').count()) === 0, 'markup must not create elements');
  await page.locator('#filter-text').fill('fdp.exe');
  await page.getByRole('button', { name: 'Apply filter' }).click();
  await page.waitForTimeout(300);
  await page.locator('[role="gridcell"]').first().click();
  check((await page.locator('.details').textContent()).includes('invoice\\u{202e}fdp.exe'), 'bidirectional override should be shown as a code');
  await context.close();
}
});

// 5. Live progress: announcements are few.
await section('5. Live progress: announcements are few.', async () => {
{
  const { page, context } = await open();
  await page.evaluate(() => localStorage.setItem('javabyte.preferences', JSON.stringify({ announce_every: 5 })));
  await page.reload();
  await showTab(page, 'Running');
  const announcements = [];
  await page.exposeFunction('recordAnnouncement', (text) => announcements.push(text));
  await page.evaluate(() => {
    new MutationObserver(() => {
      const text = document.querySelector('#announcer').textContent;
      // eslint-disable-next-line no-undef
      if (text) recordAnnouncement(text);
    }).observe(document.querySelector('#announcer'), { childList: true, subtree: true, characterData: true });
  });
  await page.getByRole('button', { name: 'Start pretend analysis' }).click();
  await page.waitForTimeout(11_500);
  await page.keyboard.press('Control+.');
  await page.waitForTimeout(500);
  check(announcements[0]?.startsWith('Analysis started'), 'start should be announced');
  check(announcements.some((a) => a.startsWith('Analysis finished. Outcome: stopped')), `stop should be announced (got: ${announcements.join(' | ')})`);
  check(announcements.length <= 5, `at most start, two progress and finish expected in 12 s, got ${announcements.length}`);
  check(await page.getByRole('button', { name: 'Start pretend analysis' }).isEnabled(), 'Start should be available again after stopping');
  await axe(page, 'running finished');
  await context.close();
}
});

// 6. Reflow: at 320 px wide (400% zoom of 1280 px) the page must not scroll sideways.
await section('6. Reflow: at 320 px wide (400% zoom of 1280 px) the page must not scroll sideways.', async () => {
for (const tab of TABS) {
  const { page, context } = await open({ viewport: { width: 320, height: 640 } });
  await showTab(page, tab);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check(overflow <= 1, `${tab} at 320 px scrolls sideways by ${overflow} px`);
  await context.close();
}
});

// 7. Forced colours (Windows high contrast) and reduced motion: no errors, focus still visible.
await section('7. Forced colours (Windows high contrast) and reduced motion: no errors, focus still visible.', async () => {
{
  const { page, context } = await open({ forcedColors: 'active', reducedMotion: 'reduce' });
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  const outline = await page.evaluate(() => getComputedStyle(document.activeElement).outlineStyle);
  check(outline !== 'none', 'focus outline should remain in forced-colours mode');
  await context.close();
}
});

// 8. Chromium's accessibility tree for the grid, as assistive technology receives it.
await section('8. Chromium\'s accessibility tree for the grid, as assistive technology receives it.', async () => {
{
  const { page, context } = await open();
  await showTab(page, 'Events');
  await page.waitForSelector('[role="gridcell"]');
  const snapshot = await page.locator('[role="grid"]').ariaSnapshot();
  check(snapshot.includes('grid "Events"'), 'grid should be named "Events"');
  check(snapshot.includes('columnheader "Number"') && snapshot.includes('columnheader "Location"'), 'grid should expose its column headers');
  check(/gridcell "\d/.test(snapshot), 'grid cells should expose their text');
  await context.close();
}
});

await browser.close();
server.close();
console.log(`${checks} checks, ${failures.length} failed`);
for (const f of failures) console.log(`FAIL ${f}`);
process.exit(failures.length ? 1 : 0);
