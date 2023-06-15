package cats.netty

import io.netty.buffer.{ByteBufHolder, ByteBufUtil}
import io.netty.handler.codec.http.{FullHttpResponse, HttpHeaderNames, HttpResponseStatus}
import io.netty.util.AsciiString
import org.scalatest.matchers.Matcher
import org.scalatest.matchers.should.Matchers.be

object ResponseMatchers {

  def haveHeaderValue(header: AsciiString, value: String): Matcher[FullHttpResponse] =
    be(value).compose(_.headers().get(header))

  def haveContentLength(n: Int): Matcher[FullHttpResponse] =
    haveHeaderValue(HttpHeaderNames.CONTENT_LENGTH, n.toString)

  def haveContentAsString(s: String): Matcher[ByteBufHolder] =
    be(s).compose { resp: ByteBufHolder =>
      new String(ByteBufUtil.getBytes(resp.content()))
    }

  def haveContent(s: String): Matcher[FullHttpResponse] =
    haveContentLength(s.length) and
      haveContentAsString(s)

  val haveEmptyContent: Matcher[FullHttpResponse] =
    haveContent("")

  def beWebSocketResponse(acceptKey: String): Matcher[FullHttpResponse] =
    be(HttpResponseStatus.SWITCHING_PROTOCOLS)
      .compose[FullHttpResponse](_.status())
      .and(haveHeaderValue(HttpHeaderNames.UPGRADE, "websocket"))
      .and(haveHeaderValue(HttpHeaderNames.CONNECTION, "upgrade"))
      .and(
        haveHeaderValue(
          HttpHeaderNames.SEC_WEBSOCKET_ACCEPT,
          acceptKey
        )
      )
      .and(haveEmptyContent)
}
