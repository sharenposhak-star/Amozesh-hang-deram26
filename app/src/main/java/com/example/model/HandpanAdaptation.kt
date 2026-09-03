package com.example.model

enum class SymbolicImportError {
    UNSUPPORTED_FORMAT,
    INVALID_SOURCE,
    INVALID_EVENT,
    PARSE_FAILED
}

sealed class SymbolicImportResult {
    data class Success(val score: SymbolicScore) : SymbolicImportResult()
    data class Failure(val error: SymbolicImportError, val message: String) : SymbolicImportResult()
}

interface SymbolicScoreImporter {
    fun import(source: String, sourceId: String): SymbolicImportResult
}

class MidiScoreImporter : SymbolicScoreImporter {
    override fun import(source: String, sourceId: String): SymbolicImportResult {
        if (sourceId.isBlank()) {
            return SymbolicImportResult.Failure(SymbolicImportError.INVALID_SOURCE, "Source id is required")
        }
        val trimmed = source.trim()
        if (trimmed.isEmpty()) {
            return SymbolicImportResult.Failure(SymbolicImportError.INVALID_SOURCE, "MIDI source content is empty")
        }
        if (!looksLikeMidiPayload(trimmed)) {
            return SymbolicImportResult.Failure(SymbolicImportError.INVALID_SOURCE, "Source does not look like a MIDI payload")
        }
        return SymbolicImportResult.Failure(
            SymbolicImportError.PARSE_FAILED,
            "MIDI parser implementation is deferred to Slice B; source was validated as MIDI-like input but not yet parsed into SymbolicScore"
        )
    }

    private fun looksLikeMidiPayload(source: String): Boolean {
        val header = source.take(4)
        if (header == "MThd") return true

        val rawHex = source.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it.isWhitespace() }
        if (rawHex.isNotBlank()) {
            val normalized = rawHex.replace(Regex("\\s+"), "")
            if (normalized.length >= 8 && normalized.startsWith("4d546864", ignoreCase = true)) {
                return true
            }
        }

        return false
    }
}

class MusicXmlScoreImporter : SymbolicScoreImporter {
    override fun import(source: String, sourceId: String): SymbolicImportResult =
        StandardMusicXmlScoreImporter().import(source, sourceId)
}

class StructuredScoreImporter : SymbolicScoreImporter {
    override fun import(source: String, sourceId: String): SymbolicImportResult =
        SymbolicImportResult.Failure(SymbolicImportError.INVALID_SOURCE, "Use importScore for typed structured data")

    fun importScore(score: SymbolicScore): SymbolicImportResult = SymbolicImportResult.Success(score)
}

data class AdaptationRequest(
    val instrumentProfile: InstrumentProfile,
    val transposeSemitones: Int = 0,
    val octaveShift: Int = 0,
    val preserveMelody: Boolean = true,
    val preserveHarmony: Boolean = true,
    val maxBpm: Int = 240,
    val allowPitchApproximation: Boolean = false,
    val maxSimultaneousNotes: Int? = null,
    val maxSimultaneousNotesPerHand: Int? = null,
    val maxSustainBeats: Double? = null,
    val minimumRepeatedNoteIntervalBeats: Double? = null
) {
    init {
        require(maxBpm > 0)
        require(maxSimultaneousNotes == null || maxSimultaneousNotes > 0)
        require(maxSimultaneousNotesPerHand == null || maxSimultaneousNotesPerHand > 0)
        require(maxSustainBeats == null || maxSustainBeats > 0.0)
        require(minimumRepeatedNoteIntervalBeats == null || minimumRepeatedNoteIntervalBeats >= 0.0)
    }
}

enum class AdaptationStatus {
    EXACT,
    PRESERVED,
    TRANSPOSED,
    OCTAVE_SHIFTED,
    REDUCED,
    MERGED,
    SIMPLIFIED,
    OMITTED,
    IMPOSSIBLE,
    UNKNOWN_UNAVAILABLE
}

enum class MusicalRole {
    MELODY,
    BASS,
    ACCOMPANIMENT,
    UNKNOWN
}

enum class HandAssignmentStatus {
    EXPLICIT,
    INFERRED,
    EITHER,
    CONFLICT,
    UNKNOWN
}

data class AdaptationAlternative(
    val targetNoteNumber: Int?,
    val targetPitch: MusicalPitch?,
    val reason: String,
    val distanceSemitones: Int? = null
)

