// The WCAG 2.2 criteria checks that axe-core does not cover, as functions run on a page as it is
// now. criteria.mjs runs them on every screen; each one has been checked against a planted fault
// (a crowded 10 px button, a hidden focus ring, a clipped box, a borderless field, a name that
// leaves out the visible words), so a pass means something.

/** Every check below, run in the page for the screen as it is now. Returns problem strings. */
export async function problemsOnScreen(page) {
  return page.evaluate(() => {
    const problems = [];
    const visible = (el) => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && !el.closest('[hidden]');
    };
    const describe = (el) => `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''} "${(el.getAttribute('aria-label') || el.textContent || el.value || '').trim().slice(0, 40)}"`;

    // 2.5.8: targets, with the spacing exception.
    const targets = [...document.querySelectorAll('button, a[href], input:not([type="hidden"]), select, textarea, [role="tab"], [role="checkbox"], [role="radio"]')]
      .filter(visible)
      // A link inside a sentence is exempt (its size is set by the line of text).
      .filter((el) => !(el.tagName === 'A' && el.closest('p, li') && el.closest('p, li').textContent.trim() !== el.textContent.trim()));
    const rects = targets.map((el) => el.getBoundingClientRect());
    const small = targets.map((el, i) => rects[i].width < 24 || rects[i].height < 24);
    const circleTouches = (cx, cy, r) => {
      const dx = Math.max(r.left - cx, 0, cx - r.right);
      const dy = Math.max(r.top - cy, 0, cy - r.bottom);
      return dx * dx + dy * dy < 12 * 12;
    };
    targets.forEach((el, i) => {
      if (!small[i]) return;
      const r = rects[i];
      const cx = (r.left + r.right) / 2;
      const cy = (r.top + r.bottom) / 2;
      // A label wrapping or naming the control is part of the same target.
      const others = targets.filter((other, j) => j !== i && !other.contains(el) && !el.contains(other));
      const crowded = others.some((other) => {
        const o = other.getBoundingClientRect();
        if (circleTouches(cx, cy, o)) return true;
        const oi = targets.indexOf(other);
        if (!small[oi]) return false;
        const ox = (o.left + o.right) / 2;
        const oy = (o.top + o.bottom) / 2;
        return Math.hypot(ox - cx, oy - cy) < 24;
      });
      if (crowded) problems.push(`2.5.8 target ${Math.round(r.width)}x${Math.round(r.height)} px and crowded: ${describe(el)}`);
    });

    // 1.4.11: fields, lists and buttons stand out from their surroundings.
    const rgb = (c) => {
      const m = c.match(/rgba?\(([^)]+)\)/);
      if (!m) return null;
      const [r, g, b, a = 1] = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
      return { r, g, b, a };
    };
    const luminance = ({ r, g, b }) => {
      const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    const contrast = (a, b) => { const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
    const backgroundBehind = (el) => {
      for (let node = el.parentElement; node; node = node.parentElement) {
        const c = rgb(getComputedStyle(node).backgroundColor);
        if (c && c.a > 0.5) return c;
      }
      return rgb(getComputedStyle(document.body).backgroundColor) ?? { r: 255, g: 255, b: 255, a: 1 };
    };
    // The visible edge of a control: its own border, or the border of a box that wraps it
    // closely (the YAML editor's text area sits in a bordered box with its line numbers).
    const edge = (el) => {
      for (let node = el, depth = 0; node && depth < 3; node = node.parentElement, depth++) {
        const s = getComputedStyle(node);
        if (parseFloat(s.borderTopWidth) > 0) return { color: rgb(s.borderTopColor), box: node };
      }
      return null;
    };
    for (const el of document.querySelectorAll('input[type="text"], input[type="search"], input[type="number"], input:not([type]), select, textarea, button')) {
      if (!visible(el)) continue;
      const s = getComputedStyle(el);
      // A button drawn as underlined text is identified by its text, which has its own contrast
      // rule (1.4.3); 1.4.11 asks for no border then.
      if (s.borderTopStyle === 'none' && rgb(s.backgroundColor)?.a === 0 && s.textDecorationLine.includes('underline')) continue;
      const around = backgroundBehind(el);
      const own = rgb(s.backgroundColor);
      const border = edge(el);
      const byBorder = border?.color && border.color.a > 0.5 ? contrast(border.color, backgroundBehind(border.box)) : 0;
      const byFill = own && own.a > 0.5 ? contrast(own, around) : 0;
      if (Math.max(byBorder, byFill) < 3) {
        problems.push(`1.4.11 boundary contrast ${Math.max(byBorder, byFill).toFixed(2)}:1: ${describe(el)}`);
      }
    }

    // 2.5.3: the accessible name contains the visible words.
    for (const el of document.querySelectorAll('button[aria-label], a[aria-label], [role="tab"][aria-label]')) {
      if (!visible(el)) continue;
      const shown = el.textContent.replace(/\s+/g, ' ').trim().toLowerCase();
      const name = el.getAttribute('aria-label').replace(/\s+/g, ' ').trim().toLowerCase();
      if (shown && !name.includes(shown)) problems.push(`2.5.3 name "${name}" does not contain the visible "${shown}"`);
    }

    // 1.3.1: one h1; no level skipped going down.
    const headings = [...document.querySelectorAll('h1, h2, h3, h4, h5, h6')].filter(visible);
    const ones = headings.filter((h) => h.tagName === 'H1').length;
    if (ones !== 1) problems.push(`1.3.1 ${ones} level-1 headings`);
    headings.reduce((previous, h) => {
      const level = Number(h.tagName[1]);
      if (level > previous + 1) problems.push(`1.3.1 heading level jumps from ${previous} to ${level}: "${h.textContent.trim().slice(0, 40)}"`);
      return level;
    }, 1);
    return problems;
  });
}

