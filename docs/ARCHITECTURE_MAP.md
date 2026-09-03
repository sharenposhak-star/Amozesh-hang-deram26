# ARCHITECTURE MAP

## Runtime Graph

```text
MainActivity
  -> AppScreen manual dispatch
    -> HomeScreen / ExerciseLibraryScreen / PatternEditorScreen / PracticeScreen
      -> HandpanViewModel
        -> HandpanRepository
          -> Room DAOs / entities

HandpanViewModel
  -> PracticeEngine
    -> PatternScheduler + DeadlineScheduler
    -> AcousticPracticeEvaluator
      -> AudioAnalysisSession
        -> AudioResourceCoordinator -> AudioRecord
        -> PitchDetector / YinPitchDetector / onset processing
      -> MusicalTargetMatcher / TargetRegistry / TargetObligation
      -> AssessmentTimeline / quality / evidence
    -> HandpanRepository.persistFinalizedAssessment

ScoreSource
  -> ScoreFormatDetector
  -> ScoreIngestionUseCase
    -> BinaryMidiScoreImporter OR MusicXmlScoreImporter
    -> PdfScoreSource/ImageScoreSource -> OmrEngine boundary
    -> SymbolicScore
    -> NormalizedMusicalTimeline.from
    -> RecognitionScoreValidator
    -> HandpanAdaptationSolver
    -> HandpanArrangement.toHandpanPattern
    -> ScoreIngestionStore + PatternDao
```

## Source Map

| Path | Important symbols | Role | Callers | Callees/dependencies | Status/tests |
|---|---|---|---|---|---|
| `model/SymbolicMusic.kt` / `model/ScoreIngestionPipeline.kt` | score/timeline/provenance/IDs and `TimelineJsonCodec` | canonical score and reconstruction | importers, adaptation, persistence reload | Java SHA-256, org.json | implemented; `SymbolicMusicTest`, persistence tests |
| `model/ScoreIngestionFoundation.kt` | PDF/image/recognition/validator | recognition boundary | pipeline | Android Bitmap/PdfRenderer | foundation; `ScoreIngestionFoundationTest` |
| `model/ScoreIngestionPipeline.kt` | detector/source/use case/results | application ingestion | ViewModel/tests | importers, solver, store | implemented/deferred; `ScoreIngestionPipelineTest` |
| `model/BinaryMidiScoreImporter.kt` | binary importer | MIDI parsing | pipeline | `SymbolicEventIds` | implemented; `MidiScoreImporterTest` |
| `model/HandpanAdaptation.kt` | importer facade/solver/arrangement/quality | adaptation | pipeline, tests | instrument profile, timeline | implemented; `HandpanAdaptationTest` |
| `model/StandardMusicXmlScoreImporter.kt` | XML parser | MusicXML parsing | facade/pipeline | secure JAXP DOM | implemented; XML tests |
| `model/HandpanPattern.kt` | `HandpanPattern` | exercise contract | repository/editor/practice | `NoteEvent` | implemented |
| `model/NoteEvent.kt` | `NoteEvent` | playable event | pattern/scheduler/codec | handpan note model | implemented |
| `model/InstrumentProfile.kt` | scale profiles | mapping target | ViewModel/adaptation | tone field definitions | implemented |
| `audio/PracticeEngine.kt` | load/play/pause/stop | orchestration | ViewModel/PracticeScreen | evaluator/scheduler/audio | implemented; practice tests |
| `audio/AcousticPracticeEvaluator.kt` | assessment lifecycle | real microphone evaluation | PracticeEngine | analysis session/matcher | implemented; real handpan tests |
| `audio/AudioAnalysisSession.kt` | capture subscription | microphone session | evaluator/recorder | AudioRecord/resource coordinator | implemented; audio tests |
| `audio/AudioResourceCoordinator.kt` | shared audio ownership | resource safety | analysis/recorder | Android audio | implemented |
| `audio/PitchDetector.kt` | detector interface/base | pitch extraction | analysis session | audio frame data | implemented |
| `audio/YinPitchDetector.kt` | YIN detector | pitch extraction | detector path/tests | numeric audio processing | implemented |
| `audio/OnsetAndPitchMatcher.kt` | onset/pitch matching | audio event extraction | analysis | pitch/timing config | implemented; matcher tests |
| `audio/PatternScheduler.kt` | target scheduling | event obligations | PracticeEngine/evaluator | `TargetObligation` | implemented; scheduler tests |
| `audio/HandpanSynthesizer.kt` | virtual sound | fallback playback | AudioEngine/virtual path | Android audio | implemented; not evidence |
| `audio/MetronomeEngine.kt` | metronome | beat clicks/haptics | ViewModel/PracticeEngine | clock/audio | implemented |
| `data/repository/HandpanRepository.kt` | persistence facade | patterns/progress/assessments/imports | ViewModel/PracticeEngine | Room DAOs | implemented; persistence tests |
| `data/local/AppDatabase.kt` | Room DB v8 | schema/migrations | ViewModel/repository/tests | Room | implemented; approval migration 7->8 |
| `data/local/ImportedScoreEntity.kt` | imported metadata | score persistence | repository/DAO | pipeline record | implemented; persistence test |
| `data/local/ImportedScoreDao.kt` | imported score queries | score persistence | repository/tests | Room | implemented |
| `ui/HandpanViewModel.kt` | application state | UI/practice/repository composition | MainActivity/screens | repository, engines, ingestion use case | wired; score import and approval callers exist |
| `ui/screens/ExerciseLibraryScreen.kt` | exercise library | pattern browsing/import | MainActivity | ViewModel | implemented; score import not wired |
| `ui/screens/PatternEditorScreen.kt` | pattern editor | manual pattern creation | MainActivity | ViewModel | implemented |
| `ui/screens/PracticeScreen.kt` | practice UI | practice controls/status | MainActivity | ViewModel/PracticeEngine | implemented |
| `ui/screens/HomeScreen.kt` | home | mode/entry selection | MainActivity | ViewModel | implemented |

## Persistence Inventory

| Table/entity | Primary use | DAO |
|---|---|---|
| `patterns` / `PatternEntity` | custom exercise definitions | `PatternDao` |
| `practice_progress` | aggregate practice progress | `PracticeProgressDao` |
| `lesson_progress` | lesson completion/stars | `LessonProgressDao` |
| `recording_tracks` | recorded audio/event tracks | `RecordingTrackDao` |
| `assessments` | finalized assessment summary | `AssessmentDao` |
| `assessment_evidence` | evidence metrics | `EvidenceDao` |
| `processed_assessments` | assessment idempotency | `ProcessedAssessmentDao` |
| `mastered_skills` | learning state | `MasteredSkillDao` |
| `imported_scores` | score source/validation/adaptation metadata | `ImportedScoreDao` |

## Caller Truth

- `ExerciseLibraryScreen` calls JSON `saveCustomPattern` and uses an OpenDocument picker for binary MIDI/MusicXML, converting bytes through `scoreSourceFromImportableBytes` before calling `HandpanViewModel.importScore`.
- `HandpanViewModel.importScore` calls `ScoreIngestionUseCase.ingestAndAdapt` asynchronously and returns a typed result callback.
- `ScoreIngestionUseCase` calls `saveImportedScore` on successful import and `saveImportedExercise` after successful adaptation.
- `PracticeEngine` is loaded from ViewModel with a `HandpanPattern`; it does not import scores itself.
- `persistFinalizedAssessment` is called from ViewModel after acoustic assessment finalization and requires valid assessment quality.
- No UI caller exists for PDF/Image source creation or OMR.