data class AdaptationDecision(
    val sourceEventId: String,
    val sourcePitch: MusicalPitch?,
    val targetNoteNumber: Int?,
    val targetPitch: MusicalPitch?,
    val transposeSemitones: Int,
    val octaveShift: Int,
    val beatPosition: Double,
    val durationBeats: Double,
    val velocity: Float?,
    val sourceVoiceId: String?,
    val role: MusicalRole,
    val hand: PlayingHand,
    val handStatus: HandAssignmentStatus,
    val confidence: Float,
    val reason: String,
    val constraintViolations: List<String> = emptyList(),
    val status: AdaptationStatus,
    val alternatives: List<AdaptationAlternative> = emptyList(),
    val isRest: Boolean = false,
    val sourceProvenance: MusicalProvenance? = null
) {
    init {
        require(sourceEventId.isNotBlank())
        require(beatPosition >= 0.0)
        require(durationBeats > 0.0)
        require(confidence in 0.0f..1.0f)
        require(velocity == null || velocity in 0.0f..1.0f)
        require(isRest || status in setOf(AdaptationStatus.REDUCED, AdaptationStatus.IMPOSSIBLE, AdaptationStatus.OMITTED, AdaptationStatus.UNKNOWN_UNAVAILABLE) || targetNoteNumber != null)
    }
}

data class HandpanArrangement(
    val sourceId: String,
    val request: AdaptationRequest,
    val decisions: List<AdaptationDecision>,
    val timeline: NormalizedMusicalTimeline
) {
    init {
        require(sourceId.isNotBlank())
        require(decisions.map { it.sourceEventId }.distinct().size == decisions.size)
    }

    fun toHandpanPattern(id: String, title: String = "Adapted $sourceId"): HandpanPattern {
        val events = decisions.filter { it.targetNoteNumber != null || it.isRest }
            .map { decision ->
                NoteEvent(
                    noteNumber = decision.targetNoteNumber ?: 0,
                    beatPosition = decision.beatPosition,
                    duration = decision.durationBeats,
                    velocity = (decision.velocity ?: 0.85f).coerceIn(0.0f, 1.0f),
                    accent = decision.role == MusicalRole.MELODY,
                    hand = decision.hand.symbol,
                    isRest = decision.isRest
                )
            }
        val signature = timeline.timeSignatureMap.firstOrNull()?.timeSignature ?: TimeSignature.Common44
        val lastBeat = timeline.events.maxOfOrNull { it.beatPosition + it.durationBeats } ?: 0.0
        return HandpanPattern(
            id = id,
            title = title,
            description = "Generated from symbolic source; inspect adaptation decisions for omissions.",
            bpm = timeline.tempoMap.firstOrNull()?.bpm?.toInt()?.coerceAtLeast(1) ?: 60,
            timeSignature = signature,
            bars = kotlin.math.ceil(lastBeat / signature.beatsPerBar).toInt().coerceAtLeast(1),
            events = events
        )
    }
}

object HandpanAdaptationSolver {
    private data class Candidate(val field: ToneFieldDefinition, val distance: Int)

    fun adapt(timeline: NormalizedMusicalTimeline, request: AdaptationRequest): HandpanArrangement {
        val decisions = timeline.events.groupBy { it.chordGroupId to it.beatPosition }
            .toSortedMap(compareBy({ it.first ?: "" }, { it.second }))
            .values.flatMap { cluster -> adaptCluster(cluster, request) }
            .sortedWith(compareBy({ it.beatPosition }, { it.sourceEventId }))
        return HandpanArrangement(timeline.sourceId, request, enforceSequentialConstraints(decisions, request), timeline)
    }

    private fun adaptCluster(events: List<NormalizedMusicalEvent>, request: AdaptationRequest): List<AdaptationDecision> {
        val preliminary = events.map { adaptEvent(it, request) }.toMutableList()
        val playable = preliminary.filter { it.targetNoteNumber != null && !it.isRest }
        val limit = request.maxSimultaneousNotes
        if (limit != null && playable.size > limit) {
            val keep = playable.sortedWith(compareByDescending<AdaptationDecision> { priority(it.role, request) }
                .thenBy { it.status != AdaptationStatus.EXACT }
                .thenBy { it.sourceEventId })
                .take(limit).map { it.sourceEventId }.toSet()
            return preliminary.map { decision ->
                if (decision.targetNoteNumber != null && decision.sourceEventId !in keep) {
                    decision.copy(targetNoteNumber = null, targetPitch = null, status = AdaptationStatus.REDUCED,
                        reason = "Chord reduced to configured simultaneous-note limit",
                        constraintViolations = decision.constraintViolations + "simultaneous-note-limit")
                } else decision
            }
        }
        return preliminary
    }

