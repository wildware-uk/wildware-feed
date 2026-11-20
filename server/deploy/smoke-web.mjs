// Driven by smoke-web.sh. The app is one canvas, so it is driven by keyboard and by where things
// are drawn at a fixed window size; what the server got is checked through the network.
import { chromium } from 'playwright';

const { BASE: base, TOKEN: token, CLIP: clip, SHOTS: shots } = process.env;
const step = (what) => console.log(`smoke-web: ${what}`);

async function mcp(name, args) {
    const res = await fetch(`${base}/mcp`, {
        method: 'POST',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } }),
    });
    const body = await res.json();
    if (body.result?.isError) throw new Error(`${name}: ${body.result.content[0].text}`);
    return body.result.content[0].text;
}

await mcp('create_project', { name: 'Garden game', description: 'A cosy farming game' });
await mcp('post_update', {
    project: 'garden-game', level: 'success', title: 'Watering can now fills at wells',
    body: 'The can fills when you stand by a **well**.\n\n- 12 uses per fill\n- works on every crop\n\n```kotlin\nfun fill(can: Can) = can.copy(water = 12)\n```',
});
await mcp('request_input', { project: 'garden-game', question: 'Which crop should come first in spring? 🌱', kind: 'choice', options: ['Carrots', 'Tomatoes', 'Pumpkins'], wait_seconds: 0 });

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
const errors = [];

async function open(options) {
    const context = await browser.newContext(options);
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(base);
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    await page.waitForTimeout(1500);
    return { context, page };
}

async function signIn(page) {
    // The password box has the keyboard from the start; Enter signs in.
    await page.keyboard.type('smoke-password', { delay: 20 });
    const signedIn = page.waitForResponse((r) => r.url().endsWith('/api/devices/sign-in'));
    const fed = page.waitForResponse((r) => r.url().includes('/api/snapshot?'), { timeout: 30_000 });
    await page.keyboard.press('Enter');
    const answer = await signedIn;
    if (answer.status() !== 201) throw new Error(`sign-in answered ${answer.status()}`);
    await fed;
    await page.waitForTimeout(2500);
}

// A click on the canvas now and then gets no answer (seen once in CI: the file dialog never
// opened, though the screenshot just before shows the button in place). Click again after a
// short wait rather than wait longer for a click the app never saw.
async function clickUntil(page, x, y, expect, tries = 3) {
    for (let i = 1; ; i++) {
        const answer = expect(5_000);
        await page.mouse.click(x, y);
        try {
            return await answer;
        } catch (e) {
            if (e.name !== 'TimeoutError' || i >= tries) throw e;
            step(`nothing answered the click at ${x},${y}; clicking again`);
        }
    }
}

// Desktop, light.
{
    const { context, page } = await open({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
    await page.screenshot({ path: `${shots}/sign-in-light.png` });
    step('the sign-in screen drew');
    await signIn(page);
    const cookies = await context.cookies();
    const session = cookies.find((c) => c.name === 'feed_device');
    if (!session?.httpOnly || session.sameSite !== 'Strict') throw new Error(`no HttpOnly, SameSite=Strict session cookie: ${JSON.stringify(cookies)}`);
    const stored = await page.evaluate(() => Object.keys(localStorage).map((k) => localStorage.getItem(k)).join('\n'));
    if (stored.includes(session.value)) throw new Error('the device key is in local storage');
    await page.screenshot({ path: `${shots}/feed-light.png` });
    step('signed in; the feed drew; the key is only in an HttpOnly cookie');

    // A reply from the composer at the bottom, with a video attached.
    const chooser = await clickUntil(page, 305, 756, (timeout) => page.waitForEvent('filechooser', { timeout }));
    await chooser.setFiles([clip]);
    await page.mouse.click(600, 756);
    await page.waitForTimeout(300);
    await page.keyboard.type('Carrots first, from the web app', { delay: 15 });
    const posted = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/messages'), { timeout: 30_000 });
    await page.keyboard.press('Control+Enter');
    const reply = await posted;
    if (reply.status() !== 201) throw new Error(`the reply answered ${reply.status()}`);
    const seen = await mcp('get_messages', { project: 'garden-game' });
    if (!seen.includes('Carrots first, from the web app')) throw new Error(`the agent did not get the reply: ${seen.slice(0, 300)}`);
    step('a reply went up with a video, and the agent read it');

    // The video plays over the page in the browser's own player.
    await page.waitForTimeout(3000);
    await page.mouse.move(620, 500);
    await page.mouse.wheel(0, 250);
    await page.waitForTimeout(1000);
    await page.screenshot({ path: `${shots}/feed-reply-light.png` });
    await page.mouse.click(500, 520);
    await page.waitForFunction(() => { const v = document.querySelector('video'); return v && v.currentTime > 0.5; }, null, { timeout: 20_000 });
    await page.screenshot({ path: `${shots}/video.png` });
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('video'));
    step('the video played, and Escape closed it');

    // Signing out revokes this browser and clears the cookie.
    const out = await clickUntil(page, 200, 768, (timeout) => page.waitForResponse((r) => r.url().endsWith('/api/devices/sign-out'), { timeout }));
    if (out.status() !== 200) throw new Error('sign-out failed');
    await page.waitForTimeout(500);
    if ((await context.cookies()).some((c) => c.name === 'feed_device')) throw new Error('the cookie outlived sign-out');
    step('signed out');
    await context.close();
}

// Desktop dark, and a phone.
for (const [name, options] of [
    ['feed-dark', { viewport: { width: 1280, height: 800 }, colorScheme: 'dark' }],
    ['phone-light', { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, colorScheme: 'light' }],
    ['phone-dark', { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, colorScheme: 'dark' }],
]) {
    const { context, page } = await open(options);
    await signIn(page);
    await page.screenshot({ path: `${shots}/${name}.png` });
    step(`${name} drew`);
    await context.close();
}

await browser.close();
if (errors.length) throw new Error(`the page threw: ${errors.join('; ')}`);
