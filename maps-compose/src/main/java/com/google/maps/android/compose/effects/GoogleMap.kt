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

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.GoogleMap as GmsGoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.DefaultMapContentPadding
import com.google.maps.android.compose.DefaultMapProperties
import com.google.maps.android.compose.DefaultMapUiSettings
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.ktx.awaitMap

/**
 * PROTOTYPE — effect-based `GoogleMap` container.
 *
 * Architectural experiment: instead of launching a second [androidx.compose.runtime.Composition]
 * driven by a custom `MapApplier`, the map content is an ordinary part of the caller's UI
 * composition. Map element composables ([Marker], [Polyline]) emit no layout nodes; they manage
 * their Maps SDK objects through [DisposableEffect] (lifecycle) and diffed [SideEffect]s
 * (property updates) via [MapElement].
 *
 * Consequences compared to the applier architecture:
 * - one composition: `CompositionLocal`s provided around map content reach the content lexically,
 *   and map content shows up in composition tooling (Layout Inspector, recomposition counts);
 * - a UI-emitting annotation tier can later compose real layout children next to the [AndroidView]
 *   in the same `Box` — impossible under a `MapApplier`-typed composition;
 * - scoping is enforced at compile time by [GoogleMapScope] instead of an applier cast at runtime;
 * - third parties can support additional map object types via the public [MapElement].
 *
 * Prototype limitations (deliberate scope cuts, not architectural blockers): no
 * `mergeDescendants`/focusability handling, no content descriptions, no info window support, no
 * `MapColorScheme`, and the [MapView] is created in `remember` rather than the `AndroidView`
 * factory, which opts out of view reuse in lazy containers.
 */
@Composable
public fun GoogleMap(
    modifier: Modifier = Modifier,
    cameraPositionState: CameraPositionState = rememberCameraPositionState(),
    properties: MapProperties = DefaultMapProperties,
    uiSettings: MapUiSettings = DefaultMapUiSettings,
    contentPadding: PaddingValues = DefaultMapContentPadding,
    locationSource: LocationSource? = null,
    onMapClick: ((LatLng) -> Unit)? = null,
    onMapLongClick: ((LatLng) -> Unit)? = null,
    onMapLoaded: (() -> Unit)? = null,
    googleMapOptionsFactory: () -> GoogleMapOptions = { GoogleMapOptions() },
    content: @Composable GoogleMapScope.() -> Unit = {},
) {
    // When in preview, early return a Box with the received modifier preserving layout.
    if (LocalInspectionMode.current) {
        Box(modifier = modifier)
        return
    }

    val context = LocalContext.current
    val options = remember { googleMapOptionsFactory() }
    val mapView = remember { MapView(context, options) }
    var map by remember { mutableStateOf<GmsGoogleMap?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { mapView },
        )
    }

    MapViewLifecycleEffect(mapView)

    LaunchedEffect(mapView) {
        map = mapView.awaitMap()
    }

    val currentMap = map ?: return
    val scope = remember(currentMap) { GoogleMapScope(currentMap, ClickDispatcher()) }

    // Map-wide singleton listeners: registered once per map instance; the user's lambdas flow
    // through rememberUpdatedState, so their identity churn never touches the Maps SDK. This
    // replaces the MapClickListeners holder + MapClickListenerUpdater machinery.
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnMapLongClick by rememberUpdatedState(onMapLongClick)
    val currentOnMapLoaded by rememberUpdatedState(onMapLoaded)
    DisposableEffect(currentMap) {
        scope.clickDispatcher.attachTo(currentMap)
        currentMap.setOnMapClickListener { currentOnMapClick?.invoke(it) }
        currentMap.setOnMapLongClickListener { currentOnMapLongClick?.invoke(it) }
        currentMap.setOnMapLoadedCallback { currentOnMapLoaded?.invoke() }
        onDispose {
            scope.clickDispatcher.detachFrom(currentMap)
            currentMap.setOnMapClickListener(null)
            currentMap.setOnMapLongClickListener(null)
            currentMap.setOnMapLoadedCallback(null)
        }
    }

    // Camera binding. Key-change semantics reproduce MapPropertiesNode's swap logic: when the
    // caller passes a different CameraPositionState, the old effect's onDispose (detach) runs
    // before the new effect's body (attach).
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    DisposableEffect(currentMap, cameraPositionState) {
        // Padding must be applied before the camera position for correct centering (same
        // ordering as MapPropertiesNode.init). MapPropertiesUpdater below re-applies it in the
        // same apply pass (idempotent), because SideEffects run after remember observers.
        currentMap.applyContentPadding(contentPadding, density, layoutDirection)
        cameraPositionState.isLiteMode = options.liteMode == true
        cameraPositionState.setMap(currentMap)
        currentMap.setOnCameraIdleListener {
            cameraPositionState.isMoving = false
            // setOnCameraMoveListener is only invoked when the camera position is changed via
            // .animate(). To handle updating state when .move() is used, it's necessary to set
            // the camera's position here as well.
            cameraPositionState.rawPosition = currentMap.cameraPosition
        }
        currentMap.setOnCameraMoveCanceledListener {
            cameraPositionState.isMoving = false
        }
        currentMap.setOnCameraMoveStartedListener {
            cameraPositionState.cameraMoveStartedReason = CameraMoveStartedReason.fromInt(it)
            cameraPositionState.isMoving = true
        }
        currentMap.setOnCameraMoveListener {
            cameraPositionState.rawPosition = currentMap.cameraPosition
        }
        onDispose {
            currentMap.setOnCameraIdleListener(null)
            currentMap.setOnCameraMoveCanceledListener(null)
            currentMap.setOnCameraMoveStartedListener(null)
            currentMap.setOnCameraMoveListener(null)
            cameraPositionState.setMap(null)
        }
    }

    MapPropertiesUpdater(
        map = currentMap,
        properties = properties,
        uiSettings = uiSettings,
        contentPadding = contentPadding,
        locationSource = locationSource,
    )

    scope.content()
}

