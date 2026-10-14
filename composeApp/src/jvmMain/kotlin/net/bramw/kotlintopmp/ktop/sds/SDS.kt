package net.bramw.kotlintopmp.ktop.sds

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.bramw.kotlintopmp.ktop.*
import kotlin.time.Clock

context(ts: TaskScope)
private suspend fun <R, OR> composedRead(lhs: ReadWriteShared<R, *>, rhs: ReadWriteShared<OR, *>): Pair<R, OR> {
    return lhs.lock.withLock {
        rhs.lock.withLock {
            val thisReader = ts.scope.async(CoroutineName("lhs composed reader")) {
                lhs.read()
            }

            val otherReader = ts.scope.async(CoroutineName("rhs composed reader")) {
                rhs.read()
            }

            val thisValue = thisReader.await()
//            println("lhs: read $thisValue")
            val otherValue = otherReader.await()
//            println("rhs: read $thisValue")
            Pair(thisValue, otherValue)
        }
    }
}

// TODO: should we launch this on the task scope or use a separate coroutinescope for non-task coroutines?
context(ts: TaskScope)
private suspend fun <W, OW> composedWrite(lhs: ReadWriteShared<*, W>, rhs: ReadWriteShared<*, OW>, newValue: Pair<W, OW>) {
    lhs.lock.withLock {
        rhs.lock.withLock {
//            println("lhs: writing ${newValue.first}")
            val thisWriter = ts.scope.launch(CoroutineName("lhs composed writer")) {
                lhs.write(newValue.first)
            }

//            println("rhs: writing ${newValue.second}")
            val otherWriter = ts.scope.launch(CoroutineName("rhs composed writer")) {
                rhs.write(newValue.second)
            }

            thisWriter.join()
            otherWriter.join()
        }
    }
}

private suspend fun <R, W, T> projectedRead(rws: ReadWriteShared<R, W>, f: (R) -> T): T {
    return rws.lock.withLock {
        val v = rws.read()
        f(v)
    }
}

private suspend fun <R, W> defaultRead(rws: ReadWriteShared<R, W>): R {
    return rws.lock.withLock {
        rws.read()
    }
}

private suspend fun <R, W, T> projectedWrite(rws: ReadWriteShared<R, W>, newValue: T, f: (T, R?) -> W?, hasPreviousValue: Boolean = true) {
    rws.lock.withLock {
        val v = if (hasPreviousValue) rws.read() else null

        f(newValue, v)?.let {
            rws.write(it)
        }
    }
}

private suspend fun <R, W> defaultWrite(rws: ReadWriteShared<R, W>, newValue: W) {
    rws.lock.withLock {
        rws.write(newValue)
    }
}


abstract class ReadWriteShared<R, W>() {
    val lock: Mutex = Mutex(false)

    abstract suspend fun write(newValue: W)

    abstract suspend fun read(): R

    open fun <T> withRead(f: (R) -> T): ReadWriteShared<T, W> {
        return object : ReadWriteShared<T, W>() {
            override suspend fun read(): T = projectedRead(this@ReadWriteShared, f)

            override suspend fun write(newValue: W) = defaultWrite(this@ReadWriteShared, newValue)
        }
    }

    open fun <T> withWrite(f: (newValue: T, oldValue: R?) -> W?): ReadWriteShared<R, T> {
        return object : ReadWriteShared<R, T>() {
            override suspend fun read(): R = defaultRead(this@ReadWriteShared)

            override suspend fun write(newValue: T) = projectedWrite<R, W, T>(this@ReadWriteShared, newValue, f)
        }
    }

    context(ts: TaskScope)
    infix fun <OR, OW> compose(other: ReadWriteShared<OR, OW>): ReadWriteShared<Pair<R, OR>, Pair<W, OW>> {
        return object : ReadWriteShared<Pair<R, OR>, Pair<W, OW>>() {
            override suspend fun read(): Pair<R, OR> = composedRead(this@ReadWriteShared, other)

            override suspend fun write(newValue: Pair<W, OW>) = composedWrite(this@ReadWriteShared, other, newValue)
        }
    }


    fun toReadOnly(): ReadOnlyShared<R> {
        // TODO: is this correct?
        return object : ReadOnlyShared<R>() {
            override suspend fun read(): R = defaultRead(this@ReadWriteShared)
        }
        // Will not work since we cannot downcast from ReadWriteShared<R, Unit> to ReadOnlyShared<R>.
//        return withWrite<Unit> { _, _ -> null } as ReadOnlyShared<R>
    }

