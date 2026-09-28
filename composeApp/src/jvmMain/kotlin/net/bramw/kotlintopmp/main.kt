package net.bramw.kotlintopmp

import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bramw.kotlintopmp.ktop.*
import net.bramw.kotlintopmp.ktop.sds.*
import kotlin.time.Duration.Companion.milliseconds

val log: (Event.ValueChanged<*>) -> Value<*> = {
    println("task\t${it.taskId}\tvalue changed to ${it.value}")
    it.value
}


suspend fun main() {
    taskScope {
        val t1 = createTask {
            value = unstableValue("Der")
            delay(100.milliseconds)
            value = null
            delay(100.milliseconds)
            value = stableValue("Die")
        }

        val s = t1 step listOf(
            onValue {
                pred {
                    it.isStableValue()
                }
                then { v ->
                    createTask {
                        value = stableValue(v!!.first + " HAHAHA")
                    }
                }
            },
        )

        val t2 = createTask {
            value = unstableValue("WWW")
            delay(50.milliseconds)
            value = null
            delay(150.milliseconds)
            value = stableValue("XXX")
        }

        val t3 = t1 trans {
            it.value?.let { v ->
                if (v.isStableValue()) stableValue(v.first + " stable!") else unstableValue(v.first + " transformed!")
            } ?: run {
                unstableValue("DWA")
            }
        }

        val t4 = t1 seq {
            createTask {
                value = stableValue(it!!.first + " HAHAHA")
            }
        }

        val parallel = t1 and t2

        val or = t1 or t2

        start(s trans log, parallel trans log, t3 trans log, or trans log, t4 trans log)
    }

    val localDateSDS = object : Shared<LocalDate> {
        private var value: LocalDate = LocalDate(2026, 9, 17)
        override suspend fun write(newValue: LocalDate) {
            value = newValue
        }

        override suspend fun read(): LocalDate {
            return value
        }
    }

    val testSDS = Const(15)

    taskScope {
        val d = set(localDateSDS, LocalDate(2026, 9, 18))
        val nextNewDate = get(localDateSDS) step ifStable { date ->
            pure(date.plus(DatePeriod(days = 1))) step
                    ifStable { nextDate ->
                        pure(nextDate.plus(DatePeriod(days = 1)))
                    }
        }

        val t = get(CurrentTime)
        val currentDate = CurrentTime.withRead { it.date }
        val a = get(localDateSDS) trans {
            println("Read a!")
            it.value
        }

        val newD = get(currentDate)
//        val newDate = createTask {
//            delay(1000.milliseconds)
//            this.value = stableValue(Unit)
//        } step ifStable {
//                get(currentDate)
//            }

        start(d trans log, nextNewDate trans log, t trans log, newD trans log, a trans log)
    }
}