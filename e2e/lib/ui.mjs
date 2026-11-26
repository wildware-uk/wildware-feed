// Driving the web app. It draws on one canvas, but Compose mirrors what it draws into an
// accessibility tree (roles and labels, in a shadow root): these helpers find boxes and buttons
// there by name, click where they are drawn, and read what the screen says from it.
import { expect, test } from '@playwright/test';
import { someAddress } from '../../server/deploy/journey.mjs';
import { local } from './env.mjs';

// Every browser a test opens is closed after it, so a file's tests don't pile up copies of the app.
const open = new Set();
test.afterEach(async () => {
    await Promise.all([...open].map((context) => context.close().catch(() => {})));
    open.clear();
});

/**
 * A browser of its own for one person, at a client address of its own; closed after the test.
 * [viewport] sets its size; [tour] leaves the guided tour on, as a new customer has it.
 */
export async function newBrowser(browser, { viewport = { width: 1280, height: 1000 }, tour = false } = {}) {
    const context = await browser.newContext({
        viewport,
        colorScheme: 'light',
        ignoreHTTPSErrors: local,
        extraHTTPHeaders: local ? { 'x-journey-client': someAddress() } : {},
    });
    open.add(context);
    // The guided tour would cover the screen after a first sign-in: these tests take it as seen.
    if (!tour) await context.addInitScript(() => localStorage.setItem('feed.tour', 'off'));
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.errors = errors;
    return page;
}

/** What the screen says: every label and text in the accessibility tree, one per line. */
export const screenText = (page) => page.evaluate(() => {
    const root = [...document.querySelectorAll('*')].find((e) => e.shadowRoot)?.shadowRoot;
    const out = [];
    const walk = (n) => {
        for (const child of n.children) {
            if (child.tagName === 'STYLE') continue;
            const own = [...child.childNodes].filter((x) => x.nodeType === 3).map((x) => x.textContent).join('').trim();
            const label = child.getAttribute('aria-label');
            if (label || own) out.push(label || own);
            walk(child);
        }
    };
    if (root) walk(root);
    return out.join('\n');
}).catch(() => '');

/** Passes once the screen says [text] (a string or a RegExp). */
export async function expectShown(page, text, timeout = 15_000) {
    await expect.poll(() => screenText(page), { timeout, message: `the screen should say ${text}` })
        .toEqual(typeof text === 'string' ? expect.stringContaining(text) : expect.stringMatching(text));
}

/** Passes once the screen no longer says [text]. */
export async function expectGone(page, text, timeout = 15_000) {
    await expect.poll(async () => (await screenText(page)).includes(text), { timeout, message: `the screen should not say ${text}` }).toBe(false);
}

/** The [role] named [name] (a string matches exactly), waiting for it to be on the screen. */
export async function find(page, role, name, timeout = 15_000) {
    const target = page.getByRole(role, { name, exact: typeof name === 'string' }).first();
    await target.waitFor({ state: 'attached', timeout });
    return target;
}

/** Whether the screen has [role] [name] now (waits a moment). */
export async function has(page, role, name, timeout = 2000) {
    return find(page, role, name, timeout).then(() => true, () => false);
}

/**
 * Clicks where [role] [name] is drawn: the canvas takes the click, not the tree's element. See
 * [tapTarget].
 */
export async function tap(page, role, name, options = {}) {
    await tapTarget(page, await find(page, role, name), `${role} "${name}"`, options);
}

/**
 * Clicks where [target] (a locator in the tree) is drawn, once it has stopped moving ([stillBox]).
 * Something scrolled out of its list (a zero-size box) or out of the window is scrolled to first,
 * with the wheel in small steps over the middle of the window.
 */
export async function tapTarget(page, target, what = 'it', { button = 'left', scrollAt } = {}) {
    const { width, height } = page.viewportSize();
    const at = scrollAt ?? { x: width / 2, y: height / 2 };
    let box = await stillBox(target);
    for (let i = 0; i < 20 && (!box || box.width === 0 || box.y < 0 || box.y + box.height > height); i++) {
        await page.mouse.move(at.x, at.y);
        await page.mouse.wheel(0, box && box.width > 0 && box.y < 0 ? -200 : 200);
        box = await stillBox(target);
    }
    if (!box || box.width === 0 || box.y + box.height / 2 > height) throw new Error(`${what} is not on the screen, even after scrolling`);
    await page.mouse.click(box.x + Math.min(box.width / 2, 40), box.y + box.height / 2, { button });
    await page.waitForTimeout(250);
}

