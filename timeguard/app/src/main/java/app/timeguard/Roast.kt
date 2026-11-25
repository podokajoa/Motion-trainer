package app.timeguard

/**
 * 경고 화면의 팩폭 한 줄. 막연한 잔소리 대신 오늘 실제 숫자로 말한다.
 * seed가 같으면 같은 문장이 나와서, 화면이 2초마다 갱신돼도 문장이 바뀌지 않는다.
 */
object Roast {
    fun line(app: String, minutes: Long, snoozeCount: Int, seed: Int): String {
        val t = Rules.fmt(minutes)
        val pages = minutes / 2                      // 보통 책 1쪽에 2분쯤
        val pct = (minutes * 100 / (16 * 60)).coerceAtLeast(1) // 깨어 있는 16시간 기준
        val yearDays = minutes * 365 / (24 * 60)    // 이 속도로 1년이면 며칠
        val list = buildList {
            if (snoozeCount >= 1) add("'5분만'이 오늘 벌써 ${snoozeCount}번째예요.")
            if (pages >= 5) add("그 ${t}이면 책 ${pages}쪽은 읽었어요.")
            add("깨어 있는 시간의 ${pct}%를 ${app}에 줬어요.")
            if (yearDays >= 1) add("이 속도면 1년에 ${yearDays}일을 통째로 ${app}에 써요.")
            add("${t} 동안 달라진 건 배터리뿐이에요.")
            add("이 앱은 당신이 오래 볼수록 돈을 벌어요.")
            add("지금 안 끄면 자기 전에 똑같이 후회해요.")
        }
        return list[Math.floorMod(seed, list.size)]
    }
}
