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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.google.android.gms.maps.GoogleMap as GmsGoogleMap

/**
 * PROTOTYPE — effect-based alternative to the [com.google.maps.android.compose.MapApplier]
 * architecture.
 *
 * Applies property values to a managed Maps SDK object with the two guarantees that
 * `ComposeNode`'s `Updater.set` provides in the applier architecture:
 *
 * 1. **Apply-phase timing** — [set] reads [set]'s `value` during composition (subscribing the
 *    recomposition scope to any snapshot state it came from) but performs the write from
 *    [SideEffect], which only runs after the composition is successfully applied. No write ever
 *    reaches the Maps SDK from a composition that may still be abandoned.
 * 2. **Per-value diffing** — each [set] call site keeps its last applied value in a [remember]ed
 *    slot and only invokes its block when the value changed.
 *
 * There is deliberately no bookkeeping here beyond one `remember` per property: slot identity,
 * conditional `set` calls, and cleanup on element recreation are all handled by the
 * composition's slot table, which is the same machinery that backs `Updater.set` diffing in the
 * applier architecture.
 */
public class ElementUpdater<T : Any> internal constructor(
    /**
     * Whether the element already carries the values staged by the composition that creates it
     * (true for elements built from a fully populated options object; false for the map itself,
     * whose properties must all be applied on the first pass).
     */
    private val valuesAppliedAtCreation: Boolean,
) {
    internal var element: T? = null

    /**
     * Applies [value] to the element via [block] whenever it differs from the last applied
     * value. The write happens in the apply phase, never during composition.
     */
    @Composable
    @Suppress("ComposableNaming") // lowercase for call-site parity with Updater.set
    public fun <V> set(value: V, block: T.(V) -> Unit) {
        val applied = remember {
            Applied(if (valuesAppliedAtCreation) value else NotApplied)
        }
        SideEffect {
            val element = element ?: return@SideEffect
            if (applied.value != value) {
                applied.value = value
                element.block(value)
            }
        }
    }

    private class Applied(var value: Any?)

    private companion object {
        val NotApplied = Any()
    }
}

/**
 * PROTOTYPE — manages the lifecycle and property updates of a single Maps SDK object without a
 * custom [androidx.compose.runtime.Applier].
 *
 * This is the effect-based counterpart of `ComposeNode<SomeMapNode, MapApplier>`:
 * - [create] runs once per `(map, keys)` from a [DisposableEffect], i.e. only after a successful
 *   composition (the node `factory` + `onAttached` equivalent);
 * - the disposal callback runs when this composable leaves the composition, the map changes, or
 *   any [keys] change identity (the `onRemoved` equivalent). The content is wrapped in [key], so
 *   a key change discards the whole group: old element disposed, per-property slots reset, new
 *   element created from current values — in that order;
 * - [update] runs as part of composition; its [ElementUpdater.set] calls subscribe to the state
 *   they read and apply only changed values after a successful apply.
 *
 * Because this is an ordinary composable, positional memoization, `key()`-based identity, and
 * conditional control flow behave exactly as they do for applier nodes — the slot table does
 * that bookkeeping in both architectures.
 *
 * **[keys] are identity keys, not property values.** Pass an object only when swapping it means
 * "this composable now controls a *different* Maps SDK object" (e.g. [Marker] passes its
 * `MarkerState` instance). Anything expressible as a setter belongs in [update]; passing a
 * changing property value (a color, a width) as a key turns cheap in-place updates into
 * teardown and recreation of the element on every change.
 *
 * Unlike applier nodes, this is public: third parties can add support for map object types the
 * library does not cover (e.g. a `Circle` or `GroundOverlay`) without library internals.
 *
 * Note: [create], [onAttached] and [onRemoved] are captured when the element is created; later
 * changes to those lambda instances are intentionally ignored until [keys] change. Route
 * user-supplied callbacks through [androidx.compose.runtime.rememberUpdatedState] as the
 * [Marker] and [Polyline] implementations do.
 */
@Composable
public fun <T : Any> GoogleMapScope.MapElement(
    vararg keys: Any?,
    create: GmsGoogleMap.() -> T,
    onAttached: (T) -> Unit = {},
    onRemoved: (T) -> Unit,
    update: @Composable ElementUpdater<T>.() -> Unit,
) {
    key(map, *keys) {
        val updater = remember { ElementUpdater<T>(valuesAppliedAtCreation = true) }
        DisposableEffect(Unit) {
            val element = map.create()
            updater.element = element
            onAttached(element)
            onDispose {
                updater.element = null
                onRemoved(element)
            }
        }
        // Composition phase: stages values and subscribes to their snapshot state. The writes
        // themselves run in the apply phase; on the first pass they are no-ops because the
        // element was just created from these same values.
        updater.update()
    }
}
