// The web app's end-to-end suite (README.md). Against the local stack, every test address is mapped
// to the test router and its self-signed certificate trusted; against staging, nothing is.
import { defineConfig } from '@playwright/test';
import { env, local } from './lib/env.mjs';

const args = [];
if (local) {
    const home = env.homeOrigin ? `,MAP ${new URL(env.homeOrigin).hostname} ${env.connect}` : '';
    const domain = new URL(env.origin).hostname;
    args.push(`--host-resolver-rules=MAP *.${domain} ${env.connect},MAP ${domain} ${env.connect}${home}`, '--ignore-certificate-errors');
}

const run = local ? `-${env.connect.split(':')[1]}` : '';

export default defineConfig({
    testDir: './specs',
    testMatch: '*.spec.mjs',
    // Each spec file has a team of its own, so files run side by side; tests in a file run in order.
    fullyParallel: false,
    workers: process.env.CI ? 3 : 2,
    timeout: 90_000,
    expect: { timeout: 15_000 },
    retries: 0,
    forbidOnly: !!process.env.CI,
    // Apart per stack (its router port), so runs against two local stacks don't share files.
    reporter: [['list'], ['html', { outputFolder: `report${run}`, open: 'never' }]],
    outputDir: `results${run}`,
    use: {
        launchOptions: { executablePath: process.env.CHROME || undefined, args },
        viewport: { width: 1280, height: 1000 },
        screenshot: 'only-on-failure',
        trace: 'retain-on-failure',
    },
});
