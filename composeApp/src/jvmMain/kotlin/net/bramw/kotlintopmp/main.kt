package net.bramw.kotlintopmp

import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bramw.kotlintopmp.ktop.*
import net.bramw.kotlintopmp.ktop.sds.*
import kotlin.time.Duration.Companion.milliseconds

fun log(name: String): (Event.ValueChanged<*>) -> Value<*> {
    return {
        println("task ${it.taskId} ($name): value changed to ${it.value}")
        it.value
    }
}

val log: (Event.ValueChanged<*>) -> Value<*> = log("")

fun <T> debug(msg: String): (Event.ValueChanged<T>) -> Value<T> {
    return {
        println(msg + " for i = ${it.value} succeeded")
        it.value
    }
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

        start(
            s trans log("s"), parallel trans log("parallel"), t3 trans log("t3"), or trans log("or"), t4 trans log("t4")
        )
    }

    println("Exited task scope 1")

    val localDateSDS = object : Shared<LocalDate>() {
        private var value: LocalDate = LocalDate(2026, 9, 17)
        override suspend fun write(newValue: LocalDate) {
            value = newValue
        }

        override suspend fun read(): LocalDate {
            return value
        }
    }

    fun createSds(): Shared<Int> {
        return object : Shared<Int>() {
            private var value = 0

            override suspend fun write(newValue: Int) {
                value += newValue
            }

            override suspend fun read(): Int = value
        }

    }

    val sdsA = createSds()
    val sdsB = createSds()
    val sdsC = createSds()

    taskScope {
        val d = set(localDateSDS, LocalDate(2026, 9, 18))
        val nextNewDate = get(localDateSDS) trans {
            println("Read local date!")
            it.value
        } step ifStable { date ->
            pure(date.plus(DatePeriod(days = 1))) step ifStable { nextDate ->
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


        val sdsAB = sdsA compose sdsB
        val sdsBC = sdsB compose sdsC

        val repeatCount = 300

        fun checker(prefix: String, first: Int, second: Int): Task<Int> {
            val l = first + second
            println("$prefix: $l")
            val result = (1..repeatCount).sum()
            println("$prefix: first=${first} == $result?")
            assert(first == result)
            println("$prefix: second=${second} == ${result * 2}?")
            assert(second == result * 2)
            return pure(l)
        }

        fun setter(sds: Shared<Pair<Int, Int>>, name: String, i: Int): Task<Pair<Int, Int>> {
            println("lhs: $name writing $i")
            println("rhs: $name writing $i")
            return set(sds, Pair(i, i)) trans debug("set($name, Pair(i, i))") step ifStable {
                get(sds) trans debug("get($name)")
            }
        }

        val sdsTester = withShared(repeatCount) { sds ->
            fun repeat(): Task<Pair<Int, Int>> {
                return get(sds) trans debug("get(sds)") step ifStable { i ->
                    set(sds, i - 1) trans debug("set(sds, i - 1)") step ifStable {
                        (setter(sdsAB, "sdsAB", i) and setter(sdsBC, "sdsBC", i)) step ifStable {
                            val ab = it.first
                            val bc = it.second

                            println("lhs: sdsAB read ${ab.first}")
                            println("rhs: sdsAB read ${ab.second}")

                            println("lhs: sdsBC read ${bc.first}")
                            println("rhs: sdsBC read ${bc.second}")

                            if (i == 1) {
                                checker("sdsAB", ab.first, ab.second) and checker("sdsBC", bc.second, bc.first)
                            } else {
                                println("Calling repeat for i = $i")
                                repeat()
                            }
                        }
                    }
                }
            }
            repeat()
        }

        // TODO: should we stop every UI task upon closing of the main UI window? If so, how do we correctly propagate up the task tree that every UI task has stopped?
        val uiTest = viewInformation(5)
        val uiTest2 = viewInformation(10)

        start(
            d trans log("d"),
            nextNewDate trans log("nextNewDate"),
            t trans log("t"),
            newD trans log("newD"),
            a trans log("a"),
//            sdsTester trans log("sdsTester"),
            uiTest trans log("uiTest"),
            uiTest2 trans log("uiTest2"),
        )
    }

    println("Exited task scope 2")
}