# IMPLEMENTATION STATUS

Status labels mean:

- **IMPLEMENTED**: real source path exists and tests cover it.
- **WIRED**: a production caller reaches it.
- **FOUNDATION ONLY**: contract/boundary exists but not the real engine or user flow.
- **DEFERRED**: intentionally not implemented.
- **NOT WIRED**: code exists but no production caller currently reaches it.

## Feature Matrix

| Feature | Status | Actual implementation | Entry point | Tests | Remaining gap |
|---|---|---|---|---|---|
| Core handpan teaching | IMPLEMENTED/WIRED | built-in patterns, lessons, editor, practice | MainActivity/screens/ViewModel | learning/practice/UI-related tests | UX and curriculum breadth |
| Real Handpan First | IMPLEMENTED/WIRED | acoustic evaluator and microphone evidence path | PracticeEngine + REAL_HANDPAN | `RealHandpanArchitectureTestSuite` | device validation |
| Virtual fallback | IMPLEMENTED/WIRED | virtual playback/synth path | PracticeEngine input mode | practice tests | must never provide acoustic evidence |
| Lessons | IMPLEMENTED/WIRED | lesson progress entity/DAO and studio dialog | lesson UI/ViewModel | learning/persistence tests | broader lesson content |
| Exercises | IMPLEMENTED/WIRED | `HandpanPattern`, built-ins, custom patterns, MIDI/MusicXML score import | library/editor/score picker | pattern/practice/ingestion tests | adaptation review and timeline reconstruction |
| Practice | IMPLEMENTED/WIRED | `PracticeEngine`, scheduler, evaluator | PracticeScreen | practice/scheduler tests | hardware tests |
| Progression | IMPLEMENTED/WIRED | practice progress and finalized assessments | repository/ViewModel | persistence tests | history UI |
| Skill/mastery | IMPLEMENTED/WIRED | evidence mapping and mastered skill updates | repository finalization | `LearningEngineTest` | curriculum mapping |
| Personalization/recommendations | PARTIAL/WIRED | `PersonalizationEngine`, ViewModel flow | ViewModel | learning tests | recommendation IDs are synthetic and mapping is incomplete |
| Microphone/AudioRecord | IMPLEMENTED/WIRED | `AudioAnalysisSession`, resource coordinator | evaluator | audio capture tests | device/noise/latency |
| Pitch detection | IMPLEMENTED/WIRED | interface and YIN detector | audio analysis | pitch tests | real hardware calibration |
| Onset detection | IMPLEMENTED/WIRED | onset/pitch matcher | audio analysis | matcher tests | device tuning |
| Note matching | IMPLEMENTED/WIRED | obligation-aware matcher | evaluator | target/matcher tests | legacy aggregate fields remain |
| Timing evaluation | IMPLEMENTED/WIRED | timing policies and timeline | evaluator | timing tests | hardware latency |
| Scoring/sustain | IMPLEMENTED/WIRED | quality/evidence/sustain handling | evaluator/repository | evaluator/learning tests | no new score import policy |
| Loops/BPM/speed ladder | IMPLEMENTED/WIRED | PracticeEngine state and scheduler | PracticeScreen | scheduler/practice tests | UI verification |
| Pause/resume/stop | IMPLEMENTED/WIRED | lifecycle methods and session context | PracticeEngine/evaluator | lifecycle tests | device interruption cases |
| Binary MIDI | IMPLEMENTED/WIRED | real binary parser | `ScoreIngestionUseCase` | MIDI tests/pipeline tests | broader MIDI metadata |
| Text MIDI | DEFERRED | validation only, then typed failure | `MidiScoreImporter` | importer boundary test | real parser only if required |
| MusicXML | IMPLEMENTED/WIRED | partwise/timewise parser | `ScoreIngestionUseCase` | XML tests/pipeline test | broader notation elements |
| PDF rendering | IMPLEMENTED/FOUNDATION | Android PdfRenderer, page identities/errors | `AndroidPdfScoreSource` | foundation tests/fake page test | device render test |
| PDF music recognition | DEFERRED | no OMR engine | none | explicit unsupported test | real OMR selection/adapter |
| Image input | IMPLEMENTED/FOUNDATION | bytes/Bitmap source identity | `ImageScoreSource` | foundation/pipeline tests | picker and device image tests |
| Image preprocessing | FOUNDATION ONLY | deterministic grayscale | `DeterministicImagePreprocessor` | limited foundation coverage | orientation/deskew/crop policy |
| OCR musical notes | NOT IMPLEMENTED | none | none | none | prohibited fake implementation |
| OMR | FOUNDATION ONLY/DEFERRED | `OmrEngine`, recognition result/status/uncertainty | none | recognition tests | real engine |
| Source identity/hash | IMPLEMENTED/WIRED | SHA-256 and page identity | source/import pipeline | foundation/pipeline tests | full provenance reconstruction |
| Score validation | IMPLEMENTED/WIRED | recognition score validator/importer validation | ingestion use case | invalid/uncertain tests | more musical invariants |
| Adaptation | IMPLEMENTED/WIRED | solver, decisions, quality, arrangement | ingestion use case | adaptation/pipeline tests | approval/quality threshold |
| Imported score persistence | IMPLEMENTED/WIRED | Room v7 entity/DAO/repository | use case/repository | persistence test | full timeline deserializer/history UI |
| Imported exercise persistence | IMPLEMENTED/WIRED | existing PatternEntity in transaction | repository/store | pipeline/persistence tests | user-facing import flow |

