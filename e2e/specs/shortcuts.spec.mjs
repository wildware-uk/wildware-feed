// Getting around by keyboard and history: the browser's own Back and Forward walk the channels
// the owner opened (each is an entry in the tab's history, in the address's #fragment), and so do
// Alt+← and Alt+→; Alt+↓ and Alt+↑ step through the channel list; "?" lists every shortcut; "/" opens search.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, find, openChannel, reload, screenText, signedIn } from '../lib/ui.mjs';

test.describe.configure({ mode: 'default' });
let team;
test.beforeAll(async () => {
    team = await Team.create('shortcuts');
    const builder = await team.agent('builder');
    await builder.call('create_project', { project: 'alpha', name: 'Alpha' });
    await builder.call('create_project', { project: 'beta', name: 'Beta' });
});
test.afterAll(async () => { await team?.delete(); });

/** Passes once channel [name] is the one open. */
const showing = (page, name) => expectShown(page, `CHANNEL\n${name}\n`);

/** The owner's browser, on Alpha then Beta: two steps in the tab's history. */
async function alphaThenBeta(browser) {
    const page = await signedIn(browser, team.owner);
    await openChannel(page, 'Alpha');
    await openChannel(page, 'Beta');
    await expect.poll(() => new URL(page.url()).hash).toBe('#/projects/beta');
    return page;
}

test("the browser's Back and Forward walk the channels opened", async ({ browser }) => {
    const page = await alphaThenBeta(browser);
    await page.goBack();
    await showing(page, 'Alpha');
    expect(new URL(page.url()).hash).toBe('#/projects/alpha');
    await page.goForward();
    await showing(page, 'Beta');
    expect(page.errors).toEqual([]);
});

test('Alt+Left and Alt+Right go back and forward too', async ({ browser }) => {
    const page = await alphaThenBeta(browser);
    await page.keyboard.press('Alt+ArrowLeft');
    await showing(page, 'Alpha');
    await page.keyboard.press('Alt+ArrowRight');
    await showing(page, 'Beta');
    // One step each: the browser did not go back a second time on its own.
    await page.keyboard.press('Alt+ArrowLeft');
    await showing(page, 'Alpha');
    expect(new URL(page.url()).hash).toBe('#/projects/alpha');
});

test('a reload stays on the channel the address names', async ({ browser }) => {
    const page = await alphaThenBeta(browser);
    await reload(page);
    await showing(page, 'Beta');
});

test('Alt+Down and Alt+Up step through the channel list', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await expectShown(page, 'Alpha,');
    // In the sidebar's order, whichever that is.
    const text = await screenText(page);
    const [first, second] = text.indexOf('Alpha,') < text.indexOf('Beta,') ? ['Alpha', 'Beta'] : ['Beta', 'Alpha'];
    await openChannel(page, first);
    await page.keyboard.press('Alt+ArrowDown');
    await showing(page, second);
    await page.keyboard.press('Alt+ArrowUp');
    await showing(page, first);
});

test('"?" lists the keyboard shortcuts and Escape closes them', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openChannel(page, 'Alpha');
    await page.keyboard.press('?');
    await expectShown(page, 'Keyboard shortcuts');
    await expectShown(page, "Back: Alt+←, the browser's Back, the mouse's Back button");
    await expectShown(page, 'Next card: J  or  ↓');
    await page.keyboard.press('Escape');
    // Closed: "/" works again (single keys stand aside while a sheet is open). The tree keeps a
    // closed sheet's words until the page is drawn afresh, so it can't tell.
    await page.keyboard.press('/');
    await find(page, 'textbox', 'Search or run a command', 5000);
});

test('"/" opens search', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openChannel(page, 'Alpha');
    await page.keyboard.press('/');
    await find(page, 'textbox', 'Search or run a command', 5000);
});
