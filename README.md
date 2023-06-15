# Netty4Cats

Preliminary open source Netty and Cats Effect integration.

This will ultimately be merged into [fs2-netty](https://github.com/typelevel/fs2-netty).

This library integrates Netty and Cats Effect. The latter is a developer friendly Functional Programming ecosystem. The
former is a mature and performant networking library. The goal is to allow using existing Netty pipelines (one or
more Netty handlers, such as the HttpCodec) and easily bridge into Cats Effect runtime for business logic.

Predefined pipelines, such as an HTTP server with WebSocket support is provided.

## Examples

### TCP Server

[Demo](demo/src/main/scala/ServerDemo.scala)
```scala
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
```

### HTTP Server

[Demo](demo/src/main/scala/HttpServerDemo.scala)
```scala
package cats.netty

import java.net.URI

import scala.concurrent.duration._

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.netty.handler.codec.http._

import cats.netty.http.HttpController.RoutingLogic
import cats.netty.http.{HttpController, HttpServer}

object HttpServerDemo extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    (for {
      netty <- NettyServerResources.make[IO].onFinalize(IO.println("server stopped"))

      server <- HttpServer
        .bind[IO](
          netty,
          Server.Host("0.0.0.0"),
          Server.Port(8082),
          HttpServer.Config[IO](
            serverName = "demo-http-server",
            requestTimeoutPeriod = 5.seconds,
            blockHttpPipelinedRequests = true,
            HttpServer.Config.Parsing.default,
            extraHeaders = EmptyHttpHeaders.INSTANCE,
            handleResponseStatus = s => IO.println(s"Response Status=${s.code().toString}"),
            redactHeader = _ => false
          ),
          controllers = List(new EchoController),
          onNewHttpConnection =
            socket => IO.println(s"New Connection ${socket.remoteAddress().toString}")
        )

      _ <- Resource.eval(
        server.isActive.flatMap(a => IO.println(s"server started and isActive=${a.toString}"))
      )
    } yield ()).useForever.as(ExitCode.Success)

  private class EchoController extends HttpController[IO] {
    override def routingLogic: (URI, HttpRequest) => Boolean =
      RoutingLogic.exactPath(HttpMethod.POST, "/echo")

    override def handle(httpRequest: FullHttpRequest): IO[HttpController.Result[IO]] =
      IO(
        HttpController.Response(
          new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            httpRequest.content().retain()
          )
        )
      )
  }
}
```

```shell
curl -X POST -d 'hello server' http://0.0.0.0:8082/echo
```

`hello server% `

Server Logs
```
server started and isActive=true
New Connection /127.0.0.1:62113
Response Status=200
server stopped
```
