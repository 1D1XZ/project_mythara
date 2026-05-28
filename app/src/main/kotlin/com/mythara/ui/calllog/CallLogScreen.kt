package com.mythara.ui.calllog

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.calllog.CallKind
import com.mythara.calllog.CallLogEntry
import com.mythara.calllog.CallLogRepository
import com.mythara.calllog.CallSource
import com.mythara.people.ContactActions
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Day-bucket header for the grouped list. */
sealed interface CallLogItem {
    data class Header(val label: String) : CallLogItem
    data class Entry(val e: CallLogEntry) : CallLogItem
}

@HiltViewModel
class CallLogViewModel @Inject constructor(
    private val repo: CallLogRepository,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val entries: StateFlow<List<CallLogEntry>> = _entries.asStateFlow()

    private val _hasPerm = MutableStateFlow(repo.hasPhoneLogPermission())
    val hasPerm: StateFlow<Boolean> = _hasPerm.asStateFlow()

    init { refresh() }

    fun refresh() {
        _hasPerm.value = repo.hasPhoneLogPermission()
        viewModelScope.launch { _entries.value = repo.loadAll() }
    }
}

/**
 * Consolidated call log (v7 P5). System phone calls + WhatsApp calls
 * captured via notifications, grouped by day. Tap a row → call back;
 * long-press → open the People screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallLogScreen(
    onOpenContacts: () -> Unit,
    vm: CallLogViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val entries by vm.entries.collectAsState()
    val hasPerm by vm.hasPerm.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refresh() }

    DisposableEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.refresh() else permLauncher.launch(Manifest.permission.READ_CALL_LOG)
        onDispose { }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact header with a "contacts →" jump chip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${entries.size} recent calls",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelMedium,
            )
            JumpChip(label = "${Glyph.Arrow} contacts", onTap = onOpenContacts)
        }

        if (!hasPerm && entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${Glyph.AccentBar} call-log access needed",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    JumpChip(label = "${Glyph.Arrow} grant") {
                        permLauncher.launch(Manifest.permission.READ_CALL_LOG)
                    }
                }
            }
            return
        }

        val items = remember(entries) { groupByDay(entries) }
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "no call history yet",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // NOTE: no per-item keys — system call log can repeat
            // (same contact called multiple times in the same instant
            // on retries). Position-based keys avoid collisions.
            items(items) { item ->
                when (item) {
                    is CallLogItem.Header -> SectionHeader(item.label)
                    is CallLogItem.Entry -> CallRow(
                        e = item.e,
                        onCall = {
                            item.e.number?.takeIf { it.isNotBlank() }?.let {
                                ContactActions.phoneCall(ctx, it)
                            }
                        },
                        onLongOpenContacts = onOpenContacts,
                    )
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun groupByDay(entries: List<CallLogEntry>): List<CallLogItem> {
    if (entries.isEmpty()) return emptyList()
    val out = ArrayList<CallLogItem>(entries.size + 8)
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterday = today - 24L * 60 * 60 * 1000
    val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    var lastLabel: String? = null
    for (e in entries) {
        val label = when {
            e.tsMs >= today -> "today"
            e.tsMs >= yesterday -> "yesterday"
            else -> dayFmt.format(Date(e.tsMs))
        }
        if (label != lastLabel) {
            out += CallLogItem.Header(label)
            lastLabel = label
        }
        out += CallLogItem.Entry(e)
    }
    return out
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(
    e: CallLogEntry,
    onCall: () -> Unit,
    onLongOpenContacts: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val (kindGlyph, kindColor) = when (e.kind) {
        CallKind.INCOMING -> "↓" to MytharaColors.Bok
        CallKind.OUTGOING -> "↑" to MytharaColors.Charple
        CallKind.MISSED -> "✕" to MytharaColors.Sriracha
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.55f))
            .combinedClickable(
                onClick = onCall,
                onLongClick = onLongOpenContacts,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direction indicator
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(kindColor.copy(alpha = 0.18f))
                .border(1.dp, kindColor.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = kindGlyph,
                color = kindColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = e.name,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.size(6.dp))
                val sourceLabel = if (e.source == CallSource.PHONE) "phone" else "wa"
                val sourceColor = if (e.source == CallSource.PHONE) MytharaColors.Malibu else MytharaColors.Bok
                Text(
                    text = sourceLabel,
                    color = sourceColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(sourceColor.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Row {
                Text(
                    text = timeFmt.format(Date(e.tsMs)),
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (e.durationMs > 0) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "${Glyph.AccentBar} ${formatDuration(e.durationMs)}",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun JumpChip(label: String, onTap: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MytharaColors.Charple.copy(alpha = 0.18f))
            .border(1.dp, MytharaColors.Charple.copy(alpha = 0.4f), shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val sec = s % 60
    return if (m > 0) "${m}m ${sec}s" else "${sec}s"
}
