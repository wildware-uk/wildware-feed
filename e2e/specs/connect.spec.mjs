// Connect an agent: the owner copies the prompt, an agent follows it to ask to join this team's
// dashboard, and the owner allows it. The prompt names the team's own address and nothing secret.
import { expect, test } from '@playwright/test';
import { send, Team } from '../lib/team.mjs';
import { expectShown, nextResponse, signedIn, tap } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
test.beforeAll(async () => { team = await Team.create('connect'); });
test.afterAll(async () => { await team?.delete(); });

test('the copied prompt brings an agent to this dashboard, and Allow lets it in', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: team.address });

    await tap(page, 'button', 'Connect an agent');
    await expectShown(page, 'Copy prompt');
    await tap(page, 'button', 'Copy prompt');
    await expectShown(page, 'Copied');
    let prompt = '';
    await expect.poll(async () => (prompt = await page.evaluate(() => navigator.clipboard.readText()).catch(() => '')), {
        message: 'the prompt is on the clipboard',
    }).toContain('Wildware Feed');

    // This team's own address, and nothing that would let anyone in without the owner.
    expect(prompt).toContain(`dashboard_url=${team.address}\n`);
    expect(prompt).toContain(`${team.address}/mcp`);
    for (const secret of [team.owner.password, team.owner.token]) expect(prompt).not.toContain(secret);
    expect(prompt).not.toMatch(/bearer|authorization/i);

    // An agent does what the prompt says: ask to join at the address it gives.
    const url = prompt.match(/pair start --url (\S+)/)?.[1];
    expect(url).toBe(team.address);
    const asked = await send(`${url}/api/pair`, { method: 'POST', body: { name: 'copied-prompt-agent' } });
    expect(asked.status, 'asking to pair').toBe(201);
    const pairing = asked.json;

    await expectShown(page, 'copied-prompt-agent wants to join');
    const allowed = nextResponse(page, /\/api\/pairings\/[^/]+\/approve$/);
    await tap(page, 'button', 'Allow');
    expect((await allowed).status()).toBe(200);
    let decided;
    for (let i = 0; i < 4 && decided?.state !== 'approved'; i++) {
        const res = await send(pairing.poll_url, { headers: { authorization: `Bearer ${pairing.poll_secret}` } });
        expect(res.status).toBe(200);
        decided = res.json;
    }
    expect(decided.state).toBe('approved');
    expect(decided.token).toBeTruthy();
});
