package at.crowdware.server

import at.crowdware.sml.PropertyValue
import at.crowdware.sml.SmlHandler
import at.crowdware.sml.SmlSaxParser
import at.crowdware.sms.ScriptEngine
import org.slf4j.LoggerFactory
import io.ktor.server.plugins.callloging.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Block(val x: Int, val y: Int, val z: Int, val key: String)

data class ChunkHeader(
    val chunkX: Int,
    val chunkY: Int,
    val chunkZ: Int,
    val blockCount: Int,
    val blockSizeCm: Int
)

data class ChunkBlock(val x: Int, val y: Int, val z: Int, val tileId: Int)

data class ChunkData(val header: ChunkHeader, val blocks: List<ChunkBlock>)

private const val CHUNK_SIZE = 32
private const val DEFAULT_BLOCK_CM = 60
private val logger = LoggerFactory.getLogger("RaidServer")

private fun parseDungeonBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    var currentLayer = 0
    var row = 0

    var linesText: String? = null
    val handler = object : SmlHandler {
        override fun startElement(name: String) {}

        override fun onProperty(name: String, value: PropertyValue) {
            if (name == "lines" && value is PropertyValue.StringValue) {
                linesText = value.value
            }
        }

        override fun endElement(name: String) {}
    }

    val parser = SmlSaxParser(text)
    parser.parse(handler)

    if (linesText.isNullOrBlank()) {
        logger.warn("No TileMap lines block found in dungeon SML")
        return blocks
    }

    for (rawLine in linesText!!.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            currentLayer = line.drop(1).toIntOrNull() ?: 0
            row = 0
            continue
        }
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) continue
        for ((col, token) in tokens.withIndex()) {
            if (token == ".") continue
            val key = token.substringBefore(":")
            blocks.add(Block(col, currentLayer, row, key))
        }
        row += 1
    }

    logger.info("Parsed ${blocks.size} blocks from dungeon SML")
    return blocks
}

private fun mapTileId(key: String): Int {
    return when (key.lowercase()) {
        "s" -> 0
        "t" -> 1
        "u" -> 2
        "v" -> 3
        "w" -> 4
        "x" -> 5
        "y" -> 6
        "z" -> 7
        else -> 0
    }
}

private fun chunkBlocks(blocks: List<Block>, chunkX: Int, chunkY: Int, chunkZ: Int): ChunkData {
    val minX = chunkX * CHUNK_SIZE
    val minY = chunkY * CHUNK_SIZE
    val minZ = chunkZ * CHUNK_SIZE
    val maxX = minX + CHUNK_SIZE - 1
    val maxY = minY + CHUNK_SIZE - 1
    val maxZ = minZ + CHUNK_SIZE - 1

    val chunkBlocks = blocks.filter {
        it.x in minX..maxX && it.y in minY..maxY && it.z in minZ..maxZ
    }.map {
        ChunkBlock(
            x = it.x - minX,
            y = it.y - minY,
            z = it.z - minZ,
            tileId = mapTileId(it.key)
        )
    }

    val header = ChunkHeader(chunkX, chunkY, chunkZ, chunkBlocks.size, DEFAULT_BLOCK_CM)
    return ChunkData(header, chunkBlocks)
}

private fun encodeChunk(chunk: ChunkData): ByteArray {
    val header = chunk.header
    val bufferSize = 16 + chunk.blocks.size * 4
    val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(header.chunkX)
    buffer.putInt(header.chunkY)
    buffer.putInt(header.chunkZ)
    buffer.putShort(header.blockCount.toShort())
    buffer.putShort(header.blockSizeCm.toShort())
    chunk.blocks.forEach {
        buffer.put(it.x.toByte())
        buffer.put(it.y.toByte())
        buffer.put(it.z.toByte())
        buffer.put(it.tileId.toByte())
    }
    return buffer.array()
}

fun main() {
    val smsEngine = ScriptEngine.withStandardLibrary()
    smsEngine.registerKotlinFunction("log") { args ->
        val msg = args.joinToString(" ") { it?.toString() ?: "null" }
        logger.info("[sms] $msg")
        null
    }
    try {
        val result = smsEngine.executeAndGetKotlin(
            """
            fun bossHealth(maxHp, damage) {
                var hp = maxHp - damage
                if (hp < 0) {
                    hp = 0
                }
                return hp
            }

            log("SMS test: boss HP after hit =", bossHealth(100, 17))
            bossHealth(100, 17)
            """.trimIndent()
        )
        logger.info("SMS test result: $result")
    } catch (e: Exception) {
        logger.error("SMS test failed", e)
    }

    val dungeonFile = File("dungeon.sml")
    val dungeonText = if (dungeonFile.exists()) dungeonFile.readText() else "Dungeon { TileMap { lines: \"\" } }"
    val blocks = parseDungeonBlocks(dungeonText)

    embeddedServer(Netty, port = 8080) {
        install(CallLogging)
        routing {
            get("/chunk") {
                val x = call.request.queryParameters["x"]?.toIntOrNull() ?: 0
                val y = call.request.queryParameters["y"]?.toIntOrNull() ?: 0
                val z = call.request.queryParameters["z"]?.toIntOrNull() ?: 0
                val chunk = chunkBlocks(blocks, x, y, z)
                logger.info("Serving chunk ($x,$y,$z) with ${chunk.blocks.size} blocks")
                val payload = encodeChunk(chunk)
                call.respondBytes(payload, contentType = ContentType.Application.OctetStream)
            }
        }
    }.start(wait = true)
}