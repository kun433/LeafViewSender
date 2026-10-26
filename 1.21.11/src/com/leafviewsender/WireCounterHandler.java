package com.leafviewsender;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * 线路字节计数器。
 *
 * 放在管道最前端（addFirst），出站方向上它是最后一个被经过的处理器，
 * 拿到的已经是「压缩 + 加密 + 加长度头」之后的字节，因此统计值就是真实的下行流量。
 *
 * 只读取大小，不修改、不释放消息；入站方向不实现，因此不影响收包。
 */
public final class WireCounterHandler extends ChannelOutboundHandlerAdapter {

    private final PlayerState state;

    public WireCounterHandler(PlayerState state) {
        this.state = state;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf buf) {
            this.state.wireBytes.add(buf.readableBytes());
        } else if (msg instanceof ByteBufHolder holder) {
            this.state.wireBytes.add(holder.content().readableBytes());
        }
        ctx.write(msg, promise);
    }
}
