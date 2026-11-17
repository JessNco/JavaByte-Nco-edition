// Automated accessibility checks for the desktop app's screens.
//
// Usage:  node run.mjs [path-to-ui/dist]
//
// The screens are served from a local folder and opened in Chromium. There is no desktop shell
// here: the screens notice that and use the in-page backend, with the same data.
//
// The browser build has no desktop backend; the screens use a stand-in that reads the committed
// tool output and runs a pretend analysis, so the whole flow can be walked: start, sample
// overview, running, report.
//
// What this covers: axe-core rules (WCAG 2.0-2.2 A and AA) on every screen, in every theme and
// at large text; focus and titles when moving between screens; keyboard operation of tabs,
// choices and the grid; reflow at 320 px; announcements during a run; hostile text shown as
// text. What it cannot cover: how NVDA or Orca actually speak it. That is the manual script in
// docs/gui/PHASE0-TESTING.md.
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
  await page.waitForSelector('#start-heading');
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


/** Walks to a screen. Each step checks focus landed on the new screen's heading. */
async function dismissNotice(page) {
  const button = page.getByRole('button', { name: 'I understand' });
  if (await button.isVisible()) await button.click();
}

async function toOverview(page) {
  await dismissNotice(page);
  await page.getByRole('button', { name: 'Choose a sample file…' }).click();
  return focusIs(page, 'overview-heading', 5000);
}

async function toRunning(page) {
  await toOverview(page);
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  return focusIs(page, 'running-heading', 5000);
}

async function toReport(page) {
  await toRunning(page);
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  await page.getByRole('button', { name: 'View the report' }).click({ timeout: 10000 });
  return focusIs(page, 'report-heading', 5000);
}

async function reportTab(page, name) {
  await page.getByRole('tab', { name }).click();
  await page.waitForTimeout(150);
}

// 1. axe on every screen, in each theme and at 200% text.
for (const [theme, textSize] of [['light', 100], ['dark', 100], ['contrast', 100], ['light', 200]]) {
await section(`axe, ${theme}, ${textSize}%`, async () => {
  const { page, context, errors } = await open({ colorScheme: theme === 'dark' ? 'dark' : 'light' });
  await page.evaluate(([t, s]) => localStorage.setItem('javabyte.preferences', JSON.stringify({ theme: t, text_size: s })), [theme, textSize]);
  await page.reload();
  await page.waitForSelector('#start-heading');
  await axe(page, `start with notice, ${theme}, ${textSize}%`);
  await toOverview(page);
  await axe(page, `overview, ${theme}, ${textSize}%`);
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  await page.waitForTimeout(400);
  await axe(page, `running, ${theme}, ${textSize}%`);
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  await page.getByRole('button', { name: 'View the report' }).click({ timeout: 10000 });
  for (const tab of ['Summary', 'Indicators', 'Events']) {
    await reportTab(page, tab);
    if (tab === 'Events') await page.waitForSelector('[role="gridcell"]');
    await axe(page, `report ${tab}, ${theme}, ${textSize}%`);
  }
  await page.getByRole('button', { name: 'Display settings' }).click();
  await axe(page, `display settings, ${theme}, ${textSize}%`);
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  await axe(page, `start with recent list, ${theme}, ${textSize}%`);
  check(errors.length === 0, `page errors (${theme}): ${errors.join('; ')}`);
  await context.close();
});
}