/** Resolves once the page has drawn [count] more frames (or after a second, for a page that draws none). */
export function frames(page, count = 2) {
    return page.evaluate((n) => new Promise((done) => {
        setTimeout(done, 1000);
        const tick = () => (--n <= 0 ? done() : requestAnimationFrame(tick));
        requestAnimationFrame(tick);
    }), count).catch(() => {});
}

/**
 * [locator]'s box once it has stopped moving: the same box on three reads in a row, with frames
 * drawn between them. Menus grow in from the pointer, sheets and dialogs slide in and cards move as
 * the feed fills, and the tree has the new place of a thing before it is drawn there: a click on the
 * way lands on whatever is under it (the menu row next to it, or outside a dialog, which closes it).
 * A box that stays empty (scrolled out of its list) or missing counts as still too, so a caller can
 * scroll to it. Returns the last box read if it never settles.
 */
export async function stillBox(locator, timeout = 10_000) {
    const page = locator.page();
    const read = () => locator.boundingBox({ timeout: 1000 }).catch(() => null);
    const same = (a, b) => (a === null && b === null) || (!!a && !!b && a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height);
    let last = await read();
    let still = 0;
    for (const end = Date.now() + timeout; Date.now() < end;) {
        await frames(page, 2);
        await page.waitForTimeout(80);
        const box = await read();
        still = same(box, last) ? still + 1 : 0;
        last = box;
        if (still >= 2) return box;
    }
    return last;
}

/**
 * Where the browser's keyboard focus is, through the app's shadow root: "box" when a text box has
 * the keys (Compose's hidden backing field has the focus), "app" when the canvas has them, or the
 * tag of whatever else does.
 */
export const keyFocus = (page) => page.evaluate(() => {
    let el = document.activeElement;
    while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
    if (!el || el === document.body) return 'none';
    if (el.classList?.contains('compose-backing-field')) return 'box';
    if (el.tagName === 'CANVAS') return 'app';
    return el.tagName.toLowerCase();
}).catch(() => 'none');

/** Passes once a text box has the keys, so what is typed next goes into it. */
export async function expectTyping(page, timeout = 5000) {
    await expect.poll(() => keyFocus(page), { timeout, message: 'a text box should have the keyboard' }).toBe('box');
}

/** Passes once the app has the keys (its canvas or one of its boxes), so a shortcut reaches it. */
export async function expectKeysInApp(page, timeout = 5000) {
    await expect.poll(() => keyFocus(page), { timeout, message: 'the app should have the keyboard' }).toMatch(/^(app|box)$/);
}

/** What the box labelled [name] holds now: the tree mirrors it as the box's text. */
async function boxText(page, name) {
    const box = await find(page, 'textbox', name);
    return box.evaluate((e) => e.textContent ?? '');
}

/**
 * Waits until the box labelled [name] holds exactly [want], so nothing is sent while the app is
 * still catching up with the keys (a slow machine draws them late). It never types again: a key the
 * app lost is a bug for people too, so the test fails and says what the box held instead.
 */
async function expectBox(page, name, want) {
    // A password box shows dots: then it is right when there are as many dots as characters.
    const shows = async () => {
        const text = await boxText(page, name);
        return /^[•●*]+$/.test(text) && want.length > 0 ? (text.length === want.length ? want : text) : text;
    };
    await expect.poll(shows, { timeout: 30_000, message: `the box "${name}" should hold what was typed (keys lost or never drawn)` }).toBe(want);
}

/** Types [text] into the box labelled [name], after what it holds; returns once the box shows it all. */
export async function typeInto(page, name, text) {
    const before = await boxText(page, name);
    await tap(page, 'textbox', name);
    await expectTyping(page);
    await page.keyboard.type(text, { delay: 15 });
    await expectBox(page, name, before + text);
}

