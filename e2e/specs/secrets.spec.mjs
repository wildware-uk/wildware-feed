// Secrets in a thread: an agent attaches a secret to its post by name, and the owner opens it right
// there in the browser, without going to Settings. The value is never in the post, and the opening
// is in the secret's audit. The value is made up.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, nextResponse, screenText, signedIn, tap } from '../lib/ui.mjs';
import { mcp, openChannelSurely } from '../lib/cards-helpers.mjs';

const value = 'sk-test-NOT-REAL-e2e-3a7f';

test.describe.configure({ mode: 'serial' });
let team;
let agent;
test.beforeAll(async () => {
    team = await Team.create('secrets');
    agent = await team.agent('keeper');
});
test.afterAll(async () => { await team?.delete(); });

test('an agent shares a secret in a thread and the owner reveals it in place', async ({ browser }) => {
    const { enabled } = await team.owner.ok('GET', '/api/secrets');
    test.skip(!enabled, 'this server has no SECRETS_KEY');
    const { secret } = await team.owner.ok('POST', '/api/secrets', { name: 'STRIPE_TEST_KEY', value, allAgents: true });

    await agent.call('create_project', { name: 'Vault' });
    const posted = await mcp(agent, 'post_update', {
        project: 'vault', title: 'Stripe is set up', body: 'The test key is attached.', secrets: ['STRIPE_TEST_KEY'],
    });
    expect(posted.secrets).toEqual(['STRIPE_TEST_KEY']);
    expect(JSON.stringify(posted)).not.toContain(value);

    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, 'Vault');
    await expectShown(page, 'Stripe is set up');
    await expectShown(page, 'STRIPE_TEST_KEY');
    expect(await screenText(page)).not.toContain(value);

    // Reveal fetches it from the card's own route, and shows it in place.
    const revealed = nextResponse(page, `/api/cards/update/${posted.update.id}/secrets/STRIPE_TEST_KEY/reveal`);
    await tap(page, 'button', 'Reveal');
    expect((await revealed).status(), 'revealing').toBe(200);
    await expectShown(page, value);
    await tap(page, 'button', 'Hide');
    await expect.poll(async () => (await screenText(page)).includes(value), { timeout: 10_000 }).toBe(false);

    // The opening is in the audit, by the owner's own browser.
    const { entries } = await team.owner.ok('GET', `/api/secrets/audit?secret=${secret.id}`);
    expect(entries.map((e) => e.action)).toEqual(['revealed', 'shared', 'created']);
    expect(entries[0].detail).toContain(`in update ${posted.update.id}`);
    expect(JSON.stringify(entries)).not.toContain(value);
});
