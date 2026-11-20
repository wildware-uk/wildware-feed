// The customer journey over HTTP, as the billing control plane and the apps drive it: make a team
// through the signed admin API, set the owner's password on the setup page, sign in with it the way
// the phone and desktop apps do, invite someone, and check no password opens another team.
//
// Used by journey-web.mjs (CI, with a browser on top) and by smoke-live.mjs (after each deploy, no
// browser). No packages: Node's own http, https and crypto.
import { createHash, createHmac, randomBytes } from 'node:crypto';
import http from 'node:http';
import https from 'node:https';

export const say = (what) => console.log(`journey: ${what}`);

/** A made-up client address, so the test router (journey-router.mjs) counts each caller apart. */
export const someAddress = () => `10.${[...randomBytes(3)].join('.')}`;

/**
 * A client for the server. For CI only: `connect` sends every request to that host:port whatever
 * the address says, as a DNS entry would; `testRouter` trusts its self-signed certificate and
 * gives each request a client address of its own.
 */
export function client({ connect, testRouter = false } = {}) {
    return function send(url, { method = 'GET', headers = {}, body } = {}) {
        const u = new URL(url);
        const data = body == null ? null : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body));
        const all = { host: u.host, ...(testRouter ? { 'x-journey-client': someAddress() } : {}), ...headers };
        if (data) {
            all['content-length'] = String(data.length);
            if (!Object.keys(all).some((k) => k.toLowerCase() === 'content-type')) all['content-type'] = 'application/json';
        }
        const [host, port] = connect ? connect.split(':') : [u.hostname, u.port];
        const tls = u.protocol === 'https:';
        const options = { method, host, port: port || (tls ? 443 : 80), path: u.pathname + u.search, headers: all, servername: u.hostname, rejectUnauthorized: !testRouter };
        return new Promise((resolve, reject) => {
            const req = (tls ? https : http).request(options, (res) => {
                const chunks = [];
                res.on('data', (c) => chunks.push(c));
                res.on('end', () => {
                    const text = Buffer.concat(chunks).toString('utf8');
                    let json = null;
                    try { json = JSON.parse(text); } catch { /* a page, not JSON */ }
                    resolve({ status: res.statusCode, headers: res.headers, text, json });
                });
            });
            req.setTimeout(30_000, () => req.destroy(new Error(`${method} ${url}: no answer in 30 s`)));
            req.on('error', reject);
            if (data) req.write(data);
            req.end();
        });
    };
}

/**
 * The admin API, signed as the control plane signs it (docs/HOSTING.md §7; the server's own
 * AdminApi.message and AdminApi.sign): five lines, v1, unix seconds, method, path and query, the
 * body's SHA-256; HMAC-SHA256 under the key, hex. The key itself never leaves this process.
 */
export function admin(send, origin, key) {
    const EMPTY = createHash('sha256').update('').digest('hex');
    return async function call(method, path, body) {
        const data = body == null ? '' : JSON.stringify(body);
        const time = String(Math.floor(Date.now() / 1000));
        const digest = data ? createHash('sha256').update(data).digest('hex') : EMPTY;
        const message = ['v1', time, method, path, digest].join('\n');
        const signature = 'v1=' + createHmac('sha256', key).update(message).digest('hex');
        const headers = { 'feed-admin-timestamp': time, 'feed-admin-signature': signature };
        if (method === 'POST') headers['idempotency-key'] = randomBytes(12).toString('hex');
        return send(`${origin}${path}`, { method, headers, body: data || undefined });
    };
}

/** Collects what went wrong and keeps going, so one run shows every broken step, not just the first. */
export function checks() {
    const failed = [];
    return {
        failed,
        /** A check: [ok] or the step is marked failed with [why]. [known] names a bug it is known to catch. */
        expect(step, ok, why, known) {
            if (ok) { say(`ok   ${step}`); return true; }
            const line = `${step}: ${why}${known ? `  [known bug: ${known}]` : ''}`;
            say(`FAIL ${line}`);
            failed.push(line);
            return false;
        },
        finish() {
            if (failed.length === 0) { say('every step passed'); return; }
            say(`${failed.length} step(s) failed:`);
            for (const f of failed) say(`  - ${f}`);
            process.exitCode = 1;
        },
    };
}

