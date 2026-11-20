// Helpers for the media, cards and requests specs.
import { execFileSync } from 'node:child_process';
import { openChannel } from './ui.mjs';

/**
 * One MCP tool call as [agent]; its structured answer. A tool answers "<summary>\n<JSON>", so
 * Agent.json (which parses the whole text) can't read it: this reads the JSON after the summary.
 */
export async function mcp(agent, tool, args = {}) {
    const text = await agent.call(tool, args);
    const start = text.indexOf('\n{');
    return { summary: start < 0 ? text : text.slice(0, start), ...(start < 0 ? {} : JSON.parse(text.slice(start + 1))) };
}

/** Labels of every [role] in the accessibility tree, for exploring and for messages. */
export const labels = (page, role = 'button') => page.getByRole(role).evaluateAll((es) => es.map((e) => e.getAttribute('aria-label') || e.textContent));

/** Runs ffmpeg quietly with [args]. */
export function ffmpeg(...args) {
    execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
}

/** Kept for the specs that use it: ui.openChannel now waits for the heading itself. */
export const openChannelSurely = openChannel;
