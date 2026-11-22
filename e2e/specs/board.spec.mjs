// The board: the owner marks two posts as work, opens the Board for their channel, drags one card to
// Done (the same work-state change an agent hears), then takes it off the board. It stays a post in
// the feed; only the board stops showing it, for everyone in the team.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectGone, expectShown, find, nextResponse, openChannel, signedIn, tap } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let agent;
test.beforeAll(async () => {
    team = await Team.create('board');
    agent = await team.agent('builder');
});
test.afterAll(async () => { await team?.delete(); });

/** The board for [channel] as the owner's API reads it: card titles by column. */
async function boardOf(channel, removed = false) {
    const board = await team.owner.ok('GET', `/api/board?channel=${channel}${removed ? '&removed=1' : ''}`);
    return Object.fromEntries(board.columns.map((c) => [c.status, c.cards.map((card) => card.title)]));
}

test('the owner drags a card to Done on the board and removes it from the board', async ({ browser }) => {
    await agent.call('create_project', { name: 'Sprint' });
    const ids = [];
    for (const body of ['Write the release notes', 'Fix the flaky upload test']) {
        const made = await team.owner.ok('POST', '/api/messages', { project: 'sprint', body });
        ids.push(made.message.id);
        await team.owner.ok('POST', `/api/cards/message/${made.message.id}/status`, { status: 'todo' });
    }
    const [notes] = ids;

    const page = await signedIn(browser, team.owner);
    await openChannel(page, 'Sprint');
    await tap(page, 'tab', 'Board');
    await expectShown(page, 'BOARD  ·  CHANNEL');
    await expectShown(page, 'Write the release notes');
    await expectShown(page, 'Fix the flaky upload test');

    // Drag the card by the mouse into the Done column.
    const card = await find(page, 'button', /^Write the release notes/);
    const done = await find(page, 'heading', 'Done');
    const from = await card.boundingBox();
    const to = await done.boundingBox();
    const moved = nextResponse(page, `/api/cards/message/${notes}/status`);
    await page.mouse.move(from.x + 40, from.y + from.height / 2);
    await page.mouse.down();
    for (let i = 1; i <= 12; i++) {
        await page.mouse.move(from.x + 40 + ((to.x + 40 - from.x - 40) * i) / 12, from.y + from.height / 2 + ((to.y + 120 - from.y - from.height / 2) * i) / 12);
        await page.waitForTimeout(30);
    }
    await page.mouse.up();
    expect((await moved).status(), 'moving the card').toBe(200);
    await expect.poll(async () => (await boardOf('sprint')).done, { timeout: 10_000 }).toEqual(['Write the release notes']);
    // The agent hears it as a work-state change, as it would from the feed.
    const work = await agent.call('list_work', { project: 'sprint', status: ['done'] });
    expect(work).toContain('Write the release notes');

    // Remove from board: gone from the board, not from the feed.
    const removed = nextResponse(page, `/api/cards/message/${notes}/board`);
    await tap(page, 'button', 'Remove from board');
    expect((await removed).status(), 'removing it from the board').toBe(200);
    await expectGone(page, 'Write the release notes');
    expect((await boardOf('sprint')).done).toEqual([]);
    expect((await boardOf('sprint', true)).done).toEqual(['Write the release notes']);
    const feed = await team.owner.ok('GET', '/api/messages?project=sprint');
    expect(JSON.stringify(feed)).toContain('Write the release notes');
    // The other card is still to do.
    expect((await boardOf('sprint')).todo).toEqual(['Fix the flaky upload test']);
});
