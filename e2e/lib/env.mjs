// Where the suite runs, from the environment: a local stack (server/deploy/test-stack.sh sets these)
// or a deployed staging server (the staging-e2e workflow sets them from its inputs and secrets).
//
//   E2E_ORIGIN           where the admin API answers, e.g. https://feed.test or the staging origin
//   E2E_ADMIN_KEY        the admin signing key (or E2E_ADMIN_KEY_FILE, a file holding it)
//   E2E_CONNECT          local only: host:port every test address really is (the test router)
//   E2E_HOME_ORIGIN      optional: the home team's address, with E2E_HOME_PASSWORD its owner password
//   E2E_CONTROL_PLANE_ORIGIN  the server's CONTROL_PLANE_ORIGIN, the page "Open" posts from (default: E2E_ORIGIN)
import { readFileSync } from 'node:fs';

const need = (name) => {
    const value = process.env[name];
    if (!value) throw new Error(`${name} is not set: run the suite through server/deploy/test-stack.sh, or set it for a deployed server`);
    return value;
};

export const env = {
    origin: need('E2E_ORIGIN').replace(/\/+$/, ''),
    get adminKey() {
        if (process.env.E2E_ADMIN_KEY) return process.env.E2E_ADMIN_KEY;
        return readFileSync(need('E2E_ADMIN_KEY_FILE'), 'utf8').trim();
    },
    connect: process.env.E2E_CONNECT || null,
    homeOrigin: process.env.E2E_HOME_ORIGIN?.replace(/\/+$/, '') || null,
    homePassword: process.env.E2E_HOME_PASSWORD || null,
    get controlPlane() { return (process.env.E2E_CONTROL_PLANE_ORIGIN || this.origin).replace(/\/+$/, ''); },
};

/** Against the local stack: self-signed certificate, made-up client addresses, every test name mapped to the router. */
export const local = env.connect !== null;
