# CODEBASE HANDOFF

Generated from the repository source tree on 2026-09-03. This document describes observed code, callers, tests, and known gaps. It is not a product roadmap disguised as status.

## 1. Repository Identity

| Field | Observed value |
|---|---|
| Repository | `Amozesh-hang-deram25` from Git remotes; attached repository identity says `Amozesh-hang-deram24` |
| Owner/remotes | `origin`: `https://github.com/alikakai2101-sys/Amozesh-hang-deram25`; `upstream`: `https://github.com/alikakaii194-rgb/Amozesh-hang-deram24.git` |
| Branch | `main` |
| HEAD | `cd55536d7b3fca7717f9a27e33a76b965712499d` |
| Working tree | Clean at audit baseline; implementation changes are currently uncommitted |
| Untracked files | None observed at audit baseline |
| Uncommitted changes | Score byte source factory, Exercise Library file-picker caller, and focused regression test |
| Gradle wrapper | Gradle 9.3.1 (`./gradlew --version`) |
| AGP | 9.1.1 (`gradle/libs.versions.toml`) |
| Kotlin | 2.2.10 catalog; Gradle reports Kotlin 2.2.21 |
| KSP | 2.3.5 |
| compileSdk | 36, minor API 1 |
| minSdk | 24 |
| targetSdk | 36 |
| Java compile target | Java 11 |
| Running JDK | OpenJDK 25.0.2 Microsoft build |
| Namespace/application | `com.example`; `com.sharn.handpan` |

Important configuration: Compose, Room/KSP, Google Services, Secrets Gradle plugin, Roborazzi, Java 11 compile options, Room schema export, and release signing validation. No PDF, OCR, OMR, or image-processing dependency is declared.

## 2. Architecture Summary

The observed application graph is:

`MainActivity -> AppScreen dispatch -> screens/components -> HandpanViewModel -> HandpanRepository -> Room DAOs/entities`

Practice is separately composed by the ViewModel:

`HandpanViewModel -> PracticeEngine -> PatternScheduler/DeadlineScheduler -> AcousticPracticeEvaluator -> AudioAnalysisSession -> AudioResourceCoordinator -> AudioRecord/PitchDetector -> AssessmentTimeline -> HandpanRepository`

Score ingestion is now an application boundary, but only some arrows have production callers:

`ScoreSource -> ScoreIngestionUseCase -> importer/recognition boundary -> SymbolicScore -> NormalizedMusicalTimeline -> validator -> HandpanAdaptationSolver -> HandpanArrangement -> HandpanPattern -> ScoreIngestionStore/PatternDao`

The Exercise Library now calls the ViewModel's `importScore` through an OpenDocument picker for binary MIDI and MusicXML. The score-to-exercise path is user-reachable for those formats; PDF/image remain deferred without OMR.

## 3. Canonical Models

| File | Symbol | Responsibility | Callers/callees | State |
|---|---|---|---|---|
| `app/src/main/java/com/example/model/SymbolicMusic.kt` | `SymbolicScore`, `SymbolicTrack`, `SymbolicMusicalEvent`, `NormalizedMusicalTimeline`, `SourceMetadata`, `MusicalProvenance` | Canonical symbolic score and normalized timing contract | Importers, adaptation, ingestion tests | Implemented and tested |
| `app/src/main/java/com/example/model/HandpanPattern.kt` | `HandpanPattern` | Existing playable exercise contract | Built-ins, editor, repository, PracticeEngine | Implemented |
| `app/src/main/java/com/example/model/NoteEvent.kt` | `NoteEvent` | Handpan note/rest event | Pattern, scheduler, evaluator, persistence codec | Implemented |
| `app/src/main/java/com/example/model/InstrumentProfile.kt` | `InstrumentProfile` | Tone-field/pitch mapping for handpan scales | Adaptation request and UI scale state | Implemented |
| `app/src/main/java/com/example/model/HandpanAdaptation.kt` | `AdaptationRequest`, `AdaptationDecision`, `HandpanArrangement`, solver, quality calculator | Canonical score adaptation and decision provenance | Score ingestion use case, adaptation tests | Implemented; review state is owned by ingestion pipeline |
| `app/src/main/java/com/example/model/ScoreIngestionFoundation.kt` | source identity, PDF/image sources, recognition contracts, validator | PDF/image/OMR boundary and recognition validation | Pipeline and foundation tests | Foundation; no OMR engine |
| `app/src/main/java/com/example/model/ScoreIngestionPipeline.kt` | `ScoreFormatDetector`, `ScoreSource`, `ScoreIngestionUseCase`, result/error contracts | One application ingestion boundary | ViewModel and pipeline tests | Implemented for binary MIDI/MusicXML; PDF/image deferred |

## 4. Importers

