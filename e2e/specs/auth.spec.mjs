// Signing up and in, in the browser, the way a new customer does: the setup link from the control
// plane, "Open", the web app's own sign-in form (password alone, email and password, a wrong one),
// signing out; and the home team at its own address.
//
// Three bugs found 2026-11-20 fail tests here until they are fixed; each failure says which.
import { expect, test } from '@playwright/test';
import { strongPassword } from '../../server/deploy/journey.mjs';
import { env } from '../lib/env.mjs';
import { adminCall, Team } from '../lib/team.mjs';
import { expectShown, has, newBrowser, nextResponse, openApp, openChannel, post, reload, tap, typeInto } from '../lib/ui.mjs';

const BUG_FOUNDER = "known bug: a hosted team's owner can't sign in with the password alone (People.signInFounder)";
const BUG_EMAIL_BOX = 'known bug: the web sign-in form has no Email box (App.kt fixes the web host check to the owner password)';
const BUG_OPEN = 'known bug: the web app ignores the cookie "Open" sets, and shows the sign-in form';

// Each test stands alone, so one run shows every known bug, not only the first.
let team;
let password;
test.beforeAll(async () => {
    team = await Team.create('auth');
    password = team.owner.password;
    await team.owner.ok('POST', '/api/projects', { name: 'Launch' });
});
test.afterAll(async () => { await team?.delete(); });

const signedInCookie = async (page) => (await page.context().cookies()).some((c) => c.name.endsWith('feed_device'));

/** The sign-in form: the email (when given) in the Email box, the password, Enter; the sign-in's answer. */
async function signInByForm(page, { email, password: typed }) {
    if (email) await typeInto(page, 'Email', email);
    const box = (await has(page, 'textbox', 'Password')) ? 'Password' : 'Owner password';
    await typeInto(page, box, typed);
    const answer = nextResponse(page, '/api/devices/sign-in', 'POST', 30_000);
    await page.keyboard.press('Enter');
    return answer;
}

test('the setup link sets the owner password', async ({ browser }) => {
    const fresh = await Team.create('setup', { setUp: false });
    try {
        const page = await newBrowser(browser);
        const chosen = strongPassword();
        await page.goto(fresh.setupUrl);
        await page.fill('input[name=password]', chosen);
        await page.fill('input[name=again]', chosen);
        await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);
        await expect(page.locator('h1')).toHaveText('Your password is set');
        // A setup link works once.
        await page.goto(fresh.setupUrl);
        await expect(page.locator('h1')).toHaveText("This link doesn't work");
        // And the password is the one that works.
        await fresh.signIn({ email: fresh.ownerEmail, password: chosen, name: 'Olive Owner' });
    } finally {
        await fresh.delete();
    }
});

test('"Open" from the control plane signs the browser in', async ({ browser }) => {
    const page = await newBrowser(browser);
    const ticket = await adminCall('POST', `/admin/teams/${team.id}/sign-in-tickets`, { email: team.owner.email });
    expect(ticket.status).toBe(201);
    // The control plane's page, standing in: it posts the one-time ticket to the team's address.
    await page.route(`${env.controlPlane}/e2e-open`, (route) => route.fulfill({
        contentType: 'text/html',
        body: `<!doctype html><form method="post" action="${team.address}/auth/ticket"><input type="hidden" name="ticket" value="${ticket.json.ticket}"></form><script>document.forms[0].submit()</script>`,
    }));
    await page.goto(`${env.controlPlane}/e2e-open`);
    await page.waitForURL(`${team.address}/`);
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    expect(await signedInCookie(page), 'the ticket set the sign-in cookie').toBe(true);
    await expect.poll(() => has(page, 'button', 'Sign out'), { message: BUG_OPEN, timeout: 15_000 }).toBe(true);
});

test('the sign-in form offers an Email box', async ({ browser }) => {
    const page = await newBrowser(browser);
    await openApp(page, team.address);
    await expectShown(page, 'Sign in');
    expect(await has(page, 'textbox', 'Email', 5000), BUG_EMAIL_BOX).toBe(true);
});

test('the owner signs in with the password alone', async ({ browser }) => {
    const page = await newBrowser(browser);
    await openApp(page, team.address);
    const res = await signInByForm(page, { password });
    expect(res.status(), BUG_FOUNDER).toBe(201);
    await expectShown(page, 'All channels');
});

test('the owner signs in with email and password, posts, and signs out', async ({ browser }) => {
    const page = await newBrowser(browser);
    await openApp(page, team.address);
    expect(await has(page, 'textbox', 'Email', 5000), BUG_EMAIL_BOX).toBe(true);
    const res = await signInByForm(page, { email: team.owner.email, password });
    expect(res.status()).toBe(201);
    await expectShown(page, 'All channels');
    await openChannel(page, 'Launch');
    await post(page, 'Signed in with my email');
    await reload(page);
    await expectShown(page, 'Signed in with my email');
    const out = nextResponse(page, '/api/devices/sign-out');
    await tap(page, 'button', 'Sign out');
    expect((await out).status()).toBe(200);
    await expectShown(page, 'Sign in');
    expect(await signedInCookie(page)).toBe(false);
});

test('a wrong password is refused, and the form says so', async ({ browser }) => {
    const page = await newBrowser(browser);
    await openApp(page, team.address);
    const res = await signInByForm(page, { password: `${password}x` });
    expect(res.status()).toBe(400);
    await expectShown(page, "That password isn't right");
    expect(await signedInCookie(page)).toBe(false);
});

test('the home team signs in with its owner password, at its own address', async ({ browser }) => {
    test.skip(!env.homeOrigin || !env.homePassword, 'no home team given (E2E_HOME_ORIGIN, E2E_HOME_PASSWORD)');
    const page = await newBrowser(browser);
    await openApp(page, env.homeOrigin);
    const res = await signInByForm(page, { password: env.homePassword });
    expect(res.status()).toBe(201);
    await expectShown(page, 'All channels');
    const out = nextResponse(page, '/api/devices/sign-out');
    await tap(page, 'button', 'Sign out');
    expect((await out).status()).toBe(200);
});
