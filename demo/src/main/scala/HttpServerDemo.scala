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
