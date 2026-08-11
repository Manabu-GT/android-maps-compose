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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.ButtCap
import com.google.android.gms.maps.model.Cap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.StyleSpan
import com.google.maps.android.ktx.addPolyline

/**
 * PROTOTYPE — effect-based polyline composable; the counterpart of the node-based
 * [com.google.maps.android.compose.Polyline].
 *
 * Compare with `PolylineNode` + `ComposeNode<PolylineNode, MapApplier>` in
 * `com.google.maps.android.compose.Polyline`: the `create` block below matches the node
 * `factory` and the `set(...)` blocks match the node `update` blocks line for line — the
 * declarative surface is identical, without a second composition or a custom applier.
 *
 * @param points the points comprising the polyline
 * @param spans style spans for the polyline
 * @param clickable boolean indicating if the polyline is clickable or not
 * @param color the color of the polyline
 * @param endCap a cap at the end vertex of the polyline
 * @param geodesic specifies whether to draw the polyline as a geodesic
 * @param jointType the joint type for all vertices of the polyline except the start and end
 * vertices
 * @param pattern the pattern for the polyline
 * @param startCap the cap at the start vertex of the polyline
 * @param tag optional tag to associate with the polyline
 * @param visible the visibility of the polyline
 * @param width the width of the polyline in screen pixels
 * @param zIndex the z-index of the polyline
 * @param onClick a lambda invoked when the polyline is clicked
 */
@Composable
public fun GoogleMapScope.Polyline(
    points: List<LatLng>,
    spans: List<StyleSpan> = emptyList(),
    clickable: Boolean = false,
    color: Color = Color.Black,
    endCap: Cap = ButtCap(),
    geodesic: Boolean = false,
    jointType: Int = JointType.DEFAULT,
    pattern: List<PatternItem>? = null,
    startCap: Cap = ButtCap(),
    tag: Any? = null,
    visible: Boolean = true,
    width: Float = 10f,
    zIndex: Float = 0f,
    onClick: (Polyline) -> Unit = {},
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val dispatcher = clickDispatcher
    MapElement(
        create = {
            val polyline = addPolyline {
                addAll(points)
                addAllSpans(spans)
                clickable(clickable)
                color(color.toArgb())
                endCap(endCap)
                geodesic(geodesic)
                jointType(jointType)
                pattern(pattern)
                startCap(startCap)
                visible(visible)
                width(width)
                zIndex(zIndex)
            }
            polyline.tag = tag
            polyline
        },
        onAttached = { polyline ->
            dispatcher.registerPolyline(polyline) { currentOnClick(it) }
        },
        onRemoved = { polyline ->
            dispatcher.unregisterPolyline(polyline)
            polyline.remove()
        },
    ) {
        set(points) { this.points = it }
        set(spans) { this.spans = it }
        set(clickable) { isClickable = it }
        set(color) { this.color = it.toArgb() }
        set(endCap) { this.endCap = it }
        set(geodesic) { isGeodesic = it }
        set(jointType) { this.jointType = it }
        set(pattern) { this.pattern = it }
        set(startCap) { this.startCap = it }
        set(tag) { this.tag = it }
        set(visible) { isVisible = it }
        set(width) { this.width = it }
        set(zIndex) { this.zIndex = it }
    }
}
