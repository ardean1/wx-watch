package com.ardean.wxwatch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import kotlinx.coroutines.launch
import androidx.wear.compose.material.*

@Composable
fun OptionsScreen(
    planesOn: Boolean, setPlanes: (Boolean) -> Unit,
    radiusIdx: Int, setRadius: (Int) -> Unit,
    refreshIdx: Int, setRefresh: (Int) -> Unit,
    zip: String, onZip: () -> Unit, onRelocate: () -> Unit, onClose: () -> Unit
) {
    val state = rememberScalingLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { focus.requestFocus() }
    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().background(Color(0xF0101820))
            .onRotaryScrollEvent { scope.launch { state.scrollBy(it.verticalScrollPixels) }; true }
            .focusRequester(focus).focusable(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp)
    ) {
        item { ListHeader { Text("wx-watch options") } }
        item {
            ToggleChip(checked = planesOn, onCheckedChange = setPlanes, label = { Text("Planes") },
                toggleControl = { Switch(checked = planesOn) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = { setRadius((radiusIdx + 1) % RADII_MI.size) },
                label = { Text("Radius: ${RADII_MI[radiusIdx]} mi") }, secondaryLabel = { Text("tap to cycle · bezel zooms") },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = { setRefresh((refreshIdx + 1) % REFRESH_S.size) },
                label = { Text("Refresh: ${REFRESH_S[refreshIdx]} s") },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = onZip, label = { Text("Fallback ZIP: $zip") }, secondaryLabel = { Text("tap to change & center") },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = onRelocate, label = { Text("Re-locate now") },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item { Chip(onClick = onClose, label = { Text("Back to map") }, modifier = Modifier.fillMaxWidth()) }
    }
}
