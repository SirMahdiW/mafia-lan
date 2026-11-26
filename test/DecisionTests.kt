package ir.mafia

import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

class Act(var asId: Int = 0, var t: Any? = null, var tally: Map<String, Int>? = null, var a: String? = null) {
    fun toMessage(): HashMap<String, Any?> {
        val m = HashMap<String, Any?>()
        m["as"] = asId
        if (t != null) m["t"] = t
        if (tally != null) m["tally"] = tally
        if (a != null) m["a"] = a
        return m
    }
}

class Bot(val e: Engine, val stop: AtomicBoolean, var stopNight: Int? = null) {
    var gunGiveT1: Int? = null
    var gunGiveT2: Int? = null
    var gunType: String? = null
    var shootT: Int? = null
    var mafiaT: Int? = null
    var gmWrongT: Int? = null
    var gmRightT: Int? = null
    var gmUsesAfterWrong: Int? = null
    var gmWokeNight2: Boolean = false
    var mafiaKillId: Int? = null
    var doneCheck: (() -> Boolean)? = null
    val unmatched = ArrayList<String>()

    // هدف مافیا: اگر انتخاب از پیش تنظیم‌شده مرده/نامعتبر بود، زندهٔ غیرمافیای بعدی
    private fun mafiaAlive(): Int? {
        val mt = mafiaT
        val pls = engList(e)
        if (mt != null && pls.any { it.id == mt && it.alive && !Roles.isM(it.role) }) return mt
        return pls.firstOrNull { it.alive && !Roles.isM(it.role) }?.id
    }

    // رأی شبانهٔ روز اول (در حالت local از مسیر «prompts» می‌آید)
    private fun tallyAct(id: Int, n: Int, phase: String?): Act {
        val m = HashMap<String, Int>()
        for (pl in engList(e)) if (pl.alive) m[pl.id.toString()] = 0
        val mk = mafiaKillId
        if (n == 1 && phase == "vote" && mk != null) m[mk.toString()] = 99
        return Act(id, null, m, null)
    }

    fun decide(v: Map<String, Any?>): List<Act> {
        val n = (v["night"] as? Number)?.toInt() ?: 0
        val sn = stopNight
        if (sn != null && n >= sn) { stop.set(true); return emptyList() }
        val res = ArrayList<Act>()
        val kind = (v["prompt"] as? Map<String, Any>?)?.get("kind") as? String
        if (kind == "tally") {
            res.add(tallyAct(0, n, v["phase"] as? String))
            return res
        }
        val pm = v["prompts"] as? Map<String, Map<String, Any?>>
        if (pm != null) {
            for ((k, pr) in pm) {
                val id = k.toIntOrNull() ?: continue
                val knd = pr["kind"] as? String
                if (knd == "tally") {
                    res.add(tallyAct(id, n, v["phase"] as? String))
                } else if (knd == "pick") {
                    val title = pr["title"] as? String ?: continue
                    val isGive = title.contains("بدهی")
                    val t: Any? = when {
                        title.contains("نوع تفنگ") -> gunType
                        isGive -> if (title.contains("(1/2)")) gunGiveT1 else gunGiveT2
                        title.contains("شلیک می‌کنی") -> shootT
                        title.contains("قربانی مافیا") -> mafiaAlive()
                        title.contains("خریداری") -> if (n == 1) gmWrongT else gmRightT
                        else -> null
                    }
                    if (t != null || isGive) {
                        if (n == 1 && title.contains("خریداری")) gmUsesAfterWrong = playerByRole(e, "good_man")?.uses
                        if (n == 2 && title.contains("خریداری")) gmWokeNight2 = true
                        res.add(Act(id, t, null, null))
                    } else if (title !in unmatched) {
                        unmatched.add(title)
                        File("bot_unmatched_debug.txt").appendText("night=$n title=$title\n", Charsets.UTF_8)
                    }
                } else if (knd == "cont" || knd == "team" || knd == "info" || knd == "wait" || knd == "reveal" ||
                    knd == "know" || knd == "result" || knd == "vote") {
                    res.add(Act(id, null, null, null))
                }
            }
        }
        return res
    }
}

