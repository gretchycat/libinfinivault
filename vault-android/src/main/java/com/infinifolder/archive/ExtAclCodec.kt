package com.infinifolder.archive

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ext stores compact version-1 ACLs; the provider API uses Linux's full
 * version-2 xattr representation. Never pass one encoding off as the other. */
internal object ExtAclCodec {
    fun isAcl(name: String) = name == "system.posix_acl_access" || name == "system.posix_acl_default"
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun export(bytes: ByteArray): ByteArray {
        val input = buffer(bytes)
        require(input.remaining() >= 4 && input.int == 1) { "Unsupported ext ACL encoding" }
        val entries = mutableListOf<Triple<Int, Int, Int>>()
        while (input.hasRemaining()) {
            require(input.remaining() >= 4)
            val tag = input.short.toInt() and 0xffff; val perm = input.short.toInt() and 0xffff
            val named = tag == 2 || tag == 8
            require(!named || input.remaining() >= 4)
            entries += Triple(tag, perm, if (named) input.int else -1)
        }
        val output = ByteBuffer.allocate(4 + entries.size * 8).order(ByteOrder.LITTLE_ENDIAN).putInt(2)
        entries.forEach { (tag, perm, id) -> output.putShort(tag.toShort()).putShort(perm.toShort()).putInt(id) }
        return output.array().also { validate(it, null) }
    }
    fun import(bytes: ByteArray, mode: Int?, access: Boolean): ByteArray {
        validate(bytes, if (access) mode else null)
        val input = buffer(bytes); input.int
        val output = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(1)
        while (input.hasRemaining()) {
            val tag = input.short; val perm = input.short; val id = input.int
            output.putShort(tag).putShort(perm)
            if (tag.toInt() == 2 || tag.toInt() == 8) output.putInt(id)
        }
        return output.array().copyOf(output.position())
    }
    fun validate(bytes: ByteArray, mode: Int?) {
        require(bytes.size >= 28 && (bytes.size - 4) % 8 == 0) { "Malformed POSIX ACL" }
        val input = buffer(bytes); require(input.int == 2) { "Unsupported POSIX ACL encoding" }
        val base = mutableMapOf<Int, Int>(); val names = mutableSetOf<Pair<Int, Int>>()
        val lastNamed = mutableMapOf<Int, Long>()
        var lastTag = 0; var named = false
        while (input.hasRemaining()) {
            val tag = input.short.toInt() and 0xffff; val perm = input.short.toInt() and 0xffff; val id = input.int
            require(tag in setOf(1,2,4,8,16,32) && perm in 0..7 && tag >= lastTag) { "Invalid POSIX ACL entry" }
            lastTag = tag
            if (tag == 2 || tag == 8) {
                val numericId = id.toLong() and 0xffffffffL
                require(id != -1 && names.add(tag to id) && numericId > (lastNamed[tag] ?: -1L))
                lastNamed[tag] = numericId; named = true
            }
            else { require(id == -1 && base.put(tag, perm) == null) }
        }
        require(base.keys.containsAll(setOf(1,4,32)) && (!named || 16 in base)) { "Incomplete POSIX ACL" }
        if (mode != null) require((mode shr 6 and 7) == base[1] && (mode and 7) == base[32] &&
            (mode shr 3 and 7) == (base[16] ?: base[4])) { "POSIX ACL and permission mode disagree" }
    }
}
