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

import com.google.android.gms.maps.GoogleMap as GmsGoogleMap
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.Polyline
import com.google.maps.android.compose.DragState
import com.google.maps.android.compose.MarkerState

/**
 * PROTOTYPE — receiver scope for the content of the effect-based [GoogleMap] composable.
 *
 * Replaces the applier-type check (`currentComposer.applier as MapApplier`) of the node
 * architecture with a compile-time constraint: map element composables are extensions on this
 * scope and simply cannot be called outside map content. It also carries the map handle, so
 * element composables need no `CompositionLocal` lookup and no cast.
 */
public class GoogleMapScope internal constructor(
    /** The underlying Maps SDK map. Content is only composed once this is available. */
    public val map: GmsGoogleMap,
    internal val clickDispatcher: ClickDispatcher,
)

/**
 * Demultiplexes the Maps SDK's map-wide singleton listeners (one `OnMarkerClickListener` etc.
 * for the entire map) to the callbacks of the individual element composables.
 *
 * This is the effect-architecture replacement for `MapApplier.attachClickListeners` +
 * `findInputCallback`: instead of a linear predicate scan over the node list on every event,
 * elements register themselves in hash maps on attach, making dispatch O(1).
 */
internal class ClickDispatcher {

    internal class MarkerEntry(
        val state: MarkerState,
        /** Stable wrapper reading the current user callback via rememberUpdatedState. */
        val onClick: (Marker) -> Boolean,
    )

    private val markers = HashMap<Marker, MarkerEntry>()
    private val polylines = HashMap<Polyline, (Polyline) -> Unit>()

    fun registerMarker(marker: Marker, entry: MarkerEntry) {
        markers[marker] = entry
    }

    fun unregisterMarker(marker: Marker) {
        markers.remove(marker)
    }

    fun registerPolyline(polyline: Polyline, onClick: (Polyline) -> Unit) {
        polylines[polyline] = onClick
    }

    fun unregisterPolyline(polyline: Polyline) {
        polylines.remove(polyline)
    }

    /**
     * Wires the map's singleton listeners to this dispatcher. Called once per map instance —
     * callback identity changes flow through the registered entries and never require
     * re-registration against the Maps SDK.
     */
    fun attachTo(map: GmsGoogleMap) {
        map.setOnMarkerClickListener { marker ->
            markers[marker]?.onClick?.invoke(marker) ?: false
        }
        map.setOnPolylineClickListener { polyline ->
            polylines[polyline]?.invoke(polyline)
        }
        map.setOnMarkerDragListener(object : GmsGoogleMap.OnMarkerDragListener {
            // Contract carried over verbatim from MapApplier: MarkerState.position is never
            // written by the library unless MarkerState.isDragging == true, because during a
            // drag the Maps SDK — not the app — is the source of truth for position.

            override fun onMarkerDragStart(marker: Marker) {
                val state = markers[marker]?.state ?: return
                state.isDragging = true
                // update position after enabling isDragging
                state.position = marker.position
                @Suppress("DEPRECATION")
                state.dragState = DragState.START
            }

            override fun onMarkerDrag(marker: Marker) {
                val state = markers[marker]?.state ?: return
                state.isDragging = true // just in case, should be set already
                state.position = marker.position
                @Suppress("DEPRECATION")
                state.dragState = DragState.DRAG
            }

            override fun onMarkerDragEnd(marker: Marker) {
                val state = markers[marker]?.state ?: return
                state.isDragging = true // just in case, should be set already
                state.position = marker.position
                // disable isDragging after updating position
                state.isDragging = false
                @Suppress("DEPRECATION")
                state.dragState = DragState.END
            }
        })
    }

    fun detachFrom(map: GmsGoogleMap) {
        map.setOnMarkerClickListener(null)
        map.setOnPolylineClickListener(null)
        map.setOnMarkerDragListener(null)
    }
}
