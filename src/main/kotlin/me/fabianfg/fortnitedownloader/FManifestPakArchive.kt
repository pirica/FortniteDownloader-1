package me.fabianfg.fortnitedownloader

import me.fungames.jfortniteparse.exceptions.ParserException
import me.fungames.jfortniteparse.ue4.pak.objects.FPakInfo
import me.fungames.jfortniteparse.ue4.pak.reader.FPakArchive
import me.fungames.jfortniteparse.ue4.versions.Ue4Version
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun FileManifest.openPakArchive(downloader: MountedBuild, game : Ue4Version) = FManifestPakArchive(
    this,
    downloader
).apply { this.game = game.game; this.ver = game.version }

@Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_OVERRIDE")
class FManifestPakArchive : FPakArchive {

    private val file : FileManifest
    private val build : MountedBuild
    private val offsets : LongArray
    private var pos : Long
    private val size : Long
    private val mutex = ReentrantLock()
    private var currentChunk : Int
    private var currentChunkStartOffset : Int

    override var littleEndian = true

    constructor(file: FileManifest, build: MountedBuild) : super(file.fileName) {
        this.file = file
        this.build = build
        this.offsets = LongArray(file.chunkParts.size + 1)
        var totalSize = 0L
        file.chunkParts.forEachIndexed { i, chunkPart ->
            this.offsets[i] = totalSize
            totalSize += chunkPart.size
        }
        size = totalSize
        offsets[offsets.size - 1] = totalSize
        pos = 0
        currentChunk = 0
        currentChunkStartOffset = 0
    }

    private constructor(file: FileManifest, build: MountedBuild, offsets : LongArray, size : Long, initialPos : Long, initialChunk : Int, initialChunkStartOffset : Int, pakInfo : FPakInfo) : super(file.fileName) {
        this.file = file
        this.build = build
        this.offsets = offsets
        this.size = size
        this.pos = initialPos
        this.currentChunk = initialChunk
        this.currentChunkStartOffset = initialChunkStartOffset
        this.pakInfo = pakInfo
    }

    override fun clone() = FManifestPakArchive(
        file,
        build,
        offsets,
        size,
        pos,
        currentChunk,
        currentChunkStartOffset,
        pakInfo
    )

    override fun pakPos() = pos

    override fun pakSize() = size

    private fun getNeededChunks(off: Int, len: Int) = mutex.withLock {
        var bytesRead = 0
        val neededChunks = mutableListOf<MountedBuild.NeededChunk>()
        while (currentChunk < file.chunkParts.size) {
            val chunkPart = file.chunkParts[currentChunk]
            if ((len - bytesRead) > chunkPart.size - currentChunkStartOffset) {
                // copy the entire buffer over
                neededChunks.add(
                    MountedBuild.NeededChunk(
                        chunkPart,
                        currentChunkStartOffset,
                        off + bytesRead,
                        (chunkPart.size - currentChunkStartOffset)
                    )
                )
                bytesRead += (chunkPart.size - currentChunkStartOffset)
                currentChunkStartOffset = 0
                currentChunk++
            } else {
                // copy what it needs to fill up the rest
                neededChunks.add(
                    MountedBuild.NeededChunk(
                        chunkPart,
                        currentChunkStartOffset,
                        off + bytesRead,
                        len - bytesRead
                    )
                )
                currentChunkStartOffset += len - bytesRead
                if(currentChunkStartOffset >= chunkPart.size) {
                    currentChunk++
                    currentChunkStartOffset = 0
                }
                break
            }
        }
        pos += len
        return@withLock neededChunks
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        mutex.withLock {
            val neededChunks = getNeededChunks(off, len)
            if (!build.fileRead(file, b, neededChunks))
                throw ParserException("Failed to read $len bytes at $pos")
            return len
        }
    }

    override fun seek(pos: Long) {
        loop@ for (i in 0 until offsets.size - 1) {
            when {
                offsets[i] == pos -> mutex.withLock {
                    this.currentChunk = i
                    this.currentChunkStartOffset = 0
                    this.pos = pos
                    return
                }
                pos > offsets[i] && pos < offsets[i + 1] -> mutex.withLock {
                    this.currentChunk = i
                    this.currentChunkStartOffset = (pos - offsets[i]).toInt()
                    this.pos = pos
                    return
                }
            }
        }
        throw IllegalArgumentException("Invalid offset $pos in file $fileName with size $size")
    }

    override fun skip(n: Long): Long {
        getNeededChunks(0, n.toInt())
        return n
    }

}