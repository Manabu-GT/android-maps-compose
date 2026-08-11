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

package com.google.maps.android.compose.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.google.maps.android.ktx.addMarker

/**
 * PROTOTYPE — effect-based marker composable; the counterpart of the node-based
 * [com.google.maps.android.compose.Marker].
 *
 * Emits no layout nodes: the Maps SDK `Marker` is created and removed by [MapElement]'s
 * [androidx.compose.runtime.DisposableEffect] and its properties are kept in sync by diffed
 * apply-phase updates. Positional memoization, `key()`, and conditional control flow manage
 * add/remove exactly as they do for applier nodes.
 *
 * [state] participates in the element's identity: passing a different [MarkerState] instance
 * recreates the marker, so the "one Marker per MarkerState" invariant holds by construction
 * (the node architecture silently keeps the original state object instead).
 *
 * Prototype scope: no info window support and no `contentDescription`
 * (they belong to the compose-annotation overlay tier in the redesign).
 *
 * @param state the [MarkerState] object controlling/observing this marker
 * @param alpha the alpha (opacity) of the marker
 * @param anchor the anchor for the marker image
 * @param draggable sets the draggability for the marker
 * @param flat sets if the marker should be flat against the map
 * @param icon sets the icon for the marker
 * @param infoWindowAnchor the anchor point of the info window on the marker image
 * @param rotation the rotation of the marker in degrees clockwise about the marker's anchor point
 * @param snippet the snippet for the marker
 * @param tag optional tag to associate with the marker
 * @param title the title for the marker
 * @param visible the visibility of the marker
 * @param zIndex the z-index of the marker
 * @param onClick a lambda invoked when the marker is clicked; return true to consume the event
 */
@Composable
public fun GoogleMapScope.Marker(
    state: MarkerState = rememberUpdatedMarkerState(),
    alpha: Float = 1.0f,
    anchor: Offset = Offset(0.5f, 1.0f),
    draggable: Boolean = false,
    flat: Boolean = false,
    icon: BitmapDescriptor? = null,
    infoWindowAnchor: Offset = Offset(0.5f, 0.0f),
    rotation: Float = 0.0f,
    snippet: String? = null,
    tag: Any? = null,
    title: String? = null,
    visible: Boolean = true,
    zIndex: Float = 0.0f,
    onClick: (Marker) -> Boolean = { false },
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val dispatcher = clickDispatcher
    MapElement(
        state, // identity key: a different MarkerState instance recreates the marker
        create = {
            val marker = addMarker {
                alpha(alpha)
                anchor(anchor.x, anchor.y)
                draggable(draggable)
                flat(flat)
                icon(icon)
                infoWindowAnchor(infoWindowAnchor.x, infoWindowAnchor.y)
                position(state.position)
                rotation(rotation)
                snippet(snippet)
                title(title)
                visible(visible)
                zIndex(zIndex)
            } ?: error("Error adding marker")
            marker.tag = tag
            marker
        },
        onAttached = { marker ->
            state.marker = marker
            dispatcher.registerMarker(
                marker,
                ClickDispatcher.MarkerEntry(state) { currentOnClick(it) },
            )
        },
        onRemoved = { marker ->
            dispatcher.unregisterMarker(marker)
            state.marker = null
            marker.remove()
        },
    ) {
        set(state.position) { position = it }
        set(alpha) { this.alpha = it }
        set(anchor) { setAnchor(it.x, it.y) }
        set(draggable) { isDraggable = it }
        set(flat) { isFlat = it }
        set(icon) { setIcon(it) }
        set(infoWindowAnchor) { setInfoWindowAnchor(it.x, it.y) }
        set(rotation) { this.rotation = it }
        set(snippet) { this.snippet = it }
        set(tag) { this.tag = it }
        set(title) { this.title = it }
        set(visible) { isVisible = it }
        set(zIndex) { this.zIndex = it }
    }
}