/** 2.4.7, 2.4.11 and 2.1.2: tabs through the screen, checking each focused control. */
export async function focusProblems(page) {
  const problems = [];
  await page.evaluate(() => document.activeElement?.blur());
  const seen = [];
  for (let step = 0; step < 400; step++) {
    await page.keyboard.press('Tab');
    const info = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body) return null;
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      const ring = (s.outlineStyle !== 'none' ? parseFloat(s.outlineWidth) : 0) || (s.boxShadow !== 'none' ? 2 : 0);
      const x = Math.min(Math.max(r.left + r.width / 2, 0), innerWidth - 1);
      const y = Math.min(Math.max(r.top + r.height / 2, 0), innerHeight - 1);
      const top = document.elementFromPoint(x, y);
      const covered = top && !el.contains(top) && !top.contains(el) && !(el.labels && [...el.labels].some((l) => l.contains(top)));
      return {
        key: el.id || el.outerHTML.slice(0, 80),
        name: (el.getAttribute('aria-label') || el.textContent || el.value || el.id || el.tagName).trim().slice(0, 40),
        ring,
        covered: covered ? top.tagName.toLowerCase() + (top.className ? '.' + top.className : '') : null,
      };
    });
    if (!info) continue;
    if (seen.includes(info.key)) return problems; // round the whole screen: no trap
    seen.push(info.key);
    if (info.ring < 2) problems.push(`2.4.7 focus ring ${info.ring} px on "${info.name}"`);
    if (info.covered) problems.push(`2.4.11 focused "${info.name}" is covered by ${info.covered}`);
  }
  problems.push(`2.1.2 tabbing did not come round after 400 steps (${seen.length} stops): a trap?`);
  return problems;
}

/** 1.4.12: the spacing the criterion names; then nothing clipped and no sideways scrolling. */
export async function spacingProblems(page) {
  const style = await page.addStyleTag({
    content: '* { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; } p { margin-bottom: 2em !important; }',
  });
  await page.waitForTimeout(100);
  const problems = await page.evaluate(() => {
    const out = [];
    if (document.documentElement.scrollWidth > innerWidth + 1) out.push(`1.4.12 the page scrolls sideways by ${document.documentElement.scrollWidth - innerWidth} px`);
    for (const el of document.querySelectorAll('body *')) {
      const s = getComputedStyle(el);
      if (el.closest('[hidden]') || s.display === 'none') continue;
      const clips = (s.overflowX === 'hidden' || s.overflowY === 'hidden' || s.overflow === 'clip') && !el.matches('.visually-hidden, .visually-hidden *');
      const text = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
      // Shortening a long line with "…" is a choice the grid makes at any spacing (the whole
      // text is in Details and in the table view); losing lines to the extra height is not.
      const cutWide = el.scrollWidth > el.clientWidth + 1 && s.textOverflow !== 'ellipsis';
      const cutTall = el.scrollHeight > el.clientHeight + 1;
      if (clips && text && (cutWide || cutTall)) {
        out.push(`1.4.12 text clipped in ${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}: "${el.textContent.trim().slice(0, 40)}"`);
      }
    }
    return out;
  });
  await style.evaluate((node) => node.remove());
  return problems;
}

