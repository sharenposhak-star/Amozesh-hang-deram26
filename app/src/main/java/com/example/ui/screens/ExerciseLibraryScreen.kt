package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.model.DifficultyLevel
import com.example.model.HandpanPattern
import com.example.model.PatternCategory
import com.example.model.AdaptationRequest
import com.example.model.ScoreIngestionResult
import com.example.model.scoreSourceFromImportableBytes
import com.example.ui.AppScreen
import com.example.ui.HandpanViewModel
import com.example.ui.components.ExportPatternDialog
import com.example.ui.components.ImportPatternDialog
import com.example.ui.theme.CharcoalBlack
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalDark
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceVariant
import com.example.ui.theme.HandpanBronze
import com.example.ui.theme.HandpanGold
import com.example.ui.theme.HandpanGoldLight
import com.example.ui.theme.HandpanTerracotta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExerciseLibraryScreen(
    viewModel: HandpanViewModel,
    onBack: () -> Unit,
    onNavigate: (AppScreen) -> Unit,
    onStartPractice: (HandpanPattern) -> Unit,
    modifier: Modifier = Modifier
) {
    val allPatterns by viewModel.allPatterns.collectAsStateWithLifecycle()
    val appState by viewModel.appUiState.collectAsStateWithLifecycle()
    val practiceStats by viewModel.practiceStats.collectAsStateWithLifecycle()
    val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var scoreImportMessage by remember { mutableStateOf<String?>(null) }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::transcribeAudio) }
    val scorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val sourceId = uri.toString()
            coroutineScope.launch {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        scoreSourceFromImportableBytes(sourceId, input.readBytes())
                    }
                }
                if (source == null) {
                    scoreImportMessage = "فقط فایل MIDI یا MusicXML قابل ورود است."
                } else {
                    viewModel.importScore(
                        source = source,
                        request = AdaptationRequest(viewModel.appUiState.value.currentInstrumentProfile),
                        exerciseId = "imported-${sourceId.hashCode()}"
                    ) { result ->
                        scoreImportMessage =
                            when (result) {
                                is ScoreIngestionResult.Adapted -> "قطعه با موفقیت به تمرین افزوده شد."
                                is ScoreIngestionResult.Partial -> "تمرین افزوده شد؛ بخشی از قطعه ساده‌سازی شده است."
                                else -> "ورود قطعه انجام نشد: ${result.status.name}"
                            }
                    }
                }
            }
        }
    }

    var selectedCategoryFilter by remember { mutableStateOf<PatternCategory?>(appState.selectedCategory) }
    var searchQuery by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var patternToShare by remember { mutableStateOf<HandpanPattern?>(null) }

    if (showImportDialog) {
        ImportPatternDialog(
            onDismiss = { showImportDialog = false },
            onPatternImported = { imported ->
                viewModel.saveCustomPattern(imported)
            }
        )
    }

    if (patternToShare != null) {
        ExportPatternDialog(
            pattern = patternToShare!!,
            onDismiss = { patternToShare = null }
        )
    }

    val filteredPatterns = remember(allPatterns, selectedCategoryFilter, searchQuery) {
        var list = if (selectedCategoryFilter == null) {
            allPatterns
        } else {
            allPatterns.filter { it.category == selectedCategoryFilter }
        }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter {
                it.title.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.notesSummary.lowercase().contains(q)
            }
        }
        list
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalBlack)
            .testTag("exercise_library_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("library_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = Color.White)
                }

                Text(
                    text = "کتابخانه تمرین‌ها و قطعات",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { audioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.testTag("library_transcribe_audio_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "تحلیل فایل صوتی", tint = HandpanGoldLight)
                    }
                    IconButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.testTag("library_import_pattern_button")
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "دریافت الگو", tint = HandpanGoldLight)
                    }
                    IconButton(
                        onClick = { scorePicker.launch(arrayOf("audio/midi", "application/xml", "text/xml", "application/vnd.recordare.musicxml+xml")) },
                        modifier = Modifier.testTag("library_import_score_button")
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = "ورود قطعه", tint = HandpanGoldLight)
                    }

                    IconButton(
                        onClick = { onNavigate(AppScreen.PATTERN_EDITOR) },
                        modifier = Modifier.testTag("library_add_pattern_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "ساخت الگو", tint = HandpanGold)
                    }
                }
            }

            scoreImportMessage?.let { message ->
                Text(message, color = HandpanGoldLight, modifier = Modifier.padding(bottom = 8.dp))
            }

            if (transcriptionState.isAnalyzing || transcriptionState.result != null || transcriptionState.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("transcription_preview"),
                    colors = CardDefaults.cardColors(containerColor = CharcoalSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        when {
                            transcriptionState.isAnalyzing -> Text("در حال تحلیل آفلاین فایل صوتی...", color = HandpanGoldLight)
                            transcriptionState.errorMessage != null -> {
                                Text(transcriptionState.errorMessage!!, color = Color(0xFFFF8A80))
                                Button(onClick = { viewModel.clearTranscription() }) { Text("بستن") }
                            }
                            else -> {
                                val result = transcriptionState.result!!
                                Text("پیش‌نمایش transcription", color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    "BPM: ${result.tempo.bpm ?: "نامشخص"}  •  ضربه‌ها: ${result.onsets.size}  •  مدت: ${result.quality.durationMs}ms",
                                    color = HandpanGoldLight,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "اطمینان: ${(result.confidence.value * 100).toInt()}%${if (result.warnings.isNotEmpty()) "  •  هشدار دارد" else ""}",
                                    color = if (result.warnings.isEmpty()) Color.LightGray else Color(0xFFFFC107),
                                    fontSize = 12.sp
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.acceptTranscription() }) { Text("ورود به تمرین") }
                                    Button(onClick = { viewModel.clearTranscription() }) { Text("بستن") }
                                }
                            }
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("library_search_input"),
                placeholder = { Text("جستجو بر اساس نام، نوت یا دسته...", fontSize = 13.sp, color = Color.Gray) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = HandpanGold, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "پاک کردن", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HandpanGold,
                    unfocusedBorderColor = CharcoalBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = CharcoalDark,
                    unfocusedContainerColor = CharcoalDark
                )
            )

            // Category Filter Pills
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                item {
                    CategoryFilterChip(
                        title = "همه تمرین‌ها",
                        isSelected = selectedCategoryFilter == null,
                        onClick = { selectedCategoryFilter = null }
                    )
                }
                items(PatternCategory.values()) { category ->
                    CategoryFilterChip(
                        title = category.persianTitle,
                        isSelected = selectedCategoryFilter == category,
                        onClick = { selectedCategoryFilter = category }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Patterns List
            if (filteredPatterns.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "هیچ الگویی در این دسته یافت نشد.\nمی‌توانید با دکمه + الگوی شخصی بسازید.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredPatterns, key = { it.id }) { pattern ->
                        val stats = practiceStats[pattern.id]
                        PatternItemCard(
                            pattern = pattern,
                            practiceCount = stats?.practiceCount ?: 0,
                            onStart = { onStartPractice(pattern) },
                            onShare = { patternToShare = pattern },
                            onDelete = if (pattern.isCustom) {
                                { viewModel.deleteCustomPattern(pattern.id) }
                            } else null
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }

        // Floating Action Button to create custom pattern
        FloatingActionButton(
            onClick = { onNavigate(AppScreen.PATTERN_EDITOR) },
            containerColor = HandpanGold,
            contentColor = CharcoalBlack,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("fab_create_pattern")
        ) {
            Icon(Icons.Default.Add, contentDescription = "الگوی جدید")
        }
    }
}

@Composable
private fun CategoryFilterChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) HandpanGold else CharcoalSurfaceVariant)
            .border(1.dp, if (isSelected) Color.White else CharcoalBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) CharcoalBlack else Color.White
        )
    }
}

