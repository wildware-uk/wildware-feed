// Records what npm `file-type` 22.0.2 says about many byte heads, so the Kotlin
// port (`media/Sniff.kt`) can be checked against it byte for byte.
//
// Usage:
//   node record.mjs <file-type/source/index.js> <work-dir> [out.json] [--large]
//
// - <file-type/source/index.js>: the entry file of an installed file-type 22.0.2.
// - <work-dir>: an empty scratch folder; ffmpeg and the archivers write real files
//   there. Nothing in it is needed afterwards.
// - out.json: where to write the fixture (default: corpus.json next to this file).
// - --large: write many more random mutations (for a one-off local run; too big
//   to commit). SniffTest reads such a file when SNIFF_EXTRA_CORPUS names it.
//
// The fixture is {"fileType": "...", "cases": [{"n": label, "h": base64 head,
// "m": mime or null}]}. Heads are at most 4100 bytes, as the server only ever
// sniffs the first 4100 bytes of an upload. Everything is deterministic: random
// bytes come from a seeded generator, and ffmpeg runs in bit-exact mode.
//
// Needs ffmpeg, zip, gzip, bzip2, xz, zstd and tar on PATH; a missing tool or a
// failing command only drops the cases it would have made.

import {execFileSync} from 'node:child_process';
import {existsSync, mkdirSync, readFileSync, utimesSync, writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import zlib from 'node:zlib';

const args = process.argv.slice(2).filter(a => !a.startsWith('--'));
const large = process.argv.includes('--large');
if (args.length < 2) {
	console.error('usage: node record.mjs <file-type/source/index.js> <work-dir> [out.json] [--large]');
	process.exit(2);
}

const fileTypeEntry = path.resolve(args[0]);
const work = path.resolve(args[1]);
const out = args[2] ? path.resolve(args[2]) : path.join(path.dirname(fileURLToPath(import.meta.url)), 'corpus.json');
mkdirSync(work, {recursive: true});

const {fileTypeFromBuffer} = await import(pathToFileURL(fileTypeEntry).href);
const fileTypePackage = JSON.parse(readFileSync(path.join(path.dirname(fileTypeEntry), '..', 'package.json'), 'utf8'));

const HEAD = 4100;

// --- deterministic randomness ---------------------------------------------------

let seed = Number(process.env.SNIFF_SEED ?? 0x5EED_1234); // Another SNIFF_SEED gives other random cases.
function random() { // Mulberry32
	seed = (seed + 0x6D2B79F5) | 0;
	let t = seed;
	t = Math.imul(t ^ (t >>> 15), t | 1);
	t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
	return ((t ^ (t >>> 14)) >>> 0) / 4_294_967_296;
}

const randomInt = n => Math.floor(random() * n);
const randomBytes = n => Uint8Array.from({length: n}, () => randomInt(256));

// --- byte helpers ---------------------------------------------------------------

const bytes = (...parts) => {
	const chunks = parts.map(p => typeof p === 'string' ? Buffer.from(p, 'latin1') : Buffer.from(p));
	return new Uint8Array(Buffer.concat(chunks));
};

const zeros = n => new Uint8Array(n);
const u16le = v => [v & 0xFF, (v >> 8) & 0xFF];
const u32le = v => [v & 0xFF, (v >>> 8) & 0xFF, (v >>> 16) & 0xFF, (v >>> 24) & 0xFF];
const u32be = v => [(v >>> 24) & 0xFF, (v >>> 16) & 0xFF, (v >>> 8) & 0xFF, v & 0xFF];
const u16be = v => [(v >> 8) & 0xFF, v & 0xFF];
const pad = (head, n, fill = 'zero') => bytes(head, fill === 'zero' ? zeros(Math.max(0, n - head.length)) : randomBytes(Math.max(0, n - head.length)));

// --- the cases ------------------------------------------------------------------

const seeds = []; // [label, bytes] - real or hand-made files, mutated later
const cases = []; // [label, head]

function add(label, data) {
	cases.push([label, data.subarray(0, HEAD)]);
}

function addSeed(label, data) {
	seeds.push([label, data.subarray(0, HEAD)]);
	add(label, data);
}

const TRUNCATIONS = [2, 3, 4, 8, 12, 16, 32, 36, 64, 100, 257, 512];

function addTruncations(label, data) {
	for (const n of TRUNCATIONS) {
		if (n < data.length) {
			add(`${label} [:${n}]`, data.subarray(0, n));
		}
	}
}

// --- real files from ffmpeg -------------------------------------------------------

const VIDEO = ['-f', 'lavfi', '-i', 'testsrc=size=64x48:rate=10:duration=1'];
const VIDEO1 = ['-f', 'lavfi', '-i', 'testsrc=size=64x48:rate=1:duration=1'];
const VIDEO25 = ['-f', 'lavfi', '-i', 'testsrc=size=64x48:rate=25:duration=0.4'];
const AUDIO = ['-f', 'lavfi', '-i', 'sine=frequency=440:duration=1:sample_rate=44100'];
const AUDIO8 = ['-f', 'lavfi', '-i', 'sine=frequency=440:duration=1:sample_rate=8000'];
const AUDIO22 = ['-f', 'lavfi', '-i', 'sine=frequency=440:duration=1:sample_rate=22050'];
const EXACT = ['-fflags', '+bitexact', '-flags:v', '+bitexact', '-flags:a', '+bitexact', '-map_metadata', '-1'];

let fileCounter = 0;
function ffmpeg(label, ext, inputs, options) {
	const file = path.join(work, `f${String(fileCounter++).padStart(3, '0')}.${ext}`);
	try {
		execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...inputs, ...EXACT, ...options, file], {stdio: ['ignore', 'ignore', 'pipe']});
	} catch (error) {
		console.warn(`skip ${label}: ${String(error.stderr ?? error.message).split('\n')[0]}`);
		return;
	}

	const data = new Uint8Array(readFileSync(file));
	addSeed(label, data);
	addTruncations(label, data);
	return data;
}

const av = [...VIDEO, ...AUDIO];

// ISO-BMFF
const mp4 = ffmpeg('mp4 h264+aac', 'mp4', av, ['-c:v', 'libx264', '-c:a', 'aac', '-f', 'mp4']);
ffmpeg('mp4 faststart', 'mp4', av, ['-c:v', 'libx264', '-c:a', 'aac', '-movflags', '+faststart', '-f', 'mp4']);
ffmpeg('mp4 fragmented', 'mp4', av, ['-c:v', 'libx264', '-c:a', 'aac', '-movflags', 'frag_keyframe+empty_moov', '-f', 'mp4']);
ffmpeg('mp4 audio-only aac', 'mp4', AUDIO, ['-c:a', 'aac', '-f', 'mp4']);
ffmpeg('mp4 hevc', 'mp4', VIDEO, ['-c:v', 'libx265', '-x265-params', 'log-level=none', '-f', 'mp4']);
ffmpeg('mp4 mpeg4', 'mp4', VIDEO, ['-c:v', 'mpeg4', '-f', 'mp4']);
for (const brand of ['mp42', 'M4V ', 'M4VH', 'M4VP', 'M4A ', 'M4B ', 'M4P ', 'F4V ', 'F4P ', 'F4A ', 'F4B ', 'qt  ', '3gp4', '3gp6', '3g2a', 'avc1', 'iso5', 'dash', 'heic', 'mif1', 'avif', 'crx ', 'MSNV']) {
	ffmpeg(`mp4 -brand ${brand.trim()}`, 'mp4', av, ['-c:v', 'libx264', '-c:a', 'aac', '-brand', brand, '-f', 'mp4']);
}

ffmpeg('mov h264+aac', 'mov', av, ['-c:v', 'libx264', '-c:a', 'aac', '-f', 'mov']);
ffmpeg('mov faststart', 'mov', av, ['-c:v', 'libx264', '-c:a', 'aac', '-movflags', '+faststart', '-f', 'mov']);
ffmpeg('mov prores', 'mov', VIDEO, ['-c:v', 'prores', '-f', 'mov']);
ffmpeg('m4a ipod aac', 'm4a', AUDIO, ['-c:a', 'aac', '-f', 'ipod']);
ffmpeg('m4a ipod alac', 'm4a', AUDIO, ['-c:a', 'alac', '-f', 'ipod']);
ffmpeg('m4v muxer', 'm4v', VIDEO, ['-c:v', 'mpeg4', '-f', 'm4v']);
ffmpeg('3gp', '3gp', [...VIDEO, ...AUDIO8], ['-c:v', 'libx264', '-c:a', 'aac', '-f', '3gp']);
ffmpeg('3g2', '3g2', [...VIDEO, ...AUDIO8], ['-c:v', 'libx264', '-c:a', 'aac', '-f', '3g2']);
ffmpeg('psp', 'mp4', av, ['-c:v', 'libx264', '-c:a', 'aac', '-f', 'psp']);
ffmpeg('ismv', 'ismv', av, ['-c:v', 'libx264', '-c:a', 'aac', '-f', 'ismv']);
ffmpeg('f4v', 'f4v', av, ['-c:v', 'libx264', '-c:a', 'aac', '-f', 'f4v']);
ffmpeg('avif still', 'avif', VIDEO1, ['-frames:v', '1', '-c:v', 'libaom-av1', '-cpu-used', '8', '-f', 'avif']);
ffmpeg('avif sequence', 'avif', VIDEO, ['-frames:v', '3', '-c:v', 'libaom-av1', '-cpu-used', '8', '-f', 'avif']);

