package be.mygod.reactmap.test

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class RegexPerformanceInstrumentedTest {
    private fun doTest(iterations: Int, input: String, pattern: Pattern, replacement: String): Long {
        val startTime = System.nanoTime()
        repeat(iterations) {
            val matcher = pattern.matcher(input)
            Assert.assertTrue(matcher.find())
            StringBuilder().also {
                matcher.appendReplacement(it, replacement)
                matcher.appendTail(it)
            }.toString()
        }
        return System.nanoTime() - startTime
    }

    @Test
    fun measure() {
        val input = InstrumentationRegistry.getInstrumentation().context.resources.openRawResource(R.raw.vendor)
            .bufferedReader().readText()
        val patterns = arrayOf(
            "([,\\n\\r\\s])this(?=\\.callInitHooks\\(\\)[,;][\\n\\r\\s]*this\\._zoomAnimated\\s*=)"
                .toPattern() to "$1(window._hijackedMap=this)",
            "([,}][\\n\\r\\s]*)this(?=\\.callInitHooks\\(\\)[,;][\\n\\r\\s]*this\\._zoomAnimated\\s*=)"
                .toPattern() to "$1(window._hijackedMap=this)",
            "([,}][\\n\\r\\s]*)this(\\.callInitHooks\\(\\)[,;][\\n\\r\\s]*this\\._zoomAnimated\\s*=)"
                .toPattern() to "$1(window._hijackedMap=this)$2",
        )
        doTest(100, input, patterns[0].first, patterns[0].second)  // warm up
        val iterations = 100
        for ((pattern, replacement) in patterns) {
            val totalTime = doTest(iterations, input, pattern, replacement)
            Log.i("TestResult", "Took: $totalTime ns\nAverage time per operation: ${totalTime * .000_001 / iterations} ms")
        }
    }
}
