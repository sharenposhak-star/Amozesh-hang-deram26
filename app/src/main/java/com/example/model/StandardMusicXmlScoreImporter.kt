package com.example.model

import java.security.MessageDigest
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

class StandardMusicXmlScoreImporter {
    fun import(source: String, sourceId: String): SymbolicImportResult {
        if (sourceId.isBlank()) return failure(SymbolicImportError.INVALID_SOURCE, "Source id is required")
        if (source.isBlank()) return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML source is empty")
        if (source.contains("<!DOCTYPE", ignoreCase = true) && source.contains("ENTITY", ignoreCase = true) && source.contains("SYSTEM", ignoreCase = true)) {
            return failure(SymbolicImportError.INVALID_SOURCE, "External entities are not allowed in MusicXML")
        }

        return try {
            val document = parseDocument(source)
            val root = document.documentElement ?: return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML root element is missing")
            val partDefinitions = extractPartDefinitions(root)
            val partMeasures = when (root.localNameOrTag()) {
                "score-partwise" -> parsePartwiseMeasures(root, partDefinitions)
                "score-timewise" -> parseTimewiseMeasures(root, partDefinitions)
                else -> return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML root must be score-partwise or score-timewise")
            }
            if (partMeasures.isEmpty()) return failure(SymbolicImportError.INVALID_SOURCE, "MusicXML contains no valid measures")

            val parsedParts = partMeasures.map { (partId, measures) ->
                val definition = partDefinitions[partId] ?: return failure(SymbolicImportError.INVALID_SOURCE, "Every part must have matching score-part metadata")
                parsePart(partId, measures, definition, sourceId)
            }

            SymbolicScore(
                metadata = SourceMetadata(
                    sourceId = sourceId,
                    title = root.child("work")?.child("work-title")?.textValue(),
                    composer = root.child("identification")?.children("creator")
                        ?.firstOrNull { it.attribute("type") == "composer" }?.textValue(),
                    format = SymbolicSourceFormat.MUSIC_XML,
                    sourceHash = sha256(source.toByteArray(Charsets.UTF_8))
                ),
                tempoMap = parsedParts.flatMap { it.tempos }
                    .sortedWith(compareBy<TempoChange>({ it.beatPosition }, { it.bpm }))
                    .distinctBy { it.beatPosition to it.bpm },
                timeSignatureMap = parsedParts.flatMap { it.signatures }
                    .sortedWith(compareBy<TimeSignatureChange>({ it.beatPosition }, { it.timeSignature.numerator }, { it.timeSignature.denominator }))
                    .distinctBy { it.beatPosition to it.timeSignature },
                keySignatureMap = parsedParts.flatMap { it.keys }
                    .sortedWith(compareBy<KeySignatureChange>({ it.beatPosition }, { it.key ?: "" }, { it.mode ?: "" }))
                    .distinctBy { it.beatPosition to it.key to it.mode },
                tracks = parsedParts.map { part -> SymbolicTrack(trackId = part.trackId, instrument = part.instrument, events = part.events) }
            ).let { SymbolicImportResult.Success(it) }
        } catch (error: InvalidMusicXmlException) {
            failure(SymbolicImportError.INVALID_SOURCE, error.message ?: "MusicXML source is invalid")
        } catch (error: MusicXmlParseException) {
            failure(SymbolicImportError.PARSE_FAILED, error.message ?: "MusicXML parse failed")
        } catch (error: IllegalArgumentException) {
            failure(SymbolicImportError.INVALID_SOURCE, error.message ?: "MusicXML source is malformed")
        } catch (error: Exception) {
            failure(SymbolicImportError.INVALID_SOURCE, error.message ?: "MusicXML source is malformed")
        }
    }

