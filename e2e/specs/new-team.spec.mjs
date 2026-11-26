// A team made a minute ago, opened in the browser for the first time, as a new customer opens it:
// no channels, no agents, nothing posted, and the guided tour on. It must draw the feed and the
// tour, on a desktop and on a phone, not a blank grey screen: whether the customer came by "Open"
// from the sign-up page or signed in with the form. A browser that can't draw the app (no WebGL 2:
// graphics acceleration off, or a remote desktop with no graphics card) says so.
import { chromium, expect, test } from '@playwright/test';
import { someAddress } from '../../server/deploy/journey.mjs';
import { env } from '../lib/env.mjs';
import { adminCall, Team } from '../lib/team.mjs';
import { expectShown, has, newBrowser, nextResponse, openApp, typeInto } from '../lib/ui.mjs';

let team;
test.beforeAll(async () => { team = await Team.create('newteam'); });
test.afterAll(async () => { await team?.delete(); });

const sizes = [['desktop', { width: 1280, height: 800 }], ['phone', { width: 390, height: 844 }]];

/** The feed and the tour are drawn, and nothing went wrong on the way. */
async function expectOpened(page) {
    await expectShown(page, 'Skip tour', 30_000);
    await expectShown(page, 'Nothing here yet');
    expect(page.errors).toEqual([]);
}

for (const [size, viewport] of sizes) {
    test(`a brand-new team opens from "Open" with the tour on a ${size}`, async ({ browser }) => {
        const page = await newBrowser(browser, { viewport, tour: true });
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
        await expectOpened(page);
    });

    test(`a brand-new team opens from the sign-in form with the tour on a ${size}`, async ({ browser }) => {
        const page = await newBrowser(browser, { viewport, tour: true });
        await openApp(page, team.address);
        if (await has(page, 'textbox', 'Email')) await typeInto(page, 'Email', team.owner.email);
        const box = (await has(page, 'textbox', 'Password')) ? 'Password' : 'Owner password';
        await typeInto(page, box, team.owner.password);
        const answer = nextResponse(page, '/api/devices/sign-in', 'POST', 30_000);
        await page.keyboard.press('Enter');
        expect((await answer).status()).toBe(201);
        await expectOpened(page);
    });
}

test('a browser without WebGL 2 says why the app can\'t draw, not a blank screen', async () => {
    // A browser of its own, started without WebGL 2, as a machine with no graphics card runs Chrome.
    const args = ['--disable-webgl2'];
    if (env.connect) {
        const domain = new URL(env.origin).hostname;
        args.push(`--host-resolver-rules=MAP *.${domain} ${env.connect},MAP ${domain} ${env.connect}`, '--ignore-certificate-errors');
    }
    const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args });
    try {
        const context = await browser.newContext({
            viewport: { width: 1280, height: 800 },
            ignoreHTTPSErrors: !!env.connect,
            extraHTTPHeaders: env.connect ? { 'x-journey-client': someAddress() } : {},
        });
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', (e) => errors.push(e.message));
        await page.goto(team.address);
        const message = page.locator('#cannot-draw');
        await expect(message).toContainText("This browser can't draw Wildware Feed", { timeout: 30_000 });
        await expect(message).toContainText('hardware acceleration (WebGL)');
        await expect(message.getByRole('link', { name: 'iPhone' })).toHaveAttribute('href', 'https://apps.apple.com/app/id6836368769');
        await expect(message.getByRole('link', { name: 'Mac, Windows or Linux' })).toHaveAttribute('href', 'https://github.com/wildware-uk/wildware-feed/releases/latest');
        expect(errors).toEqual([]);
    } finally {
        await browser.close();
    }
});
