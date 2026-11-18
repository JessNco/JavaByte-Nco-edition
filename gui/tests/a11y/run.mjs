// Automated accessibility checks for the desktop app's screens, in Chromium.
//
// Usage:  node run.mjs [path-to-ui/dist]
//
// What this covers: axe-core rules (WCAG 2.0-2.2 A and AA) on every screen, in every theme and
// at large text; focus and titles when moving between screens; keyboard operation of tabs,
// choices and the grid; reflow at 320 px; announcements during a run; hostile text shown as
// text; Preferences and the isolation question; the Tools screen.
//
// The harness (server, browser, the walk through every screen) is in lib.mjs. The WCAG 2.2
// criteria axe does not test are in criteria.mjs. What only a person can judge, with NVDA, Orca
// and their own eyes, is the manual audit in docs/gui/PHASE6-AUDIT.md.

import {
  check, section, open, axe, focusIs, toOverview, toSettings, openAllDisclosures, yamlText, toRunning, toReport, reportTab, walkScreens, finish,
} from './lib.mjs';

// 1. axe on every screen, in each theme and at 200% text.
for (const [theme, textSize] of [['light', 100], ['dark', 100], ['contrast', 100], ['light', 200]]) {
await section(`axe, ${theme}, ${textSize}%`, async () => {
  const { page, context, errors } = await open({ colorScheme: theme === 'dark' ? 'dark' : 'light' });
  await page.evaluate(([t, s]) => localStorage.setItem('javabyte.preferences', JSON.stringify({ theme: t, text_size: s })), [theme, textSize]);
  await page.reload();
  await page.waitForSelector('#start-heading');
  await walkScreens(page, (label) => axe(page, `${label}, ${theme}, ${textSize}%`));
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
  check((await page.locator('#overview-command').textContent()).startsWith('javabyte run'), 'the equivalent command is shown');
  await radios.last().check();
  await page.waitForTimeout(200);
  check((await page.locator('#overview-command').textContent()).includes('clinit-sweep'), 'the command follows the choice');
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
  check(await focusIs(page, 'report-tab-settings'), 'End moves to the last tab');

  // The recent list opens a report too.
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  check(await focusIs(page, 'start-heading'), 'Start focuses its heading');
  await page.getByRole('button', { name: /^Open report: earlier\.jar/ }).click();
  check(await focusIs(page, 'report-heading', 5000), 'a recent run opens its report');
  check((await page.locator('#report-heading').textContent()).includes('earlier.jar'), 'the opened report is the chosen one');
  await context.close();
});

// 3b. Settings: forms that edit YAML in place, problems tied to fields, lists by keyboard,
// the YAML tab, presets, import, and the command line.
await section('settings', async () => {
  const { page, context, errors } = await open();
  await toOverview(page);
  check(await toSettings(page), 'Change settings first focuses the settings heading');
  check((await page.title()) === 'Settings – JavaByte', `settings title: ${await page.title()}`);
  check((await page.locator('#settings-panel-forms input, #settings-panel-forms select, #settings-panel-forms textarea').count()) > 20, 'the forms have fields');

  // A field changes only its line; the comment on it stays.
  const sleeps = page.getByLabel('Sleeps per thread', { exact: true });
  check((await sleeps.inputValue()) === '40', 'fields show the suggested settings');
  check((await sleeps.getAttribute('aria-describedby')) !== null, 'fields are tied to their description');
  await sleeps.fill('200');
  await sleeps.press('Tab');
  check((await yamlText(page)).includes('  maxSleeps: 200        # ends beacon loops'), 'a form change edits only its line and keeps the comment');
  check((await yamlText(page)).includes('# Uncomment to also run every static initialiser'), 'other comments are kept');

  // A problem: shown in a summary with a link, and at the field, in words.
  // A problem inside a closed group: the summary link opens the group to reach the field.
  await sleeps.fill('lots');
  await sleeps.press('Tab');
  await page.waitForSelector('#settings-problems-title', { timeout: 5000 });
  check((await page.locator('#settings-problems-title').textContent()).includes('1 problem'), 'the summary counts the problems');
  check((await sleeps.getAttribute('aria-invalid')) === 'true', 'the field is marked invalid');
  const described = (await sleeps.getAttribute('aria-describedby')).split(' ');
  const errorText = await page.locator(`#${described[described.length - 1]}`).textContent();
  check(errorText.startsWith('Problem:'), `the field's problem is in words, tied to it: ${errorText}`);
  await page.locator('#settings-problems-title ~ ul a').first().click();
  check(await focusIs(page, 'field-limits-maxSleeps'), 'the summary link moves focus to the field');
  // Analysing with a problem shows the problems instead of starting.
  await page.getByRole('button', { name: 'Analyse with these settings' }).click();
  check(await focusIs(page, 'settings-problems-title', 3000), 'analysing with problems focuses the summary');
  check((await page.title()) === 'Settings – JavaByte', 'analysing with problems stays on the settings');
  await page.getByRole('button', { name: 'Reset to default: Sleeps per thread' }).click();
  await page.waitForTimeout(1500);
  check((await page.locator('#settings-problems-title').count()) === 0, 'fixing the problem clears the summary');
  check(!(await yamlText(page)).includes('maxSleeps'), 'reset to default removes the setting');

  // Lists by keyboard: add, edit, move, remove.
  await page.getByRole('button', { name: 'Add web answer' }).click();
  check(await focusIs(page, 'field-network-http-0-url'), 'adding an item focuses its first field');
  check((await page.locator('h4', { hasText: 'Editing web answer 1 of 1' }).count()) === 1, 'the editor says which item it edits');
  await page.keyboard.type('gate\\.example');
  await page.keyboard.press('Tab');
  await page.getByRole('button', { name: 'Done' }).click();
  check(await focusIs(page, 'field-network-http-edit-0'), 'Done returns focus to the item');
  await page.getByRole('button', { name: 'Add web answer' }).click();
  check(await focusIs(page, 'field-network-http-1-url'), 'the second item opens at its first field');
  await page.keyboard.type('second');
  await page.keyboard.press('Tab');
  await page.waitForTimeout(300);
  const after = await page.evaluate(() => ({ inEditor: !!document.activeElement?.closest('.item-editor'), label: document.activeElement?.getAttribute('aria-label') }));
  check(after.inEditor && after.label === 'Reset to default: Address pattern', `after a change, Tab moves on inside the editor (focus: ${JSON.stringify(after)})`);
  await page.getByRole('button', { name: 'Done' }).click();
  await page.getByRole('button', { name: 'Move up: web answer 2' }).click();
  check(await focusIs(page, 'field-network-http-down-0'), 'after moving to the top, focus stays with the item');
  const yaml = await yamlText(page);
  check(yaml.indexOf('url: second') < yaml.indexOf("url: 'gate\\.example'"), `the move is in the YAML:\n${yaml}`);
  check(yaml.includes('  http:\n    - url: second'), `new lists are written in block style:\n${yaml}`);
  await page.getByRole('button', { name: 'Remove web answer 1' }).click();
  check(!(await yamlText(page)).includes('second'), 'remove takes the item out');

  // Names and values: keys with dots are one name.
  const more = page.locator('#section-environment').locator('..').getByRole('button', { name: 'More settings' });
  check((await more.getAttribute('aria-expanded')) === 'false', 'More settings starts closed and says so');
  await more.press('Enter');
  check((await more.getAttribute('aria-expanded')) === 'true', 'Enter opens More settings, and it says so');
  await page.locator('#field-environment-properties-new-name').fill('java.version');
  await page.locator('#field-environment-properties-new-value').fill('17');
  await page.getByRole('button', { name: 'Add to Java system properties' }).click();
  check((await yamlText(page)).includes("java.version: '17'"), 'a name with dots is one key, and text stays text');

  // The YAML tab: arrow keys between tabs; line numbers are hidden from screen readers.
  await page.getByRole('tab', { name: 'Forms' }).focus();
  await page.keyboard.press('ArrowRight');
  check(await focusIs(page, 'settings-tab-yaml'), 'ArrowRight moves to the YAML tab');
  await page.keyboard.press('Enter');
  check((await page.locator('.yaml-editor .gutter').getAttribute('aria-hidden')) === 'true', 'line numbers are hidden from screen readers');
  const area = page.locator('#settings-yaml');
  await area.fill((await area.inputValue()) + 'limits: [broken\n');
  await page.waitForSelector('#syntax-title', { timeout: 3000 });
  check((await page.locator('#settings-panel-forms').textContent()).includes('hidden until the YAML can be read'), 'forms step aside while the YAML cannot be read');
  await page.locator('#syntax-title ~ ul a').first().click();
  check(await focusIs(page, 'settings-yaml'), 'the syntax link puts the caret in the YAML');
  const fixed = (await area.inputValue()).replace('limits: [broken\n', '');
  await area.fill(fixed);
  await page.waitForTimeout(600);
  check((await page.locator('#syntax-title').count()) === 0, 'fixing the YAML brings the forms back');
  await page.getByRole('tab', { name: 'Forms' }).click();

  // Presets: fill in fields people can see, focus the first, and can be undone.
  await page.getByRole('button', { name: 'Presets' }).click();
  const before = await yamlText(page);
  await page.getByRole('button', { name: 'Let a waiting loop run longer' }).click();
  check(await focusIs(page, 'field-limits-maxSleeps'), 'a preset shows the field it changed');
  check((await page.getByLabel('Sleeps per thread', { exact: true }).inputValue()) === '200', 'the preset filled in the field');
  await page.getByRole('button', { name: 'Undo the last preset' }).click();
  check((await yamlText(page)) === before, 'undo puts the settings back exactly');
  await page.locator('#preset-new-name').fill('Slow beacon');
  await page.getByRole('button', { name: 'Save as preset' }).click();
  await page.getByRole('button', { name: 'Apply preset Slow beacon' }).waitFor({ timeout: 3000 });
  await page.getByRole('button', { name: 'Delete preset Slow beacon' }).click();
  await page.waitForTimeout(300);
  check((await page.getByRole('button', { name: 'Apply preset Slow beacon' }).count()) === 0, 'a saved preset can be deleted');

  // Import keeps the chosen sample, and says the file was for another one.
  await page.getByRole('button', { name: 'Import…' }).click();
  await page.waitForTimeout(300);
  check((await page.locator('#announcer').textContent()).includes('made for another sample'), 'import says the file was for another sample');
  check((await yamlText(page)).includes('example.jar') && !(await yamlText(page)).includes('dropper.jar'), 'the imported settings use the chosen sample');
  check((await page.getByLabel('Sleeps per thread', { exact: true }).inputValue()) === '200', 'imported values reach the forms');

  // The command line follows the settings.
  check((await page.locator('#settings-command-heading + pre').textContent()).includes('-s limits.maxSleeps=200'), 'the command line follows the settings');
  check(errors.length === 0, `page errors: ${errors.join('; ')}`);
  await context.close();
});

// 3c. The full report: limits, files (viewer, zip, the warning), settings used, compare,
// event actions and jumping, and opening reports from disk.
await section('full report', async () => {
  const { page, context, errors } = await open();
  await toReport(page);

  // Limits and gaps: every part's ending, in words.
  await reportTab(page, 'Limits and gaps');
  check((await page.locator('#report-panel-limits h3').count()) === 4, 'limits has its four headings');
  check((await page.locator('#report-panel-limits .endings li').first().textContent()).includes('The sample ran to its end.'), 'endings are in words');

  // Files: a table, hostile names shown safely.
  await reportTab(page, 'Files');
  await page.waitForSelector('#report-panel-files tbody tr');
  check((await page.locator('#report-panel-files tbody tr').count()) === 4, 'every saved file is listed');
  const panelText = await page.locator('#report-panel-files').textContent();
  check(!panelText.includes('‮'), 'a right-to-left override in a name is not passed to the page');
  check(panelText.includes('invoice'), 'the name is still shown');

  // Zip: needs a selection first; the button says why.
  const zip = page.getByRole('button', { name: 'Save selected as a password zip…' });
  check(await zip.isDisabled(), 'the zip button waits for a selection');
  check((await zip.getAttribute('aria-describedby')) === 'files-zip-hint', 'and says why');
  await page.getByRole('checkbox', { name: 'Select stage.bin' }).check();
  await zip.click();
  await page.waitForTimeout(200);
  check((await page.locator('#report-panel-files .inline-status').textContent()).includes('1 file saved in a password zip'), 'saving a zip is confirmed');

  // The viewer: text, hex, pages, and markup shown literally.
  await page.getByRole('button', { name: /^View invoice/ }).click();
  check(await focusIs(page, 'viewer-heading'), 'viewing a file focuses its heading');
  const text = await page.locator('.file-view').textContent();
  check(text.includes('<img src=x onerror=alert(1)>'), 'markup in a file is shown as text');
  check((await page.locator('.file-view img').count()) === 0, 'and never becomes an element');
  check(!text.includes('‮') && !text.includes('\u0007'), 'control and direction characters are shown as codes');
  await page.getByLabel('Hex', { exact: true }).check();
  check(/^00000000  3c 69 6d 67/.test(await page.locator('.file-view').textContent()), 'hex has offsets and bytes');
  await page.getByRole('button', { name: 'View stage.bin' }).click();
  check((await page.locator('.viewer [role="status"]').textContent()).includes('65,536 of 200,008'), 'the viewer shows the first 64 KB and says so');
  await page.getByRole('button', { name: /^Show 64\.0 KB more/ }).click();
  await page.waitForTimeout(200);
  check((await page.locator('.viewer [role="status"]').textContent()).includes('131,072 of 200,008'), 'Show more adds the next 64 KB');
  await page.getByRole('button', { name: 'View Loader.class' }).click();
  await page.getByLabel('Instructions (bytecode)').check();
  await page.waitForTimeout(200);
  const listing = await page.locator('.file-view').textContent();
  check(listing.includes('LDC') && listing.includes('<script>') && (await page.locator('.file-view script').count()) === 0, 'class instructions are shown, as text');

  // Save as is: a real alert dialog, Cancel first, the box before saving, no timer.
  const opener = page.getByRole('button', { name: 'Save as is…' });
  await opener.click();
  const dialog = page.getByRole('alertdialog');
  check(await dialog.isVisible(), 'the warning is an alert dialog');
  check(await focusIs(page, 'as-is-cancel'), 'focus starts on Cancel');
  check((await dialog.getAttribute('aria-describedby')) === 'as-is-warning', 'the warning is the dialog description');
  check((await page.locator('#as-is-warning').textContent()).includes('may be malware'), 'the warning says what can happen');
  const saveAsIs = page.locator('#as-is-save');
  check(await saveAsIs.isDisabled(), 'Save as is waits for the box');
  await page.keyboard.press('Escape');
  check(!(await dialog.isVisible()), 'Escape closes the warning');
  check(await focusIs(page, 'save-as-is-open'), 'focus returns to the button that opened it');
  await opener.click();
  await page.getByLabel('I understand this file may be harmful').check();
  check(!(await saveAsIs.isDisabled()), 'ticking the box enables Save as is');
  await saveAsIs.click();
  await page.waitForTimeout(200);
  check((await page.locator('#report-panel-files .inline-status').textContent()).includes('saved as is'), 'saving as is is confirmed');

  // Settings used, and running again with changes.
  await reportTab(page, 'Settings used');
  check((await page.locator('#report-panel-settings pre').count()) === 2, 'the settings file and the effective settings are shown');
  await page.getByRole('button', { name: 'Run again with changes' }).click();
  check(await focusIs(page, 'settings-heading', 5000), 'Run again with changes opens the settings');

  // Events: filter by action, and go to an event number.
  await page.getByRole('button', { name: 'Report', exact: true }).click();
  await reportTab(page, 'Events');
  await page.waitForSelector('[role="gridcell"]');
  check(await page.locator('#filter-action').isDisabled(), 'the action filter waits for a category');
  await page.locator('#filter-category').selectOption('net');
  await page.waitForFunction(() => document.querySelectorAll('#filter-action option').length > 1);
  check(!(await page.locator('#filter-action').isDisabled()), 'choosing a category offers its actions');
  const firstAction = await page.locator('#filter-action option').nth(1).getAttribute('value');
  await page.locator('#filter-action').selectOption(firstAction);
  await page.waitForTimeout(300);
  const cats = await page.locator('[role="gridcell"][aria-colindex="3"]').allTextContents();
  check(cats.length > 0 && cats.every((a) => a === firstAction), `only ${firstAction} events are listed`);
  await page.getByRole('button', { name: 'Clear filter' }).click();
  await page.waitForTimeout(300);
  await page.locator('#jump-to-event').fill('123456');
  await page.getByRole('button', { name: 'Go', exact: true }).click();
  check(await focusIs(page, 'cell-123456-0', 5000), 'going to an event number focuses its row');

  // Compare: the previous run of the same sample is suggested.
  await page.getByRole('button', { name: 'Compare with another run' }).click();
  check(await focusIs(page, 'compare-heading'), 'Compare focuses its heading');
  check((await page.locator('#compare-first option:checked').textContent()).includes('2026-11-16 18:00'), 'the previous run of the same sample is suggested first');
  check((await page.locator('#compare-second option:checked').textContent()).includes('2026-11-17 10:00'), 'the open report is the second');
  await page.getByRole('button', { name: 'Compare', exact: true }).click();
  check(await focusIs(page, 'compare-result-heading'), 'the result takes focus');
  const result = await page.locator('.compare-result').textContent();
  check(result.includes('limits.maxSleeps'), 'settings that differ are named');
  check(result.includes('c2.example.test/next'), 'what is new in the second run is listed');

  // Opening a report from disk.
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  await page.getByRole('button', { name: 'Open a report…' }).click();
  check(await focusIs(page, 'report-heading', 5000), 'an opened report shows');
  check((await page.locator('#report-heading').textContent()).includes('cli-run.jar'), 'the opened report is the one chosen');
  check(errors.length === 0, `page errors: ${errors.join('; ')}`);
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
  await toSettings(page);
  await openAllDisclosures(page);
  await page.getByRole('button', { name: 'Add web answer' }).click();
  await page.locator('#field-network-http-0-url').fill('gate\\.example');
  await page.keyboard.press('Tab');
  await page.waitForTimeout(200);
  check((await overflow()) <= 1, `settings forms at 320 px scroll sideways by ${await overflow()} px`);
  await page.getByRole('tab', { name: /^YAML/ }).click();
  check((await overflow()) <= 1, `settings YAML at 320 px scrolls sideways by ${await overflow()} px`);
  await page.getByRole('button', { name: 'Back to the overview' }).click();
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  await page.waitForTimeout(400);
  check((await overflow()) <= 1, `running at 320 px scrolls sideways by ${await overflow()} px`);
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  await page.getByRole('button', { name: 'View the report' }).click({ timeout: 10000 });
  for (const tab of ['Summary', 'Indicators', 'Events', 'Limits and gaps', 'Files', 'Settings used']) {
    await reportTab(page, tab);
    if (tab === 'Files') {
      await page.getByRole('button', { name: 'View stage.bin' }).click();
      await page.waitForSelector('.file-view');
    }
    check((await overflow()) <= 1, `report ${tab} at 320 px scrolls sideways by ${await overflow()} px`);
  }
  await reportTab(page, 'Files');
  await page.getByRole('button', { name: 'Save as is…' }).click();
  check((await overflow()) <= 1, `the warning at 320 px scrolls sideways by ${await overflow()} px`);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Compare with another run' }).click();
  await page.getByRole('button', { name: 'Compare', exact: true }).click();
  await page.waitForSelector('#compare-result-heading');
  check((await overflow()) <= 1, `compare at 320 px scrolls sideways by ${await overflow()} px`);
  await page.getByRole('button', { name: 'Tools' }).click();
  await page.waitForSelector('#tools-panel-policy tbody tr');
  check((await overflow()) <= 1, `tools at 320 px scrolls sideways by ${await overflow()} px`);
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

// 8. Preferences, and the question before an analysis that cannot be isolated (as on Ubuntu
// 24.04 with the AppImage, which the stand-in imitates with ?isolation=unavailable).
await section('preferences and isolation', async () => {
  const { page, context, errors } = await open({}, '?isolation=unavailable');
  check(await page.getByText('The AppImage cannot turn off the network').isVisible(), 'the first-run notice says isolation is not available, and why');
  await page.getByRole('button', { name: 'Preferences' }).click();
  check(await focusIs(page, 'preferences-heading'), 'Preferences focuses its heading');
  check((await page.title()) === 'Preferences – JavaByte', `preferences title: ${await page.title()}`);
  check(await page.getByRole('checkbox', { name: 'Turn off the network for each analysis' }).isChecked(), 'isolation is on by default');
  check(await page.getByRole('checkbox', { name: 'Ask me before an analysis that cannot be isolated' }).isChecked(), 'asking is on by default');
  check((await page.locator('#prefs-isolate').getAttribute('aria-describedby')) === 'prefs-isolate-hint', 'the isolation setting is explained');
  check(await page.getByRole('button', { name: 'Choose another Java…' }).isVisible(), 'another Java can be chosen');
  await axe(page, 'preferences, isolation unavailable');

  await page.getByRole('button', { name: 'Start', exact: true }).click();
  await toOverview(page);
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  const dialog = page.getByRole('alertdialog', { name: 'This analysis will not be isolated from the network' });
  check(await dialog.isVisible(), 'Analyse asks first when the run cannot be isolated');
  check(await focusIs(page, 'isolation-cancel'), 'focus starts on Cancel, the safe choice');
  check((await dialog.getAttribute('aria-describedby')) === 'isolation-reason', 'the dialog is described by the reason');
  await axe(page, 'isolation question');
  await page.keyboard.press('Escape');
  check(!(await dialog.isVisible()), 'Escape closes the question');
  check((await page.evaluate(() => document.activeElement?.textContent)) === 'Analyse', 'focus returns to the Analyse button');
  check(await page.locator('#running-heading').isHidden(), 'nothing ran');

  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  await page.getByRole('checkbox', { name: /Don't ask me again/ }).check();
  await page.getByRole('button', { name: 'Analyse without isolation' }).click();
  check(await focusIs(page, 'running-heading', 5000), 'agreeing starts the analysis');
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  await page.getByRole('button', { name: 'Preferences' }).click();
  await page.waitForSelector('#prefs-isolate');
  check(!(await page.getByRole('checkbox', { name: 'Ask me before an analysis that cannot be isolated' }).isChecked()), "Don't ask again is kept, and shown in Preferences");
  check(errors.length === 0, `page errors: ${errors.join('; ')}`);
  await context.close();
});

// 9. Tools: the policy and the defaults, searchable, as tables screen readers can walk.
await section('tools', async () => {
  const { page, context, errors } = await open();
  await page.getByRole('button', { name: 'Tools' }).click();
  check(await focusIs(page, 'tools-heading'), 'Tools focuses its heading');
  check((await page.title()) === 'Tools – JavaByte', `tools title: ${await page.title()}`);
  await page.waitForSelector('#tools-panel-policy tbody tr');
  const allRules = await page.locator('#tools-panel-policy tbody tr').count();
  check(allRules > 100, `the whole policy is listed (${allRules} rules)`);
  check((await page.locator('#tools-policy-search-count').textContent()) === `${allRules} rows.`, 'the count says how many rules there are');
  check(await page.getByRole('table', { name: 'Policy rules, in the order JavaByte reads them' }).isVisible(), 'the policy table is named by its caption');
  check((await page.locator('#tools-panel-policy th[scope="row"]').first().textContent()) === 'java/lang/Object', 'each rule is a row header');
  await page.getByLabel('Search the policy').fill('timer');
  await page.waitForTimeout(150);
  const timerRows = page.locator('#tools-panel-policy tbody tr');
  check((await timerRows.count()) >= 1 && (await timerRows.count()) < allRules, 'searching narrows the rules');
  check((await page.locator('#tools-panel-policy tbody').textContent()).includes('Blocked'), 'a blocking rule says Blocked in words');
  check(/of \d+ rows match\./.test(await page.locator('#tools-policy-search-count').textContent()), 'the count follows the search');
  await page.getByLabel('Search the policy').fill('zzzz no such rule');
  await page.waitForTimeout(150);
  check(await page.getByText('Nothing matches.').isVisible(), 'no match is said in words, not shown as an empty table');
  await page.getByRole('tab', { name: 'Policy' }).focus();
  await page.keyboard.press('ArrowRight');
  check(await focusIs(page, 'tools-tab-defaults'), 'ArrowRight moves to the defaults tab');
  await page.waitForSelector('#tools-panel-defaults tbody tr');
  await page.getByLabel('Search the options').fill('timeoutSeconds');
  await page.waitForTimeout(150);
  const row = page.locator('#tools-panel-defaults tbody tr').first();
  check((await row.locator('th').textContent()) === 'limits.timeoutSeconds' && (await row.locator('td').first().textContent()) === '120', 'an option shows its default');
  await axe(page, 'tools, searched');
  check(errors.length === 0, `page errors: ${errors.join('; ')}`);
  await context.close();
});

// 10. Windows with the sandbox add-on (the stand-in imitates it): the choice of isolation in
// Preferences, the starting stage of Windows Sandbox, and a failed start with its offer.
await section('windows sandbox', async () => {
  const unavailable = await open({}, '?os=windows');
  await unavailable.page.getByRole('button', { name: 'Preferences' }).click();
  await unavailable.page.waitForSelector('#isolation-appcontainer');
  check(await unavailable.page.getByRole('radio', { name: /^AppContainer/ }).isChecked(), 'AppContainer is the default');
  const sandboxRadio = unavailable.page.getByRole('radio', { name: /^Windows Sandbox/ });
  check(await sandboxRadio.isDisabled(), 'Windows Sandbox cannot be chosen where it is not turned on');
  check((await sandboxRadio.getAttribute('aria-describedby')) === 'isolation-windows-sandbox-why', 'and the reason is tied to it');
  await axe(unavailable.page, 'preferences, Windows');
  await unavailable.context.close();

  const { page, context, errors } = await open({}, '?os=windows&sandbox=works');
  await page.getByRole('button', { name: 'Preferences' }).click();
  await page.waitForSelector('#isolation-windows-sandbox');
  await page.getByRole('radio', { name: /^Windows Sandbox/ }).check();
  await page.waitForTimeout(200);
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  await toOverview(page);
  await page.getByRole('button', { name: 'Analyse', exact: true }).click();
  await page.waitForSelector('.starting', { timeout: 5000 });
  check((await page.locator('.starting').textContent()).startsWith('Starting Windows Sandbox'), 'the starting stage is shown in words');
  await page.waitForFunction(() => document.querySelector('#announcer')?.textContent.startsWith('Starting Windows Sandbox'), null, { timeout: 3000 }).catch(() => {});
  check((await page.locator('#announcer').textContent()).startsWith('Starting Windows Sandbox'), 'and announced');
  await axe(page, 'running, Windows Sandbox starting');
  await page.waitForSelector('.starting', { state: 'detached', timeout: 6000 });
  check(true, 'the starting stage ends when the analysis begins');
  await page.getByRole('button', { name: 'Stop analysis' }).click();
  check(errors.length === 0, `page errors: ${errors.join('; ')}`);
  await context.close();

  const failing = await open({}, '?os=windows&sandbox=fails');
  await failing.page.getByRole('button', { name: 'Preferences' }).click();
  await failing.page.getByRole('radio', { name: /^Windows Sandbox/ }).check();
  await failing.page.waitForTimeout(200);
  await failing.page.getByRole('button', { name: 'Start', exact: true }).click();
  await toOverview(failing.page);
  await failing.page.getByRole('button', { name: 'Analyse', exact: true }).click();
  await failing.page.waitForSelector('#isolation-failed-heading', { timeout: 6000 });
  check((await failing.page.locator('#announcer').textContent()).startsWith('Windows Sandbox could not start'), 'a failed start is announced with its reason');
  check(!(await failing.page.getByRole('button', { name: 'View the report' }).isVisible()), 'no report is offered for a run that never began');
  await axe(failing.page, 'running, Windows Sandbox failed');
  await failing.page.getByRole('button', { name: 'Analyse in an AppContainer instead' }).click();
  check(await focusIs(failing.page, 'running-heading', 3000), 'choosing the AppContainer starts again and focuses the heading');
  await failing.page.waitForTimeout(2000);
  check(!(await failing.page.locator('#isolation-failed-heading').isVisible()), 'and the AppContainer run goes ahead');
  await failing.context.close();
});

// 11. Chromium's accessibility tree for the grid, as assistive technology receives it.
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

await finish();
