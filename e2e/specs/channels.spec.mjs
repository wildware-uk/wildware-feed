// Channels: the owner makes one, posts in it, and an agent's update shows up live.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, nextResponse, openChannel, post, reload, replaceText, signedIn, tap, typeInto } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
test.beforeAll(async () => { team = await Team.create('channels'); });
test.afterAll(async () => { await team?.delete(); });

/** A channel as the server has it, by its slug. */
const channel = async (slug) => (await team.owner.ok('GET', '/api/snapshot')).projects.find((p) => p.slug === slug);

test('the owner makes a channel and posts in it', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    const made = nextResponse(page, '/api/projects');
    await tap(page, 'button', 'New channel');
    await typeInto(page, 'Channel name', 'Launch');
    await tap(page, 'button', 'Create channel');
    expect((await made).status()).toBe(201);
    await reload(page);
    await openChannel(page, 'Launch');
    await post(page, 'First post in Launch');
    await reload(page);
    await expectShown(page, 'First post in Launch');
});

test("an agent's update shows up live, without a reload", async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    const agent = await team.agent('builder');
    await agent.call('post_update', { project: 'launch', title: 'Build is green', body: 'All **42** tests pass.', level: 'success' });
    await expectShown(page, 'Build is green');
    await expectShown(page, 'builder');
});

test('the owner renames the channel in its settings', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openChannel(page, 'Launch');
    await tap(page, 'button', 'Channel settings');
    await expectShown(page, 'Archive channel');
    await replaceText(page, 'Channel name', 'Launch day');
    const saved = nextResponse(page, /^\/api\/projects\/[^/]+$/, 'PATCH');
    await tap(page, 'button', 'Save');
    expect((await saved).status()).toBe(200);
    await reload(page);
    await expectShown(page, /Launch day, \d+ unread/);
    expect((await channel('launch')).name).toBe('Launch day');
});

test('an agent in the channel is listed, and the owner removes it from the channel', async ({ browser }) => {
    const agent = await team.agent('helper');
    await agent.call('post_update', { project: 'launch', title: 'Helper is here', body: 'Joined.', level: 'info' });
    expect(await agent.call('list_subscriptions')).toContain('launch');
    const page = await signedIn(browser, team.owner);
    await openChannel(page, 'Launch day');
    await expectShown(page, 'Remove helper from this channel');
    await tap(page, 'button', 'Remove helper from this channel');
    // It moves under "Not in this channel", and is told so in the channel.
    await expectShown(page, 'NOT IN THIS CHANNEL');
    await expectShown(page, 'I removed you from #launch');
});

test('the owner makes a project and puts the channel in it', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    const made = nextResponse(page, '/api/groups');
    await tap(page, 'button', 'New project, a group of channels');
    await typeInto(page, 'Project name', 'Q4 launch');
    await tap(page, 'button', 'Create project');
    expect((await made).status()).toBe(201);
    await reload(page);
    await expectShown(page, 'Q4 launch');
    await openChannel(page, 'Launch day');
    await tap(page, 'button', 'Channel settings');
    await tap(page, 'button', 'Q4 launch');
    const saved = nextResponse(page, /^\/api\/projects\/[^/]+$/, 'PATCH');
    await tap(page, 'button', 'Save');
    expect((await saved).status()).toBe(200);
    const { groups } = await team.owner.ok('GET', '/api/snapshot');
    expect((await channel('launch')).groupId).toBe(groups.find((g) => g.name === 'Q4 launch').id);
});
