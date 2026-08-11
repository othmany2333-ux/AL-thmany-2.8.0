import com.althmany.groupmanager.domain.RuntimeIdempotencyGuard
import com.althmany.groupmanager.domain.RuntimeScreenFingerprint
import com.althmany.groupmanager.domain.WhatsAppLinkParser
import kotlin.system.measureNanoTime

private fun ms(nanos: Long): String = "%.2f".format(nanos / 1_000_000.0)

fun main() {
    val input = buildString {
        repeat(5_000) { index ->
            val code = "A%05dBCDEFghijkLMNopqrs".format(index)
            append("https://chat.whatsapp.com/").append(code).append('\n')
            if (index % 10 == 0) append("https://chat.whatsapp.com/").append(code).append('\n')
        }
    }

    lateinit var report: com.althmany.groupmanager.model.ParseReport
    val parserNs = measureNanoTime { report = WhatsAppLinkParser.extract(input) }
    check(report.accepted.size == 5_000) { "Expected 5000 benchmark links, got ${report.accepted.size}" }
    check(report.duplicateCount == 500) { "Expected 500 duplicate links, got ${report.duplicateCount}" }

    var fingerprint = 0L
    val fingerprintNs = measureNanoTime {
        repeat(20_000) { i ->
            fingerprint = RuntimeScreenFingerprint.calculate(
                sequenceOf("انضمام إلى المجموعة", "دعوة مجموعة", "إغلاق", "screen-$i"),
                sequenceOf("invite", "join")
            )
        }
    }
    check(fingerprint != 0L)

    val guard = RuntimeIdempotencyGuard()
    var allowed = 0
    val guardNs = measureNanoTime {
        repeat(100_000) { i ->
            val key = "${i % 64}:JOIN"
            if (guard.shouldAllow(key, i.toLong(), 1_200L)) {
                guard.recordSuccess(key, i.toLong())
                allowed++
            }
        }
    }
    check(allowed > 0)

    println("SMART RUNTIME MICRO-BENCHMARK")
    println("- parse 5,000 unique + 500 duplicate links: ${ms(parserNs)} ms")
    println("- 20,000 screen fingerprints: ${ms(fingerprintNs)} ms")
    println("- 100,000 idempotency checks: ${ms(guardNs)} ms")
    println("- benchmark sanity checks: PASSED")
}
