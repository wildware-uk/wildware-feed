// Driven by test-stack.sh: stands in for Heroku's router in front of the test server. It takes https
// (a throwaway self-signed certificate), forwards plain http to the server, and adds
// X-Forwarded-For and X-Forwarded-Proto as the router does, so the server sees what it sees in
// production: https addresses, Secure __Host- cookies, Sec-Fetch-Site on every form post.
//
// Test only: a request's X-Journey-Client header picks the client address the server sees, so each
// person in the test has an address of their own and the sign-in lockout counts them apart.
import { readFileSync } from 'node:fs';
import http from 'node:http';
import https from 'node:https';

const { ROUTER_PORT, SERVER_PORT, CERT, KEY } = process.env;

https.createServer({ cert: readFileSync(CERT), key: readFileSync(KEY) }, (req, res) => {
    const headers = { ...req.headers };
    const client = headers['x-journey-client'] || req.socket.remoteAddress;
    delete headers['x-journey-client'];
    headers['x-forwarded-for'] = headers['x-forwarded-for'] ? `${headers['x-forwarded-for']}, ${client}` : client;
    headers['x-forwarded-proto'] = 'https';
    headers['x-forwarded-port'] = '443';
    const upstream = http.request({ host: '127.0.0.1', port: SERVER_PORT, method: req.method, path: req.url, headers }, (answer) => {
        res.writeHead(answer.statusCode, answer.rawHeaders);
        answer.pipe(res);
    });
    upstream.on('error', (e) => { res.writeHead(502); res.end(`router: ${e.message}`); });
    req.pipe(upstream);
}).listen(Number(ROUTER_PORT), '127.0.0.1', () => console.log(`router: https on ${ROUTER_PORT}`));