/** The bug this journey was written for (2026-11-20): a hosted team's owner set a password, and signing in with it said "incorrect". */
export const FOUNDER_BUG = "a hosted team's owner signs in with the password alone (People.signInFounder looked for the home team's owner)";

export const strongPassword = () => 'J-' + randomBytes(12).toString('base64url');

/** A fresh team and its owner, through the admin API; their setup link. */
export async function makeTeam(adminCall, c, { slug, name, email }) {
    const made = await adminCall('POST', '/admin/teams', { slug, name, owner: { email, name: `${name} owner` } });
    if (!c.expect(`admin: make team ${slug}`, made.status === 201, `answered ${made.status} ${made.text.slice(0, 200)}`)) {
        throw new Error(`could not make team ${slug}; nothing after this can run`);
    }
    return { id: made.json.team.id, slug, address: made.json.team.address, email, setupUrl: made.json.owner.setupUrl };
}

/** The setup page, filled in as a browser would: GET the form, POST the password twice. */
export async function setPasswordByForm(send, c, team, password) {
    const page = await send(team.setupUrl);
    c.expect(`${team.slug}: setup link opens the form`, page.status === 200 && page.text.includes('<form'), `answered ${page.status}`);
    const form = new URLSearchParams({ password, again: password }).toString();
    const done = await send(team.setupUrl, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded', origin: team.address },
        body: form,
    });
    c.expect(`${team.slug}: setup says "Your password is set"`, done.status === 200 && done.text.includes('Your password is set'), `answered ${done.status} ${done.text.slice(0, 200)}`);
    const again = await send(team.setupUrl);
    c.expect(`${team.slug}: the setup link works once`, again.status === 404, `a used link answered ${again.status}`);
}

/** POST /api/devices/sign-in, as the phone and desktop apps call it. */
export async function deviceSignIn(send, address, { password, email }) {
    const body = { password, name: 'Journey test device' };
    if (email !== undefined) body.email = email;
    return send(`${address}/api/devices/sign-in`, { method: 'POST', body });
}

const bearer = (token) => ({ authorization: `Bearer ${token}` });

/** A post straight into the feed, then read back with the same key. */
export async function postAndReadBack(send, c, label, address, token) {
    const text = `Journey check ${randomBytes(4).toString('hex')}`;
    const posted = await send(`${address}/api/messages`, { method: 'POST', headers: bearer(token), body: { body: text } });
    c.expect(`${label}: post a message`, posted.status === 201, `answered ${posted.status} ${posted.text.slice(0, 200)}`);
    const read = await send(`${address}/api/messages`, { headers: bearer(token) });
    c.expect(`${label}: read the message back`, read.status === 200 && read.text.includes(text), `answered ${read.status}; the message ${read.text.includes(text) ? 'is' : 'is not'} there`);
    return text;
}

/**
 * Everything but the browser, on [team] whose owner has [password] set:
 * the apps' password sign-ins, a ticket sign-in, an invited member, and a wrong password refused.
 */
