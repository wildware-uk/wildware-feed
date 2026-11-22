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

/** A browser of its own for one person, at a client address of its own; closed after the test. */
export async function newBrowser(browser) {
    const context = await browser.newContext({
        viewport: { width: 1280, height: 1000 },
        colorScheme: 'light',
        ignoreHTTPSErrors: local,
        extraHTTPHeaders: local ? { 'x-journey-client': someAddress() } : {},
    });
    open.add(context);
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
 * Clicks where [role] [name] is drawn: the canvas takes the click, not the tree's element. Something
 * scrolled out of its list (a zero-size box) or out of the window is scrolled to first, with the
 * wheel in small steps over the middle of the window.
 */
export async function tap(page, role, name, { button = 'left', scrollAt } = {}) {
    const target = await find(page, role, name);
    const { width, height } = page.viewportSize();
    const at = scrollAt ?? { x: width / 2, y: height / 2 };
    let box = await target.boundingBox();
    for (let i = 0; i < 20 && (!box || box.width === 0 || box.y < 0 || box.y + box.height > height); i++) {
        await page.mouse.move(at.x, at.y);
        await page.mouse.wheel(0, box && box.width > 0 && box.y < 0 ? -200 : 200);
        await page.waitForTimeout(150);
        box = await target.boundingBox();
    }
    if (!box || box.width === 0 || box.y + box.height / 2 > height) throw new Error(`${role} "${name}" is not on the screen, even after scrolling`);
    await page.mouse.click(box.x + Math.min(box.width / 2, 40), box.y + box.height / 2, { button });
    await page.waitForTimeout(250);
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
    await page.keyboard.type(text, { delay: 15 });
    await expectBox(page, name, before + text);
}

/** Replaces what the box labelled [name] holds with [text]; returns once the box shows exactly that. */
export async function replaceText(page, name, text) {
    await tap(page, 'textbox', name);
    await page.keyboard.press('Control+a');
    // A key typed straight after select-all can be lost (the select-all itself lands late).
    await page.waitForTimeout(250);
    await page.keyboard.type(text, { delay: 15 });
    await expectBox(page, name, text);
}

/** The web app at [address], drawn and settled. */
export async function openApp(page, address) {
    await page.goto(address);
    await settle(page);
}

/** A reload, settled. The accessibility tree keeps a closed dialog's contents until the page is drawn afresh. */
export async function reload(page) {
    await page.reload();
    await settle(page);
}

async function settle(page) {
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    await page.waitForTimeout(1200);
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
}

/** A tab the web app opened ([popup], from `context.waitForEvent('page')`), drawn and settled. */
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
