package net.bramw.kotlintopmp

import kotlinx.coroutines.delay
import net.bramw.kotlintopmp.ktop.*
import kotlin.time.Duration.Companion.milliseconds

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
            it?.let { v ->
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

        val parallel = t1 parAnd t2

        val or = t1 parOr t2

        val printer = createTask<Unit> {
            for (event in this.channel) {
                if (event is Event.ValueChanged<*>) {
                    println("task\t${event.taskId}\tvalue changed to ${event.value}")
                }
            }
        }

        parallel.subscribe(printer)
        s.subscribe(printer)
        t3.subscribe(printer)
        t4.subscribe(printer)
        or.subscribe(printer)
        t1.subscribe(printer)
        t2.subscribe(printer)

        start(listOf(s, parallel, t3, or, printer))
    }

    println("Done with taskScope")
}