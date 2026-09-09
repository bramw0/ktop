package net.bramw.kotlintopmp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.bramw.kotlintopmp.ktop.*

data class Person(val fname: String, val lname: String) : Display {
    override fun display(): String {
        return "My name is $fname $lname"
    }
}

fun main() {
    with(TaskExecutor) {
        execute {
            val t6 = createTask {
                value = unstableValue("Der")
                delay(1000)
                value = null
                delay(1000)
                value = stableValue("Die")
            }

            val t7 = createTask {
                value = unstableValue("Kip")
                delay(2000)
                value = stableValue("Lip")
            }

            val t8 = t6 parAnd t7
            t8.subscribe {
                println("t8: New value: $it")
            }
            val p = Person("Bram", "Weessies")

            val info = viewInformation(p) step listOf(
                Continuation.OnAction(
                    UserAction.Continue,
                    ifPred {
                        pred {
                            it.hasValue()
                        }
                        then { v ->
                            v?.let {
                                viewInformation(it.first.copy(lname = "Weeshuis"))
                            } ?: run {
                                viewInformation(p)
                            }
                        }
                    }
                )
            )

            val text = updateInformation("Hey")
            val l = updateInformation(listOf("Je", "moeder"))

            start(listOf(info, t8, text, l))
        }

        println("Done with execution")

        runBlocking {
            join()
        }
    }
}