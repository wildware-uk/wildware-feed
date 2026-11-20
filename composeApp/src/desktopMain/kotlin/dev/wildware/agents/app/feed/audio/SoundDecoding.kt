package dev.wildware.agents.app.feed.audio

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import io.github.jaredmdobson.concentus.OpusDecoder
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import net.sourceforge.jaad.aac.Decoder as AacDecoder
import net.sourceforge.jaad.adts.ADTSDemultiplexer
import net.sourceforge.jaad.mp4.MP4Container
import net.sourceforge.jaad.mp4.MP4InputStream
import net.sourceforge.jaad.mp4.api.Track
import net.sourceforge.jaad.mp4.api.Type
import java.io.EOFException
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** A sound being decoded, a block at a time: 16-bit signed samples with the channels interleaved. */
interface PcmSource : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    /** The next block of samples, or null at the end. */
    fun read(): ShortArray?

    override fun close() {}
}

/** A sound this computer can't decode, with the words to show for it ("Can't play FLAC"). */
class UnplayableSound(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What a sound file is, told by its first bytes rather than its name or the type it was sent with. */
enum class SoundFormat(val label: String) {
    Mp3("MP3"),
    Mp4("M4A"),
    Adts("AAC"),
    Vorbis("Ogg Vorbis"),
    Opus("Opus"),
    Wave("WAV"),
    Flac("FLAC"),
    Unknown("this sound"),
}

/**
 * Turns an attached sound into samples to play, in plain Java so it works the same on every desktop:
 * MP3 (JLayer), AAC in an M4A or as raw ADTS (JAAD), Ogg Vorbis (JOrbis), Ogg Opus (Concentus), and
 * WAV, AIFF and AU (Java Sound itself). Anything else is an [UnplayableSound] saying what it is.
 */
object SoundDecoding {
    fun formatOf(bytes: ByteArray): SoundFormat {
        if (bytes.size < 12) return SoundFormat.Unknown
        if (bytes.ascii(0, "RIFF") && bytes.ascii(8, "WAVE")) return SoundFormat.Wave
        if (bytes.ascii(0, "FORM") && (bytes.ascii(8, "AIFF") || bytes.ascii(8, "AIFC"))) return SoundFormat.Wave
        if (bytes.ascii(0, ".snd")) return SoundFormat.Wave
        if (bytes.ascii(4, "ftyp")) return SoundFormat.Mp4
        if (bytes.ascii(0, "fLaC")) return SoundFormat.Flac
        if (bytes.ascii(0, "OggS")) return oggFormat(bytes)
        val start = afterId3(bytes)
        if (start + 1 >= bytes.size) return SoundFormat.Unknown
        val b0 = bytes[start].toInt() and 0xFF
        val b1 = bytes[start + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return SoundFormat.Unknown
        return when ((b1 shr 1) and 0x3) {
            0 -> if ((b1 and 0xF0) == 0xF0) SoundFormat.Adts else SoundFormat.Unknown
            else -> SoundFormat.Mp3
        }
    }

    /** A decoder for [bytes], ready to read from the start. */
    fun open(bytes: ByteArray): PcmSource {
        val format = formatOf(bytes)
        return try {
            when (format) {
                SoundFormat.Mp3 -> Mp3Source(bytes)
                SoundFormat.Mp4 -> Mp4AacSource(bytes)
                SoundFormat.Adts -> AdtsAacSource(bytes)
                SoundFormat.Vorbis -> VorbisSource(bytes)
                SoundFormat.Opus -> OpusSource(bytes)
                SoundFormat.Wave, SoundFormat.Unknown -> JavaSoundSource(bytes)
                SoundFormat.Flac -> throw UnplayableSound("Can't play FLAC")
            }
        } catch (e: UnplayableSound) {
            throw e
        } catch (e: Exception) {
            throw UnplayableSound("Can't play ${format.label}", e)
        }
    }

    /** How long [bytes] plays for, by decoding all of it. */
    fun durationMs(bytes: ByteArray): Long = open(bytes).use { source ->
        var frames = 0L
        while (true) frames += (source.read() ?: break).size / source.channels
        frames * 1000 / source.sampleRate
    }

    /** Ogg holds Vorbis, Opus or FLAC: its first packet says which. */
    private fun oggFormat(bytes: ByteArray): SoundFormat {
        val segments = bytes[26].toInt() and 0xFF
        val body = 27 + segments
        return when {
            bytes.ascii(body, "OpusHead") -> SoundFormat.Opus
            body + 7 <= bytes.size && bytes[body].toInt() == 1 && bytes.ascii(body + 1, "vorbis") -> SoundFormat.Vorbis
            bytes.ascii(body + 1, "FLAC") -> SoundFormat.Flac
            else -> SoundFormat.Unknown
        }
    }

    /** Where the sound starts after any ID3 tags at the front (an MP3's, or now and then an AAC's). */
    private fun afterId3(bytes: ByteArray): Int {
        var at = 0
        while (at + 10 <= bytes.size && bytes.ascii(at, "ID3")) {
            val size = (0 until 4).fold(0) { acc, i -> (acc shl 7) or (bytes[at + 6 + i].toInt() and 0x7F) }
            val footer = if ((bytes[at + 5].toInt() and 0x10) != 0) 10 else 0
            at += 10 + size + footer
        }
        return at
    }

    private fun ByteArray.ascii(at: Int, text: String): Boolean =
        at >= 0 && at + text.length <= size && text.indices.all { this[at + it] == text[it].code.toByte() }
}

/** MP3 (and MP2), through JLayer. */
private class Mp3Source(bytes: ByteArray) : PcmSource {
    private val stream = Bitstream(ByteArrayInputStream(bytes))
    private val decoder = Decoder()
    private var first: ShortArray?
    override val sampleRate: Int
    override val channels: Int

    init {
        val header = stream.readFrame() ?: throw UnplayableSound("Can't play MP3: no sound in it")
        sampleRate = header.frequency()
        channels = if (header.mode() == Header.SINGLE_CHANNEL) 1 else 2
        first = decode(header)
    }

    private fun decode(header: Header): ShortArray {
        val out = decoder.decodeFrame(header, stream) as SampleBuffer
        stream.closeFrame()
        return out.buffer.copyOf(out.bufferLength)
    }

    override fun read(): ShortArray? {
        first?.let { first = null; return it }
        val header = stream.readFrame() ?: return null
        return decode(header)
    }

    override fun close() = stream.close()
}

/** Decodes AAC frames with JAAD, into 16-bit samples. */
private class AacFrames(private val decoder: AacDecoder) {
    private val buffer = net.sourceforge.jaad.SampleBuffer()
    var sampleRate = 0
        private set
    var channels = 0
        private set

    fun decode(frame: ByteArray): ShortArray {
        decoder.decodeFrame(frame, buffer)
        if (buffer.bitsPerSample != 16) throw UnplayableSound("Can't play AAC")
        sampleRate = buffer.sampleRate
        channels = buffer.channels
        val data = ByteBuffer.wrap(buffer.data).order(if (buffer.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(data.remaining() / 2 / channels * channels)
        data.asShortBuffer().get(out)
        return out
    }
}

/** AAC in an MP4 file (.m4a). */
private class Mp4AacSource(bytes: ByteArray) : PcmSource {
    private val track: Track
    private val frames: AacFrames
    private var first: ShortArray?
    override val sampleRate: Int
    override val channels: Int

    init {
        val movie = MP4Container(MP4InputStream.open(ByteArrayInputStream(bytes))).movie
        track = movie.getTracks(Type.AUDIO).firstOrNull() ?: throw UnplayableSound("Can't play M4A: no sound in it")
        if (track.codec?.toString() != "AAC") throw UnplayableSound("Can't play ${track.codec ?: "this"} in M4A")
        val config = track.decoderSpecificInfo?.data ?: throw UnplayableSound("Can't play M4A")
        frames = AacFrames(AacDecoder.create(config))
        first = frames.decode(track.readNextFrame()?.data ?: throw UnplayableSound("Can't play M4A: no sound in it"))
        sampleRate = frames.sampleRate
        channels = frames.channels
    }

    override fun read(): ShortArray? {
        first?.let { first = null; return it }
        return frames.decode(track.readNextFrame()?.data ?: return null)
    }
}

/** Raw AAC (.aac): ADTS frames one after another, each with its own header. */
private class AdtsAacSource(bytes: ByteArray) : PcmSource {
    private val adts = ADTSDemultiplexer(ByteArrayInputStream(bytes))
    private val frames = AacFrames(AacDecoder.create(adts.decoderInfo))
    private var first: ShortArray?
    override val sampleRate: Int
    override val channels: Int

    init {
        first = frames.decode(nextFrame() ?: throw UnplayableSound("Can't play AAC: no sound in it"))
        sampleRate = frames.sampleRate
        channels = frames.channels
    }

    private fun nextFrame(): ByteArray? = try {
        adts.readNextFrame()
    } catch (_: EOFException) {
        null
    }

    override fun read(): ShortArray? {
        first?.let { first = null; return it }
        return frames.decode(nextFrame() ?: return null)
    }
}

/** The packets of the first stream in an Ogg file, one at a time. */
private class OggPackets(private val bytes: ByteArray) {
    private val sync = SyncState().apply { init() }
    private val stream = StreamState()
    private val page = Page()
    private var serial: Int? = null
    private var offset = 0

    /** Fills [packet] with the next one; false at the end. The packet is only good until the next call. */
    fun next(packet: Packet): Boolean {
        while (true) {
            if (serial != null && stream.packetout(packet) == 1) return true
            when (sync.pageout(page)) {
                1 -> {
                    if (serial == null) {
                        serial = page.serialno()
                        stream.init(page.serialno())
                    }
                    if (page.serialno() == serial) stream.pagein(page)
                }
                0 -> {
                    if (offset >= bytes.size) return false
                    val n = minOf(8192, bytes.size - offset)
                    val into = sync.buffer(n)
                    System.arraycopy(bytes, offset, sync.data, into, n)
                    sync.wrote(n)
                    offset += n
                }
                // -1: bytes that weren't a page were skipped; carry on.
            }
        }
    }
}

/** Ogg Vorbis, through JOrbis. */
private class VorbisSource(bytes: ByteArray) : PcmSource {
    private val packets = OggPackets(bytes)
    private val packet = Packet()
    private val info = Info().apply { init() }
    private val dsp = DspState()
    private val block: Block
    private val pcm = arrayOfNulls<Array<FloatArray>>(1)
    private val index: IntArray
    override val sampleRate: Int
    override val channels: Int

    init {
        val comment = Comment().apply { init() }
        repeat(3) {
            if (!packets.next(packet) || info.synthesis_headerin(comment, packet) < 0) throw UnplayableSound("Can't play Ogg Vorbis")
        }
        sampleRate = info.rate
        channels = info.channels
        dsp.synthesis_init(info)
        block = Block(dsp)
        index = IntArray(channels)
    }

    override fun read(): ShortArray? {
        while (true) {
            val frames = dsp.synthesis_pcmout(pcm, index)
            if (frames > 0) {
                val channelsPcm = pcm[0]!!
                val out = ShortArray(frames * channels)
                for (i in 0 until frames) for (c in 0 until channels) {
                    val v = (channelsPcm[c][index[c] + i] * 32767f).toInt().coerceIn(-32768, 32767)
                    out[i * channels + c] = v.toShort()
                }
                dsp.synthesis_read(frames)
                return out
            }
            if (!packets.next(packet)) return null
            if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block)
        }
    }
}

/** Ogg Opus, through Concentus. Opus always decodes at 48 kHz. */
private class OpusSource(bytes: ByteArray) : PcmSource {
    private val packets = OggPackets(bytes)
    private val packet = Packet()
    private val decoder: OpusDecoder
    private val buffer: ShortArray
    private var toSkip: Int

    /** Frames decoded so far, before the skip at the start is taken off: what an Ogg granule counts. */
    private var decoded = 0L
    override val sampleRate = 48_000
    override val channels: Int

    init {
        if (!packets.next(packet)) throw UnplayableSound("Can't play Opus")
        val head = packet.packet_base.copyOfRange(packet.packet, packet.packet + packet.bytes)
        if (head.size < 19) throw UnplayableSound("Can't play Opus")
        channels = head[9].toInt() and 0xFF
        toSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        val mapping = head[18].toInt() and 0xFF
        if (channels !in 1..2 || mapping > 1) throw UnplayableSound("Can't play surround Opus")
        if (!packets.next(packet)) throw UnplayableSound("Can't play Opus") // the tags
        decoder = OpusDecoder(sampleRate, channels)
        buffer = ShortArray(MaxFrame * channels)
    }

    override fun read(): ShortArray? {
        while (true) {
            if (!packets.next(packet)) return null
            var frames = decoder.decode(packet.packet_base, packet.packet, packet.bytes, buffer, 0, MaxFrame, false)
            // The last page's granule says where the sound really ends; the final frame is padded past it.
            if (packet.e_o_s != 0 && packet.granulepos >= 0) frames = frames.coerceAtMost((packet.granulepos - decoded).toInt().coerceAtLeast(0))
            decoded += frames
            val skip = minOf(toSkip, frames)
            toSkip -= skip
            if (frames - skip <= 0) continue
            return buffer.copyOfRange(skip * channels, frames * channels)
        }
    }

    private companion object {
        /** 120 ms at 48 kHz: the longest an Opus packet can be. */
        const val MaxFrame = 5760
    }
}

/** WAV, AIFF and AU, and anything else Java Sound knows, turned into 16-bit little-endian samples. */
private class JavaSoundSource(bytes: ByteArray) : PcmSource {
    private val input: AudioInputStream
    private val buffer: ByteArray
    override val sampleRate: Int
    override val channels: Int

    init {
        val original = try {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw UnplayableSound("Can't play this kind of sound", e)
        }
        val format = original.format
        sampleRate = format.sampleRate.toInt()
        channels = format.channels
        val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.sampleRate, 16, channels, channels * 2, format.sampleRate, false)
        input = if (format.matches(target)) original else AudioSystem.getAudioInputStream(target, original)
        buffer = ByteArray(4096 * channels * 2)
    }

    override fun read(): ShortArray? {
        val n = input.readNBytes(buffer, 0, buffer.size)
        if (n <= 0) return null
        val frames = n / (channels * 2)
        if (frames == 0) return null
        val out = ShortArray(frames * channels)
        ByteBuffer.wrap(buffer, 0, frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    override fun close() = input.close()
}
