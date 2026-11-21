// Two teams from one browser. The web app is served by one team's own address and signs in with
// that address's cookie, so it keeps one team per tab: "Add a team" opens the other team's address
// in a new tab, where it signs in on its own, and the switcher offers that tab's team again.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, find, reload, screenText, settledTab, signInPage, signedIn, tap, typeInto } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let a;
let b;
test.beforeAll(async () => {
    [a, b] = await Promise.all([Team.create('teamsa'), Team.create('teamsb')]);
    await a.owner.ok('POST', '/api/projects', { name: 'Apples' });
    await b.owner.ok('POST', '/api/projects', { name: 'Bananas' });
});
test.afterAll(async () => { await Promise.all([a?.delete(), b?.delete()]); });

const host = (team) => new URL(team.address).host;

/** Opens the team switcher at the top of the sidebar. */
async function openSwitcher(page) {
    await tap(page, 'button', /^Team /);
    await find(page, 'button', /^Add a team/);
}

test('"Add a team" opens the other team\'s address in a new tab, which signs in on its own', async ({ browser }) => {
    const page = await signedIn(browser, a.owner);
    await expectShown(page, 'Apples');
    await openSwitcher(page);
    await tap(page, 'button', /^Add a team/);
    await typeInto(page, 'Team address', b.address);
    const opened = page.context().waitForEvent('page');
    await tap(page, 'button', 'Open in a new tab');
    const tab = await settledTab(await opened);
    expect(new URL(tab.url()).origin).toBe(new URL(b.address).origin);
    // A new address has no cookie yet: team B asks this tab to sign in, and team A's tab stays A's.
    await expectShown(tab, 'Sign in');
    expect(await screenText(tab)).not.toContain('Apples');
    // (The accessibility tree keeps a closed dialog until the page is drawn afresh.)
    await reload(page);
    await expectShown(page, 'Apples');
    // The switcher in A's tab now offers B, as a tab of its own.
    await openSwitcher(page);
    await expectShown(page, host(b));
});

test('signed in to both, picking the other team in the switcher opens its own feed in a new tab', async ({ browser }) => {
    const page = await signedIn(browser, a.owner);
    // The same browser, signed in at B's address too (a cookie of B's own).
    const other = await page.context().newPage();
    await signInPage(other, b.owner);
    await expectShown(other, 'Bananas');
    await other.close();
    // A's tab remembers B once it has opened it.
    await page.evaluate((address) => {
        const kept = JSON.parse(localStorage.getItem('feed.teams') || '{}');
        kept.links = [...(kept.links || []), { server: address, name: new URL(address).host }];
        localStorage.setItem('feed.teams', JSON.stringify(kept));
    }, b.address);
    await reload(page);
    await openSwitcher(page);
    const opened = page.context().waitForEvent('page');
    await tap(page, 'button', new RegExp(`^${host(b).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}, opens in a new tab`));
    const tab = await settledTab(await opened);
    expect(new URL(tab.url()).origin).toBe(new URL(b.address).origin);
    await expectShown(tab, 'Bananas');
    expect(await screenText(tab)).not.toContain('Apples');
    // And A's own tab never left A.
    await expectShown(page, 'Apples');
    expect(await screenText(page)).not.toContain('Bananas');
});
