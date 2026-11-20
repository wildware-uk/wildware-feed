// Driven by journey.sh. A new hosted team's owner, then a teammate, then the home team, in a real
// browser on each team's own https address, plus the same sign-ins over HTTP the way the phone and
// desktop apps make them.
//
// The web app draws on one canvas, but Compose mirrors what it draws into an accessibility tree
// (roles and labels, in a shadow root): the test finds boxes and buttons there by name, clicks
// where they are drawn, and reads what the screen says from it.
//
// Every step is checked and the run goes on after a failure, so one run lists every broken step.
// A step marked [known bug: ...] is one this test was written to catch.
import { chromium } from 'playwright';
import { admin, checks, client, crossTeam, deviceJourney, deviceSignIn, FOUNDER_BUG, makeTeam, say, setPasswordByForm, someAddress, strongPassword } from './journey.mjs';

const home = 'https://home.test';
const { ROUTER_PORT: port, TEAM_DOMAIN: domain, ADMIN_KEY: key, HOME_PASSWORD: homePassword, SHOTS: shots } = process.env;
const send = client({ connect: `127.0.0.1:${port}`, testRouter: true });
const adminCall = admin(send, `https://${domain}`, key);
const c = checks();

/** Found 2026-11-20: the web app's sign-in never asked the server how to sign in, so it never offered an Email box. */
const WEB_EMAIL_BUG = 'the web sign-in has no Email box (App.kt fixes the web host check to the owner password)';
/** Found 2026-11-20: "Open" set the cookie, but the web app only trusts a session its own form saved in local storage. */
const WEB_OPEN_BUG = 'the web app ignores the cookie a ticket sets, and shows the sign-in form';

const browser = await chromium.launch({
    executablePath: process.env.CHROME || undefined,
    args: [
        // Every team's address, the directory and the home team reach the test router, as DNS would
        // send them; its certificate is the test's own.
        `--host-resolver-rules=MAP *.${domain} 127.0.0.1:${port},MAP ${domain} 127.0.0.1:${port},MAP home.test 127.0.0.1:${port}`,
        '--ignore-certificate-errors',
    ],
});
const errors = [];
let shot = 0;
const snap = (page, name) => page.screenshot({ path: `${shots}/${String(++shot).padStart(2, '0')}-${name}.png` });

async function newPage() {
    // Each browser is its own person, at an address of its own.
    const context = await browser.newContext({
        viewport: { width: 1280, height: 800 }, colorScheme: 'light', ignoreHTTPSErrors: true,
        extraHTTPHeaders: { 'x-journey-client': someAddress() },
    });
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    return { context, page };
}

// ---- reading and driving the canvas through its accessibility tree ------------------------------

/** What the screen says: every label and text in the accessibility tree, one per line. */
const screenText = (page) => page.evaluate(() => {
    const root = [...document.querySelectorAll('*')].find((e) => e.shadowRoot)?.shadowRoot;
    const out = [];
    const walk = (n) => {
        for (const child of n.children) {
            if (child.tagName === 'STYLE') continue;
            const own = [...child.childNodes].filter((x) => x.nodeType === 3).map((x) => x.textContent).join('').trim();
            const label = child.getAttribute('aria-label');
            if (label || own) out.push(label || own);
            walk(child);
        }
    };
    if (root) walk(root);
    return out.join('\n');
}).catch(() => '');

/** Waits until the screen says [text]; whether it did. */
async function shows(page, text, timeout = 10_000) {
    const until = Date.now() + timeout;
    while (Date.now() < until) {
        if ((await screenText(page)).includes(text)) return true;
        await page.waitForTimeout(250);
    }
    return false;
}

/** The [role] named [name] (a string matches exactly), or null when the screen has none. */
async function find(page, role, name, timeout = 10_000) {
    const target = page.getByRole(role, { name, exact: typeof name === 'string' }).first();
    try {
        await target.waitFor({ state: 'attached', timeout });
        return target;
    } catch {
        return null;
    }
}