// EBML
ffmpeg('webm vp8+vorbis', 'webm', av, ['-c:v', 'libvpx', '-c:a', 'libvorbis', '-f', 'webm']);
ffmpeg('webm vp9+opus', 'webm', av, ['-c:v', 'libvpx-vp9', '-c:a', 'libopus', '-f', 'webm']);
ffmpeg('webm audio opus', 'webm', AUDIO, ['-c:a', 'libopus', '-f', 'webm']);
ffmpeg('mkv h264+aac', 'mkv', av, ['-c:v', 'libx264', '-c:a', 'aac', '-f', 'matroska']);
ffmpeg('mkv ffv1+flac', 'mkv', av, ['-c:v', 'ffv1', '-c:a', 'flac', '-f', 'matroska']);
ffmpeg('mka flac', 'mka', AUDIO, ['-c:a', 'flac', '-f', 'matroska']);
ffmpeg('mkv vp9 (matroska doctype)', 'mkv', VIDEO, ['-c:v', 'libvpx-vp9', '-f', 'matroska']);

// Ogg
ffmpeg('ogg vorbis', 'ogg', AUDIO, ['-c:a', 'libvorbis', '-f', 'ogg']);
ffmpeg('ogg native vorbis', 'ogg', AUDIO, ['-c:a', 'vorbis', '-strict', '-2', '-ac', '2', '-f', 'ogg']);
ffmpeg('ogg opus', 'ogg', AUDIO, ['-c:a', 'libopus', '-f', 'ogg']);
ffmpeg('opus muxer', 'opus', AUDIO, ['-c:a', 'libopus', '-f', 'opus']);
ffmpeg('ogv theora', 'ogv', VIDEO, ['-c:v', 'libtheora', '-f', 'ogg']);
ffmpeg('ogv theora+vorbis', 'ogv', av, ['-c:v', 'libtheora', '-c:a', 'libvorbis', '-f', 'ogg']);
ffmpeg('ogg flac', 'oga', AUDIO, ['-c:a', 'flac', '-f', 'ogg']);
ffmpeg('oga muxer flac', 'oga', AUDIO, ['-c:a', 'flac', '-f', 'oga']);
ffmpeg('spx speex', 'spx', AUDIO8, ['-c:a', 'libspeex', '-f', 'spx']);

// MPEG audio
ffmpeg('mp3 lame default id3', 'mp3', AUDIO, ['-c:a', 'libmp3lame', '-f', 'mp3']);
ffmpeg('mp3 id3v2.3', 'mp3', AUDIO, ['-c:a', 'libmp3lame', '-id3v2_version', '3', '-f', 'mp3']);
ffmpeg('mp3 no id3', 'mp3', AUDIO, ['-c:a', 'libmp3lame', '-id3v2_version', '0', '-f', 'mp3']);
ffmpeg('mp3 no id3 no xing', 'mp3', AUDIO, ['-c:a', 'libmp3lame', '-id3v2_version', '0', '-write_xing', '0', '-f', 'mp3']);
ffmpeg('mp3 22050 (mpeg2)', 'mp3', AUDIO22, ['-c:a', 'libmp3lame', '-id3v2_version', '0', '-f', 'mp3']);
ffmpeg('mp3 8000 (mpeg2.5)', 'mp3', AUDIO8, ['-c:a', 'libmp3lame', '-id3v2_version', '0', '-f', 'mp3']);
ffmpeg('mp3 shine', 'mp3', AUDIO, ['-c:a', 'libshine', '-id3v2_version', '0', '-f', 'mp3']);
ffmpeg('mp3 big id3 tag', 'mp3', AUDIO, ['-c:a', 'libmp3lame', '-metadata', `comment=${'x'.repeat(6000)}`, '-f', 'mp3']);
ffmpeg('mp3 id3 tag of 3000', 'mp3', AUDIO, ['-c:a', 'libmp3lame', '-write_xing', '0', '-metadata', `comment=${'y'.repeat(3000)}`, '-f', 'mp3']);
ffmpeg('mp2', 'mp2', AUDIO, ['-c:a', 'mp2', '-f', 'mp2']);
ffmpeg('mp2 twolame', 'mp2', AUDIO, ['-c:a', 'libtwolame', '-f', 'mp2']);
ffmpeg('aac adts', 'aac', AUDIO, ['-c:a', 'aac', '-f', 'adts']);
ffmpeg('aac adts mono 8k', 'aac', AUDIO8, ['-c:a', 'aac', '-f', 'adts']);
ffmpeg('aac latm', 'latm', AUDIO, ['-c:a', 'aac', '-f', 'latm']);

// RIFF and other audio
ffmpeg('wav s16', 'wav', AUDIO, ['-c:a', 'pcm_s16le', '-f', 'wav']);
ffmpeg('wav u8', 'wav', AUDIO8, ['-c:a', 'pcm_u8', '-f', 'wav']);
ffmpeg('wav rf64', 'wav', AUDIO, ['-c:a', 'pcm_s16le', '-rf64', 'always', '-f', 'wav']);
ffmpeg('wav adpcm', 'wav', AUDIO, ['-c:a', 'adpcm_ms', '-f', 'wav']);
ffmpeg('wav mp3 inside', 'wav', AUDIO, ['-c:a', 'libmp3lame', '-f', 'wav']);
ffmpeg('w64', 'w64', AUDIO, ['-c:a', 'pcm_s16le', '-f', 'w64']);
ffmpeg('avi mpeg4+pcm', 'avi', av, ['-c:v', 'mpeg4', '-c:a', 'pcm_s16le', '-f', 'avi']);
ffmpeg('avi mjpeg', 'avi', VIDEO, ['-c:v', 'mjpeg', '-f', 'avi']);
ffmpeg('flac', 'flac', AUDIO, ['-c:a', 'flac', '-f', 'flac']);
ffmpeg('aiff', 'aiff', AUDIO, ['-c:a', 'pcm_s16be', '-f', 'aiff']);
ffmpeg('caf', 'caf', AUDIO, ['-c:a', 'pcm_s16le', '-f', 'caf']);
ffmpeg('au', 'au', AUDIO, ['-c:a', 'pcm_s16be', '-f', 'au']);
ffmpeg('voc', 'voc', AUDIO8, ['-c:a', 'pcm_u8', '-f', 'voc']);
ffmpeg('wavpack', 'wv', AUDIO, ['-c:a', 'wavpack', '-f', 'wv']);
ffmpeg('tta', 'tta', AUDIO, ['-c:a', 'tta', '-f', 'tta']);
ffmpeg('ac3', 'ac3', AUDIO, ['-c:a', 'ac3', '-f', 'ac3']);
ffmpeg('eac3', 'eac3', AUDIO, ['-c:a', 'eac3', '-f', 'eac3']);
ffmpeg('dts', 'dts', AUDIO, ['-c:a', 'dca', '-strict', '-2', '-ac', '2', '-f', 'dts']);
ffmpeg('truehd', 'thd', AUDIO, ['-c:a', 'truehd', '-strict', '-2', '-f', 'truehd']);
ffmpeg('gsm', 'gsm', AUDIO8, ['-c:a', 'libgsm', '-f', 'gsm']);
ffmpeg('mmf', 'mmf', AUDIO8, ['-c:a', 'adpcm_yamaha', '-f', 'mmf']);
ffmpeg('wma', 'wma', AUDIO, ['-c:a', 'wmav2', '-f', 'asf']);
ffmpeg('wmv', 'wmv', av, ['-c:v', 'wmv2', '-c:a', 'wmav2', '-f', 'asf']);
ffmpeg('wtv', 'wtv', av, ['-c:v', 'mpeg2video', '-c:a', 'mp2', '-f', 'wtv']);
ffmpeg('rm', 'rm', av, ['-c:v', 'rv10', '-c:a', 'ac3', '-f', 'rm']);
ffmpeg('sox', 'sox', AUDIO, ['-c:a', 'pcm_s32le', '-f', 'sox']);
ffmpeg('ircam', 'sf', AUDIO, ['-c:a', 'pcm_s16le', '-f', 'ircam']);

