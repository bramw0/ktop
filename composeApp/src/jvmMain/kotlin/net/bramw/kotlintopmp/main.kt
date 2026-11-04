package net.bramw.kotlintopmp

import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bramw.kotlintopmp.ktop.*
import net.bramw.kotlintopmp.ktop.sds.*
import net.bramw.kotlintopmp.ktop.ui.viewInformation
import kotlin.time.Duration.Companion.milliseconds

fun <T : Any> log(name: String): (Event.ValueChanged<T>) -> Value<T>? {
    return {
        println("task ${it.taskId} ($name): value changed to ${it.value}")
        it.value
    }
}

fun <T : Any> debug(msg: String): (Event.ValueChanged<T>) -> Value<T>? {
    return {
        println(msg + " for i = ${it.value} succeeded")
        it.value
    }
}


suspend fun main() {
//    taskScope {
//        val t1 = createTask {
//            value = unstableValue("Der")
//            delay(100.milliseconds)
//            value = null
//            delay(100.milliseconds)
//            value = stableValue("Die")
//        }
//
//        val s = t1 step listOf(
//            onValue {
//                pred {
//                    it.isStableValue()
//                }
//                then { v ->
//                    createTask {
//                        value = stableValue(v!!.inner + " HAHAHA")
//                    }
//                }
//            },
//        )
//
//        val t2 = createTask {
//            value = unstableValue("WWW")
//            delay(50.milliseconds)
//            value = null
//            delay(150.milliseconds)
//            value = stableValue("XXX")
//        }
//
//        val t3 = t1 transValue {
//            it.value?.let { v ->
//                if (v.isStableValue()) stableValue(v.inner + " stable!") else unstableValue(v.inner + " transformed!")
//            } ?: run {
//                unstableValue("DWA")
//            }
//        }
//
//        val t4 = t1 seq {
//            createTask {
//                value = stableValue(it!!.inner + " HAHAHA")
//            }
//        }
//
//        val parallel = t1 and t2
//
//        val or = t1 or t2
//
//        start(
//            s transValue log("s"),
//            parallel transValue log("parallel"),
//            t3 transValue log("t3"),
//            or transValue log("or"),
//            t4 transValue log("t4")
//        )
//    }

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
        val nextNewDate = get(localDateSDS) transValue {
            println("Read local date!")
            it.value
        } step ifStable { date ->
            pure(date.plus(DatePeriod(days = 1))) step ifStable { nextDate ->
                pure(nextDate.plus(DatePeriod(days = 1)))
            }
        }

        val t = get(CurrentTime)
        val currentDate = CurrentTime.withRead { it.date }
        val a = get(localDateSDS) transValue {
            println("Read a!")
            it.value
        }

        val newD = get(currentDate)


        val sdsAB = sdsA compose sdsB
        val sdsBC = sdsB compose sdsC

        val repeatCount = 100
        val repeatResult = (1..repeatCount).sum()

        fun checker(prefix: String, first: Int, second: Int): Task<Int> {
            val l = first + second
            println("$prefix: $l")
            println("$prefix: first=${first} == $repeatResult?")
            assert(first == repeatResult)
            println("$prefix: second=${second} == ${repeatResult * 2}?")
            assert(second == repeatResult * 2)
            return pure(l)
        }

        fun setter(sds: Shared<Pair<Int, Int>>, name: String, i: Int): Task<Pair<Int, Int>> {
            println("lhs: $name writing $i")
            println("rhs: $name writing $i")
            return set(sds, Pair(i, i)) transValue debug("set($name, Pair(i, i))") step ifStable {
                get(sds) transValue debug("get($name)")
            }
        }

        val sdsTester = withShared(repeatCount) { sds ->
            fun repeat(): Task<Pair<Int, Int>> {
                return get(sds) transValue debug("get(sds)") step ifStable { i ->
                    set(sds, i - 1) transValue debug("set(sds, i - 1)") step ifStable {
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
        // no, we should not. The UI is supposed to only be a (read-only) mirror of the currently running tasks, not the manager. The user can send actions to the tasks, but no more.
//        val uiTest: Task<Int> = createTask {
//            delay(1000.milliseconds)
//            this.value = stableValue(Unit)
//        } step ifStable {
//            println("Going to view 5!")
//            viewInformation(5) step onAction(UserAction.Continue) {
//                then {
//                    viewInformation(15)
//                }
//            }
//        }

//        val uiTest = viewInformation(listOf(Color.GREEN, Color.RED, Color.BLUE, Color.GREEN))
        val uiTest =
            viewInformation(setOf(mapOf(Color.GREEN to 5, Color.BLUE to 8), mapOf(Color.RED to 10, Color.GREEN to 15)))

        val uiTest3 =
            viewInformation(mapOf(Color.GREEN to Color.RED, Color.RED to Color.BLUE, Color.BLUE to Color.GREEN))

        val uiTest4 = viewInformation(Color.RED)

        val uiTest5 = viewInformation(
            Family(
                setOf(
                    Person(listOf("bram", "weessies"), 22)
                ),
                House(7329)
            )
        )

        val uiTest6 = viewInformation(Container(20, "Hello"))

        val uiTest2 = viewInformation(10)

        val watcher: Task<Int> = watch(sdsA) transValue log("sdsA") step onValue {
            pred {
                (it.isStableValue() || it.isUnstableValue()) && it!!.inner == repeatResult
            }
            then {
                pure(it)
            }
        }

        start(
//            d transValue log("d"),
//            nextNewDate transValue log("nextNewDate"),
//            t transValue log("t"),
//            newD transValue log("newD"),
//            a transValue log("a"),
//            sdsTester transValue log("sdsTester"),
//            watcher,
            uiTest transValue log("uiTest"),
            uiTest2 transValue log("uiTest2"),
            uiTest3 transValue log("uiTest3"),
            uiTest4 transValue log("uiTest4"),
            uiTest5 transValue log("uiTest5"),
            uiTest6 transValue log("uiTest6"),
        )
        delay(1000.milliseconds)
        println("Started")
    }

    println("Exited task scope 2")
}

enum class Color(val rgb: Int) {
    RED(0xFF0000), GREEN(0x00FF00), BLUE(0x0000FF)
}

data class Person(val name: List<String>, val age: Int?)
data class House(val post: Int)
data class Family(val people: Set<Person>, val house: House)

data class Container<T, V>(val inner: T, val outer: V)
