import ir.mafia.*
import java.io.File
fun main() {
    val e = Engine({ emptyList() }, 0.02, 2000, 3000, 3000)
    Server(3055, e) { n -> File("app/src/main/assets/$n").takeIf { it.exists() }?.readBytes() }.start()
    println("up")
}