fun runBot(e: Engine, token: String, stop: AtomicBoolean, bot: Bot): Boolean {
    var guard = 0
    var lastInfo = ""
    while (!stop.get()) {
        guard++
        if (guard > 25000) {
            File("bot_unmatched_debug.txt").writeText("GUARD-EXCEEDED $lastInfo unmatched=${bot.unmatched}\n", Charsets.UTF_8)
            return false
        }
        val v = Json.parseMap(e.view(token)) as? Map<String, Any?>
        if (v == null) { lastInfo = "view unparsable"; Thread.sleep(2); continue }
        lastInfo = "phase=${v["phase"]} night=${v["night"]}"
        if (bot.doneCheck?.invoke() == true) { stop.set(true); return true }
        if (v["phase"] as? String == "end") return true
        if (v["winner"] != null) return true
        val acts = bot.decide(v)
        for (a in acts) {
            e.act(token, a.toMessage())
        }
        if (acts.isEmpty()) {
            val ph = v["phase"] as? String ?: ""
            if (ph == "day") {
                val sp = v["sp"] as? Map<String, Any?>
                val cur = (sp?.get("cur") as? Number)?.toInt()
                if (cur != null && cur >= 0) {
                    val m = HashMap<String, Any?>()
                    m["as"] = 0
                    m["a"] = "next"
                    e.act(token, m)
                }
            }
        }
        Thread.sleep(2)
    }
    return false
}

fun startGame(e: Engine, names: List<String>, cfg: Map<String, Any?>): String {
    val b = LinkedHashMap<String, Any?>()
    b["names"] = names
    b["manual"] = true
    b["twoRound"] = false
    b["cfg"] = cfg
    val r = e.localStart(b) as Map<String, Any?>
    return r["token"] as String
}

fun engList(e: Engine): MutableList<P> {
    val f: Field = Engine::class.java.getDeclaredField("pl")
    f.isAccessible = true
    return f.get(e) as MutableList<P>
}

fun engLog(e: Engine): MutableList<String> {
    val f: Field = Engine::class.java.getDeclaredField("log")
    f.isAccessible = true
    return f.get(e) as MutableList<String>
}

fun playerByRole(e: Engine, role: String): P? = engList(e).firstOrNull { it.role == role }

