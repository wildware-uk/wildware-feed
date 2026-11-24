// Messages: posting, threads and replies, @mentions, reactions, editing and deleting. What the
// screen shows, what the server answers, and what agents read over MCP.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectGone, expectShown, expectTyping, find, screenText, nextResponse, post, reload, signedIn, stillBox, tap, typeInto } from '../lib/ui.mjs';
import { expectAgentGets, goToChannel, inbox, listMessages, openCardMenu, sendWithKeys, settledBox, tapOnCard, toolJson } from '../lib/messages-helpers.mjs';

// Not serial: a failure doesn't skip the rest (the next test gets a fresh worker and team).
test.describe.configure({ mode: 'default' });
let team, builder, reviewer;
test.beforeAll(async () => {
    team = await Team.create('messages');
    // builder works in Alpha; reviewer works in Beta only.
    builder = await team.agent('builder');
    reviewer = await team.agent('reviewer');
    await builder.call('create_project', { project: 'alpha', name: 'Alpha' });
    await reviewer.call('create_project', { project: 'beta', name: 'Beta' });
    await reviewer.call('post_update', { project: 'beta', title: 'Reviewer is here', body: 'Working in Beta.' });
});
test.afterAll(async () => { await team?.delete(); });
// Each test's browser closes after it, so a file's tests don't pile up copies of the app.
let opened = [];
test.afterEach(async () => { await Promise.all(opened.map((p) => p.context().close().catch(() => {}))); opened = []; });

/** The owner's browser, in channel Alpha. */
async function inAlpha(browser) {
    const page = await signedIn(browser, team.owner);
    opened.push(page);
    await goToChannel(page, 'Alpha');
    return page;
}

test('the owner posts a message; it stays after a reload and the agent in the channel gets it', async ({ browser }) => {
    const page = await inAlpha(browser);
    await post(page, 'Morning, the coffee machine is fixed');
    await expectShown(page, 'Morning, the coffee machine is fixed');
    await reload(page);
    await expectShown(page, 'Collapse post: Morning, the coffee machine is fixed');
    const kept = await listMessages(team.owner, 'project=alpha');
    expect(kept.map((m) => m.body)).toContain('Morning, the coffee machine is fixed');
    const got = await expectAgentGets(builder, 'Morning, the coffee machine is fixed');
    expect(got.author).toBe('human');
});

test("the owner opens a card's thread and replies; the agent gets the reply and its answer shows live", async ({ browser }) => {
    const { update } = await toolJson(builder, 'post_update', { project: 'alpha', title: 'Migration plan ready', body: 'Three steps, one hour of downtime.' });
    const page = await inAlpha(browser);
    await expectShown(page, 'Collapse update: Migration plan ready');
    await tapOnCard(page, /^Collapse update: Migration plan ready/, 'Reply');
    // The thread's reply box opens with the focus in it. (A click in it would take the focus away:
    // see the test below.)
    await find(page, 'textbox', 'Reply');
    await expectTyping(page);
    await page.keyboard.type('Go ahead on Saturday', { delay: 15 });
    const sent = nextResponse(page, '/api/messages');
    await sendWithKeys(page);
    const res = await sent;
    expect(res.status()).toBe(201);
    expect(res.request().postDataJSON()).toMatchObject({ body: 'Go ahead on Saturday', update: update.id });
    await expectShown(page, 'Go ahead on Saturday');

    const got = await expectAgentGets(builder, 'Go ahead on Saturday');
    expect(got.update_id).toBe(update.id);
    // The agent answers in the same thread, and the open page shows it without a reload.
    await builder.call('post_message', { update_id: update.id, body: 'Booked for Saturday 06:00' });
    await expectShown(page, 'Booked for Saturday 06:00');
    const thread = await listMessages(team.owner, `update=${update.id}`);
    expect(thread.map((m) => m.body)).toEqual(expect.arrayContaining(['Go ahead on Saturday', 'Booked for Saturday 06:00']));
});

