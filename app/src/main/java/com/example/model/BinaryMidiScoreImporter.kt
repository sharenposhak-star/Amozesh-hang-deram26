package com.example.model

import java.security.MessageDigest

class BinaryMidiScoreImporter {
    fun import(source: ByteArray, sourceId: String): SymbolicImportResult {
        if (sourceId.isBlank()) return failure("Source id is required")
        if (source.isEmpty()) return failure("MIDI source is empty")
        if (source.size < 4 || !source.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4D, 0x54, 0x68, 0x64))) {
            return SymbolicImportResult.Failure(SymbolicImportError.INVALID_SOURCE, "MIDI header chunk MThd is missing")
        }

        return try {
            val cursor = ByteCursor(source)
            cursor.expectAscii("MThd")
            val headerLength = cursor.readUInt32()
            if (headerLength != 6L) throw MidiParseException("MIDI header length must be 6")
            val format = cursor.readUInt16()
            if (format !in 0..1) throw MidiParseException("MIDI format $format is unsupported")
            val trackCount = cursor.readUInt16()
            if (trackCount == 0) throw MidiParseException("MIDI must contain at least one track")
            val division = cursor.readUInt16()
            if (division and 0x8000 != 0 || division == 0) {
                throw MidiParseException("SMPTE or zero MIDI division is unsupported")
            }

            val parsedTracks = (0 until trackCount).map { trackIndex ->
                cursor.expectAscii("MTrk")
                val trackLength = cursor.readUInt32().toIntOrThrow("track length")
                val end = cursor.position + trackLength
                if (end > source.size) throw MidiParseException("Track $trackIndex is truncated")
                cursor.limit = end
                try {
                    parseTrack(cursor, end, trackIndex, division)
                } finally {
                    cursor.limit = source.size
                }
            }
            if (cursor.position != source.size) throw MidiParseException("Trailing bytes after MIDI tracks")

            val notes = parsedTracks.flatMap { it.notes }
                .sortedWith(compareBy<ParsedNote>({ it.startTick }, { it.trackIndex }, { it.sequence }, { it.channel }, { it.pitch }))
            val events = notes.mapIndexed { ordinal, note ->
                val trackId = "track-${note.trackIndex}"
                val eventId = SymbolicEventIds.deterministic(
                    sourceId,
                    trackId,
                    ordinal,
                    "${note.startTick}:${note.endTick}:${note.channel}:${note.pitch}:${note.velocity}:${note.sequence}"
                )
                SymbolicMusicalEvent(
                    eventId = eventId,
                    beatPosition = note.startTick.toDouble() / division,
                    durationBeats = (note.endTick - note.startTick).toDouble() / division,
                    pitch = MusicalPitch(note.pitch),
                    velocity = note.velocity / 127.0f,
                    chordGroupId = notes.filter { it.trackIndex == note.trackIndex && it.startTick == note.startTick }
                        .takeIf { it.size > 1 }?.let { "$trackId-tick-${note.startTick}" },
                    trackId = trackId,
                    channel = note.channel,
                    provenance = MusicalProvenance(
                        sourceId = sourceId,
                        sourceTrackId = trackId,
                        sourceEventId = eventId,
                        sourceLocation = "track=${note.trackIndex},startTick=${note.startTick},endTick=${note.endTick}"
                    )
                )
            }
            val tempos = parsedTracks.flatMap { it.tempos }.sortedBy { it.first }
                .map { TempoChange(it.first.toDouble() / division, 60_000_000.0 / it.second) }
                .distinctBy { it.beatPosition to it.bpm }
            val signatures = parsedTracks.flatMap { it.signatures }.sortedBy { it.first }
                .map { TimeSignatureChange(it.first.toDouble() / division, TimeSignature(it.second.first, it.second.second)) }
                .distinctBy { it.beatPosition to it.timeSignature }
            SymbolicImportResult.Success(
                SymbolicScore(
                    metadata = SourceMetadata(sourceId, format = SymbolicSourceFormat.MIDI, sourceHash = sha256(source)),
                    tempoMap = tempos,
                    timeSignatureMap = signatures,
                    keySignatureMap = emptyList(),
                    tracks = parsedTracks.map { track ->
                        SymbolicTrack("track-${track.trackIndex}", events = events.filter { it.trackId == "track-${track.trackIndex}" })
                    }
                )
            )
        } catch (error: MidiParseException) {
            failure(error.message ?: "MIDI parse failed")
        } catch (error: RuntimeException) {
            failure(error.message ?: "MIDI parse failed")
        }
    }

    private fun parseTrack(cursor: ByteCursor, end: Int, trackIndex: Int, division: Int): ParsedTrack {
        var absoluteTick = 0L
        var runningStatus = -1
        var sequence = 0
        var sawEndOfTrack = false
        val openNotes = mutableMapOf<Pair<Int, Int>, ArrayDeque<OpenNote>>()
        val notes = mutableListOf<ParsedNote>()
        val tempos = mutableListOf<Pair<Long, Int>>()
        val signatures = mutableListOf<Pair<Long, Pair<Int, Int>>>()

        while (cursor.position < end) {
            absoluteTick += cursor.readVlq().toLong()
            if (cursor.position >= end) throw MidiParseException("Track $trackIndex event is truncated")
            var status = cursor.readUnsignedByte()
            if (status < 0x80) {
                if (runningStatus < 0x80 || runningStatus >= 0xF0) throw MidiParseException("Invalid running status in track $trackIndex")
                cursor.unread()
                status = runningStatus
            } else if (status in 0x80..0xEF) {
                runningStatus = status
            }

            when {
                status in 0x80..0x8F -> {
                    val channel = status and 0x0F
                    val pitch = cursor.readUnsignedByte()
                    cursor.readUnsignedByte()
                    closeNote(openNotes, notes, channel, pitch, absoluteTick, trackIndex)
                    sequence++
                }
                status in 0x90..0x9F -> {
                    val channel = status and 0x0F
                    val pitch = cursor.readUnsignedByte()
                    val velocity = cursor.readUnsignedByte()
                    if (velocity == 0) {
                        closeNote(openNotes, notes, channel, pitch, absoluteTick, trackIndex)
                    } else {
                        openNotes.getOrPut(channel to pitch) { ArrayDeque() }
                            .addLast(OpenNote(absoluteTick, velocity, sequence++))
                    }
                }
                status in 0xA0..0xBF || status in 0xE0..0xEF -> {
                    cursor.readUnsignedByte()
                    cursor.readUnsignedByte()
                }
                status in 0xC0..0xDF -> cursor.readUnsignedByte()
                status == 0xFF -> {
                    val type = cursor.readUnsignedByte()
                    val length = cursor.readVlq()
                    if (cursor.position + length > end) throw MidiParseException("Meta event is truncated in track $trackIndex")
                    when (type) {
                        0x51 -> {
                            if (length != 3) throw MidiParseException("Tempo event must contain 3 bytes")
                            tempos += absoluteTick to cursor.readUInt24()
                        }
                        0x58 -> {
                            if (length != 4) throw MidiParseException("Time signature event must contain 4 bytes")
                            val numerator = cursor.readUnsignedByte()
                            val denominatorExponent = cursor.readUnsignedByte()
                            cursor.readUnsignedByte()
                            cursor.readUnsignedByte()
                            if (denominatorExponent > 30) throw MidiParseException("Invalid time signature denominator")
                            signatures += absoluteTick to (numerator to (1 shl denominatorExponent))
                        }
                        0x2F -> {
                            if (length != 0) throw MidiParseException("End-of-track event must be empty")
                            sawEndOfTrack = true
                        }
                        else -> cursor.skip(length)
                    }
                    runningStatus = -1
                }
                status == 0xF0 || status == 0xF7 -> {
                    val length = cursor.readVlq()
                    cursor.skip(length)
                    runningStatus = -1
                }
                else -> throw MidiParseException("Unsupported or invalid MIDI status 0x${status.toString(16)}")
            }
        }
        if (cursor.position != end || !sawEndOfTrack) throw MidiParseException("Track $trackIndex has no valid end-of-track")
        if (openNotes.values.any { it.isNotEmpty() }) throw MidiParseException("Unclosed note in track $trackIndex")
        return ParsedTrack(trackIndex, notes, tempos, signatures)
    }

    private fun closeNote(
        openNotes: MutableMap<Pair<Int, Int>, ArrayDeque<OpenNote>>,
        notes: MutableList<ParsedNote>,
        channel: Int,
        pitch: Int,
        endTick: Long,
        trackIndex: Int
    ) {
        val queue = openNotes[channel to pitch]
        val open = queue?.removeFirstOrNull() ?: throw MidiParseException("Note-off without matching note-on in track $trackIndex")
        if (endTick <= open.startTick) throw MidiParseException("Note duration is not positive in track $trackIndex")
        notes += ParsedNote(trackIndex, channel, pitch, open.velocity, open.startTick, endTick, open.sequence)
    }

    private fun failure(message: String) = SymbolicImportResult.Failure(SymbolicImportError.PARSE_FAILED, message)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class ParsedTrack(
        val trackIndex: Int,
        val notes: List<ParsedNote>,
        val tempos: List<Pair<Long, Int>>,
        val signatures: List<Pair<Long, Pair<Int, Int>>>
    )

    private data class OpenNote(val startTick: Long, val velocity: Int, val sequence: Int)
    private data class ParsedNote(val trackIndex: Int, val channel: Int, val pitch: Int, val velocity: Int, val startTick: Long, val endTick: Long, val sequence: Int)

    private class MidiParseException(message: String) : Exception(message)

    private class ByteCursor(private val bytes: ByteArray) {
        var position: Int = 0
            private set
        var limit: Int = bytes.size

        fun expectAscii(value: String) {
            value.forEach { expected ->
                if (readUnsignedByte() != expected.code) throw MidiParseException("Expected $value")
            }
        }

        fun readUnsignedByte(): Int {
            if (position >= limit) throw MidiParseException("Unexpected end of MIDI data")
            return bytes[position++].toInt() and 0xFF
        }

        fun unread() {
            if (position == 0) throw MidiParseException("Invalid cursor position")
            position--
        }

        fun readUInt16(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()

        fun readUInt24(): Int = (readUnsignedByte() shl 16) or (readUnsignedByte() shl 8) or readUnsignedByte()

        fun readUInt32(): Long = (readUnsignedByte().toLong() shl 24) or (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 8) or readUnsignedByte().toLong()

        fun readVlq(): Int {
            var value = 0
            repeat(4) {
                val byte = readUnsignedByte()
                value = (value shl 7) or (byte and 0x7F)
                if (byte and 0x80 == 0) return value
            }
            throw MidiParseException("Variable-length quantity exceeds four bytes")
        }

        fun skip(length: Int) {
            if (length < 0 || position + length > limit) throw MidiParseException("MIDI event is truncated")
            position += length
        }
    }
}

private fun Long.toIntOrThrow(label: String): Int {
    if (this > Int.MAX_VALUE) throw IllegalArgumentException("$label is too large")
    return toInt()
}
