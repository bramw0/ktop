package net.bramw.kotlintopmp.ktop

import net.bramw.kotlintopmp.ktop.Stability.Stable
import net.bramw.kotlintopmp.ktop.Stability.Unstable
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

enum class Stability {
    Stable, Unstable,
}

typealias Value<T> = Pair<T, Stability>?

// Use an experimental contract to let the Kotlin compiler use this null check to smart cast the corresponding nullable Value.
@OptIn(ExperimentalContracts::class)
fun <T> Value<T>.hasValue(): Boolean {
    contract {
        returns(true) implies (this@hasValue != null)
    }
    return this != null
}

fun <T> unstableValue(value: T): Value<T> = Value(value, Unstable)
fun <T> stableValue(value: T): Value<T> = Value(value, Stable)
fun <T> Value<T>.isStableValue(): Boolean = hasValue() && second == Stable
fun <T> Value<T>.isNoValue(): Boolean = !hasValue()
fun <T> Value<T>.isUnstableValue(): Boolean = hasValue() && second == Unstable
