// Media: the owner attaches a picture, a sound and a video from the composer. Each uploads, posts and
// shows in the feed; the picture opens in the viewer, and the sound and the video really play.
// A picture's Save downloads the upload itself, under its own name.
import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { Team } from '../lib/team.mjs';
import { expectGone, expectShown, nextResponse, signedIn, tap, typeInto } from '../lib/ui.mjs';
import { ffmpeg, openChannelSurely } from '../lib/cards-helpers.mjs';

test.describe.configure({ mode: 'serial' });
let team;
let agent;
test.beforeAll(async () => {
    team = await Team.create('media');
    agent = await team.agent('builder');
});
test.afterAll(async () => { await team?.delete(); });

/** The owner in a fresh channel of their own, so the post lands at the top of its feed. */
async function inFreshChannel(browser, name) {
    await agent.call('create_project', { name });
    const page = await signedIn(browser, team.owner);
    await openChannelSurely(page, name);
    return page;
}

/** Attaches [file] with "Attach files" and posts it with [text]; what the upload answered. */
async function attachAndPost(page, file, text) {
    const chooser = page.waitForEvent('filechooser');
    await tap(page, 'button', 'Attach files');
    await (await chooser).setFiles([file]);
    await expectShown(page, `Attached: ${file.split('/').pop()}`);
    await typeInto(page, 'Message', text);
    const uploaded = nextResponse(page, '/api/media', 'POST', 30_000);
    const posted = nextResponse(page, '/api/messages', 'POST', 30_000);
    await tap(page, 'button', 'Send');
    const upload = await uploaded;
    expect(upload.status(), 'the upload').toBe(201);
    const media = (await upload.json()).media;
    const post = await posted;
    expect(post.status(), 'the post').toBe(201);
    expect(post.request().postData(), 'the post carries the upload').toContain(media.id);
    return { media };
}

/** Keeps every sound or video the page starts playing, even one never put in the page, as window.played. */
const watchPlayback = (page) => page.evaluate(() => {
    window.played = [];
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function (...args) {
        window.played.push(this);
        return play.apply(this, args);
    };
});

test('a picture: it uploads, posts, shows, and opens full size', async ({ browser }, info) => {
    const picture = info.outputPath('red-square.png');
    ffmpeg('-f', 'lavfi', '-i', 'color=c=red:s=64x48', '-frames:v', '1', picture);
    const page = await inFreshChannel(browser, 'Pictures');

    const drawn = page.waitForResponse((r) => /\/media\/[^/]+\/thumb/.test(new URL(r.url()).pathname), { timeout: 15_000 });
    const { media } = await attachAndPost(page, picture, 'A red square');
    expect(media).toMatchObject({ kind: 'image', mime: 'image/png' });
    await expectShown(page, 'A red square');
    await expectShown(page, 'Image: Attached image. Open full size');
    const thumb = await drawn;
    expect(thumb.status(), 'the picture in the feed').toBe(200);
    expect(thumb.headers()['content-type']).toMatch(/^image\//);

    await tap(page, 'button', /^Image: Attached image/);
    await expectShown(page, 'Close pictures');
    // A 64 × 48 picture fits at its own size: never blown up.
    await expectShown(page, 'Zoom 100%');
    await tap(page, 'button', 'Zoom in');
    await expectShown(page, 'Zoom 125%');
    await tap(page, 'button', 'Fit to window');
    await expectShown(page, 'Zoom 100%');
    // The wheel zooms too, around the pointer.
    const { width, height } = page.viewportSize();
    await page.mouse.move(width / 2, height / 2);
    await page.mouse.wheel(0, -120);
    await expectGone(page, 'Zoom 100%');
    await page.keyboard.press('Escape');
    await expectGone(page, 'Close pictures');

    // The agent reads the post with its picture.
    const read = await agent.call('get_messages', { project: 'pictures' });
    expect(read).toContain('A red square');
    expect(read).toContain(media.id);
});

test('a picture saves: the browser downloads the original as itself', async ({ browser }, info) => {
    const picture = info.outputPath('blue-square.png');
    ffmpeg('-f', 'lavfi', '-i', 'color=c=blue:s=64x48', '-frames:v', '1', picture);
    const page = await inFreshChannel(browser, 'Saved pictures');

    const { media } = await attachAndPost(page, picture, 'A blue square to keep');
    await expectShown(page, 'A blue square to keep');

    const downloading = page.waitForEvent('download', { timeout: 15_000 });
    await tap(page, 'button', /^Save picture image\.png/);
    const download = await downloading;
    expect(download.suggestedFilename()).toBe('image.png');
    expect(new URL(download.url()).pathname).toBe(`/media/${media.id}/original`);
    expect(new URL(download.url()).searchParams.get('download')).toBe('1');
    const kept = info.outputPath('kept.png');
    await download.saveAs(kept);
    expect(readFileSync(kept).equals(readFileSync(picture)), 'the upload byte for byte, not a thumbnail').toBe(true);

    // The dashboard sends it as an attachment, and only with the owner's sign-in (asked from the page,
    // which knows the team's address).
    const asked = (credentials) => page.evaluate(async ([url, credentials]) => {
        const r = await fetch(url, { credentials, cache: 'no-store' });
        return { status: r.status, disposition: r.headers.get('content-disposition') };
    }, [download.url(), credentials]);
    const answer = await asked('same-origin');
    expect(answer.status).toBe(200);
    expect(answer.disposition).toMatch(/^attachment; filename="image\.png"/);
    expect((await asked('omit')).status, 'nobody signed in').toBe(401);
});

test('a sound: it uploads, posts, shows, and plays', async ({ browser }, info) => {
    const sound = info.outputPath('tone.mp3');
    ffmpeg('-f', 'lavfi', '-i', 'sine=frequency=440:duration=3', '-c:a', 'libmp3lame', '-b:a', '32k', sound);
    const page = await inFreshChannel(browser, 'Sounds');

    const { media } = await attachAndPost(page, sound, 'A short tone');
    expect(media).toMatchObject({ kind: 'audio', mime: 'audio/mpeg' });
    await expectShown(page, 'A short tone');
    await expectShown(page, 'Play sound 1 of 1, tone.mp3, 0:03');

    await watchPlayback(page);
    await tap(page, 'button', /^Play sound 1 of 1, tone\.mp3/);
    await page.waitForFunction(() => window.played.some((m) => m instanceof HTMLAudioElement && m.currentTime > 0.5), null, { timeout: 15_000 });
    await expectShown(page, /^Pause sound 1 of 1, tone\.mp3/m);
});

test('a video: it uploads, posts, shows, and plays', async ({ browser }, info) => {
    const clip = info.outputPath('clip.mp4');
    ffmpeg('-f', 'lavfi', '-i', 'testsrc=size=160x120:rate=10:duration=3', '-pix_fmt', 'yuv420p', '-c:v', 'libx264', '-movflags', '+faststart', clip);
    const page = await inFreshChannel(browser, 'Videos');

    const { media } = await attachAndPost(page, clip, 'A short clip');
    expect(media).toMatchObject({ kind: 'video', mime: 'video/mp4' });
    await expectShown(page, 'A short clip');
    await expectShown(page, 'Play video 1 of 1, 0:03');

    // It plays over the page in the browser's own player; Escape closes it.
    await tap(page, 'button', /^Play video 1 of 1/);
    await page.waitForFunction(() => { const v = document.querySelector('video'); return v && v.currentTime > 0.5; }, null, { timeout: 20_000 });
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('video'), null, { timeout: 5000 });
});
