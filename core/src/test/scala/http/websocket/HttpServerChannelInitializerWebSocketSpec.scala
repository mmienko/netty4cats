package cats.netty
package http.websocket

import java.nio.channels.ClosedChannelException
import java.util

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

import cats.data.{EitherT, NonEmptyList}
import cats.effect.std.{Dispatcher, Supervisor}
import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO}
import cats.syntax.all._
import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelHandlerContext, ChannelOutboundHandlerAdapter, ChannelPromise}
import io.netty.handler.codec.http.HttpMethod.GET
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.codec.http.websocketx.extensions._
import io.netty.handler.codec.{EncoderException, TooLongFrameException}
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.ReferenceCountUtil
import org.scalatest.BeforeAndAfterAll

import cats.netty.ResponseMatchers._
import cats.netty.Utils.ValueDiscard
import cats.netty.http.websocket.HttpServerChannelInitializerWebSocketSpec._
import cats.netty.http.{HttpClientConnection, HttpController}
import cats.netty.testkit.EmbeddedChannelF

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Var",
    "DisableSyntax.var"
  )
)
class HttpServerChannelInitializerWebSocketSpec extends BaseSpec with BeforeAndAfterAll {

  implicit val disp: Dispatcher[IO] = generalDispatcher
  implicit val supervisor: Supervisor[IO] = generalSupervisor

