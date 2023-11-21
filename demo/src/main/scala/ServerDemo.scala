package cats.netty

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.ServerSocketChannel

import cats.netty.channel.NettyToCatsChannelInitializer.Handlers
import cats.netty.channel.NettyToCatsEffectRuntimeHandler

object ServerDemo extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    (for {
      netty <- NettyServerResources.make[IO].onFinalize(IO.println("server stopped"))

      server <- Server
        .bind[IO, ServerSocketChannel](
          netty,
          Server.Host("0.0.0.0"),
          Server.Port(8081),
          (_: ServerSocketChannel) =>
            IO.println("new connection") *> IO(Handlers.fromHandler(new EchoHandler))
        )

      _ <- Resource.eval(
        server.isActive.flatMap(a => IO.println(s"server started and isActive=${a.toString}"))
      )
    } yield ()).useForever.as(ExitCode.Success)

  class EchoHandler extends NettyToCatsEffectRuntimeHandler[IO, ByteBuf] {
    override def channelReadF(msg: ByteBuf)(implicit ctx: ChannelHandlerContext): IO[Unit] = IO(
      ctx.writeAndFlush(msg)
    ).void

    override protected def userEventTriggeredF(evt: AnyRef)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] = IO.unit

    override protected def exceptionCaughtF(cause: Throwable)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] = IO.unit

    override protected def channelWritabilityChangedF(isWriteable: Boolean)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] = IO.unit

    override protected def channelInactiveF(implicit ctx: ChannelHandlerContext): IO[Unit] = IO.unit
  }
}
