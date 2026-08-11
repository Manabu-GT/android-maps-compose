// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.maps.android.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.effects.GoogleMap as EffectGoogleMap
import com.google.maps.android.compose.effects.Marker as EffectMarker
import com.google.maps.android.compose.effects.Polyline as EffectPolyline

/**
 * Usage sample for the effect-based prototype in `com.google.maps.android.compose.effects`.
 *
 * Not registered in the manifest — this exists to demonstrate (and compile-check) the call-site
 * shape of the prototype API: same surface as the applier-based library, with `key()`-managed
 * identity, conditional content, and MarkerState-driven position all flowing through the
 * ordinary UI composition.
 */
private data class Stop(val id: String, val position: LatLng)

@Composable
internal fun EffectsPrototypeDemo() {
    val singapore = LatLng(1.3588227, 103.8742114)
    val stops = remember {
        listOf(
            Stop("a", LatLng(1.3588227, 103.8742114)),
            Stop("b", LatLng(1.3007050, 103.8558700)),
            Stop("c", LatLng(1.2842220, 103.8452330)),
        )
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(singapore, 11f)
    }

    EffectGoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(mapType = MapType.NORMAL),
        uiSettings = MapUiSettings(compassEnabled = false),
        onMapClick = { selectedId = null },
    ) {
        // Route between the stops; recomposes (and diff-applies) when selection changes color.
        EffectPolyline(
            points = stops.map { it.position },
            color = if (selectedId != null) Color.Blue else Color.Gray,
            width = 12f,
        )

        // key()-managed identity: add/remove/reorder of stops maps to marker add/remove/no-op.
        for (stop in stops) {
            key(stop.id) {
                EffectMarker(
                    state = rememberUpdatedMarkerState(position = stop.position),
                    title = "Stop ${stop.id}",
                    zIndex = if (stop.id == selectedId) 1f else 0f,
                    onClick = {
                        selectedId = stop.id
                        false
                    },
                )
            }
        }
    }
}