// Other video containers
ffmpeg('flv', 'flv', av, ['-c:v', 'flv', '-c:a', 'aac', '-f', 'flv']);
ffmpeg('swf', 'swf', VIDEO, ['-c:v', 'flv', '-f', 'swf']);
ffmpeg('mpegts', 'ts', av, ['-c:v', 'mpeg2video', '-c:a', 'mp2', '-f', 'mpegts']);
ffmpeg('m2ts', 'm2ts', av, ['-c:v', 'libx264', '-c:a', 'aac', '-mpegts_m2ts_mode', '1', '-f', 'mpegts']);
ffmpeg('mpeg-ps', 'mpg', av, ['-c:v', 'mpeg2video', '-c:a', 'mp2', '-f', 'mpeg']);
ffmpeg('mpeg1 system (vcd)', 'mpg', [...VIDEO25, ...AUDIO], ['-c:v', 'mpeg1video', '-c:a', 'mp2', '-f', 'vcd']);
ffmpeg('vob', 'vob', av, ['-c:v', 'mpeg2video', '-c:a', 'ac3', '-f', 'vob']);
ffmpeg('mpeg1video raw', 'm1v', VIDEO25, ['-c:v', 'mpeg1video', '-f', 'mpeg1video']);
ffmpeg('mpeg2video raw', 'm2v', VIDEO, ['-c:v', 'mpeg2video', '-f', 'mpeg2video']);
ffmpeg('h264 raw', 'h264', VIDEO, ['-c:v', 'libx264', '-f', 'h264']);
ffmpeg('hevc raw', 'hevc', VIDEO, ['-c:v', 'libx265', '-x265-params', 'log-level=none', '-f', 'hevc']);
ffmpeg('ivf', 'ivf', VIDEO, ['-c:v', 'libvpx', '-f', 'ivf']);
ffmpeg('nut', 'nut', av, ['-c:v', 'ffv1', '-c:a', 'pcm_s16le', '-f', 'nut']);
ffmpeg('mxf', 'mxf', VIDEO25, ['-c:v', 'mpeg2video', '-f', 'mxf']);
ffmpeg('gxf', 'gxf', VIDEO25, ['-s', '720x576', '-c:v', 'mpeg2video', '-f', 'gxf']);
ffmpeg('dv', 'dv', VIDEO, ['-s', '720x576', '-r', '25', '-pix_fmt', 'yuv420p', '-c:v', 'dvvideo', '-f', 'dv']);
ffmpeg('y4m', 'y4m', VIDEO, ['-pix_fmt', 'yuv420p', '-f', 'yuv4mpegpipe']);
ffmpeg('mjpeg raw', 'mjpeg', VIDEO, ['-c:v', 'mjpeg', '-f', 'mjpeg']);
ffmpeg('amv', 'amv', [...VIDEO, ...['-f', 'lavfi', '-i', 'sine=duration=1:sample_rate=22050']], ['-c:v', 'amv', '-c:a', 'adpcm_ima_amv', '-block_size', '2205', '-r', '10', '-f', 'amv']);

// Images
ffmpeg('png', 'png', VIDEO1, ['-frames:v', '1', '-c:v', 'png', '-f', 'image2']);
ffmpeg('png rgba', 'png', VIDEO1, ['-frames:v', '1', '-pix_fmt', 'rgba', '-c:v', 'png', '-f', 'image2']);
ffmpeg('apng', 'apng', VIDEO, ['-c:v', 'apng', '-plays', '0', '-f', 'apng']);
ffmpeg('apng one frame', 'apng', VIDEO1, ['-frames:v', '1', '-c:v', 'apng', '-f', 'apng']);
ffmpeg('gif', 'gif', VIDEO1, ['-frames:v', '1', '-f', 'gif']);
ffmpeg('gif animated', 'gif', VIDEO, ['-f', 'gif']);
ffmpeg('webp lossy', 'webp', VIDEO1, ['-frames:v', '1', '-c:v', 'libwebp', '-f', 'webp']);
ffmpeg('webp lossless', 'webp', VIDEO1, ['-frames:v', '1', '-c:v', 'libwebp', '-lossless', '1', '-f', 'webp']);
ffmpeg('webp animated', 'webp', VIDEO, ['-c:v', 'libwebp_anim', '-f', 'webp']);
ffmpeg('jpeg', 'jpg', VIDEO1, ['-frames:v', '1', '-c:v', 'mjpeg', '-f', 'image2']);
ffmpeg('jpeg-ls', 'jls', VIDEO1, ['-frames:v', '1', '-c:v', 'jpegls', '-f', 'image2']);
ffmpeg('jpeg2000 j2k', 'j2k', VIDEO1, ['-frames:v', '1', '-c:v', 'jpeg2000', '-f', 'image2']);
ffmpeg('jpeg2000 jp2', 'jp2', VIDEO1, ['-frames:v', '1', '-c:v', 'libopenjpeg', '-format', 'jp2', '-f', 'image2']);
ffmpeg('jpeg xl', 'jxl', VIDEO1, ['-frames:v', '1', '-c:v', 'libjxl', '-f', 'image2']);
ffmpeg('bmp', 'bmp', VIDEO1, ['-frames:v', '1', '-c:v', 'bmp', '-f', 'image2']);
ffmpeg('tiff', 'tif', VIDEO1, ['-frames:v', '1', '-c:v', 'tiff', '-f', 'image2']);
ffmpeg('ico', 'ico', VIDEO1, ['-frames:v', '1', '-c:v', 'bmp', '-f', 'ico']);
ffmpeg('pcx', 'pcx', VIDEO1, ['-frames:v', '1', '-c:v', 'pcx', '-f', 'image2']);
ffmpeg('tga', 'tga', VIDEO1, ['-frames:v', '1', '-c:v', 'targa', '-f', 'image2']);
ffmpeg('ppm', 'ppm', VIDEO1, ['-frames:v', '1', '-c:v', 'ppm', '-f', 'image2']);
ffmpeg('pam', 'pam', VIDEO1, ['-frames:v', '1', '-c:v', 'pam', '-f', 'image2']);
ffmpeg('sgi', 'sgi', VIDEO1, ['-frames:v', '1', '-c:v', 'sgi', '-f', 'image2']);
ffmpeg('xbm', 'xbm', VIDEO1, ['-frames:v', '1', '-pix_fmt', 'monow', '-c:v', 'xbm', '-f', 'image2']);
ffmpeg('xwd', 'xwd', VIDEO1, ['-frames:v', '1', '-c:v', 'xwd', '-f', 'image2']);
ffmpeg('sunrast', 'sun', VIDEO1, ['-frames:v', '1', '-c:v', 'sunrast', '-f', 'image2']);
ffmpeg('exr', 'exr', VIDEO1, ['-frames:v', '1', '-pix_fmt', 'gbrpf32le', '-c:v', 'exr', '-f', 'image2']);
ffmpeg('hdr', 'hdr', VIDEO1, ['-frames:v', '1', '-pix_fmt', 'gbrpf32le', '-c:v', 'hdr', '-f', 'image2']);
ffmpeg('dpx', 'dpx', VIDEO1, ['-frames:v', '1', '-c:v', 'dpx', '-f', 'image2']);
ffmpeg('qoi', 'qoi', VIDEO1, ['-frames:v', '1', '-c:v', 'qoi', '-f', 'image2']);
ffmpeg('fits', 'fits', VIDEO1, ['-frames:v', '1', '-pix_fmt', 'gray', '-c:v', 'fits', '-f', 'fits']);

// --- ISO-BMFF major brands, patched into a real mp4 head -------------------------

if (mp4) {
	const brands = ['avif', 'avis', 'mif1', 'msf1', 'heic', 'heix', 'hevc', 'hevx', 'qt  ', 'qt\0\0', 'qt \0', '\0qt ', 'M4V ', 'M4VH', 'M4VP', 'M4V\0', 'M4P ', 'M4B ', 'M4A ', 'M4A\0', 'm4a ', 'F4V ', 'F4P ', 'F4A ', 'F4B ', 'crx ', '3gp4', '3gp5', '3g2a', '3g2\0', '3g\0\0', '3gx ', 'isom', 'mp41', 'mp42', 'XAVC', 'MSNV', ' qt ', '\tqt ', 'qt\t ', 'qt\n\0', 'qt \0', 'avif\0', ' M4A', ' M4A', '\x80abc', '\x20\0\0\0', '\x1Fabc', '\x60abc', '\x40abc', '\x9Fabc', '\0\0\0\0', '\0M4A'];
	for (const brand of brands) {
		const data = new Uint8Array(mp4.subarray(0, 64));
		const b = Buffer.from(brand.slice(0, 4), 'latin1');
		data.set(b, 8);
		add(`mp4 brand ${JSON.stringify(brand)}`, data);
		add(`mp4 brand ${JSON.stringify(brand)} [:12]`, data.subarray(0, 12));
	}
}

// Old QuickTime files start with an atom other than ftyp.
for (const atom of ['free', 'mdat', 'moov', 'wide', 'skip', 'pnot', 'ftyp']) {
	addSeed(`qt atom ${atom}`, pad(bytes(u32be(8), atom, u32be(16), 'mdat'), 300, 'random'));
}

// --- hand-made magic numbers --------------------------------------------------------

