package com.example.model

import java.security.MessageDigest

enum class SymbolicSourceFormat {
    MIDI,
    MUSIC_XML,
    STRUCTURED_SCORE,
    PDF,
    IMAGE
}

enum class DataAvailability {
    PRESENT,
    UNKNOWN,
    UNAVAILABLE
}

data class SourceMetadata(
    val sourceId: String,
    val title: String? = null,
    val composer: String? = null,
    val format: SymbolicSourceFormat,
    val sourceInstrument: String? = null,
    val sourceHash: String? = null
) {
    init {
        require(sourceId.isNotBlank())
    }
}

data class MusicalPitch(
    val midiNumber: Int,
    val spelling: String? = null
) {
    init {
        require(midiNumber in 0..127)
    }

    val octave: Int
        get() = midiNumber / 12 - 1
}

data class TempoChange(
    val beatPosition: Double,
    val bpm: Double
) {
    init {
        require(beatPosition >= 0.0)
        require(bpm > 0.0)
    }
}

data class TimeSignatureChange(
    val beatPosition: Double,
    val timeSignature: TimeSignature
) {
    init {
        require(beatPosition >= 0.0)
    }
}

data class KeySignatureChange(
    val beatPosition: Double,
    val key: String?,
    val mode: String?,
    val availability: DataAvailability = if (key == null && mode == null) {
        DataAvailability.UNKNOWN
    } else {
        DataAvailability.PRESENT
    }
)

data class MusicalProvenance(
    val sourceId: String,
    val sourceTrackId: String? = null,
    val sourceEventId: String? = null,
    val sourceLocation: String? = null
) {
    init {
        require(sourceId.isNotBlank())
    }
}

data class SymbolicMusicalEvent(
    val eventId: String,
    val beatPosition: Double,
    val durationBeats: Double,
    val measureNumber: Int? = null,
    val subdivision: Subdivision? = null,
    val pitch: MusicalPitch? = null,
    val velocity: Float? = null,
    val voiceId: String? = null,
    val sourceHand: PlayingHand? = null,
    val chordGroupId: String? = null,
    val trackId: String? = null,
    val channel: Int? = null,
    val staffId: String? = null,
    val accidental: String? = null,
    val tie: String? = null,
    val articulation: String? = null,
    val isRest: Boolean = false,
    val provenance: MusicalProvenance,
    val availability: DataAvailability = if (isRest || pitch != null) {
        DataAvailability.PRESENT
    } else {
        DataAvailability.UNKNOWN
    }
) {
    init {
        require(eventId.isNotBlank())
        require(beatPosition >= 0.0)
        require(durationBeats > 0.0)
        require(measureNumber == null || measureNumber > 0)
        require(channel == null || channel in 0..15)
        require(velocity == null || velocity in 0.0f..1.0f)
        require(isRest || pitch != null || availability != DataAvailability.PRESENT)
    }
}

data class SymbolicTrack(
    val trackId: String,
    val channel: Int? = null,
    val instrument: String? = null,
    val events: List<SymbolicMusicalEvent>
) {
    init {
        require(trackId.isNotBlank())
        require(channel == null || channel in 0..15)
        require(events.map { it.eventId }.distinct().size == events.size)
    }
}

data class ScoreSection(
    val sectionId: String,
    val startBeat: Double,
    val endBeat: Double,
    val label: String? = null,
    val repeatCount: Int = 1
) {
    init {
        require(sectionId.isNotBlank())
        require(startBeat >= 0.0)
        require(endBeat > startBeat)
        require(repeatCount > 0)
    }
}

