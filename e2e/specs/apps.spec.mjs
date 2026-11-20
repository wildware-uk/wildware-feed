// The phone and desktop apps' side, over HTTP: password sign-ins (alone, and with an email), a
// ticket, an invited member; and no password of one team, or of the home team, opening another.
// The same checks server/deploy/smoke-live.sh runs against production after a deploy.
import { expect, test } from '@playwright/test';
import { checks, crossTeam, deviceJourney, deviceSignIn } from '../../server/deploy/journey.mjs';
import { env } from '../lib/env.mjs';
import { adminCall, send, Team } from '../lib/team.mjs';

let a;
let b;
test.beforeAll(async () => { [a, b] = await Promise.all([Team.create('appsa'), Team.create('appsb')]); });
test.afterAll(async () => { await Promise.all([a?.delete(), b?.delete()]); });

/** The journey's shape of a team: its address and its owner's email and password. */
const plain = (t) => ({ id: t.id, slug: t.slug, address: t.address, email: t.owner.email, password: t.owner.password });

test('the apps sign in: owner by password, with and without email; a ticket; a member', async () => {
    const c = checks();
    await deviceJourney(send, adminCall, c, plain(a), a.owner.password);
    expect(c.failed).toEqual([]);
});

test("no team's password, key or the home team's password opens another team", async () => {
    const c = checks();
    const okAtA = () => deviceSignIn(send, a.address, { email: a.owner.email, password: a.owner.password });
    await crossTeam(send, c, plain(a), plain(b), {
        ownerTokenOfA: a.owner.token, homeAddress: env.homeOrigin, homePassword: env.homePassword, signInOkAt: okAtA,
    });
    expect(c.failed).toEqual([]);
});