    private fun enforceSequentialConstraints(
        decisions: List<AdaptationDecision>,
        request: AdaptationRequest
    ): List<AdaptationDecision> {
        val result = decisions.toMutableList()
        request.maxSimultaneousNotesPerHand?.let { limit ->
            result.groupBy { it.beatPosition }.values.forEach { cluster ->
                cluster.filter { it.targetNoteNumber != null && !it.isRest }
                    .groupBy { it.hand }
                    .values
                    .filter { it.size > limit }
                    .forEach { handEvents ->
                        handEvents.sortedWith(compareByDescending<AdaptationDecision> { priority(it.role, request) }
                            .thenBy { it.status != AdaptationStatus.EXACT }
                            .thenBy { it.sourceEventId })
                            .drop(limit)
                            .forEach { rejected ->
                                val index = result.indexOfFirst { it.sourceEventId == rejected.sourceEventId }
                                if (index >= 0) result[index] = rejected.copy(
                                    targetNoteNumber = null,
                                    targetPitch = null,
                                    status = AdaptationStatus.REDUCED,
                                    reason = "Chord reduced to configured per-hand limit",
                                    constraintViolations = rejected.constraintViolations + "per-hand-limit"
                                )
                            }
                    }
            }
        }
        request.minimumRepeatedNoteIntervalBeats?.let { minimumInterval ->
            result.filter { it.targetNoteNumber != null && !it.isRest }
                .groupBy { it.targetNoteNumber }
                .values
                .forEach { repeated ->
                    repeated.sortedBy { it.beatPosition }.zipWithNext().forEach { (previous, current) ->
                        if (current.beatPosition - previous.beatPosition < minimumInterval) {
                            val index = result.indexOfFirst { it.sourceEventId == current.sourceEventId }
                            if (index >= 0) result[index] = current.copy(
                                targetNoteNumber = null,
                                targetPitch = null,
                                status = AdaptationStatus.IMPOSSIBLE,
                                reason = "Repeated note interval is below configured practical limit",
                                constraintViolations = current.constraintViolations + "repeated-note-limit"
                            )
                        }
                    }
                }
        }
        return result
    }

    private fun adaptEvent(event: NormalizedMusicalEvent, request: AdaptationRequest): AdaptationDecision {
        val base = decisionBase(event, request)
        if (event.isRest) return base.copy(status = AdaptationStatus.PRESERVED, confidence = 1.0f, reason = "Rest preserved", isRest = true)
        val sourcePitch = event.pitch ?: return base.copy(status = AdaptationStatus.UNKNOWN_UNAVAILABLE, confidence = 0.0f,
            reason = "Source pitch unavailable", constraintViolations = listOf("source-pitch-unavailable"))
        val shiftedMidi = sourcePitch.midiNumber + request.transposeSemitones + request.octaveShift * 12
        val candidates = request.instrumentProfile.fields.mapNotNull { field ->
            val midi = midiNumber(field.scientificPitch)
            if (midi < 0) null else Candidate(field, kotlin.math.abs(midi - shiftedMidi))
        }.sortedWith(compareBy<Candidate>({ it.distance }, { it.field.displayNumber }))
        val exact = candidates.filter { it.distance == 0 }
        val selected = exact.firstOrNull() ?: candidates.firstOrNull { request.allowPitchApproximation }
        val alternatives = candidates.take(5).map { candidate ->
            AdaptationAlternative(candidate.field.displayNumber, MusicalPitch(midiNumber(candidate.field.scientificPitch)),
                if (candidate.distance == 0) "Exact available pitch" else "Approximate pitch candidate", candidate.distance)
        }
        if (selected == null) return base.copy(sourcePitch = sourcePitch, status = AdaptationStatus.IMPOSSIBLE, confidence = 0.0f,
            reason = if (candidates.isEmpty()) "No valid instrument pitch candidate" else "Exact pitch unavailable and approximation is disabled",
            constraintViolations = listOf("exact-pitch-unavailable"), alternatives = alternatives)
        val hand = event.sourceHand ?: selected.field.defaultHand
        val handStatus = if (event.sourceHand != null) HandAssignmentStatus.EXPLICIT else if (hand == PlayingHand.EITHER) HandAssignmentStatus.EITHER else HandAssignmentStatus.INFERRED
        val status = when {
            selected.distance == 0 && request.transposeSemitones == 0 && request.octaveShift == 0 -> AdaptationStatus.EXACT
            request.octaveShift != 0 -> AdaptationStatus.OCTAVE_SHIFTED
            else -> AdaptationStatus.TRANSPOSED
        }
        val violations = buildList {
            request.maxSustainBeats?.let { if (event.durationBeats > it) add("sustain-limit") }
        }
        val durationBeats = request.maxSustainBeats?.let { event.durationBeats.coerceAtMost(it) }
            ?: event.durationBeats
        return base.copy(sourcePitch = sourcePitch, targetNoteNumber = selected.field.displayNumber,
            targetPitch = MusicalPitch(midiNumber(selected.field.scientificPitch)), durationBeats = durationBeats,
            status = if (violations.isEmpty()) status else AdaptationStatus.SIMPLIFIED,
            hand = hand, handStatus = handStatus, confidence = (1.0f - selected.distance / 12.0f).coerceIn(0.0f, 1.0f),
            reason = if (violations.isEmpty()) "${status.name.lowercase()} profile mapping" else "Duration exceeds configured sustain limit",
            constraintViolations = violations, alternatives = alternatives)
    }