## Score -> Exercise -> Practice Truth Table

| Boundary | Source method/type | Destination method/type | Error/result | Production caller | Status |
|---|---|---|---|---|---|
| detection | `ScoreFormatDetector.detect(ByteArray)` | `DetectedScoreFormat` | `Unsupported` for unknown | `ScoreIngestionUseCase` | WIRED in use case |
| MIDI import | `BinaryMidiScoreImporter.import(ByteArray, sourceId)` | `SymbolicImportResult.Success(SymbolicScore)` | typed importer failure | use case | WIRED |
| MusicXML import | `MusicXmlScoreImporter.import(String, sourceId)` | `SymbolicImportResult` | typed importer failure | use case | WIRED |
| normalization | `NormalizedMusicalTimeline.from(SymbolicScore)` | canonical timeline | constructor invariants | use case/adaptation | WIRED |
| recognition validation | `RecognitionScoreValidator.validate(RecognitionResult)` | `ScoreValidationResult.Valid` | invalid/uncertain/rejected | image/PDF recognition path | WIRED boundary |
| adaptation | `HandpanAdaptationSolver.adapt` | `HandpanArrangement` | impossible decisions | use case | WIRED |
| exercise projection | `HandpanArrangement.toHandpanPattern` | `HandpanPattern` | pattern constructor failures | use case | WIRED |
| persistence | `ScoreIngestionStore.saveImportedExercise` | `ImportedScoreDao` + `PatternDao` | Room exception | repository | WIRED |
| practice load | `PracticeEngine.loadPattern` | Practice state | no score import API | ViewModel/screens | NOT WIRED from score UI |
| acoustic evaluation | `PracticeEngine`/`AcousticPracticeEvaluator` | finalized assessment/evidence | lifecycle/quality gate | ViewModel | WIRED for existing patterns |
| learning | `persistFinalizedAssessment` | mastered skills/recommendation flows | transaction/idempotency | ViewModel | WIRED |

## Explicit PDF/Image Rule

PDF rendering is not sheet-music recognition. Image decoding/preprocessing is not musical recognition. `OmrEngine` is an interface, not an implementation. `UNCERTAIN`, `REJECTED`, and `UNSUPPORTED` results cannot become valid exercises.

## Known Defects and Risks

### VERIFIED DEFECTS

- No complete timeline deserializer exists for the stored imported-score JSON.
- The existing UI does not call the new ViewModel score-import method.
- Text MIDI importer does not parse.

### VERIFIED LIMITATIONS

- `HandpanPattern` validates event starts but permits duration beyond total pattern length.
- Adaptation has no explicit minimum quality threshold or approval UI.
- PDF multi-page recognition merge is not implemented.

### ARCHITECTURAL/DEVICE/DATA RISKS

- OMR engine license, APK size, runtime and Android compatibility are unresolved.
- Physical microphone, noise, Bluetooth, latency and PDF renderer behavior are not proven here.
- Persisted provenance is currently location-oriented rather than a complete structured provenance codec.
- Room schema migration tests from every historical version are limited.

## Validation Evidence

The prior handoff recorded PASS for focused ingestion tests, full `testDebugUnitTest`, `lint`, and `assembleDebug`. In this continuation audit, each requested Gradle validation was blocked before task execution because the container has no Android SDK location (`ANDROID_HOME`/`local.properties`).