    private fun parseDocument(source: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isCoalescing = true
            isExpandEntityReferences = false
            isXIncludeAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(source.byteInputStream(Charsets.UTF_8)))
    }

    private fun extractPartDefinitions(root: Element): Map<String, Element> {
        val partList = root.child("part-list") ?: throw InvalidMusicXmlException("MusicXML part-list is required")
        val definitions = mutableMapOf<String, Element>()
        for (scorePart in partList.children("score-part")) {
            val id = scorePart.requiredAttribute("id")
            if (id in definitions) throw InvalidMusicXmlException("Duplicate score-part id: $id")
            definitions[id] = scorePart
        }
        if (definitions.isEmpty()) throw InvalidMusicXmlException("MusicXML part-list is empty")
        return definitions
    }

    private fun parsePartwiseMeasures(root: Element, partDefinitions: Map<String, Element>): List<Pair<String, List<MeasureSlice>>> {
        val byPart = linkedMapOf<String, MutableList<MeasureSlice>>()
        for (part in root.children("part")) {
            val partId = part.requiredAttribute("id")
            if (partId !in partDefinitions) throw InvalidMusicXmlException("Part $partId must be declared in part-list")
            if (partId in byPart) throw InvalidMusicXmlException("Duplicate part id: $partId")
            val measures = part.children("measure")
            if (measures.isEmpty()) throw InvalidMusicXmlException("Part $partId has no measures")
            val seenNumbers = mutableSetOf<Int>()
            val sliceList = mutableListOf<MeasureSlice>()
            for (measure in measures) {
                val number = measure.requiredAttribute("number").toIntOrNullOrThrow("measure number")
                if (!seenNumbers.add(number)) throw InvalidMusicXmlException("Duplicate measure number $number in part $partId")
                sliceList += MeasureSlice(number, measure)
            }
            byPart[partId] = sliceList
        }
        if (byPart.isEmpty()) throw InvalidMusicXmlException("MusicXML contains no parts")
        return byPart.toList()
    }

    private fun parseTimewiseMeasures(root: Element, partDefinitions: Map<String, Element>): List<Pair<String, List<MeasureSlice>>> {
        val byPart = linkedMapOf<String, MutableList<MeasureSlice>>()
        val seenNumbersByPart = mutableMapOf<String, MutableSet<Int>>()
        val measures = root.children("measure")
        if (measures.isEmpty()) throw InvalidMusicXmlException("Score-timewise has no measures")
        for (measure in measures) {
            val measureNumber = measure.requiredAttribute("number").toIntOrNullOrThrow("measure number")
            val partIdsInMeasure = mutableSetOf<String>()
            for (part in measure.children("part")) {
                val partId = part.requiredAttribute("id")
                if (partId !in partDefinitions) throw InvalidMusicXmlException("Part $partId must be declared in part-list")
                if (!partIdsInMeasure.add(partId)) throw InvalidMusicXmlException("Duplicate part id $partId in measure $measureNumber")
                val seenNumbers = seenNumbersByPart.getOrPut(partId) { mutableSetOf() }
                if (!seenNumbers.add(measureNumber)) throw InvalidMusicXmlException("Duplicate measure number $measureNumber in part $partId")
                byPart.getOrPut(partId) { mutableListOf() }.add(MeasureSlice(measureNumber, part))
            }
        }
        if (byPart.isEmpty()) throw InvalidMusicXmlException("Score-timewise contains no part data")
        return byPart.toList()
    }

    private fun parsePart(partId: String, measures: List<MeasureSlice>, definition: Element, sourceId: String): ParsedPart {
        val instrument = definition.child("part-name")?.textValue()
        var divisions: Int? = null
        var absoluteBeat = 0.0
        var eventOrdinal = 0
        val events = mutableListOf<SymbolicMusicalEvent>()
        val tempos = mutableListOf<TempoChange>()
        val signatures = mutableListOf<TimeSignatureChange>()
        val keys = mutableListOf<KeySignatureChange>()
        val tieStarts = linkedMapOf<TieKey, Int>()

        for (measureSlice in measures) {
            val measureNumber = measureSlice.number
            val measure = measureSlice.element
            val attributes = measure.child("attributes")
            if (attributes != null) {
                attributes.child("divisions")?.textValue()?.toIntOrNull()?.let { value ->
                    if (value <= 0) throw MusicXmlParseException("Divisions must be positive in measure $measureNumber")
                    divisions = value
                }
                attributes.child("time")?.let { time ->
                    val numerator = time.child("beats")?.textValue()?.toIntOrNullOrThrow("time numerator")
                        ?: throw MusicXmlParseException("Time numerator is missing in measure $measureNumber")
                    val denominator = time.child("beat-type")?.textValue()?.toIntOrNullOrThrow("time denominator")
                        ?: throw MusicXmlParseException("Time denominator is missing in measure $measureNumber")
                    signatures += TimeSignatureChange(absoluteBeat, TimeSignature(numerator, denominator))
                }
                attributes.child("key")?.let { key ->
                    val fifths = key.child("fifths")?.textValue()
                    val mode = key.child("mode")?.textValue()
                    if (fifths == null) throw MusicXmlParseException("Key fifths are missing in measure $measureNumber")
                    keys += KeySignatureChange(absoluteBeat, "fifths=$fifths", mode)
                }
            }

            val currentDivisions = divisions ?: throw MusicXmlParseException("Divisions are required before notes in measure $measureNumber")
            var cursor = 0.0
            var measureEnd = 0.0
            val voiceOffsets = mutableMapOf<VoiceStaffKey, Double>()
            val lastStarts = mutableMapOf<VoiceStaffKey, Double>()

            for (item in measure.children()) {
                when (item.localNameOrTag()) {
                    "direction" -> item.child("sound")?.attribute("tempo")?.toDoubleOrNull()?.let { bpm ->
                        if (bpm <= 0.0) throw MusicXmlParseException("Tempo must be positive in measure $measureNumber")
                        tempos += TempoChange(absoluteBeat + cursor, bpm)
                    }
                    "backup" -> {
                        val delta = durationInBeats(item, currentDivisions, measureNumber)
                        cursor -= delta
                        if (cursor < 0.0) throw MusicXmlParseException("Backup moves before measure start in measure $measureNumber")
                        measureEnd = maxOf(measureEnd, cursor)
                    }
                    "forward" -> {
                        val delta = durationInBeats(item, currentDivisions, measureNumber)
                        cursor += delta
                        measureEnd = maxOf(measureEnd, cursor)
                    }
                    "note" -> {
                        val duration = durationInBeats(item, currentDivisions, measureNumber)
                        val isChord = item.child("chord") != null
                        val voice = item.child("voice")?.textValue()
                        val staff = item.child("staff")?.textValue()
                        val voiceKey = VoiceStaffKey(voice, staff)
                        val existingStart = lastStarts[voiceKey] ?: cursor
                        val start = if (isChord) existingStart else cursor
                        val pitch = item.child("pitch")?.let { parsePitch(it, partId) }
                        val rest = item.child("rest") != null
                        if (!rest && pitch == null) throw MusicXmlParseException("Note must contain pitch or rest in measure $measureNumber")
                        val velocity = item.child("velocity")?.textValue()?.toFloatOrNull()?.let {
                            if (it < 0.0f || it > 127.0f) throw MusicXmlParseException("Velocity is out of range in measure $measureNumber")
                            it / 127.0f
                        }
                        val location = "part=$partId,measure=$measureNumber,voice=$voice,staff=$staff"
                        val eventIdPayload = "$partId|$measureNumber|${absoluteBeat + start}|${pitch?.midiNumber ?: "rest"}|$duration|$voice|$staff|$isChord"
                        val eventId = SymbolicEventIds.deterministic(sourceId, partId, eventOrdinal++, eventIdPayload)
                        val event = SymbolicMusicalEvent(
                            eventId = eventId,
                            beatPosition = absoluteBeat + start,
                            durationBeats = duration,
                            measureNumber = measureNumber,
                            pitch = pitch,
                            velocity = velocity,
                            voiceId = voice,
                            trackId = partId,
                            chordGroupId = if (isChord) "$partId-$measureNumber-$voice-$staff-${absoluteBeat + start}" else null,
                            articulation = null,
                            isRest = rest,
                            provenance = MusicalProvenance(sourceId, partId, eventId, location)
                        )
                        val tieTypes = item.children("tie").mapNotNull { it.attribute("type") }
                        if (tieTypes.any { it !in setOf("start", "stop") }) {
                            throw MusicXmlParseException("Invalid tie type in measure $measureNumber")
                        }
                        if (isChord) {
                            val chordId = "$partId-$measureNumber-$voice-$staff-${absoluteBeat + start}"
                            val previousChordIndex = events.indexOfLast {
                                it.trackId == partId && it.voiceId == voice && it.beatPosition == absoluteBeat + start
                            }
                            if (previousChordIndex >= 0) {
                                events[previousChordIndex] = events[previousChordIndex].copy(chordGroupId = chordId)
                            }
                        }
                        val tieKey = TieKey(partId, voice, staff, pitch?.midiNumber)
                        if (tieTypes.any { it == "stop" }) {
                            val previousIndex = tieStarts[tieKey]
                                ?: throw MusicXmlParseException("Malformed tie stop without matching tie start in measure $measureNumber")
                            val previous = events[previousIndex]
                            events[previousIndex] = previous.copy(
                                durationBeats = (absoluteBeat + start + duration) - previous.beatPosition,
                                articulation = listOfNotNull(previous.articulation, "tie-stop").distinct().joinToString(",")
                            )
                            if (tieTypes.any { it == "start" }) {
                                tieStarts[tieKey] = previousIndex
                            } else {
                                tieStarts.remove(tieKey)
                            }
                        } else {
                            events += event.copy(
                                articulation = tieTypes.takeIf { it.isNotEmpty() }?.joinToString(",") { "tie-$it" }
                            )
                            if (tieTypes.any { it == "start" }) tieStarts[tieKey] = events.lastIndex
                        }

                        if (rest) {
                            measureEnd = maxOf(measureEnd, start + duration)
                        } else if (!isChord) {
                            val nextCursor = start + duration
                            cursor = maxOf(cursor, nextCursor)
                            voiceOffsets[voiceKey] = nextCursor
                            measureEnd = maxOf(measureEnd, nextCursor)
                        } else {
                            val nextCursor = start + duration
                            voiceOffsets[voiceKey] = maxOf(voiceOffsets[voiceKey] ?: start, nextCursor)
                            measureEnd = maxOf(measureEnd, nextCursor)
                        }
                        lastStarts[voiceKey] = start
                    }
                }
            }

            val currentTimeSignature = signatures.lastOrNull() ?: TimeSignatureChange(absoluteBeat, TimeSignature.Common44)
            if (measureEnd > currentTimeSignature.timeSignature.beatsPerBar) {
                throw MusicXmlParseException("Measure content exceeds time signature in measure $measureNumber")
            }
            val measureLength = if (measure.children("note").isEmpty()) {
                currentTimeSignature.timeSignature.beatsPerBar.toDouble()
            } else {
                measureEnd
            }
            if (measure.children("note").isEmpty() && measureLength > 0.0) {
                val restEvent = SymbolicMusicalEvent(
                    eventId = SymbolicEventIds.deterministic(sourceId, partId, eventOrdinal++, "${partId}|${measureNumber}|rest|${absoluteBeat}|$measureLength"),
                    beatPosition = absoluteBeat,
                    durationBeats = measureLength,
                    measureNumber = measureNumber,
                    isRest = true,
                    trackId = partId,
                    provenance = MusicalProvenance(sourceId, partId, null, "part=$partId,measure=$measureNumber,implicit-rest")
                )
                events += restEvent
            }
            absoluteBeat += measureLength
        }

            if (tieStarts.isNotEmpty()) {
                throw MusicXmlParseException("Malformed tie start without matching tie stop in part $partId")
            }

        return ParsedPart(partId, instrument, events, tempos, signatures, keys)
    }

    private fun parsePitch(pitch: Element, partId: String): MusicalPitch {
        val step = pitch.child("step")?.textValue()?.uppercase()
        val octave = pitch.child("octave")?.textValue()?.toIntOrNull()
        val alterValue = pitch.child("alter")?.textValue()?.toDoubleOrNull() ?: 0.0
        val alter = alterValue.roundToInt()
        if (step !in setOf("A", "B", "C", "D", "E", "F", "G") || octave == null || abs(alterValue - alter) > 0.0001) {
            throw MusicXmlParseException("Invalid pitch in part $partId")
        }
        val pitchMap = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
        val midi = (octave!! + 1) * 12 + pitchMap.getValue(step!!) + alter
        return try {
            MusicalPitch(midi, "$step${accidental(alter)}$octave")
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
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class MeasureSlice(val number: Int, val element: Element)
    private data class ParsedPart(
        val trackId: String,
        val instrument: String?,
        val events: List<SymbolicMusicalEvent>,
        val tempos: List<TempoChange>,
        val signatures: List<TimeSignatureChange>,
        val keys: List<KeySignatureChange>
    )
    private data class VoiceStaffKey(val voice: String?, val staff: String?)
    private data class TieKey(val part: String, val voice: String?, val staff: String?, val pitch: Int?)
    private class MusicXmlParseException(message: String) : Exception(message)
    private class InvalidMusicXmlException(message: String) : Exception(message)
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
private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
private fun abs(value: Double): Double = kotlin.math.abs(value)
