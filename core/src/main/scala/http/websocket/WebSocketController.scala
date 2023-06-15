package cats.netty
package http.websocket

import java.net.URI
import java.security.{MessageDigest, NoSuchAlgorithmException}

import scala.jdk.CollectionConverters._

import cats.data.EitherT
import cats.effect.Sync
import cats.syntax.all._
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpVersion._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.codec.http.websocketx.extensions.{WebSocketExtensionUtil, WebSocketServerExtension, WebSocketServerExtensionHandshaker}
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.FastThreadLocal
import org.slf4j.{Logger, LoggerFactory}

import cats.netty.http.HttpController
import cats.netty.http.HttpController.RoutingLogic
import cats.netty.http.websocket.WebSocketController._

@SuppressWarnings(
  Array(
    "DisableSyntax.valInAbstract",
    "org.wartremover.warts.Null",
    "DisableSyntax.null"
  )
)
abstract class WebSocketController[F[_]](
  paths: Set[String],
  subProtocols: List[String],
  extensionHandshakers: List[WebSocketServerExtensionHandshaker]
)(implicit
  F: Sync[F]
) extends HttpController[F] {

  private val AnySubProtocolAllowed = subProtocols.contains(SubProtocolWildcard)

  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  override val routingLogic: (URI, HttpRequest) => Boolean =
    RoutingLogic.exactPaths(HttpMethod.GET, paths)

  override def handle(
    httpRequest: FullHttpRequest
  ): F[HttpController.Result[F]] =
    (for {
      wsReq <- parseWebSocketRequest(httpRequest)
        .leftSemiflatTap(resp =>
          reportBadRequest(httpRequest.uri(), httpRequest.headers(), resp.status())
            .flatMap(extraHeaders => F.delay(resp.headers().add(extraHeaders)))
            .void
            .handleErrorWith(e => F.delay(logger.error("Bad WebSocket Request Not Reported", e)))
        )

      upgrade <- validateWebSocketRequest(wsReq)
        .leftSemiflatMap { error =>
          F.delay(
            buildResponse(
              status = error match {
                case Error.BadRequest(_) =>
                  HttpResponseStatus.BAD_REQUEST
                case Error.Internal(_) =>
                  HttpResponseStatus.INTERNAL_SERVER_ERROR
              },
              error.extraHeaders
            )
          )
        }

      switchToWs <- EitherT.liftF[F, FullHttpResponse, HttpController.SwitchToWebSocket[F]](
        for {
          /*
          The accept header, a hashed concatenation of key (client nonce) & WS GUID, proves to the client that we are
          processing its WS req.
           */
          accept <- sha1[F]((wsReq.key + WebSocket13AcceptGuid).getBytes(CharsetUtil.US_ASCII))
            .flatMap(base64[F])

          response <- F.delay(
            new DefaultFullHttpResponse(
              HTTP_1_1,
              HttpResponseStatus.SWITCHING_PROTOCOLS,
              Unpooled.EMPTY_BUFFER,
              new DefaultHttpHeaders(true)
                .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
                .set(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, accept)
                .add(upgrade.extraHeaders), // `add` vs. `set` to prevent overrides
              EmptyHttpHeaders.INSTANCE
            )
          )

          // If subprotocol was negotiated, then set header on response.
          _ <- wsReq.subProtocol.traverse(sp =>
            F.delay(response.headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, sp))
          )

          _ <- F.unlessA(wsReq.extensions.isEmpty) {
            createExtensionHeader[F](wsReq.extensions)
              .flatMap(h =>
                F.delay(response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, h))
              )
          }
        } yield HttpController.SwitchToWebSocket[F](
          upgrade.config,
          upgrade.webSocketSession,
          wsReq.subProtocol,
          wsReq.extensions,
          response
        )
      )
    } yield switchToWs)
      .leftMap(HttpController.Response[F])
      .merge

  protected def validateWebSocketRequest(
    webSocketRequest: WebSocketRequest
  ): EitherT[F, Error, Upgrade[F]]

  protected def reportBadRequest(
    uri: String,
    httpHeaders: HttpHeaders,
    status: HttpResponseStatus
  ): F[HttpHeaders]

  private def parseWebSocketRequest(
    httpRequest: FullHttpRequest
  ): EitherT[F, DefaultFullHttpResponse, WebSocketRequest] = EitherT(F.delay {
    for {
      headers <- httpRequest.headers().asRight[DefaultFullHttpResponse]

      version <- Option(headers.get(HttpHeaderNames.SEC_WEBSOCKET_VERSION))
        .toRight(UpgradeRequired)

      _ <- validate(
        // All other versions in RFC were strictly draft versions, not to be used. Netty 5 only supports version 13.
        version == WebSocketVersion.V13.toHttpHeaderValue,
        error = UpgradeRequired
      )

      key <- Option(headers.get(HttpHeaderNames.SEC_WEBSOCKET_KEY))
        .toRight(BadRequest)

      extensions = Option(headers.getAsString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS)).toList
        .flatMap(extractExtensions)
        .map(ext => extensionHandshakers.collectFirstSome(hs => Option(hs.handshakeExtension(ext))))
        .collect { case Some(e) => e }

      /*
      Extensions make use of the reserved bits in WS frames before the application data. The extension can pick which
      bit of the reserved space to use. If that bit is set, then that frame will have the extension applied. These
      special bits, RSV, are defined by the WS spec.
       */
      validExtensions = extensions
        .foldLeft((0, List.empty[WebSocketServerExtension])) { case ((rsv, valid), ext) =>
          if ((rsv & ext.rsv()) == 0)
            (
              rsv | ext.rsv(), // Shift to next RSV bit since this extension is negotiated
              ext :: valid
            )
          else
            (rsv, valid) // This extension is competing for an RSV bit that's already taken, skip
        }
        ._2
        .reverse // maintain order of negotiation
    } yield WebSocketRequest(
      new QueryStringDecoder(httpRequest.uri(), true),
      httpRequest.headers(),
      key,
      selectSubprotocol(headers.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)),
      // Previous versions of Netty didn't validate these headers, so we leave them as optional until we can rule out
      // requests are coming from valid clients.
      hasConnectionUpgradeHeader =
        headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true),
      hasWebSocketUpgradeHeader =
        headers.containsValue(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true),
      validExtensions
    )
  })

  private def selectSubprotocol(subProtocolHeader: String): Option[String] =
    for {
      requestedSubprotocols <- Option.unless(subProtocolHeader == null || subProtocols.isEmpty)(
        subProtocolHeader.split(",").map(_.trim)
      )

      // Find the first subprotocol supported by the server. It may support explict protocols, any (first by client),
      // or none.
      sp <-
        if (AnySubProtocolAllowed)
          requestedSubprotocols.headOption
        else
          requestedSubprotocols.find(subProtocols.contains)
    } yield sp

}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Null",
    "DisableSyntax.null",
    "org.wartremover.warts.Throw",
    "DisableSyntax.throw"
  )
)
object WebSocketController {

