package ir.mafia

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

typealias M = Map<String, Any?>

class P(val id: Int, val token: String, val name: String) {
    @Volatile var role = ""
    @Volatile var alive = true
    @Volatile var seen = false
    var self = false
    var shot = false
    var armor = false
    var smith = false          // زرهِ آهنگر (در روز مقابل تروریست محافظت می‌کند)
    var slaughtered = false    // سلاخی‌شده (برگشت‌ناپذیر)
    var uses = 0
    var lastT = -1
    @Volatile var host = false
}

class Pend(val who: Set<Int>, val vf: (P) -> M, val hd: (P, M) -> Boolean) {
    val latch = CountDownLatch(1)
}

object Roles {
    val names = mapOf(
        "cit" to "شهروند ساده", "doctor" to "دکتر", "karagah" to "کارآگاه", "karagah_vije" to "پوآرو",
        "sniper" to "تیرانداز", "kalantar" to "حرفه‌ای", "black_smith" to "آهنگر", "arm" to "زره‌پوش",
        "mohafez" to "محافظ", "angel" to "فرشته نجات", "keshish" to "کشیش", "freemason" to "فراماسون",
        "constantine" to "کنستانتین", "gun_smith" to "تفنگدار", "hamshhri_kein" to "همشهری کین", "mayor" to "شهردار",
        "joker" to "جوکر", "ghatel" to "قاتل", "zoodiak" to "زودیاک", "sherlock" to "شرلوک", "jack" to "جک گنجشکه",
        "nostradamos" to "نوستراداموس", "mafia" to "مافیا ساده", "raees_mafia" to "پدرخوانده", "doctor_lecter" to "دکتر لکتر",
        "spy" to "جاسوس", "terrorist" to "تروریست", "bomber" to "بمب‌گذار", "good_man" to "گودمن", "vakil" to "وکیل",
        "matador" to "ماتادور", "blocker" to "بلاکر", "natasha" to "ناتاشا", "sharlatan" to "شارلاتان"
    )
    val mafiaTeam = setOf(
        "mafia", "raees_mafia", "doctor_lecter", "spy", "terrorist", "bomber", "good_man", "vakil",
        "matador", "blocker", "natasha", "sharlatan"
    )
    val independentTeam = setOf("joker", "ghatel", "zoodiak", "sherlock", "jack", "nostradamos")
    fun isM(r: String) = mafiaTeam.contains(r)
    fun isIndie(r: String) = independentTeam.contains(r)
    fun teamOf(r: String) = if (mafiaTeam.contains(r)) "mafia" else if (independentTeam.contains(r)) "independent" else "citizen"
}

class Sc(val n: String, val d: String, val min: Int, val max: Int, val ideal: Int, val b: (Int) -> List<String>)

private fun fill(a: MutableList<String>, n: Int): List<String> { while (a.size < n) a.add("cit"); return a }

val SC = listOf(
    Sc("کلاسیک", "ساده و سریع", 5, 9, 7) { n ->
        val a = mutableListOf<String>(); repeat(if (n >= 7) 2 else 1) { a.add("mafia") }
        a.add("doctor"); a.add("karagah"); fill(a, n)
    },
    Sc("پدرخوانده", "کارآگاه، پدرخوانده را شهروند می‌بیند؛ زره‌پوش یک شلیک را تاب می‌آورد", 8, 15, 11) { n ->
        val m = if (n <= 9) 2 else if (n <= 13) 3 else 4
        val a = mutableListOf("raees_mafia"); repeat(m - 1) { a.add("mafia") }
        a.add("doctor"); a.add("karagah"); a.add("arm"); fill(a, n)
    },
    Sc("حرفه‌ای", "همه نقش‌ها + تک‌تیرانداز", 10, 20, 20) { n ->
        val a = mutableListOf("raees_mafia"); repeat(n / 3 - 1) { a.add("mafia") }
        a.add("doctor"); a.add("karagah"); a.add("arm"); a.add("sniper"); fill(a, n)
    },
    Sc("تست", "فقط برای آزمایش: ۲ تا ۴ نفر؛ بازی تا مرگ یک طرف ادامه دارد", 2, 4, 2) { n ->
        mutableListOf("mafia", "doctor", "karagah", "sniper").take(n)
    }
)

fun comp(a: List<String>) = a.groupingBy { it }.eachCount().entries.joinToString("، ") { "${Roles.names[it.key]} ×${it.value}" }