    fun toWriteOnly(): WriteOnlyShared<W> {
        // TODO: is this correct?
        return object : WriteOnlyShared<W>() {
            override suspend fun write(newValue: W) = defaultWrite(this@ReadWriteShared, newValue)
        }
    }
}

// Similar to type synonym in Clean, but does not allow the passing of default parameters or overriding of functions.
//typealias ReadOnlyShared<R> = ReadWriteShared<R, Unit>

abstract class ReadOnlyShared<R> : ReadWriteShared<R, Nothing>() {
    override suspend fun write(newValue: Nothing): Nothing = error("Trying to write to read-only share")
    override fun <T> withWrite(f: (newValue: T, oldValue: R?) -> Nothing?): Nothing = error("Trying to write to read-only share")

    override fun <T> withRead(f: (R) -> T): ReadOnlyShared<T> {
        return object : ReadOnlyShared<T>() {
            override suspend fun read(): T = projectedRead(this@ReadOnlyShared, f)
        }
    }

    context(ts: TaskScope)
    infix fun <OR> compose(other: ReadOnlyShared<OR>): ReadOnlyShared<Pair<R, OR>> {
        return object : ReadOnlyShared<Pair<R, OR>>() {
            override suspend fun read(): Pair<R, OR> = composedRead(this@ReadOnlyShared, other)
        }
    }
}

// TODO: Should you be able to compose a ReadOnlyShared and ReadWriteShared together? Should you be able to write to the combined SDS, but only give one of the two values since the other does not support writing? Yes! Since ReadOnlyShared<R> == ReadWriteShared<R, Nothing> this should just work and should not be treated as different types.

abstract class WriteOnlyShared<W> : ReadWriteShared<Nothing, W>() {
    override suspend fun read(): Nothing = error("Trying to read from write-only share")
    override fun <T> withRead(f: (Nothing) -> T): Nothing = error("Trying to read from write-only share")

    override fun <T> withWrite(f: (newValue: T, oldValue: Nothing?) -> W?): WriteOnlyShared<T> {
        return object : WriteOnlyShared<T>() {
            override suspend fun write(newValue: T) = projectedWrite(this@WriteOnlyShared, newValue, f, hasPreviousValue = false)
        }
    }

    context(ts: TaskScope)
    infix fun <OW> compose(other: WriteOnlyShared<OW>): WriteOnlyShared<Pair<W, OW>> {
        return object : WriteOnlyShared<Pair<W, OW>>() {
            override suspend fun write(newValue: Pair<W, OW>) = composedWrite(this@WriteOnlyShared, other, newValue)
        }
    }
}

typealias Shared<R> = ReadWriteShared<R, R>

class Const<R>(val value: R) : ReadOnlyShared<R>() {
    override suspend fun read(): R {
        return value
    }
}

object CurrentTime : ReadOnlyShared<LocalDateTime>() {
    override suspend fun read(): LocalDateTime {
        return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    }
}

object Null : WriteOnlyShared<Unit>() {
    override suspend fun write(newValue: Unit) {}
}


fun <R> TaskScope.get(sds: ReadWriteShared<R, *>): Task<R> {
    return createTask {
        sds.lock.withLock {
            val value = sds.read()
            this.value = stableValue(value)
        }
    }
}

fun <W> TaskScope.set(sds: ReadWriteShared<*, W>, newValue: W): Task<W> {
    return createTask {
        sds.lock.withLock {
            sds.write(newValue)
            this.value = stableValue(newValue)
        }
    }
}

fun <R, W> TaskScope.update(sds: ReadWriteShared<R, W>, f: (R) -> W): Task<W> {
    return createTask {
        sds.lock.withLock {
            val newValue = f(sds.read())
            sds.write(newValue)
            this.value = stableValue(newValue)
        }
    }
}

fun <R, T> withShared(initialValue: R, block: (Shared<R>) -> Task<T>): Task<T> {
    val s = object : Shared<R>() {
        var value = initialValue
        override suspend fun read(): R = value

        override suspend fun write(newValue: R) {
            value = newValue
        }
    }
    return block(s)
}

