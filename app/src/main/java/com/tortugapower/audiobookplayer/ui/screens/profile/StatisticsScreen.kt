package com.tortugapower.audiobookplayer.ui.screens.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.viewmodel.ProfileViewModel
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit
) {
    val todayHourlyStats by viewModel.todayHourlyStats.collectAsState()
    val weekDailyStats by viewModel.weekDailyStats.collectAsState()
    val todayChangePercent by viewModel.todayChangePercent.collectAsState()
    val weekChangePercent by viewModel.weekChangePercent.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Today, 1 = Week
    var selectedBarIndex by remember { mutableStateOf<Int?>(null) }

    // Reset selected bar when tab changes
    LaunchedEffect(selectedTab) {
        selectedBarIndex = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_stats)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Pill style segment controller at the top
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                PillTabSelector(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Percentage change (-13% or +5%)
            val changePercent = if (selectedTab == 0) todayChangePercent else weekChangePercent
            if (changePercent != null) {
                Text(
                    text = if (changePercent >= 0) "+$changePercent%" else "$changePercent%",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (changePercent >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            } else {
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Define stats calculations in the Column scope so they are visible everywhere
            val dataList: List<Long> = if (selectedTab == 0) todayHourlyStats else weekDailyStats.map { it.second }
            val totalDurationMs = dataList.sum()
            val totalMinutes = totalDurationMs / 60000
            val displayTotalTime = if (totalMinutes < 60) "${totalMinutes}m" else "${totalMinutes / 60}h ${totalMinutes % 60}m"

            // Time listening duration and Date Range Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val selectedValueMs = if (selectedBarIndex != null) dataList.getOrNull(selectedBarIndex!!) else null
                val displayTime = if (selectedValueMs != null) {
                    val min = selectedValueMs / 60000
                    if (min < 60) "${min}m" else "${min / 60}h ${min % 60}m"
                } else {
                    displayTotalTime
                }

                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Date Range Label on the right
                val dateLabel = if (selectedTab == 0) {
                    if (selectedBarIndex == null) {
                        SimpleDateFormat("d MMM", Locale.getDefault()).format(Calendar.getInstance().time) + " - Today"
                    } else {
                        val hour = selectedBarIndex!!
                        val nextHour = (hour + 1) % 24
                        "$hour – $nextHour"
                    }
                } else {
                    if (selectedBarIndex == null) {
                        getWeekRangeLabel()
                    } else {
                        weekDailyStats.getOrNull(selectedBarIndex!!)?.first ?: ""
                    }
                }

                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(24.dp))

            // The Graph component (with left labels and gridlines)
            val maxVal = dataList.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Y-axis labels on the left (M_hours down to 0)
                Column(
                    modifier = Modifier
                        .width(40.dp)
                        .fillMaxHeight()
                        .padding(bottom = 20.dp, end = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    (5 downTo 0).forEach { i ->
                        val valAtLabel = maxVal * i / 5
                        Text(
                            text = formatYLabel(valAtLabel, maxVal),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // Graph area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Gridlines (6 lines)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 20.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        repeat(6) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }

                    // Bars
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        dataList.forEachIndexed { index, value ->
                            val barHeightFactor = value.toFloat() / maxVal.toFloat()
                            val isSelected = selectedBarIndex == index
                            val animatedHeightFactor by animateFloatAsState(
                                targetValue = barHeightFactor,
                                label = "barHeight"
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selectedBarIndex = if (selectedBarIndex == index) null else index }
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                // Tooltip text above selected bar
                                Box(
                                    modifier = Modifier.height(18.dp),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (isSelected && value > 0L) {
                                        val min = value / 60000
                                        val valLabel = if (min < 60) "${min}m" else "${min / 60}h ${min % 60}m"
                                        Text(
                                            text = valLabel,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // Rounded capsule bar
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = if (dataList.size > 7) 2.dp else 6.dp),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(animatedHeightFactor.coerceAtLeast(0.01f))
                                            .clip(RoundedCornerShape(4.dp)) // Slightly rounded rectangular shape
                                            .background(
                                                if (isSelected) {
                                                    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primaryContainer
                                                    else Color(0xFF1B3D5F)
                                                } else if (value > 0L) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                                }
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // X-axis Labels Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, top = 4.dp), // offset by Y-label width
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val labelsList = if (selectedTab == 0) {
                    List(24) { i ->
                        when (i) {
                            0 -> "0"
                            6 -> "6"
                            12 -> "12"
                            18 -> "18"
                            else -> ""
                        }
                    }
                } else {
                    weekDailyStats.map { it.first.take(1).uppercase(Locale.getDefault()) } // Take first letter (M, T, W...)
                }

                labelsList.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Daily Average / Details row at the bottom
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedTab == 0) stringResource(R.string.profile_today) else stringResource(R.string.profile_daily_average),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )

                val avgTimeText = if (selectedTab == 0) {
                    displayTotalTime
                } else {
                    val dailyAvgMs = totalDurationMs / 7
                    val avgMin = dailyAvgMs / 60000
                    if (avgMin < 60) "${avgMin}m" else "${avgMin / 60}h ${avgMin % 60}m"
                }

                Text(
                    text = avgTimeText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PillTabSelector(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .height(36.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf(
                stringResource(R.string.profile_today),
                stringResource(R.string.profile_week)
            )
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surface
                            else Color.Transparent
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabSelected(index) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun formatYLabel(valueMs: Long, maxValMs: Long): String {
    val mins = valueMs / 60000.0
    return if (maxValMs < 3600000L) { // Less than 1 hour max
        String.format(Locale.US, "%.0f", mins) // Show minutes (e.g. 45, 30)
    } else {
        String.format(Locale.US, "%.1f", mins / 60.0) // Show hours (e.g. 1.5, 2.0)
    }
}

fun getWeekRangeLabel(): String {
    val cal = Calendar.getInstance()
    val sdf = SimpleDateFormat("d MMM", Locale.getDefault())
    val endLabel = "Today"
    cal.add(Calendar.DAY_OF_YEAR, -6)
    val startLabel = sdf.format(cal.time)
    return "$startLabel - $endLabel"
}
