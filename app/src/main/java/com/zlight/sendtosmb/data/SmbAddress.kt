package com.zlight.sendtosmb.data

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A share and optional navigation root. All repository paths are relative to [basePath]. */
@ConsistentCopyVisibility
data class SmbAddress private constructor(
    val host: String,
    val port: Int,
    val share: String,
    val basePath: String,
) {
    val canonicalUrl: String
        get() = URI("smb", null, host, if (port == 445) -1 else port,
            "/" + listOf(share, basePath.replace('\\', '/')).filter(String::isNotEmpty).joinToString("/"),
            null, null).toASCIIString()

    fun resolve(path: String): String =
        listOf(basePath, normalizeRelativePath(path)).filter(String::isNotEmpty).joinToString("\\")

    companion object {
        fun parse(raw: String): SmbAddress {
            val value = raw.trim()
            require(value.isNotEmpty()) { "请输入 SMB 地址，例如 smb://192.168.1.10/共享" }
            val unc = value.startsWith("\\\\") || value.startsWith("/")
            val source = if (unc) "smb://" + value.replace('\\', '/').trimStart('/') else value
            require(source.startsWith("smb://", ignoreCase = true)) {
                "地址应使用 smb://服务器/共享 或 \\\\服务器\\共享 格式"
            }
            // UNC names are literal. URI paths use percent encoding; '+' is never a space.
            val uri = try {
                URI(if (unc) source.replace("%", "%25").replace("#", "%23").replace("?", "%3F").replace(" ", "%20")
                    else source.replace(" ", "%20"))
            } catch (_: Exception) { throw IllegalArgumentException("SMB 地址格式无效") }
            require(uri.rawUserInfo == null && uri.rawAuthority?.contains('@') != true) {
                "请在用户名和密码字段中填写凭据，不要放进地址"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "SMB 地址不支持查询参数或片段；文件名中的 # 请写为 %23"
            }
            val authority = requireNotNull(uri.rawAuthority) { "SMB 地址缺少服务器" }
            val host: String
            val portText: String?
            if (authority.startsWith('[')) {
                val end = authority.indexOf(']')
                require(end > 1) { "IPv6 地址需要使用方括号" }
                host = authority.substring(1, end)
                val suffix = authority.substring(end + 1)
                require(suffix.isEmpty() || suffix.startsWith(':')) { "服务器端口格式无效" }
                portText = if (suffix.isEmpty()) null else suffix.substring(1)
                require(host.contains(':') && host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
                    "IPv6 地址无效"
                }
            } else {
                require(authority.count { it == ':' } <= 1) { "IPv6 地址需要使用方括号" }
                val parts = authority.split(':', limit = 2)
                host = try { IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES) }
                    catch (_: Exception) { throw IllegalArgumentException("服务器名称无效") }
                portText = parts.getOrNull(1)
                require(host.isNotBlank() && host.length <= 253) { "服务器名称无效" }
            }
            val port = if (portText == null) 445 else portText.toIntOrNull()
                ?: throw IllegalArgumentException("端口必须是 1–65535 的数字")
            require(port in 1..65535) { "端口必须是 1–65535 的数字" }
            val path = decodePath(uri.rawPath.orEmpty()).removePrefix("/")
            val normalized = normalizeRelativePath(path)
            require(normalized.isNotEmpty()) { "请在服务器后填写共享名称" }
            val segments = normalized.split('\\')
            return SmbAddress(host, port, segments.first(), segments.drop(1).joinToString("\\"))
        }

        /** Reject absolute paths, parent traversal, NTFS streams and Windows path aliases. */
        fun normalizeRelativePath(path: String): String {
            require(!path.startsWith('/') && !path.startsWith('\\')) { "请使用共享内的相对路径" }
            val parts = path.replace('/', '\\').split('\\').filter { it.isNotEmpty() && it != "." }
            parts.forEach(::validateName)
            return parts.joinToString("\\")
        }

        fun validateName(name: String) {
            require(name.isNotEmpty() && name != "." && name != "..") { "文件名称不能为空或为 .、.." }
            require(name.none { it.code < 32 || it in "\\/:*?\"<>|" }) { "文件名称含有不支持的字符" }
            require(!name.endsWith('.') && !name.endsWith(' ')) { "文件名称不能以空格或句点结尾" }
        }

        fun childPath(parent: String, name: String): String {
            validateName(name)
            return listOf(normalizeRelativePath(parent), name).filter(String::isNotEmpty).joinToString("/")
                .replace('\\', '/')
        }

        fun parentPath(path: String): String = normalizeRelativePath(path).substringBeforeLast('\\', "").replace('\\', '/')

        private fun decodePath(raw: String): String {
            val bytes = ByteArrayOutputStream()
            var index = 0
            while (index < raw.length) {
                if (raw[index] == '%') {
                    require(index + 2 < raw.length) { "地址中的百分号编码无效" }
                    val code = raw.substring(index + 1, index + 3).toIntOrNull(16)
                        ?: throw IllegalArgumentException("地址中的百分号编码无效")
                    // Encoded separators can hide traversal or silently change share boundaries.
                    require(code != 0x2F && code != 0x5C) { "地址中的路径分隔符不能使用百分号编码" }
                    bytes.write(code)
                    index += 3
                } else {
                    val point = Character.codePointAt(raw, index)
                    bytes.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
                    index += Character.charCount(point)
                }
            }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: Exception) { throw IllegalArgumentException("地址中的 UTF-8 编码无效") }
        }
    }
}