- `BinaryMidiScoreImporter.kt`: parses MIDI format 0/1 binary chunks, tracks, notes, tempo and time signatures; emits deterministic event IDs and source hash. It rejects malformed headers, tracks, running status, durations and unclosed notes.
- `HandpanAdaptation.kt: MidiScoreImporter`: text MIDI-like input is deliberately validated then returned as `PARSE_FAILED`. It is not a parser and must remain `DEFERRED` unless a real requirement appears.
- `HandpanAdaptation.kt: MusicXmlScoreImporter`: delegates to `StandardMusicXmlScoreImporter`.
- `StandardMusicXmlScoreImporter.kt`: parses `score-partwise` and `score-timewise`, part definitions, measures, divisions, cursor movement, voice/staff, rests, chords, ties, tempo, time and key signatures, with secure XML parser settings.
- `StructuredScoreImporter`: only accepts an already typed `SymbolicScore` through `importScore`; string import intentionally fails.

## 5. Score Ingestion Matrix

| Input | Detection | Import/recognition | Normalization | Validation | Practice eligibility |
|---|---|---|---|---|---|
| Binary MIDI | `MThd` magic bytes | `BinaryMidiScoreImporter` | `NormalizedMusicalTimeline.from` | importer plus recognition validator path | Yes after adaptation |
| Text MIDI | MIDI-like text check only | No real parser | No | failure result | No; deferred |
| MusicXML partwise | XML content signature | `StandardMusicXmlScoreImporter` | same canonical timeline | importer plus validator | Yes after adaptation |
| MusicXML timewise | XML content signature | same importer | same canonical timeline | importer plus validator | Yes after adaptation |
| Structured PDF | `%PDF-` | Android `PdfRenderer` only | no notation extraction | rendering result only | No without OMR |
| Scanned PDF | `%PDF-` | page rasterization boundary | image source only | rendering failure explicit | No without OMR |
| Image | PNG/JPEG/GIF/BMP/WebP signatures | `OmrEngine` boundary | deterministic grayscale helper | recognition validator if engine exists | No without OMR |
| OCR musical notes | None | Not implemented | None | None | No |
| OMR | `OmrEngine` interface only | No engine dependency | None | `RecognitionResult`/validator boundary | No |

## 6. Practice and Real Handpan

`PracticeEngine.kt` owns load/play/pause/resume/stop, preview/count-in, loop, BPM, speed multiplier, speed ladder, target state and round completion. `PracticeInputMode` contains `REAL_HANDPAN` and `VIRTUAL_HANDPAN`.

REAL_HANDPAN is the primary invariant. The real path is:

`PracticeEngine -> AcousticPracticeEvaluator.startAssessment(context, pattern, scaleConfig, bpm) -> AudioAnalysisSession.acquire -> AudioRecord capture -> PitchDetector/YinPitchDetector and onset processing -> MusicalTargetMatcher -> AssessmentTimeline -> assessment quality/evidence -> HandpanRepository.persistFinalizedAssessment`

`VIRTUAL_HANDPAN` is a fallback/playback path and must never manufacture microphone evidence. `AudioResourceCoordinator` protects shared capture resources. Error states include capture failure and microphone error; pause/resume/stop are session-aware. Existing tests cover lifecycle, matching, timing, calibration, persistence, and Real Handpan architecture.

## 7. Target Identity

The old `Set<Int>` representation cannot distinguish two simultaneous occurrences of the same pitch. The current target path uses `TargetObligation` with an occurrence-specific `obligationId`; `TargetIdentity` carries target identity and expected notes, `TargetRegistry` tracks consumed obligations, `PatternScheduler` creates one obligation per event occurrence, and `MusicalTargetMatcher` consumes the matching obligation rather than only a pitch number. Assessment timeline events persist obligation and target IDs.

Legacy `expectedNotes: Set<Int>` compatibility fields remain in assessment-facing models for aggregate/display semantics, but matching uses obligations. Tests include `PatternSchedulerTargetIdentityTest`, matcher tests, and scheduler/evaluator integration tests.

## 8. Persistence

`AppDatabase` is Room version 8. Migrations are `1->2`, `2->3`, `3->4`, `4->5`, `5->6`, `6->7`, and `7->8`. Imported score records persist deterministic `NOT_REQUIRED`, `PENDING`, `APPROVED`, or `REJECTED` adaptation approval state. Entities and DAOs remain unchanged apart from the imported-score field.

`PatternEntity` serializes `HandpanPattern.events` as JSON (`n`, `b`, `d`, `v`, `a`, `r`, optional `h`). `RecordingTrackEntity` uses `RecordingTrackCodec` for recorded events/timeline. Imported scores store metadata, complete structured provenance, format/status/confidence/page count, adaptation metrics, and a deterministic versioned canonical timeline JSON string. `TimelineJsonCodec.decode` reconstructs `NormalizedMusicalTimeline` with typed malformed/unknown-version failures.

`HandpanRepository` combines built-ins with custom patterns, saves custom/imported exercises, persists imported-score records, records practice progress, and transactionally persists finalized valid assessments plus evidence and learning updates.

