// Teams kept apart on one server: nothing of team B's shows in team A's browser, and no key,
// cookie, token or invite of one team works at another's address.
import { expect, test } from '@playwright/test';
import { deviceSignIn } from '../../server/deploy/journey.mjs';
import { send, Team } from '../lib/team.mjs';
import { expectShown, openApp, screenText, signedIn } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let a;
let b;
test.beforeAll(async () => {
    [a, b] = await Promise.all([Team.create('isola'), Team.create('isolb')]);
    await a.owner.ok('POST', '/api/projects', { name: 'Apples' });
    await a.owner.ok('POST', '/api/messages', { body: 'Apples are ripe', project: 'apples' });
    await b.owner.ok('POST', '/api/projects', { name: 'Secret plans' });
    await b.owner.ok('POST', '/api/messages', { body: 'Team B only: the launch date', project: 'secret-plans' });
    await b.invite({ name: 'Bea Bystander' });
});
test.afterAll(async () => { await Promise.all([a?.delete(), b?.delete()]); });

test("team A's browser shows A's channels, posts and people, never B's", async ({ browser }) => {
    const bAgent = await b.agent('b-builder');
    await bAgent.call('post_update', { project: 'secret-plans', title: 'Team B build', body: 'B only.', level: 'info' });
    const page = await signedIn(browser, a.owner);
    await expectShown(page, 'Apples are ripe');
    const text = await screenText(page);
    for (const secret of ['Secret plans', 'Team B only', 'Team B build', 'Bea Bystander', 'b-builder']) {
        expect(text, `team A's screen shows ${secret}`).not.toContain(secret);
    }
});

test("a browser signed in to team A is not signed in at team B's address", async ({ browser }) => {
    const page = await signedIn(browser, a.owner);
    await openApp(page, b.address);
    await expectShown(page, 'Sign in');
    expect(await page.evaluate(async () => (await fetch('/api/snapshot')).status)).toBe(401);
});

test("no key, token or password of team B works at team A's address", async () => {
    // B's owner's device key.
    expect((await send(`${a.address}/api/snapshot`, { headers: { authorization: `Bearer ${b.owner.token}` } })).status).toBe(401);
    // B's agent token, over MCP.
    const bAgent = await b.agent('b-sneaky');
    const mcp = await send(`${a.address}/mcp`, {
        method: 'POST',
        headers: { authorization: `Bearer ${bAgent.token}`, accept: 'application/json, text/event-stream' },
        body: { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'list_projects', arguments: {} } },
    });
    expect(mcp.status).toBe(401);
    // B's owner's email and password.
    expect((await deviceSignIn(send, a.address, { email: b.owner.email, password: b.owner.password })).status).toBe(400);
    // An invite to B, used at A.
    const invite = await b.owner.ok('POST', '/api/invites', { email: null, role: 'member' });
    const peek = await send(`${a.address}/api/invite/peek`, { method: 'POST', body: { token: invite.link } });
    expect(peek.status).toBe(404);
});

test("an agent of team A can't read or post into team B", async () => {
    const aAgent = await a.agent('a-builder');
    const projects = await aAgent.call('list_projects');
    expect(projects).toContain('apples');
    expect(projects).not.toContain('secret-plans');
    // B's channel doesn't exist as far as A's agent can tell.
    await expect(aAgent.call('post_update', { project: 'secret-plans', title: 'From team A', body: 'A only.', level: 'info' })).rejects.toThrow(/no such project/);
    const bSnapshot = await b.owner.ok('GET', '/api/snapshot');
    expect(JSON.stringify(bSnapshot)).not.toContain('From team A');
});