  "on WebSocket Upgrade" - {
    "upgrades without a subprotocol" in runIO {
      for {
        _ <- IO(Given("HTTP channel with a WebSocket controller"))
        wsSession = MockWebSocketSession()
        conn <- HttpClientConnection(controllers =
          List(
            webSocketController(
              wsSession,
              extraHeaders = new DefaultHttpHeaders().set("example-header", "example-value")
            )
          )
        )

        _ <- IO(When("client sends a WebSocket Upgrade request"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- conn.sendRequest(wsRequest)

        _ <- IO(Then("client should get an Upgrade response"))
        _ <- conn.readResponse { response: FullHttpResponse =>
          response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)

          And("response should have the extra headers added")
          response
            .headers()
            .get("example-header") shouldEqual "example-value"
        }

        _ <- IO(And("channel pipeline should be mutated to a WebSocket pipeline"))
        _ = conn.channel.underlying.pipeline().names().asScala.toList shouldEqual List(
          "wsencoder",
          "wsdecoder",
          "Utf8FrameValidator",
          "WebSocketHandler",
          "DefaultChannelPipeline$TailContext#0"
        )

        _ <- IO(And("conn should be open"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(And("WebSocket request is released"))
        _ <- IO(wsRequest.refCnt() shouldBe 0)

        _ <- IO(And("WebSocket Listener should be notified of successful handshake"))
        sp <- wsSession.subProtocol.get
        _ <- IO(sp shouldEqual None)

        _ <- IO(And("connection can cleanly close"))
        _ <- conn.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "upgrades with a subprotocol matching a server's subprotocol" in runIO {
      for {
        _ <- IO(
          Given("HTTP connection with a WebSocket controller that accepts WebSocket Requests")
        )
        wsSession = MockWebSocketSession()
        conn <- HttpClientConnection(controllers =
          List(
            webSocketController(
              wsSession,
              subProtocols = NonEmptyList.of("first", "second", "third")
            )
          )
        )

        _ <- IO(When("client sends a WebSocket Upgrade request"))
        wsRequest =
          webSocketRequest(subprotocol = "second".some)
        _ <- conn.sendRequest(wsRequest)

        _ <- IO(Then("client should get an Upgrade response"))
        _ <- conn.readResponse { response: FullHttpResponse =>
          response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)

          And("response should have the negotiated subprotocol")
          response
            .headers()
            .get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL) shouldEqual "second"
        }

        _ <- IO(And("connection should be open"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(And("WebSocket listener should be notified"))
        sp <- wsSession.subProtocol.get
        _ <- IO(sp.value shouldEqual "second")
      } yield ()
    }

    "upgrades if server accepts any subprotocol" in runIO {
      for {
        _ <- IO(
          Given("HTTP connection with a WebSocket controller that accepts WebSocket Requests")
        )
        wsSession = MockWebSocketSession()
        conn <- HttpClientConnection(controllers =
          List(
            webSocketController(
              wsSession,
              subProtocols = NonEmptyList.one("*")
            )
          )
        )

        _ <- IO(When("client sends a WebSocket Upgrade request"))
        wsRequest =
          webSocketRequest(subprotocol = "second".some)
        _ <- conn.sendRequest(wsRequest)

        _ <- IO(Then("client should get an Upgrade response"))
        _ <- conn.readResponse { response: FullHttpResponse =>
          response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)

          And("response should have the negotiated subprotocol")
          response
            .headers()
            .get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL) shouldEqual "second"
        }

        _ <- IO(And("connection should be open"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(And("WebSocket listener should be notified"))
        sp <- wsSession.subProtocol.get
        _ <- IO(sp.value shouldEqual "second")
      } yield ()
    }

    "upgrades with a subprotocol not matching a server's subprotocol" in runIO {
      for {
        _ <- IO(
          Given("HTTP connection with a WebSocket controller that accepts WebSocket Requests")
        )
        wsSession = MockWebSocketSession()
        conn <- HttpClientConnection(
          List(
            webSocketController(
              wsSession,
              subProtocols = NonEmptyList.of("first", "second", "third")
            )
          )
        )

        _ <- IO(When("client sends a WebSocket Upgrade request"))
        wsRequest =
          webSocketRequest(subprotocol = "not found".some)
        _ <- conn.sendRequest(wsRequest)

        _ <- IO(Then("client should get an Upgrade response"))
        _ <- conn.readResponse { response: FullHttpResponse =>
          response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)

          And("response should NOT have the negotiated subprotocol")
          response
            .headers()
            .contains(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL) shouldEqual false
        }

        _ <- IO(And("connection should be open"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(And("WebSocket listener should be notified"))
        sp <- wsSession.subProtocol.get
        _ <- IO(sp shouldEqual None)
      } yield ()
    }

    "upgrades without connection and upgrade headers" in runIO {
      for {
        _ <- IO(Given("HTTP connection with a WebSocket controller"))
        wsSession = MockWebSocketSession()
        conn <- HttpClientConnection(controllers = List(webSocketController(wsSession)))

        _ <- IO(When("client sends a WebSocket Upgrade request"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- IO(
          wsRequest.headers().remove(HttpHeaderNames.UPGRADE).remove(HttpHeaderNames.CONNECTION)
        )
        _ <- conn.sendRequest(wsRequest)

        _ <- IO(Then("client should get an Upgrade response"))
        _ <- conn.readResponse { response: FullHttpResponse =>
          response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)
        }

        _ <- IO(And("WebSocket Listener should be notified of successful handshake"))
        sp <- wsSession.subProtocol.get
        _ <- IO(sp shouldEqual None)

        _ <- IO(And("connection can cleanly close"))
        _ <- conn.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "multiple upgrades are safe" in runIO {
      for {
        connections <- List
          .tabulate(100)(_ =>
            HttpClientConnection(controllers = List(webSocketController(MockWebSocketSession())))
          )
          .traverse(identity)

        _ <- connections.parTraverse_ { chn =>
          val request = webSocketRequest(subprotocol = None)
          chn.sendRequest(request) *>
            chn.readResponse { response: FullHttpResponse =>
              response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)
            } *> IO(request.refCnt() shouldBe 0)
        }
      } yield ()
    }

    "rejects bad WebSocket requests" in runIO {
      def validate(response: FullHttpResponse, status: HttpResponseStatus) = {
        response.status() shouldBe status

        And("response should have the extra headers added")
        response
          .headers()
          .get("example-header") shouldEqual "example-value"
      }

      for {
        _ <- IO(Given("HTTP channel with a WebSocket controller"))
        wsSession = MockWebSocketSession()
        controller = webSocketController(
          wsSession,
          extraHeaders = new DefaultHttpHeaders().set("example-header", "example-value")
        )
        channel <- HttpClientConnection(controllers = List(controller))

        _ <- IO(When("client sends a WebSocket request w/o version"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- IO(wsRequest.headers().remove(HttpHeaderNames.SEC_WEBSOCKET_VERSION))
        _ <- channel.sendRequest(wsRequest)

        _ <- IO(Then("client should get a bad-request response"))
        _ <- channel.readResponse { response: FullHttpResponse =>
          validate(response, HttpResponseStatus.UPGRADE_REQUIRED)
          response should haveHeaderValue(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
        }

        _ <- IO(And("WebSocket is released"))
        _ <- IO(wsRequest.refCnt() shouldBe 0)

        _ <- IO(When("client sends a WebSocket request w/ wrong version"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- IO(wsRequest.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "12"))
        _ <- channel.sendRequest(wsRequest)

        _ <- IO(Then("client should get a bad-request response"))
        _ <- channel.readResponse { response: FullHttpResponse =>
          validate(response, HttpResponseStatus.UPGRADE_REQUIRED)
        }

        _ <- IO(And("WebSocket is released"))
        _ <- IO(wsRequest.refCnt() shouldBe 0)

        _ <- IO(When("client sends a WebSocket request w/o key"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- IO(wsRequest.headers().remove(HttpHeaderNames.SEC_WEBSOCKET_KEY))
        _ <- channel.sendRequest(wsRequest)

        _ <- IO(Then("client should get a bad-request response"))
        _ <- channel.readResponse { response: FullHttpResponse =>
          validate(response, HttpResponseStatus.BAD_REQUEST)
        }

        _ <- IO(controller.getBadRequests shouldBe 3)

        _ <- IO(And("WebSocket is released"))
        _ <- IO(wsRequest.refCnt() shouldBe 0)

        _ <- IO(And("channel should remain open"))
        _ <- IO(channel.isActive shouldBe true)
      } yield ()
    }

    "rejects bad WebSocket requests even if WebSocket controller fails" in runIO {
      for {
        _ <- IO(Given("HTTP channel with a WebSocket controller"))
        channel <- HttpClientConnection(controllers = List(ErrorWebSocketController))

        _ <- IO(When("client sends a bad WebSocket Upgrade request"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- IO(wsRequest.headers().remove(HttpHeaderNames.SEC_WEBSOCKET_KEY))
        _ <- channel.sendRequest(wsRequest)

        _ <- IO(Then("client should get a bad-request response"))
        _ <- channel.readResponse { response: FullHttpResponse =>
          response.status() shouldBe HttpResponseStatus.BAD_REQUEST
        }

        _ <- IO(And("channel should be closed"))
        _ <- IO(channel.isActive shouldBe true)

        _ <- IO(And("WebSocket is released"))
        _ <- IO(wsRequest.refCnt() shouldBe 0)
      } yield ()
    }

    "500 if WebSocket Controller errors while processing valid WebSocket request" in runIO {
      for {
        _ <- IO(Given("HTTP channel with a WebSocket controller"))
        conn <- HttpClientConnection(controllers = List(ErrorWebSocketController))

        _ <- IO(When("client sends a WebSocket Upgrade request"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- conn.sendRequest(wsRequest)

        _ <- IO(Then("client should get a bad-request response"))
        _ <- conn.readResponse { response: FullHttpResponse =>
          response.status() shouldBe HttpResponseStatus.INTERNAL_SERVER_ERROR
        }

        _ <- IO(And("channel should be closed"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(And("WebSocket is released"))
        _ <- IO(wsRequest.refCnt() shouldBe 0)
      } yield ()
    }

    "no-op if rejects a bad WebSocket Request and WebSocket Listener has an error" in runIO {
      for {
        _ <- IO(Given("HTTP channel with a WebSocket controller"))
        conn <- HttpClientConnection(controllers =
          List(webSocketController(ErrorWebSocketListener))
        )

        _ <- IO(When("client sends a WebSocket Upgrade request"))
        wsRequest = webSocketRequest(subprotocol = None)
        _ <- conn.sendRequest(wsRequest)
        _ <- conn.close

        _ <- IO(Then("channel should be closed"))
        _ <- IO(conn.isActive shouldBe false)

        _ <- IO(And("WebSocket is released"))
        _ <- IO(wsRequest.refCnt() shouldBe 0)

        _ <- IO(And("channel can cleanly close"))
        _ <- conn.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "closes channel if WebSocket Listener raises exception" in runIO {
      for {
        _ <- IO(Given(s"a WebSocket channel"))
        _ <- IO(When("WebSocket Listener throws an error on handshake complete"))
        wsListener = ErrorWebSocketListener
        channel <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(Then("close from should be sent"))
        _ <- channel.readWebSocketFrame { frame: CloseWebSocketFrame =>
          frame.statusCode() shouldBe 1011
          frame.reasonText() shouldBe "Internal server error"
        }

        _ <- IO(And("connection should be closed"))
        _ <- IO(channel.isActive shouldBe false)

        _ <- IO(And("WebSocket Listener should be notified of close"))
        reason =
          (WebSocketCloseStatus.INTERNAL_SERVER_ERROR, CloseInitiator.Server)
        _ <- wsListener.closeReason.get.map(_ shouldBe reason)

        _ <- channel.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "slow processing on connected processing still sees Netty events in order" in runIO {
      for {
        _ <- IO(Given(s"a WebSocket channel"))
        _ <- IO(And("a slow WebSocket Listener"))
        gate1 <- Deferred[IO, Unit]
        gate2 <- Deferred[IO, Unit]
        wsListener = new MockWebSocketSession {
          override def connected(
            subProtocol: Option[String],
            webSocket: WebSocket[IO],
            pipelineEventScheduler: PipelineEventScheduler[IO]
          )(implicit ctx: ChannelHandlerContext): IO[Unit] = gate1.get *> gate2.complete(()).void
        }
        conn <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(Then("reads are backpressured"))
        _ <- IO(conn.underlying.config().isAutoRead shouldBe false)

        _ <- IO(When("connection is closed"))
        _ <- conn.close
        _ <- IO(conn.underlying.runPendingTasks())

        _ <- IO(And("CE runtime finished"))
        _ <- gate1.complete(())
        _ <- gate2.get

        _ <- IO(Then("reads are no longer backpressured"))
        _ <- IO(conn.underlying.config().isAutoRead shouldBe true)

        _ <- IO(And("WebSocket Listener should be notified of close"))
        reason =
          (WebSocketCloseStatus.ABNORMAL_CLOSURE, CloseInitiator.Client)
        _ <- conn.channel.processTasksUntil(!conn.isActive)
        _ <- wsListener.closeReason.get.map(_ shouldBe reason)
      } yield ()
    }

    "extensions" - {
      "non-compatible extensions are negotiated" in runIO {
        for {
          _ <- IO(Given("HTTP connection with a WebSocket controller"))
          wsSession = MockWebSocketSession()
          conn <- HttpClientConnection(controllers =
            List(
              webSocketController(
                wsSession,
                extensions = List(
                  new MockWebSocketServerExtensionHandshaker(
                    "ext_1",
                    rsv = WebSocketExtension.RSV1
                  ),
                  new MockWebSocketServerExtensionHandshaker(
                    "ext_2",
                    rsv = WebSocketExtension.RSV1
                  )
                )
              )
            )
          )

          _ <- IO(When("client sends a WebSocket Upgrade request"))
          wsRequest = webSocketRequest(
            subprotocol = None,
            extensions = List("ext_1", "ext_2")
          )
          _ <- conn.sendRequest(wsRequest)

          _ <- IO(Then("client should get an Upgrade response"))
          _ <- conn.readResponse { response: FullHttpResponse =>
            response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)

            And("response should have extension")
            /*
            These extensions are "non-compatible" in the sense that they compete for the first RSV bit (space).
            Since "ext_1" is first, then it gets chosen.
             */
            response
              .headers()
              .get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS) shouldEqual "ext_1"
          }

          _ <- IO(
            And("conn pipeline should be mutated to a WebSocket pipeline with extension codex")
          )
          _ = conn.channel.underlying.pipeline().names().asScala.toList shouldEqual List(
            "wsencoder",
            "wsdecoder",
            "HttpServerChannelInitializerWebSocketSpec$TestWebSocketExtensionDecoder#0",
            "HttpServerChannelInitializerWebSocketSpec$TestWebSocketExtensionEncoder#0",
            "Utf8FrameValidator",
            "WebSocketHandler",
            "DefaultChannelPipeline$TailContext#0"
          )

          _ <- IO(And("connection can cleanly close"))
          _ <- conn.finishAndReleaseAll.map(_ shouldBe false)
        } yield ()
      }

      "compatible extensions are negotiated" in runIO {
        for {
          _ <- IO(Given("HTTP channel with a WebSocket controller"))
          wsSession = MockWebSocketSession()
          conn <- HttpClientConnection(controllers =
            List(
              webSocketController(
                wsSession,
                extensions = List(
                  new MockWebSocketServerExtensionHandshaker(
                    "ext_1",
                    rsv = WebSocketExtension.RSV1
                  ),
                  new MockWebSocketServerExtensionHandshaker(
                    "ext_2",
                    rsv = WebSocketExtension.RSV2
                  )
                )
              )
            )
          )

          _ <- IO(When("client sends a WebSocket Upgrade request"))
          wsRequest = webSocketRequest(
            subprotocol = None,
            extensions = List("ext_1", "ext_2")
          )
          _ <- conn.sendRequest(wsRequest)

          _ <- IO(Then("client should get an Upgrade response"))
          _ <- conn.readResponse { response: FullHttpResponse =>
            response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)

            And("response should have extension")
            response
              .headers()
              .get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS) shouldEqual "ext_1,ext_2"
          }

          _ <- IO(
            And("channel pipeline should be mutated to a WebSocket pipeline with extension codex")
          )
          _ = conn.channel.underlying.pipeline().names().asScala.toList shouldEqual List(
            "wsencoder",
            "wsdecoder",
            "HttpServerChannelInitializerWebSocketSpec$TestWebSocketExtensionDecoder#0",
            "HttpServerChannelInitializerWebSocketSpec$TestWebSocketExtensionEncoder#0",
            "HttpServerChannelInitializerWebSocketSpec$TestWebSocketExtensionDecoder#1",
            "HttpServerChannelInitializerWebSocketSpec$TestWebSocketExtensionEncoder#1",
            "Utf8FrameValidator",
            "WebSocketHandler",
            "DefaultChannelPipeline$TailContext#0"
          )

          _ <- IO(And("channel can cleanly close"))
          _ <- conn.finishAndReleaseAll.map(_ shouldBe false)
        } yield ()
      }

      "mismatched extensions are not negotiated" in runIO {
        for {
          _ <- IO(Given("HTTP channel with a WebSocket controller"))
          wsSession = MockWebSocketSession()
          conn <- HttpClientConnection(controllers =
            List(
              webSocketController(
                wsSession,
                extensions = List(
                  new MockWebSocketServerExtensionHandshaker(
                    "ext_1",
                    rsv = WebSocketExtension.RSV1
                  ),
                  new MockWebSocketServerExtensionHandshaker(
                    "ext_2",
                    rsv = WebSocketExtension.RSV2
                  )
                )
              )
            )
          )

          _ <- IO(When("client sends a WebSocket Upgrade request"))
          wsRequest = webSocketRequest(
            subprotocol = None,
            extensions = List("unknown_ext_1", "unknown_ext_2")
          )
          _ <- conn.sendRequest(wsRequest)

          _ <- IO(Then("client should get an Upgrade response"))
          _ <- conn.readResponse { response: FullHttpResponse =>
            response should beWebSocketResponse(acceptKey = WebSocketAcceptKey)

            And("response shouldNOT  have extension")
            response.headers().contains(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS) shouldBe false
          }

          _ <- IO(
            And("channel pipeline should be mutated to a WebSocket pipeline with extension codex")
          )
          _ = conn.channel.underlying.pipeline().names().asScala.toList shouldEqual List(
            "wsencoder",
            "wsdecoder",
            "Utf8FrameValidator",
            "WebSocketHandler",
            "DefaultChannelPipeline$TailContext#0"
          )

          _ <- IO(And("channel can cleanly close"))
          _ <- conn.finishAndReleaseAll.map(_ shouldBe false)
        } yield ()

      }
    }
  }

  "after WebSocket Upgrade, on an establish WebSocket channel" - {
    "all client sent ping/pong control-frames and data-frames notify WebSocket Listener" in runIO {
      for {
        _ <- IO(Given(s"a WebSocket channel"))
        wsListener = MockWebSocketSession()
        conn <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(And("control frames to send"))
        controlFrames = List(
          () => new PingWebSocketFrame(),
          () => new PongWebSocketFrame()
        )

        _ <- IO(And("data frames to send"))
        dataFrames = List(
          () => new TextWebSocketFrame("with data"),
          () => new BinaryWebSocketFrame(),
          /*
        Order matters for below, start with a non final ws frame, then sent an intermediate
           */
          () => new TextWebSocketFrame(false, 0, Unpooled.buffer(0)),
          () => new ContinuationWebSocketFrame(false, 0, Unpooled.buffer(0)),
          () => new ContinuationWebSocketFrame()
        )

        _ <- (controlFrames ::: dataFrames).traverse_ { frameFactory =>
          for {
            frameToSend <- IO(frameFactory())
            frameName = frameToSend.getClass.getSimpleName
            _ <- IO(When(s"client sends a $frameName"))
            _ <- conn.sendWebSocketFrame(frameToSend)

            _ <- IO(Then("WebSocket Listener should get the frame"))
            _ <- conn.channel.processTasksUntil(wsListener.hasFrames)
            frames = wsListener.getAllFrames
            _ <- IO(frames shouldBe List(frameFactory()))

            _ <- IO(Then("channel processes the frames w/o any side effects."))
            _ <- IO(conn.underlying.outboundMessages().isEmpty shouldBe true)

            _ <- IO(And("frame isn't released yet (listener are currently responsible for that)"))
            _ = withClue(frameName) {
              frames.foreach(_.refCnt() shouldBe 1)
              // release so that Netty leak detector won't spam logs
              frames.foreach(_.release())
            }
          } yield ()
        }

        _ <- conn.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "client sent frames with WebSocket Listener log error" in runIO {
      var frame = none[WebSocketFrame]
      for {
        _ <- IO(Given(s"a WebSocket channel that errors on handling frames"))
        wsListener = new MockWebSocketSession {
          override def handleFrame(f: WebSocketFrame): IO[Unit] =
            IO { frame = f.some } *> IO.raiseError[Unit](
              new Exception("unit test exception") with NoStackTrace
            )
        }
        conn <- createWebSocketChannel(
          webSocketController(wsListener, maxFramePayloadLength = 1000)
        )

        _ <- IO(When("client sends a frame"))
        txtFrame = new TextWebSocketFrame(
          "payload must be nonempty because Netty won't allocate BytBuffer as an optimization, hence" +
            "nothing needs to be released, hence refcount checks will fail in tests"
        )
        _ <- conn.sendWebSocketFrame(txtFrame)

        _ <- IO(Then("frame is released"))
        _ <- conn.channel.processTasksUntil(frame.isDefined && frame.value.refCnt() != 1)
        _ <- IO(frame.value.refCnt() shouldBe 0)

        _ <- IO(And("channel is still open"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(And("WebSocket Listener is NOT notified of error"))
        _ <- wsListener.exception.tryGet.map(_ shouldBe None)
      } yield ()
    }

    "all server sent data frames sends appear on channel" in runIO {
      for {
        _ <- IO(Given(s"a WebSocket channel"))
        wsListener = MockWebSocketSession()
        channel <- createWebSocketChannel(webSocketController(wsListener))
        ws <- wsListener.webSocket.get

        _ <- IO(When("server sends a text data frame"))
        _ <- ws.writeAndFlush(new TextWebSocketFrame("hello"))

        _ <- IO(And("server sends a binary data frame"))
        _ <- ws.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer("hello".getBytes())))

        _ <- IO(Then("client channel should see the text frame"))
        _ <- channel.readWebSocketFrame { frame: TextWebSocketFrame =>
          frame shouldEqual new TextWebSocketFrame("hello")
        }

        _ <- IO(And("client channel should see the binary frame"))
        _ <- channel.readWebSocketFrame { frame: BinaryWebSocketFrame =>
          frame shouldEqual new BinaryWebSocketFrame(
            Unpooled.wrappedBuffer("hello".getBytes())
          )
        }

        _ <- IO(When("server sends a text data frame sync"))
        _ <- ws.writeAndFlushSync(new TextWebSocketFrame("hello"))

        _ <- IO(And("server sends a binary data frame sync"))
        _ <- ws.writeAndFlushSync(
          new BinaryWebSocketFrame(Unpooled.wrappedBuffer("hello".getBytes()))
        )

        _ <- IO(Then("client channel should see the text frame"))
        _ <- channel.readWebSocketFrame { frame: TextWebSocketFrame =>
          frame shouldEqual new TextWebSocketFrame("hello")
        }

        _ <- IO(And("client channel should see the binary frame"))
        _ <- channel.readWebSocketFrame { frame: BinaryWebSocketFrame =>
          frame shouldEqual new BinaryWebSocketFrame(
            Unpooled.wrappedBuffer("hello".getBytes())
          )
        }
      } yield ()
    }

    "all server sent ping/pong frames appear on channel" in runIO {
      def payload() = Unpooled.wrappedBuffer("hello".getBytes())
      for {
        _ <- IO(Given(s"a WebSocket channel"))
        wsListener = MockWebSocketSession()
        channel <- createWebSocketChannel(webSocketController(wsListener))
        ws <- wsListener.webSocket.get

        _ <- IO(When("server sends a ping frame"))
        _ <- ws.writeAndFlush(new PingWebSocketFrame(payload()))

        _ <- IO(And("server sends a pong frame"))
        _ <- ws.writeAndFlush(new PongWebSocketFrame(payload()))

        _ <- IO(Then("client channel should see the text frame"))
        _ <- channel.readWebSocketFrame { frame: PingWebSocketFrame =>
          frame shouldEqual new PingWebSocketFrame(payload())
        }

        _ <- IO(And("client channel should see the binary frame"))
        _ <- channel.readWebSocketFrame { frame: PongWebSocketFrame =>
          frame shouldEqual new PongWebSocketFrame(payload())
        }

        _ <- IO(When("server sends a ping frame sync"))
        _ <- ws.writeAndFlushSync(new PingWebSocketFrame(payload()))

        _ <- IO(And("server sends a pong frame sync"))
        _ <- ws.writeAndFlushSync(new PongWebSocketFrame(payload()))

        _ <- IO(Then("client channel should see the text frame"))
        _ <- channel.readWebSocketFrame { frame: PingWebSocketFrame =>
          frame shouldEqual new PingWebSocketFrame(payload())
        }

        _ <- IO(And("client channel should see the binary frame"))
        _ <- channel.readWebSocketFrame { frame: PongWebSocketFrame =>
          frame shouldEqual new PongWebSocketFrame(payload())
        }
      } yield ()
    }

    "frames are validated from server" - {
      "too large frames are rejected" in runIO {
        for {
          _ <- IO(Given(s"a WebSocket channel"))
          wsListener = MockWebSocketSession()
          channel <- createWebSocketChannel(webSocketController(wsListener))
          ws <- wsListener.webSocket.get

          _ <- IO(When("server sends a ping frame with length over 125"))
          _ <- ws.writeAndFlush(
            new PingWebSocketFrame(Unpooled.wrappedBuffer(("a" * 126).getBytes()))
          )

          _ <- IO(Then("channel should not send anything to client"))
          _ <- IO(channel.underlying.outboundMessages().isEmpty shouldBe true)

          _ <- IO(And("WebSocket Listener should NOT get notified of the exception"))
          _ <- wsListener.exception.tryGet.map(_ shouldBe None)

          _ <- IO(When("server sends a ping frame sync with length over 125"))
          res <- ws
            .writeAndFlushSync(
              new PingWebSocketFrame(Unpooled.wrappedBuffer(("a" * 126).getBytes()))
            )
            .attempt

          _ <- IO(Then("channel should not send anything to client"))
          _ <- IO(channel.underlying.outboundMessages().isEmpty shouldBe true)

          _ <- IO(And("WebSocket Listener should NOT get notified of the exception"))
          _ <- wsListener.exception.tryGet.map(_ shouldBe None)

          _ <- IO(And("the result of writeAndFlush should have an error"))
          _ <- IO(
            res.left.value.toString shouldEqual new EncoderException(
              new TooLongFrameException(
                "invalid payload for PING (payload length must be <= 125, was 126"
              )
            ).toString
          )
        } yield ()
      }
    }

    "frames are validated from client" - {
      "text frames must be UTF-8" in runIO {
        for {
          _ <- IO(Given(s"a WebSocket channel"))
          wsListener = MockWebSocketSession()
          channel <- createWebSocketChannel(webSocketController(wsListener))

          _ <- IO(When("client sends a text frame with invalid utf8"))
          _ <- channel.sendWebSocketFrame(
            new TextWebSocketFrame(Unpooled.copiedBuffer(Array(0xff.byteValue())))
          )

          _ <- IO(And("close frame is sent by server"))
          _ <- channel.readWebSocketFrame { frame: CloseWebSocketFrame =>
            frame.statusCode() shouldBe 1007
            frame.reasonText() shouldBe "bytes are not UTF-8"
          }

          _ <- IO(And("channel should be closed"))
          _ <- IO(channel.isActive shouldBe false)

          _ <- IO(And("WebSocket Listener should be notified of close"))
          reason = (
            new WebSocketCloseStatus(1007, "bytes are not UTF-8"),
            CloseInitiator.Server
          )
          _ <- wsListener.closeReason.get.map(_ shouldBe reason)

          _ <- IO(And("WebSocket Listener should not get the exception"))
          _ <- wsListener.exception.tryGet.map(_ shouldBe None)
        } yield ()
      }

      "too large frames are rejected" in runIO {
        for {
          _ <- IO(Given(s"a WebSocket channel"))
          wsListener = MockWebSocketSession()
          channel <- createWebSocketChannel(
            webSocketController(wsListener, maxFramePayloadLength = 1)
          )

          _ <- IO(When(s"client sends a malformed frame"))
          _ <- channel.sendWebSocketFrame(new TextWebSocketFrame("--"))

          _ <- IO(Then("close frame is sent by server"))
          _ <- channel.readWebSocketFrame { frame: CloseWebSocketFrame =>
            frame.statusCode() shouldBe 1009
            frame.reasonText() shouldBe "Max frame length of 1 has been exceeded."
          }

          _ <- IO(And("channel should be closed"))
          _ <- IO(channel.isActive shouldBe false)

          _ <- IO(And("WebSocket Listener should be notified of close"))
          reason = (
            new WebSocketCloseStatus(
              1009,
              "Max frame length of 1 has been exceeded."
            ),
            CloseInitiator.Server
          )
          _ <- wsListener.closeReason.get.map(_ shouldBe reason)

          _ <- IO(And("WebSocket Listener should not get the exception"))
          _ <- wsListener.exception.tryGet.map(_ shouldBe None)
        } yield ()
      }

      "malformed frames are rejected and server closes channel" in runIO {
        for {
          _ <- IO(Given(s"a WebSocket channel"))
          wsListener = MockWebSocketSession()
          channel <- createWebSocketChannel(webSocketController(wsListener))

          _ <- IO(When(s"client sends a malformed frame"))
          _ <- IO(channel.underlying.writeInbound(Unpooled.wrappedBuffer("not a frame".getBytes())))

          _ <- IO(And("close frame is sent by server"))
          _ <- channel.readWebSocketFrame { frame: CloseWebSocketFrame =>
            frame.statusCode() shouldBe 1002
            frame
              .reasonText() shouldBe "RSV != 0 and no extension negotiated, RSV:6"
          }

          _ <- IO(And("channel should be closed"))
          _ <- IO(channel.isActive shouldBe false)

          _ <- IO(And("WebSocket Listener should be notified of close"))
          reason = (
            new WebSocketCloseStatus(
              1002,
              "RSV != 0 and no extension negotiated, RSV:6"
            ),
            CloseInitiator.Server
          )
          _ <- wsListener.closeReason.get.map(_ shouldBe reason)

          _ <- IO(And("WebSocket Listener should not get the exception"))
          _ <- wsListener.exception.tryGet.map(_ shouldBe None)
        } yield ()
      }

      "continuation frames must be sent in order" in runIO {
        List(
          () => new ContinuationWebSocketFrame(false, 0, Unpooled.buffer(0)),
          () => new ContinuationWebSocketFrame() // isFinal in default constructor
        ).traverse_ { makeFrame =>
          for {
            _ <- IO(Given(s"a WebSocket channel"))
            wsListener = MockWebSocketSession()
            channel <- createWebSocketChannel(webSocketController(wsListener))

            _ <- IO(When(s"client sends a continuation frames"))
            _ <- channel.sendWebSocketFrame(makeFrame())

            _ <- IO(Then("WebSocket Listener should NOT get the frame"))
            _ <- IO(wsListener.getAllFrames shouldBe List.empty[WebSocketFrame])

            _ <- IO(And("close frame is sent by server"))
            _ <- channel.readWebSocketFrame { frame: CloseWebSocketFrame =>
              frame.statusCode() shouldBe 1002
              frame
                .reasonText() shouldBe "received continuation data frame outside fragmented message"
            }

            _ <- IO(And("channel should be closed"))
            _ <- IO(channel.isActive shouldBe false)

            _ <- IO(And("WebSocket Listener should be notified of close"))
            reason = (
              new WebSocketCloseStatus(
                1002,
                "received continuation data frame outside fragmented message"
              ),
              CloseInitiator.Server
            )
            _ <- wsListener.closeReason.get.map(_ shouldBe reason)

            _ <- IO(And("WebSocket Listener should not get the exception"))
            _ <- wsListener.exception.tryGet.map(_ shouldBe None)
          } yield ()
        }
      }
    }

    "closing channel from server" - {
      "sends a close frame and closes the channel" in runIO {
        List(
          WebSocketCloseStatus.NORMAL_CLOSURE,
          WebSocketCloseStatus.MESSAGE_TOO_BIG,
          WebSocketCloseStatus.INTERNAL_SERVER_ERROR
        ).traverse_ { closeStatus =>
          for {
            _ <- IO(Given(s"a WebSocket channel"))
            wsListener = MockWebSocketSession()
            channel <- createWebSocketChannel(webSocketController(wsListener))

            _ <- IO(When(s"closing the WebSocket with ${closeStatus.reasonText()}"))
            _ <- wsListener.webSocket.get.flatMap(_.close(closeStatus))

            _ <- IO(Then("client gets a close frame with status code"))
            _ <- channel.readWebSocketFrame { closeFrame: CloseWebSocketFrame =>
              closeFrame.statusCode() shouldEqual closeStatus.code()
            }

            _ <- IO(And("channel should be closed"))
            _ <- IO(channel.isActive shouldBe false)

            _ <- IO(Then("WebSocket Listener is notified of close"))
            closeReason = (closeStatus, CloseInitiator.Application)
            _ <- wsListener.closeReason.get.map(_ shouldBe closeReason)
          } yield ()
        }
      }

      "is idempotent" in runIO {
        for {
          _ <- IO(Given("a closed WebSocket channel"))
          wsListener = MockWebSocketSession()
          channel <- createWebSocketChannel(webSocketController(wsListener))
          ws <- wsListener.webSocket.get
          _ <- ws.close(WebSocketCloseStatus.NORMAL_CLOSURE)
          _ <- channel.readWebSocketFrame((_: WebSocketFrame) => ())

          _ <- IO(When(s"closing the WebSocket"))
          _ <- ws.close(WebSocketCloseStatus.INTERNAL_SERVER_ERROR)

          _ <- IO(Then("client doesn't get any more frames"))
          _ <- IO(channel.underlying.outboundMessages().isEmpty shouldBe true)

          _ <- IO(And("channel has no exceptions"))
          // this will change once we don't fire-and-forget, but instead look at channel promise
          _ <- IO(channel.underlying.checkException())

          _ <- IO(
            Then(
              "WebSocket Listener is notified of first close, i.e. second close doesn't change the close reason"
            )
          )
          closeReason =
            (WebSocketCloseStatus.NORMAL_CLOSURE, CloseInitiator.Application)
          _ <- wsListener.closeReason.get.map(_ shouldBe closeReason)
        } yield ()
      }

      "force closes if close frame takes too long to flush" in runIO {
        for {
          _ <- IO(Given(s"a WebSocket channel that never flushes (b/c it's back-pressured)"))
          wsListener = MockWebSocketSession()
          conn <- createWebSocketChannel(
            webSocketController(wsListener, closeTimeout = 100.milliseconds)
          )
          _ <- addClientSimulatedBackPressureHandler(conn.channel)
          ws <- wsListener.webSocket.get

          _ <- IO(When("closing the channel"))
          _ <- ws.close(WebSocketCloseStatus.NORMAL_CLOSURE)

          _ <- IO(Then("it shouldn't immediately close"))
          _ <- IO(conn.isActive shouldBe true)

          _ <- IO(And("WebSocket Listener is notified yet"))
          _ <- wsListener.closeReason.tryGet.map(_ shouldBe None)

          _ <- IO(When("waiting over the force timeout period"))
          _ <- IO.sleep(250.millis)
          _ <- IO(conn.underlying.runPendingTasks())

          _ <- IO(Then("channel should be closed"))
          _ <- IO(conn.isActive shouldBe false)

          _ <- IO(Then("WebSocket Listener is notified of abnormal close"))
          closeReason =
            (WebSocketCloseStatus.ABNORMAL_CLOSURE, CloseInitiator.Server)
          _ <- wsListener.closeReason.get.map(_ shouldBe closeReason)
        } yield ()
      }

      "won't send any further frames after close" in runIO {
        for {
          _ <- IO(Given(s"a WebSocket channel"))
          wsListener = MockWebSocketSession()
          channel <- createWebSocketChannel(webSocketController(wsListener))
          _ <- wsListener.webSocket.get.flatMap(_.close(WebSocketCloseStatus.NORMAL_CLOSURE))

          _ <- channel.readWebSocketFrame((_: WebSocketFrame) => ())
          ws <- wsListener.webSocket.get

          _ <- List(
            ws.writeAndFlushSync(new TextWebSocketFrame("hello")),
            ws.writeAndFlushSync(
              new BinaryWebSocketFrame(Unpooled.wrappedBuffer("hello".getBytes()))
            ),
            ws.writeAndFlushSync(new PingWebSocketFrame()),
            ws.writeAndFlushSync(new PongWebSocketFrame())
          ).traverse_ { writeIO =>
            for {
              _ <- IO(When("sending frame"))
              writeRes <- writeIO.attempt

              _ <- IO(Then("the write should fail"))
              // Compiler doesn't find this class
              _ <- IO(
                writeRes.left.value.toString shouldEqual "java.nio.channels.ClosedChannelException"
              )

              _ <- IO(And("we should NOT see any frames on channel"))
              _ <- IO(channel.underlying.outboundMessages().isEmpty shouldBe true)
            } yield ()
          }
        } yield ()
      }

      "also won't send any further frames if close frame takes too long to flush" in runIO {
        for {
          _ <- IO(Given(s"a WebSocket channel that never flushes (b/c it's back-pressured)"))
          wsListener = MockWebSocketSession()
          conn <- createWebSocketChannel(
            webSocketController(wsListener, closeTimeout = 100.milliseconds)
          )
          _ <- addClientSimulatedBackPressureHandler(conn.channel)
          ws <- wsListener.webSocket.get

          _ <- IO(When("closing the channel"))
          _ <- ws.close(WebSocketCloseStatus.NORMAL_CLOSURE)

          _ <- IO(And("sending another data frame"))
          frame1 = new TextWebSocketFrame("one")
          frame2 = new TextWebSocketFrame("one")
          _ <- ws.writeAndFlush(frame1)
          syncRes <- ws.writeAndFlushSync(frame2).attempt

          _ <- IO(Then("data frames should be release"))
          _ <- IO(frame1.refCnt() shouldBe 0)
          _ <- IO(frame2.refCnt() shouldBe 0)

          _ <- IO(And("sync send should get notified channel is closed"))
          _ <- IO(syncRes.left.value shouldBe a[ClosedChannelException])

          _ <- IO(And("channel still shouldn't immediately close"))
          _ <- IO(conn.isActive shouldBe true)

          _ <- IO(When("waiting over the force timeout period"))
          _ <- IO.sleep(200.millis)
          _ <- IO(conn.underlying.runPendingTasks())

          _ <- IO(Then("channel is closed"))
          _ <- IO(conn.isActive shouldBe false)
        } yield ()
      }
    }

    "closing channel from client" - {
      "without a close frame results in abnormal close" in runIO {
        for {
          _ <- IO(Given("a WebSocket channel that closes after handshake"))
          wsListener = MockWebSocketSession()
          channel <- createWebSocketChannel(
            webSocketController(wsListener, closeTimeout = 100.milliseconds)
          )

          _ <- IO(When("WebSocket connection is established but client closes the connection"))
          _ <- IO(
            channel.underlying
              .pipeline()
              .addFirst(new ChannelOutboundHandlerAdapter {
                // To mimic client forcefully closing connection, we stick a handler that immediately closes the channel.
                // It resides at the front of the pipeline w/o so that close event doesn't propagate from the back of
                // pipeline through each handler.
                override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
                  val _ = ctx.close()
                }
              })
          )

          _ <- IO(Then("WebSocket Listener is notified of abnormal close"))
          closeReason =
            (WebSocketCloseStatus.ABNORMAL_CLOSURE, CloseInitiator.Client)
          _ <- wsListener.closeReason.get.map(_ shouldBe closeReason)
        } yield ()
      }

      "an inbound close frame closes the channel" in runIO {
        List(
          WebSocketCloseStatus.NORMAL_CLOSURE,
          WebSocketCloseStatus.ENDPOINT_UNAVAILABLE
        ).traverse_ { closeStatus =>
          for {
            _ <- IO(Given(s"a WebSocket channel"))
            wsListener = MockWebSocketSession()
            channel <- createWebSocketChannel(webSocketController(wsListener))

            _ <- IO(When("sending a close frame"))
            _ <- channel.sendWebSocketFrame(
              new CloseWebSocketFrame(closeStatus)
            )

            _ <- IO(Then("should get back same close frame"))
            _ <- channel.readWebSocketFrame { frame: CloseWebSocketFrame =>
              frame.statusCode() shouldEqual closeStatus
                .code()
            }

            _ <- IO(And("channel should be closed"))
            _ <- IO(channel.isActive shouldBe false)

            _ <- IO(And("WebSocket Listener should be notified of close"))
            reason = (closeStatus, CloseInitiator.Client)
            _ <- wsListener.closeReason.get.map(_ shouldBe reason)
          } yield ()
        }
      }
    }

    "setting read timeout notifies WebSocket Listener after elapsed time" in runIO {
      for {
        _ <- IO(Given("a WebSocket channel"))
        wsListener = MockWebSocketSession()
        channel <- createWebSocketChannel(
          webSocketController(wsListener)
        )
        ws <- wsListener.webSocket.get

        _ <- IO(When("updating the read timeout"))
        _ <- ws.updateReadTimeout(200.milliseconds)

        _ <- IO(And("waiting less then the timeout"))
        _ <- channel.channel.runPendingTasksUntil(false, timeout = 100.millis)

        _ <- IO(Then("WebSocket Listener should NOT be notified"))
        _ <- wsListener.pipelineEvents.tryGet.map(_ shouldBe None)

        _ <- IO(When("waiting pass the read timeout period"))
        _ <- channel.channel.runPendingTasksUntil(
          false,
          timeout = 110.millis
        ) // for a total of 210 ms

        _ <- IO(Then("WebSocket Listener should be notified"))
        _ <- wsListener.pipelineEvents.tryGet.map(
          _.value.dequeue() shouldBe IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT
        )

        _ <- IO(When("updating the read timeout again"))
        _ <- ws.updateReadTimeout(100.milliseconds)

        _ <- IO(And("waiting the new timeout"))
        _ <- channel.channel.runPendingTasksUntil(false, timeout = 110.millis)

        _ <- IO(Then("WebSocket Listener should be notified"))
        _ <- wsListener.pipelineEvents.tryGet.map(
          _.value.dequeue() shouldBe IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT
        )

        _ <- channel.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "channel events notify WebSocket Listener" in runIO {
      class TestEvent
      for {
        _ <- IO(Given(s"a WebSocket channel"))
        wsListener = MockWebSocketSession()
        channel <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(When("custom event is sent down the channel"))
        _ = channel.underlying.pipeline().fireUserEventTriggered(new TestEvent)

        _ <- IO(Then("WebSocket Listener should be notified"))
        _ <- wsListener.pipelineEvents.get
          .map(_.dequeue() shouldBe a[TestEvent])
          .timeout(0.5.seconds)

        _ <- channel.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "channel events with WebSocket Listener error" in runIO {
      class TestEvent
      for {
        _ <- IO(Given(s"a WebSocket channel that errors on handling pipeline events"))

        wsListener = new MockWebSocketSession {
          override def handlePipelineEvent(evt: AnyRef): IO[Unit] =
            IO.raiseError[Unit](
              new Exception("unit test exception")
            )
        }
        conn <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(When("custom event is sent down the channel"))
        _ <- IO(conn.underlying.pipeline().fireUserEventTriggered(new TestEvent))
        // Send frame after event and wait for frame to ensure event was processed. Otherwise, this thread will
        // complete before Netty's thread.
        _ <- conn.sendWebSocketFrame(new TextWebSocketFrame())

        _ <- IO(Then("channel is still open"))
        _ <- conn.channel.processTasksUntil(wsListener.hasFrames)
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(And("WebSocket Listener is NOT notified of error"))
        _ <- wsListener.exception.tryGet.map(_ shouldBe None)
      } yield ()
    }

    "channel exceptions notify WebSocket Listener" in runIO {
      for {
        _ <- IO(Given(s"a WebSocket channel"))
        wsListener = MockWebSocketSession()
        channel <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(When("exceptions is sent down the channel"))
        error = new Throwable("catch me")
        _ = channel.underlying.pipeline().fireExceptionCaught(error)

        _ <- IO(Then("WebSocket Listener should be notified"))
        _ <- wsListener.exception.get.map(_ shouldBe error).timeout(0.5.seconds)

        _ <- channel.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }

    "channel exceptions with WebSocket Listener error" in runIO {
      for {
        _ <- IO(Given(s"a WebSocket channel that errors on handling exceptions"))
        wsListener = new MockWebSocketSession {
          override def handleException(cause: Throwable): IO[Unit] =
            IO.raiseError[Unit](
              new Exception("unit test exception")
            )
        }
        channel <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(When("exceptions is sent down the channel"))
        _ = channel.underlying.pipeline().fireExceptionCaught(new Throwable("catch me"))

        _ <- IO(Then("channel is still open"))
        _ <- IO(channel.isActive shouldBe true)
      } yield ()
    }

    "closing channel with WebSocket Listener error" in runIO {
      for {
        _ <- IO(Given(s"a WebSocket channel that errors on handling closed notifications"))
        wsListener = new MockWebSocketSession {
          override def handleClosed(
            status: WebSocketCloseStatus,
            closeInitiator: CloseInitiator
          ): IO[Unit] = IO.raiseError[Unit](new Exception("unit test exception"))
        }
        channel <- createWebSocketChannel(webSocketController(wsListener))

        _ <- IO(When("channel is closed"))
        _ <- channel.sendWebSocketFrame(
          new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE)
        )

        _ <- IO(Then("Close Frame should still be sent"))
        _ <- channel.readWebSocketFrame { frame: CloseWebSocketFrame =>
          frame.statusCode() shouldEqual WebSocketCloseStatus.NORMAL_CLOSURE
            .code()
        }

        _ <- IO(And("WebSocket Listener should NOT be notified"))
        _ <- wsListener.exception.tryGet.map(_ shouldBe None)

        _ <- channel.finishAndReleaseAll.map(_ shouldBe false)
      } yield ()
    }
  }

  override protected def afterAll(): Unit = {
    supervisorClose.unsafeRunSync()
    dispClose.unsafeRunSync()
  }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.Var",
    "DisableSyntax.var",
    "org.wartremover.warts.Null",
    "DisableSyntax.null"
  )
)
object HttpServerChannelInitializerWebSocketSpec {

  private lazy val (generalDispatcher, dispClose) =
    Dispatcher.parallel[IO].allocated.unsafeRunSync()

  private lazy val (generalSupervisor, supervisorClose) =
    Supervisor[IO](await = true).allocated.unsafeRunSync()

  private val WebSocketKey: String = "x3JJHMbDL1EzLkh9GBhXDw=="
  private val WebSocketAcceptKey: String = "HSmrc0sMlYUkAGmm5OPpG2HaGWk="

  def webSocketRequest(
    subprotocol: Option[String],
    extensions: List[String] = Nil
  ): DefaultFullHttpRequest = {
    val request = new DefaultFullHttpRequest(HTTP_1_1, GET, "/")
    request
      .headers()
      .set(HttpHeaderNames.UPGRADE, "websocket")
      .set(HttpHeaderNames.CONNECTION, "Upgrade")
      .set(HttpHeaderNames.SEC_WEBSOCKET_KEY, WebSocketKey)
      .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")

    subprotocol.foreach(sp => request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, sp))
    extensions match {
      case Nil =>
        ()
      case exts =>
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, exts.mkString(", "))
    }

    request
  }

  private val UnitTestException = new Exception("unit test exception") with NoStackTrace
  private def ErrorWebSocketController =
    new MockWebSocketController(subProtocols = Nil, EmptyHttpHeaders.INSTANCE, extensions = Nil) {
      override protected def validateWebSocketRequest(
        webSocketRequest: WebSocketController.WebSocketRequest
      ): EitherT[IO, WebSocketController.Error, WebSocketController.Upgrade[IO]] =
        EitherT.leftT[IO, WebSocketController.Upgrade[IO]](
          WebSocketController.Error.Internal(EmptyHttpHeaders.INSTANCE)
        )

      override protected def reportBadRequest(
        uri: String,
        httpHeaders: HttpHeaders,
        status: HttpResponseStatus
      ): IO[HttpHeaders] = IO.raiseError(UnitTestException)
    }

  private def ErrorWebSocketListener = new MockWebSocketSession {

    override def failedToEstablish(
      cause: Throwable,
      httpHeaders: HttpHeaders,
      status: HttpResponseStatus
    ): IO[Unit] =
      IO.raiseError[Unit](UnitTestException)

    override def connected(
      subProtocol: Option[String],
      webSocket: WebSocket[IO],
      pipelineEventScheduler: PipelineEventScheduler[IO]
    )(implicit ctx: ChannelHandlerContext): IO[Unit] =
      IO.raiseError[Unit](UnitTestException)
  }

  private def createWebSocketChannel(
    wsController: HttpController[IO]
  ) =
    for {
      conn <- HttpClientConnection(controllers = List(wsController))(
        generalDispatcher,
        generalSupervisor
      )
      _ <- conn.sendRequest(webSocketRequest(subprotocol = None))
      // Read and release response
      _ <- conn.readResponse(_ => ())

      _ <- conn.channel.waitForReadsToProcess
    } yield conn

  private def webSocketController(
    wsSession: WebSocketSession[IO],
    maxFramePayloadLength: Int = 100,
    allowExtensions: Boolean = false,
    subProtocols: NonEmptyList[String] = NonEmptyList.one("sp"),
    extraHeaders: HttpHeaders = EmptyHttpHeaders.INSTANCE,
    closeTimeout: FiniteDuration = 10.seconds,
    extensions: List[WebSocketServerExtensionHandshaker] = Nil
  ): MockWebSocketController =
    new MockWebSocketController(subProtocols.toList, extraHeaders, extensions) {
      override protected def validateWebSocketRequest(
        webSocketRequest: WebSocketController.WebSocketRequest
      ): EitherT[IO, WebSocketController.Error, WebSocketController.Upgrade[IO]] =
        EitherT.rightT[IO, WebSocketController.Error](
          WebSocketController
            .Upgrade[IO](
              WebSocketConfig(
                maxFramePayloadLength,
                allowExtensions,
                subProtocols,
                closeTimeout
              ),
              wsSession,
              extraHeaders
            )
        )
    }

  private def addClientSimulatedBackPressureHandler(
    channel: EmbeddedChannelF[IO]
  ) =
    IO(
      channel.underlying
        .pipeline()
        .addBefore(
          "WebSocketHandler",
          "blocker",
          new ChannelOutboundHandlerAdapter {

            override def write(
              ctx: ChannelHandlerContext,
              msg: Any,
              promise: ChannelPromise
            ): Unit = {
              val _ = ReferenceCountUtil.release(msg)
            }
          }
        )
    )

  private abstract class MockWebSocketController(
    subProtocols: List[String],
    extraHeaders: HttpHeaders,
    extensions: List[WebSocketServerExtensionHandshaker]
  ) extends WebSocketController[IO](paths = Set("/"), subProtocols, extensions) {
    private var badReqs = 0

    override protected def reportBadRequest(
      uri: String,
      httpHeaders: HttpHeaders,
      status: HttpResponseStatus
    ): IO[HttpHeaders] = IO { badReqs += 1 }.as(extraHeaders)

    def getBadRequests: Int = badReqs
  }

  private class MockWebSocketServerExtensionHandshaker(name: String, rsv: Int)
      extends WebSocketServerExtensionHandshaker {
    private val _rsv = rsv

    override def handshakeExtension(
      extensionData: WebSocketExtensionData
    ): WebSocketServerExtension = {
      Option
        .when(extensionData.name() == name)(new WebSocketServerExtension {
          override def newReponseData(): WebSocketExtensionData =
            new WebSocketExtensionData(name, new util.HashMap[String, String]())

          override def rsv(): Int = _rsv

          override def newExtensionEncoder(): WebSocketExtensionEncoder =
            new TestWebSocketExtensionEncoder

          override def newExtensionDecoder(): WebSocketExtensionDecoder =
            new TestWebSocketExtensionDecoder
        })
        .orNull
    }
  }

  private class TestWebSocketExtensionEncoder extends WebSocketExtensionEncoder {
    override def encode(
      ctx: ChannelHandlerContext,
      msg: WebSocketFrame,
      out: util.List[AnyRef]
    ): Unit = ValueDiscard[Boolean](out.add(msg))
  }

  private class TestWebSocketExtensionDecoder extends WebSocketExtensionDecoder {
    override def decode(
      ctx: ChannelHandlerContext,
      msg: WebSocketFrame,
      out: util.List[AnyRef]
    ): Unit = ValueDiscard[Boolean](out.add(msg))
  }
}