// TODO: does this implementation have the same semantics as above? the combine combinator would have to be defined in ReadOnlyShared, WriteOnlyShared and ReadWriteShared instead of once for all the types
//interface ReadOnlyShared<R> {
//    suspend fun read(): R
//
//    fun <T> withRead(f: (R) -> T): ReadOnlyShared<T> {
//        return object : ReadOnlyShared<T> {
//            override suspend fun read(): T = f(this@ReadOnlyShared.read())
//        }
//    }
//}
//
//// TODO: is there a way to remove the R parameter and still be able to override the function inside of ReadWriteShared using its R parameter?
//interface WriteOnlyShared<W, R> {
//    suspend fun write(newValue: W)
//
//    fun <T> withWrite(f: (T, R?) -> W?): WriteOnlyShared<T, R> {
//        return object : WriteOnlyShared<T, R> {
//            override suspend fun write(newValue: T) {
//                // TODO: Make sure to lock the data source
//                f(newValue, null)?.let {
//                    this@WriteOnlyShared.write(it)
//                }
//            }
//        }
//    }
//}
//
//interface ReadWriteShared<R, W> : ReadOnlyShared<R>, WriteOnlyShared<W, R> {
//    override fun <T> withRead(f: (R) -> T): ReadWriteShared<T, W> {
//        return object : ReadWriteShared<T, W> {
//            override suspend fun write(newValue: W) = this@ReadWriteShared.write(newValue)
//
//            override suspend fun read(): T = f(this@ReadWriteShared.read())
//        }
//    }
//
//    override fun <T> withWrite(f: (T, R?) -> W?): ReadWriteShared<R, T> {
//        return object : ReadWriteShared<R, T> {
//            override suspend fun read(): R = this@ReadWriteShared.read()
//            override suspend fun write(newValue: T) {
//                // TODO: Make sure to lock the data source
//                f(newValue, read())?.let {
//                    this@ReadWriteShared.write(it)
//                }
//            }
//        }
//    }
//
//    // withReadWrite function does not work well with Kotlin conventions due to 2 lambdas in the argument. Better to chain .withWrite { ... }.withRead { ... }
////    fun <T, V> withReadWrite(readF: (R) -> T, writeF: (V, R?) -> W?): ReadWriteShared<T, V> {
////        return withWrite(writeF).withRead(readF)
////    }
//
//    // toReadOnly function using withWrite combinator is not needed since automatic upcasting from ReadWriteShared to ReadOnlyShared is performed.
//}

//interface ReadWriteShared<R, W> {
//    suspend fun read(): R = error("")
//    suspend fun write(newValue: W): Unit = error("")
//
//    fun <T> withRead(f: (R) -> T): ReadWriteShared<T, W> {
//        return object : ReadWriteShared<T, W> {
//            override suspend fun read(): T = f(this@ReadWriteShared.read())
//            override suspend fun write(newValue: W) = this@ReadWriteShared.write(newValue)
//        }
//    }
//
//    fun <T> withWrite(f: (T, R?) -> W?): ReadWriteShared<R, T> {
//        return object : ReadWriteShared<R, T> {
//            override suspend fun read(): R = this@ReadWriteShared.read()
//            override suspend fun write(newValue: T) {
//                // TODO: Make sure to lock the data source
//                f(newValue, read())?.let {
//                    this@ReadWriteShared.write(it)
//                }
//            }
//        }
//    }
//}
//
//typealias ReadOnlyShared<R> = ReadWriteShared<R, Any>
//typealias WriteOnlyShared<W> = ReadWriteShared<Any, W>
//typealias Shared<R> = ReadWriteShared<R, R>

//class Const<R>(val value: R) : ReadOnlyShared<R> {
//    override suspend fun read(): R {
//        return value
//    }
//}
//
//object CurrentTime : ReadOnlyShared<LocalDateTime> {
//    override suspend fun read(): LocalDateTime {
//        return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
//    }
//}
//
//object Null : WriteOnlyShared<Unit> {
//    override suspend fun write(newValue: Unit) {}
//}
//
//
//fun <R> TaskScope.get(sds: ReadOnlyShared<R>): Task<R> {
//    return createTask {
//        this.value = stableValue(sds.read())
//    }
//}
//
//fun <W> TaskScope.set(sds: WriteOnlyShared<W>, newValue: W): Task<W> {
//    return createTask {
//        sds.write(newValue)
//        this.value = stableValue(newValue)
//    }
//}

// Multiple approaches:
// - typealias approach:
//      typealiases cannot have star projections in their expansion so the readonly and writeonly aliases cannot work as intended since no SDSs match ReadWriteShared<R, Any> or Any = Unit or Any = Nothing
//      but makes it so there is one central place for combinators to live
// - readwriteshared as top interface:
//      downcasting will not work so new objects have to be made when casting a readwriteshared to readonly or writeonly
//      has central place for combinators to live
// - readwriteshared inherits from readonly and writeonly:
//      upcasting works flawlessly so set and get only get the read and write part of the shared data source
//      but there is no central place for combinators to live since combinators also have to work on readonly and writeonly SDSs