// 2. Moving between screens: focus, titles, navigation state, the flow's buttons.
await section('screens and focus', async () => {
  const { page, context } = await open();
  check((await page.title()) === 'Start – JavaByte', `start title: ${await page.title()}`);
  await page.keyboard.press('Tab');
  check((await page.evaluate(() => document.activeElement.textContent)) === 'Skip to main content', 'first Tab reaches the skip link');
  // Steps not yet possible are disabled and say why.
  const report = page.getByRole('button', { name: 'Report', exact: true });
  check(await report.isDisabled(), 'Report is unavailable before any run');
  check((await report.getAttribute('aria-describedby')) !== null, 'an unavailable step says why');
  check(await page.getByRole('heading', { name: 'Before you start' }).isVisible(), 'the safety notice shows the first time');
  await page.getByRole('button', { name: 'I understand' }).click();
  check(await focusIs(page, 'choose-file'), 'after the notice, focus moves to the main action');
  await page.reload();
  check(!(await page.getByRole('heading', { name: 'Before you start' }).isVisible()), 'the notice is remembered');

  check(await toOverview(page), 'choosing a sample focuses the overview heading');
  check((await page.title()) === 'Sample overview – JavaByte', `overview title: ${await page.title()}`);
  check((await page.locator('#overview-heading').textContent()).includes('example.jar'), 'overview names the sample');
  check((await page.getByRole('button', { name: 'Sample overview' }).getAttribute('aria-current')) === 'page', 'nav marks the current screen');
  const radios = page.getByRole('radio');
  check((await radios.count()) >= 2, 'start options are radio buttons');
  check(await radios.first().isChecked(), 'the recommended start is chosen');
  check((await page.locator('pre.command').textContent()).startsWith('javabyte run'), 'the equivalent command is shown');
  await radios.last().check();
  await page.waitForTimeout(200);
  check((await page.locator('pre.command').textContent()).includes('clinit-sweep'), 'the command follows the choice');
  check((await page.getByText('Important: Part of it is native code').count()) === 1, 'findings say what matters, in words');

  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  check(await focusIs(page, 'running-heading', 5000), 'starting focuses the running heading');
  check((await page.title()) === 'Running – JavaByte', 'running title');
  await page.waitForTimeout(800);
  await page.keyboard.press('Control+.');
  await page.getByRole('button', { name: 'View the report' }).waitFor({ timeout: 10000 });
  // Announcements are set a moment after the screen changes, so wait for it.
  await page.waitForFunction(() => document.querySelector('#announcer').textContent.startsWith('Analysis finished.'), null, { timeout: 3000 }).catch(() => {});
  check((await page.locator('#announcer').textContent()).startsWith('Analysis finished.'), `the end is announced (got: ${await page.locator('#announcer').textContent()})`);
  await page.getByRole('button', { name: 'View the report' }).click();
  check(await focusIs(page, 'report-heading', 5000), 'opening the report focuses its heading');
  check((await page.locator('.lead').textContent()).startsWith('The sample'), 'the report opens with a plain summary');

  // Report tabs: one tab stop, arrow keys.
  await page.getByRole('tab', { name: 'Summary' }).focus();
  await page.keyboard.press('ArrowRight');
  check(await focusIs(page, 'report-tab-indicators'), 'ArrowRight moves to Indicators');
  check(await page.locator('#report-panel-indicators').isVisible(), 'the Indicators panel shows');
  check((await page.locator('#report-panel-indicators h3').count()) >= 1, 'indicator lists have headings');
  await page.keyboard.press('End');
  check(await focusIs(page, 'report-tab-events'), 'End moves to the last tab');

  // The recent list opens a report too.
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  check(await focusIs(page, 'start-heading'), 'Start focuses its heading');
  await page.getByRole('button', { name: /^Open report: earlier\.jar/ }).click();
  check(await focusIs(page, 'report-heading', 5000), 'a recent run opens its report');
  check((await page.locator('#report-heading').textContent()).includes('earlier.jar'), 'the opened report is the chosen one');
  await context.close();
});

// 3. The event grid in the report: one tab stop, arrow keys, jumps over 300,000 rows.
await section('event grid', async () => {
  const { page, context } = await open();
  await toReport(page);
  await reportTab(page, 'Events');
  await page.waitForSelector('[role="gridcell"]');
  const grid = page.locator('[role="grid"]');
  check((await grid.getAttribute('aria-rowcount')) === '300001', 'grid reports 300,000 rows plus the header');
  check((await page.locator('[role="gridcell"][tabindex="0"]').count()) === 1, 'exactly one cell is in the tab order');
  check((await page.locator('[role="row"]').count()) < 120, 'only rows near the view exist in the page');
  await page.locator('#cell-0-0').focus();
  await page.keyboard.press('ArrowDown');
  check(await focusIs(page, 'cell-1-0'), 'Down arrow moves to the next row');
  await page.keyboard.press('ArrowRight');
  check(await focusIs(page, 'cell-1-1'), 'Right arrow moves to the next column');
  const started = Date.now();
  await page.keyboard.press('Control+End');
  check(await focusIs(page, 'cell-299999-4', 5000), 'Control+End focuses the last cell');
  check(Date.now() - started < 1500, `jumping to the last row took ${Date.now() - started} ms`);
  await page.keyboard.press('Control+Home');
  check(await focusIs(page, 'cell-0-0', 5000), 'Control+Home returns to the first cell');
  check((await page.locator('[role="grid"] img, [role="grid"] script').count()) === 0, 'markup from the sample does not become elements');
  await page.locator('#filter-category').selectOption('net');
  await page.waitForFunction(() => document.querySelector('#events-count').textContent.includes('match'));
  check(/of 300,000 events match/.test(await page.locator('#events-count').textContent()), 'the filter count is stated');
  await page.getByLabel('A simple table, 50 events per page').check();
  await page.waitForSelector('#report-panel-events table.simple-table caption');
  await page.getByRole('button', { name: 'Next page' }).click();
  await page.waitForTimeout(200);
  check((await page.locator('#announcer').textContent()).startsWith('Page 2 of'), 'page changes are announced');
  await axe(page, 'events table view');
  await context.close();
});

