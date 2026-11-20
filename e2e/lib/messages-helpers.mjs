// Helpers for the message and search specs: a card's menu, sending with the keyboard, and what an
// agent reads over MCP.
import { expect } from '@playwright/test';
import { find, openChannel, screenText } from './ui.mjs';

/** Sends what is in the focused message or reply box: Ctrl+Enter, as a multiline box wants. */
export async function sendWithKeys(page) {
    await page.keyboard.press('Control+Enter');
}

/**
 * Opens the menu of the card whose Collapse button is named [collapse] (e.g. /^Collapse post: Hello/).
 * A right-click on the card's words opens the browser's text menu ("Select all"), so this clicks
 * the card's header row, just left of its Collapse button, where there are no words.
 */
export async function openCardMenu(page, collapse) {
    const button = await find(page, 'button', collapse);
    const { height } = page.viewportSize();
    let box = await settledBox(button);
    for (let i = 0; i < 20 && (!box || box.width === 0 || box.y < 0 || box.y + box.height > height); i++) {
        await page.mouse.move(640, height / 2);
        await page.mouse.wheel(0, box && box.width > 0 && box.y < 0 ? -200 : 200);
        await page.waitForTimeout(150);
        box = await settledBox(button);
    }
    if (!box || box.width === 0) throw new Error(`the card "${collapse}" is not on the screen`);
    await page.mouse.click(box.x - 60, box.y + box.height / 2, { button: 'right' });
    // The menu grows in from the pointer: wait until its rows stop moving before anyone taps one.
    await settledBox(page.getByRole('button', { name: 'Delete', exact: true }).last());
}

/** [locator]'s box once it has stopped moving (cards slide in and menus grow in). */
export async function settledBox(locator, timeout = 5000) {
    await locator.waitFor({ state: 'attached', timeout });
    let last = null;
    for (const end = Date.now() + timeout; Date.now() < end;) {
        const box = await locator.boundingBox();
        if (box && last && box.x === last.x && box.y === last.y && box.width === last.width && box.height === last.height) return box;
        last = box;
        await locator.page().waitForTimeout(150);
    }
    return last;
}

/** A tool's JSON: its answer is a sentence for the agent, then the JSON. */
export async function toolJson(agent, tool, args) {
    const text = await agent.call(tool, args);
    const at = text.indexOf('{');
    if (at < 0) throw new Error(`mcp ${tool}: no JSON in "${text.slice(0, 200)}"`);
    return JSON.parse(text.slice(at));
}

/** Every message waiting for [agent], read with get_messages page by page (marks them read). */
export async function inbox(agent) {
    const all = [];
    for (let i = 0; i < 10; i++) {
        const page = await toolJson(agent, 'get_messages', {}).catch(async (e) => {
            // "No new messages" comes without JSON.
            if (/no JSON/.test(e.message)) return { messages: [], unread: 0 };
            throw e;
        });
        all.push(...page.messages);
        if (!page.unread) break;
    }
    return all;
}

/** Passes once [agent]'s get_messages has a message whose body contains [text]; that message. */
export async function expectAgentGets(agent, text, timeout = 10_000) {
    const seen = [];
    let found;
    await expect.poll(async () => {
        seen.push(...await inbox(agent));
        found = seen.find((m) => m.body?.includes(text));
        return !!found;
    }, { timeout, message: `${agent.name} should get "${text}" from get_messages` }).toBe(true);
    return found;
}

/** The messages the server lists for [query] (e.g. "project=alpha" or "update=<id>"), as [person] reads them. */
export async function listMessages(person, query) {
    const json = await person.ok('GET', `/api/messages?${query}`, undefined, 200);
    return json.messages ?? [];
}

/**
 * Taps the button named [name] that belongs to the card whose Collapse button is named [collapse]:
 * the first one after it in the tree (e.g. that card's own "Reply"). A card's menu would do too,
 * but after a menu closes the tree keeps its rows until a reload.
 */
export async function tapOnCard(page, collapse, name) {
    await find(page, 'button', collapse);
    const labels = await page.getByRole('button').evaluateAll((es) => es.map((e) => e.getAttribute('aria-label') || e.textContent || ''));
    const start = labels.findIndex((l) => collapse.test(l));
    const index = labels.findIndex((l, i) => i > start && l === name);
    if (start < 0 || index < 0) throw new Error(`no "${name}" on the card ${collapse}`);
    const target = page.getByRole('button').nth(index);
    const box = await target.boundingBox();
    if (!box || box.width === 0) throw new Error(`"${name}" on the card ${collapse} is not drawn`);
    await page.mouse.click(box.x + Math.min(box.width / 2, 40), box.y + box.height / 2);
    await page.waitForTimeout(400);
}

/**
 * Channel [name] open, checked: the header says "CHANNEL" then its name. A tap on the side bar
 * right after the page loads is sometimes lost (the feed stays on All channels), so it tries again.
 */
export async function goToChannel(page, name) {
    for (let i = 0; i < 4; i++) {
        await openChannel(page, name);
        const shown = await expect.poll(async () => (await screenText(page)).includes(`CHANNEL\n${name}\n`), { timeout: 2000 }).toBe(true).then(() => true, () => false);
        if (shown) return;
    }
    throw new Error(`channel ${name} did not open`);
}
