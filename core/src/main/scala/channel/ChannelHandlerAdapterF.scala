package cats.netty
package channel

import cats.Applicative
import io.netty.channel.ChannelHandlerContext

abstract class ChannelHandlerAdapterF[F[_]: Applicative, I] extends ChannelHandlerF[F, I] {

  override def channelRead(msg: I)(implicit ctx: ChannelHandlerContext): F[Unit] =
    Applicative[F].unit

  override def userEventTriggered(evt: AnyRef)(implicit ctx: ChannelHandlerContext): F[Unit] =
    Applicative[F].unit

  override def exceptionCaught(cause: Throwable)(implicit ctx: ChannelHandlerContext): F[Unit] =
    Applicative[F].unit

  override def channelWritabilityChanged(isWriteable: Boolean)(implicit
    ctx: ChannelHandlerContext
  ): F[Unit] = Applicative[F].unit

  override def channelInactive(implicit ctx: ChannelHandlerContext): F[Unit] = Applicative[F].unit
}
