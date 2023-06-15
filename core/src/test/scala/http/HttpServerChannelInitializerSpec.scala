package cats.netty
package http
import java.net.URI

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.std.{Dispatcher, Supervisor}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.prop.Tables.Table

import cats.netty.ResponseMatchers._
import cats.netty.Utils.ValueDiscard
import cats.netty.http.HttpServerChannelInitializerSpec._

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.DefaultArguments"
  )
)
class HttpServerChannelInitializerSpec extends BaseSpec with BeforeAndAfterAll {

  implicit val (generalDispatcher: Dispatcher[IO], dispClose: IO[Unit]) =
    Dispatcher.parallel[IO].allocated.unsafeRunSync()
  implicit val (generalSupervisor: Supervisor[IO], supervisorClose: IO[Unit]) =
    Supervisor[IO](await = true).allocated.unsafeRunSync()

  "server can accept HTTP requests over a tcp connection" - {
    "returns a default of 404 if no controllers exist" in runIO {
      for {
        _ <- IO(Given(s"a connection to a server with no controllers"))
        conn <- HttpClientConnection(controllers = Nil)

        _ <- IO(When("a request is sent"))
        request = makeSimpleGet(ClientKeepAliveSetting.KeepAliveUnset)
        _ <- conn.sendRequest(request)

        _ <- IO(Then("response is 404"))
        _ <- assert404(conn)

        _ <- IO(And("request is release"))
        _ <- IO(request.refCnt() shouldBe 0)
        _ <- conn.finishAndReleaseAll
      } yield ()
    }

    "returns 404 if no controller can accept an http request" in runIO {
      class TestController extends HttpController[IO] {
        override val routingLogic: (URI, HttpRequest) => Boolean = (_, _) => false

        override def handle(httpRequest: FullHttpRequest): IO[HttpController.Result[IO]] =
          HttpController.Response[IO](createSimpleOkResponse(body = None)).pure[IO]
      }
      for {
        _ <- IO(Given(s"a connection to a server with no controller that handles request"))
        conn <- HttpClientConnection(controllers = List.fill(5)(new TestController))

        _ <- IO(When("a request is sent"))
        request = makeSimpleGet(ClientKeepAliveSetting.KeepAliveUnset)
        _ <- conn.sendRequest(request)

        _ <- IO(Then("response is 404"))
        _ <- assert404(conn)

        _ <- IO(And("request is release"))
        _ <- IO(request.refCnt() shouldBe 0)
      } yield ()
    }

    "routes requests to first accepting controller and writes its response back to client" in runIO {
      for {
        _ <- IO(Given(s"a connection to a server with a sync and async controller"))
        syncController = new HttpController[IO] {
          override val routingLogic: (URI, HttpRequest) => Boolean =
            (_, httpRequest) => httpRequest.uri() == "/one"

          override def handle(httpRequest: FullHttpRequest): IO[HttpController.Result[IO]] =
            HttpController
              .Response[IO](createSimpleOkResponse(body = "one".some))
              .pure[IO]
        }
        asyncController =
          new AsyncTestController(uri = "/two", responseBody = "two".some)
        conn <- HttpClientConnection(controllers = List(syncController, asyncController))

        _ <- IO(When("a request is sent targeting sync controller"))
        req1 = makeSimpleGet(ClientKeepAliveSetting.KeepAliveUnset)
        _ <- IO(req1.setUri("/one"))
        _ <- conn.sendRequest(req1)

        _ <- IO(Then("the controller's response is sent back"))
        _ <- assert200(conn, "one".some)

        _ <- IO(And("request is release"))
        _ <- IO(req1.refCnt() shouldBe 0)

        _ <- IO(When("a request is sent targeting async controller"))
        req2 = makeSimpleGet(ClientKeepAliveSetting.KeepAliveUnset)
        _ <- IO(req2.setUri("/two"))
        _ <- conn.sendRequest(req2)

        _ <- IO(Then("request is received, but response has not been sent yet"))
        _ <- IO(asyncController.waitForRequest())
        _ <- IO(req2.refCnt() shouldBe 0)
        _ <- IO(conn.underlying.outboundMessages().isEmpty shouldBe true)

        _ <- IO(When("the controller's response is sent back"))
        _ <- IO(asyncController.completeSuccessfully())

        _ <- IO(Then("response should be received"))
        _ <- assert200(conn, "two".some)
      } yield ()
    }

    "returns 500 for controller errors" in runIO {
      class TestController extends HttpController[IO] {
        override val routingLogic: (URI, HttpRequest) => Boolean = (_, _) => true

        override def handle(httpRequest: FullHttpRequest): IO[HttpController.Result[IO]] =
          IO.raiseError(new Throwable("test error"))
      }
      for {
        _ <- IO(Given(s"a connection to a server with no controller that handles request"))
        conn <- HttpClientConnection(controllers = List(new TestController))

        _ <- IO(When("a request is sent"))
        request = makeSimpleGet(ClientKeepAliveSetting.KeepAliveUnset)
        _ <- conn.sendRequest(request)

        _ <- IO(Then("response is 500"))
        _ <- IO(assert500(conn))

        _ <- IO(And("request is release"))
        _ <- IO(request.refCnt() shouldBe 0)

        _ <- IO(And("connection remains open"))
        _ <- IO(conn.isActive shouldBe true)
        _ <- conn.finishAndReleaseAll
      } yield ()
    }

    "Connection header is set for responses and connection remains open or closed" in {
      // format: off
      val table = Table(
        ("RequestKeepAliveSetting",                 "ServerKeepAliveSetting",                 "IsResponseKeepAlive",   "IsConnectionOpen"),
        (ClientKeepAliveSetting.CloseConnection,    ServerKeepAliveSetting.KeepAliveSet,       false,                  false),
        (ClientKeepAliveSetting.KeepAliveUnset,     ServerKeepAliveSetting.KeepAliveSet,       true,                   true),
        (ClientKeepAliveSetting.KeepAliveSet,       ServerKeepAliveSetting.KeepAliveSet,       true,                   true),

        (ClientKeepAliveSetting.CloseConnection,    ServerKeepAliveSetting.CloseConnection,    false,                  false),
        (ClientKeepAliveSetting.KeepAliveUnset,     ServerKeepAliveSetting.CloseConnection,    false,                  false),
        (ClientKeepAliveSetting.KeepAliveSet,       ServerKeepAliveSetting.CloseConnection,    false,                  false),

        (ClientKeepAliveSetting.CloseConnection,    ServerKeepAliveSetting.KeepAliveUnset,     false,                  false),
        (ClientKeepAliveSetting.KeepAliveUnset,     ServerKeepAliveSetting.KeepAliveUnset,     true,                   true),
        (ClientKeepAliveSetting.KeepAliveSet,       ServerKeepAliveSetting.KeepAliveUnset,     true,                   true)
      )
      // format: on
      TableDrivenPropertyChecks.forAll(table) {
        (
          clientKeepAliveSetting,
          serverKeepAliveSetting,
          isResponseKeepAlive,
          isConnectionOpen
        ) =>
          (for {
            _ <- IO(Given(s"a request with ${clientKeepAliveSetting.toString}"))
            request = makeSimpleGet(clientKeepAliveSetting)

            _ <- IO(And(s"a server connection with ${serverKeepAliveSetting.toString}"))
            conn <- HttpClientConnection(
              controllers = `200Controller`(serverKeepAliveSetting),
              requestTimeoutPeriod = 1.minute,
              maxHttpContentLength = 1,
              maxInitialLineLength = 100,
              maxHeaderSize = 100
            )

            _ <- IO(When("request is sent"))
            _ <- conn.sendRequest(request)

            _ <- IO(Then(s"response is received with ${isResponseKeepAlive.toString}"))
            _ <- assertSimple200(conn, isResponseKeepAlive)

            _ <- IO(And("request is release"))
            _ <- IO(request.refCnt() shouldBe 0)

            _ <- IO(And(s"connection is ${if (isConnectionOpen) "open" else "closed"}"))
            _ <- IO(conn.isActive shouldBe isConnectionOpen)
          } yield ()).unsafeRunSync()
      }
    }

    "client connections with `Connection: keep-alive` header in requests can reuse connection until `Connection: close`" in runIO {
      for {
        _ <- IO(Given("a multiple request with `Connection: keep-alive`"))
        requests =
          List.fill(3)(makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet))

        _ <- IO(And(s"a server connection with keep-alive"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveSet),
          requestTimeoutPeriod = 1.minute,
          maxHttpContentLength = 1,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- requests.traverse_ { request =>
          for {
            _ <- IO(When("request is sent"))
            _ <- conn.sendRequest(request)

            _ <- IO(Then("response is received"))
            _ <- assertSimple200(conn, keepAlive = true)

            _ <- IO(And("request is release"))
            _ <- IO(request.refCnt() shouldBe 0)
          } yield ()
        }

        _ <- IO(And("connection is open"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(When("request with `Connection: close` is sent"))
        request = makeSimpleGet(ClientKeepAliveSetting.CloseConnection)
        _ <- conn.sendRequest(request)

        _ <- IO(Then("response is received"))
        _ <- assertSimple200(conn, keepAlive = false)

        _ <- IO(And("request is release"))
        _ <- IO(request.refCnt() shouldBe 0)

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }
  }

  "persistent connections are closed after no activity" - {
    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/408
    "a newly inactive open connection is closed after timeout" in runIO {
      for {
        _ <- IO(Given("a connection"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveUnset),
          requestTimeoutPeriod = 200.millis,
          maxHttpContentLength = 1,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- IO(When("waiting the timeout period"))
        _ <- IO.sleep(200.millis)

        _ <- IO(Then("server should send 408"))
        _ <- assert408(conn)

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }

    "a connection remains active for bursts of HTTP activity, but closes after timeout" in runIO {
      for {
        _ <- IO(Given("a connection"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveUnset),
          requestTimeoutPeriod = 200.millis,
          maxHttpContentLength = 1,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- IO(When("there's a bursts of http activity"))
        _ <- List(50.millis, 150.millis, 190.millis, 30.millis).traverse_ { timeout =>
          IO.sleep(timeout) *>
            conn.sendRequest(makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)) *>
            conn.readResponse(_ => ()) *>
            conn.statues.take.void // drop
        }

        _ <- IO(Then("connection is still open"))
        _ <- IO(conn.isActive shouldBe true)

        _ <- IO(When("waiting the timeout period"))
        _ <- IO.sleep(200.millis)

        _ <- IO(Then("server should send 408"))
        _ <- assert408(conn)

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }

    "a connection with an HTTP message in flight, but doesn't receive last http part in time, is closed" in runIO {
      for {
        _ <- IO(Given("a connection"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveUnset),
          requestTimeoutPeriod = 200.millis,
          maxHttpContentLength = 1000,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- IO(When("sending only part of Http request"))
        request = new DefaultHttpRequest(
          HttpVersion.HTTP_1_1,
          HttpMethod.GET,
          "/test-uri"
        )
        // make server think more http object/bytes on the way
        _ <- IO(
          HttpUtil.setContentLength(
            request,
            100
          )
        )
        _ <- conn.sendRequest(request)

        _ <- IO(And("waiting the timeout period"))
        _ <- IO.sleep(200.millis)

        _ <- IO(Then("server should send 408"))
        _ <- assert408(conn)

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }

    "connection is never closed if response takes too long to write" in {
      /*
      This isn't really possible to test, but calling out here that such functionality can technically exist.
      Netty provides this possible via IdleTimeoutHandler, but this isn't occurring in practice. It can also lead to
      confusion for application level code since devs will need to mange multiple timeouts. Suppose app code is writing
      to database, if response_timeout < data_response_timeout, then server will eagerly return 5xx despite client
      and app willing to wait longer
       */
    }
  }

  "HTTP streaming" - {
    "HTTP pipelining is blocked" in runIO {
      // https://doc.akka.io/docs/akka-http/current/client-side/configuration.html doesn't support it
      // Per https://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html
      // Pipelining should not be supported for non-idempotent requests, which is what we care about
      // If we really need to support server side than can base impl off of https://github.com/spinscale/netty4-http-pipelining
      for {
        _ <- IO(Given("a connection with an async controller"))
        asyncController =
          new AsyncTestController(uri = "/test-uri", responseBody = None)
        conn <- HttpClientConnection(controllers = List(asyncController))
        _ <- IO(conn.underlying.config().isAutoRead shouldBe true)

        _ <- IO(When("multiple requests are sent without waiting for responses"))
        req1 = makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)
        req2 = makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)
        req3 = makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)
        _ <- conn.sendRequest(req1, req2, req3)
        _ <- IO(asyncController.waitForRequest())

        _ <- IO(And("then controller sends response"))
        _ <- IO(asyncController.completeSuccessfully())

        _ <- IO(Then("client receives response for 1st request"))
        _ <- assertSimple200(conn, keepAlive = true, assertStatusRecorded = false)
        _ <- IO(req1.refCnt() shouldBe 0)

        _ <- IO(And("autoread is turned off"))
        _ <- IO(conn.underlying.config().isAutoRead shouldBe false)

        _ <- IO(And("2nd response it 429"))
        _ <- conn.readResponse { resp: FullHttpResponse =>
          resp.status() shouldBe HttpResponseStatus.TOO_MANY_REQUESTS
          resp should haveEmptyContent
          HttpUtil.isKeepAlive(resp) shouldBe false
          resp.headers().size() shouldBe 2
        }
        _ <- IO(req2.refCnt() shouldBe 0)

        _ <- IO(And("3rd request is ignored"))
        _ <- IO(conn.underlying.outboundMessages().isEmpty shouldBe true)
        _ <- IO(req3.refCnt() shouldBe 0)

        _ <- IO(And("statuses are recorded"))
        s1 <- conn.statues.take.timeout(200.millis)
        s2 <- conn.statues.take.timeout(100.millis)
        statuses = List(s1, s2).sortBy(_.code())
        // recording takes place async
        _ <- IO(statuses shouldBe List(HttpResponseStatus.OK, HttpResponseStatus.TOO_MANY_REQUESTS))

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }

    "HTTP 2.0 is not implemented" in {
      // TODO: send a HTTP2 request or HTTP1.1 upgrade request
    }
  }

  "malformed HTTP requests" - {
    "malformed initial HTTP request, bytes that do not conform to HTTP spec at all, are dropped by Netty" in runIO {
      for {
        _ <- IO(Given("a connection"))
        channel <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveSet),
          requestTimeoutPeriod = 1.minute,
          maxHttpContentLength = 1,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- IO(When("malformed HTTP request is sent"))
        _ <- IO(
          channel.underlying.writeInbound(
            Unpooled.wrappedBuffer("malformed HTTP request".getBytes())
          )
        )

        _ <- IO(Then("bytes are discarded by Netty"))
        _ <- IO(channel.underlying.outboundMessages().isEmpty shouldBe true)
        // no exceptions thrown either
        _ <- IO(channel.underlying.checkException())
        /*
      The request decoder doesn't report any errors when the first line of HTTP request doesn't conform to HTTP spec.
      Then the ByteToMessageDecoder drops the bytes. For all intents and purposes, this shouldn't present a problem
      in prod. Most traffic is behind ALB's and clients connecting directly to ports are well behaved.

      For greater assurance, we'll need to make a PR in Netty to generate some kind of error to notify downstream
      handlers in pipeline.
         */

        _ <- IO(And("channel is open"))
        _ <- IO(channel.isActive shouldBe true)
      } yield ()
    }

    "content-length is too big" in runIO {
      for {
        _ <- IO(Given("a connection"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveUnset),
          requestTimeoutPeriod = 200.millis,
          maxHttpContentLength = 1,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- IO(When("sending large Http request"))
        request = new DefaultFullHttpRequest(
          HttpVersion.HTTP_1_1,
          HttpMethod.GET,
          "/test-uri",
          Unpooled.wrappedBuffer(("a" * 100).getBytes)
        )
        _ <- IO(
          HttpUtil.setContentLength(
            request,
            100
          )
        )
        _ <- conn.sendRequest(request)

        _ <- IO(Then("413 is sent"))
        _ <- conn.readResponse { resp: FullHttpResponse =>
          resp.status() shouldBe HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE
          resp should haveEmptyContent
          HttpUtil.isKeepAlive(resp) shouldBe true
          resp.headers().size() shouldBe 1
        }
        // TODO: Consider an extra handler for reporting these, not urgent
        // _ <- assertStatusRecords(conn, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE)

        _ <- IO(And("request is release"))
        _ <- IO((request.refCnt() shouldBe 0))

        _ <- IO(And("connection is open"))
        _ <- IO(conn.isActive shouldBe true)
      } yield ()
    }

    "header-size is too big" in runIO {
      for {
        _ <- IO(Given("a connection"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveUnset),
          requestTimeoutPeriod = 200.millis,
          maxHttpContentLength = 10,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- IO(When("sending large Http request"))
        request = makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)
        _ <- IO(
          request
            .headers()
            .add(HttpHeaderNames.COOKIE, "a" * 101)
        )

        _ <- conn.sendRequest(request)

        _ <- IO(Then("431 is sent"))
        _ <- conn.readResponse { resp: FullHttpResponse =>
          resp.status() shouldBe HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE
          resp should haveEmptyContent
          HttpUtil.isKeepAlive(resp) shouldBe false
          resp.headers().size() shouldBe 2
        }
        _ <- assertStatusRecords(conn, HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE)

        _ <- IO(And("request is release"))
        _ <- IO(request.refCnt() shouldBe 0)

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }

    "1st request with http initial line length is too big closes connection" in runIO {
      for {
        _ <- IO(Given("a connection"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveUnset),
          requestTimeoutPeriod = 200.millis,
          maxHttpContentLength = 10,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )

        _ <- IO(When("sending large Http request"))
        request = makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)
          .setUri("/" + "a" * 100)

        _ <- conn.sendRequest(request)

        _ <- IO(Then("414 is sent"))
        _ <- conn.readResponse { resp: FullHttpResponse =>
          resp.status() shouldBe HttpResponseStatus.REQUEST_URI_TOO_LONG
          resp should haveEmptyContent
          /*
        Server could not check Connection header since request headers were not decoded. Default behavior is to assume
        client wants to close connection since this was never negotiated between client and server.
           */
          HttpUtil.isKeepAlive(resp) shouldBe false
          resp.headers().size() shouldBe 2
        }
        _ <- assertStatusRecords(conn, HttpResponseStatus.REQUEST_URI_TOO_LONG)

        _ <- IO(And("request is release"))
        _ <- IO(request.refCnt() shouldBe 0)

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }

    "any request with http initial line length is too big after 1st request closes connection" in runIO {
      for {
        _ <- IO(Given("a connection"))
        conn <- HttpClientConnection(
          controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveUnset),
          requestTimeoutPeriod = 200.millis,
          maxHttpContentLength = 10,
          maxInitialLineLength = 100,
          maxHeaderSize = 100
        )
        _ <- conn.sendRequest(
          makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)
        )
        _ <- conn.readResponse(_ => ())
        _ <- conn.statues.take // drop

        _ <- IO(When("sending large Http request"))
        request = makeSimpleGet(ClientKeepAliveSetting.KeepAliveSet)
          .setUri("/" + "a" * 100)

        _ <- conn.sendRequest(request)

        _ <- IO(Then("414 is sent"))
        _ <- conn.readResponse { resp: FullHttpResponse =>
          resp.status() shouldBe HttpResponseStatus.REQUEST_URI_TOO_LONG
          resp should haveEmptyContent
          /*
        Server could not check Connection header since request headers were not decoded. Default behavior is to assume
        client wants to close connection since this was never negotiated between client and server.
           */
          HttpUtil.isKeepAlive(resp) shouldBe false
          resp.headers().size() shouldBe 2
        }
        _ <- assertStatusRecords(conn, HttpResponseStatus.REQUEST_URI_TOO_LONG)

        _ <- IO(And("request is release"))
        _ <- IO(request.refCnt() shouldBe 0)

        _ <- IO(And("connection is closed"))
        _ <- IO(conn.isActive shouldBe false)
      } yield ()
    }
  }

  "connection can handle generic exceptions in pipeline" in runIO {
    for {
      _ <- IO(Given("a connection"))
      conn <- HttpClientConnection(
        controllers = `200Controller`(ServerKeepAliveSetting.KeepAliveSet),
        requestTimeoutPeriod = 1.minute,
        maxHttpContentLength = 1,
        maxInitialLineLength = 100,
        maxHeaderSize = 100
      )

      _ <- IO(When("exception is picked up by handler"))
      _ <- IO(
        conn.underlying
          .pipeline()
          .fireExceptionCaught(new Exception("unit test exception"))
      )

      _ <- IO(Then("a 500 response is returned"))
      _ <- conn.readResponse { resp: FullHttpResponse =>
        resp.status() shouldBe HttpResponseStatus.INTERNAL_SERVER_ERROR
      }
      _ <- assertStatusRecords(conn, HttpResponseStatus.INTERNAL_SERVER_ERROR)

      _ <- IO(And("channel is closed"))
      _ <- IO(conn.isActive shouldBe false)

      _ <- IO(And("no messages should be left in channel"))
      _ <- conn.finishAndReleaseAll
    } yield ()
  }

  "tcp backpressure" ignore {
    /*
    Not handled because we don't expect this being an issue for E@E since payloads are small.

    However, this scenario is possible if a response entity is very large, the server starts streaming it back,
    client continues to receive response, but it sends a new request concurrently.
    If we ever need to deal with this then upon high water mark breach, we queue/hold the next request, wait until
    low water mark is crossed, then forward the request to controller for processing.
    This will ensure a natural nad ordered flow back to client. Not that a third request should be naturally queue up
    because the client (a good client that doesn't do HTTP pipelining) won't send until 2nd response starts streaming.
    But that response is queued up behind the 1st.
     */
  }

  private def assertStatusRecords(conn: HttpClientConnection, status: HttpResponseStatus) =
    conn.statues.take.flatMap(s => IO(s shouldBe status)).timeout(200.millis)

  private def assertSimple200(
    conn: HttpClientConnection,
    keepAlive: Boolean,
    assertStatusRecorded: Boolean = true
  ) = conn.readResponse { resp: FullHttpResponse =>
    resp.protocolVersion() shouldBe HttpVersion.HTTP_1_1
    resp.status() shouldBe HttpResponseStatus.OK
    resp should haveEmptyContent
    HttpUtil.isKeepAlive(resp) shouldBe keepAlive
    // HTTP 1.1 doesn't need to set the Connection: Keep-Alive header on response if client requested keep-alive.
    // So Netty doesn't set it. It will even remove Connection header.
    if (keepAlive)
      resp.headers().size() shouldBe 1
    else
      resp.headers().size() shouldBe 2
  } *> IO.whenA(assertStatusRecorded)(assertStatusRecords(conn, HttpResponseStatus.OK).void)

  private def assert408(conn: HttpClientConnection) =
    conn.readResponse { resp: FullHttpResponse =>
      resp.status() shouldBe HttpResponseStatus.REQUEST_TIMEOUT
      resp should haveEmptyContent
      HttpUtil.isKeepAlive(resp) shouldBe false
      resp.headers().size() shouldBe 2
    } *> assertStatusRecords(conn, HttpResponseStatus.REQUEST_TIMEOUT)

  private def assert404(conn: HttpClientConnection) =
    conn.readResponse { resp: FullHttpResponse =>
      resp.status() shouldBe HttpResponseStatus.NOT_FOUND
      resp should haveEmptyContent
      HttpUtil.isKeepAlive(resp) shouldBe true
      resp.headers().size() shouldBe 1
    } *> assertStatusRecords(conn, HttpResponseStatus.NOT_FOUND)

  private def assert500(conn: HttpClientConnection) =
    conn.readResponse { resp =>
      resp.status() shouldBe HttpResponseStatus.INTERNAL_SERVER_ERROR
      HttpUtil.isKeepAlive(resp) shouldBe true
      resp.headers().size() shouldBe 0
    } *> assertStatusRecords(conn, HttpResponseStatus.INTERNAL_SERVER_ERROR)

  private def assert200(conn: HttpClientConnection, body: Option[String]) =
    conn.readResponse { resp: FullHttpResponse =>
      resp.status() shouldBe HttpResponseStatus.OK
      resp should body.fold(haveEmptyContent)(haveContent)
      HttpUtil.isKeepAlive(resp) shouldBe true
      resp.headers().size() shouldBe 1
    } *> assertStatusRecords(conn, HttpResponseStatus.OK)

  private def `200Controller`(setting: ServerKeepAliveSetting) = {
    List(new HttpController[IO] {

      override val routingLogic: (URI, HttpRequest) => Boolean = (_, _) => true

      override def handle(httpRequest: FullHttpRequest): IO[HttpController.Result[IO]] = {
        val resp = createSimpleOkResponse().touch("from test")

        val res = IO.pure(HttpController.Response[IO](resp))

        setting match {
          case ServerKeepAliveSetting.KeepAliveSet =>
            IO(HttpUtil.setKeepAlive(resp, true)) *>
              res
          case ServerKeepAliveSetting.CloseConnection =>
            IO(HttpUtil.setKeepAlive(resp, false)) *>
              res
          case ServerKeepAliveSetting.KeepAliveUnset =>
            res
        }
      }
    })
  }

  override protected def afterAll(): Unit = {
    supervisorClose.unsafeRunSync()
    dispClose.unsafeRunSync()
  }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments"
  )
)
object HttpServerChannelInitializerSpec {

  private def makeSimpleGet(
    setting: ClientKeepAliveSetting
  ): DefaultFullHttpRequest = {
    val request = new DefaultFullHttpRequest(
      HttpVersion.HTTP_1_1,
      HttpMethod.GET,
      "/test-uri"
    )
    setting match {
      case ClientKeepAliveSetting.KeepAliveSet =>
        HttpUtil.setKeepAlive(request, true)
      case ClientKeepAliveSetting.CloseConnection =>
        HttpUtil.setKeepAlive(request, false)
      case ClientKeepAliveSetting.KeepAliveUnset =>
        ()
    }
    request
  }

  private def createSimpleOkResponse(body: Option[String] = None) = {
    val resp = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.OK,
      body
        .map(b => Unpooled.wrappedBuffer(b.getBytes))
        .getOrElse(Unpooled.EMPTY_BUFFER)
    )
    // IMPORTANT: Set this header, otherwise Netty response decoder (used in test HttpUtil) will not know when
    // to stop aggregating. This will cause tests to fail in unexpected ways
    HttpUtil.setContentLength(resp, body.fold(0L)(_.length.toLong))
    resp
  }

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Var",
      "DisableSyntax.var",
      "DisableSyntax.while"
    )
  )
  private class AsyncTestController(uri: String, responseBody: Option[String])
      extends HttpController[IO] {

    private var callback: Option[
      Either[Throwable, HttpController.Result[IO]] => Unit
    ] = None

    override val routingLogic: (URI, HttpRequest) => Boolean = (_, httpRequest) =>
      httpRequest.uri() == uri

    override def handle(httpRequest: FullHttpRequest): IO[HttpController.Result[IO]] =
      IO.async_ { cb =>
        callback = Some(cb)
      }

    // TODO: mhhh
    def waitForRequest(): Unit = {
      var tries = 0
      while (callback.isEmpty && tries < 10) {
        Thread.sleep(10)
        tries = tries + 1
      }
    }

    def completeSuccessfully(): Unit = ValueDiscard[Option[Unit]](
      for {
        cb <- callback
      } yield cb(
        HttpController
          .Response[IO](createSimpleOkResponse(responseBody))
          .asRight[Throwable]
      )
    )
  }

  private sealed trait ClientKeepAliveSetting extends Product with Serializable

  private object ClientKeepAliveSetting {
    case object KeepAliveSet extends ClientKeepAliveSetting
    case object CloseConnection extends ClientKeepAliveSetting
    case object KeepAliveUnset extends ClientKeepAliveSetting
  }

  private sealed trait ServerKeepAliveSetting extends Product with Serializable

  private object ServerKeepAliveSetting {
    case object KeepAliveSet extends ServerKeepAliveSetting
    case object CloseConnection extends ServerKeepAliveSetting
    case object KeepAliveUnset extends ServerKeepAliveSetting
  }

}
