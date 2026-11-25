// Secrets in a thread: an agent attaches a secret to its post by name, and the owner opens it right
// there in the browser, without going to Settings. The value is never in the post, and the opening
// is in the secret's audit. And an agent asks for several secrets on one card: the owner fills them
// all in, checks one with Show, and saves them together. Every value is made up.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectGone, expectShown, nextResponse, screenText, signedIn, tap, typeInto } from '../lib/ui.mjs';
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

test('an agent asks for two secrets on one card and the owner saves both with Show on for one', async ({ browser }) => {
    const { enabled } = await team.owner.ok('GET', '/api/secrets');
    test.skip(!enabled, 'this server has no SECRETS_KEY');
    const keyId = 'AKIA-NOT-REAL-e2e-0001';
    const keySecret = 'not-real-e2e-secret-0002';
    await agent.call('create_project', { name: 'Keys' });

    // request_secret returns at once, pending: the answer reaches the agent later.
    const question = 'The AWS keys for the deploy, please';
    const asked = await mcp(agent, 'request_secret', {
        project: 'keys', question,
        fields: [
            { name: 'AWS_ACCESS_KEY_ID', label: 'Access key id' },
            { name: 'AWS_SECRET_ACCESS_KEY', label: 'Secret access key', hint: 'Shown once when you make the key' },
        ],
    });
    expect(asked).toMatchObject({ state: 'pending' });
    const id = asked.request_id;

    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, 'Keys');
    await expectShown(page, question);
    await expectShown(page, 'AWS_ACCESS_KEY_ID');
    await expectShown(page, 'AWS_SECRET_ACCESS_KEY');

    await typeInto(page, 'Value of AWS_ACCESS_KEY_ID, hidden as you type', keyId);
    // The first box's eye: what was typed shows until the box loses the cursor.
    await tap(page, 'button', 'Show secret');
    await expectShown(page, 'Hide secret');
    await typeInto(page, 'Value of AWS_SECRET_ACCESS_KEY, hidden as you type', keySecret);
    // Moving to the second box hid the first again: both eyes say Show.
    await expect.poll(async () => page.getByRole('button', { name: 'Show secret', exact: true }).count(), { timeout: 10_000 }).toBe(2);

    const saved = nextResponse(page, `/api/requests/${id}/secret`);
    await tap(page, 'button', /^Save all/);
    const response = await saved;
    expect(response.status(), 'saving both').toBe(200);
    expect(await response.text()).not.toContain(keySecret);
    // Answered, the card leaves the feed.
    await expectGone(page, question);

    // The agent reads the answer: the names saved, never the values.
    const settled = await mcp(agent, 'get_messages', { request_id: id });
    expect(settled.thread.card).toMatchObject({ state: 'answered', response: { kind: 'secret', value: ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY'] } });
    expect(JSON.stringify(settled)).not.toContain(keyId);
    expect(JSON.stringify(settled)).not.toContain(keySecret);
    const listed = await mcp(agent, 'list_secrets');
    expect(listed.secrets.map((s) => s.name)).toEqual(expect.arrayContaining(['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY']));
    expect(await screenText(page)).not.toContain(keySecret);
});