/** Clicks where [role] [name] is drawn (the canvas takes the click, not the tree's element), scrolling it into view first. */
async function tap(page, role, name) {
    const target = await find(page, role, name);
    let box = await target?.boundingBox();
    if (!box) throw new Error(`no ${role} "${name}" on the screen`);
    const { height } = page.viewportSize();
    for (let i = 0; i < 6 && (box.y < 0 || box.y + box.height > height); i++) {
        await page.mouse.move(640, height / 2);
        await page.mouse.wheel(0, box.y + box.height / 2 - height / 2);
        await page.waitForTimeout(400);
        box = await target.boundingBox();
    }
    await page.mouse.click(box.x + Math.min(box.width / 2, 40), box.y + box.height / 2);
    await page.waitForTimeout(250);
}

async function typeInto(page, name, text) {
    await tap(page, 'textbox', name);
    await page.keyboard.type(text, { delay: 20 });
}

/** The web app at [address], drawn and settled. */
async function openApp(page, address) {
    await page.goto(address);
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    await page.waitForTimeout(1500);
}

const feedLoads = (page) => page.waitForResponse((r) => r.url().includes('/api/snapshot') && r.status() === 200, { timeout: 30_000 }).catch(() => null);

/**
 * The sign-in form: the email (when given) in the Email box, the password, Enter. The sign-in's
 * HTTP status, or why it couldn't be tried.
 */
async function signInByForm(page, { email, password }) {
    if (email) {
        if (!(await find(page, 'textbox', 'Email', 3000))) return 'no Email box on the form';
        await typeInto(page, 'Email', email);
    }
    const box = (await find(page, 'textbox', 'Password', 1000)) ? 'Password' : 'Owner password';
    await typeInto(page, box, password);
    const answer = page.waitForResponse((r) => r.url().endsWith('/api/devices/sign-in'), { timeout: 30_000 }).catch(() => null);
    await page.keyboard.press('Enter');
    return (await answer)?.status() ?? 'no answer';
}

const signedInCookie = async (context) => (await context.cookies()).some((x) => x.name.endsWith('feed_device'));

/** Sign out from the side bar: revokes this browser, clears its cookie, back to the sign-in form. */
async function signOut(page, context, label) {
    const out = page.waitForResponse((r) => r.url().endsWith('/api/devices/sign-out'), { timeout: 10_000 }).catch(() => null);
    await tap(page, 'button', 'Sign out');
    const answer = await out;
    const back = await shows(page, 'Sign in');
    const cookie = await signedInCookie(context);
    c.expect(`${label}: sign out`, answer?.status() === 200 && !cookie && back, `sign-out answered ${answer?.status() ?? 'nothing'}; cookie ${cookie ? 'still there' : 'gone'}; sign-in form ${back ? 'shown' : 'not shown'}`);
}

/** A new channel from the side bar. */
async function newChannel(page, label, name) {
    const made = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/projects'), { timeout: 10_000 }).catch(() => null);
    await tap(page, 'button', 'New channel');
    await typeInto(page, 'Channel name', name);
    await tap(page, 'button', 'Create channel');
    const answer = await made;
    c.expect(`${label}: make channel "${name}"`, answer?.status() === 201, `answered ${answer?.status() ?? 'nothing'}`);
    // The accessibility tree keeps a closed dialog's contents until the page is drawn afresh.
    await page.reload();
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    await page.waitForTimeout(1500);
}

/** A post from the composer into [channel], then a reload that must show it. */
async function postInBrowser(page, label, channel) {
    const text = `Hello from the journey, ${label}`;
    await tap(page, 'button', new RegExp(`^${channel},`));
    await typeInto(page, 'Message', text);
    const posted = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/messages'), { timeout: 15_000 }).catch(() => null);
    await tap(page, 'button', 'Send');
    const answer = await posted;
    c.expect(`${label}: post a message from the web app`, answer?.status() === 201, `the post answered ${answer?.status() ?? 'nothing'}`);
    await page.reload();
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    const seen = await shows(page, text, 15_000);
    await snap(page, `${label.replace(/\W+/g, '-')}-after-reload`);
    c.expect(`${label}: after a reload the message is on the screen`, seen, 'not shown');
}

async function step(name, run) {
    try {
        await run();
    } catch (e) {
        c.expect(name, false, `stopped: ${e.message.split('\n')[0]}`);
    }
}

