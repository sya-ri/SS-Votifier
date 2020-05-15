package me.syari.ss.votifier.net.protocol.v1

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.CorruptedFrameException
import me.syari.ss.votifier.BootstrapBuilder
import me.syari.ss.votifier.model.Vote
import me.syari.ss.votifier.net.protocol.v1.RSA.decrypt
import me.syari.ss.votifier.util.QuietException
import java.nio.charset.StandardCharsets

object VotifierProtocol1Decoder: ByteToMessageDecoder() {
    @Throws(QuietException::class)
    override fun decode(
        ctx: ChannelHandlerContext, buf: ByteBuf, list: MutableList<Any>
    ) {
        if (!ctx.channel().isActive) {
            buf.skipBytes(buf.readableBytes())
            return
        }
        if (buf.readableBytes() < 256) {
            return
        }
        if (256 < buf.readableBytes()) {
            throw QuietException(
                "Could not decrypt data from " + ctx.channel().remoteAddress() + " as it is too long. Attack?"
            )
        }
        var block = ByteBufUtil.getBytes(buf)
        buf.skipBytes(buf.readableBytes())
        block = try {
            decrypt(block, BootstrapBuilder.keyPair.private)
        } catch (e: Exception) {
            throw CorruptedFrameException(
                "Could not decrypt data from ${ctx.channel()
                    .remoteAddress()}. Make sure the public key on the list is correct.", e
            )
        }
        val all = String(block, StandardCharsets.US_ASCII)
        val split = all.split("\n")
        if (split.size < 5) {
            throw QuietException("Not enough fields specified in vote. This is not a NuVotifier issue. Got ${split.size} fields, but needed 5.")
        }
        if (split[0] != "VOTE") {
            throw QuietException("The VOTE opcode was not present. This is not a NuVotifier issue, but a bug with the server list.")
        }
        val vote = Vote(split[1], split[2], split[3], split[4], null)
        list.add(vote)
        ctx.pipeline().remove(this)
    }
}