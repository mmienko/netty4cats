package cats.netty

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.ServerSocketChannel

import cats.netty.channel.ChannelHandlerAdapterF
import cats.netty.channel.NettyToCatsChannelInitializer.Handlers

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

  class EchoHandler extends ChannelHandlerAdapterF[IO, ByteBuf] {
    override def channelRead(msg: ByteBuf)(implicit ctx: ChannelHandlerContext): IO[Unit] = IO(
      ctx.writeAndFlush(msg)
    ).void
  }
}
