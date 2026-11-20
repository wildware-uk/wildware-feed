// People: the owner invites someone from the People sheet, they join in their own browser and see
// the team's feed, the owner makes them an admin, then removes them, which signs them out.
import { expect, test } from '@playwright/test';
import { strongPassword } from '../../server/deploy/journey.mjs';
import { Team } from '../lib/team.mjs';
import { expectShown, newBrowser, nextResponse, openApp, reload, screenText, signedIn, tap, typeInto } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let link;
// Accounts outlive teams, so each run's email is new.
const sam = { name: 'Sam Rivera', email: `sam-${Date.now()}@people.example`, password: strongPassword() };
test.beforeAll(async () => {
    team = await Team.create('people');
    await team.owner.ok('POST', '/api/projects', { name: 'Launch' });
    await team.owner.ok('POST', '/api/messages', { body: 'Welcome to Launch, everyone', project: 'launch' });
});
test.afterAll(async () => { await team?.delete(); });

/** The People sheet, from the account sheet. */
async function openPeople(page) {
    await tap(page, 'button', 'Alerts on this device');
    await tap(page, 'button', /^People/);
    await expectShown(page, 'Invite people by a link or a code');
}

test('the owner invites someone by email from the People sheet', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openPeople(page);
    await expectShown(page, 'Olive Owner');
    await tap(page, 'button', 'Invite someone');
    await typeInto(page, 'Their email', sam.email);
    const made = nextResponse(page, '/api/invites');
    await tap(page, 'button', 'Make invite');
    const res = await made;
    expect(res.status()).toBe(201);
    link = (await res.json()).link;
    expect(link).toContain(`${team.address}/join/`);
    await expectShown(page, '/join/');
});

test('they join in their own browser and see the feed', async ({ browser }) => {
    const page = await newBrowser(browser);
    await page.goto(link);
    await Promise.all([page.waitForNavigation(), page.click('text=Or join in this browser')]);
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    await expectShown(page, 'Join a team');
    await typeInto(page, 'Your name', sam.name);
    await typeInto(page, 'Email', sam.email);
    await typeInto(page, 'Choose a password', sam.password);
    const accepted = nextResponse(page, '/api/invite/accept', 'POST', 30_000);
    // Join the team is drawn under the last box; Enter there joins, as for a keyboard user.
    await tap(page, 'textbox', 'Name for this device');
    await page.keyboard.press('Enter');
    expect((await accepted).status()).toBe(201);
    await expectShown(page, 'All channels');
    await expectShown(page, 'Welcome to Launch, everyone');
    // Signed in for good: a reload keeps them in.
    await reload(page);
    await expectShown(page, 'Welcome to Launch, everyone');
    expect(await screenText(page)).not.toContain('Join a team');
    await page.context().close();
});

test('the owner makes them an admin', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openPeople(page);
    await expectShown(page, sam.name);
    // The second Change is Sam's: the owner is listed first.
    const change = page.getByRole('button', { name: 'Change' }).nth(1);
    const box = await change.boundingBox();
    await page.mouse.click(box.x + 20, box.y + box.height / 2);
    await expectShown(page, `Remove ${sam.name}`);
    const changed = nextResponse(page, /^\/api\/people\/[^/]+$/, 'PATCH');
    await tap(page, 'button', 'Admin');
    expect((await changed).status()).toBe(200);
    const people = await team.owner.ok('GET', '/api/people');
    expect(people.people.find((p) => p.name === sam.name)?.role).toBe('admin');
});

test('removing them signs their browser out at once', async ({ browser }) => {
    const samPage = await signedIn(browser, await team.signIn(sam));
    const page = await signedIn(browser, team.owner);
    await openPeople(page);
    const change = page.getByRole('button', { name: 'Change' }).nth(1);
    const box = await change.boundingBox();
    await page.mouse.click(box.x + 20, box.y + box.height / 2);
    await tap(page, 'button', `Remove ${sam.name}`);
    await expectShown(page, 'Their devices are signed out at once');
    const removed = nextResponse(page, /^\/api\/people\/[^/]+$/, 'DELETE');
    await tap(page, 'button', 'Remove');
    expect((await removed).status()).toBe(200);
    // Sam's browser loses its key: the next load is the sign-in form.
    await openApp(samPage, team.address);
    await expectShown(samPage, 'Sign in');
    const res = await samPage.evaluate(async () => (await fetch('/api/snapshot')).status);
    expect(res).toBe(401);
});
