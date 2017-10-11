package loghub.netty;

import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCounted;
import loghub.Decoder.DecodeException;
import loghub.Event;
import loghub.Helpers;
import loghub.Pipeline;
import loghub.Receiver;
import loghub.configuration.Properties;
import loghub.netty.servers.AbstractNettyServer;
import loghub.netty.servers.ServerFactory;

public abstract class NettyReceiver<S extends AbstractNettyServer<SF, BSC, SC, SA>, SF extends ServerFactory<BSC, SA>, BSC extends ServerChannel, SC extends Channel, SA extends SocketAddress, SM> extends Receiver implements ChannelConsumer<ServerBootstrap, ServerChannel, SA> {

    @Sharable
    private class EventSender extends SimpleChannelInboundHandler<Map<String, Object>> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Map<String, Object> msg) throws Exception {
            Event event = emptyEvent();
            Object addr = ctx.channel().attr(SOURCEADDRESSATTRIBUTE).get();
            if (addr != null) {
                event.put("host", addr);
            }
            populate(event, ctx, msg);
            send(event);
        }
    }

    @Sharable
    private class LogHubDecoder extends MessageToMessageDecoder<SM> {
        @Override
        protected void decode(ChannelHandlerContext ctx, SM msg, List<Object> out) {
            try {
                Map<String, Object> content = decoder.decode(getContent(msg));
                out.add(content);
            } catch (DecodeException e) {
                manageDecodeException(e);
                if (closeOnError) {
                    ctx.close();
                }
            }
        }
    }

    @Sharable
    private class SourceAddressResolver extends MessageToMessageDecoder<SM> {
        @Override
        protected void decode(ChannelHandlerContext ctx, SM msg, List<Object> out) {
            //The message is not transformeed in this step, so don't decrease reference count
            if (msg instanceof ReferenceCounted) {
                ((ReferenceCounted) msg).retain();
            }
            Object address = ResolveSourceAddress(ctx, msg);
            if(address != null) {
                ctx.channel().attr(SOURCEADDRESSATTRIBUTE).set(address);
            }
            out.add(msg);
        }
    }

    @Sharable
    private class ExceptionHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx,
                Throwable cause) throws Error {
            if (Helpers.isFatal(cause)) {
                throw (Error) cause;
            }
            logger.error("Unmannageded exception: {}", cause.getMessage());
            logger.debug("details", cause);
            if (closeOnError) {
                ctx.close();
            }
        }
    }

    private static final AttributeKey<Object> SOURCEADDRESSATTRIBUTE = AttributeKey.newInstance("SourceAddressAttibute");

    private ChannelFuture cf;
    private S server;
    protected MessageToMessageDecoder<SM> nettydecoder;
    private final EventSender sender = new EventSender();
    private final MessageToMessageDecoder<SM> resolver = new SourceAddressResolver();
    private final ChannelInboundHandlerAdapter exceptionhandler = new ExceptionHandler();
    private final boolean selfDecoder;
    private final boolean closeOnError;

    public NettyReceiver(BlockingQueue<Event> outQueue, Pipeline pipeline) {
        super(outQueue, pipeline);
        selfDecoder = getClass().isAnnotationPresent(SelfDecoder.class);
        closeOnError = getClass().isAnnotationPresent(CloseOnError.class);
    }

    @Override
    public boolean configure(Properties properties) {
        // Prepare the Netty decoder, before it's used during server creation in #getServer()
        if (! selfDecoder && nettydecoder == null && decoder != null) {
            nettydecoder = new LogHubDecoder();
        }
        server = getServer();
        cf = server.configure(properties, this);
        return cf != null && super.configure(properties);
    }

    public ChannelFuture getChannelFuture() {
        return cf;
    }

    @Override
    public void run() {
        try {
            // Wait until the server socket is closed.
            cf.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.finish();
        }
    }

    @Override
    public void addHandlers(ChannelPipeline p) {
        p.addFirst("SourceResolver", resolver);
        if (! selfDecoder) {
            p.addLast("MessageDecoder", getNettyDecoder());
        }
        p.addLast("Sender", sender);
        p.addLast("ExceptionHandler", exceptionhandler);
    }

    protected ChannelInboundHandlerAdapter getNettyDecoder() {
        if (nettydecoder == null) {
            throw new NullPointerException("nettydecoder");
        }
        return nettydecoder;
    }

    protected void populate(Event event, ChannelHandlerContext ctx, Map<String, Object> msg) {
        event.putAll(msg);
    }

    protected abstract ByteBuf getContent(SM message);

    protected abstract Object ResolveSourceAddress(ChannelHandlerContext ctx, SM message);

    public abstract SA getListenAddress();

    protected abstract S getServer();

    @Override
    public void close() {
        try {
            cf.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.getFactory().finish();
        }
        super.close();
    }

}
