// Shared harness for the browser accessibility checks (run.mjs, criteria.mjs): serves the
// built screens from a folder, opens them in Chromium, counts checks and failures, runs axe, and
// walks the app's flow. Each script calls finish() at the end, which prints the result and sets
// the exit code.
//
// The browser build has no desktop backend; the screens use a stand-in that reads the committed
// tool output and runs a pretend analysis, so the whole flow can be walked.
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
export const url = `http://127.0.0.1:${server.address().port}/`;

export const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
export const failures = [];
export let checks = 0;
export function check(condition, message) {
  checks++;
  if (!condition) failures.push(message);
}

export async function section(name, body) {
  try {
    await body();
  } catch (error) {
    failures.push(`section "${name}" stopped: ${error.message.split('\n')[0]}`);
  }
}

export async function open(options = {}, query = '') {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...options });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(url + query);
  await page.waitForSelector('#start-heading');
  return { page, context, errors };
}

export async function axe(page, label) {
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
export async function focusIs(page, id, timeout = 2000) {
  await page.waitForFunction((wanted) => document.activeElement?.id === wanted, id, { timeout }).catch(() => {});
  return (await page.evaluate(() => document.activeElement?.id)) === id;
}

export async function showTab(page, name) {
  await page.getByRole('tab', { name }).click();
  await page.waitForTimeout(150);
}


/** Walks to a screen. Each step checks focus landed on the new screen's heading. */
export async function dismissNotice(page) {
  const button = page.getByRole('button', { name: 'I understand' });
  if (await button.isVisible()) await button.click();
}

export async function toOverview(page) {
  await dismissNotice(page);
  await page.getByRole('button', { name: 'Choose a sample file…' }).click();
  return focusIs(page, 'overview-heading', 5000);
}

export async function toSettings(page) {
  await page.getByRole('button', { name: 'Change settings first' }).click();
  const ok = await focusIs(page, 'settings-heading', 5000);
  // The forms appear once the schema has loaded.
  await page.waitForSelector('#section-limits');
  return ok;
}

/** Opens every "More settings" group and the presets, so what they hold is checked too. */
export async function openAllDisclosures(page) {
  await page.evaluate(() => document.querySelectorAll('.disclosure-toggle[aria-expanded="false"]').forEach((b) => b.click()));
  await page.waitForTimeout(100);
}

/** The YAML text as the YAML tab holds it. */
export function yamlText(page) {
  return page.locator('#settings-yaml').inputValue();
}

export async function toRunning(page) {
  await toOverview(page);
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  return focusIs(page, 'running-heading', 5000);
}

export async function toReport(page) {
  await toRunning(page);
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  await page.getByRole('button', { name: 'View the report' }).click({ timeout: 10000 });
  return focusIs(page, 'report-heading', 5000);
}

export async function reportTab(page, name) {
  await page.getByRole('tab', { name }).click();
  await page.waitForTimeout(150);
}


/** Closes the browser and the server, prints the result, and exits non-zero on any failure. */
export async function finish() {
  await browser.close();
  server.close();
  console.log(`${checks} checks, ${failures.length} failed`);
  for (const f of failures) console.log(`FAIL ${f}`);
  process.exit(failures.length ? 1 : 0);
}

/**
 * Walks every screen and state the browser build can reach, from the first-run notice to the
 * Tools and Preferences screens, calling `visit(label)` at each. Shared by the axe sweep and the
 * criteria checks, so both cover the same ground and new screens are added in one place.
 */
export async function walkScreens(page, visit) {
  await visit('start with notice');
  await toOverview(page);
  await visit('overview');
  await toSettings(page);
  // Every folded group open, so the fields inside are checked too.
  await openAllDisclosures(page);
  await page.getByRole('button', { name: 'Add web answer' }).click();
  // An answer needs an address; without one, Analyse would rightly refuse to start.
  await page.locator('#field-network-http-0-url').fill('gate\\.example');
  await page.keyboard.press('Tab');
  await page.waitForTimeout(200);
  await visit('settings forms');
  await page.getByRole('tab', { name: /^YAML/ }).click();
  await visit('settings YAML');
  await page.getByRole('tab', { name: 'Forms' }).click();
  await page.getByRole('button', { name: 'Back to the overview' }).click();
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  await page.waitForTimeout(400);
  await visit('running');
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  await page.getByRole('button', { name: 'View the report' }).click({ timeout: 10000 });
  for (const tab of ['Summary', 'Indicators', 'Events', 'Limits and gaps', 'Files', 'Settings used']) {
    await reportTab(page, tab);
    if (tab === 'Events') await page.waitForSelector('[role="gridcell"]');
    if (tab === 'Files') {
      await page.getByRole('button', { name: 'View stage.bin' }).click();
      await page.waitForSelector('.file-view');
    }
    await openAllDisclosures(page);
    await visit(`report ${tab}`);
  }
  await reportTab(page, 'Files');
  await page.getByRole('button', { name: 'Save as is…' }).click();
  await visit('save-as-is warning');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Compare with another run' }).click();
  await page.getByRole('button', { name: 'Compare', exact: true }).click();
  await page.waitForSelector('#compare-result-heading');
  await visit('compare');
  await page.getByRole('button', { name: 'Tools' }).click();
  await page.waitForSelector('#tools-panel-policy table');
  await visit('tools policy');
  await page.getByRole('tab', { name: 'Options and defaults' }).click();
  await page.waitForSelector('#tools-panel-defaults table');
  await visit('tools defaults');
  await page.getByRole('button', { name: 'Preferences' }).click();
  await page.waitForSelector('#prefs-isolate');
  await visit('preferences');
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  await visit('start with recent list');
}
