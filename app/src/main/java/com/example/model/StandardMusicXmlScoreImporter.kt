package com.example.model

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

class StandardMusicXmlScoreImporter {
    fun import(source: String, sourceId: String): SymbolicImportResult {
        if (sourceId.isBlank()) return failure(SymbolicImportError.INVALID_SOURCE, "Source id is required")
        if (source.isBlank()) return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML source is empty")
        return try {
            val document = parseDocument(source)
            val root = document.documentElement
            val partList = root.child("part-list") ?: return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML part-list is required")
            val partDefinitions = partList.children("score-part").associateBy { it.requiredAttribute("id") }
            if (partDefinitions.isEmpty()) return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML requires at least one score-part")
            val partMeasures: List<Pair<String, List<MeasureSlice>>> = when (root.localNameOrTag()) {
                "score-partwise" -> root.children("part").map { part ->
                    part.requiredAttribute("id") to part.children("measure").map { measure ->
                        MeasureSlice(measure.requiredAttribute("number").toIntOrNullOrThrow("measure number"), measure)
                    }
                }
                "score-timewise" -> {
                    val measures = root.children("measure")
                    if (measures.isEmpty()) return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML requires at least one measure")
                    partDefinitions.keys.map { partId ->
                        partId to measures.mapNotNull { measure ->
                            measure.children("part").firstOrNull { it.attribute("id") == partId }?.let { part ->
                                MeasureSlice(measure.requiredAttribute("number").toIntOrNullOrThrow("measure number"), part)
                            }
                        }
                    }
                }
                else -> return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML root must be score-partwise or score-timewise")
            }
            if (partMeasures.isEmpty()) return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML requires at least one part")
            if (partMeasures.any { (partId, measures) -> partId !in partDefinitions || measures.isEmpty() }) {
                return failure(SymbolicImportError.INVALID_SOURCE, "Every part must have a matching score-part and measure")
            }
            val parsed = partMeasures.map { (partId, measures) ->
                parsePart(partId, measures, partDefinitions.getValue(partId), sourceId)
            }
            val allEvents = parsed.flatMap { it.events }
            val tempos = parsed.flatMap { it.tempos }.sortedWith(compareBy({ it.beatPosition }, { it.bpm }))
                .distinctBy { it.beatPosition to it.bpm }
            val signatures = parsed.flatMap { it.signatures }.sortedBy { it.beatPosition }.distinctBy { it.beatPosition }
            val keys = parsed.flatMap { it.keys }.sortedBy { it.beatPosition }.distinctBy { it.beatPosition }
            val tracks = parsed.map { result ->
                SymbolicTrack(trackId = result.trackId, instrument = result.instrument, events = result.events)
            }
            SymbolicImportResult.Success(
                SymbolicScore(
                    metadata = SourceMetadata(
                        sourceId = sourceId,
                        title = root.child("work")?.child("work-title")?.textValue(),
                        composer = root.child("identification")?.children("creator")
                            ?.firstOrNull { it.attribute("type") == "composer" }?.textValue(),
                        format = SymbolicSourceFormat.MUSIC_XML,
                        sourceHash = sha256(source.toByteArray(Charsets.UTF_8))
                    ),
                    tempoMap = tempos,
                    timeSignatureMap = signatures,
                    keySignatureMap = keys,
                    tracks = tracks
                )
            )
        } catch (error: MusicXmlParseException) {
            failure(SymbolicImportError.PARSE_FAILED, error.message ?: "MusicXML parse failed")
        } catch (error: Exception) {
            failure(SymbolicImportError.INVALID_SOURCE, error.message ?: "MusicXML source is malformed")
        }
    }

