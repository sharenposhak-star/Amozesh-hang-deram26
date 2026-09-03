# CONTINUATION CONTRACT

The next coding agent must treat the current source tree as authoritative and must preserve these rules.

1. Inspect the current repository before editing.
2. Never assume a feature exists because an interface exists.
3. Trace real callers before adding a second implementation.
4. Preserve `REAL_HANDPAN` as the primary learning and practice path.
5. Never create fake OCR or OMR.
6. Never silently convert uncertain recognition into valid notes.
7. Preserve deterministic source hashes and event/occurrence identities.
8. Preserve Room migration history and add migrations rather than rewriting history.
9. Run focused tests before full validation.
10. Run full unit tests, lint, and assemble after meaningful changes.
11. Report failures as failures.
12. Never claim PASS from truncated output; capture the task completion status.
13. Modify only files justified by the current task.
14. Never reset, revert, checkout, clean, stash, or discard user work.
15. Avoid duplicate score models, repositories, importers, or use cases; extend the canonical boundary.
16. Distinguish `implemented`, `wired`, `tested`, `foundation only`, `deferred`, and `not implemented`.
17. Keep PDF rendering separate from notation recognition.
18. Keep image loading/preprocessing separate from notation recognition.
19. Require valid, playable adaptation before allowing an imported exercise into Practice.
20. Preserve adaptation decisions and provenance when notes are transformed, reduced, simplified, or omitted.
21. Do not use virtual/synthetic sound as microphone evidence.
22. Do not redesign UI while implementing infrastructure unless a real caller is required.
23. Keep text MIDI deferred unless a demonstrated product requirement justifies a real parser.
24. Add tests for every new boundary and preserve all existing fixtures semantically.
25. Recheck source identity matching between recognition results and canonical score metadata.

## Recommended Order

### PHASE A: Complete Application Wiring (completed in this continuation audit)

Likely files: `HandpanViewModel.kt`, `ExerciseLibraryScreen.kt`, `ImportPatternDialog.kt`, `MainActivity.kt`, pipeline tests.

Prerequisite: use the existing `ScoreIngestionUseCase`, `ScoreSource`, and repository store. The Exercise Library now provides a MIDI/MusicXML file-picker caller and result message without changing PracticeEngine. The canonical byte-to-source boundary is covered by `ScoreIngestionPipelineTest`. PDF/Image remain unsupported without a real OMR engine.

Acceptance: binary MIDI and MusicXML can be selected through the Exercise Library caller; unsupported bytes and ingestion/adaptation failures are surfaced; successful adapted exercises appear through the existing library flow.

### PHASE B: Adaptation Review and Quality Policy (implemented in this continuation audit)

Likely files: `HandpanAdaptation.kt`, `ScoreIngestionPipeline.kt`, ViewModel state, focused tests.

Prerequisite: preserve decision statuses and source IDs. Output: explicit persisted approval/rejection for partial adaptation; no product-defined quality threshold was invented. Tests cover exact, partial pending/approved/rejected, impossible, reload state, and provenance. Do not silently drop source events.

Acceptance: user approval is required for partial adaptation; rejected/uncertain/impossible results cannot create a playable exercise. Room migration `7->8` stores the approval state.

### PHASE C: Persistence Reconstruction and History (implemented in this continuation audit)

Likely files: `ImportedScoreEntity.kt`, a canonical timeline codec near the model boundary, `ImportedScoreDao.kt`, repository, migration/tests.

Prerequisite: retain schema 8 and existing JSON formats. Output: versioned canonical timeline/provenance round-trip and typed malformed/unknown-version failure through `TimelineJsonCodec`; history UI remains absent. Tests cover normalized fields, deterministic output, Room reload, and provenance. Legacy unversioned timeline compatibility remains a documented compatibility consideration.

Acceptance: a newly persisted imported score can be reconstructed as the same canonical timeline with deterministic IDs and complete structured provenance.

### PHASE D: Real OMR Feasibility and Adapter

Likely files: `ScoreIngestionFoundation.kt`, `ScoreIngestionPipeline.kt`, dependency catalog only if technically justified, OMR adapter tests.

Prerequisite: select a real engine and document license, size, runtime, Android support and failure behavior. Output: adapter implementing `OmrEngine`, never a fake recognizer. Tests: recognized/uncertain/rejected, source locations, confidence, multi-page ordering and no silent promotion.

Acceptance: only a real engine result can produce a normalized score; uncertain items remain uncertain or block validation.

### PHASE E: Device Validation and UI Polish

Likely files: audio/device tests and existing screens only after infrastructure is stable.

Prerequisite: phases A-D and physical device access. Output: microphone/PDF rendering/device latency evidence and targeted UX improvements. Do not change the audio evidence contract or replace REAL_HANDPAN.

## Exact Source Tree Map

```text
app/src/main/java/com/example/
├── MainActivity.kt
├── audio/
│   ├── AcousticPracticeEvaluator.kt
│   ├── AudioAnalysisSession.kt
│   ├── AudioEngine.kt
│   ├── AudioResourceCoordinator.kt
│   ├── HandpanSynthesizer.kt
│   ├── MetronomeEngine.kt
│   ├── OnsetAndPitchMatcher.kt
│   ├── PatternScheduler.kt
│   ├── PerformanceRecorder.kt
│   ├── PitchDetector.kt
│   ├── PracticeEngine.kt
│   ├── PracticeHitValidation.kt
│   ├── PracticeTimeline.kt
│   └── YinPitchDetector.kt
├── data/
│   ├── builtin/BuiltinExercises.kt
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── *Entity.kt / *Dao.kt
│   │   ├── ImportedScoreEntity.kt
│   │   ├── ImportedScoreDao.kt
│   │   ├── PatternEntity.kt
│   │   ├── PatternDao.kt
│   │   └── RecordingTrackCodec.kt
│   └── repository/HandpanRepository.kt
├── model/
│   ├── BinaryMidiScoreImporter.kt
│   ├── HandpanAdaptation.kt
│   ├── HandpanPattern.kt
│   ├── InstrumentProfile.kt
│   ├── LearningEngine.kt
│   ├── MusicalTargetMatcher.kt
│   ├── NoteEvent.kt
│   ├── ScoreIngestionFoundation.kt
│   ├── ScoreIngestionPipeline.kt
│   ├── StandardMusicXmlScoreImporter.kt
│   └── SymbolicMusic.kt
└── ui/
    ├── HandpanViewModel.kt
    ├── components/*.kt
    ├── screens/*.kt
    └── theme/*.kt

app/src/test/
├── importer/score/adaptation tests at com/example/
├── practice/audio/learning regression tests at com/example/
└── data/local/PersistentDataArchitectureTest.kt
```

## Final Working Agreement

Before any edit, read the owning implementation and its nearest tests. After the first edit, run the narrowest executable check. Keep failures visible. Leave the repository without a commit unless the user explicitly requests one.