// 4. Hostile text is shown, escaped, not executed.
await section('hostile text', async () => {
  const { page, context } = await open();
  await toReport(page);
  await reportTab(page, 'Events');
  await page.locator('#filter-text').fill('fdp.exe');
  await page.getByRole('button', { name: 'Apply filter' }).click();
  await page.waitForTimeout(300);
  await page.locator('[role="gridcell"]').first().click();
  check((await page.locator('.details').textContent()).includes('invoice\\u{202e}fdp.exe'), 'a bidirectional override is shown as a code');
  await page.locator('#filter-text').fill('onerror');
  await page.getByRole('button', { name: 'Apply filter' }).click();
  await page.waitForTimeout(300);
  await page.locator('[role="gridcell"]').first().click();
  check((await page.locator('.details').textContent()).includes('<img src=x onerror=alert(1)>'), 'markup is shown literally');
  check((await page.locator('.details img').count()) === 0, 'markup does not create elements');
  await context.close();
});

// 5. Announcements during a run are few.
await section('progress announcements', async () => {
  const { page, context } = await open();
  await page.evaluate(() => localStorage.setItem('javabyte.preferences', JSON.stringify({ announce_every: 5 })));
  await page.reload();
  await page.waitForSelector('#start-heading');
  const announcements = [];
  await page.exposeFunction('recordAnnouncement', (text) => announcements.push(text));
  await page.evaluate(() => {
    new MutationObserver(() => {
      const text = document.querySelector('#announcer').textContent;
      // eslint-disable-next-line no-undef
      if (text) recordAnnouncement(text);
    }).observe(document.querySelector('#announcer'), { childList: true, subtree: true, characterData: true });
  });
  await toRunning(page);
  await page.waitForTimeout(11_500);
  await page.keyboard.press('Control+.');
  await page.waitForTimeout(1500);
  const progress = announcements.filter((a) => /^\d+ seconds/.test(a));
  check(announcements.some((a) => a.startsWith('Analysis of example.jar started')), `the start is announced (got: ${announcements.join(' | ')})`);
  check(announcements.some((a) => a.startsWith('Analysis finished.')), `the end is announced (got: ${announcements.join(' | ')})`);
  check(progress.length >= 1 && progress.length <= 3, `about one progress sentence every 5 s, got ${progress.length}`);
  check(announcements.length <= 8, `few announcements in 12 s, got ${announcements.length}: ${announcements.join(' | ')}`);
  await context.close();
});

// 6. Reflow: at 320 px wide (400% zoom of 1280 px) no screen scrolls sideways.
await section('reflow', async () => {
  const { page, context } = await open({ viewport: { width: 320, height: 640 } });
  const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check((await overflow()) <= 1, `start at 320 px scrolls sideways by ${await overflow()} px`);
  await toOverview(page);
  check((await overflow()) <= 1, `overview at 320 px scrolls sideways by ${await overflow()} px`);
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  await page.waitForTimeout(400);
  check((await overflow()) <= 1, `running at 320 px scrolls sideways by ${await overflow()} px`);
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  await page.getByRole('button', { name: 'View the report' }).click({ timeout: 10000 });
  for (const tab of ['Summary', 'Indicators', 'Events']) {
    await reportTab(page, tab);
    check((await overflow()) <= 1, `report ${tab} at 320 px scrolls sideways by ${await overflow()} px`);
  }
  await context.close();
});

// 7. Forced colours (Windows high contrast) and reduced motion: focus still visible.
await section('forced colours', async () => {
  const { page, context } = await open({ forcedColors: 'active', reducedMotion: 'reduce' });
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  const outline = await page.evaluate(() => getComputedStyle(document.activeElement).outlineStyle);
  check(outline !== 'none', 'focus outline remains in forced-colours mode');
  await context.close();
});

// 8. Chromium's accessibility tree for the grid, as assistive technology receives it.
await section('grid accessibility tree', async () => {
  const { page, context } = await open();
  await toReport(page);
  await reportTab(page, 'Events');
  await page.waitForSelector('[role="gridcell"]');
  const snapshot = await page.locator('[role="grid"]').ariaSnapshot();
  check(snapshot.includes('grid "Events"'), 'the grid is named "Events"');
  check(snapshot.includes('columnheader "Number"') && snapshot.includes('columnheader "Location"'), 'the grid exposes its column headers');
  await context.close();
});

await browser.close();
server.close();
console.log(`${checks} checks, ${failures.length} failed`);
for (const f of failures) console.log(`FAIL ${f}`);
process.exit(failures.length ? 1 : 0);
