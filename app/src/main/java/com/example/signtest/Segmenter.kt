package com.example.signtest

/** ตัดช่วง "ยกมือทำท่า" ออกเป็นคลิป — พอร์ตตรรกะมาจาก WordSignRecognizer.handleFrame ของแอป IAQ (ค่าเท่าเดิม) */
class Segmenter {
    class Clip(val frames: List<SignFrame>, val activeFrames: Int, val durationMs: Long, val tooLong: Boolean)

    companion object {
        const val END_GAP_MS = 700L
        const val MAX_SEGMENT_MS = 5000L
        const val LEAD_FRAMES = 3
        const val MAX_FRAMES = 150
    }

    private val frames = ArrayList<SignFrame>()
    var inSign = false; private set
    var activeFrames = 0; private set
    private var segmentStartMs = 0L
    private var lastActiveMs = 0L
    private var needRelease = false

    fun reset() { frames.clear(); inSign = false; activeFrames = 0; needRelease = false }

    fun onFrame(now: Long, f: SignFrame): Clip? {
        val handSeen = f.left != null || f.right != null
        frames.add(f)
        if (handSeen) {
            if (!needRelease) {
                if (!inSign) { inSign = true; segmentStartMs = now; activeFrames = 0 }
                lastActiveMs = now
                activeFrames++
            } else lastActiveMs = now
        }
        if (!inSign) {
            while (frames.size > LEAD_FRAMES) frames.removeAt(0)
            if (needRelease && !handSeen && now - lastActiveMs >= 500L) needRelease = false
        } else if (frames.size > MAX_FRAMES) frames.removeAt(LEAD_FRAMES)

        val timeUp = inSign && now - segmentStartMs >= MAX_SEGMENT_MS
        val handsDown = inSign && !handSeen && now - lastActiveMs >= END_GAP_MS
        if (timeUp || handsDown) {
            val clip = Clip(ArrayList(frames), activeFrames, now - segmentStartMs, timeUp)
            reset()
            if (timeUp) { needRelease = true; lastActiveMs = now }
            return clip
        }
        return null
    }
}