  private val WebSocket13AcceptGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
  private val SubProtocolWildcard = "*"
  private val UpgradeHeaders = new DefaultHttpHeaders(true)
    .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue)

  private val SHA1 = new FastThreadLocal[MessageDigest]() {
    @throws[Exception]
    override protected def initialValue: MessageDigest = try {
      MessageDigest.getInstance("SHA1")
    } catch {
      case _: NoSuchAlgorithmException =>
        throw new InternalError("SHA-1 not supported on this platform; platform is outdated")
    }
  }

  private def sha1[F[_]: Sync](data: Array[Byte]) = Sync[F].delay {
    /*
    While Cats Effect doesn't play well with ThreadLocal since CE Fibers aren't 1-to-1 with threads, this is safe
     */
    val digest = SHA1.get
    digest.reset()
    digest.digest(data)
  }

  private def base64[F[_]: Sync](data: Array[Byte]): F[String] = Sync[F].delay {
    java.util.Base64.getEncoder.encodeToString(data)
  }

  private def validate(cond: Boolean, error: => DefaultFullHttpResponse) =
    Either.cond(cond, (), error)

  private def extractExtensions(extensionHeader: String) =
    WebSocketExtensionUtil.extractExtensions(extensionHeader).asScala.toList

  private def UpgradeRequired =
    buildResponse(HttpResponseStatus.UPGRADE_REQUIRED, extraHeaders = UpgradeHeaders)

  private def BadRequest =
    buildResponse(HttpResponseStatus.BAD_REQUEST, extraHeaders = EmptyHttpHeaders.INSTANCE)

  private def buildResponse(status: HttpResponseStatus, extraHeaders: HttpHeaders) =
    new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      status,
      Unpooled.EMPTY_BUFFER,
      new DefaultHttpHeaders(true)
        .addInt(HttpHeaderNames.CONTENT_LENGTH, 0)
        .add(extraHeaders),
      EmptyHttpHeaders.INSTANCE
    )

  /*
  Copied from
  io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionUtil.computeMergeExtensionsHeaderValue
   */
  private def createExtensionHeader[F[_]: Sync](
    extensions: List[WebSocketServerExtension]
  ): F[String] = Sync[F].delay {
    val sb = extensions
      .map(_.newReponseData())
      // Java's initial SB capacity is 16. Optimize for compression ext which may take up to 150
      .foldLeft(new StringBuilder(150)) { case (sb, ext) =>
        sb.append(ext.name())
        ext.parameters().entrySet().forEach { e =>
          sb.append(";")
            .append(e.getKey)
          if (e.getValue != null) {
            val _ = sb.append('=').append(e.getValue)
          }
        }
        sb.append(",")
      }

    if (extensions.nonEmpty)
      sb.setLength(sb.length() - 1) // adjust for comma

    sb.toString()
  }

  final case class Upgrade[F[_]](
    config: WebSocketConfig,
    webSocketSession: WebSocketSession[F],
    extraHeaders: HttpHeaders
  )

  object Upgrade {
    def apply[F[_]](
      config: WebSocketConfig,
      webSocketSession: WebSocketSession[F]
    ): Upgrade[F] =
      new Upgrade(config, webSocketSession, new DefaultHttpHeaders())
  }

  sealed abstract class Error extends Product with Serializable {
    def extraHeaders: HttpHeaders
  }

  object Error {
    final case class BadRequest(override val extraHeaders: HttpHeaders) extends Error
    final case class Internal(override val extraHeaders: HttpHeaders) extends Error
  }

  final case class WebSocketRequest(
    uri: QueryStringDecoder,
    headers: HttpHeaders,
    key: String,
    subProtocol: Option[String],
    hasConnectionUpgradeHeader: Boolean,
    hasWebSocketUpgradeHeader: Boolean,
    extensions: List[WebSocketServerExtension]
  ) {
    def path: String = uri.path()
    def queryParams: Map[String, List[String]] =
      uri.parameters().asScala.map(kv => kv.copy(_2 = kv._2.asScala.toList)).toMap
  }
}
