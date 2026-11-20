// Settings on this device: which alerts it gets (kept on the server, per device), sounds on and
// off, and light or dark. Each survives a reload.
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectShown, has, nextResponse, reload, signedIn, tap } from '../lib/ui.mjs';

test.describe.configure({ mode: 'serial' });
let team;
test.beforeAll(async () => { team = await Team.create('settings'); });
test.afterAll(async () => { await team?.delete(); });

async function openAlerts(page) {
    await tap(page, 'button', 'Alerts on this device');
    await expectShown(page, 'TELL ME ABOUT');
}

test('turning question alerts off and on again is saved for this device', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await openAlerts(page);
    let saved = nextResponse(page, '/api/devices/current/alert-prefs', 'PUT');
    await tap(page, 'button', 'Questions');
    let res = await saved;
    expect(res.status()).toBe(200);
    expect(JSON.parse(res.request().postData()).prefs.types).not.toContain('question');
    await reload(page);
    await openAlerts(page);
    saved = nextResponse(page, '/api/devices/current/alert-prefs', 'PUT');
    await tap(page, 'button', 'Questions');
    res = await saved;
    expect(res.status()).toBe(200);
    // Back to every type: the defaults, sent as no preferences at all.
    const prefs = JSON.parse(res.request().postData()).prefs;
    expect(prefs === null || prefs.types.includes('question')).toBe(true);
});

test('sounds can be muted, and stay muted after a reload', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    const sounds = () => page.evaluate(() => localStorage.getItem('feed.sounds'));
    await openAlerts(page);
    await tap(page, 'button', 'Play sounds');
    await expect.poll(sounds).toBe('off');
    await reload(page);
    expect(await sounds()).toBe('off');
    await openAlerts(page);
    await tap(page, 'button', 'Play sounds');
    await expect.poll(sounds).not.toBe('off');
});

test('dark mode switches, and stays after a reload', async ({ browser }) => {
    const page = await signedIn(browser, team.owner);
    await tap(page, 'button', 'Switch to dark');
    await expect.poll(() => has(page, 'button', 'Switch to light')).toBe(true);
    await reload(page);
    expect(await has(page, 'button', 'Switch to light', 5000)).toBe(true);
    await tap(page, 'button', 'Switch to light');
    await expect.poll(() => has(page, 'button', 'Switch to dark')).toBe(true);
});
