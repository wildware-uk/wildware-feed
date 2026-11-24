// Search everything (the "Search" button or Ctrl+K): finds cards, posts and replies by a word,
// and opening a result shows it, even from another channel (on its own, over the channel on screen). Search runs in the app over what it
// has loaded, so the server's part is having the things to find.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectGone, expectKeysInApp, expectShown, find, has, signedIn, tap, typeInto } from '../lib/ui.mjs';
import { goToChannel, settledBox, toolJson } from '../lib/messages-helpers.mjs';

test.describe.configure({ mode: 'default' });
let team, builder, card;
test.beforeAll(async () => {
    team = await Team.create('search');
    builder = await team.agent('builder');
    await builder.call('create_project', { project: 'alpha', name: 'Alpha' });
    await builder.call('create_project', { project: 'beta', name: 'Beta' });
    ({ update: card } = await toolJson(builder, 'post_update', { project: 'alpha', title: 'Quarterly kumquat report', body: 'Sales are up in the north.' }));
    await toolJson(builder, 'post_message', { update_id: card.id, body: 'The pomegranate numbers come next week' });
    const posted = await team.owner.ok('POST', '/api/messages', { body: 'Remember the walrus meeting on Thursday', project: 'beta' });
    expect(posted.message.body).toBe('Remember the walrus meeting on Thursday');
});
test.afterAll(async () => { await team?.delete(); });
let opened = [];
test.afterEach(async () => { await Promise.all(opened.map((p) => p.context().close().catch(() => {}))); opened = []; });

/** The owner's browser in channel [name]. */
async function inChannel(browser, name) {
    const page = await signedIn(browser, team.owner);
    opened.push(page);
    await goToChannel(page, name);
    return page;
}

const SEARCH = 'Search or run a command';

test('Ctrl+K finds a card by a word; opening it shows the card from another channel', async ({ browser }) => {
    const page = await inChannel(browser, 'Beta');
    await expectGone(page, 'Quarterly kumquat report');
    // The app has the keyboard after a tap in it (and after a load: see the last test).
    await expectKeysInApp(page);
    await page.keyboard.press('Control+k');
    await typeInto(page, SEARCH, 'kumquat');
    await expectShown(page, /FOUND\nQuarterly kumquat report\. Card · builder · Alpha/);
    await page.keyboard.press('Enter'); // the first result is highlighted
    await expectGone(page, SEARCH);
    await expectShown(page, 'Collapse update: Quarterly kumquat report');
    await expectShown(page, 'Sales are up in the north.');
});

test('the Search button finds a message by a word, and opening it shows the message', async ({ browser }) => {
    const page = await inChannel(browser, 'Alpha');
    await expectGone(page, 'Remember the walrus meeting on Thursday');
    await tap(page, 'button', 'Search everything. Shortcut Command K');
    await typeInto(page, SEARCH, 'walrus');
    const result = page.getByRole('button', { name: /^Remember the walrus meeting on Thursday\. Card · You · Beta/ });
    await settledBox(result);
    await tap(page, 'button', /^Remember the walrus meeting on Thursday\. Card · You · Beta/);
    await expectGone(page, SEARCH);
    await expectShown(page, 'Collapse post: Remember the walrus meeting on Thursday');
});

test("a reply in a card's thread is found too, and opening it shows the thread", async ({ browser }) => {
    const page = await inChannel(browser, 'Beta');
    await tap(page, 'button', 'Search everything. Shortcut Command K');
    await typeInto(page, SEARCH, 'pomegranate');
    await expectShown(page, /The pomegranate numbers come next week\. Reply · builder · Alpha/);
    await page.keyboard.press('Enter');
    await expectGone(page, SEARCH);
    await expectShown(page, 'Collapse update: Quarterly kumquat report');
    await expectShown(page, 'The pomegranate numbers come next week');
    // Opening a reply opens its card's thread, ready to answer.
    expect(await has(page, 'textbox', 'Reply', 5000)).toBe(true);
});

// A card the channel on screen doesn't hold opens on its own, read by itself with its thread: the
// feed under it is never paged or switched to find it, and "Back to feed" leaves it as it was.
test("another channel's card opens on its own, quickly, and Back to feed leaves the channel as it was", async ({ browser }) => {
    const page = await inChannel(browser, 'Beta');
    await expectShown(page, 'Remember the walrus meeting on Thursday');
    await tap(page, 'button', 'Search everything. Shortcut Command K');
    await typeInto(page, SEARCH, 'kumquat');
    await expectShown(page, /FOUND\nQuarterly kumquat report\. Card · builder · Alpha/);
    const asked = Date.now();
    await page.keyboard.press('Enter');
    await expectGone(page, SEARCH);
    // On its own: the card, its reply, and the way back, within a few seconds.
    await find(page, 'button', 'Back to feed', 5000);
    await expectShown(page, 'Sales are up in the north.', 5000);
    await expectShown(page, 'The pomegranate numbers come next week', 5000);
    expect(Date.now() - asked).toBeLessThan(5000);
    await tap(page, 'button', 'Back to feed');
    await expectGone(page, 'Sales are up in the north.');
    // Still Beta, as it was: its own post, and no Alpha card dragged in.
    await expectShown(page, 'Remember the walrus meeting on Thursday');
    await expectGone(page, 'Quarterly kumquat report');
});

test('a word nothing has finds nothing', async ({ browser }) => {
    const page = await inChannel(browser, 'Alpha');
    await tap(page, 'button', 'Search everything. Shortcut Command K');
    await typeInto(page, SEARCH, 'kumq');
    await expectShown(page, /FOUND\nQuarterly kumquat report/);
    await page.keyboard.type('xyz', { delay: 15 }); // "kumqxyz": in nothing
    await expectGone(page, 'FOUND');
    await expectGone(page, 'Quarterly kumquat report. Card');
});

// Ctrl+K works straight after a load, before any click: the web app gives its canvas the focus once
// it is drawn. (A loaded page used to have the focus on its body, which the app never hears.)
test('Ctrl+K opens search straight after the page loads', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    opened.push(page);
    await page.keyboard.press('Control+k');
    await find(page, 'textbox', SEARCH, 3000);
});