class Engine(
    val ips: () -> List<String>,
    private val speed: Double = 1.0,
    private val dayMs: Long = 45000,
    private val nightMs: Long = 30000,
    private val voteMs: Long = 60000
) {
    private val lock = Any()
    var onChange: () -> Unit = {}
    var onReset: () -> Unit = {}
    @Volatile var phase = "lobby"
    private val pl = ArrayList<P>()
    private var saying: M? = null
    private var sn = 0
    @Volatile private var pend: Pend? = null
    @Volatile private var endsAt = 0L
    private val log = ArrayList<String>()
    private var night = 1
    @Volatile var winner: String? = null
    @Volatile private var test = false
    @Volatile private var revealRole = true
    @Volatile private var showVotes = false
    @Volatile private var docNoRepeat = true
    @Volatile private var manual = false
    @Volatile private var voice = false
    @Volatile private var twoRound = true
    @Volatile private var defense = false
    @Volatile private var paused = false
    @Volatile private var speaker = -1
    @Volatile private var turn: CountDownLatch? = null
    @Volatile private var endDay = false
    @Volatile private var speakMs = dayMs
    private var dayNo = 0
    @Volatile private var local = false
    @Volatile private var localToken: String? = null
    private val ctl = P(0, "", "گرداننده").also { it.host = true }
    @Volatile private var silenced: Int? = null
    private val queue = ArrayList<Int>()
    private val spoken = ArrayList<Int>()
    // وضعیت نقش‌های مرجع (هر بازی از نو پر می‌شود)
    private val blockedIds = HashSet<Int>()      // بلاک ماتادور/بلاکر در همین شب
    private var saveActor: Int? = null           // دکتر
    private var bgActor: Int? = null             // محافظ
    private var lecActor: Int? = null            // دکتر لکتر
    private var natActor: Int? = null            // ناتاشا
    private var shaActor: Int? = null            // شارلاتان
    private var manActor: Int? = null            // قاتل
    private var bsActor: Int? = null             // آهنگر
    private var hamActor: Int? = null            // همشهری کین
    private var vakActor: Int? = null            // وکیل
    private var bsTarget: Int? = null            // گیرندهٔ زره آهنگر این شب
    private var hamPick: Int? = null             // سوژهٔ همشهری کین
    private var hamKill = false                  // همشهری کین شب بعد بمیرد
    private var vakilClient: Int? = null         // موکل وکیل (امروز از رأی خارج است)
    private var gunTo: Int? = null               // صاحب تفنگ
    private var bombT: Int? = null               // صندلی بمباری‌شده
    private var bombCode = -1                    // رمز خنثی‌سازی ۱..۴
    private var keshishBonus: Int? = null        // زمان اضافهٔ صحبت برای سوژهٔ کشیش
    private var masonDeath = false               // مرگ ماسونی این شب
    private val mason = HashSet<Int>()           // اعضای جذب‌شدهٔ تیم ماسون
    private var initM = 1                        // تعداد اولیهٔ مافیا
    private var jackT: Int? = null               // شیشهٔ عمر جک
    private var hamKillT: Int? = null            // همشهری کین شب بعد بمیرد (اعلام‌شده)
    private var noSide = "c"                     // پیش‌بینی نوستراداموس: m یا c

    private val MSF = listOf(
        "mafia", "raees_mafia", "doctor_lecter", "spy", "terrorist", "bomber", "good_man", "vakil",
        "matador", "blocker", "natasha", "sharlatan"
    )
    private val CSF = listOf(
        "cit", "doctor", "karagah", "karagah_vije", "sniper", "kalantar", "black_smith", "arm", "mohafez",
        "angel", "keshish", "freemason", "constantine", "gun_smith", "hamshhri_kein", "mayor"
    )
    private val INDEP = listOf("joker", "ghatel", "zoodiak", "sherlock", "jack", "nostradamos")

    private fun cfgRoles(c: M, n: Int): List<String>? {
        val mc = (c["mafia"] as? Number)?.toInt() ?: return null
        val l = ArrayList<String>()
        for (k in MSF) if (c[k] == true) l.add(k)
        if (l.size > mc) return null
        while (l.size < mc) l.add("mafia")
        for (k in CSF) if (k != "freemason" && c[k] == true) l.add(k)
        for (k in INDEP) if (c[k] == true) l.add(k)
        // فراماسون همیشه دو نفر است (کلید «freemason» و نام قدیمی «fm» هر دو پذیرفته می‌شوند)
        if (c["freemason"] == true || c["fm"] == true) { l.add("freemason"); l.add("freemason") }
        if (mc < 1 || l.size > n || mc * 2 >= n) return null
        return fill(l, n)
    }

    private fun begin(m: M): Boolean {
        var rs: List<String>? = null
        val cfg = m["cfg"] as? Map<*, *>
        if (cfg != null) {
            @Suppress("UNCHECKED_CAST") val cm = cfg as Map<String, Any?>
            rs = cfgRoles(cm, pl.size)?.shuffled()
            revealRole = (cm["reveal"] as? Boolean) ?: true
            showVotes = (cm["votes"] as? Boolean) ?: false
            docNoRepeat = (cm["docRule"] as? Boolean) ?: true
            (cm["speak"] as? Number)?.let { speakMs = it.toLong().coerceIn(10L, 300L) * 1000 }
            test = false
        } else {
            val sc = SC.getOrNull((m["sc"] as? Number)?.toInt() ?: -1)
            if (sc != null && pl.size in sc.min..sc.max) { rs = sc.b(pl.size).shuffled(); test = sc.n == "تست"; revealRole = true; showVotes = false; docNoRepeat = true }
        }
        val roles = rs ?: return false
        blockedIds.clear(); mason.clear(); masonDeath = false
        saveActor = null; bgActor = null; lecActor = null; natActor = null; shaActor = null
        manActor = null; bsActor = null; hamActor = null; vakActor = null
        bsTarget = null; hamPick = null; hamKill = false; vakilClient = null
        gunTo = null; bombT = null; bombCode = -1; keshishBonus = null; jackT = null
        hamKillT = null; noSide = "c"
        silenced = null
        initM = roles.count { Roles.isM(it) }
        // زره: زره‌پوش از ابتدا زره دارد؛ پدرخوانده، قاتل و زودیاک هم زره شبانه دارند
        pl.forEachIndexed { i, q ->
            q.role = roles[i]
            q.uses = 0; q.self = false; q.shot = false; q.lastT = -1; q.seen = false
            q.slaughtered = false; q.smith = false
            q.armor = roles[i] == "arm" || roles[i] == "raees_mafia" || roles[i] == "ghatel" || roles[i] == "zoodiak"
        }
        phase = "reveal"
        Thread { run() }.start()
        return true
    }

    fun localStart(b: M): M = synchronized(lock) {
        if (phase != "lobby") return@synchronized mapOf("err" to "بازی در جریان است")
        val n = (b["n"] as? Number)?.toInt() ?: (b["names"] as? List<*>)?.size ?: 0
        if (n < 3 || n > 30) return@synchronized mapOf("err" to "تعداد بازیکن نامعتبر است")
        pl.clear()
        val given = (b["names"] as? List<*>)?.map { (it as? String ?: "").trim().take(16) } ?: emptyList()
        val used = HashSet<String>()
        for (i in 1..n) {
            var nm = given.getOrNull(i - 1)?.ifEmpty { null } ?: "بازیکن $i"
            while (!used.add(nm)) nm += "'"
            pl.add(P(i, java.util.UUID.randomUUID().toString(), nm))
        }
        manual = (b["manual"] as? Boolean) ?: false
        voice = (b["voice"] as? Boolean) ?: !manual
        twoRound = (b["twoRound"] as? Boolean) ?: true
        local = true
        val tk = java.util.UUID.randomUUID().toString()
        localToken = tk
        if (!begin(b)) { pl.clear(); local = false; localToken = null; return@synchronized mapOf("err" to "ترکیب نقش‌ها معتبر نیست") }
        push()
        mapOf("token" to tk)
    }

    private fun push() = onChange()
    private fun nap(ms: Long) {
        if (manual) return
        var left = (ms * speed).toLong()
        while (left > 0) { val c = minOf(100L, left); Thread.sleep(c); if (!paused) left -= c }
    }

    private fun awaitL(latch: CountDownLatch, ms: Long) {
        if (ms <= 0) { latch.await(); return }
        var last = System.currentTimeMillis()
        while (true) {
            if (latch.await(100, TimeUnit.MILLISECONDS)) return
            val now = System.currentTimeMillis()
            if (paused) endsAt += now - last else if (now >= endsAt) return
            last = now
        }
    }
    private fun p(t: String?): P? = pl.firstOrNull { it.token == t }
    private fun byId(x: Any?): P? { val i = (x as? Number)?.toInt() ?: return null; return pl.firstOrNull { it.id == i } }
    private fun alive() = pl.filter { it.alive }

    private fun scen(): List<M> {
        val n = pl.size
        return SC.withIndex().filter { n in it.value.min..it.value.max }
            .sortedBy { Math.abs(it.value.ideal - n) }
            .map { mapOf("i" to it.index, "n" to it.value.n, "d" to it.value.d, "c" to comp(it.value.b(n))) }
    }

    private fun viewLocal(): String {
        val pd = pend
        val prompts = LinkedHashMap<String, Any?>()
        if (pd != null) for (id in pd.who) { val q = if (id == 0) ctl else byId(id); if (q != null) prompts[id.toString()] = pd.vf(q) }
        return Json.write(mapOf(
            "local" to true, "voice" to voice, "manual" to manual, "defense" to defense, "phase" to phase, "say" to saying, "night" to night, "winner" to winner, "log" to log.takeLast(5),
            "rem" to (if (endsAt > 0) maxOf(0L, endsAt - System.currentTimeMillis()) else 0L), "paused" to paused, "speakSec" to speakMs / 1000,
            "sp" to (if (phase == "day") mapOf("cur" to speaker, "q" to queue.toList(), "done" to spoken.toList()) else null),
            "players" to pl.map { mapOf("id" to it.id, "name" to it.name, "alive" to it.alive, "seen" to it.seen, "role" to it.role, "rn" to Roles.names[it.role], "m" to (if (Roles.isM(it.role)) 1 else 0)) },
            "prompts" to prompts
        ))
    }

    fun view(t: String?): String = synchronized(lock) {
        if (local && t != null && t == localToken) return@synchronized viewLocal()
        val me = p(t)
        val end = phase == "end"
        val host = me?.host == true && phase == "lobby"
        val pd = pend
        Json.write(mapOf(
            "phase" to phase, "say" to saying, "rem" to (if (endsAt > 0) maxOf(0L, endsAt - System.currentTimeMillis()) else 0L), "paused" to paused, "speakSec" to speakMs / 1000,
            "sp" to (if (phase == "day") mapOf("cur" to speaker, "q" to queue.toList(), "done" to spoken.toList()) else null), "night" to night, "winner" to winner,
            "log" to log.takeLast(5),
            "me" to me?.let { mapOf("id" to it.id, "name" to it.name, "role" to it.role, "rn" to Roles.names[it.role], "alive" to it.alive, "host" to it.host, "m" to (if (Roles.isM(it.role)) 1 else 0)) },
            "players" to pl.map { mapOf("id" to it.id, "name" to it.name, "alive" to it.alive, "seen" to it.seen, "rn" to (if (end) Roles.names[it.role] else null)) },
            "prompt" to (if (me != null && pd != null && pd.who.contains(me.id)) pd.vf(me) else null),
            "sc" to (if (host) scen() else null),
            "urls" to (if (host) ips() else null)
        ))
    }

    fun join(b: M, loop: Boolean): M = synchronized(lock) {
        var me = p(b["token"] as? String)
        if (me == null) {
            val name = ((b["name"] as? String) ?: "").trim().take(16)
            if (phase != "lobby" || name.isEmpty() || pl.size >= 20 || pl.any { it.name == name })
                return@synchronized mapOf("err" to (if (phase != "lobby") "بازی شروع شده است" else "نام نامعتبر یا تکراری است"))
            me = P(pl.size + 1, java.util.UUID.randomUUID().toString(), name)
            pl.add(me)
        }
        if (loop || pl.none { it.host }) {
            if (loop) pl.forEach { it.host = false }
            me.host = true
        }
        push()
        mapOf("token" to me.token)
    }

    fun act(t: String?, m: M) {
        synchronized(lock) {
            val me: P = (if (local && t != null && t == localToken) (byId(m["as"]) ?: ctl) else p(t)) ?: return
            val a = m["a"] as? String
            if (paused && a != "resume") return
            if (a == "start" && me.host && phase == "lobby" && pl.size >= 2) {
                begin(m)
            } else if (a == "pause" && me.host && phase != "lobby" && phase != "end") {
                paused = true; push()
            } else if (a == "resume" && me.host) {
                paused = false; push()
            } else if (a == "next" && phase == "day" && (me.host || me.id == speaker)) {
                turn?.countDown()
            } else if (a == "add" && me.host && phase == "day" && endsAt > 0) {
                endsAt += 15000; push()
            } else if (a == "jump" && me.host && phase == "day") {
                val id = (m["t"] as? Number)?.toInt()
                if (id != null && queue.remove(id)) { queue.add(0, id); turn?.countDown() }
            } else if (a == "skip" && me.host && phase == "day") {
                endDay = true; turn?.countDown()
            } else if (a == "toggle" && me.host && local) {
                byId(m["t"])?.let { it.alive = !it.alive }; push()
            } else if (a == "reset" && me.host && phase == "end") {
                paused = false; manual = false; voice = false; defense = false; local = false; localToken = null; silenced = null; pl.clear(); log.clear(); saying = null; winner = null; night = 1; pend = null; phase = "lobby"
                onReset()
            } else {
                val pd = pend
                if (pd != null && pd.who.contains(me.id)) {
                    if (pd.hd(me, m)) pd.latch.countDown() else push()
                }
            }
            Unit
        }
    }

    private fun ask(who: List<Int>, vf: (P) -> M, hd: (P, M) -> Boolean, ms: Long) {
        val pd = Pend(who.toSet(), vf, hd)
        synchronized(lock) { pend = pd; endsAt = if (ms > 0) System.currentTimeMillis() + ms else 0 }
        push()
        awaitL(pd.latch, ms)
        synchronized(lock) { pend = null; endsAt = 0 }
        push()
    }

    private fun askCont(text: String) = ask(listOf(0), { _ -> mapOf("kind" to "cont", "text" to text) }, { _, _ -> true }, 0)

    private fun say(k: Int, t: String, wait: Boolean = true) {
        synchronized(lock) { saying = mapOf("k" to k, "t" to t, "n" to ++sn) }
        push()
        if (wait) { if (manual) askCont(t) else nap(1800 + t.length * 70L) }
    }

    private fun wakeRole(kw: Int, ks: Int, name: String, who: List<P>, vf: (P) -> M, hd: (P, M) -> Boolean, ms: Long) {
        say(kw, "$name بیدار شود.")
        if (who.isEmpty()) { if (manual) askCont("(این نقش در بازی نیست یا مرده است؛ برای پنهان‌ماندن چند لحظه صبر کن و بعد ادامه بده)") else nap(6000 + (Math.random() * 3000).toLong()) }
        else ask(who.map { it.id }, vf, hd, if (manual) 0L else ms)
        say(ks, "$name بخوابد.")
        nap(1200)
    }

    private fun win(): String? {
        val a = alive()
        val m = a.count { Roles.isM(it.role) }
        val c = a.size - m
        if (test) return if (m == 0) "شهروندان" else if (c == 0) "مافیا" else null
        // ۱) تساوی با مافیا → برد مافیا
        if (m > 0 && m >= c) return "مافیا"
        // ۲) مافیا تمام شد و جک زنده است → برد جک
        if (m == 0 && a.any { it.role == "jack" }) return "جک"
        // ۳) سه نفر آخر: نقش‌های مستقل تنها برنده می‌شوند
        if (a.size <= 3) {
            if (a.any { it.role == "ghatel" }) return "قاتل"
            if (a.any { it.role == "zoodiak" }) return "زودیاک"
            if (a.any { it.role == "sherlock" }) return "شرلوک"
            if (a.any { it.role == "jack" }) return "جک"
        }
        // ۴) مافیا تمام شد و مستقلی باقی نمانده → برد شهروندان (دلقک فقط با رأی خارج می‌شود)
        if (m == 0 && a.none { it.role in INDEP }) return "شهروندان"
        return null
    }

    private fun finish(w: String) { synchronized(lock) { winner = w; phase = "end" }; push() }

    private fun reveal() {
        phase = "reveal"
        ask(pl.map { it.id }, { q -> if (q.seen) mapOf("kind" to "wait") else mapOf("kind" to "reveal") },
            { q, _ -> q.seen = true; pl.all { it.seen } }, 0)
    }

    private fun intro() {
        phase = "intro"
        say(2, "شب معارفه. همه چشم‌های خود را ببندند.")
        nap(1200)
        val mf = pl.filter { Roles.isM(it.role) }
        val ack = HashSet<Int>()
        wakeRole(8, 9, "مافیا", mf,
            { _ -> mapOf("kind" to "team", "team" to mf.map { listOf(it.name, Roles.names[it.role]) }) },
            { q, _ -> ack.add(q.id); ack.size >= (if (local) 1 else mf.size) }, 0)
        val fms = pl.filter { it.role == "freemason" }
        if (fms.isNotEmpty()) {
            val ack2 = HashSet<Int>()
            wakeRole(0, 0, "فراماسون", fms, { _ -> mapOf("kind" to "team", "team" to fms.map { listOf(it.name, "فراماسون") }) },
                { q, _ -> ack2.add(q.id); ack2.size >= (if (local) 1 else fms.size) }, 0)
        }
        // نوستراداموس: سه بازیکن را نشان می‌دهد، تعداد مافیا را می‌گیرد و سایدش را پیش‌بینی می‌کند
        for (n in pl.filter { it.role == "nostradamos" }) {
            say(0, "نوستراداموس بیدار شود.")
            val picks = ArrayList<Int>()
            repeat(3) {
                var p: Int? = null
                ask(listOf(n.id),
                    { _ -> mapOf("kind" to "pick", "title" to "یکی از بازیکنان را انتخاب کن (${picks.size + 1}/3)",
                        "opts" to alive().filter { !picks.contains(it.id) }.map { it.id }) },
                    { _, m -> val x = byId(m["t"]); if (x != null && x.alive && !picks.contains(x.id)) { p = x.id; picks.add(x.id) }; true },
                    if (manual) 0L else nightMs)
                if (p == null) { alive().firstOrNull { !picks.contains(it.id) }?.let { picks.add(it.id) } }
            }
            val mc = picks.count { id -> byId(id)?.let { Roles.isM(it.role) } == true }
            ask(listOf(n.id), { _ -> mapOf("kind" to "info", "text" to "تعداد مافیا بین سه نفر: $mc") }, { _, _ -> true }, if (manual) 0L else nightMs)
            if (mc >= 2) {
                noSide = "m"
                ask(listOf(n.id), { _ -> mapOf("kind" to "info", "text" to "با توجه به تعداد مافیا، باید با ساید مافیا وارد بازی شوی.") }, { _, _ -> true }, if (manual) 0L else nightMs)
            } else {
                var s = ""
                ask(listOf(n.id),
                    { _ -> mapOf("kind" to "pick", "title" to "کدام تیم برنده می‌شود؟", "opts" to listOf("شهروندان", "مافیا")) },
                    { _, m -> s = (m["t"] as? String) ?: ""; true },
                    if (manual) 0L else nightMs)
                noSide = if (s == "مافیا") "m" else "c"
            }
            synchronized(lock) { log.add("نوستراداموس ساید ${if (noSide == "m") "مافیا" else "شهروندان"} را پیش‌بینی کرد.") }
            say(0, "نوستراداموس بخوابد.")
            nap(1200)
        }
        for ((r, kw, ks) in listOf(Triple("doctor", 10, 11), Triple("karagah", 12, 13), Triple("sniper", 14, 15), Triple("kalantar", 16, 17), Triple("arm", 18, 19), Triple("mohafez", 20, 21), Triple("angel", 22, 23), Triple("keshish", 24, 25), Triple("gun_smith", 26, 27), Triple("hamshhri_kein", 28, 29))) {
            val h = pl.filter { it.role == r }
            if (h.isNotEmpty()) wakeRole(kw, ks, Roles.names[r] ?: r, h, { _ -> mapOf("kind" to "know") }, { _, _ -> true }, 0)
        }
    }

    // استعلام کارآگاه: مافیا مثبت (به‌جز پدرخوانده که منفی است)، جاسوس مثبت؛
    // شارلاتان نتیجهٔ استعلام هدفش را برعکس می‌کند.
    private fun looksMafia(x: P, charl: Map<Int, Boolean>): Boolean {
        return charl[x.id] ?: (Roles.isM(x.role) && x.role != "raees_mafia")
    }

    private fun night() {
        phase = "night"; push()
        say(1, "شب شد. همه چشم‌های خود را ببندند.")
        nap(1500)
        // پاک‌سازی وضعیت یک‌شبه
        blockedIds.clear(); saveActor = null; bgActor = null; lecActor = null
        natActor = null; shaActor = null; manActor = null; bsActor = null
        hamActor = null; vakActor = null; bsTarget = null; hamPick = null
        masonDeath = false; vakilClient = null
        val dawn = ArrayList<String>()          // پیام‌های اعلام‌شده در صبح
        var save: Int? = null
        var sniperT = -1
        var sniperA: Int? = null
        var kalBy = -1
        var kalT = -1
        var bgT: Int? = null
        var lecSave: Int? = null
        var zooT: Int? = null
        var zooBy = -1
        var zooReflect = false                  // زودیاک به زره‌پوش/محافظ زد → خودش می‌میرد
        val charl = HashMap<Int, Boolean>()     // شارلاتان: نتیجهٔ استعلام هدف را برعکس می‌کند
        val visits = HashMap<Int, MutableList<Int>>()
        fun visit(a: P, t: P) { visits.getOrPut(t.id) { ArrayList() }.add(a.id) }

        // ---- شرلوک: پیش از مافیا بیدار می‌شود و نقش یک بازیکن را حدس می‌زند ----
        for (sl in pl.filter { it.role == "sherlock" && it.alive }) {
            say(0, "شرلوک بیدار شود.")
            var st: Int? = null
            var guess: String? = null
            ask(listOf(sl.id),
                { _ -> mapOf("kind" to "pick", "title" to "نقش چه کسی را حدس می‌زنی؟", "skip" to 1,
                    "opts" to alive().filter { it != sl }.map { it.id }) },
                { _, m -> val x = byId(m["t"]); if (x != null && x.alive && x != sl) st = x.id; true },
                if (manual) 0L else nightMs)
            if (st != null) {
                val opts = alive().mapNotNull { Roles.names[it.role] }.distinct()
                ask(listOf(sl.id),
                    { _ -> mapOf("kind" to "pick", "title" to "نقش او چیست؟", "skip" to 1, "opts" to opts) },
                    { _, m -> guess = m["t"] as? String; true },
                    if (manual) 0L else nightMs)
                val tg = byId(st)
                val g = guess
                if (tg != null && tg.alive && g != null && g == Roles.names[tg.role]) {
                    // حدس درست: شرلوک نقش او را می‌گیرد و او سلاخی می‌شود (برگشت‌ناپذیر)
                    val old = tg.role
                    sl.role = old
                    tg.alive = false
                    tg.slaughtered = true
                    dawn.add("${tg.name} سلاخی شد.")
                }
            }
            say(0, "شرلوک بخوابد.")
            nap(1200)
        }

        val mf = alive().filter { Roles.isM(it.role) }
        var converted = false
        // ---- گودمن: به‌جای شات، یک شهروند ساده را یک‌بار به مافیا تبدیل می‌کند ----
        val gm = mf.firstOrNull { it.role == "good_man" }
        if (gm != null && gm.uses == 0 && (mf.size < initM || mf.size == 1)) {
            val cits = alive().filter { it.role == "cit" }
            if (cits.isNotEmpty()) {
                say(0, "گودمن بیدار شود.")
                var pick: Int? = null
                ask(listOf(gm.id),
                    { _ -> mapOf("kind" to "pick", "title" to "یک شهروند ساده را به مافیا تبدیل کن",
                        "skip" to 1, "opts" to cits.map { it.id }) },
                    { _, m -> val x = byId(m["t"]); pick = if (x != null && x.alive && x.role == "cit") x.id else null; true },
                    if (manual) 0L else nightMs)
                val p = pick
                if (p != null) {
                    val x = byId(p)!!
                    x.role = "mafia"
                    gm.uses = 1
                    converted = true
                    ask(listOf(x.id),
                        { _ -> mapOf("kind" to "team", "team" to alive().filter { Roles.isM(it.role) }.map { listOf(it.name, Roles.names[it.role] ?: "") }) },
                        { _, _ -> true }, if (manual) 0L else nightMs)
                }
                say(0, "گودمن بخوابد.")
                nap(1200)
            }
        }

        val votes = LinkedHashMap<Int, Int>()
        var tgt: Int? = null
        if (!converted) {
            wakeRole(8, 9, "مافیا", mf,
                { _ -> mapOf("kind" to "pick", "title" to "قربانی مافیا را انتخاب کنید",
                    "opts" to alive().filter { !Roles.isM(it.role) }.map { it.id },
                    "votes" to votes.entries.map { listOf(byId(it.key)?.name, byId(it.value)?.name) }) },
                { q, m -> val x = byId(m["t"]); if (x != null && x.alive && !Roles.isM(x.role)) { votes[q.id] = x.id; visit(q, x) }; votes.size >= (if (local) 1 else mf.size) }, nightMs)
            val g = mf.firstOrNull { it.role == "raees_mafia" }
            val cnt = votes.values.groupingBy { it }.eachCount()
            tgt = if (g != null && votes[g.id] != null) votes[g.id] else cnt.maxByOrNull { it.value }?.key
        }

        fun solo(role: String, name: String, kw: Int, ks: Int, vf: (P) -> M, hd: (P, M) -> Boolean) {
            val all = pl.filter { it.role == role }
            if (all.isNotEmpty()) wakeRole(kw, ks, name, all.filter { it.alive }, vf, hd, nightMs)
        }
        fun pk(title: String, opts: List<Int>): M = mapOf("kind" to "pick", "title" to title, "opts" to opts)
        fun inf(text: String): M = mapOf("kind" to "info", "text" to text)
        fun mafiaIds() = alive().filter { Roles.isM(it.role) }.map { it.id }

        solo("matador", "ماتادور", 0, 0, { q -> pk("چه کسی را امشب بی‌اثر کنی؟", alive().filter { it != q && !Roles.isM(it.role) && it.id != q.lastT }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && !Roles.isM(x.role) && x.id != q.lastT && q.id !in blockedIds) { blockedIds.add(x.id); q.lastT = x.id; visit(q, x) }; true })
        solo("natasha", "ناتاشا", 0, 0, { _ -> pk("چه کسی فردا ساکت باشد؟", alive().map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive) { silenced = x.id; natActor = q.id; visit(q, x) }; true })
        solo("sharlatan", "شارلاتان", 0, 0, { q -> if (q.uses >= 2) inf("دو بار از توانایی‌ات استفاده کرده‌ای.") else pk("استعلام چه کسی برعکس شود؟", alive().map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && q.uses < 2) { charl[x.id] = !looksMafia(x, charl); shaActor = q.id; q.uses++; visit(q, x) }; true })
        solo("vakil", "وکیل", 0, 0, { q -> pk("موکلت را برای امروز انتخاب کن", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q) { vakilClient = x.id; vakActor = q.id; visit(q, x) }; true })
        solo("doctor_lecter", "دکتر لکتر", 0, 0, { q -> pk("کدام مافیا را نجات می‌دهی؟", mafiaIds().filter { !q.self || it != q.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && Roles.isM(x.role) && (!q.self || x != q)) { lecSave = x.id; lecActor = q.id; if (x == q) q.self = true; visit(q, x) }; true })

        solo("doctor", "دکتر", 10, 11, { q -> pk("چه کسی را نجات می‌دهی؟", alive().filter { (!q.self || it != q) && !(docNoRepeat && it.id == q.lastT) }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && (!q.self || x != q) && !(docNoRepeat && x.id == q.lastT)) { save = x.id; saveActor = q.id; if (x == q) q.self = true; q.lastT = x.id; visit(q, x) }; true })
        solo("mohafez", "محافظ", 0, 0, { q -> pk("چه کسی را شبانه سپر کنی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q) { bgT = x.id; bgActor = q.id; visit(q, x) }; true })

        // کارآگاه: هدف را انتخاب می‌کند؛ نتیجه پس از بیدارشدن بلاکر و کشیش داده می‌شود
        var karT: Int? = null
        var karA: Int? = null
        solo("karagah", "کارآگاه", 12, 13,
            { _ -> pk("از چه کسی استعلام می‌گیری؟", alive().filter { it.role != "" }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive) { karT = x.id; karA = q.id; visit(q, x) }; true })

        // پوآرو: فقط نقش‌های مستقل را شناسایی می‌کند
        for (pv in pl.filter { it.role == "karagah_vije" && it.alive }) {
            say(0, "پوآرو بیدار شود.")
            var pt: Int? = null
            ask(listOf(pv.id),
                { _ -> pk("از چه کسی استعلام مستقل بگیری؟", alive().filter { it != pv }.map { it.id }) },
                { _, m -> val x = byId(m["t"]); if (x != null && x.alive && x != pv && pv.id !in blockedIds) { pt = x.id; visit(pv, x) }; true },
                if (manual) 0L else nightMs)
            val px = pt?.let { byId(it) }
            if (px != null && pv.alive) {
                val ans = if (px.role in INDEP) "نقش ${px.name}: ${Roles.names[px.role]}" else "${px.name} مستقل نیست."
                ask(listOf(pv.id), { _ -> inf(ans) }, { _, _ -> true }, if (manual) 0L else nightMs)
            }
            say(0, "پوآرو بخوابد.")
            nap(1200)
        }

        solo("sniper", "تیرانداز", 14, 15,
            { q -> if (q.shot) inf("تیرت را قبلاً زده‌ای.") else mapOf("kind" to "pick", "title" to "شلیک می‌کنی؟", "skip" to 1, "sk" to "شلیک نمی‌کنم", "opts" to alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x != q && !q.shot) { q.shot = true; sniperT = x.id; sniperA = q.id; visit(q, x) }; true })
        solo("kalantar", "حرفه‌ای", 16, 17, { q -> pk("چه کسی را شلیک می‌کنی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q) { kalBy = q.id; kalT = x.id; visit(q, x); q.uses++ }; true })
        solo("blocker", "بلاکر", 0, 0, { q -> pk("چه کسی را امشب بی‌اثر کنی؟", alive().filter { it != q && !Roles.isM(it.role) }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && !Roles.isM(x.role)) { blockedIds.add(x.id); visit(q, x) }; true })

        // کشیش: اثر بلاک ماتادور/بلاکر و لالی ناتاشا را از سوژه برمی‌دارد
        solo("keshish", "کشیش", 0, 0, { _ -> pk("چه کسی را پاک‌سازی کنی؟", alive().map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && q.id !in blockedIds) {
                val wasB = blockedIds.remove(x.id)
                val wasS = silenced == x.id
                if (wasS) silenced = null else if (!wasB) keshishBonus = x.id
                visit(q, x)
            }; true })

        // نتیجهٔ استعلام کارآگاه — پس از بلاکر و کشیش (اگر بلاک شده باشد همیشه «منفی»)
        val karTarget = karT
        val karActor = karA
        if (karTarget != null && karActor != null) {
            val kv = byId(karTarget)
            val ka = byId(karActor)
            if (kv != null && ka != null && ka.alive) {
                if (shaActor != null && shaActor in blockedIds) charl.clear()
                val ans = if (karActor in blockedIds) false else looksMafia(kv, charl)
                ask(listOf(karActor),
                    { _ -> mapOf("kind" to "result", "name" to kv.name, "m" to ans) },
                    { _, _ -> true }, if (manual) 0L else nightMs)
            }
        }

        var manT: Int? = null
        solo("ghatel", "قاتل", 0, 0, { q -> pk("چه کسی را می‌کشی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && q.id !in blockedIds) { manT = x.id; manActor = q.id; visit(q, x) }; true })
        // جک: هر شب شیشهٔ عمر (طلسم) خود را مشخص می‌کند؛ فقط با مرگ او از بازی می‌رود
        solo("jack", "جک گنجشکه", 0, 0, { q -> pk("شیشهٔ عمرت را روی چه کسی بگذاری؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && q.id !in blockedIds) { jackT = x.id; visit(q, x) }; true })
        // زودیاک: فقط در شب‌های زوج شلیک می‌کند؛ به زره‌پوش یا محافظ زند → خودش می‌میرد
        if (night % 2 == 0) solo("zoodiak", "زودیاک", 0, 0,
            { q -> pk("به چه کسی شلیک می‌کنی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && q.id !in blockedIds) {
                zooBy = q.id
                if (x.role == "arm" || x.role == "mohafez") zooReflect = true
                else if (x.id != bgT) { zooT = x.id; visit(q, x) }
            }; true })
        solo("black_smith", "آهنگر", 0, 0, { q -> pk("به چه کسی زره بدهی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && q.id !in blockedIds) { bsTarget = x.id; bsActor = q.id; visit(q, x) }; true })
        solo("constantine", "کنستانتین", 0, 0,
            { q -> if (q.uses >= 1) inf("یک‌بار مصرف کرده‌ای.") else { val d = pl.filter { !it.alive && !it.slaughtered && it.id != q.id }
                if (d.isEmpty()) inf("کسی برای بازگشت نیست.") else pk("چه کسی را به بازی برگردانی؟", d.map { it.id }) } },
            { q, m -> val x = byId(m["t"]); if (x != null && !x.alive && !x.slaughtered && q.uses == 0 && q.id !in blockedIds) {
                x.alive = true; x.armor = false; x.smith = false; q.uses = 1; dawn.add("${x.name} به بازی بازگشت.") }; true })
        // فراماسون: هر شب یک بازیکن را جذب تیم ماسون می‌کند؛ انتخاب مافیا (به‌جز جاسوس) = مرگ ماسونی
        val joined = ArrayList<Int>()
        solo("freemason", "فراماسون", 0, 0,
            { q -> if (q.id in blockedIds) inf("بلاک شده‌ای و نمی‌توانی کسی را انتخاب کنی.")
                   else pk("چه کسی را به تیم ماسون اضافه کنی؟", alive().filter { it != q && it.role != "freemason" && !mason.contains(it.id) }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && q.id !in blockedIds && x.role != "freemason" && !mason.contains(x.id)) {
                if (Roles.isM(x.role) && x.role != "spy") masonDeath = true
                else { mason.add(x.id); joined.add(x.id) }
                visit(q, x)
            }; true })
        for (jid in joined) {
            ask(listOf(jid),
                { _ -> mapOf("kind" to "team", "team" to pl.filter { it.alive && (it.role == "freemason" || mason.contains(it.id)) }
                    .map { listOf(it.name, if (it.role == "freemason") "فراماسون" else (Roles.names[it.role] ?: "")) }) },
                { _, _ -> true }, if (manual) 0L else nightMs)
        }
        solo("hamshhri_kein", "همشهری کین", 0, 0,
            { q -> if (q.uses >= 1) inf("این توانایی را یک‌بار مصرف کرده‌ای.")
                   else pk("کدام بازیکن را تحقیق کنی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && q.uses == 0 && q.id !in blockedIds) { hamPick = x.id; hamActor = q.id; visit(q, x) }; true })
        solo("gun_smith", "تفنگدار", 0, 0,
            { q -> if (q.uses >= 1) inf("تفنگ جنگی‌ات را سوزانده‌ای و دیگر فرصتی نداری.")
                   else if (gunTo != null) inf("تفنگ هنوز دست بازیکنی است.")
                   else pk("تفنگ را به چه کسی بدهی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> if (q.id in blockedIds && q.uses == 0) q.uses = 1 else {
                val x = byId(m["t"]); if (x != null && x.alive && x != q && gunTo == null && q.uses == 0) { gunTo = x.id; visit(q, x) }
            }; true })
        // بمب‌گذار: یک‌بار صندلی بازیکنی را بمب می‌گذارد و رمز خنثی‌سازی را تعیین می‌کند
        for (b in pl.filter { it.role == "bomber" && it.alive && it.uses == 0 && it.id !in blockedIds && bombT == null }) {
            say(0, "بمب‌گذار بیدار شود.")
            var bt: Int? = null
            ask(listOf(b.id),
                { _ -> mapOf("kind" to "pick", "title" to "صندلی چه کسی بمب بگذاری؟", "skip" to 1, "opts" to alive().filter { it != b }.map { it.id }) },
                { _, m -> val x = byId(m["t"]); if (x != null && x.alive && x != b) bt = x.id; true },
                if (manual) 0L else nightMs)
            val t = bt
            if (t != null) {
                var code = ""
                ask(listOf(b.id),
                    { _ -> mapOf("kind" to "pick", "title" to "رمز خنثی‌سازی (۱ تا ۴)", "opts" to listOf("1", "2", "3", "4")) },
                    { _, m -> code = (m["t"] as? String) ?: ""; true },
                    if (manual) 0L else nightMs)
                val c = code.toIntOrNull()
                if (c != null && c in 1..4) { bombT = t; bombCode = c; b.uses = 1 }
            }
            say(0, "بمب‌گذار بخوابد.")
            nap(1200)
        }

        val dead = ArrayList<P>()
        var jokerOut = false                 // جوکر این شب با شلیک از بازی رفت
        fun hit(x: P, saved: Boolean): Boolean { // آیا در این حمله می‌میرد؟ (زره اول می‌شود)
            if (x.armor) { if (x.role != "arm") x.armor = false; return false }
            return !saved
        }
        fun kill(x: P) {
            if (!x.alive || dead.contains(x)) return
            if (x.role == "joker") jokerOut = true
            dead.add(x)
        }
        // بلاک در پایان شب (پس از کشیش) اعمال می‌شود
        fun blockedA(a: Int?) = a != null && a in blockedIds
        val effSave = if (blockedA(saveActor)) null else save
        val effLec = if (blockedA(lecActor)) null else lecSave
        val effBg = if (blockedA(bgActor)) null else bgT
        if (blockedA(natActor)) silenced = null
        if (blockedA(vakActor)) vakilClient = null
        fun saved(x: P) = x.id == effSave || x.id == effLec
        // زره آهنگر: پیش از محاسبهٔ مرگ‌ها (زره آهنگر در تمام حالات اولویت دارد)
        val bst = bsTarget; val bsa = bsActor
        if (bst != null && bsa != null && !blockedA(bsa)) {
            val x = byId(bst)
            if (x != null && x.alive && !x.armor) { x.armor = true; x.smith = true }
        }
        // شات مافیا (محافظ در برابر شات مافیا فدا می‌شود)
        if (tgt != null) {
            val x = byId(tgt)!!
            val b = pl.firstOrNull { it.role == "mohafez" && it.alive }
            if (b != null && effBg == tgt && b != x) kill(b)
            else if (hit(x, saved(x))) kill(x)
        }
        // تیرانداز: هدف می‌میرد مگر زره یا درمان (بدون عکس‌العمل تیر)
        if (sniperT >= 0 && !blockedA(sniperA)) { val x = byId(sniperT)!!; if (hit(x, saved(x))) kill(x) }
        // حرفه‌ای: مافیا/مستقل → هدف می‌میرد؛ شهروند → خود حرفه‌ای حتی با زره و درمان
        if (kalT >= 0 && !blockedA(kalBy)) {
            val x = byId(kalT)!!
            if (Roles.isM(x.role) || x.role in INDEP) { if (hit(x, saved(x))) kill(x) }
            else { val kh = byId(kalBy)!!; kill(kh) }
        }
        // قاتل
        val mt = manT
        if (mt != null) { val x = byId(mt)!!; if (hit(x, saved(x))) kill(x) }
        // زودیاک (شب‌های زوج)
        val zt = zooT
        if (zt != null) { val x = byId(zt)!!; if (hit(x, saved(x))) kill(x) }
        // نامیرایی شب: جک و نوستراداموس از شلیک شبانه جان سالم به در می‌برند
        dead.removeAll { it.role == "jack" || it.role == "nostradamos" }
        dead.forEach { it.alive = false }
        // طلسم جک: با مرگ صاحب شیشهٔ عمر، جک هم از بازی می‌رود
        val jt = jackT
        if (jt != null) {
            val tx = byId(jt)
            val jk = pl.firstOrNull { it.role == "jack" && it.alive }
            if (tx != null && !tx.alive && jk != null) {
                jk.alive = false
                dawn.add("${jk.name} با از دست رفتن شیشهٔ عمرش از بازی رفت.")
            }
        }
        // فرشتهٔ نجات: یک‌بار در کل بازی، یکی از کشته‌های همین شب (به‌جز مرگ حرفه‌ای) را برمی‌گرداند
        val ang = pl.firstOrNull { it.role == "angel" && it.alive && it.uses == 0 }
        if (ang != null) {
            val cands = dead.filter { it != ang && it.id != kalBy }
            if (cands.isNotEmpty()) {
                say(0, "فرشتهٔ نجات بیدار شود.")
                var ap: Int? = null
                ask(listOf(ang.id),
                    { _ -> mapOf("kind" to "pick", "title" to "کدامیک از کشته‌های امشب را برگردانی؟", "skip" to 1, "opts" to cands.map { it.id }) },
                    { _, m -> val x = byId(m["t"]); if (x != null && x in cands) ap = x.id; true },
                    if (manual) 0L else nightMs)
                val p = ap
                if (p != null) {
                    val x = byId(p)!!
                    dead.remove(x); x.alive = true; ang.uses = 1
                    dawn.add("${x.name} توسط فرشتهٔ نجات زنده شد.")
                }
                say(0, "فرشتهٔ نجات بخوابد.")
                nap(1200)
            }
        }
        // مرگ اعلام‌شدهٔ همشهری کین (شب بعد، بدون نجات)
        val hk = hamKillT; hamKillT = null
        if (hk != null) {
            val x = byId(hk)
            if (x != null && x.alive) { x.alive = false; dawn.add("${x.name} به دلیل تحقیق همشهری کین کشته شد.") }
        }
        // مرگ ماسونی: فراماسون و همهٔ اعضای ماسون (به‌جز جاسوس) می‌میرند؛ زره و دکتر و فرشته بی‌اثر
        if (masonDeath) {
            val vs = pl.filter { it.alive && (it.role == "freemason" || (mason.contains(it.id) && it.role != "spy")) }
            if (vs.isNotEmpty()) {
                vs.forEach { it.alive = false }
                dawn.add("مرگ ماسونی: " + vs.joinToString(" و ") { it.name })
            }
        }
        // زودیاک به زره‌پوش یا محافظ زد → خودش حتی با درمان می‌میرد
        if (zooReflect) {
            val z = byId(zooBy)
            if (z != null && z.alive) { z.alive = false; dawn.add("${z.name} با شلیک به زره‌پوش یا محافظ کشته شد.") }
        }
        // همشهری کین: اعلام ساید سوژه در صبح و مرگ خودش در شب بعد
        val hp = hamPick; val ha = hamActor
        if (hp != null && ha != null) {
            val hx = byId(hp); val hh = byId(ha)
            if (hx != null && hx.alive && hh != null && hh.alive) {
                if (Roles.isM(hx.role)) { dawn.add("${hx.name} مافیا است!"); hamKillT = hh.id; hh.uses = 1 }
                else hh.uses = 1
            }
        }
        // جوکر: مرگ فقط با شات مافیا → پیروزی مافیا
        var jokByMafia = false
        val jokP = pl.firstOrNull { it.role == "joker" }
        if (jokerOut && jokP != null && !jokP.alive) {
            val other = sniperT == jokP.id || kalT == jokP.id || manT == jokP.id || zooT == jokP.id
            if (tgt == jokP.id && !other) jokByMafia = true
        }
        val nm = dead.joinToString(" و ") { it.name }
        val morning = (if (dead.isEmpty()) "امشب کسی از بازی خارج نشد." else "امشب $nm از بازی خارج شد.") +
            if (dawn.isNotEmpty()) " — " + dawn.joinToString(" — ") else ""
        synchronized(lock) { log.add(morning); night++ }
        say(if (dead.isEmpty()) 6 else if (dead.size > 1) 5 else 4, morning, false)
        if (manual) askCont("📢 اعلام صبح: " + morning)
        if (jokByMafia) finish("مافیا") else win()?.let { finish(it) }
    }

    private fun day() {
        val sil = silenced
        silenced = null
        if (sil != null) synchronized(lock) { log.add((byId(sil)?.name ?: "") + " امروز ساکت است.") }
        val ids = alive().map { it.id }.filter { it != sil }
        val st = if (ids.isEmpty()) 0 else dayNo % ids.size
        synchronized(lock) { dayNo++; phase = "day"; queue.clear(); queue.addAll(ids.drop(st) + ids.take(st)); spoken.clear(); endDay = false }
        say(3, "روز شد. همه چشم‌ها را باز کنید.", false)
        if (manual) askCont("روز شد؛ همه چشم‌ها را باز کنید. (بعد از ادامه، نوبت صحبت‌ها شروع می‌شود)")
        push()
        while (true) {
            val t = CountDownLatch(1)
            val sp = synchronized(lock) {
                if (endDay || queue.isEmpty()) null
                else { val x = byId(queue.removeAt(0)); speaker = x?.id ?: -1; turn = t; endsAt = System.currentTimeMillis() + speakMs; x }
            }
            if (sp == null) break
            if (!sp.alive) continue
            push()
            awaitL(t, speakMs + if (sp.id == keshishBonus) speakMs else 0)
            synchronized(lock) { spoken.add(sp.id) }
        }
        synchronized(lock) { speaker = -1; endsAt = 0; phase = "vote" }
        keshishBonus = null

        // ---- خواب نیمروزی: خنثی‌سازی بمب پس از صحبت‌های دور اول ----
        val btm = bombT
        if (btm != null) {
            val bx = byId(btm)
            if (bx != null && bx.alive) {
                val mh = pl.firstOrNull { it.role == "mohafez" && it.alive }
                var defused = false
                var exploded: P? = null
                if (mh != null && mh.id == btm) {
                    // بمب روی خودِ محافظ باشد: رمز به او گفته می‌شود و حتماً خنثی می‌شود
                    defused = true
                    synchronized(lock) { log.add("رمز خنثی‌سازی به محافظ گفته شد؛ بمب خنثی شد.") }
                } else {
                    var guesser: P? = null
                    if (mh != null) {
                        say(0, "خواب نیمروزی: محافظ بیدار شود.")
                        var sac = false
                        ask(listOf(mh.id),
                            { _ -> mapOf("kind" to "pick", "title" to "آیا برای نجات ${bx.name} فداکاری می‌کنی؟", "opts" to listOf("خیر", "بله")) },
                            { _, m -> sac = (m["t"] as? String) == "بله"; true },
                            if (manual) 0L else voteMs)
                        say(0, "محافظ بخوابد.")
                        nap(1200)
                        if (sac) guesser = mh
                    }
                    if (guesser == null) guesser = bx
                    val g = guesser
                    var code = ""
                    ask(listOf(g.id),
                        { _ -> mapOf("kind" to "pick", "title" to "رمز خنثی‌سازی بمب (۱ تا ۴) را حدس بزن", "opts" to listOf("1", "2", "3", "4")) },
                        { _, m -> code = (m["t"] as? String) ?: ""; true },
                        if (manual) 0L else voteMs)
                    if (code.toIntOrNull() == bombCode) defused = true
                    else exploded = g
                }
                bombT = null
                when {
                    defused -> { say(0, "بمب خنثی شد."); synchronized(lock) { log.add("بمب خنثی شد.") } }
                    exploded != null -> {
                        exploded.alive = false
                        say(0, "${exploded.name} منفجر شد و از بازی خارج شد.")
                        synchronized(lock) { log.add("${exploded.name} در انفجار بمب از بازی خارج شد.") }
                    }
                }
            } else bombT = null
        }

        // ---- شلیک تفنگ جنگی، پیش از رأی‌گیری (به سر برخورد می‌کند؛ زره و نامیرایی بی‌اثر) ----
        val gto = gunTo
        gunTo = null
        if (gto != null) {
            val gh = byId(gto)
            if (gh != null && gh.alive) {
                say(0, "${gh.name} تفنگ در دست دارد؛ پیش از رأی‌گیری باید شلیک کند.")
                var shootT: Int? = null
                ask(listOf(gh.id),
                    { _ -> mapOf("kind" to "pick", "title" to "به چه کسی شلیک می‌کنی؟", "opts" to alive().filter { it != gh }.map { it.id }) },
                    { _, m -> val x = byId(m["t"]); if (x != null && x.alive && x != gh) shootT = x.id; true },
                    if (manual) 0L else voteMs)
                val s = shootT
                if (s != null) {
                    val x = byId(s)!!
                    x.alive = false
                    say(0, "${x.name} با شلیک تفنگ جنگی از بازی خارج شد.")
                    synchronized(lock) { log.add("${gh.name} به ${x.name} شلیک کرد؛ ${x.name} حذف شد.") }
                    if (x.role == "joker") synchronized(lock) { log.add("جوکر با تفنگ حذف شد؛ بازی ادامه دارد.") }
                    val w0 = win()
                    if (w0 != null) { finish(w0); return }
                }
            }
        }
        // موکل وکیل: کاملاً از چرخهٔ رأی‌گیری خارج است (نه رأی می‌دهد، نه رأی می‌گیرد، نه به دفاعیه می‌رود)
        val al = alive().filter { it.id != vakilClient }
        val votes = HashMap<Int, Int>()
        val c = HashMap<Int, Int>()
        // خواب نیمروزی شهردار: یک‌بار در کل بازی، اگر دو نفر یا بیشتر رأی آورده باشند و خودش متهم نباشد
        fun mayorNap(accused: List<Int>): Pair<Boolean, Int?> {
            val mr = pl.firstOrNull { it.role == "mayor" && it.alive && it.uses == 0 } ?: return Pair(false, null)
            if (accused.size < 2 || accused.contains(mr.id)) return Pair(false, null)
            say(0, "خواب نیمروزی: شهردار بیدار شود.")
            var pick: Int? = null
            ask(listOf(mr.id),
                { _ -> mapOf("kind" to "pick", "title" to "خواب نیمروزی: رد کل رأی‌گیری یا خروج مستقیم یک متهم", "skip" to 1, "opts" to accused) },
                { _, m -> val t = byId(m["t"]); if (t != null && t.alive) pick = t.id; true },
                if (manual) 0L else voteMs)
            mr.uses = 1
            say(0, "شهردار بخوابد.")
            nap(1200)
            return if (pick != null) Pair(false, pick) else Pair(true, null)
        }
        // خروج از بازی با رأی: جک نامیرا است، زره‌پوش زرهش می‌افتد، تروریست یک نفر را با خود می‌برد
        fun doEliminate(x: P): Boolean { // true → جوکر با رأی برنده شد
            if (x.role == "jack") {
                synchronized(lock) { log.add("${x.name} رأی آورد اما جک گنجشکه نامیراست؛ نقشش افشا شد و در بازی می‌ماند.") }
                return false
            }
            if (x.role == "arm" && x.armor) {
                x.armor = false; x.smith = false; x.role = "cit"
                synchronized(lock) { log.add("${x.name} اخراج شد؛ زرهش افتاد و به شهروند ساده تبدیل شد.") }
                return false
            }
            x.alive = false
            synchronized(lock) { log.add("${x.name} اخراج شد" + (if (revealRole) "؛ نقش: ${Roles.names[x.role]}" else "")) }
            if (x.role == "joker") return true
            if (x.role == "terrorist") {
                val opts = alive().map { it.id }
                if (opts.isNotEmpty()) {
                    say(0, "تروریست بیدار شود: یک نفر را با خود ببرد.")
                    var tk: Int? = null
                    ask(listOf(x.id),
                        { _ -> mapOf("kind" to "pick", "title" to "چه کسی را با خود می‌بری؟", "opts" to opts) },
                        { _, m -> val t = byId(m["t"]); if (t != null && t.alive) tk = t.id; true },
                        if (manual) 0L else voteMs)
                    val t = tk?.let { byId(it) }
                    if (t != null && t.alive) {
                        when {
                            t.smith && t.armor ->
                                synchronized(lock) { log.add("زره آهنگر مانع خروج ${t.name} شد.") }
                            t.role == "arm" && t.armor -> {
                                t.armor = false; t.smith = false; t.role = "cit"
                                synchronized(lock) { log.add("${t.name} زرهش افتاد و به شهروند ساده تبدیل شد.") }
                            }
                            else -> {
                                t.alive = false
                                synchronized(lock) { log.add("${t.name} با تروریست از بازی خارج شد.") }
                                if (t.role == "joker") synchronized(lock) { log.add("جوکر با تروریست خارج شد؛ بازی ادامه دارد.") }
                            }
                        }
                    }
                }
            }
            return false
        }
        if (local) {
            fun tallyAsk(opts: List<Int>, title: String) {
                c.clear()
                ask(listOf(0), { _ -> mapOf("kind" to "tally", "title" to title, "opts" to opts) },
                    { _, m -> (m["tally"] as? Map<*, *>)?.forEach { (k, v) -> val id = (k as? String)?.toIntOrNull(); val cn = (v as? Number)?.toInt() ?: 0; if (id != null && id in opts && cn > 0) c[id] = cn }; true }, 0)
            }
            tallyAsk(al.map { it.id }, if (twoRound) "مرحله اول: تعداد رأی هر نفر" else "شمارش رأی‌ها")
            val (cancelNap, elimNap) = mayorNap(c.keys.filter { it != vakilClient }.toList())
            if (cancelNap) {
                synchronized(lock) { log.add("رأی‌گیری با فرمان شهردار ملغی شد؛ امروز کسی خارج نشد.") }
                c.clear()
            } else if (elimNap != null) {
                val x = byId(elimNap)!!
                synchronized(lock) { log.add("شهردار ${x.name} را مستقیماً خارج کرد.") }
                if (doEliminate(x)) { finish("دلقک"); return }
                c.clear()
            } else if (twoRound) {
                val need = (al.size + 1) / 2
                val defs = c.filter { it.value >= need }.keys.toList()
                synchronized(lock) { log.add(if (defs.isEmpty()) "کسی به دفاعیه نرفت." else "به دفاعیه رفتند: " + defs.joinToString("، ") { byId(it)?.name ?: "" }) }
                if (defs.isEmpty()) c.clear() else {
                    synchronized(lock) { defense = true; phase = "day"; queue.clear(); queue.addAll(defs); spoken.clear() }
                    push()
                    while (true) {
                        val id = synchronized(lock) { if (queue.isEmpty()) null else queue.removeAt(0) } ?: break
                        val t2 = CountDownLatch(1)
                        synchronized(lock) { speaker = id; turn = t2; endsAt = System.currentTimeMillis() + speakMs }
                        push(); awaitL(t2, speakMs)
                        synchronized(lock) { spoken.add(id) }
                    }
                    synchronized(lock) { speaker = -1; endsAt = 0; defense = false; phase = "vote" }
                    tallyAsk(defs, "مرحله دوم: فقط بین دفاعیه‌کنندگان (خودشان رأی نمی‌دهند)")
                }
            }
        } else {
            ask(al.map { it.id },
                { q -> if (votes.containsKey(q.id)) mapOf("kind" to "wait", "n" to votes.size, "total" to al.size)
                    else mapOf("kind" to "vote", "opts" to alive().filter { it != q && it.id != vakilClient }.map { it.id }) },
                { q, m -> votes[q.id] = (m["t"] as? Number)?.toInt() ?: -1; votes.size >= al.size }, voteMs)
            votes.forEach { (voter, t) -> if (t >= 0) c[t] = (c[t] ?: 0) + (if (byId(voter)?.role == "mayor") 2 else 1) }
            val (cancelNap, elimNap) = mayorNap(c.keys.filter { it != vakilClient }.toList())
            if (cancelNap) {
                synchronized(lock) { log.add("رأی‌گیری با فرمان شهردار ملغی شد؛ امروز کسی خارج نشد.") }
                c.clear()
            }
            if (elimNap != null) {
                c.clear()
                val x = byId(elimNap)!!
                synchronized(lock) { log.add("شهردار ${x.name} را مستقیماً خارج کرد.") }
                if (doEliminate(x)) { finish("دلقک"); return }
            }
        }
        val mx = c.values.maxOrNull() ?: 0
        val top = c.filter { it.value == mx }.keys
        var jokerWin = false
        if (showVotes && !local) synchronized(lock) { log.add("رأی‌ها: " + votes.entries.joinToString("، ") { (byId(it.key)?.name ?: "") + "←" + (if (it.value >= 0) byId(it.value)?.name ?: "" else "ممتنع") }) }
        if (mx > 0 && top.size == 1) {
            val x = byId(top.first())!!
            if (doEliminate(x)) jokerWin = true
        } else if (mx > 0) synchronized(lock) { log.add("رأی‌ها مساوی بود؛ کسی اخراج نشد.") }
        else synchronized(lock) { log.add("کسی رأی نیاورد؛ کسی اخراج نشد.") }
        if (jokerWin) { finish("دلقک"); return }
        val w = win()
        if (w != null) finish(w) else push()
    }

    private fun run() {
        try {
            reveal(); intro(); day()
            while (winner == null) { night(); if (winner != null) break; day() }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
