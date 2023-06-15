package cats.netty
package http

import java.net.URI

import scala.util.matching.Regex

import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension
import io.netty.handler.codec.http.{HttpMethod, HttpRequest, _}

import cats.netty.http.HttpController.Result
import cats.netty.http.websocket.{WebSocketConfig, WebSocketSession}

trait HttpController[F[_]] {

  /**
    * A Function that determines if request is handle.
    * @return
    *   if true, this controller's `handle` method will be invoked.
    */
  def routingLogic: (URI, HttpRequest) => Boolean

  /**
    * Handle the request
    * @param httpRequest
    * @return
    */
  def handle(httpRequest: FullHttpRequest): F[Result[F]]

}

object HttpController {

  /**
    * Signal to conditionally remove a handler after a protocol switch when upgrading to a WebSocket
    * connection.
    */
  sealed abstract class Result[F[_]] extends Product with Serializable

  final case class Response[F[_]](value: FullHttpResponse) extends Result[F]

  final case class SwitchToWebSocket[F[_]](
    config: WebSocketConfig,
    webSocketSession: WebSocketSession[F],
    subProtocol: Option[String],
    extensions: List[WebSocketServerExtension],
    response: FullHttpResponse
  ) extends Result[F]

  object RoutingLogic {
    def exactPath(method: HttpMethod, path: String): (URI, HttpRequest) => Boolean =
      (uri, req) => req.method() == method && (uri.getPath == path)

    def exactPaths(method: HttpMethod, paths: Set[String]): (URI, HttpRequest) => Boolean =
      (uri, req) => req.method() == method && paths.contains(uri.getPath)

    def regexPath(method: HttpMethod, path: Regex): (URI, HttpRequest) => Boolean = {
      val re = path.regex
      (uri, req) => req.method() == method && uri.getPath.matches(re)
    }
  }
}