data class SymbolicScore(
    val metadata: SourceMetadata,
    val tempoMap: List<TempoChange>,
    val timeSignatureMap: List<TimeSignatureChange>,
    val keySignatureMap: List<KeySignatureChange>,
    val tracks: List<SymbolicTrack>,
    val sections: List<ScoreSection> = emptyList()
) {
    init {
        require(tracks.map { it.trackId }.distinct().size == tracks.size)
        require(tempoMap.zipWithNext().all { it.first.beatPosition <= it.second.beatPosition })
        require(timeSignatureMap.zipWithNext().all { it.first.beatPosition <= it.second.beatPosition })
        require(keySignatureMap.zipWithNext().all { it.first.beatPosition <= it.second.beatPosition })
    }

    val events: List<SymbolicMusicalEvent>
        get() = tracks.flatMapIndexed { trackIndex, track ->
            track.events.mapIndexed { eventIndex, event -> Triple(event, trackIndex, eventIndex) }
        }.sortedWith(compareBy<Triple<SymbolicMusicalEvent, Int, Int>>({ it.first.beatPosition }, { it.second }, { it.third })
        ).map { it.first }
}

data class NormalizedMusicalEvent(
    val sourceEventId: String,
    val beatPosition: Double,
    val durationBeats: Double,
    val absoluteQuarterNotes: Double,
    val measureNumber: Int,
    val beatInMeasure: Double,
    val pitch: MusicalPitch?,
    val staffId: String? = null,
    val accidental: String? = null,
    val tie: String? = null,
    val velocity: Float?,
    val isRest: Boolean,
    val voiceId: String?,
    val sourceHand: PlayingHand?,
    val chordGroupId: String?,
    val provenance: MusicalProvenance
)

data class NormalizedMusicalTimeline(
    val sourceId: String,
    val events: List<NormalizedMusicalEvent>,
    val tempoMap: List<TempoChange>,
    val timeSignatureMap: List<TimeSignatureChange>,
    val sourceHash: String? = null,
    val title: String? = null,
    val composer: String? = null,
    val provenance: List<MusicalProvenance> = emptyList(),
    /** Track identities are semantic; their collection order is not. */
    val trackIds: List<String> = emptyList(),
    val keySignatureMap: List<KeySignatureChange> = emptyList()
) {
    init {
        require(sourceId.isNotBlank())
        require(events.map { it.sourceEventId }.distinct().size == events.size)
        require(events.zipWithNext().all { it.first.beatPosition <= it.second.beatPosition })
    }

    companion object {
        fun from(score: SymbolicScore): NormalizedMusicalTimeline {
            val signatures = score.timeSignatureMap.ifEmpty {
                listOf(TimeSignatureChange(0.0, TimeSignature.Common44))
            }
            return NormalizedMusicalTimeline(
                sourceId = score.metadata.sourceId,
                events = score.events.map { event ->
                    val signature = signatures.lastOrNull { it.beatPosition <= event.beatPosition }
                        ?: signatures.first()
                    val beatsPerBar = signature.timeSignature.beatsPerBar.toDouble()
                    val measure = (event.beatPosition / beatsPerBar).toInt() + 1
                    NormalizedMusicalEvent(
                        sourceEventId = event.eventId,
                        beatPosition = event.beatPosition,
                        durationBeats = event.durationBeats,
                        absoluteQuarterNotes = event.beatPosition,
                        measureNumber = event.measureNumber ?: measure,
                        beatInMeasure = event.beatPosition % beatsPerBar,
                        pitch = event.pitch,
                        staffId = event.staffId,
                        accidental = event.accidental,
                        tie = event.tie,
                        velocity = event.velocity,
                        isRest = event.isRest,
                        voiceId = event.voiceId,
                        sourceHand = event.sourceHand,
                        chordGroupId = event.chordGroupId,
                        provenance = event.provenance
                    )
                },
                tempoMap = score.tempoMap,
                timeSignatureMap = score.timeSignatureMap,
                sourceHash = score.metadata.sourceHash,
                title = score.metadata.title,
                composer = score.metadata.composer,
                provenance = score.events.map { it.provenance }.distinct(),
                trackIds = score.tracks.map { it.trackId },
                keySignatureMap = score.keySignatureMap
            )
        }
    }
}

object SymbolicEventIds {
    fun deterministic(sourceId: String, trackId: String, ordinal: Int, payload: String): String {
        require(sourceId.isNotBlank())
        require(trackId.isNotBlank())
        require(ordinal >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sourceId|$trackId|$ordinal|$payload".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}