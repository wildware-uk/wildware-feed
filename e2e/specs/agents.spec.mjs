// Agents: one asks to join, the owner allows it in the browser, it collects its token and posts,
// and the post shows up live. Another is denied. A removed agent's token stops working.
import { expect, test } from '@playwright/test';
import { Agent, send, Team } from '../lib/team.mjs';
import { expectGone, expectShown, nextResponse, signedIn, tap } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
test.beforeAll(async () => {
    team = await Team.create('agents');
    await team.owner.ok('POST', '/api/projects', { name: 'Builds' });
});
test.afterAll(async () => { await team?.delete(); });

/** An agent asking to join, as a real one does over plain HTTP; its pairing. */
async function ask(name) {
    const res = await send(`${team.address}/api/pair`, { method: 'POST', body: { name } });
    expect(res.status, 'asking to pair').toBe(201);
    return res.json;
}

/** The agent's poll, until it is decided. */
async function poll(pairing) {
    for (let i = 0; i < 4; i++) {
        const res = await send(pairing.poll_url, { headers: { authorization: `Bearer ${pairing.poll_secret}` } });
        expect(res.status).toBe(200);
        if (res.json.state !== 'pending') return res.json;
    }
    throw new Error('the pairing was never decided');
}

test('the owner allows an agent; it posts, and the post shows live', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    const pairing = await ask('laptop-builder');
    await expectShown(page, 'laptop-builder wants to join');
    // The code the agent was told, as the app spaces it out.
    await expectShown(page, pairing.code.replace(/-/g, '').split('').join(' ').replace(/^(\S \S \S) /, '$1 - '));
    const allowed = nextResponse(page, /\/api\/pairings\/[^/]+\/approve$/);
    await tap(page, 'button', 'Allow');
    expect((await allowed).status()).toBe(200);
    const decided = await poll(pairing);
    expect(decided.state).toBe('approved');
    expect(decided.token).toBeTruthy();
    const agent = new Agent(team, { name: 'laptop-builder', token: decided.token });
    await agent.call('post_update', { project: 'builds', title: 'Nightly build passed', body: 'Took 4 minutes.', level: 'success' });
    await expectShown(page, 'Nightly build passed');
    await expectShown(page, 'laptop-builder');
});

test('the owner denies an agent; it gets no token', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    const pairing = await ask('stranger-bot');
    await expectShown(page, 'stranger-bot wants to join');
    const denied = nextResponse(page, /\/api\/pairings\/[^/]+\/deny$/);
    await tap(page, 'button', 'Deny');
    expect((await denied).status()).toBe(200);
    const decided = await poll(pairing);
    expect(decided.state).toBe('denied');
    expect(decided.token).toBeFalsy();
    await expectGone(page, 'stranger-bot wants to join');
});

test("a removed agent's token stops working", async () => {
    const agent = await team.agent('short-lived');
    await agent.call('list_projects');
    const { agents } = await team.owner.ok('GET', '/api/agents');
    const id = agents.find((a) => a.name === 'short-lived').id;
    await team.owner.ok('DELETE', `/api/agents/${id}`);
    const res = await send(`${team.address}/mcp`, {
        method: 'POST',
        headers: { authorization: `Bearer ${agent.token}`, accept: 'application/json, text/event-stream' },
        body: { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'list_projects', arguments: {} } },
    });
    expect(res.status).toBe(401);
});