// ---- 1. A new team's owner, in the browser ------------------------------------------------------
const acme = await makeTeam(adminCall, c, { slug: 'acme', name: 'Acme', email: 'owner@acme.example' });
acme.password = strongPassword();
await step('acme owner in the browser', async () => {
    const { context, page } = await newPage();
    await page.goto(acme.setupUrl);
    await page.fill('input[name=password]', acme.password);
    await page.fill('input[name=again]', acme.password);
    await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);
    const text = await page.locator('body').innerText();
    await snap(page, 'acme-password-set');
    c.expect('acme: the setup page sets a password and says "Your password is set"', text.includes('Your password is set'), `the page says: ${text.slice(0, 200)}`);

    // "Open" from the control plane: its page posts a one-time ticket to the team's address.
    const ticket = await adminCall('POST', `/admin/teams/${acme.id}/sign-in-tickets`, { email: acme.email });
    c.expect('acme: admin: sign-in ticket for Open', ticket.status === 201, `answered ${ticket.status} ${ticket.text.slice(0, 160)}`);
    await page.route(`https://${domain}/journey-open`, (route) => route.fulfill({
        contentType: 'text/html',
        body: `<!doctype html><form method="post" action="${acme.address}/auth/ticket"><input type="hidden" name="ticket" value="${ticket.json?.ticket}"></form><script>document.forms[0].submit()</script>`,
    }));
    const opened = feedLoads(page);
    await page.goto(`https://${domain}/journey-open`);
    const openedOk = (await opened) !== null && (await shows(page, 'All channels'));
    await snap(page, 'acme-opened');
    if (c.expect('acme: Open (a ticket) signs the browser in to the feed', openedOk, 'the sign-in form showed', WEB_OPEN_BUG)) {
        await newChannel(page, 'acme owner', 'Launch');
        await signOut(page, context, 'acme owner after Open');
    }

    // The bug: the password just set, in the web app's own sign-in form.
    await openApp(page, acme.address);
    await snap(page, 'acme-sign-in-form');
    c.expect('acme: the sign-in form offers an Email box', (await find(page, 'textbox', 'Email', 3000)) !== null, 'only an owner password box', WEB_EMAIL_BUG);
    let feed = feedLoads(page);
    const alone = await signInByForm(page, { password: acme.password });
    let signedIn = c.expect('acme: web app signs the owner in with the password alone', alone === 201, `sign-in answered ${alone}`, FOUNDER_BUG);
    if (!signedIn) {
        await snap(page, 'acme-password-alone-refused');
        await openApp(page, acme.address);
        feed = feedLoads(page);
        const withEmail = await signInByForm(page, { email: acme.email, password: acme.password });
        signedIn = c.expect('acme: web app signs the owner in with email and password', withEmail === 201, `sign-in answered ${withEmail}`, withEmail === 201 ? undefined : WEB_EMAIL_BUG);
    }
    if (signedIn) {
        c.expect('acme: the feed shows after signing in', (await feed) !== null && (await shows(page, 'All channels')), 'no feed');
        if (!openedOk) await newChannel(page, 'acme owner', 'Launch');
        await postInBrowser(page, 'acme owner', 'Launch');
        await signOut(page, context, 'acme owner');
    } else {
        say('skipped: acme owner posting from the web app (could not sign in)');
    }
    await context.close();
});
await step('acme wrong password in the browser', async () => {
    const { context, page } = await newPage();
    await openApp(page, acme.address);
    const wrong = await signInByForm(page, { password: acme.password + 'x' });
    const said = await shows(page, "That password isn't right");
    await snap(page, 'acme-wrong-password');
    c.expect('acme: web app refuses a wrong password and says so', wrong === 400 && said && !(await signedInCookie(context)), `sign-in answered ${wrong}; message ${said ? 'shown' : 'not shown'}`);
    await context.close();
});

