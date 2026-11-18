// WCAG 2.2 AA criteria that axe-core does not test, checked on every screen and state of the
// browser build (the same walk as run.mjs), in the light, dark and high-contrast themes.
//
// Usage:  node criteria.mjs [path-to-ui/dist]
//
//   2.5.8  Target size: controls at least 24 by 24 CSS pixels, or spaced so a 24 px circle on
//          each touches no other control. Links inside sentences are exempt.
//   2.4.7  Focus visible, and 2.4.11 focus not obscured: tabbing through the screen, each focused
//          control has an outline or ring at least 2 px wide and is not covered by anything.
//   2.1.2  No keyboard trap: tabbing comes back round to where it started.
//   1.4.12 Text spacing: with the spacing the criterion names, no text is clipped and the page
//          does not scroll sideways.
//   1.4.11 Non-text contrast: each text field, list and button can be told from what is around
//          it, by its border or its background, at 3:1.
//   2.5.3  Label in name: a control's accessible name contains its visible words.
//   1.3.1  Headings: one level-1 heading, and no level skipped on the way down.
//
// What a person still has to judge, with a screen reader and by eye, is in
// docs/gui/PHASE6-AUDIT.md.

import { check, section, open, walkScreens, finish } from './lib.mjs';
import { problemsOnScreen, focusProblems, spacingProblems } from './checks.mjs';

for (const theme of ['light', 'dark', 'contrast']) {
  await section(`criteria, ${theme}`, async () => {
    const { page, context, errors } = await open({ colorScheme: theme === 'dark' ? 'dark' : 'light' });
    await page.evaluate((t) => localStorage.setItem('javabyte.preferences', JSON.stringify({ theme: t })), theme);
    await page.reload();
    await page.waitForSelector('#start-heading');
    await walkScreens(page, async (label) => {
      const found = [...(await problemsOnScreen(page))];
      // Focus and spacing do not depend on colour; once is enough.
      if (theme === 'light') found.push(...(await focusProblems(page)), ...(await spacingProblems(page)));
      for (const problem of new Set(found)) check(false, `[${label}, ${theme}] ${problem}`);
      check(true, `${label}, ${theme}`);
    });
    check(errors.length === 0, `page errors (${theme}): ${errors.join('; ')}`);
    await context.close();
  });
}

await finish();