    private fun decisionBase(event: NormalizedMusicalEvent, request: AdaptationRequest) = AdaptationDecision(
        sourceEventId = event.sourceEventId, sourcePitch = event.pitch, targetNoteNumber = null, targetPitch = null,
        transposeSemitones = request.transposeSemitones, octaveShift = request.octaveShift, beatPosition = event.beatPosition,
        durationBeats = event.durationBeats, velocity = event.velocity, sourceVoiceId = event.voiceId, role = role(event),
        hand = event.sourceHand ?: PlayingHand.EITHER, handStatus = if (event.sourceHand != null) HandAssignmentStatus.EXPLICIT else HandAssignmentStatus.UNKNOWN,
        confidence = 0.0f, reason = "Unresolved", status = AdaptationStatus.IMPOSSIBLE, sourceProvenance = event.provenance, isRest = event.isRest)

    private fun priority(role: MusicalRole, request: AdaptationRequest): Int = when (role) {
        MusicalRole.MELODY -> if (request.preserveMelody) 4 else 2
        MusicalRole.BASS -> if (request.preserveHarmony) 3 else 1
        MusicalRole.ACCOMPANIMENT -> if (request.preserveHarmony) 2 else 1
        MusicalRole.UNKNOWN -> 0
    }

    private fun role(event: NormalizedMusicalEvent): MusicalRole = when {
        event.voiceId?.contains("melody", ignoreCase = true) == true -> MusicalRole.MELODY
        event.voiceId?.contains("bass", ignoreCase = true) == true -> MusicalRole.BASS
        event.chordGroupId != null -> MusicalRole.ACCOMPANIMENT
        else -> MusicalRole.UNKNOWN
    }

    private fun midiNumber(scientificPitch: String): Int {
        val match = Regex("^([A-Ga-g])([#b]?)(-?\\d+)$").matchEntire(scientificPitch) ?: return -1
        val base = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)[match.groupValues[1].uppercase()[0]] ?: return -1
        val accidental = when (match.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
        return (match.groupValues[3].toInt() + 1) * 12 + base + accidental
    }
}

data class AdaptationMetric(
    val numerator: Double,
    val denominator: Double,
    val weight: Double,
    val tolerance: Double?,
    val unknownCount: Int,
    val unknownPolicy: String
) {
    val ratio: Double?
        get() = if (denominator == 0.0) null else (numerator / denominator).coerceIn(0.0, 1.0)
}

data class AdaptationQuality(
    val pitchFidelity: AdaptationMetric,
    val rhythmicFidelity: AdaptationMetric,
    val beatAlignment: AdaptationMetric,
    val durationFidelity: AdaptationMetric,
    val melodyPreservation: AdaptationMetric,
    val harmonicPreservation: AdaptationMetric,
    val playableNoteRatio: AdaptationMetric,
    val simultaneousPlayabilityRatio: AdaptationMetric,
    val handAssignmentFeasibility: AdaptationMetric,
    val tempoFeasibility: AdaptationMetric,
    val omittedNoteRatio: AdaptationMetric,
    val transformedNoteRatio: AdaptationMetric,
    val confidence: AdaptationMetric
)

