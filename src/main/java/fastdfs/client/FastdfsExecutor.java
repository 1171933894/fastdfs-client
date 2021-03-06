/**
 *
 */
package fastdfs.client;

import fastdfs.client.exchange.Replier;
import fastdfs.client.exchange.ReplierDecoder;
import fastdfs.client.exchange.Requestor;
import fastdfs.client.exchange.RequestorEncoder;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FastdfsExecutor
 *
 * @author liulongbiao
 */
final class FastdfsExecutor implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(FastdfsExecutor.class);

    private final EventLoopGroup loopGroup;
    private final FastdfsPoolGroup poolGroup;

    FastdfsExecutor(FastdfsSettings settings) {
        loopGroup = new NioEventLoopGroup(settings.maxThreads(), new ThreadFactory() {
            // 在匿名类里面除了需要实现接口方法外，还可以定义局部变量
            final String threadPrefix = "fastdfs-";
            final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(null, r, threadPrefix + threadNumber.getAndIncrement());
            }
        });
        poolGroup = new FastdfsPoolGroup(
                loopGroup,
                settings.connectTimeout(),
                settings.readTimeout(),
                settings.idleTimeout(),
                settings.maxConnPerHost(),
                settings.maxPendingRequests()
        );
    }

    /**
     * 访问 Fastdfs 服务器
     *
     * @param addr
     * @param encoder
     * @param decoder
     * @return
     */
    <T> CompletableFuture<T> execute(InetSocketAddress addr, Requestor.Encoder encoder, Replier.Decoder<T> decoder) {
        return execute(addr, new RequestorEncoder(encoder), new ReplierDecoder<>(decoder));
    }

    /**
     * @param addr
     * @param encoder
     * @param replier
     * @param <T>
     * @return
     */
    <T> CompletableFuture<T> execute(InetSocketAddress addr, Requestor.Encoder encoder, Replier<T> replier) {
        return execute(addr, new RequestorEncoder(encoder), replier);
    }

    /**
     * @param addr
     * @param requestor
     * @param replier
     * @param <T>
     * @return
     */
    <T> CompletableFuture<T> execute(InetSocketAddress addr, Requestor requestor, Replier<T> replier) {
        CompletableFuture<T> promise = new CompletableFuture<>();
        execute(addr, requestor, replier, promise);
        return promise;
    }

    private <T> void execute(InetSocketAddress addr, Requestor requestor, Replier<T> replier, CompletableFuture<T> promise) {
        FastdfsPool pool = poolGroup.get(addr);
        pool.acquire().addListener(new FastdfsChannelListener<>(pool, requestor, replier, promise));
    }

    @PreDestroy
    public void close() throws IOException {
        if (null != poolGroup) {
            try {
                poolGroup.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (null != loopGroup) {
            loopGroup.shutdownGracefully();
        }
    }

    private static class FastdfsChannelListener<T> implements FutureListener<Channel> {

        final FastdfsPool pool;
        final Requestor requestor;
        final Replier<T> replier;
        final CompletableFuture<T> promise;

        FastdfsChannelListener(FastdfsPool pool,
                               Requestor requestor,
                               Replier<T> replier,
                               CompletableFuture<T> promise) {
            this.pool = pool;
            this.requestor = requestor;
            this.replier = replier;
            this.promise = promise;
        }

        @Override
        public void operationComplete(Future<Channel> cf) throws Exception {

            if (cf.isCancelled()) {
                promise.cancel(true);
                return;
            }

            if (!cf.isSuccess()) {
                promise.completeExceptionally(cf.cause());
                return;
            }

            Channel channel = cf.getNow();
            promise.whenComplete((result, error) -> {
                if (null != error) {
                    channel.close().addListener(ignore -> pool.release(channel));
                    return;
                }

                pool.release(channel);
            });

            try {

                FastdfsOperation<T> fastdfsOperation = new FastdfsOperation<>(channel, requestor, replier, promise);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("execute {}", fastdfsOperation);
                }

                fastdfsOperation.execute();
            } catch (Exception e) {
                promise.completeExceptionally(e);
            }
        }
    }
}
