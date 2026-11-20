// A fresh team for a spec file, made the way the billing control plane makes one (the signed admin
// API), and the people and agents in it, over HTTP. The browser parts are in ui.mjs.
import { randomBytes } from 'node:crypto';
import { admin, client, deviceSignIn, strongPassword } from '../../server/deploy/journey.mjs';
import { env, local } from './env.mjs';

export const send = client(local ? { connect: env.connect, testRouter: true } : {});
export const adminCall = admin(send, env.origin, env.adminKey);

const hex = (n) => randomBytes(n).toString('hex');

/** An HTTP failure with what the server said. */
function check(res, what, ...ok) {
    if (!ok.includes(res.status)) throw new Error(`${what}: answered ${res.status} ${res.text.slice(0, 300)}`);
    return res.json;
}

/** A person: their email, password, name, and a device key for the API. */
export class Person {
    constructor(team, { email, password, name, token, membershipId }) {
        Object.assign(this, { team, email, password, name, token, membershipId });
    }

    /** A call to the team's API as this person. */
    async api(method, path, body) {
        return send(`${this.team.address}${path}`, { method, headers: { authorization: `Bearer ${this.token}` }, body });
    }

    async ok(method, path, body, ...statuses) {
        return check(await this.api(method, path, body), `${method} ${path}`, ...(statuses.length ? statuses : [200, 201]));
    }
}

/** An agent with a token: MCP tools called as it. */
export class Agent {
    constructor(team, { name, token }) {
        Object.assign(this, { team, name, token });
    }

    /** One MCP tool call; its text answer (an error throws). */
    async call(tool, args = {}) {
        const res = await send(`${this.team.address}/mcp`, {
            method: 'POST',
            headers: { authorization: `Bearer ${this.token}`, accept: 'application/json, text/event-stream' },
            body: { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: tool, arguments: args } },
        });
        const json = check(res, `mcp ${tool}`, 200);
        if (json.error) throw new Error(`mcp ${tool}: ${JSON.stringify(json.error)}`);
        if (json.result?.isError) throw new Error(`mcp ${tool}: ${json.result.content?.[0]?.text}`);
        return json.result?.content?.[0]?.text ?? '';
    }

    /** A tool's structured answer: tools answer "<summary>\n<JSON>". */
    async json(tool, args) {
        const text = await this.call(tool, args);
        const start = text.indexOf('{');
        return JSON.parse(text.slice(start));
    }
}

export class Team {
    /**
     * A new team with an owner who has set a password and signed in over HTTP. [label]: a few
     * letters, for its address. With `setUp: false` the owner has only a setup link
     * (team.setupUrl, team.ownerEmail), as a new customer has.
     */
    static async create(label, { setUp = true } = {}) {
        const run = hex(3);
        const slug = `e2e-${label.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 14)}-${run}`;
        const email = `owner-${run}@${slug}.example`;
        const made = check(await adminCall('POST', '/admin/teams', { slug, name: `E2E ${label}`, owner: { email, name: 'Olive Owner' } }), 'make a team', 201);
        const team = new Team(made.team.id, slug, made.team.address);
        team.setupUrl = made.owner.setupUrl;
        team.ownerEmail = email;
        if (!setUp) return team;
        const password = strongPassword();
        const form = new URLSearchParams({ password, again: password }).toString();
        const setup = await send(made.owner.setupUrl, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', origin: team.address }, body: form });
        if (!setup.text.includes('Your password is set')) throw new Error(`setting the owner's password: ${setup.status} ${setup.text.slice(0, 200)}`);
        team.owner = await team.signIn({ email, password, name: 'Olive Owner', membershipId: made.owner.membershipId });
        return team;
    }

    constructor(id, slug, address) {
        Object.assign(this, { id, slug, address, people: [] });
    }

    /** [who] signed in over HTTP, as the apps sign in. */
    async signIn(who) {
        const res = await deviceSignIn(send, this.address, { email: who.email, password: who.password });
        const json = check(res, `sign in ${who.email}`, 201);
        const person = new Person(this, { ...who, token: json.token });
        this.people.push(person);
        return person;
    }

    /** Someone the owner invited (over the API), who has accepted and signed in. */
    async invite({ name = 'Sam Member', role = 'member' } = {}) {
        const email = `${name.split(' ')[0].toLowerCase()}-${hex(3)}@${this.slug}.example`;
        const invite = await this.owner.ok('POST', '/api/invites', { email, role });
        const password = strongPassword();
        const accepted = check(await send(`${this.address}/api/invite/accept`, {
            method: 'POST', body: { token: invite.link, name, email, password, deviceName: 'E2E device' },
        }), 'accept the invite', 201);
        const person = new Person(this, { email, password, name, token: accepted.token, membershipId: accepted.person?.id });
        this.people.push(person);
        return person;
    }

    /** An agent, paired as a real one pairs: it asks, the owner allows, it collects its token. */
    async agent(name = 'builder') {
        const asked = check(await send(`${this.address}/api/pair`, { method: 'POST', body: { name } }), 'ask to pair', 201);
        await this.owner.ok('POST', `/api/pairings/${asked.pairing_id}/approve`, {});
        for (let i = 0; i < 5; i++) {
            const polled = check(await send(asked.poll_url, { headers: { authorization: `Bearer ${asked.poll_secret}` } }), 'collect the token', 200);
            if (polled.token) return new Agent(this, { name, token: polled.token });
        }
        throw new Error(`pairing ${name}: no token`);
    }

    /** Gone again, so a staging server doesn't fill with test teams. */
    async delete() {
        const res = await adminCall('DELETE', `/admin/teams/${this.id}`);
        if (res.status !== 202 && res.status !== 404) throw new Error(`delete ${this.slug}: ${res.status}`);
    }
}
