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

data class ChunkCoord(val x: Int, val y: Int, val z: Int)

private const val CHUNK_SIZE = 32
private const val DEFAULT_BLOCK_CM = 60
private const val DEFAULT_WORLD_SEED = 0L
private const val ENV_WORLD_SEED = "WORLD_SEED"
private const val ENV_CHUNK_DEBUG = "CHUNK_DEBUG"
private val logger = LoggerFactory.getLogger("RaidServer")

private data class DungeonParseResult(
    val blocks: List<Block>,
    val chunkSize: Int?
)

private fun resolveWorldSeed(): Long {
    val env = System.getenv(ENV_WORLD_SEED)?.trim().orEmpty()
    if (env.isEmpty()) return DEFAULT_WORLD_SEED
    return env.toLongOrNull() ?: DEFAULT_WORLD_SEED
}

private fun isDebugEnabled(envName: String): Boolean {
    val value = System.getenv(envName)?.trim()?.lowercase().orEmpty()
    return value == "1" || value == "true" || value == "yes" || value == "on"
}

private fun parseDungeonBlocks(text: String, warnOnMissingLines: Boolean = true): DungeonParseResult {
    val blocks = mutableListOf<Block>()
    var currentLayer = 0
    var row = 0

    var linesText: String? = null
    var chunkSize: Int? = null
    val handler = object : SmlHandler {
        override fun startElement(name: String) {}

        override fun onProperty(name: String, value: PropertyValue) {
            if (name == "ChunkSize" && value is PropertyValue.IntValue) {
                chunkSize = value.value
            }
            if (name == "lines" && value is PropertyValue.StringValue) {
                linesText = value.value
            }
        }

        override fun endElement(name: String) {}
    }

    val parser = SmlSaxParser(text)
    parser.parse(handler)

    if (linesText.isNullOrBlank()) {
        if (warnOnMissingLines) {
            logger.warn("No TileMap lines block found in dungeon SML")
        }
        return DungeonParseResult(blocks, chunkSize)
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
    return DungeonParseResult(blocks, chunkSize)
}

private fun mapTileId(key: String): Int {
    if (key == "S") {
        return 8
    }
    return when (key.lowercase()) {
        "s" -> 0
        "t" -> 1
        "u" -> 2
        "v" -> 3
        "w" -> 4
        "x" -> 5
        "y" -> 6
        "z" -> 7
        else -> if (key.length == 1) key[0].code else 0
    }
}

private fun chunkBlocks(blocks: List<Block>, chunkX: Int, chunkY: Int, chunkZ: Int, chunkSize: Int): ChunkData {
    val minX = chunkX * chunkSize
    val minY = chunkY * chunkSize
    val minZ = chunkZ * chunkSize
    val maxX = minX + chunkSize - 1
    val maxY = minY + chunkSize - 1
    val maxZ = minZ + chunkSize - 1

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

private fun collectChunkCoords(blocks: List<Block>, chunkSize: Int): List<ChunkCoord> {
    val coords = blocks.map {
        ChunkCoord(
            x = Math.floorDiv(it.x, chunkSize),
            y = Math.floorDiv(it.y, chunkSize),
            z = Math.floorDiv(it.z, chunkSize)
        )
    }.distinct().sortedWith(compareBy({ it.x }, { it.y }, { it.z }))
    return coords
}

private fun resolveDungeonFolder(args: Array<String>): File {
    val envFolder = System.getenv("DUNGEON_FOLDER")?.trim().orEmpty()
    val argFolder = args.firstOrNull()?.trim().orEmpty()
    val folder = when {
        argFolder.isNotEmpty() -> argFolder
        envFolder.isNotEmpty() -> envFolder
        else -> "."
    }
    val file = File(folder)
    if (!file.exists()) {
        file.mkdirs()
    }
    return file
}

private fun parseChunkFileName(name: String): Triple<Int, Int, Int>? {
    val regex = Regex("dungeon_(-?\\d+)_(-?\\d+)_(-?\\d+)\\.sml")
    val match = regex.matchEntire(name) ?: return null
    val (x, y, z) = match.destructured
    return Triple(x.toInt(), y.toInt(), z.toInt())
}

private fun loadChunkedBlocks(folder: File, chunkSize: Int): List<Block> {
    val blocks = mutableListOf<Block>()
    if (!folder.exists()) return blocks
    val chunkFiles = folder.listFiles { file ->
        file.isFile && file.name.startsWith("dungeon_") && file.name.endsWith(".sml")
    }?.mapNotNull { file ->
        parseChunkFileName(file.name)?.let { coords -> coords to file }
    }?.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
        ?: emptyList()

    chunkFiles.forEach { (coords, file) ->
        val chunkText = file.readText()
        val chunkParse = parseDungeonBlocks(chunkText, warnOnMissingLines = false)
        val offsetX = coords.first * chunkSize
        val offsetY = coords.second * chunkSize
        val offsetZ = coords.third * chunkSize
        chunkParse.blocks.forEach { blk ->
            blocks.add(Block(blk.x + offsetX, blk.y + offsetY, blk.z + offsetZ, blk.key))
        }
    }
    if (blocks.isEmpty()) {
        logger.warn("No chunk files loaded from ${folder.absolutePath}")
    }
    logger.info("Loaded ${blocks.size} blocks from chunked dungeon files")
    return blocks
}

fun main(args: Array<String>) {
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

    val dungeonFolder = resolveDungeonFolder(args)
    val dungeonFile = File(dungeonFolder, "dungeon.sml")
    val worldSeed = resolveWorldSeed()
    val debugChunks = isDebugEnabled(ENV_CHUNK_DEBUG)
    val dungeonText = if (dungeonFile.exists()) dungeonFile.readText() else "Dungeon { TileMap { lines: \"\" } }"
    val parseResult = parseDungeonBlocks(dungeonText)
    val chunkSize = parseResult.chunkSize ?: CHUNK_SIZE
    val blocks = if (parseResult.chunkSize != null) {
        loadChunkedBlocks(dungeonFolder, chunkSize)
    } else {
        parseResult.blocks
    }

    val chunkCoords = collectChunkCoords(blocks, chunkSize)
    logger.info("World seed: $worldSeed")
    if (debugChunks) {
        logger.info("Chunk load order (${chunkCoords.size} total):")
        chunkCoords.forEachIndexed { index, coord ->
            logger.info("chunk[$index] = (${coord.x},${coord.y},${coord.z})")
        }
    }
    embeddedServer(Netty, port = 8080) {
        install(CallLogging)
        routing {
            get("/dungeon") {
                call.respondText(dungeonText, ContentType.Text.Plain)
            }
            get("/chunks") {
                val payload = chunkCoords.joinToString(separator = "\n") { "${it.x},${it.y},${it.z}" }
                call.respondText(payload, ContentType.Text.Plain)
            }
            get("/chunk") {
                val x = call.request.queryParameters["x"]?.toIntOrNull() ?: 0
                val y = call.request.queryParameters["y"]?.toIntOrNull() ?: 0
                val z = call.request.queryParameters["z"]?.toIntOrNull() ?: 0
                val chunk = chunkBlocks(blocks, x, y, z, chunkSize)
                logger.info("Serving chunk ($x,$y,$z) with ${chunk.blocks.size} blocks")
                val payload = encodeChunk(chunk)
                call.respondBytes(payload, contentType = ContentType.Application.OctetStream)
            }
        }
    }.start(wait = true)
}