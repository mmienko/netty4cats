package cats.netty

import cats.effect.{Async, Resource}
import cats.syntax.all._
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueEventLoopGroup, KQueueServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.NettyRuntime

import cats.netty.Utils.fromNetty

final case class NettyServerResources(
  private[netty] val boss: EventLoopGroup,
  private[netty] val worker: EventLoopGroup,
  private[netty] val channelClass: Class[_ <: ServerSocketChannel]
)

object NettyServerResources {

  private val OneBossThread = 1

  /**
    * Use Epoll only if available, otherwise NIO. Mac doesn't natively support Epoll. boss - single
    * threaded event loop to handle all only connections + register w/ workers worker -
    * multithreaded event loop, equal to # cores
    */

  // TODO: io-uring
  def make[F[_]](implicit F: Async[F]): Resource[F, NettyServerResources] =
    Resource.make {
      for {
        threads <- F.delay(math.max(1, NettyRuntime.availableProcessors))
        resources <- F.ifElseM[NettyServerResources](
          (
            F.delay(Epoll.isAvailable),
            F.delay(
              NettyServerResources(
                new EpollEventLoopGroup(OneBossThread),
                new EpollEventLoopGroup(threads),
                classOf[EpollServerSocketChannel]
              )
            )
          ),
          (
            F.delay(KQueue.isAvailable),
            F.delay(
              NettyServerResources(
                new KQueueEventLoopGroup(OneBossThread),
                new KQueueEventLoopGroup(threads),
                classOf[KQueueServerSocketChannel]
              )
            )
          )
        )(
          F.delay(
            NettyServerResources(
              new NioEventLoopGroup(OneBossThread),
              new NioEventLoopGroup(threads),
              classOf[NioServerSocketChannel]
            )
          )
        )
      } yield resources
    } { resources =>
      fromNetty(F.delay(resources.boss.shutdownGracefully())) *>
        fromNetty(F.delay(resources.worker.shutdownGracefully())).void
    }

}