// ---- 2. A second person: invited, joins in the browser, signs in with email and password -------
const sam = { email: 'sam@acme.example', password: strongPassword() };
await step('Sam joins in the browser', async () => {
    // The owner's channel, made over HTTP if the browser steps above could not (a member can't make one).
    const owner = await deviceSignIn(send, acme.address, { email: acme.email, password: acme.password });
    if (owner.json?.token) await send(`${acme.address}/api/projects`, { method: 'POST', headers: { authorization: `Bearer ${owner.json.token}` }, body: { name: 'Launch' } });
    const invite = await adminCall('POST', `/admin/teams/${acme.id}/invites`, { email: sam.email, role: 'member' });
    c.expect('acme: admin: invite Sam', invite.status === 201, `answered ${invite.status} ${invite.text.slice(0, 160)}`);
    const { context, page } = await newPage();
    await page.goto(invite.json.url);
    await Promise.all([page.waitForNavigation(), page.click('text=Or join in this browser')]);
    await page.waitForFunction(() => !document.getElementById('loading'), null, { timeout: 60_000 });
    c.expect('acme: the invite opens the join form', await shows(page, 'Join a team'), 'no join form');
    await typeInto(page, 'Your name', 'Sam');
    await typeInto(page, 'Email', sam.email);
    await typeInto(page, 'Choose a password', sam.password);
    const accepted = page.waitForResponse((r) => r.url().endsWith('/api/invite/accept'), { timeout: 30_000 }).catch(() => null);
    // Join the team is drawn below the window; Enter in the last box joins, as it does for a keyboard user.
    await tap(page, 'textbox', 'Name for this device');
    await page.keyboard.press('Enter');
    const answer = await accepted;
    const joined = await shows(page, 'All channels', 15_000);
    await snap(page, 'sam-joined');
    c.expect('acme: Sam joins from the invite and sees the feed', answer?.status() === 201 && joined, `accept answered ${answer?.status() ?? 'nothing'}; feed ${joined ? 'shown' : 'not shown'}`);
    if (joined) {
        await postInBrowser(page, 'acme Sam', 'Launch');
        await signOut(page, context, 'acme Sam');
    }
    await context.close();
});
await step('Sam signs in in the browser', async () => {
    const { context, page } = await newPage();
    await openApp(page, acme.address);
    const feed = feedLoads(page);
    const signedIn = await signInByForm(page, { email: sam.email, password: sam.password });
    await snap(page, 'sam-sign-in');
    if (c.expect('acme: web app signs Sam in with email and password', signedIn === 201, `sign-in answered ${signedIn}`, signedIn === 201 ? undefined : WEB_EMAIL_BUG)) {
        c.expect('acme: Sam sees the feed, with his own message', (await feed) !== null && (await shows(page, 'Hello from the journey, acme Sam')), 'not shown');
    }
    await context.close();
});

// ---- 3. The home team, at its own address: its owner password still signs in, and posts --------
await step('home team in the browser', async () => {
    const { context, page } = await newPage();
    await openApp(page, home);
    const feed = feedLoads(page);
    const signedIn = await signInByForm(page, { password: homePassword });
    c.expect('home: web app signs the owner in with the password', signedIn === 201, `sign-in answered ${signedIn}`);
    c.expect('home: the feed shows', (await feed) !== null && (await shows(page, 'All channels')), 'no feed');
    await newChannel(page, 'home owner', 'Garden');
    await postInBrowser(page, 'home owner', 'Garden');
    c.expect("home: acme's channel is not here", !(await screenText(page)).includes('Launch'), 'the home team shows Launch');
    await signOut(page, context, 'home owner');
    await context.close();
});

// ---- 4. The apps' sign-ins over HTTP, and other teams kept out ----------------------------------
say('the apps, over HTTP');
await step('the apps over HTTP', async () => {
    const { ownerToken } = await deviceJourney(send, adminCall, c, acme, acme.password);
    const globex = await makeTeam(adminCall, c, { slug: 'globex', name: 'Globex', email: 'owner@globex.example' });
    globex.password = strongPassword();
    await setPasswordByForm(send, c, globex, globex.password);
    const okAtAcme = () => deviceSignIn(send, acme.address, { email: acme.email, password: acme.password });
    await crossTeam(send, c, acme, globex, { ownerTokenOfA: ownerToken, homeAddress: home, homePassword, signInOkAt: okAtAcme });
});

await browser.close();
c.expect('the pages threw nothing', errors.length === 0, errors.join('; '));
c.finish();