const magic = [
	['bmp', 'BM'], ['ac3', [0x0B, 0x77]], ['dmg', [0x78, 0x01]], ['exe', 'MZ'], ['ps', '%!'],
	['eps', '%!PS-Adobe-3.0 EPSF-3.0\n'], ['Z a0', [0x1F, 0xA0]], ['Z 9d', [0x1F, 0x9D]], ['cpio', [0xC7, 0x71]],
	['arj', [0x60, 0xEA]], ['gif', 'GIF89a'], ['jxr', [0x49, 0x49, 0xBC]], ['bz2', 'BZh91AY'],
	['mpc', 'MP+'], ['swf C', 'CWS'], ['swf F', 'FWS'], ['jpg', [0xFF, 0xD8, 0xFF, 0xE0]], ['jls', [0xFF, 0xD8, 0xFF, 0xF7]],
	['jpg 3 bytes', [0xFF, 0xD8, 0xFF]], ['avro', [0x4F, 0x62, 0x6A, 0x01]], ['flif', 'FLIF'], ['psd', '8BPS'], ['mpck', 'MPCK'],
	['aiff', 'FORM'], ['icns', 'icns'], ['zip 0304 empty', [0x50, 0x4B, 3, 4]], ['zip 0506', [0x50, 0x4B, 5, 6]], ['zip 0708', [0x50, 0x4B, 7, 8]],
	['zip 0306', [0x50, 0x4B, 3, 6]], ['zip 0504', [0x50, 0x4B, 5, 4]], ['PK 0102', [0x50, 0x4B, 1, 2]],
	['midi', 'MThd'], ['woff', bytes('wOFF', [0, 1, 0, 0])], ['woff otto', 'wOFFOTTO'], ['woff bad', 'wOFFabcd'],
	['woff2', bytes('wOF2', [0, 1, 0, 0])], ['woff2 otto', 'wOF2OTTO'], ['pcap le', [0xD4, 0xC3, 0xB2, 0xA1]], ['pcap be', [0xA1, 0xB2, 0xC3, 0xD4]],
	['dsf', 'DSD '], ['lz', 'LZIP'], ['flac', 'fLaC'], ['bpg', [0x42, 0x50, 0x47, 0xFB]], ['wv', 'wvpk'], ['pdf', '%PDF-1.7\n'],
	['wasm', [0x00, 0x61, 0x73, 0x6D, 1, 0, 0, 0]], ['ape', 'MAC '], ['ebml empty', [0x1A, 0x45, 0xDF, 0xA3]], ['sqlite', 'SQLite format 3\0'],
	['nes', [0x4E, 0x45, 0x53, 0x1A]], ['crx', 'Cr24'], ['cab', 'MSCF'], ['cab isc', 'ISc('], ['rpm', [0xED, 0xAB, 0xEE, 0xDB]],
	['eps binary', [0xC5, 0xD0, 0xD3, 0xC6]], ['zst', [0x28, 0xB5, 0x2F, 0xFD]], ['elf', [0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0]],
	['pst', [0x21, 0x42, 0x44, 0x4E]], ['parquet', 'PAR1'], ['parquet e', 'PARE'], ['ttc', 'ttcf'],
	['macho feedface', [0xFE, 0xED, 0xFA, 0xCE]], ['macho feedfacf', [0xFE, 0xED, 0xFA, 0xCF]], ['macho cefaedfe', [0xCE, 0xFA, 0xED, 0xFE]], ['macho cffaedfe', [0xCF, 0xFA, 0xED, 0xFE]],
	['lz4', [0x04, 0x22, 0x4D, 0x18]], ['regf', 'regf'], ['sav', '$FL2'], ['sav 3', '$FL3'], ['otf', [0x4F, 0x54, 0x54, 0x4F, 0x00]],
	['amr', '#!AMR\n'], ['rtf', '{\\rtf1'], ['flv', [0x46, 0x4C, 0x56, 0x01]], ['it', 'IMPM'],
	...['-lh0-', '-lh1-', '-lh5-', '-lh7-', '-lzs-', '-lz4-', '-lz5-', '-lhd-'].map(s => [`lzh ${s}`, bytes([0x20, 0x00], s)]),
	['mpeg-ps mpeg1', [0, 0, 1, 0xBA, 0x21]], ['mpeg-ps mpeg2', [0, 0, 1, 0xBA, 0x44]], ['mpeg-ps other', [0, 0, 1, 0xBA, 0x00]], ['mpeg seq', [0, 0, 1, 0xB3]],
	['chm', 'ITSF'], ['macho fat', [0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 2]], ['java class', [0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 52]],
	['cafebabe neither', [0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 0]], ['cafebabe 31 arch', [0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 31]],
	['rm', '.RMF'], ['drc', 'DRACO'], ['xz', [0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00]], ['xml', '<?xml version="1.0"?><a/>'], ['xml no space', '<?xml?>'],
	['7z', [0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C]], ['rar4', [0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00]], ['rar5', [0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00]],
	['rar bad', [0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x02]], ['stl', 'solid cube\n'], ['dwg', 'AC1032'], ['dwg 1015', 'AC1015'], ['dwg 1051', 'AC1051'], ['dwg 0999', 'AC0999'],
	['AC text', 'ACME corp'], ['cpio ascii', '070707'], ['blend', 'BLENDER-v300'], ['deb', '!<arch>\ndebian-binary   '], ['ar', '!<arch>\nfoo.o/          '],
	['deb high bit', bytes('!<arch>\n', [0xE4, 0xE5, 0xE2, 0xE9, 0xE1, 0xEE, 0xAD, 0xE2, 0xE9, 0xEE, 0xE1, 0xF2, 0xF9])],
	['vtt', 'WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n'], ['vtt space', 'WEBVTT - title\n'], ['vtt eof', 'WEBVTT'], ['vtt bad', 'WEBVTTX\n'],
	['arrow', [0x41, 0x52, 0x52, 0x4F, 0x57, 0x31, 0, 0]], ['glb', [0x67, 0x6C, 0x54, 0x46, 2, 0, 0, 0]],
	['orf', [0x49, 0x49, 0x52, 0x4F, 0x08, 0, 0, 0, 0x18]], ['xcf', 'gimp xcf v011\0'], ['reg', 'REGEDIT4\r\n'],
	['riff webp', bytes('RIFF', u32le(100), 'WEBPVP8 ')], ['riff avi', bytes('RIFF', u32le(100), 'AVI LIST')], ['riff wav', bytes('RIFF', u32le(100), 'WAVEfmt ')],
	['riff qcp', bytes('RIFF', u32le(100), 'QLCMfmt ')], ['riff ani', bytes('RIFF', u32le(100), 'ACONanih')], ['riff cdr', bytes('RIFF', u32le(100), 'CDR9')], ['riff short', bytes('RIFF', u32le(100), 'WAV')],
	['riff rmid', bytes('RIFF', u32le(100), 'RMIDdata')], ['riff amv', bytes('RIFF', u32le(100), 'AMV LIST')],
	['rw2', [0x49, 0x49, 0x55, 0x00, 0x18, 0, 0, 0, 0x88, 0xE7, 0x74, 0xD8]],
	['ktx', [0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A]], ['mie', bytes([0x7E, 0x10, 0x04, 0x00], '0MIE')],
	['shp', [0, 0, 0x27, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]], ['j2c', [0xFF, 0x4F, 0xFF, 0x51]],
	...['jp2 ', 'jpx ', 'jpm ', 'mjp2', 'jpXX', '\xEAp2 '].map(t => [`jp2 family ${t}`, bytes([0, 0, 0, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A], [0, 0, 0, 0x14], 'ftyp', t)]),
	['jxl codestream', [0xFF, 0x0A]], ['jxl container', [0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A]],
	['utf16be xml', bytes([0xFE, 0xFF], Buffer.from('<?xml version', 'utf16le').swap16())], ['utf16be text', bytes([0xFE, 0xFF, 0, 0x68, 0, 0x69])],
	['cfb', [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]], ['icc', bytes(zeros(36), 'acsp')], ['ace', bytes(zeros(7), '**ACE**')],
	['vcard', 'BEGIN:VCARD\r\nVERSION:3.0\r\n'], ['ics', 'BEGIN:VCALENDAR\r\n'], ['begin other', 'BEGIN:OTHER'], ['raf', 'FUJIFILMCCD-RAW 0201'],
	['xm', 'Extended Module: song'], ['voc', 'Creative Voice File\x1A'], ['mxf', [0x06, 0x0E, 0x2B, 0x34, 0x02, 0x05, 0x01, 0x01, 0x0D, 0x01, 0x02, 0x01, 0x01, 0x02]],
	['s3m', bytes(zeros(44), 'SCRM')], ['mobi', bytes(zeros(60), 'BOOKMOBI')], ['dicom', bytes(zeros(128), 'DICM')],
	['lnk', [0x4C, 0, 0, 0, 0x01, 0x14, 0x02, 0, 0, 0, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46]],
	['alias', [0x62, 0x6F, 0x6F, 0x6B, 0, 0, 0, 0, 0x6D, 0x61, 0x72, 0x6B, 0, 0, 0, 0]], ['fbx', 'Kaydara FBX Binary  \0'],
	['eot', bytes(zeros(8), [0x01, 0x00, 0x02], zeros(23), 'LP')], ['indd', [0x06, 0x06, 0xED, 0xF5, 0xD8, 0x1D, 0x46, 0xE5, 0xBD, 0x31, 0xEF, 0xE7, 0xFE, 0x74, 0xB7, 0x1D]],
	['jmp le', [0xFF, 0xFF, 0, 0, 7, 0, 0, 0, 4, 0, 0, 0, 1, 0, 1, 0]], ['jmp be', [0, 0, 0xFF, 0xFF, 0, 0, 0, 7, 0, 0, 0, 4, 0, 1, 0, 1]],
	['tar ustar', bytes(zeros(257), 'ustar\0')], ['tar gnu', bytes(zeros(257), 'ustar  \0')], ['utf16le xml', bytes([0xFF, 0xFE], Buffer.from('<?xml version', 'utf16le'))],
	['skp', bytes([0xFF, 0xFE, 0xFF, 0x0E], Buffer.from('SketchUp Model', 'utf16le'))], ['reg utf16', bytes([0xFF, 0xFE], Buffer.from('Windows Registry Editor Version 5.00\r\n', 'utf16le'))],
	['utf16le text', bytes([0xFF, 0xFE], Buffer.from('hello', 'utf16le'))], ['pgp', '-----BEGIN PGP MESSAGE-----\n'],
	['ttf', [0x00, 0x01, 0x00, 0x00, 0x00]], ['ico', [0, 0, 1, 0, 1, 0]], ['cur', [0, 0, 2, 0, 1, 0]],
	['mpeg-ts sync', bytes([0x47], zeros(187), [0x47])], ['bdav sync', bytes(zeros(4), [0x47], zeros(191), [0x47])],
	['utf8 bom pdf', bytes([0xEF, 0xBB, 0xBF], '%PDF-1.4')], ['utf8 bom xml', bytes([0xEF, 0xBB, 0xBF], '<?xml version="1.0"?>')], ['utf8 bom text', bytes([0xEF, 0xBB, 0xBF], 'hello world')],
	['utf8 bom bom png', bytes([0xEF, 0xBB, 0xBF, 0xEF, 0xBB, 0xBF], [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])], ['utf8 bom mp3 frame', bytes([0xEF, 0xBB, 0xBF, 0xFF, 0xFB, 0x90, 0x64])],
	['svg', '<svg xmlns="http://www.w3.org/2000/svg"></svg>'], ['html', '<!doctype html><html></html>'], ['json', '{"a":1}'], ['text', 'hello, world\n'],
	['shebang', '#!/bin/sh\necho hi\n'], ['csv', 'a,b,c\n1,2,3\n'],
];

