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
import androidx.compose.runtime.remember
import com.google.android.gms.maps.GoogleMap as GmsGoogleMap

/**
 * PROTOTYPE — effect-based alternative to the [com.google.maps.android.compose.MapApplier]
 * architecture.
 *
 * Records `(value, block)` pairs while the composition runs and replays only the *changed*
 * ones against the managed object after the composition is successfully applied. This is the
 * effect-based equivalent of the [androidx.compose.runtime.Updater.set] blocks used by
 * `ComposeNode` in the applier architecture, preserving its two key properties:
 *
 * 1. **Apply-phase timing** — no write ever reaches the Maps SDK from a composition that may
 *    still be abandoned. Values are staged during composition (which also subscribes the
 *    recomposition scope to any snapshot state they were read from) and applied from
 *    [SideEffect], which only runs after a successful apply.
 * 2. **Per-value diffing** — a recomposition that changes one parameter results in exactly one
 *    setter call, mirroring the slot-comparison behavior of `Updater.set`.
 */
public class UpdateScope<T : Any> internal constructor() {

    private val pendingValues = ArrayList<Any?>()
    private val pendingBlocks = ArrayList<Any?>() // T.(V) -> Unit, erased
    private val appliedValues = ArrayList<Any?>()
    private var cursor = 0
    private var subject: T? = null

    /**
     * Stages [value] for application via [block]. [block] is invoked with the managed object as
     * receiver, but only when [value] differs (structurally) from the last applied value —
     * including on the first pass after creation when the creator did not already apply it.
     *
     * Must only be called from within the `update` lambda of [MapElement] (or an equivalent
     * recording pass); call order identifies the slot, so `set` calls must not be reordered
     * conditionally between recompositions.
     */
    public fun <V> set(value: V, block: T.(V) -> Unit) {
        val i = cursor++
        if (i < pendingValues.size) {
            pendingValues[i] = value
            pendingBlocks[i] = block
        } else {
            pendingValues.add(value)
            pendingBlocks.add(block)
        }
    }

    /** Runs [update] as a recording pass. Composition-phase only; performs no external writes. */
    internal fun record(update: UpdateScope<T>.() -> Unit) {
        cursor = 0
        update()
        // Trim slots if the update block staged fewer values than last time.
        while (pendingValues.size > cursor) {
            pendingValues.removeAt(pendingValues.size - 1)
            pendingBlocks.removeAt(pendingBlocks.size - 1)
            if (appliedValues.size > cursor) appliedValues.removeAt(appliedValues.size - 1)
        }
    }

    /**
     * Attaches a subject that was just created *from the currently staged values* (e.g. via an
     * options object). The staged values are considered applied, making the first apply pass a
     * no-op.
     */
    internal fun attach(subject: T) {
        this.subject = subject
        appliedValues.clear()
        appliedValues.addAll(pendingValues)
    }

    /**
     * Attaches a subject that was created independently of the staged values (e.g. the map
     * itself). Every staged value is applied on the first apply pass.
     */
    internal fun attachUnapplied(subject: T) {
        this.subject = subject
        appliedValues.clear()
    }

    internal fun detach() {
        subject = null
    }

    /** Applies staged values that differ from the last applied ones. Apply-phase only. */
    internal fun applyPending() {
        val subject = subject ?: return
        for (i in pendingValues.indices) {
            val value = pendingValues[i]
            if (i >= appliedValues.size) {
                appliedValues.add(NotApplied)
            }
            if (appliedValues[i] != value) {
                appliedValues[i] = value
                @Suppress("UNCHECKED_CAST")
                (pendingBlocks[i] as T.(Any?) -> Unit).invoke(subject, value)
            }
        }
    }

    private companion object {
        private val NotApplied = Any()
    }
}

/**
 * PROTOTYPE — manages the lifecycle and property updates of a single Maps SDK object without a
 * custom [androidx.compose.runtime.Applier].
 *
 * This is the effect-based counterpart of `ComposeNode<SomeMapNode, MapApplier>`:
 * - [create] runs once per `(map, keys)` from a [DisposableEffect], i.e. only after a
 *   successful composition (the node `factory` + `onAttached` equivalent);
 * - the disposal callback runs when this composable leaves the composition, the map changes, or
 *   any [keys] change identity (the `onRemoved` equivalent — including automatic
 *   detach-old-then-attach-new ordering on key changes);
 * - [update] is a recording pass executed during composition (subscribing to any snapshot state
 *   it reads) whose staged values are diffed and applied from [SideEffect].
 *
 * Because this is an ordinary composable, positional memoization, `key()`-based identity and
 * conditional control flow behave exactly as they do for applier nodes — the slot table does
 * that bookkeeping in both architectures.
 *
 * Unlike applier nodes, this is public: third parties can add support for map object types the
 * library does not cover (e.g. a `Circle` or `GroundOverlay`) without library internals.
 *
 * Note: [create], [onAttached] and [onRemoved] are captured when the effect launches; later
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
    update: UpdateScope<T>.() -> Unit,
) {
    val scope = remember(map, *keys) { UpdateScope<T>() }
    // Composition phase: stage values + subscribe to the snapshot state they came from.
    scope.record(update)
    DisposableEffect(map, *keys) {
        val element = map.create()
        scope.attach(element)
        onAttached(element)
        onDispose {
            scope.detach()
            onRemoved(element)
        }
    }
    // Apply phase. SideEffects are dispatched after remember observers within the same apply
    // pass, so on the first pass `create` has already run and this is a no-op (see attach()).
    SideEffect { scope.applyPending() }
}
