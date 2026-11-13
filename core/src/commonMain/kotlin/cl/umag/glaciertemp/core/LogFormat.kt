package cl.umag.glaciertemp.core

/**
 * Layout del registro del log, derivado del LOG_SIGNATURE de 16 bits que el firmware
 * graba en EEPROM. Las asignaciones de bits vienen del bloque de canales de
 * GlacierTemp_1_cell_v02_claude.ino y coinciden con decode_logh.py.
 *
 * El signature es autodescriptivo: codifica que canales escribieron el log y cuantas
 * sondas DS18B20 habia, de modo que el layout se recupera del propio dato.
 */
data class Field(val name: String, val scale: Double, val decimals: Int)

object LogFormat {

    private val CHANNELS = listOf(
        Triple("Volt",   0x0001, Field("Volt",   1000.0, 2)),
        Triple("Temp",   0x0002, Field("Temp",    100.0, 2)),
        Triple("RH",     0x0004, Field("RH",       10.0, 1)),
        Triple("HAtemp", 0x0008, Field("HAtemp",  100.0, 2)),
    )
    private val ANALOG = listOf(
        "A0" to 0x0020, "A1" to 0x0040, "A2" to 0x0080, "A3" to 0x0100,
    )

    const val DS_BIT = 0x0010
    const val DS_MASK = 0x0E00
    const val DS_SHIFT = 9
    const val INVALID = -32768
    const val TIMESTAMP_BYTES = 4

    /** Version de formato que llevan los bits 12..15: la del log normal. */
    const val FORMAT_VERSION = 1

    /**
     * Version de un log de captura continua (CONT, firmware 3.10): los mismos canales que la
     * version 1, con un uint16 de milisegundos (0..999) detras de los cuatro bytes de
     * segundos. Mismos bits de canal y otra version a proposito: un lector que solo conoce
     * la 1 se niega a leerlo en vez de desalinear cada campo dos bytes.
     */
    const val FORMAT_CONT = 2

    /**
     * Versiones con sensor de distancia HC-SR04P (firmware 3.17): un int16 "Dist" (cm; mm desde la version 5) detras
     * de las sondas DS18B20 y antes de los analogicos. Los 16 bits del signature ya estaban
     * todos ocupados, asi que el canal se codifica en la version: 3 = normal + Dist,
     * 4 = CONT + Dist. Un lector anterior se niega a leerlo en vez de desalinear los campos.
     */
    const val FORMAT_DIST = 3
    const val FORMAT_CONT_DIST = 4

    /**
     * Las mismas, con la distancia en mm (firmware 3.19, media recortada de 5 pings):
     * 5 = normal + Dist, 6 = CONT + Dist. Las 3 y 4 (cm) se siguen leyendo y se pasan a mm,
     * para que la columna Dist sea siempre mm.
     */
    const val FORMAT_DIST_MM = 5
    const val FORMAT_CONT_DIST_MM = 6

    /** Bytes de la marca de tiempo de un log v2: segundos y milisegundos. */
    const val TIMESTAMP_BYTES_CONT = 6

    fun formatVersion(signature: Int): Int = (signature ushr 12) and 0x0F

    /** Si esta app sabe leer un log con esta firma. Cualquier otra version, no. */
    fun isSupported(signature: Int): Boolean = formatVersion(signature) in FORMAT_VERSION..FORMAT_CONT_DIST_MM

    /** Si los registros llevan milisegundos (log de CONT). */
    fun hasMillis(signature: Int): Boolean =
        formatVersion(signature).let { it == FORMAT_CONT || it == FORMAT_CONT_DIST || it == FORMAT_CONT_DIST_MM }

    /** Si los registros llevan la distancia del HC-SR04P. */
    fun hasDist(signature: Int): Boolean =
        formatVersion(signature) in FORMAT_DIST..FORMAT_CONT_DIST_MM

    /** Si la distancia esta grabada en cm (versiones 3 y 4, firmware 3.17-3.18). */
    fun distInCm(signature: Int): Boolean =
        formatVersion(signature).let { it == FORMAT_DIST || it == FORMAT_CONT_DIST }

    /** Bytes de la marca de tiempo de cada registro. */
    fun timestampBytes(signature: Int): Int =
        if (hasMillis(signature)) TIMESTAMP_BYTES_CONT else TIMESTAMP_BYTES

    /** Numero de sondas DS18B20 que codifica el signature; 0 si el canal no esta presente. */
    fun dsCount(signature: Int): Int =
        if (signature and DS_BIT == 0) 0 else ((signature and DS_MASK) ushr DS_SHIFT) + 1

    /** Campos que componen un registro, en el orden en que estan grabados. */
    fun fields(signature: Int): List<Field> = buildList {
        CHANNELS.forEach { (_, bit, f) -> if (signature and bit != 0) add(f) }
        repeat(dsCount(signature)) { add(Field("DS$it", 100.0, 2)) }
        // Siempre en mm: un valor en cm (v3/v4) se divide por la escala 0.1, o sea x10.
        if (hasDist(signature)) add(Field("Dist", if (distInCm(signature)) 0.1 else 1.0, 0))
        ANALOG.forEach { (name, bit) -> if (signature and bit != 0) add(Field(name, 1000.0, 3)) }
    }

    /** Tamano del registro en bytes: la marca de tiempo (4, o 6 con ms) mas 2 por campo. */
    fun recordBytes(signature: Int): Int = timestampBytes(signature) + 2 * fields(signature).size

    /** Nombre completo de cada canal. El corto es el encabezado de la columna del CSV. */
    fun channelDescription(name: String): String = when {
        name == "Volt" -> "Battery voltage"
        name == "Temp" -> "Air temperature (HDC1080)"
        name == "RH" -> "Relative humidity (HDC1080)"
        name == "HAtemp" -> "High-accuracy temperature (TMP119)"
        name.startsWith("DS") -> "Probe ${name.drop(2)} temperature (DS18B20)"
        name == "Dist" -> "Distance (HC-SR04P ultrasonic)"
        name.startsWith("A") -> "Analog input $name"
        else -> name
    }

    /**
     * Lo que la firma significa, en palabras.
     *
     * El numero hexadecimal no es una curiosidad: dice QUE canales escribieron el log, y por
     * eso el decodificador puede recuperar la disposicion del registro a partir del propio
     * dato. Pero eso es util para el programa, no para quien mira la pantalla: a una persona
     * hay que decirle que columnas trae y cuanto ocupa cada registro.
     */
    fun describe(signature: Int): List<String> =
        fields(signature).map { channelDescription(it.name) }

    /**
     * La unidad de un canal, para ensenarla junto al numero.
     *
     * No va en [Field] porque el CSV no la lleva: las columnas del fichero son nombres
     * pelados, y meterla ahi cambiaria el formato de todo lo ya exportado. Aqui es solo
     * para la pantalla, donde un numero suelto no dice si son grados o voltios.
     */
    fun unitOf(name: String): String = when {
        name == "Volt" -> "V"
        name == "RH" -> "%"
        name == "Temp" || name == "HAtemp" -> "°C"
        name.startsWith("DS") -> "°C"
        name == "Dist" -> "mm"
        // Los canales analogicos se guardan en mV con escala 1000: son voltios.
        name.startsWith("A") -> "V"
        else -> ""
    }

    /** Resumen de una linea, para cuando no hay sitio para la lista entera. */
    fun summary(signature: Int): String {
        val f = fields(signature)
        return "${f.size} channels · ${recordBytes(signature)} bytes per record"
    }
}