// A click inside the thread's reply box, while it has the focus, keeps it. (It used to take it away:
// the feed around the box took the click as a tap on the feed, so every other click lost what was
// typed next.)
test('a click inside the focused thread reply box keeps the focus', async ({ browser }) => {
    await builder.call('post_update', { project: 'alpha', title: 'Focus check', body: 'Click in the reply box.' });
    const page = await inAlpha(browser);
    await tapOnCard(page, /^Collapse update: Focus check/, 'Reply');
    // The thread opens with the reply box focused; where it ends up once the thread has opened.
    const box = await stillBox(await find(page, 'textbox', 'Reply'));
    await expectTyping(page);
    await page.keyboard.type('one ', { delay: 15 });
    for (const word of ['two ', 'three']) {
        await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
        await expectTyping(page);
        await page.keyboard.type(word, { delay: 15 });
    }
    const sent = nextResponse(page, '/api/messages', 'POST', 5000);
    await sendWithKeys(page);
    expect((await sent).request().postDataJSON().body).toBe('one two three');
});

// Cards go by latest activity, so a reply moves its card to the top of the feed. The feed goes
// with it, so the card the owner just answered doesn't vanish from under them.
test('replying on a card further down takes the feed to the top with it', async ({ browser }) => {
    await builder.call('create_project', { project: 'pile', name: 'Pile' });
    // Oldest first, so card 11 ends up last: two digits each, so no title starts another.
    for (let n = 11; n <= 20; n++) {
        await builder.call('post_update', { project: 'pile', title: `Pile card ${n}`, body: `Card ${n} of the pile.\n\nA second paragraph, so each card takes some room.` });
    }
    const page = await signedIn(browser, team.owner);
    opened.push(page);
    await goToChannel(page, 'Pile');
    // A card scrolled out of the list leaves the tree, so a box is read without waiting for one.
    const oldest = page.getByRole('button', { name: /^Collapse update: Pile card 11/ }).first();
    const newest = page.getByRole('button', { name: /^Collapse update: Pile card 20/ }).first();
    const boxOf = (target) => target.boundingBox({ timeout: 500 }).catch(() => null);
    const { height } = page.viewportSize();
    const onScreen = (box) => !!box && box.width > 0 && box.y >= 0 && box.y + box.height <= height;
    await find(page, 'button', /^Collapse update: Pile card 20/);
    // Down to the end of the feed, where the oldest card is, so the top is out of sight.
    for (let i = 0; i < 40 && !onScreen(await boxOf(oldest)); i++) {
        await page.mouse.move(640, height / 2);
        await page.mouse.wheel(0, 300);
        await page.waitForTimeout(150);
    }
    for (let i = 0; i < 4; i++) {
        await page.mouse.wheel(0, 300);
        await page.waitForTimeout(150);
    }
    await settledBox(oldest);
    expect(onScreen(await boxOf(oldest)), 'the oldest card is on screen').toBe(true);
    expect(onScreen(await boxOf(newest)), 'the newest card is out of sight').toBe(false);
    await tapOnCard(page, /^Collapse update: Pile card 11/, 'Reply');
    await find(page, 'textbox', 'Reply');
    await expectTyping(page);
    await page.keyboard.type('Picking this one up', { delay: 15 });
    const sent = nextResponse(page, '/api/messages');
    await sendWithKeys(page);
    expect((await sent).status()).toBe(201);
    // Card 11 is first now, on screen at the top, with the rest under it and its reply box still open.
    await expect.poll(async () => {
        const [top, next] = [await boxOf(oldest), await boxOf(newest)];
        return onScreen(top) && onScreen(next) && top.y < height / 2 && next.y > top.y;
    }, { timeout: 15_000 }).toBe(true);
    await find(page, 'textbox', 'Reply');
});