/**
 * Keeps the map's runtime-configurable properties in sync — the effect-based counterpart of
 * `MapUpdater`/`MapPropertiesNode`, using the same recording-pass + diffed-apply mechanism as
 * element updates. The first apply pass applies everything, because the map was created from
 * [GoogleMapOptions] defaults rather than from these values.
 */
@SuppressLint("MissingPermission")
@Composable
private fun MapPropertiesUpdater(
    map: GmsGoogleMap,
    properties: MapProperties,
    uiSettings: MapUiSettings,
    contentPadding: PaddingValues,
    locationSource: LocationSource?,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = remember(map) { UpdateScope<GmsGoogleMap>().also { it.attachUnapplied(map) } }
    scope.record {
        set(Triple(contentPadding, density, layoutDirection)) { (padding, d, ld) ->
            applyContentPadding(padding, d, ld)
        }
        set(locationSource) { setLocationSource(it) }

        set(properties.isBuildingEnabled) { isBuildingsEnabled = it }
        set(properties.isIndoorEnabled) { isIndoorEnabled = it }
        set(properties.isMyLocationEnabled) { isMyLocationEnabled = it }
        set(properties.isTrafficEnabled) { isTrafficEnabled = it }
        set(properties.latLngBoundsForCameraTarget) { setLatLngBoundsForCameraTarget(it) }
        set(properties.mapStyleOptions) { setMapStyle(it) }
        set(properties.mapType) { mapType = it.value }
        set(properties.maxZoomPreference) { setMaxZoomPreference(it) }
        set(properties.minZoomPreference) { setMinZoomPreference(it) }

        // try/catch pattern carried over from MapUpdater for HMS/microG compatibility (#804).
        set(uiSettings.compassEnabled) {
            try { this.uiSettings.isCompassEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.indoorLevelPickerEnabled) {
            try { this.uiSettings.isIndoorLevelPickerEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.mapToolbarEnabled) {
            try { this.uiSettings.isMapToolbarEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.myLocationButtonEnabled) {
            try { this.uiSettings.isMyLocationButtonEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.rotationGesturesEnabled) {
            try { this.uiSettings.isRotateGesturesEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.scrollGesturesEnabled) {
            try { this.uiSettings.isScrollGesturesEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.scrollGesturesEnabledDuringRotateOrZoom) {
            try { this.uiSettings.isScrollGesturesEnabledDuringRotateOrZoom = it } catch (e: Exception) { }
        }
        set(uiSettings.tiltGesturesEnabled) {
            try { this.uiSettings.isTiltGesturesEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.zoomControlsEnabled) {
            try { this.uiSettings.isZoomControlsEnabled = it } catch (e: Exception) { }
        }
        set(uiSettings.zoomGesturesEnabled) {
            try { this.uiSettings.isZoomGesturesEnabled = it } catch (e: Exception) { }
        }
    }
    SideEffect { scope.applyPending() }
}

private fun GmsGoogleMap.applyContentPadding(
    contentPadding: PaddingValues,
    density: Density,
    layoutDirection: LayoutDirection,
) {
    with(density) {
        setPadding(
            contentPadding.calculateLeftPadding(layoutDirection).roundToPx(),
            contentPadding.calculateTopPadding().roundToPx(),
            contentPadding.calculateRightPadding(layoutDirection).roundToPx(),
            contentPadding.calculateBottomPadding().roundToPx(),
        )
    }
}

/**
 * Drives the [MapView]'s lifecycle from the composition-local lifecycle, and destroys it when
 * this composable leaves the composition (the MapView outliving the Activity's ON_DESTROY is
 * prevented by walking down to CREATED on that event; final destruction is owned by disposal).
 */
@Composable
private fun MapViewLifecycleEffect(mapView: MapView) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView, lifecycle) {
        val observer = MapViewLifecycleObserver(mapView)
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java", ReplaceWith("onTrimMemory(level)"))
            override fun onLowMemory() {
                mapView.onLowMemory()
            }

            override fun onTrimMemory(level: Int) {
                mapView.onLowMemory()
            }
        }
        context.registerComponentCallbacks(callbacks)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
            observer.moveToDestroyed()
        }
    }
}

private class MapViewLifecycleObserver(private val mapView: MapView) : LifecycleEventObserver {
    private var currentState: Lifecycle.State = Lifecycle.State.INITIALIZED

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_DESTROY) {
            // MapView.onDestroy is only invoked when this composable is disposed, not when the
            // (possibly retained) lifecycle owner is destroyed.
            if (currentState > Lifecycle.State.CREATED) moveTo(Lifecycle.State.CREATED)
        } else {
            moveTo(event.targetState)
        }
    }

    fun moveToDestroyed() {
        if (currentState > Lifecycle.State.INITIALIZED) moveTo(Lifecycle.State.DESTROYED)
    }

    private fun moveTo(target: Lifecycle.State) {
        while (currentState != target) {
            val event = if (currentState < target) {
                Lifecycle.Event.upFrom(currentState)
            } else {
                Lifecycle.Event.downFrom(currentState)
            } ?: error("no lifecycle event between $currentState and $target")
            invokeEvent(event)
        }
    }

    private fun invokeEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
            Lifecycle.Event.ON_START -> mapView.onStart()
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            Lifecycle.Event.ON_STOP -> mapView.onStop()
            Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
            else -> error("Unsupported lifecycle event: $event")
        }
        currentState = event.targetState
    }
}
