package app.timeguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMathTest {
    private val M = 60_000L

    @Test fun sumsSessionsAndSwitches() {
        val a = Accumulator(0)
        a.onEvent(Ev.RESUMED, "yt", "Main", 0)
        a.onEvent(Ev.PAUSED, "yt", "Main", 10 * M)
        a.onEvent(Ev.RESUMED, "ig", "Feed", 10 * M)
        a.onEvent(Ev.RESUMED, "yt", "Main", 15 * M) // 바로 다른 앱으로
        assertEquals(10 * M, a.totals["yt"])
        assertEquals(5 * M, a.totals["ig"])
        assertEquals(20 * M, a.totalNow("yt", 25 * M))
        assertEquals("yt", a.current)
    }

    @Test fun innerScreenChangeKeepsCounting() {
        val a = Accumulator(0)
        a.onEvent(Ev.RESUMED, "yt", "Main", 0)
        a.onEvent(Ev.RESUMED, "yt", "Player", 2 * M) // 같은 앱 새 화면이 먼저 뜸
        a.onEvent(Ev.PAUSED, "yt", "Main", 2 * M)     // 이전 화면 멈춤은 무시
        assertEquals(7 * M, a.totalNow("yt", 7 * M))
    }

    @Test fun screenOffStops() {
        val a = Accumulator(0)
        a.onEvent(Ev.RESUMED, "yt", "Main", 0)
        a.onEvent(Ev.SCREEN_OFF, null, null, 3 * M)
        assertEquals(3 * M, a.totalNow("yt", 60 * M))
        assertNull(a.current)
    }

    @Test fun countsOnlyFromMidnight() {
        val a = Accumulator(100 * M)
        a.onEvent(Ev.RESUMED, "yt", "Main", 90 * M)
        assertEquals(5 * M, a.totalNow("yt", 105 * M))
    }

    @Test fun decideAnnouncesEachStepOnce() {
        assertNull(Rules.decide(9 * M, 30, 10, 0, 0).announceStep)
        assertEquals(1, Rules.decide(10 * M, 30, 10, 0, 0).announceStep)
        assertNull(Rules.decide(12 * M, 30, 10, 0, 1).announceStep)
        assertEquals(2, Rules.decide(21 * M, 30, 10, 0, 1).announceStep)
    }

    @Test fun decideBlocksAtLimitAndSnooze() {
        assertFalse(Rules.decide(29 * M, 30, 10, 0, 2).block)
        val d = Rules.decide(32 * M, 30, 10, 0, 2)
        assertTrue(d.block)
        assertNull(d.announceStep)
        val s = Rules.snoozeAfter(32, 30)
        assertEquals(7, s)
        assertFalse(Rules.decide(36 * M, 30, 10, s, 3).block)
        assertTrue(Rules.decide(37 * M, 30, 10, s, 3).block)
        assertFalse(Rules.decide(500 * M, 0, 10, 0, 0).block) // 제한 없음
    }

    @Test fun levelsAndFormat() {
        assertEquals(Level.OK, Rules.level(10, 30))
        assertEquals(Level.NEAR, Rules.level(24, 30))
        assertEquals(Level.OVER, Rules.level(30, 30))
        assertEquals("45분", Rules.fmt(45))
        assertEquals("1시간", Rules.fmt(60))
        assertEquals("1시간 12분", Rules.fmt(72))
    }
}

class RoastTest {
    @Test fun usesRealNumbersAndIsStable() {
        val all = (0 until 20).map { Roast.line("유튜브", 72, 2, it) }.toSet()
        assertTrue(all.contains("'5분만'이 오늘 벌써 2번째예요."))
        assertTrue(all.contains("그 1시간 12분이면 책 36쪽은 읽었어요."))
        assertTrue(all.contains("깨어 있는 시간의 7%를 유튜브에 줬어요."))
        assertTrue(all.contains("이 속도면 1년에 18일을 통째로 유튜브에 써요."))
        assertEquals(Roast.line("유튜브", 72, 2, 5), Roast.line("유튜브", 72, 2, 5))
        // 숫자가 작으면 어색한 문장은 빠진다
        val small = (0 until 20).map { Roast.line("인스타", 3, 0, it) }
        assertFalse(small.any { it.contains("쪽") || it.contains("5분만") || it.contains("1년") })
    }
}

class PenaltyTest {
    @Test fun blackoutEverySixSeconds() {
        assertFalse(Rules.blackoutDue(5_999, 0))
        assertTrue(Rules.blackoutDue(6_000, 0))
        // 1분에 10번
        var last = -6_000L; var n = 0
        for (t in 0L..59_999L step 2_000L) if (Rules.blackoutDue(t, last)) { last = t; n++ }
        assertEquals(10, n)
    }
}
