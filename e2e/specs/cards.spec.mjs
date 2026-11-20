// Cards: an agent's update shows as it was posted, the owner pins it and marks it as work, and the
// blocks on it (a checklist and a poll) take the owner's tick and vote, which the agent reads back.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, find, nextResponse, reload, signedIn, tap } from '../lib/ui.mjs';
import { mcp, openChannelSurely } from '../lib/cards-helpers.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let agent;
test.beforeAll(async () => {
    team = await Team.create('cards');
    agent = await team.agent('builder');
});
test.afterAll(async () => { await team?.delete(); });

/** A fresh channel with one update in it, so the card sits at the top of the feed. */
async function channelWithUpdate(name, update) {
    await agent.call('create_project', { name });
    const slug = name.toLowerCase();
    const posted = await mcp(agent, 'post_update', { project: slug, ...update });
    return posted.update;
}

/** Opens [title]'s card menu (a right-click on the card, as on the desktop) and taps [row]. */
async function fromCardMenu(page, title, row) {
    await tap(page, 'button', `Collapse update: ${title}`, { button: 'right' });
    await tap(page, 'button', row);
}

test("an agent's update shows its title, body and level", async ({ browser }) => {
    await channelWithUpdate('Builds', { title: 'Nightly build failed', body: 'The **linker** ran out of memory.', level: 'error' });
    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, 'Builds');
    await expectShown(page, 'Nightly build failed');
    // Markdown is drawn, not shown as asterisks.
    await expectShown(page, 'The linker ran out of memory.');
    await expectShown(page, /builder, Agent, Posted [^\n]*, Error/);
});

test('the owner pins a card; the agent marks it as work and the card shows it live', async ({ browser }) => {
    const update = await channelWithUpdate('Tasks', { title: 'Rotate the signing key', body: 'Due this week.', level: 'info' });
    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, 'Tasks');
    await expectShown(page, 'Rotate the signing key');

    const pinned = nextResponse(page, `/api/cards/update/${update.id}/pin`);
    await fromCardMenu(page, 'Rotate the signing key', 'Pin');
    expect((await pinned).status(), 'pinning').toBe(200);
    await reload(page);
    await openChannelSurely(page, 'Tasks');
    await expectShown(page, /^PINNED$/m);
    await expectShown(page, /builder, Agent, Posted [^\n]*, pinned/);

    // The agent takes it on, then finishes it: the card changes without a reload.
    await mcp(agent, 'set_status', { update_id: update.id, status: 'in_progress' });
    await expectShown(page, /, status In progress/);
    const work = await mcp(agent, 'list_work', { project: 'tasks' });
    expect(JSON.stringify(work)).toContain('Rotate the signing key');
    await mcp(agent, 'set_status', { update_id: update.id, status: 'done' });
    await expectShown(page, /, status Done/);
});

// App bug: the work-state picker opens from the card menu, but the accessibility tree stays frozen
// on the closed menu, so the picker's rows can't be found (nor read by a screen reader), and Tab
// moves focus to the side bar behind it. Marked as failing until that is fixed.
test.fail('the owner marks a card as work from its menu', async ({ browser }) => {
    const update = await channelWithUpdate('Chores', { title: 'Renew the domain', body: 'Before it lapses.', level: 'info' });
    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, 'Chores');
    await expectShown(page, 'Renew the domain');
    const marked = nextResponse(page, `/api/cards/update/${update.id}/status`, 'POST', 5000);
    await fromCardMenu(page, 'Renew the domain', 'Mark as work…');
    // "Cancelled" is only in the picker.
    await expectShown(page, /^Cancelled$/m, 5000);
    await tap(page, 'button', 'In progress');
    expect((await marked).status(), 'marking as work').toBe(200);
    await expectShown(page, /, status In progress/);
});

test('the owner ticks a checklist and votes in a poll; the agent reads both back', async ({ browser }) => {
    const update = await channelWithUpdate('Launch', {
        title: 'Launch prep', body: 'Before we ship v2.', level: 'warn',
        blocks: [
            { id: 'todo', type: 'checklist', title: 'Before ship', items: [{ id: 'notes', text: 'Write the notes' }, { id: 'tag', text: 'Tag the build' }] },
            { id: 'day', type: 'poll', question: 'Ship on which day?', options: [{ id: 'mon', label: 'Monday' }, { id: 'tue', label: 'Tuesday' }] },
        ],
    });
    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, 'Launch');
    await expectShown(page, 'Before ship');
    await expectShown(page, '0 of 2');
    await expectShown(page, 'Monday, 0 votes');

    // A checklist line and a poll option are buttons in the tree (their tick isn't exposed as checked).
    const ticked = nextResponse(page, `/api/cards/update/${update.id}/blocks/todo/check`);
    await tap(page, 'button', 'Write the notes');
    expect((await ticked).status(), 'ticking').toBe(200);
    const voted = nextResponse(page, `/api/cards/update/${update.id}/blocks/day/vote`);
    await tap(page, 'button', 'Tuesday, 0 votes');
    expect((await voted).status(), 'voting').toBe(200);
    await expectShown(page, 'Write the notes, done by you');
    await expectShown(page, '1 of 2');
    await expectShown(page, 'Tuesday, 1 vote');

    const { blocks } = await mcp(agent, 'get_blocks', { update_id: update.id });
    const list = blocks.find((b) => b.id === 'todo');
    const poll = blocks.find((b) => b.id === 'day');
    expect(list.items.find((i) => i.id === 'notes')).toMatchObject({ done: true, done_by: { kind: 'person', name: 'Olive Owner' } });
    expect(list.items.find((i) => i.id === 'tag').done).toBe(false);
    expect(poll.options.find((o) => o.id === 'tue')).toMatchObject({ votes: 1, voters: [{ kind: 'person', name: 'Olive Owner' }] });
    expect(poll.total_voters).toBe(1);

    // The agent ticks the other line; the card changes in place, without a reload.
    await mcp(agent, 'update_block', { update_id: update.id, block_id: 'todo', patch: { items: [{ id: 'tag', done: true }] } });
    await expectShown(page, '2 of 2');
    await expect(find(page, 'button', /^Tag the build/)).resolves.toBeTruthy();
});