for (const [label, sig] of magic) {
	const head = bytes(sig);
	addSeed(`magic ${label}`, head);
	add(`magic ${label} +zeros`, pad(head, 64));
	addSeed(`magic ${label} +random`, pad(head, 300, 'random'));
	if (head.length > 2) {
		add(`magic ${label} [:-1]`, head.subarray(0, head.length - 1));
	}
}

// ASAR: a pickle header followed by a JSON directory.
for (const [label, json] of [['asar', '{"files":{"a":{"size":1,"offset":"0"}}}'], ['asar files 0', '{"files":0}'], ['asar files ""', '{"files":""}'],
	['asar files []', '{"files":[]}'], ['asar escaped key', '{"\\u0066iles":true}'], ['asar dup keys', '{"files":1,"files":null}'], ['asar not object', '[1,2]'],
	['asar bad json', '{"files":}'], ['asar trailing space', '{"files":1}  '], ['asar null', 'null'], ['asar files -0', '{"files":-0}'], ['asar files 1e-400', '{"files":1e-400}'],
	['asar files false', '{"files":false}'], ['asar files "0"', '{"files":"0"}'], ['asar files nested', ' {"x":{"files":1}, "files" : {} } '], ['asar bom', '﻿{"files":1}'],
	['asar leading zero', '{"files":01}'], ['asar control char', '{"files":"a\tb"}'], ['asar unicode', '{"files":"é","é":1}']]) {
	const text = Buffer.from(json, 'utf8');
	const header = bytes([4, 0, 0, 0], u32le(text.length + 8), u32le(text.length + 4), u32le(text.length), text);
	addSeed(label, header);
	add(`${label} +zeros`, pad(header, 300));
	add(`${label} +1 size`, bytes([4, 0, 0, 0], u32le(0), u32le(0), u32le(text.length + 1), text, ' '));
}

// --- MPEG audio frames and ADTS, every header bit pattern of byte 1 and 2 ------------

for (let b1 = 0xE0; b1 <= 0xFF; b1++) {
	for (const b2 of [0x00, 0x10, 0x44, 0x90, 0x9C, 0xE0, 0xF0, 0x0C, 0x50, 0x80]) {
		add(`mpeg frame ff ${b1.toString(16)} ${b2.toString(16)}`, bytes([0xFF, b1, b2, 0x64], zeros(4)));
	}
}

add('mpeg frame 3 bytes', bytes([0xFF, 0xFB, 0x90]));
add('mpeg frame 2 bytes', bytes([0xFF, 0xFB]));

// ID3 tags: sizes inside, at and beyond the head; repeated tags; tags with a frame after.
const frame = bytes([0xFF, 0xFB, 0x90, 0x64], zeros(200));
const syncsafe = n => [(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F];
for (const size of [0, 1, 10, 100, 4080, 4089, 4090, 4091, 5000, 0x0FFF_FFFF]) {
	const tag = bytes('ID3', [4, 0, 0], syncsafe(size), zeros(Math.min(size, 4100)));
	add(`id3 size ${size} then frame`, bytes(tag, frame));
	add(`id3 size ${size} alone`, tag);
	add(`id3 size ${size} then png`, bytes(tag, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A], u32be(13), 'IHDR', zeros(17), u32be(0), 'IDAT'));
	add(`id3 size ${size} then adts`, bytes(tag, [0xFF, 0xF1, 0x50, 0x80], zeros(8)));
	add(`id3 size ${size} then text`, bytes(tag, 'hello'));
	add(`id3 size ${size} then id3`, bytes(tag, 'ID3', [4, 0, 0], syncsafe(3), zeros(3), frame));
}

add('id3 header only', bytes('ID3', [4, 0, 0, 0, 0, 0]));
add('id3 9 bytes', bytes('ID3', [4, 0, 0, 0, 0, 0, 0]));
add('id3 10 bytes', bytes('ID3', [4, 0, 0, 0, 0, 0, 0x0A]));
add('id3 non-syncsafe size', bytes('ID3', [4, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF], frame));
add('id3 x300', bytes(...Array.from({length: 300}, () => bytes('ID3', [4, 0, 0, 0, 0, 0, 0])), frame));
add('id3 x255 then frame', bytes(...Array.from({length: 255}, () => bytes('ID3', [4, 0, 0, 0, 0, 0, 0])), frame));
add('id3 x256 then frame', bytes(...Array.from({length: 256}, () => bytes('ID3', [4, 0, 0, 0, 0, 0, 0])), frame));
add('id3 x257 then frame', bytes(...Array.from({length: 257}, () => bytes('ID3', [4, 0, 0, 0, 0, 0, 0])), frame));
add('utf8 bom x300 then pdf', bytes(...Array.from({length: 300}, () => [0xEF, 0xBB, 0xBF]), '%PDF'));
add('utf8 bom x256 then pdf', bytes(...Array.from({length: 256}, () => [0xEF, 0xBB, 0xBF]), '%PDF'));
add('utf8 bom x255 then pdf', bytes(...Array.from({length: 255}, () => [0xEF, 0xBB, 0xBF]), '%PDF'));
add('utf8 bom x1000', bytes(...Array.from({length: 1000}, () => [0xEF, 0xBB, 0xBF])));
add('bom then id3 then frame', bytes([0xEF, 0xBB, 0xBF], 'ID3', [4, 0, 0, 0, 0, 0, 0], frame));
add('id3 then bom then pdf', bytes('ID3', [4, 0, 0, 0, 0, 0, 0], [0xEF, 0xBB, 0xBF], '%PDF'));

// --- Ogg pages with each codec identification header ---------------------------------

function oggPage(packet) {
	return bytes('OggS', [0, 2], zeros(8), u32le(1), u32le(0), u32le(0), [1, packet.length], packet);
}

