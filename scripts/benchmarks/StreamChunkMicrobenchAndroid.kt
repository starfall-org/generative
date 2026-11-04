package org.starfall.generative.features.home.chat.logic

import android.os.Debug
import kotlinx.coroutines.*
import org.starfall.generative.features.home.chat.model.*

@Suppress("DEPRECATION")
private fun allocatedBytes() = Debug.getThreadAllocSize().toLong()

private val normal = "This is text. "
private val chunks = List(20_000) { normal }
private val thinkChunks = buildString {
    append("<think>")
    repeat(19_995) { append("Rèasoning. ") }
    append("</think>Answer.")
}.chunked(14)

private data class Sample(val wallMs: Double, val cpuMs: Double, val allocKB: Double)
private fun measured(work: () -> Unit): Sample {
    val beforeCpu = Debug.threadCpuTimeNanos()
    val beforeAlloc = allocatedBytes()
    val start = System.nanoTime()
    work()
    val wall = (System.nanoTime() - start) / 1e6
    val cpu = (Debug.threadCpuTimeNanos() - beforeCpu) / 1e6
    val allocated = allocatedBytes() - beforeAlloc
    return Sample(wall, cpu, allocated / 1024.0)
}
private fun splitterPlain() {
    val splitter = StreamResponseTextSplitter()
    var total = 0
    for (fragment in chunks) total += splitter.append(fragment).content.length
    total += splitter.finish().content.length
    check(total == chunks.size * normal.length)
}
private fun splitterThink() {
    val splitter = StreamResponseTextSplitter()
    var reasoning = 0; var content = 0
    for (fragment in thinkChunks) { val result = splitter.append(fragment); reasoning += result.reasoning.length; content += result.content.length }
    val final = splitter.finish(); reasoning += final.reasoning.length; content += final.content.length
    check(reasoning > 100_000 && content == "Answer.".length)
}
private fun batcherOnly() {
    val batcher = StreamCharBatcher()
    var total = 0
    for ((i,fragment) in chunks.withIndex()) {
        batcher.append(fragment)
        if (i % 5 == 4) total += batcher.takeAllPending()?.text?.length ?: 0
    }
    total += batcher.finishAndTakeRemainder()?.text?.length ?: 0
    check(total == chunks.size * normal.length)
}
private fun displayPaced() {
    var published = 0
    var characters = 0L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val display = ChatStreamDisplay("test", "message", 1, "", "", false, scope,
        publish = { _,state -> published++; characters = state.content.length })
    for (fragment in chunks) display.appendText(fragment)
    display.finish(ChatStreamStatus.COMPLETED)
    scope.cancel()
    check(published in 1..chunks.size && characters == chunks.size * normal.length.toLong())
}
private fun displayFrequentFlush() {
    var published = 0
    var characters = 0L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val display = ChatStreamDisplay("test", "message", 1, "", "", false, scope,
        publish = { _,state -> published++; characters = state.content.length })
    for ((i,fragment) in chunks.withIndex()) {
        display.appendText(fragment)
        if (i % 20 == 19) display.flushPending()
    }
    display.finish(ChatStreamStatus.COMPLETED)
    scope.cancel()
    check(published >= 1000 && characters == chunks.size * normal.length.toLong())
}
private fun snapshotAppendOnly() {
    var snapshot = StreamTextSnapshot.of()
    chunks.forEachIndexed { i, frag -> snapshot = snapshot.append(frag, (i + 1).toLong()) }
    check(snapshot.length == chunks.size * normal.length.toLong())
}

fun main() {
    println("Android direct-source CPU/alloc benchmark / VM=${System.getProperty("java.vm.name")}; normal fragments=${chunks.size}; fragment chars=${normal.length}; source=${chunks.size*normal.length}; think fragments=${thinkChunks.size}")
    @Suppress("DEPRECATION")
    Debug.startAllocCounting()
    val tests = listOf(
       "splitterPlain" to ::splitterPlain,
       "splitterThink" to ::splitterThink,
       "batcherOnly" to ::batcherOnly,
       "snapshotAppendOnly" to ::snapshotAppendOnly,
       "displayPaced" to ::displayPaced,
       "displayFrequentFlush" to ::displayFrequentFlush
    )
    tests.forEach { (name, test) ->
        repeat(3) { test() }
        val samples = (0 until 7).map { measured(test) }
        val byWall = samples.sortedBy { it.wallMs }
        val byCpu = samples.sortedBy { it.cpuMs }
        val byAlloc = samples.sortedBy { it.allocKB }
        fun fmt(v: Double) = "%.2f".format(java.util.Locale.US, v)
        println("$name wall_ms_median=${fmt(byWall[3].wallMs)} cpu_ms_median=${fmt(byCpu[3].cpuMs)} alloc_KB_median=${fmt(byAlloc[3].allocKB)} minMaxWall=${fmt(byWall.first().wallMs)}..${fmt(byWall.last().wallMs)}")
    }
}
