package cats.netty
package channel

import cats.Applicative
import io.netty.channel.ChannelHandlerContext

abstract class ChannelHandlerAdapterF[F[_]: Applicative, I] extends ChannelHandlerF[F, I] {

  override def channelReadF(msg: I)(implicit ctx: ChannelHandlerContext): F[Unit] =
    Applicative[F].unit

  override def userEventTriggeredF(evt: AnyRef)(implicit ctx: ChannelHandlerContext): F[Unit] =
    Applicative[F].unit

  override def exceptionCaughtF(cause: Throwable)(implicit ctx: ChannelHandlerContext): F[Unit] =
    Applicative[F].unit

  override def channelWritabilityChangedF(isWriteable: Boolean)(implicit
    ctx: ChannelHandlerContext
  ): F[Unit] = Applicative[F].unit

  override def channelInactiveF(implicit ctx: ChannelHandlerContext): F[Unit] = Applicative[F].unit
}
