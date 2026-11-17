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
    var uses = 0
    var lastT = -1
    @Volatile var host = false
}

class Pend(val who: Set<Int>, val vf: (P) -> M, val hd: (P, M) -> Boolean) {
    val latch = CountDownLatch(1)
}

object R {
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

fun comp(a: List<String>) = a.groupingBy { it }.eachCount().entries.joinToString("، ") { "${R.names[it.key]} ×${it.value}" }

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
    private val bomberT = HashMap<Int, Int>()
    private val terT = HashMap<Int, Int>()
    private val hamshhriT = HashMap<Int, Int>()

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
        pl.forEachIndexed { i, q -> q.role = roles[i]; q.armor = roles[i] == "arm" }
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
            "players" to pl.map { mapOf("id" to it.id, "name" to it.name, "alive" to it.alive, "seen" to it.seen, "role" to it.role, "rn" to R.names[it.role], "m" to (if (R.isM(it.role)) 1 else 0)) },
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
            "me" to me?.let { mapOf("id" to it.id, "name" to it.name, "role" to it.role, "rn" to R.names[it.role], "alive" to it.alive, "host" to it.host, "m" to (if (R.isM(it.role)) 1 else 0)) },
            "players" to pl.map { mapOf("id" to it.id, "name" to it.name, "alive" to it.alive, "seen" to it.seen, "rn" to (if (end) R.names[it.role] else null)) },
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
        val m = alive().count { R.isM(it.role) }
        val c = alive().count { !R.isM(it.role) }
        if (test) return if (m == 0) "شهروندان" else if (c == 0) "مافیا" else null
        if (m == 0) return "شهروندان"
        if (m >= c) return "مافیا"
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
        val mf = pl.filter { R.isM(it.role) }
        val ack = HashSet<Int>()
        wakeRole(8, 9, "مافیا", mf,
            { _ -> mapOf("kind" to "team", "team" to mf.map { listOf(it.name, R.names[it.role]) }) },
            { q, _ -> ack.add(q.id); ack.size >= (if (local) 1 else mf.size) }, 0)
        val fms = pl.filter { it.role == "freemason" }
        if (fms.isNotEmpty()) {
            val ack2 = HashSet<Int>()
            wakeRole(0, 0, "فراماسون", fms, { _ -> mapOf("kind" to "team", "team" to fms.map { listOf(it.name, "فراماسون") }) },
                { q, _ -> ack2.add(q.id); ack2.size >= (if (local) 1 else fms.size) }, 0)
        }
        for ((r, kw, ks) in listOf(Triple("doctor", 10, 11), Triple("karagah", 12, 13), Triple("sniper", 14, 15), Triple("kalantar", 16, 17), Triple("arm", 18, 19), Triple("mohafez", 20, 21), Triple("angel", 22, 23), Triple("keshish", 24, 25), Triple("gun_smith", 26, 27), Triple("hamshhri_kein", 28, 29))) {
            val h = pl.filter { it.role == r }
            if (h.isNotEmpty()) wakeRole(kw, ks, R.names[r] ?: r, h, { _ -> mapOf("kind" to "know") }, { _, _ -> true }, 0)
        }
    }

    private fun looksMafia(x: P, charl: Set<Int>, law: Set<Int>): Boolean {
        var r = x.role == "mafia"
        if (charl.contains(x.id)) r = true
        if (law.contains(x.id)) r = false
        return r
    }

    private fun night() {
        phase = "night"; push()
        say(1, "شب شد. همه چشم‌های خود را ببندند.")
        nap(1500)
        var save: Int? = null
        var shotBy = -1
        var shotT = -1
        var bgT: Int? = null
        var blocked: Int? = null
        var lecSave: Int? = null
        var revive: P? = null
        val charl = HashSet<Int>()
        val law = HashSet<Int>()
        val visits = HashMap<Int, MutableList<Int>>()
        fun visit(a: P, t: P) { visits.getOrPut(t.id) { ArrayList() }.add(a.id) }
        val mf = alive().filter { it.role == "mafia" || it.role == "raees_mafia" }
        val votes = LinkedHashMap<Int, Int>()
        wakeRole(8, 9, "مافیا", mf,
            { _ -> mapOf("kind" to "pick", "title" to "قربانی مافیا را انتخاب کنید",
                "opts" to alive().filter { !R.isM(it.role) }.map { it.id },
                "votes" to votes.entries.map { listOf(byId(it.key)?.name, byId(it.value)?.name) }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && !R.isM(x.role)) { votes[q.id] = x.id; visit(q, x) }; votes.size >= (if (local) 1 else mf.size) }, nightMs)
        val g = mf.firstOrNull { it.role == "raees_mafia" }
        val cnt = votes.values.groupingBy { it }.eachCount()
        val tgt: Int? = if (g != null && votes[g.id] != null) votes[g.id] else cnt.maxByOrNull { it.value }?.key

        fun solo(role: String, name: String, kw: Int, ks: Int, vf: (P) -> M, hd: (P, M) -> Boolean) {
            val all = pl.filter { it.role == role }
            if (all.isNotEmpty()) wakeRole(kw, ks, name, all.filter { it.alive }, vf, hd, nightMs)
        }
        fun pk(title: String, opts: List<Int>): M = mapOf("kind" to "pick", "title" to title, "opts" to opts)
        fun inf(text: String): M = mapOf("kind" to "info", "text" to text)
        fun mafiaIds() = alive().filter { R.isM(it.role) }.map { it.id }

        solo("matador", "ماتادور", 0, 0, { q -> pk("توانایی چه کسی را امشب بی‌اثر می‌کنی؟", alive().filter { it != q && it.id != q.lastT }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && x.id != q.lastT) { blocked = x.id; q.lastT = x.id; visit(q, x) }; true })
        solo("natasha", "ناتاشا", 0, 0, { q -> pk("چه کسی فردا ساکت باشد؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q) { silenced = x.id; visit(q, x) }; true })
        solo("sharlatan", "شارلاتان", 0, 0, { q -> if (q.uses >= 2) inf("دو بار از توانایی‌ات استفاده کرده‌ای.") else pk("چه کسی برای کارآگاه «مافیا» دیده شود؟", alive().filter { !R.isM(it.role) }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && q.uses < 2) { charl.add(x.id); q.uses++; visit(q, x) }; true })
        solo("vakil", "وکیل", 0, 0, { _ -> pk("کدام مافیا برای کارآگاه «شهروند» دیده شود؟", mafiaIds()) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && R.isM(x.role)) { law.add(x.id); visit(q, x) }; true })
        solo("doctor_lecter", "دکتر لکتر", 0, 0, { q -> pk("کدام مافیا را نجات می‌دهی؟", mafiaIds().filter { !q.self || it != q.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && R.isM(x.role) && (!q.self || x != q)) { lecSave = x.id; if (x == q) q.self = true; visit(q, x) }; true })

        solo("doctor", "دکتر", 10, 11, { q -> pk("چه کسی را نجات می‌دهی؟", alive().filter { (!q.self || it != q) && !(docNoRepeat && it.id == q.lastT) }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && (!q.self || x != q) && !(docNoRepeat && x.id == q.lastT)) { if (blocked != q.id) save = x.id; if (x == q) q.self = true; q.lastT = x.id; visit(q, x) }; true })
        solo("mohafez", "محافظ", 0, 0, { q -> pk("از چه کسی محافظت می‌کنی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && blocked != q.id) { bgT = x.id; visit(q, x) }; true })

        var res: Pair<String, Boolean>? = null
        var detBlocked = false
        solo("karagah", "کارآگاه", 12, 13,
            { q -> if (detBlocked) inf("توانایی‌ات امشب بی‌اثر شد.") else { val r = res
                if (r != null) mapOf("kind" to "result", "name" to r.first, "m" to r.second) else pk("از چه کسی استعلام می‌گیری؟", alive().filter { it != q }.map { it.id }) } },
            { q, m -> if (res == null && !detBlocked) { val x = byId(m["t"]); if (x != null) { if (blocked == q.id) detBlocked = true else { res = Pair(x.name, looksMafia(x, charl, law)); visit(q, x) } }; false } else true })

        solo("sniper", "تیرانداز", 14, 15,
            { q -> if (q.shot) inf("تیرت را قبلاً زده‌ای.") else mapOf("kind" to "pick", "title" to "شلیک می‌کنی؟", "skip" to 1, "opts" to alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x != q && !q.shot) { q.shot = true; if (blocked != q.id) { shotBy = q.id; shotT = x.id; visit(q, x) } }; true })

        solo("kalantar", "حرفه‌ای", 16, 17, { q -> pk("چه کسی را شلیک می‌کنی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q) { if (blocked != q.id) { shotBy = q.id; shotT = x.id; visit(q, x) }; q.uses++ }; true })

        var cres: String? = null
        var cBlocked = false
        solo("blocker", "بلاکر", 0, 0, { q -> if (cBlocked) inf("توانایی‌ات امشب بی‌اثر شد.") else { val r = cres
            if (r != null) inf(r) else pk("نقش دقیق چه کسی را می‌خواهی بدانی؟", alive().filter { it != q && !R.isM(it.role) }.map { it.id }) } },
            { q, m -> if (cres == null && !cBlocked) { val x = byId(m["t"]); if (x != null && x.alive) { if (blocked == q.id) cBlocked = true else { cres = "نقش " + x.name + ": " + R.names[x.role]; visit(q, x) } }; false } else true })
        var manT: Int? = null
        solo("ghatel", "قاتل", 0, 0, { q -> pk("چه کسی را می‌کشی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> val x = byId(m["t"]); if (x != null && x.alive && x != q && blocked != q.id) { manT = x.id; visit(q, x) }; true })
        var bres: String? = null
        solo("jack", "جک گنجشکه", 0, 0, { q -> val r = bres; if (r != null) inf(r) else pk("به خانه‌ی چه کسی سر می‌زنی؟", alive().filter { it != q }.map { it.id }) },
            { q, m -> if (bres == null) { val x = byId(m["t"])
                if (x != null && x.alive && x != q) { val vs = visits[x.id]?.filter { it != q.id }?.mapNotNull { byId(it)?.name }?.distinct()
                    bres = if (blocked == q.id) "توانایی‌ات امشب بی‌اثر شد." else if (vs.isNullOrEmpty()) "شب گذشته کسی به ${x.name} سر نزد." else "به ${x.name} سر زدند: " + vs.joinToString("، ") }
                false } else true })

        val dead = ArrayList<P>()
        if (tgt != null && tgt != save) {
            val x = byId(tgt)!!
            val b = pl.firstOrNull { it.role == "mohafez" && it.alive }
            if (b != null && bgT == tgt && b != x) dead.add(b) else if (x.armor) x.armor = false else dead.add(x)
        }
        if (shotT >= 0) {
            val x = byId(shotT)!!
            if (R.isM(x.role)) { if (x.id != save && x.id != lecSave && !dead.contains(x)) dead.add(x) }
            else { val sh = byId(shotBy)!!; if (!dead.contains(sh)) dead.add(sh) }
        }
        val mt = manT
        if (mt != null && mt != save) { val x = byId(mt)!!; if (x.armor) x.armor = false else if (!dead.contains(x)) dead.add(x) }
        dead.forEach { it.alive = false }
        val rv = revive
        if (rv != null) { rv.alive = true; dead.remove(rv) }
        val nm = dead.joinToString(" و ") { it.name }
        var msg = if (dead.isEmpty()) "امشب کسی از بازی خارج نشد." else "امشب از بازی خارج شد: $nm"
        if (rv != null) msg += " | " + rv.name + " زنده شد."
        synchronized(lock) { log.add(msg); night++ }
        say(if (dead.isEmpty()) 6 else if (dead.size > 1) 5 else 4,
            if (dead.isEmpty()) "امشب کسی از بازی خارج نشد." else "امشب $nm از بازی خارج شد.", false)
        if (manual) askCont("📢 اعلام صبح: " + msg)
        win()?.let { finish(it) }
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
            awaitL(t, speakMs)
            synchronized(lock) { spoken.add(sp.id) }
        }
        synchronized(lock) { speaker = -1; endsAt = 0; phase = "vote" }
        val al = alive()
        val votes = HashMap<Int, Int>()
        val c = HashMap<Int, Int>()
        if (local) {
            fun tallyAsk(opts: List<Int>, title: String) {
                c.clear()
                ask(listOf(0), { _ -> mapOf("kind" to "tally", "title" to title, "opts" to opts) },
                    { _, m -> (m["tally"] as? Map<*, *>)?.forEach { (k, v) -> val id = (k as? String)?.toIntOrNull(); val cn = (v as? Number)?.toInt() ?: 0; if (id != null && id in opts && cn > 0) c[id] = cn }; true }, 0)
            }
            tallyAsk(al.map { it.id }, if (twoRound) "مرحله اول: تعداد رأی هر نفر" else "شمارش رأی‌ها")
            if (twoRound) {
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
                    else mapOf("kind" to "vote", "opts" to al.filter { it != q }.map { it.id }) },
                { q, m -> votes[q.id] = (m["t"] as? Number)?.toInt() ?: -1; votes.size >= al.size }, voteMs)
            votes.forEach { (voter, t) -> if (t >= 0) c[t] = (c[t] ?: 0) + (if (byId(voter)?.role == "mayor") 2 else 1) }
        }
        val mx = c.values.maxOrNull() ?: 0
        val top = c.filter { it.value == mx }.keys
        var jokerWin = false
        if (showVotes && !local) synchronized(lock) { log.add("رأی‌ها: " + votes.entries.joinToString("، ") { (byId(it.key)?.name ?: "") + "←" + (if (it.value >= 0) byId(it.value)?.name ?: "" else "ممتنع") }) }
        if (mx > 0 && top.size == 1) {
            val x = byId(top.first())!!
            x.alive = false
            synchronized(lock) { log.add("${x.name} اخراج شد" + (if (revealRole) "؛ نقش: ${R.names[x.role]}" else "")) }
            if (x.role == "joker") jokerWin = true
        } else synchronized(lock) { log.add("رأی‌ها مساوی بود؛ کسی اخراج نشد.") }
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
