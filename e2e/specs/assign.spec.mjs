// Assigning work: the owner opens a board card's menu, picks Assign…, ticks an agent and themself
// and saves. The card shows who is on it, "Assigned to me" keeps only that card, and the agent finds
// it with list_work assigned_to "me" and hears the line in the thread, though it follows nothing.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectGone, expectShown, find, has, nextResponse, openChannel, signedIn, tap } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let agent;
test.beforeAll(async () => {
    team = await Team.create('assign');
    agent = await team.agent('builder');
});
test.afterAll(async () => { await team?.delete(); });

/** The first of [roles] the tree names [name] with: a chip or toggle may be a button, tab or checkbox. */
async function roleOf(page, roles, name) {
    for (const role of roles) if (await has(page, role, name)) return role;
    return roles[0];
}

test('the owner assigns an agent from the board and filters to their own work', async ({ browser }) => {
    // The owner's channel, which the agent never follows.
    await team.owner.ok('POST', '/api/projects', { name: 'Release', slug: 'release' });
    const ids = {};
    for (const body of ['Unassigned card', 'Ship the login fix']) {
        const made = await team.owner.ok('POST', '/api/messages', { project: 'release', body });
        ids[body] = made.message.id;
        await team.owner.ok('POST', `/api/cards/message/${made.message.id}/status`, { status: 'todo' });
    }
    const login = ids['Ship the login fix'];

    const page = await signedIn(browser, team.owner);
    await openChannel(page, 'Release');
    await tap(page, await roleOf(page, ['tab', 'button'], 'Board'), 'Board');
    await expectShown(page, 'Ship the login fix');
    await expectShown(page, 'Unassigned card');

    // The newest card is on top: its menu, then Assign….
    await tap(page, 'button', 'Card options');
    await tap(page, 'button', 'Assign…');
    await find(page, 'textbox', 'Search people and agents');
    // Each row is spoken as what a press does: "Assign builder", then "Unassign builder".
    await find(page, await roleOf(page, ['button', 'checkbox'], 'Assign builder'), 'Assign builder');
    await tap(page, await roleOf(page, ['button', 'checkbox'], 'Assign builder'), 'Assign builder');
    await find(page, await roleOf(page, ['button', 'checkbox'], 'Unassign builder'), 'Unassign builder');
    await tap(page, await roleOf(page, ['button', 'checkbox'], 'Assign Olive Owner'), 'Assign Olive Owner');
    const saved = nextResponse(page, `/api/cards/message/${login}/assignees`);
    await tap(page, 'button', 'Save');
    expect((await saved).status(), 'saving the assignees').toBe(200);

    // The board says who is on it, to the eye and the screen reader.
    const read = await team.owner.ok('GET', '/api/board?channel=release');
    const card = read.columns.flatMap((c) => c.cards).find((c) => c.card.id === login);
    expect(card.assignees.map((a) => a.name)).toEqual(['builder', 'Olive Owner']);
    await find(page, await roleOf(page, ['button', 'group'], /assigned to builder, Olive Owner/), /assigned to builder, Olive Owner/);

    // Assigned to me: only the login fix.
    const filtered = page.waitForResponse((r) => new URL(r.url()).pathname.endsWith('/api/board') && r.url().includes('assigned=me'), { timeout: 15_000 });
    await tap(page, await roleOf(page, ['button', 'tab', 'checkbox', 'radio'], 'Assigned to me'), 'Assigned to me');
    expect((await filtered).status(), 'reading my work').toBe(200);
    await expectGone(page, 'Unassigned card');
    await expectShown(page, 'Ship the login fix');

    // The agent: it finds the card, and the line in the thread told it how to take it.
    expect(await agent.call('list_work', { assigned_to: 'me' })).toContain('Ship the login fix');
    expect(await agent.call('list_work', {})).not.toContain('Ship the login fix');
    const heard = await agent.call('get_messages', { mark_read: true });
    expect(heard).toContain('you were assigned: Ship the login fix');
    expect(heard).toContain('set_status in_progress');
});