for (const [label, packet] of [['opus', bytes('OpusHead', [1, 2], zeros(9))], ['theora', bytes([0x80], 'theora', zeros(10))], ['video ogm', bytes([0x01], 'video\0\0\0', zeros(8))],
	['flac', bytes([0x7F], 'FLAC', [1, 0], zeros(10))], ['speex', bytes('Speex   1.2', zeros(10))], ['vorbis', bytes([0x01], 'vorbis', zeros(10))], ['skeleton', bytes('fishead\0', zeros(10))],
	['vp8', bytes('OVP80', zeros(10))], ['opus tags', bytes('OpusTags', zeros(10))], ['empty', bytes(zeros(16))]]) {
	const page = oggPage(packet);
	addSeed(`ogg page ${label}`, page);
	add(`ogg page ${label} [:35]`, page.subarray(0, 35));
	add(`ogg page ${label} [:36]`, page.subarray(0, 36));
	add(`ogg page ${label} [:34]`, page.subarray(0, 34));
}

// --- PNG chunk walks -------------------------------------------------------------------

function pngChunk(type, data = zeros(0), lengthOverride) {
	return bytes(u32be(lengthOverride ?? data.length), type, data, zeros(4));
}

const PNG_SIG = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
const ihdr = pngChunk('IHDR', zeros(13));
for (const [label, chunks] of [
	['png ihdr idat', [ihdr, pngChunk('IDAT', zeros(10))]],
	['apng ihdr actl', [ihdr, pngChunk('acTL', zeros(8)), pngChunk('IDAT')]],
	['png actl after idat', [ihdr, pngChunk('IDAT'), pngChunk('acTL', zeros(8))]],
	['png text then actl', [ihdr, pngChunk('tEXt', bytes('Comment\0', 'x'.repeat(50))), pngChunk('acTL', zeros(8))]],
	['png big text then actl', [ihdr, pngChunk('tEXt', zeros(5000)), pngChunk('acTL', zeros(8))]],
	['png text exactly to end', [ihdr, pngChunk('tEXt', zeros(4100 - 8 - 25 - 12))]],
	['png ihdr only', [ihdr]],
	['png ihdr bad length', [pngChunk('IHDR', zeros(12)), pngChunk('IDAT')]],
	['png no ihdr', [pngChunk('tEXt', zeros(4)), pngChunk('IDAT')]],
	['png cgbi', [pngChunk('CgBI', zeros(4)), ihdr, pngChunk('IDAT')]],
	['png cgbi then text', [pngChunk('CgBI', zeros(4)), pngChunk('tEXt', zeros(4)), pngChunk('IDAT')]],
	['png idat first', [pngChunk('IDAT', zeros(4))]],
	['png actl first', [pngChunk('acTL', zeros(8))]],
	['png negative length', [ihdr, pngChunk('tEXt', zeros(0), 0x8000_0000)]],
	['png huge length', [ihdr, pngChunk('tEXt', zeros(0), 0x7FFF_FFF0)]],
	['png length past end', [ihdr, pngChunk('tEXt', zeros(0), 4000)]],
	['png two ihdr', [ihdr, ihdr, pngChunk('acTL', zeros(8))]],
	['png many chunks', [ihdr, ...Array.from({length: 400}, () => pngChunk('tIME')), pngChunk('acTL', zeros(8))]],
	['png 9 trailing bytes', [ihdr, pngChunk('tEXt', zeros(4)), zeros(9)]],
	['png 8 trailing bytes', [ihdr, pngChunk('tEXt', zeros(4)), zeros(8)]],
	['png 4 trailing bytes', [ihdr, zeros(4)]],
]) {
	const data = bytes(PNG_SIG, ...chunks);
	addSeed(label, data);
	addTruncations(label, data);
}

// --- EBML --------------------------------------------------------------------------------

function vint(n) { // Smallest EBML size field
	for (let length = 1; length <= 8; length++) {
		if (n < (2 ** (7 * length)) - 1) {
			const out = [];
			let v = n;
			for (let i = 0; i < length; i++) {
				out.unshift(v % 256);
				v = Math.floor(v / 256);
			}

			out[0] |= 0x80 >> (length - 1);
			return out;
		}
	}
}

function ebmlElement(id, data) {
	return bytes(id, vint(data.length), data);
}

const EBML_ID = [0x1A, 0x45, 0xDF, 0xA3];
for (const [label, children] of [
	['ebml webm', [ebmlElement([0x42, 0x86], [1]), ebmlElement([0x42, 0x82], 'webm')]],
	['ebml matroska', [ebmlElement([0x42, 0x86], [1]), ebmlElement([0x42, 0x82], 'matroska')]],
	['ebml webm nul padded', [ebmlElement([0x42, 0x82], 'webm\0\0\0')]],
	['ebml webm nul then newline', [ebmlElement([0x42, 0x82], 'webm\0\n')]],
	['ebml webm nul newline nul', [ebmlElement([0x42, 0x82], 'webm\0\n\0x')]],
	['ebml bom webm', [ebmlElement([0x42, 0x82], bytes([0xEF, 0xBB, 0xBF], 'webm'))]],
	['ebml WEBM', [ebmlElement([0x42, 0x82], 'WEBM')]],
	['ebml mkv3d', [ebmlElement([0x42, 0x82], 'mkv3d')]],
	['ebml doctype first', [ebmlElement([0x42, 0x82], 'webm'), ebmlElement([0x42, 0x86], [1])]],
	['ebml no doctype', [ebmlElement([0x42, 0x86], [1]), ebmlElement([0x42, 0xF7], [1])]],
	['ebml long doctype', [ebmlElement([0x42, 0x82], 'w'.repeat(70))]],
	['ebml 64 doctype', [ebmlElement([0x42, 0x82], 'w'.repeat(64))]],
	['ebml big child', [ebmlElement([0x42, 0x86], zeros(3000)), ebmlElement([0x42, 0x82], 'webm')]],
	['ebml child past end', [ebmlElement([0x42, 0x86], zeros(5000)), ebmlElement([0x42, 0x82], 'webm')]],
	['ebml 8-byte length', [bytes([0x42, 0x86], [0x01, 0, 0, 0, 0, 0, 0, 1], [1]), ebmlElement([0x42, 0x82], 'webm')]],
	['ebml long id', [bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x42, 0x82], [0x84], 'webm')]],
	['ebml 4-byte id', [bytes([0x10, 0x00, 0x42, 0x82], [0x84], 'webm'), ebmlElement([0x42, 0x82], 'webm')]],
]) {
	const body = bytes(...children);
	const data = bytes(EBML_ID, vint(body.length), body);
	addSeed(label, data);
	addTruncations(label, data);
}

addSeed('ebml root length 1', bytes(EBML_ID, [0x81], ebmlElement([0x42, 0x86], [1]), ebmlElement([0x42, 0x82], 'webm')));
addSeed('ebml root length 0', bytes(EBML_ID, [0x80], ebmlElement([0x42, 0x82], 'webm')));
addSeed('ebml root huge', bytes(EBML_ID, [0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF], ebmlElement([0x42, 0x86], [1]), ebmlElement([0x42, 0x82], 'webm')));
addSeed('ebml many children', bytes(EBML_ID, [0x01, 0, 0, 0, 0, 0, 0x10, 0], ...Array.from({length: 300}, () => ebmlElement([0xEC], [])), ebmlElement([0x42, 0x82], 'webm')));

// --- TIFF ----------------------------------------------------------------------------------

function tiff(bigEndian, version, ifdOffset, extra) {
	const u16 = bigEndian ? u16be : u16le;
	const u32 = bigEndian ? u32be : u32le;
	return bytes(bigEndian ? 'MM' : 'II', u16(version), u32(ifdOffset), extra);
}

for (const be of [false, true]) {
	const u16 = be ? u16be : u16le;
	const tag = id => bytes(u16(id), zeros(10));
	const e = be ? 'be' : 'le';
	for (const [label, data] of [
		['plain', tiff(be, 42, 8, bytes(u16(2), tag(256), tag(257), zeros(4)))],
		['dng', tiff(be, 42, 8, bytes(u16(2), tag(256), tag(50706)))],
		['arw', tiff(be, 42, 8, bytes(u16(1), tag(50341)))],
		['cr2', tiff(be, 42, 16, bytes('CR', [2, 0], zeros(4), u16(1), tag(256)))],
		['nef', tiff(be, 42, 8, bytes(u16(0x1C), u16(0xFE), zeros(20)))],
		['nef 1f', tiff(be, 42, 8, bytes(u16(0x1F), u16(0x0B), zeros(20)))],
		['ifd offset 6', tiff(be, 42, 6, bytes(u16(1), tag(256)))],
		['ifd offset 4', tiff(be, 42, 4, bytes(u16(1), tag(256)))],
		['ifd past end', tiff(be, 42, 3000, zeros(100))],
		['ifd far past end', tiff(be, 42, 100_000, zeros(100))],
		['ifd at end', tiff(be, 42, 120, zeros(112))],
		['too many tags', tiff(be, 42, 8, bytes(u16(600), zeros(100)))],
		['tags truncated', tiff(be, 42, 8, bytes(u16(100), tag(256), tag(257)))],
		['bigtiff', tiff(be, 43, 8, zeros(16))],
		['version 41', tiff(be, 41, 8, zeros(16))],
		['bad then pdf at 256', bytes(tiff(be, 41, 8, zeros(16)))],
		['ifd truncated then tar', bytes(tiff(be, 42, 200, zeros(192)), zeros(1))],
	]) {
		addSeed(`tiff ${e} ${label}`, data);
		addTruncations(`tiff ${e} ${label}`, data);
	}
}

