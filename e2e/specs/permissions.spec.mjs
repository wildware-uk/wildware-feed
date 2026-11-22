// Permissions (server spec/14): the owner makes a channel private from its Permissions panel, a
// member stops seeing it anywhere, the owner gives them Read, and they can read it but not post.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, nextResponse, openChannel, reload, screenText, signedIn, tap } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let sam;
test.beforeAll(async () => {
    team = await Team.create('perms');
    await team.owner.ok('POST', '/api/projects', { name: 'Launch' });
    await team.owner.ok('POST', '/api/projects', { name: 'Vault' });
    await team.owner.ok('POST', '/api/messages', { body: 'Launch is for everyone', project: 'launch' });
    await team.owner.ok('POST', '/api/messages', { body: 'The vault plan, for few eyes', project: 'vault' });
    sam = await team.invite({ name: 'Sam Member' });
});
test.afterAll(async () => { await team?.delete(); });

/** The open channel's Permissions panel, from its settings. */
async function openPermissions(page, channel) {
    await openChannel(page, channel);
    await tap(page, 'button', 'Channel settings');
    await tap(page, 'button', 'Permissions…');
    await expectShown(page, 'Only people and agents listed can see this');
}

test('a member sees every channel before anything is set', async ({ browser }) => {
    const page = await signedIn(browser, sam);
    await expectShown(page, 'The vault plan, for few eyes');
    await expectShown(page, 'Launch is for everyone');
    await page.context().close();
});

test('the owner makes the channel private from its Permissions panel', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openPermissions(page, 'Vault');
    await expectShown(page, 'Sam Member');
    const saved = nextResponse(page, /^\/api\/projects\/[^/]+\/access$/, 'PUT');
    await tap(page, 'button', 'Only people and agents listed can see this');
    expect((await saved).status()).toBe(200);
    const panel = await team.owner.ok('GET', '/api/projects/vault/access');
    expect(panel.private).toBe(true);
    await page.context().close();
});

test('the member no longer sees it: not in the list, the feed or the API', async ({ browser }) => {
    const page = await signedIn(browser, sam);
    await expectShown(page, 'Launch is for everyone');
    const text = await screenText(page);
    expect(text).not.toContain('Vault');
    expect(text).not.toContain('The vault plan');
    const snapshot = await sam.ok('GET', '/api/snapshot');
    expect(snapshot.projects.map((p) => p.slug)).toEqual(['launch']);
    expect((await sam.api('GET', '/api/projects/vault/access')).status).toBe(404);
    expect((await sam.api('POST', '/api/messages', { body: 'let me in', project: 'vault' })).status).toBe(404);
    await page.context().close();
});

test('the owner gives the member Read, and they can read it but not post', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openPermissions(page, 'Vault');
    // The owner's own row can't be changed, so the first level choices are Sam's. (The web app's
    // accessibility tree names tick boxes and chips as buttons, as the settings spec taps them.)
    const saved = nextResponse(page, /^\/api\/projects\/[^/]+\/access\/member\/[^/]+$/, 'PUT');
    await tap(page, 'button', 'Read');
    expect((await saved).status()).toBe(200);
    await expectShown(page, 'Read · Set here');
    await page.context().close();

    const samPage = await signedIn(browser, sam);
    await reload(samPage);
    await expectShown(samPage, 'The vault plan, for few eyes');
    expect((await sam.api('POST', '/api/messages', { body: 'may I?', project: 'vault' })).status).toBe(403);
    const log = await team.owner.ok('GET', '/api/access/audit');
    expect(log.entries.map((e) => `${e.change}:${e.to}`)).toEqual(['level:read', 'private:true']);
});