## 9. UI Map

- `MainActivity.kt`: creates the Compose host and manually dispatches `AppScreen` values. Caller of screens; no score file picker.
- `ui/screens/HomeScreen.kt`: home, mode/scale selection, built-in entry points. Uses ViewModel state.
- `ui/screens/ExerciseLibraryScreen.kt`: built-in/custom exercise list, JSON pattern import/export, audio transcription preview, and MIDI/MusicXML score import through `importScore`.
- `ui/screens/PatternEditorScreen.kt`: manually creates and edits numeric `HandpanPattern` values.
- `ui/screens/PracticeScreen.kt`: displays practice state, mode, calibration, feedback and summary; uses `PracticeEngine` through ViewModel.
- `ui/screens/MetronomeScreen.kt`, `RhythmTrainerScreen.kt`, `SettingsScreen.kt`: existing auxiliary flows.
- `ui/components/ImportPatternDialog.kt`: JSON pattern import only.
- Other dialogs/components cover guides, scales, backing tracks, recording, samples, export, lessons and assessment summary.

No UI redesign was added; score import uses the existing Exercise Library surface.

## 10. Tests and Validation

Important test files include `MidiScoreImporterTest.kt`, `MusicXmlScoreImporterTest.kt`, `TimewiseMusicXmlImporterTest.kt`, `SymbolicMusicTest.kt`, `ScoreIngestionFoundationTest.kt`, `ScoreIngestionPipelineTest.kt`, `HandpanAdaptationTest.kt`, `ImportedScorePersistenceTest.kt`, `LearningEngineTest.kt`, `PracticeEngineEndToEndPersistenceTest.kt`, `DurableAssessmentPersistenceTest.kt`, `RealHandpanArchitectureTestSuite.kt`, `AcousticPracticeEvaluator`-related tests, `PatternSchedulerAndFractionalBeatTest.kt`, `PatternSchedulerTargetIdentityTest.kt`, `SchedulerEvaluatorTimelineIntegrationTest.kt`, and `data/local/PersistentDataArchitectureTest.kt`.

Verified commands after the final source change:

```text
./gradlew :app:testDebugUnitTest --tests com.example.ScoreIngestionPipelineTest --tests com.example.data.local.ImportedScorePersistenceTest --tests com.example.ScoreIngestionFoundationTest --console=plain  PASS
./gradlew testDebugUnitTest --console=plain  PASS
./gradlew lint --console=plain  PASS
./gradlew assembleDebug --console=plain  PASS
```

APK: `app/build/outputs/apk/debug/app-debug.apk`, 19,423,416 bytes, SHA-256 `37ae93698ed99c4445fe28a554a7b1428c37b7078cb396cbfd894bddd559997a7`.

Known environment limitation: no physical microphone/device/PDF rendering instrumentation run was performed in this container; JVM/Robolectric tests cannot prove hardware latency, noise, Bluetooth, or camera behavior. Gradle emits existing deprecation and restricted-native-access warnings; validation tasks still pass.

## 11. Status Summary

### DONE

- Binary MIDI and MusicXML canonical import/normalization.
- Existing practice, audio, learning and assessment persistence paths.
- Content-based score format detection.
- Typed ingestion results/errors.
- Canonical ingestion use case for binary MIDI/MusicXML.
- Adaptation and playable exercise creation boundary.
- Imported-score Room metadata persistence and migration 7.
- PDF renderer/image source/OMR contracts without fake recognition.
- Focused and full unit tests, lint and debug assembly.

### PARTIAL

- Score ingestion has a ViewModel entry point but no file-picker/review UI caller.
- PDF rendering is implemented, but PDF sheet-music recognition is not.
- Image loading/preprocessing is implemented, but image music recognition is not.
- Adaptation records decisions and quality, and partial adaptations now require explicit approval before playable exercise persistence. A product-defined minimum quality threshold remains absent.
- Imported timeline JSON is stored, but no complete reconstruction API exists.

### NOT IMPLEMENTED / DEFERRED

- Real OMR engine.
- OCR-to-musical-notes.
- Text MIDI parser.
- Multi-page OMR merge into one score.
- Imported-score review/history UI.

## 12. Critical Risks

- Do not interpret PDF rendering as notation recognition.
- Do not let uncertain/rejected recognition create notes or patterns.
- Newly persisted provenance is structured and lossless; historical location-only rows cannot recover fields that were never stored.
- `HandpanPattern` checks event start positions but not whether duration exceeds pattern length.
- No minimum adaptation-quality threshold blocks a mostly reduced arrangement.
- No device-level audio/PDF tests are available here.
- Existing UI has manual navigation and the new score import method is not yet user reachable.

See `ARCHITECTURE_MAP.md`, `IMPLEMENTATION_STATUS.md`, and `CONTINUATION_CONTRACT.md` for the compact continuation-oriented views.