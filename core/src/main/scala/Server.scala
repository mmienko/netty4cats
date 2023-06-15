package cats.netty

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel

import cats.netty.Utils.fromNetty
import cats.netty.channel.{ChannelOption, NettyToCatsChannelInitializer}

class Server[F[_]: Async](channel: Channel) {

  def close: F[Unit] = fromNetty[F](Sync[F].delay(channel.close())).void

  def waitForClose: F[Unit] = fromNetty[F](Sync[F].delay(channel.closeFuture())).void

  def isActive: F[Boolean] = Sync[F].delay(channel.isActive)
}

object Server {

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.DefaultArguments",
      "org.wartremover.warts.NonUnitStatements"
    )
  )
  def bind[F[_]: Async, C <: Channel](
    resources: NettyServerResources,
    host: Host,
    port: Port,
    onNewConnection: NettyToCatsChannelInitializer.OnNewConnection[F, C],
    options: List[ChannelOption] = Nil
  ): Resource[F, Server[F]] =
    Dispatcher.parallel[F].evalMap { dispatcher =>
      Sync[F]
        .defer {
          val server = new ServerBootstrap()
            .group(resources.boss, resources.worker)
            .channel(resources.channelClass)
          // TODO: backpressure accepting TCP connections `server.option[Boolean](AUTO_READ, false)` or mutate in server class

          options.foreach(opt => server.option(opt.key, opt.value))

          server.childHandler(new NettyToCatsChannelInitializer(dispatcher, onNewConnection))

          val cf = server.bind(host.value, port.value)

          fromNetty[F](cf.pure[F]).as(new Server[F](cf.channel()))
        }
    }

  final case class Host(value: String) extends AnyVal
  final case class Port(value: Int) extends AnyVal

}