fun main() {
    // موتور بازی نخ‌های غیر-daemon دارد؛ در پایان یا پس از خطا باید JVM خاموش شود
    Thread.setDefaultUncaughtExceptionHandler { _, ex ->
        ex.printStackTrace()
        kotlin.system.exitProcess(1)
    }
    println("TEST-START")

    // ============ تست سه نفر آخر: قاتل/زودیاک ============
    val e1 = Engine({ emptyList() }, 1.0, 0, 0, 0)
    val pl1 = engList(e1)
    pl1.clear()
    pl1.add(P(1, "t1", "p1").also { it.role = "ghatel"; it.alive = true })
    pl1.add(P(2, "t2", "p2").also { it.role = "mafia"; it.alive = true })
    pl1.add(P(3, "t3", "p3").also { it.role = "mafia"; it.alive = true })
    val wm = Engine::class.java.getDeclaredMethod("win")
    wm.isAccessible = true
    check((wm.invoke(e1) as? String) == "قاتل") { "FAIL 3a" }
    println("TEST-OK: سه نفر آخر - قاتل+۲ مافیا = برد قاتل")

    val e2 = Engine({ emptyList() }, 1.0, 0, 0, 0)
    val pl2 = engList(e2)
    pl2.clear()
    pl2.add(P(1, "t1", "p1").also { it.role = "mafia"; it.alive = true })
    pl2.add(P(2, "t2", "p2").also { it.role = "mafia"; it.alive = true })
    pl2.add(P(3, "t3", "p3").also { it.role = "cit"; it.alive = true })
    check((wm.invoke(e2) as? String) == "مافیا") { "FAIL 3b" }
    println("TEST-OK: پس از مرگ قاتل، تساوی عادی مافیا برقرار شد")

    val e3 = Engine({ emptyList() }, 1.0, 0, 0, 0)
    val pl3 = engList(e3)
    pl3.clear()
    pl3.add(P(1, "t1", "p1").also { it.role = "zoodiak"; it.alive = true })
    pl3.add(P(2, "t2", "p2").also { it.role = "mafia"; it.alive = true })
    pl3.add(P(3, "t3", "p3").also { it.role = "mafia"; it.alive = true })
    check((wm.invoke(e3) as? String) == "زودیاک") { "FAIL 3c" }
    println("TEST-OK: سه نفر آخر - زودیاک+۲ مافیا = برد زودیاک")

    val e4 = Engine({ emptyList() }, 1.0, 0, 0, 0)
    val pl4 = engList(e4)
    pl4.clear()
    pl4.add(P(1, "t1", "p1").also { it.role = "ghatel"; it.alive = true })
    pl4.add(P(2, "t2", "p2").also { it.role = "mafia"; it.alive = true })
    pl4.add(P(3, "t3", "p3").also { it.role = "mafia"; it.alive = true })
    pl4.add(P(4, "t4", "p4").also { it.role = "cit"; it.alive = true })
    check(wm.invoke(e4) == null) { "FAIL 3d" }
    println("TEST-OK: سه نفر آخر - قاتل+۲ مافیا+شهروند ادامه داشت")

    println("ALL-WIN-OK")

    fun gameGunPractice(): Boolean {
        val e = Engine({ emptyList() }, 1.0, 0, 0, 0)
        val token = startGame(e, listOf("a", "b", "c", "d", "e", "f"), linkedMapOf("mafia" to 1, "gun_smith" to true, "arm" to true))
        val bot = Bot(e, AtomicBoolean(false))
        val pls = engList(e)
        val armId = pls.firstOrNull { it.role == "arm" }?.id
        val citIds = pls.filter { it.role == "cit" }.map { it.id }
        bot.gunType = "تفنگ مشقی"
        bot.gunGiveT1 = citIds.firstOrNull()
        bot.gunGiveT2 = citIds.getOrNull(1)
        bot.shootT = armId
        bot.mafiaT = citIds.getOrNull(2)
        bot.doneCheck = { engLog(e).any { it.contains("تفنگ مشقی") } }
        runBot(e, token, bot.stop, bot)
        val arm = engList(e).firstOrNull { it.role == "arm" }
        check(arm != null && arm.alive && arm.armor) { "G1-FAIL: شلیک مشقی بدون اثر؛ زره‌پوش باید سالم بماند" }
        val lg = engLog(e)
        check(lg.any { it.contains("تفنگ مشقی") }) { "G1-FAIL: شلیک مشقی باید بدون اثر ثبت شود" }
        check(playerByRole(e, "gun_smith")?.uses == 0) { "G1-FAIL: تفنگ جنگی نشده" }
        println("TEST-OK: تفنگ مشقی - شلیک مشقی هیچ اثری نداشت و زره‌پوش سالم ماند")
        return true
    }

    fun gameGunWar(): Boolean {
        val e = Engine({ emptyList() }, 1.0, 0, 0, 0)
        val token = startGame(e, listOf("a", "b", "c", "d", "e", "f"), linkedMapOf("mafia" to 1, "gun_smith" to true, "arm" to true))
        val bot = Bot(e, AtomicBoolean(false))
        val pls = engList(e)
        val armId = pls.firstOrNull { it.role == "arm" }?.id
        val citIds = pls.filter { it.role == "cit" }.map { it.id }
        bot.gunType = "تفنگ جنگی"
        bot.gunGiveT1 = citIds.firstOrNull()
        bot.shootT = armId
        bot.mafiaT = citIds.getOrNull(1)
        bot.doneCheck = { playerByRole(e, "gun_smith")?.uses == 1 }
        runBot(e, token, bot.stop, bot)
        val arm = engList(e).firstOrNull { it.role == "arm" }
        check(arm != null && !arm.alive) { "G2-FAIL: تفنگ جنگی باید سر زره را بزند" }
        check(engList(e).firstOrNull { it.role == "gun_smith" }?.uses == 1) { "G2-FAIL: تفنگ جنگی یک‌بار مصرف می‌شود" }
        val lg = engLog(e)
        check(lg.any { it.contains("حذف شد") }) { "G2-FAIL: شلیک جنگی باید اجرا شود" }
        println("TEST-OK: تفنگ جنگی - زره آهنگر را با اثر سرشلیک کرد")
        return true
    }

    fun gameGunReturn(): Boolean {
        val e = Engine({ emptyList() }, 1.0, 0, 0, 0)
        val token = startGame(e, listOf("a", "b", "c", "d", "e", "f"), linkedMapOf("mafia" to 1, "gun_smith" to true, "arm" to true))
        val bot = Bot(e, AtomicBoolean(false))
        val pls = engList(e)
        val citIds = pls.filter { it.role == "cit" }.map { it.id }
        bot.gunType = "تفنگ جنگی"
        bot.gunGiveT1 = citIds.firstOrNull()
        bot.mafiaT = citIds.firstOrNull()
        bot.doneCheck = { engLog(e).any { it.contains("به تفنگدار برگشت") } }
        runBot(e, token, bot.stop, bot)
        val lg = engLog(e)
        check(lg.any { it.contains("به تفنگدار برگشت") }) { "G3-FAIL: تفنگ جنگی باید به تفنگدار برگردد" }
        check(playerByRole(e, "gun_smith")?.uses == 0) { "G3-FAIL: بعد از شلیک نشدن، فرصت به تفنگدار برمی‌گردد" }
        println("TEST-OK: تفنگ جنگی - اگر گیرنده همان شب کشته شود، به تفنگدار برمی‌گردد")
        return true
    }

    fun gameGoodMan(): Boolean {
        val e = Engine({ emptyList() }, 1.0, 0, 0, 0)
        val token = startGame(e, listOf("a", "b", "c", "d", "e", "f", "g"), linkedMapOf("mafia" to 3, "good_man" to true, "arm" to true))
        val bot = Bot(e, AtomicBoolean(false))
        val pls = engList(e)
        val armId = pls.firstOrNull { it.role == "arm" }?.id
        val citIds = pls.filter { it.role == "cit" }.map { it.id }
        val mafIds = pls.filter { it.role == "mafia" }.map { it.id }
        bot.mafiaKillId = mafIds.firstOrNull()
        bot.mafiaT = citIds.getOrNull(1)
        bot.gmWrongT = armId
        bot.gmRightT = citIds.firstOrNull()
        bot.doneCheck = { playerByRole(e, "good_man")?.uses == 1 }
        runBot(e, token, bot.stop, bot)
        val gm = playerByRole(e, "good_man")
        check(gm != null && gm.uses == 1) { "G4-FAIL: گودمن بعد از موفقیت یک‌بار مصرف می‌شود" }
        check(bot.gmUsesAfterWrong == 0 && bot.gmWokeNight2) { "G4-FAIL: جریمه اشتباه فقط شبانه بود و گودمن شب بعد دوباره خریداری کرد" }
        println("TEST-OK: گودمن - انتخاب اشتباه فقط شبانه بود و در شب بعد امکان خریداری")
        return true
    }

    var allOk = true
    allOk = gameGunPractice() && allOk
    allOk = gameGunWar() && allOk
    allOk = gameGunReturn() && allOk
    allOk = gameGoodMan() && allOk
    if (allOk) println("ALL-TESTS-OK") else println("TESTS-FAILED")
    kotlin.system.exitProcess(if (allOk) 0 else 1)
}
