package net.bramw.kotlintopmp.ktop

import net.bramw.kotlintopmp.ktop.Stability.Stable
import net.bramw.kotlintopmp.ktop.Stability.Unstable
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

enum class Stability {
    Stable, Unstable,
}

// Moved from typealias to data class because T could still be nullable, which could lead to (un)stable values of null; which is incorrect and should be a novalue instead.
// However, giving T an upper bound caused type inference to not always be able to figure out the correct type in case of multiple generics with upper bounds; which is the case in the Pred class when continuations are created.
//typealias Value<T> = Pair<T, Stability>?

data class Value<T : Any>(val inner: T, val stability: Stability) {
    fun isStableValue(): Boolean = stability == Stable
    fun isUnstableValue(): Boolean = stability == Unstable
}

fun <T : Any> unstableValue(value: T): Value<T> = Value(value, Unstable)
fun <T : Any> stableValue(value: T): Value<T> = Value(value, Stable)

// Use an experimental contract to let the Kotlin compiler use this null check to smart cast the corresponding nullable Value.
@OptIn(ExperimentalContracts::class)
fun <T: Any> Value<T>?.hasValue(): Boolean {
    contract {
        returns(true) implies (this@hasValue != null)
    }
    return this != null
}

fun <T : Any> Value<T>?.isStableValue(): Boolean = hasValue() && stability == Stable
fun <T : Any> Value<T>?.isUnstableValue(): Boolean = hasValue() && stability == Unstable
fun <T : Any> Value<T>?.isNoValue(): Boolean = !hasValue()

fun <T : Any> Value<T>?.asNullable(): T? = this?.inner