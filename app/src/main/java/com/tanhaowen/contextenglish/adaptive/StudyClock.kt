package com.tanhaowen.contextenglish.adaptive

/** Counts foreground answering/reading time, excluding background and feedback. */
class StudyClock(private val nanos: () -> Long = System::nanoTime) {
    private var accumulated=0L
    private var entered: Long?=null
    @Synchronized fun restore(millis: Long) { accumulated=millis.coerceAtLeast(0); entered=null }
    @Synchronized fun resume() { if(entered==null) entered=nanos() }
    @Synchronized fun elapsed(): Long = accumulated + (entered?.let { (nanos()-it).coerceAtLeast(0)/1_000_000 } ?: 0)
    @Synchronized fun pause(): Long { accumulated=elapsed(); entered=null; return accumulated }
    @Synchronized fun reset(run: Boolean) { restore(0); if(run) resume() }
}