    private fun parseDocument(source: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isCoalescing = true
            setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        return builder.parse(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)))
    }

    private fun parsePart(partId: String, measures: List<MeasureSlice>, definition: Element, sourceId: String): ParsedPart {
        val instrument = definition.child("part-name")?.textValue()
        var divisions: Int? = null
        var absoluteBeat = 0.0
        var ordinal = 0
        val events = mutableListOf<SymbolicMusicalEvent>()
        val tempos = mutableListOf<TempoChange>()
        val signatures = mutableListOf<TimeSignatureChange>()
        val keys = mutableListOf<KeySignatureChange>()
        val tiedEvents = mutableMapOf<TieKey, Int>()

        measures.forEach { measureSlice ->
            val measureNumber = measureSlice.number
            val measure = measureSlice.element
            val attributes = measure.child("attributes")
            attributes?.child("divisions")?.textValue()?.toIntOrNull()?.let {
                if (it <= 0) throw MusicXmlParseException("Divisions must be positive in measure $measureNumber")
                divisions = it
            }
            val currentDivisions = divisions ?: throw MusicXmlParseException("Divisions are required before notes")
            attributes?.child("time")?.let { time ->
                val numerator = time.child("beats")?.textValue()?.toIntOrNull()
                    ?: throw MusicXmlParseException("Time numerator is invalid in measure $measureNumber")
                val denominator = time.child("beat-type")?.textValue()?.toIntOrNull()
                    ?: throw MusicXmlParseException("Time denominator is invalid in measure $measureNumber")
                signatures += TimeSignatureChange(absoluteBeat, TimeSignature(numerator, denominator))
            }
            attributes?.child("key")?.let { key ->
                val fifths = key.child("fifths")?.textValue()
                    ?: throw MusicXmlParseException("Key fifths are missing in measure $measureNumber")
                val mode = key.child("mode")?.textValue()
                keys += KeySignatureChange(absoluteBeat, "fifths=$fifths", mode)
            }

            var cursor = 0.0
            var measureEnd = 0.0
            var previousStart = 0.0
            val expectedMeasureBeats = signatures.lastOrNull()?.timeSignature?.beatsPerBar?.toDouble()
            measure.children().forEach { item ->
                when (item.localNameOrTag()) {
                    "direction" -> item.child("sound")?.attribute("tempo")?.toDoubleOrNull()?.let { bpm ->
                        if (bpm <= 0.0) throw MusicXmlParseException("Tempo must be positive in measure $measureNumber")
                        tempos += TempoChange(absoluteBeat + cursor, bpm)
                    }
                    "backup", "forward" -> {
                        val duration = durationInBeats(item, currentDivisions, measureNumber)
                        cursor = if (item.localNameOrTag() == "backup") cursor - duration else cursor + duration
                        if (cursor < 0.0) throw MusicXmlParseException("Backup moves before measure start in measure $measureNumber")
                        measureEnd = maxOf(measureEnd, cursor)
                    }
                    "note" -> {
                        val duration = durationInBeats(item, currentDivisions, measureNumber)
                        val isChord = item.child("chord") != null
                        val start = if (isChord) previousStart else cursor
                        val event = parseNote(item, sourceId, partId, measureNumber, absoluteBeat + start, duration, ordinal, isChord)
                        val tieTypes = item.children("tie").mapNotNull { it.attribute("type") }
                        val tieKey = TieKey(partId, event.voiceId, item.child("staff")?.textValue(), event.pitch?.midiNumber)
                        val priorIndex = tiedEvents[tieKey]
                        if (isChord && events.isNotEmpty()) {
                            val chordId = "$partId-$measureNumber-${absoluteBeat + start}-${event.voiceId}-${item.child("staff")?.textValue()}"
                            val priorEvent = events.last()
                            events[events.lastIndex] = priorEvent.copy(chordGroupId = chordId)
                        }
                        if (priorIndex != null && "stop" in tieTypes) {
                            val prior = events[priorIndex]
                            events[priorIndex] = prior.copy(
                                durationBeats = event.beatPosition + event.durationBeats - prior.beatPosition,
                                articulation = listOfNotNull(prior.articulation, "tie-stop").distinct().joinToString(",")
                            )
                        } else {
                            events += event.copy(articulation = tieTypes.takeIf { it.isNotEmpty() }?.joinToString(",") { "tie-$it" })
                            if ("start" in tieTypes) tiedEvents[tieKey] = events.lastIndex
                        }
                        ordinal++
                        previousStart = start
                        if (!isChord) {
                            cursor += duration
                            measureEnd = maxOf(measureEnd, cursor)
                        }
                    }
                }
            }
            if (expectedMeasureBeats != null && measureEnd > expectedMeasureBeats) {
                throw MusicXmlParseException("Measure content exceeds time signature in measure $measureNumber")
            }
            absoluteBeat += measureEnd
        }
        return ParsedPart(partId, instrument, events, tempos, signatures, keys)
    }

    private fun parseNote(
        note: Element,
        sourceId: String,
        partId: String,
        measureNumber: Int,
        beatPosition: Double,
        duration: Double,
        ordinal: Int,
        isChord: Boolean
    ): SymbolicMusicalEvent {
        val voice = note.child("voice")?.textValue()
        val staff = note.child("staff")?.textValue()
        val pitchElement = note.child("pitch")
        val rest = note.child("rest") != null
        if (!rest && pitchElement == null) throw MusicXmlParseException("Note must contain pitch or rest in part $partId")
        val pitch = pitchElement?.let { parsePitch(it, partId) }
        val trackId = partId
        val location = "part=$partId,measure=$measureNumber,ordinal=$ordinal"
        val payload = "$measureNumber:$beatPosition:$duration:${pitch?.midiNumber}:$voice:$staff:$isChord"
        val eventId = SymbolicEventIds.deterministic(sourceId, trackId, ordinal, payload)
        val chordGroup = if (isChord) "$partId-$measureNumber-$beatPosition-$voice-$staff" else null
        val velocity = note.child("velocity")?.textValue()?.toFloatOrNull()?.let {
            if (it !in 0.0f..127.0f) throw MusicXmlParseException("Velocity is out of range in $location")
            it / 127.0f
        }
        return SymbolicMusicalEvent(
            eventId = eventId,
            beatPosition = beatPosition,
            durationBeats = duration,
            measureNumber = measureNumber,
            pitch = pitch,
            velocity = velocity,
            voiceId = voice,
            chordGroupId = chordGroup,
            trackId = trackId,
            articulation = null,
            isRest = rest,
            provenance = MusicalProvenance(sourceId, partId, eventId, location)
        )
    }

    private fun parsePitch(pitch: Element, partId: String): MusicalPitch {
        val step = pitch.child("step")?.textValue()?.uppercase()
        val octave = pitch.child("octave")?.textValue()?.toIntOrNull()
        val alter = pitch.child("alter")?.textValue()?.toDoubleOrNull() ?: 0.0
        if (step !in setOf("A", "B", "C", "D", "E", "F", "G") || octave == null || alter % 1.0 != 0.0) {
            throw MusicXmlParseException("Invalid pitch in part $partId")
        }
        val pitchClass = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11).getValue(step!!)
        val midi = (octave + 1) * 12 + pitchClass + alter.toInt()
        return try {
            MusicalPitch(midi, "$step${accidental(alter.toInt())}$octave")
        } catch (_: IllegalArgumentException) {
            throw MusicXmlParseException("Pitch is outside MIDI range in part $partId")
        }
    }

    private fun durationInBeats(element: Element, divisions: Int, measureNumber: Int): Double {
        val raw = element.child("duration")?.textValue()?.toDoubleOrNull()
            ?: throw MusicXmlParseException("Duration is required in measure $measureNumber")
        if (raw <= 0.0) throw MusicXmlParseException("Duration must be positive in measure $measureNumber")
        return raw / divisions
    }

    private fun accidental(alter: Int) = when (alter) {
        -2 -> "bb"
        -1 -> "b"
        1 -> "#"
        2 -> "##"
        else -> ""
    }

    private fun failure(error: SymbolicImportError, message: String) = SymbolicImportResult.Failure(error, message)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class MeasureSlice(val number: Int, val element: Element)

    private data class ParsedPart(
        val trackId: String,
        val instrument: String?,
        val events: List<SymbolicMusicalEvent>,
        val tempos: List<TempoChange>,
        val signatures: List<TimeSignatureChange>,
        val keys: List<KeySignatureChange>
    )

    private data class TieKey(val part: String, val voice: String?, val staff: String?, val pitch: Int?)
    private class MusicXmlParseException(message: String) : Exception(message)
}

private fun Element.localNameOrTag(): String = (localName ?: tagName).substringAfterLast(':')
private fun Element.children(): List<Element> = childNodes.let { nodes ->
    (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}
private fun Element.children(name: String): List<Element> = children().filter { it.localNameOrTag() == name }
private fun Element.child(name: String): Element? = children(name).firstOrNull()
private fun Element.attribute(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }
private fun Element.requiredAttribute(name: String): String = attribute(name) ?: throw IllegalArgumentException("Required attribute $name is missing")
private fun Element.textValue(): String = textContent.trim()
private fun String.toIntOrNullOrThrow(label: String): Int = toIntOrNull() ?: throw IllegalArgumentException("$label is invalid")
