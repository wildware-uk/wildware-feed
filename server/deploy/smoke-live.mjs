// Driven by smoke-live.sh: the customer journey against a live server, over HTTP. Makes two
// throwaway teams through the signed admin API, sets each owner's password on its setup page,
// signs in with it as the apps do, invites and signs in a member, checks neither team's password
// (nor the home team's, when given) opens the other, then deletes both teams, whatever happened.
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { admin, checks, client, crossTeam, deviceJourney, deviceSignIn, makeTeam, say, setPasswordByForm, strongPassword } from './journey.mjs';

const origin = process.env.ORIGIN?.replace(/\/+$/, '');
if (!origin) throw new Error('ORIGIN: the admin API origin, such as https://feed.example.com');
const key = readFileSync(process.env.ADMIN_KEY_FILE, 'utf8').trim();
const homeAddress = process.env.HOME_ORIGIN?.replace(/\/+$/, '');
const homePassword = process.env.HOME_PASSWORD_FILE ? readFileSync(process.env.HOME_PASSWORD_FILE, 'utf8').trim() : undefined;

// CONNECT (testing this script only): send every request to that host:port, trusting its certificate.
const send = client(process.env.CONNECT ? { connect: process.env.CONNECT, testRouter: true } : {});
const adminCall = admin(send, origin, key);
const c = checks();
const run = randomBytes(3).toString('hex');
const made = [];

try {
    const a = await makeTeam(adminCall, c, { slug: `journey-${run}-a`, name: `Journey ${run} A`, email: `journey-${run}-a@example.com` });
    made.push(a);
    const b = await makeTeam(adminCall, c, { slug: `journey-${run}-b`, name: `Journey ${run} B`, email: `journey-${run}-b@example.com` });
    made.push(b);
    say(`teams ${a.address} and ${b.address}`);
    for (const t of [a, b]) {
        t.password = strongPassword();
        await setPasswordByForm(send, c, t, t.password);
    }
    const { ownerToken } = await deviceJourney(send, adminCall, c, a, a.password);
    const okAtA = () => deviceSignIn(send, a.address, { email: a.email, password: a.password });
    await crossTeam(send, c, a, b, { ownerTokenOfA: ownerToken, homeAddress, homePassword, signInOkAt: okAtA });
    if (!homePassword) say('skipped the home team (no HOME_ORIGIN and HOME_PASSWORD_FILE)');
} catch (e) {
    c.expect('the journey ran to the end', false, e.message);
} finally {
    for (const t of made) {
        const gone = await adminCall('DELETE', `/admin/teams/${t.id}`).catch((e) => ({ status: e.message }));
        c.expect(`delete ${t.slug}`, gone.status === 202, `answered ${gone.status}; delete it by hand`);
    }
}
c.finish();