test('"Mention an agent" names one agent: it gets the message, and so does the channel', async ({ browser }) => {
    const page = await inAlpha(browser);
    await inbox(builder); await inbox(reviewer); // start both with nothing unread
    await tap(page, 'button', 'Mention an agent');
    await expectTyping(page);
    await page.keyboard.type('rev', { delay: 15 });
    await find(page, 'button', /^Mention reviewer/);
    await page.keyboard.press('Enter'); // picks the highlighted name
    await page.keyboard.type('please look at the logo sizes', { delay: 15 });
    const sent = nextResponse(page, '/api/messages');
    await sendWithKeys(page);
    expect((await sent).status()).toBe(201);
    await expectShown(page, '@reviewer please look at the logo sizes');

    const got = await expectAgentGets(reviewer, 'please look at the logo sizes');
    expect(got.body).toBe('@reviewer please look at the logo sizes');
    // builder works in this channel: a person's @name reaches the channel too.
    expect((await inbox(builder)).map((m) => m.body)).toContain('@reviewer please look at the logo sizes');
});

// "Mention an agent" puts "@" in the box and the cursor after it, so the list of agents opens and
// typing narrows it. (It used to keep the focus on the button.)
test('"Mention an agent" opens the list of agents and keeps typing in the box', async ({ browser }) => {
    const page = await inAlpha(browser);
    await tap(page, 'textbox', 'Message');
    await tap(page, 'button', 'Mention an agent');
    await expectTyping(page);
    await page.keyboard.type('rev', { delay: 15 });
    await find(page, 'button', /^Mention reviewer/, 3000);
});

test('typing @name and picking from the list mentions that agent', async ({ browser }) => {
    const page = await inAlpha(browser);
    await inbox(builder); await inbox(reviewer);
    await typeInto(page, 'Message', '@buil');
    await expectShown(page, '1 to mention');
    await find(page, 'button', /^Mention builder/);
    await page.keyboard.press('Tab');
    await page.keyboard.type('the nightly build is red', { delay: 15 });
    const sent = nextResponse(page, '/api/messages');
    await sendWithKeys(page);
    expect((await sent).status()).toBe(201);
    await expectShown(page, '@builder the nightly build is red');

    await expectAgentGets(builder, '@builder the nightly build is red');
    // reviewer doesn't follow this channel, and wasn't named.
    expect((await inbox(reviewer)).map((m) => m.body)).not.toContain('@builder the nightly build is red');
});

// A line an agent read with get_messages counts as delivered: the server records a delivery for
// each message get_messages hands over, as the live stream does. (It used to record only the
// stream's, so a read line showed, 15 s on, in red: "Not delivered to any agent".)
test('a message an agent read with get_messages is not marked "Not delivered to any agent"', async ({ browser }) => {
    const page = await inAlpha(browser);
    await post(page, 'Is the staging database back up');
    await expectAgentGets(builder, 'Is the staging database back up');
    await page.waitForTimeout(16_000); // the app waits 15 s before it calls a line undelivered
    await reload(page);
    await expectShown(page, 'Is the staging database back up');
    expect(await screenText(page)).not.toMatch(/Is the staging database back up\n(.*\n){0,2}Not delivered to any agent/);
});

test('the owner reacts from the card menu; the reaction shows and is kept', async ({ browser }) => {
    const page = await inAlpha(browser);
    await post(page, 'Lunch order goes in at noon');
    await openCardMenu(page, /^Collapse post: Lunch order goes in at noon/);
    const reacted = nextResponse(page, '/reactions');
    await tap(page, 'button', 'React with 🎉');
    const res = await reacted;
    expect(res.status()).toBe(200);
    expect(res.request().postDataJSON()).toMatchObject({ emoji: '🎉' });
    // The tree keeps the closed menu's rows until the page is drawn afresh.
    await reload(page);
    await expectShown(page, '🎉, 1, including you');
});