// A TIFF whose IFD read runs off the end moves the tokenizer; later checks then peek from there.
addSeed('tiff moved then vcard', bytes('II', u16le(42), u32le(20), zeros(12), 'BEGIN:VCARD\r\n'));
addSeed('tiff moved then pgp', bytes('II', u16le(42), u32le(8), u16le(500), 'xxxx'));
addSeed('tiff moved then mpeg', bytes('MM', u16be(42), u32be(8), [0xFF, 0xFB, 0x90, 0x64]));

// --- ASF -----------------------------------------------------------------------------------

const ASF_HEADER = [0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C];
const ASF_STREAM = [0x91, 0x07, 0xDC, 0xB7, 0xB7, 0xA9, 0xCF, 0x11, 0x8E, 0xE6, 0x00, 0xC0, 0x0C, 0x20, 0x53, 0x65];
const ASF_AUDIO = [0x40, 0x9E, 0x69, 0xF8, 0x4D, 0x5B, 0xCF, 0x11, 0xA8, 0xFD, 0x00, 0x80, 0x5F, 0x5C, 0x44, 0x2B];
const ASF_VIDEO = [0xC0, 0xEF, 0x19, 0xBC, 0x4D, 0x5B, 0xCF, 0x11, 0xA8, 0xFD, 0x00, 0x80, 0x5F, 0x5C, 0x44, 0x2B];
const asfObject = (guid, payload, size) => bytes(guid, u32le(size ?? payload.length + 24), u32le(0), payload);
for (const [label, objects] of [
	['audio', [asfObject(randomBytes(16), zeros(10)), asfObject(ASF_STREAM, bytes(ASF_AUDIO, zeros(20)))]],
	['video', [asfObject(ASF_STREAM, bytes(ASF_VIDEO, zeros(20)))]],
	['other stream', [asfObject(ASF_STREAM, bytes(randomBytes(16), zeros(20)))]],
	['size below 24', [asfObject(randomBytes(16), zeros(0), 10)]],
	['size past end', [asfObject(randomBytes(16), zeros(10), 100_000)]],
	['no stream', [asfObject(randomBytes(16), zeros(40))]],
]) {
	const data = bytes(ASF_HEADER, zeros(14), ...objects);
	addSeed(`asf ${label}`, data);
	addTruncations(`asf ${label}`, data);
}

// --- ZIP family ------------------------------------------------------------------------------

function zipEntry(name, content, {method = 8, descriptor = false, sizeOverride, extra = zeros(0), encoding = 'utf8'} = {}) {
	const raw = Buffer.from(content);
	const data = method === 8 ? zlib.deflateRawSync(raw) : raw;
	const nameBytes = typeof name === 'string' ? Buffer.from(name, encoding) : Buffer.from(name);
	const size = sizeOverride ?? (descriptor ? 0 : data.length);
	const header = bytes(u32le(0x04034B50), u16le(20), u16le(descriptor ? 8 : 0), u16le(method), zeros(4), u32le(0), u32le(size), u32le(descriptor ? 0 : raw.length), u16le(nameBytes.length), u16le(extra.length), nameBytes, extra);
	const tail = descriptor ? bytes(u32le(0x08074B50), u32le(0), u32le(data.length), u32le(raw.length)) : zeros(0);
	return bytes(header, data, tail);
}

