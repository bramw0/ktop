package net.bramw.kotlintopmp.ktop

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.reflect.full.createInstance
import kotlin.reflect.typeOf
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

//@OptIn(ExperimentalTime::class)
//fun LocalDate.default(): LocalDate = LocalDate(2026, 9, 17)
//fun LocalDate.default(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

open class ReadWriteShared<R, W>(initialValue: R, val conversion: (W) -> R) {
    private var value: R = initialValue

    open suspend fun write(newValue: W) {
        this.value = conversion(newValue)
    }

    open suspend fun read(): R {
        return this.value
    }
}

class ReadOnlyShared<R>(initialValue: R) : ReadWriteShared<R, Unit>(initialValue, { initialValue }) {
    override suspend fun write(newValue: Unit) = Unit
}

class WriteOnlyShared<W> : ReadWriteShared<Unit, W>(Unit, {}) {
    override suspend fun read(): Unit = Unit
}

class Shared<R>(initialValue: R) : ReadWriteShared<R, R>(initialValue, { it })

val currentDate: ReadOnlyShared<LocalDate> = ReadOnlyShared(LocalDate(2026, 9, 17))

fun <R, W> TaskScope.get(sds: ReadWriteShared<R, W>): Task<R> {
    return createTask {
        this.value = stableValue(sds.read())
    }
}

// get(currentDate) step ...