@Composable
private fun PatternItemCard(
    pattern: HandpanPattern,
    practiceCount: Int,
    onStart: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CharcoalBorder, RoundedCornerShape(16.dp))
            .testTag("pattern_card_${pattern.id}"),
        colors = CardDefaults.cardColors(containerColor = CharcoalSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pattern.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                // Difficulty badge
                val badgeBg = when (pattern.difficulty) {
                    DifficultyLevel.BEGINNER -> Color(0xFF2E4C38)
                    DifficultyLevel.INTERMEDIATE -> Color(0xFF4C3E22)
                    DifficultyLevel.ADVANCED -> Color(0xFF4C2722)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = pattern.difficulty.persianLabel,
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = pattern.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD6C8BB),
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Note Sequence Badge (e.g. 1 - 3 - 5 - 3)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CharcoalBlack.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ترتیب نت‌ها: ${pattern.notesSummary}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HandpanGoldLight
                    )

                    Text(
                        text = "${pattern.bpm} BPM • ${pattern.timeSignature.displayName}",
                        fontSize = 11.sp,
                        color = HandpanBronze
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (practiceCount > 0) {
                    Text(
                        text = "تعداد تمرین: $practiceCount بار",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                } else {
                    Text(
                        text = "تمرین نشده",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "اشتراک‌گذاری", tint = Color.LightGray)
                    }

                    if (onDelete != null) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف", tint = Color.LightGray)
                        }
                    }

                    Button(
                        onClick = onStart,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HandpanGold),
                        modifier = Modifier.testTag("start_practice_${pattern.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = CharcoalBlack, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تمرین", color = CharcoalBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