const centralEnd = bytes(u32le(0x02014B50), zeros(42));
const contentTypes = kind => `<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Override PartName="/x" ContentType="application/vnd.openxmlformats-officedocument.${kind}.main+xml"/></Types>`;
for (const [label, entries] of [
	['zip plain', [zipEntry('a.txt', 'hello hello hello'), centralEnd]],
	['zip stored', [zipEntry('a.txt', 'hello', {method: 0}), centralEnd]],
	['epub', [zipEntry('mimetype', 'application/epub+zip', {method: 0}), zipEntry('META-INF/container.xml', '<x/>')]],
	['epub deflated mimetype', [zipEntry('mimetype', 'application/epub+zip'), centralEnd]],
	['odt', [zipEntry('mimetype', 'application/vnd.oasis.opendocument.text', {method: 0}), centralEnd]],
	['ods', [zipEntry('mimetype', 'application/vnd.oasis.opendocument.spreadsheet', {method: 0}), centralEnd]],
	['odp padded', [zipEntry('mimetype', ' \n\tapplication/vnd.oasis.opendocument.presentation\r\n', {method: 0}), centralEnd]],
	['mimetype upper', [zipEntry('mimetype', 'APPLICATION/EPUB+ZIP', {method: 0}), centralEnd]],
	['mimetype unknown', [zipEntry('mimetype', 'application/x-unknown', {method: 0}), centralEnd]],
	['mimetype bom', [zipEntry('mimetype', '﻿application/epub+zip', {method: 0}), centralEnd]],
	['mimetype macro', [zipEntry('mimetype', 'application/vnd.ms-excel.sheet.macroEnabled', {method: 0}), centralEnd]],
	['mimetype method 9', [zipEntry('mimetype', 'application/epub+zip', {method: 9}), centralEnd]],
	['docx', [zipEntry('[Content_Types].xml', contentTypes('wordprocessingml.document')), zipEntry('word/document.xml', '<w/>'), centralEnd]],
	['xlsx', [zipEntry('[Content_Types].xml', contentTypes('spreadsheetml.sheet')), zipEntry('xl/workbook.xml', '<w/>'), centralEnd]],
	['pptx', [zipEntry('[Content_Types].xml', contentTypes('presentationml.presentation')), centralEnd]],
	['docm', [zipEntry('[Content_Types].xml', '<x ContentType="application/vnd.ms-word.document.macroEnabled.main+xml"/>'), centralEnd]],
	['3mf content types', [zipEntry('[Content_Types].xml', '<x ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>'), centralEnd]],
	['content types unknown', [zipEntry('[Content_Types].xml', '<x ContentType="application/foo.main+xml"/>'), zipEntry('word/a.xml', 'x'), centralEnd]],
	['content types no quote', [zipEntry('[Content_Types].xml', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"'), centralEnd]],
	['content types stored', [zipEntry('[Content_Types].xml', contentTypes('wordprocessingml.document'), {method: 0}), centralEnd]],
	['libreoffice docx order', [zipEntry('_rels/.rels', '<r/>'), zipEntry('word/document.xml', '<w/>'.repeat(10)), zipEntry('docProps/app.xml', '<a/>'), zipEntry('[Content_Types].xml', contentTypes('wordprocessingml.document')), centralEnd]],
	['dirs then truncated', [zipEntry('xl/workbook.xml', 'x'.repeat(100)), zipEntry('xl/big.xml', randomBytes(5000), {method: 0})]],
	['ppt dir then content types big', [zipEntry('ppt/a.xml', 'x'), zipEntry('[Content_Types].xml', randomBytes(5000), {method: 0})]],
	['content types then word', [zipEntry('[Content_Types].xml', 'no main here'), zipEntry('word/document.xml', 'x'), centralEnd]],
	['content types skipped', [zipEntry('[Content_Types].xml', 'x', {method: 0, sizeOverride: 2_000_000}), zipEntry('word/a.xml', 'x')]],
	['jar', [zipEntry('META-INF/', ''), zipEntry('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n'), centralEnd]],
	['xpi', [zipEntry('META-INF/mozilla.rsa', 'x'), centralEnd]],
	['apk', [zipEntry('AndroidManifest.xml', 'x'), zipEntry('classes.dex', 'dex\n035'), centralEnd]],
	['apk classes2', [zipEntry('res/classes2.dex', 'x'), centralEnd]],
	['3mf dir', [zipEntry('3D/3dmodel.model', '<model/>'), zipEntry('[Content_Types].xml', 'x', {sizeOverride: 3_000_000, method: 0})]],
	['keynote', [zipEntry('Index/Document.iwa', 'x'), zipEntry('Index/MasterSlide-1.iwa', 'x'), centralEnd]],
	['numbers', [zipEntry('Index/Document.iwa', 'x'), zipEntry('Index/Tables/DataList.iwa', 'x'), centralEnd]],
	['pages', [zipEntry('Index/Document.iwa', 'x'), zipEntry('Index/Other.iwa', 'x'), centralEnd]],
	['pages calc engine', [zipEntry('Index/Document.iwa', 'x'), zipEntry('Index/CalculationEngine.iwa', 'x'), centralEnd]],
	['descriptor entry', [zipEntry('a.txt', 'hello world', {descriptor: true}), centralEnd]],
	['descriptor mimetype', [zipEntry('mimetype', 'application/epub+zip', {method: 0, descriptor: true}), centralEnd]],
	['descriptor content types', [zipEntry('[Content_Types].xml', contentTypes('wordprocessingml.document'), {descriptor: true}), centralEnd]],
	['descriptor then docx', [zipEntry('a.txt', 'abc', {descriptor: true}), zipEntry('[Content_Types].xml', contentTypes('wordprocessingml.document')), centralEnd]],
	['descriptor never ends', [zipEntry('a.txt', randomBytes(3000), {method: 0, descriptor: true}).subarray(0, 2000)]],
	['encrypted sig', [zipEntry('a.txt', 'x'), bytes([0xD0, 0xCF, 0x11, 0xE0], zeros(40))]],
	['bad sig', [zipEntry('a.txt', 'x'), bytes('JUNKJUNK', zeros(40))]],
	['extra field', [zipEntry('mimetype', 'application/epub+zip', {method: 0, extra: randomBytes(20)}), centralEnd]],
	['filename bom', [zipEntry(bytes([0xEF, 0xBB, 0xBF], 'mimetype'), 'application/epub+zip', {method: 0}), centralEnd]],
	['filename invalid utf8', [zipEntry(bytes('word/', [0xFF, 0xC0]), 'x'), zipEntry('[Content_Types].xml', 'x', {method: 0, sizeOverride: 9_000_000})]],
	['corrupt deflate', [zipEntry('[Content_Types].xml', randomBytes(50), {method: 0}).map((b, i) => i === 8 ? 8 : b), centralEnd]],
	['truncated deflate', [zipEntry('[Content_Types].xml', contentTypes('wordprocessingml.document').repeat(5)).subarray(0, 200)]],
	['deflate bomb', [zipEntry('[Content_Types].xml', zeros(2_000_000)), centralEnd]],
	['deflate trailing junk', [bytes(u32le(0x04034B50), u16le(20), u16le(0), u16le(8), zeros(4), u32le(0), u32le(zlib.deflateRawSync(Buffer.from(contentTypes('wordprocessingml.document'))).length + 5), u32le(0), u16le(19), u16le(0), '[Content_Types].xml', zlib.deflateRawSync(Buffer.from(contentTypes('wordprocessingml.document'))), 'JUNK!'), centralEnd]],
	['mimetype empty deflate', [zipEntry('mimetype', '', {method: 8, sizeOverride: 0}), centralEnd]],
	['mimetype empty stored', [zipEntry('mimetype', '', {method: 0}), centralEnd]],
	['empty archive', [bytes(u32le(0x06054B50), zeros(18))]],
	['zip only central', [centralEnd]],
]) {
	const data = bytes(...entries);
	addSeed(label, data);
	addTruncations(label, data);
}

// --- archivers and compressors -----------------------------------------------------------

function tool(label, command, toolArgs, file) {
	try {
		execFileSync(command, toolArgs, {cwd: work, stdio: ['ignore', 'ignore', 'pipe']});
		const data = new Uint8Array(readFileSync(path.join(work, file)));
		addSeed(label, data);
		addTruncations(label, data);
	} catch (error) {
		console.warn(`skip ${label}: ${String(error.stderr ?? error.message).split('\n')[0]}`);
	}
}

writeFileSync(path.join(work, 'a.txt'), 'hello world\n'.repeat(400));
writeFileSync(path.join(work, 'b.bin'), randomBytes(3000));
for (const name of ['a.txt', 'b.bin']) {
	utimesSync(path.join(work, name), 946_684_800, 946_684_800); // Fixed times keep the archives the same run to run.
}

tool('zip cli', 'zip', ['-q', '-X', '-D', 'z1.zip', 'a.txt', 'b.bin'], 'z1.zip');
tool('zip cli stored', 'zip', ['-q', '-X', '-0', 'z2.zip', 'a.txt'], 'z2.zip');
tool('tar ustar', 'tar', ['--format=ustar', '--owner=0', '--group=0', '--mtime=@0', '-cf', 't1.tar', 'a.txt'], 't1.tar');
tool('tar gnu', 'tar', ['--format=gnu', '--owner=0', '--group=0', '--mtime=@0', '-cf', 't2.tar', 'a.txt'], 't2.tar');
tool('tar v7', 'tar', ['--format=v7', '--owner=0', '--group=0', '--mtime=@0', '-cf', 't3.tar', 'a.txt'], 't3.tar');
tool('tar pax', 'tar', ['--format=pax', '--pax-option=delete=atime,delete=ctime', '--owner=0', '--group=0', '--mtime=@0', '-cf', 't4.tar', 'a.txt'], 't4.tar');
tool('xz', 'xz', ['-kf', 'a.txt'], 'a.txt.xz');
tool('bzip2', 'bzip2', ['-kf', 'a.txt'], 'a.txt.bz2');
tool('zstd', 'zstd', ['-qf', 'a.txt', '-o', 'a.txt.zst'], 'a.txt.zst');
addSeed('gzip', new Uint8Array(zlib.gzipSync(Buffer.from('hello world\n'.repeat(400)))));
addSeed('gzip of tar', new Uint8Array(zlib.gzipSync(Buffer.from(bytes(zeros(257), 'ustar\0', zeros(250))))));
addSeed('gzip bad', bytes([0x1F, 0x8B, 0x08], randomBytes(40)));
addSeed('gzip header only', bytes([0x1F, 0x8B, 0x08]));
addSeed('zlib', new Uint8Array(zlib.deflateSync(Buffer.from('hello world'))));

// A v7 tar has no "ustar" magic; it is found by its checksum.
function v7Tar(checksumField) {
	const header = new Uint8Array(512);
	header.set(Buffer.from('file.txt'), 0);
	header.set(Buffer.from('0000644\0'), 100);
	let sum = 0;
	for (let i = 0; i < 512; i++) {
		sum += i >= 148 && i < 156 ? 0x20 : header[i];
	}

	header.set(Buffer.from(checksumField(sum), 'latin1').subarray(0, 8), 148);
	return header;
}

for (const [label, field] of [['octal', s => s.toString(8).padStart(6, '0') + '\0 '], ['spaces', s => `  ${s.toString(8)}\0 `], ['sign', s => `+${s.toString(8)}\0 `],
	['nbsp', s => `Â ${s.toString(8)}\0`], ['wrong', s => (s + 1).toString(8).padStart(6, '0') + '\0 '], ['junk', () => 'xxxxxx\0 '], ['empty', () => '\0\0\0\0\0\0\0 '],
	['nul then newline', s => `${s.toString(8)}\0\n`], ['digits then 9', s => `${s.toString(8)}9\0`]]) {
	add(`tar v7 checksum ${label}`, v7Tar(field));
}

// --- junk ------------------------------------------------------------------------------------

add('empty', zeros(0));
add('one byte', bytes([0x47]));
add('one zero byte', zeros(1));
add('two zero bytes', zeros(2));
add('4100 zero bytes', zeros(4100));
add('4100 0xFF bytes', new Uint8Array(4100).fill(0xFF));
for (let i = 0; i < 60; i++) {
	add(`junk ${i}`, randomBytes(1 + randomInt(i < 40 ? 64 : 4100)));
}

// --- mutations ---------------------------------------------------------------------------------

function mutate(data) {
	const copy = new Uint8Array(data);
	const flips = 1 + randomInt(3);
	for (let i = 0; i < flips; i++) {
		const where = random() < 0.7 ? randomInt(Math.min(copy.length, 48)) : randomInt(copy.length);
		copy[where] = random() < 0.5 ? randomInt(256) : copy[where] ^ (1 << randomInt(8));
	}

	return copy;
}

function shortLength(data) {
	return Math.min(data.length, random() < 0.6 ? 2 + randomInt(80) : 2 + randomInt(700));
}

const realSeeds = [...seeds];
const mutantsPerSeed = large ? 60 : 3;
for (const [label, data] of realSeeds) {
	for (let i = 0; i < mutantsPerSeed; i++) {
		const mutant = mutate(data);
		const n = large && random() < 0.3 ? mutant.length : shortLength(mutant);
		add(`mutant of ${label}`, mutant.subarray(0, n));
	}
}

// --- run file-type and write the fixture ----------------------------------------------------------

const seen = new Set();
const lines = [];
let errors = 0;
for (const [label, head] of cases) {
	const b64 = Buffer.from(head).toString('base64');
	if (seen.has(b64)) {
		continue;
	}

	seen.add(b64);
	let mime = null;
	try {
		mime = (await fileTypeFromBuffer(head))?.mime ?? null;
	} catch (error) {
		errors++;
		console.warn(`file-type threw on ${label}: ${error.message}`);
		continue;
	}

	lines.push(JSON.stringify({n: label, h: b64, m: mime}));
}

writeFileSync(out, `{"fileType": ${JSON.stringify(fileTypePackage.version)}, "cases": [\n${lines.join(',\n')}\n]}\n`);
console.log(`wrote ${lines.length} cases to ${path.basename(out)} (${errors} skipped because file-type threw)`);
if (!existsSync(out)) {
	process.exit(1);
}
