package me.syari.ss.votifier.net.protocol

import com.google.gson.JsonObject
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import me.syari.ss.core.scheduler.CreateScheduler.run
import me.syari.ss.votifier.Main.Companion.plugin
import me.syari.ss.votifier.api.Vote
import me.syari.ss.votifier.api.VotifierEvent
import me.syari.ss.votifier.net.VotifierSession
import me.syari.ss.votifier.util.JsonUtil.toJson
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

@Sharable
class VoteInboundHandler: SimpleChannelInboundHandler<Vote>() {
    private val lastError = AtomicLong()
    private val errorsSent = AtomicLong()

    override fun channelRead0(
        ctx: ChannelHandlerContext,
        vote: Vote
    ) {
        val session = ctx.channel().attr(VotifierSession.KEY).get()
        onVoteReceived(vote, session.version, ctx.channel().remoteAddress().toString())
        session.completeVote()
        if (session.version == VotifierSession.ProtocolVersion.ONE) {
            ctx.close()
        } else {
            val jsonObject = JsonObject().apply {
                addProperty("status", "ok")
            }
            ctx.writeAndFlush(toJson(jsonObject)).addListener(ChannelFutureListener.CLOSE)
        }
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable
    ) {
        val session = ctx.channel().attr(VotifierSession.KEY).get()
        val remoteAddress = ctx.channel().remoteAddress().toString()
        val hasCompletedVote = session.hasCompletedVote
        if (session.version == VotifierSession.ProtocolVersion.TWO) {
            val jsonObject = JsonObject().apply {
                addProperty("status", "error")
                addProperty("cause", cause.javaClass.simpleName)
                addProperty("error", cause.message)
            }
            ctx.writeAndFlush(toJson(jsonObject)).addListener(ChannelFutureListener.CLOSE)
        } else {
            ctx.close()
        }
        if (!willThrottleErrorLogging()) {
            onError(cause, hasCompletedVote, remoteAddress)
        }
    }

    private fun willThrottleErrorLogging(): Boolean {
        val lastErrorAt = lastError.get()
        val now = System.currentTimeMillis()
        return if (now <= lastErrorAt + 2000) {
            5 <= errorsSent.incrementAndGet()
        } else {
            lastError.set(now)
            errorsSent.set(0)
            false
        }
    }

    private fun onVoteReceived(
        vote: Vote,
        protocolVersion: VotifierSession.ProtocolVersion,
        remoteAddress: String
    ) {
        plugin.logger.info("Got a ${protocolVersion.humanReadable} vote record from $remoteAddress ->$vote")
        run(plugin, async = false) {
            fireVotifierEvent(vote)
        }
    }

    private fun fireVotifierEvent(vote: Vote) {
        VotifierEvent(vote.serviceName, vote.username).callEvent()
    }

    private fun onError(
        throwable: Throwable,
        alreadyHandledVote: Boolean,
        remoteAddress: String
    ) {
        if (alreadyHandledVote) {
            plugin.logger.log(
                Level.SEVERE, "Vote processed, however an exception occurred with a vote from $remoteAddress", throwable
            )
        } else {
            plugin.logger.log(Level.SEVERE, "Unable to process vote from $remoteAddress", throwable)
        }
    }
}