test("an agent's reaction on the owner's message shows live", async ({ browser }) => {
    const page = await inAlpha(browser);
    await post(page, 'Who can review the release notes');
    const mine = (await listMessages(team.owner, 'project=alpha')).find((m) => m.body === 'Who can review the release notes');
    expect(mine, 'the post is on the server').toBeTruthy();
    const { reactions } = await toolJson(builder, 'react', { message_id: mine.id, emoji: '👀' });
    expect(reactions).toEqual([expect.objectContaining({ emoji: '👀' })]);
    await expectShown(page, '👀, 1. Double tap to add yours.');
});

test("an agent edits its message: the screen shows the new words, marked edited", async ({ browser }) => {
    const { update } = await toolJson(builder, 'post_update', { project: 'alpha', title: 'Release status', body: 'Tagging now.' });
    const { message } = await toolJson(builder, 'post_message', { update_id: update.id, body: 'ETA is ten minutes' });
    const page = await inAlpha(browser);
    await expectShown(page, 'ETA is ten minutes');
    await builder.call('edit_message', { message_id: message.id, body: 'ETA is twenty minutes' });
    await expectShown(page, 'ETA is twenty minutes');
    await expectGone(page, 'ETA is ten minutes');
    // Drawn as "edited"; the tree reads out when: "Edited Friday 20 November 2026 at 15:11".
    await expectShown(page, /builder, .*, Edited \w+ \d+ \w+ \d{4} at \d\d:\d\d\nETA is twenty minutes/);
});

// Not in the app: the owner can't edit their own message. The card menu has no Edit (Overlays.kt
// menuRows: Reply, Copy text, Copy link, Pin, Delete), and the server has no route for it (only
// DELETE /api/messages/{id}; edit_message is MCP, agents only).
test.fixme('the owner edits their own message', async () => {});

test('the owner deletes their own message: it is gone from the screen and the server', async ({ browser }) => {
    const page = await inAlpha(browser);
    await post(page, 'Ignore this, wrong channel');
    await openCardMenu(page, /^Collapse post: Ignore this, wrong channel/);
    await tap(page, 'button', 'Delete');
    await expectShown(page, 'Delete this post?');
    const deleted = page.waitForResponse((r) => r.request().method() === 'DELETE' && /\/api\/messages\/[^/]+$/.test(new URL(r.url()).pathname));
    await tap(page, 'button', 'Delete');
    const res = await deleted;
    expect(res.status()).toBe(200);
    await expectGone(page, 'Ignore this, wrong channel');
    await reload(page);
    await expectShown(page, 'Mention an agent');
    await expectGone(page, 'Ignore this, wrong channel');
    const left = await listMessages(team.owner, 'project=alpha');
    expect(left.filter((m) => m.body === 'Ignore this, wrong channel' && !m.deleted_at)).toEqual([]);
});

// An agent the owner @mentions in a thread reads all of it: the post that started the thread named
// nobody, so it never reached the agent, but get_messages with the mention's id hands it over.
test('an agent @mentioned in a thread reads the whole thread, from the post that started it', async () => {
    await team.owner.ok('POST', '/api/projects', { name: 'Gamma' });
    const { message: root } = await team.owner.ok('POST', '/api/messages', { project: 'gamma', body: 'Here is the launcher for the new teammate' });
    await team.owner.ok('POST', '/api/messages', { replyTo: root.id, body: 'It needs the usual flags' });
    const { message: ask } = await team.owner.ok('POST', '/api/messages', { replyTo: root.id, body: '@reviewer can you start a teammate with it' });
    const got = await expectAgentGets(reviewer, 'can you start a teammate');
    expect(got.thread).toEqual({ kind: 'message', id: root.id });
    const read = await toolJson(reviewer, 'get_messages', { message_id: ask.id });
    expect(read.messages.map((m) => m.body)).toEqual([
        'Here is the launcher for the new teammate', 'It needs the usual flags', '@reviewer can you start a teammate with it',
    ]);
    expect(read.thread).toMatchObject({ kind: 'message', id: root.id, channel: 'gamma' });
});
