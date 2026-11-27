// Arranging the side bar by hand: drag a channel into a project, and the channel menu's
// "Move to project…". Both stick after a reload.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, find, nextResponse, reload, signedIn, stillBox, tap } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let ops;
test.beforeAll(async () => {
    team = await Team.create('arrange');
    for (const name of ['Alpha', 'Beta']) await team.owner.ok('POST', '/api/projects', { name });
    ops = (await team.owner.ok('POST', '/api/groups', { name: 'Ops' })).group;
});
test.afterAll(async () => { await team?.delete(); });

/** A channel as the server has it, by its slug. */
const channel = async (slug) => (await team.owner.ok('GET', '/api/snapshot')).projects.find((p) => p.slug === slug);

/**
 * Where a side bar row is drawn once it has stopped moving: a channel by name, or a project's
 * heading. The tree can have a row before it is laid out (no box yet), and after a reload the rows
 * settle as the channels and projects come in, so a box read at once can be none or on its way.
 */
const rowBox = async (page, name, project = false) => {
    const row = await find(page, 'button', new RegExp(`^${name}, ${project ? 'project' : ''}`));
    let box = null;
    await expect.poll(async () => {
        box = await stillBox(row);
        return !!box && box.height > 0;
    }, { timeout: 15_000, message: `the ${name} row should be drawn` }).toBe(true);
    return box;
};

/** The rows' tops, top to bottom, by name: projects' headings as "project:Name". */
const order = async (page, rows) => {
    const tops = await Promise.all(rows.map(async (r) => [r, (await rowBox(page, r.replace(/^project:/, ''), r.startsWith('project:'))).y]));
    return tops.sort((a, b) => a[1] - b[1]).map(([r]) => r);
};

test('the owner drags a channel into a project and it stays there after a reload', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await expectShown(page, 'Ops');
    const from = await rowBox(page, 'Beta');
    const to = await rowBox(page, 'Ops', true);
    const moved = nextResponse(page, '/api/projects/order', 'PUT');
    await page.mouse.move(from.x + 40, from.y + from.height / 2);
    await page.mouse.down();
    // In steps, as a hand moves: the drag starts past a few pixels and follows each move.
    await page.mouse.move(from.x + 40, from.y + from.height / 2 + 12, { steps: 4 });
    await page.mouse.move(to.x + 40, to.y + to.height / 2, { steps: 12 });
    await page.waitForTimeout(200);
    await page.mouse.up();
    expect((await moved).status(), 'the move').toBe(200);
    expect((await channel('beta')).groupId).toBe(ops.id);

    await reload(page);
    await expectShown(page, 'Ops');
    // Under its project's heading, above the channels in no project.
    await expect.poll(() => order(page, ['project:Ops', 'Beta', 'Alpha']), { timeout: 15_000 }).toEqual(['project:Ops', 'Beta', 'Alpha']);
});

test("the channel menu's Move to project moves it, and it stays after a reload", async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await expectShown(page, 'Ops');
    await tap(page, 'button', /^Alpha, /, { button: 'right' });
    await tap(page, 'button', 'Move to project…');
    const moved = nextResponse(page, '/api/projects/order', 'PUT');
    // A choice in a dialog is a button in the web app's accessibility tree, as in channels.spec.mjs.
    await tap(page, 'button', 'Ops');
    expect((await moved).status(), 'the move').toBe(200);
    expect((await channel('alpha')).groupId).toBe(ops.id);

    await reload(page);
    await expectShown(page, 'Ops');
    // In its project, moved in at the end: after Beta.
    await expect.poll(() => order(page, ['project:Ops', 'Beta', 'Alpha']), { timeout: 15_000 }).toEqual(['project:Ops', 'Beta', 'Alpha']);
});
