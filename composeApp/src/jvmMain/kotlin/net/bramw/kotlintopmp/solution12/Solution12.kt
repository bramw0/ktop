package solution12

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import solution12.TaskContinuation.OnValue
import solution12.TaskStability.Stable
import solution12.TaskStability.Unstable


fun Job?.tag(): String {
    return hashCode().toString(16)
}

fun <T> Task<T>?.tag(): String {
    return hashCode().toString(16)
}

fun <T> Task<T>?.fullID(job: Job? = null): String {
    return "t@${tag()} - j@${job?.tag() ?: this?.handle?.tag()} - ${this?.taskName} - ${this?.value}"
}



data class Person(val name: String)

fun viewPerson(name: String?): Task<Person> {
    return TaskExecutor.createTask {
        value = value?.let {
            Pair(Person(it.first.name + name), Stable)
        } ?: run {
            Pair(Person(name ?: ""), Stable)
        }
    }
}

fun String.display(): String {
    return this
}

fun main() {
    with(TaskExecutor) {
        execute {
//            val t = createTask {
//                value = Pair(Person("Hello"), Unstable)
//                delay(2000)
//                value = Pair(value!!.first, Stable)
//            }
//
//            val t2 = createTask(Pair(Person("dwa"), Unstable)) {
//                delay(2000)
//                println("Delayed, continuing with ${fullID()}")
//                value = Pair(Person("Radboud"), Stable)
//            } seqNoShare viewPerson("Bla") seqNoShare viewPerson("Hello")
//
//            t.subscribe { v ->
//                println("t: New value: $v")
//            }
//
//            t2.subscribe { v ->
//                println("t2: New value: $v")
//            }
//
//            val t3 = t2 step listOf(
//                OnValue(
//                    ifPred {
//                        pred { v ->
//                            v.isNoValue()
//                        }
//                        then { v ->
//                            createTask {
//                                value = v?.let {
//                                    println("Hello, then has run on value $it")
//                                    Pair("yuh", it.second)
//                                } ?: value
//                            }
//                        }
//                    }
//                ),
//                OnValue(
//                    ifPred {
//                        pred { v ->
//                            v.isStableValue()
//                        }
//                        then { v ->
//                            createTask {
//                                value = v?.let {
//                                    println("Hello, then has run on value $it")
//                                    Pair("yuh", it.second)
//                                } ?: value
//                            }
//                        }
//                    }
//                )
//            )
//
//            t3.subscribe { v -> println("t3: New value: $v") }
//
////            val t4 = createTask {
////                value = Pair("Comp", Stable)
////            } seq { v ->
////                createTask {
////                    value = v?.let {
////                        Pair(it.first + "uting", Stable)
////                    } ?: Pair("uting", Stable)
////                }
////            }
//
//            val t4 = createTask {
//                value = Pair("Comp", Stable)
//            } seqNoShare  viewPerson("Hello")
//
//            t4.subscribe {
//                println("t4: New value: $it")
//            }
//
//            val t5 = t par t2
//
//            t5.subscribe {
//                println("t5: New value: $it")
//            }
//
            val t6 = createTask {
                value = unstableTaskValue("Der")
                delay(1000)
                value = null
                delay(1000)
                value = stableTaskValue("Die")
            }

            val t7 = createTask {
                value = unstableTaskValue("Kip")
                delay(2000)
                value = stableTaskValue("Lip")
            }

            val t8 = t6 parAnd t7
            t8.subscribe {
                println("t8: New value: $it")
            }
//            start(listOf(t8, t, t3, t5, t4))

//            val info = viewInformation(Stable)
            val info = viewStringInformation("Hello")
            start(listOf(info, t8))
        }

        println("Done with execution")

//        val a = createTask {
//            println("Starting a")
//            delay(500)
//            println("Sir")
//            value = unstableTaskValue("Hello")
//        } trans { v ->
//            v?.let {
//                unstableTaskValue(v.first + "boe")
//            }
//        } trans { v ->
//            v?.let {
//                stableTaskValue(v.first + "ha")
//            }
//        }
//
//        a.subscribe { println("Hello $it") }
//
//        start(a)

        runBlocking {
            join()
        }
    }
}