export async function deviceJourney(send, adminCall, c, team, password) {
    const founder = await deviceSignIn(send, team.address, { password });
    const founderOk = c.expect(`${team.slug}: apps sign in with the owner password alone`, founder.status === 201, `answered ${founder.status} ${founder.text.slice(0, 160)}`, FOUNDER_BUG);
    if (founderOk) await postAndReadBack(send, c, `${team.slug} owner (password alone)`, team.address, founder.json.token);

    const withEmail = await deviceSignIn(send, team.address, { password, email: team.email });
    c.expect(`${team.slug}: apps sign in with the owner's email and password`, withEmail.status === 201, `answered ${withEmail.status} ${withEmail.text.slice(0, 160)}`);
    const ownerToken = withEmail.json?.token;
    if (ownerToken) await postAndReadBack(send, c, `${team.slug} owner (email)`, team.address, ownerToken);

    const wrong = await deviceSignIn(send, team.address, { password: password + 'x' });
    c.expect(`${team.slug}: a wrong owner password is refused`, wrong.status === 400, `answered ${wrong.status}`);

    // The "Open" button: the control plane's ticket, traded for a device key.
    const ticket = await adminCall('POST', `/admin/teams/${team.id}/sign-in-tickets`, { email: team.email });
    if (c.expect(`${team.slug}: admin: sign-in ticket`, ticket.status === 201, `answered ${ticket.status} ${ticket.text.slice(0, 160)}`)) {
        const traded = await send(`${team.address}/api/devices/ticket`, { method: 'POST', body: { ticket: ticket.json.ticket, deviceName: 'Journey ticket device' } });
        c.expect(`${team.slug}: a ticket signs the apps in`, traded.status === 201, `answered ${traded.status}`);
        const reused = await send(`${team.address}/api/devices/ticket`, { method: 'POST', body: { ticket: ticket.json.ticket, deviceName: 'Journey ticket device' } });
        c.expect(`${team.slug}: a ticket works once`, reused.status === 401, `a used ticket answered ${reused.status}`);
    }

    // A second person: invited by the control plane, joins, signs in with email and password.
    const member = { email: `member-${randomBytes(3).toString('hex')}@${team.slug}.example`, password: strongPassword() };
    const invite = await adminCall('POST', `/admin/teams/${team.id}/invites`, { email: member.email, role: 'member' });
    if (c.expect(`${team.slug}: admin: invite a member`, invite.status === 201, `answered ${invite.status} ${invite.text.slice(0, 160)}`)) {
        const accepted = await send(`${team.address}/api/invite/accept`, {
            method: 'POST',
            body: { token: invite.json.url, name: 'Journey Member', email: member.email, password: member.password, deviceName: 'Journey member phone' },
        });
        c.expect(`${team.slug}: the member accepts the invite`, accepted.status === 201, `answered ${accepted.status} ${accepted.text.slice(0, 160)}`);
        const signedIn = await deviceSignIn(send, team.address, { email: member.email, password: member.password });
        if (c.expect(`${team.slug}: apps sign the member in with email and password`, signedIn.status === 201, `answered ${signedIn.status} ${signedIn.text.slice(0, 160)}`)) {
            await postAndReadBack(send, c, `${team.slug} member`, team.address, signedIn.json.token);
        }
        const wrongMember = await deviceSignIn(send, team.address, { email: member.email, password: member.password + 'x' });
        c.expect(`${team.slug}: a wrong member password is refused`, wrongMember.status === 400, `answered ${wrongMember.status}`);
        // A success after each refusal, so the five-in-15-minutes lockout never trips the run itself.
        await deviceSignIn(send, team.address, { email: member.email, password: member.password });
    }
    return { member, ownerToken };
}

/** No password, and no key, from one team opens another. */
export async function crossTeam(send, c, a, b, { ownerTokenOfA, homeAddress, homePassword, signInOkAt } = {}) {
    const other = await deviceSignIn(send, a.address, { password: b.password });
    c.expect(`${b.slug}'s owner password does not open ${a.slug}`, other.status === 400, `answered ${other.status}`);
    await signInOkAt();
    const otherEmail = await deviceSignIn(send, a.address, { password: b.password, email: b.email });
    c.expect(`${b.slug}'s owner email and password do not open ${a.slug}`, otherEmail.status === 400, `answered ${otherEmail.status}`);
    await signInOkAt();
    if (ownerTokenOfA) {
        const read = await send(`${b.address}/api/snapshot`, { headers: bearer(ownerTokenOfA) });
        c.expect(`a device key from ${a.slug} does not read ${b.slug}`, read.status === 401, `answered ${read.status}`);
    }
    if (homePassword) {
        const home = await deviceSignIn(send, a.address, { password: homePassword });
        c.expect(`the home team's owner password does not open ${a.slug}`, home.status === 400, `answered ${home.status}`);
        await signInOkAt();
        const homeOk = await deviceSignIn(send, homeAddress, { password: homePassword });
        c.expect('the home team still signs in with its owner password', homeOk.status === 201, `answered ${homeOk.status} ${homeOk.text.slice(0, 160)}`);
        const hosted = await deviceSignIn(send, homeAddress, { password: a.password });
        c.expect(`${a.slug}'s owner password does not open the home team`, hosted.status === 400, `answered ${hosted.status}`);
        await deviceSignIn(send, homeAddress, { password: homePassword });
    }
}
