package ru.pkgh.lib

fun normalize(value: Double): Double = value

fun normalize(value: Int): Double = value.toDouble()

fun normalize(value: String): Double {
    val cleaned = value.trim().replace(",", ".")
    return cleaned.toDoubleOrNull() ?: Double.NaN
}

fun normalize(value: Double, unit: String): Double = when (unit.lowercase()) {
    "км", "km" -> value
    "м", "m" -> value / 1000.0
    "мили", "миль", "mi" -> value * 1.60934
    "тыс.км", "kkm" -> value * 1000.0
    else -> Double.NaN
}

fun makeMileageRangeFilter(minKm: Double, maxKm: Double): (Double) -> Boolean {
    return { km -> !km.isNaN() && km >= minKm && km <= maxKm }
}

fun makeOverdueFilter(standardIntervalKm: Double, tolerancePercent: Double): (Double) -> Boolean {
    val maxAllowedKm = standardIntervalKm * (1.0 + tolerancePercent / 100.0)
    return { km -> !km.isNaN() && km > 0.0 && km <= maxAllowedKm }
}

fun makeMaintenanceCostCalculator(baseFee: Double, ratePerKm: Double): (Double) -> Double {
    return { km -> baseFee + km * ratePerKm }
}

val mileageOperations: Map<String, (Double) -> Double> = mapOf(
    "без изменений" to { km -> km },
    "округление до сотен км" to { km -> Math.round(km / 100.0) * 100.0 },
    "поправка на износ шин (+3%)" to { km -> km * 1.03 },
    "городской коэффициент (*1.25)" to { km -> km * 1.25 },
    "перевод в мили" to { km -> km / 1.60934 }
)

fun getMileageOperation(name: String): (Double) -> Double {
    return mileageOperations[name] ?: { km -> km }
}

fun processServiceRecords(
    rawRecords: List<String>,
    isAccepted: (Double) -> Boolean,
    transform: (Double) -> Double,
    verbose: Boolean = false
): List<Double> {
    var rejectedCount = 0
    var brokenCount = 0

    fun parseOne(raw: String): Double {
        val parts = raw.trim().split(" ")
        return if (parts.size == 2) {
            val number = normalize(parts[0])
            if (number.isNaN()) Double.NaN else normalize(number, parts[1])
        } else {
            normalize(raw)
        }
    }

    fun log(message: String) {
        if (verbose) {
            println("  [ТО-лог] $message")
        }
    }

    fun evaluate(km: Double, source: String): Double? {
        if (km.isNaN()) {
            brokenCount++
            log("'$source' — некорректная запись одометра")
            return null
        }
        if (!isAccepted(km)) {
            rejectedCount++
            log("$km км — отклонено правилом фильтрации")
            return null
        }
        val transformed = transform(km)
        log("$km км -> $transformed")
        return transformed
    }

    val processed = mutableListOf<Double>()
    for (record in rawRecords) {
        val validValue = evaluate(parseOne(record), record)
        if (validValue != null) {
            processed.add(validValue)
        }
    }
    log("Обработка завершена: принято ${processed.size}, отклонено $rejectedCount, ошибок $brokenCount")
    return processed
}

fun applyChain(value: Double, steps: List<(Double) -> Double>): Double {
    var result = value
    for (step in steps) {
        result = step(result)
    }
    return result
}

fun combine(vararg steps: (Double) -> Double): (Double) -> Double {
    return { value -> applyChain(value, steps.toList()) }
}

fun summarize(
    values: List<Double>,
    label: String,
    aggregate: (List<Double>) -> Double
): String {
    if (values.isEmpty()) {
        return "$label: нет данных"
    }
    val result = aggregate(values)
    return "$label: ${Math.round(result * 100) / 100.0}"
}

fun main() {
    val rawServiceLogs = listOf(
        "9850.5", "10200,0", "6200 mi", "1500000 м", "ошибка датчика", "-500", "14500 км", "28000"
    )

    println("=== 1. Проверка независимости фабрик ===")
    val lightCarRange = makeMileageRangeFilter(1000.0, 20000.0)
    val truckRange = makeMileageRangeFilter(5000.0, 60000.0)
    println("Пробег 25000 км: легковое ТО = ${lightCarRange(25000.0)}, грузовое ТО = ${truckRange(25000.0)}")

    val strictWarranty = makeOverdueFilter(10000.0, 5.0)
    val loyalService = makeOverdueFilter(10000.0, 20.0)
    println("Пробег 11200 км: строгая гарантия = ${strictWarranty(11200.0)}, лояльный сервис = ${loyalService(11200.0)}")

    val economyTariff = makeMaintenanceCostCalculator(1500.0, 0.8)
    val premiumTariff = makeMaintenanceCostCalculator(3000.0, 1.5)
    println("Стоимость ТО за 10000 км: эконом = ${economyTariff(10000.0)} руб., премиум = ${premiumTariff(10000.0)} руб.\n")

    println("=== 2. Перебор реестра операций для пробега 10000 км ===")
    for ((name, operation) in mileageOperations) {
        println("$name -> ${operation(10000.0)}")
    }
    println()

    println("=== 3. Обработка журнала ТО и цепочка преобразований ===")
    val adjustedMileageChain = combine(
        getMileageOperation("поправка на износ шин (+3%)"),
        getMileageOperation("городской коэффициент (*1.25)"),
        getMileageOperation("округление до сотен км")
    )

    val effectiveMileages = processServiceRecords(
        rawServiceLogs,
        lightCarRange,
        adjustedMileageChain,
        verbose = true
    )
    println("Приведённый пробег (км): $effectiveMileages\n")

    println("=== 4. Статистика по автопарку ===")
    println(summarize(effectiveMileages, "Суммарный приведённый пробег (км)") { it.sum() })
    println(summarize(effectiveMileages, "Средний интервал ТО (км)") { it.average() })
    println(summarize(effectiveMileages, "Максимальный интервал (км)") { it.max() })
    println(summarize(effectiveMileages, "Доля авто с повышенной нагрузкой (%)") { list ->
        list.count { it > 12000.0 }.toDouble() / list.size * 100.0
    })

    println("\n=== 5. Проверка работы с пустым списком ===")
    println(summarize(emptyList(), "Пустой журнал") { it.average() })
}