object AdaptationQualityCalculator {
    fun calculate(arrangement: HandpanArrangement): AdaptationQuality {
        val decisions = arrangement.decisions
        val eligible = decisions.filterNot { it.isRest }
        val unknown = eligible.count { it.status == AdaptationStatus.UNKNOWN_UNAVAILABLE }
        val playable = eligible.count { it.targetNoteNumber != null && it.status != AdaptationStatus.IMPOSSIBLE }
        val transformed = eligible.count { it.status in setOf(AdaptationStatus.TRANSPOSED, AdaptationStatus.OCTAVE_SHIFTED, AdaptationStatus.MERGED, AdaptationStatus.SIMPLIFIED) }
        val omitted = eligible.count { it.status in setOf(AdaptationStatus.OMITTED, AdaptationStatus.IMPOSSIBLE, AdaptationStatus.REDUCED) }
        val exact = eligible.count { it.status == AdaptationStatus.EXACT || it.status == AdaptationStatus.PRESERVED }
        val melody = eligible.filter { it.role == MusicalRole.MELODY }
        val harmony = eligible.filter { it.role == MusicalRole.ACCOMPANIMENT || it.role == MusicalRole.BASS }
        val metric: (Double, Double, Double, Double?, Int, String) -> AdaptationMetric = { n, d, w, t, u, p -> AdaptationMetric(n, d, w, t, u, p) }
        val groups = decisions.filterNot { it.isRest }.groupBy { it.beatPosition }.values.filter { it.size > 1 }
        return AdaptationQuality(
            metric(exact.toDouble(), (eligible.size - unknown).toDouble(), 1.0, 0.0, unknown, "unknown excluded"),
            metric(eligible.size.toDouble(), eligible.size.toDouble(), 1.0, 0.0, 0, "canonical timing preserved"),
            metric(eligible.count { it.beatPosition >= 0.0 }.toDouble(), eligible.size.toDouble(), 1.0, 0.0, 0, "invalid events rejected"),
            metric(eligible.count { it.durationBeats > 0.0 }.toDouble(), eligible.size.toDouble(), 1.0, 0.0, 0, "invalid events rejected"),
            metric(melody.count { it.status == AdaptationStatus.EXACT || it.status == AdaptationStatus.PRESERVED }.toDouble(), melody.size.toDouble(), 1.5, 0.0, 0, "unknown role excluded"),
            metric(harmony.count { it.status !in setOf(AdaptationStatus.IMPOSSIBLE, AdaptationStatus.OMITTED, AdaptationStatus.REDUCED) }.toDouble(), harmony.size.toDouble(), 1.0, null, 0, "unknown role excluded"),
            metric(playable.toDouble(), eligible.size.toDouble(), 1.0, null, unknown, "rests excluded"),
            metric(groups.count { it.all { d -> d.targetNoteNumber != null && d.status != AdaptationStatus.REDUCED } }.toDouble(), groups.size.toDouble(), 1.0, null, 0, "single events excluded"),
            metric(eligible.count { it.handStatus != HandAssignmentStatus.CONFLICT }.toDouble(), eligible.size.toDouble(), 1.0, null, 0, "unknown hand is not failure"),
            metric(if (arrangement.timeline.tempoMap.any { it.bpm <= arrangement.request.maxBpm }) playable.toDouble() else 0.0, eligible.size.toDouble(), 1.0, arrangement.request.maxBpm.toDouble(), 0, "missing tempo is unknown"),
            metric(omitted.toDouble(), eligible.size.toDouble(), 1.0, null, unknown, "rests excluded"),
            metric(transformed.toDouble(), eligible.size.toDouble(), 1.0, null, unknown, "rests excluded"),
            metric(eligible.sumOf { it.confidence.toDouble() }, (eligible.size - unknown).toDouble(), 1.0, null, unknown, "unknown confidence excluded")
        )
    }
}

data class PlaybackEventTiming(val sourceEventId: String, val beatPosition: Double, val durationBeats: Double)

object SymbolicPlaybackTiming {
    fun scale(timeline: NormalizedMusicalTimeline, speedMultiplier: Double): List<PlaybackEventTiming> {
        require(speedMultiplier > 0.0)
        return timeline.events.map { PlaybackEventTiming(it.sourceEventId, it.beatPosition / speedMultiplier, it.durationBeats / speedMultiplier) }
    }
}