/** Replaces what the box labelled [name] holds with [text]; returns once the box shows exactly that. */
export async function replaceText(page, name, text) {
    await tap(page, 'textbox', name);
    await expectTyping(page);
    await page.keyboard.press('Control+a');
    // A key typed straight after select-all can be lost (the select-all itself lands late).
    await page.waitForTimeout(250);
    await page.keyboard.type(text, { delay: 15 });
    await expectBox(page, name, text);
}

/** The web app at [address], drawn. */
export async function openApp(page, address) {
    await page.goto(address);
    await settle(page);
}

/**
 * A reload of a signed-in page, drawn and live again. The accessibility tree keeps a closed
 * dialog's contents until the page is drawn afresh.
 */
export async function reload(page) {
    await page.reload();
    await settle(page);
    await expectLive(page);
}

/** The app has taken over from the page's "Loading" and drawn its first screen into the tree. */
async function settle(page) {
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    await expect.poll(async () => (await screenText(page)).length > 0, { timeout: 30_000, message: 'the app should draw its first screen' }).toBe(true);
}

/**
 * Passes once a signed-in app is connected: its live stream is open (the header says "Live"), so
 * the feed has been read and what agents do next shows without a reload.
 */
export async function expectLive(page) {
    await expectShown(page, 'Connection: Live', 30_000);
}

/** The next response to [method] on a path ending with [path] (or matching it, a RegExp), as a promise to await after acting. */
export function nextResponse(page, path, method = 'POST', timeout = 15_000) {
    const matches = (p) => (typeof path === 'string' ? p.endsWith(path) : path.test(p));
    return page.waitForResponse((r) => r.request().method() === method && matches(new URL(r.url()).pathname), { timeout });
}

/**
 * [person]'s browser signed in to their team's web app, without the sign-in form: the device key
 * as the web app's own cookie, and the session note the web app keeps. The sign-in form itself is
 * tested in auth.spec.mjs; here it would only be in the way.
 */
export async function signedIn(browser, person) {
    const page = await newBrowser(browser);
    await signInPage(page, person);
    return page;
}

/** [page] signed in to [person]'s team as [signedIn] does it, in the browser [page] is in (a second tab, say). */
export async function signInPage(page, person) {
    const address = person.team.address;
    // From a page on the team's own address, so the browser keeps the cookie as the web app would.
    await page.goto(`${address}/api/server`);
    const answer = await page.evaluate(async ({ email, password, name }) => {
        const res = await fetch('/api/devices/sign-in', {
            method: 'POST', headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ email, password, name, cookie: true }),
        });
        return { status: res.status, body: await res.json() };
    }, { email: person.email, password: person.password, name: `E2E browser (${person.name})` });
    if (answer.status !== 201) throw new Error(`signing ${person.name} in: ${answer.status} ${JSON.stringify(answer.body)}`);
    const { device } = answer.body;
    await page.evaluate((session) => localStorage.setItem('feed.session', JSON.stringify(session)), { server: address, token: '', deviceName: device.name, deviceId: device.id });
    await openApp(page, address);
    await expectShown(page, 'All channels');
    await expectLive(page);
}

/** A tab the web app opened ([popup], from `context.waitForEvent('page')`), drawn. */
export async function settledTab(popup) {
    await popup.waitForLoadState();
    await settle(popup);
    return popup;
}

/**
 * Opens the channel [name] from the side bar and waits until the feed's heading says so. The side
 * bar can reorder just after a reload (by recent activity), so a tap that lands on the neighbour
 * is tried again.
 */
export async function openChannel(page, name) {
    const label = new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')},`);
    for (let i = 0; i < 4; i++) {
        await tap(page, 'button', label);
        const deadline = Date.now() + 3000;
        while (Date.now() < deadline) {
            if ((await screenText(page)).includes(`CHANNEL\n${name}\n`)) return;
            await page.waitForTimeout(200);
        }
    }
    throw new Error(`the channel ${name} would not open`);
}

/** A post from the composer into the open channel. */
export async function post(page, text) {
    await typeInto(page, 'Message', text);
    const answer = nextResponse(page, '/api/messages');
    await tap(page, 'button', 'Send');
    const res = await answer;
    expect(res.status(), 'the post').toBe(201);
}
