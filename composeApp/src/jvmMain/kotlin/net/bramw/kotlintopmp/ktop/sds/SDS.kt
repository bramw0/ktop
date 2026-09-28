package net.bramw.kotlintopmp.ktop.sds

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.bramw.kotlintopmp.ktop.*
import kotlin.time.Clock

//interface ReadWriteShared<R, W> {
//    suspend fun write(newValue: W)
//
//    suspend fun read(): R
//
//    fun <T> withRead(f: (R) -> T): ReadWriteShared<T, W> {
//        return object : ReadWriteShared<T, W> {
//            override suspend fun write(newValue: W) = this@ReadWriteShared.write(newValue)
//
//            override suspend fun read(): T = f(this@ReadWriteShared.read())
//        }
//    }
//
//    fun <T> withWrite(f: (T, R) -> W?): ReadWriteShared<R, T> {
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
//    fun toReadOnly(): ReadOnlyShared<R> {
//        // TODO: is this correct?
//        return object : ReadOnlyShared<R> {
//            override suspend fun read(): R = this@ReadWriteShared.read()
//        }
//        // Will not work since we cannot downcast from ReadWriteShared<R, Unit> to ReadOnlyShared<R>.
////        return withWrite<Unit> { _, _ -> null } as ReadOnlyShared<R>
//    }
//
//    fun toWriteOnly(): WriteOnlyShared<W> {
//        // TODO: is this correct?
//        return object : WriteOnlyShared<W> {
//            override suspend fun write(newValue: W) = this@ReadWriteShared.write(newValue)
//        }
//    }
//}
//
//// Similar to type synonym in Clean, but does not allow the passing of default parameters or overriding of functions.
////typealias ReadOnlyShared<R> = ReadWriteShared<R, Unit>
//
//interface ReadOnlyShared<R> : ReadWriteShared<R, Unit> {
//    override suspend fun write(newValue: Unit): Nothing = error("Trying to write to read-only share")
//    override fun <T> withRead(f: (R) -> T): ReadOnlyShared<T> {
//        return object : ReadOnlyShared<T> {
//            override suspend fun read(): T = f(this@ReadOnlyShared.read())
//        }
//    }
//
//    override fun <T> withWrite(f: (T, R) -> Unit?): Nothing = error("Trying to write to read-only share")
//}
//
//interface WriteOnlyShared<W> : ReadWriteShared<Unit, W> {
//    override suspend fun read(): Nothing = error("Trying to read from write-only share")
//    override fun <T> withRead(f: (Unit) -> T): Nothing = error("Trying to read from write-only share")
//
//    override fun <T> withWrite(f: (T, Unit) -> W?): WriteOnlyShared<T> {
//        return object : WriteOnlyShared<T> {
//            override suspend fun write(newValue: T) {
//                // TODO: Make sure to lock the data source
//                f(newValue, Unit)?.let {
//                    this@WriteOnlyShared.write(it)
//                }
//            }
//        }
//    }
//}
//
//interface Shared<R> : ReadWriteShared<R, R>
//
//
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
//object Null : WriteOnlyShared<Nothing> {
//    override suspend fun write(newValue: Nothing) {}
//}
//
//
//fun <R> TaskScope.get(sds: ReadWriteShared<R, *>): Task<R> {
//    return createTask {
//        this.value = stableValue(sds.read())
//    }
//}
//
//fun <W> TaskScope.set(sds: ReadWriteShared<*, W>, newValue: W): Task<W> {
//    return createTask {
//        sds.write(newValue)
//        this.value = stableValue(newValue)
//    }
//}

interface ReadOnlyShared<R> {
    suspend fun read(): R

    fun <T> withRead(f: (R) -> T): ReadOnlyShared<T> {
        return object : ReadOnlyShared<T> {
            override suspend fun read(): T = f(this@ReadOnlyShared.read())
        }
    }
}

// TODO: is there a way to remove the R parameter and still be able to override the function inside of ReadWriteShared using its R parameter?
interface WriteOnlyShared<W, R> {
    suspend fun write(newValue: W)

    fun <T> withWrite(f: (T, R?) -> W?): WriteOnlyShared<T, R> {
        return object : WriteOnlyShared<T, R> {
            override suspend fun write(newValue: T) {
                // TODO: Make sure to lock the data source
                f(newValue, null)?.let {
                    this@WriteOnlyShared.write(it)
                }
            }
        }
    }
}

interface ReadWriteShared<R, W> : ReadOnlyShared<R>, WriteOnlyShared<W, R> {
    override fun <T> withRead(f: (R) -> T): ReadWriteShared<T, W> {
        return object : ReadWriteShared<T, W> {
            override suspend fun write(newValue: W) = this@ReadWriteShared.write(newValue)

            override suspend fun read(): T = f(this@ReadWriteShared.read())
        }
    }

    override fun <T> withWrite(f: (T, R?) -> W?): ReadWriteShared<R, T> {
        return object : ReadWriteShared<R, T> {
            override suspend fun read(): R = this@ReadWriteShared.read()
            override suspend fun write(newValue: T) {
                // TODO: Make sure to lock the data source
                f(newValue, read())?.let {
                    this@ReadWriteShared.write(it)
                }
            }
        }
    }

    // withReadWrite function does not work well with Kotlin conventions due to 2 lambdas in the argument. Better to chain .withWrite { ... }.withRead { ... }
//    fun <T, V> withReadWrite(readF: (R) -> T, writeF: (V, R?) -> W?): ReadWriteShared<T, V> {
//        return withWrite(writeF).withRead(readF)
//    }

    // toReadOnly function using withWrite combinator is not needed since automatic upcasting from ReadWriteShared to ReadOnlyShared is performed.
}

typealias Shared<R> = ReadWriteShared<R, R>

class Const<R>(val value: R) : ReadOnlyShared<R> {
    override suspend fun read(): R {
        return value
    }
}

object CurrentTime : ReadOnlyShared<LocalDateTime> {
    override suspend fun read(): LocalDateTime {
        return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    }
}

object Null : WriteOnlyShared<Nothing, Nothing> {
    override suspend fun write(newValue: Nothing) {}
}


fun <R> TaskScope.get(sds: ReadOnlyShared<R>): Task<R> {
    return createTask {
        this.value = stableValue(sds.read())
    }
}

fun <W> TaskScope.set(sds: WriteOnlyShared<W, *>, newValue: W): Task<W> {
    return createTask {
        sds.write(newValue)
        this.value = stableValue(newValue)
    }
}
