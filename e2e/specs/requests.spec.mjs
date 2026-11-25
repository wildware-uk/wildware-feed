// Requests: an agent asks with request_input, the owner answers in the browser, and the agent
// reads the answer with get_messages({request_id}). "Waiting on you" counts what is still pending.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectGone, expectShown, nextResponse, signedIn, tap, typeInto } from '../lib/ui.mjs';
import { mcp, openChannelSurely } from '../lib/cards-helpers.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let agent;
test.beforeAll(async () => {
    team = await Team.create('requests');
    agent = await team.agent('builder');
});
test.afterAll(async () => { await team?.delete(); });

/** request_input as the agent. It returns at once, pending: the answer reaches the agent later. */
async function ask(args) {
    const asked = await mcp(agent, 'request_input', args);
    expect(asked).toMatchObject({ state: 'pending' });
    return { id: asked.request_id };
}

/** The question's card as the agent reads it with get_messages({request_id}): its state and answer. */
async function heard(id) {
    return (await mcp(agent, 'get_messages', { request_id: id })).thread.card;
}

test('a choice: the owner clicks an option, and the agent gets it', async ({ browser }) => {
    await agent.call('create_project', { name: 'Deploys' });
    const page = await signedIn(browser, team.owner);
    await expectShown(page, 'Waiting on you, nothing');

    const { id } = await ask({ project: 'deploys', kind: 'choice', question: 'Which region goes first?', options: ['eu-west', 'us-east'] });
    // Live, without a reload: the count, the channel's note, and the question itself.
    await expectShown(page, 'Waiting on you, 1 question');
    await expectShown(page, 'Deploys, 0 unread, 1 question waiting on you');
    await openChannelSurely(page, 'Deploys');
    await expectShown(page, 'Which region goes first?');

    await tap(page, 'button', /^us-east/);
    const answered = nextResponse(page, `/api/requests/${id}/answer`);
    // A pick is sent with the question's own button, which counts it.
    await tap(page, 'button', /^Send 1 pick/);
    expect((await answered).status(), 'the answer').toBe(200);

    expect(await heard(id)).toMatchObject({ state: 'answered', response: { kind: 'choice', value: 'us-east' } });

    await expectShown(page, 'Waiting on you, nothing');
});

test('a form and a text question: the owner types the answers, and the agent gets them', async ({ browser }) => {
    await agent.call('create_project', { name: 'Notes' });
    const page = await signedIn(browser, team.owner);

    const form = await ask({ project: 'notes', kind: 'form', question: 'Any release notes?', options: ['Publish', 'Skip'], placeholder: 'Notes for the changelog' });
    const text = await ask({ project: 'notes', kind: 'text', question: 'What should the tag be called?', placeholder: 'A tag name' });
    await expectShown(page, 'Waiting on you, 2 questions');
    await openChannelSurely(page, 'Notes');
    await expectShown(page, 'Any release notes?');
    await expectShown(page, 'What should the tag be called?');

    // The form: words in its box, then its "Publish" action.
    await typeInto(page, 'Your answer', 'Faster sync, fewer crashes.');
    const formAnswered = nextResponse(page, `/api/requests/${form.id}/answer`);
    await tap(page, 'button', 'Publish');
    expect((await formAnswered).status(), 'the form answer').toBe(200);
    expect(await heard(form.id)).toMatchObject({ state: 'answered', response: { value: { action: 'Publish', text: 'Faster sync, fewer crashes.' } } });
    await expectShown(page, 'Waiting on you, 1 question');

    // The text question is the only one left: its box and the arrow at its end.
    await expectGone(page, 'Any release notes?');
    await typeInto(page, 'Your answer', 'v2.0.0');
    const textAnswered = nextResponse(page, `/api/requests/${text.id}/answer`);
    await tap(page, 'button', 'Send answer');
    expect((await textAnswered).status(), 'the text answer').toBe(200);
    expect(await heard(text.id)).toMatchObject({ state: 'answered', response: { kind: 'text', value: 'v2.0.0' } });

    await expectShown(page, 'Waiting on you, nothing');
});
