// Feedback: the owner sends a feature request from the browser and it goes at once; an agent's
// waits as a question, the owner edits it and sends it in the browser, and the agent hears so. Sent
// means Sentry's user feedback on a server with SENTRY_DSN; without one (the test stack), "saved".
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, nextResponse, replaceText, signedIn, tap, typeInto } from '../lib/ui.mjs';
import { mcp, openChannelSurely } from '../lib/cards-helpers.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let agent;
test.beforeAll(async () => {
    team = await Team.create('feedback');
    agent = await team.agent('scout');
});
test.afterAll(async () => { await team?.delete(); });

/** What the Send button says: "Send to <recipient>", or "Save it" where feedback goes nowhere. */
async function sendLabel() {
    const { recipient, sends } = await team.owner.ok('GET', '/api/feedback');
    return { label: sends ? `Send to ${recipient}` : 'Save it', done: sends ? 'sent' : 'saved' };
}

test('the owner sends a feature request from the browser, and it goes at once', async ({ browser }) => {
    const { label, done } = await sendLabel();
    const page = await signedIn(browser, team.owner);
    await tap(page, 'button', 'Alerts on this device');
    await tap(page, 'button', 'Feedback');
    await tap(page, 'button', 'Send feedback');
    await tap(page, 'button', "A feature you'd like");
    await tap(page, 'button', 'Voice');
    await typeInto(page, 'Title, in a line', 'Voice notes in threads');
    await typeInto(page, 'Details: what you tried, what happened, what would help', 'I would like to reply by voice.');
    const sent = nextResponse(page, '/api/feedback');
    await tap(page, 'button', label);
    expect((await sent).status(), 'sending').toBe(201);
    // The team's list shows it, sent (or saved).
    await expectShown(page, 'Voice notes in threads');
    await expectShown(page, done === 'sent' ? 'Sent' : 'Saved');

    const { feedback } = await team.owner.ok('GET', '/api/feedback');
    expect(feedback[0]).toMatchObject({ kind: 'feature_request', area: 'voice', title: 'Voice notes in threads', status: done, author: { kind: 'person', name: 'Olive Owner' } });
});

test("an agent's feedback waits for the owner, who edits it and sends it in the browser", async ({ browser }) => {
    await agent.call('create_project', { name: 'Ideas' });
    const proposed = await mcp(agent, 'send_feedback', {
        kind: 'feedback', area: 'agents', title: 'get_messages pages slowly', body: 'Paging takes three calls.', channel: 'ideas',
    });
    expect(proposed).toMatchObject({ state: 'pending', feedback: { status: 'pending' } });
    const { label, done } = await sendLabel();
    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, 'Ideas');
    await expectShown(page, 'get_messages pages slowly', 20_000);
    await replaceText(page, 'What gets sent', 'Paging takes three calls; one would do.');
    const answered = nextResponse(page, `/api/requests/${proposed.request_id}/answer`);
    await tap(page, 'button', label);
    expect((await answered).status(), 'sending it').toBe(200);

    // The agent hears it went, with the owner's words, and its list says so.
    const awaited = await mcp(agent, 'await_request', { request_id: proposed.request_id });
    expect(awaited).toMatchObject({ state: 'answered', response: { value: { action: label, text: 'Paging takes three calls; one would do.' } } });
    const mine = await mcp(agent, 'list_feedback', { mine: true });
    expect(mine.feedback[0]).toMatchObject({ title: 'get_messages pages slowly', status: done, body: 'Paging takes three calls; one would do.' });
});
